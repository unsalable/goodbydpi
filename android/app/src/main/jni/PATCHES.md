# Vendored native sources and local patches

This file records the upstream revisions and every local patch of the native components under
`android/app/src/main/jni/`: hev-socks5-tunnel (first section, originally `PATCHES.hev.md`) and byedpi
(second section).

---

# hev-socks5-tunnel — vendored sources and local patches

Fragment of `PATCHES.md` (the lead merges it). Directory: `android/app/src/main/jni/hev-socks5-tunnel/`.

## Upstream revisions

| path in tree | repository | commit (full SHA) | note |
|---|---|---|---|
| `.` | https://github.com/heiher/hev-socks5-tunnel | `d9dca26c7ad0e494492244f0309e80ee583e739e` | tag **2.18.0** = `main` HEAD on 2026-09-27 ("HevConfig: Bump up to 2.18.0") |
| `src/core` | https://github.com/heiher/hev-socks5-core | `ab2a15a88463e3327ec75a41a975e33a72c45b10` | SPEC pin. 2.18.0's gitlink is `162dd996299fc2d2bff2dd63728f8a2cd71ed31a`; `162dd99..ab2a15a` changes **only README.md** (checked with the GitHub compare API), so the code is identical |
| `third-part/hev-task-system` | https://github.com/heiher/hev-task-system | `328f35d903221b51811b3d02b277d665dfbdc75f` | same as the 2.18.0 gitlink |
| `third-part/lwip` | https://github.com/heiher/lwip | `e22c9d2873cd5a8aade9deb2e518b75dab05170e` | same as the 2.18.0 gitlink |
| `third-part/yaml` | https://github.com/heiher/yaml | `9e7614d4310df7af71d593bb82672837d66e7657` | SPEC pin. 2.18.0's gitlink is `efa36117a8646d26d12b58e05bac472d7854a70d`; `efa3611..9e7614d` only renames LICENSE and edits README.md, so the code is identical |

No hev-socks5-tunnel commit pins exactly `ab2a15a8` + `9e7614d4`; those are the HEADs of the submodule
repos. Because the only differences are docs and license files, the tree above is code-identical to the
official 2.18.0 submodule set.

How it was fetched. `git clone --recursive` hits Windows MAX_PATH, so each repo was downloaded as
`https://codeload.github.com/heiher/<repo>/tar.gz/<sha>` and extracted, then the submodule directories
were filled in with plain files. Tarball SHA-256 values:

```
0b236c0db11c78a6ebdae0260dfef9aee7c1e4b95e788a80c31cf09c2282742c  hev-socks5-tunnel d9dca26
041847c954dcd5472f10fec10ed864d73fb39e896b534c1b0681de9587e556b0  hev-socks5-core   ab2a15a
f0a9d1d1be3476c01a3ceb6a31a4cb30de375b55fe1f5c230fa61f5148256abc  hev-task-system   328f35d
66314eac16f623598704413e5bb2fd9b8c2ce638607e4ebb14a7665f7887c627  lwip              e22c9d2
29de819214d28ea6b9c3f6920e85c595bdfa2fa7ef8daa7081bdebb2ef7b0c9f  yaml              9e7614d
```

### Removed from the upstream trees (not used by the Android build)

- hev-socks5-tunnel: `.github/`, `.gitmodules`, `.gitignore`, `.dockerignore`, `Dockerfile`, `docker/`,
  `build-apple.sh`, `android/` (upstream's Java `hev.htproxy.TProxyService` binding; ours is
  `engine/TProxy.kt`), and `third-part/wintun/` (Windows-only prebuilt DLLs).
- hev-socks5-core, hev-task-system, lwip, yaml: `.github/`, `.gitignore`, and `.gitlab-ci.yml`.
- hev-task-system: `apps/` and `tests/`, which its `build.mk` never compiles because it only builds
  `src/`, plus `build-apple.sh`.

Kept: every `LICENSE`, `README.md`, `Makefile`/`build.mk`/`configs.mk`/`Android.mk`/`Application.mk`,
`conf/main.yml` (the reference config), and all of `src/` in every repo. The lwip `src/` tree was
left complete because its `build.mk` wildcards the directory.

