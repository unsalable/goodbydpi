using System.Buffers.Binary;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using GoodbyeDpiUI.Models;

namespace GoodbyeDpiUI.Services;

/// <summary>Yakalanan bir paket icin karar.</summary>
internal enum PacketVerdict
{
    /// <summary>Paketi oldugu gibi gecir.</summary>
    Pass,

    /// <summary>Paketi dusur (gonderme).</summary>
    Drop,

    /// <summary>Paketin yerine cikti listesindekileri sirayla gonder.</summary>
    Replace,
}

/// <summary>Gonderilecek bir paket. <see cref="Recalc"/> ise saglamalar yeniden hesaplanir.</summary>
internal readonly record struct OutPacket(byte[] Data, int Length, bool Recalc, bool CorruptTcpChecksum);

/// <summary>
/// Kendi DPI motorumuzun paket mantigi. WinDivert'e hic dokunmaz: gelen paket
/// icin karar verir ve gonderilecek paketleri uretir. Boylece surucu/yonetici
/// gerektirmeden <see cref="SelfTest"/> ile uctan uca dogrulanabilir.
///
/// GoodbyeDPI "-5 --set-ttl 5" davranisinin karsiligi:
///  1. ClientHello / HTTP isteginden once engelsiz siteye (www.w3.org) ait sahte
///     istek gonderilir (dusuk TTL ve/veya bozuk saglama / gecmis SEQ / MD5 ile).
///  2. Gercek istek 2. bayttan (ve istege bagli SNI adinin ortasindan) TCP
///     parcalarina bolunur, parcalar ters sirada (istege bagli ortusmeyle) gonderilir.
///  3. QUIC baslangic paketleri dusurulur, DNS sorgulari secilen sunucuya yonlendirilir.
///  4. Discord ses (IP Discovery) ve STUN paketlerinden once sahte UDP paketleri gider.
/// </summary>
internal sealed class PacketProcessor
{
    private const int ProtocolTcp = 6;
    private const int ProtocolUdp = 17;

    // GoodbyeDPI --auto-ttl varsayilanlari (a1-a2-max) ve --min-ttl.
    private const int AutoTtl1 = 1;
    private const int AutoTtl2 = 4;
    private const int AutoTtlMax = 10;
    private const int MinHops = 3;

    private const int TableLimit = 4096;
    private const long EntryLifetimeMs = 120_000;

    /// <summary>
    /// Buyuk ClientHello'nun devam paketi olabilecek en buyuk TCP payload'u. Toplu yukleme
    /// (MSS boyutunda ~1440-1460 baytlik) paketleri bunun ustunde kalir ve hic yakalanmaz.
    /// </summary>
    internal const int MaxContinuationPayload = 1200;

    private const int PendingLimit = 256;
    private const long PendingLifetimeMs = 3_000;
    private const int MaxTlsRecord = 16_384 + 5;

    /// <summary>TCP MD5 imzasi secenegi: NOP, NOP, tur 19, uzunluk 18, 16 bayt ozet.</summary>
    internal const int Md5OptionLength = 20;

    private const uint DiscordDiscoveryHead = 0x00010046; // tur 1 (istek), uzunluk 70
    private const uint StunMagicCookie = 0x2112A442;

    private readonly NativeDpiConfig _cfg;
    private readonly DnsProfile _dns;
    private readonly byte[]? _resolverV4;
    private readonly byte[]? _resolverV6;

    /// <summary>Tani sayaclari (yalnizca motor is parcacigi yazar; okuma yaklasiktir).</summary>
    public EngineStats Stats { get; } = new();

    private readonly Dictionary<FlowKey, TtlRecord> _ttl = new();
    private readonly Dictionary<DnsKey, DnsMapping> _dnsMap = new();
    private readonly Dictionary<FlowKey, PendingHello> _pending = new();
    private readonly List<int> _points = new(4);

    public PacketProcessor(NativeDpiConfig cfg, DnsProfile dns)
    {
        _cfg = cfg.Clone();
        _cfg.FakeRepeats = Math.Clamp(_cfg.FakeRepeats, 1, 10);
        _cfg.VoiceFakeRepeats = Math.Clamp(_cfg.VoiceFakeRepeats, 1, 20);
        _cfg.SeqOverlap = Math.Clamp(_cfg.SeqOverlap, 0, 1000);
        _dns = dns;

        if (dns.IsActive)
        {
            if (TryParse(dns.V4Addr, AddressFamily.InterNetwork, out var v4)) _resolverV4 = v4;
            if (TryParse(dns.V6Addr, AddressFamily.InterNetworkV6, out var v6)) _resolverV6 = v6;
        }
    }

