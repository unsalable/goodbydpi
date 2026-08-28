namespace GoodbyeDpiUI.Models;

/// <summary>
/// DPI atlatma yontemi. Argumanlar GoodbyeDPI-Turkey 0.2.3rc3 release'indeki
/// turkey_dnsredir*.cmd dosyalarindan birebir alinmistir.
/// </summary>
public sealed record DpiMethod(string Id, string Name, string Description, string Arguments)
{
    public static readonly DpiMethod Default = new(
        "default",
        "Varsayilan",
        "Fake packet + auto-TTL, TTL 5. Repo'nun turkey_dnsredir.cmd karsiligi.",
        "-5 --set-ttl 5");

    public static readonly DpiMethod Ttl3 = new(
        "ttl3",
        "Alternatif 1 - TTL 3",
        "Sadece TTL 3. Superonline alternatif 1/3.",
        "--set-ttl 3");

    public static readonly DpiMethod NoTtl = new(
        "nottl",
        "Alternatif 2 - TTL yok",
        "Fake packet, TTL ayari yok. Bazi siteler yavas aciliyorsa bunu dene.",
        "-5");

    public static readonly DpiMethod WrongChecksum = new(
        "mode9",
        "Alternatif 3 - Mod 9",
        "Fake packet + wrong checksum + wrong seq + QUIC engelleme.",
        "-9");

    public static readonly IReadOnlyList<DpiMethod> All = new[] { Default, Ttl3, NoTtl, WrongChecksum };

    public static DpiMethod FromId(string? id) =>
        All.FirstOrDefault(m => string.Equals(m.Id, id, StringComparison.OrdinalIgnoreCase)) ?? Default;
}

/// <summary>DNS yonlendirme secenegi. GoodbyeDPI'in --dns-addr / --dns-port argumanlarina cevrilir.</summary>
public sealed record DnsProfile(string Id, string Name, string Description, string Arguments)
{
    /// <summary>
    /// Turkiye icin asil ise yarayan secenek: 1253 standart disi bir port oldugu icin
    /// ISS'in 53. porttaki DNS kacirmasindan kurtulur.
    /// </summary>
    public static readonly DnsProfile Yandex = new(
        "yandex",
        "Yandex (1253)",
        "77.88.8.8:1253 - ISS DNS kacirmasini asar, repo varsayilani.",
        "--dns-addr 77.88.8.8 --dns-port 1253 --dnsv6-addr 2a02:6b8::feed:0ff --dnsv6-port 1253");

    /// <summary>
    /// Cloudflare yalnizca 53. portta hizmet verir. Hizli ve gizlilik dostudur ama
    /// ISS 53. portu kaciriyorsa yonlendirme etkisiz kalir.
    /// </summary>
    public static readonly DnsProfile Cloudflare = new(
        "cloudflare",
        "Cloudflare",
        "1.1.1.1:53 - hizli, ancak ISS 53. portu kaciriyorsa etkisiz kalabilir.",
        "--dns-addr 1.1.1.1 --dnsv6-addr 2606:4700:4700::1111");

    public static readonly DnsProfile Off = new(
        "off",
        "Kapali",
        "DNS'e dokunulmaz, yalnizca DPI atlatma yapilir.",
        string.Empty);

    public static readonly IReadOnlyList<DnsProfile> All = new[] { Cloudflare, Yandex, Off };

    public static DnsProfile FromId(string? id) =>
        All.FirstOrDefault(p => string.Equals(p.Id, id, StringComparison.OrdinalIgnoreCase)) ?? Cloudflare;
}
