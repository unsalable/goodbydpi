using System.Windows;
using System.Windows.Media.Animation;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Sonumlu yay egrisi: UIKit / SwiftUI'daki spring animasyonunun WPF karsiligi.
///
/// Varsayilan EasingMode (EaseOut) ile kullanilir. Yay duragan baslar, hizla hizlanir
/// ve hedefe yumusakca oturur; Bounce verilirse hedefi hafifce asip geri doner.
/// Egri, zarfi animasyon suresinin sonunda binde ikiye inecek sekilde olceklenir;
/// yani Duration yayin tamamen yerlestigi andir.
/// </summary>
public sealed class SpringEase : EasingFunctionBase
{
    /// <summary>Oturma esigi: zarf sure bitiminde bu degere iner.</summary>
    private const double SettleThreshold = 0.002;

    public static readonly DependencyProperty BounceProperty = DependencyProperty.Register(
        nameof(Bounce), typeof(double), typeof(SpringEase), new PropertyMetadata(0.0));

    /// <summary>
    /// 0 = hic asmadan oturur (kritik sonum). 0.15-0.3 Apple'in hafif esnemesi,
    /// 0.4 ustu belirgin ziplama. SwiftUI'daki bounce ile ayni anlamda.
    /// </summary>
    public double Bounce
    {
        get => (double)GetValue(BounceProperty);
        set => SetValue(BounceProperty, value);
    }

    // EaseOut modunda WPF 1 - EaseInCore(1 - t) hesapliyor; boylece sonuc tam yay egrisi olur.
    protected override double EaseInCore(double normalizedTime) => 1 - Evaluate(1 - normalizedTime, Bounce);

    protected override Freezable CreateInstanceCore() => new SpringEase();

    /// <summary>0..1 zamaninda yay konumu; t = 1'de tam olarak 1.</summary>
    internal static double Evaluate(double t, double bounce)
    {
        var zeta = Math.Clamp(1 - bounce, 0.1, 1);
        return Step(t, zeta) / Step(1, zeta);
    }

    private static double Step(double t, double zeta)
    {
        if (zeta >= 0.999)
        {
            // Kritik sonum: e^(-wt)(1 + wt) = esik -> w ~ 8.6
            const double omega = 8.6;
            return 1 - Math.Exp(-omega * t) * (1 + omega * t);
        }

        var root = Math.Sqrt(1 - zeta * zeta);

        // Zarf e^(-zeta w t) / root, t = 1'de esige insin.
        var omegaN = Math.Log(1 / (SettleThreshold * root)) / zeta;
        var omegaD = omegaN * root;
        var decay = Math.Exp(-zeta * omegaN * t);

        return 1 - decay * (Math.Cos(omegaD * t) + zeta / root * Math.Sin(omegaD * t));
    }
}
