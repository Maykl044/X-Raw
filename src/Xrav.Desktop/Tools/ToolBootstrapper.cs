using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text.Json;
using Xrav.Desktop.Storage;

namespace Xrav.Desktop.Tools;

public sealed record BootstrapStatus(string Stage, string Detail);

public sealed class BootstrapException : Exception
{
    public BootstrapException(string message, Exception? inner = null) : base(message, inner) { }
}

/// <summary>
/// Скачивает в <see cref="AppDataPaths.ToolsDir"/> Windows-бинарники, нужные туннелю:
///   * xray-core (https://github.com/XTLS/Xray-core/releases/latest) — x86 или x64 в зависимости от арх. процесса
///   * hev-socks5-tunnel (https://github.com/heiher/hev-socks5-tunnel/releases/latest) — только x64 (для x86 будет sing-box)
///   * sing-box (https://github.com/SagerNet/sing-box/releases/latest) — для Hysteria2/TUIC и для x86 host'а
///   * wintun.dll, geoip.dat, geosite.dat — поставляются вместе с xray-core
/// Использует только GitHub API + публичные релизы; никаких авторизаций не нужно.
/// </summary>
public sealed class ToolBootstrapper
{
    private readonly HttpClient _http;
    private readonly bool _isX86;

    public ToolBootstrapper(HttpClient? http = null)
    {
        _http = http ?? CreateDefaultClient();
        _isX86 = RuntimeInformation.ProcessArchitecture == Architecture.X86;
    }

    public static HttpClient CreateDefaultClient()
    {
        var c = new HttpClient(new HttpClientHandler { AllowAutoRedirect = true });
        c.Timeout = TimeSpan.FromMinutes(5);
        c.DefaultRequestHeaders.UserAgent.ParseAdd("X-Rav/1.0 (+https://github.com/Maykl044/X-Raw)");
        c.DefaultRequestHeaders.Accept.ParseAdd("application/octet-stream, application/zip, application/json;q=0.9, */*;q=0.5");
        return c;
    }

    public async Task EnsureToolsAsync(IProgress<BootstrapStatus>? progress = null, CancellationToken ct = default)
    {
        Directory.CreateDirectory(AppDataPaths.ToolsDir);
        Directory.CreateDirectory(AppDataPaths.XrayAssetDir);

        var xrayExe = Path.Combine(AppDataPaths.ToolsDir, "xray.exe");
        var hevExe = Path.Combine(AppDataPaths.ToolsDir, "hev-socks5-tunnel.exe");
        var msysDll = Path.Combine(AppDataPaths.ToolsDir, "msys-2.0.dll");
        var wintunDll = Path.Combine(AppDataPaths.ToolsDir, "wintun.dll");
        var singBoxExe = Path.Combine(AppDataPaths.ToolsDir, "sing-box.exe");
        var geoip = Path.Combine(AppDataPaths.XrayAssetDir, "geoip.dat");
        var geosite = Path.Combine(AppDataPaths.XrayAssetDir, "geosite.dat");

        if (!File.Exists(xrayExe) || !File.Exists(geoip) || !File.Exists(geosite) || !File.Exists(wintunDll))
        {
            progress?.Report(new("xray-core", $"Запрос последнего релиза… (arch={(_isX86 ? "x86" : "x64")})"));
            await DownloadAndExtractXrayAsync(xrayExe, geoip, geosite, wintunDll, progress, ct).ConfigureAwait(false);
        }
        else progress?.Report(new("xray-core", "уже установлен"));

        if (!_isX86)
        {
            // hev-socks5-tunnel у автора собирается только под x64 → на x86 пропускаем,
            // там туннель пойдёт через sing-box (он есть и под x86).
            if (!File.Exists(hevExe) || !File.Exists(msysDll))
            {
                progress?.Report(new("hev-socks5-tunnel", "Запрос последнего релиза…"));
                await DownloadHevAsync(hevExe, msysDll, wintunDll, progress, ct).ConfigureAwait(false);
            }
            else progress?.Report(new("hev-socks5-tunnel", "уже установлен"));
        }
        else
        {
            progress?.Report(new("hev-socks5-tunnel", "пропущен: только x64-релиз"));
        }

        if (!File.Exists(singBoxExe))
        {
            progress?.Report(new("sing-box", "Запрос последнего релиза…"));
            await DownloadSingBoxAsync(singBoxExe, progress, ct).ConfigureAwait(false);
        }
        else progress?.Report(new("sing-box", "уже установлен"));

        progress?.Report(new("done", $"Готово: {AppDataPaths.ToolsDir}"));
    }

