using System.Text.Json;
using System.Text.Json.Nodes;

namespace Xrav.Desktop.Xray;

/// <summary>
/// Подготавливает импортированный пользовательский <c>config.json</c> для использования в качестве backend xray на Windows:
/// убирает sockopt.mark, чистит TUN inbound, гарантирует SOCKS inbound на 127.0.0.1:10808 и корректный routing на outbound с tag=proxy.
/// </summary>
public static class ImportedXrayJsonPatcher
{
    public static string PatchForWindowsTunnel(string rawJson)
    {
        if (string.IsNullOrWhiteSpace(rawJson))
            throw new InvalidOperationException("Пустой JSON.");
        var root = JsonNode.Parse(rawJson) as JsonObject
            ?? throw new InvalidOperationException("Корень конфига должен быть объектом.");
        RemoveSockoptMark(root);
        StripTunInbound(root);
        EnsureDns(root);
        EnsureSocksInbound(root);
        EnsureProxyOutboundTag(root);
        EnsureDefaultRouting(root);
        return root.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
    }

    private static void RemoveSockoptMark(JsonObject root)
    {
        if (root["outbounds"] is not JsonArray outBounds) return;
        foreach (var ob in outBounds.OfType<JsonObject>())
        {
            if (ob["streamSettings"] is not JsonObject ss) continue;
            if (ss["sockopt"] is JsonObject sock) sock.Remove("mark");
        }
    }

    private static void StripTunInbound(JsonObject root)
    {
        if (root["inbounds"] is not JsonArray inbounds) return;
        var filtered = new JsonArray();
        foreach (var item in inbounds)
        {
            if (item is not JsonObject inb) continue;
            var proto = inb["protocol"]?.GetValue<string?>();
            if (string.Equals(proto, "tun", StringComparison.OrdinalIgnoreCase)) continue;
            filtered.Add(JsonNode.Parse(inb.ToJsonString())!);
        }
        root["inbounds"] = filtered;
    }

    private static void EnsureDns(JsonObject root)
    {
        if (root["dns"] is not JsonObject dns)
        {
            root["dns"] = DefaultDns();
            return;
        }
        if (dns["servers"] is not JsonArray servers || servers.Count == 0)
            dns["servers"] = new JsonArray { "1.1.1.1", "8.8.8.8" };
    }

    private static JsonObject DefaultDns() =>
        new()
        {
            ["servers"] = new JsonArray { "1.1.1.1", "8.8.8.8" }
        };

    private static void EnsureSocksInbound(JsonObject root)
    {
        var inbounds = root["inbounds"] as JsonArray ?? new JsonArray();
        root["inbounds"] = inbounds;
        foreach (var item in inbounds.OfType<JsonObject>())
        {
            var p = item["protocol"]?.GetValue<string?>();
            var port = item["port"]?.GetValue<int?>();
            var listen = item["listen"]?.GetValue<string?>();
            if (string.Equals(p, "socks", StringComparison.OrdinalIgnoreCase) &&
                port == Tunnel.TunnelConstants.SocksInboundPort &&
                (string.IsNullOrEmpty(listen) || listen == "127.0.0.1"))
            {
                // Принудительно проставим тэг для маршрутизации
                item["tag"] = Tunnel.TunnelConstants.XraySocksInboundTag;
                return;
            }
        }
        inbounds.Add(SocksInbound());
    }

    private static JsonObject SocksInbound() =>
        new()
        {
            ["tag"] = Tunnel.TunnelConstants.XraySocksInboundTag,
            ["port"] = Tunnel.TunnelConstants.SocksInboundPort,
            ["listen"] = "127.0.0.1",
            ["protocol"] = "socks",
            ["settings"] = new JsonObject
            {
                ["auth"] = "noauth",
                ["udp"] = true,
                ["userLevel"] = Tunnel.TunnelConstants.UserLevel
            },
            ["sniffing"] = DefaultSniffing()
        };

    private static JsonObject DefaultSniffing() =>
        new()
        {
            ["enabled"] = true,
            ["destOverride"] = new JsonArray { "http", "tls", "quic" },
            ["metadataOnly"] = false,
            ["routeOnly"] = false
        };

    /// <summary>Гарантирует наличие хотя бы одного outbound с tag=proxy. Если нет — присваивает первому.</summary>
    private static void EnsureProxyOutboundTag(JsonObject root)
    {
        var outbounds = root["outbounds"] as JsonArray;
        if (outbounds is null || outbounds.Count == 0)
        {
            // конфиг без outbound — сломан, но добавим freedom как заглушку
            outbounds = new JsonArray(new JsonObject
            {
                ["tag"] = "proxy",
                ["protocol"] = "freedom",
                ["settings"] = new JsonObject()
            });
            root["outbounds"] = outbounds;
            return;
        }
        var hasProxy = outbounds.OfType<JsonObject>()
            .Any(o => o["tag"]?.GetValue<string?>() == "proxy");
        if (!hasProxy)
        {
            // ставим tag=proxy первому outbound (skip blackhole/dns)
            JsonObject? target = null;
            foreach (var ob in outbounds.OfType<JsonObject>())
            {
                var p = ob["protocol"]?.GetValue<string?>();
                if (p is "blackhole" or "dns") continue;
                target = ob; break;
            }
            target ??= outbounds.OfType<JsonObject>().FirstOrDefault();
            if (target is not null) target["tag"] = "proxy";
        }
    }

    private static void EnsureDefaultRouting(JsonObject root)
    {
        var routing = root["routing"] as JsonObject ?? new JsonObject();
        root["routing"] = routing;
        if (routing["domainStrategy"] is not JsonValue dsv
            || string.IsNullOrWhiteSpace(dsv.GetValue<string>()))
            routing["domainStrategy"] = "IPIfNonMatch";

        var rules = routing["rules"] as JsonArray ?? new JsonArray();
        routing["rules"] = rules;
        if (rules.Count == 0)
        {
            rules.Add(PrivateDirectRule());
            rules.Add(SocksToProxyRule());
            return;
        }
        if (!IsRoutingAlreadyCoversSocksIn(rules))
        {
            rules.Add(SocksToProxyRule());
        }
    }

    private static JsonObject PrivateDirectRule() =>
        new()
        {
            ["type"] = "field",
            ["ip"] = new JsonArray
            {
                "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
                "::1/128", "fc00::/7", "fe80::/10"
            },
            ["outboundTag"] = "direct"
        };

    private static JsonObject SocksToProxyRule() =>
        new()
        {
            ["type"] = "field",
            ["inboundTag"] = new JsonArray { Tunnel.TunnelConstants.XraySocksInboundTag },
            ["outboundTag"] = "proxy",
            ["network"] = "tcp,udp"
        };

    private static bool IsRoutingAlreadyCoversSocksIn(JsonArray rules)
    {
        foreach (var r in rules.OfType<JsonObject>())
        {
            var tag = r["inboundTag"];
            if (tag is JsonArray arr)
            {
                foreach (var t in arr)
                {
                    if (t?.GetValue<string?>() == Tunnel.TunnelConstants.XraySocksInboundTag)
                        return true;
                }
            }
        }
        return false;
    }
}
