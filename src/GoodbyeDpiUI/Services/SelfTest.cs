using System.Buffers.Binary;
using System.IO;
using System.Text;
using GoodbyeDpiUI.Models;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Yonetici gerektirmeyen, WinDivert surucusune dokunmayan ic dogrulama takimi.
/// "--selftest" argumaniyla calistirilir; paket cozumleme, TLS SNI bulma, motorun
/// paket kararlari (sahte paket, bolme, otomatik TTL, QUIC, DNS), filtre derleme,
/// DNS arguman uretimi ve surum kiyaslama gibi mantigi gercek kodla test eder.
/// Sonuclar bir dosyaya yazilir ve cikis kodu 0 (gecti) / 1 (kaldi) olur.
/// </summary>
internal static class SelfTest
{
    private static readonly StringBuilder Log = new();
    private static int _failed;
    private static int _passed;

    public static int Run(string? outputPath = null)
    {
        Section("TLS / IP cozumleme", () =>
        {
            TlsSniIpv4();
            TlsSniIpv6();
            IpMutators();
        });

        Section("Sahte paketler", FakePacketContent);
        Section("Motor: TLS (Varsayilan)", () => { EngineTlsDefault(v6: false); EngineTlsDefault(v6: true); });
        Section("Motor: otomatik TTL", EngineAutoTtl);
        Section("Motor: profiller", EngineProfiles);
        Section("Motor: HTTP", EngineHttp);
        Section("Motor: QUIC", EngineQuic);
        Section("Motor: DNS yonlendirme", () => { EngineDns(v6: false); EngineDns(v6: true); });
        Section("WinDivert: filtre + saglama", WinDivertNative);
        Section("DNS profilleri", () => { DnsArguments(); DnsValidation(); });
        Section("Native profiller", NativeProfiles);
        Section("Surum", VersionParsing);

        Log.Insert(0, _failed == 0
            ? $"TUM TESTLER GECTI ({_passed})\n\n"
            : $"{_failed} TEST KALDI, {_passed} GECTI\n\n");

        var path = outputPath ?? Path.Combine(Path.GetTempPath(), "goodbyedpi-selftest.txt");
        try { File.WriteAllText(path, Log.ToString()); } catch { /* onemsiz */ }

        return _failed == 0 ? 0 : 1;
    }

    private static void Section(string title, Action body)
    {
        Log.AppendLine($"== {title}");
        try
        {
            body();
        }
        catch (Exception ex)
        {
            Check($"{title}: istisna", false, ex.ToString());
        }

        Log.AppendLine();
    }

    // ================================================== TLS / IP cozumleme

    private static void TlsSniIpv4()
    {
        var (packet, len, sniStart, host) = BuildTlsPacket(v6: false, "example.com");
        var ip = IpPacket.Parse(packet, len);

        Check("v4 parse gecerli", ip.Valid);
        Check("v4 protokol TCP", ip.Protocol == 6);
        Check("v4 dst port 443", ip.DstPort == 443);
        Check("v4 payload ofseti 40", ip.PayloadOffset == 40);
        Check("v4 TTL okundu", ip.Ttl == 64, $"{ip.Ttl}");

        var sni = TlsParser.FindSniOffset(packet, ip.PayloadOffset, ip.PayloadLength);
        Check("v4 SNI ofseti bulundu", sni == sniStart, $"beklenen {sniStart}, bulunan {sni}");

        if (sni > 0)
        {
            var found = Encoding.ASCII.GetString(packet, ip.PayloadOffset + sni, host.Length);
            Check("v4 SNI adi dogru", found == host, $"'{found}'");
        }

        Check("v4 SNI uzunlugu",
            TlsParser.TryFindSni(packet, ip.PayloadOffset, ip.PayloadLength, out _, out var nameLen) && nameLen == host.Length,
            $"{nameLen}");
    }

    private static void TlsSniIpv6()
    {
        var (packet, len, sniStart, _) = BuildTlsPacket(v6: true, "cloudflare.com");
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

        // IP parcasi (MF) cozumlenmemeli: L4 basligi guvenilmez.
        var frag = (byte[])packet.Clone();
        frag[6] = 0x20;
        Check("IPv4 parcasi atlandi", !IpPacket.Parse(frag, len).Valid);
    }

    // ======================================================= sahte paketler

