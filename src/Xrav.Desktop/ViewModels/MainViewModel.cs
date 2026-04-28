using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.ComponentModel;
using System.Net.Http;
using System.Windows;
using System.Windows.Input;
using Xrav.Core.Domain;
using Xrav.Core.State;
using Xrav.Core.Subscription;
using Xrav.Core.Xray;
using Xrav.Desktop.Localization;
using Xrav.Desktop.Logging;
using Xrav.Desktop.Services;
using Xrav.Desktop.Storage;
using Xrav.Desktop.Theme;
using Xrav.Desktop.Tools;

namespace Xrav.Desktop.ViewModels;

public enum MainTab
{
    Servers,
    Subscription,
    Log,
    Settings
}

public sealed class MainViewModel : ViewModelBase
{
    private readonly ITunnelService _tunnel;
    private readonly IUserStateStore _store;
    private readonly AppPrefs _prefs;
    private readonly KeyHealthMonitor _health;
    private static readonly HttpClient SharedHttp = ToolBootstrapper.CreateDefaultClient();
    private MainTab _selectedTab = MainTab.Servers;
    private bool _powerBusy;
    private string _subscriptionFilterEncoded = "@all";
    private bool _persistSuspended;
    private VpnKey? _selectedKey;
    private SubscriptionEntry? _selectedSubscription;
    private string _newSubscriptionUrl = "";
    private string _newSubscriptionLabel = "";
    private string _bootstrapStatus = "";
    private bool _bootstrapBusy;
    private string _manualKeyText = "";
    private bool _urlSchemeRegistered;

