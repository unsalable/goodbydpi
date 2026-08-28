using System.Text.Json.Serialization;

namespace GoodbyeDpiUI.Models;

/// <summary>%AppData%\GoodbyeDPI-UI\settings.json icinde saklanan kullanici tercihleri.</summary>
public sealed class AppSettings
{
    [JsonPropertyName("darkMode")]
    public bool DarkMode { get; set; }

    [JsonPropertyName("method")]
    public string Method { get; set; } = DpiMethod.Default.Id;

    [JsonPropertyName("dns")]
    public string Dns { get; set; } = DnsProfile.Cloudflare.Id;

    /// <summary>Windows acilisinda otomatik baslat (Gorev Zamanlayici gorevi).</summary>
    [JsonPropertyName("runAtStartup")]
    public bool RunAtStartup { get; set; } = true;

    /// <summary>Uygulama acilir acilmaz baglantiyi da baslat.</summary>
    [JsonPropertyName("autoConnect")]
    public bool AutoConnect { get; set; } = true;

    /// <summary>Pencere kapatilinca cikmak yerine tepsiye kucul.</summary>
    [JsonPropertyName("minimizeToTray")]
    public bool MinimizeToTray { get; set; } = true;

    public AppSettings Clone() => (AppSettings)MemberwiseClone();
}

[JsonSourceGenerationOptions(WriteIndented = true)]
[JsonSerializable(typeof(AppSettings))]
internal partial class AppSettingsJsonContext : JsonSerializerContext;
