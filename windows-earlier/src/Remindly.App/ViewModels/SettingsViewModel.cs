using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.Win32;
using Remindly.App.Services;
using Remindly.App.Views;
using Remindly.Core.Logic;
using Remindly.Core.Models;
using Remindly.Core.Sync;

namespace Remindly.App.ViewModels;

/// <summary>
/// Settings. Two kinds of switch live here, kept visually apart like on the phone:
///  • SYNCED (change here → phone follows): theme, time format, groups/topics, default due,
///    schedule default, snooze length, ring seconds, alert masters, shop-mode toggles.
///  • THIS PC ONLY (never leave this machine): cloud-sync toggle, alerts on this PC, tray/startup,
///    Personal PIN, sounds, catalogue sync opt-in.
/// </summary>
public sealed partial class SettingsViewModel : ObservableObject
{
    private readonly AppServices _s;
    private readonly MainViewModel _main;
    private bool _loading;

    // account / sync
    [ObservableProperty] private string _accountLine = "";
    [ObservableProperty] private bool _isSignedIn;
    [ObservableProperty] private bool _hasClient;
    [ObservableProperty] private bool _cloudSync;
    [ObservableProperty] private bool _catalogueSync;
    [ObservableProperty] private string _syncStatus = "";
    [ObservableProperty] private string _collectionsText = "";
    [ObservableProperty] private string _projectLine = "";
    // this PC
    [ObservableProperty] private bool _alertsOnThisPc;
    [ObservableProperty] private bool _startWithWindows;
    [ObservableProperty] private bool _closeToTray;
    [ObservableProperty] private bool _soundOnNotify;
    [ObservableProperty] private bool _showMissedSummary;
    [ObservableProperty] private bool _themeFollowsPhone;
    [ObservableProperty] private string _localTheme = "SYSTEM";
    [ObservableProperty] private string _pinStatus = "";
    // synced
    [ObservableProperty] private string _theme = "SYSTEM";
    [ObservableProperty] private string _timeFormat = "PHONE";
    [ObservableProperty] private bool _alertsEnabled;
    [ObservableProperty] private string _tasksAlertsOn = "INHERIT";
    [ObservableProperty] private string _learnAlertsOn = "INHERIT";
    [ObservableProperty] private string _shopAlertsOn = "INHERIT";
    [ObservableProperty] private string _callsAlertsOn = "INHERIT";
    [ObservableProperty] private int _snoozeMinutes = 90;
    [ObservableProperty] private int _ringSeconds = 7;
    [ObservableProperty] private int _alarmSeconds = 7;
    [ObservableProperty] private int _defaultDueMinutes = 1080;
    [ObservableProperty] private string _defaultDueText = "18:00";
    [ObservableProperty] private string _globalNewDueMode = "TOMORROW";
    [ObservableProperty] private int _globalNewDueDays = 3;
    [ObservableProperty] private string _schedDefault = "ONCE";
    [ObservableProperty] private bool _globalNewDueTimed = true;
    [ObservableProperty] private bool _showTasks = true;
    [ObservableProperty] private bool _showLearn = true;
    [ObservableProperty] private bool _showCalls = true;
    [ObservableProperty] private bool _badges = true;
    [ObservableProperty] private bool _groupHeaderCheck = true;
    [ObservableProperty] private bool _cardShowAlertType;
    [ObservableProperty] private bool _showMediumTag;
    [ObservableProperty] private bool _shopCheckoutCalc = true;
    [ObservableProperty] private bool _shopCheapestHint = true;
    [ObservableProperty] private bool _shopArriveAlert = true;
    [ObservableProperty] private string _shopArriveTypes = "N";
    [ObservableProperty] private int _shopArriveCooldownMin = 10;
    [ObservableProperty] private float _shopNewRadius = 150f;
    [ObservableProperty] private string _defaultCountryCode = "91";
    [ObservableProperty] private string _tasksGroupsText = "";
    [ObservableProperty] private string _shopGroupsText = "";
    [ObservableProperty] private string _learnTopicsText = "";
    [ObservableProperty] private string _logTail = "";
    public string DataFolder => AppPaths.Root;
    public string VersionLine => $"Remindly for Windows {AppPaths.Version} · sync schema {Constants.SyncSchema} · matches Android 2.8/2.9";
    public float[] RadiusStops => Heal.RadiusStops;
    public List<KeyValuePair<string, string>> TriOptions { get; } = new() { new("INHERIT", "Inherit"), new("ON", "On"), new("OFF", "Off") };
    public List<KeyValuePair<string, string>> ThemeOptions { get; } = new() { new("SYSTEM", "Follow Windows"), new("LIGHT", "Light"), new("DARK", "Dark") };
    public List<KeyValuePair<string, string>> TimeFormatOptions { get; } = new() { new("PHONE", "Follow this PC"), new("H12", "12-hour"), new("H24", "24-hour") };
    public List<KeyValuePair<string, string>> DueModeOptions { get; } = new() { new("OFF", "No date"), new("TODAY", "Today"), new("TOMORROW", "Tomorrow"), new("IN_N", "In N days") };
    public List<KeyValuePair<string, string>> SchedOptions { get; } = new() { new("NONE", "No reminders"), new("ONCE", "One-time"), new("REPEAT", "Repeat") };
    public List<KeyValuePair<string, string>> ArriveOptions { get; } = new() { new("N", "Notify"), new("R", "Ring"), new("A", "Alarm"), new("NR", "Ring + Notify"), new("NA", "Alarm + Notify"), new("", "Silent") };