## Build integration (no build-file patches)

The upstream `Android.mk` files are used unmodified. The integration lives in our own
`jni/Android.mk` and `jni/Application.mk`:

- `REV_ID := d9dca26` is set before including hev. Otherwise hev's `build.mk` asks our repo's git for
  the commit id, which gives the wrong id or prints an error when git is not on PATH.
- `APP_CFLAGS := -O2 -DPKGNAME=io/github/unsalable/goodbyedpi/engine -DCLSNAME=TProxy`. This is the
  approach the upstream README documents.
- `APP_MODULES := hev-socks5-tunnel byedpi` skips upstream's `hev-socks5-tunnel-bin` CLI. Passing
  `GDPI_NATIVE_TOOLS=1` on the command line builds every module, including the CLI.

## Local source patches

Every patch is marked `/* gdpi: ... */` in the code. The unified diff against the pristine trees can
be regenerated with `diff -u`; all hunks are shown in full below.

### P1 — `src/hev-jni.c`: synchronous config/fd validation in `TProxyStartService`

Upstream returns `true` as soon as the worker thread is created. A bad YAML file, a missing file or an
invalid fd then shows up only later, when `TProxyIsRunning` turns false. SPEC needs a boolean start
result that reports config errors. With this patch, while `mutex` is held and no worker thread
exists (the previous one has been joined), native code runs `fcntl(fd, F_GETFD)` and
`hev_config_init_from_file(path)`. If either fails, it returns `JNI_FALSE`. This is safe because
`hev_config_init_from_file` calls `hev_config_reset()` first, so parsing is idempotent, and the
worker thread parses the same file again. The patch also calls `hev_socks5_tunnel_clear_stop()`
(P2) before it spawns the thread.

```diff
@@ includes
 #include <jni.h>
+#include <fcntl.h>
 #include <pthread.h>
@@
 #include "hev-main.h"
+/* gdpi: senkron config dogrulamasi ve stop bayragi temizligi icin */
+#include "hev-config.h"
+#include "hev-socks5-tunnel.h"
@@ native_start_service, after joining the previous thread
+    if (fd < 0 || fcntl (fd, F_GETFD) < 0)
+        goto exit;
+    if (!config_path)
+        goto exit;
+    bytes = (const jbyte *)(*env)->GetStringUTFChars (env, config_path, NULL);
+    if (!bytes)
+        goto exit;
+    res = hev_config_init_from_file ((const char *)bytes);
+    (*env)->ReleaseStringUTFChars (env, config_path, (const char *)bytes);
+    if (res < 0)
+        goto exit;
+    hev_socks5_tunnel_clear_stop ();
```

### P2 — `src/hev-socks5-tunnel.c/.h`: `hev_socks5_tunnel_clear_stop()`

`hev_socks5_tunnel_stop()` sets `SYNC_STOP` when a stop request arrives before `hev_socks5_tunnel_init`
has published `SYNC_SEND`. Only the next `hev_socks5_tunnel_run()` consumes that flag, and
`hev_socks5_tunnel_fini()` never clears it. The flag can therefore survive a run in two cases:
init fails after the stop request arrived, or `TProxyStopService` races with a worker that is already
exiting. The next start in the same process would then quit immediately. The new function clears the
flag, and P1 calls it only while no worker thread exists.

```diff
+void hev_socks5_tunnel_clear_stop (void);            /* header */
+void
+hev_socks5_tunnel_clear_stop (void)
+{
+    atomic_fetch_and (&tsync, ~SYNC_STOP);
+}
```

### P3 — `src/hev-socks5-tunnel.c` `lwip_io_task_entry`: no busy loop on tun EOF/error

`hev_tunnel_read()` returns NULL without yielding for three reasons: EOF, a persistent read error
such as EBADF or EBADFD after the tun is torn down, and a failed `pbuf_alloc`. Upstream then
immediately runs `continue`. The cooperative scheduler never runs the event task again, which has two
effects: the thread burns 100% CPU, and `TProxyStopService` hangs forever because it joins a thread
that never ends. This was reproduced: after closing the socketpair peer, the process used about 1004 ms
of CPU per second and stop never returned. The patch sleeps 20 ms after each failure. After 50
consecutive failures (about 1 s) the tunnel stops itself. The worker thread then exits and
`TProxyIsRunning` returns false, so the app watchdog can rebuild the VPN.

