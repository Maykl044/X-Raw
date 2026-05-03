using System;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Windows;
using WinForms = System.Windows.Forms;
using Xrav.Desktop.ViewModels;

namespace Xrav.Desktop.Services;

/// <summary>
/// Системный трей: пока окно скрыто, в трее живёт иконка с контекстным меню
/// (Открыть · Подключить/Отключить · Выйти). Двойной клик по иконке —
/// восстанавливает окно. Реальный exit только через "Выйти" в меню.
/// </summary>
public sealed class TrayIconManager : IDisposable
{
    private readonly Window _window;
    private readonly WinForms.NotifyIcon _icon;
    private readonly WinForms.ToolStripMenuItem _miOpen;
    private readonly WinForms.ToolStripMenuItem _miToggle;
    private readonly WinForms.ToolStripMenuItem _miExit;
    private bool _userExitRequested;
    private bool _disposed;

    public bool UserExitRequested => _userExitRequested;

    public TrayIconManager(Window window)
    {
        _window = window;

        _icon = new WinForms.NotifyIcon
        {
            Icon = LoadAppIcon() ?? SystemIcons.Application,
            Text = "X-Rav",
            Visible = true,
        };

        _miOpen = new WinForms.ToolStripMenuItem("Открыть");
        _miOpen.Click += (_, _) => Restore();

        _miToggle = new WinForms.ToolStripMenuItem("Подключить / Отключить");
        _miToggle.Click += (_, _) => ToggleConnection();

        _miExit = new WinForms.ToolStripMenuItem("Выйти");
        _miExit.Click += (_, _) => RequestExit();

        var menu = new WinForms.ContextMenuStrip();
        menu.Items.Add(_miOpen);
        menu.Items.Add(new WinForms.ToolStripSeparator());
        menu.Items.Add(_miToggle);
        menu.Items.Add(new WinForms.ToolStripSeparator());
        menu.Items.Add(_miExit);
        _icon.ContextMenuStrip = menu;

        _icon.DoubleClick += (_, _) => Restore();
    }

    private static Icon? LoadAppIcon()
    {
        // Иконка лежит в ресурсах сборки как Assets/app.ico (см. csproj).
        try
        {
            var asm = Assembly.GetExecutingAssembly();
            var uri = new Uri("pack://application:,,,/Assets/X-Rav.ico", UriKind.Absolute);
            var info = System.Windows.Application.GetResourceStream(uri);
            if (info?.Stream is not null)
                return new Icon(info.Stream);
        }
        catch { /* fallback ниже */ }

        try
        {
            var path = Path.Combine(AppContext.BaseDirectory, "Assets", "X-Rav.ico");
            if (File.Exists(path)) return new Icon(path);
        }
        catch { }
        return null;
    }

    public void Restore()
    {
        _window.Dispatcher.Invoke(() =>
        {
            _window.Show();
            if (_window.WindowState == WindowState.Minimized)
                _window.WindowState = WindowState.Normal;
            _window.Activate();
            _window.Topmost = true;
            _window.Topmost = false;
        });
    }

    public void Hide()
    {
        _window.Dispatcher.Invoke(() => _window.Hide());
        ShowBalloonHint();
    }

    private void ShowBalloonHint()
    {
        try
        {
            _icon.BalloonTipTitle = "X-Rav свернут в трей";
            _icon.BalloonTipText = "Приложение продолжает работу в фоне. Двойной клик — открыть, ПКМ — меню.";
            _icon.ShowBalloonTip(3000);
        }
        catch { /* не критично */ }
    }

    private void ToggleConnection()
    {
        _window.Dispatcher.Invoke(() =>
        {
            if (_window.DataContext is not MainViewModel vm) return;
            if (vm.TunnelState is TunnelConnectionState.Connected
                or TunnelConnectionState.Connecting
                or TunnelConnectionState.Reconnecting)
            {
                if (vm.DisconnectCommand.CanExecute(null)) vm.DisconnectCommand.Execute(null);
            }
            else
            {
                if (vm.ConnectCommand.CanExecute(null)) vm.ConnectCommand.Execute(null);
            }
        });
    }

    public void RequestExit()
    {
        _userExitRequested = true;
        _window.Dispatcher.Invoke(() =>
        {
            // Чтоб гарантированно завершить процесс — закрываем окно и убиваем приложение.
            // Closing-handler увидит UserExitRequested=true и пропустит свертывание в трей.
            _window.Close();
            System.Windows.Application.Current?.Shutdown();
        });
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try
        {
            _icon.Visible = false;
            _icon.Dispose();
        }
        catch { /* swallow */ }
    }
}
