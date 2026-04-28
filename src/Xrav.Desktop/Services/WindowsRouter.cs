using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;

namespace Xrav.Desktop.Services;

/// <summary>
/// Управление маршрутами и DNS Windows для tunnel-режима (xray + hev).
///
/// hev-socks5-tunnel на Windows только создаёт Wintun-интерфейс с указанным IP,
/// но НЕ прописывает default route и DNS — это надо делать самому. Без этого
/// Windows продолжает гнать трафик через реальный шлюз и в туннель ничего не
/// попадает («Подключено» в UI, но интернета нет).
///
/// Стратегия:
/// 1. Резолв host VPN-сервера → IPv4 (через Dns.GetHostAddresses).
/// 2. Запоминаем текущий default gateway и его interface index.
/// 3. Прямой маршрут VPN_SERVER_IP/32 → ORIGINAL_GATEWAY (чтобы зашифрованный
///    исходящий трафик xray не зацикливался обратно в туннель).
/// 4. Default route через TUN: 0.0.0.0/1 + 128.0.0.0/1 → tunIp на интерфейсе
///    Wintun. Это «псевдо-default» — он перекрывает реальный 0.0.0.0/0 за счёт
///    более специфичной маски, но не трогает оригинальную запись (легче откат).
/// 5. DNS на TUN-интерфейс: 1.1.1.1, 8.8.8.8.
/// </summary>
public sealed class WindowsRouter
{
    public sealed record Snapshot(
        string? OriginalGateway,
        int? OriginalIfIndex,
        string TunInterfaceName,
        string TunIp,
        string ServerIp);

    public Action<string>? Log { get; set; }

    public async Task<Snapshot?> ApplyAsync(string serverHost, string tunInterfaceName, string tunIp, CancellationToken ct = default)
    {
        try
        {
            var serverIp = await ResolveIpv4Async(serverHost, ct).ConfigureAwait(false);
            if (string.IsNullOrEmpty(serverIp))
            {
                Log?.Invoke($"[router] не смог резолвить {serverHost}, маршруты не настроены");
                return null;
            }

            var (gw, ifIndex) = GetDefaultGateway();
            if (string.IsNullOrEmpty(gw))
            {
                Log?.Invoke("[router] не нашёл текущий default gateway — маршруты не настроены");
                return null;
            }

            Log?.Invoke($"[router] server={serverHost} ({serverIp}); original gw={gw} if={ifIndex}; TUN={tunInterfaceName} ({tunIp})");

            // 1. Direct route VPN server → original gw
            Run("route", $"add {serverIp} mask 255.255.255.255 {gw} metric 5 if {ifIndex}");

            // 2. 0.0.0.0/1 и 128.0.0.0/1 через TUN (псевдо-default)
            Run("route", $"add 0.0.0.0 mask 128.0.0.0 {tunIp} metric 5");
            Run("route", $"add 128.0.0.0 mask 128.0.0.0 {tunIp} metric 5");

            // 3. DNS на TUN-интерфейс
            RunNetsh($"interface ipv4 set dnsservers \"{tunInterfaceName}\" static 1.1.1.1 primary validate=no");
            RunNetsh($"interface ipv4 add dnsservers \"{tunInterfaceName}\" 8.8.8.8 index=2 validate=no");

            return new Snapshot(gw, ifIndex, tunInterfaceName, tunIp, serverIp);
        }
        catch (Exception ex)
        {
            Log?.Invoke($"[router] ApplyAsync: {ex.Message}");
            return null;
        }
    }

    public void Revert(Snapshot? s)
    {
        if (s is null) return;
        try
        {
            // Удалить псевдо-default
            Run("route", $"delete 0.0.0.0 mask 128.0.0.0 {s.TunIp}");
            Run("route", $"delete 128.0.0.0 mask 128.0.0.0 {s.TunIp}");
            // Удалить direct маршрут к VPN серверу
            if (!string.IsNullOrEmpty(s.OriginalGateway))
                Run("route", $"delete {s.ServerIp} mask 255.255.255.255 {s.OriginalGateway}");
            // Сбросить DNS на TUN (как будто получает по DHCP — освобождает)
            RunNetsh($"interface ipv4 set dnsservers \"{s.TunInterfaceName}\" dhcp");
        }
        catch (Exception ex)
        {
            Log?.Invoke($"[router] Revert: {ex.Message}");
        }
    }

    /// <summary>Парсит вывод <c>route print -4</c> и возвращает default gateway + interface index.</summary>
    public static (string? Gateway, int? IfIndex) GetDefaultGateway()
    {
        try
        {
            var iface = NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up
                            && n.NetworkInterfaceType != NetworkInterfaceType.Loopback
                            && n.NetworkInterfaceType != NetworkInterfaceType.Tunnel
                            && !n.Description.Contains("WireGuard", StringComparison.OrdinalIgnoreCase)
                            && !n.Description.Contains("Wintun", StringComparison.OrdinalIgnoreCase)
                            && !n.Description.Contains("TAP", StringComparison.OrdinalIgnoreCase))
                .Select(n => new
                {
                    Iface = n,
                    Props = n.GetIPProperties()
                })
                .Where(x => x.Props.GatewayAddresses
                    .Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork
                              && !g.Address.Equals(IPAddress.Any)))
                .OrderBy(x => GetIPv4InterfaceMetric(x.Iface))
                .FirstOrDefault();

            if (iface is null) return (null, null);

            var gw = iface.Props.GatewayAddresses
                .Select(g => g.Address)
                .FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork);
            var idx = iface.Props.GetIPv4Properties()?.Index;
            return (gw?.ToString(), idx);
        }
        catch
        {
            return (null, null);
        }
    }

    private static int GetIPv4InterfaceMetric(NetworkInterface n)
    {
        try { return n.GetIPProperties().GetIPv4Properties()?.Index ?? int.MaxValue; }
        catch { return int.MaxValue; }
    }

    public static async Task<string?> ResolveIpv4Async(string host, CancellationToken ct = default)
    {
        if (IPAddress.TryParse(host, out var ip) && ip.AddressFamily == AddressFamily.InterNetwork)
            return ip.ToString();
        try
        {
            var addrs = await Dns.GetHostAddressesAsync(host, ct).ConfigureAwait(false);
            var v4 = addrs.FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork);
            return v4?.ToString();
        }
        catch
        {
            return null;
        }
    }

    private void Run(string exe, string args)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = exe,
                Arguments = args,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };
            using var p = Process.Start(psi);
            if (p is null) return;
            p.WaitForExit(5000);
            var so = p.StandardOutput.ReadToEnd().Trim();
            var se = p.StandardError.ReadToEnd().Trim();
            var combined = (so + (string.IsNullOrEmpty(se) ? "" : " | " + se)).Trim();
            Log?.Invoke($"[router] {exe} {args} → exit={p.ExitCode}{(string.IsNullOrEmpty(combined) ? "" : " " + combined.Replace("\n", " ").Replace("\r", ""))}");
        }
        catch (Exception ex)
        {
            Log?.Invoke($"[router] {exe} {args}: {ex.Message}");
        }
    }

    private void RunNetsh(string args) => Run("netsh", args);
}