```diff
+    unsigned int read_fails = 0; /* gdpi */
@@
         buf = hev_tunnel_read (tun_fd, mtu, task_io_yielder, NULL);
-        if (!buf)
+        if (!buf) {
+            if (!READ_ONCE (run))
+                break;
+            if (++read_fails == 50) {
+                LOG_E ("socks5 tunnel read failed, stopping");
+                hev_socks5_tunnel_stop ();
+            }
+            hev_task_sleep (20);
             continue;
+        }
+        read_fails = 0;
```

### P4 — `src/misc/hev-logger.c` and `src/core/src/hev-socks5-logger.c`: reset `fd` in `*_logger_fini`

Both loggers `close(fd)` but leave the stale number in the static variable. Consider a restart in the
same process where the new config has `log-file: null`: `*_logger_init` returns early and `fd` keeps
the old number. `hev_logger_log` would then write log lines into whatever file or socket now holds
that number. The patch adds one line, `fd = -1;`, in each file.

## Verification

The results are recorded in `android/docs/HEV_NOTES.md` under "Verification". In summary: all 4 ABIs
build with `-j8` and no warnings, every LOAD segment is aligned to 0x4000, and the JNI harness passes
62 of 62 checks on the emulator.

---

# byedpi — vendored sources and local patches

Directory: `android/app/src/main/jni/byedpi/` (upstream), `android/app/src/main/jni/byedpi-jni/` (ours).
Usage notes for the Kotlin side: `android/docs/BYEDPI_NOTES.md`.

## Upstream revision

| path in tree | repository | commit (full SHA) | note |
|---|---|---|---|
| `byedpi/` | https://github.com/hufrea/byedpi | `ba532298de7b28cfe854aea83d061369d13ca290` | `master` HEAD on 2026-09-28 ("Save pointer to free before exit"), `VERSION "17.3"` |

Fetched as `https://codeload.github.com/hufrea/byedpi/tar.gz/ba532298de7b28cfe854aea83d061369d13ca290`, with
tarball SHA-256 `c7211cfc0729d213219be9b67d774e375d75ae53bbf18b6af1967f06919bbac4`.

### Removed from the upstream tree

The following are Windows-only or packaging files, and nothing in the ndk-build uses them:

- `win_service.c`, `win_service.h`
- `dist/` (bsd/docker/linux/windows service files)
- `Dockerfile`, `.dockerignore`, `.github/`, `.gitignore`, `.editorconfig`
- `Makefile`: the Linux CLI build is replaced by `byedpi-jni/Android.mk` for the library and by
  `android/tools/native/Android.mk` for the test CLI.

Kept unmodified: `LICENSE` (copied to `licenses/LICENSE-byedpi.txt` and `app/src/main/assets/licenses/`),
`README.md`, `conev.c`, `kavl.h`, `mpool.h`, `packets.c`, `packets.h`, `extend.h`, `desync.h`.

## Build integration (our files, no upstream build files)

- `byedpi-jni/Android.mk` defines the module **`byedpi`** (`libbyedpi.so`). Its sources are
  `conev.c desync.c extend.c mpool.c packets.c proxy.c`, the wrapper `byedpi_main.c`
  (`#define main byedpi_main` + `#include "../byedpi/main.c"`), `byedpi_lib.c` and `byedpi_jni.c`.
  - Flags: `-std=c99 -D_DEFAULT_SOURCE` as in upstream's Makefile, plus
    `-DBYEDPI_LIB -DNDEBUG -O2 -fvisibility=hidden`.
  - Warnings: `-Wall -Wextra` with upstream's `-Wno-unused*`. Two harmless upstream warnings are
    silenced (`-Wno-unknown-attributes` for the gcc-only `nonstring`, `-Wno-sign-compare` in
    `desync.c`). Implicit declarations, int-conversion, format, return-type and pointer-type
    mismatches are errors.
  - `-llog`, plus `-Wl,-z,max-page-size=16384 -Wl,--gc-sections`.
  - The top-level `jni/Android.mk` includes it; `Application.mk` lists it in `APP_MODULES`.