    private static void FakePacketContent()
    {
        var hello = FakePackets.TlsClientHello;
        Check("Sahte ClientHello 517 bayt", hello.Length == 517, $"{hello.Length}");
        Check("Sahte ClientHello tanindi", TlsParser.IsClientHello(hello, 0, hello.Length));
        Check("Kayit uzunlugu tutarli", BinaryPrimitives.ReadUInt16BigEndian(hello.AsSpan(3)) == hello.Length - 5);
        Check("Handshake uzunlugu tutarli", ((hello[6] << 16) | (hello[7] << 8) | hello[8]) == hello.Length - 9);

        var ok = TlsParser.TryFindSni(hello, 0, hello.Length, out var off, out var nameLen);
        var name = ok ? Encoding.ASCII.GetString(hello, off, nameLen) : "";
        Check("Sahte SNI = www.w3.org", name == FakePackets.FakeHost, $"'{name}'");

        var http = Encoding.ASCII.GetString(FakePackets.HttpRequest);
        Check("Sahte HTTP Host = www.w3.org", http.StartsWith("GET / HTTP/1.1\r\nHost: www.w3.org\r\n"));
    }

    // =========================================================== motor

    private const string BlockedHost = "discord.com";

    private static void EngineTlsDefault(bool v6)
    {
        var tag = v6 ? "v6" : "v4";
        var proc = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Off);

        // Gercekci boyutta (tek pakete sigan) ClientHello, SNI = discord.com.
        var hello = FakePackets.BuildClientHello(BlockedHost, 1200);
        var packet = BuildTcp(v6, 50000, 443, seq: 777_000, ack: 42, flags: 0x18, ttl: 128, hello);
        var original = (byte[])packet.Clone();

        var output = new List<OutPacket>();
        var verdict = proc.Process(packet, packet.Length, outbound: true, output, 1000);

        Check($"{tag} ClientHello: Replace", verdict == PacketVerdict.Replace, $"{verdict}");
        Check($"{tag} Orijinal tampon degismedi", packet.AsSpan().SequenceEqual(original));
        if (output.Count < 2) { Check($"{tag} Cikti sayisi", false, $"{output.Count}"); return; }

        // 1) Ilk giden: sahte paket, dusuk TTL, engelsiz SNI, ayni SEQ.
        var fake = output[0];
        var fip = IpPacket.Parse(fake.Data, fake.Length);
        var fakePayload = fake.Data.AsSpan(fip.PayloadOffset, fip.PayloadLength);

        Check($"{tag} Ilk paket sahte ClientHello", fakePayload.SequenceEqual(FakePackets.TlsClientHello));
        Check($"{tag} Sahte pakette engelli SNI YOK", !Contains(fake.Data, BlockedHost));
        Check($"{tag} Sahte TTL = 5 (SYN-ACK yok, sabit)", fip.Ttl == 5, $"{fip.Ttl}");
        Check($"{tag} Sahte SEQ ayni", fip.TcpSeq == 777_000);
        Check($"{tag} Sahte saglama bozulmuyor", fake is { Recalc: true, CorruptTcpChecksum: false });
        Check($"{tag} Sahte IP uzunlugu", IpLengthMatches(fake.Data, fake.Length, v6));

        // 2) Kalanlar: gercek istek parcalari. Ters sirada gelmeli ve birlesince orijinal olmali.
        var segments = output.Skip(1).ToList();
        Check($"{tag} 3 parca (2. bayt + SNI ortasi)", segments.Count == 3, $"{segments.Count}");

        var seqs = segments.Select(s => IpPacket.Parse(s.Data, s.Length).TcpSeq).ToList();
        Check($"{tag} Parcalar ters sirada", seqs.SequenceEqual(seqs.OrderByDescending(x => x)), string.Join(",", seqs));

        var rebuilt = Reassemble(segments, 777_000);
        Check($"{tag} Parcalar birlesince orijinal payload", rebuilt.AsSpan().SequenceEqual(hello));
        Check($"{tag} Hicbir parcada tam SNI yok", segments.All(s => !Contains(s.Data, BlockedHost)));
        Check($"{tag} Parca IP uzunluklari", segments.All(s => IpLengthMatches(s.Data, s.Length, v6)));
        Check($"{tag} Parca TTL korunur", segments.All(s => IpPacket.Parse(s.Data, s.Length).Ttl == 128));

