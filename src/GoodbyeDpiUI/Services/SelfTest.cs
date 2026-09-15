using System.Buffers.Binary;
using System.IO;
using System.Text;
using GoodbyeDpiUI.Models;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Yonetici gerektirmeyen, WinDivert surucusune dokunmayan ic dogrulama takimi.
/// "--selftest" argumaniyla calistirilir; paket cozumleme, TLS SNI bulma, DNS
/// arguman uretimi ve surum kiyaslama gibi saf mantigi gercek kodla test eder.
/// Sonuclar bir dosyaya yazilir ve cikis kodu 0 (gecti) / 1 (kaldi) olur.
/// </summary>
internal static class SelfTest
{
    private static readonly StringBuilder Log = new();
    private static int _failed;

    public static int Run()
    {
        TlsSniIpv4();
        TlsSniIpv6();
        IpMutators();
        DnsArguments();
        DnsValidation();
        NativeProfiles();
        VersionParsing();

        Log.Insert(0, _failed == 0 ? "TUM TESTLER GECTI\n\n" : $"{_failed} TEST KALDI\n\n");

        var path = Path.Combine(Path.GetTempPath(), "goodbyedpi-selftest.txt");
        try { File.WriteAllText(path, Log.ToString()); } catch { /* onemsiz */ }

        return _failed == 0 ? 0 : 1;
    }

    // -------------------------------------------------- testler

    private static void TlsSniIpv4()
    {
        var (packet, len, sniStart, host) = BuildTlsPacket(v6: false, "example.com");
        var ip = IpPacket.Parse(packet, len);

        Check("v4 parse gecerli", ip.Valid);
        Check("v4 protokol TCP", ip.Protocol == 6);
        Check("v4 dst port 443", ip.DstPort == 443);
        Check("v4 payload ofseti 40", ip.PayloadOffset == 40);

        var sni = TlsParser.FindSniOffset(packet, ip.PayloadOffset, ip.PayloadLength);
        Check("v4 SNI ofseti bulundu", sni == sniStart, $"beklenen {sniStart}, bulunan {sni}");

        if (sni > 0)
        {
            var found = Encoding.ASCII.GetString(packet, ip.PayloadOffset + sni, host.Length);
            Check("v4 SNI adi dogru", found == host, $"'{found}'");
        }
    }

    private static void TlsSniIpv6()
    {
        var (packet, len, sniStart, host) = BuildTlsPacket(v6: true, "cloudflare.com");
        var ip = IpPacket.Parse(packet, len);

        Check("v6 parse gecerli", ip.Valid && ip.IsV6);
        Check("v6 payload ofseti 60", ip.PayloadOffset == 60, $"{ip.PayloadOffset}");

        var sni = TlsParser.FindSniOffset(packet, ip.PayloadOffset, ip.PayloadLength);
        Check("v6 SNI ofseti bulundu", sni == sniStart, $"beklenen {sniStart}, bulunan {sni}");
    }

    private static void IpMutators()
    {
        var (packet, len, _, _) = BuildTlsPacket(v6: false, "test.org");
        var ip = IpPacket.Parse(packet, len);

        var copy = (byte[])packet.Clone();
        ip.SetTtl(copy, 5);
        Check("TTL 5 yazildi", copy[8] == 5);

        var seq0 = ip.TcpSeq;
        ip.SetTcpSeq(copy, seq0 + 100);
        var seq1 = BinaryPrimitives.ReadUInt32BigEndian(copy.AsSpan(24)); // v4: 20 + 4
        Check("SEQ + 100 yazildi", seq1 == seq0 + 100, $"{seq0} -> {seq1}");

        ip.SetTotalLength(copy, 30);
        Check("Toplam uzunluk 30", BinaryPrimitives.ReadUInt16BigEndian(copy.AsSpan(2)) == 30);

        ip.SetDstPort(copy, 8443);
        Check("Dst port 8443", BinaryPrimitives.ReadUInt16BigEndian(copy.AsSpan(22)) == 8443);

        var before = BinaryPrimitives.ReadUInt16BigEndian(copy.AsSpan(20 + 16));
        ip.CorruptTcpChecksum(copy);
        var after = BinaryPrimitives.ReadUInt16BigEndian(copy.AsSpan(20 + 16));
        Check("TCP saglama bozuldu", before != after);
    }

