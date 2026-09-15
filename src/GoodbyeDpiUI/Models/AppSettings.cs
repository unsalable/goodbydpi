using System.Text.Json.Serialization;

namespace GoodbyeDpiUI.Models;

/// <summary>%AppData%\GoodbyeDPI-UI\settings.json icinde saklanan kullanici tercihleri.</summary>
public sealed class AppSettings
{
    [JsonPropertyName("darkMode")]
    public bool DarkMode { get; set; }

    /// <summary>Hangi altyapi kullanilsin: kendi motorumuz mu, hazir goodbyedpi.exe mi.</summary>
    [JsonPropertyName("engine")]
    public EngineKind Engine { get; set; } = EngineKind.Native;

    /// <summary>Secili internet saglayicisi (IspProfile.Id); yontem listesini ve onerileri belirler.</summary>
    [JsonPropertyName("isp")]
    public string Isp { get; set; } = IspProfile.GeneralId;

    /// <summary>Hazir goodbyedpi altyapisi icin secili yontem.</summary>
    [JsonPropertyName("method")]
    public string Method { get; set; } = DpiMethod.Default.Id;

    /// <summary>Kendi motorumuz icin secili profil (NativeProfile.Id).</summary>
    [JsonPropertyName("nativeProfile")]
    public string NativeProfile { get; set; } = Models.NativeProfile.Default.Id;

    /// <summary>"Ozel" native profili icin kullanicinin duzenledigi ayarlar.</summary>
    [JsonPropertyName("nativeCustom")]
    public NativeDpiConfig NativeCustom { get; set; } = new();

    [JsonPropertyName("dns")]
    public string Dns { get; set; } = DnsProfile.Cloudflare.Id;

    // --- Ozel DNS (Dns == "custom" oldugunda kullanilir) ---

    [JsonPropertyName("dnsCustomV4")]
    public string DnsCustomV4 { get; set; } = string.Empty;

    [JsonPropertyName("dnsCustomV4Port")]
    public int DnsCustomV4Port { get; set; } = 53;

    [JsonPropertyName("dnsCustomV6")]
    public string DnsCustomV6 { get; set; } = string.Empty;

    [JsonPropertyName("dnsCustomV6Port")]
    public int DnsCustomV6Port { get; set; } = 53;

    /// <summary>Windows acilisinda otomatik baslat (Gorev Zamanlayici gorevi).</summary>
    [JsonPropertyName("runAtStartup")]
    public bool RunAtStartup { get; set; } = true;

    /// <summary>Uygulama acilir acilmaz baglantiyi da baslat.</summary>
    [JsonPropertyName("autoConnect")]
    public bool AutoConnect { get; set; } = true;

    /// <summary>Pencere kapatilinca cikmak yerine tepsiye kucul.</summary>
    [JsonPropertyName("minimizeToTray")]
    public bool MinimizeToTray { get; set; } = true;

    /// <summary>Acilista GitHub'da yeni surum var mi diye bak ve otomatik guncelle.</summary>
    [JsonPropertyName("autoUpdate")]
    public bool AutoUpdate { get; set; } = true;

    public AppSettings Clone()
    {
        var copy = (AppSettings)MemberwiseClone();
        copy.NativeCustom = NativeCustom.Clone();
        return copy;
    }
}

[JsonSourceGenerationOptions(WriteIndented = true)]
[JsonSerializable(typeof(AppSettings))]
internal partial class AppSettingsJsonContext : JsonSerializerContext;