    private async Task DownloadAndExtractXrayAsync(
        string xrayExe,
        string geoip,
        string geosite,
        string wintunDll,
        IProgress<BootstrapStatus>? progress,
        CancellationToken ct)
    {
        var assetName = _isX86 ? "Xray-windows-32.zip" : "Xray-windows-64.zip";
        var assetUrl = await PickAssetUrlAsync(
            "https://api.github.com/repos/XTLS/Xray-core/releases/latest",
            n => string.Equals(n, assetName, StringComparison.OrdinalIgnoreCase),
            $"xray-core: не нашли {assetName} в последнем релизе.",
            ct).ConfigureAwait(false);

        var zipPath = Path.Combine(AppDataPaths.RuntimeDir, assetName);
        Directory.CreateDirectory(AppDataPaths.RuntimeDir);
        progress?.Report(new("xray-core", "Скачиваем zip…"));
        await DownloadFileAsync(assetUrl, zipPath, progress, ct).ConfigureAwait(false);

        progress?.Report(new("xray-core", "Распаковка zip…"));
        using (var zip = ZipFile.OpenRead(zipPath))
        {
            ExtractEntryByExactName(zip, "xray.exe", xrayExe);
            ExtractEntryByExactName(zip, "geoip.dat", geoip, optional: true);
            ExtractEntryByExactName(zip, "geosite.dat", geosite, optional: true);
            ExtractEntryByExactName(zip, "wintun.dll", wintunDll, optional: true);
        }
        TryDelete(zipPath);

        if (!File.Exists(geoip))
            await DownloadAsset(
                "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat", geoip, progress, "geoip.dat", ct).ConfigureAwait(false);
        if (!File.Exists(geosite))
            await DownloadAsset(
                "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat", geosite, progress, "geosite.dat", ct).ConfigureAwait(false);
    }

    private async Task DownloadHevAsync(string hevExe, string msysDll, string wintunDll, IProgress<BootstrapStatus>? progress, CancellationToken ct)
    {
        var assetUrl = await PickAssetUrlAsync(
            "https://api.github.com/repos/heiher/hev-socks5-tunnel/releases/latest",
            n => n.Equals("hev-socks5-tunnel-win64.zip", StringComparison.OrdinalIgnoreCase)
                 || (n.Contains("win", StringComparison.OrdinalIgnoreCase)
                     && n.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)),
            "hev-socks5-tunnel: не нашли windows zip в последнем релизе.",
            ct).ConfigureAwait(false);

