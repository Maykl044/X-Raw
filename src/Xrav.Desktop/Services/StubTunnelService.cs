using System.ComponentModel;
using System.Runtime.CompilerServices;
using Xrav.Core.Domain;

namespace Xrav.Desktop.Services;

/// <summary>Заглушка (без xray/hev).</summary>
public sealed class StubTunnelService : ITunnelService, INotifyPropertyChanged
{
    private TunnelConnectionState _state = TunnelConnectionState.Disconnected;
    private string? _lastError;

    public TunnelConnectionState State
    {
        get => _state;
        private set
        {
            if (_state == value) return;
            _state = value;
            OnPropertyChanged();
        }
    }

    public string? LastError
    {
        get => _lastError;
        private set
        {
            if (_lastError == value) return;
            _lastError = value;
            OnPropertyChanged();
        }
    }

    public async Task ConnectAsync(VpnKey? activeKey, CancellationToken cancellationToken = default)
    {
        State = TunnelConnectionState.Connecting;
        await Task.Delay(400, cancellationToken).ConfigureAwait(true);
        LastError = "Режим заглушки: выберите WinTunnelService (см. MainWindow) для xray+Wintun.";
        State = TunnelConnectionState.Error;
    }

    public async Task DisconnectAsync(CancellationToken cancellationToken = default)
    {
        await Task.Delay(50, cancellationToken).ConfigureAwait(true);
        State = TunnelConnectionState.Disconnected;
        LastError = null;
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
