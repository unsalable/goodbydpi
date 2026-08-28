using System.Windows;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Acik/koyu tema sozlugunu takas eder.
///
/// Renkleri animasyonla gecirmeyi denemek bir cikmaz sokak: WPF hem
/// ResourceDictionary'e konan hem de bir Style/ControlTemplate setter'indan
/// referans verilen her Freezable'i muhurluyor, muhurlu firca da animasyon
/// kabul etmiyor. Bu yuzden takas ani yapiliyor; gecisin yumusakligini
/// MainWindow sagliyor: eski goruntunun anlik gorubtusunu ustte tutup
/// soldurarak gercek bir capraz gecis olusturuyor.
/// </summary>
public sealed class ThemeService
{
    private static readonly Uri LightUri = new("Themes/Light.xaml", UriKind.Relative);
    private static readonly Uri DarkUri = new("Themes/Dark.xaml", UriKind.Relative);

    /// <summary>App.xaml'deki birlestirilmis sozlukler icinde tema sozlugunun sirasi.</summary>
    private const int ThemeDictionaryIndex = 1;

    public bool IsDark { get; private set; }

    public void Initialize(bool dark)
    {
        IsDark = dark;
        Apply(dark);
    }

    public void Toggle() => Set(!IsDark);

    public void Set(bool dark)
    {
        if (IsDark == dark) return;
        IsDark = dark;
        Apply(dark);
    }

    private static void Apply(bool dark)
    {
        var merged = Application.Current?.Resources.MergedDictionaries;
        if (merged is null || merged.Count <= ThemeDictionaryIndex) return;

        merged[ThemeDictionaryIndex] = new ResourceDictionary
        {
            Source = dark ? DarkUri : LightUri,
        };
    }
}
