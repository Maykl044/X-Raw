using Xrav.Core.Domain;

namespace Xrav.Desktop.Xray;

public static class VpnKeyXrayConfig
{
    public static bool TryGetPatchedConfig(VpnKey? key, out string json, out string? error)
    {
        json = "";
        if (key is null)
        {
            error = "Нет ключа. Добавьте в список как минимум один ключ (на Windows сейчас используется первый).";
            return false;
        }
        if (key.Protocol != KeyProtocol.Json)
        {
            error = "Сборка outbound из VLESS/VMess/Trojan-ссылок на Windows пока не перенесена. Добавьте ключ с протоколом «JSON» (готовый config.json из v2rayN / sing-box, экспорт).";
            return false;
        }
        try
        {
            json = ImportedXrayJsonPatcher.PatchForWindowsTunnel(key.Raw);
            error = null;
            return true;
        }
        catch (Exception ex)
        {
            error = ex.Message;
            return false;
        }
    }
}
