using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using GoodbyeDpiUI.Models;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Kendi DPI atlatma motorumuz. WinDivert surucusuyle cikis trafigini yakalar ve
/// GoodbyeDPI'in kullandigi tekniklerin yonetilen (C#) bir uygulamasini calistirir:
///
///  - Sahte paket: TLS ClientHello / HTTP istegini once dusuk TTL (ya da yanlis
///    saglama/SEQ) ile bir kez gonderir; DPI bu sahte paketi gorup senkronu kaybeder,
///    dusuk TTL yuzunden paket sunucuya ulasmadan yolda olur.
///  - TLS bolme: gercek ClientHello'yu iki TCP parcasina boler (istege bagli ters
///    sirada), boylece SNI tek parcada gorunmez.
///  - DNS yonlendirme: cikis DNS sorgularini secilen sunucuya yonlendirir, donen
///    yaniti istemcinin bekledigi adrese geri yazar (ISS DNS kacirmasini asar).
///  - QUIC engelleme: cikis UDP/443 trafigini dusurur; tarayici TCP+TLS'e duser.
///
/// goodbyedpi.exe'den farki: ayri surec/konsol yok, tek WinDivert handle'i ve
/// yonetilen bir okuma dongusu. Yonetici yetkisi WinDivert surucusu icin sarttir.
/// </summary>
public sealed class NativeDpiService : IDpiBackend
{
    private const int MaxPacket = 65535;

    // WinDivertHelperCalcChecksums bayraklari (windivert.h).
    private const ulong CalcAll = 0;
    private const ulong NoTcpChecksum = 0x0008; // WINDIVERT_HELPER_NO_TCP_CHECKSUM

    private readonly object _gate = new();
    private Thread? _worker;
    private nint _handle = WinDivert.InvalidHandle;
    private volatile bool _stopping;

    // DNS geri yazma tablosu: istemci efemeral portu -> sordugu asil sunucu.
    // Cikista dst'yi bizim sunucuya cevirir, donen yanitin src'sini geri yazariz.
    private readonly Dictionary<ushort, DnsMapping> _dnsV4 = new();
    private readonly Dictionary<ushort, DnsMapping> _dnsV6 = new();

    private NativeDpiConfig _cfg = new();
    private DnsProfile _dns = DnsProfile.Off;
    private byte[]? _resolverV4;   // 4 bayt
    private byte[]? _resolverV6;   // 16 bayt

    public ConnectionState State { get; private set; } = ConnectionState.Disconnected;
    public string? LastError { get; private set; }
    public event EventHandler? StateChanged;

    public Task StartAsync(EngineRequest request)
    {
        Stop();

        var error = GoodbyeDpiService.ValidateRuntime(out var dir); // WinDivert dosyalari ayni klasorde
        if (error is not null || dir is null)
        {
            Fail(error ?? "Runtime klasoru bulunamadi.");
            return Task.CompletedTask;
        }

        _cfg = request.NativeConfig.Clone();
        _dns = request.Dns;
        PrepareResolver();

        SetState(ConnectionState.Connecting);
        LastError = null;
        _stopping = false;
        _dnsV4.Clear();
        _dnsV6.Clear();

        try
        {
            WinDivert.EnsureLoaded(dir);

            var filter = BuildFilter();
            var filterError = WinDivert.ValidateFilter(filter);
            if (filterError is not null)
            {
                Fail("Filtre olusturulamadi: " + filterError);
                return Task.CompletedTask;
            }

            var handle = WinDivert.WinDivertOpen(filter, WinDivert.LayerNetwork, 0, 0);
            if (handle == WinDivert.InvalidHandle)
            {
                Fail(DescribeOpenError(Marshal_GetLastPInvokeError()));
                return Task.CompletedTask;
            }

            lock (_gate) _handle = handle;

            _worker = new Thread(() => RunLoop(handle))
            {
                IsBackground = true,
                Name = "NativeDpiEngine",
            };
            _worker.Start();

            SetState(ConnectionState.Connected);
        }
        catch (DllNotFoundException)
        {
            Fail("WinDivert.dll bulunamadi. Runtime klasoru eksik olabilir.");
        }
        catch (Exception ex)
        {
            Fail("Motor baslatilamadi: " + ex.Message);
        }

        return Task.CompletedTask;
    }