    private static void DnsArguments()
    {
        Check("Cloudflare arg",
            DnsProfile.Cloudflare.Arguments == "--dns-addr 1.1.1.1 --dnsv6-addr 2606:4700:4700::1111",
            DnsProfile.Cloudflare.Arguments);

        Check("Yandex arg",
            DnsProfile.Yandex.Arguments ==
            "--dns-addr 77.88.8.8 --dns-port 1253 --dnsv6-addr 2a02:6b8::feed:0ff --dnsv6-port 1253",
            DnsProfile.Yandex.Arguments);

        Check("Kapali arg bos", DnsProfile.Off.Arguments == "");

        var custom = DnsProfile.CreateCustom("8.8.8.8", 53, null, 0);
        Check("Ozel v4 arg", custom.Arguments == "--dns-addr 8.8.8.8", custom.Arguments);
        Check("Ozel aktif", custom.IsActive);

        var customPort = DnsProfile.CreateCustom("9.9.9.9", 5353, null, 0);
        Check("Ozel v4 port arg", customPort.Arguments == "--dns-addr 9.9.9.9 --dns-port 5353", customPort.Arguments);
    }

    private static void DnsValidation()
    {
        Check("Gecerli IPv4 kabul", DnsProfile.CreateCustom("1.2.3.4", 53, null, 0).Validate() is null);
        Check("Gecersiz IPv4 red", DnsProfile.CreateCustom("999.1.1.1", 53, null, 0).Validate() is not null);
        Check("Gecersiz IPv6 red", DnsProfile.CreateCustom(null, 0, "sadece-metin", 53).Validate() is not null);
        Check("Bos profil sorunsuz", DnsProfile.CreateCustom(null, 0, null, 0).Validate() is null);
    }

    private static void NativeProfiles()
    {
        Check("Varsayilan profil", NativeProfile.FromId("default").Id == "default");
        Check("Bilinmeyen -> varsayilan", NativeProfile.FromId("yok").Id == "default");

        var def = NativeProfile.Default.Build();
        Check("Varsayilan: sahte+TTL", def is { FakePacket: true, FakeTtl: true, BlockQuic: true });

        var cks = NativeProfile.WrongChecksum.Build();
        Check("Checksum profili", cks is { FakeWrongChecksum: true, FakeTtl: false });

        var split = NativeProfile.SplitOnly.Build();
        Check("Bolme profili sahtesiz", split is { FakePacket: false, SplitTls: true });
    }

    private static void VersionParsing()
    {
        Check("v-onekli tag", UpdateService.TryParseTag("v2.1.0", out var a) && a == new Version(2, 1, 0));
        Check("duz tag", UpdateService.TryParseTag("3.0.0", out var b) && b == new Version(3, 0, 0));
        Check("bozuk tag red", !UpdateService.TryParseTag("final", out _));
        Check("kisa tag", UpdateService.TryParseTag("2.0", out var c) && c == new Version(2, 0, 0));
    }

    // -------------------------------------------------- yardimcilar

    private static void Check(string name, bool ok, string? detail = null)
    {
        Log.AppendLine(ok ? $"[GECTI] {name}" : $"[KALDI] {name}  {detail}");
        if (!ok) _failed++;
    }

