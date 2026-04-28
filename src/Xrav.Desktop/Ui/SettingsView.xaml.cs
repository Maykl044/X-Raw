using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Controls;
using Xrav.Desktop.ViewModels;

namespace Xrav.Desktop.Ui;

public partial class SettingsView : UserControl, INotifyPropertyChanged
{
    private string _page = "menu";

    public SettingsView()
    {
        InitializeComponent();
        DataContextChanged += (_, _) => { /* re-evaluate triggers */ };
    }

    /// <summary>
    /// "menu" — главное меню; иначе ID секции: appearance/mode/url/tools/folders/faq/about.
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

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([CallerMemberName] string? p = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(p));
}
