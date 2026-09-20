using System.Text.Json.Serialization;

namespace GoodbyeDpiUI.Models;

/// <summary>Sahte istegin icerigi.</summary>
public enum FakePayloadKind
{
    /// <summary>Engelsiz siteye (www.w3.org) ait gecerli gorunumlu TLS ClientHello / HTTP istegi.</summary>
    Tls = 0,

    /// <summary>4 sifir bayt. DPI akisi taniyamaz, "bilinmeyen protokol" sayip birakir.</summary>
    Zeros = 1,
}

/// <summary>
/// Kendi DPI motorumuzun (NativeDpiService) davranis ayarlari.
///
/// Hazir profillerin yani sira kullanicinin elle duzenledigi "Ozel" profil de
/// bu tiple tasinir; bu yuzden mutable ve JSON'a serize edilebilir.
/// Varsayilanlar GoodbyeDPI-Turkey'in "-5 --set-ttl 5" ayarinin karsiligidir.
/// Eski ayar dosyalarinda olmayan alanlar asagidaki varsayilanlarla dolar.
/// </summary>
public sealed class NativeDpiConfig
{
    // ------------------------------------------------------------ sahte paket

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

    /// <summary>
    /// Sahte pakete TCP MD5 imzasi secenegi (RFC 2385) eklensin. MD5 anahtari olmayan
    /// sunucu (Linux) paketi atar, DPI ise icerigi isler. zapret "--dpi-desync-fooling=md5sig".
    /// </summary>
    [JsonPropertyName("fakeMd5Sig")]
    public bool FakeMd5Sig { get; set; }

    /// <summary>Sahte istegin icerigi: TLS/HTTP istegi ya da sifir baytlar.</summary>
    [JsonPropertyName("fakePayload")]
    public FakePayloadKind FakePayload { get; set; } = FakePayloadKind.Tls;

    /// <summary>Sahte istegin kendisi de 2. bayttan iki TCP parcasi halinde gitsin.</summary>
    [JsonPropertyName("splitFake")]
    public bool SplitFake { get; set; }

    /// <summary>Her sahte paket kac kez gonderilsin (1-10). Kayipli hatlarda DPI'in gormesini garantiler.</summary>
    [JsonPropertyName("fakeRepeats")]
    public int FakeRepeats { get; set; } = 1;

    // ------------------------------------------------------------------ bolme

    /// <summary>Gercek TLS ClientHello'yu TCP parcalarina bol.</summary>
    [JsonPropertyName("splitTls")]
    public bool SplitTls { get; set; } = true;

    /// <summary>Parcalari ters sirada gonder (segment birlestiremeyen DPI'lari asar).</summary>
    [JsonPropertyName("reverseSplit")]
    public bool ReverseSplit { get; set; } = true;

    /// <summary>Sabit bolme konumu (payload icindeki bayt ofseti). 0 ise sabit konumdan bolunmez.</summary>
    [JsonPropertyName("splitPosition")]
    public int SplitPosition { get; set; } = 2;

    /// <summary>
    /// Ayrica SNI ana bilgisayar adinin ortasindan bol (ad hicbir parcada tam gorunmez).
    /// Birden fazla TCP paketine yayilan buyuk ClientHello'larda (Chromium'un ML-KEM /
    /// Kyber istegi) ad ikinci pakette de olsa oradan bolunur.
    /// </summary>
    [JsonPropertyName("splitSni")]
    public bool SplitSni { get; set; } = true;

    /// <summary>
    /// Sira ortusmesi (zapret "seqovl"): bir parcanin basina bu kadar sahte bayt eklenir ve
    /// SEQ ayni miktarda geri cekilir. Sunucu bu baytlari gercek veriyle ezer; DPI ise istegi
    /// bozuk gorur. Ters sirada ikinci parcaya uygulanir ve ilk bolme konumundan kucuk olmalidir.
    /// 0 = kapali.
    /// </summary>
    [JsonPropertyName("seqOverlap")]
    public int SeqOverlap { get; set; }

    // ------------------------------------------------------------------ diger

    /// <summary>Cikis QUIC/HTTP3 baslangic paketlerini engelle; uygulama TCP+TLS'e duser.</summary>
    [JsonPropertyName("blockQuic")]
    public bool BlockQuic { get; set; } = true;

    /// <summary>HTTP (port 80) isteklerine de ayni teknikleri uygula.</summary>
    [JsonPropertyName("fragmentHttp")]
    public bool FragmentHttp { get; set; } = true;

    /// <summary>
    /// Discord ses baglantisinin IP Discovery paketinden ve WebRTC/STUN mesajlarindan once
    /// sahte UDP paketleri gonder. Turk ISS'leri Discord sesini bu ilk UDP paketlerinden
    /// taniyip engelliyor: sesli kanala girilir ama "RTC baglaniyor"da kalinir.
    /// </summary>
    [JsonPropertyName("voiceFake")]
    public bool VoiceFake { get; set; } = true;

