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
  * hev-socks5-tunnel — https://github.com/heiher/hev-socks5-tunnel @ `d9dca26c` (tag 2.18.0) with
    its submodules vendored as plain files (src/core = hev-socks5-core `ab2a15a8`,
    third-part/hev-task-system `328f35d9`, third-part/lwip `e22c9d28`, third-part/yaml `9e7614d4`;
    core and yaml differ from the 2.18.0 gitlinks only in docs/license files, see PATCHES.md).
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
| `default` | Varsayılan | fake + disorder: `--disorder 2 --split 0+hm --fake -1 --ttl 5` |
| `fixedttl` | Sabit TTL | `--fake -1 --ttl 5` |
| `disorder` | Ters sıra | `--disorder 2` (desktop: split@2 reversed) |
| `ttl4` / `ttl3` | Sahte TTL 4 / 3 | `--fake -1 --ttl 4` / `3` |
| `md5sig` | MD5 imzası | `--fake -1 --ttl 5 --md5sig` (kernel without TCP_MD5SIG, e.g. GKI: `--md5sig` omitted, TTL only) |
| `md5ttl3` | MD5 + TTL 3 | `--fake -1 --ttl 3 --md5sig` (same MD5 rule) |
| `fakesplit5` | Bölünmüş sahte | `--fake 2 --fake -1 --ttl 5` (one coherent fake cut at byte 2, byedpi patch B8) |
| `zerofake` | Boş sahte | `--fake -1 --ttl 5 --fake-data ':\x00\x00\x00\x00'` |
| `split2` | Düz bölme | `--split 2` |
| `split` | Sadece bölme | `--split 2 --split 0+hm` |
| `tlsrec` | TLS kayıt bölme (Android'e özel) | `--tlsrec 3+s` |
| `checksum` | (desktop only) | not offered on Android; stored id resolves to `default` |

(Table corrected to the as-built argv; each group is preceded by `--proto=tls,http` or
`--proto=tls`, and every TLS fake also gets `--fake-sni <fakeSni>`. Verified rows and reasons:
BYEDPI_NOTES §7.2; what changed and why: §8.)

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
  (As built, with smart mode on the primary group is a no-desync group and the selected method
  is the first fallback — §8 Engine mapping.)
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
  machine), AGP **8.13.x** (built: 8.13.2), Kotlin **2.2.x** (built: 2.2.21; +
  `org.jetbrains.kotlin.plugin.compose`, `plugin.serialization`), JDK 17 toolchain. Compose via
  the newest BOM that builds with compileSdk 36 on this AGP (built: 2026.06.01; 2026.08.00+
  needs compileSdk 37 + AGP 9) (step back if a newer one demands more). Version catalog in
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

## 8. Deviations (as built)

State after wave 4 (`0573715`). Sections 0–7 are the original contract; every place where the
code differs is listed here with the reason. Details: `BYEDPI_NOTES.md`, `HEV_NOTES.md`,
`app/src/main/jni/PATCHES.md`.

### Native (§1, §1.1)

* **byedpi stop wakes an eventfd owned by the glue, not `shutdown(server_fd)`** — the loop
  closes `server_fd` itself; a late `shutdown()` from another thread could hit a reused fd number.
* **`NativeBridge` returns specific codes** (`0`, `-1` start, `-2` args, `-3` busy, `-4` exited,
  `-5` internal) — the watchdog must tell retryable failures from `ByeDpiArgs` bugs.
* **More native patches than §1.1**: byedpi B4 (upstream bugs: `--pf` byte order, cache expiry,
  fake mmap leak, >31 groups, 32-bit `load_cache`), B5 `--deny-net`, B6 accept errors / backlog
  1024 / errno / 64-bit timers, B7 fallback trigger semantics, B8 coherent split fakes; hev P1–P5
  (sync config check, stale stop flag, tun-EOF busy loop, logger fd, `MSG_MORE` 200 ms handshake
  delay) — bugs and gaps found during integration; PATCHES.md has the evidence for each.

### Tun / VPN (§1.2)

* **`setMetered(false)` on API 29+** instead of the default — a Q+ VPN is metered by default;
  `false` inherits the underlying network, so Wi-Fi does not look metered.
