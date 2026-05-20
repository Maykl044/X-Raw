using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Xrav.Desktop.Storage;
using Xrav.Desktop.Tools;

namespace Xrav.Desktop.Services;

/// <summary>
/// Стадии работы апдейтера hev-socks5-tunnel. Используются для индикации
/// в UI (idle → loading → success/error).
/// </summary>
public enum HevUpdateStage
{
    Idle,
    Checking,
    Downloading,
    Extracting,
    Installing,
    Success,
    UpToDate,
    Error,
}

public sealed record HevUpdateProgress(HevUpdateStage Stage, string? Message = null, double Progress = 0);

/// <summary>
/// Скачивает свежий <c>hev-socks5-tunnel.exe</c> из GitHub Releases репозитория
/// <c>heiher/hev-socks5-tunnel</c> и перезаписывает локальный файл в
/// <see cref="AppDataPaths.ToolsDir"/>. Версия сохраняется в sidecar-файле
/// <c>hev-socks5-tunnel.version</c> рядом с .exe, чтобы запоминать что
/// поверх bundled-версии стояло обновление.
/// </summary>
public sealed class HevSocks5Updater
{
    private const string Repo = "heiher/hev-socks5-tunnel";
    private const string ApiLatest = "https://api.github.com/repos/heiher/hev-socks5-tunnel/releases/latest";
    private const string UserAgent = "X-Rav-Updater";

    private static readonly string HevExePath = Path.Combine(AppDataPaths.ToolsDir, "hev-socks5-tunnel.exe");
    private static readonly string HevVersionFile = Path.Combine(AppDataPaths.ToolsDir, "hev-socks5-tunnel.version");

    /// <summary>
    /// Возвращает текущую версию hev-socks5-tunnel. Если рядом с .exe есть
    /// sidecar-файл — берём оттуда (это последнее обновление). Иначе —
    /// версия зашитого в .exe бинарника (см. <see cref="BundledTools.HevVersion"/>).
    /// </summary>
    public string GetCurrentVersion()
    {
        try
        {
            if (File.Exists(HevVersionFile))
            {
                var v = File.ReadAllText(HevVersionFile).Trim();
                if (!string.IsNullOrWhiteSpace(v)) return v;
            }
        }
        catch { /* fallback ниже */ }
        return BundledTools.HevVersion;
    }

    public async Task<(bool success, string newVersion, string? error)> UpdateAsync(
        IProgress<HevUpdateProgress>? progress = null,
        CancellationToken ct = default)
    {
        progress?.Report(new HevUpdateProgress(HevUpdateStage.Checking, "Запрашиваю GitHub Releases…"));

        using var http = CreateHttp();
        ReleaseInfo? release;
        try
        {
            release = await FetchLatestReleaseAsync(http, ct).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            progress?.Report(new HevUpdateProgress(HevUpdateStage.Error, "Не удалось получить релиз: " + ex.Message));
            return (false, GetCurrentVersion(), ex.Message);
        }

        if (release is null || release.Assets.Count == 0)
        {
            progress?.Report(new HevUpdateProgress(HevUpdateStage.Error, "GitHub не вернул релиз / ассеты"));
            return (false, GetCurrentVersion(), "no release");
        }

        string newVersion = (release.TagName ?? "").TrimStart('v');
        string current = GetCurrentVersion();
        if (!string.IsNullOrEmpty(newVersion) && string.Equals(newVersion, current, StringComparison.OrdinalIgnoreCase))
        {
            progress?.Report(new HevUpdateProgress(HevUpdateStage.UpToDate,
                $"Уже актуальная версия: {current}"));
            return (true, current, null);
        }

        var asset = PickAssetForArch(release.Assets);
        if (asset is null)
        {
            progress?.Report(new HevUpdateProgress(HevUpdateStage.Error,
                "В релизе нет подходящего Windows-ассета"));
            return (false, current, "no asset");
        }

        EnsureNotInUse();

        Directory.CreateDirectory(AppDataPaths.ToolsDir);
        var tempZip = Path.Combine(Path.GetTempPath(), $"hev-{Guid.NewGuid():N}.zip");
        var tempExtract = Path.Combine(Path.GetTempPath(), $"hev-extract-{Guid.NewGuid():N}");

        try
        {
            progress?.Report(new HevUpdateProgress(HevUpdateStage.Downloading,
                $"Скачиваю {asset.Name} ({asset.Size / 1024} КБ)…"));
            await DownloadAsync(http, asset.DownloadUrl!, tempZip, progress, ct).ConfigureAwait(false);

            progress?.Report(new HevUpdateProgress(HevUpdateStage.Extracting,
                "Распаковываю архив…"));
            Directory.CreateDirectory(tempExtract);
            ZipFile.ExtractToDirectory(tempZip, tempExtract, overwriteFiles: true);

            // Ищем hev-socks5-tunnel.exe внутри (бывает в подпапке).
            var exeInside = Directory.EnumerateFiles(tempExtract, "hev-socks5-tunnel.exe", SearchOption.AllDirectories)
                                     .FirstOrDefault();
            if (exeInside is null)
            {
                progress?.Report(new HevUpdateProgress(HevUpdateStage.Error,
                    "В архиве не найден hev-socks5-tunnel.exe"));
                return (false, current, "missing exe");
            }

            progress?.Report(new HevUpdateProgress(HevUpdateStage.Installing,
                "Устанавливаю в каталог приложения…"));

            // Безопасная замена: пишем во временный файл рядом, потом меняем местами.
            var stagedPath = HevExePath + ".new";
            File.Copy(exeInside, stagedPath, overwrite: true);

            if (File.Exists(HevExePath))
            {
                try { File.Delete(HevExePath); }
                catch (IOException)
                {
                    // Файл занят — рекомендуем отключиться.
                    progress?.Report(new HevUpdateProgress(HevUpdateStage.Error,
                        "Файл занят процессом. Отключите VPN и повторите."));
                    try { File.Delete(stagedPath); } catch { }
                    return (false, current, "file in use");
                }
            }
            File.Move(stagedPath, HevExePath);

            try { File.WriteAllText(HevVersionFile, newVersion); }
            catch { /* sidecar — не критично */ }

            progress?.Report(new HevUpdateProgress(HevUpdateStage.Success,
                $"Обновлено до {newVersion}"));
            return (true, newVersion, null);
        }
        catch (Exception ex)
        {
            progress?.Report(new HevUpdateProgress(HevUpdateStage.Error, ex.Message));
            return (false, current, ex.Message);
        }
        finally
        {
            try { if (File.Exists(tempZip)) File.Delete(tempZip); } catch { }
            try { if (Directory.Exists(tempExtract)) Directory.Delete(tempExtract, recursive: true); } catch { }
        }
    }

