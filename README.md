# GoodbyeDPI UI

DPI atlatma için sade, hafif ve animasyonlu bir WPF masaüstü arayüzü.
Tek düğme, sistem tepsisi, açık/koyu tema, Windows açılışında otomatik başlatma.

## Özellikler

- **Kendi DPI motorumuz** — WinDivert üzerine C# ile yazılmış, ayrı bir `goodbyedpi.exe`
  sürecine ihtiyaç duymayan yerleşik motor:
  - Sahte paket: gerçek istekten önce engelsiz bir siteye (`www.w3.org`) ait sahte
    ClientHello / HTTP isteği gönderir; DPI akışı o siteye ait sanar. Sahte paket
    düşük TTL (sunucu mesafesine göre otomatik) ya da yanlış sağlama / SEQ ile gider.
  - İsteği 2. bayttan ve site adının (SNI) ortasından TCP parçalarına bölme, ters sırada gönderme
  - Çıkış DNS sorgularını seçilen sunucuya yönlendirme (ISS DNS yönlendirmesini aşar)
  - QUIC başlangıç paketlerini engelleme (uygulamalar TCP+TLS'e geçer)
  - Yalnızca gereken paketleri yakalar (ClientHello, HTTP istek başı, SYN-ACK, QUIC, DNS);
    indirme/yükleme trafiği kullanıcı moduna hiç çıkmaz.
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

### Testler

Yönetici gerektirmeyen iç doğrulama (motorun paket kararları, sahte paket içeriği,
parçaların yeniden birleşmesi, otomatik TTL, QUIC, DNS gidiş-dönüşü, WinDivert filtre
derleme ve sağlama toplamları, DNS/sürüm mantığı):

```powershell
dotnet build src\GoodbyeDpiUI\GoodbyeDpiUI.csproj
dotnet src\GoodbyeDpiUI\bin\Debug\net8.0-windows\win-x64\GoodbyeDPI-UI.dll --selftest sonuc.txt
```

Canlı motor testi (yönetici gerekir). Motoru açar ve Discord / Roblox / example.com
uç noktalarına .NET ve curl ile HTTPS isteği atar:

```powershell
GoodbyeDPI-UI.exe --enginetest default --curl --out sonuc.txt
GoodbyeDPI-UI.exe --enginetest off --curl          # taban ölçüm: engel gerçekten var mı?
GoodbyeDPI-UI.exe --enginetest gdpi --method default  # hazır goodbyedpi.exe ile karşılaştırma
GoodbyeDPI-UI.exe --enginetest default --set autoTtl=false --set splitSni=false
```

`--hold <sn> --ready <dosya> --stopfile <dosya>` motoru açık tutar; böylece yönetici
olmayan bir oturumdan (ör. Chrome headless) da sonda yapılabilir.
