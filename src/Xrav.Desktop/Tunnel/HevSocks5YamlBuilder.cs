namespace Xrav.Desktop.Tunnel;

/// <summary>YAML для <see href="https://github.com/heiher/hev-socks5-tunnel">hev-socks5-tunnel</see> (аналог <c>HevSocks5Tunnel.buildYamlConfig</c> в Android, с полем <c>name</c> для Wintun в Windows).</summary>
public static class HevSocks5YamlBuilder
{
    public static string Build(
        int mtu = TunnelConstants.TunMtu,
        string ipv4Client = TunnelConstants.TunIpv4Client,
        string? ipv6Client = null,
        string socksHost = "127.0.0.1",
        int socksPort = TunnelConstants.SocksInboundPort,
        string tunnelName = "X-RavWintun",
        int tcpTimeoutMs = 300_000,
        int udpTimeoutMs = 60_000,
        string logLevel = "warn")
    {
        var w = new StringWriter();
        w.WriteLine("tunnel:");
        w.WriteLine(string.IsNullOrWhiteSpace(tunnelName)
            ? "  name: X-RavWintun"
            : $"  name: {tunnelName}");
        w.WriteLine($"  mtu: {mtu}");
        w.WriteLine($"  ipv4: {ipv4Client}");
        if (!string.IsNullOrWhiteSpace(ipv6Client))
            w.WriteLine($"  ipv6: '{ipv6Client}'");
        w.WriteLine("socks5:");
        w.WriteLine($"  address: {socksHost}");
        w.WriteLine($"  port: {socksPort}");
        w.WriteLine("  udp: 'udp'");
        w.WriteLine("misc:");
        w.WriteLine($"  tcp-read-write-timeout: {tcpTimeoutMs}");
        w.WriteLine($"  udp-read-write-timeout: {udpTimeoutMs}");
        w.WriteLine($"  log-level: {logLevel}");
        return w.ToString();
    }
}
