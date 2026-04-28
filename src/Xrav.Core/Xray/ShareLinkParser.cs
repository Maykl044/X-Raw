using System.Collections.Specialized;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Xrav.Core.Domain;

namespace Xrav.Core.Xray;

/// <summary>
/// Парсер vless/vmess/trojan/ss/hysteria2/tuic ссылок. Упрощённая версия эталона из v2rayN/v2rayNG.
/// </summary>
public static class ShareLinkParser
{
    public static bool TryParse(string raw, out ShareLink? link, out KeyProtocol detectedProtocol, out string? error)
    {
        link = null;
        detectedProtocol = KeyProtocol.Unknown;
        error = null;
        if (string.IsNullOrWhiteSpace(raw))
        {
            error = "Пустая ссылка.";
            return false;
        }
        var trimmed = raw.Trim();
        try
        {
            if (trimmed.StartsWith("vless://", StringComparison.OrdinalIgnoreCase))
            {
                detectedProtocol = KeyProtocol.Vless;
                link = ParseVless(trimmed);
            }
            else if (trimmed.StartsWith("vmess://", StringComparison.OrdinalIgnoreCase))
            {
                detectedProtocol = KeyProtocol.VMess;
                link = ParseVmess(trimmed);
            }
            else if (trimmed.StartsWith("trojan://", StringComparison.OrdinalIgnoreCase))
            {
                detectedProtocol = KeyProtocol.Trojan;
                link = ParseTrojan(trimmed);
            }
            else if (trimmed.StartsWith("ss://", StringComparison.OrdinalIgnoreCase))
            {
                detectedProtocol = KeyProtocol.Shadowsocks;
                link = ParseShadowsocks(trimmed);
            }
            else if (trimmed.StartsWith("hysteria2://", StringComparison.OrdinalIgnoreCase)
                     || trimmed.StartsWith("hy2://", StringComparison.OrdinalIgnoreCase))
            {
                detectedProtocol = KeyProtocol.Hysteria2;
                link = ParseHysteria2(trimmed);
            }
            else if (trimmed.StartsWith("tuic://", StringComparison.OrdinalIgnoreCase))
            {
                detectedProtocol = KeyProtocol.Tuic;
                link = ParseTuic(trimmed);
            }
            else
            {
                error = "Неподдерживаемая схема ссылки.";
                return false;
            }
            return link is not null;
        }
        catch (Exception ex)
        {
            error = "Не удалось распарсить ссылку: " + ex.Message;
            return false;
        }
    }

    public static VpnKey? TryBuildVpnKey(string raw, string? subscriptionId, KeySource source)
    {
        if (!TryParse(raw, out var link, out var proto, out _))
        {
            // На случай чистого JSON
            if (LooksLikeJson(raw))
            {
                return new VpnKey(
                    Id: Guid.NewGuid().ToString("N"),
                    Remark: TryReadJsonRemark(raw) ?? "Импорт JSON",
                    Protocol: KeyProtocol.Json,
                    Host: null,
                    Port: null,
                    Source: source,
                    Raw: raw,
                    SubscriptionId: subscriptionId)
                {
                    FullConfig = TryPrettifyJson(raw),
                    FullConfigKind = "json"
                };
            }
            return null;
        }
        // При импорте сразу строим полный JSON-конфиг (xray для VLESS/VMess/Trojan/SS,
        // sing-box для Hysteria2/TUIC) — пользователь видит конкретный конфиг, не сырую ссылку.
        var (cfg, kind) = TryBuildConfigForLink(link!, proto);
        return new VpnKey(
            Id: Guid.NewGuid().ToString("N"),
            Remark: string.IsNullOrWhiteSpace(link!.Remark) ? $"{proto} {link.Host}" : link.Remark,
            Protocol: proto,
            Host: link.Host,
            Port: link.Port,
            Source: source,
            Raw: raw,
            SubscriptionId: subscriptionId)
        {
            FullConfig = cfg,
            FullConfigKind = kind
        };
    }

    private static (string? Config, string? Kind) TryBuildConfigForLink(ShareLink link, KeyProtocol proto)
    {
        try
        {
            return proto switch
            {
                KeyProtocol.Hysteria2 or KeyProtocol.Tuic =>
                    (Xrav.Core.SingBox.SingBoxConfigBuilder.BuildFromShareLink(link), "sing-box"),
                _ => (XrayConfigBuilder.BuildFromShareLink(link), "xray")
            };
        }
        catch
        {
            return (null, null);
        }
    }

