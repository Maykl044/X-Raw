using System;
using System.IO;
using System.Windows;

namespace Xrav.Desktop.Ui;

public partial class JsonViewerWindow : Window
{
    private readonly string _remark;
    private readonly string _kind;

    public JsonViewerWindow(string remark, string kind, string json)
    {
        InitializeComponent();
        _remark = remark;
        _kind = kind;
        TitleText.Text = remark;
        KindText.Text = kind switch
        {
            "xray" => "xray-core config (config.json)",
            "sing-box" => "sing-box config (config.json)",
            "json" => "raw JSON config",
            _ => kind
        };
        JsonBox.Text = json;
    }

    private void OnCopyClick(object sender, RoutedEventArgs e)
    {
        try { Clipboard.SetText(JsonBox.Text); }
        catch { /* clipboard race */ }
    }

    private void OnSaveClick(object sender, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.SaveFileDialog
        {
            FileName = SafeFileName(_remark) + ".config.json",
            Filter = "JSON-конфиг (*.json)|*.json|Все файлы|*.*",
            DefaultExt = ".json"
        };
        if (dlg.ShowDialog(this) == true)
        {
            try
            {
                File.WriteAllText(dlg.FileName, JsonBox.Text);
            }
            catch (Exception ex)
            {
                MessageBox.Show("Ошибка сохранения: " + ex.Message, "X-Rav",
                    MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }
    }

    private void OnCloseClick(object sender, RoutedEventArgs e) => Close();

    private static string SafeFileName(string s)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var arr = s.ToCharArray();
        for (int i = 0; i < arr.Length; i++)
            if (Array.IndexOf(invalid, arr[i]) >= 0) arr[i] = '_';
        var name = new string(arr).Trim();
        return string.IsNullOrEmpty(name) ? "xray" : name;
    }
}