    public void Stop()
    {
        Thread? worker;
        nint handle;

        lock (_gate)
        {
            _stopping = true;
            handle = _handle;
            _handle = WinDivert.InvalidHandle;
            worker = _worker;
            _worker = null;
        }

        if (handle != WinDivert.InvalidHandle)
        {
            // Once okuma/gonderimi durdur, sonra kapat: bekleyen Recv geri doner.
            try { WinDivert.WinDivertShutdown(handle, WinDivert.ShutdownBoth); } catch { /* zaten kapali */ }
            try { WinDivert.WinDivertClose(handle); } catch { /* zaten kapali */ }
        }

        if (worker is not null && worker.IsAlive && !ReferenceEquals(worker, Thread.CurrentThread))
            worker.Join(3000);

        SetState(ConnectionState.Disconnected);
    }

    // ------------------------------------------------------------- filtre

    private void PrepareResolver()
    {
        _resolverV4 = null;
        _resolverV6 = null;

        if (!_dns.IsActive) return;

        if (!string.IsNullOrWhiteSpace(_dns.V4Addr) &&
            IPAddress.TryParse(_dns.V4Addr, out var v4) && v4.AddressFamily == AddressFamily.InterNetwork)
            _resolverV4 = v4.GetAddressBytes();

        if (!string.IsNullOrWhiteSpace(_dns.V6Addr) &&
            IPAddress.TryParse(_dns.V6Addr, out var v6) && v6.AddressFamily == AddressFamily.InterNetworkV6)
            _resolverV6 = v6.GetAddressBytes();
    }

    /// <summary>Ayarlara gore yalnizca gereken trafigi yakalayan WinDivert filtresi.</summary>
    private string BuildFilter()
    {
        var clauses = new List<string>
        {
            // TLS ClientHello: ilk bayt 0x16 (handshake). Payload sarti CPU'yu korur.
            "(outbound and tcp.DstPort == 443 and tcp.PayloadLength > 0)",
        };

        if (_cfg.FragmentHttp)
            clauses.Add("(outbound and tcp.DstPort == 80 and tcp.PayloadLength > 0)");

        if (_cfg.BlockQuic)
            clauses.Add("(outbound and udp.DstPort == 443)");

        if (_resolverV4 is not null)
        {
            clauses.Add("(outbound and udp.DstPort == 53 and ip)");
            clauses.Add($"(inbound and udp and ip.SrcAddr == {_dns.V4Addr} and udp.SrcPort == {_dns.V4Port})");
        }

        if (_resolverV6 is not null)
        {
            clauses.Add("(outbound and udp.DstPort == 53 and ipv6)");
            clauses.Add($"(inbound and udp and ipv6.SrcAddr == {_dns.V6Addr} and udp.SrcPort == {_dns.V6Port})");
        }

        return "!impostor and !loopback and (" + string.Join(" or ", clauses) + ")";
    }

    // --------------------------------------------------------- okuma dongusu

    private void RunLoop(nint handle)
    {
        var packet = new byte[MaxPacket];
        var addr = new WinDivertAddress();

        while (!_stopping)
        {
            if (!WinDivert.WinDivertRecv(handle, packet, MaxPacket, out var len, ref addr))
            {
                if (_stopping) break;

                // Shutdown disi bir hata: dongu bozulmasin diye kisa bekleyip devam et.
                var err = Marshal_GetLastPInvokeError();
                if (err is ErrorNoData or ErrorOperationAborted) break; // handle kapandi
                continue;
            }

            if (len == 0) continue;

            try
            {
                Process(handle, packet, (int)len, ref addr);
            }
            catch
            {
                // Tek bir paket islenirken hata olursa baglantiyi kesme: paketi oldugu
                // gibi gecirmeye calis, motor ayakta kalsin.
                TrySend(handle, packet, (int)len, ref addr);
            }
        }
    }

