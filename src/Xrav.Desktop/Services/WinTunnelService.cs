using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.CompilerServices;
using Xrav.Core.Domain;
using Xrav.Desktop.Storage;
using Xrav.Desktop.Tunnel;
using Xrav.Desktop.Xray;

namespace Xrav.Desktop.Services;

/// <summary>
/// xray.exe (SOCKS 10808) + hev-socks5-tunnel + Wintun.
/// В <see cref="AppDataPaths.ToolsDir"/> положите: <c>xray.exe</c>, <c>hev-socks5-tunnel.exe</c>, <c>wintun.dll</c> (wintun.net).
/// Geo: <see cref="AppDataPaths.XrayAssetDir"/> + <c>XRAY_LOCATION_ASSET</c> для процесса xray.
/// </summary>
public sealed class WinTunnelService : ITunnelService, INotifyPropertyChanged, IDisposable
{
    private readonly SemaphoreSlim _gate = new(1, 1);
    private Process? _xray;
    private Process? _hev;
    private Process? _singBox;
    private readonly WindowsRouter _router = new();
    private WindowsRouter.Snapshot? _routerSnapshot;
    private readonly System.Collections.Concurrent.ConcurrentQueue<string> _xrayTail = new();
    private readonly System.Collections.Concurrent.ConcurrentQueue<string> _hevTail = new();
    private readonly System.Collections.Concurrent.ConcurrentQueue<string> _sbTail = new();

    private static void Tail(System.Collections.Concurrent.ConcurrentQueue<string> q, string? line)
    {
        if (string.IsNullOrWhiteSpace(line)) return;
        q.Enqueue(line.TrimEnd());
        while (q.Count > 8 && q.TryDequeue(out _)) { }
    }
    private DataReceivedEventHandler? _xrayErr;
    private DataReceivedEventHandler? _xrayOut;
    private DataReceivedEventHandler? _hevErr;
    private DataReceivedEventHandler? _hevOut;
    private DataReceivedEventHandler? _sbErr;
    private DataReceivedEventHandler? _sbOut;
    private TunnelConnectionState _state = TunnelConnectionState.Disconnected;
    private string? _lastError;

    public TunnelConnectionState State
    {
        get => _state;
        private set
        {
            if (_state == value) return;
            _state = value;
            OnPropertyChanged();
        }
    }

    public string? LastError
    {
        get => _lastError;
        private set
        {
            if (_lastError == value) return;
            _lastError = value;
            OnPropertyChanged();
        }
    }

    /// <summary>Строки из stdout/stderr xray и hev (для UI).</summary>
    public event EventHandler<TunnelLogEventArgs>? LogLine;

    public async Task ConnectAsync(VpnKey? activeKey, CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            StopProcesses();
            LastError = null;
            State = TunnelConnectionState.Connecting;

            var xrayExe = Path.Combine(AppDataPaths.ToolsDir, "xray.exe");
            var hevExe = Path.Combine(AppDataPaths.ToolsDir, "hev-socks5-tunnel.exe");
            var wintunDll = Path.Combine(AppDataPaths.ToolsDir, "wintun.dll");
            var msysDll = Path.Combine(AppDataPaths.ToolsDir, "msys-2.0.dll");
            var singBoxExe = Path.Combine(AppDataPaths.ToolsDir, "sing-box.exe");
            Directory.CreateDirectory(AppDataPaths.ToolsDir);

            if (!VpnKeyXrayConfig.TryGetBackend(activeKey, out var backend, out var cfgErr))
            {
                LastError = cfgErr;
                State = TunnelConnectionState.Error;
                return;
            }

            Directory.CreateDirectory(AppDataPaths.RuntimeDir);
            Directory.CreateDirectory(AppDataPaths.XrayAssetDir);

            if (backend!.Kind == BackendKind.SingBox)
            {
                if (!File.Exists(singBoxExe) || !File.Exists(wintunDll))
                {
                    LastError = $"Нет sing-box.exe или wintun.dll в {AppDataPaths.ToolsDir}. "
                        + "Откройте «Настройки → Бинарники» и нажмите «Подготовить».";
                    State = TunnelConnectionState.Error;
                    return;
                }
                await File.WriteAllTextAsync(AppDataPaths.SingBoxConfigPath, backend.ConfigJson, cancellationToken).ConfigureAwait(false);
                await StartSingBoxAsync(singBoxExe, AppDataPaths.SingBoxConfigPath, cancellationToken).ConfigureAwait(false);
            }
            else
            {
                if (!File.Exists(xrayExe) || !File.Exists(hevExe) || !File.Exists(wintunDll) || !File.Exists(msysDll))
                {
                    LastError = "Не найдены бинарники в "
                        + AppDataPaths.ToolsDir
                        + ". Откройте «Настройки → Бинарники» и нажмите «Подготовить» (нужен интернет).";
                    State = TunnelConnectionState.Error;
                    return;
                }
                await File.WriteAllTextAsync(AppDataPaths.XrayConfigPath, backend.ConfigJson, cancellationToken).ConfigureAwait(false);
                await File.WriteAllTextAsync(AppDataPaths.HevConfigPath, HevSocks5YamlBuilder.Build(), cancellationToken).ConfigureAwait(false);
                await StartXrayHevAsync(xrayExe, AppDataPaths.XrayConfigPath, hevExe, AppDataPaths.HevConfigPath, cancellationToken).ConfigureAwait(false);

                // После старта hev НАДО прописать маршруты и DNS на TUN-интерфейс —
                // hev на Windows этого не делает сам.
                _router.Log = msg => EmitLog("router", msg);
                if (!string.IsNullOrEmpty(backend.ServerHost))
                {
                    // Даём hev ~1с на подъём интерфейса.
                    await Task.Delay(1500, cancellationToken).ConfigureAwait(false);
                    _routerSnapshot = await _router.ApplyAsync(
                        backend.ServerHost!,
                        "X-RavWintun",
                        TunnelConstants.TunIpv4Client,
                        cancellationToken).ConfigureAwait(false);
                }
            }

            State = TunnelConnectionState.Connected;
        }
        catch (OperationCanceledException)
        {
            StopProcesses();
            State = TunnelConnectionState.Disconnected;
            throw;
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
            State = TunnelConnectionState.Error;
            StopProcesses();
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task DisconnectAsync(CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await Task.Run(StopProcesses, cancellationToken).ConfigureAwait(false);
            State = TunnelConnectionState.Disconnected;
        }
        finally
        {
            _gate.Release();
        }
    }

