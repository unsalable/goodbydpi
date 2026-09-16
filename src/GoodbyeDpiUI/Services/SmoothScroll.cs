using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Fare tekerleginde yumusak (suzulen) kaydirma. ThinScrollViewer stili tum ince
/// kaydirma alanlarinda acar: pencere govdesi ve acilir listeler.
///
/// Her tekerlek adimi hedef ofseti biriktirir; konum her karede hedefe ustel olarak
/// yaklasir. Ard arda gelen adimlar animasyonu bastan baslatmadigi icin hiz kesilmez.
/// Kare dongusu yalnizca kayarken abonedir; durunca CPU kullanimi sifira doner.
/// </summary>
public static class SmoothScroll
{
    /// <summary>Tekerlek birimi (120) basina piksel carpani.</summary>
    private const double PixelsPerDelta = 0.45;

    /// <summary>Saniyedeki yaklasma hizi; 16 ile mesafenin %95'i ~190 ms'de alinir.</summary>
    private const double Sharpness = 16;

    public static readonly DependencyProperty IsEnabledProperty = DependencyProperty.RegisterAttached(
        "IsEnabled", typeof(bool), typeof(SmoothScroll), new PropertyMetadata(false, OnIsEnabledChanged));

    public static bool GetIsEnabled(DependencyObject element) => (bool)element.GetValue(IsEnabledProperty);

    public static void SetIsEnabled(DependencyObject element, bool value) => element.SetValue(IsEnabledProperty, value);

    private static readonly DependencyProperty StateProperty = DependencyProperty.RegisterAttached(
        "State", typeof(Glide), typeof(SmoothScroll));

    static SmoothScroll()
    {
        // ComboBox imlecin altina gelen ogeye odak veriyor; odaklanan oge de kendini gorunur
        // alana cekmek icin BringIntoView istiyor. Liste imlecin altinda suzulurken her yeni
        // oge bunu tetikleyip listeyi geri ziplatiyor ve kaydirmayi yarida kesiyordu.
        // Imlecin altindaki oge zaten gorunur; bu istegi listeye ulasmadan yut.
        EventManager.RegisterClassHandler(typeof(ComboBoxItem), FrameworkElement.RequestBringIntoViewEvent,
            new RequestBringIntoViewEventHandler(OnItemRequestBringIntoView));
    }

    private static void OnItemRequestBringIntoView(object sender, RequestBringIntoViewEventArgs e)
    {
        if (sender is ComboBoxItem { IsMouseOver: true } item && ReferenceEquals(e.TargetObject, item))
            e.Handled = true;
    }