- `NDEBUG` is set because upstream's `assert()`s would `abort()` the whole app process in a debug
  build. The test binaries in `android/tools/native` keep asserts on.
- `byedpi-jni/byedpi_lib.c` holds the JVM-free start/stop state machine: a mutex, the states
  IDLE / RUNNING / STOPPING, and an eventfd that the lib owns. `byedpi-jni/byedpi_jni.c` holds
  `JNI_OnLoad` + `RegisterNatives` for `NativeBridge`.

## Local source patches

Every hunk is marked `/* gdpi: ... */` or `// gdpi`. Everything guarded by `#ifdef BYEDPI_LIB`
is library-only; the other hunks also apply to the test CLI (`ciadpi`). Regenerate the full diff
with `diff -u <pristine tarball> app/src/main/jni/byedpi`; it has about 310 changed lines and
upstream whitespace is preserved.

### B1 — library mode (`BYEDPI_LIB`), restartable in-process

| file | hunk | why |
|---|---|---|
| `main.c` | `DAEMON` defined only `#ifndef BYEDPI_LIB` | `-D/--daemon` would `fork()` the app process; `--pidfile` is useless. Unknown options → -2 |
| `main.c:main` | `#ifdef BYEDPI_LIB` prologue: the first call saves a pristine copy of `params`; every later call restores it; `optind = 1; optreset = 1` (bionic). The `SS_LOCAL_PORT`/`SS_PLUGIN_OPTIONS` env handling is compiled out | `parse_args`/`init` mutate `params` (`mode |=`, `def_ttl`, `baddr` family, `laddr`, the group list, flags…), and getopt keeps global state. Without this, start #2 inherits start #1's options |
| `main.c:clear_params` | also `free(params.need_free)` (the array, not just its elements) and the new `redirects`/`drop_udp` arrays | leaked on every run with `--hosts` |
| `main.c:parse_args` | `'?'` logs the offending argv under `BYEDPI_LIB`; `invalid value` goes through `LOG(LOG_E)` instead of `fprintf(stderr)` (same output in the CLI, since LOG_E always prints) | stderr is `/dev/null` in an app |
| `error.h` | under `BYEDPI_LIB`: `LOG()` / `uniperror()` go to `__android_log_(v)print`, tag `ciadpi`, with the same `-x` threshold as the CLI | SPEC 1.1; errors only at `-x 0` |
| `proxy.c` | `on_cancel`/`on_hup` and the `SIGINT/SIGTERM/SIGHUP` `signal()` calls are compiled out under `BYEDPI_LIB`; `SIGPIPE` → `SIG_IGN` is kept | ART owns the process signals |
| `proxy.c:run` | under `BYEDPI_LIB`: if a stop is already pending, return 0 before `listen()` | stop-before-start never opens the port |
| `proxy.c:start_event_loop` | under `BYEDPI_LIB`: registers `dup(byedpi_lib_wake_fd())` with `on_lib_wake` (sets `pool->brk`) | replaces `shutdown(server_fd)` from another thread, which could hit a reused fd number; the pool closes the dup, and the lib closes the original under its mutex |
| `proxy.h` | prototypes `byedpi_lib_wake_fd()`, `byedpi_lib_stop_pending()` under `BYEDPI_LIB` | provided by `byedpi-jni/byedpi_lib.c` |

### B2 — `--redirect FROM=TO` (TCP CONNECT + UDP ASSOCIATE)

