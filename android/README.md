# GoodbyeDPI Android

Masaüstü GoodbyeDPI-UI'nin Android sürümü: VpnService + hev-socks5-tunnel + byedpi ile root
gerektirmeden DPI atlatma. Mimari ve sözleşmeler: [`docs/SPEC.md`](docs/SPEC.md).

## Derleme

Gerekenler: JDK 17, Android SDK (compileSdk 36), NDK 29.0.14206865. `local.properties` içinde
`sdk.dir` olmalı (git'e girmez).

```powershell
cd android
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17..."   # PATH'teki java eskiyse
.\gradlew.bat assembleDebug -Pgdpi.abi=x86_64     # geliştirme: tek ABI, hızlı
.\gradlew.bat dist                                 # sürüm: build\dist\GoodbyeDPI-Android(-<sürüm>).apk + SHA256SUMS.txt
```

Depo derin bir klasördeyse Windows yol sınırı için `-Pgdpi.buildDir=C:\t\b` ekleyin.

## İmzalama

Sürüm APK'sı `android/keystore.properties` ile imzalanır (git'e girmez):

```properties
storeFile=../goodbyedpi-release.jks
storePassword=...
keyAlias=goodbyedpi
keyPassword=...
```

Dosya yoksa derleme bozulmaz ama hata ayıklama anahtarıyla imzalanır. **Yayımlanan her sürüm aynı
anahtarla imzalanmalı**: Android farklı imzalı bir güncellemeyi reddeder, uygulama kendini
güncelleyemez. Anahtarı yedekleyin.

## Yayımlama

1. `app/build.gradle.kts` içinde `appVersionName` ve `appVersionCode`'u artırın.
2. `powershell -NoProfile -ExecutionPolicy Bypass -File android\tools\release.ps1 -BuildDir C:\t\rel -DryRun`
   — `dist`'i derler, imzayı doğrular, SHA-256'yı hesaplar, `release-notes.md` yazar ve GitHub'a
   yalnızca okuma istekleriyle yayın denetimlerini yapar; hiçbir şey yayımlamaz.
3. Her şey geçtiyse aynı komutu `-DryRun` yerine `-Publish` ile çalıştırın (`gh auth login` gerekli).
   Betik Windows PowerShell 5.1 ile çalışır ve şu durumlarda **yayımlamayı reddeder**:
   * `keystore.properties` yok ya da APK sabitlenmiş sürüm sertifikasıyla (SHA-256
     `b819e563…74b5`) imzalı değil (hata ayıklama anahtarı `CN=Android Debug` dahil);
   * `versionName` veya `versionCode` GitHub'daki en yeni `android-v*` sürümünden büyük değil
     (önceki `versionCode` sürüm notundaki `versionCode: N` satırından, yoksa APK'dan okunur);
   * `android-v<sürüm>` sürümü ya da etiketi zaten var.
4. Etiket `android-vX.Y.Z`, sürüm **`--latest=false`** ile, iki APK adıyla
   (`GoodbyeDPI-Android.apk`, `GoodbyeDPI-Android-<sürüm>.apk`) ve SHA-256 satırlı notla
   yayımlanır: aynı depodaki masaüstü güncelleyicisi `/releases/latest`'i okuyor, Android
   sürümünü görmemeli.

## Otomatik güncelleme nasıl çalışır

* Denetim (Otomatik güncelle açıksa, en fazla 6 saatte bir): uygulama her öne geldiğinde, VPN
  motoru başladığında ve 6 saatte bir çalışan kalıcı bir `JobScheduler` işinde (VPN süreci
  günlerce açık kalsa da denetim sürer). `GET /repos/unsalable/goodbydpi/releases?per_page=100`;
  ilk sayfada Android sürümü yoksa `Link: rel="next"` ile en fazla 3 sayfa geriye gidilir.
* Yalnızca `android-v` ile başlayan etiketler aday; taslak ve ön sürümler atlanır. Dosya:
  `GoodbyeDPI-Android.apk`, yoksa `GoodbyeDPI-Android-<sürüm>.apk` (başka `.apk`'lar seçilmez).
  Sürüm etiketten okunur ve `versionName` ile sayısal karşılaştırılır.
* APK `cacheDir/updates`'e indirilir ve SHA-256 ile doğrulanır (GitHub'ın `digest` alanı, yoksa
  sürüm notunda APK adının yanındaki 64 haneli özet). Uyuşmazsa dosya silinir.
* Paket adı, sürüm kodu ve imza kontrol edilip `PackageInstaller` oturumuyla kurulur; uygulama
  arka plandayken de. Android 12+ cihazlarda kurulum kaydının sahibi uygulamanın kendisiyse
  (`UPDATE_PACKAGES_WITHOUT_USER_ACTION` + `USER_ACTION_NOT_REQUIRED`) onay sormadan geçer.
  Tarayıcıdan kurulmuş bir uygulamanın ilk güncellemesi sistem onayı ister: arayüz açıksa onay
  ekranı hemen açılır; değilse "Güncelleme onay bekliyor" bildirimi çıkar ve uygulama bir
  sonraki açılışında onay ekranı kendiliğinden gelir.
* Bulunan sürüm süreçler arasında hatırlanır: süreç ölse de bir sonraki açılışta 6 saat
  beklenmeden kurulur. "Daha sonra" / indirmeden vazgeçmek 24 saatlik bir ertelemedir; hata
  bandını kapatmak hiçbir şey kaydetmez (sonraki denetim yeniden dener). Paketi yanlış olan bir
  sürüm (eski `versionCode`, başka imza) otomatik olarak bir daha indirilmez; elle
  "Tekrar dene" yine dener.
* "Bilinmeyen uygulamaları yükle" izni yoksa arayüz kullanıcıyı ayar ekranına yönlendirir;
  izin verilip uygulamaya dönülünce güncelleme kaldığı yerden sürer.

## Test araçları (`tools/`)

* `e2e.py` — emülatörde uçtan uca test (`py -3 tools\e2e.py --serial emulator-5554 --apk ... --probe-apk ...`,
  `--list`, `--only a,b`, cihaz genelini etkileyen adımlar için `--allow-disruptive`).
* `:probe` modülü — VPN'in içinden HTTP/DNS/UDP/QUIC ölçen yardımcı uygulama (dağıtılmaz);
  kullanım `probe/src/main/java/.../ProbeActivity.kt` başındaki açıklamada.
* Hata ayıklama derlemesinde `DebugUpdateReceiver` güncelleyiciyi adb'den sürer
  (`--es cmd check|open|bg|install|conntest ...`, ayrıntı dosyanın başında). Gerçek periyodik
  iş: `adb shell cmd jobscheduler run -f <paket> 4201`.
* Ayarlar > BAĞLANTI TESTİ motor açıkken byedpi'ye adı değil IP'yi verir: ad, seçili DNS'e
  vekil üzerinden (198.18.0.53:53, DNS-over-TCP) sorulur; DNS "Kapalı"ysa sistem çözücüsüne düşer.
