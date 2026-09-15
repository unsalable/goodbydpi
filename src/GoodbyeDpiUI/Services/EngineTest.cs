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
///   --enginetest [yontem kimligi (default|disorder|ttl4|md5sig|...)|gdpi|off]
///                [--isp turktelekom|superonline|...] [--set anahtar=deger]...
///                [--dns yandex|cloudflare|off] [--method default|ttl3|nottl|mode9]
///                [--out dosya] [--curl] [--chrome [--chromerepeat n]] [--voice]
///                [--hold saniye --ready dosya --stopfile dosya]
///
/// --isp: yontem verilmemisse saglayicinin onerdigi yontem ve DNS kullanilir.
/// --voice: STUN (Google) yaniti + Discord IP Discovery bicimli paketin motorca
/// yakalanip sahte UDP uretildigi sayaclardan dogrulanir.
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
        "https://media.discordapp.net/",
        "https://www.roblox.com/",
        "https://apis.roblox.com/",
    ];

    private const string ControlUrl = "https://www.example.com/";

    private static readonly string[] ChromeUrls = ["https://discord.com/login", "https://www.roblox.com/"];

    public static int Run(string[] args)
    {
        var log = new StringBuilder();
        // --isp verilirse yontem belirtilmedikce saglayicinin onerisi, DNS de onun DNS'i kullanilir.
        var isp = IspProfile.FromId(ArgAfter(args, "--isp"));
        var explicitMode = ArgAfter(args, "--enginetest") is { } m && !m.StartsWith("--") ? m.ToLowerInvariant() : null;
        var mode = explicitMode ?? isp.Recommended.Id;
        var dns = (ArgAfter(args, "--dns")?.ToLowerInvariant() ?? isp.DnsId) switch
        {
            "off" => DnsProfile.Off,
            "cloudflare" => DnsProfile.Cloudflare,
            _ => DnsProfile.Yandex,
        };

        var outPath = ArgAfter(args, "--out") ?? Path.Combine(Path.GetTempPath(), "goodbyedpi-enginetest.txt");
        var useCurl = args.Contains("--curl", StringComparer.OrdinalIgnoreCase);
        var useChrome = args.Contains("--chrome", StringComparer.OrdinalIgnoreCase);
        var useVoice = args.Contains("--voice", StringComparer.OrdinalIgnoreCase);
        var chromeRepeat = int.TryParse(ArgAfter(args, "--chromerepeat"), out var cr) ? Math.Clamp(cr, 1, 20) : 1;

        IDpiBackend? backend = null;
        var pass = false;

        try
        {
            var cfg = NativeProfile.FromId(mode).Build();
            cfg = ApplyOverrides(cfg, args, log);

            log.AppendLine($"Mod: {mode}   Saglayici: {isp.Name}   DNS: {dns.Name}");
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
                // Chromium uzanti sirasini her baglantida karistirir; ML-KEM'li buyuk
                // ClientHello'da SNI bazen ikinci pakete duser. Tekrar bu durumu da yakalar.
                log.AppendLine();
                var urls = Enumerable.Repeat(ChromeUrls, chromeRepeat).SelectMany(u => u).ToArray();
                var n = urls.Count(u => TryChrome(u, log));
                chromeOk = n == urls.Length;
                log.AppendLine($"=> Chrome: {n}/{urls.Length}");
            }

            var voiceOk = true;
            if (useVoice)
            {
                log.AppendLine();
                voiceOk = TryVoice(backend as NativeDpiService, log);
            }

            pass = controlOk && blockedOk == BlockedUrls.Length && curlOk && chromeOk && voiceOk;
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

    /// <summary>
    /// Ses yolunun canli sinamasi:
    ///  1. Google STUN sunucusuna Binding istegi atilir ve yanit beklenir; motor STUN'dan once
    ///     sahte UDP gonderirken gercek istegi bozmamali.
    ///  2. Discord IP Discovery bicimindeki paket TEST-NET-2 (198.51.100.0/24, yonlendirilmeyen
    ///     belge adresi) hedefine gonderilir; motorun paketi yakalayip sahte UDP urettigi
    ///     sayaclardan dogrulanir. Gercek bir sunucuya hic trafik gitmez.
    /// </summary>
    private static bool TryVoice(NativeDpiService? engine, StringBuilder log)
    {
        var before = (engine?.Stats?.StunMessages ?? 0, engine?.Stats?.VoiceDiscoveries ?? 0, engine?.Stats?.VoiceFakesSent ?? 0);
        var ok = true;

        try
        {
            using var udp = new System.Net.Sockets.UdpClient(System.Net.Sockets.AddressFamily.InterNetwork);
            udp.Client.ReceiveTimeout = 4000;

            var stunHost = Dns.GetHostAddresses("stun.l.google.com")
                .First(a => a.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork);

            var request = new byte[20];
            request[1] = 0x01;                                       // Binding Request
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(4), 0x2112A442);
            Random.Shared.NextBytes(request.AsSpan(8, 12));           // islem kimligi

            var sw = Stopwatch.StartNew();
            udp.Send(request, request.Length, new IPEndPoint(stunHost, 19302));
            var remote = new IPEndPoint(IPAddress.Any, 0);
            var response = udp.Receive(ref remote);
            var matches = response.Length >= 20 && response.AsSpan(8, 12).SequenceEqual(request.AsSpan(8, 12));
            log.AppendLine($"[{(matches ? "OK" : "HATA")}]{(matches ? "  " : "")} STUN {stunHost}:19302 yanit {response.Length} bayt ({sw.ElapsedMilliseconds} ms)");
            ok &= matches;
        }
        catch (Exception ex)
        {
            log.AppendLine($"[HATA] STUN: {ex.Message}");
            ok = false;
        }

        try
        {
            using var udp = new System.Net.Sockets.UdpClient(System.Net.Sockets.AddressFamily.InterNetwork);
            var discovery = new byte[74];
            discovery[1] = 0x01;
            discovery[3] = 70;
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(discovery.AsSpan(4), 0x00C0FFEE);
            udp.Send(discovery, discovery.Length, new IPEndPoint(IPAddress.Parse("198.51.100.7"), 50004));
            Thread.Sleep(300);
            log.AppendLine("Discord IP Discovery bicimli paket 198.51.100.7:50004 hedefine gonderildi");
        }
        catch (Exception ex)
        {
            log.AppendLine($"[HATA] IP Discovery gonderilemedi: {ex.Message}");
            ok = false;
        }

        if (engine?.Stats is { } stats)
        {
            var stun = stats.StunMessages - before.Item1;
            var disc = stats.VoiceDiscoveries - before.Item2;
            var fakes = stats.VoiceFakesSent - before.Item3;
            var engineOk = stun >= 1 && disc >= 1 && fakes >= 2;
            log.AppendLine($"[{(engineOk ? "OK" : "HATA")}]{(engineOk ? "  " : "")} motor: STUN {stun}, IP Discovery {disc}, sahte UDP {fakes}");
            ok &= engineOk;
        }

        log.AppendLine($"=> Ses: {(ok ? "OK" : "HATA")}");
        return ok;
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
