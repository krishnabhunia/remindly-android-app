using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;
using Remindly.App.Services;
using Remindly.Core;
using Remindly.Core.Updates;

namespace Remindly.App.Views;

/// <summary>Settings: Updates (Auto update), General, Sharing a list, Backup &amp; Android import, Bin, About.</summary>
public sealed class SettingsView : ScrollViewer, IPage
{
    private readonly StackPanel _root = new() { MaxWidth = 860, HorizontalAlignment = HorizontalAlignment.Left };
    private readonly TextBlock _updateStatus = Ui.Sub("");
    private readonly TextBlock _updateTimes = Ui.Sub("");
    private readonly ProgressBar _updateProgress = new() { Height = 6, Maximum = 100, Visibility = Visibility.Collapsed, Margin = new Thickness(0, 6, 0, 0) };
    private readonly Button _installBtn;
    private readonly Button _checkBtn;

    public SettingsView()
    {
        VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
        Content = _root;
        _checkBtn = Ui.Primary("Check now", async () =>
        {
            if (App.Updates == null) return;
            await App.Updates.CheckNowAsync(interactive: true);
            RefreshUpdates();
        });
        _installBtn = Ui.Btn("Download and install", async () =>
        {
            if (App.Updates == null) return;
            await App.Updates.DownloadAndInstallAsync(background: false);
            RefreshUpdates();
        });
        if (App.Updates != null) App.Updates.Changed += () => Dispatcher.BeginInvoke(RefreshUpdates);
    }

