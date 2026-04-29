using System.Globalization;
using System.Windows.Data;
using System.Windows.Media;
using Xrav.Desktop.Services;

namespace Xrav.Desktop.Ui;

public sealed class TunnelStateToColorConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var brush = value switch
        {
            TunnelConnectionState.Connected => "#FF00D4A0",
            TunnelConnectionState.Connecting or TunnelConnectionState.Reconnecting => "#FFFFB547",
            TunnelConnectionState.Error => "#FFFF4D6D",
            _ => "#FF7A7F94"
        };
        var c = (Color)ColorConverter.ConvertFromString(brush)!;
        return new SolidColorBrush(c);
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

public sealed class TunnelStateToPowerBrushConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var grad = (RadialGradientBrush)System.Windows.Application.Current.FindResource(
            value is TunnelConnectionState.Connected or TunnelConnectionState.Connecting or TunnelConnectionState.Reconnecting
                ? "X.Brush.PowerOn"
                : "X.Brush.PowerOff");
        return grad;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

public sealed class TunnelStateMatchConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        if (value is not TunnelConnectionState st || parameter is not string p) return false;
        return p switch
        {
            "Disconnected" => st == TunnelConnectionState.Disconnected,
            "Connecting"   => st is TunnelConnectionState.Connecting or TunnelConnectionState.Reconnecting,
            "Connected"    => st == TunnelConnectionState.Connected,
            "Error"        => st == TunnelConnectionState.Error,
            "Idle"         => st is TunnelConnectionState.Disconnected or TunnelConnectionState.Error,
            _ => false
        };
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}
