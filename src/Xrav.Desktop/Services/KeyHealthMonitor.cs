using System.Collections.ObjectModel;
using System.Windows;
using Xrav.Core.Domain;
using Xrav.Desktop.Tools;

namespace Xrav.Desktop.Services;

/// <summary>
/// Фоновый сервис: пингует все ключи раз в N секунд, выбирает с минимальной задержкой и
/// при необходимости (auto-failover) переподключает туннель.
/// </summary>
public sealed class KeyHealthMonitor : IDisposable
{
    private readonly ObservableCollection<VpnKey> _keys;
    private readonly Func<VpnKey?> _getActive;
    private readonly Action<VpnKey> _onSwitch;
    private readonly Action<VpnKey, int?> _updateLatency;
    private CancellationTokenSource? _cts;
    private Task? _loop;
    private TimeSpan _interval = TimeSpan.FromSeconds(30);

    public bool Enabled { get; private set; }
    public VpnKey? BestKey { get; private set; }

    public KeyHealthMonitor(
        ObservableCollection<VpnKey> keys,
        Func<VpnKey?> getActive,
        Action<VpnKey> onSwitch,
        Action<VpnKey, int?> updateLatency)
    {
        _keys = keys;
        _getActive = getActive;
        _onSwitch = onSwitch;
        _updateLatency = updateLatency;
    }

    public void Start()
    {
        if (Enabled) return;
        Enabled = true;
        _cts = new CancellationTokenSource();
        _loop = Task.Run(() => LoopAsync(_cts.Token));
    }

    public void Stop()
    {
        if (!Enabled) return;
        Enabled = false;
        try { _cts?.Cancel(); } catch { /* ignore */ }
    }

    private async Task LoopAsync(CancellationToken ct)
    {
        // Первый прогон сразу, далее по интервалу
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await TickAsync(ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                Logging.FileLogger.Error("healthmon", ex);
            }
            try
            {
                await Task.Delay(_interval, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException) { break; }
        }
    }

    private async Task TickAsync(CancellationToken ct)
    {
        // Снимок ключей в UI-потоке
        VpnKey[] snap = Array.Empty<VpnKey>();
        var app = Application.Current;
        if (app?.Dispatcher is not null)
            snap = await app.Dispatcher.InvokeAsync(() => _keys.ToArray());
        if (snap.Length == 0) return;

        // Пингуем параллельно
        var results = await Task.WhenAll(snap.Select(async k =>
        {
            if (ct.IsCancellationRequested) return (k, (int?)null);
            var ms = await PingTester.MeasureAsync(k).ConfigureAwait(false);
            return (k, ms);
        })).ConfigureAwait(false);

        // Обновляем UI и выбираем лучший
        VpnKey? best = null;
        int bestMs = int.MaxValue;
        foreach (var (k, ms) in results)
        {
            _updateLatency(k, ms);
            if (ms is int v && v < bestMs)
            {
                bestMs = v;
                best = k;
            }
        }
        BestKey = best;
        if (best is null) return;

        // Если активный ключ не пингуется — переключиться на лучший
        var active = _getActive();
        if (active is null)
        {
            _onSwitch(best);
            return;
        }
        var activeMs = results.FirstOrDefault(t => t.k.Id == active.Id).Item2;
        if (activeMs is null && bestMs < int.MaxValue)
        {
            // Активный мёртв — переключаемся
            _onSwitch(best);
        }
    }

    public void Dispose()
    {
        Stop();
        _cts?.Dispose();
    }
}