* **`excludeLan` also drops 100.64.0.0/10 and 240.0.0.0/4** (with 224/4 → 224/3) — CGNAT is the
  carrier's own network with no DPI; 240/4 holds 255.255.255.255, and tunnelled broadcasts would
  leave through byedpi without `SO_BROADCAST`. Kept routed: `198.18.0.0/15`, `fd00:6764:7069::/64`.
* **Virtual nets are refused**: byedpi always gets `--deny-net 198.18.0.0/15 --deny-net
  fd00:6764:7069::/48` (a `--redirect` FROM still wins) — Private DNS probes `198.18.0.53:853`;
  without it a real SYN went to the ISP and the session hung. With DNS "Kapalı",
  `198.18.0.53:53` is refused too.
* **Cross-family DNS**: a profile with only an IPv6 address still redirects the IPv4 resolver
  (byedpi supports it); the IPv6 resolver is added only if the profile has v6 *and* IPv6 is on.
* **`setUnderlyingNetworks`**: API 31+ follows `registerBestMatchingNetworkCallback(INTERNET,
  NOT_VPN)`; below 31 it passes `null` and reads DNS from `registerDefaultNetworkCallback` — on
  S+ the default callback reports the VPN itself to its owner; "last `onAvailable` wins" picked
  cellular DNS while on Wi-Fi.

### Engine mapping (§1.3)

* **Base argv adds `-N`** (SOCKS domain requests get reply 08) — a blocking `getaddrinfo` would
  stall byedpi's single-threaded loop; hev and the connection tester only send IPs.
* **Preset table corrected** (`default` = disorder 2 + `0+hm` split + fake; `split` uses `0+hm`;
  `fakesplit5` = `--fake 2 --fake -1`) — `default` mirrors the desktop (split@2 reversed + SNI
  split; parts must ascend or byedpi cancels them); `+s` cancels the part on plain HTTP, `+h`
  splits the SNI or the Host.
* **MD5 on kernels without `TCP_MD5SIG`** (Android GKI): no separate TTL fallback group; patched
  byedpi sends the fake TTL-only on `ENOPROTOOPT`, and `ByeDpiArgs` probes once and omits
  `--md5sig` — so `md5sig`≡`fixedttl`, `md5ttl3`≡`ttl3` and fallback chains dedupe them.
* **`--ttl` is always emitted for fakes**, even when only MD5 is ticked — an unprotected fake
  reaches the server and breaks the connection.
* **Voice fakes use `--ttl 64`**, not the method TTL, in three UDP groups (50000-65535,
  3478-3481, 19294-19344) — the desktop sends them with the real packet's TTL (proven on Turkish
  ISPs), a low TTL only risks the DPI missing them; byedpi keeps one `--pf` per group.
* **Fallback layout**: per distinct fallback `--auto=torst,ssl_err <group> --cache-ttl 3600`,
  then `--timeout 4:0:0:1`; no `redirect` trigger — legitimate cross-domain HTTP redirects would
  cycle through every group; `:1` lifts the timeout once the server answers (mobile stalls).
* **Smart mode (`AppSettings.smartMode`, default on; not in the original spec)**: the first TCP
  group is only the scope (`--proto=tls,http`, no desync); the selected method becomes the first
  `--auto=torst,ssl_err` group, followed by the ISP alternatives when `autoFallback` is on (the
  method stays even when it is off). Reason: a user on a Turkish line could not use Google
  search — fakes use a fixed low TTL, and Google's in-ISP caches / nearby CDNs are within that
  TTL, so the fake reaches the real server and breaks the connection. Reproduced on the emulator,
  whose slirp NAT delivers every fake: www.google.com answers the `ttl4` fake ClientHello (byedpi
  log: 2.9 KB server flight → `ssl_err`), v1.0.0 only recovered through the `disorder` fallback
  (first request 2.3 s vs 1.1 s), and with `autoFallback` off Google search, YouTube, example.com
  and discord.com failed 3/3; smart mode: 200 everywhere, only the direct group used, and with a
  simulated SNI-RST DPI the blocked host still opens via the method. Unblocked hosts are never touched; a blocked host
  costs one transparent replay (RST) or the 4 s timeout (silent drop) once per IP:port per hour.
  Off = the exact v1.0.0 argv. No built-in "never desync" host list (`--hosts`): a static
  exemption group ends the fallback chain, so a future block of an exempted service (YouTube was
  blocked in Turkey before) could not be bypassed. BYEDPI_NOTES §7.3.

