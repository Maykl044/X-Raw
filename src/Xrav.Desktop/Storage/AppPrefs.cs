using System.Text.Json;

namespace Xrav.Desktop.Storage;

/// <summary>Пользовательские настройки UI: язык, тема, smart-select. Хранятся в <c>%APPDATA%\X-Rav\prefs.json</c>.</summary>
public sealed class AppPrefs
{
    public string Language { get; set; } = "ru";
    public string Theme { get; set; } = "system";
    public bool AutoSelect { get; set; } = false;
    public bool UrlSchemeRegistered { get; set; } = false;

    // Универсальный обход блокировок (применяется ко всем TLS/REALITY ключам).
    public bool BypassFragmentEnabled { get; set; } = true;
    public string BypassFragmentLength { get; set; } = "10-20";
    public string BypassFragmentInterval { get; set; } = "10-20";
    public bool BypassMuxEnabled { get; set; } = true;
    public int BypassMuxConcurrency { get; set; } = 8;
    public bool BypassNoiseEnabled { get; set; } = false;

    public static string FilePath => Path.Combine(AppDataPaths.XravRoot, "prefs.json");

    public static AppPrefs Load()
    {
        try
        {
            if (!File.Exists(FilePath)) return new AppPrefs();
            var json = File.ReadAllText(FilePath);
            return JsonSerializer.Deserialize<AppPrefs>(json) ?? new AppPrefs();
        }
        catch { return new AppPrefs(); }
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(AppDataPaths.XravRoot);
            var json = JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true });
            File.WriteAllText(FilePath, json);
        }
        catch (Exception ex)
        {
            Logging.FileLogger.Error("prefs", ex);
        }
    }
}
