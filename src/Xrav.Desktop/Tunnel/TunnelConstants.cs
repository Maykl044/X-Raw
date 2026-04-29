namespace Xrav.Desktop.Tunnel;

/// <summary>Должны совпадать с <c>LibXrayController.SOCKS_INBOUND_PORT</c> / <c>XrayConfigBuilder</c> в Android.</summary>
public static class TunnelConstants
{
    public const int SocksInboundPort = 10808;
    public const int TunMtu = 1500;
    public const string TunIpv4Client = "10.10.14.1";
    public const int UserLevel = 8;
    public const string XraySocksInboundTag = "socks";
}