    /// <summary>Ses / STUN paketinden once gonderilecek sahte UDP paketi sayisi (1-20).</summary>
    [JsonPropertyName("voiceFakeRepeats")]
    public int VoiceFakeRepeats { get; set; } = 6;

    /// <summary>
    /// Sahte paketi koruyan en az bir yontem (TTL / yanlis saglama / yanlis SEQ / MD5) secili mi?
    /// Hicbiri yoksa sahte istek sunucuya ulasip baglantiyi bozacagi icin motor TTL'e duser.
    /// </summary>
    [JsonIgnore]
    public bool HasFakeProtection => FakeTtl || FakeWrongChecksum || FakeWrongSeq || FakeMd5Sig;

    /// <summary>
    /// Acilir listede profilin altinda gorunen kisa ozet: hangi tekniklerin acik oldugu.
    /// Kullanicinin adlandirdigi profiller birbirinden ancak bu satirla ayirt edilebiliyor.
    /// </summary>
    [JsonIgnore]
    public string Summary
    {
        get
        {
            var parts = new List<string>(4);

            if (FakePacket)
            {
                var how =
                    AutoTtl && FakeTtl ? "oto TTL" :
                    FakeTtl ? $"TTL {Ttl}" :
                    FakeMd5Sig ? "MD5" :
                    FakeWrongChecksum || FakeWrongSeq ? "bozuk sağlama" :
                    "korumasız";

                var payload = FakePayload == FakePayloadKind.Zeros ? "boş sahte" : "sahte paket";
                parts.Add(SplitFake ? $"{payload} ({how}, bölünmüş)" : $"{payload} ({how})");
            }

            if (SplitTls)
            {
                var split = ReverseSplit ? "ters sıra bölme" : "bölme";
                if (SeqOverlap > 0) split += $" +{SeqOverlap} örtüşme";
                parts.Add(split);
            }

            if (BlockQuic) parts.Add("QUIC engeli");
            if (VoiceFake) parts.Add("Discord ses");

            return parts.Count == 0 ? "Atlatma tekniği seçilmedi." : string.Join(" · ", parts);
        }
    }

    public NativeDpiConfig Clone() => (NativeDpiConfig)MemberwiseClone();
}

/// <summary>
/// Kendi motorumuz icin secilebilir hazir yontemler. Her biri bir
/// <see cref="NativeDpiConfig"/> uretir; "custom" yontemi kullanicinin
/// ayarlarindan gelen yapilandirmayi kullanir. Hangi yontemin hangi internet
/// saglayicisinda onerildigi <see cref="IspProfile"/> icinde tanimlidir.
/// </summary>
public sealed record NativeProfile(string Id, string Name, string Description, Func<NativeDpiConfig> Build)
{
    public const string CustomId = "custom";

    /// <summary>Kimlik kullanicinin olusturdugu bir ozel profile mi ait?</summary>
    public static bool IsCustomId(string? id) => CustomIds.IsCustom(id);

    /// <summary>
    /// Esitlik yalnizca kimlige bakar. Kullanicinin profilleri liste her
    /// tazelendiginde yeniden uretiliyor; kayit varsayilani <see cref="Build"/>
    /// temsilcisini de karsilastirdigi icin ComboBox secili ogeyi kaybediyordu.
    /// </summary>
    public bool Equals(NativeProfile? other) =>
        other is not null && string.Equals(Id, other.Id, StringComparison.OrdinalIgnoreCase);

    public override int GetHashCode() => StringComparer.OrdinalIgnoreCase.GetHashCode(Id);

    // Canli testte (Turk ISS, Discord/Roblox): TTL tabanli profiller calisti; yanlis
    // saglama/SEQ ve sahte paketsiz bolme calismadi (GoodbyeDPI -9 da ayni). Onlar,
    // TTL'in ise yaramadigi aglar (orn. sanal makine, mobil hotspot) icin duruyor.

    public static readonly NativeProfile Default = new(
        "default",
        "Varsayılan",
        "Sahte paket (otomatik TTL) + istek bölme + QUIC engeli + Discord ses desteği.",
        () => new NativeDpiConfig());

    public static readonly NativeProfile FixedTtl = new(
        "fixedttl",
        "Sabit TTL",
        "Sahte paket her zaman TTL 5 ile gider (GoodbyeDPI-Turkey \"-5 --set-ttl 5\" karşılığı).",
        () => new NativeDpiConfig { AutoTtl = false, SplitSni = false });