        var zipPath = Path.Combine(AppDataPaths.RuntimeDir, "hev-win64.zip");
        Directory.CreateDirectory(AppDataPaths.RuntimeDir);
        progress?.Report(new("hev-socks5-tunnel", "Скачиваем zip…"));
        await DownloadFileAsync(assetUrl, zipPath, progress, ct).ConfigureAwait(false);
        progress?.Report(new("hev-socks5-tunnel", "Распаковка…"));
        using (var zip = ZipFile.OpenRead(zipPath))
        {
            ExtractEntryByExactName(zip, "hev-socks5-tunnel.exe", hevExe);
            ExtractEntryByExactName(zip, "msys-2.0.dll", msysDll);
            if (!File.Exists(wintunDll))
                ExtractEntryByExactName(zip, "wintun.dll", wintunDll, optional: true);
        }
        TryDelete(zipPath);
    }

    private async Task DownloadSingBoxAsync(string singBoxExe, IProgress<BootstrapStatus>? progress, CancellationToken ct)
    {
        // Современная сборка sing-box (без legacy windows-7), под нужную арх.
        var archTag = _isX86 ? "windows-386" : "windows-amd64";
        var assetUrl = await PickAssetUrlAsync(
            "https://api.github.com/repos/SagerNet/sing-box/releases/latest",
            n => n.Contains(archTag, StringComparison.OrdinalIgnoreCase)
                 && !n.Contains("legacy", StringComparison.OrdinalIgnoreCase)
                 && n.EndsWith(".zip", StringComparison.OrdinalIgnoreCase),
            $"sing-box: не нашли {archTag} zip в последнем релизе.",
            ct).ConfigureAwait(false);

        var zipPath = Path.Combine(AppDataPaths.RuntimeDir, $"sing-box-{archTag}.zip");
        Directory.CreateDirectory(AppDataPaths.RuntimeDir);
        progress?.Report(new("sing-box", "Скачиваем zip…"));
        await DownloadFileAsync(assetUrl, zipPath, progress, ct).ConfigureAwait(false);
        progress?.Report(new("sing-box", "Распаковка…"));
        using (var zip = ZipFile.OpenRead(zipPath))
        {
            ExtractEntryByExactName(zip, "sing-box.exe", singBoxExe);
        }
        TryDelete(zipPath);
    }

    private async Task<string> PickAssetUrlAsync(string releaseApiUrl, Func<string, bool> match, string errorMsg, CancellationToken ct)
    {
        var releaseJson = await _http.GetStringAsync(releaseApiUrl, ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(releaseJson);
        if (!doc.RootElement.TryGetProperty("assets", out var assets))
            throw new BootstrapException(errorMsg);

        foreach (var asset in assets.EnumerateArray())
        {
            var name = asset.GetProperty("name").GetString() ?? "";
            if (match(name))
            {
                var url = asset.GetProperty("browser_download_url").GetString();
                if (!string.IsNullOrEmpty(url)) return url!;
            }
        }
        throw new BootstrapException(errorMsg);
    }

    private async Task DownloadAsset(string url, string dest, IProgress<BootstrapStatus>? progress, string label, CancellationToken ct)
    {
        progress?.Report(new(label, $"Скачиваем {label}…"));
        await DownloadFileAsync(url, dest, progress, ct).ConfigureAwait(false);
    }

    private async Task DownloadFileAsync(string url, string destPath, IProgress<BootstrapStatus>? progress, CancellationToken ct)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(destPath)!);
        var tmp = destPath + ".tmp";
        try
        {
            using (var resp = await _http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false))
            {
                resp.EnsureSuccessStatusCode();
                var total = resp.Content.Headers.ContentLength;
                using var s = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
                using var f = File.Create(tmp);
                var buf = new byte[81920];
                long read = 0;
                int n;
                long lastReport = 0;
                while ((n = await s.ReadAsync(buf.AsMemory(0, buf.Length), ct).ConfigureAwait(false)) > 0)
                {
                    await f.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
                    read += n;
                    if (total is long t && read - lastReport > 524_288)
                    {
                        lastReport = read;
                        progress?.Report(new("download", $"{Path.GetFileName(destPath)}: {read / 1024} КБ из {t / 1024} КБ"));
                    }
                }
            }
            if (File.Exists(destPath)) File.Delete(destPath);
            File.Move(tmp, destPath);
        }
        catch (Exception ex)
        {
            TryDelete(tmp);
            throw new BootstrapException($"Скачивание {url} не удалось: {ex.Message}", ex);
        }
    }

    private static void ExtractEntryByExactName(ZipArchive zip, string name, string dest, bool optional = false)
    {
        ZipArchiveEntry? entry = null;
        foreach (var e in zip.Entries)
        {
            if (string.Equals(Path.GetFileName(e.FullName), name, StringComparison.OrdinalIgnoreCase))
            {
                entry = e;
                break;
            }
        }
        if (entry is null)
        {
            if (optional) return;
            throw new BootstrapException($"В zip нет файла {name}.");
        }
        Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
        var tmp = dest + ".tmp";
        using (var es = entry.Open())
        using (var fs = File.Create(tmp))
            es.CopyTo(fs);
        if (File.Exists(dest)) File.Delete(dest);
        File.Move(tmp, dest);
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }
}