    /// <summary>Sahte paketler TTL ile mi korunuyor (otomatik TTL icin SYN-ACK izlemek gerekir mi)?</summary>
    private bool UsesTtlFake => _cfg.FakePacket && (_cfg.FakeTtl || !_cfg.HasFakeProtection);

    /// <summary>Buyuk ClientHello'nun ikinci paketi de SNI ortasindan bolunecek mi?</summary>
    private bool TracksContinuation => _cfg.SplitTls && _cfg.SplitSni;

    // ================================================================ filtre

    private const string NoLocalV4 =
        "(ip.DstAddr < 127.0.0.1 or ip.DstAddr > 127.255.255.255) and " +
        "(ip.DstAddr < 10.0.0.0 or ip.DstAddr > 10.255.255.255) and " +
        "(ip.DstAddr < 192.168.0.0 or ip.DstAddr > 192.168.255.255) and " +
        "(ip.DstAddr < 172.16.0.0 or ip.DstAddr > 172.31.255.255) and " +
        "(ip.DstAddr < 169.254.0.0 or ip.DstAddr > 169.254.255.255)";

    private const string NoLocalV6 =
        "(ipv6.DstAddr > ::1) and " +
        "(ipv6.DstAddr < 2001::0 or ipv6.DstAddr > 2001:1::0) and " +
        "(ipv6.DstAddr < fc00::0 or ipv6.DstAddr > fe00::0) and " +
        "(ipv6.DstAddr < fe80::0 or ipv6.DstAddr > fec0::0) and " +
        "(ipv6.DstAddr < ff00::0 or ipv6.DstAddr > ffff::0)";

    private const string NoLocal = "((ip and " + NoLocalV4 + ") or (ipv6 and " + NoLocalV6 + "))";

    /// <summary>
    /// Yalnizca gereken paketleri yakalayan WinDivert filtresi. Surekli veri akisi
    /// (indirme / MSS boyutunda yukleme) hic kullanici moduna cikmaz: yalnizca ClientHello,
    /// kucuk TLS devam paketleri, HTTP istek basi, SYN-ACK, QUIC baslangici, Discord ses /
    /// STUN el sikismasi ve DNS paketleri yakalanir.
    /// </summary>
    public string BuildFilter()
    {
        // Yerel aga gitmeyen cikis paketleri: tek bir NoLocal kosulu altinda toplanir
        // (WinDivert filtre boyutu sinirli; her yakalama icin tekrarlamayalim).
        var remote = new List<string>();

        if (_cfg.FakePacket || _cfg.SplitTls)
        {
            remote.Add(
                "(tcp and tcp.DstPort == 443 and tcp.PayloadLength > 5 and " +
                "tcp.Payload[0] == 0x16 and tcp.Payload[1] == 0x03 and tcp.Payload[5] == 0x01)");
        }

        if (TracksContinuation)
        {
            remote.Add(
                "(tcp and tcp.DstPort == 443 and tcp.PayloadLength > 0 and " +
                $"tcp.PayloadLength <= {MaxContinuationPayload})");
        }

        if (_cfg.FragmentHttp && (_cfg.FakePacket || _cfg.SplitTls))
        {
            remote.Add(
                "(tcp and tcp.DstPort == 80 and tcp.PayloadLength > 4 and (" +
                "tcp.Payload32[0] == 0x47455420 or tcp.Payload32[0] == 0x504F5354 or " +  // "GET " "POST"
                "tcp.Payload32[0] == 0x48454144 or tcp.Payload32[0] == 0x50555420 or " +  // "HEAD" "PUT "
                "tcp.Payload32[0] == 0x4F505449 or tcp.Payload32[0] == 0x44454C45 or " +  // "OPTI" "DELE"
                "tcp.Payload32[0] == 0x50415443 or tcp.Payload32[0] == 0x434F4E4E))");    // "PATC" "CONN"
        }

        if (_cfg.BlockQuic)
            remote.Add("(udp and udp.DstPort == 443 and udp.PayloadLength >= 1200 and udp.Payload[0] >= 0xC0)");

        if (_cfg.VoiceFake)
        {
            // Payload32[i] = i. 32 bitlik kelime (bayt ofseti 4*i).
            remote.Add("(udp and udp.PayloadLength == 74 and udp.Payload32[0] == 0x00010046 and udp.Payload32[2] == 0)");
            remote.Add("(udp and udp.PayloadLength >= 20 and udp.Payload32[1] == 0x2112A442 and udp.Payload[0] < 0x40)");
        }

        var clauses = new List<string>();
        if (remote.Count > 0)
            clauses.Add("(outbound and " + NoLocal + " and (" + string.Join(" or ", remote) + "))");

        if (UsesTtlFake && _cfg.AutoTtl)
            clauses.Add("(inbound and tcp and tcp.Syn and tcp.Ack and (tcp.SrcPort == 443 or tcp.SrcPort == 80))");

        if (_resolverV4 is not null)
        {
            clauses.Add("(outbound and ip and udp and udp.DstPort == 53 and udp.PayloadLength >= 12)");
            clauses.Add($"(inbound and ip and udp and udp.SrcPort == {Port(_dns.V4Port)} and ip.SrcAddr == {_dns.V4Addr})");
        }

        if (_resolverV6 is not null)
        {
            clauses.Add("(outbound and ipv6 and udp and udp.DstPort == 53 and udp.PayloadLength >= 12)");
            clauses.Add($"(inbound and ipv6 and udp and udp.SrcPort == {Port(_dns.V6Port)} and ipv6.SrcAddr == {_dns.V6Addr})");
        }

        // Hicbir teknik secili degilse filtre hicbir seyi yakalamasin.
        if (clauses.Count == 0) return "false";

        return "!impostor and !loopback and (" + string.Join(" or ", clauses) + ")";
    }

