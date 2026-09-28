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
| -4 | exited without a stop request | fatal `accept()` error such as EMFILE, `epoll_wait` failure, or `-h`/`-v` passed |
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
* **Stop latency** is immediate. It is bounded only when the loop is inside a blocking
  `getaddrinfo`, which happens only for SOCKS requests that carry domain names. hev always sends IPs.
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
  * `--redirect`, `--drop-udp`, `-W`, `-Z`, `/`
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
| `torst` (`t…`) | `DETECT_TORST` | upstream `recv`/`send` fails with ECONNRESET, ECONNREFUSED, ETIMEDOUT or EHOSTUNREACH (`handle_err` → `on_torst`), including the `--timeout` expiry (ETIMEDOUT from `TCP_USER_TIMEOUT`) and the partial-TLS timer (`on_timeout`) |
| `redirect` (`r…`) | `DETECT_HTTP_LOCAT` | first HTTP response is a 30x whose `Location` points to a *different* registrable domain (`on_response` → `packets.c:is_http_redirect`) |
| `ssl_err` (`s…`) | `DETECT_TLS_ERR` | first response to a TLS ClientHello is not a ServerHello, or its session id differs (`on_response`); or the server closes before answering (`on_fin`, only while `mark` is set, round 1) |
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
| `--fake POS` `-f` | the segment `[prev, POS)` is first sent with **fake bytes** and low TTL (§4). The real bytes follow as a kernel retransmission | desync.c:send_fake | verified (wire) |
| `--oob POS` `-o`, `--disoob` | segment + 1 urgent byte (`--oob-data`, default `a`). **Not used**: the server may see an extra byte | desync.c:send_oob | code |
| `--ttl N` `-t` | TTL of fakes (TCP and UDP) in this group, 1..255. **Without `--ttl`, fakes use TTL 8** (`desync.c DEFAULT_TTL`) | desync.c:send_fake/desync_udp | verified (wire `ttl8/457B`, UDP `ttl 8`) |
| `--md5sig` `-S` | add TCP MD5 option to fake segments. **The Android GKI kernel has no `TCP_MD5SIG`** (`setsockopt: Protocol not available`, ENOPROTOOPT). Upstream then closed every connection of the group. **Patched**: logs once and continues with TTL only | desync.c:send_fake | verified (wire shows the TTL fake, connection not killed) |
| `--fake-sni NAME` `-n` | SNI written into the TLS fake. `?` = random letter, `#` = digit, `*` = letter/digit. Repeatable (random pick). It also **resizes the fake ClientHello to the real request's length** (`change_tls_sni`), so always emit it for TLS fakes | desync.c:get_tcp_fake, packets.c:change_tls_sni | verified (wire: `www.w3.org`, record len = real len − 5) |
| `--fake-data :STR` / `FILE` `-l` | custom fake payload for TCP fakes **and** UDP fakes of this group; only the first `-l` per group counts. `:` means inline, with C escapes (`main.c:parse_cform`): `\r \n \t \\ \f \b \v \a`, `\xHH`, `\OOO` (octal, up to 3 digits). An unknown `\c` gives `c`. **4 zero bytes = `:\x00\x00\x00\x00`** (or `:\0\0\0\0`). This is a single argv element with literal backslashes, no shell (Kotlin: `":\\x00\\x00\\x00\\x00"`) | main.c:ftob/data_from_str | verified (wire: all-zero fake) |
| `--tlsrec POS` `-r` | split the ClientHello *TLS record* into two records (a 5-byte header is inserted). Plain POS is an offset in the record payload. `N+s` is the SNI start + N, `0+sm` the SNI middle. Only applied to TLS ClientHellos; a CH without SNI with `+s` is cancelled (logged `tlsrec cancel`) | desync.c:tamp, packets.c:part_tls | verified (wire: record1 len 129, record2 header at 134) |
| `--udp-fake N` `-a` | before each client datagram of **round 1** (all datagrams until the first reply), send N fake datagrams (`--fake-data` or 64 zero bytes) with TTL `--ttl` (default 8), then the real one with the normal TTL | desync.c:desync_udp, extend.c:udp_hook | verified (`udp_fake_default_ttl8`: `64/ttl8,64/ttl8,15/ttl64`) |
| `--auto=…` `-A` | see §2 | main.c, extend.c | verified |
| `--timeout S[:P[:C[:B]]]` `-T` | **Global.** S seconds (float) → `TCP_USER_TIMEOUT` = S·1000 ms on every upstream socket when its first request is sent (`extend.c:setup_conn`). If sent data stays un-ACKed that long, the kernel aborts with ETIMEDOUT, which triggers `torst`. **B** bytes: once the server sent more than B bytes, the timeout is removed (`tcp_recv_hook`). **Always set B ≥ 1**, otherwise a mobile stall longer than S kills long-lived connections later *and* caches a fallback for that IP. P = partial-TLS-record timer seconds (0 = off); C = number of P expiries before `torst` | main.c case 'T', extend.c | verified (3 s → first request 4.7-6.2 s via fallback) |
| `--cache-ttl SEC` `-u` | per group. On a trigger, the chosen fallback group is cached per **destination IP + port** (`extend.c:cache_add`). Later connections to that IP:port start directly at that group. An entry expires after the *cached group's* `--cache-ttl` seconds; 0 or absent means it lives until the proxy stops. **Put it on every fallback group.** **Patched**: expiry deleted a random IPv4 entry (length passed in bytes instead of bits) | extend.c:cache_get/cache_add | verified (5 s: hit 0.6 s, then after 7 s slow again) |
| `--def-ttl N` `-g` | sets TTL on *all* outgoing sockets. **Leave unset**: `def_ttl` is then read from a socket (64) and used only to restore after fakes | main.c:init, extend.c:socket_mod | code |
| `--redirect FROM=TO` | **ours.** Repeatable; `ip:port` or `[ipv6]:port` on both sides, and both ports are required. See §6 | main.c:parse_redirect, proxy.c | verified |
| `--drop-udp P[-Q]` | **ours.** Repeatable, decimal, 1..65535, P ≤ Q. See §6 | main.c:parse_drop_udp, proxy.c | verified |
| `-U` / `-N` | no UDP / no domain resolving. **Do not use** (hev needs UDP; the diagnostics may send domains) | main.c | verified (`-U`) |

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
* Every fake part restarts from byte 0 of the fake payload. `--fake 2 --fake -1` puts `16 03` in the
  first segment and then a whole fake record again (wire-verified).
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

