# hev-socks5-tunnel: notes for the Kotlin side

These notes cover the vendored **hev-socks5-tunnel 2.18.0** (`d9dca26`) with our local patches P1–P4
(see `app/src/main/jni/PATCHES.hev.md`). A Kotlin implementer can write `HevConfig` and the VPN
service from this file alone. Each claim cites `file:function` relative to
`app/src/main/jni/hev-socks5-tunnel/`. Anything marked **verified** was run on the emulator; the
Verification section at the end lists the commands.

---

## 1. JNI contract (verified)

```kotlin
package io.github.unsalable.goodbyedpi.engine
internal object TProxy {
    init { System.loadLibrary("hev-socks5-tunnel") }
    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic external fun TProxyStopService(): Boolean
    @JvmStatic external fun TProxyIsRunning(): Boolean
    @JvmStatic external fun TProxyGetStats(): LongArray   // [txPackets, txBytes, rxPackets, rxBytes]
}
```

* **Registration.** `src/hev-jni.c:JNI_OnLoad` looks up the class with
  `FindClass("io/github/unsalable/goodbyedpi/engine/TProxy")`, then calls `RegisterNatives` with:
  * `TProxyStartService (Ljava/lang/String;I)Z`
  * `TProxyStopService ()Z`
  * `TProxyIsRunning ()Z`
  * `TProxyGetStats ()[J`

  The C functions take `(JNIEnv*, jobject thiz, ...)` and never use `thiz`, so static natives work.
  Kotlin `@JvmStatic external` in an `object` compiles to `public static final native` methods
  (checked with `javap`). The harness loaded the real compiled `TProxy.kt` and called all four
  methods successfully.
* **Class and method names must survive R8.** If R8 renames `TProxy` or its natives, `FindClass` or
  `RegisterNatives` fails, `JNI_OnLoad` returns `JNI_ERR`, and `System.loadLibrary` throws
  `UnsatisfiedLinkError`. Keep rule:
  `-keep class io.github.unsalable.goodbyedpi.engine.TProxy { static native <methods>; }`.
  The PKGNAME/CLSNAME values come from `jni/Application.mk` (`APP_CFLAGS`). Changing the Kotlin
  package or class name requires changing them there too.
* **`TProxyStartService(path, fd)`** (`src/hev-jni.c:native_start_service`, with patch P1):
  * Returns **false** in these cases:
    * a tunnel is already running;
    * `fd < 0` or the fd is not open (`fcntl F_GETFD`);
    * the file cannot be opened or parsed as YAML;
    * `socks5.port` or `socks5.address` is missing;
    * `socks5.username` is set without `password`, or the other way round;
    * `malloc` or `pthread_create` fails.

    Config parse errors are printed to stderr only, which Android discards. The Kotlin side cannot
    get a reason, so show a generic message (for example "Tünel yapılandırması geçersiz").
  * Returns **true** once the worker thread is created. A few failures can still happen
    asynchronously in the thread (`src/hev-main.c:hev_socks5_tunnel_main_inner`). Each one makes
    `TProxyIsRunning()` false within milliseconds:
    * `log-file` cannot be opened;
    * `hev_task_system_init` or `hev_socks5_tunnel_init` fails. The realistic cause is an fd on
      which `ioctl(FIONBIO)` fails.

    **Check `TProxyIsRunning()` about 200–300 ms after a true result** before reporting `Running`.
  * The config file is read twice, once for synchronous validation and once in the thread. Do not
    rewrite it between the call and roughly 50 ms after the call returns.
* **`TProxyStopService()`** (`src/hev-jni.c:native_stop_service`):
  * If the tunnel is running, calls `hev_socks5_tunnel_quit()`, then `pthread_join`s the worker.
  * Returns true when the join succeeds. It also returns true when nothing was running, and on a
    double stop (both verified).
  * Blocks until all sessions are torn down. Measured time was 0–4 ms. Call it off the main thread.
