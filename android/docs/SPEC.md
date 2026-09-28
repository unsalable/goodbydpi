# GoodbyeDPI Android — build specification

This is the working contract for the Android port of the WPF desktop app in `src/GoodbyeDpiUI`.
Everything an implementer needs to agree on lives here: architecture, package layout, the exact
cross-module Kotlin/JNI signatures, engine mapping rules, service lifecycle rules, UI/UX spec,
test plan and conventions. If you must deviate, update this file in the same change and say why.

## 0. Goal

A sideloadable, signed **APK** that does for Android what the desktop app does for Windows:
one big power button that bypasses Turkish ISP DPI (Discord, Roblox, …), with ISP presets,
method presets, named custom method profiles, named custom DNS servers, light/dark theme,
auto-connect, start-on-boot, auto-update from GitHub releases — and it must **keep running
reliably in the background** (foreground VPN service, survives screen-off/doze/network changes,
restarts after process death, boot and app update, supports Android "Always-on VPN").
UI must be **smooth and animated** (spring motion like the desktop), and **optimized**
(R8, small APK, no busy loops, ~0% CPU when idle, low memory).

No root. Min SDK 24 (raise to 26 only if the native build genuinely needs it; document why).
Target/compile SDK 36. Primary language of all user-visible text: **Turkish** (with proper
diacritics: ı, ğ, ş, ç, ö, ü, İ). Code comments: Turkish in ASCII (like the desktop code:
"Kendi motorumuz ...", no diacritics) — keep comment density similar to the desktop sources:
explain *why*, not *what*.

## 1. Architecture (non-root DPI bypass)

```
 apps ──► tun (VpnService) ──► hev-socks5-tunnel (lwIP tun2socks, native thread)
                                   │  SOCKS5 TCP CONNECT / UDP ASSOCIATE
                                   ▼
                           byedpi "ciadpi" SOCKS5 proxy on 127.0.0.1:<port> (native thread)
                           applies desync (split / disorder / fake+TTL / md5sig / tlsrec / oob /
                           udp-fake), DNS redirect, QUIC drop
                                   │  normal sockets of OUR app uid
                                   ▼
                           underlying network (Wi-Fi / mobile)
```

* Our own package is excluded from the VPN with `Builder.addDisallowedApplication(packageName)`,
  so byedpi's outgoing sockets never loop back into the tun (no `protect()` plumbing needed).
  Side effect (intended): the app's own HTTP (update check, connection test) bypasses the tun.
* Both native components are vendored C sources (MIT) built with **ndk-build** through AGP
  `externalNativeBuild.ndkBuild`:
  * byedpi — https://github.com/hufrea/byedpi @ `ba532298de7b28cfe854aea83d061369d13ca290`
  * hev-socks5-tunnel — https://github.com/heiher/hev-socks5-tunnel @ `main` (2.18.0 era) with
    its submodules vendored as plain files (src/core = hev-socks5-core `ab2a15a8`,
    third-part/hev-task-system `328f35d9`, third-part/lwip `e22c9d28`, third-part/yaml `9e7614d4`).
    Git submodules/clones hit Windows MAX_PATH in long paths; download
    `https://codeload.github.com/heiher/<repo>/tar.gz/<sha>` and extract instead.
* Location: `android/app/src/main/jni/` (`Android.mk`, `Application.mk`, `byedpi/`,
  `byedpi-jni/`, `hev-socks5-tunnel/`). Record upstream commit ids and every local patch in
  `android/app/src/main/jni/PATCHES.md`. Keep patches minimal, guarded by `#ifdef BYEDPI_LIB`
  / clearly marked `/* gdpi: ... */`, so upstream updates stay mergeable.
* Licenses: add `licenses/LICENSE-byedpi.txt`, `LICENSE-hev-socks5-tunnel.txt`,
  `LICENSE-lwip.txt`, `LICENSE-hev-task-system.txt`, `LICENSE-yaml.txt` (copy upstream files) and
  show them in the app (Ayarlar → Hakkında → Açık kaynak lisansları).

### 1.1 byedpi patches (required)

