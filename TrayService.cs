using System.Drawing;
using System.Windows;
using WinForms = System.Windows.Forms;

namespace Remindly.App.Services;

/// <summary>System-tray presence: open, today's count, pause alerts, quit. WinForms NotifyIcon (rock solid, unpackaged).</summary>
public sealed class TrayService : IDisposable
{
    private readonly WinForms.NotifyIcon _icon;
    private readonly AppServices _s;
    private readonly WinForms.ToolStripMenuItem _pause;
    private readonly WinForms.ToolStripMenuItem _today;

    public event Action? OpenRequested;
    public event Action? QuitRequested;

    public TrayService(AppServices s)
    {
        _s = s;
        Icon ico;
        using (var st = Application.GetResourceStream(new Uri("pack://application:,,,/Assets/app.ico"))!.Stream) ico = new Icon(st);
        var menu = new WinForms.ContextMenuStrip();
        var open = new WinForms.ToolStripMenuItem("Open Remindly") { Font = new Font(menu.Font, System.Drawing.FontStyle.Bold) };
        open.Click += (_, _) => OpenRequested?.Invoke();
        _today = new WinForms.ToolStripMenuItem("Today") { Enabled = false };
        _pause = new WinForms.ToolStripMenuItem("Pause alerts for 1 hour");
        _pause.Click += (_, _) =>
        {
            if (_s.Alerts.Active) _s.Alerts.PauseFor(TimeSpan.FromHours(1)); else _s.Alerts.Resume();
            Refresh();
        };
        var quit = new WinForms.ToolStripMenuItem("Quit");
        quit.Click += (_, _) => QuitRequested?.Invoke();
        menu.Items.AddRange(new WinForms.ToolStripItem[] { open, _today, new WinForms.ToolStripSeparator(), _pause, new WinForms.ToolStripSeparator(), quit });
        _icon = new WinForms.NotifyIcon { Icon = ico, Visible = true, Text = "Remindly", ContextMenuStrip = menu };
        _icon.DoubleClick += (_, _) => OpenRequested?.Invoke();
        _icon.MouseClick += (_, e) => { if (e.Button == WinForms.MouseButtons.Left) OpenRequested?.Invoke(); };
        Refresh();
    }

    public void Refresh()
    {
        try
        {
            var now = Core.Models.Clock.Now();
            var tomorrow = Core.Logic.Time.StartOfTomorrow(now);
            var n = _s.Repo.Items.Count(i => i.DeletedAt == null && !i.Done && i.DueAt is long d && d < tomorrow);
            var paused = !_s.Alerts.Active;
            _today.Text = n == 0 ? "Nothing due today" : $"{n} due today";
            _pause.Text = paused ? "Resume alerts" : "Pause alerts for 1 hour";
            var status = _s.Sync.StatusLine();
            _icon.Text = $"Remindly · {_today.Text}\n{status}{(paused ? "\nAlerts paused" : "")}"[..Math.Min(127, 200)];
        }
        catch { /* tray text is cosmetic */ }
    }

    public void Balloon(string title, string text)
    {
        try { _icon.ShowBalloonTip(4000, title, text, WinForms.ToolTipIcon.None); } catch { }
    }

    public void Dispose()
    {
        try { _icon.Visible = false; _icon.Dispose(); } catch { }
    }
}
