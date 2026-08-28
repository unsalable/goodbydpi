using System.Diagnostics;
using System.IO;
using System.Text;
using GoodbyeDpiUI.Models;

namespace GoodbyeDpiUI.Services;

/// <summary>goodbyedpi.exe surecinin yasam dongusunu yonetir.</summary>
public sealed class GoodbyeDpiService : IDisposable
{
    private const string ProcessName = "goodbyedpi";

    /// <summary>
    /// goodbyedpi.exe "Filter activated..." satirini fflush etmeden yazar; stdout bir
    /// pipe'a baglandiginda satir blok tamponunda kalir ve zamaninda gelmez. Bu yuzden
    /// hazir olma sinyali "surec bu sure boyunca ayakta kaldi" olarak olculur - hatali
    /// arguman veya WinDivert surucu hatasinda surec bundan cok once cikar.
    /// </summary>
    private static readonly TimeSpan ReadyGrace = TimeSpan.FromMilliseconds(1200);

    private readonly object _gate = new();
    private readonly StringBuilder _output = new();
    private Process? _process;
    private CancellationTokenSource? _startCts;

    public ConnectionState State { get; private set; } = ConnectionState.Disconnected;

    /// <summary>Basarisizlikta kullaniciya gosterilecek aciklama.</summary>
    public string? LastError { get; private set; }

    public event EventHandler? StateChanged;

    // ------------------------------------------------------------------ yollar

    /// <summary>Runtime klasorunu bulur: cikti yani, proje yani ya da bir ust dizin.</summary>
    public static string? ResolveRuntimeDirectory()
    {
        var arch = Environment.Is64BitOperatingSystem ? "x86_64" : "x86";
        var baseDir = AppContext.BaseDirectory;

        string[] candidates =
        [
            Path.Combine(baseDir, "Runtime", arch),
            Path.Combine(baseDir, "..", "Runtime", arch),
            Path.Combine(baseDir, "..", "..", "..", "Runtime", arch),
            Path.Combine(baseDir, arch),
        ];

        foreach (var candidate in candidates)
        {
            var full = Path.GetFullPath(candidate);
            if (File.Exists(Path.Combine(full, "goodbyedpi.exe"))) return full;
        }

        return null;
    }

    /// <summary>Gerekli dosyalar yerinde mi? Degilse okunabilir bir hata dondurur.</summary>
    public static string? ValidateRuntime(out string? directory)
    {
        var dir = ResolveRuntimeDirectory();
        directory = dir;

        if (dir is null)
            return "goodbyedpi.exe bulunamadi. Runtime klasoru uygulamanin yaninda olmali.";

        string[] needed = Environment.Is64BitOperatingSystem
            ? ["goodbyedpi.exe", "WinDivert.dll", "WinDivert64.sys"]
            : ["goodbyedpi.exe", "WinDivert.dll", "WinDivert32.sys"];

        var missing = needed.Where(f => !File.Exists(Path.Combine(dir, f))).ToArray();
        if (missing.Length > 0)
            return $"Eksik dosya: {string.Join(", ", missing)}. Antivirus silmis olabilir.";

        return null;
    }

    public static string BuildArguments(DpiMethod method, DnsProfile dns) =>
        string.IsNullOrEmpty(dns.Arguments)
            ? method.Arguments
            : method.Arguments + " " + dns.Arguments;

    // ---------------------------------------------------------------- baslatma

    public async Task StartAsync(DpiMethod method, DnsProfile dns)
    {
        Stop();

        var error = ValidateRuntime(out var dir);
        if (error is not null || dir is null)
        {
            Fail(error ?? "Runtime klasoru bulunamadi.");
            return;
        }

        SetState(ConnectionState.Connecting);
        LastError = null;
        lock (_gate) _output.Clear();

        // Onceki calistirmadan kalmis kendi surecimizi temizle.
        KillOrphans(dir);

        Process proc;
        CancellationToken token;

        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = Path.Combine(dir, "goodbyedpi.exe"),
                Arguments = BuildArguments(method, dns),
                WorkingDirectory = dir,   // WinDivert.dll yani yerden yuklensin
                UseShellExecute = false,  // yukseltilmis ust surecten yetki devralir
                CreateNoWindow = true,    // konsol penceresi acilmasin
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };

