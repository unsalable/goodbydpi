using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
using System.Windows;

namespace GoodbyeDpiUI.Services;

/// <summary>Bir GitHub release'inde bulunan, kurulabilir yeni surum.</summary>
public sealed record UpdateInfo(Version Version, string Tag, string AssetName, string DownloadUrl, string? Sha256);

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

    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        var c = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
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
            using var doc = JsonDocument.Parse(await Http.GetStringAsync(ApiUrl, ct).ConfigureAwait(false));
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

            return new UpdateInfo(version, tag, assetName, url, sha);
        }
        catch
        {
            return null; // internet yok / API hatasi / bozuk yanit: sessizce gec
        }
    }

    /// <summary>Setup dosyasini gecici klasore indirir; SHA-256 varsa dogrular. Yol doner.</summary>
    public async Task<string?> DownloadAsync(UpdateInfo info, CancellationToken ct = default)
    {
        try
        {
            var dir = Path.Combine(Path.GetTempPath(), "GoodbyeDPI-UI-Update");
            Directory.CreateDirectory(dir);
            var path = Path.Combine(dir, info.AssetName);

            using (var resp = await Http.GetAsync(info.DownloadUrl, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false))
            {
                resp.EnsureSuccessStatusCode();
                await using var src = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
                await using var dst = File.Create(path);
                await src.CopyToAsync(dst, ct).ConfigureAwait(false);
            }

            if (info.Sha256 is not null && !await VerifyHashAsync(path, info.Sha256, ct).ConfigureAwait(false))
            {
                try { File.Delete(path); } catch { /* onemsiz */ }
                return null;
            }

            return path;
        }
        catch
        {
            return null;
        }
    }

    private static async Task<bool> VerifyHashAsync(string path, string expected, CancellationToken ct)
    {
        await using var fs = File.OpenRead(path);
        var hash = await SHA256.HashDataAsync(fs, ct).ConfigureAwait(false);
        var actual = Convert.ToHexString(hash);
        return string.Equals(actual, expected, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// Sessiz kurulumu baslatir ve uygulamadan cikar. Kurucu dosyalari degistirip
    /// uygulamayi yeniden baslatir. Yonetici yetkisi ust surecten devralinir.
    /// </summary>
    public static bool LaunchInstaller(string setupPath)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = setupPath,
                // Inno Setup sessiz kurulum bayraklari. Calisan uygulamayi kapatir,
                // kurar ve (installer script'indeki [Run] ile) yeniden acar.
                Arguments = "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS /RESTARTAPPLICATIONS",
                UseShellExecute = false,
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