    private void StopProcesses()
    {
        // Сначала откатить маршруты/DNS, потом убивать процессы — иначе при падении hev
        // интерфейс исчезает и netsh не сможет по нему отработать.
        if (_routerSnapshot is not null)
        {
            _router.Revert(_routerSnapshot);
            _routerSnapshot = null;
        }
        if (_hev is not null)
        {
            DetachHevLog();
            TryKill(_hev);
            _hev = null;
        }
        if (_xray is not null)
        {
            DetachXrayLog();
            TryKill(_xray);
            _xray = null;
        }
        if (_singBox is not null)
        {
            DetachSingBoxLog();
            TryKill(_singBox);
            _singBox = null;
        }
    }

    private Task StartXrayHevAsync(string xrayExe, string xrayCfg, string hevExe, string hevCfg, CancellationToken ct) =>
        Task.Run(() =>
        {
            var tools = AppDataPaths.ToolsDir;
            var xrayPsi = new ProcessStartInfo
            {
                FileName = xrayExe,
                Arguments = $"run -c \"{xrayCfg}\"",
                WorkingDirectory = tools,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardError = true,
                RedirectStandardOutput = true
            };
            xrayPsi.Environment["XRAY_LOCATION_ASSET"] = AppDataPaths.XrayAssetDir;
            _xray = Process.Start(xrayPsi);
            if (_xray is null) throw new InvalidOperationException("xray: Process.Start вернул null.");
            _xrayTail.Clear();
            AttachXrayLogHandlers();
            _xray.BeginErrorReadLine();
            _xray.BeginOutputReadLine();
            Thread.Sleep(700);
            if (_xray.HasExited)
            {
                var code = _xray.ExitCode;
                var tail = string.Join(" | ", _xrayTail);
                DetachXrayLog();
                _xray = null;
                throw new InvalidOperationException(
                    $"xray сразу завершился (код {code}). "
                    + (string.IsNullOrEmpty(tail) ? "Нет stderr (вероятно Windows Defender / SmartScreen блокирует xray.exe — добавьте исключение для " + AppDataPaths.ToolsDir + ")." : "stderr: " + tail));
            }

            var hevPsi = new ProcessStartInfo
            {
                FileName = hevExe,
                Arguments = $"\"{hevCfg}\"",
                WorkingDirectory = tools,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardError = true,
                RedirectStandardOutput = true
            };
            _hev = Process.Start(hevPsi);
            if (_hev is null) throw new InvalidOperationException("hev: Process.Start вернул null.");
            _hevTail.Clear();
            AttachHevLogHandlers();
            _hev.BeginErrorReadLine();
            _hev.BeginOutputReadLine();
            Thread.Sleep(700);
            if (_hev.HasExited)
            {
                var code = _hev.ExitCode;
                var tail = string.Join(" | ", _hevTail);
                DetachHevLog();
                TryKill(_xray);
                DetachXrayLog();
                _xray = null;
                _hev = null;
                throw new InvalidOperationException(
                    $"hev-socks5-tunnel сразу завершился (код {code}). "
                    + (string.IsNullOrEmpty(tail) ? "wintun.dll рядом с hev, запуск от админа. YAML: \"" + hevCfg + "\"." : "stderr: " + tail));
            }
        }, ct);