Timing guidance:

* `--timeout 4:0:0:1` means a blocked host costs about 4 s once, then it is cached.
* The cache is per proxy run. Every engine restart (settings change) starts it empty.

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
    if (fakeMd5Sig) parts += ["--md5sig"]      // GKI: no TCP_MD5SIG -> patched to TTL only
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
  MD5 plus TTL. On GKI it is TTL only. We deliberately do *not* use `--ttl 64` for "MD5 only":
  without kernel MD5 the fake would reach the server and break every connection.
* `splitFake` together with `splitTls` is not emitted. The fake split at 2 would sit behind the
  split at `splitPosition` and be cancelled.
* `splitPosition` must stay ≥ 1 (`DpiConfig.SPLIT_RANGE`). With HTTP, a split position beyond the
  Host header cancels the parts after it. That is harmless.

### 7.2 Preset table (TCP group, `fragmentHttp` on)

| id | TCP group argv | emulator result | reasoning |
|---|---|---|---|
| `default` | `--proto=tls,http --disorder 2 --split 0+hm --fake -1 --ttl 5 --fake-sni www.w3.org` | fake → EXPECTED; wire verified | desktop: fake TTL + split@2 reversed + SNI split. Disorder = reversed split; `0+hm` = SNI/Host middle; fake covers the rest |
| `fixedttl` | `--proto=tls,http --fake -1 --ttl 5 --fake-sni www.w3.org` | EXPECTED | GoodbyeDPI-Turkey `--set-ttl 5` |
| `disorder` | `--proto=tls,http --disorder 2` | **200 / 200 / 200** | zapret multidisorder pos=2 (seqovl impossible without root) |
| `ttl4` | `--proto=tls,http --fake -1 --ttl 4 --fake-sni www.w3.org` | EXPECTED | zapret fake ttl=4 |
| `ttl3` | `--proto=tls,http --fake -1 --ttl 3 --fake-sni www.w3.org` | EXPECTED | zapret fake ttl=3 |
| `md5sig` | `--proto=tls,http --fake -1 --ttl 5 --md5sig --fake-sni www.w3.org` | EXPECTED; wire: TTL-only fake | zapret fake md5sig; md5 is unavailable on GKI, so it degrades to TTL 5 |
| `md5ttl3` | `--proto=tls,http --fake -1 --ttl 3 --md5sig --fake-sni www.w3.org` | EXPECTED | zapret fake md5sig ttl=3 (TTL 3 only on GKI) |
| `fakesplit5` | `--proto=tls,http --fake 2 --fake -1 --ttl 5 --fake-sni www.w3.org` | EXPECTED; wire: `ttl5/2B` + `ttl5/455B` | zapret2 multisplit blob=fake pos=2. Closest: two fake segments; byedpi restarts the fake payload in each |
| `zerofake` | `--proto=tls,http --fake -1 --ttl 5 --fake-data :\x00\x00\x00\x00` | EXPECTED; wire: all-zero `ttl5/457B` | zapret2 fake blob=0x00000000; the fake is zero-padded to the range length |
| `split2` | `--proto=tls,http --split 2` | **200 / 200 / 200** | zapret multisplit pos=2 |
| `split` | `--proto=tls,http --split 2 --split 0+hm` | **200 / 200 / 200** | desktop "Sadece bölme" (split@2 + SNI middle) |
| `tlsrec` | `--proto=tls,http --tlsrec 3+s` | **200 / 200 / 200** | Android-only, replaces `checksum` in Genel. Defeats per-packet SNI matching (verified with the DPI simulation) |