    /// <summary>Tek bir yakalanan paketi degerlendirir ve uygun aksiyonu uygular.</summary>
    private void Process(nint handle, byte[] packet, int len, ref WinDivertAddress addr)
    {
        var ip = IpPacket.Parse(packet, len);

        if (!ip.Valid)
        {
            TrySend(handle, packet, len, ref addr);
            return;
        }

        if (addr.Outbound)
        {
            if (ip.Protocol == ProtocolTcp)
            {
                var dstPort = ip.DstPort;

                if (dstPort == 443 && LooksLikeTls(packet, ip))
                {
                    HandleTls(handle, packet, len, ref addr, ip);
                    return;
                }

                if (dstPort == 80 && _cfg.FragmentHttp && LooksLikeHttp(packet, ip))
                {
                    HandleHttpSplit(handle, packet, len, ref addr, ip);
                    return;
                }
            }
            else if (ip.Protocol == ProtocolUdp)
            {
                var dstPort = ip.DstPort;

                if (dstPort == 443 && _cfg.BlockQuic)
                    return; // QUIC: dusur, gonderme

                if (dstPort == 53 && RedirectDnsOut(packet, ip, ref addr))
                {
                    WinDivert.WinDivertHelperCalcChecksums(packet, (uint)len, ref addr, CalcAll);
                    TrySend(handle, packet, len, ref addr);
                    return;
                }
            }
        }
        else // inbound: yalnizca DNS yaniti yakalanir
        {
            if (ip.Protocol == ProtocolUdp && RewriteDnsIn(packet, ip, ref addr))
            {
                WinDivert.WinDivertHelperCalcChecksums(packet, (uint)len, ref addr, CalcAll);
                TrySend(handle, packet, len, ref addr);
                return;
            }
        }

        TrySend(handle, packet, len, ref addr);
    }

    // ------------------------------------------------------------- TLS

    private static bool LooksLikeTls(byte[] p, in IpPacket ip)
    {
        // TLS kaydi: content type 0x16 (handshake), surum 0x03 0x0X.
        var off = ip.PayloadOffset;
        if (ip.PayloadLength < 6) return false;
        return p[off] == 0x16 && p[off + 1] == 0x03;
    }

    private void HandleTls(nint handle, byte[] packet, int len, ref WinDivertAddress addr, in IpPacket ip)
    {
        // 1) Sahte paket(ler): gercekten once, DPI'i senkronundan cikarmak icin.
        if (_cfg.FakePacket)
            SendFake(handle, packet, len, ref addr, ip);

        // 2) Gercek ClientHello: istege bagli olarak iki parcaya bolunur.
        if (_cfg.SplitTls && ip.PayloadLength > 1)
        {
            var pos = ChooseSplitPosition(packet, ip);
            if (pos > 0 && pos < ip.PayloadLength)
            {
                SendSplit(handle, packet, len, ref addr, ip, pos);
                return;
            }
        }

        TrySend(handle, packet, len, ref addr);
    }

    /// <summary>Bolme konumunu secer: ayarli deger, yoksa SNI'dan hemen once.</summary>
    private int ChooseSplitPosition(byte[] p, in IpPacket ip)
    {
        if (_cfg.SplitPosition > 0) return _cfg.SplitPosition;

        var sni = TlsParser.FindSniOffset(p, ip.PayloadOffset, ip.PayloadLength);
        if (sni > 0) return sni;

        // SNI bulunamadi: kaydin ortasindan bol.
        return ip.PayloadLength / 2;
    }

    // ------------------------------------------------------------- HTTP

    private static bool LooksLikeHttp(byte[] p, in IpPacket ip)
    {
        if (ip.PayloadLength < 5) return false;

        // GET / POST / PUT / HEAD / OPTIONS / DELETE / CONNECT / TRACE ... ile baslar mi?
        return p[ip.PayloadOffset] is (byte)'G' or (byte)'P' or (byte)'H' or (byte)'D' or (byte)'O' or (byte)'C' or (byte)'T';
    }

