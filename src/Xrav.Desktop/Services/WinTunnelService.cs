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
    private DataReceivedEventHandler? _xrayErr;
    private DataReceivedEventHandler? _xrayOut;
    private DataReceivedEventHandler? _hevErr;
    private DataReceivedEventHandler? _hevOut;
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
            Directory.CreateDirectory(AppDataPaths.ToolsDir);

            if (!File.Exists(xrayExe))
            {
                LastError = $"Нет {xrayExe}. Положите xray.exe (Windows) в папку tools.";
                State = TunnelConnectionState.Error;
                return;
            }
            if (!File.Exists(hevExe))
            {
                LastError = $"Нет {hevExe}. Соберите hev-socks5-tunnel (Windows) и положите .exe в tools.";
                State = TunnelConnectionState.Error;
                return;
            }
            if (!File.Exists(wintunDll))
            {
                LastError = $"Нет {wintunDll}. С wintun.net скачайте wintun.dll в папку tools (рядом с hev).";
                State = TunnelConnectionState.Error;
                return;
            }

            if (!VpnKeyXrayConfig.TryGetPatchedConfig(activeKey, out var configJson, out var cfgErr))
            {
                LastError = cfgErr;
                State = TunnelConnectionState.Error;
                return;
            }

            Directory.CreateDirectory(AppDataPaths.RuntimeDir);
            Directory.CreateDirectory(AppDataPaths.XrayAssetDir);
            await File.WriteAllTextAsync(AppDataPaths.XrayConfigPath, configJson, cancellationToken).ConfigureAwait(false);
            var hevYaml = HevSocks5YamlBuilder.Build();
            await File.WriteAllTextAsync(AppDataPaths.HevConfigPath, hevYaml, cancellationToken).ConfigureAwait(false);

            var tools = AppDataPaths.ToolsDir;
            var cfg = AppDataPaths.XrayConfigPath;
            var hevCfg = AppDataPaths.HevConfigPath;

            await Task.Run(
                () =>
            {
                var xrayPsi = new ProcessStartInfo
                {
                    FileName = xrayExe,
                    Arguments = $"run -c \"{cfg}\"",
                    WorkingDirectory = tools,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardError = true,
                    RedirectStandardOutput = true
                };
                xrayPsi.Environment["XRAY_LOCATION_ASSET"] = AppDataPaths.XrayAssetDir;
                _xray = Process.Start(xrayPsi);
                if (_xray is null) throw new InvalidOperationException("xray: Process.Start вернул null.");
                AttachXrayLogHandlers();
                _xray.BeginErrorReadLine();
                _xray.BeginOutputReadLine();
                Thread.Sleep(400);
                if (_xray.HasExited)
                {
                    var code = _xray.ExitCode;
                    DetachXrayLog();
                    _xray = null;
                    throw new InvalidOperationException(
                        $"xray сразу завершился (код {code}). Проверьте JSON и geoip.dat/geosite.dat в «{AppDataPaths.XrayAssetDir}».");
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
                hevPsi.Environment["XRAY_LOCATION_ASSET"] = AppDataPaths.XrayAssetDir;
                _hev = Process.Start(hevPsi);
                if (_hev is null) throw new InvalidOperationException("hev: Process.Start вернул null.");
                AttachHevLogHandlers();
                _hev.BeginErrorReadLine();
                _hev.BeginOutputReadLine();
                Thread.Sleep(400);
                if (_hev.HasExited)
                {
                    var code = _hev.ExitCode;
                    DetachHevLog();
                    TryKill(_xray);
                    DetachXrayLog();
                    _xray = null;
                    _hev = null;
                    throw new InvalidOperationException(
                        $"hev-socks5-tunnel сразу завершился (код {code}). wintun.dll рядом с hev, админ, YAML: «{hevCfg}».");
                }
            },
                cancellationToken).ConfigureAwait(false);

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
    }

    private void EmitLog(string source, string? data)
    {
        if (string.IsNullOrWhiteSpace(data)) return;
        LogLine?.Invoke(this, new TunnelLogEventArgs(source, data.TrimEnd()));
    }

    private void AttachXrayLogHandlers()
    {
        if (_xray is null) return;
        _xrayErr = (_, e) => EmitLog("xray", e.Data);
        _xrayOut = (_, e) => EmitLog("xray", e.Data);
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
        _hevErr = (_, e) => EmitLog("hev", e.Data);
        _hevOut = (_, e) => EmitLog("hev", e.Data);
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
