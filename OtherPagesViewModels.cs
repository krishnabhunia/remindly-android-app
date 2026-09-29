using System.Collections.ObjectModel;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Remindly.App.Services;
using Remindly.Core.Logic;
using Remindly.Core.Models;

namespace Remindly.App.ViewModels;

// ═══════════════════════════════ Scheduled alerts (N42) ═══════════════════════════════

public sealed partial class SchedRowViewModel : ObservableObject
{
    private readonly ScheduledViewModel _list;
    public AlertEvent Ev { get; }
    public string When { get; }
    public string Title => Ev.Title;
    public string Source { get; }
    public string Glyph => Alerts.AlertTypeIcon(Ev.Types.Length == 0 ? "OFF" : Ev.Types);
    public string TypeLabel => Alerts.AlertTypeLabel(Ev.Types.Length == 0 ? "OFF" : Ev.Types);
    public bool IsPast { get; }
    public string Flag { get; }
    public bool HasFlag => Flag.Length > 0;
    public bool IsItem => Ev.Source is AlertSource.Item or AlertSource.Expiry;
    public SchedRowViewModel(ScheduledViewModel list, AlertEvent ev, AppServices s, bool alertsOnPc)
    {
        _list = list; Ev = ev;
        When = s.Relative(ev.FireAt);
        IsPast = ev.FireAt < Clock.Now();
        Source = ev.Source switch { AlertSource.Call => "Calls", AlertSource.Expiry => "Buy · expiry", _ => (ev.Tab?.Label() ?? "") } + " · " + ev.Subtitle;
        Flag = !alertsOnPc ? "alerts off on this PC" : "";
    }
    [RelayCommand] private void Open() => _list.Open(this);
    [RelayCommand] private void Mute() => _list.Mute(this);
    [RelayCommand] private void DeleteItem() => _list.DeleteRecord(this);
}

public sealed partial class ScheduledViewModel : ObservableObject
{
    private readonly AppServices _s;
    private readonly MainViewModel _main;
    private readonly DispatcherTimer _debounce;
    public ObservableCollection<SchedRowViewModel> Rows { get; } = new();
    [ObservableProperty] private string _horizon = "7D";   // 24H | 7D | ALL
    [ObservableProperty] private string _summary = "";
    [ObservableProperty] private bool _isEmpty;
    [ObservableProperty] private string _pcNote = "";

    public ScheduledViewModel(AppServices s, MainViewModel main)
    {
        _s = s; _main = main;
        _debounce = new DispatcherTimer(DispatcherPriority.Background, s.Dispatcher) { Interval = TimeSpan.FromMilliseconds(150) };
        _debounce.Tick += (_, _) => { _debounce.Stop(); Rebuild(); };
        s.Repo.Changed += _ => _debounce.Start();
        Rebuild();
    }
    partial void OnHorizonChanged(string value) => Rebuild();
    [RelayCommand] private void SetHorizon(string h) => Horizon = h;

    public void Rebuild()
    {
        Rows.Clear();
        var now = Clock.Now();
        long? h = Horizon switch { "24H" => 24L * 3600_000, "7D" => 7L * 86_400_000, _ => null };
        var rows = _s.Alerts.Upcoming(now, h);
        var on = _s.Repo.Prefs.AlertsOnThisPc && !_s.Repo.Prefs.AlertsPaused(now);
        foreach (var r in rows) Rows.Add(new SchedRowViewModel(this, r, _s, on));
        var next = rows.FirstOrDefault(r => r.FireAt >= now);
        Summary = $"{rows.Count} trigger(s) armed" + (next != null ? " · next " + _s.Relative(next.FireAt) : "");
        IsEmpty = rows.Count == 0;
        PcNote = on ? "These will ring on this PC and on your phone." : "Alerts are off (or paused) on this PC — the phone still rings.";
    }

    public void Open(SchedRowViewModel row)
    {
        if (row.IsItem) { var it = _s.Repo.Item(row.Ev.RecordId); if (it != null) { _main.NavigateTo(it.Tab switch { Tab.SHOP => "buy", Tab.LEARN => "learn", _ => "tasks" }); _main.OpenItemEditor(it, it.Tab); } }
        else { var c = _s.Repo.Call(row.Ev.RecordId); if (c != null) { _main.NavigateTo("calls"); _main.OpenCallEditor(c); } }
    }

    /// <summary>"Delete this alert" (N44) — the trigger goes, the record stays: item muted / snooze dropped / return cancelled.</summary>
    public void Mute(SchedRowViewModel row)
    {
        if (row.IsItem)
        {
            var it = _s.Repo.Item(row.Ev.RecordId); if (it == null) return;
            if (row.Ev.Subtitle == "Snoozed") { _s.Repo.Upsert(Engine.CancelSnooze(it)); _main.Ack("Snooze removed — the original due re-arms", () => _s.Repo.Upsert(it)); return; }
            if (row.Ev.Source == AlertSource.Expiry) { _s.Repo.Upsert(it with { ExpiryAt = null }); _main.Ack("Expiry alert removed", () => _s.Repo.Upsert(it)); return; }
            var prev = it.AlertType;
            _s.Repo.Upsert(Engine.Mute(it));
            _main.Ack("Alert deleted — the item stays, muted 🔕", () => { var cur = _s.Repo.Item(it.Id); if (cur != null) _s.Repo.Upsert(Engine.Unmute(cur, prev)); });
        }
        else
        {
            var c = _s.Repo.Call(row.Ev.RecordId); if (c == null) return;
            _s.Repo.Upsert(c with { SnoozedUntil = null, NagAt = null, NagFired = true, RecurAt = c.RepeatMode != "OFF" ? null : c.RecurAt, RepeatMode = "OFF" });
            _main.Ack("Call alert deleted — the reminder stays", () => _s.Repo.Upsert(c));
        }
    }

