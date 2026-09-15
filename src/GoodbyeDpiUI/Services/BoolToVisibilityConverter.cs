using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// bool -> Visibility. ConverterParameter "invert" verilirse mantik tersine doner.
/// XAML'de gorunurlugu koda gerek kalmadan ayarlara baglamak icin.
/// </summary>
public sealed class BoolToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var flag = value is true;
        if (parameter as string == "invert") flag = !flag;
        return flag ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
