using System.Reflection;
using System.Runtime.InteropServices;
using Xrav.Desktop.Storage;

namespace Xrav.Desktop.Tools;

/// <summary>
/// Зашитые в .exe бинарники xray-core / sing-box / hev-socks5-tunnel / wintun + geoip/geosite.
/// При первом запуске распаковываются в <see cref="AppDataPaths.ToolsDir"/> и
/// <see cref="AppDataPaths.XrayAssetDir"/>.
/// </summary>
public static class BundledTools
{
    /// <summary>Версии встроенных бинарников. Используются для уведомления о новых релизах.</summary>
    public const string XrayVersion    = "26.3.27";
    public const string SingBoxVersion = "1.13.11";
    public const string HevVersion     = "2.7.4";

    private static bool IsX86 => RuntimeInformation.ProcessArchitecture == Architecture.X86;

    private static IReadOnlyList<(string ResourceName, string DestPath)> EnumerateMappings()
    {
        var arch = IsX86 ? "x86" : "x64";
        var list = new List<(string, string)>
        {
            ($"Bundled.{arch}.xray.exe",     Path.Combine(AppDataPaths.ToolsDir, "xray.exe")),
            ($"Bundled.{arch}.sing-box.exe", Path.Combine(AppDataPaths.ToolsDir, "sing-box.exe")),
            ($"Bundled.{arch}.wintun.dll",   Path.Combine(AppDataPaths.ToolsDir, "wintun.dll")),
            ("Bundled.common.geoip.dat",   Path.Combine(AppDataPaths.XrayAssetDir, "geoip.dat")),
            ("Bundled.common.geosite.dat", Path.Combine(AppDataPaths.XrayAssetDir, "geosite.dat"))
        };
        if (!IsX86)
        {
            list.Add(("Bundled.x64.hev-socks5-tunnel.exe", Path.Combine(AppDataPaths.ToolsDir, "hev-socks5-tunnel.exe")));
            list.Add(("Bundled.x64.msys-2.0.dll",          Path.Combine(AppDataPaths.ToolsDir, "msys-2.0.dll")));
            list.Add(("Bundled.x64.libcronet.dll",         Path.Combine(AppDataPaths.ToolsDir, "libcronet.dll")));
        }
        return list;
    }

    /// <summary>
    /// Распаковывает встроенные бинарники в каталоги пользователя.
    /// Перезаписывает только те файлы, у которых размер отличается от встроенного
    /// (т.е. при выходе нового билда X-Rav со свежими бинарниками — обновятся).
    /// </summary>
    public static int ExtractMissing()
    {
        Directory.CreateDirectory(AppDataPaths.ToolsDir);
        Directory.CreateDirectory(AppDataPaths.XrayAssetDir);
        int extracted = 0;
        var asm = Assembly.GetExecutingAssembly();
        foreach (var (resourceName, destPath) in EnumerateMappings())
        {
            using var stream = asm.GetManifestResourceStream(resourceName);
            if (stream is null) continue; // ресурс не вшит (debug-билд без bundled)
            long expected = stream.Length;
            if (File.Exists(destPath))
            {
                try
                {
                    var fi = new FileInfo(destPath);
                    if (fi.Length == expected) continue; // уже распакован
                }
                catch { /* перезаписываем */ }
            }
            try
            {
                using var fs = File.Create(destPath);
                stream.CopyTo(fs);
                extracted++;
            }
            catch (IOException) { /* файл занят процессом — пропускаем */ }
        }
        return extracted;
    }
}