### Models, state and controller (§2)

* **ISP lists are fake-first** (e.g. TT `ttl4, disorder, ttl3, default`; Superonline `ttl3,
  md5sig, disorder, md5ttl3`), not desktop-identical — the desktop "Ters sıra" relies on seqovl,
  which a kernel socket cannot produce; live desktop tests showed only fake methods working
  (BYEDPI_NOTES §7.4). Untested on a Turkish line.
* **`DpiConfig` adds `fakeTtl` and `splitFake`; `splitPosition` is 1..64** (desktop: 0 = none) —
  byedpi's SNI split is a separate part, so the fixed position must be a real byte offset.
* **`AppSettings` adds `lastUpdateCheck`, `pendingUpdate`, `dismissedUpdate`** — the last is
  legacy and unread; update snooze state lives in SharedPreferences `gdpi_update`.
* **`AppSettings` adds `smartMode` (default true, also for a missing key) and
  `settingsVersion` (2)** — see Engine mapping. A file without `settingsVersion` is a 1.0.0 file
  (`encodeDefaults` wrote every other field, so an untouched default cannot be told apart
  otherwise); `SettingsRepository` upgrades it once via `AppSettings.upgradedFrom`.
* **Default DNS is Yandex (1253), not Cloudflare** (desktop keeps Cloudflare) — Turkish ISPs
  hijack port 53, so Cloudflare:53 returned the ISP's poisoned answer for blocked hosts on the
  Genel profile, which does not set a DNS. Unknown/deleted DNS selections also fall back to
  Yandex. Upgrade from 1.0.0: only `isp=general` + `dns=cloudflare` (the untouched default)
  moves to Yandex, once; ISP profiles, custom entries and "Kapalı" are kept, and re-selecting
  Cloudflare afterwards sticks.
* **`EngineState.Running` adds `socksPort`, `argv` and `generation`** — the connection test
  needs the port, the diagnostics screen shows the argv actually running, and `generation`
  (`DpiEngine.generation`, new on every start and on every in-place update that swaps byedpi or
  the tun; not on a name-only change) tells the UI that the engine changed while the port
  stayed the same.
* **`ServiceController` adds `recoverIfNeeded`, `recoverInBackground` (internal),
  `EXTRA_CONNECT`, `CONNECT_ALIAS`** — process-death recovery and the tile's connect request.
* **`SettingsRepository` adds `updateNow()` (non-suspending) and CAS updates; the service writes
  `wantRunning` with `runBlocking`** — suspending in the single-thread engine queue let a queued
  action (START during STOP) interleave.
* **More files than §2 suggests** (`ByeDpiRunner`, `VpnTunBuilder`, `Recovery`, `KeeperService`,
  `RecoveryJobService`, `RestartPolicy`, `UpdateManager`, `UpdateMemo`, `UpdateJobService`,
  `DnsWire`, …) — split by responsibility.

### Service & stability (§3)

* **`START_STICKY` does not restart a VPN service on API 36** — the kernel closes the tun, `Vpn`
  unbinds with `DeadObjectException` and AMS drops the record. Added: `KeeperService` (plain,
  non-exported START_STICKY service that `Vpn` does not bind; restarted ~1 s after death, then
  recovers), recovery in `App.onCreate`, and a persisted 15-min `RecoveryJobService` backstop.
  Background recovery only runs while armed (`arm()` on every engine start, and the flag alone
  already when the service accepts a user/boot/always-on START, so a process death during the
  first start's retry backoff is still recovered; `disarm()` on user
  stop, revoke and `fail()` clears an explicit flag, so a later process birth in the same boot,
  e.g. the 6-hourly update job, does not reopen a failed VPN), in the boot the engine was armed
  in (after a reboot `BootReceiver` decides) and never in instrumentation processes. The same
  gate applies to an AMS sticky restart of `DpiVpnService` (null intent). UI and tile recovery
  still retry.
