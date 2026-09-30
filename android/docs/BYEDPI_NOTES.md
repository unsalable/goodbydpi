# byedpi: notes for the Kotlin side

These notes cover the vendored **byedpi** `ba532298de7b28cfe854aea83d061369d13ca290` (upstream
`master` HEAD, "17.3") with our local patches (see `app/src/main/jni/PATCHES.md`, byedpi section).
A Kotlin implementer can write `ByeDpiArgs` and the engine's byedpi thread handling from this file
alone. Each claim cites `file:function` relative to `app/src/main/jni/byedpi/`.

* **verified** means it was run on emulator-5554 (x86_64, API 36, kernel `6.6.66-android15-8`
  GKI) with `android/tools/native/smoke.py`. Section 9 lists the commands and the real results.
* **code** means it was read in the source but not exercised directly.

The emulator's slirp NAT terminates TCP inside the emulator. It ignores TTL and ACKs every segment
at once. So a fake segment *reaches the server* there, and fake methods cannot succeed on the
emulator. That is expected. The fake methods are verified on the wire with tcpdump inside the
emulator, which shows TTL and content before slirp. Their DPI efficacy can only be judged on a
real Turkish ISP.

---

## 1. JNI contract (verified, R8 release APK)

```kotlin
internal object NativeBridge {
    init { System.loadLibrary("byedpi") }
    @JvmStatic external fun byedpiStart(args: Array<String>): Int   // blocks
    @JvmStatic external fun byedpiStop(): Int
    // + constants OK=0, ERR_START=-1, ERR_ARGS=-2, ERR_BUSY=-3, ERR_EXITED=-4, ERR_INTERNAL=-5
}
```

* **Registration.** `byedpi-jni/byedpi_jni.c:JNI_OnLoad` calls `RegisterNatives` on
  `io/github/unsalable/goodbyedpi/engine/NativeBridge`.
  * If the class or method names change, loading fails loudly with `UnsatisfiedLinkError`.
  * The keep rule is already in `app/proguard-rules.pro`.
  * Verified: `dexdump` of the release APK shows both methods as `PUBLIC STATIC FINAL NATIVE`.
    `tools/native/JniSmoke.java` runs against the R8-processed release APK through `app_process`
    and passes 13/13 checks (section 9).
* **args** are the options only, **without the program name**. The glue prepends `"ciadpi"`.
  * A null element returns `-2`.
  * Up to 1024 arguments are accepted.
  * Strings are copied into C memory. byedpi keeps pointers into them for the whole run, so the glue
    frees them only after the run ends.
* **Return codes** (`byedpi-jni/byedpi_lib.h`, `byedpi_lib.c:byedpi_lib_start`):

| code | meaning | typical cause |
|---:|---|---|
| 0 | clean stop, requested by `byedpiStop` | normal |
| -1 | start failed | `bind: Address already in use` (port taken), socket/epoll failure |
| -2 | bad arguments | unknown option, invalid value, a missing value, more than 64 groups |
| -3 | busy | another `byedpiStart` is running in this process |
| -4 | exited without a stop request | fatal `accept()` error (EBADF/EINVAL-class only; EMFILE/ENFILE/ENOBUFS/ENOMEM pause the listener for 100 ms instead, PATCHES B6), `epoll_wait` failure, or `-h`/`-v` passed |
| -5 | internal | OOM, eventfd failure, or a JNI string conversion failure (a Java exception may be pending) |

  The reason is logged to **logcat tag `ciadpi`**, for example `E ciadpi: invalid value: -V 0` or
  `E ciadpi: bind: Address already in use`. The watchdog should treat -1, -4 and -5 as "restart
  with backoff", and -2 as a bug in `ByeDpiArgs`.
* **Threading.**
  * `byedpiStart` runs the whole proxy, a single-threaded epoll loop, on the calling thread. Call it
    on a dedicated thread. The loop makes no JNI calls.
  * `byedpiStop` is thread-safe and never blocks for long (it takes one mutex and writes to one
    eventfd). It returns 0 if a run is in progress or already stopping, and -1 if nothing is running.
  * Double stop and concurrent stops are safe. After the loop ends, later stops return -1.
* **Only one instance per process.** A second start returns -3 immediately (verified). The previous
  run's state is fully reset on each start (section 8). Start → stop → start was run 50× in
  `restart_test` and 5× through JNI.
* **Stop-before-start.**
  * A stop that arrives while `byedpiStart` is still parsing or initialising makes that start return
    0 promptly. If the stop comes before `listen()`, the port is never opened; otherwise the loop
    exits on its first iteration (verified: `R1`/`R7` in `restart_test`; JNI `stop_during_start`
    took 52-55 ms).
  * A stop that arrives **before the thread entered native code** returns -1 and does nothing. The
    lib cannot latch a stop, because that would kill a legitimate later start.
  * To cancel a start safely, loop: `while (thread.isAlive) { NativeBridge.byedpiStop(); thread.join(50) }`.
* **Readiness.** There is no callback. Poll a TCP connect to `127.0.0.1:<port>` every ~10–20 ms, for
  up to 3 s (SPEC §3). Typical time to listen is under 10 ms.
* **Stop latency** is immediate. The only blocking call in the loop was `getaddrinfo` for SOCKS
  requests carrying domain names; `ByeDpiArgs` passes `-N`, so those get reply `08` and nothing
  blocks. hev and the connection tester always send IP addresses.
* **Mechanism.** The SPEC suggested `shutdown(server_fd)`. It was replaced by an eventfd that
  `byedpi_lib.c` owns (`proxy.c:start_event_loop` registers a `dup` of it). Reason: the loop closes
  `server_fd` itself. A late `shutdown()` from another thread could hit that fd *number* after the
  app reused it, for example for hev's socket. This is a deviation from SPEC §1.1 wording, with the
  same semantics.
* **No `exit()` or `abort()` is reachable.**
  * The upstream code has no `exit()`.
  * `daemon()`/`--pidfile` are compiled out in lib mode (`main.c`: `DAEMON` is not defined).
  * `assert()` is disabled in the `.so` (`-DNDEBUG`); the test binaries keep it on.
  * Signal handlers are not installed. `SIGPIPE` is still set to `SIG_IGN`, as SPEC requires.

## 2. Argument model: groups (code + verified)

`main.c:parse_args` walks argv left to right with `getopt_long`.

* **Global options** can appear anywhere; the last value wins:
  * `-i/--ip`, `-p/--port`, `-c/--max-conn`, `-b/--buf-size`, `-x/--debug`
  * `-N`, `-U`, `-I`, `-g/--def-ttl`, `-T/--timeout`, `-F/--tfo`
  * `--redirect`, `--drop-udp`, `--deny-net`, `-W`, `-Z`, `/`
* **Group options** apply to the *current group*:
  * `-K/--proto`, `-V/--pf`, `-H/--hosts`, `-j/--ipset`, `-R/--round`
  * the part options `-s/--split`, `-d/--disorder`, `-o/--oob`, `-q/--disoob`, `-f/--fake`
  * `-t/--ttl`, `-S/--md5sig`, `-n/--fake-sni`, `-l/--fake-data`, `-O/--fake-offset`, `-Q`, `-e`,
    `-M`, `-r/--tlsrec`, `-m`
  * `-a/--udp-fake`, `-u/--cache-ttl`, `-L/--auto-mode`, `-Y`, `-y`, `-C`, `#`
* **`-A/--auto=<list>` always closes the current group and starts a new one.** Every occurrence does
  (`main.c:parse_args case 'A'` → `add_group`). The list sets the *new* group's `detect` mask.
  Only the **first letter** of each comma-separated token is read:

