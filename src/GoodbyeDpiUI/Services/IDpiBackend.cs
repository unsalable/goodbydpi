using GoodbyeDpiUI.Models;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Bir DPI atlatma altyapisinin ortak sozlesmesi. Iki uygulamasi var:
/// hazir <see cref="GoodbyeDpiService"/> ve kendi <see cref="NativeDpiService"/>
/// motorumuz. <see cref="DpiController"/> secilen ayara gore birini kullanir.
/// </summary>
public interface IDpiBackend : IDisposable
{
    ConnectionState State { get; }

    /// <summary>Basarisizlikta kullaniciya gosterilecek aciklama.</summary>
    string? LastError { get; }

    event EventHandler? StateChanged;

    /// <summary>Verilen yontem/DNS ile baglantiyi baslatir.</summary>
    Task StartAsync(EngineRequest request);

    void Stop();
}

/// <summary>Baglantiyi baslatmak icin gereken tum secenekler.</summary>
public sealed record EngineRequest(
    DpiMethod GoodbyeMethod,
    NativeDpiConfig NativeConfig,
    DnsProfile Dns);
