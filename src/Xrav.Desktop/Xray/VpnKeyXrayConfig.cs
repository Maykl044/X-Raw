using Xrav.Core.Domain;
using Xrav.Core.SingBox;
using Xrav.Core.Xray;
using Xrav.Desktop.Storage;
using Xrav.Desktop.Tunnel;

namespace Xrav.Desktop.Xray;

public enum BackendKind
{
    /// <summary>xray.exe (SOCKS на 127.0.0.1:10808) + hev-socks5-tunnel + Wintun.</summary>
    XrayWithHev,

    /// <summary>sing-box.exe со встроенным TUN-инбаундом (auto_route). Hev/Wintun-обвязка не нужна.</summary>
    SingBox
}

public sealed record BackendConfig(BackendKind Kind, string ConfigJson, string? ServerHost = null);

public static class VpnKeyXrayConfig
{
    /// <summary>
    /// Решает, каким backend'ом и с каким конфигом запустить туннель.
    /// Hysteria2/TUIC всегда уходят в sing-box. Импортированный JSON и линки VLESS/VMess/Trojan/SS — в xray+hev.
    /// </summary>
    public static bool TryGetBackend(VpnKey? key, out BackendConfig? backend, out string? error)
    {
        backend = null;
        if (key is null)
        {
            error = "Нет ключа. Добавьте в список как минимум один ключ.";
            return false;
        }

        try
        {
            switch (key.Protocol)
            {
                case KeyProtocol.Json:
                    backend = new BackendConfig(BackendKind.XrayWithHev,
                        ImportedXrayJsonPatcher.PatchForWindowsTunnel(key.Raw),
                        ImportedXrayJsonPatcher.ExtractServerHost(key.Raw));
                    error = null;
                    return true;

                case KeyProtocol.Vless:
                case KeyProtocol.VMess:
                case KeyProtocol.Trojan:
                case KeyProtocol.Shadowsocks:
                {
                    if (!ShareLinkParser.TryParse(key.Raw, out var link, out _, out var parseErr))
                    {
                        error = parseErr ?? "Не удалось распарсить ключ.";
                        return false;
                    }
                    backend = new BackendConfig(BackendKind.XrayWithHev,
                        XrayConfigBuilder.BuildFromShareLink(link!, BuildOptionsFromPrefs(), TunnelConstants.SocksInboundPort),
                        link!.Host);
                    error = null;
                    return true;
                }

                case KeyProtocol.Hysteria2:
                case KeyProtocol.Tuic:
                {
                    if (!ShareLinkParser.TryParse(key.Raw, out var link, out _, out var parseErr))
                    {
                        error = parseErr ?? "Не удалось распарсить ключ.";
                        return false;
                    }
                    backend = new BackendConfig(BackendKind.SingBox,
                        SingBoxConfigBuilder.BuildFromShareLink(link!),
                        link!.Host);
                    error = null;
                    return true;
                }

                default:
                    error = "Неизвестный протокол ключа.";
                    return false;
            }
        }
        catch (Exception ex)
        {
            error = ex.Message;
            return false;
        }
    }

    /// <summary>Берёт текущие prefs обхода блокировок и собирает <see cref="XrayBuildOptions"/>.</summary>
    public static XrayBuildOptions BuildOptionsFromPrefs()
    {
        var prefs = AppPrefs.Load();
        return new XrayBuildOptions(
            EnableFragment: prefs.BypassFragmentEnabled,
            FragmentLength: prefs.BypassFragmentLength,
            FragmentInterval: prefs.BypassFragmentInterval,
            EnableMux: prefs.BypassMuxEnabled,
            MuxConcurrency: prefs.BypassMuxConcurrency,
            EnableNoise: prefs.BypassNoiseEnabled
        );
    }

    /// <summary>Совместимость со старым кодом: только xray-конфиг (без sing-box-кейсов).</summary>
    public static bool TryGetPatchedConfig(VpnKey? key, out string json, out string? error)
    {
        json = "";
        if (!TryGetBackend(key, out var backend, out error)) return false;
        if (backend!.Kind != BackendKind.XrayWithHev)
        {
            error = $"Протокол {key!.Protocol} требует sing-box backend.";
            return false;
        }
        json = backend.ConfigJson;
        error = null;
        return true;
    }
}
