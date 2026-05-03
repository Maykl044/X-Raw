using System.Collections.ObjectModel;
using Xrav.Core.Domain;

namespace Xrav.Desktop.ViewModels;

/// <summary>
/// Группа ключей для отображения в ServersView. Соответствует одной подписке
/// (или специальной группе "Ручные" для VpnKey без SubscriptionId).
/// Header содержит домен/метку подписки + дату последнего обновления, тело —
/// список ключей. Раскладка в стиле Happ 2.9.0.
/// </summary>
public sealed class KeyGroup
{
    public KeyGroup(string id, string label, string lastUpdate, IEnumerable<VpnKey> keys, bool isManual = false)
    {
        Id = id;
        Label = label;
        LastUpdate = lastUpdate;
        IsManual = isManual;
        Keys = new ObservableCollection<VpnKey>(keys);
    }

    /// <summary>SubscriptionId или "@manual" для группы ручных ключей.</summary>
    public string Id { get; }

    /// <summary>Отображаемое имя подписки (домен) или "Ручные ключи".</summary>
    public string Label { get; }

    /// <summary>Краткая строка последнего обновления, например "02.05.2026 18:15", либо пусто.</summary>
    public string LastUpdate { get; }

    /// <summary>true для группы локально импортированных ключей.</summary>
    public bool IsManual { get; }

    public ObservableCollection<VpnKey> Keys { get; }

    public int Count => Keys.Count;
}
