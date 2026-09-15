# GoodbyeDPI UI

DPI atlatma için sade, hafif ve animasyonlu bir WPF masaüstü arayüzü.
Tek düğme, sistem tepsisi, açık/koyu tema, Windows açılışında otomatik başlatma.

## Özellikler

- **Kendi DPI motorumuz** — WinDivert üzerine C# ile yazılmış, ayrı bir `goodbyedpi.exe`
  sürecine ihtiyaç duymayan yerleşik motor:
  - Sahte paket (düşük TTL / yanlış sağlama / yanlış SEQ) ile DPI senkron bozma
  - TLS ClientHello'yu SNI'dan önce iki parçaya bölme (istege bağlı ters sıra)
  - Cikış DNS sorgularını seçilen sunucuya yönlendirme (ISS DNS kaçırmasını aşar)
  - QUIC (UDP 443) engelleme
- **Hazır GoodbyeDPI altyapısı** — isteyen, hâlihazırda çalışan `goodbyedpi.exe`
  altyapısını da seçebilir. Ayarlardaki "Kendi motorumuz" anahtarıyla geçiş yapılır.
- **Özel DPI ayarı** — kendi motor için TTL, bölme, sağlama, QUIC gibi tüm teknikler
  "Özel" profilinden tek tek açılıp kapatılabilir.
- **Özel DNS sunucusu** — DNS listesindeki "Özel" seçeneğiyle kendi IPv4/IPv6 adres ve
  portunuzu girebilirsiniz.
- **Otomatik güncelleme** — uygulama her açıldığında GitHub'daki en son sürümü kontrol
  eder; daha yeni bir sürüm varsa setup dosyasını indirip (SHA-256 ile doğrulayarak)
  sessizce kurar ve kendini yeniden başlatır. Ayarlardan kapatılabilir.

Hazır GoodbyeDPI parametreleri [cagritaskn/GoodbyeDPI-Turkey](https://github.com/cagritaskn/GoodbyeDPI-Turkey)
`0.2.3rc3-turkey` sürümündeki `turkey_dnsredir*.cmd` dosyalarından alınmıştır.

## Kurulum

[Releases](https://github.com/unsalable/goodbydpi/releases) sayfasından
`GoodbyeDPI-UI-Setup.exe` dosyasını indirip çalıştırın. Kurulum yönetici izni ister
(WinDivert sürücüsü için gereklidir).

## Derleme

Gereksinim: .NET 8 SDK.

```powershell
# Uygulamayı yayınla (self-contained tek dosya + Runtime)
dotnet publish src\GoodbyeDpiUI\GoodbyeDpiUI.csproj -c Release -o build\app

# Kurulum dosyasını üret (Inno Setup 6 gerekir)
ISCC installer\GoodbyeDPI-UI.iss   # -> build\GoodbyeDPI-UI-Setup.exe
```

Yönetici gerektirmeyen iç doğrulama testleri: `dotnet build` sonrası
`dotnet bin\...\GoodbyeDPI-UI.dll --selftest` (paket/TLS/DNS/sürüm mantığı).
