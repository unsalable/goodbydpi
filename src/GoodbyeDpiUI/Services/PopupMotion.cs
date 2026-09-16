using System.Windows;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Acilir panelin (Popup) acilip kapanmasini canlandirir: kutunun kenarindan hafif
/// buyuyup yay ile oturarak acilir, kisa bir solma ve kuculmeyle kapanir.
///
/// Popup.IsOpen dogrudan IsDropDownOpen'a baglanirsa panel aninda yok olur ve kapanis
/// animasyonu hic gorunmez. Bu yuzden sablon IsOpen yerine PopupMotion.IsOpen'i baglar;
/// panel kapanis animasyonu bitince gercekten kapatilir.
///
/// Panel acikken kutu yer degistirirse (ustteki icerik buyudu, govde kaydi) panel de
/// pesinden gider; WPF Popup bunu kendiliginden yapmiyor ve havada takili kaliyordu.
/// </summary>
public static class PopupMotion
{
    public static readonly DependencyProperty IsOpenProperty = DependencyProperty.RegisterAttached(
        "IsOpen", typeof(bool), typeof(PopupMotion), new PropertyMetadata(false, OnIsOpenChanged));

    public static bool GetIsOpen(DependencyObject element) => (bool)element.GetValue(IsOpenProperty);

    public static void SetIsOpen(DependencyObject element, bool value) => element.SetValue(IsOpenProperty, value);

    private static readonly DependencyProperty StateProperty = DependencyProperty.RegisterAttached(
        "State", typeof(Motion), typeof(PopupMotion));