* **Crash loops: one-shot check job + give-up** — a second native crash within AMS's crash
  window makes AMS delay the keeper restart by 30-60 min. Every background recovery therefore
  also schedules a non-persisted `RecoveryJobService` run (`CHECK_JOB_ID`, min latency 30 s
  after the 1st consecutive recovery, 1 min after the 2nd-4th, 30 s again after the 5th, whose
  check only has to see the death and give up; deadline +30 s); JobScheduler is not subject to
  the crash penalty. An unconstrained job was seen to run at its deadline rather than its min
  latency (emulator, ACTIVE bucket), once even ~35 s past it, so after the AMS penalty a crash
  is recovered within about checkDelay + 30 s (1-1.5 min) and a 6th crash is reported within
  about 1 min. The delay used to grow to 8 min, which left the VPN down and the give-up alert
  8 min late (E2E-V7-2); the battery argument for a long backoff is weak because a counted
  loop ends at the give-up and a check that finds the engine up finishes in-process in
  milliseconds. If the engine is up, the check keeps watching (every 1 min) for 10 min after
  the last recovery. Only crash-like deaths
  count toward the give-up (API 30+ `ApplicationExitInfo` reason `CRASH`, `CRASH_NATIVE`,
  `ANR`, `EXIT_SELF`, `INITIALIZATION_FAILURE`, `EXCESSIVE_RESOURCE_USAGE` or `UNKNOWN`, and
  `SIGNALED` whose status is a crash signal: SIGILL, SIGTRAP, SIGABRT, SIGBUS, SIGFPE, SIGSEGV,
  SIGSYS; AMS files a native crash as `SIGNALED`/11 when the debuggerd report is missing or
  loses the race with the zygote death notice; no
  record, API < 30 or no death newer than the last arm/recovery also counts); `LOW_MEMORY`,
  `SIGNALED` with SIGKILL/SIGTERM (kill -9, OEM task killers), `OTHER` etc. are recovered
  without counting — the
  give-up exists for a deterministic native crash loop, and LMK kills every ~10 min on a low-RAM
  phone would otherwise disable auto-reconnect within an hour. The count also resets once the
  engine has run 10 min without interruption (service health loop, and a check job that finds
  it Running after the watch window). Counted recoveries less than 15 min apart are
  consecutive; the 6th in a
  row is not attempted: background recovery is disarmed, the stale foreground record AMS keeps
  for the dead service (its "Bağlı" notification stayed up) is released by starting the service
  with `ACTION_REFRESH_NOTIFICATION` (no engine -> leave foreground, stop), and an alerts
  notification "Bağlantı koptu"
  (tap: `.ConnectRequest` + `EXTRA_CONNECT`) is posted. A user/tile/boot start
  resets the count, and so does opening the UI or the tile panel even while a background start
  from the same cold process is already under way.
* **Stale "Bağlı" notification after a process death (known limitation)** — the foreground
  notification of the dead `DpiVpnService` stays in the status bar (AMS keeps the record)
  until our process runs again; a dead process cannot remove it. Usually that is ~1 s (keeper
  restart). After a second crash within AMS's crash window the keeper is deferred 30-150 min,
  and the window is then bounded by the check job: about 1-1.5 min (it was up to 8.5 min,
  E2E-V7-1). When the new process does not recover (background recovery not armed, the user
  stopped the app), `recoverInBackground` sees the status notification in
  `NotificationManager.getActiveNotifications()` while the engine is `Stopped` and releases the
  record the same way as the give-up (start with `ACTION_REFRESH_NOTIFICATION`, leave
  foreground, stop); with no such notification nothing is started. The status notification text
  is not made neutral: while it is up it is correct except for this bounded window.
  If VPN consent is gone (another VPN app was prepared while our process was dead), background
  recovery clears `wantRunning` and disarms like `onRevoke`; on API 31+ the stale record cannot
  be released from the background (the FGS start exemption is `OP_ACTIVATE_VPN`), so it stays
  until AMS's deferred sticky restart or the next app open. The in-process dedupe of recovery requests
  (5 s) only records a start that was actually issued: a give-up in `App.onCreate` does not make
  the UI's recovery a moment later a silent no-op.
