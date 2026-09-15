using System.Text.Json.Serialization;

namespace GoodbyeDpiUI.Models;

/// <summary>
/// Kendi DPI motorumuzun (NativeDpiService) davranis ayarlari.
///
/// Hazir profillerin yani sira kullanicinin elle duzenledigi "Ozel" profil de
/// bu tiple tasinir; bu yuzden mutable ve JSON'a serize edilebilir.
/// </summary>
public sealed class NativeDpiConfig
{
    /// <summary>Sahte (fake) TLS ClientHello / HTTP istegi gonder.</summary>
    [JsonPropertyName("fakePacket")]
    public bool FakePacket { get; set; } = true;

    /// <summary>Sahte paket dusuk TTL ile gonderilsin (DPI gorur, sunucuya ulasmaz).</summary>
    [JsonPropertyName("fakeTtl")]
    public bool FakeTtl { get; set; } = true;

    /// <summary>Sahte paketin IP TTL degeri.</summary>
    [JsonPropertyName("ttl")]
    public int Ttl { get; set; } = 5;

    /// <summary>Sahte paket yanlis TCP saglama toplamiyla gonderilsin (sunucu atar, DPI isler).</summary>
    [JsonPropertyName("fakeWrongChecksum")]
    public bool FakeWrongChecksum { get; set; }

    /// <summary>Sahte paket gecmiste kalan SEQ/ACK ile gonderilsin.</summary>
    [JsonPropertyName("fakeWrongSeq")]
    public bool FakeWrongSeq { get; set; }

    /// <summary>Gercek TLS ClientHello'yu iki TCP parcasina bol.</summary>
    [JsonPropertyName("splitTls")]
    public bool SplitTls { get; set; } = true;

    /// <summary>Parcalari ters sirada gonder (segment birlestiremeyen DPI'lari asar).</summary>
    [JsonPropertyName("reverseSplit")]
    public bool ReverseSplit { get; set; } = true;

    /// <summary>Bolme konumu (payload icindeki bayt ofseti). 0 ise SNI'dan hemen once bolunur.</summary>
    [JsonPropertyName("splitPosition")]
    public int SplitPosition { get; set; }

    /// <summary>Cikis QUIC/HTTP3 (UDP 443) trafigini engelle; tarayici TCP'ye duser.</summary>
    [JsonPropertyName("blockQuic")]
    public bool BlockQuic { get; set; } = true;

    /// <summary>HTTP (port 80) isteklerini de parcala.</summary>
    [JsonPropertyName("fragmentHttp")]
    public bool FragmentHttp { get; set; } = true;

    public NativeDpiConfig Clone() => (NativeDpiConfig)MemberwiseClone();
}

/// <summary>
/// Kendi motorumuz icin secilebilir hazir profiller. Her biri bir
/// <see cref="NativeDpiConfig"/> uretir; "custom" profili kullanicinin
/// ayarlarindan gelen yapilandirmayi kullanir.
/// </summary>
public sealed record NativeProfile(string Id, string Name, string Description, Func<NativeDpiConfig> Build)
{
    public const string CustomId = "custom";

    public static readonly NativeProfile Default = new(
        "default",
        "Varsayilan",
        "Sahte paket (TTL 5) + TLS bolme + QUIC engel. Cogu baglanti icin onerilir.",
        () => new NativeDpiConfig());

    public static readonly NativeProfile WrongChecksum = new(
        "checksum",
        "Alternatif - Yanlis saglama",
        "Sahte paket yanlis TCP saglamayla gonderilir. VM disinda TTL'den daha guvenli.",
        () => new NativeDpiConfig { FakeTtl = false, FakeWrongChecksum = true });

    public static readonly NativeProfile SplitOnly = new(
        "split",
        "Alternatif - Sadece bolme",
        "Sahte paket yok; yalnizca TLS ClientHello parcalara bolunur. En hafif yontem.",
        () => new NativeDpiConfig { FakePacket = false, BlockQuic = true });

    public static readonly NativeProfile Custom = new(
        CustomId,
        "Ozel",
        "Asagidaki ayarlari kendin belirle.",
        () => new NativeDpiConfig());

    public static readonly IReadOnlyList<NativeProfile> All =
        new[] { Default, WrongChecksum, SplitOnly, Custom };

    public static NativeProfile FromId(string? id) =>
        All.FirstOrDefault(p => string.Equals(p.Id, id, StringComparison.OrdinalIgnoreCase)) ?? Default;
}
