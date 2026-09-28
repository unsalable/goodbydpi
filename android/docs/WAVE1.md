# Wave 1 notes (lead)

What exists after wave 1 and the gotchas the next agents need. SPEC.md stays the contract.

## Built
* Gradle project (AGP 8.13.2, Kotlin 2.2.21, Compose BOM 2026.06.01 — newer BOMs need
  compileSdk 37 + AGP 9). 49 JVM tests for models/settings.
* model/ + data/SettingsRepository (see gotchas), App.kt (channels `App.CHANNEL_STATUS`,
  `App.CHANNEL_ALERTS`), placeholder MainActivity, :probe skeleton, full manifest.
* hev-socks5-tunnel 2.18.0 vendored + 4 small `/* gdpi */` patches, `engine/TProxy.kt`
  (matches SPEC exactly), HEV_NOTES.md (YAML, fd ownership, stop model, watchdog recipe).
* Contract stubs (bodies to be replaced by their owners, signatures fixed):
  `service/EngineStateHolder.kt`, `service/ServiceController.kt`, `service/Components.kt`
  (placeholder DpiVpnService/BootReceiver/QuickTileService), `engine/EngineConfig.kt`,
  `engine/ByeDpiArgs.kt`, `update/UpdateManager.kt`, `diag/ConnectionTester.kt`.
* byedpi (lib + JNI + NativeBridge.kt + BYEDPI_NOTES.md) is being redone in parallel with
  wave 2; the runtime agent starts after it lands.

## Gradle knobs
`-Pgdpi.abi=x86_64` (single ABI dev build; `:app:dist` refuses it), `-Pgdpi.appIdSuffix=.devX`
(debug only), `-Pgdpi.buildDir=C:\t\<name>` (short build dir — use it in deep worktree paths:
ndk-build's make.exe crashes when NDK_OUT exceeds ~190 chars), `-Pgdpi.cxxDir=...`.
Debug APK: `app/build/outputs/apk/debug/GoodbyeDPI-Android-1.0.0-debug.apk`.
`:app:dist` → `build/dist/GoodbyeDPI-Android-<ver>.apk`, `GoodbyeDPI-Android.apk`, `SHA256SUMS.txt`.

## SettingsRepository gotchas
* `update {}` runs `migrate()` on the result: deleting the last custom profile / DNS entry puts
  a default one back; a method/dns id pointing at a deleted custom entry becomes
  "default"/"cloudflare"; "checksum" becomes "default".
* `update {}` publishes to the StateFlow first, then writes; no write when nothing changed.
* `DpiConfig.sanitized()` does not force fake protection: the engine must check
  `hasFakeProtection` and fall back to TTL.

## hev gotchas (details in HEV_NOTES.md)
* Pass `pfd.fd` (not detachFd); stop order: `TProxyStopService()` then `pfd.close()`.
* hev makes the fd non-blocking itself (leave `setBlocking` default); `setMtu` must equal
  YAML `tunnel.mtu` (8500); `socks5.udp: 'udp'` is mandatory with byedpi.
* hev keeps running when byedpi dies — watch byedpi separately. `TProxyIsRunning()==false`
  means hev exited by itself (tun EOF/error) or init failed; check ~250 ms after start.
* byedpi treats TCP half-close as full close (clients that shut down their write side after
  the request, like `nc` without `-q`, get no response). Normal HTTP/TLS clients are fine.
