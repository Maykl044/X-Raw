using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace Xrav.Desktop.Ui;

public partial class ServersView : UserControl
{
    public ServersView() => InitializeComponent();

    /// <summary>
    /// Прокидываем колёсико с вложенных ListBox / ScrollViewer на внешний ScrollViewer,
    /// который держит весь сгруппированный список ключей. Без этого скролл «застревает»
    /// когда курсор находится над карточкой ключа — внутренний ListBox перехватывает
    /// MouseWheel и не даёт прокручивать всю страницу.
    /// </summary>
    private void GroupListBox_PreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        if (e.Handled) return;
        e.Handled = true;
        var args = new MouseWheelEventArgs(e.MouseDevice, e.Timestamp, e.Delta)
        {
            RoutedEvent = MouseWheelEvent,
            Source = sender,
        };
        if (sender is UIElement ui && VisualTreeHelper.GetParent(ui) is UIElement parent)
            parent.RaiseEvent(args);
    }
}
