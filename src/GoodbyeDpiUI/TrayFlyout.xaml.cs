using System.ComponentModel;
using System.Windows;
using System.Windows.Media.Animation;
using GoodbyeDpiUI.Services;

namespace GoodbyeDpiUI;

/// <summary>
/// Tepsi simgesine tiklaninca acilan panel.
///
/// WinForms ContextMenuStrip yerine gercek bir WPF penceresi: uygulamanin
/// temasini, hap secicilerini ve animasyonlarini oldugu gibi kullanabiliyor.
/// Odak kaybinda kendini gizler, kapatilmaz - her acilista yeniden olusturmak
/// yerine tek ornek tutuluyor.
/// </summary>
public partial class TrayFlyout : Window
{
    private readonly Action _showMainWindow;
    private readonly Action _exitApplication;

    /// <summary>
    /// Panel odagi bir kez aldiktan sonra odak kaybinda kapanir.
    ///
    /// Bu bayrak olmadan, gosterildigi anda gelen ilk Deactivated (odak henuz
    /// panele gecmeden baska bir pencerede kalabiliyor) paneli aninda kapatiyor
    /// ve kullanici yalniz bir titreme goruyor.
    /// </summary>
    private bool _activatedOnce;

    public TrayFlyout(object viewModel, Action showMainWindow, Action exitApplication)
    {
        InitializeComponent();

        DataContext = viewModel;
        _showMainWindow = showMainWindow;
        _exitApplication = exitApplication;

        Activated += (_, _) => _activatedOnce = true;
        Deactivated += (_, _) => { if (AutoHide && _activatedOnce) HidePanel(); };
        SourceInitialized += (_, _) => NativeMethods.ApplyRoundedCorners(this);
    }

    /// <summary>
    /// Odak kaybinda panelin kapanip kapanmayacagi. Normal kullanimda true;
    /// yalnizca gorsel dogrulama derlemesi bunu kapatiyor.
    /// </summary>
    public bool AutoHide { get; set; } = true;

    /// <summary>Paneli imlecin bulundugu kosede, calisma alani icinde gosterir.</summary>
    public void ShowNear()
    {
        if (IsVisible)
        {
            HidePanel();
            return;
        }

        // Olculeri alabilmek icin once ekran disinda gosteriyoruz.
        Left = -10000;
        Top = -10000;
        Show();
        UpdateLayout();

        NativeMethods.PositionInWorkAreaCorner(this);

        Activate();
        Focus();
        PlayEnterAnimation();
    }

    public void HidePanel()
    {
        if (!IsVisible) return;
        _activatedOnce = false;
        Hide();
    }

    /// <summary>Icerik solarak ve hafif yay esnemesiyle yukari kayarak yerine oturur.</summary>
    private void PlayEnterAnimation()
    {
        if (!SystemParameters.ClientAreaAnimation)
        {
            RootShift.BeginAnimation(System.Windows.Media.TranslateTransform.YProperty, null);
            RootShift.Y = 0;
            return;
        }

        Root.BeginAnimation(OpacityProperty, new DoubleAnimation
        {
            From = 0,
            To = 1,
            Duration = TimeSpan.FromMilliseconds(200),
            EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut },
        });

        RootShift.BeginAnimation(System.Windows.Media.TranslateTransform.YProperty, new DoubleAnimation
        {
            From = 12,
            To = 0,
            Duration = TimeSpan.FromMilliseconds(560),
            EasingFunction = new SpringEase { Bounce = 0.18 },
        });
    }

    private void Open_Click(object sender, RoutedEventArgs e)
    {
        HidePanel();
        _showMainWindow();
    }

    private void Exit_Click(object sender, RoutedEventArgs e)
    {
        HidePanel();
        _exitApplication();
    }

    /// <summary>Panel kapatilmaz, yalnizca gizlenir - uygulama tepside yasamaya devam eder.</summary>
    protected override void OnClosing(CancelEventArgs e)
    {
        e.Cancel = true;
        HidePanel();
    }
}