* **`TProxyIsRunning()`** is a lock-free atomic load. It turns false in three situations: after stop,
  when the worker exits on its own (async init failure or P3's read-failure self-stop), and before
  the first start.
* **`TProxyGetStats()`** (`src/hev-socks5-tunnel.c:hev_socks5_tunnel_stats`):
  * **tx** = packets and bytes read from the tun, meaning app → network. **rx** = packets and bytes
    written to the tun, meaning network → app. The counts are IP packet sizes.
  * Counters are cumulative for the current run and reset to 0 in `hev_socks5_tunnel_fini`, which
    runs on every stop (verified: all four values are 0 after stop).
  * The counters are `size_t`, so they **wrap at 2^32 on 32-bit ABIs** (armeabi-v7a, x86). When
    computing speed, treat a negative delta as a wrap: add 2^32, or drop that sample.
  * Reads are unsynchronised but word-sized, so they are cheap. Poll at most once per second, and
    only while the UI is visible.
* **One tunnel per process.** Start and stop are serialized by a pthread mutex in `hev-jni.c`.
  Start → stop → start in the same process is fully supported: 5 consecutive cycles in the harness
  each forwarded a DNS query. All state is re-initialised per run: config reset
  (`hev-config.c:hev_config_reset`), task system (`hev_task_system_init`/`_fini` per thread), lwIP
  netif and PCBs (`gateway_init`/`_fini`), loggers (P4), and the stale stop flag (P2).

## 2. Tun fd ownership, blocking and MTU

* **hev does not close an external fd** (verified). `src/hev-socks5-tunnel.c:tunnel_init` stores the
  fd and leaves `tun_fd_local = 0`. `tunnel_fini` only closes fds that hev opened itself (CLI mode).
  Kotlin owns the `ParcelFileDescriptor`:
  1. `pfd = builder.establish()`
  2. `TProxyStartService(path, pfd.fd)` — pass `getFd()`, **not** `detachFd()`
  3. …
  4. `TProxyStopService()`
  5. `pfd.close()`

  Closing exactly once, after stop, is correct. There is no double close, and no detach is needed.
* **Never close the PFD while hev is running.** hev registers the fd with epoll. If the fd is closed
  underneath it, epoll drops the registration, and hev keeps "running" but forwards nothing. There
  is no CPU burn and stop still works (verified), but traffic is black-holed. A reused fd number
  could also receive hev's writes. Always stop first.
* **Blocking mode does not matter.** `tunnel_init` forces `ioctl(fd, FIONBIO, 1)` on the external fd.
  Leave `VpnService.Builder.setBlocking()` at its default (false, non-blocking). Nothing on the Kotlin
  side may read from or write to the tun fd, because hev owns the I/O and the fd is non-blocking
  anyway.
* **The MTU must match.** With an external fd, hev only uses `tunnel.mtu` as its read-buffer size
  (`lwip_io_task_entry`, then `hev_tunnel_read(tun_fd, mtu, ...)`). Set `Builder.setMtu(8500)` and
  YAML `tunnel.mtu: 8500` to the same value. If the Builder MTU is larger than hev's mtu, big packets
  are truncated. lwIP's TCP MSS is fixed at 8191 (`third-part/lwip/src/ports/include/lwipopts.h`).
* **Ignored keys with an external fd.** `tunnel.ipv4`, `ipv6`, `name`, `multi-queue`, `post-up-script`
  and `pre-down-script` are ignored: `tunnel_init` returns before reading them. The real addresses
  come from `Builder.addAddress`. We still write ipv4/ipv6 so that the file documents itself.
* **tun EOF or hard read errors (patch P3).** A hard read error means `hev_tunnel_read` returns NULL,
  for example EOF, EBADF, or EBADFD after the tun device is torn down. Patched hev then sleeps 20 ms
  per failure. After about 1 s of consecutive failures it stops itself, and `TProxyIsRunning()` turns
  false. The watchdog should then run `TProxyStopService()` (the join), close the old PFD, and
  re-establish. Verified by closing the socketpair peer: hev exited in about 1 s and used 20 ms of
  CPU. Unpatched upstream would spin at 100% CPU and `TProxyStopService` would hang forever.

## 3. Thread model and how stop works

* **Threads.** `native_start_service` creates **one pthread**, which runs `hev_socks5_tunnel_main`.
  Inside that thread everything is cooperative coroutines (hev-task-system) on one epoll loop
  (`hev-main.c:hev_socks5_tunnel_main_inner`, then `hev_socks5_tunnel_run`, then
  `hev_task_system_run`). The coroutines are:
  * an event task (`event_task_entry`);
  * the tun reader (`lwip_io_task_entry`);
  * the lwIP timer (`lwip_timer_task_entry`);
  * one task per TCP or UDP session (`tcp_accept_handler`, `udp_recv_handler`, then
    `hev_socks5_session_task_entry`).

  There are no other threads and no JVM attach. The only blocking libc call is `getaddrinfo`, used
  for non-numeric addresses (`src/core/src/hev-socks5-misc.c`). Our `socks5.address` is numeric, so
  it never blocks.
* **Stop.** `hev_socks5_tunnel_stop` works through the `tsync` flags (SEND, SENT, WAIT, STOP):
  1. It writes one byte into a socketpair (`event_fds[1]`).
  2. `event_task_entry` wakes up, sets `run = 0`, and calls `hev_socks5_session_terminate` on every
     session (timeout 0 plus wakeup).
  3. It joins the io and timer tasks. `hev_task_system_run` returns when no tasks remain.
  4. `hev_socks5_tunnel_fini` tears everything down, and the thread ends.

  If stop arrives before init finishes, `SYNC_STOP` makes `run()` return immediately. Patch P2 clears
  a stale flag on the next start.
* **Idle cost.** With no sessions, the timer task parks in `HEV_TASK_WAITIO`, so it does not wake at
  all (`lwip_timer_task_entry`). With at least one session, it wakes every `TCP_TMR_INTERVAL` of
  250 ms (`lwip/priv/tcp_priv.h`). A phone almost always has some idle long-lived TCP connection, so
  expect 4 cheap wakeups per second. That is near 0% CPU, but it is not zero.
* **Memory.**
  * Each session has its own coroutine stack of `task-stack-size`. The stack backend is `STACK_MMAP`
    (`third-part/hev-task-system/configs.mk`), so only touched pages are committed.
  * The TCP ring buffer of `tcp-buffer-size` is `alloca`'d on that stack
    (`src/hev-socks5-session-tcp.c`).
  * The UDP splice uses `1500 × udp-copy-buffer-nums` bytes of stack
    (`src/hev-socks5-session-udp.c:hev_socks5_session_udp_fwd_b`).
  * lwIP uses static pools: 4096 TCP PCBs and 1024 UDP PCBs (`lwipopts.h`). They sit in about 1.8 MB
    of `.bss`, which costs virtual memory only until used.

## 4. Behaviour towards the SOCKS5 server (byedpi)

* **The server being down is per-session, not fatal (verified).**
  * If the connection to `socks5.address:port` fails, `src/hev-socks5-session.c:hev_socks5_session_run`
    returns for that session only. The app's TCP connection is reset or closed; its UDP flow
    gets no reply.
  * hev keeps running and `TProxyIsRunning()` stays true.
  * Once byedpi is back, new connections work without restarting hev. Verified with the CLI: kill
    ciadpi, then `nc` fails and hev stays alive; restart ciadpi, then HTTP 301 comes back.
  * This means **the watchdog must monitor byedpi itself** (`byedpiStart` returning). hev's
    `IsRunning` does not say whether byedpi is alive.
* **Timeouts** (`src/core/src/hev-socks5-client.c`):
  * `connect-timeout` covers TCP connect to byedpi (`hev_socks5_client_connect`).
  * The SOCKS handshake, including waiting for byedpi's CONNECT reply (which happens after byedpi
    connects upstream), runs under `tcp-read-write-timeout` (`hev_socks5_client_handshake`).
  * After the handshake:
    * TCP sessions end after `tcp-read-write-timeout` ms with **no data in either direction**;
    * UDP sessions end after `udp-read-write-timeout` ms idle.

    Both are idle timeouts reset by I/O (`hev_socks5_task_io_yielder`).
  * App-level TCP keepalive probes are answered by lwIP and do **not** reach hev's session, so they
    do not reset the idle timer.
