namespace GoodbyeDpiUI.Models;

/// <summary>Baglantiyi hangi altyapinin kuracagi.</summary>
public enum EngineKind
{
    /// <summary>Kendi WinDivert tabanli DPI motorumuz (NativeDpiService).</summary>
    Native,

    /// <summary>Hazir goodbyedpi.exe surecini calistiran altyapi.</summary>
    GoodbyeDpi,
}