    private void HandleHttpSplit(nint handle, byte[] packet, int len, ref WinDivertAddress addr, in IpPacket ip)
    {
        var pos = _cfg.SplitPosition > 0 ? _cfg.SplitPosition : 2;
        if (pos > 0 && pos < ip.PayloadLength)
        {
            SendSplit(handle, packet, len, ref addr, ip, pos);
            return;
        }

        TrySend(handle, packet, len, ref addr);
    }

    // ----------------------------------------------------- sahte paket

    /// <summary>
    /// Gercek isteğin bir kopyasini "sahte" olarak once gonderir. Kopya ya dusuk TTL
    /// ile (sunucuya ulasmadan yolda olur) ya da bozuk saglama/SEQ ile (sunucu atar)
    /// gonderilir; her durumda DPI onu gecerli bir ClientHello sanip senkronu kaybeder.
    /// Payload'a dokunmuyoruz: DPI'in gercek bir istek gormesi tekniğin ta kendisi.
    /// </summary>
    private void SendFake(nint handle, byte[] packet, int len, ref WinDivertAddress addr, in IpPacket ip)
    {
        var fake = new byte[len];
        Array.Copy(packet, fake, len);

        if (_cfg.FakeTtl)
            ip.SetTtl(fake, ClampTtl(_cfg.Ttl));

        if (_cfg.FakeWrongSeq)
            // SEQ'i gecmise al: sunucu eski/gecersiz sayar, ama DPI yine isler.
            ip.SetTcpSeq(fake, unchecked(ip.TcpSeq - 10000));

        var fakeAddr = addr;

        if (_cfg.FakeWrongChecksum)
        {
            // TCP saglamayi WinDivert'e hesaplatma; kasitli yanlis birak.
            WinDivert.WinDivertHelperCalcChecksums(fake, (uint)len, ref fakeAddr, NoTcpChecksum);
            ip.CorruptTcpChecksum(fake);
        }
        else
        {
            WinDivert.WinDivertHelperCalcChecksums(fake, (uint)len, ref fakeAddr, CalcAll);
        }

        TrySend(handle, fake, len, ref fakeAddr);
    }

    private static int ClampTtl(int ttl) => ttl < 1 ? 1 : ttl > 255 ? 255 : ttl;

    // ---------------------------------------------------- TCP bolme

    /// <summary>
    /// Cikis TCP payload'unu <paramref name="pos"/> ofsetinden iki ayri TCP
    /// segmentine boler. SEQ numaralari buna gore ayarlanir; istege bagli olarak
    /// ikinci parca once gonderilir (reverse-frag).
    /// </summary>
    private void SendSplit(nint handle, byte[] packet, int len, ref WinDivertAddress addr, in IpPacket ip, int pos)
    {
        var first = BuildSegment(packet, len, ip, 0, pos, out var firstLen);
        var second = BuildSegment(packet, len, ip, pos, ip.PayloadLength - pos, out var secondLen);

        var firstAddr = addr;
        var secondAddr = addr;
        WinDivert.WinDivertHelperCalcChecksums(first, (uint)firstLen, ref firstAddr, CalcAll);
        WinDivert.WinDivertHelperCalcChecksums(second, (uint)secondLen, ref secondAddr, CalcAll);

        if (_cfg.ReverseSplit)
        {
            TrySend(handle, second, secondLen, ref secondAddr);
            TrySend(handle, first, firstLen, ref firstAddr);
        }
        else
        {
            TrySend(handle, first, firstLen, ref firstAddr);
            TrySend(handle, second, secondLen, ref secondAddr);
        }
    }

