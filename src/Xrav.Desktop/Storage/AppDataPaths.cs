namespace Xrav.Desktop.Storage;

public static class AppDataPaths
{
    public static string XravRoot { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "X-Rav");

    public static string UserStateFile { get; } = Path.Combine(XravRoot, "user.json");

    /// <summary>Каталог, куда пользователь кладёт <c>xray.exe</c>, <c>wintun.dll</c>, <c>hev-socks5-tunnel.exe</c> (релизы с GitHub / wintun.net).</summary>
    public static string ToolsDir { get; } = Path.Combine(XravRoot, "tools");

    public static string RuntimeDir { get; } = Path.Combine(XravRoot, "runtime");

    public static string XrayConfigPath { get; } = Path.Combine(RuntimeDir, "config.json");
    public static string HevConfigPath { get; } = Path.Combine(RuntimeDir, "hev-socks5-tunnel.yml");

    /// <summary>Каталог с geoip.dat / geosite.dat — переменная <c>XRAY_LOCATION_ASSET</c> (аналог <c>filesDir/xray_env</c> в Android).</summary>
    public static string XrayAssetDir { get; } = Path.Combine(XravRoot, "xray_assets");
}
