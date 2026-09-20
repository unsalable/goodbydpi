using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;

namespace GoodbyeDpiUI.Services;

/// <summary>Bir GitHub release'inde bulunan, kurulabilir yeni surum.</summary>
public sealed record UpdateInfo(Version Version, string Tag, string AssetName, string DownloadUrl, string? Sha256, long Size);

/// <summary>Indirme ilerlemesi: su ana kadar inen ve toplam bayt (toplam bilinmiyorsa 0).</summary>
public readonly record struct DownloadProgress(long Done, long Total);

/// <summary>
/// Acilista GitHub'daki en son release'i kontrol eder; daha yeni bir surum varsa
/// setup dosyasini indirip (varsa SHA-256 ile dogrulayarak) sessiz kurulumu baslatir
/// ve uygulamadan cikar - Inno Setup dosyalari degistirip uygulamayi yeniden acar.
///
/// Uygulama zaten yonetici olarak calistigi icin kurucu ikinci bir UAC penceresi
/// acmaz. Internet yoksa ya da bir sey ters giderse tum adimlar sessizce atlanir;
/// guncelleme hicbir zaman uygulamanin acilmasini engellemez.
/// </summary>
public sealed class UpdateService
{
    private const string Owner = "unsalable";
    private const string Repo = "goodbydpi";
    private const string ApiUrl = $"https://api.github.com/repos/{Owner}/{Repo}/releases/latest";

    /// <summary>GitHub API sorgusunun ust siniri; yanit gelmiyorsa acilisi bekletmeyelim.</summary>
    private static readonly TimeSpan CheckTimeout = TimeSpan.FromSeconds(30);

    /// <summary>Indirme ust siniri. ~60 MB'lik setup yavas hatta uzun surebilir.</summary>
    private static readonly TimeSpan DownloadTimeout = TimeSpan.FromMinutes(20);

