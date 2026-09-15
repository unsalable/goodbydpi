using GoodbyeDpiUI.Models;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Secilen altyapiya (<see cref="EngineKind"/>) gore kendi motorumuzu ya da hazir
/// goodbyedpi.exe altyapisini kullanir. Disariya tek bir baglanti nesnesi gibi
/// gorunur: durum ve hata her zaman o an etkin olan altyapidan gelir.
/// </summary>
public sealed class DpiController : IDisposable
{
    private readonly NativeDpiService _native = new();
    private readonly GoodbyeDpiService _goodbye = new();

    private IDpiBackend _active;

    public DpiController()
    {
        _active = _native;
        _native.StateChanged += Bubble;
        _goodbye.StateChanged += Bubble;
    }

    public ConnectionState State => _active.State;
    public string? LastError => _active.LastError;
    public event EventHandler? StateChanged;

    /// <summary>Su an etkin altyapi hangisi (durum satirinda gosterilir).</summary>
    public EngineKind ActiveEngine { get; private set; } = EngineKind.Native;

    public async Task StartAsync(EngineKind engine, EngineRequest request)
    {
        // Altyapi degistiyse eskisini durdur; iki motor ayni anda WinDivert'i tutmasin.
        var next = engine == EngineKind.GoodbyeDpi ? (IDpiBackend)_goodbye : _native;

        if (!ReferenceEquals(next, _active))
        {
            _active.Stop();
            _active = next;
            ActiveEngine = engine;
        }

        await _active.StartAsync(request);
    }

    public void Stop() => _active.Stop();

    private void Bubble(object? sender, EventArgs e)
    {
        // Yalnizca etkin altyapinin olaylarini disari yansit.
        if (ReferenceEquals(sender, _active))
            StateChanged?.Invoke(this, EventArgs.Empty);
    }

    public void Dispose()
    {
        _native.Dispose();
        _goodbye.Dispose();
    }
}
