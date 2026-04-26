namespace Xrav.Core.Xray;

/// <summary>
/// Нормализованное представление ссылки vless/vmess/trojan/ss/hysteria2/tuic после парсинга.
/// </summary>
public sealed record ShareLink
{
    public required KeyKind Kind { get; init; }
    public required string Host { get; init; }
    public required int Port { get; init; }
    public string? UserId { get; init; }
    public string? Password { get; init; }
    public string Remark { get; init; } = "";
    public string Network { get; init; } = "tcp";
    public string Security { get; init; } = "none";
    public string? Sni { get; init; }
    public string? Alpn { get; init; }
    public string? Fingerprint { get; init; }
    public string? PublicKey { get; init; }
    public string? ShortId { get; init; }
    public string? SpiderX { get; init; }
    public string? Flow { get; init; }
    public string? Path { get; init; }
    public string? HttpHost { get; init; }
    public string? ServiceName { get; init; }
    public string? HeaderType { get; init; }
    public string? Encryption { get; init; }
    public string? Method { get; init; }
    public bool AllowInsecure { get; init; }
    public string Raw { get; init; } = "";
}

public enum KeyKind
{
    Vless,
    VMess,
    Trojan,
    Shadowsocks,
    Hysteria2,
    Tuic
}