        var smallest = segments.Select(s => IpPacket.Parse(s.Data, s.Length)).OrderBy(s => s.TcpSeq).First();
        Check($"{tag} Ilk parca 2 bayt", smallest.PayloadLength == 2, $"{smallest.PayloadLength}");

        // TLS uygulama verisi (0x17) ve ClientHello olmayan handshake dokunulmadan gecmeli.
        var appData = BuildTcp(v6, 50000, 443, 1, 1, 0x18, 128, [0x17, 0x03, 0x03, 0x00, 0x20, 0x01, 0x02, 0x03]);
        Check($"{tag} TLS uygulama verisi Pass", proc.Process(appData, appData.Length, true, output, 1000) == PacketVerdict.Pass);

        var keyExchange = BuildTcp(v6, 50000, 443, 1, 1, 0x18, 128, [0x16, 0x03, 0x03, 0x00, 0x46, 0x10, 0x00, 0x00, 0x42]);
        Check($"{tag} ClientKeyExchange Pass", proc.Process(keyExchange, keyExchange.Length, true, output, 1000) == PacketVerdict.Pass);

        // Buyuk ClientHello'nun devami (0x16 ile baslamayan ikinci parca) dokunulmaz.
        var continuation = BuildTcp(v6, 50000, 443, 2, 1, 0x18, 128, Enumerable.Repeat((byte)0x41, 300).ToArray());
        Check($"{tag} ClientHello devami Pass", proc.Process(continuation, continuation.Length, true, output, 1000) == PacketVerdict.Pass);
    }

    private static void EngineAutoTtl()
    {
        Check("AutoTTL 9 hop -> 5", PacketProcessor.ComputeAutoTtl(55, 5) == 5, $"{PacketProcessor.ComputeAutoTtl(55, 5)}");
        Check("AutoTTL 8 hop -> 4", PacketProcessor.ComputeAutoTtl(56, 5) == 4);
        Check("AutoTTL 7 hop -> 4", PacketProcessor.ComputeAutoTtl(121, 5) == 4, $"{PacketProcessor.ComputeAutoTtl(121, 5)}");
        Check("AutoTTL 4 hop -> 2", PacketProcessor.ComputeAutoTtl(60, 5) == 2, $"{PacketProcessor.ComputeAutoTtl(60, 5)}");
        Check("AutoTTL 2 hop -> gonderme", PacketProcessor.ComputeAutoTtl(62, 5) == 0);
        Check("AutoTTL 20 hop -> 10 (tavan)", PacketProcessor.ComputeAutoTtl(108, 5) == 10);
        Check("AutoTTL 255 tabanli", PacketProcessor.ComputeAutoTtl(243, 5) == 8, $"{PacketProcessor.ComputeAutoTtl(243, 5)}");
        Check("AutoTTL bilinmeyen -> sabit", PacketProcessor.ComputeAutoTtl(20, 5) == 5);

        foreach (var v6 in new[] { false, true })
        {
            var tag = v6 ? "v6" : "v4";

            // Uzak sunucu: SYN-ACK TTL 52 (12 hop) -> sahte TTL 8.
            var proc = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Off);
            var synAck = BuildTcp(v6, 443, 50001, 5000, 778_000, 0x12, 52, [], swapAddr: true);
            var output = new List<OutPacket>();
            Check($"{tag} SYN-ACK Pass", proc.Process(synAck, synAck.Length, outbound: false, output, 1000) == PacketVerdict.Pass);

            var hello = BuildTcp(v6, 50001, 443, 778_000, 5001, 0x18, 128, FakePackets.BuildClientHello(BlockedHost, 600));
            proc.Process(hello, hello.Length, true, output, 1050);
            var ttl = IpPacket.Parse(output[0].Data, output[0].Length).Ttl;
            Check($"{tag} 12 hop sunucu -> sahte TTL 8", ttl == 8, $"{ttl}");

            // Cok yakin sunucu (2 hop): sahte paket gonderilmemeli, sadece parcalar.
            var near = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Off);
            var nearSyn = BuildTcp(v6, 443, 50002, 1, 900, 0x12, 62, [], swapAddr: true);
            near.Process(nearSyn, nearSyn.Length, false, output, 1000);
            var nearHello = BuildTcp(v6, 50002, 443, 900, 2, 0x18, 128, FakePackets.BuildClientHello(BlockedHost, 600));
            near.Process(nearHello, nearHello.Length, true, output, 1010);
            Check($"{tag} Yakin sunucuda sahte paket yok",
                output.Count > 0 && output.All(o => !Contains(o.Data, FakePackets.FakeHost)), $"{output.Count}");

            // Farkli akisin SYN-ACK'i bu akisi etkilememeli -> sabit TTL.
            var other = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Off);
            var otherSyn = BuildTcp(v6, 443, 59999, 1, 1, 0x12, 62, [], swapAddr: true);
            other.Process(otherSyn, otherSyn.Length, false, output, 1000);
            var otherHello = BuildTcp(v6, 50003, 443, 1, 2, 0x18, 128, FakePackets.BuildClientHello(BlockedHost, 600));
            other.Process(otherHello, otherHello.Length, true, output, 1010);
            Check($"{tag} Baska akisin kaydi karismaz", IpPacket.Parse(output[0].Data, output[0].Length).Ttl == 5);
        }
    }

    private static void EngineProfiles()
    {
        var output = new List<OutPacket>();
        var hello = FakePackets.BuildClientHello(BlockedHost, 700);

        // Yanlis saglama (GoodbyeDPI -9 karsiligi)
        var cks = new PacketProcessor(NativeProfile.WrongChecksum.Build(), DnsProfile.Off);
        var p1 = BuildTcp(false, 50010, 443, 100_000, 90_000, 0x18, 128, hello);
        cks.Process(p1, p1.Length, true, output, 0);
        var f = IpPacket.Parse(output[0].Data, output[0].Length);
        Check("Checksum: tek sahte paket", output.Count(o => Contains(o.Data, FakePackets.FakeHost)) == 1);
        Check("Checksum: saglama bozulacak", output[0].CorruptTcpChecksum);
        Check("Checksum: TTL degismedi", f.Ttl == 128, $"{f.Ttl}");
        Check("Checksum: SEQ -10000", f.TcpSeq == 90_000, $"{f.TcpSeq}");
        Check("Checksum: ACK -66000", f.TcpAck == 24_000, $"{f.TcpAck}");

        // Sadece bolme
        var split = new PacketProcessor(NativeProfile.SplitOnly.Build(), DnsProfile.Off);
        var p2 = BuildTcp(false, 50011, 443, 5, 5, 0x18, 128, hello);
        split.Process(p2, p2.Length, true, output, 0);
        Check("Bolme: sahte yok", output.All(o => !Contains(o.Data, FakePackets.FakeHost)));
        Check("Bolme: 3 parca", output.Count == 3, $"{output.Count}");
        Check("Bolme: birlesince orijinal", Reassemble(output, 5).AsSpan().SequenceEqual(hello));

        // Korumasiz sahte paket istenirse TTL'e dusmeli (yoksa sunucuya ulasip bozar).
        var unsafeCfg = new NativeDpiConfig { FakeTtl = false, FakeWrongChecksum = false, FakeWrongSeq = false, SplitTls = false };
        var unsafeProc = new PacketProcessor(unsafeCfg, DnsProfile.Off);
        var p3 = BuildTcp(false, 50012, 443, 5, 5, 0x18, 128, hello);
        unsafeProc.Process(p3, p3.Length, true, output, 0);
        Check("Korumasiz: TTL'e duser", output.Count == 2 && IpPacket.Parse(output[0].Data, output[0].Length).Ttl == 5);
        Check("Korumasiz: orijinal degismeden arkasindan", output[1].Data == p3 && !output[1].Recalc);

        // Eski ayar dosyasi: splitPosition=0 -> yalnizca SNI ortasindan bol.
        var legacy = new PacketProcessor(new NativeDpiConfig { SplitPosition = 0 }, DnsProfile.Off);
        var p4 = BuildTcp(false, 50013, 443, 5, 5, 0x18, 128, hello);
        legacy.Process(p4, p4.Length, true, output, 0);
        Check("splitPosition=0: sahte + 2 parca", output.Count == 3, $"{output.Count}");

        // Hicbir teknik yok: filtre hicbir sey yakalamaz.
        var none = new PacketProcessor(
            new NativeDpiConfig { FakePacket = false, SplitTls = false, BlockQuic = false, FragmentHttp = false }, DnsProfile.Off);
        Check("Teknik yok: filtre 'false'", none.BuildFilter() == "false");
    }

    private static void EngineHttp()
    {
        var proc = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Off);
        var output = new List<OutPacket>();
        var req = Encoding.ASCII.GetBytes("GET / HTTP/1.1\r\nHost: " + BlockedHost + "\r\n\r\n");

        var p = BuildTcp(false, 50020, 80, 10, 10, 0x18, 128, req);
        var verdict = proc.Process(p, p.Length, true, output, 0);

        Check("HTTP: Replace", verdict == PacketVerdict.Replace);
        Check("HTTP: once sahte istek", output.Count > 0 && Contains(output[0].Data, "Host: www.w3.org"));
        Check("HTTP: birlesince orijinal", Reassemble(output.Skip(1).ToList(), 10).AsSpan().SequenceEqual(req));

        var noHttp = new PacketProcessor(new NativeDpiConfig { FragmentHttp = false }, DnsProfile.Off);
        Check("HTTP kapali: Pass", noHttp.Process(p, p.Length, true, output, 0) == PacketVerdict.Pass);
        Check("HTTP kapali: filtrede port 80 yok", !noHttp.BuildFilter().Contains("DstPort == 80"));
    }

    private static void EngineQuic()
    {
        var proc = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Off);
        var output = new List<OutPacket>();

        var initial = new byte[1250];
        initial[0] = 0xC3;
        BinaryPrimitives.WriteUInt32BigEndian(initial.AsSpan(1), 1);
        var pkt = BuildUdp(false, 50030, 443, initial);
        Check("QUIC Initial dusuruldu", proc.Process(pkt, pkt.Length, true, output, 0) == PacketVerdict.Drop);

        var shortHeader = new byte[1250];
        shortHeader[0] = 0x43;
        var sh = BuildUdp(false, 50030, 443, shortHeader);
        Check("QUIC kisa baslik gecer", proc.Process(sh, sh.Length, true, output, 0) == PacketVerdict.Pass);

        var small = BuildUdp(false, 50030, 443, [0xC3, 0, 0, 0, 1, 0, 0]);
        Check("Kucuk UDP/443 gecer", proc.Process(small, small.Length, true, output, 0) == PacketVerdict.Pass);

        var off = new PacketProcessor(new NativeDpiConfig { BlockQuic = false }, DnsProfile.Off);
        Check("QUIC engeli kapali: gecer", off.Process(pkt, pkt.Length, true, output, 0) == PacketVerdict.Pass);
    }

    private static void EngineDns(bool v6)
    {
        var tag = v6 ? "v6" : "v4";
        var proc = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Yandex);
        var output = new List<OutPacket>();

        var query = new byte[29];
        BinaryPrimitives.WriteUInt16BigEndian(query, 0xBEEF);        // txid
        query[2] = 0x01;                                             // RD, QR=0

        var q = BuildUdp(v6, 53001, 53, query, dstLast: 1);
        var origDst = IpPacket.Parse(q, q.Length).GetDstAddrBytes();
        var verdict = proc.Process(q, q.Length, outbound: true, output, 0);
        var qip = IpPacket.Parse(q, q.Length);

        var resolver = System.Net.IPAddress.Parse(v6 ? DnsProfile.Yandex.V6Addr! : DnsProfile.Yandex.V4Addr!).GetAddressBytes();
        Check($"{tag} DNS sorgusu yonlendirildi", verdict == PacketVerdict.Replace && output[0].Recalc);
        Check($"{tag} Hedef = Yandex", qip.GetDstAddrBytes().AsSpan().SequenceEqual(resolver));
        Check($"{tag} Hedef port = 1253", qip.DstPort == 1253, $"{qip.DstPort}");

        // Yanit: Yandex:1253 -> istemci:53001; kaynak eski DNS sunucusuna geri yazilmali.
        var answer = (byte[])query.Clone();
        answer[2] = 0x81;
        var resp = BuildUdp(v6, 1253, 53001, answer, srcOverride: resolver);
        Check($"{tag} Yanit geri yazildi", proc.Process(resp, resp.Length, outbound: false, output, 10) == PacketVerdict.Replace);
        var rip = IpPacket.Parse(resp, resp.Length);
        Check($"{tag} Kaynak = eski sunucu", rip.GetSrcAddrBytes().AsSpan().SequenceEqual(origDst));
        Check($"{tag} Kaynak port = 53", rip.SrcPort == 53);

        // Tekrar gonderime gelen ikinci yanit da geri yazilmali.
        var resp2 = BuildUdp(v6, 1253, 53001, answer, srcOverride: resolver);
        Check($"{tag} Ikinci yanit da geri yazildi", proc.Process(resp2, resp2.Length, false, output, 20) == PacketVerdict.Replace);

        // Bilinmeyen islem kimligi dokunulmaz.
        var stray = (byte[])answer.Clone();
        stray[0] = 0x12;
        var sp = BuildUdp(v6, 1253, 53001, stray, srcOverride: resolver);
        Check($"{tag} Bilinmeyen yanit Pass", proc.Process(sp, sp.Length, false, output, 30) == PacketVerdict.Pass);

        // Sorgu zaten secili sunucuya (Cloudflare 1.1.1.1:53) gidiyorsa yeniden yazilmaz.
        var cf = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Cloudflare);
        var cfAddr = System.Net.IPAddress.Parse(v6 ? DnsProfile.Cloudflare.V6Addr! : DnsProfile.Cloudflare.V4Addr!).GetAddressBytes();
        var direct = BuildUdp(v6, 53002, 53, query, dstOverride: cfAddr);
        Check($"{tag} Dogrudan sorguya dokunulmaz", cf.Process(direct, direct.Length, true, output, 0) == PacketVerdict.Pass);

        // DNS kapaliyken port 53 gecer.
        var offProc = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Off);
        var q2 = BuildUdp(v6, 53003, 53, query, dstLast: 1);
        Check($"{tag} DNS kapali: Pass", offProc.Process(q2, q2.Length, true, output, 0) == PacketVerdict.Pass);
    }

    // ====================================================== WinDivert (yerel DLL)

    private static void WinDivertNative()
    {
        var error = GoodbyeDpiService.ValidateRuntime(out var dir);
        if (error is not null || dir is null)
        {
            Check("Runtime klasoru", false, error);
            return;
        }

        WinDivert.EnsureLoaded(dir);

        var dnsOptions = new[]
        {
            DnsProfile.Off, DnsProfile.Cloudflare, DnsProfile.Yandex,
            DnsProfile.CreateCustom("9.9.9.9", 9953, "2620:fe::fe", 9953),
        };

        var configs = NativeProfile.All.Select(p => (p.Name, Cfg: p.Build())).Concat(
        [
            ("TTL sabit", new NativeDpiConfig { AutoTtl = false }),
            ("HTTP+QUIC kapali", new NativeDpiConfig { FragmentHttp = false, BlockQuic = false }),
            ("Hepsi kapali", new NativeDpiConfig { FakePacket = false, SplitTls = false, BlockQuic = false, FragmentHttp = false }),
        ]);

        foreach (var (name, cfg) in configs)
        {
            foreach (var dns in dnsOptions)
            {
                var filter = new PacketProcessor(cfg, dns).BuildFilter();
                var err = WinDivert.ValidateFilter(filter);
                Check($"Filtre derlenir: {name} / {dns.Name}", err is null, err + "\n    " + filter);
            }
        }

        // Saglama: Recalc -> gecerli; CorruptTcpChecksum -> kesinlikle gecersiz.
        foreach (var v6 in new[] { false, true })
        {
            var tag = v6 ? "v6" : "v4";
            var proc = new PacketProcessor(NativeProfile.WrongChecksum.Build(), DnsProfile.Off);
            var output = new List<OutPacket>();
            var p = BuildTcp(v6, 50040, 443, 1000, 2000, 0x18, 128, FakePackets.BuildClientHello(BlockedHost, 400));
            proc.Process(p, p.Length, true, output, 0);

            foreach (var (o, i) in output.Select((o, i) => (o, i)))
            {
                var addr = new WinDivertAddress { Outbound = true };
                NativeDpiService.PrepareForSend(o, ref addr);
                var valid = TcpChecksumValid(o.Data, o.Length);
                Check($"{tag} paket {i}: saglama {(o.CorruptTcpChecksum ? "bozuk" : "gecerli")}",
                    valid != o.CorruptTcpChecksum);
            }
        }
    }

    // ============================================================ DNS / profil / surum

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
        Check("Varsayilan: sahte+otomatik TTL", def is { FakePacket: true, FakeTtl: true, AutoTtl: true, Ttl: 5, BlockQuic: true });
        Check("Varsayilan: 2. bayt + SNI bolme, ters", def is { SplitTls: true, SplitPosition: 2, SplitSni: true, ReverseSplit: true });

        var fixedTtl = NativeProfile.FromId("fixedttl").Build();
        Check("Sabit TTL profili", fixedTtl is { FakePacket: true, FakeTtl: true, AutoTtl: false, Ttl: 5, SplitPosition: 2, SplitSni: false });

        var cks = NativeProfile.WrongChecksum.Build();
        Check("Checksum profili", cks is { FakeWrongChecksum: true, FakeWrongSeq: true, FakeTtl: false });

        var split = NativeProfile.SplitOnly.Build();
        Check("Bolme profili sahtesiz", split is { FakePacket: false, SplitTls: true });

        // Eski ayar dosyasi yeni alanlari tasimiyor: varsayilanlarla dolmali.
        var legacyJson = """{"engine":0,"nativeProfile":"custom","nativeCustom":{"fakePacket":true,"ttl":3,"splitPosition":0}}""";
        var legacy = System.Text.Json.JsonSerializer.Deserialize(legacyJson, AppSettingsJsonContext.Default.AppSettings);
        Check("Eski ayar: autoTtl varsayilan acik", legacy?.NativeCustom is { AutoTtl: true, SplitSni: true, Ttl: 3, SplitPosition: 0 });
    }

    private static void VersionParsing()
    {
        Check("v-onekli tag", UpdateService.TryParseTag("v2.1.0", out var a) && a == new Version(2, 1, 0));
        Check("duz tag", UpdateService.TryParseTag("3.0.0", out var b) && b == new Version(3, 0, 0));
        Check("bozuk tag red", !UpdateService.TryParseTag("final", out _));
        Check("kisa tag", UpdateService.TryParseTag("2.0", out var c) && c == new Version(2, 0, 0));
    }

    // ============================================================ yardimcilar

    private static void Check(string name, bool ok, string? detail = null)
    {
        Log.AppendLine(ok ? $"[GECTI] {name}" : $"[KALDI] {name}  {detail}");
        if (ok) _passed++;
        else _failed++;
    }

    private static bool Contains(byte[] data, string ascii) =>
        data.AsSpan().IndexOf(Encoding.ASCII.GetBytes(ascii)) >= 0;

    private static bool IpLengthMatches(byte[] data, int len, bool v6) => v6
        ? BinaryPrimitives.ReadUInt16BigEndian(data.AsSpan(4)) == len - 40
        : BinaryPrimitives.ReadUInt16BigEndian(data.AsSpan(2)) == len;

    /// <summary>TCP parcalarini SEQ'e gore dizip payload'u yeniden kurar.</summary>
    private static byte[] Reassemble(IReadOnlyList<OutPacket> segments, uint baseSeq)
    {
        var parts = segments
            .Select(s => (Pkt: s, Ip: IpPacket.Parse(s.Data, s.Length)))
            .OrderBy(x => x.Ip.TcpSeq)
            .ToList();

        var result = new List<byte>();
        foreach (var (pkt, ip) in parts)
        {
            if (ip.TcpSeq - baseSeq != result.Count) return []; // bosluk / ust uste binme
            result.AddRange(pkt.Data.AsSpan(ip.PayloadOffset, ip.PayloadLength).ToArray());
        }

        return result.ToArray();
    }

    /// <summary>TCP saglamasini WinDivert'ten bagimsiz olarak dogrular (v4/v6).</summary>
    private static bool TcpChecksumValid(byte[] p, int len)
    {
        var v6 = p[0] >> 4 == 6;
        var l4 = v6 ? 40 : (p[0] & 0x0F) * 4;
        var tcpLen = len - l4;

        ulong sum = 0;
        void AddBytes(ReadOnlySpan<byte> s)
        {
            for (var i = 0; i + 1 < s.Length; i += 2) sum += (uint)((s[i] << 8) | s[i + 1]);
            if (s.Length % 2 == 1) sum += (uint)(s[^1] << 8);
        }

        if (v6)
        {
            AddBytes(p.AsSpan(8, 32));
            sum += (uint)tcpLen;
            sum += 6;
        }
        else
        {
            AddBytes(p.AsSpan(12, 8));
            sum += 6;
            sum += (uint)tcpLen;
        }

        AddBytes(p.AsSpan(l4, tcpLen));
        while (sum >> 16 != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (ushort)sum == 0xFFFF;
    }

    private static byte[] BuildTcp(bool v6, ushort srcPort, ushort dstPort, uint seq, uint ack, byte flags, byte ttl,
        byte[] payload, bool swapAddr = false)
    {
        var ipHeader = v6 ? 40 : 20;
        const int tcpHeader = 20;
        var total = ipHeader + tcpHeader + payload.Length;
        var p = new byte[total];

        WriteIpHeader(p, v6, protocol: 6, ttl, total, swapAddr, dstLast: 0, srcOverride: null, dstOverride: null);

        var l4 = ipHeader;
        BinaryPrimitives.WriteUInt16BigEndian(p.AsSpan(l4), srcPort);
        BinaryPrimitives.WriteUInt16BigEndian(p.AsSpan(l4 + 2), dstPort);
        BinaryPrimitives.WriteUInt32BigEndian(p.AsSpan(l4 + 4), seq);
        BinaryPrimitives.WriteUInt32BigEndian(p.AsSpan(l4 + 8), ack);
        p[l4 + 12] = 0x50;
        p[l4 + 13] = flags;
        BinaryPrimitives.WriteUInt16BigEndian(p.AsSpan(l4 + 14), 65535);

        Array.Copy(payload, 0, p, ipHeader + tcpHeader, payload.Length);
        return p;
    }

    private static byte[] BuildUdp(bool v6, ushort srcPort, ushort dstPort, byte[] payload,
        byte dstLast = 0, byte[]? srcOverride = null, byte[]? dstOverride = null)
    {
        var ipHeader = v6 ? 40 : 20;
        var total = ipHeader + 8 + payload.Length;
        var p = new byte[total];

        WriteIpHeader(p, v6, protocol: 17, ttl: 64, total, swapAddr: false, dstLast, srcOverride, dstOverride);

        var l4 = ipHeader;
        BinaryPrimitives.WriteUInt16BigEndian(p.AsSpan(l4), srcPort);
        BinaryPrimitives.WriteUInt16BigEndian(p.AsSpan(l4 + 2), dstPort);
        BinaryPrimitives.WriteUInt16BigEndian(p.AsSpan(l4 + 4), (ushort)(8 + payload.Length));
        Array.Copy(payload, 0, p, l4 + 8, payload.Length);
        return p;
    }

    private static void WriteIpHeader(byte[] p, bool v6, byte protocol, byte ttl, int total, bool swapAddr,
        byte dstLast, byte[]? srcOverride, byte[]? dstOverride)
    {
        byte[] local = v6 ? [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10] : [192, 168, 0, 10];
        byte[] remote = v6 ? [0x26, 0x06, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x68, 0x10] : [162, 159, 136, 232];

        if (dstLast != 0)
        {
            // Yerel DNS sunucusu (orn. 192.168.0.1 / fe80::1 benzeri).
            remote = v6 ? [0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, dstLast] : [192, 168, 0, dstLast];
        }

        var src = swapAddr ? remote : local;
        var dst = swapAddr ? local : remote;
        if (srcOverride is not null) src = srcOverride;
        if (dstOverride is not null) dst = dstOverride;

        if (v6)
        {
            p[0] = 0x60;
            BinaryPrimitives.WriteUInt16BigEndian(p.AsSpan(4), (ushort)(total - 40));
            p[6] = protocol;
            p[7] = ttl;
            Array.Copy(src, 0, p, 8, 16);
            Array.Copy(dst, 0, p, 24, 16);
        }
        else
        {
            p[0] = 0x45;
            BinaryPrimitives.WriteUInt16BigEndian(p.AsSpan(2), (ushort)total);
            BinaryPrimitives.WriteUInt16BigEndian(p.AsSpan(4), 0x1234);
            p[6] = 0x40; // DF
            p[8] = ttl;
            p[9] = protocol;
            Array.Copy(src, 0, p, 12, 4);
            Array.Copy(dst, 0, p, 16, 4);
        }
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
        var recHeader = 5;
        var hsHeader = 4;
        var bodyBeforeExt = 2 + 32 + 1 + (2 + 2) + (1 + 1) + 2; // ver+rand+sid+cipher+comp+extlen
        var extHeader = 4;      // ext type + ext len
        var sniHeader = 2 + 1 + 2; // list_len + name_type + name_len
        var sniStart = recHeader + hsHeader + bodyBeforeExt + extHeader + sniHeader;

        var packet = BuildTcp(v6, 51000, 443, 1000, 0, 0x18, 64, payload);
        return (packet, packet.Length, sniStart, host);
    }
}
