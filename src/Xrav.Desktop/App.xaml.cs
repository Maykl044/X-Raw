using System.Globalization;
using System.Windows;
using System.Windows.Threading;
using Xrav.Desktop.Logging;

namespace Xrav.Desktop;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        var ru = new CultureInfo("ru-RU");
        CultureInfo.DefaultThreadCurrentCulture = ru;
        CultureInfo.DefaultThreadCurrentUICulture = ru;
        Thread.CurrentThread.CurrentCulture = ru;
        Thread.CurrentThread.CurrentUICulture = ru;

        DispatcherUnhandledException += OnDispatcherUnhandled;
        AppDomain.CurrentDomain.UnhandledException += OnDomainUnhandled;
        TaskScheduler.UnobservedTaskException += OnUnobservedTask;

        FileLogger.Log("app", "X-Rav started");
        base.OnStartup(e);
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