* **UDP must use `socks5.udp: 'udp'`** (standard UDP ASSOCIATE, verified with byedpi).
  * The default `'tcp'` uses hev's own UDP-in-TCP extension (`HEV_SOCKS5_REQ_CMD_FWD_UDP`) that
    byedpi does not implement.
  * hev sends `UDP ASSOCIATE` with DST `0.0.0.0:0`, because the address family follows the IPv4
    control connection.
  * byedpi replies with the local address of its client-side UDP socket, 127.0.0.1:ephemeral
    (`byedpi proxy.c:udp_associate`). hev `connect()`s a UDP socket to that address
    (`hev-socks5-client-udp.c:hev_socks5_client_udp_set_upstream_addr`).
  * `socks5.udp-address` is not needed.
* **UDP is per flow.** lwIP creates one UDP PCB per (src ip:port, dst ip:port) and hev runs one SOCKS5
  association per PCB. Each association is a TCP control connection plus a UDP socket on both hev
  and byedpi (`hev-socks5-tunnel.c:udp_recv_handler`, `lwip/src/core/udp.c:udp_input`).
  * Every DNS query from a fresh source port becomes a new association, which stays open until
    `udp-read-write-timeout`. This is the main reason to keep that timeout modest on phones: many
    idle DNS associations cost fds in both processes.
  * `max-session-count` caps TCP and UDP sessions together. When the cap is hit, the oldest session
    is terminated (`hev_socks5_tunnel_insert_session`).
