using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace Xrav.Desktop.Ui;

/// <summary>
/// Динамический блик на Button: при наведении и перемещении курсора
/// добавляет RadialGradientBrush-оверлей, центр которого следует за мышью.
/// Имитирует "physical lighting" как в Material 4.0 / iOS.
/// </summary>
public static class CursorLight
{
    public static readonly DependencyProperty EnabledProperty =
        DependencyProperty.RegisterAttached(
            "Enabled", typeof(bool), typeof(CursorLight),
            new PropertyMetadata(false, OnEnabledChanged));

    public static void SetEnabled(DependencyObject d, bool v) => d.SetValue(EnabledProperty, v);
    public static bool GetEnabled(DependencyObject d) => (bool)d.GetValue(EnabledProperty);

    private static readonly DependencyProperty OverlayKey =
        DependencyProperty.RegisterAttached(
            "_Overlay", typeof(Border), typeof(CursorLight),
            new PropertyMetadata(null));

    private static void OnEnabledChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not Button b) return;
        if ((bool)e.NewValue)
        {
            b.MouseMove += OnMove;
            b.MouseLeave += OnLeave;
            b.MouseEnter += OnEnter;
        }
        else
        {
            b.MouseMove -= OnMove;
            b.MouseLeave -= OnLeave;
            b.MouseEnter -= OnEnter;
        }
    }

    private static Border EnsureOverlay(Button b)
    {
        var existing = b.GetValue(OverlayKey) as Border;
        if (existing is not null) return existing;

        // Ищем корневой Border в шаблоне кнопки и вклеиваем внутрь второй Border-оверлей.
        // Если шаблон не Border-based — молча выходим.
        if (b.Template?.FindName("bd", b) is not Border root)
            return null!;

        var overlay = new Border
        {
            CornerRadius = root.CornerRadius,
            IsHitTestVisible = false,
            Opacity = 0,
            Background = new RadialGradientBrush(
                Color.FromArgb(80, 255, 255, 255),
                Color.FromArgb(0, 255, 255, 255))
            {
                GradientOrigin = new Point(0.5, 0.5),
                Center = new Point(0.5, 0.5),
                RadiusX = 0.6,
                RadiusY = 0.6,
            },
        };

        // Вставляем overlay в Grid поверх содержимого. Если parent — Border, оборачиваем.
        if (root.Child is Grid g)
        {
            g.Children.Add(overlay);
            Grid.SetColumnSpan(overlay, 10);
            Grid.SetRowSpan(overlay, 10);
        }
        else
        {
            var wrap = new Grid();
            var prev = root.Child;
            root.Child = null;
            if (prev is not null) wrap.Children.Add(prev);
            wrap.Children.Add(overlay);
            root.Child = wrap;
        }
        b.SetValue(OverlayKey, overlay);
        return overlay;
    }

    private static void OnEnter(object sender, MouseEventArgs e)
    {
        if (sender is not Button b) return;
        var ov = EnsureOverlay(b);
        if (ov is not null) ov.Opacity = 1.0;
    }

    private static void OnLeave(object sender, MouseEventArgs e)
    {
        if (sender is not Button b) return;
        if (b.GetValue(OverlayKey) is Border ov) ov.Opacity = 0;
    }

    private static void OnMove(object sender, MouseEventArgs e)
    {
        if (sender is not Button b) return;
        var ov = EnsureOverlay(b);
        if (ov is null || ov.ActualWidth <= 0 || ov.ActualHeight <= 0) return;
        var p = e.GetPosition(ov);
        var x = System.Math.Max(0, System.Math.Min(1, p.X / ov.ActualWidth));
        var y = System.Math.Max(0, System.Math.Min(1, p.Y / ov.ActualHeight));
        if (ov.Background is RadialGradientBrush rgb)
        {
            rgb.GradientOrigin = new Point(x, y);
            rgb.Center = new Point(x, y);
        }
    }
}
