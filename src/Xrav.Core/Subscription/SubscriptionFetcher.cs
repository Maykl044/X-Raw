using System.Net.Http;
using System.Text;
using Xrav.Core.Domain;
using Xrav.Core.Xray;

namespace Xrav.Core.Subscription;

/// <summary>
/// HTTP-загрузчик подписок (v2rayN/v2rayNG-совместимый: тело — base64 строк со ссылками либо plain).
/// </summary>
public static class SubscriptionFetcher
{
    public static async Task<IReadOnlyList<VpnKey>> FetchAsync(
        HttpClient http,
        string url,
        string subscriptionId,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(url)) return Array.Empty<VpnKey>();
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.UserAgent.ParseAdd("X-Rav/1.0 (+https://github.com/Maykl044/X-Raw)");
        using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
        var body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        return ParseBody(body, subscriptionId);
    }

    public static IReadOnlyList<VpnKey> ParseBody(string body, string subscriptionId)
    {
        if (string.IsNullOrWhiteSpace(body)) return Array.Empty<VpnKey>();
        var content = TryDecodeBase64(body) ?? body;
        var keys = new List<VpnKey>();
        foreach (var raw in content.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            if (string.IsNullOrWhiteSpace(raw)) continue;
            if (raw.StartsWith("#")) continue;
            var key = ShareLinkParser.TryBuildVpnKey(raw, subscriptionId, KeySource.Subscription);
            if (key is not null) keys.Add(key);
        }
        return keys;
    }

    private static string? TryDecodeBase64(string s)
    {
        var trimmed = s.Trim();
        // Detect plain ssh:// vless:// etc
        if (trimmed.Contains("://")) return null;
        try
        {
            var normalised = trimmed.Replace('-', '+').Replace('_', '/').Replace("\n", "").Replace("\r", "");
            var pad = (4 - normalised.Length % 4) % 4;
            if (pad > 0) normalised += new string('=', pad);
            var bytes = Convert.FromBase64String(normalised);
            var decoded = Encoding.UTF8.GetString(bytes);
            return decoded.Contains("://") ? decoded : null;
        }
        catch
        {
            return null;
        }
    }
}
