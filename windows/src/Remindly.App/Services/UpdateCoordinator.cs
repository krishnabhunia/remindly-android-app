using System.Diagnostics;
using System.Windows;
using System.Windows.Threading;
using Microsoft.Win32;
using Remindly.Core;
using Remindly.Core.Updates;

namespace Remindly.App.Services;

/// <summary>
/// The "Auto update" behaviour:
///  • checks GitHub once a day — the first time on the day after the last check (never twice a day);
///  • with Auto update ticked, a newer version is downloaded, verified (SHA-256) and installed in the
///    background: an installed copy runs the new Setup silently (Setup closes this copy, updates and
///    starts it again); a portable copy swaps its exe and restarts;
///  • the install waits while you are in the middle of something (an editor or a reminder is open,
///    or Remindly is the active window) and goes ahead at the next quiet moment.
/// With the box unticked nothing is checked automatically; Settings → Updates → Check now still works.
/// </summary>
public sealed class UpdateCoordinator
{
    private readonly UpdateService _service = new();
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromMinutes(30) };
    private readonly DispatcherTimer _pendingTimer = new() { Interval = TimeSpan.FromMinutes(2) };
    private string? _pendingFile;
    private bool _busy;

    public UpdateCheckResult? Last { get; private set; }
    public string Status { get; private set; } = "";
    public int Progress { get; private set; } = -1;
    public bool Busy => _busy;
    public event Action? Changed;

    public UpdateCoordinator()
    {
        _timer.Tick += async (_, _) => await TickAsync();
        _pendingTimer.Tick += (_, _) => TryApplyPending();
    }

    public void Start()
    {
        CleanPortableLeftover();
        var s = AppState.Current.Settings;
        // First run: the first automatic check happens tomorrow.
        if (s.UpdateLastCheck <= 0) AppState.Current.UpdateSettingsQuiet(x => x with { UpdateLastCheck = Clock.NowMs() });
        SystemEvents.PowerModeChanged += (_, e) =>
        {
            if (e.Mode == PowerModes.Resume) Application.Current.Dispatcher.BeginInvoke(async () => await TickAsync());
        };
        _timer.Start();
        Application.Current.Dispatcher.BeginInvoke(DispatcherPriority.ApplicationIdle, async () => await TickAsync());
    }

    public void Stop()
    {
        _timer.Stop();
        _pendingTimer.Stop();
    }

    public DateTime NextAutoCheck => Clock.ToLocal(UpdateLogic.NextCheckAt(AppState.Current.Settings.UpdateLastCheck, Clock.NowMs()));

    private async Task TickAsync()
    {
        var s = AppState.Current.Settings;
        if (_busy || !UpdateLogic.IsCheckDue(s.UpdateLastCheck, Clock.NowMs(), s.UpdateAutoCheck)) return;
        await CheckNowAsync(interactive: false);
    }

    /// <summary>Checks now. Automatic checks with Auto update on also download and install.</summary>
    public async Task<UpdateCheckResult?> CheckNowAsync(bool interactive)
    {
        if (_busy) return Last;
        _busy = true;
        SetStatus("Checking GitHub for a new version…");
        try
        {
            var r = await _service.CheckAsync(CancellationToken.None);
            Last = r;
            AppState.Current.UpdateSettingsQuiet(x => x with { UpdateLastCheck = Clock.NowMs() });
            SetStatus(r.Message);
            if (r.Status == UpdateStatus.UpdateAvailable)
            {
                if (!interactive && AppState.Current.Settings.UpdateAutoCheck)
                {
                    _busy = false;
                    await DownloadAndInstallAsync(background: true);
                }
                else App.Tray?.Balloon("Remindly update", r.Message + " Open Settings → Updates to install it.");
            }
            return r;
        }
        catch (Exception ex)
        {
            Log.Error("Update check failed", ex);
            SetStatus("Update check failed: " + ex.Message);
            return null;
        }
        finally { _busy = false; Changed?.Invoke(); }
    }

    /// <summary>Downloads + verifies the newer release, then installs it (now, or at the next quiet moment when background).</summary>
    public async Task DownloadAndInstallAsync(bool background)
    {
        if (_busy || Last?.Release is not ReleaseInfo release || Last.Status != UpdateStatus.UpdateAvailable) return;
        _busy = true;
        try
        {
            bool installed = InstallInfo.IsInstalledMode();
            SetStatus($"Downloading Remindly {release.Version.ToString(3)}…");
            var zip = await _service.DownloadAsync(release, new Progress<int>(p => { Progress = p; Changed?.Invoke(); }), CancellationToken.None);
            var file = await Task.Run(() => UpdateService.ExtractForMode(zip, installed));
            Progress = -1;
            _pendingFile = file;
            if (background && UserIsBusy())
            {
                SetStatus($"Remindly {release.Version.ToString(3)} is downloaded and verified — it installs as soon as you are not using Remindly.");
                _pendingTimer.Start();
                return;
            }
            Apply();
        }
        catch (Exception ex)
        {
            Progress = -1;
            Log.Error("Update download/install failed", ex);
            SetStatus("Update failed: " + ex.Message);
            if (background) App.Tray?.Balloon("Remindly update failed", ex.Message, warning: true);
        }
        finally { _busy = false; Changed?.Invoke(); }
    }

    private void TryApplyPending()
    {
        if (_pendingFile == null) { _pendingTimer.Stop(); return; }
        if (UserIsBusy()) return;
        _pendingTimer.Stop();
        Apply();
    }

    /// <summary>An editor or reminder is open, or Remindly is the window the user is working in.</summary>
    private static bool UserIsBusy()
    {
        var app = Application.Current;
        if (App.Reminders?.AnyAlertOpen == true) return true;
        foreach (Window w in app.Windows)
            if (w != app.MainWindow && w.IsVisible) return true;
        return app.MainWindow is { IsVisible: true, IsActive: true };
    }

    private void Apply()
    {
        var file = _pendingFile;
        if (file == null || !File.Exists(file)) return;
        _pendingFile = null;
        bool hidden = Application.Current.MainWindow is not { IsVisible: true };
        try
        {
            if (InstallInfo.IsInstalledMode())
            {
                // Setup finds this copy running, asks it to exit (--exit → the Exit event), installs and starts it again.
                var args = "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /REMINDLYUPDATE=1" + (hidden ? " /RELAUNCH=tray" : "");
                Process.Start(new ProcessStartInfo(file, args) { UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(file)! });
                Log.Info("Started Setup for the background update: " + file);
                App.Current.ExitApp("installing update");
                return;
            }

            // Portable: the running exe can be renamed (not deleted) — move it aside, put the new one in its place, restart.
            var exe = InstallInfo.ExePath;
            var old = Path.Combine(InstallInfo.ExeDirectory, "Remindly.old.exe");
            try { if (File.Exists(old)) File.Delete(old); } catch { }
            File.Move(exe, old);
            try { File.Copy(file, exe, overwrite: true); }
            catch
            {
                File.Move(old, exe); // put the running version back
                throw;
            }
            Process.Start(new ProcessStartInfo(exe, "--updated" + (hidden ? " --tray" : "")) { UseShellExecute = false, WorkingDirectory = InstallInfo.ExeDirectory });
            Log.Info("Portable copy replaced; restarting " + exe);
            App.Current.ExitApp("portable update applied");
        }
        catch (Exception ex)
        {
            Log.Error("Applying the update failed", ex);
            SetStatus("Could not install the update: " + ex.Message + " — download it from the Releases page.");
            App.Tray?.Balloon("Remindly update", "Could not install the update automatically. Open Settings → Updates.", warning: true);
        }
    }

    /// <summary>The previous portable exe stays behind as Remindly.old.exe until the next start.</summary>
    private static void CleanPortableLeftover()
    {
        try
        {
            var old = Path.Combine(InstallInfo.ExeDirectory, "Remindly.old.exe");
            if (File.Exists(old)) File.Delete(old);
        }
        catch { /* the old process may still be exiting */ }
    }

    private void SetStatus(string s)
    {
        Status = s;
        Changed?.Invoke();
    }
}