| file | hunk |
|---|---|
| `params.h` | `struct redirect_rule { union sockaddr_u from, to; }`; `params.redirects`, `params.redirect_n` |
| `conev.h` | `struct eval` gets `const struct redirect_rule *redir`: the per-association relabel flag |
| `main.c` | long-only option `redirect` (val `OPT_REDIRECT` = 0x10, so no short letter is taken from upstream); `parse_redirect()`: both sides `ip:port` / `[ipv6]:port` via upstream `get_addr`, both ports required, FROM v4-mapped → IPv4 (`map_fix`); help text |
| `proxy.c` | `addr_unmap()`, `redirect_find()` (family + address + port after un-mapping), `redirect_tcp()`, called in `handle_s5` (SOCKS5 CONNECT) and `on_request` (SOCKS4/HTTP CONNECT) before `connect_hook` |
| `proxy.c:on_udp_tunnel` | on the association's first forwarded datagram: if the destination matches FROM, `connect()` the upstream socket to TO and set `pair->redir`; on replies, if `val->redir`, label the SOCKS5 UDP header with FROM instead of the real source |

### B3 — `--drop-udp PORT[-PORT]`

| file | hunk |
|---|---|
| `params.h` | `params.drop_udp` (host-order ranges), `params.drop_udp_n` |
| `main.c` | long-only option `drop-udp` (val 0x11), `parse_drop_udp()` (decimal, 1..65535, lo ≤ hi, repeatable) |
| `proxy.c:on_udp_tunnel` | after parsing the client's SOCKS5 UDP header and before binding/redirecting, `continue` (drop this datagram only) if the requested destination port is in a range. The association and other ports are unaffected, and a dropped first datagram does not bind the association |

### B4 — upstream bug fixes found while reviewing (all generic, not `BYEDPI_LIB`-only)

| file | fix | effect upstream |
|---|---|---|
| `extend.c:check_l34` | compare `--pf` bounds with `ntohs()` | `pf[]` is network order: on little-endian every real range was wrong (443 and 1253 "matched" `--pf=50000-65535`; 18096 did not match `17900-18200`). Single ports were fine. Verified with `udp_socks_test` |
| `extend.c:cache_get` | `mem_delete(..., len * 8)` | the length was passed in bytes where bits are expected, so an expired entry deleted an arbitrary IPv4 entry |
| `desync.c:desync` | `munmap` the fake page if `send_fake` did not take ownership (fake part of zero length, or `pipe()` failure) | one leaked mmap per such request in a long-lived process |
| `desync.c:send_fake` | `TCP_MD5SIG` failing with `ENOPROTOOPT` → log once, remember it for the process, continue with TTL only | Android GKI kernels have no TCP_MD5SIG (verified on the emulator): `--md5sig` closed every connection of the group |
| `proxy.c:s5_get_addr` | unknown ATYP → `-S_ER_ATP` | the address size stayed 0, and the port was read from `buffer[-2]` |
| `main.c:add_group` | `dp->bit = (uint64_t)1 << id`; more than 64 groups → parse error | `1 << id` on `int` broke the masks from the 32nd group on (UB) |
| `mpool.c:load_cache` | `fscanf("%jd")` into an `intmax_t` temporary | wrote 8 bytes into a 4-byte `time_t` on 32-bit ABIs, a stack overflow with `--cache-file` |

### Decided not to patch

- **TCP half-close** (`recv()==0` → full close in `extend.c:tcp_recv_hook`/`on_fin`). Supporting
  it would need per-direction EOF state through `on_tunnel`, `on_fin` (which also drives `ssl_err`
  detection) and the replay logic. That is not a minimal change, and a mistake would leak half-open
  fds in a long-lived process. Browsers, OkHttp and TLS clients do not half-close. See BYEDPI_NOTES §10.
- `--fake-offset` with a non-page-aligned offset `munmap`s an unaligned address, which leaks. It is
  not used by the app, so this is documented only.
- The UDP association sends to its first destination only (upstream design). hev uses one
  association per flow.

## Verification

The details and the real results are in `android/docs/BYEDPI_NOTES.md` §9. In summary:

- The standalone module and the top-level `jni/` build, for 4 ABIs, with 0 warnings; every LOAD
  segment is `Align 0x4000`; the only export is `JNI_OnLoad`.
- `smoke.py` on emulator-5554 covers presets, wire (tcpdump), the DPI simulation (iptables string
  DROP/RST), UDP/redirect/drop/malformed (ASan), `restart_test` (+ ASan) and the JNI test against
  the R8 release APK.
