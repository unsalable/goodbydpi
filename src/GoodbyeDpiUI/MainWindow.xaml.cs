using System.ComponentModel;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using GoodbyeDpiUI.Services;
using GoodbyeDpiUI.ViewModels;

namespace GoodbyeDpiUI;

public partial class MainWindow : Window
{
    private bool _hintShown;

    public MainWindow()
    {
        InitializeComponent();

        SourceInitialized += OnSourceInitialized;
        IsVisibleChanged += OnIsVisibleChanged;
        SettingsToggle.Checked += (_, _) => AnimateSettingsPanel(MeasureSettingsContent());
        SettingsToggle.Unchecked += (_, _) => AnimateSettingsPanel(0);
    }

#if UITEST
    /// <summary>Gorsel dogrulama derlemesinde ayar panelini acar.</summary>
    public void OpenSettingsPanel() =>
        ContentRendered += (_, _) => SettingsToggle.IsChecked = true;

    /// <summary>
    /// Pencereyi WPF'in kendi cizimiyle PNG'ye kaydeder.
    ///
    /// PrintWindow/CopyFromScreen gibi Win32 yollari bu pencerede eski kare
    /// dondurup yaniltici sonuc veriyordu; RenderTargetBitmap tam olarak
    /// kullanicinin gordugu agaci ciziyor.
    /// </summary>
    public void SaveRender(string path)
    {
        var dpi = VisualTreeHelper.GetDpi(this);
        var bmp = new RenderTargetBitmap(
            (int)Math.Ceiling(RootPanel.ActualWidth * dpi.DpiScaleX),
            (int)Math.Ceiling(RootPanel.ActualHeight * dpi.DpiScaleY),
            dpi.PixelsPerInchX, dpi.PixelsPerInchY, PixelFormats.Pbgra32);

        // Pencerenin kendisini ciziyoruz: RootPanel'in arka plani yok, sadece onu
        // cizmek Window.Background'i disarida birakip yaniltici sonuc veriyordu.
        bmp.Render(this);

        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bmp));
        using var fs = System.IO.File.Create(path);
        encoder.Save(fs);
    }

    /// <summary>Acik temayi kaydeder, temayi cevirir, koyu temayi kaydeder.</summary>
    public void RunThemeShot(string prefix)
    {
        ContentRendered += async (_, _) =>
        {
            await Task.Delay(700);
            SaveRender(prefix + "-1-acik.png");

            Theme_Click(this, new RoutedEventArgs());

            await Task.Delay(1200);
            SaveRender(prefix + "-2-koyu.png");

            var sb = new System.Text.StringBuilder();
            sb.AppendLine("IsDark=" + Vm?.IsDark);

            var merged = Application.Current.Resources.MergedDictionaries;
            sb.AppendLine("Birlestirilmis sozluk sayisi=" + merged.Count);
            for (var i = 0; i < merged.Count; i++)
                sb.AppendLine($"  [{i}] {merged[i].Source}");

            string[] keys =
            [
                "BgBrush", "SurfaceBrush", "SurfaceAltBrush", "StrokeBrush",
                "TextBrush", "TextMutedBrush", "AccentBrush", "TrackBrush",
            ];

            foreach (var k in keys)
            {
                var b = Application.Current.Resources[k] as System.Windows.Media.SolidColorBrush;
                sb.AppendLine($"{k}={b?.Color}");
            }

            var wb = Background as System.Windows.Media.SolidColorBrush;
            sb.AppendLine("Window.Background=" + wb?.Color);

            System.IO.File.WriteAllText(prefix + "-bitti.txt", sb.ToString());
        };
    }
