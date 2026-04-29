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
    public const string SocksInboundTag = "socks";
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

        // Metrics outbound + dokodemo-door inbound для real-time счётчиков (как у v2rayN).
        outbounds.Add(new JsonObject
        {
            ["tag"] = "metrics_out",
            ["protocol"] = "freedom",
            ["settings"] = new JsonObject()
        });

        var inbounds = new JsonArray(
            SocksInbound(socksInboundPort),
            HttpInbound(socksInboundPort + 1),
            MetricsInbound(11111));

        var root = new JsonObject
        {
            ["log"] = new JsonObject { ["loglevel"] = "warning" },
            ["dns"] = DefaultDns(),
            ["inbounds"] = inbounds,
            ["outbounds"] = outbounds,
            ["routing"] = DefaultRouting(),
            ["policy"] = DefaultPolicy(),
            ["stats"] = new JsonObject(),
            ["metrics"] = new JsonObject { ["tag"] = "metrics_out" }
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

        // Mux в формате v2rayN: объект всегда присутствует (с enabled=false по умолчанию),
        // включаем только если явно запрошено и протокол это допускает. xudpConcurrency / xudpProxyUDP443
        // пишем всегда — xray-core их спокойно принимает без эффекта если mux off.
        var canMux = opts.EnableMux
                     && link.Kind is KeyKind.Vless or KeyKind.VMess or KeyKind.Trojan
                     && !string.Equals(link.Flow, "xtls-rprx-vision", StringComparison.OrdinalIgnoreCase);
        ob["mux"] = new JsonObject
        {
            ["enabled"] = canMux,
            ["concurrency"] = canMux ? opts.MuxConcurrency : -1,
            ["xudpConcurrency"] = opts.XudpConcurrency,
            ["xudpProxyUDP443"] = opts.XudpProxyUDP443
        };

        return ob;
    }

    /// <summary>
    /// Нормализует ALPN-список из share-link: lower-case, trim, отбрасываем пустые и неизвестные;
    /// если передан h3 для не-QUIC транспорта (xhttp/ws/tcp/h2/grpc) — пишем warning в лог
    /// (h3 = QUIC = UDP, xray client transport идёт поверх TCP, сервер должен поддерживать h2/h1).
    /// </summary>
    private static JsonArray? NormalizeAlpn(string? raw, string network)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        var allowed = new HashSet<string> { "h2", "http/1.1", "h3" };
        var items = raw.Split(',', StringSplitOptions.RemoveEmptyEntries)
                       .Select(s => s.Trim().ToLowerInvariant())
                       .Where(s => allowed.Contains(s))
                       .Distinct()
                       .ToArray();
        if (items.Length == 0) return null;
        bool isQuicTransport = network == "quic";
        if (!isQuicTransport && items.Contains("h3"))
        {
            try { Console.Error.WriteLine("[xray-config] warning: ALPN h3 указан для не-QUIC транспорта (" + network + ") — сервер должен также поддерживать h2/http1.1, иначе TLS handshake провалится."); }
            catch { }
        }
        return new JsonArray(items.Select(a => (JsonNode)a).ToArray());
    }

    private static JsonObject BuildFragmentOutbound(XrayBuildOptions opts)
    {
        var fragment = new JsonObject
        {
            ["packets"] = opts.FragmentPackets,
            ["length"] = opts.FragmentLength,
            ["interval"] = opts.FragmentInterval
        };
        if (!string.IsNullOrEmpty(opts.FragmentMaxSplit))
            fragment["maxSplit"] = opts.FragmentMaxSplit;
        var settings = new JsonObject
        {
            ["fragment"] = fragment
        };
        if (opts.EnableNoise)
        {
            settings["noise"] = new JsonObject
            {
                ["type"] = "rand",
                ["packet"] = opts.NoisePacket,
                ["delay"] = opts.NoiseDelay
            };
        }
        // Формат v2rayN: streamSettings.network=raw, sockopt.TcpNoDelay=true, sockopt.mark=255.
        return new JsonObject
        {
            ["tag"] = FragmentOutboundTag,
            ["protocol"] = "freedom",
            ["settings"] = settings,
            ["streamSettings"] = new JsonObject
            {
                ["network"] = "raw",
                ["security"] = "",
                ["sockopt"] = new JsonObject
                {
                    ["TcpNoDelay"] = true,
                    ["mark"] = 255
                }
            }
        };
    }

    private static JsonObject BuildVlessOutbound(ShareLink l)
    {
        var user = new JsonObject
        {
            ["id"] = l.UserId ?? "",
            ["encryption"] = l.Encryption ?? "none",
            ["level"] = 8,
            ["security"] = "auto"
        };
        if (!string.IsNullOrEmpty(l.Flow)) user["flow"] = l.Flow!;
        return new JsonObject
        {
            ["tag"] = ProxyOutboundTag,
            ["protocol"] = "vless",
            ["settings"] = new JsonObject
            {
                ["vnext"] = new JsonArray(new JsonObject
                {
                    ["address"] = l.Host,
                    ["port"] = l.Port,
                    ["users"] = new JsonArray(user)
                })
            },
            ["streamSettings"] = BuildStreamSettings(l)
        };
    }

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
                    ["security"] = string.IsNullOrEmpty(l.Encryption) ? "auto" : l.Encryption,
                    ["level"] = 8
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
            var alpnArr = NormalizeAlpn(l.Alpn, net);
            if (alpnArr is not null) tls["alpn"] = alpnArr;
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
            // xray-core REALITY поддерживает alpn — кладём если задан в ссылке.
            var alpnArr = NormalizeAlpn(l.Alpn, net);
            if (alpnArr is not null) reality["alpn"] = alpnArr;
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
            var alpnArr = NormalizeAlpn(l.Alpn, net);
            if (alpnArr is not null) tls["alpn"] = alpnArr;
            ss["security"] = "tls";
            ss["tlsSettings"] = tls;
        }

        switch (net)
        {
            case "ws":
            case "websocket":
                {
                    // Сохраняем path КАК ЕСТЬ из share-link (вкл. пустую строку) — некоторые CDN-backendы
                    // (b-cdn.net, fly.io и др.) требуют пустой path и отвергают Upgrade /. Это поведение
                    // v2rayN, который референсный клиент для этих ключей.
                    var path = l.Path ?? "";
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
                        // Формат v2rayN: только headers.Host (без дублирования в wsSettings.host — xray-core этого не ждёт).
                        ws["headers"] = new JsonObject { ["Host"] = l.HttpHost };
                    }
                    if (maxEarlyData > 0)
                    {
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
            ["enabled"] = false,
            ["destOverride"] = new JsonArray()
        }
    };

    public static JsonObject HttpInbound(int port) => new()
    {
        ["tag"] = "http",
        ["listen"] = "127.0.0.1",
        ["port"] = port,
        ["protocol"] = "http",
        ["settings"] = new JsonObject { ["userLevel"] = 8 },
        ["sniffing"] = new JsonObject
        {
            ["enabled"] = false,
            ["destOverride"] = new JsonArray()
        }
    };

    public static JsonObject MetricsInbound(int port) => new()
    {
        ["tag"] = "metrics_in",
        ["listen"] = "127.0.0.1",
        ["port"] = port,
        ["protocol"] = "dokodemo-door",
        ["settings"] = new JsonObject { ["address"] = "127.0.0.1" }
    };

    /// <summary>Политика handshake/idle (в формате v2rayN).</summary>
    public static JsonObject DefaultPolicy() => new()
    {
        ["levels"] = new JsonObject
        {
            ["0"] = new JsonObject
            {
                ["statsUserDownlink"] = true,
                ["statsUserUplink"] = true
            },
            ["8"] = new JsonObject
            {
                ["connIdle"] = 300,
                ["downlinkOnly"] = 1,
                ["handshake"] = 4,
                ["uplinkOnly"] = 1
            }
        },
        ["system"] = new JsonObject
        {
            ["statsInboundDownlink"] = true,
            ["statsInboundUplink"] = true,
            ["statsOutboundDownlink"] = true,
            ["statsOutboundUplink"] = true
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
    /// DNS в формате v2rayN: 1.1.1.1 как основной + 8.8.8.8 как fallback, с queryStrategy=UseIP
    /// и hosts-маппингом googleapis.cn → googleapis.com (стандартный bypass для китайских доменов).
    /// </summary>
    public static JsonObject DefaultDns() => new()
    {
        ["hosts"] = new JsonObject
        {
            ["domain:googleapis.cn"] = "googleapis.com"
        },
        ["queryStrategy"] = "UseIP",
        ["servers"] = new JsonArray(
            "1.1.1.1",
            new JsonObject
            {
                ["address"] = "1.1.1.1",
                ["domains"] = new JsonArray(),
                ["port"] = 53
            },
            new JsonObject
            {
                ["address"] = "8.8.8.8",
                ["domains"] = new JsonArray(),
                ["port"] = 53
            })
    };

    public static JsonObject DefaultRouting() => new()
    {
        ["domainStrategy"] = "IPIfNonMatch",
        ["rules"] = new JsonArray(
            new JsonObject
            {
                ["inboundTag"] = new JsonArray("metrics_in"),
                ["outboundTag"] = "metrics_out"
            },
            new JsonObject
            {
                ["inboundTag"] = new JsonArray(SocksInboundTag),
                ["outboundTag"] = ProxyOutboundTag,
                ["port"] = "53"
            },
            new JsonObject
            {
                ["ip"] = new JsonArray("1.1.1.1"),
                ["outboundTag"] = ProxyOutboundTag,
                ["port"] = "53"
            },
            new JsonObject
            {
                ["ip"] = new JsonArray("8.8.8.8"),
                ["outboundTag"] = DirectOutboundTag,
                ["port"] = "53"
            })
    };
}
