using System.Threading;
using System.Windows;
using System.Windows.Threading;
using Microsoft.Win32;
using Remindly.App.Services;
using Remindly.App.ViewModels;
using Remindly.App.Views;
using Remindly.Core.Models;

namespace Remindly.App;

public partial class App : Application
{
    public static AppServices Services { get; private set; } = null!;
    public static MainViewModel MainVm { get; private set; } = null!;
    public bool StartMinimized { get; init; }
    private EventWaitHandle? _showEvent;
    private readonly List<AlertPopupWindow> _popups = new();
    private bool _quitting;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        DispatcherUnhandledException += OnDispatcherUnhandledException;
        TaskScheduler.UnobservedTaskException += (_, a) => { Log.Error("Unobserved task exception", a.Exception); a.SetObserved(); };
        AppDomain.CurrentDomain.UnhandledException += (_, a) => Log.Error("Unhandled exception", a.ExceptionObject as Exception);

        Services = new AppServices(Dispatcher);
        Log.Info($"Remindly for Windows {AppPaths.Version} starting. OS={Environment.OSVersion}, .NET={Environment.Version}, uid={(Services.Auth.Uid is { } u ? u[..Math.Min(6, u.Length)] + "…" : "signed out")}");
        ApplyTheme();
        Services.Repo.Changed += c => { if (c == "settings" || c == "prefs") ApplyTheme(); Services.Tray?.Refresh(); };
        Services.Sync.StateChanged += () => Dispatcher.BeginInvoke(() => Services.Tray?.Refresh());
        SystemEvents.UserPreferenceChanged += (_, _) => Dispatcher.BeginInvoke(ApplyTheme);

        MainVm = new MainViewModel(Services);
        var main = new MainWindow { DataContext = MainVm };
        MainWindow = main;

        Services.Tray = new TrayService(Services);
        Services.Tray.OpenRequested += ShowMain;
        Services.Tray.QuitRequested += Quit;
        Services.Alerts.Fired += OnAlertFired;
        Services.Alerts.MissedWhileClosed += OnMissedWhileClosed;
        Services.Repo.Prefs.StartWithWindows = StartupRegistration.IsEnabled();

        // Second instance → bring us forward.
        _showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, Program.ShowEventName);
        ThreadPool.RegisterWaitForSingleObject(_showEvent, (_, _) => Dispatcher.BeginInvoke(ShowMain), null, -1, false);

        if (!StartMinimized || !Services.Repo.Prefs.CloseToTray) main.Show();
        else Log.Info("Started minimized to the tray.");
    }

    public void ShowMain()
    {
        var w = MainWindow;
        if (w == null) return;
        if (!w.IsVisible) w.Show();
        if (w.WindowState == WindowState.Minimized) w.WindowState = WindowState.Normal;
        w.Activate(); w.Topmost = true; w.Topmost = false; w.Focus();
    }

    public void Quit()
    {
        _quitting = true;
        try { Services.Dispose(); } catch { }
        Log.Info("Quit.");
        Shutdown();
    }

    public bool IsQuitting => _quitting;

    // ── theme ──
    public static void ApplyTheme()
    {
        var prefs = Services.Repo.Prefs;
        var mode = prefs.ThemeFollowsPhone ? Services.Repo.Settings.Theme : prefs.LocalTheme;
        var dark = mode == "DARK" || (mode != "LIGHT" && SystemIsDark());
        var name = dark ? "Dark" : "Light";
        var dict = new ResourceDictionary { Source = new Uri($"pack://application:,,,/Themes/{name}.xaml", UriKind.Absolute) };
        var merged = Current.Resources.MergedDictionaries;
        if (merged.Count > 0) merged[0] = dict; else merged.Add(dict);
    }

    private static bool SystemIsDark()
    {
        try { using var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"); return k?.GetValue("AppsUseLightTheme") is int v && v == 0; }
        catch { return false; }
    }

    // ── alerts ──
    private void OnAlertFired(AlertEvent ev)
    {
        try
        {
            if (ev.Types.Contains('A')) { new AlarmWindow(ev).Show(); return; }
            var popup = new AlertPopupWindow(ev);
            popup.Closed += (_, _) => { _popups.Remove(popup); Restack(); };
            _popups.Add(popup);
            popup.Show();
            Restack();
            if (ev.Types.Contains('R')) Services.Alerts.Sounds.PlayLoop("R");
            else if (Services.Repo.Prefs.SoundOnNotify) Services.Alerts.Sounds.PlayOnce("notify.wav");
        }
        catch (Exception ex) { Log.Error("show alert", ex); }
    }

    private void OnMissedWhileClosed(List<AlertEvent> list)
    {
        var text = list.Count == 1 ? $"1 reminder came due while Remindly was closed: {list[0].Title}" : $"{list.Count} reminders came due while Remindly was closed.";
        var ev = new AlertEvent(AlertSource.Summary, 0, Clock.Now(), "N", "While you were away", text, null, "summary");
        var popup = new AlertPopupWindow(ev, list);
        popup.Closed += (_, _) => { _popups.Remove(popup); Restack(); };
        _popups.Add(popup); popup.Show(); Restack();
    }

    private void Restack()
    {
        var area = SystemParameters.WorkArea;
        double bottom = area.Bottom - 12;
        foreach (var p in _popups.AsEnumerable().Reverse())
        {
            p.Left = area.Right - p.Width - 12;
            p.Top = bottom - p.ActualHeight;
            bottom = p.Top - 8;
        }
    }

    /// <summary>The checkout calculator must run before a Buy item completes (Engine.startComplete on the phone).</summary>
    public static void RequestCheckout(Item item)
    {
        Current.Dispatcher.BeginInvoke(() =>
        {
            var dlg = new CheckoutDialog(item) { Owner = Current.MainWindow?.IsVisible == true ? Current.MainWindow : null };
            dlg.ShowDialog();
        });
    }

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        Log.Error("Dispatcher exception", e.Exception);
        try
        {
            var root = e.Exception.GetBaseException();
            var detail = e.Exception.Message + (ReferenceEquals(root, e.Exception) ? "" : "\n\nRoot cause: " + root.GetType().Name + ": " + root.Message);
            MessageBox.Show(MainWindow, "Something went wrong:\n\n" + detail + "\n\nDetails were written to the log file:\n" + AppPaths.LogFile,
                AppPaths.ProductName, MessageBoxButton.OK, MessageBoxImage.Error);
        }
        catch { }
        e.Handled = true;
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try { Services?.Repo.SavePrefs(); } catch { }
        base.OnExit(e);
    }
}
