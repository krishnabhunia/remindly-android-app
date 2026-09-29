using System.Windows.Threading;
using Remindly.Core.Logic;
using Remindly.Core.Models;

namespace Remindly.App.Services;

public enum AlertSource { Item, Call, Expiry, Summary }

/// <summary>One armed trigger (the "Scheduled alerts" row) or one firing.</summary>
public sealed record AlertEvent(AlertSource Source, long RecordId, long FireAt, string Types, string Title, string Subtitle, Tab? Tab, string Kind)
{
    public string Key => $"{Source}:{RecordId}:{FireAt}";
}

/// <summary>
/// The PC-side AlarmManager: every 10 s it looks at what is armed and fires what has come due,
/// exactly once per (record, time) thanks to a persisted ledger. Windows semantics that mirror
/// the phone: a live snooze wins over dueAt; a muted item arms nothing; per-tab alert switches
/// inherit the global master; Quiet demotes the record to Notify; Done on a repeat advances it.
/// </summary>
public sealed class AlertEngine : IDisposable
{
    private readonly AppServices _s;
    private readonly DispatcherTimer _timer;
    private readonly long _startedAt = Clock.Now();
    private bool _startupPassDone;
    public Sounds Sounds { get; } = new();
    public const long GraceMs = 24L * 3600 * 1000;   // fire things at most a day late (PC was off)

    /// <summary>Raised on the UI thread for each firing; the App shows the popup / alarm card.</summary>
    public event Action<AlertEvent>? Fired;
    /// <summary>Raised once after start-up with the reminders that came due while the app was closed.</summary>
    public event Action<List<AlertEvent>>? MissedWhileClosed;

    public AlertEngine(AppServices s)
    {
        _s = s;
        _timer = new DispatcherTimer(DispatcherPriority.Normal, s.Dispatcher) { Interval = TimeSpan.FromSeconds(10) };
        _timer.Tick += (_, _) => Tick();
        _timer.Start();
        s.Dispatcher.BeginInvoke(Tick, DispatcherPriority.ApplicationIdle);
    }

    public bool Active => _s.Repo.Prefs.AlertsOnThisPc && !_s.Repo.Prefs.AlertsPaused(Clock.Now());

    /// <summary>Everything armed right now, soonest first (the Scheduled-alerts page + tray tooltip).</summary>
    public List<AlertEvent> Upcoming(long now, long? horizonMs = null)
    {
        var repo = _s.Repo; var s = repo.Settings;
        var rows = new List<AlertEvent>();
        foreach (var i in repo.Items)
        {
            if (i.Done || i.DeletedAt != null) continue;
            if (!i.IsMuted && Alerts.EnabledFor(i.Tab, s))
            {
                var fireAt = i.SnoozedUntil ?? i.DueAt;
                if (fireAt is long f && f >= now - GraceMs)
                    rows.Add(new AlertEvent(AlertSource.Item, i.Id, f, Alerts.ResolveItem(i), i.Title, i.SnoozedUntil != null ? "Snoozed" : (i.RepeatMode != "OFF" ? Recurrence.RepeatLabel(i) ?? "Due" : "Due"), i.Tab, "due"));
            }
            if (i.Tab == Tab.SHOP && i.ExpiryAt is long ex && Alerts.EnabledFor(Tab.SHOP, s))
            {
                var at = Time.AtMinuteOfDay(Time.LocalDate(ex), 9 * 60);
                if (at >= now - GraceMs) rows.Add(new AlertEvent(AlertSource.Expiry, i.Id, at, "N", i.Title, "Expires today", Tab.SHOP, "expiry"));
            }
        }
        if (Alerts.EnabledFor(null, s))
            foreach (var c in repo.Calls)
            {
                if (c.Done || c.DeletedAt != null) continue;
                long? fireAt = c.SnoozedUntil ?? (c.RepeatMode != "OFF" ? c.RecurAt : null) ?? (!c.NagFired ? c.NagAt : null);
                if (fireAt is long f && f >= now - GraceMs)
                    rows.Add(new AlertEvent(AlertSource.Call, c.Id, f, Alerts.ResolveCall(c), c.Display, c.SnoozedUntil != null ? "Snoozed call" : c.RepeatMode != "OFF" ? "Recurring call" : "Call back", null, "call"));
            }
        var q = rows.Where(r => horizonMs == null || r.FireAt <= now + horizonMs.Value).OrderBy(r => r.FireAt);
        return q.ToList();
    }

