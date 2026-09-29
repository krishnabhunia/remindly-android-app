using System.Windows;
using Remindly.Core;
using Remindly.Core.Updates;
using WinForms = System.Windows.Forms;

namespace Remindly.App.Services;

/// <summary>The notification-area icon: open, quick add, check for updates, exit; balloon messages.</summary>
public sealed class TrayService : IDisposable
{
    private readonly WinForms.NotifyIcon _icon;
    private bool _hintShown;

    public TrayService()
    {
        _icon = new WinForms.NotifyIcon
        {
            Icon = LoadIcon(),
            Visible = true,
            Text = $"Remindly {UpdateService.CurrentVersion.ToString(3)}",
        };
        var menu = new WinForms.ContextMenuStrip();
        menu.Items.Add("Open Remindly", null, (_, _) => App.Current.ShowMain());
        menu.Items.Add("New task…", null, (_, _) => App.Current.Dispatcher.BeginInvoke(() =>
        {
            App.Current.ShowMain();
            App.Current.Main.NewItemFromTray();
        }));
        menu.Items.Add("Check for updates", null, (_, _) => App.Current.Dispatcher.BeginInvoke(async () =>
        {
            var r = await (App.Updates?.CheckNowAsync(interactive: true) ?? Task.FromResult<UpdateCheckResult?>(null));
            if (r != null && r.Status != UpdateStatus.UpdateAvailable) Balloon("Remindly", r.Message);
        }));
        menu.Items.Add(new WinForms.ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) => App.Current.Dispatcher.BeginInvoke(() => App.Current.ExitApp("tray Exit")));
        _icon.ContextMenuStrip = menu;
        _icon.DoubleClick += (_, _) => App.Current.ShowMain();
        _icon.BalloonTipClicked += (_, _) => App.Current.ShowMain();
    }

    private static System.Drawing.Icon LoadIcon()
    {
        try
        {
            var s = Application.GetResourceStream(new Uri("pack://application:,,,/Assets/app.ico"))?.Stream;
            if (s != null) return new System.Drawing.Icon(s);
        }
        catch (Exception ex) { Log.Warn("Tray icon: " + ex.Message); }
        return System.Drawing.SystemIcons.Application;
    }

    public void Balloon(string title, string text, bool warning = false)
    {
        try
        {
            _icon.BalloonTipTitle = title;
            _icon.BalloonTipText = string.IsNullOrWhiteSpace(text) ? " " : text;
            _icon.BalloonTipIcon = warning ? WinForms.ToolTipIcon.Warning : WinForms.ToolTipIcon.Info;
            _icon.ShowBalloonTip(5000);
        }
        catch (Exception ex) { Log.Warn("Balloon: " + ex.Message); }
    }

    /// <summary>Said once per run when the window is closed to the notification area.</summary>
    public void HintStillRunning()
    {
        if (_hintShown) return;
        _hintShown = true;
        Balloon("Remindly is still running", "Reminders keep working here. Right-click the icon to exit.");
    }

    public void SetTooltip(long? nextFire)
    {
        var t = $"Remindly {UpdateService.CurrentVersion.ToString(3)}" + (nextFire is long n ? $"\nNext: {Clock.FormatDayTime(n)}" : "");
        _icon.Text = t.Length > 63 ? t[..63] : t;
    }

    public void Dispose()
    {
        _icon.Visible = false;
        _icon.Dispose();
    }
}
