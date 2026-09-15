using System.Globalization;
using System.Net;
using System.Text;

namespace GoodbyeDpiUI.Models;

/// <summary>
/// DPI atlatma yontemi (yalnizca hazir goodbyedpi.exe altyapisi icin).
/// Argumanlar GoodbyeDPI-Turkey 0.2.3rc3 release'indeki turkey_dnsredir*.cmd
/// dosyalarindan birebir alinmistir. Kendi motorumuz bunun yerine
/// <see cref="NativeProfile"/> / <see cref="NativeDpiConfig"/> kullanir.
/// </summary>
public sealed record DpiMethod(string Id, string Name, string Description, string Arguments)
{
    public static readonly DpiMethod Default = new(
        "default",
        "Varsayılan",
        "Sahte paket, TTL 5 (-5 --set-ttl 5). GoodbyeDPI-Turkey turkey_dnsredir.cmd karşılığı.",
        "-5 --set-ttl 5");

    public static readonly DpiMethod Ttl3 = new(
        "ttl3",
        "Alternatif 1 - TTL 3",
        "Sadece TTL 3. Superonline için alternatif.",
        "--set-ttl 3");

    public static readonly DpiMethod NoTtl = new(
        "nottl",
        "Alternatif 2 - TTL yok",
        "Sahte paket, TTL ayarı yok. Bazı siteler yavaş açılıyorsa bunu dene.",
        "-5");

    public static readonly DpiMethod WrongChecksum = new(
        "mode9",
        "Alternatif 3 - Mod 9",
        "Sahte paket + yanlış sağlama + yanlış SEQ + QUIC engeli.",
        "-9");

    public static readonly IReadOnlyList<DpiMethod> All = new[] { Default, Ttl3, NoTtl, WrongChecksum };

    public static DpiMethod FromId(string? id) =>
        All.FirstOrDefault(m => string.Equals(m.Id, id, StringComparison.OrdinalIgnoreCase)) ?? Default;
}

/// <summary>
/// DNS yonlendirme secenegi. Yapisal alanlar (adres + port) tutulur; hazir
/// goodbyedpi altyapisi icin <see cref="Arguments"/> bunlardan uretilir, kendi
/// motorumuz ise dogrudan alanlari kullanir.
/// </summary>
public sealed record DnsProfile(
    string Id,
    string Name,
    string Description,
    string? V4Addr,
    int V4Port,
    string? V6Addr,
    int V6Port)
{
    public const string CustomId = "custom";

    /// <summary>DNS yonlendirmesi acik mi (en az bir adres tanimli mi)?</summary>
    public bool IsActive => !string.IsNullOrWhiteSpace(V4Addr) || !string.IsNullOrWhiteSpace(V6Addr);

    /// <summary>
    /// Cloudflare yalnizca 53. portta hizmet verir. Hizli ve gizlilik dostudur ama
    /// ISS 53. portu kaciriyorsa yonlendirme etkisiz kalir.
    /// </summary>
    public static readonly DnsProfile Cloudflare = new(
        "cloudflare",
        "Cloudflare",
        "1.1.1.1:53 — hızlı, ancak ISS 53. portu yönlendiriyorsa etkisiz kalabilir.",
        "1.1.1.1", 53, "2606:4700:4700::1111", 53);

    /// <summary>
    /// Turkiye icin asil ise yarayan secenek: 1253 standart disi bir port oldugu icin
    /// ISS'in 53. porttaki DNS kacirmasindan kurtulur.
    /// </summary>
    public static readonly DnsProfile Yandex = new(
        "yandex",
        "Yandex (1253)",
        "77.88.8.8:1253 — standart dışı port, ISS DNS yönlendirmesini aşar.",
        "77.88.8.8", 1253, "2a02:6b8::feed:0ff", 1253);

    public static readonly DnsProfile Off = new(
        "off",
        "Kapalı",
        "DNS'e dokunulmaz, yalnızca DPI atlatma yapılır.",
        null, 0, null, 0);

    /// <summary>Yerlesik (kullanicinin duzenlemedigi) profiller.</summary>
    public static readonly IReadOnlyList<DnsProfile> BuiltIn = new[] { Cloudflare, Yandex, Off };

    /// <summary>Kullanicinin girdigi adres/porttan bir "Ozel" profil olusturur.</summary>
    public static DnsProfile CreateCustom(string? v4Addr, int v4Port, string? v6Addr, int v6Port)
    {
        v4Addr = string.IsNullOrWhiteSpace(v4Addr) ? null : v4Addr.Trim();
        v6Addr = string.IsNullOrWhiteSpace(v6Addr) ? null : v6Addr.Trim();

        var summary = v4Addr is not null
            ? $"{v4Addr}:{(v4Port <= 0 ? 53 : v4Port)}"
            : v6Addr is not null ? $"[{v6Addr}]:{(v6Port <= 0 ? 53 : v6Port)}" : "tanımsız";

        return new DnsProfile(
            CustomId,
            "Özel",
            $"Kendi DNS sunucun: {summary}",
            v4Addr,
            v4Port <= 0 ? 53 : v4Port,
            v6Addr,
            v6Port <= 0 ? 53 : v6Port);
    }

    /// <summary>Kimlige gore yerlesik profili bulur; "custom" ise saglayicidan alir.</summary>
    public static DnsProfile FromId(string? id, Func<DnsProfile>? customProvider = null)
    {
        if (string.Equals(id, CustomId, StringComparison.OrdinalIgnoreCase) && customProvider is not null)
            return customProvider();

        return BuiltIn.FirstOrDefault(p => string.Equals(p.Id, id, StringComparison.OrdinalIgnoreCase)) ?? Cloudflare;
    }

    /// <summary>Adres metinlerinin gecerli IP olup olmadigini denetler.</summary>
    public string? Validate()
    {
        if (!IsActive) return null;

        if (V4Addr is not null &&
            (!IPAddress.TryParse(V4Addr, out var v4) || v4.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork))
            return "Geçersiz IPv4 adresi.";

        if (V6Addr is not null &&
            (!IPAddress.TryParse(V6Addr, out var v6) || v6.AddressFamily != System.Net.Sockets.AddressFamily.InterNetworkV6))
            return "Geçersiz IPv6 adresi.";

        if (V4Port is < 0 or > 65535 || V6Port is < 0 or > 65535)
            return "Port 0-65535 aralığında olmalı.";

        return null;
    }

    /// <summary>goodbyedpi.exe icin --dns-addr/--dns-port arguman dizisi.</summary>
    public string Arguments
    {
        get
        {
            if (!IsActive) return string.Empty;

            var sb = new StringBuilder();

            if (!string.IsNullOrWhiteSpace(V4Addr))
            {
                sb.Append("--dns-addr ").Append(V4Addr);
                if (V4Port is not 53 and > 0)
                    sb.Append(" --dns-port ").Append(V4Port.ToString(CultureInfo.InvariantCulture));
            }

            if (!string.IsNullOrWhiteSpace(V6Addr))
            {
                if (sb.Length > 0) sb.Append(' ');
                sb.Append("--dnsv6-addr ").Append(V6Addr);
                if (V6Port is not 53 and > 0)
                    sb.Append(" --dnsv6-port ").Append(V6Port.ToString(CultureInfo.InvariantCulture));
            }

            return sb.ToString();
        }
    }
}
