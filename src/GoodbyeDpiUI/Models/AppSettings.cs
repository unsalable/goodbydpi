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

    /// <summary>Kendi motorumuz icin secili profil (NativeProfile.Id ya da ozel profilin kimligi).</summary>
    [JsonPropertyName("nativeProfile")]
    public string NativeProfile { get; set; } = Models.NativeProfile.Default.Id;

    /// <summary>Kullanicinin olusturdugu, adlandirilmis ozel yontem profilleri.</summary>
    [JsonPropertyName("customProfiles")]
    public List<CustomNativeProfile> CustomProfiles { get; set; } = [];

    [JsonPropertyName("dns")]
    public string Dns { get; set; } = DnsProfile.Cloudflare.Id;

    /// <summary>Kullanicinin olusturdugu, adlandirilmis ozel DNS sunuculari.</summary>
    [JsonPropertyName("customDns")]
    public List<CustomDnsEntry> CustomDns { get; set; } = [];

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

    /// <summary>
    /// Kurulumu baslatilan guncellemenin surumu. Uygulama guncellemeden sonra kendini
    /// yeniden acinca bu deger calisan surumle eslesirse "guncellendi" bildirimi gosterilir.
    /// </summary>
    [JsonPropertyName("pendingUpdate")]
    public string? PendingUpdate { get; set; }

    // ------------------------------------------------------ eski surumlerden tasima
    //
    // 2.2.0 ve oncesi tek bir ozel yontem profili ile tek bir ozel DNS girisi biliyordu.
    // Asagidaki alanlar yalnizca o dosyalari okumak icin duruyor; Migrate() degerleri
    // listelere tasidiktan sonra null'a cekiyor ve bir daha yazilmiyorlar.

    [JsonPropertyName("nativeCustom")]
    public NativeDpiConfig? NativeCustom { get; set; }

    [JsonPropertyName("dnsCustomV4")]
    public string? DnsCustomV4 { get; set; }

    [JsonPropertyName("dnsCustomV4Port")]
    public int? DnsCustomV4Port { get; set; }

    [JsonPropertyName("dnsCustomV6")]
    public string? DnsCustomV6 { get; set; }

    [JsonPropertyName("dnsCustomV6Port")]
    public int? DnsCustomV6Port { get; set; }

    /// <summary>
    /// Eski tekil ayarlari listelere tasir ve her iki listede en az bir giris birakir.
    /// Yeni kurulumda da calisir: kullanici "Ozel"i secer secmez duzenleyecegi bir
    /// profil hazir olur. Cagrilmasi guvenlidir, ikinci cagri hicbir sey yapmaz.
    /// </summary>
    public void Migrate()
    {
        if (CustomProfiles.Count == 0)
        {
            CustomProfiles.Add(new CustomNativeProfile
            {
                Id = CustomIds.Legacy,
                Name = CustomNativeProfile.DefaultName,
                Config = NativeCustom?.Clone() ?? new NativeDpiConfig(),
            });
        }

        NativeCustom = null;

        if (CustomDns.Count == 0)
        {
            CustomDns.Add(new CustomDnsEntry
            {
                Id = CustomIds.Legacy,
                Name = CustomDnsEntry.DefaultName,
                V4 = DnsCustomV4 ?? string.Empty,
                V4Port = DnsCustomV4Port ?? 53,
                V6 = DnsCustomV6 ?? string.Empty,
                V6Port = DnsCustomV6Port ?? 53,
            });
        }

        DnsCustomV4 = null;
        DnsCustomV4Port = null;
        DnsCustomV6 = null;
        DnsCustomV6Port = null;
    }
}

[JsonSourceGenerationOptions(WriteIndented = true, DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull)]
[JsonSerializable(typeof(AppSettings))]
internal partial class AppSettingsJsonContext : JsonSerializerContext;
