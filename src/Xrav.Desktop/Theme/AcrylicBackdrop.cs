using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;

namespace Xrav.Desktop.Theme;

/// <summary>
/// Включает «настоящий» системный backdrop (Mica / Acrylic) на Windows 11+.
/// На более старых ОС безопасно ничего не делает — будет использован обычный
/// градиентный фон окна.
/// </summary>
public static class AcrylicBackdrop
{
    public enum Backdrop
    {
        Auto,
        MicaAlt,
        Acrylic,
        Mica,
        None
    }

    private const int DWMWA_SYSTEMBACKDROP_TYPE = 38;
    private const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;

    [DllImport("dwmapi.dll", PreserveSig = false)]
    private static extern void DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);

    public static void Apply(Window window, Backdrop kind = Backdrop.Auto, bool dark = true)
    {
        if (window is null) return;

        // Windows 11 Build 22000+ ; mica/acrylic backdrop требует 22621+.
        var os = Environment.OSVersion.Version;
        var build = os.Build;
        if (os.Major < 10) return; // не Windows 10/11.

        var helper = new WindowInteropHelper(window);
        var hwnd = helper.EnsureHandle();
        if (hwnd == IntPtr.Zero) return;

        // 1) Включаем "immersive dark mode" чтобы рамка/заголовок брали тёмную палитру.
        try
        {
            int useDark = dark ? 1 : 0;
            DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, ref useDark, sizeof(int));
        }
        catch { /* ignore */ }

        if (build < 22621)
        {
            // На 22000-22622 backdrop API нестабилен — оставим прозрачность как есть.
            return;
        }

        int backdropValue = kind switch
        {
            Backdrop.None => 1,    // DWMSBT_NONE
            Backdrop.Mica => 2,    // DWMSBT_MAINWINDOW (Mica)
            Backdrop.Acrylic => 3, // DWMSBT_TRANSIENTWINDOW (Acrylic)
            Backdrop.MicaAlt => 4, // DWMSBT_TABBEDWINDOW (Mica Alt)
            _ => 4 // Auto = MicaAlt
        };

        try
        {
            DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, ref backdropValue, sizeof(int));

            // Сделаем фон самой Window прозрачным, иначе backdrop не виден.
            window.Background = Brushes.Transparent;
            // Также прозрачный окно-композитный канал у hwnd.
            var src = HwndSource.FromHwnd(hwnd);
            if (src is not null)
                src.CompositionTarget!.BackgroundColor = Colors.Transparent;
        }
        catch { /* ignore — старая система */ }
    }
}
