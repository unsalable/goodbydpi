namespace GoodbyeDpiUI.Models;

/// <summary>
/// Internet saglayicisina ozel hazir ayar. Her ISS'in DPI kutusu farkli davrandigi icin
/// birinde calisan yontem digerinde calismayabilir; burada her saglayici icin bilinen
/// calisan yontemler oneri sirasiyla tutulur (ilki onerilen).
///
/// Kaynak: SplitWire-Turkey (cagritaskn) icindeki zapret / zapret2 Turkiye ISS hazir
/// ayarlari ve GoodbyeDPI-Turkey. Turk ISS'lerinin hepsi 53. porttaki DNS'i kaciriyor;
/// bu yuzden hazir ayarlar standart disi portlu Yandex DNS'i secer.
/// </summary>
public sealed record IspProfile(
    string Id,
    string Name,
    string Description,
    IReadOnlyList<NativeProfile> Methods,
    string? DnsId,
    string GoodbyeMethodId)
{
    public const string GeneralId = "general";

    /// <summary>Onerilen yontem (listenin ilki).</summary>
    public NativeProfile Recommended => Methods[0];

    /// <summary>Belirli bir saglayici secilmemis: onceki surumlerle ayni genel yontemler, DNS'e dokunulmaz.</summary>
    public static readonly IspProfile General = new(
        GeneralId,
        "Genel",
        "Sağlayıcıya özel ayar yok; genel yöntemler listelenir.",
        [NativeProfile.Default, NativeProfile.FixedTtl, NativeProfile.Disorder, NativeProfile.WrongChecksum, NativeProfile.SplitOnly],
        DnsId: null,
        DpiMethod.Default.Id);

    public static readonly IspProfile TurkTelekom = new(
        "turktelekom",
        "Türk Telekom",
        "Önerilen: Ters sıra (sahte paket yok). Olmazsa Sahte TTL 4 / 3. Discord sesi için UDP desteği açık, DNS: Yandex.",
        [NativeProfile.Disorder, NativeProfile.FakeTtl4, NativeProfile.FakeTtl3, NativeProfile.Default],
        DnsProfile.Yandex.Id,
        DpiMethod.Default.Id);

    public static readonly IspProfile Superonline = new(
        "superonline",
        "Superonline",
        "Önerilen: Ters sıra. Olmazsa MD5 imzası / MD5 + TTL 3 / Sahte TTL 3. DNS: Yandex.",
        [NativeProfile.Disorder, NativeProfile.Md5Sig, NativeProfile.Md5Ttl3, NativeProfile.FakeTtl3],
        DnsProfile.Yandex.Id,
        DpiMethod.Ttl3.Id);

    public static readonly IspProfile Vodafone = new(
        "vodafone",
        "Vodafone",
        "Önerilen: Bölünmüş sahte (TTL 5). Olmazsa Ters sıra / Varsayılan. DNS: Yandex.",
        [NativeProfile.SplitFakeTtl5, NativeProfile.Disorder, NativeProfile.Default],
        DnsProfile.Yandex.Id,
        DpiMethod.Default.Id);

    public static readonly IspProfile TurkNet = new(
        "turknet",
        "TürkNet",
        "Önerilen: Varsayılan (otomatik TTL). Olmazsa Sabit TTL / Ters sıra. DNS: Yandex.",
        [NativeProfile.Default, NativeProfile.FixedTtl, NativeProfile.Disorder],
        DnsProfile.Yandex.Id,
        DpiMethod.Default.Id);

    public static readonly IspProfile Kablonet = new(
        "kablonet",
        "Kablonet",
        "Türksat Kablonet. Önerilen: Ters sıra. Olmazsa Sahte TTL 4 / Varsayılan. DNS: Yandex.",
        [NativeProfile.Disorder, NativeProfile.FakeTtl4, NativeProfile.Default],
        DnsProfile.Yandex.Id,
        DpiMethod.Default.Id);

    public static readonly IspProfile TelekomMobil = new(
        "telekommobil",
        "TT Mobil",
        "Türk Telekom mobil hat / hotspot. Önerilen: Boş sahte (TTL 5). Olmazsa Ters sıra / Sahte TTL 4. DNS: Yandex.",
        [NativeProfile.ZeroFake, NativeProfile.Disorder, NativeProfile.FakeTtl4],
        DnsProfile.Yandex.Id,
        DpiMethod.Default.Id);

    public static readonly IspProfile TurkcellMobil = new(
        "turkcellmobil",
        "Turkcell Mobil",
        "Turkcell mobil hat / hotspot. Önerilen: Ters sıra. Olmazsa Varsayılan / Sahte TTL 3. DNS: Yandex.",
        [NativeProfile.Disorder, NativeProfile.Default, NativeProfile.FakeTtl3],
        DnsProfile.Yandex.Id,
        DpiMethod.Ttl3.Id);

    public static readonly IspProfile VodafoneMobil = new(
        "vodafonemobil",
        "Vodafone Mobil",
        "Vodafone mobil hat / hotspot. Önerilen: Ters sıra. Olmazsa Düz bölme / Bölünmüş sahte. DNS: Yandex.",
        [NativeProfile.Disorder, NativeProfile.PlainSplit, NativeProfile.SplitFakeTtl5],
        DnsProfile.Yandex.Id,
        DpiMethod.Default.Id);

    public static readonly IReadOnlyList<IspProfile> All =
    [
        General, TurkTelekom, Superonline, Vodafone, TurkNet, Kablonet, TelekomMobil, TurkcellMobil, VodafoneMobil,
    ];

    public static IspProfile FromId(string? id) =>
        All.FirstOrDefault(p => string.Equals(p.Id, id, StringComparison.OrdinalIgnoreCase)) ?? General;
}