The emulator results are for `https://example.com`, `https://www.google.com` and
`http://example.com`. `argv parsed + proxy up` passed for all 12 presets.

### 7.3 Full argv layout (verified to parse and to recover on the emulator)

```
-i 127.0.0.1 -p <port> -c 2048 -b 16384
[DNS active]   --redirect 198.18.0.53:53=<v4>:<port>
               [--redirect [fd00:6764:7069::53]:53=[<v6>]:<port>]     (only if the profile has v6)
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
  ETIMEDOUT just kills the connection.
* Group count is 3 (voice) + 1 + fallbacks + 1 (catch-all), and must stay ≤ 64.
* Emitted as separate argv elements, with no shell quoting (the JNI passes them verbatim).

Literal example (the default preset, Yandex DNS, fallbacks disorder / split / tlsrec). This is
exactly `FULL_LAYOUT` in `tools/native/smoke.py`:

```
-i 127.0.0.1 -p 10808 -c 2048 -b 16384
--redirect 198.18.0.53:53=77.88.8.8:1253 --redirect [fd00:6764:7069::53]:53=[2a02:6b8::feed:0ff]:1253
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
* **`--fake-offset`** would leak one mmap per fake (`munmap` on a non-page-aligned address), so it
  is not used.
* `-y/--cache-file` and `-P/--protect-path` work but are not needed. `-D`/`--pidfile` do not
  exist in lib mode.
* **Unverified**: the DPI efficacy of any method on real Turkish networks, and `TCP_MD5SIG` on
  non-GKI (vendor) kernels. On such kernels the MD5 option is actually added.
