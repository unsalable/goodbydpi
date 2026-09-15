using System.Text.Json.Serialization;

namespace GoodbyeDpiUI.Models;

/// <summary>
/// Kendi DPI motorumuzun (NativeDpiService) davranis ayarlari.
///
/// Hazir profillerin yani sira kullanicinin elle duzenledigi "Ozel" profil de
/// bu tiple tasinir; bu yuzden mutable ve JSON'a serize edilebilir.
/// Varsayilanlar GoodbyeDPI-Turkey'in "-5 --set-ttl 5" ayarinin karsiligidir.
/// </summary>
public sealed class NativeDpiConfig
{
    /// <summary>Gercek istekten once engelsiz bir siteye ait sahte TLS ClientHello / HTTP istegi gonder.</summary>
    [JsonPropertyName("fakePacket")]
    public bool FakePacket { get; set; } = true;

    /// <summary>Sahte paket dusuk TTL ile gonderilsin (DPI gorur, sunucuya ulasmaz).</summary>
    [JsonPropertyName("fakeTtl")]
    public bool FakeTtl { get; set; } = true;

    /// <summary>
    /// Sahte paketin TTL'ini sunucunun SYN-ACK'indan olculen mesafeye gore hesapla.
    /// Olculemezse <see cref="Ttl"/> kullanilir. Sunucu cok yakinsa sahte paket gonderilmez
    /// (yoksa sahte istek sunucuya ulasip baglantiyi bozardi).
    /// </summary>
    [JsonPropertyName("autoTtl")]
    public bool AutoTtl { get; set; } = true;

    /// <summary>Sahte paketin IP TTL degeri (otomatik TTL kapaliyken ya da olculemediginde).</summary>
    [JsonPropertyName("ttl")]
    public int Ttl { get; set; } = 5;

    /// <summary>Sahte paket yanlis TCP saglama toplamiyla gonderilsin (sunucu atar, DPI isler).</summary>
    [JsonPropertyName("fakeWrongChecksum")]
    public bool FakeWrongChecksum { get; set; }

    /// <summary>Sahte paket gecmiste kalan SEQ/ACK ile gonderilsin.</summary>
    [JsonPropertyName("fakeWrongSeq")]
    public bool FakeWrongSeq { get; set; }

    /// <summary>Gercek TLS ClientHello'yu TCP parcalarina bol.</summary>
    [JsonPropertyName("splitTls")]
    public bool SplitTls { get; set; } = true;

    /// <summary>Parcalari ters sirada gonder (segment birlestiremeyen DPI'lari asar).</summary>
    [JsonPropertyName("reverseSplit")]
    public bool ReverseSplit { get; set; } = true;

    /// <summary>Sabit bolme konumu (payload icindeki bayt ofseti). 0 ise sabit konumdan bolunmez.</summary>
    [JsonPropertyName("splitPosition")]
    public int SplitPosition { get; set; } = 2;

    /// <summary>Ayrica SNI ana bilgisayar adinin ortasindan bol (ad hicbir parcada tam gorunmez).</summary>
    [JsonPropertyName("splitSni")]
    public bool SplitSni { get; set; } = true;

    /// <summary>Cikis QUIC/HTTP3 baslangic paketlerini engelle; uygulama TCP+TLS'e duser.</summary>
    [JsonPropertyName("blockQuic")]
    public bool BlockQuic { get; set; } = true;

    /// <summary>HTTP (port 80) isteklerine de ayni teknikleri uygula.</summary>
    [JsonPropertyName("fragmentHttp")]
    public bool FragmentHttp { get; set; } = true;

    /// <summary>
    /// Sahte paketi koruyan en az bir yontem (TTL / yanlis saglama / yanlis SEQ) secili mi?
    /// Hicbiri yoksa sahte istek sunucuya ulasip baglantiyi bozacagi icin motor TTL'e duser.
    /// </summary>
    [JsonIgnore]
    public bool HasFakeProtection => FakeTtl || FakeWrongChecksum || FakeWrongSeq;

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

    // Canli testte (Turk ISS, Discord/Roblox): TTL tabanli profiller calisti; yanlis
    // saglama/SEQ ve sahte paketsiz bolme calismadi (GoodbyeDPI -9 da ayni). Onlar,
    // TTL'in ise yaramadigi aglar (orn. sanal makine, mobil hotspot) icin duruyor.

    public static readonly NativeProfile Default = new(
        "default",
        "Varsayılan",
        "Sahte paket (otomatik TTL) + istek bölme + QUIC engeli. Önerilen yöntem.",
        () => new NativeDpiConfig());

    public static readonly NativeProfile FixedTtl = new(
        "fixedttl",
        "Sabit TTL",
        "Sahte paket her zaman TTL 5 ile gider (GoodbyeDPI-Turkey \"-5 --set-ttl 5\" karşılığı).",
        () => new NativeDpiConfig { AutoTtl = false, SplitSni = false });

    public static readonly NativeProfile WrongChecksum = new(
        "checksum",
        "Yanlış sağlama",
        "Sahte paket bozuk sağlama ve SEQ ile gider (GoodbyeDPI -9). TTL yöntemi işe yaramazsa dene.",
        () => new NativeDpiConfig { FakeTtl = false, FakeWrongChecksum = true, FakeWrongSeq = true });

    public static readonly NativeProfile SplitOnly = new(
        "split",
        "Sadece bölme",
        "Sahte paket yok, yalnızca istek bölünür. En hafifi; ama çoğu ISS'de tek başına yetmez.",
        () => new NativeDpiConfig { FakePacket = false });

    public static readonly NativeProfile Custom = new(
        CustomId,
        "Özel",
        "Aşağıdaki ayarları kendin belirle.",
        () => new NativeDpiConfig());

    public static readonly IReadOnlyList<NativeProfile> All =
        new[] { Default, FixedTtl, WrongChecksum, SplitOnly, Custom };

    public static NativeProfile FromId(string? id) =>
        All.FirstOrDefault(p => string.Equals(p.Id, id, StringComparison.OrdinalIgnoreCase)) ?? Default;
}