    private static void OnIsEnabledChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not ScrollViewer viewer) return;

        viewer.PreviewMouseWheel -= OnPreviewMouseWheel;
        if ((bool)e.NewValue) viewer.PreviewMouseWheel += OnPreviewMouseWheel;
    }

    /// <summary>Hedef ofsete suzulerek gider (ornegin yeni acilan paneli gostermek icin).</summary>
    public static void ScrollTo(ScrollViewer viewer, double offset) =>
        GlideOf(viewer).MoveTo(Math.Max(offset, 0));

    /// <summary>Ogeyi gorunur alana, gerekiyorsa en az kaydirmayla suzerek getirir.</summary>
    public static void BringIntoView(ScrollViewer viewer, FrameworkElement element)
    {
        if (viewer.Content is not Visual content || !element.IsDescendantOf(content)) return;

        var bounds = element.TransformToAncestor(content).TransformBounds(new Rect(element.RenderSize));
        var target = viewer.VerticalOffset;

        if (bounds.Bottom > target + viewer.ViewportHeight) target = bounds.Bottom - viewer.ViewportHeight;
        if (bounds.Top < target) target = bounds.Top;

        if (Math.Abs(target - viewer.VerticalOffset) > 0.5) ScrollTo(viewer, target);
    }

    private static void OnPreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        var viewer = (ScrollViewer)sender;

        // Acilir liste ayri bir pencerede cizilse de olaylari mantiksal agac uzerinden
        // kutunun atalarina, yani pencere govdesine de ugruyor. Kaynak bu alanin gorsel
        // alt agacinda degilse olay ona ait degil: listenin kendi kaydirmasina birak.
        // (Eskiden govde bunu yutuyordu: liste kaymiyor, govde kayip panel yerinde kaliyordu.)
        if (e.OriginalSource is not DependencyObject source || !IsInVisualSubtree(viewer, source)) return;

        // Liste acikken imlec kutuda yakalidir ve disaridaki tekerlek de kutuya gelir.
        // Arka plan kaymasin; kayarsa acilir panel kutudan kopup havada kalir.
        if (HasOpenDropDownInside(viewer))
        {
            e.Handled = true;
            return;
        }

        if (viewer.ScrollableHeight <= 0) return;

        e.Handled = true;

        var glide = GlideOf(viewer);
        var target = glide.IsRunning ? glide.Target : viewer.VerticalOffset;
        glide.MoveTo(Math.Clamp(target - e.Delta * PixelsPerDelta, 0, viewer.ScrollableHeight));
    }

    private static Glide GlideOf(ScrollViewer viewer)
    {
        if (viewer.GetValue(StateProperty) is Glide glide) return glide;

        glide = new Glide(viewer);
        viewer.SetValue(StateProperty, glide);
        return glide;
    }

    /// <summary>Oge bu kaydirma alaninin icinde, ayni pencerede mi (acilir liste degil)?</summary>
    private static bool IsInVisualSubtree(ScrollViewer viewer, DependencyObject source)
    {
        // Metin ogeleri (Run vb.) Visual degil; en yakin gorsel ataya cik.
        while (source is not Visual and not System.Windows.Media.Media3D.Visual3D)
        {
            source = LogicalTreeHelper.GetParent(source);
            if (source is null) return false;
        }

        return viewer.IsAncestorOf(source);
    }

    private static bool HasOpenDropDownInside(ScrollViewer viewer) =>
        Mouse.Captured is ComboBox { IsDropDownOpen: true } combo && viewer.IsAncestorOf(combo);

    /// <summary>Tek bir kaydirma alaninin suzulme durumu.</summary>
    private sealed class Glide(ScrollViewer viewer)
    {
        private double _position;
        private TimeSpan _lastFrame;

        public double Target { get; private set; }
        public bool IsRunning { get; private set; }

        public void MoveTo(double target)
        {
            Target = target;

            // Animasyonlar kapaliysa (Windows > Erisilebilirlik > Animasyon efektleri) aninda git.
            if (!SystemParameters.ClientAreaAnimation)
            {
                Stop();
                viewer.ScrollToVerticalOffset(target);
                return;
            }

            // Suzulurken yeni hedef yeterli: konum ve hiz kesintisiz devam eder.
            if (IsRunning) return;

            _position = viewer.VerticalOffset;
            _lastFrame = TimeSpan.Zero;
            IsRunning = true;
            CompositionTarget.Rendering += OnFrame;
        }

        private void Stop()
        {
            if (!IsRunning) return;
            IsRunning = false;
            CompositionTarget.Rendering -= OnFrame;
        }

        private void OnFrame(object? sender, EventArgs e)
        {
            // Rendering ayni kare icin birden fazla kez gelebilir; zamani ilerlemeyeni atla.
            var now = ((RenderingEventArgs)e).RenderingTime;
            if (now == _lastFrame) return;

            var dt = _lastFrame == TimeSpan.Zero ? 1 / 60.0 : Math.Min((now - _lastFrame).TotalSeconds, 0.05);
            _lastFrame = now;

            // Kullanici arada cubugu surukleyip ofseti degistirdiyse ya da alan gizlendiyse birak.
            if (!viewer.IsVisible || Math.Abs(viewer.VerticalOffset - _position) > 2 || HasOpenDropDownInside(viewer))
            {
                Stop();
                return;
            }

            // Hedef burada kalici olarak kirpilmiyor: pencere o sirada uzuyorsa (yeni panel
            // acildi) kaydirilabilir alan buyudukce asil hedefe devam edilir.
            var goal = Math.Clamp(Target, 0, viewer.ScrollableHeight);
            _position += (goal - _position) * (1 - Math.Exp(-Sharpness * dt));

            if (Math.Abs(goal - _position) < 0.25)
            {
                _position = goal;
                Stop();
            }

            viewer.ScrollToVerticalOffset(_position);
        }
    }
}
