using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.ComponentModel;
using System.Windows;
using System.Windows.Input;
using Xrav.Core.Domain;
using Xrav.Core.State;
using Xrav.Desktop.Services;
using Xrav.Desktop.Storage;

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
    private MainTab _selectedTab = MainTab.Servers;
    private bool _powerBusy;
    private string _subscriptionFilterEncoded = "@all";
    private bool _persistSuspended;
    private VpnKey? _selectedKey;

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

    /// <summary>Ключ для подключения (сохраняется как <c>activeKeyId</c> в user.json).</summary>
    public VpnKey? SelectedKey
    {
        get => _selectedKey;
        set
        {
            if (ReferenceEquals(_selectedKey, value)) return;
            _selectedKey = value;
            OnPropertyChanged();
            if (!_persistSuspended) Persist();
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
        set => Set(ref _powerBusy, value);
    }

    public ICommand ConnectCommand { get; }
    public ICommand DisconnectCommand { get; }
    public ICommand GoServersCommand { get; }
    public ICommand GoSubscriptionCommand { get; }
    public ICommand GoSettingsCommand { get; }
    public ICommand ClearTunnelLogCommand { get; }

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
}