* **User stops are honoured** — recovery first checks `ApplicationExitInfo` for
  `REASON_USER_REQUESTED` newer than the last arm (API 30+, not within 60 s of a package update)
  or, below 30, a cancelled backstop job; then it clears `wantRunning`. Force stop and "Durdur" in
  Active apps must not be undone.
* **Settings changes apply in place instead of a full `RESTART`** — `DpiEngine.reconfigure`
  swaps only byedpi (same port) when the argv changes, and establishes a new tun while the old fd
  is still open when routes/addresses/VPN DNS change; name-only changes just relabel. Apps never
  see the VPN network drop. `RESTART` + `EXTRA_FORCE` still rebuilds fully.
* **DNS "Kapalı"**: the tun is rebuilt in place (1 s settle) when the underlying network's
  effective DNS set changes — the old network's resolvers are unreachable on the new one.
* **Watchdog**: byedpi exit is a thread callback, hev is polled every 20 s (idle CPU); budget
  as specified; after `Failed` background recovery is disarmed so it does not loop on the error.
* **`fail()`/`onRevoke` call `stopSelf(lastStartId)`** — a START queued meanwhile keeps the
  service alive.
* **The tile's connect request goes through the non-exported `activity-alias .ConnectRequest`**
  (Recents relaunches ignored) — `MainActivity` is exported; any app could send `EXTRA_CONNECT`.

### UI (§4)

* **Pickers are custom in-window sheets (`GdpiSheet`), not `ModalBottomSheet`** — Material's
  sheet opens a Dialog window each time (150–200 ms first frame on the emulator).
* **The Connected halo pulses 3 times, then stays static** — a continuous pulse drew frames
  forever (611 frames per idle 10 s before, 0 after).
* **Extra text tokens `successText` / `dangerText` / `accentText`** (light `#047857` / `#B91C1C`
  / `#4F46E5`, dark = SPEC colours) for small text and glyphs — the SPEC light colours are below
  4.5:1 on white; fills, rings and dots keep the SPEC palette.
* **Theme uses `UiModeManager.setApplicationNightMode` (API 31+) + `configChanges="uiMode"`** —
  no activity recreation; below 31 the starting window follows the system mode.
* **Notification permission is asked after VPN consent, not together** — two system dialogs at
  once; a refused consent should not be followed by an unrelated prompt. The service is already
  foreground by then and its notification was dropped, so on "Allow" the UI sends
  `ACTION_REFRESH_NOTIFICATION`, which just re-runs `startForeground` (status + Durdur appear
  in the first session).
* **"+ Yeni DNS" / "+ Yeni özel ayar" reuse the untouched placeholder** — `migrate()` keeps one
  entry per list; while that entry still has the default name and no addresses (DNS) or the
  default `DpiConfig` (method), the new entry takes its slot instead of adding "Özel DNS 2".
* **Connection test results are cleared** when a run starts and when the running engine changes:
  reconnect, stop, and an in-place update after a method/DNS change, which keeps the port
  (keyed on port + `generation`); a running test is cancelled on the same trigger so it cannot
  mix results from the old and the new engine.
* **Additions**: landscape two-pane main screen; the diagnostics sheet shows the running argv
  (`describe()` only when disconnected); the licenses list also shows `THIRD-PARTY-NOTICES.txt`
  (AndroidX, Compose, Kotlin; Apache-2.0).
* **Connection test resolves names itself** — DNS-over-TCP to `198.18.0.53:53` through the proxy
  (the selected DNS via `--redirect`), system resolver if refused (DNS "Kapalı"), then SOCKS
  CONNECT to the IP with SNI/hostname checks on the name; required by `-N`.

### Updates (§5)

* **Release scheme**: tag `android-vX.Y.Z`, `gh release create … --latest=false`, assets
  `GoodbyeDPI-Android.apk` + `GoodbyeDPI-Android-<ver>.apk`, notes with SHA-256 lines and
  `versionCode: N` (`tools/release.ps1`) — the desktop updater in the same repo reads
  `/releases/latest` and must never see the APK.