    private static string Port(int port) =>
        (port is > 0 and <= 65535 ? port : 53).ToString(CultureInfo.InvariantCulture);

    // ================================================================ karar

    /// <summary>
    /// Tek bir paketi degerlendirir. <see cref="PacketVerdict.Replace"/> donerse
    /// <paramref name="output"/> icindeki paketler sirayla gonderilmelidir.
    /// </summary>
    public PacketVerdict Process(byte[] packet, int len, bool outbound, List<OutPacket> output, long nowMs)
    {
        output.Clear();

        var ip = IpPacket.Parse(packet, len);
        if (!ip.Valid) return PacketVerdict.Pass;

        if (ip.Protocol == ProtocolTcp)
        {
            if (!outbound)
            {
                if (ip.IsSynAck) RecordServerTtl(ip, nowMs);
                return PacketVerdict.Pass;
            }

            if (ip.DstPort == 443)
            {
                if (TlsParser.IsClientHello(packet, ip.PayloadOffset, ip.PayloadLength))
                    return HandleRequest(packet, len, ip, isTls: true, output, nowMs);

                if (ip.PayloadLength > 0 && _pending.Count > 0)
                    return HandleContinuation(packet, ip, output, nowMs);

                return PacketVerdict.Pass;
            }

            if (ip.DstPort == 80 && _cfg.FragmentHttp && LooksLikeHttp(packet, ip))
                return HandleRequest(packet, len, ip, isTls: false, output, nowMs);

            return PacketVerdict.Pass;
        }

        if (ip.Protocol == ProtocolUdp)
        {
            if (outbound)
            {
                if (ip.DstPort == 443 && _cfg.BlockQuic && IsQuicInitial(packet, ip))
                {
                    Stats.QuicDropped++;
                    return PacketVerdict.Drop;
                }

                if (ip.DstPort == 53 && RedirectDnsOut(packet, ip, nowMs))
                {
                    Stats.DnsRedirected++;
                    output.Add(new OutPacket(packet, len, Recalc: true, CorruptTcpChecksum: false));
                    return PacketVerdict.Replace;
                }

                if (_cfg.VoiceFake && ip.DstPort != 53)
                {
                    if (IsDiscordDiscovery(packet, ip))
                    {
                        Stats.VoiceDiscoveries++;
                        return HandleVoice(packet, len, ip, output);
                    }

                    if (IsStunMessage(packet, ip))
                    {
                        Stats.StunMessages++;
                        return HandleVoice(packet, len, ip, output);
                    }
                }
            }
            else if (RewriteDnsIn(packet, ip))
            {
                Stats.DnsAnswered++;
                output.Add(new OutPacket(packet, len, Recalc: true, CorruptTcpChecksum: false));
                return PacketVerdict.Replace;
            }
        }

        return PacketVerdict.Pass;
    }

    // ================================================== TLS / HTTP istegi

    private PacketVerdict HandleRequest(byte[] p, int len, in IpPacket ip, bool isTls, List<OutPacket> output, long nowMs)
    {
        if (isTls)
        {
            Stats.ClientHellos++;
            // Buyuk ClientHello'larda SNI sonraki TCP parcasinda kalabilir (tani icin sayilir).
            if (!TlsParser.TryFindSni(p, ip.PayloadOffset, ip.PayloadLength, out _, out _)) Stats.HellosWithoutSni++;
        }
        else
        {
            Stats.HttpRequests++;
        }

        // 1) Sahte istek(ler): gercekten ONCE gider, DPI'i yanlis siteye kilitler.
        if (_cfg.FakePacket)
            AddFakes(p, ip, FakePayloadFor(isTls), output, nowMs);

        Stats.FakesSent += output.Count;

        // 2) Gercek istek: bolme noktalarina gore TCP parcalarina ayrilir.
        CollectSplitPoints(p, ip, isTls);
        if (isTls) TrackContinuation(p, ip, nowMs);

        if (_points.Count > 0)
        {
            AddSegments(p, ip, output);
            return PacketVerdict.Replace;
        }

        if (output.Count == 0) return PacketVerdict.Pass;

        // Sahte paket gitti ama bolme yok: orijinali degistirmeden arkasindan gonder.
        output.Add(new OutPacket(p, len, Recalc: false, CorruptTcpChecksum: false));
        return PacketVerdict.Replace;
    }

