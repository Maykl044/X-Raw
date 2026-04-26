using Xrav.Core.Domain;
using Xrav.Core.Xray;
using Xrav.Desktop.Tunnel;

namespace Xrav.Desktop.Xray;

public static class VpnKeyXrayConfig
{
    public static bool TryGetPatchedConfig(VpnKey? key, out string json, out string? error)
    {
        json = "";
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
                    json = ImportedXrayJsonPatcher.PatchForWindowsTunnel(key.Raw);
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
                    json = XrayConfigBuilder.BuildFromShareLink(link!, TunnelConstants.SocksInboundPort);
                    error = null;
                    return true;
                }

                case KeyProtocol.Hysteria2:
                case KeyProtocol.Tuic:
                    error = $"Протокол {key.Protocol} требует sing-box, а не xray-core. Поддержка добавится отдельным backend.";
                    return false;

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
}