            proc = new Process { StartInfo = psi, EnableRaisingEvents = true };
            proc.OutputDataReceived += OnOutput;
            proc.ErrorDataReceived += OnOutput;
            proc.Exited += OnExited;

            if (!proc.Start())
            {
                Fail("goodbyedpi.exe baslatilamadi.");
                return;
            }

            proc.BeginOutputReadLine();
            proc.BeginErrorReadLine();
        }
        catch (Exception ex)
        {
            Fail("Baslatma hatasi: " + ex.Message);
            return;
        }

        lock (_gate)
        {
            _process = proc;
            _startCts?.Cancel();
            _startCts = new CancellationTokenSource();
            token = _startCts.Token;
        }

        try
        {
            await Task.Delay(ReadyGrace, token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return; // arada durduruldu ya da yeniden baslatildi
        }

        lock (_gate)
        {
            if (!ReferenceEquals(_process, proc)) return;
            if (proc.HasExited) return; // OnExited zaten Fail cagirdi
        }

        SetState(ConnectionState.Connected);
    }

    public void Stop()
    {
        Process? proc;

        lock (_gate)
        {
            _startCts?.Cancel();
            _startCts = null;
            proc = _process;
            _process = null;
        }

        if (proc is not null)
        {
            proc.Exited -= OnExited;
            try
            {
                if (!proc.HasExited) proc.Kill(entireProcessTree: true);
                proc.WaitForExit(3000);
            }
            catch
            {
                // Zaten kapanmis olabilir.
            }
            finally
            {
                proc.Dispose();
            }
        }

        SetState(ConnectionState.Disconnected);
    }

    // ------------------------------------------------------------- yardimcilar

    /// <summary>Yalnizca bizim Runtime klasorumuzden calisan artik surecleri kapatir.</summary>
    private static void KillOrphans(string runtimeDir)
    {
        foreach (var p in Process.GetProcessesByName(ProcessName))
        {
            try
            {
                // Baska bir kurulumun (ornegin servis olarak kurulmus) surecine dokunma.
                var path = p.MainModule?.FileName;
                if (path is not null &&
                    path.StartsWith(runtimeDir, StringComparison.OrdinalIgnoreCase))
                {
                    p.Kill(entireProcessTree: true);
                    p.WaitForExit(2000);
                }
            }
            catch
            {
                // Erisim reddedildi ya da surec zaten kapandi: atla.
            }
            finally
            {
                p.Dispose();
            }
        }
    }

    private void OnOutput(object sender, DataReceivedEventArgs e)
    {
        if (e.Data is null) return;

        lock (_gate)
        {
            if (_output.Length < 4096) _output.AppendLine(e.Data);
        }

        // Tamponlama yuzunden genelde gec gelir, ama geldiyse hemen degerlendir.
        if (State == ConnectionState.Connecting &&
            e.Data.Contains("Filter activated", StringComparison.OrdinalIgnoreCase))
        {
            SetState(ConnectionState.Connected);
        }
    }

    private void OnExited(object? sender, EventArgs e)
    {
        if (sender is not Process p) return;

        lock (_gate)
        {
            if (!ReferenceEquals(_process, p)) return;
            _process = null;
        }

        // Beklenmedik cikis: sebebini kullaniciya goster.
        Fail(DescribeExit(p.ExitCode));
    }

    private string DescribeExit(int exitCode)
    {
        string captured;
        lock (_gate) captured = _output.ToString().Trim();

        var hint = exitCode switch
        {
            0 => "GoodbyeDPI beklenmedik sekilde kapandi.",
            1 or -1 => "WinDivert surucusu yuklenemedi. Antivirus engelliyor olabilir.",
            _ => $"GoodbyeDPI, {exitCode} cikis koduyla sonlandi.",
        };

        return string.IsNullOrEmpty(captured) ? hint : hint + "\n" + captured;
    }

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