    public SettingsViewModel(AppServices s, MainViewModel main)
    {
        _s = s; _main = main;
        Load();
        s.Repo.Changed += c => { if (c is "settings" or "prefs") Load(); };
        s.Sync.StateChanged += () => s.Dispatcher.BeginInvoke(RefreshSync);
        s.Auth.Changed += () => s.Dispatcher.BeginInvoke(Load);
    }

    private void Load()
    {
        _loading = true;
        try
        {
            var st = _s.Repo.Settings; var p = _s.Repo.Prefs;
            CloudSync = st.CloudSync; CatalogueSync = p.CatalogueSyncEnabled;
            AlertsOnThisPc = p.AlertsOnThisPc; StartWithWindows = StartupRegistration.IsEnabled(); CloseToTray = p.CloseToTray; SoundOnNotify = p.SoundOnNotify; ShowMissedSummary = p.ShowMissedSummaryOnStart;
            ThemeFollowsPhone = p.ThemeFollowsPhone; LocalTheme = p.LocalTheme;
            PinStatus = p.PinHash == null ? "No PIN set on this PC" : "PIN set on this PC";
            Theme = st.Theme; TimeFormat = st.TimeFormat;
            AlertsEnabled = st.AlertsEnabled; TasksAlertsOn = st.TasksAlertsOn; LearnAlertsOn = st.LearnAlertsOn; ShopAlertsOn = st.ShopAlertsOn; CallsAlertsOn = st.CallsAlertsOn;
            SnoozeMinutes = Alerts.SnoozeMinutes(st); RingSeconds = st.RingRingSeconds; AlarmSeconds = st.AlarmRingSeconds;
            DefaultDueMinutes = st.DefaultDueMinutes; DefaultDueText = $"{st.DefaultDueMinutes / 60 % 24:00}:{st.DefaultDueMinutes % 60:00}";
            GlobalNewDueMode = st.GlobalNewDueMode; GlobalNewDueDays = st.GlobalNewDueDays; SchedDefault = st.SchedDefault; GlobalNewDueTimed = st.GlobalNewDueTimed;
            ShowTasks = st.ShowTasks; ShowLearn = st.ShowLearn; ShowCalls = st.ShowCalls; Badges = st.Badges; GroupHeaderCheck = st.GroupHeaderCheck; CardShowAlertType = st.CardShowAlertType; ShowMediumTag = st.ShowMediumTag;
            ShopCheckoutCalc = st.ShopCheckoutCalc; ShopCheapestHint = st.ShopCheapestHint; ShopArriveAlert = st.ShopArriveAlert; ShopArriveTypes = Heal.NormalizeAlertTypes(st.ShopArriveTypes) ?? "N"; ShopArriveCooldownMin = st.ShopArriveCooldownMin; ShopNewRadius = st.ShopNewRadius;
            DefaultCountryCode = st.DefaultCountryCode;
            TasksGroupsText = string.Join(", ", st.TasksGroups); ShopGroupsText = string.Join(", ", st.ShopGroups); LearnTopicsText = string.Join(", ", st.LearnTopics);
            ProjectLine = $"Firebase project {_s.Firebase.ProjectId} · same as the phone";
            RefreshSync();
        }
        finally { _loading = false; }
    }

