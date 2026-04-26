namespace Xrav.Core.Domain;

public sealed record SubscriptionEntry(
    string Id,
    string Url,
    string Label,
    long AddedAt,
    long? LastRefreshedAt = null,
    string? LastStatus = null
);

public abstract record SubscriptionFilter
{
    public static AllFilter All { get; } = new();
    public static ManualFilter Manual { get; } = new();

    public sealed record Specific(string SubscriptionId) : SubscriptionFilter
    {
        public override string Encode() => SubscriptionId;
    }

    public sealed record AllFilter : SubscriptionFilter
    {
        public override string Encode() => "@all";
    }

    public sealed record ManualFilter : SubscriptionFilter
    {
        public override string Encode() => "@manual";
    }

    public abstract string Encode();

    public static SubscriptionFilter Decode(string? raw) => raw switch
    {
        null or "" or "@all" => All,
        "@manual" => Manual,
        _ => new Specific(raw)
    };
}

public static class VpnKeyFilterExtensions
{
    public static IReadOnlyList<VpnKey> ApplyFilter(
        this IReadOnlyList<VpnKey> keys,
        SubscriptionFilter filter) =>
        filter switch
        {
            SubscriptionFilter.AllFilter => keys,
            SubscriptionFilter.ManualFilter =>
                keys.Where(k => k.Source != KeySource.Subscription).ToList(),
            SubscriptionFilter.Specific s =>
                keys.Where(k => k.SubscriptionId == s.SubscriptionId).ToList(),
            _ => keys
        };
}