* **Half-close.** hev forwards an app's FIN as `shutdown(SHUT_WR)` towards byedpi
  (`hev-socks5-session-tcp.c:tcp_splice_f`). **byedpi does not support half-close**: it treats
  `POLLRDHUP` as a full close (`byedpi proxy.c`, event handler). A client that sends its request and
  then half-closes gets no response through the tunnel. Observed with `nc`; normal HTTP/TLS clients
  do not half-close. This is a byedpi limitation to note for the byedpi agent, not a hev bug.
* **Never set `socks5.mark`.** `SO_MARK` needs `CAP_NET_ADMIN`. `hev-socks5-session.c:hev_socks5_session_bind`
  would fail on every session. Also leave `tcp-fastopen` and `pipeline` unset: there is no benefit
  on loopback. Leave `username` and `password` unset too.

## 5. YAML schema at 2.18.0 (`src/hev-config.c`)

The top-level keys are `tunnel`, `socks5`, `mapdns` and `misc`; unknown keys are ignored.

* Values are scalars parsed with `strtoul`, so a non-numeric value becomes 0.
* If a value inside a section is **not a scalar**, the rest of that section is silently skipped
  (the parse loop `break`s). Emit flat scalars only.
* Quote IPv6 strings. The reset defaults come from `hev_config_reset`.