    private byte[] FakePayloadFor(bool isTls) => _cfg.FakePayload switch
    {
        FakePayloadKind.Zeros => FakePackets.Zeros,
        _ => isTls ? FakePackets.TlsClientHello : FakePackets.HttpRequest,
    };

    private static bool LooksLikeHttp(byte[] p, in IpPacket ip)
    {
        if (ip.PayloadLength < 5) return false;
        var word = BinaryPrimitives.ReadUInt32BigEndian(p.AsSpan(ip.PayloadOffset));
        return word is 0x47455420 or 0x504F5354 or 0x48454144 or 0x50555420
                    or 0x4F505449 or 0x44454C45 or 0x50415443 or 0x434F4E4E;
    }

    // ------------------------------------------------------ sahte paket

    private void AddFakes(byte[] p, in IpPacket ip, byte[] fakePayload, List<OutPacket> output, long nowMs)
    {
        // Koruma secilmemisse sahte istek sunucuya ulasip baglantiyi bozar: TTL'e dus.
        if (_cfg.FakeTtl || !_cfg.HasFakeProtection)
        {
            var ttl = ResolveFakeTtl(ip, nowMs);
            if (ttl > 0)
                AddFake(p, ip, fakePayload, ttl, wrongSeq: false, corruptChecksum: false, _cfg.FakeMd5Sig, output);
        }

        // TTL'siz koruma (yanlis saglama / SEQ / yalniz MD5): TTL degismeden ayri bir sahte.
        if (_cfg.FakeWrongChecksum || _cfg.FakeWrongSeq || (_cfg.FakeMd5Sig && !_cfg.FakeTtl))
            AddFake(p, ip, fakePayload, ttl: 0, _cfg.FakeWrongSeq, _cfg.FakeWrongChecksum, _cfg.FakeMd5Sig, output);
    }

    /// <summary>Tek bir sahte istegi (istenirse iki parcaya bolunmus ve tekrarli) ekler.</summary>
    private void AddFake(byte[] p, in IpPacket ip, byte[] payload, int ttl, bool wrongSeq, bool corruptChecksum,
        bool md5sig, List<OutPacket> output)
    {
        OutPacket first, second = default;
        var split = _cfg.SplitFake && payload.Length > 2;

        if (split)
        {
            first = BuildFake(p, ip, payload.AsSpan(0, 2), 0, ttl, wrongSeq, corruptChecksum, md5sig);
            second = BuildFake(p, ip, payload.AsSpan(2), 2, ttl, wrongSeq, corruptChecksum, md5sig);
        }
        else
        {
            first = BuildFake(p, ip, payload, 0, ttl, wrongSeq, corruptChecksum, md5sig);
        }

        // Ayni tampon tekrar gonderilebilir: saglama her gonderimde yeniden hesaplanip bozulur.
        for (var i = 0; i < _cfg.FakeRepeats; i++)
        {
            output.Add(first);
            if (split) output.Add(second);
        }
    }

    /// <summary>
    /// Orijinal IP+TCP basliklarini koruyup payload'u sahte icerikle degistirir.
    /// <paramref name="seqOffset"/> sahte icerigin istek icindeki konumudur (bolunmus sahte icin).
    /// </summary>
    internal static OutPacket BuildFake(byte[] p, in IpPacket ip, ReadOnlySpan<byte> fakePayload, int seqOffset,
        int ttl, bool wrongSeq, bool corruptChecksum, bool md5sig)
    {
        var baseHeader = ip.PayloadOffset;
        var optLen = md5sig && ip.L4HeaderLength + Md5OptionLength <= 60 ? Md5OptionLength : 0;
        var headerLen = baseHeader + optLen;
        var total = headerLen + fakePayload.Length;
        var buf = new byte[total];

        Array.Copy(p, buf, baseHeader);
        if (optLen > 0)
        {
            // NOP, NOP, MD5 imzasi (tur 19, uzunluk 18) + anlamsiz 16 baytlik ozet.
            buf[baseHeader] = 0x01;
            buf[baseHeader + 1] = 0x01;
            buf[baseHeader + 2] = 19;
            buf[baseHeader + 3] = 18;
            for (var i = 0; i < 16; i++) buf[baseHeader + 4 + i] = (byte)(0x5B ^ (i * 37));
            ip.SetTcpHeaderLength(buf, ip.L4HeaderLength + optLen);
        }

        fakePayload.CopyTo(buf.AsSpan(headerLen));
        ip.SetTotalLength(buf, total);

        if (ttl > 0) ip.SetTtl(buf, ttl);

        var seq = unchecked(ip.TcpSeq + (uint)seqOffset);
        if (wrongSeq)
        {
            // GoodbyeDPI ile ayni kayma: sunucu pencere disi sayip atar, DPI yine isler.
            seq = unchecked(seq - 10000);
            ip.SetTcpAck(buf, unchecked(ip.TcpAck - 66000));
        }

        ip.SetTcpSeq(buf, seq);
        return new OutPacket(buf, total, Recalc: true, CorruptTcpChecksum: corruptChecksum);
    }

