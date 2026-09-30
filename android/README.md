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
   **Genel** kalsın; varsayılan DNS zaten **Yandex (1253)**'tür.
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

* **Google (ya da engelsiz başka bir site) açılmıyor, arama yapılamıyor.** Ayarlar → GENEL →
  **Akıllı mod** açık olmalı (varsayılan). Neden: sahte paketli yöntemler sahteyi düşük ve sabit
  bir TTL ile yollar; Google'ın ve bazı CDN'lerin ISS'in içindeki önbellek sunucuları o kadar
  yakın olabilir ki sahte paket DPI'da ölmeden sunucuya ulaşır ve bağlantıyı bozar. Akıllı mod
  her bağlantıyı önce hiçbir şey yapmadan dener; yöntemi yalnızca bağlantı DPI tarafından
  sıfırlanır, takılır ya da TLS cevabı gelmezse devreye sokar. Engelsiz sitelere hiç dokunulmaz.
  Akıllı mod kapalıyken (1.0.0 davranışı) yöntem her HTTPS/HTTP bağlantısına uygulanır: bu
  durumda sahte paketsiz bir yöntem (Ters sıra, TLS kayıt bölme) seçin ya da akıllı modu açın.
* **Google, YouTube (Chrome, Google ve YouTube uygulamaları) açılmıyor; Discord, Roblox, Instagram
  uygulaması çalışıyor.** 1.0.1 ve öncesinde tünel, bağlı ağda IPv6 olmasa da (Türkiye'de mobil
  hatların çoğu) IPv6 sunuyordu; Chrome ve Google uygulamaları IPv6 ile bağlanmaya çalışıp
  `ERR_CONNECTION_RESET` / `ERR_QUIC_PROTOCOL_ERROR` alıyordu. 1.0.2'den itibaren Ayarlar → GENEL →
  **IPv6 (otomatik)** IPv6'yı yalnızca bağlı ağ (mobil veri / Wi-Fi) gerçekten IPv6 ile internete
  çıkabiliyorsa kullanır; altındaki "Şu an: …" satırı o anki durumu gösterir (ağda IPv6 yok /
  IPv6 etkin / ağda IPv6 var ama çalışmıyor). Ağ değişince (Wi-Fi ↔ mobil veri) bağlantı
  kendiliğinden, kopmadan uyarlanır; IPv6 aynı ağda sonradan bozulursa da en geç 10 dakika içinde
  fark edilip kapatılır (tek bir başarısız deneme yetmez, ~20 sn sonra tekrarlanması gerekir:
  asansörde kısa bir sinyal kaybı bağlantıları koparmasın). Yine de Google veya YouTube açılmıyorsa bu anahtarı kapatıp
  deneyin. Google "olağan dışı trafik / robot değilim" sayfası gösteriyorsa bu genelde
  operatörün paylaşılan IP adresinden kaynaklanır (VPN kapalıyken de çıkar); uçak modunu açıp
  kapatmak yeni bir IP verir.
* **Bir site açılmıyor / yöntem işe yaramıyor.**
  1. Doğru sağlayıcının seçili olduğundan emin olun (mobil veride "… Mobil" profilleri).
  2. **Akıllı mod** açıkken engellenen bir sitenin ilk açılışı biraz uzar: DPI bağlantıyı
     sıfırlıyorsa yarım saniye kadar, sessizce düşürüyorsa 4 sn kadar. Sonra seçili yöntem
     devreye girer ve o adres için 1 saat hatırlanır. **Otomatik yedek yöntem** (varsayılan açık)
     seçili yöntem de işe yaramazsa sağlayıcının diğer yöntemlerini sırayla dener. Bağlantıyı
     kapatıp açmak ya da yöntemi değiştirmek bu belleği sıfırlar. DPI engelli siteye bağlantıyı
     kesmek yerine kendi uyarı sayfasını gösteriyorsa (düz HTTP) akıllı mod bunu engel olarak
     algılamaz: o site için akıllı modu kapatmayı deneyin.
  3. Olmazsa **YÖNTEM** listesinden sağlayıcının alternatiflerini (listedeki sırayla) elle deneyin.
     Sahte paketli yöntemlerde TTL önemlidir: fazla düşükse DPI sahteyi görmez, fazla yüksekse
     sahte sunucuya ulaşıp bağlantıyı bozar. Ayarlar → YÖNTEM → **+ Yeni özel ayar** seçili
     yöntemin kopyasını açar; orada TTL'i birer birer değiştirerek deneyebilirsiniz.
  4. DNS olarak **Yandex (1253)** kullanın (varsayılan); Cloudflare 53. portta olduğu için ISS
     kaçırabilir ve engelli sitelerin adresini yanlış (uyarı sayfasına) çözer. 1.0.0'dan
     güncellenen ve Genel + Cloudflare'de kalmış (hiç değiştirilmemiş) kurulumlar bir kez
     Yandex'e alınır; Cloudflare'i sonra yeniden seçerseniz bir daha değiştirilmez.
  5. Tarayıcının önbelleği eski sonucu tutabilir: gizli sekmede deneyin.
