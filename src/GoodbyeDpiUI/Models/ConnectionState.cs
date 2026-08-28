namespace GoodbyeDpiUI.Models;

/// <summary>Arayuzun gosterdigi baglanti durumu.</summary>
public enum ConnectionState
{
    /// <summary>goodbyedpi.exe calismiyor.</summary>
    Disconnected,

    /// <summary>Surec baslatildi, "Filter activated" satiri henuz gelmedi.</summary>
    Connecting,

    /// <summary>Surec calisiyor ve filtre aktif.</summary>
    Connected,

    /// <summary>Baslatilamadi ya da beklenmedik sekilde kapandi.</summary>
    Failed
}