1. **Library mode** (`-DBYEDPI_LIB`, `-Dmain=byedpi_main` for main.c only):
   * do not install SIGINT/SIGTERM/SIGHUP handlers (ART owns the process); keep `SIGPIPE` ignore;
   * route `LOG(...)` to logcat (`__android_log_print`, tag `ciadpi`) instead of stderr;
   * make the proxy restartable in-process: before every start restore `params` from a pristine
     copy taken on first use, reset getopt (`optind = 1; optreset = 1;` on bionic), reset any
     other static state that survives a run (check `server_fd`, caches, mempools, `fake_*`).
2. **Redirect** `--redirect FROM=TO` (repeatable; `ip:port`, IPv6 as `[addr]:port`):
   * TCP CONNECT to FROM connects to TO instead.
   * UDP ASSOCIATE: when the first datagram's destination is FROM, the outgoing UDP socket
     connects to TO; replies from TO are re-labelled as coming from FROM in the SOCKS5 UDP header
     (per association flag, not a global address match). Mind v4/v6 + v4-mapped (`map_fix`).
   * Used for DNS: apps query a virtual resolver inside the tun, byedpi forwards to the chosen
     server **and port** (Yandex 77.88.8.8:1253 dodges ISP port-53 hijacking).
3. **UDP drop** `--drop-udp PORT[-PORT]` (repeatable): silently drop client datagrams whose
   destination port matches (used for QUIC/HTTP3 block = 443 → apps fall back to TCP+TLS).
   Must not tear down the association or affect other ports.

JNI glue (`byedpi-jni/byedpi_jni.c`) registers natives for class
`io/github/unsalable/goodbyedpi/engine/NativeBridge`:

```kotlin
package io.github.unsalable.goodbyedpi.engine
internal object NativeBridge {
    init { System.loadLibrary("byedpi") }
    /** Blocks until the proxy stops. Returns 0 on clean stop, <0 on error (bind failure, bad args). */
    @JvmStatic external fun byedpiStart(args: Array<String>): Int
    /** Thread-safe; wakes the loop (shutdown(server_fd)). Returns 0, or -1 if not running. */
    @JvmStatic external fun byedpiStop(): Int
}
```
Only one byedpi instance may run per process; the glue must serialize start/stop and be safe
against stop-before-start / double stop races.

hev: build `libhev-socks5-tunnel.so` with `-DPKGNAME=io/github/unsalable/goodbyedpi/engine
-DCLSNAME=TProxy`:

```kotlin
package io.github.unsalable.goodbyedpi.engine
internal object TProxy {
    init { System.loadLibrary("hev-socks5-tunnel") }
    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic external fun TProxyStopService(): Boolean
    @JvmStatic external fun TProxyIsRunning(): Boolean
    /** [txPackets, txBytes, rxPackets, rxBytes] */
    @JvmStatic external fun TProxyGetStats(): LongArray
}
```
(Check the exact registration contract in `hev-jni.c`: static vs instance methods, ProGuard keep
rules for both classes.) 16 KB page alignment for all `.so` (Android 15+).

### 1.2 Tun / VPN addressing

| item | value |
|---|---|
| tun IPv4 | `198.18.0.1/32` (hev `tunnel.ipv4: 198.18.0.1`) |
| tun IPv6 | `fd00:6764:7069::1/128` (hev `tunnel.ipv6`) |
| virtual DNS v4 | `198.18.0.53` → byedpi `--redirect 198.18.0.53:53=<dns v4>:<port>` |
| virtual DNS v6 | `fd00:6764:7069::53` (only if the DNS profile has an IPv6 address) |
| MTU | 8500 (hev default; tun is terminated by lwIP) |
| routes | `0.0.0.0/0` and `::/0`; if "Yerel ağı hariç tut" is on (default), route the complement of private/link-local/multicast ranges (10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10?, 224/4, fc00::/7, fe80::/10, ff00::/8) while still covering 198.18.0.0/15 — compute with a unit-tested CIDR complement (on API 33+ `excludeRoute` may be used instead, but one code path is preferable) |
| DNS "Kapalı" (off) | do not redirect: add the underlying network's DNS servers (`LinkProperties.dnsServers` of the active non-VPN network) to the VPN so queries still flow through the tun; if none, fall back to 1.1.1.1 |
| disallowed apps | own package; optional user list later (not in v1) |
| `setMetered` | leave default (inherits underlying) |
| `setBlocking` | per hev expectation (check `hev-tunnel-linux.c`) |

