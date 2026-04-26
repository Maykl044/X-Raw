using System.Globalization;
using System.Windows;

namespace Xrav.Desktop;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        // Язык по умолчанию — русский; мультиязычность не требуется.
        var ru = new CultureInfo("ru-RU");
        CultureInfo.DefaultThreadCurrentCulture = ru;
        CultureInfo.DefaultThreadCurrentUICulture = ru;
        Thread.CurrentThread.CurrentCulture = ru;
        Thread.CurrentThread.CurrentUICulture = ru;
        base.OnStartup(e);
    }
}
