using System.Net.Http;
using System.Text.Json.Nodes;

namespace Xrav.Desktop.Tools;

public sealed record UpdateInfo(string Tool, string Current, string Latest);

/// <summary>
/// Опрашивает GitHub releases для xray-core / sing-box / hev и сравнивает
/// с версиями встроенных бинарников. Сетевая ошибка — игнорируется.
/// </summary>
public static class UpdateChecker
{
    private static readonly (string Tool, string Repo, string Current)[] Sources =
    {
        ("Xray-core",        "XTLS/Xray-core",       BundledTools.XrayVersion),
        ("sing-box",         "SagerNet/sing-box",    BundledTools.SingBoxVersion),
        ("hev-socks5-tunnel","heiher/hev-socks5-tunnel", BundledTools.HevVersion)
    };

    public static async Task<IReadOnlyList<UpdateInfo>> CheckAsync(HttpClient http, CancellationToken ct = default)
    {
        var result = new List<UpdateInfo>();
        foreach (var (tool, repo, current) in Sources)
        {
            try
            {
                using var req = new HttpRequestMessage(HttpMethod.Get,
                    $"https://api.github.com/repos/{repo}/releases/latest");
                req.Headers.UserAgent.ParseAdd("X-Rav/1.0");
                using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
                if (!resp.IsSuccessStatusCode) continue;
                var body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
                var node = JsonNode.Parse(body) as JsonObject;
                var tag = node?["tag_name"]?.GetValue<string>() ?? "";
                tag = tag.TrimStart('v', 'V');
                if (string.IsNullOrEmpty(tag)) continue;
                if (!IsNewer(tag, current)) continue;
                result.Add(new UpdateInfo(tool, current, tag));
            }
            catch { /* network noise — ignore */ }
        }
        return result;
    }

    /// <summary>Сравнение semver-подобных строк: "26.3.27" vs "26.4.0".</summary>
    private static bool IsNewer(string remote, string current)
    {
        var a = ParseVersion(remote);
        var b = ParseVersion(current);
        for (int i = 0; i < Math.Max(a.Length, b.Length); i++)
        {
            int x = i < a.Length ? a[i] : 0;
            int y = i < b.Length ? b[i] : 0;
            if (x > y) return true;
            if (x < y) return false;
        }
        return false;
    }

    private static int[] ParseVersion(string s)
    {
        var parts = s.Split('.', '-', '+');
        var nums = new List<int>();
        foreach (var p in parts)
        {
            if (int.TryParse(p, out var n)) nums.Add(n);
            else break;
        }
        return nums.ToArray();
    }
}
