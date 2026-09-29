using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Remindly.App.Services;
using Remindly.Core.Logic;
using Remindly.Core.Models;

namespace Remindly.App.ViewModels;

public sealed partial class CallRowViewModel : ObservableObject
{
    private readonly CallsViewModel _list;
    public CallReminder Call { get; }
    public long Id => Call.Id;
    public string Display => Call.Display;
    public string Number => Call.Number;
    public string SourceLabel => Call.Source.Label();
    public bool IsAuto => Call.Source == CallSource.AUTO;
    public string Subtitle { get; }
    public string Note => Call.Note ?? "";
    public bool HasNote => !string.IsNullOrWhiteSpace(Call.Note);
    public bool IsDone => Call.Done;
    public bool IsOverdue { get; }
    public string AlertGlyph => Alerts.AlertTypeIcon(Call.AlertType);
    public bool HasWhatsApp => Call.Message != null;
    public string ComingUp { get; }
    public bool HasComingUp => ComingUp.Length > 0;

    public CallRowViewModel(CallsViewModel list, CallReminder c, AppServices s)
    {
        _list = list; Call = c;
        var now = Clock.Now();
        var parts = new List<string>();
        if (c.LastMissedAt is long m) parts.Add("missed " + s.Relative(m));
        if (c.MissedCount > 1) parts.Add($"×{c.MissedCount}");
        if (c.RepeatMode != "OFF") parts.Add("↻ " + Recurrence.RepeatLabel(c.RepeatMode, c.RepeatDays, c.RepeatN, c.RepeatUnit, c.RepeatOrd, c.RepeatDow, c.RepeatOrdList) + (c.RecurAt is long r ? " · next " + s.Relative(r) : ""));
        if (!string.IsNullOrWhiteSpace(c.Label)) parts.Add("#" + c.Label);
        if (!string.IsNullOrWhiteSpace(c.Company)) parts.Add(c.Company!);
        if (c.Done && c.DoneAt is long d) parts.Add("done " + s.Relative(d));
        if (!string.IsNullOrWhiteSpace(c.ClearedNote)) parts.Add("“" + c.ClearedNote + "”");
        Subtitle = string.Join("  ·  ", parts);
        IsOverdue = !c.Done && Alerts.IsOverdueDay(Grouping.CallBasis(c, false), now);
        var snooze = Alerts.LiveSnooze(c.SnoozedUntil, now);
        ComingUp = snooze is long sn ? "Snoozed · " + s.Relative(sn) : c.NagAt is long nag && !c.NagFired && nag > now ? "Nag · " + s.Relative(nag) : "";
    }

    [RelayCommand] private void Toggle() => _list.ToggleDone(this);
    [RelayCommand] private void Edit() => _list.Edit(this);
    [RelayCommand] private void Delete() => _list.Delete(this);
    [RelayCommand] private void Snooze() => _list.Snooze(this);
    [RelayCommand] private void CallNow() => Dialogs.OpenUrl("tel:" + Call.Number.Replace(" ", ""));
    [RelayCommand] private void WhatsApp() => _list.WhatsApp(this);
    [RelayCommand] private void CopyNumber() => Dialogs.CopyToClipboard(Call.Number);
}

public sealed partial class CallGroupViewModel : ObservableObject
{
    public string Label { get; init; } = "";
    [ObservableProperty] private bool _isExpanded = true;
    public ObservableCollection<CallRowViewModel> Items { get; } = new();
    public string CountLabel => Items.Count.ToString();
}

/// <summary>
/// Calls tab. Windows cannot read a phone's call log, so this is the phone's list mirrored live plus
/// manual call reminders you add here; Call/WhatsApp buttons hand off to Phone Link / wa.me.
/// </summary>
public sealed partial class CallsViewModel : ObservableObject
{
    private readonly AppServices _s;
    private readonly MainViewModel _main;
    private readonly DispatcherTimer _debounce;
    [ObservableProperty] private bool _showDone;
    [ObservableProperty] private string _search = "";
    [ObservableProperty] private string _summary = "";
    [ObservableProperty] private bool _isEmpty;
    public ObservableCollection<CallGroupViewModel> Groups { get; } = new();