| token | flag | fires when (all `extend.c`) |
|---|---|---|
| `torst` (`t…`) | `DETECT_TORST` | upstream `recv`/`send` fails with ECONNRESET, ECONNREFUSED, ETIMEDOUT or EHOSTUNREACH (`handle_err` → `on_torst`), including the `--timeout` expiry (ETIMEDOUT from `TCP_USER_TIMEOUT`) and the partial-TLS timer (`on_timeout`). **Patched (B7)**: only while the server is in its first round (`round_count <= 1`); a reset later in a working connection just closes it and leaves the cache alone |
| `redirect` (`r…`) | `DETECT_HTTP_LOCAT` | first HTTP response is a 30x whose `Location` points to a *different* registrable domain (`on_response` → `packets.c:is_http_redirect`) |
| `ssl_err` (`s…`) | `DETECT_TLS_ERR` | first response to a TLS ClientHello is not a ServerHello, or its session id differs (`on_response`); or the server closes before answering (`on_fin`, only while `mark` is set, round 1). **Patched (B7)**, only in groups with a fake part: the client closes after the server's first flight without a second round, or the first record of the second round (either side) is a plaintext TLS alert; no replay, the cache entry moves the next connection to the fallback |
| `conn` (`c…`) | `DETECT_CONNECT` | the upstream TCP connect fails (RST, ETIMEDOUT after 1 SYN retry: `proxy.c:create_conn` sets `TCP_SYNCNT=1`) (`on_connerr`) |
| `none` (`n…`) | 0 | nothing. The new group is a **static** group, so `--auto=none` is just a group separator |
| `p=<float>` | – | sets the *previous* group's priority. Only used with `--auto-mode s`; we don't use it |

  Anything else, such as `--auto=q`, is `invalid value` (-2, verified). Setting any detect flag also
  turns on `params.auto_reconnect`: the first request of each connection (up to `-b` bytes) is kept
  so it can be replayed.
* **Trailing catch-all group** (`main.c:parse_args`, end).
  * Rule: if every group that was *closed by an `--auto`* had some filter (`--proto`, `--pf`,
    `--hosts`, `--ipset` or a detect mask), byedpi appends one extra **empty static group** at the end.
    The last group is never checked; a parse with no `--auto` at all also gets the catch-all.
  * It catches everything the filtered groups skipped, such as plain HTTP when the scope is TLS-only,
    other TCP, or UDP outside the voice ranges. That traffic is relayed without desync. Verified in
    the full layout: 7 user groups plus group 7, the auto-appended one (`dump_all_cache` log).
  * Without any matching group, a TCP connection is dropped (`connect_hook`: `drop connection`).
    For UDP the whole association is torn down (`udp_hook` returns -1).
  * With our layouts this never happens. **Keep a `--proto` on every group that is followed by
    `--auto`**, so the catch-all is always appended.
* **Group limit.** At most 64 groups. More gives `too many groups!` → -2. This is our patch: upstream
  stored `1 << id` in an `int`, so above 31 groups the masks broke silently.

### 2.1 Which group a TCP connection gets (code, verified by the DPI simulation)

1. **At CONNECT** (`extend.c:connect_hook`): look up the cache (§5). Then `find_dp` walks groups in
   argv order and takes the first one where all of these hold:
   * it has not been tried yet;
   * it is a **static** group (`detect == 0`), is the cached group, or its detect mask matches the
     cached trigger;
   * `check_l34` passes: `--proto` TCP/UDP family, `--pf`, `--ipset`, and the IPv4-only flag.
   `--proto` payload and `--hosts` are *not* checked yet, because there is no data.
2. **At the first request data** (`extend.c:setup_conn` → `find_dp` again, starting from the chosen
   group), the payload filters are checked:
   * `--proto=tls` matches a TLS ClientHello (`packets.c:is_tls_chello`: `16 03 xx`, handshake type 1);
   * `--proto=http` matches an HTTP method line;
   * `--hosts` matches the SNI or Host.
   On a mismatch, the walk continues to the next static group, which is how HTTP ends up in the
   catch-all.
3. **Auto groups are never picked for a new connection.** They are used only after a trigger
   (`on_trigger`) or from the cache.
4. **On a trigger** (`extend.c:on_trigger`), the next group is the first *not yet tried* group
   **after the current one** whose detect mask contains the trigger type. The walk **stops at the
   first static group** it meets.
   * **Fallback groups must directly follow the primary group, and nothing static may sit between
     them.** Verified: `rst: static group ends chain` — with `--auto=none` in between, the fallback is
     never used.
   * An auto group whose mask does not match the trigger type is skipped, and the walk goes on.
     Verified: `rst: chain skips failing alt` saved group 1 and then group 2.
   * If a next group exists and the request can be replayed, byedpi reconnects transparently with it.
     Replay is possible when the client is alive and the saved first request exists (the server has
     not answered yet). The app sees one successful connection.
   * If no next group exists, the IP:port is marked "unreach": the cache is reset to group 0, and
     the current connection is closed. HTTP data already received is passed through.
   * Patch B9: an `ssl_err` in the **last** group (or with `autoFallback` off) also reaches
     "unreach" now. Before B9, upstream left the entry on the failed group for `--cache-ttl`. A
     **client** close before the server's first byte is no longer a trigger. It only un-pins an
     entry that points at the connection's fallback group (§5.1).
5. **UDP** (`extend.c:udp_hook`): on the association's first datagram, byedpi takes the first
   **static** group whose `check_l34(SOCK_DGRAM, dst)` passes:
   * `--proto=udp` or no proto;
   * `--pf`, where `dst` is the *redirected* target (`TO`);
   * `--ipset`.
   `--proto=tls/http` groups never match UDP. The choice is cached for the association. Payload,
   `--hosts` and auto groups are not used for UDP.

## 3. Option reference (only what ByeDpiArgs needs)

