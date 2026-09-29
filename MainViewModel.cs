using System.Collections.ObjectModel;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Remindly.App.Services;
using Remindly.App.Views;
using Remindly.Core.Logic;
using Remindly.Core.Models;
using Remindly.Core.Sync;

namespace Remindly.App.ViewModels;

public sealed partial class NavItemViewModel : ObservableObject
{
    public string Key { get; }
    public string Title { get; }
    public string Glyph { get; }
    [ObservableProperty] private bool _isSelected;
    [ObservableProperty] private string? _badge;
    private readonly Action<NavItemViewModel> _onSelect;
    public NavItemViewModel(string key, string title, string glyph, Action<NavItemViewModel> onSelect) { Key = key; Title = title; Glyph = glyph; _onSelect = onSelect; }
    partial void OnIsSelectedChanged(bool value) { if (value) _onSelect(this); }
}

/// <summary>Shell: ☰ mode (Task / Shop), the bottom-nav tabs as a sidebar, sync status, the right-hand editor panel.</summary>
public sealed partial class MainViewModel : ObservableObject
{
    private readonly AppServices _s;
    private readonly Dictionary<string, object> _pages = new();
    private readonly DispatcherTimer _refresh;

    [ObservableProperty] private string _mode = "TASK";
    [ObservableProperty] private object? _currentPage;
    [ObservableProperty] private string _currentTitle = "";
    [ObservableProperty] private object? _editor;
    [ObservableProperty] private string _syncStatus = "";
    [ObservableProperty] private string _syncDetail = "";
    [ObservableProperty] private string _accountLine = "";
    [ObservableProperty] private bool _isSignedIn;
    [ObservableProperty] private bool _personalUnlocked;
    [ObservableProperty] private string? _ackText;
    [ObservableProperty] private bool _alertsPaused;
    public ObservableCollection<NavItemViewModel> NavItems { get; } = new();
    public string WindowTitle => "Remindly for Windows";
    public string VersionText => $"Remindly {AppPaths.Version} · Windows";
    public bool IsTaskMode => Mode == "TASK";
    public bool IsShopMode => Mode == "SHOP";
    private Action? _ackUndo;
    private DispatcherTimer? _ackTimer;

    public MainViewModel(AppServices s)
    {
        _s = s;
        Mode = s.Repo.Prefs.LastMode == "SHOP" ? "SHOP" : "TASK";
        BuildNav();
        s.Sync.StateChanged += () => _s.Dispatcher.BeginInvoke(RefreshStatus);
        s.Auth.Changed += () => _s.Dispatcher.BeginInvoke(RefreshStatus);
        s.Repo.Changed += c => { if (c != "prefs") RefreshBadges(); RefreshStatus(); };
        RefreshStatus();
        _refresh = new DispatcherTimer(DispatcherPriority.Background, s.Dispatcher) { Interval = TimeSpan.FromSeconds(30) };
        _refresh.Tick += (_, _) => { RefreshBadges(); RefreshStatus(); };
        _refresh.Start();
    }

    partial void OnModeChanged(string value)
    {
        OnPropertyChanged(nameof(IsTaskMode)); OnPropertyChanged(nameof(IsShopMode));
        _s.Repo.Prefs.LastMode = value; _s.Repo.SavePrefs();
        Editor = null;
        BuildNav();
    }

