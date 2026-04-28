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
    /// Бейдж слева от имени ключа: если в Remark есть emoji-флаг страны (regional
    /// indicator pair, U+1F1E6..U+1F1FF), показываем флаг; иначе короткое имя протокола.
    /// </summary>
    public string DisplayBadge => FlagExtractor.TryExtract(Remark) ?? Protocol.ShortLabel();

    /// <summary>true если бейдж — это эмодзи-флаг (рендерится крупнее, без фона).</summary>
    public bool BadgeIsFlag => FlagExtractor.TryExtract(Remark) is not null;

    public string Subtitle => $"{ShortProtocolLabel} · {Host ?? "—"}";
    public string LatencyDisplay => LatencyMs is null ? "—" : $"{LatencyMs} мс";
}

/// <summary>
/// Достаём emoji-флаг из remark.  Флаг = пара кодпоинтов из диапазона
/// regional indicator (U+1F1E6..U+1F1FF), напр. 🇫🇮 = U+1F1EB U+1F1EE.
/// Также распознаём ⚑ (U+2691) и тп.
/// </summary>
public static class FlagExtractor
{
    public static string? TryExtract(string? text)
    {
        if (string.IsNullOrEmpty(text)) return null;
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
                    if (cp2 >= 0x1F1E6 && cp2 <= 0x1F1FF)
                        return text.Substring(i, 4);
                }
                i += 2;
            }
            else i++;
        }
        return null;
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