    public void Refresh()
    {
        var state = AppState.Current;
        var s = state.Settings;
        _root.Children.Clear();
        _root.Children.Add(Ui.H1("Settings"));

        // ── Updates ──
        var v = UpdateService.CurrentVersion.ToString(3);
        var upd = Section("Updates", $"Remindly for Windows {v} · {InstallInfo.ModeLabel}");
        upd.Children.Add(Ui.Setting("Auto update", s.UpdateAutoCheck, on =>
        {
            state.UpdateSettingsQuiet(x => x with { UpdateAutoCheck = on });
            RefreshUpdates();
        }, "Checks GitHub once a day — the first time on the next day — and installs a new version in the background. " +
           "Remindly restarts by itself when you are not using it; your data stays."));
        upd.Children.Add(_updateTimes);
        upd.Children.Add(Ui.Row(Detach(_checkBtn), Detach(_installBtn), Ui.Btn("Releases page", () => InstallInfo.OpenUrl(UpdateLogic.ReleasesUrl))));
        upd.Children.Add(Detach(_updateProgress));
        upd.Children.Add(Detach(_updateStatus));
        _root.Children.Add(Card(upd));
        RefreshUpdates();

        // ── General ──
        var gen = Section("General", null);
        gen.Children.Add(Ui.Setting("Start with Windows", s.StartWithWindows, on =>
        {
            InstallInfo.SetStartWithWindows(on);
            state.UpdateSettingsQuiet(x => x with { StartWithWindows = on });
        }, "Starts hidden in the notification area so reminders fire even before you open Remindly."));
        gen.Children.Add(Ui.Setting("Keep running in the notification area when the window is closed", s.CloseToTray,
            on => state.UpdateSettingsQuiet(x => x with { CloseToTray = on }), "Off: closing the window exits Remindly and no reminders fire until you start it again."));
        var snooze = new ComboBox { Width = 90 };
        foreach (var m in new[] { 5, 10, 15, 30, 60 }) snooze.Items.Add(m);
        snooze.SelectedItem = new[] { 5, 10, 15, 30, 60 }.Contains(s.SnoozeMinutes) ? s.SnoozeMinutes : 10;
        snooze.SelectionChanged += (_, _) => state.UpdateSettingsQuiet(x => x with { SnoozeMinutes = (int)snooze.SelectedItem });
        var hour = new ComboBox { Width = 90 };
        for (int h = 0; h < 24; h++) hour.Items.Add($"{h:00}:00");
        hour.SelectedIndex = s.DefaultDueHour;
        hour.SelectionChanged += (_, _) => state.UpdateSettingsQuiet(x => x with { DefaultDueHour = hour.SelectedIndex });
        gen.Children.Add(Ui.Row(Ui.Sub("Snooze for "), snooze, Ui.Sub("  minutes      A date without a time rings at "), hour));
        _root.Children.Add(Card(gen));

        // ── Sharing a list ──
        var share = Section("Sharing a list", "The text is \"Groceries:-\", a blank line, then \"1. Milk - 2 / L - Urgent - Bought\".");
        share.Children.Add(Ui.Setting("Include bought items", s.ShareIncludeDone, on => state.UpdateSettingsQuiet(x => x with { ShareIncludeDone = on })));
        share.Children.Add(Ui.Setting("Quantity / type (\"2 / L\")", s.ShareIncludeQty, on => state.UpdateSettingsQuiet(x => x with { ShareIncludeQty = on })));
        share.Children.Add(Ui.Setting("\" - Urgent\" on urgent items", s.ShareUrgentTag, on => state.UpdateSettingsQuiet(x => x with { ShareUrgentTag = on })));
        share.Children.Add(Ui.Setting("\" - Bought\" on bought items", s.ShareBoughtTag, on => state.UpdateSettingsQuiet(x => x with { ShareBoughtTag = on })));
        var suffix = new TextBox { Text = s.ShareHeadingSuffix, Width = 70 };
        suffix.LostFocus += (_, _) => state.UpdateSettingsQuiet(x => x with { ShareHeadingSuffix = suffix.Text });
        share.Children.Add(Ui.Row(Ui.Sub("Text after the list name "), suffix));
        _root.Children.Add(Card(share));

        // ── Backup ──
        var bk = Section("Backup and your phone", "Import an Android backup (Settings → Backup on the phone, a remindly-data-*.json file) to bring your tasks, lists, calls and Buy items to this PC. Importing twice changes nothing; the newer copy of each record wins.");
        bk.Children.Add(Ui.Row(
            Ui.Primary("Import a backup…", Import),
            Ui.Btn("Export a backup…", Export),
            Ui.Btn("Open the data folder", () => InstallInfo.OpenUrl(AppPaths.DataRoot))));
        bk.Children.Add(Ui.Sub($"Saved automatically to {AppPaths.DataFile} · a daily copy is kept for 7 days."));
        _root.Children.Add(Card(bk));

        // ── Bin ──
        var binItems = state.Data.Items.Where(i => i.DeletedAt != null).OrderByDescending(i => i.DeletedAt).ToList();
        var binCalls = (state.Data.Calls ?? new()).Where(c => c.DeletedAt != null).OrderByDescending(c => c.DeletedAt).ToList();
        var bin = Section("Bin", $"{binItems.Count + binCalls.Count} deleted record(s) · kept for 30 days");
        foreach (var i in binItems.Take(40))
        {
            bin.Children.Add(Ui.Columns("*,Auto",
                Ui.Text($"{i.Title}  ·  {TabNames.Title(i.Tab)}  ·  deleted {Clock.FormatDate(i.DeletedAt!.Value)}", 13.5),
                Ui.Row(Ui.Btn("Restore", () => { state.RestoreFromBin(i); App.Current.Main.Snack($"Restored \"{i.Title}\""); }),
                       Ui.Btn("Delete forever", () => { if (Ui.Confirm($"Delete \"{i.Title}\" for good?")) state.DeleteForever(i); }, style: "Danger"))));
        }
        foreach (var c in binCalls.Take(20))
        {
            bin.Children.Add(Ui.Columns("*,Auto",
                Ui.Text($"{c.Display}  ·  Calls  ·  deleted {Clock.FormatDate(c.DeletedAt!.Value)}", 13.5),
                Ui.Row(Ui.Btn("Restore", () => state.UpsertCall(c with { DeletedAt = null })),
                       Ui.Btn("Delete forever", () => { if (Ui.Confirm($"Delete the call-back for {c.Display} for good?")) state.DeleteCallForever(c); }, style: "Danger"))));
        }
        if (binItems.Count + binCalls.Count > 0)
            bin.Children.Add(Ui.Btn("Empty the Bin", () =>
            {
                if (!Ui.Confirm("Delete everything in the Bin for good?")) return;
                foreach (var i in binItems) state.Data.Items.RemoveAll(x => x.Id == i.Id);
                state.Data.Calls?.RemoveAll(x => x.DeletedAt != null);
                state.UpdateSettings(x => x);
            }, style: "Danger"));
        _root.Children.Add(Card(bin));

        // ── About ──
        var about = Section("About", null);
        about.Children.Add(Ui.Text($"Remindly {v} for Windows", 15, FontWeights.SemiBold));
        about.Children.Add(Ui.Sub($"{InstallInfo.ModeLabel} copy · {InstallInfo.ExePath}"));
        about.Children.Add(Ui.Sub("The Windows companion of the Remindly Android app: Tasks · Learn · Calls ⇄ Buy (lists) · Shops · Products. " +
            "Geofenced shop arrivals, call-log detection, maps and cloud sync stay on the phone."));
        about.Children.Add(Ui.Row(Ui.Btn("Source on GitHub", () => InstallInfo.OpenUrl(UpdateLogic.RepoUrl)), Ui.Btn("Log file", () => InstallInfo.OpenUrl(AppPaths.LogFile))));
        _root.Children.Add(Card(about));
    }

