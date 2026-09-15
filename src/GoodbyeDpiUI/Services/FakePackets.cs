using System.Text;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Sahte (fake) paketlerin icerigi.
///
/// Teknigin ozu: DPI once ENGELSIZ bir siteye (www.w3.org) giden gecerli gorunumlu
/// bir istek gorur, akisi o siteye ait sayar ve gercek istegi incelemeyi birakir.
/// Sahte paket dusuk TTL / bozuk saglama / gecmis SEQ ile gonderildigi icin sunucuya
/// ulasmaz ya da sunucu tarafindan atilir.
///
/// ONEMLI: sahte paket asla gercek istegin kopyasi olmamali. Kopya, engelli SNI'yi
/// (orn. discord.com) DPI'a bir kez daha gosterir ve baglanti tam da bu yuzden
/// sifirlanir - onceki surumdeki Discord/Roblox hatasinin sebebi buydu.
/// GoodbyeDPI de ayni yaklasimi (fakepackets.c, www.w3.org) kullanir.
/// </summary>
internal static class FakePackets
{
    public const string FakeHost = "www.w3.org";

    /// <summary>Sahte HTTP istegi (port 80).</summary>
    public static readonly byte[] HttpRequest = Encoding.ASCII.GetBytes(
        "GET / HTTP/1.1\r\n" +
        "Host: " + FakeHost + "\r\n" +
        "User-Agent: curl/7.65.3\r\n" +
        "Accept: */*\r\n" +
        "Accept-Encoding: deflate, gzip, br\r\n\r\n");

    /// <summary>Sahte TLS ClientHello (port 443), SNI = www.w3.org, 517 bayt.</summary>
    public static readonly byte[] TlsClientHello = BuildClientHello(FakeHost, 517);

    /// <summary>
    /// Tarayici ClientHello'suna benzeyen, DPI ayristiricilarinin sorunsuz okuyacagi
    /// iyi bicimli bir TLS 1.2/1.3 ClientHello kurar. Rastgele alanlar sabittir
    /// (deterministik); DPI icin anlamli olan yalnizca yapi ve SNI.
    /// </summary>
    internal static byte[] BuildClientHello(string host, int targetRecordLength)
    {
        var hostBytes = Encoding.ASCII.GetBytes(host);

        var ext = new List<byte>();

        // server_name
        var sni = new List<byte>();
        U16(sni, hostBytes.Length + 3);          // server_name_list uzunlugu
        sni.Add(0x00);                           // host_name
        U16(sni, hostBytes.Length);
        sni.AddRange(hostBytes);
        Extension(ext, 0x0000, sni);

        Extension(ext, 0x0017, []);              // extended_master_secret
        Extension(ext, 0xFF01, [0x00]);          // renegotiation_info
        Extension(ext, 0x000A, [0x00, 0x06, 0x00, 0x1D, 0x00, 0x17, 0x00, 0x18]); // supported_groups
        Extension(ext, 0x000B, [0x01, 0x00]);    // ec_point_formats
        Extension(ext, 0x0023, []);              // session_ticket
        Extension(ext, 0x0010,                   // ALPN: h2, http/1.1
        [
            0x00, 0x0C, 0x02, (byte)'h', (byte)'2',
            0x08, (byte)'h', (byte)'t', (byte)'t', (byte)'p', (byte)'/', (byte)'1', (byte)'.', (byte)'1',
        ]);
        Extension(ext, 0x0005, [0x01, 0x00, 0x00, 0x00, 0x00]); // status_request
        Extension(ext, 0x000D,                   // signature_algorithms
        [
            0x00, 0x10, 0x04, 0x03, 0x08, 0x04, 0x04, 0x01, 0x05, 0x03,
            0x08, 0x05, 0x05, 0x01, 0x08, 0x06, 0x06, 0x01,
        ]);
        Extension(ext, 0x002B, [0x04, 0x03, 0x04, 0x03, 0x03]); // supported_versions: 1.3, 1.2
        Extension(ext, 0x002D, [0x01, 0x01]);    // psk_key_exchange_modes

        var keyShare = new List<byte>();
        U16(keyShare, 2 + 2 + 32);
        U16(keyShare, 0x001D);                   // x25519
        U16(keyShare, 32);
        keyShare.AddRange(Pattern(32, 0x5A));
        Extension(ext, 0x0033, keyShare);        // key_share

        var body = new List<byte> { 0x03, 0x03 }; // legacy_version = TLS 1.2
        body.AddRange(Pattern(32, 0x3C));         // random
        body.Add(32);
        body.AddRange(Pattern(32, 0x91));         // legacy_session_id

        byte[] suites =
        [
            0x13, 0x01, 0x13, 0x02, 0x13, 0x03, 0xC0, 0x2B, 0xC0, 0x2F, 0xC0, 0x2C,
            0xC0, 0x30, 0xCC, 0xA9, 0xCC, 0xA8, 0xC0, 0x13, 0xC0, 0x14, 0x00, 0x9C,
            0x00, 0x9D, 0x00, 0x2F, 0x00, 0x35,
        ];
        U16(body, suites.Length);
        body.AddRange(suites);
        body.Add(0x01);
        body.Add(0x00);                           // compression: null

        // padding uzantisi (RFC 7685) ile kaydi hedef uzunluga tamamla.
        const int fixedOverhead = 5 + 4 + 2 + 4; // kayit + handshake basligi + ext uzunlugu + padding basligi
        var padLen = targetRecordLength - fixedOverhead - body.Count - ext.Count;
        if (padLen >= 0)
            Extension(ext, 0x0015, new byte[padLen]);

        U16(body, ext.Count);
        body.AddRange(ext);

        var handshake = new List<byte> { 0x01 };  // ClientHello
        handshake.Add((byte)(body.Count >> 16));
        handshake.Add((byte)(body.Count >> 8));
        handshake.Add((byte)body.Count);
        handshake.AddRange(body);

        var record = new List<byte> { 0x16, 0x03, 0x01 };
        U16(record, handshake.Count);
        record.AddRange(handshake);

        return record.ToArray();
    }

    private static void Extension(List<byte> dst, int type, IReadOnlyCollection<byte> data)
    {
        U16(dst, type);
        U16(dst, data.Count);
        dst.AddRange(data);
    }

    private static void U16(List<byte> dst, int value)
    {
        dst.Add((byte)(value >> 8));
        dst.Add((byte)value);
    }

    private static byte[] Pattern(int count, byte seed)
    {
        var b = new byte[count];
        for (var i = 0; i < count; i++) b[i] = (byte)(seed * (i + 7) ^ (i * 31));
        return b;
    }
}