* **Discord sesli kanala giriyor ama ses bağlanmıyor.** Bütün hazır yöntemlerde "Discord ses ve
  aramalar (UDP)" açıktır: ses bağlantısının ilk paketlerinden önce sahte UDP paketleri gider.
  Sahteler yalnızca bağlantının başında gittiği için GoodbyeDPI bağlıyken sesli kanala yeniden
  girin; gerekirse Discord'u tamamen kapatıp açın. Özel ayarda bu anahtarın açık olduğundan emin
  olun; gerekirse "Sahte UDP tekrar sayısı"nı artırın (varsayılan 6, en çok 20).
* **Neyi test etmeli:** Ayarlar → **BAĞLANTI TESTİ** (www.google.com, www.youtube.com,
  discord.com, roblox.com, www.instagram.com, example.com). Her site için IPv4 ve IPv6 ayrı
  denenir: `IPv4: ✓ 312 ms · IPv6: ✗ Bağlantı kurulamadı`. Nasıl okunur:
  * IPv6 satırı **kırmızıysa** tünel IPv6 sunuyor ama IPv6 bağlantısı kurulamıyor: Chrome, Google
    ve YouTube uygulamaları IPv6'yı seçtiği için bu siteler açılmaz (Discord ve Roblox'un IPv6
    adresi yok, bu yüzden etkilenmez; test bunları `IPv6: kayıt yok` diye gösterir). Ayarlar →
    GENEL → IPv6'yı kapatıp tekrar deneyin ve Tanılama raporunu gönderin.
  * `IPv6: kullanılmıyor (ağda IPv6 yok)` gibi gri bir satır, tünelin IPv6 kullanmadığını
    gösterir; uygulamalar IPv4'ten bağlanır, bu satır sorun değildir.
  * Google satırının altında "Google robot doğrulaması istiyor" yazıyorsa Google operatörün
    (paylaşılan) IP adresini işaretlemiş: uçak modunu açıp kapatmak (yeni IP) genelde geçirir;
    uygulama kaynaklı değildir. Testte robot doğrulaması çıkmaması tarayıcıda da çıkmayacağı
    anlamına gelmez (test tek ve çerezsiz bir istek); tarayıcıda "robot değilim" sayfası
    görüyorsanız neden yine aynıdır.
  * Bağlantı kapalıyken test doğrudan yapılır (motor olmadan); atlatmayı ölçmek için önce bağlanın.
    Bu durumda IPv6'sız ağda `IPv6: bilinmiyor` görünür (listenin altında bir kez açıklanır):
    sistem, ağda IPv6 yoksa sitelerin IPv6 adresini hiç sormaz.

  Tarayıcıda discord.com, roblox.com ve bildiğiniz başka engelli siteler; Discord uygulamasında
  bir sesli kanal. example.com engelsizdir: o da açılmıyorsa sorun bağlantının kendisindedir.
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
* **Hata bildirirken** önce Ayarlar → **BAĞLANTI TESTİ**'ni bağlıyken çalıştırın, sonra Ayarlar →
  HAKKINDA → **Tanılama** → **Kopyala** ile raporu ekleyin. Rapor: uygulama ve Android sürümü,
  cihaz modeli, ağ türü (Mobil veri / Wi-Fi) ve operatör adı, bağlı ağın adres **türleri** (ör.
  "IPv4 CGNAT", "IPv6 küresel"; IP adresleri yazılmaz), ağda IPv6 varsayılan yolu, Özel DNS
  durumu (bilinen sağlayıcıların kişisel profil adreslerinde kimlik kısmı `*` ile, kendi
  sunucunuzun adı tümüyle gizlenir), tünelin IPv6 durumu (ayar,
  ağdaki IPv6, erişim denemesi, tünelde IPv6 açık mı), sağlayıcı / yöntem / akıllı mod /
  otomatik yedek / DNS, motorun çalışan komut satırı (kendi girdiğiniz DNS sunucusunun adresi
  `<özel-DNS>` olarak gizlenir) ve son bağlantı testinin site site, IPv4 / IPv6 ayrı sonuçları
  (hata türüyle). Wi-Fi'deyken SIM'in operatörü "SIM operatörü" olarak ayrıca belirtilir.

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
  `chromium_web` adımı Google arama, m.youtube.com ve wikipedia.org'u WebView (Chrome ile aynı
  Chromium ağ yığını) ile tünelden yükler; `--ipv4-only-underlying` (adb root) adımlardan önce
  wlan0/eth0'da IPv6'yı kapatıp sonda geri açar: Türk mobil verisinin çoğu gibi IPv6'sız ağ.
  1.0.1 bu durumda `net::ERR_CONNECTION_RESET` veriyordu (tünel IPv6 sunuyor, ağ taşımıyordu).
