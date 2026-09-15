# GoodbyeDPI UI

DPI atlatma için sade, hafif ve animasyonlu bir WPF masaüstü arayüzü.
Tek düğme, sistem tepsisi, açık/koyu tema, Windows açılışında otomatik başlatma.

## Özellikler

- **Kendi DPI motorumuz** — WinDivert üzerine C# ile yazılmış, ayrı bir `goodbyedpi.exe`
  sürecine ihtiyaç duymayan yerleşik motor:
  - Sahte paket: gerçek istekten önce engelsiz bir siteye (`www.w3.org`) ait sahte
    ClientHello / HTTP isteği gönderir; DPI akışı o siteye ait sanar. Sahte paket
    düşük TTL (sunucu mesafesine göre otomatik) ya da yanlış sağlama / SEQ ile gider.
    Sahte paket MD5 imzası seçeneğiyle, sıfır bayt içerikle ya da iki parçaya bölünmüş de gidebilir.
  - İsteği 2. bayttan ve site adının (SNI) ortasından TCP parçalarına bölme, ters sırada ve
    sıra örtüşmesiyle (zapret `seqovl`) gönderme. Chromium'un iki TCP paketine yayılan
    ML-KEM (Kyber) ClientHello'sunda site adı ikinci paketteyse o paket de bölünür.
  - **Discord ses / aramalar:** Discord ses sunucusuna giden IP Discovery paketinden ve
    WebRTC/STUN mesajlarından önce sahte UDP paketleri gönderir. Türk ISS'leri sesi bu ilk
    paketlerden tanıyıp engelliyor ("sesli kanala giriliyor ama bağlanmıyor" sorunu).
  - Çıkış DNS sorgularını seçilen sunucuya yönlendirme (ISS DNS yönlendirmesini aşar)
  - QUIC başlangıç paketlerini engelleme (uygulamalar TCP+TLS'e geçer)
  - Yalnızca gereken paketleri yakalar (ClientHello ve küçük devam paketleri, HTTP istek başı,
    SYN-ACK, QUIC, Discord ses / STUN el sıkışması, DNS); indirme, MSS boyutlu yükleme ve
    ses/oyun verisi kullanıcı moduna hiç çıkmaz.
- **İnternet sağlayıcısına özel ayarlar** — Ayarlar → *İnternet sağlayıcı* bölümünden
  sağlayıcını seçince o hat için bilinen çalışan yöntem, GoodbyeDPI yöntemi ve DNS birlikte
  uygulanır; yöntem listesi o sağlayıcının alternatiflerini gösterir:

  | Sağlayıcı | Önerilen | Alternatifler |
  |---|---|---|
  | Türk Telekom | Ters sıra | Sahte TTL 4, Sahte TTL 3, Varsayılan |
  | Superonline | Ters sıra | MD5 imzası, MD5 + TTL 3, Sahte TTL 3 |
  | Vodafone | Bölünmüş sahte (TTL 5) | Ters sıra, Varsayılan |
  | TürkNet | Varsayılan | Sabit TTL, Ters sıra |
  | Kablonet | Ters sıra | Sahte TTL 4, Varsayılan |
  | TT Mobil | Boş sahte (TTL 5) | Ters sıra, Sahte TTL 4 |
  | Turkcell Mobil | Ters sıra | Varsayılan, Sahte TTL 3 |
  | Vodafone Mobil | Ters sıra | Düz bölme, Bölünmüş sahte |

  Parametreler [cagritaskn/SplitWire-Turkey](https://github.com/cagritaskn/SplitWire-Turkey)
  içindeki zapret / zapret2 Türkiye ISS hazır ayarlarından uyarlanmıştır. Tüm hazır ayarlarda
  Discord ses desteği açık ve DNS Yandex (1253) seçilir.
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

Yönetici gerektirmeyen iç doğrulama (motorun paket kararları, sahte paket çeşitleri,
sıra örtüşmesinde sunucunun ve DPI'ın gördüğü akış, çok paketli ClientHello, Discord ses /
STUN sahte UDP'leri, otomatik TTL, QUIC, DNS gidiş-dönüşü, WinDivert filtre derleme ve
`WinDivertHelperEvalFilter` ile gerçek paket eşleşmesi, sağlama toplamları, sağlayıcı
profilleri, DNS/sürüm mantığı):

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
GoodbyeDPI-UI.exe --isp turktelekom --curl --voice     # sağlayıcının önerisi + ses yolu
GoodbyeDPI-UI.exe --enginetest ttl4 --curl --chrome --chromerepeat 4
```

`--voice`, Google STUN sunucusundan yanıt alınabildiğini ve Discord IP Discovery biçimli
paketin (belge adresi 198.51.100.7'ye) motorca yakalanıp sahte UDP üretildiğini sayaçlardan
doğrular.

`--hold <sn> --ready <dosya> --stopfile <dosya>` motoru açık tutar; böylece yönetici
olmayan bir oturumdan (ör. Chrome headless) da sonda yapılabilir.
