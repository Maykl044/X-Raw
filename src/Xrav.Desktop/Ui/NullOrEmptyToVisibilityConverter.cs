using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace Xrav.Desktop.Ui;

/// <summary>Visible если value != null, иначе Collapsed.</summary>
public sealed class NullToVisibilityCollapsedConverter : IValueConverter
{
    public object Convert(object value, System.Type targetType, object parameter, CultureInfo culture)
        => value is null ? Visibility.Collapsed : Visibility.Visible;

    public object ConvertBack(object value, System.Type targetType, object parameter, CultureInfo culture)
        => throw new System.NotSupportedException();
}

/// <summary>Visible если строка непуста и не whitespace, иначе Collapsed.</summary>
public sealed class StringToVisibilityCollapsedConverter : IValueConverter
{
    public object Convert(object value, System.Type targetType, object parameter, CultureInfo culture)
    {
        var s = value as string;
        return string.IsNullOrWhiteSpace(s) ? Visibility.Collapsed : Visibility.Visible;
    }

    public object ConvertBack(object value, System.Type targetType, object parameter, CultureInfo culture)
        => throw new System.NotSupportedException();
}