    private int ResolveFakeTtl(in IpPacket ip, long nowMs)
    {
        var fixedTtl = Math.Clamp(_cfg.Ttl, 1, 255);
        if (!_cfg.AutoTtl) return fixedTtl;

        return _ttl.TryGetValue(FlowKey.Outbound(ip), out var rec) && nowMs - rec.Tick < EntryLifetimeMs
            ? ComputeAutoTtl(rec.Ttl, fixedTtl)
            : fixedTtl;
    }

    /// <summary>
    /// Sunucudan gelen SYN-ACK'in TTL'inden hop sayisini tahmin edip sahte paketin
    /// TTL'ini hesaplar (GoodbyeDPI tcp_get_auto_ttl). 0 = sunucu cok yakin, sahte
    /// paket gonderme. Baslangic TTL'i taninmazsa <paramref name="fallback"/> doner.
    /// </summary>
    internal static int ComputeAutoTtl(int observedTtl, int fallback)
    {
        int hops;
        if (observedTtl is > 98 and <= 128) hops = 128 - observedTtl;
        else if (observedTtl is > 34 and <= 64) hops = 64 - observedTtl;
        else if (observedTtl is > 192 and <= 255) hops = 255 - observedTtl;
        else return fallback;

        if (hops <= AutoTtl1 || hops < MinHops) return 0;

        var ttl = hops - AutoTtl2;
        if (ttl < AutoTtl2 && hops <= 9)
            ttl = hops - AutoTtl1 - (int)((AutoTtl2 - AutoTtl1) * (hops / 10.0));

        if (ttl > AutoTtlMax) ttl = AutoTtlMax;
        return ttl < 1 ? 0 : ttl;
    }

    private void RecordServerTtl(in IpPacket ip, long nowMs)
    {
        if (!UsesTtlFake || !_cfg.AutoTtl) return;

        if (_ttl.Count >= TableLimit) Prune(_ttl, nowMs, r => r.Tick);
        _ttl[FlowKey.Inbound(ip)] = new TtlRecord((byte)ip.Ttl, nowMs);
    }

    // ------------------------------------------------------- TCP bolme

    private void CollectSplitPoints(byte[] p, in IpPacket ip, bool isTls)
    {
        _points.Clear();
        if (!_cfg.SplitTls) return;

        var n = ip.PayloadLength;

        // Sabit konum. HTTP'de 0 verilmisse GoodbyeDPI'daki gibi 2. bayt.
        var pos = _cfg.SplitPosition > 0 ? _cfg.SplitPosition : isTls ? 0 : 2;
        if (pos > 0 && pos < n) _points.Add(pos);

        if (isTls && _cfg.SplitSni &&
            TlsParser.TryFindSni(p, ip.PayloadOffset, n, out var nameOffset, out var nameLength) &&
            nameLength >= 2)
        {
            var mid = nameOffset + nameLength / 2;
            if (mid > 0 && mid < n && !_points.Contains(mid)) _points.Add(mid);
        }

        _points.Sort();
    }

    private void AddSegments(byte[] p, in IpPacket ip, List<OutPacket> output)
    {
        var count = _points.Count + 1;
        var segments = new OutPacket[count];

        // Sira ortusmesi (zapret seqovl): duz bolmede ilk parcaya, ters sirada ikinci
        // parcaya (sondan bir onceki gonderilen). Ters sirada ilk bolme konumundan kucuk
        // olmali; yoksa sunucu ilk parcayi alinca sahte baytlari ezemez ve iptal edilir.
        var overlap = _cfg.SeqOverlap;
        var overlapIndex = -1;
        if (overlap > 0)
        {
            if (!_cfg.ReverseSplit) overlapIndex = 0;
            else if (count >= 2 && overlap < _points[0]) overlapIndex = 1;
        }

        var start = 0;
        for (var i = 0; i < count; i++)
        {
            var end = i < _points.Count ? _points[i] : ip.PayloadLength;
            var length = end - start;

            // Paket orijinalinden buyumesin (MTU asimi olmasin).
            var ovl = i == overlapIndex && length + overlap <= ip.PayloadLength ? overlap : 0;

            var seg = BuildSegment(p, ip, start, length, ovl);
            segments[i] = new OutPacket(seg, seg.Length, Recalc: true, CorruptTcpChecksum: false);
            start = end;
        }

        if (_cfg.ReverseSplit) Array.Reverse(segments);
        output.AddRange(segments);
    }