* `native/smoke.py` — byedpi'nin hazır yöntemleri, kablo (tcpdump) kontrolleri, DPI benzetimi,
  cihazda UDP/yönlendirme testleri ve `--apk` ile JNI testi
  (`py -3 tools\native\smoke.py --serial emulator-5554`).
* `:probe` modülü — VPN'in içinden HTTP/DNS/UDP/QUIC, `tcp` (adres sırası + sırayla bağlanma)
  ve `web` (WebView = Chromium ağ yığını) ölçen yardımcı uygulama (dağıtılmaz);
  kullanım `probe/src/main/java/.../ProbeActivity.kt` başındaki açıklamada.
* Hata ayıklama derlemesinde `DebugIpv6Receiver` IPv6 erişim denemesini zorlar (`--es probe
  pass|fail|real`, `--es ipv6 on|off`). Emülatörde yalnızca fec0 adresi olduğundan tünelde IPv6
  açık yolu otomatik testle sınanamaz; elle (adb root): `adb shell ip -6 addr add
  2001:db8:77::5/64 dev wlan0`, bağlan, `--es probe pass` → logcat'te `ipv6: … tun=true`;
  `--es ipv6 off` ~1,5 sn içinde `tun=false` yapmalı, `--es ipv6 on` geri açmalı; `--es probe
  fail` → `tun=false`; sonda `--es probe real` ve `ip -6 addr del 2001:db8:77::5/64 dev wlan0`
  (ayrıntı docs/SPEC.md §8).
* Hata ayıklama derlemesinde `DebugUpdateReceiver` güncelleyiciyi adb'den sürer
  (`--es cmd check|open|bg|install|conntest ...`, ayrıntı dosyanın başında). Gerçek periyodik
  iş: `adb shell cmd jobscheduler run -f <paket> 4201`.
* Ayarlar > BAĞLANTI TESTİ motor açıkken byedpi'ye adı değil IP'yi verir: ad, seçili DNS'e
  vekil üzerinden (198.18.0.53:53, DNS-over-TCP; A ve AAAA ayrı) sorulur; DNS "Kapalı"ysa sistem
  çözücüsüne düşer. Her aileden bir adres ayrı denenir (Google: `/search?q=test`, 429 ya da
  `/sorry/` = robot doğrulaması; YouTube: `/generate_204`, 204 beklenir).

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
