using System;
using System.Collections.Concurrent;
using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media.Imaging;

namespace Xrav.Desktop.Ui;

/// <summary>
/// Конвертирует ISO-3166-1 alpha-2 код страны (например, "FI", "RU") в BitmapImage
/// флага из ресурсов Assets/Flags/{CC}.png. Если код не распознан или ресурс
/// отсутствует — возвращает null (вызывающая сторона должна показать fallback).
/// </summary>
public sealed class CountryFlagImageConverter : IValueConverter
{
    private static readonly ConcurrentDictionary<string, BitmapImage?> Cache = new();

    public object? Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is not string s || s.Length != 2) return null;
        var code = s.ToUpperInvariant();
        if (code[0] < 'A' || code[0] > 'Z' || code[1] < 'A' || code[1] > 'Z') return null;
        return Cache.GetOrAdd(code, c =>
        {
            try
            {
                var uri = new Uri($"pack://application:,,,/Assets/Flags/{c}.png", UriKind.Absolute);
                var img = new BitmapImage();
                img.BeginInit();
                img.UriSource = uri;
                img.CacheOption = BitmapCacheOption.OnLoad;
                img.EndInit();
                img.Freeze();
                return img;
            }
            catch
            {
                return null;
            }
        });
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        => throw new NotSupportedException();
}