    /// <summary>
    /// Orijinal paketin baslik(lar)ini koruyarak payload'un [payloadStart, +count)
    /// dilimini iceren yeni bir IP+TCP paketi olusturur. SEQ, dilim ofseti kadar artar.
    /// <paramref name="overlap"/> &gt; 0 ise dilimin basina o kadar sifir bayt eklenir ve
    /// SEQ ayni miktarda geri cekilir (sira ortusmesi).
    /// </summary>
    internal static byte[] BuildSegment(byte[] src, in IpPacket ip, int payloadStart, int count, int overlap = 0)
    {
        var headerLen = ip.PayloadOffset;
        var seg = new byte[headerLen + overlap + count];

        Array.Copy(src, 0, seg, 0, headerLen);
        Array.Copy(src, ip.PayloadOffset + payloadStart, seg, headerLen + overlap, count);

        ip.SetTotalLength(seg, seg.Length);
        ip.SetTcpSeq(seg, unchecked(ip.TcpSeq + (uint)payloadStart - (uint)overlap));
        return seg;
    }

    // ------------------------------------------- cok paketli ClientHello (ML-KEM)

    /// <summary>
    /// ClientHello kaydi bu pakete sigmiyorsa (Chromium'un ~1.8 KB'lik ML-KEM istegi)
    /// devam paketini beklemek uzere akisi kaydeder. Paket bekletilmez: ilk parca hemen
    /// gider, devam paketi geldiginde SNI oradaysa o da ortasindan bolunur.
    /// </summary>
    private void TrackContinuation(byte[] p, in IpPacket ip, long nowMs)
    {
        if (!TracksContinuation) return;

        var key = FlowKey.Outbound(ip);
        var n = ip.PayloadLength;
        var recordEnd = 5 + BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(ip.PayloadOffset + 3));

        // Ad bu pakette tamamen gorunuyorsa devam paketinde bolunecek bir sey yok.
        var nameVisible = TlsParser.TryFindSni(p, ip.PayloadOffset, n, out var nameOffset, out var nameLength) &&
                          nameOffset + nameLength < n;

        if (recordEnd <= n || recordEnd > MaxTlsRecord || nameVisible)
        {
            _pending.Remove(key);
            return;
        }

        if (_pending.Count >= PendingLimit) Prune(_pending, nowMs, h => h.Tick, PendingLifetimeMs, PendingLimit);

