using System.Diagnostics;
using Microsoft.Win32;

namespace Xrav.Desktop.Tools;

/// <summary>
/// Регистрирует URL-схему <c>x-rav://</c> в <c>HKCU\Software\Classes</c> (без прав администратора).
/// Поддерживаемые формы:
///   x-rav://import?url=&lt;urlencoded vless/vmess/trojan/ss/hysteria2/tuic link&gt;
///   x-rav://sub?url=&lt;urlencoded subscription URL&gt;
/// </summary>
public static class UrlSchemeRegistrar
{
    public const string Scheme = "x-rav";

    public static bool IsRegistered()
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey($@"Software\Classes\{Scheme}\shell\open\command");
            return k?.GetValue(null) is string s && !string.IsNullOrEmpty(s);
        }
        catch { return false; }
    }

    public static void Register()
    {
        try
        {
            var exe = Process.GetCurrentProcess().MainModule?.FileName;
            if (string.IsNullOrEmpty(exe) || !File.Exists(exe)) return;
            var cmd = $"\"{exe}\" \"%1\"";

            using (var k = Registry.CurrentUser.CreateSubKey($@"Software\Classes\{Scheme}"))
            {
                k.SetValue(null, "URL:X-Rav VPN Protocol");
                k.SetValue("URL Protocol", "");
            }
            using (var k = Registry.CurrentUser.CreateSubKey($@"Software\Classes\{Scheme}\DefaultIcon"))
            {
                k.SetValue(null, $"\"{exe}\",0");
            }
            using (var k = Registry.CurrentUser.CreateSubKey($@"Software\Classes\{Scheme}\shell\open\command"))
            {
                k.SetValue(null, cmd);
            }
            Logging.FileLogger.Log("urlScheme", $"registered: {cmd}");
        }
        catch (Exception ex)
        {
            Logging.FileLogger.Error("urlScheme", ex);
        }
    }

    public static void Unregister()
    {
        try
        {
            Registry.CurrentUser.DeleteSubKeyTree($@"Software\Classes\{Scheme}", throwOnMissingSubKey: false);
        }
        catch (Exception ex)
        {
            Logging.FileLogger.Error("urlScheme.unregister", ex);
        }
    }

    /// <summary>Извлекает <c>url</c> параметр из x-rav://import?url=... или x-rav://sub?url=...</summary>
    public static (string action, string? url) Parse(string raw)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(raw)) return ("", null);
            var trimmed = raw.Trim().TrimEnd('/');
            if (!trimmed.StartsWith($"{Scheme}://", StringComparison.OrdinalIgnoreCase))
                return ("", null);
            var rest = trimmed.Substring($"{Scheme}://".Length);
            var qIdx = rest.IndexOf('?');
            var action = qIdx >= 0 ? rest.Substring(0, qIdx) : rest;
            string? url = null;
            if (qIdx >= 0)
            {
                foreach (var pair in rest.Substring(qIdx + 1).Split('&'))
                {
                    var eq = pair.IndexOf('=');
                    if (eq <= 0) continue;
                    var k = pair.Substring(0, eq);
                    var v = pair.Substring(eq + 1);
                    if (string.Equals(k, "url", StringComparison.OrdinalIgnoreCase))
                    {
                        url = Uri.UnescapeDataString(v);
                        break;
                    }
                }
            }
            return (action.ToLowerInvariant(), url);
        }
        catch { return ("", null); }
    }
}
