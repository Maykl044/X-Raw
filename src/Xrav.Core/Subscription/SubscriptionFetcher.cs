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
    public static Task<IReadOnlyList<VpnKey>> FetchAsync(
        HttpClient http,
        string url,
        string subscriptionId,
        CancellationToken ct = default)
        => FetchAsync(http, url, subscriptionId, clientUid: null, ct);

    /// <summary>
    /// Загружает подписку и передаёт идентификатор клиента (UUIDv4) панели
    /// VPN тремя путями одновременно (для совместимости с разными панелями):
    /// <list type="bullet">
    /// <item>HTTP-заголовок <c>X-Client-UID: &lt;uuid&gt;</c>;</item>
    /// <item>HTTP-заголовок <c>User-Agent: X-Rav/1.0 (&lt;uuid&gt;)</c>;</item>
    /// <item>query-параметр <c>?uid=&lt;uuid&gt;</c> (добавляется только если
    /// в URL ещё нет ни <c>uid</c>, ни <c>token</c>).</item>
    /// </list>
    /// Если <paramref name="clientUid"/> пустой или невалидный — поведение
    /// идентично старой версии (без идентификации клиента).
    /// </summary>
    public static async Task<IReadOnlyList<VpnKey>> FetchAsync(
        HttpClient http,
        string url,
        string subscriptionId,
        string? clientUid,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(url)) return Array.Empty<VpnKey>();

        var effectiveUrl = AppendClientUidQuery(url, clientUid);
        using var req = new HttpRequestMessage(HttpMethod.Get, effectiveUrl);
        var ua = IsValidUuid(clientUid)
            ? $"X-Rav/1.0 ({clientUid})"
            : "X-Rav/1.0 (+https://github.com/Maykl044/X-Raw)";
        req.Headers.UserAgent.ParseAdd(ua);
        if (IsValidUuid(clientUid))
            req.Headers.TryAddWithoutValidation("X-Client-UID", clientUid);
        using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
        var body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        return ParseBody(body, subscriptionId);
    }

    private static bool IsValidUuid(string? s) =>
        !string.IsNullOrWhiteSpace(s) && Guid.TryParseExact(s, "D", out _);

    /// <summary>
    /// Добавляет <c>?uid=&lt;uuid&gt;</c> к URL, если там ещё нет ни
    /// параметра <c>uid</c>, ни <c>token</c>. Сохраняет существующий fragment.
    /// </summary>
    private static string AppendClientUidQuery(string url, string? clientUid)
    {
        if (!IsValidUuid(clientUid)) return url;
        try
        {
            var u = new Uri(url, UriKind.Absolute);
            var existing = u.Query;
            // Не дублируем — если уже есть наша или какая-то токен-идентификация.
            if (existing.Contains("uid=", StringComparison.OrdinalIgnoreCase) ||
                existing.Contains("token=", StringComparison.OrdinalIgnoreCase))
                return url;
            var sep = string.IsNullOrEmpty(existing) ? "?" : "&";
            var leftOfFragment = url;
            string fragment = "";
            int hash = url.IndexOf('#');
            if (hash >= 0)
            {
                leftOfFragment = url[..hash];
                fragment = url[hash..];
            }
            return leftOfFragment + sep + "uid=" + Uri.EscapeDataString(clientUid!) + fragment;
        }
        catch
        {
            // Относительный URL / битый формат — не пытаемся ничего менять.
            return url;
        }
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