    public CallsViewModel(AppServices s, MainViewModel main)
    {
        _s = s; _main = main;
        _debounce = new DispatcherTimer(DispatcherPriority.Background, s.Dispatcher) { Interval = TimeSpan.FromMilliseconds(120) };
        _debounce.Tick += (_, _) => { _debounce.Stop(); Rebuild(); };
        s.Repo.Changed += c => { if (c is "calls" or "settings") _debounce.Start(); };
        Rebuild();
    }

    partial void OnShowDoneChanged(bool value) => Rebuild();
    partial void OnSearchChanged(string value) => _debounce.Start();
    [RelayCommand] private void ShowActive() => ShowDone = false;
    [RelayCommand] private void ShowDoneList() => ShowDone = true;
    [RelayCommand] private void Add() => _main.OpenCallEditor(null);

    public void Rebuild()
    {
        var list = _s.Repo.Calls.Where(c => c.DeletedAt == null && c.Done == ShowDone).ToList();
        var q = Search.Trim();
        if (q.Length > 0) list = list.Where(c => c.Display.Contains(q, StringComparison.OrdinalIgnoreCase) || c.Number.Contains(q) || (c.Note?.Contains(q, StringComparison.OrdinalIgnoreCase) ?? false)).ToList();
        Groups.Clear();
        var today = Time.LocalDate(Clock.Now());
        foreach (var y in Grouping.BuildYearGroups(list, true, c => c.Id, c => Grouping.CallBasis(c, ShowDone)))
            foreach (var m in y.Months) foreach (var d in m.Days)
            {
                var date = DateOnly.ParseExact(d.Key, "yyyy-MM-dd");
                var g = new CallGroupViewModel { Label = (date == today ? "Today · " : date == today.AddDays(-1) ? "Yesterday · " : "") + d.Label + (date.Year != today.Year ? " " + date.Year : "") };
                foreach (var c in d.Items) g.Items.Add(new CallRowViewModel(this, c, _s));
                Groups.Add(g);
            }
        Summary = ShowDone ? $"{list.Count} done" : $"{list.Count} to call back";
        IsEmpty = list.Count == 0;
    }

    public void Edit(CallRowViewModel row) => _main.OpenCallEditor(_s.Repo.Call(row.Id));

    public void ToggleDone(CallRowViewModel row)
    {
        var c = _s.Repo.Call(row.Id); if (c == null) return;
        if (c.Done) { _s.Repo.Upsert(Engine.UndoCall(c)); _main.Ack("Back on the Calls list", () => _s.Repo.Upsert(c)); return; }
        var done = Engine.CompleteCall(c, Clock.Now());
        _s.Repo.Upsert(done);
        _main.Ack(done.RecurAt is long r && done.RepeatMode != "OFF" ? "Done · returns " + _s.DayTime(r) : "Done", () => _s.Repo.Upsert(c));
    }

    public void Snooze(CallRowViewModel row)
    {
        var c = _s.Repo.Call(row.Id); if (c == null) return;
        var until = Alerts.SnoozeTargetMs(_s.Repo.Settings, Clock.Now());
        _s.Repo.Upsert(Engine.SnoozeCall(c, until));
        _main.Ack(Alerts.SnoozeToast(Alerts.SnoozeMinutes(_s.Repo.Settings), until, _s.Time(until)), () => _s.Repo.Upsert(c));
    }

    public void Delete(CallRowViewModel row)
    {
        var c = _s.Repo.Call(row.Id); if (c == null) return;
        _s.Repo.Upsert(c with { DeletedAt = Clock.Now() });
        _main.Ack("Moved to Recently deleted", () => { var cur = _s.Repo.Call(c.Id); if (cur != null) _s.Repo.Upsert(cur with { DeletedAt = null }); });
    }