    /// <summary>Ilerleme bu kadar bayt biriktikce bildirilir (yuzde basina ~2-3 adim).</summary>
    private const long ProgressStep = 256 * 1024;

    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        // Zaman asimi istek basina veriliyor: HttpClient.Timeout govde okunurken de
        // isliyor ve tek bir 30 sn'lik sinir buyuk indirmeyi ortasinda kesiyordu.
        var c = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
        // GitHub API User-Agent zorunlu tutar.
        c.DefaultRequestHeaders.UserAgent.ParseAdd("GoodbyeDPI-UI-Updater");
        c.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        return c;
    }

    /// <summary>Calisan uygulamanin surumu (revizyon yok sayilarak normalize edilir).</summary>
    public static Version CurrentVersion
    {
        get
        {
            var v = Assembly.GetEntryAssembly()?.GetName().Version ?? new Version(0, 0, 0);
            return Normalize(v);
        }
    }

    /// <summary>Yeni surum varsa bilgisini, yoksa null dondurur. Hata durumunda da null.</summary>
    public async Task<UpdateInfo?> CheckAsync(CancellationToken ct = default)
    {
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(CheckTimeout);

            using var doc = JsonDocument.Parse(await Http.GetStringAsync(ApiUrl, timeout.Token).ConfigureAwait(false));
            var root = doc.RootElement;

            if (root.TryGetProperty("draft", out var draft) && draft.GetBoolean()) return null;

            var tag = root.GetProperty("tag_name").GetString();
            if (string.IsNullOrWhiteSpace(tag)) return null;

            if (!TryParseTag(tag, out var version)) return null;
            if (version <= CurrentVersion) return null;

            // Kurulabilir dosyayi sec: once "*setup.exe", yoksa herhangi bir ".exe".
            if (!root.TryGetProperty("assets", out var assets) || assets.ValueKind != JsonValueKind.Array)
                return null;

            JsonElement? chosen = null;
            foreach (var a in assets.EnumerateArray())
            {
                var name = a.GetProperty("name").GetString() ?? "";
                if (!name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) continue;
                if (name.Contains("setup", StringComparison.OrdinalIgnoreCase)) { chosen = a; break; }
                chosen ??= a;
            }

            if (chosen is not { } asset) return null;

            var assetName = asset.GetProperty("name").GetString()!;
            var url = asset.GetProperty("browser_download_url").GetString();
            if (string.IsNullOrWhiteSpace(url)) return null;

            string? sha = null;
            if (asset.TryGetProperty("digest", out var digest) && digest.GetString() is { } d &&
                d.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase))
                sha = d["sha256:".Length..];

            var size = asset.TryGetProperty("size", out var s) && s.TryGetInt64(out var bytes) ? bytes : 0;

            return new UpdateInfo(version, tag, assetName, url, sha, size);
        }
        catch
        {
            return null; // internet yok / API hatasi / bozuk yanit: sessizce gec
        }
    }

    /// <summary>
    /// Setup dosyasini gecici klasore indirir; SHA-256 varsa dogrular. Yol doner.
    /// Ilerleme <paramref name="progress"/> ile bildirilir; iptal edilirse yarim
    /// dosya silinir ve null donulur.
    /// </summary>
    public async Task<string?> DownloadAsync(
        UpdateInfo info, IProgress<DownloadProgress>? progress = null, CancellationToken ct = default)
    {
        var path = Path.Combine(Path.GetTempPath(), "GoodbyeDPI-UI-Update", info.AssetName);

        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(DownloadTimeout);
            var token = timeout.Token;

            Directory.CreateDirectory(Path.GetDirectoryName(path)!);

            using (var resp = await Http
                       .GetAsync(info.DownloadUrl, HttpCompletionOption.ResponseHeadersRead, token)
                       .ConfigureAwait(false))
            {
                resp.EnsureSuccessStatusCode();

                var total = resp.Content.Headers.ContentLength ?? info.Size;
                progress?.Report(new DownloadProgress(0, total));

                await using var src = await resp.Content.ReadAsStreamAsync(token).ConfigureAwait(false);
                await using var dst = File.Create(path);

                var buffer = new byte[81920];
                long done = 0, reported = 0;

                int read;
                while ((read = await src.ReadAsync(buffer, token).ConfigureAwait(false)) > 0)
                {
                    await dst.WriteAsync(buffer.AsMemory(0, read), token).ConfigureAwait(false);
                    done += read;

                    if (done - reported < ProgressStep && done != total) continue;
                    reported = done;
                    progress?.Report(new DownloadProgress(done, total));
                }

                progress?.Report(new DownloadProgress(done, total == 0 ? done : total));
            }

            if (info.Sha256 is not null && !await VerifyHashAsync(path, info.Sha256, ct).ConfigureAwait(false))
            {
                Delete(path);
                return null;
            }

            return path;
        }
        catch
        {
            Delete(path); // yarim inen dosya kalmasin
            return null;
        }
    }

    private static void Delete(string path)
    {
        try { File.Delete(path); } catch { /* onemsiz */ }
    }

    private static async Task<bool> VerifyHashAsync(string path, string expected, CancellationToken ct)
    {
        await using var fs = File.OpenRead(path);
        var hash = await SHA256.HashDataAsync(fs, ct).ConfigureAwait(false);
        var actual = Convert.ToHexString(hash);
        return string.Equals(actual, expected, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// Sessiz kurulumu baslatir. Cagiran taraf hemen uygulamadan cikmali: kurucu
    /// calisan uygulamanin kapanmasini bekler (AppMutex), dosyalari degistirir ve
    /// uygulamayi yeniden acar.
    ///
    /// Kurucunun kendi [Run] adimina guvenmek yetmiyordu (sessiz kurulumdan sonra
    /// uygulama kimi zaman geri gelmiyordu), bu yuzden gizli bir kabuk kurulumun
    /// bitmesini bekleyip uygulamayi <paramref name="relaunchArguments"/> ile
    /// kendisi aciyor. Uygulama tek ornek oldugundan iki yol da calissa ikinci
    /// kopya sessizce cikar.
    /// </summary>
    public static bool LaunchInstaller(string setupPath, string? relaunchArguments = null)
    {
        // Inno Setup sessiz kurulum bayraklari.
        const string SetupArguments = "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS /RESTARTAPPLICATIONS";

        try
        {
            var command = $"\"{setupPath}\" {SetupArguments}";

            // "&&" degil "&": kurulum hata verse bile uygulamayi geri aciyoruz,
            // kullanici guncellenmemis de olsa kapanmis bir uygulamayla kalmasin.
            if (Environment.ProcessPath is { } exe)
            {
                var args = string.IsNullOrWhiteSpace(relaunchArguments) ? "" : " " + relaunchArguments.Trim();
                command += $" & start \"\" \"{exe}\"{args}";
            }

            var psi = new ProcessStartInfo
            {
                FileName = "cmd.exe",
                // /s: disttaki tirnak cifti kaldirilir, ic tirnaklar oldugu gibi kalir.
                Arguments = $"/s /c \"{command}\"",
                UseShellExecute = false,
                CreateNoWindow = true,
                // Kabugun calisma klasoru kurulum klasoru OLMAMALI: acik bir klasor
                // tanitici kurucunun dosya degistirmesini zorlastirabiliyor.
                WorkingDirectory = Path.GetTempPath(),
            };

            Process.Start(psi);
            return true;
        }
        catch
        {
            return false;
        }
    }

    // ------------------------------------------------------------- yardimci

    internal static bool TryParseTag(string tag, out Version version)
    {
        var t = tag.Trim();
        if (t.StartsWith('v') || t.StartsWith('V')) t = t[1..];

        if (Version.TryParse(t, out var parsed))
        {
            version = Normalize(parsed);
            return true;
        }

        version = new Version(0, 0, 0);
        return false;
    }

    /// <summary>Revizyon/eksik bilesenleri sifirlar; yalnizca Major.Minor.Build kiyaslanir.</summary>
    private static Version Normalize(Version v) =>
        new(v.Major, v.Minor, v.Build < 0 ? 0 : v.Build);
}