    private void RefreshSync()
    {
        IsSignedIn = _s.Auth.IsSignedIn; HasClient = _s.Auth.HasClient;
        AccountLine = IsSignedIn ? $"Signed in as {_s.Auth.Session?.Email}" : HasClient ? "Not signed in" : "Google Desktop client not configured yet";
        SyncStatus = _s.Sync.StatusLine() + (_s.Sync.LastError != null ? " — " + _s.Sync.LastError : "");
        CollectionsText = string.Join("\n", _s.Sync.Collections.Values.Select(c => $"{c.Name,-13} {(c.PermissionDenied ? "rules missing (Android 2.9)" : c.Current ? "live" : "—")}"));
    }

    private void Save(Action<AppSettings> edit) { if (!_loading) _s.Repo.UpdateSettings(edit); }
    private void Prefs(Action<Core.Store.LocalPrefs> edit) { if (_loading) return; edit(_s.Repo.Prefs); _s.Repo.SavePrefs(); }

    // ── this PC ──
    partial void OnCloudSyncChanged(bool value) { if (_loading) return; _s.Repo.UpdateSettings(s => s.CloudSync = value); _s.Sync.SetEnabled(value); }
    partial void OnCatalogueSyncChanged(bool value) { Prefs(p => p.CatalogueSyncEnabled = value); if (!_loading) { _s.Sync.SetEnabled(false); _s.Sync.SetEnabled(_s.Repo.Settings.CloudSync); } }
    partial void OnAlertsOnThisPcChanged(bool value) => Prefs(p => p.AlertsOnThisPc = value);
    partial void OnStartWithWindowsChanged(bool value) { if (!_loading) { StartupRegistration.Set(value); Prefs(p => p.StartWithWindows = value); } }
    partial void OnCloseToTrayChanged(bool value) => Prefs(p => p.CloseToTray = value);
    partial void OnSoundOnNotifyChanged(bool value) => Prefs(p => p.SoundOnNotify = value);
    partial void OnShowMissedSummaryChanged(bool value) => Prefs(p => p.ShowMissedSummaryOnStart = value);
    partial void OnThemeFollowsPhoneChanged(bool value) { Prefs(p => p.ThemeFollowsPhone = value); if (!_loading) App.ApplyTheme(); }
    partial void OnLocalThemeChanged(string value) { Prefs(p => p.LocalTheme = value); if (!_loading) App.ApplyTheme(); }
    // ── synced ──
    partial void OnThemeChanged(string value) => Save(s => s.Theme = value);
    partial void OnTimeFormatChanged(string value) => Save(s => s.TimeFormat = value);
    partial void OnAlertsEnabledChanged(bool value) => Save(s => s.AlertsEnabled = value);
    partial void OnTasksAlertsOnChanged(string value) => Save(s => s.TasksAlertsOn = value);
    partial void OnLearnAlertsOnChanged(string value) => Save(s => s.LearnAlertsOn = value);
    partial void OnShopAlertsOnChanged(string value) => Save(s => s.ShopAlertsOn = value);
    partial void OnCallsAlertsOnChanged(string value) => Save(s => s.CallsAlertsOn = value);
    partial void OnSnoozeMinutesChanged(int value) => Save(s => s.SnoozeM1 = Math.Clamp(value, 1, 1440));
    partial void OnRingSecondsChanged(int value) => Save(s => s.RingRingSeconds = value <= 0 ? 0 : Alerts.CoerceRingSeconds(value));
    partial void OnAlarmSecondsChanged(int value) => Save(s => s.AlarmRingSeconds = value <= 0 ? 0 : Alerts.CoerceRingSeconds(value));
    partial void OnDefaultDueTextChanged(string value)
    {
        if (_loading) return;
        if (TimeSpan.TryParseExact(value.Trim(), new[] { @"h\:mm", @"hh\:mm" }, System.Globalization.CultureInfo.InvariantCulture, out var ts)) Save(s => s.DefaultDueMinutes = (int)ts.TotalMinutes);
    }
    partial void OnGlobalNewDueModeChanged(string value) => Save(s => s.GlobalNewDueMode = value);
    partial void OnGlobalNewDueDaysChanged(int value) => Save(s => s.GlobalNewDueDays = Math.Clamp(value, 1, 365));
    partial void OnSchedDefaultChanged(string value) => Save(s => s.SchedDefault = value);
    partial void OnGlobalNewDueTimedChanged(bool value) => Save(s => s.GlobalNewDueTimed = value);
    partial void OnShowTasksChanged(bool value) { Save(s => s.ShowTasks = value); }
    partial void OnShowLearnChanged(bool value) { Save(s => s.ShowLearn = value); }
    partial void OnShowCallsChanged(bool value) { Save(s => s.ShowCalls = value); }
    partial void OnBadgesChanged(bool value) => Save(s => s.Badges = value);
    partial void OnGroupHeaderCheckChanged(bool value) => Save(s => s.GroupHeaderCheck = value);
    partial void OnCardShowAlertTypeChanged(bool value) => Save(s => s.CardShowAlertType = value);
    partial void OnShowMediumTagChanged(bool value) => Save(s => s.ShowMediumTag = value);
    partial void OnShopCheckoutCalcChanged(bool value) => Save(s => s.ShopCheckoutCalc = value);
    partial void OnShopCheapestHintChanged(bool value) => Save(s => s.ShopCheapestHint = value);
    partial void OnShopArriveAlertChanged(bool value) => Save(s => s.ShopArriveAlert = value);
    partial void OnShopArriveTypesChanged(string value) => Save(s => s.ShopArriveTypes = value);
    partial void OnShopArriveCooldownMinChanged(int value) => Save(s => s.ShopArriveCooldownMin = Math.Clamp(value, 0, 1440));
    partial void OnShopNewRadiusChanged(float value) => Save(s => s.ShopNewRadius = Heal.SnapRadius(value));
    partial void OnDefaultCountryCodeChanged(string value) { if (!_loading && value.Trim().Length is >= 1 and <= 4 && value.Trim().All(char.IsDigit)) Save(s => s.DefaultCountryCode = value.Trim()); }

