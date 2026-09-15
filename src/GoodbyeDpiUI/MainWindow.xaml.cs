using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using GoodbyeDpiUI.Services;
using GoodbyeDpiUI.ViewModels;

namespace GoodbyeDpiUI;

public partial class MainWindow : Window
{
    /// <summary>Pencerenin tasarim olarak en fazla uzayacagi yukseklik (DIP).</summary>
    private const double DesignMaxHeight = 820;

    /// <summary>Calisma alani kenarlarindan birakilan bosluk (DIP).</summary>
    private const double ScreenMargin = 16;

    private bool _hintShown;

    public MainWindow()
    {
        InitializeComponent();

        SourceInitialized += OnSourceInitialized;
        IsVisibleChanged += OnIsVisibleChanged;
        DpiChanged += (_, _) => UpdateMaxHeight();
        LocationChanged += (_, _) => UpdateMaxHeight();
        SizeChanged += OnSizeChanged;
        PreviewKeyDown += OnPreviewKeyDown;

        SettingsToggle.Checked += (_, _) => OpenSettingsPanel();
        SettingsToggle.Unchecked += (_, _) => CloseSettingsPanel();
    }

#if UITEST
    /// <summary>Gorsel dogrulama derlemesinde ayar panelini acar.</summary>
    public void OpenSettingsOnStart() =>
        ContentRendered += (_, _) => SettingsToggle.IsChecked = true;

    /// <summary>
    /// Gorsel dogrulama: ayarlar acikken sirayla Ozel profile gecer, asagi kaydirir,
    /// geri Varsayilan'a doner ve her adimda pencere olcusu + goruntu kaydeder.
    /// Bu, "Ozel secince pencere asagi uzuyor / kaydirilamiyor / bos alan kaliyor"
    /// hatasinin regresyon kontroludur.
    /// </summary>
    public void RunLayoutShot(string prefix)
    {
        ContentRendered += async (_, _) =>
        {
            var log = new System.Text.StringBuilder();

            async Task Step(string name, int delayMs)
            {
                await Task.Delay(delayMs);
                UpdateLayout();
                SaveRender($"{prefix}-{name}.png");
                log.AppendLine($"{name}: pencere={ActualHeight:F0} max={MaxHeight:F0} " +
                               $"kaydirilabilir={BodyScroll.ScrollableHeight:F0} ofset={BodyScroll.VerticalOffset:F0} " +
                               $"panel={SettingsPanel.ActualHeight:F0} panelYerel={SettingsPanel.Height}");
            }

            await Step("1-kapali", 700);

            SettingsToggle.IsChecked = true;
            await Step("2-ayarlar", 900);

            if (Vm is { } vm)
            {
                // Saglayici secimi yontem listesini, onerilen yontemi ve DNS'i birlikte degistirmeli.
                vm.SelectedIsp = Models.IspProfile.TurkTelekom;
                await Step("2b-turktelekom", 900);
                log.AppendLine($"saglayici: {vm.SelectedIsp.Name}, yontem: {vm.SelectedNativeProfile.Name}, " +
                               $"GoodbyeDPI: {vm.SelectedMethod.Name}, DNS: {vm.SelectedDns.Name}, " +
                               $"liste: {string.Join(" | ", vm.NativeProfiles.Select(p => p.Name))}");
                log.AppendLine($"ozet: {vm.ProfileSummary}");

                vm.SelectedNativeProfile = Models.NativeProfile.Custom;
                await Step("3-ozel", 900);

                // Imlec hap listesinin ustundeyken tekerlek govdeyi kaydirmali.
                BodyScroll.ScrollToTop();
                await Task.Delay(150);
                var pills = FindDescendant<ListBox>(SettingsPanel);
                pills?.RaiseEvent(new MouseWheelEventArgs(Mouse.PrimaryDevice, Environment.TickCount, -360)
                {
                    RoutedEvent = UIElement.PreviewMouseWheelEvent,
                });
                await Task.Delay(450);
                log.AppendLine($"tekerlek-hap-ustunde: ofset 0 -> {BodyScroll.VerticalOffset:F0} (liste bulundu: {pills is not null})");

                BodyScroll.ScrollToEnd();
                await Step("4-ozel-alt", 500);

                vm.SelectedNativeProfile = vm.SelectedIsp.Recommended;
                await Step("5-varsayilana-donus", 900);

                vm.SelectedIsp = Models.IspProfile.General;
                await Step("5b-genel", 700);
                log.AppendLine($"genele donus: yontem {vm.SelectedNativeProfile.Name}, DNS {vm.SelectedDns.Name}");
            }

            SettingsToggle.IsChecked = false;
            await Step("6-kapandi", 900);

            System.IO.File.WriteAllText(prefix + "-olcu.txt", log.ToString());
            Application.Current.Shutdown();
        };
    }

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

