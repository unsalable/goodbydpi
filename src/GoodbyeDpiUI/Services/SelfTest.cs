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
        Section("Motor: sira ortusmesi (seqovl)", EngineSeqOverlap);
        Section("Motor: sahte paket cesitleri", EngineFakeVariants);
        Section("Motor: cok paketli ClientHello (ML-KEM)", () => { EngineContinuation(v6: false); EngineContinuation(v6: true); });
        Section("Motor: Discord ses / STUN", () => { EngineVoice(v6: false); EngineVoice(v6: true); });
        Section("Motor: HTTP", EngineHttp);
        Section("Motor: QUIC", EngineQuic);
        Section("Motor: DNS yonlendirme", () => { EngineDns(v6: false); EngineDns(v6: true); });
        Section("WinDivert: filtre + saglama", WinDivertNative);
        Section("WinDivert: filtre paket eslesmesi", WinDivertFilterMatches);
        Section("DNS profilleri", () => { DnsArguments(); DnsValidation(); });
        Section("Native profiller", NativeProfiles);
        Section("Internet saglayici profilleri", IspProfiles);
        Section("Surum", VersionParsing);
        Section("Hareket: yay egrisi", SpringCurve);

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
            new NativeDpiConfig { FakePacket = false, SplitTls = false, BlockQuic = false, FragmentHttp = false, VoiceFake = false },
            DnsProfile.Off);
        Check("Teknik yok: filtre 'false'", none.BuildFilter() == "false");
    }

    private static void EngineSeqOverlap()
    {
        var output = new List<OutPacket>();
        var hello = FakePackets.BuildClientHello(BlockedHost, 700);

        // Ters sira (Turk Telekom onerisi): zapret2 multidisorder pos=2 seqovl=1.
        var proc = new PacketProcessor(NativeProfile.Disorder.Build(), DnsProfile.Off);
        var p = BuildTcp(false, 50100, 443, 900_000, 5, 0x18, 128, hello);
        Check("Ters sira: Replace", proc.Process(p, p.Length, true, output, 0) == PacketVerdict.Replace);
        Check("Ters sira: sahte paket yok", output.All(o => !Contains(o.Data, FakePackets.FakeHost)));
        Check("Ters sira: 2 parca", output.Count == 2, $"{output.Count}");
        if (output.Count != 2) return;

        var first = IpPacket.Parse(output[0].Data, output[0].Length);
        var second = IpPacket.Parse(output[1].Data, output[1].Length);
        Check("Ters sira: ilk giden SEQ+1 (1 bayt ortusme)", first.TcpSeq == 900_001, $"{first.TcpSeq}");
        Check("Ters sira: ilk giden = sahte bayt + 3. bayttan sonrasi",
            output[0].Data[first.PayloadOffset] == 0 && first.PayloadLength == hello.Length - 1 &&
            output[0].Data.AsSpan(first.PayloadOffset + 1).SequenceEqual(hello.AsSpan(2)));
        Check("Ters sira: son giden ilk 2 bayt", second.TcpSeq == 900_000 && second.PayloadLength == 2);
        Check("Ters sira: paketler orijinalden buyuk degil", output.All(o => o.Length <= p.Length));
        Check("Ters sira: sunucu orijinal istegi alir", ReceiverView(output, 900_000).AsSpan().SequenceEqual(hello));

        var dpiView = FirstWinsView(output, 900_000);
        Check("Ters sira: ilk gelen veriyi tutan DPI ClientHello goremez", !TlsParser.IsClientHello(dpiView, 0, dpiView.Length),
            BitConverter.ToString(dpiView, 0, Math.Min(6, dpiView.Length)));

        // Duz bolme + ortusme: ilk parcanin SEQ'i geri cekilir, sunucu pencere disini atar.
        var fwd = new PacketProcessor(
            new NativeDpiConfig { FakePacket = false, SplitSni = false, ReverseSplit = false, SeqOverlap = 3 }, DnsProfile.Off);
        var p2 = BuildTcp(false, 50101, 443, 800_000, 5, 0x18, 128, hello);
        fwd.Process(p2, p2.Length, true, output, 0);
        var f0 = IpPacket.Parse(output[0].Data, output[0].Length);
        Check("Duz ortusme: ilk parca SEQ-3, 3 sahte + 2 gercek bayt",
            f0.TcpSeq == 799_997 && f0.PayloadLength == 5 && output[0].Data[f0.PayloadOffset + 3] == hello[0],
            $"{f0.TcpSeq}/{f0.PayloadLength}");
        Check("Duz ortusme: sunucu orijinali alir", ReceiverView(output, 800_000).AsSpan().SequenceEqual(hello));

        // Ortusme ilk bolme konumundan kucuk degilse iptal (sunucu sahte baytlari ezemezdi).
        var cancel = new PacketProcessor(new NativeDpiConfig { FakePacket = false, SplitSni = false, SeqOverlap = 2 }, DnsProfile.Off);
        var p3 = BuildTcp(false, 50102, 443, 700_000, 5, 0x18, 128, hello);
        cancel.Process(p3, p3.Length, true, output, 0);
        Check("Ortusme >= bolme konumu: iptal edilir",
            IpPacket.Parse(output[0].Data, output[0].Length).TcpSeq == 700_002 && Reassemble(output, 700_000).AsSpan().SequenceEqual(hello));

        // SNI bolmesiyle birlikte (3 parca): ortusme ikinci parcada, sunucu yine orijinali alir.
        var multi = new PacketProcessor(new NativeDpiConfig { FakePacket = false, SeqOverlap = 1 }, DnsProfile.Off);
        var p4 = BuildTcp(true, 50103, 443, 600_000, 5, 0x18, 128, hello);
        multi.Process(p4, p4.Length, true, output, 0);
        Check("SNI + ortusme: 3 parca", output.Count == 3, $"{output.Count}");
        Check("SNI + ortusme: sunucu orijinali alir", ReceiverView(output, 600_000).AsSpan().SequenceEqual(hello));
        Check("SNI + ortusme: hicbir parcada tam SNI yok", output.All(o => !Contains(o.Data, BlockedHost)));
    }

    private static void EngineFakeVariants()
    {
        var output = new List<OutPacket>();
        var hello = FakePackets.BuildClientHello(BlockedHost, 700);

        // MD5 imzasi (Superonline): 20 baytlik TCP secenegi, TTL degismez, orijinal arkadan.
        var md5 = new PacketProcessor(NativeProfile.Md5Sig.Build(), DnsProfile.Off);
        var p1 = BuildTcp(false, 50110, 443, 10_000, 20_000, 0x18, 128, hello);
        md5.Process(p1, p1.Length, true, output, 0);
        Check("MD5: sahte + degismemis orijinal", output.Count == 2 && output[1].Data == p1 && !output[1].Recalc, $"{output.Count}");
        if (output.Count == 2)
        {
            var m = IpPacket.Parse(output[0].Data, output[0].Length);
            Check("MD5: TCP basligi 40 bayt", m.Valid && m.L4HeaderLength == 40, $"{m.L4HeaderLength}");
            Check("MD5: secenek NOP NOP 19 18", output[0].Data.AsSpan(m.L4Offset + 20, 4).SequenceEqual(new byte[] { 1, 1, 19, 18 }));
            Check("MD5: icerik sahte ClientHello", output[0].Data.AsSpan(m.PayloadOffset).SequenceEqual(FakePackets.TlsClientHello));
            Check("MD5: IP uzunlugu", IpLengthMatches(output[0].Data, output[0].Length, v6: false));
            Check("MD5: TTL ve SEQ degismez", m.Ttl == 128 && m.TcpSeq == 10_000 && !output[0].CorruptTcpChecksum);
        }

        var md5v6 = BuildTcp(true, 50111, 443, 10_000, 20_000, 0x18, 128, hello);
        new PacketProcessor(NativeProfile.Md5Ttl3.Build(), DnsProfile.Off).Process(md5v6, md5v6.Length, true, output, 0);
        var m6 = IpPacket.Parse(output[0].Data, output[0].Length);
        Check("MD5 + TTL 3 (v6): tek sahte, hop limit 3, secenekli",
            output.Count == 2 && m6.Ttl == 3 && m6.L4HeaderLength == 40 && IpLengthMatches(output[0].Data, output[0].Length, v6: true));

        // Bos sahte (TT Mobil): 4 sifir bayt, TTL 5.
        var zero = new PacketProcessor(NativeProfile.ZeroFake.Build(), DnsProfile.Off);
        var p2 = BuildTcp(false, 50112, 443, 30_000, 1, 0x18, 128, hello);
        zero.Process(p2, p2.Length, true, output, 0);
        var z = IpPacket.Parse(output[0].Data, output[0].Length);
        Check("Bos sahte: 4 sifir bayt, TTL 5",
            output.Count == 2 && z.PayloadLength == 4 && output[0].Data.AsSpan(z.PayloadOffset).IndexOfAnyExcept((byte)0) < 0 && z.Ttl == 5);

        var httpReq = Encoding.ASCII.GetBytes("GET / HTTP/1.1\r\nHost: " + BlockedHost + "\r\n\r\n");
        var ph = BuildTcp(false, 50113, 80, 1, 1, 0x18, 128, httpReq);
        zero.Process(ph, ph.Length, true, output, 0);
        Check("Bos sahte: HTTP'de de sifir bayt",
            !Contains(output[0].Data, "Host:") && IpPacket.Parse(output[0].Data, output[0].Length).PayloadLength == 4);

        // Bolunmus sahte (Vodafone): sahte istek 2 parca TTL 5, gercek istek degismez.
        var sf = new PacketProcessor(NativeProfile.SplitFakeTtl5.Build(), DnsProfile.Off);
        var p3 = BuildTcp(false, 50114, 443, 40_000, 1, 0x18, 128, hello);
        sf.Process(p3, p3.Length, true, output, 0);
        Check("Bolunmus sahte: 2 sahte + orijinal", output.Count == 3 && output[2].Data == p3, $"{output.Count}");
        if (output.Count == 3)
        {
            var a = IpPacket.Parse(output[0].Data, output[0].Length);
            var b = IpPacket.Parse(output[1].Data, output[1].Length);
            Check("Bolunmus sahte: SEQ ve boylar",
                a.TcpSeq == 40_000 && a.PayloadLength == 2 && b.TcpSeq == 40_002 && b.PayloadLength == FakePackets.TlsClientHello.Length - 2);
            Check("Bolunmus sahte: TTL 5", a.Ttl == 5 && b.Ttl == 5);
            Check("Bolunmus sahte: birlesince sahte ClientHello",
                Reassemble(output.Take(2).ToList(), 40_000).AsSpan().SequenceEqual(FakePackets.TlsClientHello));
        }

        // Tekrar sayisi.
        var rep = new PacketProcessor(new NativeDpiConfig { AutoTtl = false, SplitTls = false, FakeRepeats = 3 }, DnsProfile.Off);
        var p4 = BuildTcp(false, 50115, 443, 1, 1, 0x18, 128, hello);
        rep.Process(p4, p4.Length, true, output, 0);
        Check("Tekrar 3: 3 sahte + orijinal",
            output.Count == 4 && output.Take(3).All(o => Contains(o.Data, FakePackets.FakeHost)), $"{output.Count}");

        var repClamp = new PacketProcessor(new NativeDpiConfig { AutoTtl = false, SplitTls = false, FakeRepeats = 99 }, DnsProfile.Off);
        repClamp.Process(p4, p4.Length, true, output, 0);
        Check("Tekrar 99 -> 10'a sinirlanir", output.Count == 11, $"{output.Count}");

        // Sahte TTL 4 (Turk Telekom alternatifi).
        var t4 = new PacketProcessor(NativeProfile.FakeTtl4.Build(), DnsProfile.Off);
        var p5 = BuildTcp(false, 50116, 443, 1, 1, 0x18, 128, hello);
        t4.Process(p5, p5.Length, true, output, 0);
        Check("Sahte TTL 4: TTL 4 sahte + degismemis orijinal",
            output.Count == 2 && IpPacket.Parse(output[0].Data, output[0].Length).Ttl == 4 && output[1].Data == p5);
    }

    private static void EngineContinuation(bool v6)
    {
        var tag = v6 ? "v6" : "v4";
        var output = new List<OutPacket>();

        // Chromium ML-KEM istegi gibi ~1.8 KB; SNI uzantisi sonda -> ikinci TCP paketinde.
        var hello = FakePackets.BuildClientHello(BlockedHost, 1800, sniLast: true);
        const int mss = 1440;
        var sniAt = hello.AsSpan().IndexOf(Encoding.ASCII.GetBytes(BlockedHost));
        Check($"{tag} test verisi: SNI ikinci pakette", sniAt > mss, $"{sniAt}");

        var proc = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Off);
        var s1 = BuildTcp(v6, 50200, 443, 400_000, 7, 0x10, 128, hello[..mss]);
        var s2 = BuildTcp(v6, 50200, 443, 400_000 + mss, 7, 0x18, 128, hello[mss..]);

        Check($"{tag} ilk paket: Replace", proc.Process(s1, s1.Length, true, output, 0) == PacketVerdict.Replace);
        var sent = new List<OutPacket>(output);
        Check($"{tag} ilk paket: sahte paket gitti", sent.Any(o => Contains(o.Data, FakePackets.FakeHost)));

        Check($"{tag} devam paketi: Replace", proc.Process(s2, s2.Length, true, output, 5) == PacketVerdict.Replace);
        Check($"{tag} devam paketi: 2 parca", output.Count == 2, $"{output.Count}");
        Check($"{tag} devam parcalari orijinalden buyuk degil", output.All(o => o.Length <= s2.Length));
        sent.AddRange(output);

        var real = sent.Where(o => !Contains(o.Data, FakePackets.FakeHost)).ToList();
        Check($"{tag} hicbir pakette tam SNI yok", real.All(o => !Contains(o.Data, BlockedHost)));
        Check($"{tag} sunucu orijinal istegi alir", ReceiverView(real, 400_000).AsSpan().SequenceEqual(hello));
        Check($"{tag} sayaclar", proc.Stats is { HelloContinuations: 1, ContinuationSplits: 1, HellosWithoutSni: 1 }, proc.Stats.ToString());

        // Ayni devam paketi ikinci kez (yeniden gonderim) -> dokunulmaz.
        Check($"{tag} tekrar gelen devam paketi Pass", proc.Process(s2, s2.Length, true, output, 10) == PacketVerdict.Pass);

        // SEQ uymayan veri -> dokunulmaz.
        var p2 = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Off);
        p2.Process(s1, s1.Length, true, output, 0);
        var wrong = BuildTcp(v6, 50200, 443, 400_000 + mss + 100, 7, 0x18, 128, hello[mss..]);
        Check($"{tag} SEQ uymayan devam Pass", p2.Process(wrong, wrong.Length, true, output, 5) == PacketVerdict.Pass);

        // Suresi dolmus kayit -> dokunulmaz.
        var p3 = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Off);
        p3.Process(s1, s1.Length, true, output, 0);
        Check($"{tag} eski kayit Pass", p3.Process(s2, s2.Length, true, output, 60_000) == PacketVerdict.Pass);

        // SNI ilk pakette tamamen gorunuyorsa devam beklenmez.
        var early = FakePackets.BuildClientHello(BlockedHost, 1800);
        var p4 = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Off);
        var e1 = BuildTcp(v6, 50201, 443, 1, 7, 0x10, 128, early[..mss]);
        var e2 = BuildTcp(v6, 50201, 443, 1 + mss, 7, 0x18, 128, early[mss..]);
        p4.Process(e1, e1.Length, true, output, 0);
        Check($"{tag} SNI ilk pakette: devam Pass", p4.Process(e2, e2.Length, true, output, 5) == PacketVerdict.Pass);

        // Uc pakete yayilan istek: ad son pakette bolunur.
        var big = FakePackets.BuildClientHello(BlockedHost, 3000, sniLast: true);
        var p5 = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Off);
        var b1 = BuildTcp(v6, 50202, 443, 5_000, 7, 0x10, 128, big[..1200]);
        var b2 = BuildTcp(v6, 50202, 443, 6_200, 7, 0x10, 128, big[1200..2400]);
        var b3 = BuildTcp(v6, 50202, 443, 7_400, 7, 0x18, 128, big[2400..]);
        p5.Process(b1, b1.Length, true, output, 0);
        var bigSent = output.Where(o => !Contains(o.Data, FakePackets.FakeHost)).ToList();
        Check($"{tag} 3 paket: orta paket Pass", p5.Process(b2, b2.Length, true, output, 1) == PacketVerdict.Pass);
        bigSent.Add(new OutPacket(b2, b2.Length, false, false));
        Check($"{tag} 3 paket: son paket bolunur",
            p5.Process(b3, b3.Length, true, output, 2) == PacketVerdict.Replace && output.Count == 2, $"{output.Count}");
        bigSent.AddRange(output);
        Check($"{tag} 3 paket: sunucu orijinali alir", ReceiverView(bigSent, 5_000).AsSpan().SequenceEqual(big));
        Check($"{tag} 3 paket: tam SNI yok", bigSent.All(o => !Contains(o.Data, BlockedHost)));

        // Ters sira (SNI bolmesi kapali): devam paketi hic izlenmez.
        var dis = new PacketProcessor(NativeProfile.Disorder.Build(), DnsProfile.Off);
        dis.Process(s1, s1.Length, true, output, 0);
        Check($"{tag} Ters sira: devam Pass", dis.Process(s2, s2.Length, true, output, 5) == PacketVerdict.Pass);
    }

    private static void EngineVoice(bool v6)
    {
        var tag = v6 ? "v6" : "v4";
        var output = new List<OutPacket>();
        var proc = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Yandex);

        var disc = BuildUdp(v6, 50300, 50004, DiscordDiscovery(0x0000BEEF));
        var original = (byte[])disc.Clone();
        Check($"{tag} IP Discovery: Replace", proc.Process(disc, disc.Length, true, output, 0) == PacketVerdict.Replace);
        Check($"{tag} IP Discovery: 6 sahte + gercek", output.Count == 7, $"{output.Count}");
        if (output.Count == 7)
        {
            var fakes = output.Take(6).Select(o => (o, ip: IpPacket.Parse(o.Data, o.Length))).ToList();
            Check($"{tag} sahte UDP: 64 sifir bayt",
                fakes.All(f => f.ip.Valid && f.ip.PayloadLength == 64 && f.o.Data.AsSpan(f.ip.PayloadOffset).IndexOfAnyExcept((byte)0) < 0));
            Check($"{tag} sahte UDP: ayni portlar", fakes.All(f => f.ip.SrcPort == 50300 && f.ip.DstPort == 50004));
            Check($"{tag} sahte UDP: UDP uzunlugu 72",
                fakes.All(f => BinaryPrimitives.ReadUInt16BigEndian(f.o.Data.AsSpan(f.ip.L4Offset + 4)) == 72));
            Check($"{tag} sahte UDP: IP uzunlugu", fakes.All(f => IpLengthMatches(f.o.Data, f.o.Length, v6)));
            Check($"{tag} sahte UDP: TTL degismez", fakes.All(f => f.ip.Ttl == 64));
            Check($"{tag} gercek paket en son ve degismeden",
                output[6].Data == disc && !output[6].Recalc && disc.AsSpan().SequenceEqual(original));
        }

        var stun = BuildUdp(v6, 50301, 19302, StunBindingRequest());
        Check($"{tag} STUN: Replace (6 sahte + gercek)",
            proc.Process(stun, stun.Length, true, output, 0) == PacketVerdict.Replace && output.Count == 7);

        var stun2 = BuildUdp(v6, 50302, 3478, StunBindingRequest(attributeBytes: 8));
        Check($"{tag} STUN (ozellikli): Replace", proc.Process(stun2, stun2.Length, true, output, 0) == PacketVerdict.Replace);

        var badLen = StunBindingRequest();
        badLen[3] = 4; // uzunluk alani paketle tutarsiz
        var bl = BuildUdp(v6, 50303, 3478, badLen);
        Check($"{tag} tutarsiz STUN Pass", proc.Process(bl, bl.Length, true, output, 0) == PacketVerdict.Pass);

        var rtp = new byte[74];
        rtp[0] = 0x80;
        rtp[1] = 0x78;
        var rp = BuildUdp(v6, 50300, 50004, rtp);
        Check($"{tag} ses verisi (RTP) Pass", proc.Process(rp, rp.Length, true, output, 0) == PacketVerdict.Pass);

        var filled = DiscordDiscovery(1);
        filled[20] = 0x31; // adres alani dolu -> istek degil
        var fp = BuildUdp(v6, 50300, 50004, filled);
        Check($"{tag} adres alani dolu 74 bayt Pass", proc.Process(fp, fp.Length, true, output, 0) == PacketVerdict.Pass);

        Check($"{tag} gelen STUN Pass", proc.Process(stun, stun.Length, outbound: false, output, 0) == PacketVerdict.Pass);

        var off = new PacketProcessor(new NativeDpiConfig { VoiceFake = false }, DnsProfile.Off);
        Check($"{tag} ses kapali: Pass", off.Process(disc, disc.Length, true, output, 0) == PacketVerdict.Pass);

        var two = new PacketProcessor(new NativeDpiConfig { VoiceFakeRepeats = 2 }, DnsProfile.Off);
        two.Process(disc, disc.Length, true, output, 0);
        Check($"{tag} tekrar 2: 2 sahte + gercek", output.Count == 3, $"{output.Count}");

        // DNS yonlendirmesi ses destegiyle bozulmaz.
        var query = new byte[29];
        query[2] = 0x01;
        var q = BuildUdp(v6, 53010, 53, query, dstLast: 1);
        Check($"{tag} DNS sorgusu hala yonlendirilir",
            proc.Process(q, q.Length, true, output, 0) == PacketVerdict.Replace && IpPacket.Parse(q, q.Length).DstPort == 1253);

        Check($"{tag} sayaclar", proc.Stats is { VoiceDiscoveries: 1, StunMessages: 2, VoiceFakesSent: 18 }, proc.Stats.ToString());
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
            ("Hepsi kapali", new NativeDpiConfig { FakePacket = false, SplitTls = false, BlockQuic = false, FragmentHttp = false, VoiceFake = false }),
            ("Hepsi acik", new NativeDpiConfig { FakeWrongChecksum = true, FakeWrongSeq = true, FakeMd5Sig = true, SplitFake = true, SeqOverlap = 1 }),
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
                var valid = L4ChecksumValid(o.Data, o.Length);
                Check($"{tag} paket {i}: saglama {(o.CorruptTcpChecksum ? "bozuk" : "gecerli")}",
                    valid != o.CorruptTcpChecksum);
            }

            // MD5 secenekli sahte, bolunmus sahte, ortusmeli parcalar ve sahte UDP de gecerli saglamayla gitmeli.
            var mixed = new PacketProcessor(
                new NativeDpiConfig { AutoTtl = false, FakeMd5Sig = true, SplitFake = true, SeqOverlap = 1 }, DnsProfile.Off);
            var hp = BuildTcp(v6, 50041, 443, 5000, 6000, 0x18, 128, FakePackets.BuildClientHello(BlockedHost, 500));
            mixed.Process(hp, hp.Length, true, output, 0);
            var mixedOk = output.Count > 0 && output.All(o =>
            {
                var addr = new WinDivertAddress { Outbound = true };
                NativeDpiService.PrepareForSend(o, ref addr);
                return !o.Recalc || L4ChecksumValid(o.Data, o.Length);
            });
            Check($"{tag} MD5 / bolunmus sahte / ortusme saglamalari gecerli", mixedOk, $"{output.Count} paket");

            var voice = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Off);
            var disc = BuildUdp(v6, 50042, 50004, DiscordDiscovery(0x11223344));
            voice.Process(disc, disc.Length, true, output, 0);
            var udpOk = output.Count == 7 && output.Take(6).All(o =>
            {
                var addr = new WinDivertAddress { Outbound = true };
                NativeDpiService.PrepareForSend(o, ref addr);
                return L4ChecksumValid(o.Data, o.Length);
            });
            Check($"{tag} sahte UDP saglamalari gecerli", udpOk, $"{output.Count} paket");
        }
    }

    /// <summary>
    /// Filtre metni gercek paketlerle eslesiyor mu? WinDivertHelperEvalFilter surucuye gitmeden
    /// kullanici modunda degerlendirir: yakalanmasi gerekenler yakalanmali, toplu veri akisi,
    /// ses verisi ve yerel ag trafigi yakalanmamali.
    /// </summary>
    private static void WinDivertFilterMatches()
    {
        var error = GoodbyeDpiService.ValidateRuntime(out var dir);
        if (error is not null || dir is null)
        {
            Check("Runtime klasoru", false, error);
            return;
        }

        WinDivert.EnsureLoaded(dir);

        static bool Eval(string filter, byte[] packet, bool outbound, bool v6)
        {
            var addr = new WinDivertAddress { Outbound = outbound, IsIPv6 = v6 };
            return WinDivert.WinDivertHelperEvalFilter(filter, packet, (uint)packet.Length, ref addr);
        }

        foreach (var v6 in new[] { false, true })
        {
            var tag = v6 ? "v6" : "v4";
            var def = new PacketProcessor(NativeProfile.Default.Build(), DnsProfile.Yandex).BuildFilter();

            var hello = BuildTcp(v6, 50500, 443, 1, 1, 0x18, 128, FakePackets.BuildClientHello(BlockedHost, 1400, sniLast: true));
            Check($"{tag} ClientHello yakalanir", Eval(def, hello, true, v6));

            byte[] lan = v6 ? [0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x50] : [192, 168, 0, 50];
            var lanHello = BuildTcp(v6, 50501, 443, 1, 1, 0x18, 128, FakePackets.BuildClientHello(BlockedHost, 600), dstOverride: lan);
            Check($"{tag} yerel aga ClientHello yakalanmaz", !Eval(def, lanHello, true, v6));

            var cont = BuildTcp(v6, 50500, 443, 1461, 1, 0x18, 128, Enumerable.Repeat((byte)0x7A, 340).ToArray());
            Check($"{tag} kucuk devam paketi yakalanir", Eval(def, cont, true, v6));

            var bulk = BuildTcp(v6, 50502, 443, 9, 1, 0x10, 128, Enumerable.Repeat((byte)0x7A, 1440).ToArray());
            Check($"{tag} MSS boyutlu yukleme yakalanmaz", !Eval(def, bulk, true, v6));

            var ack = BuildTcp(v6, 50502, 443, 9, 1, 0x10, 128, []);
            Check($"{tag} bos ACK yakalanmaz", !Eval(def, ack, true, v6));

            var download = BuildTcp(v6, 443, 50502, 9, 1, 0x18, 128, Enumerable.Repeat((byte)0x7A, 500).ToArray(), swapAddr: true);
            Check($"{tag} gelen veri yakalanmaz", !Eval(def, download, false, v6));

            var synAck = BuildTcp(v6, 443, 50503, 1, 2, 0x12, 52, [], swapAddr: true);
            Check($"{tag} SYN-ACK yakalanir", Eval(def, synAck, false, v6));

            var http = BuildTcp(v6, 50504, 80, 1, 1, 0x18, 128, Encoding.ASCII.GetBytes("GET / HTTP/1.1\r\nHost: x\r\n\r\n"));
            Check($"{tag} HTTP istegi yakalanir", Eval(def, http, true, v6));

            var disc = BuildUdp(v6, 50505, 50004, DiscordDiscovery(0xCAFE0001));
            Check($"{tag} Discord IP Discovery yakalanir", Eval(def, disc, true, v6));

            var stun = BuildUdp(v6, 50506, 19302, StunBindingRequest());
            Check($"{tag} STUN yakalanir", Eval(def, stun, true, v6));

            var rtp = new byte[74];
            rtp[0] = 0x80;
            rtp[1] = 0x78;
            Check($"{tag} ses verisi (RTP) yakalanmaz", !Eval(def, BuildUdp(v6, 50505, 50004, rtp), true, v6));

            var game = BuildUdp(v6, 50507, 49152, Enumerable.Repeat((byte)0x33, 120).ToArray());
            Check($"{tag} oyun UDP trafigi yakalanmaz", !Eval(def, game, true, v6));

            var quic = new byte[1250];
            quic[0] = 0xC3;
            BinaryPrimitives.WriteUInt32BigEndian(quic.AsSpan(1), 1);
            Check($"{tag} QUIC Initial yakalanir", Eval(def, BuildUdp(v6, 50508, 443, quic), true, v6));

            var query = new byte[29];
            query[2] = 0x01;
            Check($"{tag} DNS sorgusu yakalanir", Eval(def, BuildUdp(v6, 53001, 53, query, dstLast: 1), true, v6));

            // Turk Telekom (Ters sira): SNI bolmesi yok, devam paketlerini yakalamaya gerek yok.
            var tt = new PacketProcessor(IspProfile.TurkTelekom.Recommended.Build(), DnsProfile.Yandex).BuildFilter();
            Check($"{tag} Ters sira: ClientHello yakalanir", Eval(tt, hello, true, v6));
            Check($"{tag} Ters sira: devam paketi yakalanmaz", !Eval(tt, cont, true, v6));
            Check($"{tag} Ters sira: Discord ses yakalanir", Eval(tt, disc, true, v6));

            var off = new PacketProcessor(new NativeDpiConfig { VoiceFake = false }, DnsProfile.Off).BuildFilter();
            Check($"{tag} ses kapali: Discord ses yakalanmaz", !Eval(off, disc, true, v6));
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
        Check("Eski ayar: yeni alanlar varsayilan",
            legacy?.NativeCustom is { VoiceFake: true, VoiceFakeRepeats: 6, SeqOverlap: 0, FakeRepeats: 1, FakeMd5Sig: false, FakePayload: FakePayloadKind.Tls },
            System.Text.Json.JsonSerializer.Serialize(legacy?.NativeCustom));
        Check("Eski ayar: saglayici Genel", legacy?.Isp == IspProfile.GeneralId, legacy?.Isp);

        var ids = NativeProfile.All.Select(p => p.Id).ToList();
        Check("Yontem kimlikleri benzersiz", ids.Distinct(StringComparer.OrdinalIgnoreCase).Count() == ids.Count);
        Check("Her yontem kimliginden geri bulunur", NativeProfile.All.All(p => ReferenceEquals(NativeProfile.FromId(p.Id), p)));

        Check("Ters sira: sahtesiz, pos 2, ters, seqovl 1",
            NativeProfile.Disorder.Build() is { FakePacket: false, SplitTls: true, SplitPosition: 2, SplitSni: false, ReverseSplit: true, SeqOverlap: 1 });
        Check("Sahte TTL 4: sabit TTL, bolme yok",
            NativeProfile.FakeTtl4.Build() is { FakePacket: true, FakeTtl: true, AutoTtl: false, Ttl: 4, SplitTls: false });
        Check("MD5: TTL'siz, md5sig", NativeProfile.Md5Sig.Build() is { FakeTtl: false, FakeMd5Sig: true, SplitTls: false });
        Check("Bos sahte: sifir bayt, TTL 5", NativeProfile.ZeroFake.Build() is { FakePayload: FakePayloadKind.Zeros, AutoTtl: false, Ttl: 5 });
        Check("Butun hazir yontemlerde Discord ses acik", NativeProfile.All.All(p => p.Build().VoiceFake));
    }

    private static void IspProfiles()
    {
        Check("Ilk saglayici Genel", IspProfile.All[0].Id == IspProfile.GeneralId);
        Check("Bilinmeyen saglayici -> Genel", IspProfile.FromId("yok") == IspProfile.General);

        var ispIds = IspProfile.All.Select(i => i.Id).ToList();
        Check("Saglayici kimlikleri benzersiz", ispIds.Distinct(StringComparer.OrdinalIgnoreCase).Count() == ispIds.Count);

        foreach (var isp in IspProfile.All)
        {
            Check($"{isp.Name}: en az bir yontem, Ozel yok",
                isp.Methods.Count > 0 && isp.Methods.All(m => m.Id != NativeProfile.CustomId));
            Check($"{isp.Name}: yontemler katalogda ve tekrarsiz",
                isp.Methods.All(m => NativeProfile.All.Contains(m)) && isp.Methods.Distinct().Count() == isp.Methods.Count);
            Check($"{isp.Name}: GoodbyeDPI yontemi gecerli", DpiMethod.FromId(isp.GoodbyeMethodId).Id == isp.GoodbyeMethodId);
            Check($"{isp.Name}: DNS gecerli",
                isp.DnsId is null ? isp.Id == IspProfile.GeneralId : DnsProfile.BuiltIn.Any(d => d.Id == isp.DnsId));
        }

        Check("Genel: onceki varsayilan yontem korunur", IspProfile.General.Recommended == NativeProfile.Default);
        Check("Turk Telekom: onerilen Ters sira", IspProfile.TurkTelekom.Recommended == NativeProfile.Disorder);
        Check("Turk Telekom: TTL 4 / 3 alternatifleri",
            IspProfile.TurkTelekom.Methods.Contains(NativeProfile.FakeTtl4) && IspProfile.TurkTelekom.Methods.Contains(NativeProfile.FakeTtl3));
        Check("Turk Telekom: DNS Yandex", IspProfile.TurkTelekom.DnsId == DnsProfile.Yandex.Id);
        Check("Superonline: MD5 alternatifi", IspProfile.Superonline.Methods.Contains(NativeProfile.Md5Sig));
        Check("Vodafone: bolunmus sahte", IspProfile.Vodafone.Recommended == NativeProfile.SplitFakeTtl5);
        Check("TT Mobil: bos sahte", IspProfile.TelekomMobil.Recommended == NativeProfile.ZeroFake);

        var json = """{"isp":"turktelekom","nativeProfile":"disorder","dns":"yandex"}""";
        var loaded = System.Text.Json.JsonSerializer.Deserialize(json, AppSettingsJsonContext.Default.AppSettings);
        Check("Ayar: saglayici okunur", loaded is not null && IspProfile.FromId(loaded.Isp) == IspProfile.TurkTelekom);

        var written = System.Text.Json.JsonSerializer.Serialize(new AppSettings { Isp = "superonline" }, AppSettingsJsonContext.Default.AppSettings);
        Check("Ayar: saglayici yazilir", written.Contains("\"isp\": \"superonline\""), written);
    }

    private static void VersionParsing()
    {
        Check("v-onekli tag", UpdateService.TryParseTag("v2.1.0", out var a) && a == new Version(2, 1, 0));
        Check("duz tag", UpdateService.TryParseTag("3.0.0", out var b) && b == new Version(3, 0, 0));
        Check("bozuk tag red", !UpdateService.TryParseTag("final", out _));
        Check("kisa tag", UpdateService.TryParseTag("2.0", out var c) && c == new Version(2, 0, 0));
    }

    // =================================================== hareket: yay egrisi

    /// <summary>
    /// Arayuzdeki tum gecisler SpringEase uzerinden gidiyor; egri bozulursa animasyonlar
    /// ya hedefi tutturamaz ya da ziplar. Ucundan ucuna ve sekil olarak dogrulaniyor.
    /// </summary>
    private static void SpringCurve()
    {
        double[] bounces = [0, 0.15, 0.32];

        foreach (var bounce in bounces)
        {
            Check($"yay({bounce}) 0'da 0", Math.Abs(SpringEase.Evaluate(0, bounce)) < 1e-9);
            Check($"yay({bounce}) 1'de 1", Math.Abs(SpringEase.Evaluate(1, bounce) - 1) < 1e-9,
                $"{SpringEase.Evaluate(1, bounce)}");
        }

        // Sonumlu (bounce 0) egri hedefi asmadan, geri gitmeden ilerlemeli.
        var previous = 0.0;
        var monotonic = true;
        var overshoot = 0.0;
        for (var i = 1; i <= 200; i++)
        {
            var value = SpringEase.Evaluate(i / 200.0, 0);
            if (value < previous - 1e-9) monotonic = false;
            overshoot = Math.Max(overshoot, value);
            previous = value;
        }

        Check("yay(0) geri gitmiyor", monotonic);
        Check("yay(0) hedefi asmiyor", overshoot <= 1 + 1e-6, $"{overshoot}");

        // Duragan baslayip hizla yol almali: yarida %85'i gecmis olsun.
        Check("yay(0) yarida yolun cogunu aliyor", SpringEase.Evaluate(0.5, 0) > 0.85,
            $"{SpringEase.Evaluate(0.5, 0):F3}");

        // Ziplamali egri hedefi belirgin ama olculu asmali.
        var peak = 0.0;
        for (var i = 1; i <= 200; i++) peak = Math.Max(peak, SpringEase.Evaluate(i / 200.0, 0.32));
        Check("yay(0.32) hedefi asiyor", peak is > 1.01 and < 1.15, $"{peak:F3}");

        // EasingFunctionBase baglantisi: varsayilan EaseOut modunda egrinin kendisi cikmali.
        var ease = new SpringEase { Bounce = 0.15 };
        var wired = true;
        for (var i = 0; i <= 10; i++)
        {
            var t = i / 10.0;
            if (Math.Abs(ease.Ease(t) - SpringEase.Evaluate(t, 0.15)) > 1e-9) wired = false;
        }

        Check("SpringEase EaseOut modunda yay egrisini veriyor", wired);
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

    /// <summary>
    /// Linux sunucu gibi davranan alici: sira disi parcalari bekletir, siradaki veri gelince
    /// ekler; zaten alinmis konumlara dusen baytlari (ortusme) atar. Teslim edilen akisi dondurur.
    /// </summary>
    private static byte[] ReceiverView(IReadOnlyList<OutPacket> sent, uint isn)
    {
        var stream = new List<byte>();
        var queue = new List<(long Start, byte[] Bytes)>();

        foreach (var o in sent)
        {
            var ip = IpPacket.Parse(o.Data, o.Length);
            long start = unchecked((int)(ip.TcpSeq - isn));
            queue.Add((start, o.Data.AsSpan(ip.PayloadOffset, ip.PayloadLength).ToArray()));

            for (var progress = true; progress;)
            {
                progress = false;
                for (var i = 0; i < queue.Count; i++)
                {
                    var (s, bytes) = queue[i];
                    if (s + bytes.Length <= stream.Count) { queue.RemoveAt(i--); continue; }
                    if (s > stream.Count) continue;

                    stream.AddRange(bytes.Skip((int)(stream.Count - s)));
                    queue.RemoveAt(i);
                    progress = true;
                    break;
                }
            }
        }

        return stream.ToArray();
    }

    /// <summary>Her konum icin ILK gonderilen bayti tutan (yeniden birlestiren) bir DPI'in gordugu akis.</summary>
    private static byte[] FirstWinsView(IReadOnlyList<OutPacket> sent, uint isn)
    {
        var map = new Dictionary<long, byte>();
        foreach (var o in sent)
        {
            var ip = IpPacket.Parse(o.Data, o.Length);
            long start = unchecked((int)(ip.TcpSeq - isn));
            for (var k = 0; k < ip.PayloadLength; k++)
                map.TryAdd(start + k, o.Data[ip.PayloadOffset + k]);
        }

        var result = new List<byte>();
        for (long i = 0; map.TryGetValue(i, out var b); i++) result.Add(b);
        return result.ToArray();
    }

    /// <summary>Discord ses IP Discovery istegi (74 bayt).</summary>
    private static byte[] DiscordDiscovery(uint ssrc)
    {
        var d = new byte[74];
        BinaryPrimitives.WriteUInt16BigEndian(d, 0x0001);
        BinaryPrimitives.WriteUInt16BigEndian(d.AsSpan(2), 70);
        BinaryPrimitives.WriteUInt32BigEndian(d.AsSpan(4), ssrc);
        return d;
    }

    /// <summary>STUN Binding istegi (RFC 5389), istege bagli bos ozellik alaniyla.</summary>
    private static byte[] StunBindingRequest(int attributeBytes = 0)
    {
        var d = new byte[20 + attributeBytes];
        BinaryPrimitives.WriteUInt16BigEndian(d, 0x0001);
        BinaryPrimitives.WriteUInt16BigEndian(d.AsSpan(2), (ushort)attributeBytes);
        BinaryPrimitives.WriteUInt32BigEndian(d.AsSpan(4), 0x2112A442);
        for (var i = 8; i < 20; i++) d[i] = (byte)(i * 11);
        return d;
    }

    /// <summary>TCP/UDP saglamasini WinDivert'ten bagimsiz olarak dogrular (v4/v6).</summary>
    private static bool L4ChecksumValid(byte[] p, int len)
    {
        var v6 = p[0] >> 4 == 6;
        var l4 = v6 ? 40 : (p[0] & 0x0F) * 4;
        var protocol = v6 ? p[6] : p[9];
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
            sum += protocol;
        }
        else
        {
            AddBytes(p.AsSpan(12, 8));
            sum += protocol;
            sum += (uint)tcpLen;
        }

        AddBytes(p.AsSpan(l4, tcpLen));
        while (sum >> 16 != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (ushort)sum == 0xFFFF;
    }

    private static byte[] BuildTcp(bool v6, ushort srcPort, ushort dstPort, uint seq, uint ack, byte flags, byte ttl,
        byte[] payload, bool swapAddr = false, byte[]? dstOverride = null)
    {
        var ipHeader = v6 ? 40 : 20;
        const int tcpHeader = 20;
        var total = ipHeader + tcpHeader + payload.Length;
        var p = new byte[total];

        WriteIpHeader(p, v6, protocol: 6, ttl, total, swapAddr, dstLast: 0, srcOverride: null, dstOverride: dstOverride);

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