| option | exact syntax / semantics at ba53229 | source | status |
|---|---|---|---|
| `-i 127.0.0.1` | listen IP (`main.c:get_addr_scheme`). **Always pass it.** The default is `0.0.0.0`, which would be an open SOCKS proxy on the phone's Wi-Fi/LAN interfaces | main.c | verified |
| `-p <port>` | 1..65535, default 1080. Always pass the chosen free loopback port | main.c | verified |
| `-c <n>` | max connections, 1..32766. The epoll pool holds `2n+1` events (TCP pair = 2 fds, UDP association = 3). If the pool is full, new connections are closed. `-c 2048` allocates about 0.9 MB virtual per run, mostly untouched | main.c, proxy.c:start_event_loop | code |
| `-b <bytes>` | per-direction relay buffer (only while a send is pending), and the max size of the replay copy of the first request | proxy.c, extend.c | code |
| `-x <0\|1\|2>` | log level to logcat tag `ciadpi`: 0 = errors only (use in release), 1 = per-connection debug (heavy), 2 = verbose | error.h (patched) | verified |
| `--proto=<list>` / `-K` | first letters: `t`(tls) = TCP+ClientHello, `h`(http) = TCP+HTTP request, `u`(udp) = UDP only, `i`(ipv4) = IPv4 destinations only. `tls,http` means either. **Do not combine `udp` with `tls`/`http`**: a group then matches nothing | main.c case 'K', extend.c:check_l34/check_proto_tcp | verified |
| `--pf=LO[-HI]` / `-V` | destination port range, one per group (the last one wins). Parsed with `strtol(base 0)`, so **emit plain decimal without leading zeros** (`050000` would be octal). **Patched**: upstream compared network-order values, so real ranges were wrong on little-endian. For example 443 and 1253 "matched" 50000-65535, and 18096 did not match 17900-18200. Single ports were always right | main.c case 'V', extend.c:check_l34 | verified (`udp_fake_pf_range`) |
| `--split POS` `-s` | cut the first request at POS; that segment is sent normally | desync.c:desync | verified (wire) |
| `--disorder POS` `-d` | the segment before POS is sent with **TTL 1**. It dies at the first hop and the kernel retransmits it later, so the server and DPI get the tail first | desync.c:desync | verified (wire: `ttl1/2B`) |
| `--fake POS` `-f` | the segment `[prev, POS)` is first sent with **fake bytes** and low TTL (§4). The real bytes follow as a kernel retransmission. **Patched (B8)**: consecutive fake parts continue one fake request instead of restarting it | desync.c:send_fake | verified (wire) |
| `--oob POS` `-o`, `--disoob` | segment + 1 urgent byte (`--oob-data`, default `a`). **Not used**: the server may see an extra byte | desync.c:send_oob | code |
| `--ttl N` `-t` | TTL of fakes (TCP and UDP) in this group, 1..255. **Without `--ttl`, fakes use TTL 8** (`desync.c DEFAULT_TTL`) | desync.c:send_fake/desync_udp | verified (wire `ttl8/457B`, UDP `ttl 8`) |
| `--md5sig` `-S` | add TCP MD5 option to fake segments. **The Android GKI kernel has no `TCP_MD5SIG`** (`setsockopt: Protocol not available`, ENOPROTOOPT). Upstream then closed every connection of the group. **Patched**: logs once and continues with TTL only. `ByeDpiArgs` probes the kernel once (`setsockopt(TCP_MD5SIG)` with a 4-byte value: ENOPROTOOPT = absent, EINVAL = present) and omits `--md5sig` when absent, so `md5sig`/`md5ttl3` dedupe against `fixedttl`/`ttl3` | desync.c:send_fake | verified (wire shows the TTL fake, connection not killed) |
| `--fake-sni NAME` `-n` | SNI written into the TLS fake. `?` = random letter, `#` = digit, `*` = letter/digit. Repeatable (random pick). It also **resizes the fake ClientHello to the real request's length** (`change_tls_sni`), so always emit it for TLS fakes | desync.c:get_tcp_fake, packets.c:change_tls_sni | verified (wire: `www.w3.org`, record len = real len − 5) |
| `--fake-data :STR` / `FILE` `-l` | custom fake payload for TCP fakes **and** UDP fakes of this group; only the first `-l` per group counts. `:` means inline, with C escapes (`main.c:parse_cform`): `\r \n \t \\ \f \b \v \a`, `\xHH`, `\OOO` (octal, up to 3 digits). An unknown `\c` gives `c`. **4 zero bytes = `:\x00\x00\x00\x00`** (or `:\0\0\0\0`). This is a single argv element with literal backslashes, no shell (Kotlin: `":\\x00\\x00\\x00\\x00"`) | main.c:ftob/data_from_str | verified (wire: all-zero fake) |
| `--tlsrec POS` `-r` | split the ClientHello *TLS record* into two records (a 5-byte header is inserted). Plain POS is an offset in the record payload. `N+s` is the SNI start + N, `0+sm` the SNI middle. Only applied to TLS ClientHellos; a CH without SNI with `+s` is cancelled (logged `tlsrec cancel`) | desync.c:tamp, packets.c:part_tls | verified (wire: record1 len 129, record2 header at 134) |
| `--udp-fake N` `-a` | before each client datagram of **round 1** (all datagrams until the first reply), send N fake datagrams (`--fake-data` or 64 zero bytes) with TTL `--ttl` (default 8), then the real one with the normal TTL | desync.c:desync_udp, extend.c:udp_hook | verified (`udp_fake_default_ttl8`: `64/ttl8,64/ttl8,15/ttl64`) |
| `--auto=…` `-A` | see §2 | main.c, extend.c | verified |
| `--timeout S[:P[:C[:B]]]` `-T` | **Global.** S seconds (float) → `TCP_USER_TIMEOUT` = S·1000 ms on the upstream socket when its first request is sent (`extend.c:setup_conn`). **Patched (B7)**: only if the server has not sent a byte yet and the chosen group is directly followed by a `torst` group; server-first protocols and groups without a fallback never get it. If sent data stays un-ACKed that long, the kernel aborts with ETIMEDOUT, which triggers `torst`. **B** bytes: once the server sent more than B bytes, the timeout is removed (`tcp_recv_hook`). **Always set B ≥ 1**, otherwise a mobile stall longer than S kills long-lived connections later *and* caches a fallback for that IP. P = partial-TLS-record timer seconds (0 = off); C = number of P expiries before `torst` | main.c case 'T', extend.c | verified (3 s → first request 4.7-6.2 s via fallback) |
| `--cache-ttl SEC` `-u` | per group. On a trigger, the chosen fallback group is cached per **destination IP + port** (`extend.c:cache_add`). Later connections to that IP:port start directly at that group. An entry expires after the *cached group's* `--cache-ttl` seconds; 0 or absent means it lives until the proxy stops. **Put it on every fallback group.** **Patched**: expiry deleted a random IPv4 entry (length passed in bytes instead of bits) | extend.c:cache_get/cache_add | verified (5 s: hit 0.6 s, then after 7 s slow again) |
| `--def-ttl N` `-g` | sets TTL on *all* outgoing sockets. **Leave unset**: `def_ttl` is then read from a socket (64) and used only to restore after fakes | main.c:init, extend.c:socket_mod | code |
| `--redirect FROM=TO` | **ours.** Repeatable; `ip:port` or `[ipv6]:port` on both sides, and both ports are required. See §6 | main.c:parse_redirect, proxy.c | verified |
| `--drop-udp P[-Q]` | **ours.** Repeatable, decimal, 1..65535, P ≤ Q. See §6 | main.c:parse_drop_udp, proxy.c | verified |
| `--deny-net ADDR/BITS` | **ours.** Repeatable; IPv4 or IPv6 (no brackets). TCP CONNECT to a denied destination that is not a `--redirect` FROM gets SOCKS reply `02` at once; a UDP datagram to it is dropped. See §6 | main.c:parse_deny_net, proxy.c:deny_dst | verified |
| `-N` | no domain resolving: SOCKS5 ATYP 3 / SOCKS4a get an error (`08`), no blocking `getaddrinfo` in the loop. **Always pass it** (hev and the connection tester send IPs) | main.c, proxy.c:s5_get_addr | verified (`no_domain_atyp3_refused`) |
| `-U` | no UDP. **Do not use** (hev needs UDP) | main.c | verified |

**Position syntax** (`POS`, `main.c:parse_offset`, `desync.c:gen_offset`): `N[:R[:S]][+F[M]]`.

* `N` is an integer.
  * A negative `N` with no flag counts from the end of the request: `-1` = n−1.
* `R` repeats the part R times, at N, N+S, N+2S, …
* `F` is the anchor:
  * `s` = SNI start (TLS only; for a non-TLS request the part is *cancelled*);
  * `h` = host start, meaning the SNI for TLS or the `Host:` value for HTTP;
  * `n` = absolute.
* `M` is the modifier:
  * `e` = host end;
  * `m` = host middle;
  * `r` = random inside the host;
  * `s` = "start": the part is not skipped on resumed sends.
* Examples: `2`, `-1`, `0+sm` (SNI middle), `0+hm` (SNI middle for TLS, Host middle for HTTP), `3+s`.
* **Parts are processed left to right in argv order.**
  * A part whose position is behind the previous one logs `split cancel`, and the rest of the
    request goes out in one piece. So emit parts in ascending position order.
  * An `+s` part on HTTP cancels too. That is why we use `+h`, which is verified: HTTP `Host` at
    offset 22 was split at 27.

## 4. How "fake" really works on Linux (verified on the wire)

`desync.c:send_fake` works in four steps:

1. The fake bytes are written into a fresh page.
2. The page is `vmsplice(SPLICE_F_GIFT)`ed into the socket, with `IP_TTL` = `--ttl`, so the kernel's
   send queue *references the page*.
3. After the segment left, `restore_state` copies the **real** bytes into the same page and
   restores the TTL.
4. The fake segment dies before the server (low TTL), so the kernel retransmits that sequence range
   after RTO (≥200 ms) with the real content and the normal TTL.

Consequences:

* The fake is **exactly as long as the part range** (`POS − prev`), not as long as `--fake-data`.
  Bytes beyond the fake data are zeros.
* A single fake part starts at byte 0 of the fake payload. **Consecutive** fake parts are one fake
  request cut at the same offsets as the real one (patch B8): `--fake 2 --fake -1` sends `16 03`
  and then the fake from byte 2 on, so the two segments reassemble to one coherent fake ClientHello
  (wire-verified: `ttl5/2B` + `ttl5/455B` starting `01 01 c5`, SNI `www.w3.org`). Upstream restarted
  every part at byte 0 (`16 03 16 03 01 …`).
* Each fake costs one RTO for the real data, about 200–300 ms added to connection setup.
* Wire capture of the `default` preset on `example.com`, as tcpdump inside the emulator shows it:
  1. `ttl1/2B`: the disorder part, `16 03`;
  2. `ttl64/134B`: the split up to the SNI middle;
  3. `ttl5/321B`: the fake, a ClientHello with SNI `www.w3.org`, resized;
  4. `ttl64/1B`: the last byte.
* Through slirp, the TTL 1 and TTL 5 segments are ACKed at once. The server therefore receives the
  fake bytes and resets or stalls: "EXPECTED" rows in section 9.

## 5. Caching, timeouts and fallback timing (verified with a simulated DPI)

