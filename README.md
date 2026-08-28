# GoodbyeDPI UI

GoodbyeDPI için sade, hafif ve animasyonlu bir WPF masaüstü arayüzü.
Tek düğme, sistem tepsisi, açık/koyu tema, Windows açılışında otomatik başlatma.

Parametreler [cagritaskn/GoodbyeDPI-Turkey](https://github.com/cagritaskn/GoodbyeDPI-Turkey)
`0.2.3rc3-turkey` sürümündeki `turkey_dnsredir*.cmd` dosyalarından birebir alınmıştır.

## Dizin yapısı

```
goodbydpi/
├── GoodbyeDpiUI.slnx
├── publish.ps1                     Dağıtım paketi üretir
├── dist/GoodbyeDPI-UI/             Yayın çıktısı (paylaşılacak klasör)
├── licenses/                       GoodbyeDPI + WinDivert lisansları
└── src/GoodbyeDpiUI/
    ├── GoodbyeDpiUI.csproj
    ├── app.manifest                requireAdministrator (UAC)
    ├── App.xaml(.cs)               Tek örnek, tepsi, otomatik bağlanma
    ├── MainWindow.xaml(.cs)        Pencere, tema geçişi, ayar paneli
    ├── TrayFlyout.xaml(.cs)        Tepsi simgesine tıklayınca açılan panel
    ├── Assets/app.ico
    ├── Themes/
    │   ├── Shared.xaml             Temadan bağımsız kaynaklar
    │   ├── Light.xaml              Açık palet
    │   ├── Dark.xaml               Koyu palet
    │   └── Controls.xaml           Güç düğmesi, anahtar, hap listesi, butonlar
    ├── Models/
    │   ├── ConnectionState.cs
    │   ├── DpiProfile.cs           Yöntem + DNS profilleri
    │   └── AppSettings.cs
    ├── Services/
    │   ├── GoodbyeDpiService.cs    Süreç yaşam döngüsü
    │   ├── SettingsService.cs      %AppData% JSON
    │   ├── StartupService.cs       Görev Zamanlayıcı
    │   ├── ThemeService.cs         Sözlük takası
    │   ├── TrayService.cs          Tepsi simgesi
    │   └── NativeMethods.cs        DWM yuvarlak köşe / koyu çerçeve
    ├── ViewModels/
    └── Runtime/
        ├── x86_64/                 goodbyedpi.exe + WinDivert (64 bit)
        └── x86/                    goodbyedpi.exe + WinDivert (32 bit)
```

## Derleme

```bash
dotnet build GoodbyeDpiUI.slnx -c Release
```

## Dağıtım

```bash
powershell -ExecutionPolicy Bypass -File publish.ps1
```

Çıktı `dist\GoodbyeDPI-UI\` altına gelir:

```
dist/GoodbyeDPI-UI/
├── GoodbyeDPI-UI.exe     ~63 MB - .NET runtime içinde gömülü
└── Runtime/
    ├── x86_64/           goodbyedpi.exe + WinDivert
    └── x86/
```

> **Paylaşırken klasörün tamamını verin.** `GoodbyeDPI-UI.exe` tek başına
> açılır ama bağlanamaz: `goodbyedpi.exe` ve WinDivert tek dosyaya gömülemez
> (aşağıda sebebi var), yanındaki `Runtime` klasöründen çalışmaları gerekir.

Karşı tarafta **.NET kurulu olması gerekmez** — runtime exe'nin içinde.
Windows 10/11 64-bit yeterli, ilk açılışta UAC onayı ister.

Küçük paket isterseniz (hedefte .NET 8 Desktop Runtime şartıyla, ~1 MB):

```bash
powershell -ExecutionPolicy Bypass -File publish.ps1 -FrameworkDependent
```

## Profiller

Yöntem ve DNS bağımsız seçilir; ikisinin birleşimi resmi `.cmd` dosyalarının
tamamını üretir.

| Yöntem | Argüman | Karşılığı |
|---|---|---|
| Varsayılan | `-5 --set-ttl 5` | `turkey_dnsredir.cmd` |
| Alternatif 1 – TTL 3 | `--set-ttl 3` | `..._alternative_superonline` |
| Alternatif 2 – TTL yok | `-5` | `..._alternative2_superonline` |
| Alternatif 3 – Mod 9 | `-9` | `..._alternative6_superonline` |

| DNS | Argüman |
|---|---|
| Cloudflare *(varsayılan)* | `--dns-addr 1.1.1.1 --dnsv6-addr 2606:4700:4700::1111` |
| Yandex (1253) | `--dns-addr 77.88.8.8 --dns-port 1253 --dnsv6-addr 2a02:6b8::feed:0ff --dnsv6-port 1253` |
| Kapalı | *(yok)* |

> **DNS seçimi hakkında:** Türkiye'de asıl işi gören seçenek Yandex'in **1253**
> portudur. 1253 standart dışı bir port olduğu için ISS'in 53. porttaki DNS
> kaçırmasından kurtulur. Cloudflare yalnızca 53. portta hizmet verir; ISS o
> portu kaçırıyorsa yönlendirme etkisiz kalır. Siteler açılmıyorsa dişli
> simgesinden **Yandex (1253)** seçeneğine geçin.

## Tasarım kararları

**Yönetici yetkisi.** `app.manifest` içinde `requireAdministrator`. WinDivert
sürücüsü için şart; UAC açılışta bir kez sorulur.

**Otomatik başlatma neden Görev Zamanlayıcı?** Kayıt defterindeki `Run` anahtarı
kullanılsaydı, uygulama yükseltilmiş çalıştığı için **her açılışta UAC penceresi**
çıkardı. `HighestAvailable` seviyeli bir görev ise UAC sormadan başlatır.
Görev adı: `GoodbyeDPI UI Autostart`, uygulamaya `--tray` argümanı geçer.

> Görev XML'i **şema 1.2** kullanır. `DisallowStartOnRemoteAppSession` ve
> `UseUnifiedSchedulingEngine` şema 1.3 öğeleridir; 1.2 içinde `schtasks`
> "Görev XML'si beklenmeyen bir düğüm içeriyor" deyip görevi **hiç oluşturmaz**.
> İlk sürümde bu yüzden otomatik başlatma sessizce çalışmıyordu. Artık bu öğeler
> yok, yükseltme durumu önceden kontrol ediliyor, görev oluşturulduktan sonra
> gerçekten var mı diye doğrulanıyor ve başarısızlık ayar panelinde gösteriliyor.

**Görevin oluşması için uygulamayı bir kez yönetici olarak çalıştırmanız yeterli.**
Ayar panelindeki anahtar açıkken uygulama görevi kendisi kurar.

**Açılışta otomatik bağlanma.** Ayar panelindeki *Otomatik bağlan* anahtarı
kontrol eder. Açılıştaki bağlantı, elle bağlanmadan farklı olarak başarısız
olursa 3, 8 ve 15 saniye sonra yeniden dener: görev bizi oturumdan 5 saniye
sonra başlatıyor ve WinDivert sürücüsü ya da ağ yığını o anda henüz hazır
olmayabiliyor.

**"Bağlandı" nasıl anlaşılıyor?** `goodbyedpi.exe`, `Filter activated...`
satırını `fflush` etmeden yazıyor; stdout bir pipe'a bağlandığında satır blok
tamponunda kalıyor ve zamanında gelmiyor. Bu yüzden hazır olma sinyali
**"süreç 1.2 saniye ayakta kaldı"** olarak ölçülüyor — hatalı argüman veya
sürücü hatasında süreç bundan çok önce çıkıyor ve tampon çıkışta boşaldığı için
hata metni güvenilir şekilde yakalanıyor. Satır erken gelirse zaten hemen
değerlendiriliyor.

**Tema geçişi neden renk animasyonu değil?** WPF, hem `ResourceDictionary`'ye
konan hem de bir `Style`/`ControlTemplate` setter'ından referans verilen her
`Freezable`'ı mühürlüyor; mühürlü fırça animasyon kabul etmiyor. Bunun yerine
tema sözlüğü anında takas ediliyor, yumuşaklık ise `MainWindow`'un eski
görüntünün anlık kopyasını üstte tutup soldurmasından geliyor (gerçek çapraz
geçiş).

**Çapraz geçiş katmanı neden `Canvas` içinde?** Doğrudan `Grid`'e konduğunda
`SizeToContent="Height"` ile birlikte anlık görüntü düzene katılıyor, pencere
aşağı doğru büyüyüp sonra geri küçülüyor ve eski görüntü içeriğin üstüne kayarak
yazıları okunmaz hale getiriyordu. `Canvas`'ın istenen boyutu her zaman sıfır
olduğu için katman düzeni hiç etkilemiyor; boyutu yakalama anında kod veriyor.

**Neden her stilde `Foreground` acikca veriliyor?** WPF'in yerlesik `ListBox`
temasi `Foreground`'u `SystemColors.ControlTextBrushKey`'den (siyah) alir ve bu,
pencereden gelen mirasi ezer. `PillList` stilinde belirtilmedigi surece koyu
temada haplarin yazisi siyah zemine siyah dusup **gorunmez** oluyordu. Olculdu:
kontrast farki 34 idi, acikca verildikten sonra 210. Ayni tuzak sistem
renklerinden deger alan her denetim icin gecerli.

**Tepsi paneli.** Tepsi simgesine tıklayınca WinForms `ContextMenuStrip` yerine
`TrayFlyout` açılır: uygulamanın temasını, hap seçicilerini ve animasyonlarını
paylaşan gerçek bir WPF penceresi. Durum, tek dokunuşluk bağlan/kes düğmesi,
yöntem ve DNS seçimi, pencereyi göster ve çıkış aynı panelde. Odak kaybında
kapanır — ancak yalnızca bir kez odak aldıktan sonra, aksi halde açılır açılmaz
kapanıp titriyordu.

**Tek dosya paketleme.** `csproj` içinde `RuntimeIdentifier=win-x64`,
`SelfContained`, `PublishSingleFile` ve `IncludeNativeLibrariesForSelfExtract`
açık. Sonuncusu olmadan WPF'in yerel kütüphaneleri (`PresentationNative`,
`wpfgfx`) exe'nin yanına ayrı dosyalar olarak düşüyor ve "tek dosya" olmuyor.
`EnableCompressionInSingleFile` paketi ~150 MB'dan ~63 MB'a indiriyor.

İki ayar bilerek **kapalı**:

- `PublishTrimmed` — WPF, XAML'i çalışma zamanında yansıma ile çözdüğü için
  trimming desteklenmiyor; açılınca uygulama ilk pencerede kaynak bulunamadı
  hatalarıyla çöküyor.
- `PublishReadyToRun` — açılışı hızlandırır ama pakete ~40 MB ekler. Paylaşım
  kolaylığı öne çıktığı için kapalı; açılış hızı öncelik olursa açılabilir.

**`goodbyedpi.exe` neden pakete gömülmüyor?** Yönetilen bir derleme değil:
ayrı bir süreç olarak çalıştırılıyor ve `WinDivert64.sys`'i çekirdek sürücüsü
olarak diskten yüklüyor. Pakete gömülse dosya yolu diye bir şeyi kalmaz.
Bu yüzden `csproj`'da `ExcludeFromSingleFile="true"` ile işaretli ve exe'nin
yanında `Runtime` klasörü olarak duruyor. `AppContext.BaseDirectory` tek dosya
modunda exe'nin klasörünü döndürdüğü için yol çözümlemesi değişmeden çalışıyor
(doğrulandı).

**Hafiflik.** Sıfır NuGet paketi. Tepsi simgesi için WindowsDesktop runtime'ında
zaten bulunan `Forms.NotifyIcon`, ayarlar için in-box `System.Text.Json`.
Yoklama (polling) yok — süreç durumu `Process.Exited` olayıyla izleniyor.
Sürekli dönen storyboard'lar `AreAnimationsEnabled`'a bağlı; pencere tepsiye
küçüldüğünde animasyon saatleri tamamen duruyor.

**Başka kurulumlara dokunmaz.** Artık süreç temizliği yalnızca kendi `Runtime`
klasöründen çalışan `goodbyedpi.exe`'leri kapatır; servis olarak kurulmuş veya
başka bir dizindeki kopyaya karışmaz.

## Bilinen sınırlar

- `InvariantGlobalization` kullanılamıyor (WPF veri bağlama kültür verisi
  istiyor), bu yüzden csproj'da kapalı.
- Kaspersky yüklüyse GoodbyeDPI çalışmaz; WinDivert sürücüsü engellenir.
- Uygulama tek örnek çalışır (mutex); ikinci kopya sessizce kapanır.
- `schtasks` hata metnini konsolun OEM kod sayfasında yazar; sıfır bağımlılık
  hedefi yüzünden beklenmedik hatalarda Türkçe karakterler bozuk görünebilir.
  Yaygın sebep olan yetki eksikliği önceden yakalanıp düzgün mesajla bildirilir.
- Uygulama zorla sonlandırılırsa (Görev Yöneticisi) `goodbyedpi.exe` arkada
  öksüz kalır. Uygulama bir sonraki açılışta kendi `Runtime` klasöründen çalışan
  öksüz süreçleri kapatır, başka kurulumlara dokunmaz.
- `#if UITEST` blokları yalnızca görsel doğrulama derlemesi içindir
  (`-p:DefineConstants=UITEST`); yayın derlemesinde tamamen dışarıda kalır.

## Lisans

`goodbyedpi.exe` ve WinDivert dosyaları kendi lisanslarıyla gelir — `licenses/`
klasörüne bakın. Arayüz kodu bu depoya aittir.
#   g o o d b y d p i  
 