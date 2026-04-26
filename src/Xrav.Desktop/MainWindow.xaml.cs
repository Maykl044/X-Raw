using System.Windows;
using System.Windows.Controls;
using Xrav.Desktop.Services;
using Xrav.Desktop.Storage;
using Xrav.Desktop.ViewModels;

namespace Xrav.Desktop;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        var tunnel = new WinTunnelService();
        var store = new JsonFileUserStateStore();
        DataContext = new MainViewModel(tunnel, store);
        Closed += (_, _) =>
        {
            if (DataContext is MainViewModel vm)
            {
                vm.Teardown();
                if (vm.Tunnel is IDisposable d) d.Dispose();
            }
        };
    }

    private void OnPowerClick(object sender, RoutedEventArgs e)
    {
        if (DataContext is not MainViewModel vm) return;
        if (vm.TunnelState is TunnelConnectionState.Connected
            or TunnelConnectionState.Connecting
            or TunnelConnectionState.Reconnecting)
        {
            if (vm.DisconnectCommand.CanExecute(null))
                vm.DisconnectCommand.Execute(null);
        }
        else
        {
            if (vm.ConnectCommand.CanExecute(null))
                vm.ConnectCommand.Execute(null);
        }
    }
}