The DPI simulation adds an `iptables -m string --string example.com` rule on uid 2000's port 443
traffic inside the emulator, and runs curl through ciadpi:

| scenario | result |
|---|---|
| DROP rule, no desync | blocked (timeout) |
| DROP, `--split 0+hm` | 200: the hostname is never in one segment |
| DROP, `--tlsrec 3+s` | 200: the record header breaks the string |
| DROP, primary plain + `--auto=torst,ssl_err` `--split 0+hm --cache-ttl 5`, `--timeout 3:0:0:1` | 1st request 200 after 4.7-6.2 s (3 s `TCP_USER_TIMEOUT` → `torst` → transparent replay), 2nd 200 in 0.6 s (cache hit), after 7 s 200 in 4.9-5.3 s (entry expired). Ranges are over several runs |
| RST (REJECT tcp-reset), `--auto=torst` → split | 200 in 1.6 s, `save: id=1` |
| RST, auto(disorder 2, also blocked) → auto(split) | 200, saves id 1 then 2 (a failing alternative is skipped) |
| RST, primary `--auto=none` static split | **fails**: a static group ends the fallback chain |
| RST, full recommended layout (§7.3) | 200 via groups 4→5 |
| RST, smart layout (§7.3.1) | 1st 200 in 2.6 s via 3 (direct, RST) → 4 (default: fake broken by slirp) → 5 (disorder, RST) → 6 (split); 2nd 200 in 0.6 s (cache) |
| no DPI, smart layout | example.com / www.google.com / http 200, only `group=3` logged, no `save:` |

Timing guidance:

* `--timeout 4:0:0:1` means a blocked host costs about 4 s once, then it is cached.
* The cache is per proxy run. Every engine restart (settings change) starts it empty.
* Only first-round failures move an IP:port to the fallback (patch B7); a reset or timeout in a
  connection that already got its second server round just closes that connection.
* The upstream connect has about 7 s (`TCP_SYNCNT` 2, patch B9; upstream 1 gave about 3 s). A
  destination that silently drops SYNs costs the app about 7 s before the RST.

### 5.1 Cache poisoning in smart mode and patch B9 (emulator-5554, 2026-09-30)

The events that move an entry, after B9:

| event | before B9 (upstream) | after B9 |
|---|---|---|
| server RST / `ETIMEDOUT` (`TCP_USER_TIMEOUT`) in round ≤ 1 | next group (`save`) or "unreach" | unchanged |
| server FIN before its first byte (`mark`) | next group or "unreach" | unchanged |
| server answered the fake, client closes (`fake_abort`) / TLS alert in round 2 | next group or "unreach" | unchanged |
| **client** closes/resets before the server's first byte | treated as `ssl_err`: **next group for 1 h** | no trigger; if the connection runs in a fallback group, its entry goes back to the head (`unpin ip`) |
| `ssl_err` response (no ServerHello, `neq_tls_sid`) and a later `ssl_err` group exists | next group + replay | unchanged |
| `ssl_err` response in the **last** group (or `autoFallback` off) | nothing: **the entry stays on the failed group for 1 h** | "unreach": the entry goes back to the head, no replay |

Reproduction: the scratch script `cachetest.py`, which is the investigator's `abort.py`/`stall.py`
with a choosable binary. It runs `ciadpi -x 1` in the smart layout for Turkcell Mobil (voice groups
0-2, direct group 3, `default` 4, and with fallbacks `disorder` 5 and `ttl3` 6), host curl via
`--socks5 --resolve www.google.com:443:142.251.157.119`.
* **abort:** netem 1500 ms only towards that IP (`prio` + `u32` filter), `curl -m 2.5` gives up
  after the ClientHello was forwarded.
* **stall:** `iptables DROP` of the shell uid's ≥ 200-byte packets to that IP for 6 s.
* Then 4 × `/search?q=test`.

| case | before B9 | after B9 |
|---|---|---|
| abort, fallbacks off | `save id=4`; 4/4 later requests fail (curl exit 35: the fake reaches Google via slirp, like a GGC) | no `save`; 4/4 200, all in group 3 |
| abort, fallbacks on | `save id=4`, then `save id=5`; 4/4 200 via disorder (slower) | no `save`; 4/4 200 in group 3 |
| stall, fallbacks off | `save id=4` (a real 6 s stall looks like a DPI drop); 4/4 later requests fail, no "unreach" | `save id=4`, the replayed request fails and hits "unreach"; 4/4 later requests 200 in group 3 |
| stall, fallbacks on | `save 4 → 5`, 200 via disorder | unchanged (legitimate: 4 failed, 5 works) |

Deterministic smoke rows (host servers, no root):
* `cache: client abort: direct group no save`: the host TLS server never answers `SILENT_SNI`.
* `cache: client abort in fallback unpins`: fake → split 1 → split 2. The fake is answered, so
  `fake_abort` pins 1. The client aborts in 1, and the next connection starts at 0 again. Before
  B9 the entry moved on to 2.
* `cache: last fallback fails: unreach`: the echo server returns the ClientHello, which is not a
  ServerHello. Group 0 → 1 → "unreach", and the next connection starts at 0. Before B9 it started
  at 1.

All three rows FAIL on the pre-B9 build and PASS after it:

| row | pre-B9 result |
|---|---|
| `client abort: direct group no save` | `saves ['1']; groups ['0','1']` |
| `client abort in fallback unpins` | `saves ['1','2']; groups ['0','1','2']` |
| `last fallback fails: unreach` | `saves ['1']; groups ['0','1','1']` |

The third row uses `--timeout 4:0:0:1` like the app. Without it, `mark` survives the first
response, and upstream's client-close trigger masked the gap.

What still costs time: a real stall longer than 4 s on the direct group still pins the IP to the
method group for an hour. It cannot be told apart from a silent DPI drop. That is harmless when the
fake does not reach the server. When it does reach the server, the method group fails with
`ssl_err`: the next fallback takes over, or, without one, the entry is reset (row 3 above). A
silent failure in the **last** group is still not detected, because `TCP_USER_TIMEOUT` is only
armed when a `torst` successor exists (B7).

## 6. `--redirect` and `--drop-udp` (our patches)

* **TCP** (`proxy.c:handle_s5`/`on_request` → `redirect_tcp`):
  * A CONNECT (also SOCKS4/HTTP CONNECT) whose destination equals FROM connects to TO instead.
  * The destination is compared after un-mapping v4-mapped IPv6, as family + address + port. FROM
    given as `[::ffff:a.b.c.d]` is stored as IPv4.
  * The SOCKS reply carries no address, so nothing is relabelled.
  * Group selection and the cache use TO.
  * Verified: `tcp_dns_redirect` sends DNS over TCP to 198.18.0.53:53 → 77.88.8.8:1253 and gets an
    answer. `tcp_redirect_v6_to_v4` goes from an IPv6 FROM to an IPv4 TO.
* **UDP** (`proxy.c:on_udp_tunnel`):
  * The *first forwarded* datagram of an association decides it. If its destination equals FROM,
    the association's upstream socket is `connect()`ed to TO, and the association is flagged
    (`struct eval.redir`, per association, not a global address match).
  * Every reply is then relabelled in the SOCKS5 UDP header as coming from FROM; the socket is
    connected, so only TO can answer.
  * FROM and TO may be of different families.
  * Verified:
    * `udp_dns_redirect_v4`: the answer is labelled `198.18.0.53:53`;
    * `udp_dns_redirect_v4mapped`: the request goes to `::ffff:198.18.0.53`, and the label is plain
      IPv4;
    * `udp_redirect_v6_to_v4`: the label is `[fd00:6764:7069::53]:53`.
* **Upstream limitation (kept):** one association = one destination. After the first datagram,
  later datagrams go to that destination whatever their header says. This is fine, because hev opens
  one association per UDP flow (HEV_NOTES §4).
* **`--deny-net`** (patch B5):
  * `ByeDpiArgs` always passes `--deny-net 198.18.0.0/15 --deny-net fd00:6764:7069::/48` (the tun's
    own blocks). A `--redirect` FROM inside them still wins (checked first).
  * TCP: reply `02` in a few ms (`tcp_deny_virtual_v4`: 9 ms, `…_v6`: 5 ms); hev resets the app's
    connection at once. This is what Android's Private DNS probe of `198.18.0.53:853` now gets,
    instead of a real SYN to the ISP. With DNS "Kapalı" there is no redirect, so `198.18.0.53:53` is
    refused as well (the connection tester relies on that to fall back to the system resolver).
  * UDP: the datagram is dropped silently, like `--drop-udp`; the association stays unbound
    (`udp_deny_virtual_silent` + `udp_deny_then_dns`).
