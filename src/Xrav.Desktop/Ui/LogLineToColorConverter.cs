using System.Globalization;
using System.Windows.Data;
using System.Windows.Media;

namespace Xrav.Desktop.Ui;

/// <summary>
/// Раскрашивает строку лога по источнику и уровню. Префикс ожидается в формате
/// <c>[HH:mm:ss] [source] message</c>. Возвращает <see cref="Brush"/>.
/// </summary>
public sealed class LogLineToColorConverter : IValueConverter
{
    private static readonly Brush Default = new SolidColorBrush(Color.FromRgb(0xBF, 0xC5, 0xD4));
    private static readonly Brush Xray    = new SolidColorBrush(Color.FromRgb(0x7C, 0xB6, 0xFF));
    private static readonly Brush SingBox = new SolidColorBrush(Color.FromRgb(0x18, 0xBF, 0xFF));
    private static readonly Brush Hev     = new SolidColorBrush(Color.FromRgb(0xFF, 0xD2, 0x6E));
    private static readonly Brush AppSrc  = new SolidColorBrush(Color.FromRgb(0xC4, 0xA8, 0xFF));
    private static readonly Brush ErrorBr = new SolidColorBrush(Color.FromRgb(0xFF, 0x6E, 0x88));
    private static readonly Brush WarnBr  = new SolidColorBrush(Color.FromRgb(0xFF, 0xC1, 0x6E));

    static LogLineToColorConverter()
    {
        Default.Freeze();
        Xray.Freeze();
        SingBox.Freeze();
        Hev.Freeze();
        AppSrc.Freeze();
        ErrorBr.Freeze();
        WarnBr.Freeze();
    }

    public object Convert(object value, System.Type targetType, object parameter, CultureInfo culture)
    {
        if (value is not string s) return Default;
        var lower = s.ToLowerInvariant();
        if (lower.Contains("error") || lower.Contains(" err ") || lower.Contains("[err"))
            return ErrorBr;
        if (lower.Contains("warn"))
            return WarnBr;

        // [HH:mm:ss] [source] ...
        int first = s.IndexOf(']');
        int second = first >= 0 ? s.IndexOf(']', first + 1) : -1;
        if (first >= 0 && second > first)
        {
            var src = s.Substring(first + 1, second - first - 1).Trim().Trim('[').ToLowerInvariant();
            return src switch
            {
                "xray" => Xray,
                "sing-box" or "singbox" or "sing" => SingBox,
                "hev" or "hev-socks5-tunnel" => Hev,
                "app" or "tunnel" or "wintun" => AppSrc,
                _ => Default
            };
        }
        return Default;
    }

    public object ConvertBack(object value, System.Type targetType, object parameter, CultureInfo culture)
        => throw new System.NotSupportedException();
}