#endif

    /// <summary>Tepsiye kucultme ipucunu gostermek icin App tarafindan atanir.</summary>
    public TrayService? Tray { get; set; }

    private MainViewModel? Vm => DataContext as MainViewModel;

    // -------------------------------------------------------------- pencere

    private void OnSourceInitialized(object? sender, EventArgs e)
    {
        NativeMethods.ApplyRoundedCorners(this);
        ApplyTitleBarTheme();

        // Tema degistikce pencere cercevesini de guncelle.
        if (Vm is { } vm)
        {
            vm.PropertyChanged += (_, args) =>
            {
                if (args.PropertyName == nameof(MainViewModel.IsDark)) ApplyTitleBarTheme();
            };
        }
    }

    private void ApplyTitleBarTheme() =>
        NativeMethods.SetDarkTitleBar(this, Vm?.IsDark ?? false);

    /// <summary>
    /// Tema degistirme: once mevcut goruntunun anlik kopyasi ustte dondurulur,
    /// sonra sozluk takasi altta aninda yapilir, en son ustteki kopya soldurulur.
    /// WPF muhurlu fircalari animasyonlayamadigi icin yumusaklik buradan geliyor.
    /// </summary>
    private void Theme_Click(object sender, RoutedEventArgs e)
    {
        if (Vm is not { } vm) return;

        CaptureForCrossfade();
        vm.IsDark = !vm.IsDark;
        StartCrossfade();
    }

    private void CaptureForCrossfade()
    {
        if (RootPanel.ActualWidth < 1 || RootPanel.ActualHeight < 1) return;

        try
        {
            var dpi = VisualTreeHelper.GetDpi(this);
            var snapshot = new RenderTargetBitmap(
                (int)Math.Ceiling(RootPanel.ActualWidth * dpi.DpiScaleX),
                (int)Math.Ceiling(RootPanel.ActualHeight * dpi.DpiScaleY),
                dpi.PixelsPerInchX,
                dpi.PixelsPerInchY,
                PixelFormats.Pbgra32);

            snapshot.Render(RootPanel);
            snapshot.Freeze();

            // Canvas cocugu oldugu icin boyutu kendisi almaz; burada veriyoruz.
            ThemeFade.Width = RootPanel.ActualWidth;
            ThemeFade.Height = RootPanel.ActualHeight;
            ThemeFade.Source = snapshot;
            ThemeFade.Visibility = Visibility.Visible;

            // Onceki gecisin animasyonu Opacity'yi 0'da tutuyor olabilir; onu
            // temizlemeden yerel deger yazmak etkisiz kalir.
            ThemeFade.BeginAnimation(OpacityProperty, null);
            ThemeFade.Opacity = 1;
        }
        catch
        {
            // Anlik goruntu alinamadiysa tema yine degisir, sadece gecis ani olur.
            ThemeFade.Visibility = Visibility.Collapsed;
        }
    }

    private void StartCrossfade()
    {
        if (ThemeFade.Visibility != Visibility.Visible) return;

        var fade = new DoubleAnimation
        {
            To = 0,
            Duration = TimeSpan.FromMilliseconds(280),
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut },
        };

        fade.Completed += (_, _) =>
        {
            ThemeFade.Visibility = Visibility.Collapsed;
            ThemeFade.Source = null; // anlik goruntuyu birak, bellekte tutma
        };

        ThemeFade.BeginAnimation(OpacityProperty, fade);
    }

    private void TitleBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed) DragMove();
    }

    private void Minimize_Click(object sender, RoutedEventArgs e) =>
        WindowState = WindowState.Minimized;

    /// <summary>Kapatma dugmesi uygulamayi sonlandirmaz, tepsiye kuculur.</summary>
    private void Close_Click(object sender, RoutedEventArgs e) => HideToTray();

    protected override void OnClosing(CancelEventArgs e)
    {
        // Alt+F4 ve sistem menusu de ayni davransin.
        e.Cancel = true;
        HideToTray();
    }

    private void HideToTray()
    {
        Hide();

        if (!_hintShown)
        {
            _hintShown = true;
            Tray?.ShowMinimizedHint();
        }
    }

    // ------------------------------------------------------------ animasyon

    /// <summary>
    /// Pencere gizliyken animasyonlari durdurur.
    ///
    /// Uygulama zamaninin cogunu tepside gecirecek; gorunmezken donen bir
    /// storyboard birakmak "arkada yormayan uygulama" hedefiyle celisirdi.
    /// </summary>
    private void OnIsVisibleChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (Vm is { } vm) vm.AreAnimationsEnabled = IsVisible;
    }

    /// <summary>
    /// Panelin acik yuksekligini sabitlemek yerine olcuyoruz: hap sayisi ya da
    /// etiket uzunlugu degistiginde icerik kirpilmasin.
    /// </summary>
    private double MeasureSettingsContent()
    {
        if (SettingsPanel.Child is not FrameworkElement content) return 0;

        content.Measure(new Size(SettingsPanel.ActualWidth > 0 ? SettingsPanel.ActualWidth : ActualWidth - 48,
                                 double.PositiveInfinity));

        return content.DesiredSize.Height;
    }

    private void AnimateSettingsPanel(double targetHeight)
    {
        var animation = new DoubleAnimation
        {
            To = targetHeight,
            Duration = TimeSpan.FromMilliseconds(260),
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut },
        };

        SettingsPanel.BeginAnimation(HeightProperty, animation);
    }
}
