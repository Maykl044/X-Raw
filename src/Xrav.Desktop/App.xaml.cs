using System.Globalization;
using System.Windows;
using System.Windows.Threading;
using Xrav.Desktop.Localization;
using Xrav.Desktop.Logging;
using Xrav.Desktop.Storage;
using Xrav.Desktop.Theme;
using Xrav.Desktop.Tools;

namespace Xrav.Desktop;

public partial class App : Application
{
    private static readonly string[] ProtocolPrefixes =
        { "vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://", "tuic://" };

    public string? PendingImportLink { get; private set; }
    public string? PendingImportSubscription { get; private set; }

    protected override void OnStartup(StartupEventArgs e)
    {
        // Префы — раньше всего, чтобы темы/язык применились до создания окна
        var prefs = AppPrefs.Load();

        var lang = LocalizationService.ParseCode(prefs.Language);
        if (string.IsNullOrEmpty(prefs.Language))
            lang = LocalizationService.DetectFromCulture();
        LocalizationService.Current.Language = lang;

        var cultureCode = LocalizationService.LangCode(lang) switch
        {
            "en" => "en-US",
            "tr" => "tr-TR",
            _ => "ru-RU"
        };
        var ci = new CultureInfo(cultureCode);
        CultureInfo.DefaultThreadCurrentCulture = ci;
        CultureInfo.DefaultThreadCurrentUICulture = ci;
        Thread.CurrentThread.CurrentCulture = ci;
        Thread.CurrentThread.CurrentUICulture = ci;

        ThemeService.Current.Initialize(ThemeService.ParseCode(prefs.Theme));

        DispatcherUnhandledException += OnDispatcherUnhandled;
        AppDomain.CurrentDomain.UnhandledException += OnDomainUnhandled;
        TaskScheduler.UnobservedTaskException += OnUnobservedTask;

        FileLogger.Log("app", "X-Rav started");

        // Сканируем процессы туннеля, оставшиеся от прошлой сессии (если приложение
        // упало — xray/hev/sing-box могут продолжать жить, держать SOCKS-порт и tun).
        // Идея из happ-daemon: проверяем "stale processes" и грохаем перед стартом.
        try
        {
            int killed = Services.StaleProcessCleanup.KillStale();
            if (killed > 0)
                FileLogger.Log("app", $"Stale tunnel processes killed: {killed}");
        }
        catch (Exception ex)
        {
            FileLogger.Error("staleCleanup", ex);
        }

        try
        {
            int extracted = BundledTools.ExtractMissing();
            if (extracted > 0)
                FileLogger.Log("app", $"Extracted {extracted} bundled binaries.");
        }
        catch (Exception ex)
        {
            FileLogger.Error("bundled", ex);
        }

        // Регистрируем x-rav:// при первом запуске
        try
        {
            if (!UrlSchemeRegistrar.IsRegistered())
            {
                UrlSchemeRegistrar.Register();
                prefs.UrlSchemeRegistered = true;
                prefs.Save();
            }
        }
        catch (Exception ex) { FileLogger.Error("urlScheme", ex); }

        // Парсим аргументы запуска: x-rav://import?url=... / vless://… / config.json
        ParseLaunchArgs(e.Args);

        base.OnStartup(e);
    }

    private void ParseLaunchArgs(string[] args)
    {
        try
        {
            foreach (var raw in args)
            {
                if (string.IsNullOrWhiteSpace(raw)) continue;
                var t = raw.Trim();

                if (t.StartsWith($"{UrlSchemeRegistrar.Scheme}://", StringComparison.OrdinalIgnoreCase))
                {
                    var (action, url) = UrlSchemeRegistrar.Parse(t);
                    if (string.IsNullOrEmpty(url)) continue;
                    if (action == "import") PendingImportLink = url;
                    else if (action == "sub") PendingImportSubscription = url;
                }
                else if (ProtocolPrefixes.Any(p => t.StartsWith(p, StringComparison.OrdinalIgnoreCase)))
                {
                    PendingImportLink = t;
                }
            }
        }
        catch (Exception ex) { FileLogger.Error("launchArgs", ex); }
    }

    private void OnDispatcherUnhandled(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        FileLogger.Error("dispatcher", e.Exception);
        MessageBox.Show(
            $"Произошла ошибка: {e.Exception.Message}\n\nПодробности — в логе:\n{Logging.FileLogger.GetLogFile()}",
            "X-Rav",
            MessageBoxButton.OK,
            MessageBoxImage.Error);
        e.Handled = true;
    }

    private void OnDomainUnhandled(object sender, UnhandledExceptionEventArgs e)
    {
        if (e.ExceptionObject is Exception ex)
            FileLogger.Error("domain", ex);
    }

    private void OnUnobservedTask(object? sender, UnobservedTaskExceptionEventArgs e)
    {
        FileLogger.Error("task", e.Exception);
        e.SetObserved();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        FileLogger.Log("app", $"X-Rav exited (code={e.ApplicationExitCode})");
        base.OnExit(e);
    }
}
