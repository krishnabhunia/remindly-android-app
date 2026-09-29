using System.Windows;
using Microsoft.Win32;
using Remindly.App.Services;
using Remindly.Core.Sync;

namespace Remindly.App.Views;

public partial class SignInDialog : Window
{
    public SignInDialog()
    {
        InitializeComponent();
        var c = App.Services.Auth.Client;
        if (c != null) { ClientId.Text = c.ClientId; Status.Text = "A client is already saved on this PC. Saving replaces it."; }
    }

    private void OnOpenConsole(object sender, RoutedEventArgs e) => Dialogs.OpenUrl("https://console.cloud.google.com/apis/credentials?project=remindly-5c1e6");

    private void OnBrowse(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog { Filter = "Google client JSON (*.json)|*.json" };
        if (dlg.ShowDialog(this) == true) LoadJson(dlg.FileName);
    }

    private void OnDragOver(object sender, DragEventArgs e) { e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None; e.Handled = true; }
    private void OnDrop(object sender, DragEventArgs e) { if (e.Data.GetData(DataFormats.FileDrop) is string[] f && f.Length > 0) LoadJson(f[0]); }

    private void LoadJson(string path)
    {
        try
        {
            var c = OAuthClient.ParseJson(File.ReadAllText(path));
            if (c == null) { Status.Text = "That file does not look like a Google OAuth client JSON."; return; }
            JsonPath.Text = path; ClientId.Text = c.ClientId; ClientSecret.Password = c.ClientSecret;
            Status.Text = "Client read from the file. Click Save client.";
        }
        catch (Exception ex) { Status.Text = "Could not read the file: " + ex.Message; }
    }

    private void OnSave(object sender, RoutedEventArgs e)
    {
        var id = ClientId.Text.Trim(); var secret = ClientSecret.Password.Trim();
        if (!id.EndsWith(".apps.googleusercontent.com", StringComparison.OrdinalIgnoreCase)) { Status.Text = "The Client ID should end with .apps.googleusercontent.com"; return; }
        if (secret.Length == 0 && App.Services.Auth.Client?.ClientId != id) { Status.Text = "Paste the Client secret too (Desktop clients have one)."; return; }
        if (secret.Length == 0) secret = App.Services.Auth.Client!.ClientSecret;
        App.Services.Auth.SaveClient(new OAuthClient(id, secret));
        Log.Info("OAuth Desktop client saved");
        DialogResult = true; Close();
    }

    private void OnClear(object sender, RoutedEventArgs e) { App.Services.Auth.ClearClient(); ClientId.Text = ""; ClientSecret.Password = ""; Status.Text = "Removed."; }
    private void OnCancel(object sender, RoutedEventArgs e) { DialogResult = false; Close(); }
}