* **`--drop-udp`**:
  * A client datagram whose *requested* destination port (before redirect) is in a range is silently
    discarded. No error is returned and the association is not torn down.
  * If the dropped datagram was the association's first, the association stays unbound, and the next
    datagram (for example DNS) binds it.
  * Verified:
    * `udp_drop_443_silent` followed by `udp_drop_443_then_dns` on the same association;
    * `udp_drop_port_never_reached`: the local echo on the dropped port received 0 datagrams;
    * a datagram to a dropped port is dropped even after the association is bound.
* **Malformed input** (verified, ASan build):
  * An unknown ATYP gets reply code 08. Upstream read `buffer[-2]` here; this is patched.
  * Bad greetings, short requests and 3 KB of garbage are handled.
  * A 1-byte datagram, a fragmented datagram (`frag≠0`, dropped) and an unknown-ATYP datagram (the
    association is closed) are handled.
  * The proxy stays up, and later associations work.

## 7. Final mapping (for `ByeDpiArgs`)

### 7.1 One TCP group from a `DpiConfig`

```
scope  = if (fragmentHttp) "--proto=tls,http" else "--proto=tls"
parts  = []                                   // emitted in this order (ascending position)
if (splitTls)                parts += [if (reverseSplit) "--disorder" else "--split", "$splitPosition"]
if (splitTls && splitSni)    parts += ["--split", "0+hm"]
if (fakePacket) {
    if (splitFake && !splitTls) parts += ["--fake", "2"]      // fake split in two segments
    parts += ["--fake", "-1"]
    parts += ["--ttl", "$ttl"]                // ALWAYS: byedpi always lowers the fake's TTL (default 8)
    if (fakeMd5Sig && kernelHasMd5 != false) parts += ["--md5sig"]   // GKI: omitted (probe), TTL only
    if (fakePayload == TLS)   parts += ["--fake-sni", fakeSni]
    else                      parts += ["--fake-data", ":\\x00\\x00\\x00\\x00"]   // Kotlin literal
}
if (tlsRecordSplit)          parts += ["--tlsrec", "3+s"]
group = [scope] + parts
```

Notes on the rules:

* `fakeTtl=false`, `fakeMd5Sig=false` (no protection) still emits `--ttl $ttl`. This is the "engine
  falls back to TTL" rule from WAVE1.
* `fakeTtl=false`, `fakeMd5Sig=true` emits `--md5sig --ttl $ttl`. On MD5-capable kernels this is
  MD5 plus TTL. On GKI it is TTL only, and `ByeDpiArgs.md5SigSupport` (a one-time
  `setsockopt(TCP_MD5SIG)` probe) drops `--md5sig` from the argv so that the fallback dedupe sees
  `md5sig == fixedttl` and `md5ttl3 == ttl3`. Unknown probe result (null) keeps `--md5sig`; the
  native ENOPROTOOPT fallback still applies. We deliberately do *not* use `--ttl 64` for "MD5
  only": without kernel MD5 the fake would reach the server and break every connection.
* `splitFake` together with `splitTls` is not emitted. The fake split at 2 would sit behind the
  split at `splitPosition` and be cancelled.
* `splitPosition` must stay ≥ 1 (`DpiConfig.SPLIT_RANGE`). With HTTP, a split position beyond the
  Host header cancels the parts after it. That is harmless.

### 7.2 Preset table (TCP group, `fragmentHttp` on)

| id | TCP group argv | emulator result | reasoning |
|---|---|---|---|
| `default` | `--proto=tls,http --disorder 2 --split 0+hm --fake -1 --ttl 5 --fake-sni www.w3.org` | fake → EXPECTED; wire verified | desktop: fake TTL + split@2 reversed + SNI split. Disorder = reversed split; `0+hm` = SNI/Host middle; fake covers the rest |
| `fixedttl` | `--proto=tls,http --fake -1 --ttl 5 --fake-sni www.w3.org` | EXPECTED | GoodbyeDPI-Turkey `--set-ttl 5` |
| `disorder` | `--proto=tls,http --disorder 2` | **200 / 200 / 200** | zapret multidisorder pos=2 **without** seqovl (impossible through a kernel socket, §7.4); weaker than the desktop method of the same name |
| `ttl4` | `--proto=tls,http --fake -1 --ttl 4 --fake-sni www.w3.org` | EXPECTED | zapret fake ttl=4 |
| `ttl3` | `--proto=tls,http --fake -1 --ttl 3 --fake-sni www.w3.org` | EXPECTED | zapret fake ttl=3 |
| `md5sig` | `--proto=tls,http --fake -1 --ttl 5 --md5sig --fake-sni www.w3.org` (GKI: without `--md5sig`) | EXPECTED; wire: TTL-only fake | zapret fake md5sig; md5 is unavailable on GKI, so it degrades to TTL 5 (= `fixedttl`) |
| `md5ttl3` | `--proto=tls,http --fake -1 --ttl 3 --md5sig --fake-sni www.w3.org` (GKI: without `--md5sig`) | EXPECTED | zapret fake md5sig ttl=3 (= `ttl3` on GKI) |
| `fakesplit5` | `--proto=tls,http --fake 2 --fake -1 --ttl 5 --fake-sni www.w3.org` | EXPECTED; wire: `ttl5/2B` + `ttl5/455B` continuing at fake byte 2 | zapret2 multisplit blob=fake pos=2: one coherent fake cut at 2 (patch B8) |
| `zerofake` | `--proto=tls,http --fake -1 --ttl 5 --fake-data :\x00\x00\x00\x00` | EXPECTED; wire: all-zero `ttl5/457B` | zapret2 fake blob=0x00000000; the fake is zero-padded to the range length |
| `split2` | `--proto=tls,http --split 2` | **200 / 200 / 200** | zapret multisplit pos=2 |
| `split` | `--proto=tls,http --split 2 --split 0+hm` | **200 / 200 / 200** | desktop "Sadece bölme" (split@2 + SNI middle) |
| `tlsrec` | `--proto=tls,http --tlsrec 3+s` | **200 / 200 / 200** | Android-only, replaces `checksum` in Genel. Defeats per-packet SNI matching (verified with the DPI simulation) |

The emulator results are for `https://example.com`, `https://www.google.com` and
`http://example.com`. `argv parsed + proxy up` passed for all 12 presets.

### 7.3 Full argv layout (verified to parse and to recover on the emulator)

This is the layout with **smart mode off** (the v1.0.0 behaviour). The default since
`settingsVersion` 2 is smart mode, §7.3.1: the same argv with a no-desync group in front, so the
selected method becomes the first fallback.

```
-i 127.0.0.1 -p <port> -c 2048 -b 16384 -N
[DNS active]   --redirect 198.18.0.53:53=<v4>:<port>
               [--redirect [fd00:6764:7069::53]:53=[<v6>]:<port>]     (only if the profile has v6)
               --deny-net 198.18.0.0/15 --deny-net fd00:6764:7069::/48   (always)
[blockQuic]    --drop-udp 443
[voiceFake]    --proto=udp --pf=50000-65535 --udp-fake <voiceFakeRepeats> --ttl 64 --auto=none
               --proto=udp --pf=3478-3481   --udp-fake <voiceFakeRepeats> --ttl 64 --auto=none
               --proto=udp --pf=19294-19344 --udp-fake <voiceFakeRepeats> --ttl 64 --auto=none
<primary TCP group (7.1)>
[autoFallback, per fallback method not equal to the primary or to an earlier fallback]
               --auto=torst,ssl_err <its TCP group (7.1)> --cache-ttl 3600
[autoFallback and >=1 fallback emitted]
               --timeout 4:0:0:1
```

Reasoning:

* **Order matters.**
  * The UDP voice groups must come **before** the primary TCP group. A static group between the
    primary and its fallbacks would end the chain (§2.1.4).
  * `--auto=none` is only a separator; the UDP groups never match TCP.
  * Fallback groups follow the primary directly. byedpi appends the catch-all group itself, because
    every group closed by `--auto` has `--proto`.