| key | type / unit | default | notes |
|---|---|---|---|
| `tunnel.mtu` | bytes | 8500 | read size for the external fd. **Must equal `Builder.setMtu`** |
| `tunnel.ipv4` | string (≤15 chars) or map `{address: ...}` | none | ignored with an external fd |
| `tunnel.ipv6` | string (≤63) or map `{address: ...}` | none | ignored with an external fd |
| `tunnel.name`, `guid`, `multi-queue`, `post-up-script`, `pre-down-script` | | | CLI/desktop only, ignored with an external fd |
| `tunnel.icmp` | `off`\|`reply` | off | `reply` makes lwIP answer every ICMP echo locally, so ping to any host "succeeds". Keep off |
| `socks5.address` | IPv4/IPv6/host (≤255) | **required** | `127.0.0.1` |
| `socks5.port` | int | **required** | byedpi port |
| `socks5.udp` | `tcp`\|`udp` | `tcp` | **must be `'udp'`** for byedpi |
| `socks5.udp-address` | string | none | overrides the address in the UDP ASSOCIATE reply. Not needed |
| `socks5.pipeline` | bool | false | leave unset |
| `socks5.username` / `password` | string | none | both or neither. Leave unset |
| `socks5.mark` | int | 0 | **never set** (needs root) |
| `socks5.tcp-fastopen` | bool | false | leave unset |
| `mapdns.address` / `port` / `network` / `netmask` / `cache-size` | | cache-size 0 = off | hev's fake-IP DNS. **Not used**: DNS goes to byedpi via `--redirect`. Do not emit the section |
| `misc.task-stack-size` | bytes | 86016 | raised to at least `20480 + max(tcp-buffer-size, 1500 × udp-copy-buffer-nums)` (`hev_config_parse_doc`) |
| `misc.tcp-buffer-size` | bytes | 65536 | clamped to lwIP `TCP_SND_BUF` (65528). Per-session ring buffer on the stack |
| `misc.udp-recv-buffer-size` | bytes | 524288 | `SO_RCVBUF` of each UDP session socket (`hev-socks5-misc.c`) |
| `misc.udp-copy-buffer-nums` | count | 10 | datagrams per UDP splice batch, 1500 B each on the stack |
| `misc.max-session-count` | count | 0 (unlimited) | when exceeded, the oldest session is terminated |
| `misc.connect-timeout` | ms | 10000 | TCP connect to the SOCKS server |
| `misc.read-write-timeout` | ms | none | fallback for both of the next two when they are ≤0 |
| `misc.tcp-read-write-timeout` | ms | 300000 | TCP idle timeout, also covers the handshake |
| `misc.udp-read-write-timeout` | ms | 60000 | UDP association idle timeout |
| `misc.log-file` | `null`\|`stdout`\|`stderr`\|path (≤1023) | none | empty or `null` disables logging. A path is opened `O_APPEND\|O_CREAT` with **no size limit** (`src/misc/hev-logger.c`). If the open fails, the run exits asynchronously |
| `misc.log-level` | `debug`\|`info`\|`warn`\|`error` | warn | anything else means warn |
| `misc.pid-file` | path | none | **never set**: it calls `daemon()`, which forks the app process (`hev-utils.c:run_as_daemon`) |
| `misc.limit-nofile` | int | 65535 | `set_limit_nofile` raises the soft limit to the hard limit, then tries `setrlimit(n, n)`. Leave unset: the default fails harmlessly, since apps have hard = soft = 32768 on the emulator. A value **below** the hard limit would permanently lower the whole app process's hard limit |

## 6. Recommended phone config and a literal example

The goals are low memory and a bounded number of fds, while keeping throughput reasonable:

* `tcp-buffer-size: 8192` together with `udp-copy-buffer-nums: 5` (7500 < 8192) gives a minimum stack
  of 20480 + 8192 = **28672**. Set `task-stack-size: 28672` explicitly. Upstream's README "low memory"
  example (4096 / 24576) is bumped to 35480 unless `udp-copy-buffer-nums` is also lowered.
* Use a smaller `udp-recv-buffer-size`, 131072. It is kernel memory per UDP session, and hundreds of
  DNS associations can exist.
* Set `max-session-count: 1000`, well below byedpi's `-c 2048`. Each hev session costs byedpi at
  least one client socket plus one upstream socket.
* Set `udp-read-write-timeout: 30000`. Idle DNS associations then die after 30 s, while voice and
  game flows are continuous and unaffected.
* Set `tcp-read-write-timeout: 600000`. At 10 minutes, idle push/keepalive connections are not cut
  every 5 minutes; 300000 is the upstream default. Tune this if battery or reconnect logs suggest
  otherwise.
* Use `connect-timeout: 5000`. byedpi is on loopback, so a slow connect means byedpi is wedged.
* Release builds set `log-file: null` and `log-level: warn`. Debug builds can use
  `log-file: <filesDir>/hev.log` with `log-level: info`. Kotlin must rotate or truncate that file
  **before each start**, for example rename it to `hev.log.1` when it exceeds 512 KB. hev never
  truncates it.

Literal file for our addressing. Kotlin writes it to `filesDir/hev.yml` before every start, replacing
`10808` with the port chosen for byedpi:

