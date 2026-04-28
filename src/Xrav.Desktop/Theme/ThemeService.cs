using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows;
using Microsoft.Win32;

namespace Xrav.Desktop.Theme;

/// <summary>
/// Управляет цветовой темой приложения (Light/Dark/System).
/// Подменяет ResourceDictionary <c>Theme/Colors.Dark.xaml</c> ↔ <c>Theme/Colors.Light.xaml</c> в Application.Resources
/// без перезапуска. В режиме <see cref="AppTheme.System"/> следит за реестром Windows и
/// автоматически переключает палитру при смене системной темы.
/// </summary>
public sealed class ThemeService : INotifyPropertyChanged
{
    public static ThemeService Current { get; } = new();

    public enum AppTheme { Light, Dark, System }

    private const string PersonalizeKey = @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize";

    private AppTheme _appTheme = AppTheme.System;
    private bool _initialized;

    public AppTheme Theme
    {
        get => _appTheme;
        set
        {
            if (_appTheme == value) return;
            _appTheme = value;
            OnPropertyChanged();
            Apply();
        }
    }

    public bool SystemIsDark
    {
        get
        {
            try
            {
                using var k = Registry.CurrentUser.OpenSubKey(PersonalizeKey);
                if (k?.GetValue("AppsUseLightTheme") is int v) return v == 0;
            }
            catch { /* реестр недоступен */ }
            return true; // безопасный fallback — тёмная
        }
    }

    public bool EffectiveIsDark => Theme switch
    {
        AppTheme.Light => false,
        AppTheme.Dark => true,
        _ => SystemIsDark
    };

    public void Initialize(AppTheme initial)
    {
        if (_initialized) return;
        _initialized = true;
        _appTheme = initial;
        Microsoft.Win32.SystemEvents.UserPreferenceChanged += OnUserPrefChanged;
        Apply();
    }

    private void OnUserPrefChanged(object? sender, UserPreferenceChangedEventArgs e)
    {
        if (Theme == AppTheme.System && e.Category == UserPreferenceCategory.General)
            Apply();
    }

    public void Apply()
    {
        var app = Application.Current;
        if (app is null) return;
        var palettePath = EffectiveIsDark ? "Theme/Colors.Dark.xaml" : "Theme/Colors.Light.xaml";
        var paletteUri = new Uri($"pack://application:,,,/X-Rav;component/{palettePath}", UriKind.Absolute);
        var themeUri = new Uri("pack://application:,,,/X-Rav;component/Theme/XravTheme.xaml", UriKind.Absolute);

        // X.Brush.* в XravTheme.xaml резолвятся через StaticResource в момент парсинга словаря,
        // поэтому при смене палитры палитру нужно подставить ПЕРЕД XravTheme и затем перезагрузить XravTheme.
        var dicts = app.Resources.MergedDictionaries;
        for (int i = dicts.Count - 1; i >= 0; i--)
        {
            var s = dicts[i].Source?.OriginalString ?? "";
            if (s.Contains("Theme/Colors.Dark.xaml")
                || s.Contains("Theme/Colors.Light.xaml")
                || s.Contains("Theme/XravTheme.xaml"))
            {
                dicts.RemoveAt(i);
            }
        }
        dicts.Add(new ResourceDictionary { Source = paletteUri });
        dicts.Add(new ResourceDictionary { Source = themeUri });

        OnPropertyChanged(nameof(EffectiveIsDark));
    }

    public static AppTheme ParseCode(string? code) => code?.ToLowerInvariant() switch
    {
        "light" => AppTheme.Light,
        "dark" => AppTheme.Dark,
        _ => AppTheme.System
    };

    public static string Code(AppTheme t) => t switch
    {
        AppTheme.Light => "light",
        AppTheme.Dark => "dark",
        _ => "system"
    };

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name ?? string.Empty));
}
