using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using Remindly.App.Services;
using Remindly.Core;
using Remindly.Core.Updates;

namespace Remindly.App;

public partial class App : Application
{
    private readonly StartOptions _opts;
    private readonly SingleInstance? _instance;
    public static TrayService? Tray { get; private set; }
    public static ReminderService? Reminders { get; private set; }
    public static UpdateCoordinator? Updates { get; private set; }
    public static bool IsSmokeTest { get; private set; }
    public static bool Exiting { get; private set; }

    public App(StartOptions opts, SingleInstance? instance)
    {
        _opts = opts;
        _instance = instance;
        IsSmokeTest = opts.SmokeTest != null;
        DispatcherUnhandledException += OnUnhandled;
    }

    public static new App Current => (App)Application.Current;
    public MainWindow Main => (MainWindow)MainWindow;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        AppState.Load();
        ApplyMode(AppState.Current.Settings.LastMode);

        if (!IsSmokeTest)
        {
            Tray = new TrayService();
            Reminders = new ReminderService();
            Updates = new UpdateCoordinator();
        }

        var main = new MainWindow();
        MainWindow = main;

        if (IsSmokeTest)
        {
            SmokeTest.Run(main, _opts.SmokeTest!);
            return;
        }

        _instance?.Listen(
            onActivate: () => Dispatcher.BeginInvoke(() => ShowMain()),
            onExit: () => Dispatcher.BeginInvoke(() => ExitApp("asked to exit (Setup / --exit)")));

        // The registry Run entry is the truth for "Start with Windows" (Setup's task writes it too);
        // an existing entry is rewritten so it follows a portable exe that was moved.
        bool autostart = InstallInfo.StartsWithWindows();
        if (autostart) InstallInfo.SetStartWithWindows(true);
        if (autostart != AppState.Current.Settings.StartWithWindows)
            AppState.Current.UpdateSettingsQuiet(s => s with { StartWithWindows = autostart });

        if (!_opts.Tray) ShowMain();
        if (_opts.Updated)
        {
            UpdateService.CleanDownloads();
            Tray.Balloon("Remindly updated", $"You are now on version {UpdateService.CurrentVersion.ToString(3)}.");
        }

        Reminders.Start();
        Updates.Start();
    }

    public void ShowMain()
    {
        var w = Main;
        if (!w.IsVisible) w.Show();
        if (w.WindowState == WindowState.Minimized) w.WindowState = WindowState.Normal;
        w.Activate();
        w.Topmost = true;
        w.Topmost = false;
        w.Focus();
    }

    /// <summary>Saves and closes everything (tray Exit, Setup's --exit, an update about to be applied).</summary>
    public void ExitApp(string why)
    {
        if (Exiting) return;
        Exiting = true;
        Log.Info("Exit: " + why);
        try { AppState.Current?.Save(); } catch { }
        try { Reminders?.Stop(); } catch { }
        try { Updates?.Stop(); } catch { }
        try { Tray?.Dispose(); } catch { }
        foreach (Window w in Windows) { try { w.Close(); } catch { } }
        Shutdown(0);
    }

    /// <summary>Task mode (purple) or Shop mode (green) — the accent follows, like the Android app.</summary>
    public static void ApplyMode(string mode)
    {
        var shop = mode == "SHOP";
        var r = Current.Resources;
        r["AccentBrush"] = new SolidColorBrush(shop ? Color.FromRgb(0x0E, 0x9F, 0x6E) : Color.FromRgb(0x5B, 0x4F, 0xE8));
        r["AccentSoftBrush"] = new SolidColorBrush(shop ? Color.FromRgb(0xE0, 0xF5, 0xEE) : Color.FromRgb(0xED, 0xEA, 0xFD));
        r["AccentInkBrush"] = new SolidColorBrush(shop ? Color.FromRgb(0x0B, 0x6E, 0x4F) : Color.FromRgb(0x3A, 0x2F, 0xA8));
        r["HeaderBrush"] = new LinearGradientBrush(
            shop ? Color.FromRgb(0x0E, 0x9F, 0x6E) : Color.FromRgb(0x4C, 0x3F, 0xD6),
            shop ? Color.FromRgb(0x00, 0xB8, 0xA9) : Color.FromRgb(0x8E, 0x5B, 0xF0), 45);
    }

    private void OnUnhandled(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        Log.Error("Unhandled UI exception", e.Exception);
        if (IsSmokeTest)
        {
            SmokeTest.Fail(e.Exception);
            e.Handled = true;
            return;
        }
        MessageBox.Show("Something went wrong: " + e.Exception.Message + "\n\nRemindly keeps running. Details are in " + AppPaths.LogFile,
            "Remindly", MessageBoxButton.OK, MessageBoxImage.Warning);
        e.Handled = true;
    }
}
