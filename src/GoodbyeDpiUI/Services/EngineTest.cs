using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using GoodbyeDpiUI.Models;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Motorlarin canli dogrulamasi (yonetici gerektirir). Kullanim:
///
///   --enginetest [default|checksum|split|custom|gdpi|off]
///                [--set anahtar=deger]... [--dns yandex|cloudflare|off]
///                [--method default|ttl3|nottl|mode9] [--out dosya] [--curl] [--chrome]
///                [--hold saniye --ready dosya --stopfile dosya]
///
/// --hold: motor baglandiktan sonra "ready" dosyasini yazar ve "stopfile" olusana
/// kadar (en fazla verilen sure) motoru acik tutar. Boylece yonetici olmayan bir
/// oturumdan (orn. Chrome, yonetici olarak calismayi reddediyor) sonda yapilabilir.
///
/// "off" motoru hic baslatmaz (engelin gercekten var oldugunu gosteren taban
/// olcum), "gdpi" hazir goodbyedpi.exe altyapisini kullanir. Motor calisirken
/// Discord / Roblox / example.com uc noktalarina .NET HttpClient ile (istege bagli
/// curl.exe ve Chrome headless ile de) HTTPS istegi atilir. Basari = 0, hata = 1.
/// </summary>
internal static class EngineTest
{
    private static readonly string[] BlockedUrls =
    [
        "https://discord.com/api/v9/gateway",
        "https://gateway.discord.gg/",
        "https://cdn.discordapp.com/",
        "https://www.roblox.com/",
        "https://apis.roblox.com/",
    ];

    private const string ControlUrl = "https://www.example.com/";

    private static readonly string[] ChromeUrls = ["https://discord.com/login", "https://www.roblox.com/"];