* **Query**: `/releases?per_page=100`, following `Link: rel="next"` (same scheme/host/port) for
  at most 3 pages until a page holds an Android release; only `android-v*` tags and only the two
  asset names are accepted — the repo is shared with frequent desktop releases, and a stray APK
  on a desktop `v2.x` would shadow every Android version.
* **SHA-256**: the asset's GitHub `digest` (`sha256:…`) first, else the 64-hex hash next to the
  APK name in the notes; with neither, only the package checks guard (package name, higher
  `versionCode`, same signing certificate) — rejecting a good file on an ambiguous hash is worse.
* **Installs without a banner tap when `autoUpdate` is on, also in the background**; on Android
  12+ `UPDATE_PACKAGES_WITHOUT_USER_ACTION` + `USER_ACTION_NOT_REQUIRED` make it silent once the
  app is its own installer of record; otherwise the system confirm opens if the UI is visible,
  else a "Güncelleme onay bekliyor" notification — same behaviour as the desktop self-update.
* **When**: every foreground transition (6 h throttle), after the engine starts, and a persisted
  JobScheduler job (id 4201, 6 h, 1 h flex, any network); a remembered found version skips the
  throttle while the user is present — the VPN keeps the process alive for days, so
  activity-only checks rarely ran.
* **Snooze / bad versions**: "Daha sonra" or a cancelled confirm = 24 h snooze; a version that
  fails the package checks or is rejected by the installer is not auto-downloaded again (a
  manual retry still is) — a broken release must not be fetched on every check.

### Build (§6)

* **Pins stepped back** where newer releases need compileSdk 37 + AGP 9: core-ktx 1.18.0,
  lifecycle 2.10.0, Compose BOM 2026.06.01; kotlinx.serialization 1.9.0 (1.10 pulls Kotlin 2.3).
* **Build dirs**: ndk-build staging in `android/.cxx` (`-Pgdpi.cxxDir`), all outputs relocatable
  with `-Pgdpi.buildDir=C:\t\<name>` — `make.exe` crashes when `NDK_OUT` reaches ~214 chars.
  Also `-Pgdpi.abi` (single-ABI dev build; `dist` refuses it) and `-Pgdpi.appIdSuffix` (debug).
* **`dist` also writes `SHA256SUMS.txt`; ignores live in `android/.gitignore`;
  `localeFilters = tr`** (the UI is Turkish only).
* **Release signing** uses `android/keystore.properties` → `release.jks`; `release.ps1` refuses
  to publish unless the certificate SHA-256 is the pinned `b819e563…74b5` — an update signed
  with another key cannot install over existing installs.

### Testing (§7)

* **UDP smoke runs on the device**: `tools/native/udp_socks_test.c` against an ASan `ciadpi`,
  not a host Python client — `adb forward` carries TCP only. `smoke.py` also runs tcpdump wire
  checks, an iptables DPI simulation, `restart_test` and `JniSmoke.java` (R8 APK). tcpdump runs
  with `--immediate-mode`: on-device libpcap 1.10.5 uses TPACKET_V3 and otherwise hands packets
  over only when a ring block fills or its ~1 s timeout expires, so the SIGINT right after curl
  dropped them (wire rows failed at random with only the SYN captured).
* **curl uses `--socks5 -4`** (names resolved on the host) because of `-N`.
* **`tools/e2e.py` finds the tun by `198.18.0.1`, not `tun0`** — an in-place rebuild can bring up
  `tun1`. Device-wide steps (Wi-Fi, doze, reboot, always-on) need `--allow-disruptive`.
* **`tools/native/tun_latency.sh`** (manual, root) measures hev session latency (P5).

### Known limitations (accepted)

* **The loopback SOCKS5 port has no authentication** — another local app could relay through our
  VPN-excluded uid (dodging Data Saver / per-app limits) or detect the bypass. Low impact (needs a
  hostile app, exposes no data); a fix needs RFC 1929 auth in byedpi, hev, the tester and tools.
* **TCP half-close is unsupported** (byedpi upstream; BYEDPI_NOTES §10).
* **Not yet verified**: DPI efficacy on a real Turkish line, the API 24–30 recovery/network
  paths (JVM tests only) and the 32-bit timer fix (compile only).