    private static T Detach<T>(T e) where T : FrameworkElement
    {
        (e.Parent as Panel)?.Children.Remove(e);
        return e;
    }

    private static StackPanel Section(string title, string? hint)
    {
        var p = new StackPanel();
        p.Children.Add(Ui.Text(title, 16, FontWeights.SemiBold));
        if (hint != null) { var h = Ui.Sub(hint); h.Margin = new Thickness(0, 2, 0, 8); p.Children.Add(h); }
        return p;
    }

    private static Border Card(UIElement child)
    {
        var c = Ui.Card(child);
        c.Padding = new Thickness(16, 12, 16, 14);
        c.Margin = new Thickness(0, 12, 0, 0);
        return c;
    }

    private void RefreshUpdates()
    {
        var u = App.Updates;
        var s = AppState.Current.Settings;
        var last = s.UpdateLastCheck > 0 ? Clock.ToLocal(s.UpdateLastCheck).ToString("ddd dd MMM, HH:mm", CultureInfo.InvariantCulture) : "never";
        var next = u != null && s.UpdateAutoCheck ? u.NextAutoCheck.ToString("ddd dd MMM", CultureInfo.InvariantCulture) : "—";
        _updateTimes.Text = s.UpdateAutoCheck ? $"Last check: {last} · next automatic check: {next}" : $"Last check: {last} · automatic checks are off";
        _updateStatus.Text = u?.Status ?? "";
        _checkBtn.IsEnabled = u is { Busy: false };
        _installBtn.Visibility = u?.Last?.Status == UpdateStatus.UpdateAvailable ? Visibility.Visible : Visibility.Collapsed;
        _installBtn.Content = u?.Last?.Release is ReleaseInfo r ? $"Install {r.Version.ToString(3)} now" : "Download and install";
        _installBtn.IsEnabled = u is { Busy: false };
        _updateProgress.Visibility = u is { Progress: >= 0 } ? Visibility.Visible : Visibility.Collapsed;
        _updateProgress.Value = Math.Max(0, u?.Progress ?? 0);
    }

    private void Import()
    {
        var dlg = new OpenFileDialog { Filter = "Remindly backup (*.json)|*.json|All files|*.*", Title = "Import a Remindly backup (Android or Windows)" };
        if (dlg.ShowDialog() != true) return;
        try
        {
            var blob = DataStore.Parse(File.ReadAllText(dlg.FileName));
            if (blob == null) { Ui.Info("That file is not a Remindly backup."); return; }
            var snap = AppState.Current.TakeSnapshot();
            var r = AppState.Current.Import(blob);
            Ui.Info($"Imported from {(r.FromAndroid ? "your phone" : "a Windows backup")}:\n\n• {r.Items} task / Learn / Buy item(s) new or newer\n• {r.Lists} new list(s)\n• {r.Calls} call-back(s)\n• {r.Shops} shop(s), {r.Products} product(s)");
            App.Current.Main.Snack("Backup imported", () => AppState.Current.Restore(snap));
        }
        catch (Exception ex)
        {
            Log.Error("Import failed", ex);
            Ui.Info("Import failed: " + ex.Message);
        }
    }

    private void Export()
    {
        var dlg = new SaveFileDialog { Filter = "Remindly backup (*.json)|*.json", FileName = $"remindly-windows-{DateTime.Now:yyyy-MM-dd}.json" };
        if (dlg.ShowDialog() != true) return;
        try
        {
            AppState.Current.Export(dlg.FileName);
            App.Current.Main.Snack("Backup saved");
        }
        catch (Exception ex) { Ui.Info("Export failed: " + ex.Message); }
    }
}