    public MainViewModel(ITunnelService tunnel, IUserStateStore store)
    {
        _tunnel = tunnel;
        _store = store;
        _prefs = AppPrefs.Load();
        _urlSchemeRegistered = UrlSchemeRegistrar.IsRegistered();
        if (tunnel is WinTunnelService w)
            w.LogLine += OnTunnelLogLine;
        ConnectCommand = new RelayCommand(Connect, () => !PowerBusy && CanConnect);
        DisconnectCommand = new RelayCommand(
            Disconnect,
            () => !PowerBusy
                 && _tunnel.State is TunnelConnectionState.Connected
                     or TunnelConnectionState.Connecting
                     or TunnelConnectionState.Error);
        GoServersCommand = new RelayCommand(() => SelectedTab = MainTab.Servers);
        GoSubscriptionCommand = new RelayCommand(() => SelectedTab = MainTab.Subscription);
        GoLogCommand = new RelayCommand(() => SelectedTab = MainTab.Log);
        GoSettingsCommand = new RelayCommand(() => SelectedTab = MainTab.Settings);
        ClearTunnelLogCommand = new RelayCommand(() => TunnelLog.Clear());
        CopyTunnelLogCommand = new RelayCommand(CopyTunnelLogToClipboard);
        ImportFromClipboardCommand = new RelayCommand(ImportFromClipboard);
        AddManualKeyCommand = new RelayCommand(AddManualKey, () => !string.IsNullOrWhiteSpace(ManualKeyText));
        RemoveSelectedKeyCommand = new RelayCommand(RemoveSelectedKey, () => SelectedKey is not null);
        AddSubscriptionCommand = new RelayCommand(
            async () => await AddSubscriptionAsync(),
            () => !string.IsNullOrWhiteSpace(NewSubscriptionUrl));
        RemoveSubscriptionCommand = new RelayCommand(RemoveSelectedSubscription, () => SelectedSubscription is not null);
        RefreshSubscriptionCommand = new RelayCommand(
            async () => await RefreshSubscriptionAsync(SelectedSubscription),
            () => SelectedSubscription is not null);
        RefreshAllSubscriptionsCommand = new RelayCommand(async () => await RefreshAllSubscriptionsAsync());
        BootstrapToolsCommand = new RelayCommand(async () => await BootstrapToolsAsync(), () => !BootstrapBusy);
        OpenDataFolderCommand = new RelayCommand(OpenDataFolder);
        OpenLogFileCommand = new RelayCommand(OpenLogFile);
        OpenWebsiteCommand = new RelayCommand(() => OpenUrl("https://rock.rockefellers.store"));
        OpenGithubCommand = new RelayCommand(() => OpenUrl("https://github.com/Maykl044/X-Raw"));
        OpenDeveloper1Command = new RelayCommand(() => OpenUrl("https://t.me/igroutech"));
        OpenDeveloper2Command = new RelayCommand(() => OpenUrl("https://t.me/BernarAr_no"));
        RegisterUrlSchemeCommand = new RelayCommand(RegisterUrlScheme);
        PingSelectedKeyCommand = new RelayCommand(async () => await PingKeyAsync(SelectedKey), () => SelectedKey is not null && !PingBusy);
        PingAllKeysCommand    = new RelayCommand(async () => await PingAllKeysAsync(), () => Keys.Count > 0 && !PingBusy);
        CheckHandshakeCommand = new RelayCommand(async () => await CheckHandshakeAsync(SelectedKey), () => SelectedKey is not null && !PingBusy);
        RefreshSubscriptionByIdCommand = new RelayCommand(async p => {
            var id = p as string;
            var entry = Subscriptions.FirstOrDefault(s => s.Id == id);
            if (entry is not null) await RefreshSubscriptionAsync(entry);
        });
        RemoveSubscriptionByIdCommand = new RelayCommand(p => {
            var id = p as string;
            var entry = Subscriptions.FirstOrDefault(s => s.Id == id);
            if (entry is null) return;
            Subscriptions.Remove(entry);
            // Удалим связанные ключи.
            var orphans = Keys.Where(k => k.SubscriptionId == id).ToList();
            foreach (var k in orphans) Keys.Remove(k);
        });
        DismissUpdateBannerCommand = new RelayCommand(() => UpdateBanner = null);
        _ = CheckForUpdatesAsync();
        if (tunnel is INotifyPropertyChanged npc)
        {
            npc.PropertyChanged += (_, a) =>
            {
                if (a.PropertyName is nameof(ITunnelService.State) or nameof(ITunnelService.LastError))
                {
                    OnPropertyChanged(nameof(TunnelState));
                    OnPropertyChanged(nameof(TunnelStateDisplay));
                    OnPropertyChanged(nameof(PowerHint));
                    OnPropertyChanged(nameof(TunnelError));
                    OnPropertyChanged(nameof(CanConnect));
                    OnPropertyChanged(nameof(AutoStatusLine));
                    RaiseAllCanExecute();
                }
            };
        }

        // Реакция на смену языка — перерисовываем локализованные свойства.
        Localization.LocalizationService.Current.PropertyChanged += (_, a) =>
        {
            if (a.PropertyName is "Item[]" or nameof(Localization.LocalizationService.Language))
            {
                OnPropertyChanged(nameof(TunnelStateDisplay));
                OnPropertyChanged(nameof(PowerHint));
                OnPropertyChanged(nameof(AutoStatusLine));
                OnPropertyChanged(nameof(KeysCountDisplay));
            }
        };

        LoadFromStore();
        Keys.CollectionChanged += OnKeysChanged;
        Subscriptions.CollectionChanged += OnSubscriptionsChanged;

        // KeyHealthMonitor: инициализируем после загрузки ключей
        _health = new KeyHealthMonitor(
            keys: Keys,
            getActive: () => SelectedKey,
            onSwitch: OnAutoSwitchKey,
            updateLatency: UpdateKeyLatency);
        if (_prefs.AutoSelect) _health.Start();
    }

    public ObservableCollection<VpnKey> Keys { get; } = new();
    public ObservableCollection<SubscriptionEntry> Subscriptions { get; } = new();
    public ObservableCollection<string> TunnelLog { get; } = new();

