using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;

namespace Xrav.Desktop.Ui;

public partial class FilterPill : UserControl
{
    public static readonly DependencyProperty LabelProperty =
        DependencyProperty.Register(nameof(Label), typeof(string), typeof(FilterPill), new PropertyMetadata(""));
    public static readonly DependencyProperty FilterValueProperty =
        DependencyProperty.Register(nameof(FilterValue), typeof(string), typeof(FilterPill), new PropertyMetadata(""));
    public static readonly DependencyProperty ActiveProperty =
        DependencyProperty.Register(nameof(Active), typeof(string), typeof(FilterPill), new PropertyMetadata(""));
    public static readonly DependencyProperty CommandProperty =
        DependencyProperty.Register(nameof(Command), typeof(ICommand), typeof(FilterPill), new PropertyMetadata(null));

    public FilterPill() => InitializeComponent();

    public string Label { get => (string)GetValue(LabelProperty); set => SetValue(LabelProperty, value); }
    public string FilterValue { get => (string)GetValue(FilterValueProperty); set => SetValue(FilterValueProperty, value); }
    public string Active { get => (string)GetValue(ActiveProperty); set => SetValue(ActiveProperty, value); }
    public ICommand? Command { get => (ICommand?)GetValue(CommandProperty); set => SetValue(CommandProperty, value); }
}