    private void BuildNav()
    {
        NavItems.Clear();
        var s = _s.Repo.Settings;
        if (Mode == "TASK")
        {
            if (s.ShowTasks) NavItems.Add(new NavItemViewModel("tasks", "Tasks", "", Select));
            if (s.ShowLearn) NavItems.Add(new NavItemViewModel("learn", "Learn", "", Select));
            if (s.ShowCalls) NavItems.Add(new NavItemViewModel("calls", "Calls", "", Select));
        }
        else
        {
            NavItems.Add(new NavItemViewModel("buy", "Buy", "", Select));
            NavItems.Add(new NavItemViewModel("shops", "Shops", "", Select));
            NavItems.Add(new NavItemViewModel("products", "Products", "", Select));
        }
        NavItems.Add(new NavItemViewModel("scheduled", "Scheduled alerts", "", Select));
        NavItems.Add(new NavItemViewModel("bin", "Recently deleted", "", Select));
        NavItems.Add(new NavItemViewModel("settings", "Settings", "", Select));
        RefreshBadges();
        var lastIdx = Mode == "TASK" ? _s.Repo.Prefs.LastTaskTab : _s.Repo.Prefs.LastShopTab;
        var pick = NavItems.ElementAtOrDefault(Math.Clamp(lastIdx, 0, Math.Max(0, NavItems.Count - 4))) ?? NavItems[0];
        pick.IsSelected = true;
    }

    private void Select(NavItemViewModel nav)
    {
        foreach (var n in NavItems) if (n != nav) n.IsSelected = false;
        var idx = NavItems.IndexOf(nav);
        if (idx >= 0 && idx < NavItems.Count - 3) { if (Mode == "TASK") _s.Repo.Prefs.LastTaskTab = idx; else _s.Repo.Prefs.LastShopTab = idx; }
        CurrentTitle = nav.Title;
        Editor = null;
        CurrentPage = PageFor(nav.Key);
    }

    private object PageFor(string key)
    {
        if (_pages.TryGetValue(key, out var p)) return p;
        object page = key switch
        {
            "tasks" => new ItemListViewModel(_s, this, Tab.TASKS),
            "learn" => new ItemListViewModel(_s, this, Tab.LEARN),
            "buy" => new ItemListViewModel(_s, this, Tab.SHOP),
            "calls" => new CallsViewModel(_s, this),
            "shops" => new ShopsViewModel(_s, this),
            "products" => new ProductsViewModel(_s, this),
            "scheduled" => new ScheduledViewModel(_s, this),
            "bin" => new BinViewModel(_s, this),
            _ => new SettingsViewModel(_s, this),
        };
        _pages[key] = page;
        return page;
    }

    public void NavigateTo(string key)
    {
        if (key is "buy" or "shops" or "products" && Mode != "SHOP") Mode = "SHOP";
        if (key is "tasks" or "learn" or "calls" && Mode != "TASK") Mode = "TASK";
        var nav = NavItems.FirstOrDefault(n => n.Key == key);
        if (nav != null) nav.IsSelected = true;
    }

    [RelayCommand] private void SwitchMode(string mode) => Mode = mode == "SHOP" ? "SHOP" : "TASK";

    [RelayCommand] private void CloseEditor() => Editor = null;

    [RelayCommand] private void TogglePersonal()
    {
        if (PersonalUnlocked) { PersonalUnlocked = false; return; }
        var prefs = _s.Repo.Prefs;
        if (prefs.PinHash == null)
        {
            if (!Dialogs.Confirm("Personal items are hidden behind a PIN on this PC. No PIN is set yet — set one now?", "Personal PIN")) return;
            var set = PinDialog.Ask("Choose a 4–8 digit PIN for this PC", confirm: true);
            if (string.IsNullOrEmpty(set)) return;
            (prefs.PinHash, prefs.PinSalt) = Pin.Make(set); _s.Repo.SavePrefs();
            PersonalUnlocked = true; return;
        }
        var pin = PinDialog.Ask("Enter your PIN to show personal items");
        if (pin == null) return;
        if (Pin.Verify(pin, prefs.PinHash, prefs.PinSalt)) PersonalUnlocked = true;
        else Dialogs.Error("Wrong PIN.");
    }

