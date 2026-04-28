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
    public const string FragmentOutboundTag = "fragment";

    public static string BuildFromShareLink(ShareLink link, int socksInboundPort = DefaultSocksInboundPort)
        => BuildFromShareLink(link, XrayBuildOptions.Default, socksInboundPort);

    public static string BuildFromShareLink(ShareLink link, XrayBuildOptions opts, int socksInboundPort = DefaultSocksInboundPort)
    {
        var sec = (link.Security ?? "").ToLowerInvariant();
        var hasTls = sec == "tls" || sec == "reality" || sec == "xtls";
        // Fragment work только с TLS — для plain TCP нечего фрагментировать.
        var useFragment = opts.EnableFragment && hasTls;

        var proxyOut = BuildOutbound(link, useFragment, opts);
        var outbounds = new JsonArray();
        outbounds.Add(proxyOut);
        if (useFragment) outbounds.Add(BuildFragmentOutbound(opts));
        outbounds.Add(DirectOutbound());
        outbounds.Add(BlockOutbound());

        var root = new JsonObject
        {
            ["log"] = new JsonObject { ["loglevel"] = "warning" },
            ["dns"] = DefaultDns(),
            ["inbounds"] = new JsonArray(SocksInbound(socksInboundPort)),
            ["outbounds"] = outbounds,
            ["routing"] = DefaultRouting()
        };
        return root.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
    }

    public static JsonObject BuildOutbound(ShareLink link) => BuildOutbound(link, useFragment: false, XrayBuildOptions.Default);

    public static JsonObject BuildOutbound(ShareLink link, bool useFragment, XrayBuildOptions opts)
    {
        var ob = link.Kind switch
        {
            KeyKind.Vless => BuildVlessOutbound(link),
            KeyKind.VMess => BuildVmessOutbound(link),
            KeyKind.Trojan => BuildTrojanOutbound(link),
            KeyKind.Shadowsocks => BuildShadowsocksOutbound(link),
            _ => throw new NotSupportedException($"xray outbound build not supported: {link.Kind}")
        };

        if (useFragment)
        {
            // Цепляем proxy-outbound через fragment-outbound: все исходящие TCP уходят
            // сначала в fragment (xray freedom + fragment{tlshello}), который рвёт
            // TLS-ClientHello на куски — DPI теряет SNI и пропускает пакет.
            var ss = ob["streamSettings"] as JsonObject;
            if (ss is not null)
            {
                var sockopt = (ss["sockopt"] as JsonObject) ?? new JsonObject();
                sockopt["dialerProxy"] = FragmentOutboundTag;
                ss["sockopt"] = sockopt;
            }
        }

        // Mux.cool — мультиплексирует несколько TCP-стримов в одном канале. Не работает
        // для Shadowsocks и для VLESS с xtls-rprx-vision.
        var canMux = opts.EnableMux
                     && link.Kind is KeyKind.Vless or KeyKind.VMess or KeyKind.Trojan
                     && !string.Equals(link.Flow, "xtls-rprx-vision", StringComparison.OrdinalIgnoreCase);
        if (canMux)
        {
            ob["mux"] = new JsonObject
            {
                ["enabled"] = true,
                ["concurrency"] = opts.MuxConcurrency
            };
        }

        return ob;
    }

    private static JsonObject BuildFragmentOutbound(XrayBuildOptions opts)
    {
        var settings = new JsonObject
        {
            ["domainStrategy"] = "AsIs",
            ["fragment"] = new JsonObject
            {
                ["packets"] = opts.FragmentPackets,
                ["length"] = opts.FragmentLength,
                ["interval"] = opts.FragmentInterval
            }
        };
        if (opts.EnableNoise)
        {
            // Mihomo/xray noise: рандомные пакеты до handshake. Экспериментально, помогает
            // когда DPI блокирует по таймингу TLS handshake.
            settings["noise"] = new JsonObject
            {
                ["type"] = "rand",
                ["packet"] = opts.NoisePacket,
                ["delay"] = opts.NoiseDelay
            };
        }
        return new JsonObject
        {
            ["tag"] = FragmentOutboundTag,
            ["protocol"] = "freedom",
            ["settings"] = settings,
            ["streamSettings"] = new JsonObject
            {
                ["sockopt"] = new JsonObject { ["tcpKeepAliveIdle"] = 100 }
            }
        };
    }

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
            tls["fingerprint"] = string.IsNullOrEmpty(l.Fingerprint) ? "chrome" : l.Fingerprint;
            if (!string.IsNullOrEmpty(l.Alpn))
                tls["alpn"] = new JsonArray(l.Alpn.Split(',').Select(a => (JsonNode)a.Trim()).ToArray());
            ss["tlsSettings"] = tls;
        }
        else if (sec == "reality")
        {
            var reality = new JsonObject();
            if (!string.IsNullOrEmpty(l.Sni)) reality["serverName"] = l.Sni;
            reality["fingerprint"] = string.IsNullOrEmpty(l.Fingerprint) ? "chrome" : l.Fingerprint;
            if (!string.IsNullOrEmpty(l.PublicKey)) reality["publicKey"] = l.PublicKey;
            if (!string.IsNullOrEmpty(l.ShortId)) reality["shortId"] = l.ShortId;
            if (!string.IsNullOrEmpty(l.SpiderX)) reality["spiderX"] = l.SpiderX;
            ss["realitySettings"] = reality;
        }
        else if (sec == "xtls")
        {
            // legacy XTLS: маппим как TLS (xray v25 устарел XTLS, но ключ должен подключаться)
            var tls = new JsonObject
            {
                ["allowInsecure"] = l.AllowInsecure
            };
            if (!string.IsNullOrEmpty(l.Sni)) tls["serverName"] = l.Sni;
            tls["fingerprint"] = string.IsNullOrEmpty(l.Fingerprint) ? "chrome" : l.Fingerprint;
            if (!string.IsNullOrEmpty(l.Alpn))
                tls["alpn"] = new JsonArray(l.Alpn.Split(',').Select(a => (JsonNode)a.Trim()).ToArray());
            ss["security"] = "tls";
            ss["tlsSettings"] = tls;
        }

        switch (net)
        {
            case "ws":
            case "websocket":
                {
                    // Парсим Early-Data из path: «/jarvic?ed=2048» → path=«/jarvic», maxEarlyData=2048
                    var path = string.IsNullOrEmpty(l.Path) ? "/" : l.Path;
                    int maxEarlyData = 0;
                    var qIdx = path.IndexOf('?');
                    if (qIdx >= 0)
                    {
                        var pathQs = path.Substring(qIdx + 1);
                        path = path.Substring(0, qIdx);
                        foreach (var kv in pathQs.Split('&', StringSplitOptions.RemoveEmptyEntries))
                        {
                            var eq = kv.IndexOf('=');
                            if (eq <= 0) continue;
                            var k = kv.Substring(0, eq);
                            var v = kv.Substring(eq + 1);
                            if (k.Equals("ed", StringComparison.OrdinalIgnoreCase) && int.TryParse(v, out var ed))
                                maxEarlyData = ed;
                        }
                    }

                    var ws = new JsonObject
                    {
                        ["path"] = path
                    };
                    if (!string.IsNullOrEmpty(l.HttpHost))
                    {
                        // xray v25.x принимает оба варианта — пишем оба для совместимости
                        ws["host"] = l.HttpHost;
                        ws["headers"] = new JsonObject { ["Host"] = l.HttpHost };
                    }
                    if (maxEarlyData > 0)
                    {
                        // Early-Data: первые N байт payload улетают в Sec-WebSocket-Protocol header.
                        // Используется для обхода DPI и ускорения handshake.
                        ws["maxEarlyData"] = maxEarlyData;
                        ws["earlyDataHeaderName"] = "Sec-WebSocket-Protocol";
                    }
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
