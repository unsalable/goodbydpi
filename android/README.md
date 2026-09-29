# GoodbyeDPI Android

Masaüstü GoodbyeDPI-UI'nin Android sürümü: tek düğmeyle Türk ISS'lerinin DPI engelini (Discord,
Roblox, …) root gerektirmeden aşar. Uygulama bir VPN kurar; trafik telefonun içinde
hev-socks5-tunnel'dan byedpi'ye gider, byedpi istekleri bölüp sahte paketlerle DPI'ı şaşırtır ve
DNS'i seçilen sunucuya yönlendirir. Trafik uzak bir aracı sunucudan geçmez, doğrudan hedefe
gider. Android 7.0 ve üstü.
Mimari ve sözleşmeler: [`docs/SPEC.md`](docs/SPEC.md) (§8: tasarımdan sapmalar ve nedenleri).

## Kurulum

1. [Releases](https://github.com/unsalable/goodbydpi/releases) sayfasında etiketi `android-v`
   ile başlayan en yeni sürümü açın ve `GoodbyeDPI-Android.apk` dosyasını indirin. Android
   sürümleri "Latest" olarak işaretlenmez (o işaret masaüstü sürümündedir), listede aşağıda
   kalabilir.
2. Dosyayı açın. Android, APK'yı açtığınız uygulama (tarayıcı ya da dosya yöneticisi) için
   **Bilinmeyen uygulamaları yükle** izni ister: izin verip geri dönün ve **Yükle**'ye dokunun.
   Play Protect uyarı gösterirse ayrıntılardan yine de yüklemeyi seçin.
3. Bundan sonraki sürümleri uygulama kendisi kurar (bkz. [Otomatik güncelleme](#otomatik-güncelleme)).

## İlk çalıştırma

1. Ana ekrandaki **SAĞLAYICI** kutucuğundan internet sağlayıcınızı seçin (Türk Telekom,
   Superonline, Vodafone, TürkNet, Kablonet, TT Mobil, Turkcell Mobil, Vodafone Mobil). Bu, o hat
   için önerilen yöntemi ve Yandex DNS'i (77.88.8.8, port 1253) birlikte seçer. Listede yoksa
   **Genel** kalsın; o zaman DNS'e dokunulmaz, DNS kutucuğundan **Yandex (1253)** seçmeniz önerilir.
2. Güç düğmesine dokunun. Android bir **bağlantı isteği** (VPN izni) sorar: **Tamam**.
3. Android 13 ve üstünde ardından bildirim izni bir kez sorulur. İzin verirseniz bağlantı açıkken
   "Bağlı · <yöntem>" bildirimi ve **Durdur** düğmesi görünür, hatalar "Uyarılar" kanalına düşer.
   Reddetseniz de bağlantı çalışır.
4. Durum "Bağlı" olunca hazırsınız. Ayarlar → **BAĞLANTI TESTİ** discord.com, roblox.com ve
   example.com'u motor üzerinden dener. İsterseniz hızlı ayarlar paneline **GoodbyeDPI**
   karosunu ekleyip oradan açıp kapatabilirsiniz.

## Önerilen telefon ayarları

* **Pil optimizasyonu kapalı.** Ayarlar → ARKA PLAN → **Pil optimizasyonunu kapat** satırı
  "Kapalı" göstermeli; göstermiyorsa dokunup izin verin. Xiaomi, Samsung, Huawei gibi
  üreticilerin ayrıca "otomatik başlatma / arka planda çalışma" kısıtları varsa GoodbyeDPI'ı
  onlardan da muaf tutun; yoksa sistem bağlantıyı arka planda kapatabilir.
* **Her zaman açık VPN (isteğe bağlı).** Ayarlar → ARKA PLAN → **Her zaman açık VPN** Android'in
  VPN ekranını açar; GoodbyeDPI'ın yanındaki dişliden açın. Böylece bağlantıyı telefon açılınca
  ve uygulama kapansa bile Android kendisi kurar. Uygulamanın **Açılışta başlat** ayarı
  (varsayılan açık) bunu zaten büyük ölçüde yapar; bu ek bir güvencedir. Hemen altındaki
  VPN'siz bağlantıları engelleme seçeneğini açarsanız GoodbyeDPI kapalıyken internet tamamen
  kesilir; bunu istemiyorsanız kapalı bırakın.
* **Özel DNS: "Otomatik" ya da "Kapalı".** (Ayarlar → Ağ ve internet → Özel DNS; Samsung'da
  Bağlantılar → Diğer bağlantı ayarları → Özel DNS.) Neden: uygulama Android'e tünelin içindeki
  sanal bir DNS sunucusunu (198.18.0.53) verir ve bu sorguları uygulamada seçtiğiniz sunucuya ve
  porta yönlendirir; ISS'lerin 53. porttaki DNS korsanlığı böyle aşılır.
  * **Otomatik:** Android önce sanal sunucuya şifreli DNS (DoT, port 853) dener; uygulama bunu
    anında reddeder, Android normal DNS'e döner ve yönlendirme çalışır.
  * **Kapalı:** normal DNS, yönlendirme çalışır.
  * **Özel DNS sağlayıcı ana makine adı** (ör. `dns.google`): bütün sorgular şifreli olarak
    doğrudan o sağlayıcıya gider; uygulamadaki DNS seçimi hiç kullanılmaz. Sağlayıcıya
    ulaşılabildiği sürece çalışır, ama ISS onu engellerse hiçbir site açılmaz. Sorun yaşarsanız
    "Otomatik"e alın.
  * Uygulamada DNS **Kapalı** seçilirse sorgular tünelden geçer ama ağın kendi DNS'ine gider;
    ISS DNS'i kaçırıyorsa bazı siteler açılmayabilir.

## Otomatik güncelleme

Ayarlar → GENEL → **Otomatik güncelle** açıkken (varsayılan):

* **Ne zaman:** uygulama her öne geldiğinde, VPN motoru her başladığında ve Android'in 6 saatte bir
  çalıştırdığı kalıcı bir arka plan işinde; son denetimden 6 saat geçmediyse GitHub'a sorulmaz.
  Ayarlar → HAKKINDA → **Güncellemeleri denetle** bu sınırı beklemeden denetler.
* **Ne aranır:** yalnızca `android-v` ile başlayan, taslak ya da ön sürüm olmayan sürümler ve
  içlerindeki `GoodbyeDPI-Android.apk` (yoksa `GoodbyeDPI-Android-<sürüm>.apk`). Masaüstü
  sürümleri ve başka `.apk`'lar yok sayılır.
* **İndirme ve doğrulama:** APK indirilir ve SHA-256'sı doğrulanır (GitHub'ın dosya özeti, yoksa
  sürüm notunda APK adının yanındaki özet); uyuşmazsa dosya silinir. Kurmadan önce paket adı,
  daha yüksek sürüm kodu ve aynı imza sertifikası da kontrol edilir.
* **Kurulum:** Android 12 ve üstünde, uygulama kendi kurulumunun sahibiyse güncelleme **hiç
  sormadan**, uygulama arka plandayken bile kurulur. Tarayıcıdan kurulmuş bir uygulamanın **ilk**
  güncellemesi ve Android 11 ve altı sistem onayı ister: arayüz açıksa onay ekranı hemen gelir,
  değilse "Güncelleme onay bekliyor" bildirimi çıkar. İlk kez GoodbyeDPI'ın kendisine
  **Bilinmeyen uygulamaları yükle** izni vermeniz istenir (Android 8+).
* **Sonrası:** bağlantı açıktıysa güncellemeden sonra kendiliğinden geri gelir; "GoodbyeDPI
  güncellendi" bildirimi ve açılışta "Güncellendi: sürüm X" notu gösterilir.
* "Daha sonra" ya da onay ekranında vazgeçmek 24 saat erteler. Paketi hatalı bir sürüm (başka imza,
  eski sürüm kodu) otomatik olarak bir daha indirilmez; elle "Tekrar dene" yine dener.

## Sorun giderme

* **Bir site açılmıyor / yöntem işe yaramıyor.**
  1. Doğru sağlayıcının seçili olduğundan emin olun (mobil veride "… Mobil" profilleri).
  2. **Otomatik yedek yöntem** (varsayılan açık) bir sitede bağlantı sıfırlanır ya da takılırsa
     sağlayıcının diğer yöntemlerini sırayla dener: ilk açılış 4 sn kadar sürebilir, çalışan
     yöntem o adres için 1 saat hatırlanır. Bağlantıyı kapatıp açmak ya da yöntemi değiştirmek bu
     belleği sıfırlar.
  3. Olmazsa **YÖNTEM** listesinden sağlayıcının alternatiflerini (listedeki sırayla) elle deneyin.
     Sahte paketli yöntemlerde TTL önemlidir: fazla düşükse DPI sahteyi görmez, fazla yüksekse
     sahte sunucuya ulaşıp bağlantıyı bozar. Ayarlar → YÖNTEM → **+ Yeni özel ayar** seçili
     yöntemin kopyasını açar; orada TTL'i birer birer değiştirerek deneyebilirsiniz.
  4. DNS olarak **Yandex (1253)** kullanın; Cloudflare 53. portta olduğu için ISS kaçırabilir.
  5. Tarayıcının önbelleği eski sonucu tutabilir: gizli sekmede deneyin.
* **Discord sesli kanala giriyor ama ses bağlanmıyor.** Bütün hazır yöntemlerde "Discord ses ve
  aramalar (UDP)" açıktır: ses bağlantısının ilk paketlerinden önce sahte UDP paketleri gider.
  Sahteler yalnızca bağlantının başında gittiği için GoodbyeDPI bağlıyken sesli kanala yeniden
  girin; gerekirse Discord'u tamamen kapatıp açın. Özel ayarda bu anahtarın açık olduğundan emin
  olun; gerekirse "Sahte UDP tekrar sayısı"nı artırın (varsayılan 6, en çok 20).
* **Neyi test etmeli:** Ayarlar → **BAĞLANTI TESTİ** (discord.com, roblox.com, example.com; her
  site için ✓/✗ ve süre). Tarayıcıda discord.com, roblox.com ve bildiğiniz başka engelli siteler;
  Discord uygulamasında bir sesli kanal. example.com engelsizdir: o da açılmıyorsa sorun
  bağlantının kendisindedir.
* **"Bağlantı kurulamadı".** Motor düşerse 1, 3 ve 10 saniye arayla yeniden kurulur; 5 dakikada
  5 deneme başarısız olursa durur ve bildirim gösterir. Güç düğmesine yeniden dokunun. Böyle
  kalıcı bir hatadan sonra bağlantı arka planda kendiliğinden açılmaz; uygulamayı ya da hızlı
  ayar karosunu açınca yeniden denenir. Başka bir VPN uygulaması açılırsa Android GoodbyeDPI'ı
  kapatır (aynı anda tek VPN olabilir).
* **"Bağlantı koptu — yeniden bağlanmak için dokun".** Uygulama arka planda kapanırsa (bellek
  sıkıntısı, çökme) bağlantı birkaç saniye ile bir-iki dakika içinde kendiliğinden geri gelir.
  Bu arada durum çubuğundaki "Bağlı" bildirimi, bağlantı gerçekte kopmuş olsa da kısa bir süre
  (en çok bir-iki dakika) görünmeye devam edebilir.
  Arka arkaya 5 kez geri getirildiği halde yine kapandıysa uygulama bunu yapmayı bırakır ve bu
  bildirimi gösterir: dokunun, bağlantı yeniden kurulur. Tekrar ediyorsa **Tanılama** çıktısıyla
  bildirin.
* **Hata bildirirken** Ayarlar → HAKKINDA → **Tanılama** ekranındaki komut satırını ekleyin.

## Derleme

Gerekenler: JDK 17, Android SDK (compileSdk 36), NDK 29.0.14206865. Gradle 8.14.3'ü sarmalayıcı
indirir. `local.properties` içinde `sdk.dir` olmalı (git'e girmez).

```powershell
cd android
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17..."   # PATH'teki java eskiyse
.\gradlew.bat assembleDebug -Pgdpi.abi=x86_64     # geliştirme: tek ABI, hızlı
.\gradlew.bat dist                                 # sürüm: build\dist\GoodbyeDPI-Android(-<sürüm>).apk + SHA256SUMS.txt
```

* Depo derin bir klasördeyse Windows yol sınırı için `-Pgdpi.buildDir=C:\t\b` ekleyin (ndk-build'in
  `make.exe`'si uzun yollarda çöküyor). Çıktılar o zaman `C:\t\b\app\outputs\...`, `dist`
  çıktısı `C:\t\b\root\dist` altına gider.
* `dist` tek ABI'li (`-Pgdpi.abi`) derlemeyi reddeder: dağıtılan APK dört ABI'yi de taşır.
* `-Pgdpi.appIdSuffix=.dev` hata ayıklama derlemesini ayrı paket adıyla kurar.

## İmzalama

Sürüm APK'sı `android/keystore.properties` ile imzalanır (ikisi de git'e girmez):

```properties
# storeFile android/ klasörüne göre çözülür
storeFile=release.jks
storePassword=...
keyAlias=goodbyedpi
keyPassword=...
```

Dosya yoksa derleme bozulmaz ama hata ayıklama anahtarıyla imzalanır (böyle bir APK yayımlanamaz).

> **Anahtarı yedekleyin.** `android/release.jks` ve `android/keystore.properties`'in bir kopyası
> `%USERPROFILE%\.goodbyedpi-android-keys\` klasöründe durur; bunu ayrıca makine dışına da (şifreli
> bir yere) yedekleyin. Yayımlanan her sürüm **aynı anahtarla** imzalanmalı: anahtar kaybolursa
> kurulu uygulamalar kendini bir daha güncelleyemez, kullanıcılar kaldırıp yeniden kurmak (ve
> ayarlarını kaybetmek) zorunda kalır. `release.ps1` bu yüzden sertifika SHA-256'sını
> (`b819e563…74b5`) sabitler.

## Yayımlama

1. `app/build.gradle.kts` başındaki `appVersionName` ve `appVersionCode`'u artırın.
2. `powershell -NoProfile -ExecutionPolicy Bypass -File android\tools\release.ps1 -BuildDir C:\t\rel -DryRun`
   — `dist`'i derler, imzayı doğrular, SHA-256'yı hesaplar, `release-notes.md` yazar ve GitHub'a
   yalnızca okuma istekleriyle yayın denetimlerini yapar; hiçbir şey yayımlamaz.
3. Her şey geçtiyse aynı komutu `-DryRun` yerine `-Publish` ile çalıştırın (`gh auth login` gerekli).
   Betik Windows PowerShell 5.1 ile çalışır ve şu durumlarda **yayımlamayı reddeder**:
   * `keystore.properties` yok ya da APK sabitlenmiş sürüm sertifikasıyla imzalı değil (hata
     ayıklama anahtarı `CN=Android Debug` dahil);
   * `versionName` veya `versionCode` GitHub'daki en yeni `android-v*` sürümünden büyük değil
     (önceki `versionCode` sürüm notundaki `versionCode: N` satırından, yoksa APK'dan okunur);
   * `android-v<sürüm>` sürümü ya da etiketi zaten var.
4. Etiket `android-vX.Y.Z`, sürüm **`--latest=false`** ile, iki APK adıyla
   (`GoodbyeDPI-Android.apk`, `GoodbyeDPI-Android-<sürüm>.apk`) ve SHA-256 satırlı notla
   yayımlanır: aynı depodaki masaüstü güncelleyicisi `/releases/latest`'i okuyor, Android
   sürümünü görmemeli.

## Test araçları (`tools/`)

* `e2e.py` — emülatörde uçtan uca test (`py -3 tools\e2e.py --serial emulator-5554 --apk ... --probe-apk ...`,
  `--list`, `--only a,b`, cihaz genelini etkileyen adımlar için `--allow-disruptive`).
* `native/smoke.py` — byedpi'nin hazır yöntemleri, kablo (tcpdump) kontrolleri, DPI benzetimi,
  cihazda UDP/yönlendirme testleri ve `--apk` ile JNI testi
  (`py -3 tools\native\smoke.py --serial emulator-5554`).
* `:probe` modülü — VPN'in içinden HTTP/DNS/UDP/QUIC ölçen yardımcı uygulama (dağıtılmaz);
  kullanım `probe/src/main/java/.../ProbeActivity.kt` başındaki açıklamada.
* Hata ayıklama derlemesinde `DebugUpdateReceiver` güncelleyiciyi adb'den sürer
  (`--es cmd check|open|bg|install|conntest ...`, ayrıntı dosyanın başında). Gerçek periyodik
  iş: `adb shell cmd jobscheduler run -f <paket> 4201`.
* Ayarlar > BAĞLANTI TESTİ motor açıkken byedpi'ye adı değil IP'yi verir: ad, seçili DNS'e
  vekil üzerinden (198.18.0.53:53, DNS-over-TCP) sorulur; DNS "Kapalı"ysa sistem çözücüsüne düşer.

## Teşekkür ve lisanslar

APK şu açık kaynak bileşenleri içerir (lisans metinleri `app/src/main/assets/licenses/` altında
ve uygulamada Ayarlar → HAKKINDA → **Açık kaynak lisansları**'nda):

| bileşen | lisans | ne işe yarar |
|---|---|---|
| [byedpi](https://github.com/hufrea/byedpi) (hufrea) | MIT | DPI atlatma vekili; yerel yamalar `app/src/main/jni/PATCHES.md`'de |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) 2.18.0 + hev-socks5-core (heiher) | MIT | tun ↔ SOCKS5 köprüsü |
| [lwIP](https://savannah.nongnu.org/projects/lwip/) ([heiher/lwip](https://github.com/heiher/lwip)) | BSD-3-Clause | hev'in içindeki TCP/IP yığını |
| [hev-task-system](https://github.com/heiher/hev-task-system) (heiher) | MIT | hev'in eşyordam çalışma zamanı |
| [yaml](https://github.com/heiher/yaml) (heiher, libyaml dalı) | MIT | hev yapılandırma ayrıştırıcısı |
| AndroidX, Jetpack Compose, Kotlin, kotlinx | Apache-2.0 | arayüz ve çalışma zamanı (`THIRD-PARTY-NOTICES.txt`) |

Sağlayıcıya özel hazır ayarlar [cagritaskn/SplitWire-Turkey](https://github.com/cagritaskn/SplitWire-Turkey)
içindeki zapret / zapret2 Türkiye ayarlarından ve GoodbyeDPI-Turkey'den uyarlanmıştır.
