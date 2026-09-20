using System.Text.Json.Serialization;

namespace GoodbyeDpiUI.Models;

/// <summary>
/// Kullanicinin kendi olusturdugu profillerin kimlik kurallari.
///
/// Ilk profil eski ayar dosyalariyla uyumlu olsun diye duz "custom" kimligini tasir
/// (eski surumler yalnizca tek bir ozel profil biliyordu); sonradan eklenenler
/// "custom:" onekiyle benzersiz kimlik alir.
/// </summary>
internal static class CustomIds
{
    /// <summary>Eski ayar dosyalarindan tasinan ilk profilin kimligi.</summary>
    public const string Legacy = "custom";

    /// <summary>Sonradan eklenen profillerin kimlik oneki.</summary>
    public const string Prefix = "custom:";

    /// <summary>Kimlik kullanicinin duzenledigi bir profile mi ait?</summary>
    public static bool IsCustom(string? id) =>
        string.Equals(id, Legacy, StringComparison.OrdinalIgnoreCase) ||
        (id is not null && id.StartsWith(Prefix, StringComparison.OrdinalIgnoreCase));

    /// <summary>Listede kullanilmayan yeni bir kimlik uretir.</summary>
    public static string NewId(IEnumerable<string> taken)
    {
        var used = new HashSet<string>(taken, StringComparer.OrdinalIgnoreCase);

        // Ilk profil hic yoksa eski surumlerin tanidigi duz kimlikle basla.
        if (!used.Contains(Legacy)) return Legacy;

        string id;
        do
        {
            id = Prefix + Guid.NewGuid().ToString("n")[..8];
        }
        while (!used.Add(id));

        return id;
    }

    /// <summary>"Özel", "Özel 2", "Özel 3"... seklinde kullanilmayan bir ad uretir.</summary>
    public static string NewName(string baseName, IEnumerable<string> taken)
    {
        var used = new HashSet<string>(taken, StringComparer.CurrentCultureIgnoreCase);
        if (!used.Contains(baseName)) return baseName;

        var (root, start) = SplitCounter(baseName);

        for (var i = start; ; i++)
        {
            var name = $"{root} {i}";
            if (!used.Contains(name)) return name;
        }
    }

    /// <summary>
    /// Sondaki sayaci ayirir: "Özel 2" cogaltilinca "Özel 2 2" degil "Özel 3" olsun.
    /// Sayac yoksa ad oldugu gibi kalir ve 2'den saymaya baslanir.
    /// </summary>
    private static (string Root, int Start) SplitCounter(string name)
    {
        var space = name.LastIndexOf(' ');

        return space > 0 && int.TryParse(name[(space + 1)..], out var n) && n > 1
            ? (name[..space], n + 1)
            : (name, 2);
    }

    /// <summary>Bos ya da yalnizca bosluktan olusan adlari yedek ada dusurur.</summary>
    public static string CleanName(string? name, string fallback)
    {
        var trimmed = name?.Trim();
        return string.IsNullOrEmpty(trimmed) ? fallback : trimmed;
    }
}

/// <summary>
/// Kullanicinin adlandirdigi bir "Ozel" yontem profili. Ayar dosyasinda bir liste
/// olarak saklanir; kullanici istedigi kadar profil olusturup aralarinda gecis yapar.
/// </summary>
public sealed class CustomNativeProfile
{
    public const string DefaultName = "Özel";

    [JsonPropertyName("id")]
    public string Id { get; set; } = CustomIds.Legacy;

    [JsonPropertyName("name")]
    public string Name { get; set; } = DefaultName;

    [JsonPropertyName("config")]
    public NativeDpiConfig Config { get; set; } = new();

    /// <summary>Listeye eklenecek, kimligi ve adi benzersiz yeni bir profil uretir.</summary>
    public static CustomNativeProfile CreateNew(
        IReadOnlyCollection<CustomNativeProfile> existing, NativeDpiConfig? seed = null, string? name = null) =>
        new()
        {
            Id = CustomIds.NewId(existing.Select(p => p.Id)),
            Name = CustomIds.NewName(CustomIds.CleanName(name, DefaultName), existing.Select(p => p.Name)),
            Config = seed?.Clone() ?? new NativeDpiConfig(),
        };

    /// <summary>Acilir listede gosterilecek NativeProfile karsiligi.</summary>
    public NativeProfile ToProfile() =>
        // Build ve aciklama her cagrida guncel Config'i okur: kullanici ayari
        // degistirdiginde listedeki ozet de, motora giden yapilandirma da tazedir.
        new(Id, Name, Config.Summary, () => Config.Clone());
}

/// <summary>Kullanicinin adlandirdigi bir "Ozel" DNS sunucusu.</summary>
public sealed class CustomDnsEntry
{
    public const string DefaultName = "Özel DNS";

    [JsonPropertyName("id")]
    public string Id { get; set; } = CustomIds.Legacy;

    [JsonPropertyName("name")]
    public string Name { get; set; } = DefaultName;

    [JsonPropertyName("v4")]
    public string V4 { get; set; } = string.Empty;

    [JsonPropertyName("v4Port")]
    public int V4Port { get; set; } = 53;

    [JsonPropertyName("v6")]
    public string V6 { get; set; } = string.Empty;

    [JsonPropertyName("v6Port")]
    public int V6Port { get; set; } = 53;

    public static CustomDnsEntry CreateNew(IReadOnlyCollection<CustomDnsEntry> existing, CustomDnsEntry? seed = null) =>
        new()
        {
            Id = CustomIds.NewId(existing.Select(e => e.Id)),
            Name = CustomIds.NewName(seed?.Name ?? DefaultName, existing.Select(e => e.Name)),
            V4 = seed?.V4 ?? string.Empty,
            V4Port = seed?.V4Port ?? 53,
            V6 = seed?.V6 ?? string.Empty,
            V6Port = seed?.V6Port ?? 53,
        };

    /// <summary>Acilir listede ve motorda kullanilan DnsProfile karsiligi.</summary>
    public DnsProfile ToProfile() => DnsProfile.CreateCustom(Id, Name, V4, V4Port, V6, V6Port);
}
