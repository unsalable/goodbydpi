using System.Runtime.InteropServices;
using GoodbyeDpiUI.Models;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Kendi DPI atlatma motorumuz. WinDivert surucusuyle yalnizca gereken paketleri
/// yakalar ve <see cref="PacketProcessor"/>'un kararlarini uygular:
///
///  - Sahte istek: ClientHello / HTTP isteginden once engelsiz bir siteye ait sahte
///    istek (dusuk TTL ya da bozuk saglama/SEQ ile) gonderilir; DPI akisi o siteye
///    ait sanip gercek istegi incelemez.
///  - Bolme: gercek istek TCP parcalarina bolunur ve ters sirada gonderilir.
///  - DNS yonlendirme: cikis DNS sorgulari secilen sunucuya yonlendirilir, yanit
///    istemcinin bekledigi adrese geri yazilir (ISS DNS kacirmasini asar).
///  - QUIC engelleme: QUIC baslangic paketleri dusurulur; uygulama TCP+TLS'e duser.
///
/// goodbyedpi.exe'den farki: ayri surec/konsol yok, tek WinDivert handle'i ve
/// yonetilen bir okuma dongusu. Yonetici yetkisi WinDivert surucusu icin sarttir.
/// </summary>
public sealed class NativeDpiService : IDpiBackend
{
    private const int MaxPacket = 0xFFFF;

    private readonly object _gate = new();
    private Thread? _worker;
    private nint _handle = WinDivert.InvalidHandle;
    private volatile bool _stopping;

    public ConnectionState State { get; private set; } = ConnectionState.Disconnected;
    public string? LastError { get; private set; }
    public event EventHandler? StateChanged;

    /// <summary>Son baslatmada kullanilan WinDivert filtresi (tani/test icin).</summary>
    public string? ActiveFilter { get; private set; }

    /// <summary>Son baslatmanin paket sayaclari (tani/test icin).</summary>
    internal PacketProcessor.EngineStats? Stats { get; private set; }

    public Task StartAsync(EngineRequest request)
    {
        Stop();

        var error = GoodbyeDpiService.ValidateRuntime(out var dir); // WinDivert dosyalari ayni klasorde
        if (error is not null || dir is null)
        {
            Fail(error ?? "Runtime klasörü bulunamadı.");
            return Task.CompletedTask;
        }

        var processor = new PacketProcessor(request.NativeConfig, request.Dns);

        SetState(ConnectionState.Connecting);
        LastError = null;
        _stopping = false;

        try
        {
            WinDivert.EnsureLoaded(dir);

            var filter = processor.BuildFilter();
            var filterError = WinDivert.ValidateFilter(filter);
            if (filterError is not null)
            {
                Fail("Filtre oluşturulamadı: " + filterError);
                return Task.CompletedTask;
            }

            var handle = WinDivert.WinDivertOpen(filter, WinDivert.LayerNetwork, 0, 0);
            if (handle == WinDivert.InvalidHandle)
            {
                Fail(DescribeOpenError(Marshal.GetLastPInvokeError()));
                return Task.CompletedTask;
            }

            ActiveFilter = filter;
            Stats = processor.Stats;

            var worker = new Thread(() => RunLoop(handle, processor))
            {
                IsBackground = true,
                Name = "NativeDpiEngine",
                // Yakalanan her paket biz geri gonderene kadar bekler: gecikme olmasin.
                Priority = ThreadPriority.AboveNormal,
            };

            lock (_gate)
            {
                _handle = handle;
                _worker = worker;
            }

            worker.Start();
            SetState(ConnectionState.Connected);
        }
        catch (DllNotFoundException)
        {
            Fail("WinDivert.dll bulunamadı. Runtime klasörü eksik olabilir.");
        }
        catch (Exception ex)
        {
            Fail("Motor başlatılamadı: " + ex.Message);
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
            // Sira onemli: once Shutdown (bekleyen Recv ERROR_NO_DATA ile doner), is
            // parcacigi bitsin, EN SON Close. Handle kapatildiktan sonra dongunun ayni
            // degeri kullanmasi baska bir nesneye yazmak demek olabilirdi.
            try { WinDivert.WinDivertShutdown(handle, WinDivert.ShutdownBoth); } catch { /* zaten kapali */ }

            if (worker is not null && worker.IsAlive && !ReferenceEquals(worker, Thread.CurrentThread))
                worker.Join(3000);

            try { WinDivert.WinDivertClose(handle); } catch { /* zaten kapali */ }
        }

        SetState(ConnectionState.Disconnected);
    }