    private static HttpClient CreateHttp()
    {
        var http = new HttpClient(new HttpClientHandler { AutomaticDecompression = System.Net.DecompressionMethods.All })
        {
            Timeout = TimeSpan.FromSeconds(60),
        };
        http.DefaultRequestHeaders.UserAgent.ParseAdd(UserAgent);
        http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        return http;
    }

    private static async Task<ReleaseInfo?> FetchLatestReleaseAsync(HttpClient http, CancellationToken ct)
    {
        using var resp = await http.GetAsync(ApiLatest, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
        var json = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);

        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        var info = new ReleaseInfo();
        if (root.TryGetProperty("tag_name", out var tag)) info.TagName = tag.GetString();
        if (root.TryGetProperty("name", out var name)) info.Name = name.GetString();
        if (root.TryGetProperty("assets", out var assets) && assets.ValueKind == JsonValueKind.Array)
        {
            foreach (var a in assets.EnumerateArray())
            {
                var ai = new AssetInfo();
                if (a.TryGetProperty("name", out var n)) ai.Name = n.GetString();
                if (a.TryGetProperty("size", out var sz) && sz.TryGetInt64(out var lv)) ai.Size = lv;
                if (a.TryGetProperty("browser_download_url", out var url)) ai.DownloadUrl = url.GetString();
                if (!string.IsNullOrEmpty(ai.Name) && !string.IsNullOrEmpty(ai.DownloadUrl))
                    info.Assets.Add(ai);
            }
        }
        return info;
    }

    private static AssetInfo? PickAssetForArch(List<AssetInfo> assets)
    {
        bool isX86 = RuntimeInformation.ProcessArchitecture == Architecture.X86;
        var arch = isX86 ? new[] { "win32", "win-x86", "i686", "x86" }
                         : new[] { "win64", "win-x64", "amd64", "x86_64", "x64" };

        // 1. zip-архив под win + нашу архитектуру
        foreach (var a in assets)
        {
            var n = a.Name!.ToLowerInvariant();
            if (!n.EndsWith(".zip", StringComparison.Ordinal)) continue;
            if (!n.Contains("win")) continue;
            if (arch.Any(t => n.Contains(t))) return a;
        }
        // 2. любой zip с win
        foreach (var a in assets)
        {
            var n = a.Name!.ToLowerInvariant();
            if (n.EndsWith(".zip", StringComparison.Ordinal) && n.Contains("win"))
                return a;
        }
        return null;
    }

    private static async Task DownloadAsync(HttpClient http, string url, string dest,
        IProgress<HevUpdateProgress>? progress, CancellationToken ct)
    {
        using var resp = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
        var total = resp.Content.Headers.ContentLength ?? -1L;
        await using var src = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        await using var dst = File.Create(dest);
        var buf = new byte[81920];
        long read = 0;
        int n;
        while ((n = await src.ReadAsync(buf, ct).ConfigureAwait(false)) > 0)
        {
            await dst.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
            read += n;
            if (total > 0)
                progress?.Report(new HevUpdateProgress(HevUpdateStage.Downloading,
                    $"Скачано {read / 1024} / {total / 1024} КБ", (double)read / total));
        }
    }

    /// <summary>
    /// Прибиваем зомби-процессы hev-socks5-tunnel.exe из ToolsDir, чтобы файл
    /// можно было перезаписать. Аналогично StaleProcessCleanup, но targeted.
    /// </summary>
    private static void EnsureNotInUse()
    {
        try
        {
            int self = Process.GetCurrentProcess().Id;
            foreach (var p in Process.GetProcessesByName("hev-socks5-tunnel"))
            {
                try
                {
                    if (p.Id == self) continue;
                    string? exePath = null;
                    try { exePath = p.MainModule?.FileName; } catch { }
                    if (string.IsNullOrEmpty(exePath)) continue;
                    if (!exePath.StartsWith(AppDataPaths.ToolsDir, StringComparison.OrdinalIgnoreCase)) continue;
                    p.Kill(entireProcessTree: true);
                    p.WaitForExit(3000);
                }
                catch { /* пропускаем — попробуем перезаписать вслепую */ }
            }
        }
        catch { }
    }

    private sealed class ReleaseInfo
    {
        public string? TagName { get; set; }
        public string? Name { get; set; }
        public List<AssetInfo> Assets { get; } = new();
    }

    private sealed class AssetInfo
    {
        public string? Name { get; set; }
        public string? DownloadUrl { get; set; }
        public long Size { get; set; }
    }
}
