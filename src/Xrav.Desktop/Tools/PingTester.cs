using System.Diagnostics;
using System.Net.Sockets;
using Xrav.Core.Domain;
using Xrav.Core.Xray;

namespace Xrav.Desktop.Tools;

public static class PingTester
{
    /// <summary>
    /// TCP RTT до host:port ключа. Возвращает мс или null при ошибке.
    /// Делает <paramref name="probes"/> попыток и возвращает минимальное время.
    /// </summary>
    public static async Task<int?> MeasureAsync(VpnKey key, int probes = 2, int timeoutMs = 2500, CancellationToken ct = default)
    {
        if (!ShareLinkParser.TryParse(key.Raw, out var link, out _, out _) || link is null)
            return null;
        return await MeasureAsync(link.Host, link.Port, probes, timeoutMs, ct).ConfigureAwait(false);
    }

    public static async Task<int?> MeasureAsync(string host, int port, int probes = 2, int timeoutMs = 2500, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(host) || port <= 0) return null;
        int? best = null;
        for (int i = 0; i < probes; i++)
        {
            var rtt = await TryProbeAsync(host, port, timeoutMs, ct).ConfigureAwait(false);
            if (rtt is not null && (best is null || rtt < best)) best = rtt;
            if (ct.IsCancellationRequested) break;
        }
        return best;
    }

    private static async Task<int?> TryProbeAsync(string host, int port, int timeoutMs, CancellationToken ct)
    {
        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(timeoutMs);
            using var client = new TcpClient { NoDelay = true };
            var sw = Stopwatch.StartNew();
            await client.ConnectAsync(host, port, cts.Token).ConfigureAwait(false);
            sw.Stop();
            return (int)sw.ElapsedMilliseconds;
        }
        catch
        {
            return null;
        }
    }
}
