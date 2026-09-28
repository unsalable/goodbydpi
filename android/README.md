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
2. `powershell -ExecutionPolicy Bypass -File android\tools\release.ps1` — `dist`'i derler,
   SHA-256'yı hesaplar, `release-notes.md` yazar ve çalıştırılacak `gh release create` komutunu
   gösterir. Gerçekten yayımlamak için `-Publish` ekleyin (`gh auth login` gerekli).
3. Etiket `android-vX.Y.Z`, sürüm **`--latest=false`** ile yayımlanır: aynı depodaki masaüstü
   güncelleyicisi `/releases/latest`'i okuyor, Android sürümünü görmemeli.

## Otomatik güncelleme nasıl çalışır

* Uygulama açılışta (Otomatik güncelle açıksa, en fazla 6 saatte bir) ve VPN servisi çalışırken
  arka planda `GET /repos/unsalable/goodbydpi/releases?per_page=30` sorgular.
* Taslak ve ön sürümler atlanır; `.apk` dosyası olmayan (masaüstü `v2.x`) sürümler hiç aday
  olmaz. Dosya önceliği: `GoodbyeDPI-Android.apk`, sonra `GoodbyeDPI-Android-*.apk`, sonra
  herhangi bir `.apk`. Sürüm etiketten okunur (`android-v1.2.3`, `v1.2.3`, `1.2.3`) ve
  `versionName` ile sayısal karşılaştırılır.
* APK `cacheDir/updates`'e indirilir ve SHA-256 ile doğrulanır (GitHub'ın `digest` alanı, yoksa
  sürüm notunda APK adının yanındaki 64 haneli özet). Uyuşmazsa dosya silinir.
* Paket adı, sürüm kodu ve imza kontrol edilip `PackageInstaller` oturumuyla kurulur. Android 12+
  cihazlarda `UPDATE_PACKAGES_WITHOUT_USER_ACTION` sayesinde kendi güncellemesi onay sormadan
  geçer (emülatörde adb ile kurulmuş uygulamada bile doğrulandı); gerekirse sistem onay ekranı
  açılır, uygulama arka plandaysa "Güncelleme onay bekliyor" bildirimi çıkar.
* "Bilinmeyen uygulamaları yükle" izni yoksa arayüz kullanıcıyı ayar ekranına yönlendirir.

## Test araçları (`tools/`)

* `e2e.py` — emülatörde uçtan uca test (`py -3 tools\e2e.py --serial emulator-5554 --apk ... --probe-apk ...`,
  `--list`, `--only a,b`, cihaz genelini etkileyen adımlar için `--allow-disruptive`).
* `:probe` modülü — VPN'in içinden HTTP/DNS/UDP/QUIC ölçen yardımcı uygulama (dağıtılmaz);
  kullanım `probe/src/main/java/.../ProbeActivity.kt` başındaki açıklamada.
* Hata ayıklama derlemesinde `DebugUpdateReceiver` güncelleyiciyi adb'den sürer
  (`--es cmd check|open|install|conntest ...`, ayrıntı dosyanın başında).