    private static List<string> Split(string t) => t.Split(new[] { ',', ';', '\n' }, StringSplitOptions.RemoveEmptyEntries).Select(x => x.Trim()).Where(x => x.Length > 0).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
    [RelayCommand] private void SaveGroups() => _s.Repo.UpdateSettings(s => { s.TasksGroups = Split(TasksGroupsText); s.ShopGroups = Split(ShopGroupsText); s.LearnTopics = Split(LearnTopicsText); });

    // ── account ──
    [RelayCommand] private void ConfigureClient() { new SignInDialog { Owner = System.Windows.Application.Current.MainWindow }.ShowDialog(); Load(); }
    [RelayCommand] private Task SignInAsync() => _main.SignInCommand.ExecuteAsync(null);
    [RelayCommand] private void SignOut()
    {
        if (!Dialogs.Confirm("Sign out on this PC? Your data stays here; sync stops until you sign in again.")) return;
        _s.Sync.SetEnabled(false);
        _s.Auth.SignOut();
        _s.Repo.Prefs.SignedInEmail = null; _s.Repo.Prefs.SignedInUid = null; _s.Repo.SavePrefs();
        Load();
    }
    [RelayCommand] private async Task PushNowAsync() { await _s.Sync.FlushPendingAsync(CancellationToken.None); RefreshSync(); }