    public void WhatsApp(CallRowViewModel row)
    {
        var c = row.Call;
        var digits = new string(c.Number.Where(char.IsDigit).ToArray());
        if (digits.Length <= 10) digits = _s.Repo.Settings.DefaultCountryCode + digits;
        var url = $"https://wa.me/{digits}" + (c.Message != null ? "?text=" + Uri.EscapeDataString(c.Message) : "");
        Dialogs.OpenUrl(url);
    }
}

/// <summary>Manual call reminder editor (Calls.kt note-and-repeat sheet).</summary>
public sealed partial class CallEditorViewModel : ObservableObject
{
    private readonly AppServices _s;
    private readonly MainViewModel _main;
    private readonly CallReminder? _snapshot;
    public bool IsNew => _snapshot == null;
    public string Heading => IsNew ? "New call reminder" : "Edit call reminder";
    public bool IsAuto => _snapshot?.Source == CallSource.AUTO;
    [ObservableProperty] private string _number = "";
    [ObservableProperty] private string _name = "";
    [ObservableProperty] private string _company = "";
    [ObservableProperty] private string _note = "";
    [ObservableProperty] private string _label = "";
    [ObservableProperty] private string _message = "";
    [ObservableProperty] private bool _hasMessage;
    [ObservableProperty] private string _alertType = "N";
    [ObservableProperty] private bool _repeat;
    [ObservableProperty] private string _repeatMode = "WEEKLY";
    [ObservableProperty] private DateTime? _date = DateTime.Today;
    [ObservableProperty] private string _timeText = "18:00";
    [ObservableProperty] private string _previewText = "";
    [ObservableProperty] private string _error = "";
    public ObservableCollection<ToggleChip> Weekdays { get; } = new();
    public List<KeyValuePair<string, string>> RepeatModeOptions { get; } = new() { new("DAILY", "Daily"), new("WEEKLY", "Weekly"), new("MONTHLY_DAY", "Monthly (same date)"), new("QUARTERLY", "Quarterly"), new("YEARLY", "Yearly") };
    public bool IsWeekly => RepeatMode == "WEEKLY";