    /// <summary>"Delete the item — and every future alert" (N44).</summary>
    public void DeleteRecord(SchedRowViewModel row)
    {
        var count = Rows.Count(r => r.Ev.Source == row.Ev.Source && r.Ev.RecordId == row.Ev.RecordId);
        if (!Dialogs.Confirm($"Delete \"{row.Title}\" and every future alert ({count} armed)? It goes to Recently deleted; nothing stays armed.", "Delete item", destructive: true)) return;
        if (row.IsItem) { var it = _s.Repo.Item(row.Ev.RecordId); if (it != null) { _s.Repo.Upsert(Engine.SoftDelete(it, Clock.Now())); _main.Ack("Deleted + all alerts", () => { var cur = _s.Repo.Item(it.Id); if (cur != null) _s.Repo.Upsert(Engine.Restore(cur)); }); } }
        else { var c = _s.Repo.Call(row.Ev.RecordId); if (c != null) { _s.Repo.Upsert(c with { DeletedAt = Clock.Now() }); _main.Ack("Deleted + all alerts", () => { var cur = _s.Repo.Call(c.Id); if (cur != null) _s.Repo.Upsert(cur with { DeletedAt = null }); }); } }
    }
}

// ═══════════════════════════════ Recently deleted (Bin) ═══════════════════════════════

public sealed partial class BinRowViewModel : ObservableObject
{
    private readonly BinViewModel _list;
    public Item? Item { get; }
    public CallReminder? Call { get; }
    public string Title => Item?.Title ?? Call?.Display ?? "";
    public string Kind { get; }
    public string DeletedText { get; }
    public string Expires { get; }
    public BinRowViewModel(BinViewModel list, Item? item, CallReminder? call, AppServices s)
    {
        _list = list; Item = item; Call = call;
        var del = item?.DeletedAt ?? call?.DeletedAt ?? 0;
        Kind = item != null ? item.Tab.Label() : "Call";
        DeletedText = "deleted " + s.Relative(del);
        var left = Math.Max(0, (del + Constants.BinKeepMs - Clock.Now()) / 86_400_000L);
        Expires = left == 0 ? "purges today" : $"purges in {left} day(s)";
    }
    [RelayCommand] private void Restore() => _list.Restore(this);
    [RelayCommand] private void Forever() => _list.Forever(this);
}

public sealed partial class BinViewModel : ObservableObject
{
    private readonly AppServices _s;
    private readonly MainViewModel _main;
    public ObservableCollection<BinRowViewModel> Rows { get; } = new();
    [ObservableProperty] private string _summary = "";
    [ObservableProperty] private bool _isEmpty;

    public BinViewModel(AppServices s, MainViewModel main)
    {
        _s = s; _main = main;
        s.Repo.Changed += c => { if (c is "items" or "calls") Rebuild(); };
        Rebuild();
    }

    public void Rebuild()
    {
        Rows.Clear();
        var items = _s.Repo.Items.Where(i => i.DeletedAt != null).Select(i => new BinRowViewModel(this, i, null, _s));
        var calls = _s.Repo.Calls.Where(c => c.DeletedAt != null).Select(c => new BinRowViewModel(this, null, c, _s));
        foreach (var r in items.Concat(calls).OrderByDescending(r => r.Item?.DeletedAt ?? r.Call?.DeletedAt)) Rows.Add(r);
        Summary = $"{Rows.Count} in the bin · kept 30 days";
        IsEmpty = Rows.Count == 0;
    }

    public void Restore(BinRowViewModel r)
    {
        if (r.Item != null) _s.Repo.Upsert(Engine.Restore(r.Item));
        if (r.Call != null) _s.Repo.Upsert(r.Call with { DeletedAt = null });
        _main.Ack("Restored", null);
    }

    public void Forever(BinRowViewModel r)
    {
        if (!Dialogs.Confirm($"Delete \"{r.Title}\" forever? This cannot be undone.", destructive: true)) return;
        if (r.Item != null) _s.Repo.DeleteForever(r.Item);
        if (r.Call != null) _s.Repo.DeleteForever(r.Call);
    }

    [RelayCommand] private void EmptyBin()
    {
        if (Rows.Count == 0 || !Dialogs.Confirm($"Delete all {Rows.Count} item(s) forever?", destructive: true)) return;
        foreach (var r in Rows.ToList()) { if (r.Item != null) _s.Repo.DeleteForever(r.Item); if (r.Call != null) _s.Repo.DeleteForever(r.Call); }
    }
}
