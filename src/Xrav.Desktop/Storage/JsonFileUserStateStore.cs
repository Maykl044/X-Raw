using System.Text.Json;
using System.Text.Json.Serialization;
using Xrav.Core.Domain;
using Xrav.Core.State;

namespace Xrav.Desktop.Storage;

/// <summary>Сохраняет состояние в <see cref="AppDataPaths.UserStateFile"/> (JSON, как <c>subscriptions_json</c> + ключи в одном файле для Windows).</summary>
public sealed class JsonFileUserStateStore : IUserStateStore
{
    private static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.CamelCase) },
        ReadCommentHandling = JsonCommentHandling.Disallow,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    public UserState Load()
    {
        var path = AppDataPaths.UserStateFile;
        if (!File.Exists(path))
            return UserState.Empty;

        try
        {
            var json = File.ReadAllText(path);
            var dto = JsonSerializer.Deserialize<StoreDto>(json, Json);
            if (dto is null) return UserState.Empty;
            return ToUserState(dto);
        }
        catch
        {
            return UserState.Empty;
        }
    }

    public void Save(UserState state)
    {
        Directory.CreateDirectory(AppDataPaths.XravRoot);
        var dto = ToDto(state);
        var json = JsonSerializer.Serialize(dto, Json);
        var path = AppDataPaths.UserStateFile;
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, json);
        if (File.Exists(path))
            File.Replace(tmp, path, null);
        else
            File.Move(tmp, path);
    }

    private static UserState ToUserState(StoreDto d)
    {
        var subs = d.Subscriptions?.Select(FromDto).ToList() ?? new List<SubscriptionEntry>();
        var filter = string.IsNullOrWhiteSpace(d.SubscriptionFilter) ? "@all" : d.SubscriptionFilter!;
        var keys = d.Keys?.Select(FromDto).Where(k => k is not null).Cast<VpnKey>().ToList() ?? new List<VpnKey>();
        return new UserState(subs, filter, keys, d.ActiveKeyId);
    }

    private static StoreDto ToDto(UserState s) => new()
    {
        Subscriptions = s.Subscriptions.Select(ToDto).ToList(),
        SubscriptionFilter = s.SubscriptionFilterEncoded,
        Keys = s.Keys.Select(ToDto).ToList(),
        ActiveKeyId = s.ActiveKeyId
    };

    private static SubscriptionEntryDto ToDto(SubscriptionEntry e) => new(
        e.Id, e.Url, e.Label, e.AddedAt, e.LastRefreshedAt, e.LastStatus);

    private static VpnKeyDto ToDto(VpnKey k) => new(
        k.Id, k.Remark, k.Protocol, k.Host, k.Port, k.Source, k.Raw, k.SubscriptionId);

    private static SubscriptionEntry FromDto(SubscriptionEntryDto e) =>
        new(e.Id, e.Url, e.Label, e.AddedAt, e.LastRefreshedAt, e.LastStatus);

    private static VpnKey? FromDto(VpnKeyDto? d)
    {
        if (d is null || string.IsNullOrWhiteSpace(d.Id) || d.Raw is null) return null;
        return new VpnKey(d.Id, d.Remark, d.Protocol, d.Host, d.Port, d.Source, d.Raw, d.SubscriptionId);
    }
}

internal sealed class StoreDto
{
    public List<SubscriptionEntryDto> Subscriptions { get; set; } = new();
    public string SubscriptionFilter { get; set; } = "@all";
    public List<VpnKeyDto> Keys { get; set; } = new();
    public string? ActiveKeyId { get; set; }
}

internal sealed record SubscriptionEntryDto(
    string Id, string Url, string Label, long AddedAt, long? LastRefreshedAt, string? LastStatus);

internal sealed record VpnKeyDto(
    string Id, string Remark, KeyProtocol Protocol, string? Host, int? Port, KeySource Source, string Raw, string? SubscriptionId);