```yaml
tunnel:
  mtu: 8500
  ipv4: 198.18.0.1
  ipv6: 'fd00:6764:7069::1'
socks5:
  address: 127.0.0.1
  port: 10808
  udp: 'udp'
misc:
  task-stack-size: 28672
  tcp-buffer-size: 8192
  udp-copy-buffer-nums: 5
  udp-recv-buffer-size: 131072
  max-session-count: 1000
  connect-timeout: 5000
  tcp-read-write-timeout: 600000
  udp-read-write-timeout: 30000
  log-file: null
  log-level: warn
```

Matching `VpnService.Builder` calls:

* `setMtu(8500)`
* `addAddress("198.18.0.1", 32)`
* `addAddress("fd00:6764:7069::1", 128)` when IPv6 is enabled
* routes and DNS as in SPEC §1.2
* `addDisallowedApplication(packageName)`
* leave `setBlocking` at its default

## 7. Engine start/stop recipe (what the service should do)

```
start:  port = freeLoopbackPort(); start byedpi thread; wait until it accepts (<=3 s)
        pfd = builder.establish() ?: fail("VPN izni yok")
        write filesDir/hev.yml (atomic: temp + rename)
        if (!TProxy.TProxyStartService(yml.path, pfd.fd)) { pfd.close(); byedpiStop(); fail(...) }
        sleep ~250 ms; if (!TProxy.TProxyIsRunning()) { TProxyStopService(); pfd.close(); byedpiStop(); fail(...) }
        Running
stop:   TProxy.TProxyStopService()      // joins the tunnel thread
        pfd.close()                     // only now
        byedpiStop(); join byedpi thread (timeout)
watchdog (while Running, e.g. every 5 s, no faster):
        if (!TProxyIsRunning())  -> hev died (tun EOF/error, P3) -> full restart with backoff
        if byedpi thread ended   -> full restart with backoff
```

Stats: sample `TProxyGetStats()` once per second, only while the UI collects. Compute speed from
deltas and handle wrap-around (see §1).

## 8. Gradle integration

```kotlin
android {
    ndkVersion = "29.0.14206865"
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
        externalNativeBuild { ndkBuild { arguments += listOf("-j${Runtime.getRuntime().availableProcessors()}") } }
    }
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
}
```

* **Application.mk.** AGP picks up `src/main/jni/Application.mk` next to `Android.mk`, passing
  `NDK_APPLICATION_MK`. It also passes `APP_ABI` and `APP_PLATFORM` itself (APP_PLATFORM from
  minSdk 24, the same value). `APP_CFLAGS` in `Application.mk` sets `-O2` and the JNI class name for
  every variant, debug included. `APP_MODULES` limits the build to `hev-socks5-tunnel` and `byedpi`,
  so upstream's CLI is not built by Gradle; no `targets(...)` call is needed.
* **Parallel build.** `-j` works: all builds here used `-j8`. A clean build of 4 ABIs took about
  46 s, and an incremental build about 4.5 s.
* **Windows path length (measured).**
  * `make.exe` from NDK r29 **crashes** with `Interrupt/Exception caught (code = 0xc0000005)` when
    `NDK_OUT` is about 214 characters or longer. A path of 189 characters or fewer builds fine. The
    deepest object is `…\obj\local\armeabi-v7a\objs\hev-task-system\src\mem\simple\hev-memory-allocator-simple.o`,
    which adds about 85 characters.
  * AGP puts ndk-build objects under `<module>/build/intermediates/cxx/<Variant>/<hash>/obj`. For this
    worktree that is about 144 characters (`C:\Users\melik\Desktop\goodbydpi\.claude\worktrees\mobile-apk-development-869e7d\android\app\build\intermediates\cxx\RelWithDebInfo\xxxxxxxx\obj`).
    For the main checkout it is about 95 characters. Both are under the limit.
  * If the repo ever sits deeper, relocate the module's build directory to a short path
    (`layout.buildDirectory`), or override `NDK_OUT` via `arguments`. Then verify the AGP-generated
    path with `app/.cxx/**/ndkBuild_command.txt`.
  * Source paths are not the problem: the longest vendored `.c` path is 195 characters, and
    clang handles it.
