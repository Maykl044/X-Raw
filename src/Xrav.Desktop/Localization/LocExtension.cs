using System.ComponentModel;
using System.Globalization;
using System.Windows.Data;
using System.Windows.Markup;

namespace Xrav.Desktop.Localization;

/// <summary>
/// XAML markup extension для коротких локализованных строк:
/// <c>&lt;TextBlock Text="{loc:T servers.title}" /&gt;</c>.
/// Сама подписывается на <see cref="LocalizationService.PropertyChanged"/> через индексер,
/// поэтому при смене языка все привязки обновляются автоматически.
/// </summary>
public sealed class TExtension : MarkupExtension
{
    public string Key { get; set; } = "";

    public TExtension() { }
    public TExtension(string key) { Key = key; }

    public override object ProvideValue(IServiceProvider serviceProvider)
    {
        var b = new Binding($"[{Key}]")
        {
            Source = LocalizationService.Current,
            Mode = BindingMode.OneWay,
            FallbackValue = Key
        };
        return b.ProvideValue(serviceProvider);
    }
}
