using System.Text.Json;
using System.Text.Json.Nodes;
using Xrav.Core.Domain;

namespace Xrav.Core.Xray;

/// <summary>
/// Сборка полного xray <c>config.json</c> для одного <see cref="VpnKey"/>: один <c>proxy</c> outbound + стандартные inbound/routing/dns.
/// SOCKS-инбаунд слушает 127.0.0.1:<paramref name="socksInboundPort"/>, чтобы hev-socks5-tunnel мог подключиться.
/// </summary>
public static class XrayConfigBuilder
{
    public const int DefaultSocksInboundPort = 10808;
    public const string SocksInboundTag = "socks-in";
    public const string ProxyOutboundTag = "proxy";
    public const string DirectOutboundTag = "direct";
    public const string BlockOutboundTag = "block";

    public static string BuildFromShareLink(ShareLink link, int socksInboundPort = DefaultSocksInboundPort)
    {
        var root = new JsonObject
        {
            ["log"] = new JsonObject { ["loglevel"] = "warning" },
            ["dns"] = DefaultDns(),
            ["inbounds"] = new JsonArray(SocksInbound(socksInboundPort)),
            ["outbounds"] = new JsonArray(BuildOutbound(link), DirectOutbound(), BlockOutbound()),
            ["routing"] = DefaultRouting()
        };
        return root.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
    }

    public static JsonObject BuildOutbound(ShareLink link) => link.Kind switch
    {
        KeyKind.Vless => BuildVlessOutbound(link),
        KeyKind.VMess => BuildVmessOutbound(link),
        KeyKind.Trojan => BuildTrojanOutbound(link),
        KeyKind.Shadowsocks => BuildShadowsocksOutbound(link),
        _ => throw new NotSupportedException($"xray outbound build not supported: {link.Kind}")
    };

    private static JsonObject BuildVlessOutbound(ShareLink l) => new()
    {
        ["tag"] = ProxyOutboundTag,
        ["protocol"] = "vless",
        ["settings"] = new JsonObject
        {
            ["vnext"] = new JsonArray(new JsonObject
            {
                ["address"] = l.Host,
                ["port"] = l.Port,
                ["users"] = new JsonArray(new JsonObject
                {
                    ["id"] = l.UserId ?? "",
                    ["encryption"] = l.Encryption ?? "none",
                    ["flow"] = l.Flow ?? ""
                })
            })
        },
        ["streamSettings"] = BuildStreamSettings(l)
    };

    private static JsonObject BuildVmessOutbound(ShareLink l) => new()
    {
        ["tag"] = ProxyOutboundTag,
        ["protocol"] = "vmess",
        ["settings"] = new JsonObject
        {
            ["vnext"] = new JsonArray(new JsonObject
            {
                ["address"] = l.Host,
                ["port"] = l.Port,
                ["users"] = new JsonArray(new JsonObject
                {
                    ["id"] = l.UserId ?? "",
                    ["alterId"] = 0,
                    ["security"] = string.IsNullOrEmpty(l.Encryption) ? "auto" : l.Encryption
                })
            })
        },
        ["streamSettings"] = BuildStreamSettings(l)
    };

    private static JsonObject BuildTrojanOutbound(ShareLink l) => new()
    {
        ["tag"] = ProxyOutboundTag,
        ["protocol"] = "trojan",
        ["settings"] = new JsonObject
        {
            ["servers"] = new JsonArray(new JsonObject
            {
                ["address"] = l.Host,
                ["port"] = l.Port,
                ["password"] = l.Password ?? "",
                ["flow"] = l.Flow ?? ""
            })
        },
        ["streamSettings"] = BuildStreamSettings(l, defaultSecurity: "tls")
    };

    private static JsonObject BuildShadowsocksOutbound(ShareLink l) => new()
    {
        ["tag"] = ProxyOutboundTag,
        ["protocol"] = "shadowsocks",
        ["settings"] = new JsonObject
        {
            ["servers"] = new JsonArray(new JsonObject
            {
                ["address"] = l.Host,
                ["port"] = l.Port,
                ["method"] = l.Method ?? "aes-256-gcm",
                ["password"] = l.Password ?? "",
                ["uot"] = true
            })
        },
        ["streamSettings"] = new JsonObject { ["network"] = "tcp" }
    };

