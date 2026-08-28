using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Windows 11'in yerlesik yuvarlak kose ve koyu pencere cercevesi ozelliklerini kullanir.
/// AllowsTransparency yerine bunu tercih ediyoruz: seffaf pencere WPF'te donanim
/// hizlandirmasini devre disi birakip yazilim cizimine dusurur, bu da hafiflik hedefiyle celisir.
/// </summary>
internal static class NativeMethods
{
    private const int DwmwaUseImmersiveDarkMode = 20;
    private const int DwmwaWindowCornerPreference = 33;
    private const int DwmwcpRound = 2;

    [DllImport("dwmapi.dll", CharSet = CharSet.Unicode, SetLastError = false)]
    private static extern int DwmSetWindowAttribute(nint hwnd, int attribute, ref int value, int size);

    /// <summary>Pencereye Windows 11 yuvarlak kose tercihini uygular (eski surumlerde sessizce yok sayilir).</summary>
    public static void ApplyRoundedCorners(Window window)
    {
        var hwnd = GetHandle(window);
        if (hwnd == 0) return;

        var preference = DwmwcpRound;
        try
        {
            DwmSetWindowAttribute(hwnd, DwmwaWindowCornerPreference, ref preference, sizeof(int));
        }
        catch (DllNotFoundException)
        {
            // Windows 7 gibi dwmapi olmayan ortamlar: gorunum duz koseli olur, sorun degil.
        }
    }

    /// <summary>Pencere cercevesini/golgesini koyu temaya gecirir.</summary>
    public static void SetDarkTitleBar(Window window, bool dark)
    {
        var hwnd = GetHandle(window);
        if (hwnd == 0) return;

        var value = dark ? 1 : 0;
        try
        {
            DwmSetWindowAttribute(hwnd, DwmwaUseImmersiveDarkMode, ref value, sizeof(int));
        }
        catch (DllNotFoundException)
        {
            // Desteklenmeyen surum: yok say.
        }
    }

    /// <summary>
    /// Pencereyi imlecin bulundugu ekranin calisma alani icinde, tepsiye en yakin
    /// kosede konumlandirir.
    ///
    /// WPF'in Left/Top ozellikleri yerine SetWindowPos kullaniliyor: cok ekranli
    /// ve farkli DPI'li kurulumlarda DIP donusumu yanlis kosede aciyordu, cihaz
    /// pikseliyle calismak bunu tamamen atlatiyor.
    /// </summary>
    public static void PositionInWorkAreaCorner(Window window)
    {
        var hwnd = GetHandle(window);
        if (hwnd == 0) return;

        if (!GetWindowRect(hwnd, out var bounds)) return;
        var width = bounds.Right - bounds.Left;
        var height = bounds.Bottom - bounds.Top;

        if (!GetCursorPos(out var cursor)) return;

        var monitor = MonitorFromPoint(cursor, MonitorDefaultToNearest);
        var info = new MonitorInfo { Size = Marshal.SizeOf<MonitorInfo>() };
        if (!GetMonitorInfo(monitor, ref info)) return;

        var work = info.WorkArea;
        const int Margin = 12;

        // Imlec calisma alaninin hangi yarisindaysa panel o kosede acilir.
        var right = cursor.X > (work.Left + work.Right) / 2;
        var bottom = cursor.Y > (work.Top + work.Bottom) / 2;

        var x = right ? work.Right - width - Margin : work.Left + Margin;
        var y = bottom ? work.Bottom - height - Margin : work.Top + Margin;

        SetWindowPos(hwnd, HwndTopmost, x, y, 0, 0, SwpNoSize | SwpNoActivate);
    }

    private static nint GetHandle(Window window) =>
        new WindowInteropHelper(window).Handle;

    // ------------------------------------------------------------- birlikte calisma

    private const int MonitorDefaultToNearest = 2;
    private static readonly nint HwndTopmost = -1;
    private const uint SwpNoSize = 0x0001;
    private const uint SwpNoActivate = 0x0010;

    [StructLayout(LayoutKind.Sequential)]
    private struct Rect
    {
        public int Left, Top, Right, Bottom;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Point
    {
        public int X, Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MonitorInfo
    {
        public int Size;
        public Rect Monitor;
        public Rect WorkArea;
        public uint Flags;
    }

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowRect(nint hwnd, out Rect rect);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorPos(out Point point);

    [DllImport("user32.dll")]
    private static extern nint MonitorFromPoint(Point point, int flags);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetMonitorInfo(nint monitor, ref MonitorInfo info);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowPos(nint hwnd, nint insertAfter, int x, int y, int cx, int cy, uint flags);
}