        var data = new byte[recordEnd];
        Array.Copy(p, ip.PayloadOffset, data, 0, n);
        _pending[key] = new PendingHello(unchecked(ip.TcpSeq + (uint)n), data, n, nowMs);
    }

    private PacketVerdict HandleContinuation(byte[] p, in IpPacket ip, List<OutPacket> output, long nowMs)
    {
        var key = FlowKey.Outbound(ip);
        if (!_pending.TryGetValue(key, out var hello)) return PacketVerdict.Pass;

        if (ip.TcpSeq != hello.NextSeq || nowMs - hello.Tick > PendingLifetimeMs)
        {
            // Siradaki paket degil (yeniden gonderim / baska veri) ya da cok eski: birak.
            _pending.Remove(key);
            return PacketVerdict.Pass;
        }

        Stats.HelloContinuations++;

        var n = ip.PayloadLength;
        var headLen = hello.Length;
        var take = Math.Min(n, hello.Data.Length - headLen);
        Array.Copy(p, ip.PayloadOffset, hello.Data, headLen, take);
        hello.Length += take;
        hello.NextSeq = unchecked(hello.NextSeq + (uint)n);

        if (hello.Length >= hello.Data.Length) _pending.Remove(key);

        if (!TlsParser.TryFindSni(hello.Data, 0, hello.Length, out var nameOffset, out var nameLength) || nameLength < 2)
            return PacketVerdict.Pass;

        // Ad bu pakette baslamiyorsa ve ortasi da burada degilse (onceki pakette bolundu) dokunma.
        var cut = nameOffset + nameLength / 2 - headLen;
        if (cut <= 0 || cut >= n) return PacketVerdict.Pass;

        Stats.ContinuationSplits++;

        var a = BuildSegment(p, ip, 0, cut);
        var b = BuildSegment(p, ip, cut, n - cut);
        var first = new OutPacket(a, a.Length, Recalc: true, CorruptTcpChecksum: false);
        var second = new OutPacket(b, b.Length, Recalc: true, CorruptTcpChecksum: false);

        if (_cfg.ReverseSplit)
        {
            output.Add(second);
            output.Add(first);
        }
        else
        {
            output.Add(first);
            output.Add(second);
        }

        return PacketVerdict.Replace;
    }

    // ------------------------------------------------------------ QUIC

    /// <summary>QUIC uzun baslikli istemci paketi (Initial, en az 1200 bayt, surum != 0).</summary>
    private static bool IsQuicInitial(byte[] p, in IpPacket ip)
    {
        if (ip.PayloadLength < 1200) return false;
        var off = ip.PayloadOffset;
        return (p[off] & 0xC0) == 0xC0 && BinaryPrimitives.ReadUInt32BigEndian(p.AsSpan(off + 1)) != 0;
    }

    // ------------------------------------------------- Discord ses / STUN

    /// <summary>
    /// Discord ses sunucusuna giden IP Discovery istegi: 74 bayt, tur 0x0001, uzunluk 70,
    /// SSRC ve ardindan sifirlarla dolu adres alani.
    /// https://discord.com/developers/docs/topics/voice-connections#ip-discovery
    /// </summary>
    internal static bool IsDiscordDiscovery(byte[] p, in IpPacket ip)
    {
        if (ip.PayloadLength != 74) return false;
        var off = ip.PayloadOffset;
        return BinaryPrimitives.ReadUInt32BigEndian(p.AsSpan(off)) == DiscordDiscoveryHead &&
               p.AsSpan(off + 8, 64).IndexOfAnyExcept((byte)0) < 0;
    }

    /// <summary>STUN mesaji (RFC 5389): ilk iki bit 0, sihirli cerez 0x2112A442, uzunluk tutarli.</summary>
    internal static bool IsStunMessage(byte[] p, in IpPacket ip)
    {
        if (ip.PayloadLength < 20) return false;
        var off = ip.PayloadOffset;
        if (p[off] >= 0x40 || BinaryPrimitives.ReadUInt32BigEndian(p.AsSpan(off + 4)) != StunMagicCookie) return false;

        var messageLength = BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(off + 2));
        return messageLength % 4 == 0 && messageLength + 20 == ip.PayloadLength;
    }

    /// <summary>
    /// Ses el sikismasindan once sahte UDP paketleri gonderir (zapret "--dpi-desync=fake
    /// --dpi-desync-repeats=6"). DPI akisin ilk paketini taniyamaz ve incelemeyi birakir;
    /// sunucu ise gecersiz icerigi yok sayar. Gercek paket en son, degismeden gider.
    /// </summary>
    private PacketVerdict HandleVoice(byte[] p, int len, in IpPacket ip, List<OutPacket> output)
    {
        var fake = BuildUdpFake(p, ip, FakePackets.UdpZeros);
        for (var i = 0; i < _cfg.VoiceFakeRepeats; i++) output.Add(fake);

        Stats.VoiceFakesSent += _cfg.VoiceFakeRepeats;
        output.Add(new OutPacket(p, len, Recalc: false, CorruptTcpChecksum: false));
        return PacketVerdict.Replace;
    }

    internal static OutPacket BuildUdpFake(byte[] p, in IpPacket ip, ReadOnlySpan<byte> payload)
    {
        var headerLen = ip.PayloadOffset;
        var total = headerLen + payload.Length;
        var buf = new byte[total];

        Array.Copy(p, buf, headerLen);
        payload.CopyTo(buf.AsSpan(headerLen));
        ip.SetTotalLength(buf, total);
        ip.SetUdpLength(buf, total - ip.L4Offset);

        return new OutPacket(buf, total, Recalc: true, CorruptTcpChecksum: false);
    }

    // ------------------------------------------------------ DNS yonlendirme

    private bool RedirectDnsOut(byte[] p, in IpPacket ip, long nowMs)
    {
        var resolver = ip.IsV6 ? _resolverV6 : _resolverV4;
        if (resolver is null) return false;

        var off = ip.PayloadOffset;
        if (ip.PayloadLength < 12 || (p[off + 2] & 0x80) != 0) return false; // sorgu degil

        var port = (ushort)(ip.IsV6 ? _dns.V6Port : _dns.V4Port);
        if (ip.DstPort == port && AddressEquals(p, ip, source: false, resolver)) return false; // zaten dogru yere

        if (_dnsMap.Count >= TableLimit) Prune(_dnsMap, nowMs, m => m.Tick);

        var txid = BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(off));
        _dnsMap[new DnsKey(ip.IsV6, ip.SrcPort, txid)] = new DnsMapping(ip.GetDstAddrBytes(), ip.DstPort, nowMs);

        ip.SetDstAddr(p, resolver);
        ip.SetDstPort(p, port);
        return true;
    }

    private bool RewriteDnsIn(byte[] p, in IpPacket ip)
    {
        var resolver = ip.IsV6 ? _resolverV6 : _resolverV4;
        if (resolver is null) return false;

        var off = ip.PayloadOffset;
        if (ip.PayloadLength < 12 || (p[off + 2] & 0x80) == 0) return false; // yanit degil

        var port = (ushort)(ip.IsV6 ? _dns.V6Port : _dns.V4Port);
        if (ip.SrcPort != port || !AddressEquals(p, ip, source: true, resolver)) return false;

        // Yanit istemciye donuyor: hedef port = istemcinin efemeral portu.
        // Kayit silinmez: ayni sorgunun tekrar gonderimine gelen ikinci yanit da
        // dogru adrese yazilmali; eski kayitlar tablo dolunca temizlenir.
        var txid = BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(off));
        if (!_dnsMap.TryGetValue(new DnsKey(ip.IsV6, ip.DstPort, txid), out var map)) return false;

        ip.SetSrcAddr(p, map.OrigAddr);
        ip.SetSrcPort(p, map.OrigPort);
        return true;
    }

    private static bool AddressEquals(byte[] p, in IpPacket ip, bool source, byte[] addr) =>
        (source ? ip.GetSrcAddrBytes() : ip.GetDstAddrBytes()).AsSpan().SequenceEqual(addr);

    // ------------------------------------------------------------ yardimci

    private static void Prune<TKey, TValue>(Dictionary<TKey, TValue> table, long nowMs, Func<TValue, long> tick,
        long lifetimeMs = EntryLifetimeMs, int limit = TableLimit)
        where TKey : notnull
    {
        foreach (var (key, value) in table)
        {
            if (nowMs - tick(value) > lifetimeMs) table.Remove(key);
        }

        // Hepsi tazeyse (asiri yuk) tabloyu sifirla; bellek sinirsiz buyumesin.
        if (table.Count >= limit) table.Clear();
    }

    private static bool TryParse(string? text, AddressFamily family, out byte[] bytes)
    {
        bytes = [];
        if (string.IsNullOrWhiteSpace(text) || !IPAddress.TryParse(text, out var addr) || addr.AddressFamily != family)
            return false;

        bytes = addr.GetAddressBytes();
        return true;
    }

    internal sealed class EngineStats
    {
        public long ClientHellos;
        public long HellosWithoutSni;
        public long HelloContinuations;
        public long ContinuationSplits;
        public long HttpRequests;
        public long FakesSent;
        public long QuicDropped;
        public long VoiceDiscoveries;
        public long StunMessages;
        public long VoiceFakesSent;
        public long DnsRedirected;
        public long DnsAnswered;

        public override string ToString() =>
            $"ClientHello {ClientHellos} (SNI'siz {HellosWithoutSni}, devam {HelloContinuations}, devamda bolme {ContinuationSplits}), " +
            $"HTTP {HttpRequests}, sahte {FakesSent}, QUIC dusen {QuicDropped}, " +
            $"Discord ses {VoiceDiscoveries} / STUN {StunMessages} (sahte UDP {VoiceFakesSent}), " +
            $"DNS yonlenen {DnsRedirected} / donen {DnsAnswered}";
    }

    private readonly record struct FlowKey(ulong AddrHi, ulong AddrLo, ushort RemotePort, ushort LocalPort)
    {
        public static FlowKey Outbound(in IpPacket ip)
        {
            var (hi, lo) = ip.ReadAddr(source: false);
            return new FlowKey(hi, lo, ip.DstPort, ip.SrcPort);
        }

        public static FlowKey Inbound(in IpPacket ip)
        {
            var (hi, lo) = ip.ReadAddr(source: true);
            return new FlowKey(hi, lo, ip.SrcPort, ip.DstPort);
        }
    }

    private readonly record struct TtlRecord(byte Ttl, long Tick);

    private readonly record struct DnsKey(bool V6, ushort ClientPort, ushort TxId);

    private readonly record struct DnsMapping(byte[] OrigAddr, ushort OrigPort, long Tick);

    /// <summary>Devam paketi beklenen ClientHello: ilk paketlerin verisi ve beklenen SEQ.</summary>
    private sealed class PendingHello(uint nextSeq, byte[] data, int length, long tick)
    {
        public uint NextSeq = nextSeq;
        public readonly byte[] Data = data;
        public int Length = length;
        public readonly long Tick = tick;
    }
}