    private static JsonObject BuildStreamSettings(ShareLink l, string defaultSecurity = "none")
    {
        // xray-core v24.9.30+ ввёл "raw" как алиас для "tcp"; принимаем оба, нормализуем к "tcp".
        var net = string.IsNullOrEmpty(l.Network) ? "tcp" : l.Network.ToLowerInvariant();
        if (net == "raw") net = "tcp";
        var sec = string.IsNullOrEmpty(l.Security) || l.Security == "none" ? defaultSecurity : l.Security;

        var ss = new JsonObject
        {
            ["network"] = net,
            ["security"] = sec
        };

        if (sec == "tls")
        {
            var tls = new JsonObject
            {
                ["allowInsecure"] = l.AllowInsecure
            };
            if (!string.IsNullOrEmpty(l.Sni)) tls["serverName"] = l.Sni;
            if (!string.IsNullOrEmpty(l.Fingerprint)) tls["fingerprint"] = l.Fingerprint;
            if (!string.IsNullOrEmpty(l.Alpn))
                tls["alpn"] = new JsonArray(l.Alpn.Split(',').Select(a => (JsonNode)a.Trim()).ToArray());
            ss["tlsSettings"] = tls;
        }
        else if (sec == "reality")
        {
            var reality = new JsonObject();
            if (!string.IsNullOrEmpty(l.Sni)) reality["serverName"] = l.Sni;
            if (!string.IsNullOrEmpty(l.Fingerprint)) reality["fingerprint"] = l.Fingerprint;
            if (!string.IsNullOrEmpty(l.PublicKey)) reality["publicKey"] = l.PublicKey;
            if (!string.IsNullOrEmpty(l.ShortId)) reality["shortId"] = l.ShortId;
            if (!string.IsNullOrEmpty(l.SpiderX)) reality["spiderX"] = l.SpiderX;
            ss["realitySettings"] = reality;
        }

        switch (net)
        {
            case "ws":
                {
                    var ws = new JsonObject
                    {
                        ["path"] = string.IsNullOrEmpty(l.Path) ? "/" : l.Path
                    };
                    if (!string.IsNullOrEmpty(l.HttpHost))
                        ws["headers"] = new JsonObject { ["Host"] = l.HttpHost };
                    ss["wsSettings"] = ws;
                    break;
                }
            case "httpupgrade":
                {
                    var hu = new JsonObject
                    {
                        ["path"] = string.IsNullOrEmpty(l.Path) ? "/" : l.Path
                    };
                    if (!string.IsNullOrEmpty(l.HttpHost)) hu["host"] = l.HttpHost;
                    ss["httpupgradeSettings"] = hu;
                    break;
                }
            case "splithttp":
            case "xhttp":
                {
                    var xh = new JsonObject
                    {
                        ["path"] = string.IsNullOrEmpty(l.Path) ? "/" : l.Path
                    };
                    if (!string.IsNullOrEmpty(l.HttpHost)) xh["host"] = l.HttpHost;
                    if (!string.IsNullOrEmpty(l.XhttpMode)) xh["mode"] = l.XhttpMode;
                    if (!string.IsNullOrEmpty(l.XhttpExtra))
                    {
                        try
                        {
                            var extra = JsonNode.Parse(l.XhttpExtra);
                            if (extra is JsonObject obj) xh["extra"] = obj;
                        }
                        catch { /* ignore malformed extra */ }
                    }
                    // xray-core 25.x понимает оба ключа, делаем оба для совместимости
                    ss["xhttpSettings"] = xh.DeepClone();
                    if (net == "splithttp") ss["splithttpSettings"] = xh.DeepClone();
                    break;
                }
            case "grpc":
                {
                    var grpc = new JsonObject
                    {
                        ["serviceName"] = l.ServiceName ?? l.Path ?? "",
                        ["multiMode"] = false
                    };
                    ss["grpcSettings"] = grpc;
                    break;
                }
            case "h2":
            case "http":
                {
                    var http = new JsonObject
                    {
                        ["path"] = l.Path ?? "/"
                    };
                    if (!string.IsNullOrEmpty(l.HttpHost))
                        http["host"] = new JsonArray(l.HttpHost.Split(',').Select(h => (JsonNode)h.Trim()).ToArray());
                    ss["httpSettings"] = http;
                    break;
                }
            case "kcp":
            case "mkcp":
                {
                    var kcp = new JsonObject
                    {
                        ["mtu"] = 1350,
                        ["tti"] = 50,
                        ["uplinkCapacity"] = 12,
                        ["downlinkCapacity"] = 100,
                        ["congestion"] = l.Congestion ?? false,
                        ["readBufferSize"] = 2,
                        ["writeBufferSize"] = 2
                    };
                    var hdr = new JsonObject { ["type"] = string.IsNullOrEmpty(l.HeaderType) ? "none" : l.HeaderType };
                    kcp["header"] = hdr;
                    if (!string.IsNullOrEmpty(l.Seed)) kcp["seed"] = l.Seed;
                    ss["kcpSettings"] = kcp;
                    break;
                }
            case "tcp":
            default:
                {
                    if (l.HeaderType == "http")
                    {
                        var tcp = new JsonObject
                        {
                            ["header"] = new JsonObject
                            {
                                ["type"] = "http",
                                ["request"] = new JsonObject
                                {
                                    ["path"] = new JsonArray(string.IsNullOrEmpty(l.Path) ? "/" : l.Path),
                                    ["headers"] = new JsonObject
                                    {
                                        ["Host"] = string.IsNullOrEmpty(l.HttpHost)
                                            ? new JsonArray()
                                            : new JsonArray(l.HttpHost.Split(',').Select(h => (JsonNode)h.Trim()).ToArray())
                                    }
                                }
                            }
                        };
                        ss["tcpSettings"] = tcp;
                    }
                    break;
                }
        }
        return ss;
    }

