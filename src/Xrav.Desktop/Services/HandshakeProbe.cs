using System;
using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Threading;
using System.Threading.Tasks;
using Xrav.Core.Domain;
using Xrav.Core.Xray;

namespace Xrav.Desktop.Services;

/// <summary>
/// Реальная проверка соединения. Два режима:
///  • <see cref="ProbeAsync"/> — TCP+TLS handshake напрямую к серверу ключа (до подключения VPN).
///  • <see cref="ProbeViaTunnelAsync"/> — HTTP(S)-проверка через локальный xray-инбаунд
///    (после подключения VPN): подтверждает что трафик реально идёт через туннель.
/// </summary>
public static class HandshakeProbe
{
    /// <summary>
    /// Проверка живого VPN-соединения через локальный xray-инбаунд.
    /// HTTP GET к /generate_204 эндпоинтам через 127.0.0.1:&lt;httpInboundPort&gt; (по умолчанию 10809).
    /// Возвращает (Ok, Ms, Detail). Ok=true — туннель пропускает трафик до глобального интернета.
    /// </summary>
    public static async Task<(bool Ok, int Ms, string Detail)> ProbeViaTunnelAsync(
        int httpInboundPort, TimeSpan timeout)
    {
        // Каскад из нескольких "captive portal" эндпоинтов: ожидаемый ответ — 204 No Content.
        // Используем разных провайдеров чтобы не зависеть от блокировки одного.
        var endpoints = new[]
        {
            "http://www.gstatic.com/generate_204",
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://cp.cloudflare.com/generate_204",
        };

        var sw = Stopwatch.StartNew();
        try
        {
            var proxy = new WebProxy($"http://127.0.0.1:{httpInboundPort}", BypassOnLocal: false);
            using var handler = new HttpClientHandler
            {
                Proxy = proxy,
                UseProxy = true,
                AllowAutoRedirect = false,
                ServerCertificateCustomValidationCallback = (_, _, _, _) => true,
            };
            using var http = new HttpClient(handler) { Timeout = timeout };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0 X-Rav handshake-probe");

            string? lastErr = null;
            foreach (var url in endpoints)
            {
                try
                {
                    using var cts = new CancellationTokenSource(timeout);
                    using var resp = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cts.Token).ConfigureAwait(false);
                    var ms = (int)sw.ElapsedMilliseconds;
                    if ((int)resp.StatusCode is 204 or 200)
                    {
                        var via = url.Contains("gstatic") ? "Google" : url.Contains("cloudflare") ? "Cloudflare" : "captive";
                        return (true, ms, $"HTTP {(int)resp.StatusCode} через VPN · {via}");
                    }
                    lastErr = $"HTTP {(int)resp.StatusCode} {resp.ReasonPhrase}";
                }
                catch (Exception ex)
                {
                    lastErr = ex.Message;
                }
            }
            return (false, (int)sw.ElapsedMilliseconds, $"Туннель не пропустил HTTP: {lastErr}");
        }
        catch (Exception ex)
        {
            return (false, (int)sw.ElapsedMilliseconds, ex.Message);
        }
    }

    public static async Task<(bool Ok, int Ms, string Detail)> ProbeAsync(VpnKey key, TimeSpan timeout)
    {
        if (string.IsNullOrEmpty(key.Host) || key.Port is null or <= 0)
            return (false, 0, "У ключа нет host/port.");

        // Парсим параметры из исходной share-ссылки, чтобы получить SNI/security.
        ShareLink? link = null;
        if (key.Source != KeySource.ManualJson)
        {
            try { ShareLinkParser.TryParse(key.Raw, out link, out _, out _); }
            catch { /* ignore */ }
        }

        var host = key.Host!;
        var port = key.Port!.Value;
        var sni = link?.Sni;
        var security = (link?.Security ?? "").ToLowerInvariant();
        var sw = Stopwatch.StartNew();

        try
        {
            using var tcp = new TcpClient();
            using var cts = new CancellationTokenSource(timeout);

#if NET6_0_OR_GREATER
            await tcp.ConnectAsync(host, port, cts.Token).ConfigureAwait(false);
#else
            var connectTask = tcp.ConnectAsync(host, port);
            if (await Task.WhenAny(connectTask, Task.Delay(timeout)).ConfigureAwait(false) != connectTask)
                return (false, (int)sw.ElapsedMilliseconds, "TCP timeout");
#endif
            var tcpMs = (int)sw.ElapsedMilliseconds;
            tcp.NoDelay = true;
            tcp.ReceiveTimeout = (int)timeout.TotalMilliseconds;

            // Для REALITY на нашей стороне настоящий handshake невозможен без xray-кора
            // (Reality использует приватный ключ сервера + наш PublicKey/ShortId).
            // Здесь мы проверяем что TLS-сервер живой, отвечает с тем же SNI.
            var isReality = security == "reality";
            var doTls = security is "tls" or "reality" or "xtls";

            if (!doTls)
            {
                // Для не-TLS ключей возвращаем результат TCP-уровня.
                return (true, tcpMs, $"TCP OK · {host}:{port}");
            }

            using var ssl = new SslStream(tcp.GetStream(), leaveInnerStreamOpen: false,
                userCertificateValidationCallback: ValidateAlwaysTrue);
            var sslOpts = new SslClientAuthenticationOptions
            {
                TargetHost = string.IsNullOrEmpty(sni) ? host : sni!,
                EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                AllowRenegotiation = true,
                CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
            };

            try
            {
                await ssl.AuthenticateAsClientAsync(sslOpts, cts.Token).ConfigureAwait(false);
            }
            catch (Exception tlsEx)
            {
                if (isReality)
                {
                    // Для Reality сервер маскируется под другой домен — TLS handshake может
                    // не пройти если SNI не совпадает с маскировочным. Это норма для Reality,
                    // но всё равно сигнал что сервер живой.
                    return (true, (int)sw.ElapsedMilliseconds,
                        $"REALITY: TCP OK ({tcpMs} мс), TLS-маскировка скрывает реальный сертификат — это ожидаемо.");
                }
                return (false, (int)sw.ElapsedMilliseconds,
                    $"TLS handshake fail: {tlsEx.Message}");
            }

            var totalMs = (int)sw.ElapsedMilliseconds;
            var proto = ssl.SslProtocol.ToString();
            var cipher = ssl.NegotiatedCipherSuite.ToString();
            var detail = $"TLS OK · {proto} · {cipher} · SNI={sslOpts.TargetHost}";
            return (true, totalMs, detail);
        }
        catch (OperationCanceledException)
        {
            return (false, (int)sw.ElapsedMilliseconds, "Таймаут соединения.");
        }
        catch (SocketException sx)
        {
            return (false, (int)sw.ElapsedMilliseconds, $"Сеть: {sx.SocketErrorCode} ({sx.Message})");
        }
        catch (Exception ex)
        {
            return (false, (int)sw.ElapsedMilliseconds, ex.Message);
        }
    }

    private static bool ValidateAlwaysTrue(object _, X509Certificate? __, X509Chain? ___, SslPolicyErrors ____) => true;
}
