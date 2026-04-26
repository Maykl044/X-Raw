namespace Xrav.Core.Domain;

/// <summary>Импортированный ключ — одна строка в списке серверов (логически соответствует VpnKey в Android).</summary>
public sealed record VpnKey(
    string Id,
    string Remark,
    KeyProtocol Protocol,
    string? Host,
    int? Port,
    KeySource Source,
    string Raw,
    string? SubscriptionId = null
)
{
    public string ShortProtocolLabel => Protocol.ShortLabel();
    public string Subtitle => $"{ShortProtocolLabel} · {Host ?? "—"}";
}

public enum KeyProtocol
{
    Vless,
    VMess,
    Trojan,
    Shadowsocks,
    Hysteria2,
    Tuic,
    Json,
    Unknown
}

public static class KeyProtocolExtensions
{
    public static string ShortLabel(this KeyProtocol p) => p switch
    {
        KeyProtocol.Vless => "VLESS",
        KeyProtocol.VMess => "VMess",
        KeyProtocol.Trojan => "Trojan",
        KeyProtocol.Shadowsocks => "SS",
        KeyProtocol.Hysteria2 => "HY2",
        KeyProtocol.Tuic => "TUIC",
        KeyProtocol.Json => "JSON",
        _ => "?"
    };
}

public enum KeySource
{
    Subscription,
    ManualUri,
    ManualJson
}
