using System.Text.Json.Nodes;
using Xrav.Core.Xray;

namespace Xrav.Core.SingBox;

/// <summary>
/// Сборка <c>sing-box.json</c> для одного <see cref="ShareLink"/>.
/// sing-box умеет TUN сам (<c>inbound: tun</c> + <c>auto_route: true</c>) — hev-socks5-tunnel и Wintun не нужны.
/// Поддерживает все протоколы: VLESS / VMess / Trojan / Shadowsocks / Hysteria2 / TUIC + REALITY и все транспорты.
/// </summary>
public static class SingBoxConfigBuilder
{
    public const string ProxyOutboundTag = "proxy";

    /// <summary>Имя Wintun-адаптера в Windows.</summary>
    public const string TunInterfaceName = "X-RavSingBox";

    public static string BuildFromShareLink(ShareLink link)
    {
        var root = new JsonObject
        {
            ["log"] = new JsonObject
            {
                ["level"] = "warn",
                ["timestamp"] = true
            },
            ["dns"] = BuildDns(),
            ["inbounds"] = new JsonArray(BuildTunInbound()),
            ["outbounds"] = new JsonArray(
                BuildOutbound(link),
                new JsonObject { ["type"] = "direct", ["tag"] = "direct" },
                new JsonObject { ["type"] = "block",  ["tag"] = "block"  },
                new JsonObject { ["type"] = "dns",    ["tag"] = "dns-out" }
            ),
            ["route"] = BuildRoute()
        };
        return root.ToJsonString(new System.Text.Json.JsonSerializerOptions { WriteIndented = true });
    }

    private static JsonObject BuildDns() => new()
    {
        ["servers"] = new JsonArray(
            new JsonObject
            {
                ["tag"] = "remote-doh",
                ["address"] = "https://1.1.1.1/dns-query",
                ["address_resolver"] = "local",
                ["detour"] = ProxyOutboundTag
            },
            new JsonObject
            {
                ["tag"] = "remote-dot",
                ["address"] = "tls://8.8.8.8",
                ["address_resolver"] = "local",
                ["detour"] = ProxyOutboundTag
            },
            new JsonObject
            {
                ["tag"] = "local",
                ["address"] = "udp://1.1.1.1",
                ["detour"] = "direct"
            },
            new JsonObject
            {
                ["tag"] = "block",
                ["address"] = "rcode://success"
            }),
        ["rules"] = new JsonArray(
            new JsonObject
            {
                ["outbound"] = new JsonArray("any"),
                ["server"] = "local"
            }),
        ["strategy"] = "ipv4_only",
        ["disable_cache"] = false,
        ["disable_expire"] = false
    };

    private static JsonObject BuildTunInbound() => new()
    {
        ["type"] = "tun",
        ["tag"] = "tun-in",
        ["interface_name"] = TunInterfaceName,
        ["mtu"] = 1500,
        ["address"] = new JsonArray("172.19.0.1/30", "fdfe:dcba:9876::1/126"),
        ["auto_route"] = true,
        ["strict_route"] = true,
        ["stack"] = "system",
        ["sniff"] = true,
        ["sniff_override_destination"] = true,
        ["domain_strategy"] = "ipv4_only",
        ["platform"] = new JsonObject
        {
            ["http_proxy"] = new JsonObject
            {
                ["enabled"] = false
            }
        }
    };

    private static JsonObject BuildRoute() => new()
    {
        ["auto_detect_interface"] = true,
        ["final"] = ProxyOutboundTag,
        ["rules"] = new JsonArray(
            new JsonObject
            {
                ["protocol"] = "dns",
                ["outbound"] = "dns-out"
            },
            new JsonObject
            {
                ["ip_is_private"] = true,
                ["outbound"] = "direct"
            })
    };

    public static JsonObject BuildOutbound(ShareLink link) => link.Kind switch
    {
        KeyKind.Vless => BuildVless(link),
        KeyKind.VMess => BuildVmess(link),
        KeyKind.Trojan => BuildTrojan(link),
        KeyKind.Shadowsocks => BuildShadowsocks(link),
        KeyKind.Hysteria2 => BuildHysteria2(link),
        KeyKind.Tuic => BuildTuic(link),
        _ => throw new NotSupportedException($"sing-box outbound: {link.Kind}")
    };

    private static JsonObject BuildVless(ShareLink l)
    {
        var o = new JsonObject
        {
            ["type"] = "vless",
            ["tag"] = ProxyOutboundTag,
            ["server"] = l.Host,
            ["server_port"] = l.Port,
            ["uuid"] = l.UserId ?? "",
            ["flow"] = l.Flow ?? "",
            ["packet_encoding"] = "xudp"
        };
        AttachTls(o, l);
        AttachTransport(o, l);
        return o;
    }

    private static JsonObject BuildVmess(ShareLink l)
    {
        var o = new JsonObject
        {
            ["type"] = "vmess",
            ["tag"] = ProxyOutboundTag,
            ["server"] = l.Host,
            ["server_port"] = l.Port,
            ["uuid"] = l.UserId ?? "",
            ["security"] = string.IsNullOrEmpty(l.Encryption) ? "auto" : l.Encryption,
            ["alter_id"] = 0,
            ["packet_encoding"] = "xudp"
        };
        AttachTls(o, l);
        AttachTransport(o, l);
        return o;
    }

    private static JsonObject BuildTrojan(ShareLink l)
    {
        var o = new JsonObject
        {
            ["type"] = "trojan",
            ["tag"] = ProxyOutboundTag,
            ["server"] = l.Host,
            ["server_port"] = l.Port,
            ["password"] = l.Password ?? ""
        };
        AttachTls(o, l, defaultEnabled: true);
        AttachTransport(o, l);
        return o;
    }