    private static string? TryPrettifyJson(string raw)
    {
        try
        {
            using var doc = JsonDocument.Parse(raw);
            return JsonSerializer.Serialize(doc.RootElement, new JsonSerializerOptions { WriteIndented = true });
        }
        catch
        {
            return raw;
        }
    }

    private static bool LooksLikeJson(string raw)
    {
        var t = raw.TrimStart();
        if (!t.StartsWith("{")) return false;
        try { JsonDocument.Parse(raw); return true; } catch { return false; }
    }

    private static string? TryReadJsonRemark(string raw)
    {
        try
        {
            var doc = JsonNode.Parse(raw);
            return doc?["remarks"]?.GetValue<string?>()
                ?? doc?["remark"]?.GetValue<string?>();
        }
        catch { return null; }
    }

    private static ShareLink ParseVless(string raw)
    {
        // vless://uuid@host:port?params#remark
        var (userInfo, host, port, q, remark) = SplitGenericUri(raw, "vless://");
        return new ShareLink
        {
            Kind = KeyKind.Vless,
            Host = host,
            Port = port,
            UserId = userInfo,
            Remark = remark,
            Raw = raw,
            Network = q.Get("type") ?? "tcp",
            Security = q.Get("security") ?? "none",
            Sni = q.Get("sni"),
            Alpn = q.Get("alpn"),
            Fingerprint = q.Get("fp"),
            PublicKey = q.Get("pbk"),
            ShortId = q.Get("sid"),
            SpiderX = q.Get("spx"),
            Flow = q.Get("flow"),
            Path = q.Get("path"),
            HttpHost = q.Get("host"),
            ServiceName = q.Get("serviceName") ?? q.Get("path"),
            HeaderType = q.Get("headerType"),
            Encryption = q.Get("encryption") ?? "none",
            AllowInsecure = ParseBool(q.Get("allowInsecure")),
            XhttpMode = q.Get("mode"),
            XhttpExtra = q.Get("extra"),
            Seed = q.Get("seed"),
            Congestion = ParseBoolNullable(q.Get("congestion"))
        };
    }

    private static ShareLink ParseVmess(string raw)
    {
        // vmess://base64(JSON{add,port,id,aid,net,type,host,path,tls,sni,scy,...})
        var b64 = raw.Substring("vmess://".Length).TrimEnd('=');
        var bytes = Base64UrlDecode(b64);
        var json = Encoding.UTF8.GetString(bytes);
        var n = JsonNode.Parse(json) as JsonObject ?? throw new InvalidOperationException("VMess: bad JSON");
        var host = StrOrEmpty(n["add"]);
        var port = IntOrZero(n["port"]);
        var remark = StrOrEmpty(n["ps"]);
        var uuid = StrOrEmpty(n["id"]);
        var net = StrOrDefault(n["net"], "tcp");
        var tls = StrOrEmpty(n["tls"]);
        var sni = StrOrEmpty(n["sni"]);
        var path = StrOrEmpty(n["path"]);
        var hostHdr = StrOrEmpty(n["host"]);
        var alpn = StrOrEmpty(n["alpn"]);
        var fp = StrOrEmpty(n["fp"]);
        var headerType = StrOrEmpty(n["type"]);
        var scy = StrOrDefault(n["scy"], "auto");
        return new ShareLink
        {
            Kind = KeyKind.VMess,
            Host = host,
            Port = port,
            UserId = uuid,
            Remark = remark,
            Network = net,
            Security = string.IsNullOrEmpty(tls) ? "none" : tls,
            Sni = NullIfEmpty(sni),
            Alpn = NullIfEmpty(alpn),
            Fingerprint = NullIfEmpty(fp),
            HeaderType = NullIfEmpty(headerType),
            Path = NullIfEmpty(path),
            HttpHost = NullIfEmpty(hostHdr),
            Encryption = scy,
            Raw = raw
        };
    }