    public static int Run(string[] args)
    {
        var log = new StringBuilder();
        var mode = ArgAfter(args, "--enginetest") is { } m && !m.StartsWith("--") ? m.ToLowerInvariant() : "default";
        var dns = ArgAfter(args, "--dns")?.ToLowerInvariant() switch
        {
            "off" => DnsProfile.Off,
            "cloudflare" => DnsProfile.Cloudflare,
            _ => DnsProfile.Yandex,
        };

        var outPath = ArgAfter(args, "--out") ?? Path.Combine(Path.GetTempPath(), "goodbyedpi-enginetest.txt");
        var useCurl = args.Contains("--curl", StringComparer.OrdinalIgnoreCase);
        var useChrome = args.Contains("--chrome", StringComparer.OrdinalIgnoreCase);

        IDpiBackend? backend = null;
        var pass = false;

        try
        {
            var cfg = NativeProfile.FromId(mode).Build();
            cfg = ApplyOverrides(cfg, args, log);

            log.AppendLine($"Mod: {mode}   DNS: {dns.Name}");
            if (mode is not ("off" or "gdpi"))
            {
                log.AppendLine("Ayar: " + JsonSerializer.Serialize(cfg));
            }

            if (mode != "off")
            {
                var method = DpiMethod.FromId(ArgAfter(args, "--method"));
                if (mode == "gdpi") log.AppendLine($"GoodbyeDPI yontemi: {method.Name} ({method.Arguments})");

                backend = mode == "gdpi" ? new GoodbyeDpiService() : new NativeDpiService();
                backend.StartAsync(new EngineRequest(method, cfg, dns)).GetAwaiter().GetResult();

                for (var i = 0; i < 50 && backend.State == ConnectionState.Connecting; i++)
                    Thread.Sleep(100);

                log.AppendLine($"Motor durumu: {backend.State}");
                if (backend.LastError is not null) log.AppendLine($"Hata: {backend.LastError}");
                if (backend is NativeDpiService native) log.AppendLine($"Filtre: {native.ActiveFilter}");

                if (backend.State != ConnectionState.Connected)
                    throw new InvalidOperationException("Motor baglanamadi.");

                if (mode == "gdpi") Thread.Sleep(1500); // surecin WinDivert'i acmasi icin
            }

            if (ArgAfter(args, "--hold") is { } holdText && int.TryParse(holdText, out var hold))
            {
                var ready = ArgAfter(args, "--ready");
                var stop = ArgAfter(args, "--stopfile");
                if (ready is not null) File.WriteAllText(ready, "hazir");

                var sw = Stopwatch.StartNew();
                while (sw.Elapsed.TotalSeconds < hold && (stop is null || !File.Exists(stop)))
                    Thread.Sleep(250);

                log.AppendLine($"Bekleme bitti: {sw.Elapsed.TotalSeconds:F0} sn (durdurma dosyasi {(stop is not null && File.Exists(stop) ? "geldi" : "gelmedi")})");
            }

            log.AppendLine();
            TryDns("discord.com", log);
            TryDns("www.roblox.com", log);
            log.AppendLine();

            var controlOk = TryHttps(ControlUrl, log);
            var blockedOk = BlockedUrls.Count(u => TryHttps(u, log));
            log.AppendLine($"=> .NET: {blockedOk}/{BlockedUrls.Length} engelli uc nokta acildi, kontrol {(controlOk ? "OK" : "HATA")}");

            var curlOk = true;
            if (useCurl)
            {
                log.AppendLine();
                var n = BlockedUrls.Count(u => TryCurl(u, log));
                curlOk = n == BlockedUrls.Length;
                log.AppendLine($"=> curl: {n}/{BlockedUrls.Length}");
            }

            var chromeOk = true;
            if (useChrome)
            {
                log.AppendLine();
                var n = ChromeUrls.Count(u => TryChrome(u, log));
                chromeOk = n == ChromeUrls.Length;
                log.AppendLine($"=> Chrome: {n}/{ChromeUrls.Length}");
            }

            pass = controlOk && blockedOk == BlockedUrls.Length && curlOk && chromeOk;
        }
        catch (Exception ex)
        {
            log.AppendLine("ISTISNA: " + ex.Message);
        }
        finally
        {
            if (backend is NativeDpiService { Stats: { } stats }) log.AppendLine("Sayaclar: " + stats);
            backend?.Stop();
            if (backend is not null) log.AppendLine($"Durduruldu. Son durum: {backend.State}");
            backend?.Dispose();
        }

        log.Insert(0, (pass ? "MOTOR CANLI TEST: GECTI" : "MOTOR CANLI TEST: SORUN VAR") + $"  [{mode}]\n\n");
        try { File.WriteAllText(outPath, log.ToString()); } catch { /* onemsiz */ }

        return pass ? 0 : 1;
    }

    private static string? ArgAfter(string[] args, string name)
    {
        var i = Array.FindIndex(args, a => a.Equals(name, StringComparison.OrdinalIgnoreCase));
        return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
    }

    /// <summary>--set fakeTtl=false gibi JSON adli ayar gecersiz kilmalari.</summary>
    private static NativeDpiConfig ApplyOverrides(NativeDpiConfig cfg, string[] args, StringBuilder log)
    {
        var node = JsonSerializer.SerializeToNode(cfg)!.AsObject();

        for (var i = 0; i < args.Length - 1; i++)
        {
            if (!args[i].Equals("--set", StringComparison.OrdinalIgnoreCase)) continue;

            var parts = args[i + 1].Split('=', 2);
            if (parts.Length != 2) continue;

            node[parts[0]] = bool.TryParse(parts[1], out var b) ? JsonValue.Create(b)
                : int.TryParse(parts[1], out var n) ? JsonValue.Create(n)
                : JsonValue.Create(parts[1]);

            log.AppendLine($"Gecersiz kilma: {parts[0]}={parts[1]}");
        }

        return node.Deserialize<NativeDpiConfig>() ?? cfg;
    }

    private static void TryDns(string host, StringBuilder log)
    {
        try
        {
            var ips = Dns.GetHostAddresses(host);
            log.AppendLine($"DNS {host}: {string.Join(", ", ips.Take(4).Select(a => a.ToString()))}");
        }
        catch (Exception ex)
        {
            log.AppendLine($"DNS {host}: HATA {ex.Message}");
        }
    }