    /// <summary>
    /// Orijinal paketin baslik(lar)ini koruyarak payload'un [payloadStart, +count)
    /// dilimini iceren yeni bir IP+TCP paketi olusturur. SEQ, dilim ofseti kadar artar.
    /// </summary>
    private static byte[] BuildSegment(byte[] src, int srcLen, in IpPacket ip, int payloadStart, int count, out int outLen)
    {
        var headerLen = ip.PayloadOffset;
        outLen = headerLen + count;

        var seg = new byte[outLen];
        Array.Copy(src, 0, seg, 0, headerLen);                                  // IP + TCP baslik
        Array.Copy(src, ip.PayloadOffset + payloadStart, seg, headerLen, count); // payload dilimi

        // IP toplam uzunlugu / IPv6 payload uzunlugu duzelt.
        ip.SetTotalLength(seg, outLen);

        // SEQ = orijinal SEQ + dilimin baslangic ofseti.
        ip.SetTcpSeq(seg, unchecked(ip.TcpSeq + (uint)payloadStart));

        return seg;
    }

    // ---------------------------------------------------- DNS yonlendirme

    private bool RedirectDnsOut(byte[] p, in IpPacket ip, ref WinDivertAddress addr)
    {
        if (ip.IsV6)
        {
            if (_resolverV6 is null) return false;

            var srcPort = ip.SrcPort;
            _dnsV6[srcPort] = new DnsMapping(ip.GetDstAddrBytes(), ip.DstPort);

            ip.SetDstAddr(p, _resolverV6);
            ip.SetDstPort(p, (ushort)_dns.V6Port);
            return true;
        }
        else
        {
            if (_resolverV4 is null) return false;

            var srcPort = ip.SrcPort;
            _dnsV4[srcPort] = new DnsMapping(ip.GetDstAddrBytes(), ip.DstPort);

            ip.SetDstAddr(p, _resolverV4);
            ip.SetDstPort(p, (ushort)_dns.V4Port);
            return true;
        }
    }

    private bool RewriteDnsIn(byte[] p, in IpPacket ip, ref WinDivertAddress addr)
    {
        // Yanit istemciye donuyor: hedef port = istemcinin efemeral portu.
        var clientPort = ip.DstPort;

        if (ip.IsV6)
        {
            if (!_dnsV6.Remove(clientPort, out var map)) return false;
            ip.SetSrcAddr(p, map.OrigAddr);
            ip.SetSrcPort(p, map.OrigPort);
            return true;
        }
        else
        {
            if (!_dnsV4.Remove(clientPort, out var map)) return false;
            ip.SetSrcAddr(p, map.OrigAddr);
            ip.SetSrcPort(p, map.OrigPort);
            return true;
        }
    }

    private readonly record struct DnsMapping(byte[] OrigAddr, ushort OrigPort);

    // ------------------------------------------------------------- yardimci

    private void TrySend(nint handle, byte[] packet, int len, ref WinDivertAddress addr)
    {
        if (_stopping) return;
        WinDivert.WinDivertSend(handle, packet, (uint)len, out _, ref addr);
    }

    private const int ProtocolTcp = 6;
    private const int ProtocolUdp = 17;
    private const int ErrorOperationAborted = 995;
    private const int ErrorNoData = 232;
    private const int ErrorAccessDenied = 5;

    private static int Marshal_GetLastPInvokeError() => Marshal.GetLastPInvokeError();

    private static string DescribeOpenError(int code) => code switch
    {
        ErrorAccessDenied => "Erisim reddedildi. Uygulamayi yonetici olarak calistirin.",
        2 => "WinDivert surucusu bulunamadi (WinDivert64.sys eksik olabilir).",
        577 => "WinDivert surucusu imza dogrulamasindan gecemedi.",
        1275 => "WinDivert surucusu engellendi. Antivirus/guvenlik yazilimini kontrol edin.",
        _ => $"WinDivert acilamadi (hata {code}).",
    };

    private void Fail(string message)
    {
        LastError = message;
        SetState(ConnectionState.Failed);
    }

    private void SetState(ConnectionState state)
    {
        if (State == state) return;
        State = state;
        StateChanged?.Invoke(this, EventArgs.Empty);
    }

    public void Dispose() => Stop();
}
