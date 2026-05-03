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
    /// <summary>Последний измеренный TCP RTT (мс), или null если не пинговали.</summary>
    public int? LatencyMs { get; init; }

    /// <summary>
    /// Полный JSON-конфиг (xray для VLESS/VMess/Trojan/SS, sing-box для Hy2/TUIC,
    /// либо raw JSON если импортирован напрямую). Строится при импорте.
    /// </summary>
    public string? FullConfig { get; init; }

    /// <summary>Тип конфига: "xray" / "sing-box" / "json".</summary>
    public string? FullConfigKind { get; init; }

    public string ShortProtocolLabel => Protocol.ShortLabel();

    /// <summary>
    /// Бейдж слева от имени ключа: ISO-2 код страны (FI/RU/DE/…) если в Remark
    /// найден regional-indicator pair, иначе короткое имя протокола.
    /// </summary>
    public string DisplayBadge => FlagExtractor.TryExtractCode(Remark) ?? Protocol.ShortLabel();

    /// <summary>Сам эмодзи-флаг (для тултипа), null если нет.</summary>
    public string? FlagEmoji => FlagExtractor.TryExtractEmoji(Remark);

    /// <summary>true если бейдж — это код страны (а не имя протокола).</summary>
    public bool BadgeIsFlag => FlagExtractor.TryExtractCode(Remark) is not null;

    public string Subtitle => $"{ShortProtocolLabel} · {Host ?? "—"}";
    public string LatencyDisplay => LatencyMs is null ? "—" : $"{LatencyMs} мс";

    /// <summary>Стек протокол/транспорт/security вида "VLESS / WS / TLS" (как в Happ).
    /// Транспорт и security вытаскиваются из query share-link'а; если их нет — только протокол.</summary>
    public string ProtocolStack
    {
        get
        {
            var parts = new List<string> { ShortProtocolLabel };
            string? type = null, security = null;
            try
            {
                if (!string.IsNullOrEmpty(Raw) && Uri.TryCreate(Raw, UriKind.Absolute, out var uri))
                {
                    var q = uri.Query.TrimStart('?');
                    foreach (var kv in q.Split('&', StringSplitOptions.RemoveEmptyEntries))
                    {
                        var eq = kv.IndexOf('=');
                        if (eq <= 0) continue;
                        var key = kv.Substring(0, eq).ToLowerInvariant();
                        var val = Uri.UnescapeDataString(kv[(eq + 1)..]);
                        if (key == "type") type = val;
                        else if (key == "security") security = val;
                    }
                }
            }
            catch { /* ignore parse */ }

            if (!string.IsNullOrWhiteSpace(type)) parts.Add(type!.ToUpperInvariant());
            if (!string.IsNullOrWhiteSpace(security) && !string.Equals(security, "none", StringComparison.OrdinalIgnoreCase))
                parts.Add(security!.ToUpperInvariant());
            return string.Join(" / ", parts);
        }
    }

    /// <summary>Ключ группировки в списке: SubscriptionId либо "@manual" для ручных.</summary>
    public string GroupKey => string.IsNullOrEmpty(SubscriptionId) ? "@manual" : SubscriptionId!;
}

/// <summary>
/// Достаём флаг-страну из remark. Regional-indicator pair = две буквы из диапазона
/// U+1F1E6..U+1F1FF (соответствуют 'A'..'Z'). Возвращаем либо ISO-2 код (FI),
/// либо сам эмодзи (🇫🇮).
/// </summary>
public static class FlagExtractor
{
    public static string? TryExtractEmoji(string? text)
    {
        var idx = FindFlagIndex(text);
        return idx >= 0 ? text!.Substring(idx, 4) : null;
    }

    public static string? TryExtractCode(string? text)
    {
        var idx = FindFlagIndex(text);
        if (idx < 0) return null;
        int cp1 = char.ConvertToUtf32(text!, idx);
        int cp2 = char.ConvertToUtf32(text!, idx + 2);
        return new string(new[] {
            (char)('A' + (cp1 - 0x1F1E6)),
            (char)('A' + (cp2 - 0x1F1E6))
        });
    }

    private static int FindFlagIndex(string? text)
    {
        if (string.IsNullOrEmpty(text)) return -1;
        int i = 0;
        while (i + 3 < text.Length)
        {
            if (char.IsHighSurrogate(text[i]) && char.IsLowSurrogate(text[i + 1]))
            {
                int cp1 = char.ConvertToUtf32(text, i);
                if (cp1 >= 0x1F1E6 && cp1 <= 0x1F1FF
                    && char.IsHighSurrogate(text[i + 2]) && char.IsLowSurrogate(text[i + 3]))
                {
                    int cp2 = char.ConvertToUtf32(text, i + 2);
                    if (cp2 >= 0x1F1E6 && cp2 <= 0x1F1FF) return i;
                }
                i += 2;
            }
            else i++;
        }
        return -1;
    }
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