* **One `--pf` range per group**, so the three voice ranges are three groups.
* **Voice `--ttl 64`**, not the method TTL:
  * The desktop sends its voice fakes with the original packet's TTL (`PacketProcessor.HandleVoice` →
    `BuildUdpFake` copies the IP header), and that was verified live on Turkish ISPs.
  * Zero-byte datagrams are ignored by Discord.
  * A low TTL would only risk the DPI never seeing them.
  * `--udp-fake` needs *some* TTL; 64 equals the Android default, so nothing changes on the wire.
  * If you prefer the SPEC's `--ttl <ttl>`, it also works; it is just weaker.
* **Detection** `torst,ssl_err`, not `redirect`. A legitimate cross-domain HTTP redirect (for
  example URL shorteners) would trigger, cycle through all groups and end "unreach" on every
  connection to that IP.
* **`--cache-ttl 3600`** on each fallback group: the working method is remembered per IP:port for
  an hour, and after that the primary is retried.
* **`--timeout 4:0:0:1`**: 4 s is enough for the ACK of a ClientHello even on poor mobile links
  (TCP_USER_TIMEOUT only counts un-ACKed *sent* data). `:0:0:1` disables it as soon as the server
  answered, so long-lived connections are safe. Emit it only with fallback groups; without them an
  ETIMEDOUT just kills the connection. Since patch B7 byedpi itself arms it only for client-first
  connections in a group that has a `torst` successor (the catch-all and the last fallback never get
  it; SMTP/IMAP-style server-first connections never get it).
* **`-N`** and **`--deny-net`**: see §3 and §6 (contract C2).
* Group count is 3 (voice) + 1 + fallbacks + 1 (catch-all), and must stay ≤ 64.
* Emitted as separate argv elements, with no shell quoting (the JNI passes them verbatim).

Literal example (the default preset, Yandex DNS, fallbacks disorder / split / tlsrec). This is
exactly `FULL_LAYOUT` in `tools/native/smoke.py`:

```
-i 127.0.0.1 -p 10808 -c 2048 -b 16384 -N
--redirect 198.18.0.53:53=77.88.8.8:1253 --redirect [fd00:6764:7069::53]:53=[2a02:6b8::feed:0ff]:1253
--deny-net 198.18.0.0/15 --deny-net fd00:6764:7069::/48
--drop-udp 443
--proto=udp --pf=50000-65535 --udp-fake 6 --ttl 64 --auto=none
--proto=udp --pf=3478-3481 --udp-fake 6 --ttl 64 --auto=none
--proto=udp --pf=19294-19344 --udp-fake 6 --ttl 64 --auto=none
--proto=tls,http --disorder 2 --split 0+hm --fake -1 --ttl 5 --fake-sni www.w3.org
--auto=torst,ssl_err --proto=tls,http --disorder 2 --cache-ttl 3600
--auto=torst,ssl_err --proto=tls,http --split 2 --split 0+hm --cache-ttl 3600
--auto=torst,ssl_err --proto=tls,http --tlsrec 3+s --cache-ttl 3600
--timeout 4:0:0:1
```

Results:

* On the emulator: the fake primary is broken by slirp, the server resets, `torst` fires, and group 4
  (`disorder`) is saved. `https://example.com` and `https://www.google.com` return 200.
* `http://example.com` returns 200 or 400, depending on the run. The fake HTTP request reached the
  server through slirp. If the server resets, `torst` recovers the request (200). If it answers
  `400` first, that is not a trigger, and the 400 is passed through. This is emulator-only.
* Under the RST DPI simulation it recovers through groups 4 → 5.

### 7.3.1 Smart mode layout (default since `settingsVersion` 2)

`AppSettings.smartMode` (default on, "Akıllı mod" in Ayarlar → GENEL) changes only the TCP part:
the first TCP group applies **no desync**, and the selected method becomes the first fallback.
With smart mode off, `ByeDpiArgs` emits exactly the §7.3 layout (unit-tested).

```
<base, --redirect, --deny-net, --drop-udp, voice UDP groups: unchanged>
--proto=tls,http                                                   (direct group: scope only, static)
--auto=torst,ssl_err <selected method's TCP group (7.1)> --cache-ttl 3600
[autoFallback, per ISP method not equal to an earlier group]
--auto=torst,ssl_err <its TCP group> --cache-ttl 3600
--timeout 4:0:0:1                                                  (whenever >= 1 fallback group)
```

Literal example (`SMART_LAYOUT` in `tools/native/smoke.py`, `smartModeStartsDirectAndUsesMethodAsFirstFallback`
in `ByeDpiArgsTest`): the §7.3 example with `--proto=tls,http` inserted before the default group
and `--auto=torst,ssl_err` in front of it:

```
... --proto=udp --pf=19294-19344 --udp-fake 6 --ttl 64 --auto=none
--proto=tls,http
--auto=torst,ssl_err --proto=tls,http --disorder 2 --split 0+hm --fake -1 --ttl 5 --fake-sni www.w3.org --cache-ttl 3600
--auto=torst,ssl_err --proto=tls,http --disorder 2 --cache-ttl 3600
--auto=torst,ssl_err --proto=tls,http --split 2 --split 0+hm --cache-ttl 3600
--auto=torst,ssl_err --proto=tls,http --tlsrec 3+s --cache-ttl 3600
--timeout 4:0:0:1
```

Türk Telekom (`ttl4`, fallbacks on): `--proto=tls,http --auto=torst,ssl_err --proto=tls,http --fake -1
--ttl 4 --fake-sni www.w3.org --cache-ttl 3600 --auto=torst,ssl_err --proto=tls,http --disorder 2
--cache-ttl 3600 --auto=… ttl3 … --auto=… default … --timeout 4:0:0:1`. With `autoFallback` off
only the method group follows the direct group (plus `--timeout`).

Why this is correct in byedpi (code, `extend.c`, and verified below):

* **New connections land in the direct group.** `connect_hook` → `find_dp` takes the first
  *static* group whose `check_l34` passes; the voice groups fail it for TCP and are added to
  `dp_mask`; auto groups are never picked for a new connection (§2.1.3). At the first request
  `setup_conn` → `find_dp` checks `--proto`: a ClientHello / HTTP request stays in the direct
  group; anything else walks on to the auto-appended catch-all (every `--auto` group has
  `--proto`), exactly as before.
* **An empty group sends the request unchanged.** `desync()` with `parts_n == 0` writes the
  buffer as is (smoke: `desync TCP: group=3` and no `save:` for example.com, www.google.com and
  plain HTTP; HTTP now returns 200 on the emulator instead of the fake-caused 400).
* **The chain rule of §2.1.4 still holds**: the fallbacks follow the direct group directly and
  nothing static sits between them. `on_trigger` walks from the head: the voice groups and the
  direct group are in `dp_mask` (tried/skipped), so the first untried group whose mask matches the
  trigger is the method group.
* **The replay is transparent.** Any detect flag sets `auto_reconnect`, so `tcp_recv_hook` keeps
  the client's first request (up to `-b` = 16384 bytes, also across several reads) in `sq_buff`
  until the server answers. `on_torst`/`on_response` → `on_trigger` → `reconnect()` copies it back
  into the client buffer, opens a new upstream socket with the next group and resends it; the app
  sees one connection. After the server has answered (DPI RST after the ServerHello) there is
  nothing to replay: that connection fails and the cache moves the next one (B7 still requires
  round 1).
* **`--timeout`** is armed by `setup_conn` because the direct group's `next` has `DETECT_TORST`
  (patch B7), so a silently dropped ClientHello costs 4 s once. It is removed as soon as the
  server sends a byte (`:1`), and never armed for server-first protocols.
* **Cache**: the working group is stored per destination IP:port (`cache_add` in `on_trigger`) for
  the method group's `--cache-ttl` (1 h); later connections start in it (`connect_hook` →
  `cache_get`), unblocked IPs never get an entry. "unreach" (every group failed) resets the entry
  to the head, so the next connection starts direct again. Since patch B9 (§5.1), an app giving
  up before the server answers no longer counts as a block, and a method group that fails with
  `ssl_err` and has no successor ends in "unreach" instead of staying cached for an hour.