    private static bool TryHttps(string url, StringBuilder log)
    {
        // Her denemede yeni baglanti: onceki basarili baglantinin yeniden kullanilmasi
        // sonucu yaniltmasin. HTTP durum kodu onemsiz; TLS el sikismasi tamamlandiysa gecti.
        using var handler = new SocketsHttpHandler
        {
            PooledConnectionLifetime = TimeSpan.Zero,
            AllowAutoRedirect = false,
            ConnectTimeout = TimeSpan.FromSeconds(8),
        };
        using var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(15) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0 GoodbyeDPI-UI-EngineTest");

        var sw = Stopwatch.StartNew();
        try
        {
            using var resp = http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead).GetAwaiter().GetResult();
            log.AppendLine($"[OK]   .NET  {url}  {(int)resp.StatusCode} ({sw.ElapsedMilliseconds} ms)");
            return true;
        }
        catch (Exception ex)
        {
            var inner = ex.InnerException?.Message ?? ex.Message;
            log.AppendLine($"[HATA] .NET  {url}  {inner} ({sw.ElapsedMilliseconds} ms)");
            return false;
        }
    }

    private static bool TryCurl(string url, StringBuilder log)
    {
        var curl = Path.Combine(Environment.SystemDirectory, "curl.exe");
        if (!File.Exists(curl)) { log.AppendLine("curl.exe yok, atlandi"); return true; }

        var (code, stdout, _) = RunProcess(curl,
            $"-s -o NUL --http1.1 -m 15 -w \"%{{http_code}} %{{time_total}}s %{{errormsg}}\" \"{url}\"", 25);
        var ok = code == 0;
        log.AppendLine($"[{(ok ? "OK" : "HATA")}]{(ok ? "  " : "")} curl  {url}  {stdout.Trim()} (cikis {code})");
        return ok;
    }

    private static bool TryChrome(string url, StringBuilder log)
    {
        string[] candidates =
        [
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"Google\Chrome\Application\chrome.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), @"Microsoft\Edge\Application\msedge.exe"),
        ];

        var exe = candidates.FirstOrDefault(File.Exists);
        if (exe is null) { log.AppendLine("Chrome/Edge yok, atlandi"); return true; }

        var profile = Path.Combine(Path.GetTempPath(), "goodbyedpi-chrome-" + Guid.NewGuid().ToString("N"));
        try
        {
            var (code, stdout, stderr) = RunProcess(exe,
                $"--headless=new --disable-gpu --no-first-run --no-default-browser-check " +
                $"--user-data-dir=\"{profile}\" --virtual-time-budget=8000 --dump-dom \"{url}\"", 45);

            // Ag hatasinda Chrome kendi hata sayfasini (main-frame-error / ERR_...) dondurur.
            var failed = stdout.Length < 500 || stdout.Contains("main-frame-error") || stdout.Contains("ERR_CONNECTION");
            var marker = failed
                ? System.Text.RegularExpressions.Regex.Match(stdout + stderr, @"ERR_[A-Z_]+").Value
                : $"{stdout.Length} bayt DOM";

            log.AppendLine($"[{(failed ? "HATA" : "OK")}]{(failed ? "" : "  ")} {Path.GetFileNameWithoutExtension(exe)}  {url}  {marker} (cikis {code})");
            return !failed;
        }
        finally
        {
            try { Directory.Delete(profile, recursive: true); } catch { /* Chrome dosyalari birakmis olabilir */ }
        }
    }

    private static (int Code, string Stdout, string Stderr) RunProcess(string exe, string arguments, int timeoutSeconds)
    {
        var psi = new ProcessStartInfo(exe, arguments)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };

        using var p = Process.Start(psi)!;
        var stdout = p.StandardOutput.ReadToEndAsync();
        var stderr = p.StandardError.ReadToEndAsync();

        if (!p.WaitForExit(timeoutSeconds * 1000))
        {
            try { p.Kill(entireProcessTree: true); } catch { /* zaten kapandi */ }
            return (-1, "zaman asimi", "");
        }

        return (p.ExitCode, stdout.GetAwaiter().GetResult(), stderr.GetAwaiter().GetResult());
    }
}