    public static JsonObject SocksInbound(int port) => new()
    {
        ["tag"] = SocksInboundTag,
        ["listen"] = "127.0.0.1",
        ["port"] = port,
        ["protocol"] = "socks",
        ["settings"] = new JsonObject
        {
            ["auth"] = "noauth",
            ["udp"] = true,
            ["userLevel"] = 8
        },
        ["sniffing"] = new JsonObject
        {
            ["enabled"] = true,
            ["destOverride"] = new JsonArray("http", "tls", "quic"),
            ["metadataOnly"] = false,
            ["routeOnly"] = false
        }
    };

    public static JsonObject DirectOutbound() => new()
    {
        ["tag"] = DirectOutboundTag,
        ["protocol"] = "freedom",
        ["settings"] = new JsonObject()
    };

    public static JsonObject BlockOutbound() => new()
    {
        ["tag"] = BlockOutboundTag,
        ["protocol"] = "blackhole",
        ["settings"] = new JsonObject()
    };

    /// <summary>
    /// DNS: DoH (1.1.1.1) + DoT (8.8.8.8) + plain UDP fallback. Обходит подмену DNS у провайдеров,
    /// при этом структурно совместим с любой версией geosite.dat (без фильтров domains/expectIPs).
    /// </summary>
    public static JsonObject DefaultDns() => new()
    {
        ["servers"] = new JsonArray(
            "https://1.1.1.1/dns-query",
            "https://dns.google/dns-query",
            "1.1.1.1",
            "8.8.8.8"
        ),
        ["queryStrategy"] = "UseIP"
    };

    public static JsonObject DefaultRouting() => new()
    {
        ["domainStrategy"] = "IPIfNonMatch",
        ["rules"] = new JsonArray(
            new JsonObject
            {
                ["type"] = "field",
                ["ip"] = new JsonArray(
                    "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
                    "::1/128", "fc00::/7", "fe80::/10"),
                ["outboundTag"] = DirectOutboundTag
            },
            new JsonObject
            {
                ["type"] = "field",
                ["inboundTag"] = new JsonArray(SocksInboundTag),
                ["outboundTag"] = ProxyOutboundTag,
                ["network"] = "tcp,udp"
            })
    };
}