    // ── PIN ──
    [RelayCommand] private void SetPin()
    {
        var p = _s.Repo.Prefs;
        if (p.PinHash != null) { var old = PinDialog.Ask("Enter your current PIN"); if (old == null) return; if (!Pin.Verify(old, p.PinHash, p.PinSalt)) { Dialogs.Error("Wrong PIN."); return; } }
        var pin = PinDialog.Ask("Choose a 4–8 digit PIN for this PC", confirm: true);
        if (string.IsNullOrEmpty(pin)) return;
        (p.PinHash, p.PinSalt) = Pin.Make(pin); _s.Repo.SavePrefs(); Load();
    }
    [RelayCommand] private void ClearPin()
    {
        var p = _s.Repo.Prefs;
        if (p.PinHash == null) return;
        var old = PinDialog.Ask("Enter your current PIN to remove it"); if (old == null) return;
        if (!Pin.Verify(old, p.PinHash, p.PinSalt)) { Dialogs.Error("Wrong PIN."); return; }
        p.PinHash = null; p.PinSalt = null; _s.Repo.SavePrefs(); _main.PersonalUnlocked = false; Load();
    }

    // ── data ──
    [RelayCommand] private void ExportData()
    {
        var dlg = new SaveFileDialog { Filter = "Remindly data (*.json)|*.json", FileName = $"remindly-data-{DateTime.Now:yyyyMMdd-HHmm}.json" };
        if (dlg.ShowDialog() != true) return;
        File.WriteAllText(dlg.FileName, _s.Repo.ExportDataJson());
        _s.Repo.Prefs.LastMidnightSweep = _s.Repo.Prefs.LastMidnightSweep; // no-op; keep prefs stable
        Dialogs.Info("Exported. The file also imports on the phone (Settings → Backup & Restore).");
    }
    [RelayCommand] private void ExportSettings()
    {
        var dlg = new SaveFileDialog { Filter = "Remindly settings (*.json)|*.json", FileName = $"remindly-settings-{DateTime.Now:yyyyMMdd-HHmm}.json" };
        if (dlg.ShowDialog() != true) return;
        File.WriteAllText(dlg.FileName, _s.Repo.ExportSettingsJson());
    }
    [RelayCommand] private void Import()
    {
        var dlg = new OpenFileDialog { Filter = "Remindly backup (*.json)|*.json|All files|*.*" };
        if (dlg.ShowDialog() != true) return;
        try { Dialogs.Info(_s.Repo.ImportJson(File.ReadAllText(dlg.FileName))); }
        catch (Exception ex) { Dialogs.Error("Could not import: " + ex.Message); }
    }
    [RelayCommand] private void OpenDataFolder() => Dialogs.OpenFolder(AppPaths.Root);
    [RelayCommand] private void ShowLog() { LogTail = Log.Tail(120); }
    [RelayCommand] private void TestNotify() => TestNotification();
    [RelayCommand] private void TestRing() => new AlertPopupWindow(new AlertEvent(AlertSource.Summary, 0, Clock.Now(), "R", "TEST · Ring", "Test — no action taken", null, "test")).Show();
    [RelayCommand] private void TestAlarm() => new AlarmWindow(new AlertEvent(AlertSource.Summary, 0, Clock.Now(), "A", "TEST · Alarm", "Submit the report", null, "test")).Show();
    [RelayCommand] private void TestNotification() => new AlertPopupWindow(new AlertEvent(AlertSource.Summary, 0, Clock.Now(), "N", "TEST · Notification", "Test — no action taken", null, "test")).Show();
    [RelayCommand] private void OpenConsoleHelp() => Dialogs.OpenUrl("https://console.cloud.google.com/apis/credentials");
}
