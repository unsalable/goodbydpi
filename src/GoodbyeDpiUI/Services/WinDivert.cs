using System.IO;
using System.Runtime.InteropServices;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// WinDivert 2.2 icin yonetilen P/Invoke sarmalayici.
///
/// Yalnizca kendi native DPI motorumuzun (NativeDpiService) ihtiyac duydugu
/// fonksiyonlar var. DLL, Runtime klasorunden acikca yuklenir (bkz.
/// <see cref="EnsureLoaded"/>); WinDivert.dll surucu .sys dosyasini kendi
/// bulundugu klasorde arar, bu yuzden WinDivert64.sys de orada durmali.
/// </summary>
internal static class WinDivert
{
    public const short LayerNetwork = 0;

    // WinDivertOpen bayraklari
    public const ulong FlagSniff = 0x0001;
    public const ulong FlagDrop = 0x0002;
    public const ulong FlagRecvOnly = 0x0004;
    public const ulong FlagSendOnly = 0x0008;
    public const ulong FlagNoInstall = 0x0010;
    public const ulong FlagFragments = 0x0020;

    // WinDivertShutdown "how"
    public const uint ShutdownRecv = 0x1;
    public const uint ShutdownSend = 0x2;
    public const uint ShutdownBoth = 0x3;

    public static readonly nint InvalidHandle = -1;

    private static bool _loaded;
    private static readonly object _loadGate = new();

    /// <summary>WinDivert.dll'i verilen klasorden onceden yukler (bir kez).</summary>
    public static void EnsureLoaded(string runtimeDir)
    {
        if (_loaded) return;

        lock (_loadGate)
        {
            if (_loaded) return;

            var dll = Path.Combine(runtimeDir, "WinDivert.dll");
            // NativeLibrary.Load tam yolla yukler; sonraki DllImport("WinDivert.dll")
            // cagrilari ayni yuklu modulu ismen bulur.
            NativeLibrary.Load(dll);
            _loaded = true;
        }
    }

    [DllImport("WinDivert.dll", CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi, SetLastError = true)]
    public static extern nint WinDivertOpen(string filter, short layer, short priority, ulong flags);

    [DllImport("WinDivert.dll", CallingConvention = CallingConvention.Cdecl, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool WinDivertRecv(nint handle, byte[] packet, uint packetLen, out uint recvLen, ref WinDivertAddress addr);

    [DllImport("WinDivert.dll", CallingConvention = CallingConvention.Cdecl, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool WinDivertSend(nint handle, byte[] packet, uint packetLen, out uint sendLen, ref WinDivertAddress addr);

    [DllImport("WinDivert.dll", CallingConvention = CallingConvention.Cdecl, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool WinDivertClose(nint handle);

    [DllImport("WinDivert.dll", CallingConvention = CallingConvention.Cdecl, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool WinDivertShutdown(nint handle, uint how);

    [DllImport("WinDivert.dll", CallingConvention = CallingConvention.Cdecl, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool WinDivertHelperCalcChecksums(byte[] packet, uint packetLen, ref WinDivertAddress addr, ulong flags);

    [DllImport("WinDivert.dll", CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool WinDivertHelperCompileFilter(
        string filter, short layer, nint obj, uint objLen, out nint errorStr, out uint errorPos);

    /// <summary>Filtre metni gecerli mi? Degilse hata aciklamasi doner.</summary>
    public static string? ValidateFilter(string filter)
    {
        var ok = WinDivertHelperCompileFilter(filter, LayerNetwork, nint.Zero, 0, out var err, out var pos);
        if (ok) return null;
        var text = Marshal.PtrToStringAnsi(err) ?? "bilinmeyen hata";
        return $"{text} (konum {pos})";
    }
}

/// <summary>
/// WINDIVERT_ADDRESS (80 bayt). Bit alanlari tek bir 32-bit <see cref="Flags"/>
/// icinde toplanmis; Outbound/IPv6 gibi bayraklara yardimci ozelliklerle erisilir.
/// Birlesim (union) alani yerine ag katmani icin IfIdx/SubIfIdx + dolgu tutuluyor.
/// </summary>
[StructLayout(LayoutKind.Sequential)]
internal struct WinDivertAddress
{
    public long Timestamp;

    // Layer:8, Event:8, Sniffed:1, Outbound:1, Loopback:1, Impostor:1,
    // IPv6:1, IPChecksum:1, TCPChecksum:1, UDPChecksum:1, Reserved1:8
    public uint Flags;

    public uint Reserved2;

    // union (64 bayt): ag katmaninda ilk iki alan IfIdx / SubIfIdx.
    public uint IfIdx;
    public uint SubIfIdx;
    public ulong U0, U1, U2, U3, U4, U5, U6; // kalan 56 bayt dolgu

    private const int OutboundBit = 17;
    private const int IPv6Bit = 20;

    public bool Outbound
    {
        readonly get => (Flags & (1u << OutboundBit)) != 0;
        set => Flags = value ? Flags | (1u << OutboundBit) : Flags & ~(1u << OutboundBit);
    }

    public readonly bool IsIPv6 => (Flags & (1u << IPv6Bit)) != 0;
}