    private static void OnIsOpenChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not Popup popup) return;

        if (popup.GetValue(StateProperty) is not Motion motion)
        {
            motion = new Motion(popup);
            popup.SetValue(StateProperty, motion);
        }

        if ((bool)e.NewValue) motion.Open();
        else motion.Close();
    }

    private sealed class Motion(Popup popup)
    {
        private const double HiddenScale = 0.94;
        private const double ClosedScale = 0.97;
        private const double Drop = 6;

        /// <summary>Her ac/kapa istegi artar; eski istegin gecikmis geri cagrisi yok sayilir.</summary>
        private int _version;

        private ScaleTransform? _scale;
        private TranslateTransform? _shift;
        private UIElement? _target;
        private Window? _window;
        private Point _anchor;

        /// <summary>Panelin acilisdaki kutuya olan dikey mesafesi; kutu kayinca bu korunur.</summary>
        private double? _gap;

        public void Open()
        {
            var version = ++_version;

            if (popup.Child is not FrameworkElement card || !SystemParameters.ClientAreaAnimation)
            {
                if (popup.Child is FrameworkElement plain) ShowStill(plain);
                popup.IsOpen = true;
                StartTracking();
                return;
            }

            EnsureTransforms(card);
            card.IsHitTestVisible = true;

            if (popup.IsOpen)
            {
                // Kapanirken yeniden acildi: bulundugu degerlerden geri gelsin, sifirlanmasin.
                PlayOpen(card, fromHidden: false, above: card.RenderTransformOrigin.Y > 0.5);
                StartTracking();
                return;
            }

            // Ilk kare gorunmesin. Panelin kutunun altina mi ustune mi acildigi ancak
            // konum hesaplaninca belli oluyor; yon oradan secilip animasyon baslatiliyor.
            SetHidden(card);
            popup.IsOpen = true;
            StartTracking();

            card.Dispatcher.BeginInvoke(DispatcherPriority.Loaded, () =>
            {
                if (version != _version || !popup.IsOpen) return;

                var above = IsAboveTarget(card);
                card.RenderTransformOrigin = new Point(0.5, above ? 1 : 0);
                _gap = MeasureGap();
                PlayOpen(card, fromHidden: true, above);
            });
        }

        public void Close()
        {
            var version = ++_version;
            StopTracking();

            if (!popup.IsOpen) return;

            if (popup.Child is not FrameworkElement card || _scale is null || !SystemParameters.ClientAreaAnimation)
            {
                popup.IsOpen = false;
                return;
            }

            // Solarken tiklama ogeleri secmesin.
            card.IsHitTestVisible = false;

            var ease = new QuadraticEase { EasingMode = EasingMode.EaseIn };
            Animate(card, UIElement.OpacityProperty, null, 0, 150, ease, () =>
            {
                if (version == _version && !GetIsOpen(popup)) popup.IsOpen = false;
            });
            Animate(_scale, ScaleTransform.ScaleXProperty, null, ClosedScale, 150, ease);
            Animate(_scale, ScaleTransform.ScaleYProperty, null, ClosedScale, 150, ease);
        }

        private void PlayOpen(FrameworkElement card, bool fromHidden, bool above)
        {
            var spring = new SpringEase { Bounce = 0.22 };

            Animate(card, UIElement.OpacityProperty, fromHidden ? 0 : null, 1, 180,
                new QuadraticEase { EasingMode = EasingMode.EaseOut });
            Animate(_scale!, ScaleTransform.ScaleXProperty, fromHidden ? HiddenScale : null, 1, 480, spring);
            Animate(_scale!, ScaleTransform.ScaleYProperty, fromHidden ? HiddenScale : null, 1, 480, spring);
            Animate(_shift!, TranslateTransform.YProperty, fromHidden ? (above ? Drop : -Drop) : null, 0, 480, spring);
        }

        private void EnsureTransforms(FrameworkElement card)
        {
            if (_scale is not null) return;

            _scale = new ScaleTransform(1, 1);
            _shift = new TranslateTransform();
            card.RenderTransform = new TransformGroup { Children = { _scale, _shift } };
            card.RenderTransformOrigin = new Point(0.5, 0);
        }

        /// <summary>Onceki animasyonlarin tuttugu degerleri birakip baslangic konumuna al.</summary>
        private void SetHidden(FrameworkElement card)
        {
            card.BeginAnimation(UIElement.OpacityProperty, null);
            _scale!.BeginAnimation(ScaleTransform.ScaleXProperty, null);
            _scale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
            _shift!.BeginAnimation(TranslateTransform.YProperty, null);

            card.Opacity = 0;
            _scale.ScaleX = _scale.ScaleY = HiddenScale;
            _shift.Y = -Drop;
        }

        /// <summary>Animasyonlar kapaliyken panel dogrudan son haliyle gorunur.</summary>
        private void ShowStill(FrameworkElement card)
        {
            card.IsHitTestVisible = true;
            card.BeginAnimation(UIElement.OpacityProperty, null);
            card.Opacity = 1;

            if (_scale is null) return;
            _scale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
            _scale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
            _shift!.BeginAnimation(TranslateTransform.YProperty, null);
            _scale.ScaleX = _scale.ScaleY = 1;
            _shift.Y = 0;
        }

        private bool IsAboveTarget(FrameworkElement card)
        {
            var target = TargetOf(popup);
            return target is not null
                   && ScreenPosition(card) is { } cardTop
                   && ScreenPosition(target) is { } targetTop
                   && cardTop.Y < targetTop.Y;
        }

        // ------------------------------------------------------ kutuyu takip

        private void StartTracking()
        {
            StopTracking();

            _target = TargetOf(popup);
            if (_target is null) return;

            _anchor = ScreenPosition(_target) ?? default;
            _target.LayoutUpdated += OnAnchorMaybeMoved;

            // Pencere buyuyunce ekran altina tasmasin diye yukari itilebiliyor (MainWindow.OnSizeChanged);
            // bu bir yerlesim degisikligi sayilmadigindan ayrica dinleniyor.
            _window = Window.GetWindow(_target);
            if (_window is not null) _window.LocationChanged += OnAnchorMaybeMoved;
        }

        private void StopTracking()
        {
            if (_target is not null) _target.LayoutUpdated -= OnAnchorMaybeMoved;
            if (_window is not null) _window.LocationChanged -= OnAnchorMaybeMoved;
            _target = null;
            _window = null;
            _gap = null;
        }

        private void OnAnchorMaybeMoved(object? sender, EventArgs e)
        {
            if (_target is null || !popup.IsOpen) return;
            if (ScreenPosition(_target) is not { } anchor || anchor == _anchor) return;

            _anchor = anchor;

            // WPF Popup konumunu yalnizca ofset ya da yerlesim degisince yeniden hesapliyor.
            var offset = popup.HorizontalOffset;
            popup.HorizontalOffset = offset + 0.01;
            popup.HorizontalOffset = offset;

            // Yeniden hesap panelin kutuya olan mesafesini birkac piksel kaydirabiliyor;
            // acilistaki mesafe korunacak sekilde duzeltiliyor.
            if (_gap is not { } wanted || MeasureGap() is not { } actual) return;

            var drift = wanted - actual;
            if (Math.Abs(drift) is > 0.5 and < 200) popup.VerticalOffset += drift;
        }

        // ------------------------------------------------------ yardimci

        /// <summary>
        /// Panel penceresinin ust kenari ile kutunun alt kenari arasindaki mesafe (DIP).
        /// Panelin kendi acilis animasyonundan etkilenmemesi icin panelin pencere kokunden olculuyor.
        /// </summary>
        private double? MeasureGap()
        {
            if (_target is null || PresentationSource.FromVisual(_target) is null) return null;
            if (popup.Child is not Visual child) return null;
            if (PresentationSource.FromVisual(child)?.RootVisual is not Visual root) return null;

            var top = _target.PointFromScreen(root.PointToScreen(new Point()));
            return top.Y - _target.RenderSize.Height;
        }

        private static UIElement? TargetOf(Popup popup) =>
            popup.PlacementTarget ?? VisualTreeHelper.GetParent(popup) as UIElement;

        private static Point? ScreenPosition(Visual visual) =>
            PresentationSource.FromVisual(visual) is null ? null : visual.PointToScreen(new Point());

        private static void Animate(IAnimatable target, DependencyProperty property, double? from, double to,
            int milliseconds, IEasingFunction easing, Action? completed = null)
        {
            var animation = new DoubleAnimation
            {
                From = from,
                To = to,
                Duration = TimeSpan.FromMilliseconds(milliseconds),
                EasingFunction = easing,
            };

            // Saat olusurken olay isleyicileri kopyalanir; BeginAnimation'dan once eklenmeli.
            if (completed is not null) animation.Completed += (_, _) => completed();

            target.BeginAnimation(property, animation, HandoffBehavior.SnapshotAndReplace);
        }
    }
}