    public string SubscriptionFilterEncoded
    {
        get => _subscriptionFilterEncoded;
        set
        {
            if (_subscriptionFilterEncoded == value) return;
            _subscriptionFilterEncoded = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(ActiveSubscriptionFilter));
            Persist();
        }
    }

    public SubscriptionFilter ActiveSubscriptionFilter => SubscriptionFilter.Decode(SubscriptionFilterEncoded);

    public VpnKey? SelectedKey
    {
        get => _selectedKey;
        set
        {
            if (ReferenceEquals(_selectedKey, value)) return;
            _selectedKey = value;
            OnPropertyChanged();
            (RemoveSelectedKeyCommand as RelayCommand)?.RaiseCanExecuteChanged();
            (CheckHandshakeCommand as RelayCommand)?.RaiseCanExecuteChanged();
            if (!_persistSuspended) Persist();
        }
    }

    public SubscriptionEntry? SelectedSubscription
    {
        get => _selectedSubscription;
        set
        {
            if (ReferenceEquals(_selectedSubscription, value)) return;
            _selectedSubscription = value;
            OnPropertyChanged();
            (RemoveSubscriptionCommand as RelayCommand)?.RaiseCanExecuteChanged();
            (RefreshSubscriptionCommand as RelayCommand)?.RaiseCanExecuteChanged();
        }
    }

    public string NewSubscriptionUrl
    {
        get => _newSubscriptionUrl;
        set
        {
            if (_newSubscriptionUrl == value) return;
            _newSubscriptionUrl = value;
            OnPropertyChanged();
            (AddSubscriptionCommand as RelayCommand)?.RaiseCanExecuteChanged();
        }
    }

    public string NewSubscriptionLabel
    {
        get => _newSubscriptionLabel;
        set
        {
            if (_newSubscriptionLabel == value) return;
            _newSubscriptionLabel = value;
            OnPropertyChanged();
        }
    }

    public string ManualKeyText
    {
        get => _manualKeyText;
        set
        {
            if (_manualKeyText == value) return;
            _manualKeyText = value;
            OnPropertyChanged();
            (AddManualKeyCommand as RelayCommand)?.RaiseCanExecuteChanged();
        }
    }

    public string BootstrapStatus
    {
        get => _bootstrapStatus;
        private set => Set(ref _bootstrapStatus, value);
    }

    public bool BootstrapBusy
    {
        get => _bootstrapBusy;
        private set
        {
            if (_bootstrapBusy == value) return;
            _bootstrapBusy = value;
            OnPropertyChanged();
            (BootstrapToolsCommand as RelayCommand)?.RaiseCanExecuteChanged();
        }
    }

    public MainTab SelectedTab
    {
        get => _selectedTab;
        set
        {
            if (_selectedTab == value) return;
            _selectedTab = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(IsServersTab));
            OnPropertyChanged(nameof(IsSubscriptionTab));
            OnPropertyChanged(nameof(IsLogTab));
            OnPropertyChanged(nameof(IsSettingsTab));
        }
    }

    // ---------- Theme / Language / Auto-select ----------

    /// <summary>Код текущей темы: "light" / "dark" / "system". Bind из списка выбора.</summary>
    public string SelectedThemeCode
    {
        get => Theme.ThemeService.Code(Theme.ThemeService.Current.Theme);
        set
        {
            var t = Theme.ThemeService.ParseCode(value);
            if (Theme.ThemeService.Current.Theme == t) return;
            Theme.ThemeService.Current.Theme = t;
            _prefs.Theme = Theme.ThemeService.Code(t);
            _prefs.Save();
            OnPropertyChanged();
        }
    }

    /// <summary>Код языка: "ru" / "en" / "tr".</summary>
    public string SelectedLanguageCode
    {
        get => Localization.LocalizationService.LangCode(Localization.LocalizationService.Current.Language);
        set
        {
            var l = Localization.LocalizationService.ParseCode(value);
            if (Localization.LocalizationService.Current.Language == l) return;
            Localization.LocalizationService.Current.Language = l;
            _prefs.Language = Localization.LocalizationService.LangCode(l);
            _prefs.Save();
            OnPropertyChanged();
        }
    }

    /// <summary>Авто-выбор лучшего ключа по пингу + автопереключение при сбое.</summary>
    public bool AutoSelectMode
    {
        get => _prefs.AutoSelect;
        set
        {
            if (_prefs.AutoSelect == value) return;
            _prefs.AutoSelect = value;
            _prefs.Save();
            if (value) _health?.Start();
            else _health?.Stop();
            OnPropertyChanged();
            OnPropertyChanged(nameof(IsManualMode));
            OnPropertyChanged(nameof(AutoStatusLine));
        }
    }

    public bool IsManualMode => !AutoSelectMode;

    public string AutoStatusLine
    {
        get
        {
            var loc = Localization.LocalizationService.Current;
            if (!AutoSelectMode) return string.Empty;
            var best = _health?.BestKey ?? Keys.OrderBy(k => k.LatencyMs ?? int.MaxValue).FirstOrDefault();
            if (best is null || best.LatencyMs is null) return loc["auto.searching"];
            return string.Format(loc["auto.connectedTo"], best.Remark, best.LatencyMs);
        }
    }

    public bool UrlSchemeRegistered
    {
        get => _urlSchemeRegistered;
        private set => Set(ref _urlSchemeRegistered, value);
    }

    public string KeysCountDisplay
    {
        get
        {
            var loc = Localization.LocalizationService.Current;
            return string.Format(loc["servers.count"], Keys.Count);
        }
    }

    public string AppVersion => typeof(MainViewModel).Assembly.GetName().Version?.ToString(3) ?? "1.0.0";

    public bool IsServersTab => SelectedTab == MainTab.Servers;
    public bool IsSubscriptionTab => SelectedTab == MainTab.Subscription;
    public bool IsLogTab => SelectedTab == MainTab.Log;
    public bool IsSettingsTab => SelectedTab == MainTab.Settings;

    public TunnelConnectionState TunnelState => _tunnel.State;
    public string? TunnelError => _tunnel.LastError;
    public string TunnelStateDisplay
    {
        get
        {
            var loc = Localization.LocalizationService.Current;
            return TunnelState switch
            {
                TunnelConnectionState.Disconnected => loc["state.disconnected"],
                TunnelConnectionState.Connecting => loc["state.connecting"] + "…",
                TunnelConnectionState.Connected => loc["state.connected"],
                TunnelConnectionState.Reconnecting => loc["state.reconnecting"] + "…",
                TunnelConnectionState.Error => loc["state.error"],
                _ => "—"
            };
        }
    }
    public string PowerHint
    {
        get
        {
            var loc = Localization.LocalizationService.Current;
            if (AutoSelectMode && TunnelState == TunnelConnectionState.Disconnected)
                return loc["power.auto"];
            return TunnelState switch
            {
                TunnelConnectionState.Connected => loc["power.disconnect"],
                TunnelConnectionState.Connecting => loc["power.connecting"],
                TunnelConnectionState.Reconnecting => loc["power.reconnecting"],
                TunnelConnectionState.Error => loc["power.error"],
                _ => loc["power.connect"]
            };
        }
    }
    public bool CanConnect => _tunnel.State is TunnelConnectionState.Disconnected or TunnelConnectionState.Error;
    public bool PowerBusy
    {
        get => _powerBusy;
        set
        {
            if (_powerBusy == value) return;
            _powerBusy = value;
            OnPropertyChanged();
            RaiseAllCanExecute();
        }
    }

    public ICommand ConnectCommand { get; }
    public ICommand DisconnectCommand { get; }
    public ICommand GoServersCommand { get; }
    public ICommand GoSubscriptionCommand { get; }
    public ICommand GoLogCommand { get; }
    public ICommand GoSettingsCommand { get; }
    public ICommand ClearTunnelLogCommand { get; }
    public ICommand CopyTunnelLogCommand { get; }
    public ICommand OpenWebsiteCommand { get; }
    public ICommand OpenGithubCommand { get; }
    public ICommand OpenDeveloper1Command { get; }
    public ICommand OpenDeveloper2Command { get; }
    public ICommand RegisterUrlSchemeCommand { get; }
    public ICommand ImportFromClipboardCommand { get; }
    public ICommand AddManualKeyCommand { get; }
    public ICommand RemoveSelectedKeyCommand { get; }
    public ICommand AddSubscriptionCommand { get; }
    public ICommand RemoveSubscriptionCommand { get; }
    public ICommand RefreshSubscriptionCommand { get; }
    public ICommand RefreshAllSubscriptionsCommand { get; }
    public ICommand BootstrapToolsCommand { get; }
    public ICommand OpenDataFolderCommand { get; }
    public ICommand OpenLogFileCommand { get; }
    public ICommand PingSelectedKeyCommand { get; }
    public ICommand PingAllKeysCommand { get; }
    public ICommand CheckHandshakeCommand { get; }
    public ICommand RefreshSubscriptionByIdCommand { get; }
    public ICommand RemoveSubscriptionByIdCommand { get; }
    public ICommand DismissUpdateBannerCommand { get; }

    private string? _updateBanner;
    public string? UpdateBanner
    {
        get => _updateBanner;
        private set
        {
            if (_updateBanner == value) return;
            _updateBanner = value;
            OnPropertyChanged();
        }
    }

    private bool _pingBusy;
    public bool PingBusy
    {
        get => _pingBusy;
        private set
        {
            if (_pingBusy == value) return;
            _pingBusy = value;
            OnPropertyChanged();
            (PingSelectedKeyCommand as RelayCommand)?.RaiseCanExecuteChanged();
            (PingAllKeysCommand as RelayCommand)?.RaiseCanExecuteChanged();
        }
    }

    private async Task PingKeyAsync(VpnKey? key)
    {
        if (key is null) return;
        PingBusy = true;
        try
        {
            var ms = await Tools.PingTester.MeasureAsync(key).ConfigureAwait(false);
            UpdateKeyLatency(key, ms);
        }
        finally { PingBusy = false; }
    }

    private async Task PingAllKeysAsync()
    {
        PingBusy = true;
        try
        {
            var snap = Keys.ToList();
            var tasks = snap.Select(async k =>
            {
                var ms = await Tools.PingTester.MeasureAsync(k).ConfigureAwait(false);
                return (k, ms);
            }).ToArray();
            var results = await Task.WhenAll(tasks).ConfigureAwait(false);
            foreach (var (k, ms) in results) UpdateKeyLatency(k, ms);
        }
        finally { PingBusy = false; }
    }

    private void UpdateKeyLatency(VpnKey key, int? ms)
    {
        var app = Application.Current;
        void Apply()
        {
            for (int i = 0; i < Keys.Count; i++)
            {
                if (Keys[i].Id == key.Id)
                {
                    Keys[i] = Keys[i] with { LatencyMs = ms };
                    if (SelectedKey?.Id == key.Id) SelectedKey = Keys[i];
                    break;
                }
            }
        }
        if (app?.Dispatcher is not null && !app.Dispatcher.CheckAccess())
            app.Dispatcher.Invoke(Apply);
        else Apply();
    }

    private async Task CheckForUpdatesAsync()
    {
        try
        {
            var updates = await Tools.UpdateChecker.CheckAsync(SharedHttp).ConfigureAwait(false);
            if (updates.Count == 0) return;
            var msg = string.Join(", ", updates.Select(u => $"{u.Tool} {u.Current}→{u.Latest}"));
            UpdateBanner = $"Доступны обновления: {msg}. Нажмите «Подготовить» в Настройках для скачивания.";
        }
        catch { /* offline */ }
    }

    public ITunnelService Tunnel => _tunnel;

    public void Teardown()
    {
        if (_tunnel is WinTunnelService w)
            w.LogLine -= OnTunnelLogLine;
        _health?.Dispose();
    }

    private void OnAutoSwitchKey(VpnKey key)
    {
        // Вызывается из фонового потока KeyHealthMonitor
        var app = System.Windows.Application.Current;
        if (app is null) return;
        app.Dispatcher.BeginInvoke(new Action(async () =>
        {
            try
            {
                var same = SelectedKey?.Id == key.Id
                    && _tunnel.State is TunnelConnectionState.Connected;
                if (same) return;
                SelectedKey = key;
                if (!AutoSelectMode) return;
                if (_tunnel.State is TunnelConnectionState.Connected
                    or TunnelConnectionState.Connecting
                    or TunnelConnectionState.Reconnecting)
                {
                    await _tunnel.DisconnectAsync();
                }
                await _tunnel.ConnectAsync(key);
            }
            catch (Exception ex)
            {
                FileLogger.Error("autoSwitch", ex);
            }
            finally
            {
                OnPropertyChanged(nameof(AutoStatusLine));
            }
        }));
    }

    private void RegisterUrlScheme()
    {
        try
        {
            UrlSchemeRegistrar.Register();
            UrlSchemeRegistered = UrlSchemeRegistrar.IsRegistered();
        }
        catch (Exception ex)
        {
            FileLogger.Error("urlScheme.user", ex);
        }
    }

    private static void OpenUrl(string url)
    {
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            FileLogger.Error("openUrl", ex);
        }
    }

    private void CopyTunnelLogToClipboard()
    {
        try
        {
            var text = string.Join(Environment.NewLine, TunnelLog);
            if (!string.IsNullOrEmpty(text))
                Clipboard.SetText(text);
        }
        catch (Exception ex)
        {
            FileLogger.Error("clipboard.copy", ex);
        }
    }

    private void OnTunnelLogLine(object? _, TunnelLogEventArgs e)
    {
        var app = Application.Current;
        if (app?.Dispatcher is not null)
        {
            app.Dispatcher.BeginInvoke(new Action(() =>
            {
                TunnelLog.Add($"[{DateTime.Now:HH:mm:ss}] [{e.Source}] {e.Line}");
                while (TunnelLog.Count > 800) TunnelLog.RemoveAt(0);
            }));
        }
    }

    private void EmitTunnelLog(string source, string line)
    {
        var app = Application.Current;
        if (app?.Dispatcher is null)
        {
            TunnelLog.Add($"[{DateTime.Now:HH:mm:ss}] [{source}] {line}");
            return;
        }
        app.Dispatcher.BeginInvoke(new Action(() =>
        {
            TunnelLog.Add($"[{DateTime.Now:HH:mm:ss}] [{source}] {line}");
            while (TunnelLog.Count > 800) TunnelLog.RemoveAt(0);
        }));
    }

    private void LoadFromStore()
    {
        _persistSuspended = true;
        try
        {
            var s = _store.Load();
            _subscriptionFilterEncoded = string.IsNullOrWhiteSpace(s.SubscriptionFilterEncoded)
                ? "@all"
                : s.SubscriptionFilterEncoded;
            OnPropertyChanged(nameof(SubscriptionFilterEncoded));
            OnPropertyChanged(nameof(ActiveSubscriptionFilter));

            Subscriptions.Clear();
            foreach (var e in s.Subscriptions)
                Subscriptions.Add(e);

            Keys.Clear();
            foreach (var k in s.Keys)
                Keys.Add(k);
            VpnKey? pick = null;
            if (!string.IsNullOrEmpty(s.ActiveKeyId))
                pick = Keys.FirstOrDefault(x => x.Id == s.ActiveKeyId);
            _selectedKey = pick ?? Keys.FirstOrDefault();
            OnPropertyChanged(nameof(SelectedKey));
        }
        finally
        {
            _persistSuspended = false;
        }
    }

    private void OnKeysChanged(object? _, NotifyCollectionChangedEventArgs e)
    {
        if (SelectedKey is { } sk && !Keys.Any(x => x.Id == sk.Id))
        {
            _persistSuspended = true;
            SelectedKey = Keys.FirstOrDefault();
            _persistSuspended = false;
        }
        OnPropertyChanged(nameof(KeysCountDisplay));
        (PingAllKeysCommand as RelayCommand)?.RaiseCanExecuteChanged();
        Persist();
    }
    private void OnSubscriptionsChanged(object? _, NotifyCollectionChangedEventArgs __) => Persist();

    private void Persist()
    {
        if (_persistSuspended) return;
        _store.Save(new UserState(Subscriptions.ToList(), SubscriptionFilterEncoded, Keys.ToList(), SelectedKey?.Id));
    }

    private async void Connect()
    {
        try
        {
            PowerBusy = true;
            await _tunnel.ConnectAsync(SelectedKey ?? Keys.FirstOrDefault());
        }
        finally
        {
            PowerBusy = false;
        }
    }

    private async void Disconnect()
    {
        try
        {
            PowerBusy = true;
            await _tunnel.DisconnectAsync();
        }
        finally
        {
            PowerBusy = false;
        }
    }

    private void ImportFromClipboard()
    {
        try
        {
            var text = Clipboard.GetText();
            if (string.IsNullOrWhiteSpace(text))
            {
                MessageBox.Show("Буфер обмена пуст или не содержит текста.", "Импорт", MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }
            ManualKeyText = text;
            AddManualKey();
        }
        catch (Exception ex)
        {
            FileLogger.Error("clipboard", ex);
            MessageBox.Show("Не удалось прочитать буфер обмена: " + ex.Message, "Импорт", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void AddManualKey()
    {
        var raw = ManualKeyText?.Trim() ?? "";
        if (string.IsNullOrEmpty(raw)) return;
        try
        {
            // Может содержать сразу пачку — разделим по новым строкам
            var added = 0;
            foreach (var line in raw.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
            {
                var key = ShareLinkParser.TryBuildVpnKey(line, subscriptionId: null, KeySource.ManualUri);
                if (key is not null)
                {
                    Keys.Add(key);
                    added++;
                }
            }
            if (added == 0)
            {
                MessageBox.Show(
                    "Не удалось распознать ни одной ссылки. Поддерживаются: vless://, vmess://, trojan://, ss://, hysteria2://, tuic://, JSON конфиг.",
                    "Импорт",
                    MessageBoxButton.OK,
                    MessageBoxImage.Warning);
                return;
            }
            ManualKeyText = "";
            SelectedKey ??= Keys.LastOrDefault();
        }
        catch (Exception ex)
        {
            FileLogger.Error("addKey", ex);
            MessageBox.Show("Импорт не удался: " + ex.Message, "Импорт", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void RemoveSelectedKey()
    {
        if (SelectedKey is null) return;
        var idx = Keys.IndexOf(SelectedKey);
        Keys.Remove(SelectedKey);
        if (Keys.Count > 0)
            SelectedKey = Keys[Math.Min(idx, Keys.Count - 1)];
        else
            SelectedKey = null;
    }

    private async Task CheckHandshakeAsync(VpnKey? key)
    {
        if (key is null) return;
        if (string.IsNullOrEmpty(key.Host) || key.Port is null or <= 0)
        {
            EmitTunnelLog("handshake", $"Ключ '{key.Remark}': нет host/port в конфиге.");
            return;
        }
        PingBusy = true;
        try
        {
            var (ok, ms, detail) = await Services.HandshakeProbe.ProbeAsync(key, TimeSpan.FromSeconds(8));
            var status = ok ? "✓ OK" : "✗ FAIL";
            EmitTunnelLog("handshake", $"{status} {ms} мс · {key.Remark}: {detail}");
            // Покажем результат пользователю кратко в MessageBox.
            System.Windows.MessageBox.Show(
                $"{status}  ({ms} мс)\n\n{detail}",
                $"Рукопожатие · {key.Remark}",
                System.Windows.MessageBoxButton.OK,
                ok ? System.Windows.MessageBoxImage.Information : System.Windows.MessageBoxImage.Warning);
        }
        finally
        {
            PingBusy = false;
        }
    }

    private async Task AddSubscriptionAsync()
    {
        var url = (NewSubscriptionUrl ?? "").Trim();
        if (string.IsNullOrEmpty(url)) return;
        var label = string.IsNullOrWhiteSpace(NewSubscriptionLabel)
            ? new Uri(url, UriKind.Absolute).Host
            : NewSubscriptionLabel.Trim();
        var entry = new SubscriptionEntry(
            Id: Guid.NewGuid().ToString("N"),
            Url: url,
            Label: label,
            AddedAt: DateTimeOffset.UtcNow.ToUnixTimeSeconds());
        Subscriptions.Add(entry);
        NewSubscriptionUrl = "";
        NewSubscriptionLabel = "";
        await RefreshSubscriptionAsync(entry).ConfigureAwait(false);
    }

    private void RemoveSelectedSubscription()
    {
        if (SelectedSubscription is null) return;
        var subId = SelectedSubscription.Id;
        // remove all keys from this subscription
        var toRemove = Keys.Where(k => k.SubscriptionId == subId).ToList();
        foreach (var k in toRemove) Keys.Remove(k);
        Subscriptions.Remove(SelectedSubscription);
        SelectedSubscription = null;
    }

    private async Task RefreshSubscriptionAsync(SubscriptionEntry? entry)
    {
        if (entry is null) return;
        try
        {
            FileLogger.Log("subscription", $"refreshing {entry.Url}");
            var fetched = await SubscriptionFetcher.FetchAsync(SharedHttp, entry.Url, entry.Id).ConfigureAwait(false);
            // replace keys belonging to this subscription
            await Application.Current.Dispatcher.InvokeAsync(() =>
            {
                var staleIds = Keys.Where(k => k.SubscriptionId == entry.Id).Select(k => k.Id).ToHashSet();
                for (var i = Keys.Count - 1; i >= 0; i--)
                    if (staleIds.Contains(Keys[i].Id)) Keys.RemoveAt(i);
                foreach (var k in fetched) Keys.Add(k);
                ReplaceSubscription(entry, status: $"OK · {fetched.Count} ключей");
            });
        }
        catch (Exception ex)
        {
            FileLogger.Error("subscription", ex);
            await Application.Current.Dispatcher.InvokeAsync(() =>
            {
                ReplaceSubscription(entry, status: "Ошибка: " + ex.Message);
                MessageBox.Show("Не удалось обновить подписку: " + ex.Message, "Подписка", MessageBoxButton.OK, MessageBoxImage.Warning);
            });
        }
    }

    private async Task RefreshAllSubscriptionsAsync()
    {
        foreach (var s in Subscriptions.ToList())
            await RefreshSubscriptionAsync(s).ConfigureAwait(false);
    }

    private void ReplaceSubscription(SubscriptionEntry old, string status)
    {
        var idx = Subscriptions.IndexOf(old);
        if (idx < 0) return;
        var updated = old with
        {
            LastRefreshedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
            LastStatus = status
        };
        Subscriptions[idx] = updated;
        if (ReferenceEquals(SelectedSubscription, old))
            SelectedSubscription = updated;
    }

    private async Task BootstrapToolsAsync()
    {
        if (BootstrapBusy) return;
        BootstrapBusy = true;
        BootstrapStatus = "Запуск…";
        try
        {
            var bootstrapper = new ToolBootstrapper();
            var progress = new Progress<BootstrapStatus>(p =>
            {
                BootstrapStatus = $"[{p.Stage}] {p.Detail}";
                FileLogger.Log("bootstrap", $"{p.Stage}: {p.Detail}");
            });
            await bootstrapper.EnsureToolsAsync(progress).ConfigureAwait(false);
            BootstrapStatus = "Готово.";
        }
        catch (Exception ex)
        {
            FileLogger.Error("bootstrap", ex);
            BootstrapStatus = "Ошибка: " + ex.Message;
            MessageBox.Show(
                "Не удалось скачать бинарники. Проверьте интернет / прокси / SSL.\n\n" + ex.Message,
                "Подготовка бинарников",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
        }
        finally
        {
            BootstrapBusy = false;
        }
    }

    private void OpenDataFolder()
    {
        try
        {
            Directory.CreateDirectory(AppDataPaths.XravRoot);
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = AppDataPaths.XravRoot,
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            FileLogger.Error("openDir", ex);
        }
    }

    private void OpenLogFile()
    {
        try
        {
            var f = FileLogger.GetLogFile();
            if (!File.Exists(f)) return;
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = f,
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            FileLogger.Error("openLog", ex);
        }
    }

    private void RaiseAllCanExecute()
    {
        (ConnectCommand as RelayCommand)?.RaiseCanExecuteChanged();
        (DisconnectCommand as RelayCommand)?.RaiseCanExecuteChanged();
    }
}