hev YAML (written to `filesDir/hev.yml` each start): tunnel mtu/ipv4/ipv6, `socks5.address
127.0.0.1`, `socks5.port <byedpi port>`, `socks5.udp 'udp'`, misc: `task-stack-size`,
`connect-timeout`, `tcp-read-write-timeout`, `udp-read-write-timeout`, `log-level warn`,
`log-file` = null (or `filesDir/hev.log` in debug builds, size-capped). Tune for low memory.

### 1.3 Engine mapping (DpiConfig → byedpi argv)

Pure Kotlin, JVM-unit-tested: `ByeDpiArgs.build(config: EngineConfig): List<String>`.
Base: `-i 127.0.0.1 -p <port> -c <max-conn 2048> -b <buf 16384>` plus desync groups.

Desktop techniques that need raw packets (wrong checksum, wrong SEQ, seqovl, auto-TTL from
SYN-ACK) are impossible without root; map each desktop preset to the closest byedpi
equivalent and say so honestly in the Turkish description. Guidance (the implementer must
verify every flag against byedpi's `desync.c`/`extend.c`/`main.c`, and fix this table if wrong):

| id | Turkish name | byedpi group (Linux semantics) |
|---|---|---|
| `default` | Varsayılan | fake + disorder: e.g. `--disorder 1 --fake -1 --ttl 5` (+ `--split 0+sm` if SNI split on) |
| `fixedttl` | Sabit TTL | `--fake -1 --ttl 5` |
| `disorder` | Ters sıra | `--disorder 2` (desktop: split@2 reversed) |
| `ttl4` / `ttl3` | Sahte TTL 4 / 3 | `--fake -1 --ttl 4` / `3` |
| `md5sig` | MD5 imzası | `--fake -1 --md5sig` (kernel may lack TCP_MD5SIG → auto fallback group with TTL) |
| `md5ttl3` | MD5 + TTL 3 | `--fake -1 --md5sig --ttl 3` |
| `fakesplit5` | Bölünmüş sahte | fake TTL 5 with the fake itself split, closest equivalent |
| `zerofake` | Boş sahte | `--fake -1 --ttl 5 --fake-data ':\0\0\0\0'` (verify escape parsing) |
| `split2` | Düz bölme | `--split 2` |
| `split` | Sadece bölme | `--split 2 --split 0+sm` |
| `tlsrec` | TLS kayıt bölme (Android'e özel) | `--tlsrec 3+s` (optionally with `--split`) |
| `checksum` | (desktop only) | not offered on Android; stored id resolves to `default` |

Common switches:
* `fragmentHttp` on → `--proto=tls,http` scope for the TCP groups, else `--proto=tls`.
* `blockQuic` → `--drop-udp 443`.
* `voiceFake` → a leading UDP group scoped to Discord voice/STUN ports
  (`--proto=udp --pf=50000-65535` and STUN 3478-3481/19294-19344 as applicable)
  with `--udp-fake <voiceFakeRepeats> --ttl <ttl>`; followed by `--auto=none` so TCP groups
  still apply to everything the UDP group skipped. **Verify group/trigger semantics in
  `extend.c`** — group order and `--auto` behaviour decide correctness.
* **Automatic fallback** (global setting `autoFallback`, default on): after the primary group,
  append the ISP's alternative methods as `--auto=torst,ssl_err` groups (and a `--timeout`
  of a few seconds), so a host that resets/stalls is transparently retried with the next method
  and cached (`--cache-ttl`). Custom profiles fall back to the ISP recommendation.
* DNS profile active → `--redirect 198.18.0.53:53=<v4>:<port>` (+ v6 equivalent).
* `--def-ttl` unset (byedpi reads it from a socket).

Also expose `fun describe(config): String` for the debug/diagnostics screen (the exact argv).

## 2. Kotlin package layout

Root package `io.github.unsalable.goodbyedpi`, single `:app` module (+ debug-only `:probe`
test app, see §7). Suggested files (owners in brackets):

```
App.kt                       Application: SettingsRepository, notification channels, crash-safe init
MainActivity.kt              single activity, Compose, edge-to-edge, VPN consent launcher, POST_NOTIFICATIONS
model/  DpiConfig.kt IspProfile.kt MethodPreset.kt DnsProfile.kt CustomProfiles.kt AppSettings.kt
data/   SettingsRepository.kt
engine/ NativeBridge.kt TProxy.kt ByeDpiArgs.kt HevConfig.kt VpnRoutes.kt EngineConfig.kt DpiEngine.kt
service/ DpiVpnService.kt EngineStateHolder.kt Notifications.kt BootReceiver.kt QuickTileService.kt ServiceController.kt
update/ UpdateChecker.kt UpdateInstaller.kt
diag/   ConnectionTester.kt
ui/     theme/{Color,Theme,Type,Motion}.kt  MainScreen.kt SettingsScreen.kt components/*  MainViewModel.kt
```

### 2.1 Models (port of `src/GoodbyeDpiUI/Models`)

* `DpiConfig` — `@Serializable` mutable-style data class (copy-on-write), JSON names match the
  desktop where the meaning matches: `fakePacket`, `ttl` (1..64, default 5), `fakeMd5Sig`,
  `fakePayload` (`TLS`/`ZEROS`), `fakeSni` (default `www.w3.org`), `splitTls`, `reverseSplit`,
  `splitPosition` (default 2), `splitSni`, `tlsRecordSplit`, `blockQuic`, `fragmentHttp`,
  `voiceFake`, `voiceFakeRepeats` (1..20, default 6). `summary` (Turkish, "sahte paket (TTL 5) ·
  ters sıra bölme · QUIC engeli · Discord ses") like the desktop `Summary`.
* `MethodPreset(id, name, description, build: () -> DpiConfig)` + `all`, `fromId()`.
* `IspProfile` — same 9 entries and order as desktop `IspProfile.All` (Genel, Türk Telekom,
  Superonline, Vodafone, TürkNet, Kablonet, TT Mobil, Turkcell Mobil, Vodafone Mobil), same
  recommended/alternative method ids (drop `checksum`; Genel gets `tlsrec` instead), DNS Yandex
  for ISP presets, `null` (don't touch) for Genel.
* `DnsProfile(id, name, description, v4Addr, v4Port, v6Addr, v6Port)`; built-ins Cloudflare
  (1.1.1.1:53 / 2606:4700:4700::1111), Yandex (77.88.8.8:1253 / 2a02:6b8::feed:0ff port 1253),
  Kapalı; custom entries; `validate(): String?` (Turkish messages as desktop).
* `CustomIds` (`custom`, `custom:xxxxxxxx`, `newName("Özel", taken)` → "Özel 2" …), 
  `CustomMethodProfile(id, name, config)`, `CustomDnsEntry(id, name, v4, v4Port, v6, v6Port)`.
* `AppSettings` (`@Serializable`, unknown keys ignored, defaults for missing):
  `themeMode` (`system|light|dark`, default system), `isp` ("general"), `method` ("default"),
  `customProfiles`, `dns` ("cloudflare"), `customDns`, `autoConnect` (false on Android — opening
  the app should not silently start a VPN the first time; the user opts in), `startOnBoot`
  (true), `autoUpdate` (true), `autoFallback` (true), `excludeLan` (true), `ipv6` (true),
  `wantRunning` (internal: the last user intent, used by boot/sticky restart), `migrate()`
  guaranteeing ≥1 custom method profile and ≥1 custom DNS entry like the desktop.

### 2.2 SettingsRepository

`class SettingsRepository(context)`: `val settings: StateFlow<AppSettings>`,
`suspend fun update(transform: (AppSettings) -> AppSettings)`, JSON in `filesDir/settings.json`
(kotlinx.serialization, `ignoreUnknownKeys`, `encodeDefaults`), atomic write (temp + rename),
serialized writes (Mutex), corrupt file → keep a `.bak` and start from defaults. Synchronous
`load()` on first access is fine (tiny file) — needed by BootReceiver/Service before UI exists.

### 2.3 Engine / service state (shared by service, tile, notification, UI)

```kotlin
package io.github.unsalable.goodbyedpi.service
sealed interface EngineState {
    data object Stopped : EngineState
    data object Starting : EngineState
    data class Running(val sinceElapsed: Long, val methodName: String, val dnsName: String) : EngineState
    data object Stopping : EngineState
    data class Failed(val message: String) : EngineState   // Turkish, user-facing
}
data class TrafficStats(val txBytes: Long, val rxBytes: Long, val txPackets: Long, val rxPackets: Long)
object EngineStateHolder {
    val state: StateFlow<EngineState>
    val traffic: StateFlow<TrafficStats>     // only sampled while someone collects (UI visible)
    internal fun set(state: EngineState)
}
object ServiceController {
    /** Returns an Intent that must be launched for VPN consent, or null if already prepared and started. */
    fun start(context: Context): Intent?
    fun stop(context: Context)
    fun restartIfRunning(context: Context)   // settings changed while running → apply live
}
```

## 3. Service & stability requirements (must all be implemented and tested)

* `DpiVpnService : VpnService` — `startForeground` immediately in `onStartCommand` (Android 12+
  FGS deadlines), `foregroundServiceType="specialUse"` with
  `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="vpn"/>`,
  permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `BIND_VPN_SERVICE` on the
  service, meta-data `android.net.VpnService.SUPPORTS_ALWAYS_ON=true`.
* Actions: `START`, `STOP`, `RESTART` (apply new settings), and the system's always-on start
  (`VpnService.SERVICE_INTERFACE` action / null intent after sticky restart) → start from saved
  settings. `START_STICKY`. `onRevoke()` → stop cleanly, state `Stopped`, `wantRunning=false`.
* Engine start sequence (off the main thread, one dedicated single-thread executor/coroutine):
  pick a free loopback port → start byedpi thread → wait until it accepts (≤3 s, else Failed
  with reason) → `Builder.establish()` (null → Failed "VPN izni yok") → write hev.yml →
  `TProxyStartService` → Running. Stop reverses: hev stop → close tun PFD → byedpiStop → join
  (timeout) . Never leak the PFD or leave a half-started engine; every failure path cleans up.
* Watchdog: if byedpi returns unexpectedly or hev stops while we think we're running,
  restart the engine with backoff (1 s, 3 s, 10 s; max 5 tries per 5 min), then `Failed`
  with a notification. No polling faster than needed; idle CPU must be ~0.
* Network changes: nothing to rebuild normally; but register a default-network callback and,
  on API 22+, call `setUnderlyingNetworks` with the current default network so the system and
  metered state stay right. Survive Wi-Fi ↔ mobile ↔ airplane transitions without crashing;
  the VPN stays up and works again once a network returns.
* Notification: channel "Bağlantı durumu" (IMPORTANCE_LOW, no sound), ongoing, shows
  "Bağlı · <yöntem>" + a "Durdur" action; tap opens the app. Separate channel "Uyarılar" for
  failures. Handle POST_NOTIFICATIONS denial gracefully (FGS still works).
* `BootReceiver`: `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`? (only if direct-boot aware; not
  needed), `MY_PACKAGE_REPLACED` → if `startOnBoot && wantRunning` (boot) or `wantRunning`
  (package replaced) and `VpnService.prepare()==null` → start the service.
* `QuickTileService`: tile shows state (active/inactive/unavailable), toggles; when VPN consent
  is missing open the activity (`startActivityAndCollapse(PendingIntent)` on API 34+).
* Battery: Settings rows for "Pil optimizasyonunu kapat" (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
  and "Her zaman açık VPN" (opens `Settings.ACTION_VPN_SETTINGS` with a short explanation).
* Settings changes while running → `RESTART` (debounced ~400 ms) so the new method applies.
* No `GlobalScope`, no leaked threads; all native calls on background threads; ANR-free.

## 4. UI / UX spec (Jetpack Compose, Material 3 base, custom look)

Mirror the desktop look & feel (see `src/GoodbyeDpiUI/MainWindow.xaml`, `Themes/*.xaml`):

* Palette — dark: bg `#0E1014`, surface `#171A21`, surfaceAlt `#1E222B`, stroke `#2A2F3A`,
  text `#F1F5F9`, muted `#94A3B8`, accent `#818CF8`, accentSoft `#262A3F`, accentAlt `#A78BFA`,
  success `#34D399`, danger `#F87171`, track `#232833`. Light: bg `#FFFFFF`, surface `#F6F7F9`,
  surfaceAlt `#EEF0F4`, stroke `#E4E7EC`, text `#0F172A`, muted `#64748B`, accent `#6366F1`,
  accentSoft `#E4E6F8`, accentAlt `#8B5CF6`, success `#10B981`, danger `#EF4444`, track `#E9EBF0`.
  Accent gradient accent→accentAlt (135°). Theme: Sistem / Açık / Koyu, animated crossfade.
* **Main screen**: top bar "GoodbyeDPI" + theme toggle + settings icon. Center: large circular
  power button (≈ 168 dp) with gradient ring; states: Kapalı (muted ring), Bağlanıyor (rotating
  arc + breathing scale), Bağlı (success glow pulsing slowly, soft halo), Hata (danger shake
  once). Spring press feedback (scale 0.94). Status line with coloured dot (colour crossfade) +
  title ("Kapalı" / "Bağlanıyor…" / "Bağlı" / "Bağlantı kurulamadı") and detail line (method ·
  DNS / error reason), text changes animate (fade + 6 dp slide-up, like desktop RefreshText).
  When running: live ↑/↓ speed + totals, updated 1×/s, numbers animate; small sparkline optional.
  Chips row: selected ISP, method, DNS — tapping opens the relevant picker sheet.
  Update banner (when available) and "güncellendi" toast after an update.
* **Settings** (separate screen with shared-axis/slide transition, predictive back):
  sections İNTERNET SAĞLAYICI, YÖNTEM (+ Yeni özel ayar), custom profile editor (name, duplicate,
  delete; groups SAHTE PAKET / BÖLME / DİĞER with switches, steppers for TTL/positions/repeats,
  animated expand/collapse, "Önerilene dön"), DNS (+ Yeni DNS, custom editor with IPv4/port,
  IPv6/port, validation message), GENEL (Otomatik bağlan, Açılışta başlat, Otomatik güncelle,
  Otomatik yedek yöntem, Yerel ağı hariç tut, IPv6), ARKA PLAN (Pil optimizasyonu, Her zaman açık
  VPN), BAĞLANTI TESTİ (runs `ConnectionTester` against discord.com, roblox.com, example.com via
  the running proxy or direct, shows per-site ✓/✗ + ms), HAKKINDA (sürüm, GitHub, lisanslar).
* Pickers are modal bottom sheets with animated selection indicator; list items show name +
  one-line description/summary (like the desktop dropdown second line).
* Motion: `spring(dampingRatio≈0.8, stiffness≈400)` style everywhere; `AnimatedContent`,
  `animateColorAsState`, `animateFloatAsState`, `updateTransition` for the power button.
  Respect the system animator scale (Compose does) — with animations off everything snaps.
* Performance: stable/immutable UI state, `collectAsStateWithLifecycle`, no recomposition
  storms (traffic updates confined to the small composable that shows them), `drawBehind`/
  `graphicsLayer` for animated visuals, no allocations in draw loops. Target: no janky frames
  in `dumpsys gfxinfo` during normal use.
* Accessibility: content descriptions (Turkish), 48 dp touch targets, contrast OK in both themes.
* App icon: adaptive vector icon (power glyph on the indigo→violet gradient), monochrome layer
  for themed icons; notification small icon vector.

## 5. Updates (GitHub releases of `unsalable/goodbydpi`)

`UpdateChecker`: on app open (if `autoUpdate`) and at most every 6 h, GET
`https://api.github.com/repos/unsalable/goodbydpi/releases?per_page=10`, take the newest non-draft,
non-prerelease release that has an asset ending in `.apk` (prefer `GoodbyeDPI-Android.apk`),
compare its tag (`v1.2.3`) with `BuildConfig.VERSION_NAME` semantically. If newer: banner →
download with progress (to `cacheDir/updates`), verify SHA-256 if the release body contains a
64-hex hash near the apk name, then install via `PackageInstaller` session (needs
`REQUEST_INSTALL_PACKAGES`; guide the user to "Bilinmeyen uygulamaları yükle" if not allowed).
No update code may crash or block the UI when offline / rate-limited.

## 6. Build & packaging

* `android/` is a standalone Gradle project: Gradle wrapper **8.14.3** (already cached on this
  machine), AGP **8.13.x**, Kotlin **2.2.x** (+ `org.jetbrains.kotlin.plugin.compose`,
  `plugin.serialization`), JDK 17 toolchain. Compose via the newest BOM that builds with
  compileSdk 36 on this AGP (step back if a newer one demands more). Version catalog in
  `gradle/libs.versions.toml`. `ndkVersion = "29.0.14206865"` (installed).
* ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`. One **universal** release APK.
* Release: `isMinifyEnabled = true`, `isShrinkResources = true`, R8 full mode, keep rules for
  JNI classes (`NativeBridge`, `TProxy`) and kotlinx.serialization. `debug` stays unminified.
* Signing: release signing config read from `android/keystore.properties` (git-ignored:
  `storeFile`, `storePassword`, `keyAlias`, `keyPassword`); if absent, sign release with the
  debug key so the build never fails. Output name `GoodbyeDPI-Android-<version>.apk`
  (and copy as `GoodbyeDPI-Android.apk` in `android/build/dist/` via a `dist` task or script).
* `versionName = "1.0.0"`, `versionCode = 1`, applicationId `io.github.unsalable.goodbyedpi`,
  app label "GoodbyeDPI".
* `.gitignore`: `android/.gradle`, `android/**/build`, `android/local.properties`,
  `android/keystore.properties`, `*.jks`, `android/app/.cxx`.
* `local.properties` with `sdk.dir` is generated locally (not committed).
* Windows path length: the repo can sit in deep paths; if ndk-build/CMake object paths exceed
  limits, shorten via `externalNativeBuild.ndkBuild.buildStagingDirectory` or similar and
  document it. Commands must work from PowerShell: `android\gradlew.bat assembleRelease`.

## 7. Testing (proceed test-first where it pays; everything below must actually run)

* **JVM unit tests** (`app/src/test`): ByeDpiArgs for every preset × options, HevConfig YAML,
  VpnRoutes CIDR complement (coverage + no overlaps + includes 198.18.0.0/15), model presets
  (ISP lists, fromId fallbacks, checksum→default), CustomIds naming, DnsProfile validation,
  AppSettings JSON round-trip + unknown/missing keys + migrate, version comparison, SHA-256
  extraction from release notes.
* **Native smoke test**: build byedpi as a standalone executable for x86_64 too (not packaged),
  push to the emulator (`/data/local/tmp`), `adb forward tcp:18080 tcp:18080`, and exercise it
  from the host with `curl --socks5-hostname` (TCP, each preset) and a small Python SOCKS5 UDP
  client (DNS through `--redirect`, `--drop-udp 443` dropping). Keep the scripts under
  `android/tools/`.
* **Instrumented tests** (`app/src/androidTest`, run on emulator `emulator-5554`): Compose UI
  flows (select ISP → method list changes; create/rename/duplicate/delete custom profile; custom
  DNS validation), SettingsRepository persistence, engine start/stop in-process (byedpi only +
  HTTPS through it via SOCKS proxy).
* **`:probe` app** (debug-only helper, separate package `io.github.unsalable.goodbyedpi.probe`,
  NOT shipped): on `am start … --es urls a,b,c --ei parallel N` performs HTTPS/HTTP requests and
  DNS lookups (so its traffic goes THROUGH the VPN), logs a machine-readable result line per URL
  to logcat tag `GDPI_PROBE` and writes `files/probe.json`.
* **E2E on emulator** (`android/tools/e2e.ps1` or `.py`): install, `appops set <pkg>
  ACTIVATE_VPN allow`, `pm grant … POST_NOTIFICATIONS`, start via UI (uiautomator) and via
  intent/tile, verify tun0 + `dumpsys connectivity` VPN, probe requests OK (TCP+DNS+UDP DNS
  redirect to 77.88.8.8:1253), QUIC dropped, then stability: kill -9 app process → service
  restarts & VPN back ≤10 s; `svc wifi disable/enable` + airplane toggle → still works; doze
  (`dumpsys deviceidle force-idle`) → still works; 200 parallel probe requests → no crash;
  10-minute soak with periodic probes → RSS stable, no FD leak (`ls /proc/<pid>/fd | wc -l`
  stable), idle CPU ~0 (`top -n 1`); reinstall while running (`MY_PACKAGE_REPLACED`) → back up;
  `adb reboot` with startOnBoot → back up; always-on VPN (`settings put secure always_on_vpn_app`)
  → starts. Screenshots of main/settings in light+dark (`adb exec-out screencap -p`) and
  `dumpsys gfxinfo` jank numbers during a scripted interaction.
* Emulator caveat: the emulator's slirp NAT re-originates TCP on the host, so TTL tricks
  (fake/disorder) cannot be judged for *DPI efficacy* there, and fake payloads may even reach the
  server and break those connections. On the emulator, efficacy is out of scope; plumbing,
  stability and non-fake methods are in scope. Also note: the desktop GoodbyeDPI-UI is running
  on this host (don't touch it), so blocked sites are already unblocked for host traffic.
