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
using Xrav.Desktop.Logging;
using Xrav.Desktop.Services;
using Xrav.Desktop.Storage;
using Xrav.Desktop.Tools;

namespace Xrav.Desktop.ViewModels;

public enum MainTab
{
    Servers,
    Subscription,
    Settings
}

public sealed class MainViewModel : ViewModelBase
{
    private readonly ITunnelService _tunnel;
    private readonly IUserStateStore _store;
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

    public MainViewModel(ITunnelService tunnel, IUserStateStore store)
    {
        _tunnel = tunnel;
        _store = store;
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
        GoSettingsCommand = new RelayCommand(() => SelectedTab = MainTab.Settings);
        ClearTunnelLogCommand = new RelayCommand(() => TunnelLog.Clear());
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
        if (tunnel is INotifyPropertyChanged npc)
        {
            npc.PropertyChanged += (_, a) =>
            {
                if (a.PropertyName is nameof(ITunnelService.State) or nameof(ITunnelService.LastError))
                {
                    OnPropertyChanged(nameof(TunnelState));
                    OnPropertyChanged(nameof(TunnelStateDisplay));
                    OnPropertyChanged(nameof(TunnelError));
                    OnPropertyChanged(nameof(CanConnect));
                    RaiseAllCanExecute();
                }
            };
        }

        LoadFromStore();
        Keys.CollectionChanged += OnKeysChanged;
        Subscriptions.CollectionChanged += OnSubscriptionsChanged;
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
            OnPropertyChanged(nameof(IsSettingsTab));
        }
    }

    public bool IsServersTab => SelectedTab == MainTab.Servers;
    public bool IsSubscriptionTab => SelectedTab == MainTab.Subscription;
    public bool IsSettingsTab => SelectedTab == MainTab.Settings;

    public TunnelConnectionState TunnelState => _tunnel.State;
    public string? TunnelError => _tunnel.LastError;
    public string TunnelStateDisplay => TunnelState switch
    {
        TunnelConnectionState.Disconnected => "Отключено",
        TunnelConnectionState.Connecting => "Подключение…",
        TunnelConnectionState.Connected => "Подключено",
        TunnelConnectionState.Reconnecting => "Переподключение…",
        TunnelConnectionState.Error => "Ошибка",
        _ => "—"
    };
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
    public ICommand GoSettingsCommand { get; }
    public ICommand ClearTunnelLogCommand { get; }
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

    public ITunnelService Tunnel => _tunnel;

    public void Teardown()
    {
        if (_tunnel is WinTunnelService w)
            w.LogLine -= OnTunnelLogLine;
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
