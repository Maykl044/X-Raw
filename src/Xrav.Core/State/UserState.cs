using Xrav.Core.Domain;

namespace Xrav.Core.State;

/// <summary>Снимок пользовательских данных: суть Android prefs + список ключей.</summary>
public sealed record UserState(
    IReadOnlyList<SubscriptionEntry> Subscriptions,
    string SubscriptionFilterEncoded,
    IReadOnlyList<VpnKey> Keys,
    string? ActiveKeyId = null
)
{
    public static UserState Empty { get; } = new(
        Array.Empty<SubscriptionEntry>(),
        "@all",
        Array.Empty<VpnKey>(),
        null);
}