    /// <summary>
    /// SNI'si bilinen bir TLS ClientHello iceren sahte IPv4/IPv6 + TCP paketi kurar.
    /// Doner: (paket, uzunluk, SNI'nin payload'a gore ofseti, ana bilgisayar adi).
    /// </summary>
    private static (byte[] packet, int len, int sniStart, string host) BuildTlsPacket(bool v6, string host)
    {
        var hostBytes = Encoding.ASCII.GetBytes(host);

        // --- TLS ClientHello payload'u kur ---
        var body = new List<byte>();
        // client_version + random(32)
        body.Add(0x03); body.Add(0x03);
        body.AddRange(new byte[32]);
        // session_id
        body.Add(0x00);
        // cipher_suites (1 suite)
        body.Add(0x00); body.Add(0x02); body.Add(0x13); body.Add(0x01);
        // compression
        body.Add(0x01); body.Add(0x00);

        // extensions: server_name
        var sni = new List<byte>();
        // ServerNameList: list_len(2), name_type(1), name_len(2), name
        var nameLen = hostBytes.Length;
        var listLen = 1 + 2 + nameLen;
        sni.Add((byte)(listLen >> 8)); sni.Add((byte)listLen);
        sni.Add(0x00);                                   // host_name
        sni.Add((byte)(nameLen >> 8)); sni.Add((byte)nameLen);
        sni.AddRange(hostBytes);

        var ext = new List<byte>();
        ext.Add(0x00); ext.Add(0x00);                    // extension type = server_name
        ext.Add((byte)(sni.Count >> 8)); ext.Add((byte)sni.Count);
        ext.AddRange(sni);

        body.Add((byte)(ext.Count >> 8)); body.Add((byte)ext.Count); // extensions_len
        body.AddRange(ext);

        // handshake: type(1) + len(3) + body
        var hs = new List<byte> { 0x01 };
        hs.Add((byte)(body.Count >> 16)); hs.Add((byte)(body.Count >> 8)); hs.Add((byte)body.Count);
        hs.AddRange(body);

        // TLS record: type(1)=0x16, version(2), len(2), handshake
        var rec = new List<byte> { 0x16, 0x03, 0x01 };
        rec.Add((byte)(hs.Count >> 8)); rec.Add((byte)hs.Count);
        rec.AddRange(hs);

        var payload = rec.ToArray();

        // SNI'nin payload icindeki ofseti: rec(5) + hs basligi(4) + body'de host'a kadar.
        // body icinde host, ext blogunun sonundadir; ofseti tam hesaplayalim:
        var recHeader = 5;
        var hsHeader = 4;
        var bodyBeforeExt = 2 + 32 + 1 + (2 + 2) + (1 + 1) + 2; // ver+rand+sid+cipher+comp+extlen
        var extHeader = 4;      // ext type + ext len
        var sniHeader = 2 + 1 + 2; // list_len + name_type + name_len
        var sniStart = recHeader + hsHeader + bodyBeforeExt + extHeader + sniHeader;

        // --- IP + TCP basliklari ---
        var ipHeader = v6 ? 40 : 20;
        const int tcpHeader = 20;
        var total = ipHeader + tcpHeader + payload.Length;
        var packet = new byte[total];

        if (v6)
        {
            packet[0] = 0x60;                                    // version 6
            BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(4), (ushort)(tcpHeader + payload.Length));
            packet[6] = 6;                                       // next header = TCP
            packet[7] = 64;                                      // hop limit
            packet[8] = 0x20; packet[9] = 0x01;                  // src ::/basit
            packet[24] = 0x20; packet[25] = 0x02;                // dst
        }
        else
        {
            packet[0] = 0x45;                                    // v4, IHL=5
            BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(2), (ushort)total);
            packet[8] = 64;                                      // TTL
            packet[9] = 6;                                       // protocol TCP
            packet[12] = 192; packet[13] = 168; packet[14] = 1; packet[15] = 10; // src
            packet[16] = 93; packet[17] = 184; packet[18] = 216; packet[19] = 34; // dst
        }

        var l4 = ipHeader;
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(l4), 51000);       // src port
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(l4 + 2), 443);     // dst port
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(l4 + 4), 1000);    // seq
        packet[l4 + 12] = 0x50;                                                // data offset = 5 (20 bayt)
        packet[l4 + 13] = 0x18;                                                // PSH+ACK

        Array.Copy(payload, 0, packet, ipHeader + tcpHeader, payload.Length);

        return (packet, total, sniStart, host);
    }
}
