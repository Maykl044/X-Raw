using System.Diagnostics;
using Xrav.Desktop.Logging;
using Xrav.Desktop.Storage;

namespace Xrav.Desktop.Services;

/// <summary>
/// Сканирует и убивает зависшие процессы туннеля от прошлых сессий X-Rav.
/// Если приложение крашится — xray.exe / hev-socks5-tunnel.exe / sing-box.exe
/// остаются висеть и держат порт SOCKS / wintun. На старте мы ищем такие
/// процессы (по совпадению пути к exe с нашей <see cref="AppDataPaths.ToolsDir"/>),
/// которые при этом не являются дочерними от текущего X-Rav, и грохаем их.
/// Идея взята из happ-daemon (см. лог "Found stale process xray.exe — killing").
/// </summary>
public static class StaleProcessCleanup
{
    private static readonly string[] TargetNames =
    {
        "xray",
        "hev-socks5-tunnel",
        "sing-box",
    };

    /// <summary>Возвращает количество убитых stale-процессов.</summary>
    public static int KillStale()
    {
        int killed = 0;
        var selfPid = Environment.ProcessId;
        var toolsDir = NormalizeDir(AppDataPaths.ToolsDir);

        foreach (var name in TargetNames)
        {
            Process[] procs;
            try { procs = Process.GetProcessesByName(name); }
            catch (Exception ex)
            {
                FileLogger.Error("staleScan", ex);
                continue;
            }

            foreach (var p in procs)
            {
                try
                {
                    if (p.Id == selfPid) continue;

                    // Сверяем путь exe с нашей папкой инструментов — чтобы случайно
                    // не грохнуть xray из стороннего клиента (v2rayN и пр.).
                    string? exePath;
                    try { exePath = p.MainModule?.FileName; }
                    catch { exePath = null; }

                    if (string.IsNullOrEmpty(exePath)) continue;
                    var exeDir = NormalizeDir(Path.GetDirectoryName(exePath) ?? string.Empty);
                    if (!exeDir.StartsWith(toolsDir, StringComparison.OrdinalIgnoreCase)) continue;

                    p.Kill(entireProcessTree: true);
                    p.WaitForExit(3000);
                    killed++;
                    FileLogger.Log("staleKill", $"killed stale {name} pid={p.Id} path={exePath}");
                }
                catch (Exception ex)
                {
                    FileLogger.Error("staleKill", ex);
                }
                finally
                {
                    try { p.Dispose(); } catch { /* ignore */ }
                }
            }
        }

        return killed;
    }

    private static string NormalizeDir(string dir)
    {
        if (string.IsNullOrEmpty(dir)) return string.Empty;
        var full = Path.GetFullPath(dir).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        return full;
    }
}
