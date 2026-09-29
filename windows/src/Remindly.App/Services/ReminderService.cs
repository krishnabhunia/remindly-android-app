using System.Windows.Threading;
using Remindly.App.Views;
using Remindly.Core;

namespace Remindly.App.Services;

/// <summary>
/// Windows' stand-in for Android's AlarmManager: every 15 s it walks recurring items back into
/// Active on their day and shows every reminder that became due (once — the key is persisted).
/// </summary>
public sealed class ReminderService
{
    private const int MaxOpenAlerts = 4;
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(15) };
    private readonly List<AlertWindow> _open = new();

    public ReminderService() => _timer.Tick += (_, _) => Tick();

    public void Start()
    {
        _timer.Start();
        Tick();
    }

    public void Stop()
    {
        _timer.Stop();
        foreach (var w in _open.ToList()) { try { w.Close(); } catch { } }
    }

    public bool AnyAlertOpen => _open.Count > 0;

    public void Tick()
    {
        try
        {
            var state = AppState.Current;
            state.Sweep();
            var due = Reminders.Due(state.Data, state.Now, state.FiredSet());
            if (due.Count > 0)
            {
                state.MarkFired(due.Select(a => a.Key));
                int room = Math.Max(0, MaxOpenAlerts - _open.Count);
                foreach (var a in due.Take(room)) Show(a);
                if (due.Count > room)
                    App.Tray?.Balloon("Remindly", $"{due.Count - room} more reminder(s) are due — open Remindly to see them.");
                Log.Info($"Alerts shown: {string.Join(", ", due.Select(a => a.Key))}");
            }
            App.Tray?.SetTooltip(Reminders.NextFire(state.Data, state.Now));
        }
        catch (Exception ex) { Log.Error("Reminder tick failed", ex); }
    }

    private void Show(DueAlert a)
    {
        var w = new AlertWindow(a, _open.Count);
        _open.Add(w);
        w.Closed += (_, _) => { _open.Remove(w); Restack(); };
        w.Show();
    }

    private void Restack()
    {
        for (int i = 0; i < _open.Count; i++) _open[i].PlaceAt(i);
    }
}