    private static T? FindDescendant<T>(DependencyObject root) where T : DependencyObject
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
        {
            var child = VisualTreeHelper.GetChild(root, i);
            if (child is T match) return match;
            if (FindDescendant<T>(child) is { } nested) return nested;
        }

        return null;
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
        UpdateMaxHeight();

        if (Vm is { } vm)
        {
            vm.PropertyChanged += (_, args) =>
            {
                switch (args.PropertyName)
                {
                    // Tema degistikce pencere cercevesini de guncelle.
                    case nameof(MainViewModel.IsDark):
                        ApplyTitleBarTheme();
                        break;

                    // Ozel panel acildiginda kullanici onu aramak zorunda kalmasin.
                    case nameof(MainViewModel.ShowCustomNative) when vm.ShowCustomNative:
                        RevealWhenLaidOut(CustomNativePanel);
                        break;

                    case nameof(MainViewModel.ShowCustomDns) when vm.ShowCustomDns:
                        RevealWhenLaidOut(CustomDnsPanel);
                        break;
                }
            };
        }
    }

    // ------------------------------------------------------ yukseklik / kaydirma

    /// <summary>
    /// Pencerenin bulundugu ekranin calisma alanina gore azami yuksekligi ayarlar.
    /// SizeToContent="Height" ile pencere icerige gore uzar; bu sinir asilinca
    /// uzamayi birakir ve govde kaydirilir (onceden ekranin altina tasiyordu).
    /// </summary>
    private void UpdateMaxHeight()
    {
        if (GetWorkArea() is not { } work) return;

        var max = Math.Min(DesignMaxHeight, work.Height - 2 * ScreenMargin);
        max = Math.Max(max, 360);

        if (Math.Abs(MaxHeight - max) > 0.5) MaxHeight = max;
    }

    /// <summary>Pencere asagi dogru buyudugunde alt kenar ekran disina tasmasin.</summary>
    private void OnSizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (!e.HeightChanged || WindowState != WindowState.Normal) return;
        if (GetWorkArea() is not { } work) return;

        var bottomLimit = work.Bottom - ScreenMargin;
        if (Top + ActualHeight > bottomLimit)
            Top = Math.Max(work.Top + ScreenMargin, bottomLimit - ActualHeight);
    }

    /// <summary>Pencerenin bulundugu monitorun calisma alani (DIP).</summary>
    private Rect? GetWorkArea()
    {
        var device = NativeMethods.GetWorkAreaPixels(this);
        if (device is not { } px) return SystemParameters.WorkArea;

        var source = PresentationSource.FromVisual(this);
        if (source?.CompositionTarget is null) return SystemParameters.WorkArea;

        var m = source.CompositionTarget.TransformFromDevice;
        return new Rect(m.Transform(px.TopLeft), m.Transform(px.BottomRight));
    }

    private void RevealWhenLaidOut(FrameworkElement element) =>
        Dispatcher.BeginInvoke(DispatcherPriority.Loaded, () =>
        {
            if (element.IsVisible) element.BringIntoView();
        });

    // Fare tekerlegi: yumusak kaydirma. Hedef ofset biriktirilir, kisa bir animasyonla gidilir.

    private double _scrollTarget;
    private double _lastAnimatedOffset = double.NaN;

    private static readonly DependencyProperty AnimatedOffsetProperty = DependencyProperty.Register(
        "AnimatedOffset", typeof(double), typeof(MainWindow),
        new PropertyMetadata(0.0, (d, e) =>
        {
            var w = (MainWindow)d;
            w._lastAnimatedOffset = (double)e.NewValue;
            w.BodyScroll.ScrollToVerticalOffset((double)e.NewValue);
        }));

    private void BodyScroll_PreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        if (BodyScroll.ScrollableHeight <= 0) return;

        e.Handled = true;

        // Kullanici arada cubugu surukleyip ya da BringIntoView ile yer degistirdiyse
        // hedefi oradan baslat.
        var current = BodyScroll.VerticalOffset;
        if (double.IsNaN(_lastAnimatedOffset) || Math.Abs(current - _lastAnimatedOffset) > 1)
            _scrollTarget = current;

        _scrollTarget = Math.Clamp(_scrollTarget - e.Delta * 0.45, 0, BodyScroll.ScrollableHeight);

        if (Vm?.AreAnimationsEnabled == false || SystemParameters.ClientAreaAnimation == false)
        {
            BodyScroll.ScrollToVerticalOffset(_scrollTarget);
            _lastAnimatedOffset = _scrollTarget;
            return;
        }

        BeginAnimation(AnimatedOffsetProperty, new DoubleAnimation
        {
            From = current,
            To = _scrollTarget,
            Duration = TimeSpan.FromMilliseconds(160),
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
        }, HandoffBehavior.SnapshotAndReplace);
    }

    private void BodyScroll_ScrollChanged(object sender, ScrollChangedEventArgs e) =>
        TitleDivider.Opacity = BodyScroll.VerticalOffset > 0.5 ? 1 : 0;

    /// <summary>Sayi/adres kutularinda Enter degeri hemen uygular (odak kaybini beklemez).</summary>
    private void OnPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter || e.OriginalSource is not TextBox box) return;

        BindingOperations.GetBindingExpression(box, TextBox.TextProperty)?.UpdateSource();
        box.SelectAll();
        e.Handled = true;
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

    /// <summary>
    /// Paneli olculen yukseklige kadar acar; animasyon bitince yukseklik sabit
    /// degerden OTOMATIGE (NaN) doner.
    ///
    /// Eskiden animasyonun son degeri kalici kaliyordu: acilistan sonra "Ozel"
    /// secilince yeni satirlar kirpiliyor, geri donunce de altta bos alan kaliyordu.
    /// </summary>
    private void OpenSettingsPanel()
    {
        var animation = new DoubleAnimation
        {
            From = SettingsPanel.ActualHeight,
            To = MeasureSettingsContent(),
            Duration = TimeSpan.FromMilliseconds(260),
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut },
        };

        animation.Completed += (_, _) =>
        {
            if (SettingsToggle.IsChecked != true) return;
            SettingsPanel.BeginAnimation(HeightProperty, null);
            SettingsPanel.Height = double.NaN;
        };

        SettingsPanel.BeginAnimation(HeightProperty, animation);
    }

    private void CloseSettingsPanel()
    {
        var animation = new DoubleAnimation
        {
            From = SettingsPanel.ActualHeight,
            To = 0,
            Duration = TimeSpan.FromMilliseconds(240),
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut },
        };

        animation.Completed += (_, _) =>
        {
            if (SettingsToggle.IsChecked == true) return;
            SettingsPanel.BeginAnimation(HeightProperty, null);
            SettingsPanel.Height = 0;
        };

        SettingsPanel.BeginAnimation(HeightProperty, animation);
    }
}