    [RelayCommand] private async Task SignInAsync()
    {
        if (!_s.Auth.HasClient)
        {
            var dlg = new SignInDialog { Owner = System.Windows.Application.Current.MainWindow };
            dlg.ShowDialog();
            if (!_s.Auth.HasClient) return;
        }
        var ok = await _s.Auth.SignInAsync(CancellationToken.None, st => _s.Dispatcher.BeginInvoke(() => SyncDetail = st));
        if (ok)
        {
            _s.Repo.Prefs.SignedInEmail = _s.Auth.Session?.Email; _s.Repo.Prefs.SignedInUid = _s.Auth.Uid; _s.Repo.SavePrefs();
            if (!_s.Repo.Settings.CloudSync && Dialogs.Confirm("Signed in. Turn on Cloud sync on this PC now?\n\nYour phone's lists appear here within seconds and edits flow both ways.", "Cloud sync"))
                _s.Repo.UpdateSettings(st => st.CloudSync = true);
            _s.Sync.SetEnabled(_s.Repo.Settings.CloudSync);
        }
        else if (_s.Auth.LastError != null) Dialogs.Error("Sign-in failed: " + _s.Auth.LastError);
        RefreshStatus();
    }

    [RelayCommand] private void ToggleAlertsPause()
    {
        if (_s.Alerts.Active) _s.Alerts.PauseFor(TimeSpan.FromHours(1)); else _s.Alerts.Resume();
        RefreshStatus();
    }

    public void RefreshStatus()
    {
        IsSignedIn = _s.Auth.IsSignedIn;
        AccountLine = IsSignedIn ? (_s.Auth.Session?.Email ?? "Signed in") : "Not signed in";
        SyncStatus = _s.Sync.StatusLine();
        var st = _s.Sync;
        var parts = new List<string>();
        if (st.LastError != null && st.State is SyncState.Error or SyncState.Offline) parts.Add(st.LastError);
        var denied = st.Collections.Values.Where(c => c.PermissionDenied).Select(c => c.Name).ToList();
        if (denied.Count > 0) parts.Add("Catalogue sync waiting for Android 2.9 rules (" + string.Join(", ", denied) + ")");
        SyncDetail = string.Join(" · ", parts);
        AlertsPaused = !_s.Alerts.Active;
    }

    public void RefreshBadges()
    {
        if (!_s.Repo.Settings.Badges) { foreach (var n in NavItems) n.Badge = null; return; }
        var tomorrow = Time.StartOfTomorrow(Clock.Now());
        foreach (var n in NavItems)
        {
            int c = n.Key switch
            {
                "tasks" => Alerts.PendingTodayCount(_s.Repo.Items, Tab.TASKS, tomorrow),
                "learn" => Alerts.PendingTodayCount(_s.Repo.Items, Tab.LEARN, tomorrow),
                "buy" => Alerts.PendingTodayCount(_s.Repo.Items, Tab.SHOP, tomorrow),
                "calls" => _s.Repo.Calls.Count(x => !x.Done && x.DeletedAt == null),
                "bin" => _s.Repo.Items.Count(x => x.DeletedAt != null) + _s.Repo.Calls.Count(x => x.DeletedAt != null),
                _ => 0,
            };
            n.Badge = c > 0 ? c.ToString() : null;
        }
    }

    /// <summary>The bottom "Moved to Done · Undo" acknowledgment (Ack.show on the phone).</summary>
    public void Ack(string text, Action? undo)
    {
        AckText = text; _ackUndo = undo;
        _ackTimer?.Stop();
        _ackTimer = new DispatcherTimer(DispatcherPriority.Normal, _s.Dispatcher) { Interval = TimeSpan.FromSeconds(6) };
        _ackTimer.Tick += (_, _) => { _ackTimer.Stop(); AckText = null; _ackUndo = null; };
        _ackTimer.Start();
    }

    [RelayCommand] private void AckUndo() { _ackUndo?.Invoke(); _ackUndo = null; AckText = null; _ackTimer?.Stop(); }
    [RelayCommand] private void AckDismiss() { AckText = null; _ackUndo = null; _ackTimer?.Stop(); }

    public void OpenItemEditor(Item? item, Tab tab) => Editor = new ItemEditorViewModel(_s, this, item, tab);
    public void OpenCallEditor(CallReminder? call) => Editor = new CallEditorViewModel(_s, this, call);
}
