using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Controls;
using Xrav.Desktop.Storage;
using Xrav.Desktop.ViewModels;

namespace Xrav.Desktop.Ui;

public partial class SettingsView : UserControl, INotifyPropertyChanged
{
    private string _page = "menu";
    private bool _suppressBypassEvents;

    public SettingsView()
    {
        InitializeComponent();
        DataContextChanged += (_, _) => { /* re-evaluate triggers */ };
        Loaded += (_, _) => LoadBypassPrefs();
    }

    /// <summary>
    /// "menu" — главное меню; иначе ID секции: appearance/mode/bypass/url/tools/folders/faq/about.
    /// </summary>
    public string Page
    {
        get => _page;
        set { if (_page == value) return; _page = value; OnPropertyChanged(); }
    }

    private void OnManualChecked(object sender, RoutedEventArgs e)
    {
        if (DataContext is MainViewModel vm && vm.AutoSelectMode)
            vm.AutoSelectMode = false;
    }

    private void OnMenuClick(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.Tag is string tag)
            Page = tag;
    }

    private void OnBackClick(object sender, RoutedEventArgs e) => Page = "menu";

    // ===== Обход блокировок =====
    private void LoadBypassPrefs()
    {
        var p = AppPrefs.Load();
        _suppressBypassEvents = true;
        try
        {
            ChkFragment.IsChecked = p.BypassFragmentEnabled;
            ChkMux.IsChecked = p.BypassMuxEnabled;
            ChkNoise.IsChecked = p.BypassNoiseEnabled;
            TxtFragLen.Text = p.BypassFragmentLength;
            TxtFragInt.Text = p.BypassFragmentInterval;
        }
        finally { _suppressBypassEvents = false; }
    }

    private void OnBypassChanged(object sender, RoutedEventArgs e)
    {
        if (_suppressBypassEvents) return;
        SaveBypassPrefs();
    }

    private void OnBypassChanged(object sender, TextChangedEventArgs e)
    {
        if (_suppressBypassEvents) return;
        SaveBypassPrefs();
    }

    private void SaveBypassPrefs()
    {
        var p = AppPrefs.Load();
        p.BypassFragmentEnabled = ChkFragment.IsChecked == true;
        p.BypassMuxEnabled = ChkMux.IsChecked == true;
        p.BypassNoiseEnabled = ChkNoise.IsChecked == true;
        if (!string.IsNullOrWhiteSpace(TxtFragLen.Text)) p.BypassFragmentLength = TxtFragLen.Text.Trim();
        if (!string.IsNullOrWhiteSpace(TxtFragInt.Text)) p.BypassFragmentInterval = TxtFragInt.Text.Trim();
        p.Save();
    }

    private void OnBypassResetClick(object sender, RoutedEventArgs e)
    {
        var defaults = new AppPrefs();
        _suppressBypassEvents = true;
        try
        {
            ChkFragment.IsChecked = defaults.BypassFragmentEnabled;
            ChkMux.IsChecked = defaults.BypassMuxEnabled;
            ChkNoise.IsChecked = defaults.BypassNoiseEnabled;
            TxtFragLen.Text = defaults.BypassFragmentLength;
            TxtFragInt.Text = defaults.BypassFragmentInterval;
        }
        finally { _suppressBypassEvents = false; }
        SaveBypassPrefs();
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([CallerMemberName] string? p = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(p));
}
