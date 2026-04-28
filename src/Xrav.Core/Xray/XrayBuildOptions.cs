namespace Xrav.Core.Xray;

/// <summary>
/// Универсальные клиентские параметры обхода блокировок: фрагментация TLS-ClientHello,
/// мультиплексирование стримов и т.п. Применяются ко всем TLS/REALITY ключам, что
/// позволяет работать даже если конкретный SNI/host у провайдера попал под DPI.
/// </summary>
public sealed record XrayBuildOptions(
    bool EnableFragment = true,
    string FragmentPackets = "tlshello",
    string FragmentLength = "10-20",
    string FragmentInterval = "10-20",
    bool EnableMux = true,
    int MuxConcurrency = 8,
    bool EnableNoise = false,
    string NoisePacket = "10-20",
    string NoiseDelay = "10-20"
)
{
    public static readonly XrayBuildOptions Default = new();
}
