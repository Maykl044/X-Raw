using System.ComponentModel;
using Xrav.Core.Domain;

namespace Xrav.Desktop.Services;

public enum TunnelConnectionState
{
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
    Error
}

/// <summary>Платформенный туннель: на Windows <see cref="WinTunnelService"/> (xray + Wintun + hev-socks5-tunnel).</summary>
public interface ITunnelService : INotifyPropertyChanged
{
    TunnelConnectionState State { get; }
    string? LastError { get; }
    Task ConnectAsync(VpnKey? activeKey, CancellationToken cancellationToken = default);
    Task DisconnectAsync(CancellationToken cancellationToken = default);
}