    private Task StartSingBoxAsync(string singBoxExe, string sbCfg, CancellationToken ct) =>
        Task.Run(() =>
        {
            var tools = AppDataPaths.ToolsDir;
            var psi = new ProcessStartInfo
            {
                FileName = singBoxExe,
                Arguments = $"run -c \"{sbCfg}\"",
                WorkingDirectory = tools,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardError = true,
                RedirectStandardOutput = true
            };
            // sing-box ищет wintun.dll в рабочем каталоге; tools = AppDataPaths.ToolsDir, wintun.dll лежит там
            _singBox = Process.Start(psi);
            if (_singBox is null) throw new InvalidOperationException("sing-box: Process.Start вернул null.");
            _sbTail.Clear();
            AttachSingBoxLogHandlers();
            _singBox.BeginErrorReadLine();
            _singBox.BeginOutputReadLine();
            Thread.Sleep(700);
            if (_singBox.HasExited)
            {
                var code = _singBox.ExitCode;
                var tail = string.Join(" | ", _sbTail);
                DetachSingBoxLog();
                _singBox = null;
                throw new InvalidOperationException(
                    $"sing-box сразу завершился (код {code}). "
                    + (string.IsNullOrEmpty(tail) ? $"Нужен запуск от админа и wintun.dll в {AppDataPaths.ToolsDir}." : "stderr: " + tail));
            }
        }, ct);

    private void AttachSingBoxLogHandlers()
    {
        if (_singBox is null) return;
        _sbErr = (_, e) => { Tail(_sbTail, e.Data); EmitLog("sing-box", e.Data); };
        _sbOut = (_, e) => { Tail(_sbTail, e.Data); EmitLog("sing-box", e.Data); };
        _singBox.ErrorDataReceived += _sbErr;
        _singBox.OutputDataReceived += _sbOut;
    }

    private void DetachSingBoxLog()
    {
        if (_singBox is null) return;
        if (_sbErr is not null) _singBox.ErrorDataReceived -= _sbErr;
        if (_sbOut is not null) _singBox.OutputDataReceived -= _sbOut;
        _sbErr = _sbOut = null;
    }

    private void EmitLog(string source, string? data)
    {
        if (string.IsNullOrWhiteSpace(data)) return;
        LogLine?.Invoke(this, new TunnelLogEventArgs(source, data.TrimEnd()));
    }

    private void AttachXrayLogHandlers()
    {
        if (_xray is null) return;
        _xrayErr = (_, e) => { Tail(_xrayTail, e.Data); EmitLog("xray", e.Data); };
        _xrayOut = (_, e) => { Tail(_xrayTail, e.Data); EmitLog("xray", e.Data); };
        _xray.ErrorDataReceived += _xrayErr;
        _xray.OutputDataReceived += _xrayOut;
    }

    private void DetachXrayLog()
    {
        if (_xray is null) return;
        if (_xrayErr is not null) _xray.ErrorDataReceived -= _xrayErr;
        if (_xrayOut is not null) _xray.OutputDataReceived -= _xrayOut;
        _xrayErr = _xrayOut = null;
    }

    private void AttachHevLogHandlers()
    {
        if (_hev is null) return;
        _hevErr = (_, e) => { Tail(_hevTail, e.Data); EmitLog("hev", e.Data); };
        _hevOut = (_, e) => { Tail(_hevTail, e.Data); EmitLog("hev", e.Data); };
        _hev.ErrorDataReceived += _hevErr;
        _hev.OutputDataReceived += _hevOut;
    }

    private void DetachHevLog()
    {
        if (_hev is null) return;
        if (_hevErr is not null) _hev.ErrorDataReceived -= _hevErr;
        if (_hevOut is not null) _hev.OutputDataReceived -= _hevOut;
        _hevErr = _hevOut = null;
    }

    private static void TryKill(Process? p)
    {
        if (p is not { HasExited: false }) return;
        try
        {
            p.Kill(entireProcessTree: true);
            p.WaitForExit(3000);
        }
        catch
        {
            /* ignore */
        }
    }

    public void Dispose()
    {
        _gate.Dispose();
        StopProcesses();
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