    private static ShareLink ParseTrojan(string raw)
    {
        var (userInfo, host, port, q, remark) = SplitGenericUri(raw, "trojan://");
        return new ShareLink
        {
            Kind = KeyKind.Trojan,
            Host = host,
            Port = port,
            Password = userInfo,
            Remark = remark,
            Raw = raw,
            Network = q.Get("type") ?? "tcp",
            Security = q.Get("security") ?? "tls",
            Sni = q.Get("sni"),
            Alpn = q.Get("alpn"),
            Fingerprint = q.Get("fp"),
            Flow = q.Get("flow"),
            Path = q.Get("path"),
            HttpHost = q.Get("host"),
            ServiceName = q.Get("serviceName") ?? q.Get("path"),
            HeaderType = q.Get("headerType"),
            AllowInsecure = ParseBool(q.Get("allowInsecure")),
            XhttpMode = q.Get("mode"),
            XhttpExtra = q.Get("extra"),
            Seed = q.Get("seed"),
            Congestion = ParseBoolNullable(q.Get("congestion"))
        };
    }

    private static ShareLink ParseShadowsocks(string raw)
    {
        // ss://method:password@host:port#remark
        // ss://base64(method:password)@host:port#remark (новый/старый стиль)
        // ss://base64(method:password@host:port)#remark (legacy)
        var withoutScheme = raw.Substring("ss://".Length);
        var hashIdx = withoutScheme.IndexOf('#');
        var remark = hashIdx >= 0 ? Uri.UnescapeDataString(withoutScheme.Substring(hashIdx + 1)) : "";
        var body = hashIdx >= 0 ? withoutScheme.Substring(0, hashIdx) : withoutScheme;

        // Strip optional ?plugin=... params
        var qIdx = body.IndexOf('?');
        if (qIdx >= 0) body = body.Substring(0, qIdx);

        string method, password, host;
        int port;
        if (body.Contains('@'))
        {
            var atIdx = body.LastIndexOf('@');
            var creds = body.Substring(0, atIdx);
            var hostPort = body.Substring(atIdx + 1);

            // creds могут быть base64(method:password) или method:password
            var (m, p) = TrySplitMethodPassword(creds);
            method = m;
            password = p;
            (host, port) = SplitHostPort(hostPort);
        }
        else
        {
            // legacy: base64 of method:password@host:port
            var decoded = Encoding.UTF8.GetString(Base64UrlDecode(body));
            var atIdx = decoded.LastIndexOf('@');
            var creds = decoded.Substring(0, atIdx);
            var hostPort = decoded.Substring(atIdx + 1);
            var (m, p) = TrySplitMethodPassword(creds);
            method = m;
            password = p;
            (host, port) = SplitHostPort(hostPort);
        }
        return new ShareLink
        {
            Kind = KeyKind.Shadowsocks,
            Host = host,
            Port = port,
            Password = password,
            Method = method,
            Remark = remark,
            Raw = raw
        };
    }

    private static ShareLink ParseHysteria2(string raw)
    {
        var schemeLen = raw.StartsWith("hy2://", StringComparison.OrdinalIgnoreCase) ? "hy2://".Length : "hysteria2://".Length;
        var rawStandard = "hysteria2://" + raw.Substring(schemeLen);
        var (userInfo, host, port, q, remark) = SplitGenericUri(rawStandard, "hysteria2://");
        return new ShareLink
        {
            Kind = KeyKind.Hysteria2,
            Host = host,
            Port = port,
            Password = userInfo,
            Remark = remark,
            Raw = raw,
            Sni = q.Get("sni") ?? q.Get("peer"),
            Fingerprint = q.Get("fp"),
            Alpn = q.Get("alpn"),
            AllowInsecure = ParseBool(q.Get("insecure"))
        };
    }

    private static ShareLink ParseTuic(string raw)
    {
        var (userInfo, host, port, q, remark) = SplitGenericUri(raw, "tuic://");
        // userInfo: uuid:password
        string? uuid = userInfo;
        string? pass = null;
        if (userInfo is not null && userInfo.Contains(':'))
        {
            var idx = userInfo.IndexOf(':');
            uuid = userInfo.Substring(0, idx);
            pass = userInfo.Substring(idx + 1);
        }
        return new ShareLink
        {
            Kind = KeyKind.Tuic,
            Host = host,
            Port = port,
            UserId = uuid,
            Password = pass,
            Remark = remark,
            Raw = raw,
            Sni = q.Get("sni"),
            Alpn = q.Get("alpn"),
            AllowInsecure = ParseBool(q.Get("allow_insecure") ?? q.Get("insecure"))
        };
    }

