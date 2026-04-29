namespace Xrav.Core.Xray;

/// <summary>
/// Универсальные клиентские параметры обхода блокировок: фрагментация TLS-ClientHello,
/// мультиплексирование стримов и т.п. Применяются ко всем TLS/REALITY ключам, что
/// позволяет работать даже если конкретный SNI/host у провайдера попал под DPI.
/// </summary>
public sealed record XrayBuildOptions(
    bool EnableFragment = true,
    string FragmentPackets = "tlshello",
    // Дефолты подобраны под v2rayN: 3 фрагмента по 100-200 байт с интервалом 1мс — рвём
    // ClientHello достаточно мелко для DPI, но без существенного оверхеда.
    string FragmentLength = "3",
    string FragmentInterval = "1",
    string FragmentMaxSplit = "100-200",
    // Mux выключен по умолчанию — включается явно через настройки обхода.
    bool EnableMux = false,
    int MuxConcurrency = -1,
    int XudpConcurrency = 8,
    string XudpProxyUDP443 = "",
    bool EnableNoise = false,
    string NoisePacket = "10-20",
    string NoiseDelay = "10-20"
)
{
    public static readonly XrayBuildOptions Default = new();
}
