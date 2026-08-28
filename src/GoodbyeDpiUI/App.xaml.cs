using System.Windows;
using GoodbyeDpiUI.Services;
using GoodbyeDpiUI.ViewModels;

namespace GoodbyeDpiUI;

public partial class App : Application
{
#if UITEST
    // Yalnizca -p:DefineConstants=UITEST ile derlenen dogrulama kopyasi icin.
    // Kurulu surum calisirken arayuzu test edebilmeyi saglar; yayin derlemesi
    // her zaman asagidaki tek isme dusar.
    private const string MutexName = "GoodbyeDPI-UI.SingleInstance.uitest";
#else
    private const string MutexName = "GoodbyeDPI-UI.SingleInstance.v1";
#endif

    private Mutex? _singleInstance;
    private GoodbyeDpiService? _dpi;
    private SettingsService? _settings;
    private TrayService? _tray;
    private MainViewModel? _vm;

    protected override void OnStartup(StartupEventArgs e)
    {
        // Tek ornek: ikinci kopya sessizce cikar, WinDivert cakismasi olmaz.
        _singleInstance = new Mutex(initiallyOwned: true, MutexName, out var isFirst);
        if (!isFirst)
        {
            Shutdown();
            return;
        }

        base.OnStartup(e);

        _settings = new SettingsService();
        var settings = _settings.Load();

        var theme = new ThemeService();
        theme.Initialize(settings.DarkMode);

        _dpi = new GoodbyeDpiService();
        _vm = new MainViewModel(_dpi, _settings, theme);

        var window = new MainWindow { DataContext = _vm };
        MainWindow = window;

        // Panel ilk tepsi tiklamasina kadar olusturulmaz: acilista is yapmaz.
        _tray = new TrayService(_vm, () => new TrayFlyout(_vm, ShowMainWindow, ExitApplication));
        window.Tray = _tray;

        // Gorev Zamanlayici bizi --tray ile baslatir: pencere acilmadan tepside dur.
        var startHidden = e.Args.Any(a =>
            string.Equals(a, StartupService.TrayArgument, StringComparison.OrdinalIgnoreCase));

        if (!startHidden) window.Show();

        // Kullanici ayardan otomatik baslatmayi kapatmis olabilir; kayitli durumla eslesmezse duzelt.
        SyncStartupTask(settings.RunAtStartup);

        if (settings.AutoConnect) _ = _vm.AutoConnectAsync();

#if UITEST
        // Yalnizca dogrulama derlemesi: tepsi tiklamasini taklit etmek zor oldugu
        // icin paneli --flyout argumaniyla dogrudan acabiliyoruz.
        if (e.Args.Any(a => string.Equals(a, "--flyout", StringComparison.OrdinalIgnoreCase)))
        {
            var flyout = new TrayFlyout(_vm, ShowMainWindow, ExitApplication) { AutoHide = false };
            flyout.ShowNear();
        }

        // Ayar panelini acik yakalayabilmek icin.
        if (e.Args.Any(a => string.Equals(a, "--settings", StringComparison.OrdinalIgnoreCase)))
            window.OpenSettingsPanel();

        // --themeshot <onek>: acik + koyu temayi PNG olarak kaydeder.
        var shotIndex = Array.FindIndex(e.Args, a =>
            string.Equals(a, "--themeshot", StringComparison.OrdinalIgnoreCase));
        if (shotIndex >= 0 && shotIndex + 1 < e.Args.Length)
            window.RunThemeShot(e.Args[shotIndex + 1]);
#endif
    }

    /// <summary>
    /// Kayitli tercih ile Gorev Zamanlayici'daki gercek durumu esitler.
    ///
    /// Hatalar bilerek yutulmuyor: gorev sessizce olusturulamadiginda kullanici
    /// bunu ancak bilgisayarini yeniden baslatinca fark ediyordu.
    /// </summary>
    private void SyncStartupTask(bool shouldBeEnabled)
    {
        try
        {
            if (StartupService.IsEnabled() == shouldBeEnabled) return;

            var ok = shouldBeEnabled
                ? StartupService.Enable(out var error)
                : StartupService.Disable(out error);

            if (!ok && _vm is not null)
                _vm.ReportStartupProblem(error);
        }
        catch (Exception ex)
        {
            _vm?.ReportStartupProblem(ex.Message);
        }
    }

    private void ShowMainWindow()
    {
        if (MainWindow is not MainWindow window) return;

        window.Show();

        if (window.WindowState == WindowState.Minimized)
            window.WindowState = WindowState.Normal;

        window.Activate();
        window.Focus();
    }

    private void ExitApplication()
    {
        // Cikarken GoodbyeDPI'i da kapat: arkada oksuz surec birakma.
        _dpi?.Stop();
        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _tray?.Dispose();
        _dpi?.Dispose();

        if (_singleInstance is not null)
        {
            try { _singleInstance.ReleaseMutex(); } catch { /* zaten birakilmis */ }
            _singleInstance.Dispose();
        }

        base.OnExit(e);
    }
}