    private static (string? userInfo, string host, int port, System.Collections.Specialized.NameValueCollection q, string remark)
        SplitGenericUri(string raw, string scheme)
    {
        var withoutScheme = raw.Substring(scheme.Length);
        var hashIdx = withoutScheme.IndexOf('#');
        var remark = hashIdx >= 0 ? Uri.UnescapeDataString(withoutScheme.Substring(hashIdx + 1)) : "";
        var body = hashIdx >= 0 ? withoutScheme.Substring(0, hashIdx) : withoutScheme;
        string? userInfo = null;
        var atIdx = body.LastIndexOf('@');
        if (atIdx >= 0)
        {
            userInfo = Uri.UnescapeDataString(body.Substring(0, atIdx));
            body = body.Substring(atIdx + 1);
        }
        string queryStr = "";
        var qIdx = body.IndexOf('?');
        if (qIdx >= 0)
        {
            queryStr = body.Substring(qIdx + 1);
            body = body.Substring(0, qIdx);
        }
        var (host, port) = SplitHostPort(body);
        var q = ParseQueryString(queryStr);
        return (userInfo, host, port, q, remark);
    }

    private static NameValueCollection ParseQueryString(string queryStr)
    {
        var nvc = new NameValueCollection();
        if (string.IsNullOrEmpty(queryStr)) return nvc;
        foreach (var pair in queryStr.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var eq = pair.IndexOf('=');
            string key, value;
            if (eq < 0) { key = pair; value = ""; }
            else { key = pair.Substring(0, eq); value = pair.Substring(eq + 1); }
            nvc.Add(Uri.UnescapeDataString(key), Uri.UnescapeDataString(value));
        }
        return nvc;
    }

    private static (string host, int port) SplitHostPort(string hostPort)
    {
        if (hostPort.StartsWith("["))
        {
            // IPv6 [::1]:port
            var endBracket = hostPort.IndexOf(']');
            if (endBracket > 0)
            {
                var h = hostPort.Substring(1, endBracket - 1);
                var rest = hostPort.Substring(endBracket + 1);
                var p = 0;
                if (rest.StartsWith(":") && int.TryParse(rest.Substring(1), out var pp)) p = pp;
                return (h, p);
            }
        }
        var colonIdx = hostPort.LastIndexOf(':');
        if (colonIdx < 0) return (hostPort, 0);
        var host = hostPort.Substring(0, colonIdx);
        var portStr = hostPort.Substring(colonIdx + 1);
        var port = int.TryParse(portStr, out var prt) ? prt : 0;
        return (host, port);
    }

    private static (string method, string password) TrySplitMethodPassword(string creds)
    {
        // try direct
        var direct = TrySplitColon(creds);
        if (direct is not null) return direct.Value;
        // try base64
        try
        {
            var decoded = Encoding.UTF8.GetString(Base64UrlDecode(creds));
            var dec = TrySplitColon(decoded);
            if (dec is not null) return dec.Value;
        }
        catch { /* ignore */ }
        return ("aes-256-gcm", creds);
    }

    private static (string method, string password)? TrySplitColon(string s)
    {
        var idx = s.IndexOf(':');
        if (idx <= 0) return null;
        return (s.Substring(0, idx), s.Substring(idx + 1));
    }

    private static byte[] Base64UrlDecode(string s)
    {
        s = s.Replace('-', '+').Replace('_', '/').Trim();
        var pad = (4 - s.Length % 4) % 4;
        if (pad > 0) s += new string('=', pad);
        return Convert.FromBase64String(s);
    }

    private static string StrOrEmpty(JsonNode? n) => n is null ? "" : n.ToString() ?? "";
    private static string StrOrDefault(JsonNode? n, string d)
    {
        var s = StrOrEmpty(n);
        return string.IsNullOrWhiteSpace(s) ? d : s;
    }
    private static int IntOrZero(JsonNode? n)
    {
        if (n is null) return 0;
        try
        {
            if (n is JsonValue v && v.TryGetValue<int>(out var i)) return i;
        }
        catch { /* ignore */ }
        var s = n.ToString();
        return int.TryParse(s, out var p) ? p : 0;
    }
    private static string? NullIfEmpty(string? s) => string.IsNullOrWhiteSpace(s) ? null : s;
    private static bool ParseBool(string? s) =>
        s is not null && (s.Equals("true", StringComparison.OrdinalIgnoreCase) || s == "1");
    private static bool? ParseBoolNullable(string? s) =>
        s is null ? null : (s.Equals("true", StringComparison.OrdinalIgnoreCase) || s == "1");
}
