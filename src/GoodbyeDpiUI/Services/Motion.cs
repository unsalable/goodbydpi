using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Sablonlarda tekrar eden gecisler icin ekli ozellikler: solma, basma/uzerine gelme
/// olcegi, donme ve beliris. Hepsi ayni sure ve egrileri kullanir; boylece arayuzun
/// her yeri ayni "fizikle" hareket eder.
///
/// Neden Setter degil: WPF temadaki muhurlu fircalarin rengini animasyonlayamiyor.
/// Renk degisimleri, ustte duran bir katmanin opakligi soldurularak yapiliyor
/// (ornegin hover halkasi, secili arka plan).
///
/// Oge henuz yuklenmemisse (ilk cizim) ya da Windows'ta animasyon efektleri kapaliysa
/// deger aninda uygulanir: acilista her anahtar kendi yerine "kayarak" gelmez.
/// </summary>
public static class Motion
{
    private static readonly Duration FadeInDuration = TimeSpan.FromMilliseconds(160);
    private static readonly Duration FadeOutDuration = TimeSpan.FromMilliseconds(260);

    // Paylasilan, dondurulmus egriler: her animasyonda yeniden olusturulmuyor.
    private static readonly IEasingFunction EaseOut = Frozen(new QuadraticEase { EasingMode = EasingMode.EaseOut });
    private static readonly IEasingFunction FadeOutEase = Frozen(new QuadraticEase { EasingMode = EasingMode.EaseIn });
    private static readonly IEasingFunction PressEase = Frozen(new CubicEase { EasingMode = EasingMode.EaseOut });
    private static readonly IEasingFunction ReleaseSpring = Frozen(new SpringEase { Bounce = 0.4 });
    private static readonly IEasingFunction HoverSpring = Frozen(new SpringEase { Bounce = 0.15 });
    private static readonly IEasingFunction TurnSpring = Frozen(new SpringEase { Bounce = 0.2 });
    private static readonly IEasingFunction SettleSpring = Frozen(new SpringEase());

    /// <summary>Animasyon oynatilacak mi: oge yuklu ve Windows animasyonlari acik.</summary>
    public static bool IsLive(DependencyObject element) =>
        SystemParameters.ClientAreaAnimation && element is FrameworkElement { IsLoaded: true };

    // ================================================================ Show

    /// <summary>true: oge solarak belirir (opaklik 1), false: solarak kaybolur (0).</summary>
    public static readonly DependencyProperty ShowProperty = DependencyProperty.RegisterAttached(
        "Show", typeof(bool), typeof(Motion), new PropertyMetadata(false, OnShowChanged));

    public static bool GetShow(DependencyObject element) => (bool)element.GetValue(ShowProperty);

    public static void SetShow(DependencyObject element, bool value) => element.SetValue(ShowProperty, value);

    /// <summary>
    /// Show'un tersi: true iken oge solarak kaybolur. Show ile birlikte capraz gecis icin
    /// (ornegin normal ve secili yazi rengi).
    /// </summary>
    public static readonly DependencyProperty HideProperty = DependencyProperty.RegisterAttached(
        "Hide", typeof(bool), typeof(Motion), new PropertyMetadata(false, OnShowChanged));

    public static bool GetHide(DependencyObject element) => (bool)element.GetValue(HideProperty);

    public static void SetHide(DependencyObject element, bool value) => element.SetValue(HideProperty, value);

