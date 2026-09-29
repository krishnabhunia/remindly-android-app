using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using Remindly.App.Services;

namespace Remindly.App;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        var p = App.Services.Repo.Prefs;
        if (p.WindowWidth >= MinWidth && p.WindowHeight >= MinHeight) { Width = p.WindowWidth; Height = p.WindowHeight; }
        if (p.WindowLeft is double l && p.WindowTop is double t && l > -10000 && t > -10000) { WindowStartupLocation = WindowStartupLocation.Manual; Left = l; Top = t; }
        if (p.WindowMaximized) WindowState = WindowState.Maximized;
        StateChanged += (_, _) => UpdateMaxRestoreGlyph();
        SourceInitialized += (_, _) => TryRoundCorners();
        Closing += OnClosing;
        UpdateMaxRestoreGlyph();
    }

    private void OnClosing(object? sender, CancelEventArgs e)
    {
        var app = (App)Application.Current;
        var p = App.Services.Repo.Prefs;
        p.WindowMaximized = WindowState == WindowState.Maximized;
        if (WindowState == WindowState.Normal) { p.WindowWidth = Width; p.WindowHeight = Height; p.WindowLeft = Left; p.WindowTop = Top; }
        App.Services.Repo.SavePrefs();
        if (!app.IsQuitting && p.CloseToTray)
        {
            // Close = hide to the tray so alerts keep firing (the phone's "app in background").
            e.Cancel = true;
            Hide();
            App.Services.Tray?.Balloon("Remindly keeps running", "Alerts still fire from the tray. Right-click the icon to quit.");
            return;
        }
        if (!app.IsQuitting) app.Quit();
    }

    private void UpdateMaxRestoreGlyph()
    {
        MaxRestoreButton.Content = WindowState == WindowState.Maximized ? "" : "";
        MaxRestoreButton.ToolTip = WindowState == WindowState.Maximized ? "Restore" : "Maximize";
    }

    private void OnMinimize(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    private void OnMaximizeRestore(object sender, RoutedEventArgs e) => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
    private void OnClose(object sender, RoutedEventArgs e) => Close();

    [DllImport("dwmapi.dll", PreserveSig = true)]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);

    private void TryRoundCorners()
    {
        try { var hwnd = new WindowInteropHelper(this).Handle; int pref = 2; DwmSetWindowAttribute(hwnd, 33, ref pref, sizeof(int)); }
        catch (Exception ex) { Log.Warn("Rounded corners unavailable: " + ex.Message); }
    }
}