    // --------------------------------------------------------- okuma dongusu

    private void RunLoop(nint handle, PacketProcessor processor)
    {
        var packet = new byte[MaxPacket];
        var output = new List<OutPacket>(4);
        var addr = new WinDivertAddress();
        var errors = 0;

        while (!_stopping)
        {
            if (!WinDivert.WinDivertRecv(handle, packet, MaxPacket, out var len, ref addr))
            {
                var err = Marshal.GetLastPInvokeError();
                if (_stopping || err is ErrorNoData or ErrorOperationAborted or ErrorInvalidHandle) break;

                // Gecici hata (orn. cok buyuk paket): donguyu bozma ama CPU'yu da yakma.
                if (++errors > 16) Thread.Sleep(10);
                continue;
            }

            errors = 0;
            if (len == 0) continue;

            try
            {
                var verdict = processor.Process(packet, (int)len, addr.Outbound, output, Environment.TickCount64);

                switch (verdict)
                {
                    case PacketVerdict.Pass:
                        Send(handle, packet, (int)len, ref addr);
                        break;

                    case PacketVerdict.Replace:
                        foreach (var o in output) SendOut(handle, o, addr);
                        break;

                    case PacketVerdict.Drop:
                        break;
                }
            }
            catch
            {
                // Tek bir paket islenirken hata olursa baglantiyi kesme: paketi oldugu
                // gibi gecir, motor ayakta kalsin. (Replace'de tampon degismis olabilir;
                // yine de dusurmekten iyidir - TCP yeniden gonderir.)
                Send(handle, packet, (int)len, ref addr);
            }
        }
    }

    private void SendOut(nint handle, in OutPacket o, WinDivertAddress addr)
    {
        PrepareForSend(o, ref addr);
        Send(handle, o.Data, o.Length, ref addr);
    }

    /// <summary>Gonderimden once saglamalari hesaplar (ve istenirse TCP saglamasini bozar).</summary>
    internal static void PrepareForSend(in OutPacket o, ref WinDivertAddress addr)
    {
        if (!o.Recalc) return;

        WinDivert.WinDivertHelperCalcChecksums(o.Data, (uint)o.Length, ref addr, 0);

        // Once dogru saglama, sonra bozma (GoodbyeDPI ile ayni): adres bayraklari
        // "gecerli" kalir, surucu/NIC saglamayi yeniden hesaplayip duzeltmez.
        if (o.CorruptTcpChecksum)
            IpPacket.Parse(o.Data, o.Length).CorruptTcpChecksum(o.Data);
    }

    private void Send(nint handle, byte[] packet, int len, ref WinDivertAddress addr)
    {
        if (_stopping) return;
        WinDivert.WinDivertSend(handle, packet, (uint)len, out _, ref addr);
    }

    // ------------------------------------------------------------- yardimci

    private const int ErrorAccessDenied = 5;
    private const int ErrorInvalidHandle = 6;
    private const int ErrorNoData = 232;
    private const int ErrorOperationAborted = 995;

    private static string DescribeOpenError(int code) => code switch
    {
        ErrorAccessDenied => "Erişim reddedildi. Uygulamayı yönetici olarak çalıştırın.",
        2 => "WinDivert sürücüsü bulunamadı (WinDivert64.sys eksik olabilir).",
        577 => "WinDivert sürücüsü imza doğrulamasından geçemedi.",
        1275 => "WinDivert sürücüsü engellendi. Antivirüs/güvenlik yazılımını kontrol edin.",
        _ => $"WinDivert açılamadı (hata {code}).",
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