    private static JsonObject BuildShadowsocks(ShareLink l) => new()
    {
        ["type"] = "shadowsocks",
        ["tag"] = ProxyOutboundTag,
        ["server"] = l.Host,
        ["server_port"] = l.Port,
        ["method"] = l.Method ?? "aes-256-gcm",
        ["password"] = l.Password ?? "",
        ["udp_over_tcp"] = true
    };

    private static JsonObject BuildHysteria2(ShareLink l)
    {
        var o = new JsonObject
        {
            ["type"] = "hysteria2",
            ["tag"] = ProxyOutboundTag,
            ["server"] = l.Host,
            ["server_port"] = l.Port,
            ["password"] = l.Password ?? l.UserId ?? ""
        };
        // hysteria2 ВСЕГДА над TLS
        var tls = new JsonObject
        {
            ["enabled"] = true,
            ["insecure"] = l.AllowInsecure
        };
        if (!string.IsNullOrEmpty(l.Sni)) tls["server_name"] = l.Sni;
        if (!string.IsNullOrEmpty(l.Alpn))
            tls["alpn"] = new JsonArray(l.Alpn.Split(',').Select(a => (JsonNode)a.Trim()).ToArray());
        o["tls"] = tls;
        return o;
    }

    private static JsonObject BuildTuic(ShareLink l)
    {
        var o = new JsonObject
        {
            ["type"] = "tuic",
            ["tag"] = ProxyOutboundTag,
            ["server"] = l.Host,
            ["server_port"] = l.Port,
            ["uuid"] = l.UserId ?? "",
            ["password"] = l.Password ?? "",
            ["congestion_control"] = "bbr",
            ["udp_relay_mode"] = "native",
            ["zero_rtt_handshake"] = false
        };
        var tls = new JsonObject
        {
            ["enabled"] = true,
            ["insecure"] = l.AllowInsecure
        };
        if (!string.IsNullOrEmpty(l.Sni)) tls["server_name"] = l.Sni;
        if (!string.IsNullOrEmpty(l.Alpn))
            tls["alpn"] = new JsonArray(l.Alpn.Split(',').Select(a => (JsonNode)a.Trim()).ToArray());
        o["tls"] = tls;
        return o;
    }

    private static void AttachTls(JsonObject o, ShareLink l, bool defaultEnabled = false)
    {
        var enabled = defaultEnabled
            || string.Equals(l.Security, "tls", StringComparison.OrdinalIgnoreCase)
            || string.Equals(l.Security, "reality", StringComparison.OrdinalIgnoreCase);
        if (!enabled) return;

        var tls = new JsonObject
        {
            ["enabled"] = true,
            ["insecure"] = l.AllowInsecure
        };
        if (!string.IsNullOrEmpty(l.Sni)) tls["server_name"] = l.Sni;
        if (!string.IsNullOrEmpty(l.Alpn))
            tls["alpn"] = new JsonArray(l.Alpn.Split(',').Select(a => (JsonNode)a.Trim()).ToArray());

        if (string.Equals(l.Security, "reality", StringComparison.OrdinalIgnoreCase))
        {
            var reality = new JsonObject { ["enabled"] = true };
            if (!string.IsNullOrEmpty(l.PublicKey)) reality["public_key"] = l.PublicKey;
            if (!string.IsNullOrEmpty(l.ShortId))   reality["short_id"]   = l.ShortId;
            tls["reality"] = reality;

            if (!string.IsNullOrEmpty(l.Fingerprint))
            {
                tls["utls"] = new JsonObject
                {
                    ["enabled"] = true,
                    ["fingerprint"] = l.Fingerprint
                };
            }
        }
        else if (!string.IsNullOrEmpty(l.Fingerprint))
        {
            tls["utls"] = new JsonObject
            {
                ["enabled"] = true,
                ["fingerprint"] = l.Fingerprint
            };
        }

        o["tls"] = tls;
    }

    private static void AttachTransport(JsonObject o, ShareLink l)
    {
        var net = string.IsNullOrEmpty(l.Network) ? "tcp" : l.Network;
        switch (net)
        {
            case "ws":
                {
                    var t = new JsonObject
                    {
                        ["type"] = "ws",
                        ["path"] = string.IsNullOrEmpty(l.Path) ? "/" : l.Path
                    };
                    if (!string.IsNullOrEmpty(l.HttpHost))
                        t["headers"] = new JsonObject { ["Host"] = l.HttpHost };
                    o["transport"] = t;
                    break;
                }
            case "grpc":
                o["transport"] = new JsonObject
                {
                    ["type"] = "grpc",
                    ["service_name"] = l.ServiceName ?? l.Path ?? ""
                };
                break;
            case "h2":
            case "http":
                {
                    var t = new JsonObject
                    {
                        ["type"] = "http"
                    };
                    if (!string.IsNullOrEmpty(l.Path)) t["path"] = l.Path;
                    if (!string.IsNullOrEmpty(l.HttpHost))
                        t["host"] = new JsonArray(l.HttpHost.Split(',').Select(h => (JsonNode)h.Trim()).ToArray());
                    o["transport"] = t;
                    break;
                }
            case "httpupgrade":
                {
                    var t = new JsonObject
                    {
                        ["type"] = "httpupgrade",
                        ["path"] = string.IsNullOrEmpty(l.Path) ? "/" : l.Path
                    };
                    if (!string.IsNullOrEmpty(l.HttpHost)) t["host"] = l.HttpHost;
                    o["transport"] = t;
                    break;
                }
        }
    }
}
