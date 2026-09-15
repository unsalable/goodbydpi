using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using GoodbyeDpiUI.Models;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Kendi motorumuzun canli dogrulamasi (yonetici gerektirir). "--enginetest" ile
/// calisir: WinDivert'i acar, DNS cozumlemesi + HTTPS istegi hala calisiyor mu diye
/// bakar (yani motor trafigi bozmuyor ve paketleri gercekten isliyor), sonucu bir
/// dosyaya yazip cikar. Basari = 0, hata = 1.
/// </summary>
internal static class EngineTest
{
    public static int Run(string[] args)
    {
        var log = new StringBuilder();
        var svc = new NativeDpiService();

        // Argumandan profil sec: --enginetest [default|checksum|split]
        var profileId = args.SkipWhile(a => !a.Equals("--enginetest", StringComparison.OrdinalIgnoreCase))
                            .Skip(1).FirstOrDefault() ?? "default";
        var profile = NativeProfile.FromId(profileId);
        var cfg = profile.Build();

        log.AppendLine($"Profil: {profile.Name}");
        log.AppendLine($"Ayar: fake={cfg.FakePacket} ttl={cfg.FakeTtl}/{cfg.Ttl} chksum={cfg.FakeWrongChecksum} " +
                       $"split={cfg.SplitTls} reverse={cfg.ReverseSplit} quic={cfg.BlockQuic}");

        var request = new EngineRequest(DpiMethod.Default, cfg, DnsProfile.Cloudflare);

        svc.StartAsync(request).GetAwaiter().GetResult();

        // "Baglaniyor" durumundan cikmasini bekle.
        for (var i = 0; i < 50 && svc.State == ConnectionState.Connecting; i++)
            Thread.Sleep(100);

        log.AppendLine($"Motor durumu: {svc.State}");
        if (svc.LastError is not null) log.AppendLine($"Hata: {svc.LastError}");

        var connected = svc.State == ConnectionState.Connected;
        var dnsOk = false;
        var httpOk = false;

        if (connected)
        {
            // Motor calisirken ag hala kullanilabiliyor mu?
            dnsOk = TryDns("example.com", log);
            httpOk = TryHttps("https://www.example.com", log);
        }

        svc.Stop();
        log.AppendLine($"Durduruldu. Son durum: {svc.State}");

        var pass = connected && dnsOk && httpOk;
        log.Insert(0, pass ? "MOTOR CANLI TEST: GECTI\n\n" : "MOTOR CANLI TEST: SORUN VAR\n\n");

        var path = Path.Combine(Path.GetTempPath(), "goodbyedpi-enginetest.txt");
        try { File.WriteAllText(path, log.ToString()); } catch { /* onemsiz */ }

        return pass ? 0 : 1;
    }

    private static bool TryDns(string host, StringBuilder log)
    {
        try
        {
            var ips = Dns.GetHostAddresses(host);
            log.AppendLine($"DNS {host}: {ips.Length} adres ({string.Join(", ", ips.Take(3).Select(a => a.ToString()))})");
            return ips.Length > 0;
        }
        catch (Exception ex)
        {
            log.AppendLine($"DNS hata: {ex.Message}");
            return false;
        }
    }

    private static bool TryHttps(string url, StringBuilder log)
    {
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(12) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("GoodbyeDPI-UI-EngineTest");
            using var resp = http.GetAsync(url).GetAwaiter().GetResult();
            log.AppendLine($"HTTPS {url}: {(int)resp.StatusCode} {resp.StatusCode}");
            return resp.IsSuccessStatusCode;
        }
        catch (Exception ex)
        {
            log.AppendLine($"HTTPS hata: {ex.Message}");
            return false;
        }
    }
}
