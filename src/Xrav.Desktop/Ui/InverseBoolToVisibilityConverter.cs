using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace Xrav.Desktop.Ui;

/// <summary>Visible когда bool == false, Collapsed когда true.</summary>
public sealed class InverseBoolToVisibilityConverter : IValueConverter
{
    public object Convert(object value, System.Type targetType, object parameter, CultureInfo culture)
    {
        if (value is bool b) return b ? Visibility.Collapsed : Visibility.Visible;
        return Visibility.Visible;
    }

    public object ConvertBack(object value, System.Type targetType, object parameter, CultureInfo culture)
        => throw new System.NotSupportedException();
}
