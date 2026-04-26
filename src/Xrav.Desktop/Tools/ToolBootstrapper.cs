using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http;
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
///   * xray-core (https://github.com/XTLS/Xray-core/releases/latest)
///   * hev-socks5-tunnel (https://github.com/heiher/hev-socks5-tunnel/releases/latest)
///   * wintun.dll (амальгама архива из xray-core, у которого она поставляется в комплекте)
/// Использует только GitHub API + публичные релизы; никаких авторизаций не нужно.
/// </summary>
public sealed class ToolBootstrapper
{
    private readonly HttpClient _http;

    public ToolBootstrapper(HttpClient? http = null)
    {
        _http = http ?? CreateDefaultClient();
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
        var xrayExe = Path.Combine(AppDataPaths.ToolsDir, "xray.exe");
        var hevExe = Path.Combine(AppDataPaths.ToolsDir, "hev-socks5-tunnel.exe");
        var wintunDll = Path.Combine(AppDataPaths.ToolsDir, "wintun.dll");
        var geoip = Path.Combine(AppDataPaths.XrayAssetDir, "geoip.dat");
        var geosite = Path.Combine(AppDataPaths.XrayAssetDir, "geosite.dat");
        Directory.CreateDirectory(AppDataPaths.XrayAssetDir);

        if (!File.Exists(xrayExe) || !File.Exists(geoip) || !File.Exists(geosite) || !File.Exists(wintunDll))
        {
            progress?.Report(new("xray-core", "Запрос последнего релиза…"));
            await DownloadAndExtractXrayAsync(xrayExe, geoip, geosite, wintunDll, progress, ct).ConfigureAwait(false);
        }
        else
        {
            progress?.Report(new("xray-core", "уже установлен"));
        }

        var msysDll = Path.Combine(AppDataPaths.ToolsDir, "msys-2.0.dll");
        if (!File.Exists(hevExe) || !File.Exists(msysDll))
        {
            progress?.Report(new("hev-socks5-tunnel", "Запрос последнего релиза…"));
            await DownloadHevAsync(hevExe, msysDll, wintunDll, progress, ct).ConfigureAwait(false);
        }
        else
        {
            progress?.Report(new("hev-socks5-tunnel", "уже установлен"));
        }

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
        var releaseJson = await _http.GetStringAsync(
            "https://api.github.com/repos/XTLS/Xray-core/releases/latest", ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(releaseJson);
        if (!doc.RootElement.TryGetProperty("assets", out var assets))
            throw new BootstrapException("xray-core: assets отсутствуют в релизе.");

        string? assetUrl = null;
        foreach (var asset in assets.EnumerateArray())
        {
            var name = asset.GetProperty("name").GetString() ?? "";
            if (string.Equals(name, "Xray-windows-64.zip", StringComparison.OrdinalIgnoreCase))
            {
                assetUrl = asset.GetProperty("browser_download_url").GetString();
                break;
            }
        }
        if (string.IsNullOrEmpty(assetUrl))
            throw new BootstrapException("xray-core: не нашли Xray-windows-64.zip в последнем релизе.");

        var zipPath = Path.Combine(AppDataPaths.RuntimeDir, "xray-windows-64.zip");
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
        var releaseJson = await _http.GetStringAsync(
            "https://api.github.com/repos/heiher/hev-socks5-tunnel/releases/latest", ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(releaseJson);
        if (!doc.RootElement.TryGetProperty("assets", out var assets))
            throw new BootstrapException("hev-socks5-tunnel: assets отсутствуют в релизе.");

        string? assetUrl = null;
        foreach (var asset in assets.EnumerateArray())
        {
            var name = asset.GetProperty("name").GetString() ?? "";
            if (name.Equals("hev-socks5-tunnel-win64.zip", StringComparison.OrdinalIgnoreCase)
                || (name.Contains("win", StringComparison.OrdinalIgnoreCase)
                    && name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)))
            {
                assetUrl = asset.GetProperty("browser_download_url").GetString();
                break;
            }
        }
        if (string.IsNullOrEmpty(assetUrl))
            throw new BootstrapException("hev-socks5-tunnel: не нашли windows x64 zip в последнем релизе.");

        var zipPath = Path.Combine(AppDataPaths.RuntimeDir, "hev-win64.zip");
        Directory.CreateDirectory(AppDataPaths.RuntimeDir);
        progress?.Report(new("hev-socks5-tunnel", "Скачиваем zip…"));
        await DownloadFileAsync(assetUrl, zipPath, progress, ct).ConfigureAwait(false);
        progress?.Report(new("hev-socks5-tunnel", "Распаковка…"));
        using (var zip = ZipFile.OpenRead(zipPath))
        {
            ExtractEntryByExactName(zip, "hev-socks5-tunnel.exe", hevExe);
            ExtractEntryByExactName(zip, "msys-2.0.dll", msysDll);
            // Берём wintun.dll из этого же zip — он там же присутствует
            if (!File.Exists(wintunDll))
                ExtractEntryByExactName(zip, "wintun.dll", wintunDll, optional: true);
        }
        TryDelete(zipPath);
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

    private static void TryDelete(string p)
    {
        try { if (File.Exists(p)) File.Delete(p); } catch { /* ignore */ }
    }
}
