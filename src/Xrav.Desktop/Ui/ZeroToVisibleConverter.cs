using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace Xrav.Desktop.Ui;

/// <summary>Returns <see cref="Visibility.Visible"/> when the int value is 0, else Collapsed.</summary>
public sealed class ZeroToVisibleConverter : IValueConverter
{
    public object Convert(object value, System.Type targetType, object parameter, CultureInfo culture)
    {
        if (value is int i) return i == 0 ? Visibility.Visible : Visibility.Collapsed;
        return Visibility.Collapsed;
    }

    public object ConvertBack(object value, System.Type targetType, object parameter, CultureInfo culture)
        => throw new System.NotSupportedException();
}