* **16 KB pages.** `APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true` (the default in r28 and later) and
  upstream's `-Wl,-z,max-page-size=16384` align every LOAD segment to 0x4000 (verified with
  `llvm-readelf -l`; a stand-in byedpi-jni module in the simulation got the same alignment). With AGP
  8.13 and minSdk ≥ 23, the libraries are stored uncompressed and 16 KB zip-aligned by default.
* **Keep rule** (see §1): `-keep class io.github.unsalable.goodbyedpi.engine.TProxy { static native <methods>; }`.

## 9. Verification (real runs)

1. **NDK build, all ABIs.** Command, run from PowerShell:

   ```
   ndk-build.cmd NDK_PROJECT_PATH=null APP_BUILD_SCRIPT=…\jni\Android.mk NDK_APPLICATION_MK=…\jni\Application.mk NDK_OUT=C:\t\hev\obj NDK_LIBS_OUT=C:\t\hev\libs -j8
   ```

   Exit 0, 0 warnings, 46 s from clean. Stripped sizes: arm64-v8a 324,616 B, armeabi-v7a
   229,940 B, x86_64 329,432 B, x86 315,140 B. `llvm-readelf -lW` shows 3 LOAD segments per library,
   all `Align 0x4000`. `NEEDED` is only libc, libm and libdl. `JNI_OnLoad` is exported, and the
   string `io/github/unsalable/goodbyedpi/engine/TProxy` is present.
2. **Top-level `Android.mk` with byedpi.** A copy of the tree was built with a stand-in
   `byedpi-jni/Android.mk`. The stand-in deliberately clobbers `LOCAL_PATH`, `TOP_PATH` and `SRCDIR`,
   and defines `byedpi` plus an extra executable. Results:
   * the default build installed only `libbyedpi.so` and `libhev-socks5-tunnel.so`;
   * `GDPI_NATIVE_TOOLS=1` also built both executables.
3. **JNI harness on emulator-5554 (x86_64, API 36, root).**
   * Setup: `app_process` loads the kotlinc-compiled `TProxy.kt` plus a Java driver (dex from d8)
     and `libhev-socks5-tunnel.so`. A `SOCK_SEQPACKET` socketpair stands in for the tun. byedpi
     `ciadpi` (ba53229, built for x86_64) listens on 127.0.0.1:18090. The driver injects a raw
     IPv4/UDP DNS query for 8.8.8.8:53 and reads the reply packet back.
   * Result: **62/62 PASS**.
   * The failed-start checks all returned false: missing file, missing `socks5.port`, fd -1, and a
     closed fd.
   * Five start → DNS round trip → stop cycles all passed: stats `[1, 57, 1, 89]`, stop took 0–4 ms,
     stats were 0 after stop, and the fd was still open after stop.
   * Stop was verified before start and twice in a row.
   * Peer EOF: hev self-exited in about 1 s using 20 ms of CPU. Before P3 it used 1004 ms of CPU per
     second and stop hung.
   * fd closed under hev: 4 ms of CPU in 2 s, and stop worked.
   * Restart after both failure cases: DNS worked.
   * The same 62 checks also pass with the literal recommended config from §6, with the port
     swapped to 18090.
4. **CLI tun test on the emulator (root).**
   * Setup: `hev-socks5-tunnel-bin` creates tun `tunhev` (198.18.0.1/32, fd00:6764:7069::1/128,
     mtu 8500). An `ip rule uidrange 2000-2000` rule routes only the shell uid into it.
   * Results:
     * TCP: `nc 1.1.1.1 80` returned `HTTP/1.1 301`.
     * UDP: DNS to 8.8.8.8 got an answer.
     * Killing ciadpi made the connection fail while hev stayed alive; restarting ciadpi made it
       work again.
     * SIGTERM gave exit 0 in 41 ms, and the tun device was removed.

The harness (`Main.java`) and the CLI script (`run.sh`) live in the hev agent's scratchpad; they are
not committed. They can be moved under `android/tools/native/` by whoever owns that directory.
