using System;
using System.Collections;
using System.Globalization;
using System.Linq;
using System.Windows.Data;
using Xrav.Core.Domain;

namespace Xrav.Desktop.Ui;

/// <summary>
/// MultiBinding: [Keys, SubscriptionId] → IEnumerable&lt;VpnKey&gt; принадлежащих этой подписке.
/// Используется в Expander.ItemsSource на вкладке «Подписка».
/// </summary>
public sealed class SubscriptionKeysConverter : IMultiValueConverter
{
    public object? Convert(object[] values, Type targetType, object parameter, CultureInfo culture)
    {
        if (values.Length < 2) return Array.Empty<VpnKey>();
        if (values[0] is not IEnumerable keys) return Array.Empty<VpnKey>();
        var subId = values[1] as string;
        if (string.IsNullOrEmpty(subId)) return Array.Empty<VpnKey>();
        return keys.OfType<VpnKey>().Where(k => k.SubscriptionId == subId).ToList();
    }

    public object[] ConvertBack(object value, Type[] targetTypes, object parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}

public sealed class SubscriptionKeyCountConverter : IMultiValueConverter
{
    public object Convert(object[] values, Type targetType, object parameter, CultureInfo culture)
    {
        if (values.Length < 2) return "0";
        if (values[0] is not IEnumerable keys) return "0";
        var subId = values[1] as string;
        if (string.IsNullOrEmpty(subId)) return "0";
        return keys.OfType<VpnKey>().Count(k => k.SubscriptionId == subId).ToString();
    }

    public object[] ConvertBack(object value, Type[] targetTypes, object parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