    private void Tick()
    {
        try
        {
            var now = Clock.Now();
            if (!Active) { _startupPassDone = true; return; }
            var due = Upcoming(now).Where(r => r.FireAt <= now && !_s.Repo.Prefs.FiredKeys.Contains(r.Key)).ToList();
            if (due.Count == 0) { _startupPassDone = true; return; }
            var firstPass = !_startupPassDone && now - _startedAt < 60_000;
            _startupPassDone = true;
            // On start-up, several reminders that came due while the PC was off become ONE summary
            // (the phone would have rung for them already) instead of a barrage of alarms.
            var stale = firstPass ? due.Where(r => r.FireAt < now - 5 * 60_000).ToList() : new List<AlertEvent>();
            foreach (var r in stale) { MarkFired(r); ClearServedSnooze(r); }
            if (stale.Count > 0 && _s.Repo.Prefs.ShowMissedSummaryOnStart) MissedWhileClosed?.Invoke(stale);
            foreach (var r in due.Except(stale))
            {
                MarkFired(r);
                ClearServedSnooze(r);
                Log.Info($"ALERT fire {r.Source} '{r.Title}' types={r.Types}");
                Fired?.Invoke(r);
            }
            _s.Repo.SavePrefs();
        }
        catch (Exception ex) { Log.Error("alert tick", ex); }
    }

    private void MarkFired(AlertEvent r)
    {
        var keys = _s.Repo.Prefs.FiredKeys;
        if (!keys.Contains(r.Key)) keys.Add(r.Key);
        if (keys.Count > 2000) keys.RemoveRange(0, keys.Count - 2000);
    }

    /// <summary>TYPE_DUE: "the snooze has now been served — clear it" (Receivers.kt).</summary>
    private void ClearServedSnooze(AlertEvent r)
    {
        var repo = _s.Repo;
        if (r.Source == AlertSource.Item && repo.Item(r.RecordId) is { SnoozedUntil: not null } i && i.SnoozedUntil <= Clock.Now()) repo.Upsert(i with { SnoozedUntil = null });
        if (r.Source == AlertSource.Call && repo.Call(r.RecordId) is { } c)
        {
            if (c.SnoozedUntil != null && c.SnoozedUntil <= Clock.Now()) c = c with { SnoozedUntil = null };
            if (c.NagAt != null && !c.NagFired && c.NagAt <= Clock.Now()) c = c with { NagFired = true };
            if (c != repo.Call(r.RecordId)) repo.Upsert(c);
        }
    }

    // ── actions from the alert card / popup ──────────────────────────────────────────────

    public string SnoozeLabel => Alerts.SnoozeMinLabel(Alerts.SnoozeMinutes(_s.Repo.Settings));

    public void Snooze(AlertEvent e)
    {
        var repo = _s.Repo; var until = Alerts.SnoozeTargetMs(repo.Settings, Clock.Now());
        if (e.Source is AlertSource.Item or AlertSource.Expiry && repo.Item(e.RecordId) is { } i) repo.Upsert(Engine.Snooze(i, until));
        if (e.Source == AlertSource.Call && repo.Call(e.RecordId) is { } c) repo.Upsert(Engine.SnoozeCall(c, until));
        Sounds.Stop();
    }

    /// <summary>Quiet (N13): back after the snooze as a Notification; the record's type becomes N permanently.</summary>
    public void Quiet(AlertEvent e)
    {
        var repo = _s.Repo; var until = Alerts.SnoozeTargetMs(repo.Settings, Clock.Now());
        if (e.Source is AlertSource.Item or AlertSource.Expiry && repo.Item(e.RecordId) is { } i) repo.Upsert(Alerts.QuietDemote(i, until));
        if (e.Source == AlertSource.Call && repo.Call(e.RecordId) is { } c) repo.Upsert(c with { AlertType = "N", SnoozedUntil = until });
        Sounds.Stop();
    }

    public void Done(AlertEvent e)
    {
        var repo = _s.Repo;
        if (e.Source is AlertSource.Item or AlertSource.Expiry && repo.Item(e.RecordId) is { } i && !i.Done)
        {
            if (i.Tab == Tab.SHOP && repo.Settings.ShopCheckoutCalc) { App.RequestCheckout(i); }
            else repo.Upsert(Engine.Complete(i, Clock.Now()).Updated);
        }
        if (e.Source == AlertSource.Call && repo.Call(e.RecordId) is { } c && !c.Done) repo.Upsert(Engine.CompleteCall(c, Clock.Now()));
        Sounds.Stop();
    }

    public void PauseFor(TimeSpan span) { _s.Repo.Prefs.PauseAlertsUntil = Clock.Now() + (long)span.TotalMilliseconds; _s.Repo.SavePrefs(); Sounds.Stop(); }
    public void Resume() { _s.Repo.Prefs.PauseAlertsUntil = null; _s.Repo.SavePrefs(); }

    public void Dispose() { _timer.Stop(); Sounds.Dispose(); }
}