    // ---- ISS'e ozel yontemler. Parametreler SplitWire-Turkey'in (cagritaskn) zapret /
    // zapret2 Turkiye ISS hazir ayarlarindan uyarlanmistir; parantez icindeki karsiliklar
    // aciklamada yazili.

    /// <summary>zapret2 "multidisorder:pos=2:seqovl=1": sahte paket yok, TTL tahmini gerekmez.</summary>
    public static readonly NativeProfile Disorder = new(
        "disorder",
        "Ters sıra",
        "Sahte paket yok: istek 2. bayttan bölünür, parçalar ters sırada ve 1 bayt örtüşmeyle gider (zapret multidisorder pos=2 seqovl=1).",
        () => new NativeDpiConfig
        {
            FakePacket = false,
            SplitPosition = 2,
            SplitSni = false,
            ReverseSplit = true,
            SeqOverlap = 1,
        });

    /// <summary>zapret "--dpi-desync=fake --dpi-desync-ttl=4".</summary>
    public static readonly NativeProfile FakeTtl4 = new(
        "ttl4",
        "Sahte TTL 4",
        "Sahte paket TTL 4 ile gider, istek bölünmez (zapret fake ttl=4).",
        () => new NativeDpiConfig { AutoTtl = false, Ttl = 4, SplitTls = false });

    /// <summary>zapret "--dpi-desync=fake --dpi-desync-ttl=3".</summary>
    public static readonly NativeProfile FakeTtl3 = new(
        "ttl3",
        "Sahte TTL 3",
        "Sahte paket TTL 3 ile gider, istek bölünmez (zapret fake ttl=3).",
        () => new NativeDpiConfig { AutoTtl = false, Ttl = 3, SplitTls = false });

    /// <summary>zapret "--dpi-desync=fake --dpi-desync-fooling=md5sig".</summary>
    public static readonly NativeProfile Md5Sig = new(
        "md5sig",
        "MD5 imzası",
        "Sahte paket TCP MD5 imzasıyla gider; sunucu atar, DPI işler (zapret fake md5sig).",
        () => new NativeDpiConfig { FakeTtl = false, FakeMd5Sig = true, SplitTls = false });

    /// <summary>zapret "--dpi-desync=fake --dpi-desync-fooling=md5sig --dpi-desync-ttl=3".</summary>
    public static readonly NativeProfile Md5Ttl3 = new(
        "md5ttl3",
        "MD5 + TTL 3",
        "Sahte paket hem MD5 imzası hem TTL 3 ile gider (zapret fake md5sig ttl=3).",
        () => new NativeDpiConfig { AutoTtl = false, Ttl = 3, FakeMd5Sig = true, SplitTls = false });

    /// <summary>zapret2 "multisplit:blob=fake_default_tls:ip_ttl=5:pos=2:nodrop".</summary>
    public static readonly NativeProfile SplitFakeTtl5 = new(
        "fakesplit5",
        "Bölünmüş sahte",
        "Sahte istek 2. bayttan iki parça halinde TTL 5 ile gider, gerçek istek değişmez (zapret2 multisplit ip_ttl=5 nodrop).",
        () => new NativeDpiConfig { AutoTtl = false, Ttl = 5, SplitFake = true, SplitTls = false });

    /// <summary>zapret2 "fake:blob=0x00000000:ip_ttl=5".</summary>
    public static readonly NativeProfile ZeroFake = new(
        "zerofake",
        "Boş sahte",
        "Sahte paket olarak 4 sıfır bayt TTL 5 ile gider; DPI akışı tanıyamaz (zapret2 fake blob=0x00000000 ip_ttl=5).",
        () => new NativeDpiConfig { AutoTtl = false, Ttl = 5, FakePayload = FakePayloadKind.Zeros, SplitTls = false });

    /// <summary>zapret "--dpi-desync=multisplit --dpi-desync-split-pos=2".</summary>
    public static readonly NativeProfile PlainSplit = new(
        "split2",
        "Düz bölme",
        "Sahte paket yok: istek 2. bayttan bölünür, parçalar normal sırada gider (zapret multisplit pos=2).",
        () => new NativeDpiConfig { FakePacket = false, SplitPosition = 2, SplitSni = false, ReverseSplit = false });

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

    /// <summary>Tum yontemler (ayar dosyasindaki kimligi cozmek icin).</summary>
    public static readonly IReadOnlyList<NativeProfile> All =
    [
        Default, FixedTtl, Disorder, FakeTtl4, FakeTtl3, Md5Sig, Md5Ttl3,
        SplitFakeTtl5, ZeroFake, PlainSplit, WrongChecksum, SplitOnly, Custom,
    ];

    public static NativeProfile FromId(string? id) =>
        All.FirstOrDefault(p => string.Equals(p.Id, id, StringComparison.OrdinalIgnoreCase)) ?? Default;
}
