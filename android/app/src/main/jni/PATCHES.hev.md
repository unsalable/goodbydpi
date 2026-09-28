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