    public CallEditorViewModel(AppServices s, MainViewModel main, CallReminder? c)
    {
        _s = s; _main = main; _snapshot = c;
        string[] dn = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };
        for (var d = 1; d <= 7; d++) Weekdays.Add(new ToggleChip { Label = dn[d - 1], Value = d, Changed = UpdatePreview });
        if (c != null)
        {
            Number = c.Number; Name = c.Name ?? Calls.FullName(c.FirstName, c.LastName) ?? ""; Company = c.Company ?? ""; Note = c.Note ?? ""; Label = c.Label ?? "";
            Message = c.Message ?? ""; HasMessage = c.Message != null; AlertType = c.AlertType;
            Repeat = c.RepeatMode != "OFF"; if (Repeat) RepeatMode = c.RepeatMode;
            foreach (var w in Weekdays) w.IsOn = c.RepeatDays.Contains(w.Value);
            var anchor = c.RecurAt ?? c.NagAt ?? c.LastMissedAt ?? c.CreatedAt;
            var l = Time.ToLocal(anchor); Date = l.Date; TimeText = l.ToString("HH:mm");
        }
        else
        {
            var st = s.Repo.Settings;
            var days = st.CallsNewDueMode switch { "TOMORROW" => 1, "NDAYS" => st.CallsNewDueDays, _ => 0 };
            Date = DateTime.Today.AddDays(days);
            TimeText = $"{st.CallReminderMinutes / 60 % 24:00}:{st.CallReminderMinutes % 60:00}";
            Weekdays[Math.Clamp(Recurrence.IsoDow(DateTime.Today) - 1, 0, 6)].IsOn = true;
        }
        UpdatePreview();
    }

    partial void OnRepeatModeChanged(string value) { OnPropertyChanged(nameof(IsWeekly)); UpdatePreview(); }
    partial void OnRepeatChanged(bool value) => UpdatePreview();
    partial void OnDateChanged(DateTime? value) => UpdatePreview();
    partial void OnTimeTextChanged(string value) => UpdatePreview();
    [RelayCommand] private void SetAlert(string t) => AlertType = t;

    private long? Anchor()
    {
        if (Date is not DateTime d) return null;
        if (!TimeSpan.TryParseExact(TimeText.Trim(), new[] { @"h\:mm", @"hh\:mm" }, CultureInfo.InvariantCulture, out var ts) && !(DateTime.TryParse(TimeText.Trim(), CultureInfo.InvariantCulture, DateTimeStyles.NoCurrentDateDefault, out var dt) && (ts = dt.TimeOfDay) >= TimeSpan.Zero)) return null;
        return Time.AtMinuteOfDay(DateOnly.FromDateTime(d), (int)ts.TotalMinutes);
    }

    private void UpdatePreview()
    {
        if (!Repeat || Anchor() is not long a) { PreviewText = ""; return; }
        var days = Weekdays.Where(w => w.IsOn).Select(w => w.Value).ToList();
        var list = Recurrence.Preview(RepeatMode, days, 1, "D", 1, 1, Array.Empty<int>(), a, 3, a);
        PreviewText = list.Count == 0 ? "" : "Next: " + string.Join("  ·  ", list.Select(_s.DayTime));
    }

    [RelayCommand] private void Cancel() => _main.CloseEditorCommand.Execute(null);

    [RelayCommand] private void Delete()
    {
        if (_snapshot == null) return;
        var live = _s.Repo.Call(_snapshot.Id) ?? _snapshot;
        _s.Repo.Upsert(live with { DeletedAt = Clock.Now() });
        _main.Ack("Moved to Recently deleted", () => { var cur = _s.Repo.Call(live.Id); if (cur != null) _s.Repo.Upsert(cur with { DeletedAt = null }); });
        _main.CloseEditorCommand.Execute(null);
    }

    [RelayCommand] private void Save()
    {
        Error = "";
        var number = Number.Trim();
        if (Calls.NormalizePhone(number).Length < 6) { Error = "Enter a phone number."; return; }
        var anchor = Anchor();
        if (anchor == null) { Error = "Pick a date and a time like 18:00."; return; }
        var live = _snapshot != null ? (_s.Repo.Call(_snapshot.Id) ?? _snapshot) : new CallReminder { Id = Ids.Next(), Source = CallSource.MANUAL, LastMissedAt = null };
        var days = Weekdays.Where(w => w.IsOn).Select(w => w.Value).ToList();
        if (Repeat && RepeatMode == "WEEKLY" && days.Count == 0) { Error = "Pick at least one weekday."; return; }
        var c = live with
        {
            Number = number, Name = string.IsNullOrWhiteSpace(Name) ? null : Name.Trim(), Company = string.IsNullOrWhiteSpace(Company) ? null : Company.Trim(),
            Note = string.IsNullOrWhiteSpace(Note) ? null : Note.Trim(), Label = string.IsNullOrWhiteSpace(Label) ? null : Label.Trim(),
            Message = HasMessage ? Message : null, AlertType = AlertType,
            RepeatMode = Repeat ? RepeatMode : "OFF", RepeatDays = Repeat && RepeatMode == "WEEKLY" ? days : new List<int>(),
        };
        if (Repeat)
        {
            var first = Recurrence.Preview(RepeatMode, days, 1, "D", 1, 1, Array.Empty<int>(), anchor.Value, 1, anchor.Value, 0, Math.Min(anchor.Value, Clock.Now()) - 1).FirstOrDefault();
            c = c with { RecurAt = first > 0 ? first : anchor, NagAt = null, NagFired = false };
        }
        else c = c with { RecurAt = null, NagAt = anchor, NagFired = false };
        if (live.NagAt != c.NagAt || live.RecurAt != c.RecurAt) c = c with { SnoozedUntil = null };
        _s.Repo.Upsert(c);
        _main.Ack(IsNew ? "Added" : "Saved", null);
        _main.CloseEditorCommand.Execute(null);
    }
}