    // Varsayilan (false) degerde geri cagri gelmez: Show kullanan oge XAML'de Opacity="0",
    // Hide kullanan oge varsayilan opaklikla (1) baslamali.
    private static void OnShowChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not UIElement element) return;

        var show = (bool)e.NewValue != (e.Property == HideProperty);
        Run(element, UIElement.OpacityProperty, show ? 1 : 0,
            show ? FadeInDuration : FadeOutDuration, EaseOut, live: IsLive(element));
    }

    // ============================================================ Pressed

    /// <summary>Basiliyken oge PressScale'e kuculur, birakinca yay ile geri esner.</summary>
    public static readonly DependencyProperty PressedProperty = DependencyProperty.RegisterAttached(
        "Pressed", typeof(bool), typeof(Motion), new PropertyMetadata(false, OnPressedChanged));

    public static bool GetPressed(DependencyObject element) => (bool)element.GetValue(PressedProperty);

    public static void SetPressed(DependencyObject element, bool value) => element.SetValue(PressedProperty, value);

    public static readonly DependencyProperty PressScaleProperty = DependencyProperty.RegisterAttached(
        "PressScale", typeof(double), typeof(Motion), new PropertyMetadata(0.95));

    public static double GetPressScale(DependencyObject element) => (double)element.GetValue(PressScaleProperty);

    public static void SetPressScale(DependencyObject element, double value) => element.SetValue(PressScaleProperty, value);

    private static void OnPressedChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not UIElement element) return;

        var scale = TransformsOf(element).Press;
        var pressed = (bool)e.NewValue;
        var to = pressed ? GetPressScale(element) : 1;
        var live = IsLive(element);

        // Basma hizli ve yumusak, birakma esnek: dokunmatik bir dugme gibi.
        var duration = pressed ? TimeSpan.FromMilliseconds(110) : TimeSpan.FromMilliseconds(560);
        var ease = pressed ? PressEase : ReleaseSpring;
        Run(scale, ScaleTransform.ScaleXProperty, to, duration, ease, live);
        Run(scale, ScaleTransform.ScaleYProperty, to, duration, ease, live);
    }

    // ============================================================ Hovered

    /// <summary>Imlec ustundeyken oge HoverScale'e buyur.</summary>
    public static readonly DependencyProperty HoveredProperty = DependencyProperty.RegisterAttached(
        "Hovered", typeof(bool), typeof(Motion), new PropertyMetadata(false, OnHoveredChanged));

    public static bool GetHovered(DependencyObject element) => (bool)element.GetValue(HoveredProperty);

    public static void SetHovered(DependencyObject element, bool value) => element.SetValue(HoveredProperty, value);

    public static readonly DependencyProperty HoverScaleProperty = DependencyProperty.RegisterAttached(
        "HoverScale", typeof(double), typeof(Motion), new PropertyMetadata(1.03));

    public static double GetHoverScale(DependencyObject element) => (double)element.GetValue(HoverScaleProperty);

    public static void SetHoverScale(DependencyObject element, double value) => element.SetValue(HoverScaleProperty, value);

    private static void OnHoveredChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not UIElement element) return;

        var scale = TransformsOf(element).Hover;
        var to = (bool)e.NewValue ? GetHoverScale(element) : 1;
        var duration = TimeSpan.FromMilliseconds((bool)e.NewValue ? 380 : 460);
        var live = IsLive(element);

        Run(scale, ScaleTransform.ScaleXProperty, to, duration, HoverSpring, live);
        Run(scale, ScaleTransform.ScaleYProperty, to, duration, HoverSpring, live);
    }

    // ============================================================ Turned

    /// <summary>true iken oge TurnAngle derece doner (acilir ok, ayar carki).</summary>
    public static readonly DependencyProperty TurnedProperty = DependencyProperty.RegisterAttached(
        "Turned", typeof(bool), typeof(Motion), new PropertyMetadata(false, OnTurnedChanged));

    public static bool GetTurned(DependencyObject element) => (bool)element.GetValue(TurnedProperty);

    public static void SetTurned(DependencyObject element, bool value) => element.SetValue(TurnedProperty, value);

    public static readonly DependencyProperty TurnAngleProperty = DependencyProperty.RegisterAttached(
        "TurnAngle", typeof(double), typeof(Motion), new PropertyMetadata(180.0));

    public static double GetTurnAngle(DependencyObject element) => (double)element.GetValue(TurnAngleProperty);

    public static void SetTurnAngle(DependencyObject element, double value) => element.SetValue(TurnAngleProperty, value);

    private static void OnTurnedChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not UIElement element) return;

        var to = (bool)e.NewValue ? GetTurnAngle(element) : 0;
        Run(TransformsOf(element).Turn, RotateTransform.AngleProperty, to,
            TimeSpan.FromMilliseconds(520), TurnSpring, IsLive(element));
    }

    // ============================================================= Reveal

    /// <summary>
    /// Oge gorunur hale geldiginde (Visibility ya da pencere gosterimi) hafifce asagidan
    /// kayip solarak belirir. Ozel ayar paneli, uyari metinleri, guncelleme bildirimi.
    /// </summary>
    public static readonly DependencyProperty RevealProperty = DependencyProperty.RegisterAttached(
        "Reveal", typeof(bool), typeof(Motion), new PropertyMetadata(false, OnRevealChanged));

    public static bool GetReveal(DependencyObject element) => (bool)element.GetValue(RevealProperty);

    public static void SetReveal(DependencyObject element, bool value) => element.SetValue(RevealProperty, value);

    private static void OnRevealChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not UIElement element) return;

        element.IsVisibleChanged -= OnRevealVisibleChanged;
        if ((bool)e.NewValue) element.IsVisibleChanged += OnRevealVisibleChanged;
    }

    private static void OnRevealVisibleChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (e.NewValue is not true || sender is not UIElement element) return;
        PlayReveal(element, offsetY: 8);
    }

    /// <summary>
    /// Ogeyi saydamdan ve offsetY kadar kaymis konumdan yerine getirir
    /// (pozitif: asagidan yukselir, negatif: yukaridan iner).
    /// </summary>
    public static void PlayReveal(UIElement element, double offsetY)
    {
        if (!SystemParameters.ClientAreaAnimation) return;

        element.BeginAnimation(UIElement.OpacityProperty,
            new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(280)) { EasingFunction = EaseOut });

        TransformsOf(element).Shift.BeginAnimation(TranslateTransform.YProperty,
            new DoubleAnimation(offsetY, 0, TimeSpan.FromMilliseconds(560)) { EasingFunction = SettleSpring });
    }

    /// <summary>Ogeyi hizla soldurur (kapanan panel icerigi).</summary>
    public static void PlayFadeOut(UIElement element, int milliseconds)
    {
        Run(element, UIElement.OpacityProperty, 0, TimeSpan.FromMilliseconds(milliseconds),
            FadeOutEase, SystemParameters.ClientAreaAnimation);
    }

    // ======================================================== RefreshText

    /// <summary>
    /// Bagli metin degistiginde yeni metin hafifce yukselerek belirir (durum satiri).
    /// Baglamada NotifyOnTargetUpdated=True olmali.
    /// </summary>
    public static readonly DependencyProperty RefreshTextProperty = DependencyProperty.RegisterAttached(
        "RefreshText", typeof(bool), typeof(Motion), new PropertyMetadata(false, OnRefreshTextChanged));

    public static bool GetRefreshText(DependencyObject element) => (bool)element.GetValue(RefreshTextProperty);

    public static void SetRefreshText(DependencyObject element, bool value) => element.SetValue(RefreshTextProperty, value);

    private static void OnRefreshTextChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not UIElement element) return;

        element.RemoveHandler(Binding.TargetUpdatedEvent, (EventHandler<DataTransferEventArgs>)OnTargetUpdated);
        if ((bool)e.NewValue) element.AddHandler(Binding.TargetUpdatedEvent, (EventHandler<DataTransferEventArgs>)OnTargetUpdated);
    }

    private static void OnTargetUpdated(object? sender, DataTransferEventArgs e)
    {
        if (e.Property != TextBlock.TextProperty || sender is not UIElement element || !IsLive(element)) return;
        PlayReveal(element, offsetY: 5);
    }

    // =========================================================== yardimci

    /// <summary>Bir ogeye ait donusumler: tek bir TransformGroup icinde, birbirini ezmeden.</summary>
    private sealed class Transforms
    {
        public readonly ScaleTransform Hover = new(1, 1);
        public readonly ScaleTransform Press = new(1, 1);
        public readonly RotateTransform Turn = new(0);
        public readonly TranslateTransform Shift = new();
    }

    private static readonly DependencyProperty TransformsProperty = DependencyProperty.RegisterAttached(
        "Transforms", typeof(Transforms), typeof(Motion));

    private static Transforms TransformsOf(UIElement element)
    {
        if (element.GetValue(TransformsProperty) is Transforms existing) return existing;

        var created = new Transforms();
        element.RenderTransform = new TransformGroup
        {
            Children = { created.Hover, created.Press, created.Turn, created.Shift },
        };

        // Olcek ve donme ogenin ortasindan olmali; sablon ayrica bir merkez verdiyse ona dokunma.
        if (element.RenderTransformOrigin == default) element.RenderTransformOrigin = new Point(0.5, 0.5);

        element.SetValue(TransformsProperty, created);
        return created;
    }

    /// <summary>Canliysa suren animasyonun bulundugu yerden hedefe gider, degilse aninda atar.</summary>
    private static void Run(IAnimatable target, DependencyProperty property, double to, Duration duration,
        IEasingFunction ease, bool live)
    {
        if (!live)
        {
            target.BeginAnimation(property, null);
            ((DependencyObject)target).SetValue(property, to);
            return;
        }

        target.BeginAnimation(property, new DoubleAnimation(to, duration) { EasingFunction = ease },
            HandoffBehavior.SnapshotAndReplace);
    }

    private static IEasingFunction Frozen(EasingFunctionBase ease)
    {
        ease.Freeze();
        return ease;
    }
}
