using System.Diagnostics;
using System.Net.NetworkInformation;

namespace Xrav.Desktop.Services;

/// <summary>
/// Ждёт пока wintun-интерфейс по имени (например, "X-RavWintun") появится в системе
/// и перейдёт в OperationalStatus=Up. Возвращает время ожидания в миллисекундах,
/// либо -1 если интерфейс так и не поднялся за <paramref name="timeout"/>.
///
/// Эта механика взята из happ-daemon: после старта tun-провайдера демон поллит
/// интерфейс до 10 секунд, прежде чем настраивать DNS и маршруты — это куда
/// надёжнее, чем фиксированный <c>Task.Delay(1500)</c>.
/// </summary>
public static class TunnelInterfaceWaiter
{
    public static async Task<int> WaitForUpAsync(
        string interfaceName,
        TimeSpan timeout,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrEmpty(interfaceName)) return -1;
        var sw = Stopwatch.StartNew();
        var deadline = sw.Elapsed + timeout;

        while (sw.Elapsed < deadline)
        {
            cancellationToken.ThrowIfCancellationRequested();

            try
            {
                var nics = NetworkInterface.GetAllNetworkInterfaces();
                foreach (var nic in nics)
                {
                    // Сравниваем по Name и Description; wintun-адаптер на Windows
                    // часто отображается по описанию ("Wintun Userspace Tunnel").
                    if (string.Equals(nic.Name, interfaceName, StringComparison.OrdinalIgnoreCase)
                        && nic.OperationalStatus == OperationalStatus.Up)
                        return (int)sw.ElapsedMilliseconds;
                }
            }
            catch
            {
                // ignore — может выкинуть пока адаптер ещё не существует
            }

            try { await Task.Delay(150, cancellationToken).ConfigureAwait(false); }
            catch (OperationCanceledException) { throw; }
        }

        return -1;
    }
}