* **Group count** grows by one (≤ 3 voice + 1 direct + 1 method + ≤ 3 ISP alternatives +
  catch-all = 9 ≪ 64).

Trade-offs (documented to the user in README "Sorun giderme"):

* Only `torst` and `ssl_err` detect a block. A DPI that answers a blocked plain-HTTP request with
  its own warning page (200/302) is not detected; `redirect` stays off for the reason in §7.3.
  Turkish blocking of HTTPS is by RST (dev machine, desktop live tests) or silent drop.
* A blocked host costs one replay (RST: one extra TCP handshake; measured on the emulator, the
  first blocked request is within ~0.5 s of the cached ones) or 4 s (silent drop: first request
  7.7 s vs 0.9 s cached, including the probe's cold start) once per IP:port per hour and per
  engine run.
* **No built-in "never desync" host list.** byedpi could do it (`check_host` matches `--hosts`
  against the TLS SNI / HTTP Host in `setup_conn`, so it works with hev's IP-only CONNECTs), but
  the direct group already leaves unblocked hosts alone, and an exemption group must be static,
  which *ends* the fallback chain (`on_trigger` stops at the first untried static group →
  "unreach"): a future block of an exempted service (YouTube was blocked in Turkey 2008-2010)
  could then not be bypassed at all. It would also be bypassed for any IP already cached by
  another SNI (the cache is per IP:port, `find_dp` starts at the cached group).

### 7.4 Why the ISP lists recommend a fake method first (wave 3)

* The desktop "Ters sıra" is zapret multidisorder pos=2 **with seqovl=1**: the tail segment is sent
  first with one garbage byte in front and SEQ pulled back by one, so a DPI that keeps the first copy
  of each byte sees a corrupt ClientHello, while the server keeps the real byte. A kernel TCP socket
  cannot do that: the kernel assigns sequence numbers and never sends two different contents for
  the same range. byedpi's `--disorder` only delays the 2-byte head (TTL 1); the tail with the whole
  SNI goes out in one normal segment. `--disoob` would inject an in-band byte that some servers keep.
* The effect seqovl relies on, "the DPI sees a first copy that never reaches the server", is exactly
  what a TTL-limited `--fake` does. Live tests on a Turkish line (desktop) showed every fake-based
  method working and plain split without fake/seqovl failing; the Android disorder has never been
  tried on a Turkish line.
* So every ISP list now starts with the first fake method SplitWire chose for that ISP (a 1:1 byedpi
  equivalent) and keeps `disorder` as the first fallback: TT `ttl4`, Superonline `ttl3` (MD5 is TTL
  only on GKI, so `md5sig` = TTL 5 fake comes next, `md5ttl3` last for MD5-capable kernels), Vodafone
  `fakesplit5`, TürkNet `default`, Kablonet `ttl4`, TT Mobil `zerofake`, Turkcell Mobil `default`,
  Vodafone Mobil `fakesplit5`.
* Failure modes are covered by the automatic fallback: a fake TTL too low for the DPI → the DPI
  resets or drops → `torst` → next method; a server closer than the TTL → the server answers the
  fake → `ssl_err` (TLS 1.3 by session id, TLS 1.2 by patch B7) → next method on the next connection.
* Unverified: the efficacy of every mapping on real Turkish networks (the emulator's slirp NAT
  re-originates TCP, and the host already runs the desktop bypass).
* The "server closer than the TTL" case was only covered after the fact by `ssl_err`: a user
  could not use Google search. On the emulator www.google.com answers the `ttl4` fake (2.9 KB
  server flight, `save: id=1`) and v1.0.0 only recovered by replaying with `disorder`; with
  `autoFallback` off, or when the next methods are fakes too / blocked, the site stays broken.
  Since smart mode (§7.3.1) the fake is only ever sent to a host whose direct connection was
  reset / timed out / not answered, so unblocked nearby servers never see it.

## 8. Restartability and leaks (verified)

State that survives a run, and what happens to it (`main.c` lib prologue, `clear_params`,
`proxy.c:start_event_loop`/`destroy_pool`):

| state | handling |
|---|---|
| `params` (modes, `def_ttl`, `baddr` family, flags, `dp` list…) | restored from a pristine copy taken on the first call |
| groups, parts, tlsrec, fake data, fake SNI lists, hosts/ipset trees | freed in `clear_params` (upstream) |
| `need_free` array, `redirects`, `drop_udp` arrays | freed. The `need_free` array leaked upstream; this is patched |
| cache (`params.mempool`) | freed; empty on every start |
| epoll pool, all client/upstream fds, buffers, fake mmaps, host strings | `destroy_pool` at loop exit |
| fake mmap when a fake part is empty or the pipe fails | **patched**: upstream leaked one mapping per such request |
| fake mmap with an offset (`restore_fake` inside the mapping, B8) | `restore_fake_base` is unmapped, never the offset pointer |
| getopt | `optind = 1; optreset = 1` (bionic) |
| `server_fd` | only written; stop uses the eventfd |
| eventfd | owned by `byedpi_lib.c`, closed under the mutex when the run ends |
| `md5_unsupported` (static in `desync.c`) | intentionally process-wide: a kernel property |
| `srand(time)` per start, `fake_tls`/`fake_http`/`fake_udp` | never modified (copies are made) |

Measurements:

* `restart_test` ran 50 start/request/stop cycles (TCP echo, TLS-like fake path, UDP redirect +
  label, `-U` alternation) in about 1.8 s. After the loop, fds and memory mappings were unchanged
  (8 fds, 158 maps) and RSS grew by 128 kB (3456 → 3584 kB).
* After the race suite as well: fds 8 → 8, maps 156 → 158 (bionic caches a thread stack), RSS
  3328 → 3456 kB. These are numbers from the final smoke run.
* It then ran the race suite: 200 stop-during-start, double stop, 8 concurrent stops, busy, 10
  bad-argument cases, port taken, and 500 rapid start/stop cycles. All 541 checks passed, and fds
  ended equal to the baseline.
* The ASan build passes the same (537 checks) with no report.

## 9. Verification (real runs)

1. **NDK, standalone module, 4 ABIs.** This builds `byedpi-jni/Android.mk` alone:
   `ndk-build NDK_PROJECT_PATH=null APP_BUILD_SCRIPT=…/byedpi-jni/Android.mk APP_ABI="arm64-v8a armeabi-v7a x86_64 x86" APP_PLATFORM=android-24`
   * 0 warnings.
   * Every LOAD segment is `Align 0x4000`.
   * NEEDED is liblog, libc, libm and libdl only.
   * The only exported symbol is `JNI_OnLoad`.
   * Sizes: 76 432 B (arm64-v8a), 54 820 B (armeabi-v7a), 79 120 B (x86_64), 73 404 B (x86).
2. **Top-level `jni/Android.mk` + `Application.mk`, 4 ABIs.** It builds `libbyedpi.so` and
   `libhev-socks5-tunnel.so`, with 0 warnings and 0x4000 alignment. `NDK_DEBUG=1` is also clean.
3. **Gradle.**
   * `gradlew :app:assembleDebug -Pgdpi.abi=x86_64` passed, and the APK has
     `lib/x86_64/libbyedpi.so`.
   * `gradlew :app:assembleRelease` passed. The APK has `libbyedpi.so` for all 4 ABIs, stored
     uncompressed. `dexdump` shows `NativeBridge.byedpiStart/byedpiStop` as
     `PUBLIC STATIC FINAL NATIVE`, with names kept.
4. **`py -3 android/tools/native/smoke.py --apk <release apk>`** runs on the host against
   emulator-5554. The full table is in the final report of this wave.
   * All 12 presets parse and come up.
   * The non-fake presets pass all 3 URLs; the fake presets are EXPECTED.
   * 7/7 wire checks, 8/8 DPI-simulation checks, 17/17 UDP/redirect/drop/malformed checks, 13/13
     JNI checks, and restart_test + ASan all pass.
   * On Windows use `py -3`: the WindowsApps `python` alias cannot see `%LOCALAPPDATA%\Android`.
5. **Wave 3 (patches B5–B8 and hev P5), emulator-5554, 2026-09-29.**
   * Builds: top-level `jni/` 4 ABIs 0 warnings, every LOAD `Align 0x4000`; standalone
     `byedpi-jni` 4 ABIs 0 warnings, only export `JNI_OnLoad`; tools x86_64 0 warnings.
   * `smoke.py` (full): 73 checks, 0 FAIL, 8 EXPECTED (one earlier run had two wire rows capture no
     packets; the cause was later found to be libpcap's TPACKET_V3 block buffering, not a start
     race, and `Capture` now runs tcpdump with `--immediate-mode`). `smoke.py --quick --apk <debug apk>`:
     68 checks, 0 FAIL, 13/13 JNI. The curl calls now use `--socks5 -4` because the proxy runs with
     `-N`.
   * New rows: `fakesplit5 coherent fake` (`ttl5/2B ttl5/455B`, 2nd segment starts `01 01 c5`);
     `fake reached tls1.2 server (abort)` and `(alert)` (host server answers the fake with a TLS 1.2
     ServerHello; saves `['1']`, the next connection uses group 1); `timeout with stall
     (client-first)` torst fired, `(server-first)` no torst and the connection stays up;
     `mid-life RST keeps primary` (no `save:`, groups `['0','0']`); `tcp_deny_virtual_v4/v6` reply 02 in
     9/5 ms; `udp_deny_virtual_silent`, `udp_deny_then_dns`, `no_domain_atyp3_refused` (08).
   * `restart_test` 589 checks PASS (fds 8 → 8), ASan build 585 checks PASS; new R8: with a low
     `RLIMIT_NOFILE` the listener hits EMFILE, the proxy keeps running and serves again once fds are
     freed; `--deny-net` bad values rejected with -2; deny-net reply 02 in every full iteration.
   * Before the B7 `ssl_err` extension a fake-to-near-TLS-1.2-server case was also reproduced with Windows curl
     (schannel `-k`) against `openssl s_server -tls1_2`: all three attempts failed with no fallback;
     after: first attempt fails, the next two return 200 through the cached fallback.

6. **Smart mode (§7.3.1), emulator-5556 (`gdpi_r`), 2026-09-30.**
   * `smoke.py --apk <debug apk>`: new rows `smart layout argv+up`, `smart layout example.com /
     www.google.com / example.com (http)` (200/0.6 s, 200/1.5 s, 200/0.6 s), `smart layout: direct
     group only` (`groups ['3'] saves []`), `rst: smart layout recovers + cache` (see §5); all
     earlier rows unchanged. Total 93 checks, 0 FAIL, 8 EXPECTED (the fake presets), 13/13 JNI,
     restart_test 589 + ASan 586 checks.
   * App through the VPN (`:probe`, HttpURLConnection, Yandex DNS; slirp delivers every fake, like
     a server closer than the fake TTL):
     * v1.0.0 argv (`smartMode=false`), Genel/`default`, no fallback: www.google.com/search,
       youtube.com, example.com, discord.com all fail (RST / decode_error alert), 3/3 runs.
     * Every preset, `autoFallback` off (Google search / YouTube / example.com): smart mode off →
       all 8 fake presets (`default fixedttl ttl4 ttl3 md5sig md5ttl3 fakesplit5 zerofake`) fail
       all three ("Connection reset"), the 4 non-fake ones pass; smart mode on → all 12 presets
       200 on all three.
     * v1.0.0 argv, Türk Telekom/`ttl4` with fallbacks: Google 200 in 1.2-3.1 s, but only via
       replay (ciadpi `-x 1`: group 0 `save: id=1` after the server answered the fake with
       2.9 KB, then group 1 `disorder`); smart layout: only group 0 (direct), 1.0-1.1 s vs 2.3 s
       for the first request. discord.com failed 6/6 (~20 s): the fake was answered
       (`ssl_err`), and the dev host's own line resets discord.com for `disorder`/`default`
       (the direct path works there only because the host runs the desktop bypass), so the
       chain ended "unreach" — a mixed case, not a pure near-server one.
     * smart mode, same settings: google.com/search and discord.com 200 6/6 (1.2-1.9 s); every
       ISP preset tried (Genel `default` with and without fallbacks, TT `ttl4`) 200 on all four
       sites.
     * Simulated DPI on the upstream side only (`iptables -I OUTPUT ! -o tun+ -p tcp --dport 443
       -m string --string example.com --algo bm -j REJECT --reject-with tcp-reset`, or `-j DROP`;
       it matches an unsplit ClientHello like a non-reassembling DPI): smart + `disorder` (SNI
       still in one segment), no ISP fallbacks: example.com "Connection reset" 2/2 (so the direct
       group really is blocked) while Google stays 200; smart + `tlsrec`, no ISP fallbacks:
       example.com 200 5/5, Google 200 5/5. RST: first request 2.2 s, then 0.8-1.05 s from the
       cache (Google's first request in the same run: 2.4 s vs 1.6 s, i.e. ~0.7 s of the first
       is the probe's cold start). DROP: first 7.3 s (4 s `--timeout`), then 0.8-1.06 s. The same
       with `discord.com` as the string and Genel fallbacks: 200 4/4, first request 0.68 s in a
       parallel run (example.com 0.60 s).

7. **Patch B9 (smart-mode cache hardening), emulator-5554, 2026-09-30.**
   * **Builds:**
     * Top-level `jni/`, 4 ABIs: 0 warnings, every LOAD `Align 0x4000`.
     * Standalone `byedpi-jni`, 4 ABIs: 0 warnings. The only export is `JNI_OnLoad`. Sizes:
       81 024 B (arm64-v8a), 57 980 B (armeabi-v7a), 83 472 B (x86_64), 78 084 B (x86).
     * Tools, x86_64: 0 warnings.
   * **Full `smoke.py --apk <debug apk, x86_64>`:** 95 checks, 0 FAIL, 9 EXPECTED, 13/13 JNI.
     * The 3 new `cache` rows pass (§5.1).
     * `restart_test` passes 589 checks and the ASan build 585 (fds 8 → 8).
     * The earlier rows are unchanged, except for timing noise: in the full runs,
       `full layout example.com (http)` came back EXPECTED 400 instead of 200. That is the slirp
       race between the fake and the real HTTP request. HTTP never reaches the B9 code (`mark` is
       0 and the primary group has `detect == 0`). An A/B check of the same three URLs on one
       proxy, 4 × with each binary, gave 200/200/200 with `saves ['4','4','4']` for both. A
       `--quick` run of the B9 build gave 63 checks, 0 FAIL, 8 EXPECTED, with HTTP 200.
     * One full run hit a single `recv: Software caused connection abort` (ECONNABORTED, a socket
       destroyed by the system while the emulator had just been restarted) in `timeout 3s /
       cache hit`. The rerun passed.
   * **The pre-B9 build**, same host servers (`--quick`): the 3 `cache` rows FAIL, as expected.
   * **ASan:** `ciadpi_asan` ran all three cache scenarios repeatedly (unpin ×2, unreach ×5):
     it stayed alive with no ASan report.
   * **Google reproduction:** see §5.1. Before B9, abort and stall with fallbacks off each broke
     4/4 later requests. After B9 they gave 4/4 200 from the direct group.

## 10. Known limitations / decisions

* **TCP half-close is not supported (upstream, kept).**
  * `proxy.c`/`extend.c` treat `recv()==0` from either side as a full close (`tcp_recv_hook` →
    `on_fin` → both sockets closed).
  * A client that sends its request and then `shutdown(SHUT_WR)`s gets no response. hev forwards an
    app's FIN like that; it was seen with `nc`.
  * Browsers, OkHttp and TLS clients do not half-close.
  * When the *server* closes, the data already handed to the client socket is still delivered: the
    kernel sends it before the FIN.
  * Adding half-close would need per-direction EOF state through `on_tunnel`, `tcp_recv_hook`,
    `on_fin` (which also drives `ssl_err` detection) and the auto-reconnect replay. That is not a
    minimal patch, and a mistake there leaks half-open fds in a long-lived process. Not patched.
* **`--fake-offset`** is not used. Its old unaligned-`munmap` leak is gone since patch B8.
* `-y/--cache-file` and `-P/--protect-path` work but are not needed. `-D`/`--pidfile` do not
  exist in lib mode.
* **Unverified**: the DPI efficacy of any method on real Turkish networks, and `TCP_MD5SIG` on
  non-GKI (vendor) kernels. On such kernels the MD5 option is actually added.
