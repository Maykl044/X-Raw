using System.Windows;
using System.Windows.Controls;
using Xrav.Desktop.ViewModels;

namespace Xrav.Desktop.Ui;

public partial class SettingsView : UserControl
{
    public SettingsView() => InitializeComponent();

    private void OnManualChecked(object sender, RoutedEventArgs e)
    {
        if (DataContext is MainViewModel vm && vm.AutoSelectMode)
            vm.AutoSelectMode = false;
    }
}
