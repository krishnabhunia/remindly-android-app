using System.Collections.ObjectModel;
using System.Globalization;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Remindly.App.Services;
using Remindly.Core.Logic;
using Remindly.Core.Models;

namespace Remindly.App.ViewModels;

public sealed partial class ToggleChip : ObservableObject
{
    public string Label { get; init; } = "";
    public int Value { get; init; }
    [ObservableProperty] private bool _isOn;
    public Action? Changed { get; init; }
    partial void OnIsOnChanged(bool value) => Changed?.Invoke();
}

/// <summary>
/// The item editor (right pane). Mirrors the phone's editor sheet: schedule kind radio
/// (No Reminders / One-time / Repeat), repeat pattern + preview, Reminder Type, tab extras, and
/// the N16 save rule — edits are applied on top of the LIVE record, never the open-time snapshot.
/// </summary>
public sealed partial class ItemEditorViewModel : ObservableObject
{
    private readonly AppServices _s;
    private readonly MainViewModel _main;
    private readonly Item? _snapshot;
    public long ItemId => _snapshot?.Id ?? 0;
    public Tab Tab { get; }
    public bool IsNew => _snapshot == null;
    public string Heading => (IsNew ? "New " : "Edit ") + (Tab == Tab.SHOP ? "buy item" : Tab == Tab.LEARN ? "learning item" : "task");
    public bool IsShop => Tab == Tab.SHOP;
    public bool IsLearn => Tab == Tab.LEARN;
    public bool IsTasks => Tab == Tab.TASKS;
    public bool CanBeNone => Tab != Tab.SHOP;   // D1: a purchase always has a buy plan

    // common
    [ObservableProperty] private string _title = "";
    [ObservableProperty] private string _notes = "";
    [ObservableProperty] private string _group = "";
    [ObservableProperty] private string _priorityKey = "";        // "" | LOW | MEDIUM | HIGH | URGENT
    [ObservableProperty] private string _alertType = "N";          // N | R | A | OFF
    [ObservableProperty] private string _schedKind = "ONCE";       // NONE | ONCE | REPEAT
    [ObservableProperty] private DateTime? _date;
    [ObservableProperty] private bool _hasTime = true;
    [ObservableProperty] private string _timeText = "18:00";
    [ObservableProperty] private string _repeatMode = "DAILY";
    [ObservableProperty] private string _monthDaysText = "";
    [ObservableProperty] private int _repeatN = 1;
    [ObservableProperty] private string _repeatUnit = "D";
    [ObservableProperty] private string _repeatCountText = "";
    [ObservableProperty] private string _previewText = "";
    [ObservableProperty] private string _comingUpText = "";
    [ObservableProperty] private string _missedText = "";
    [ObservableProperty] private bool _personal;
    // learn
    [ObservableProperty] private string _platform = "";
    [ObservableProperty] private string _url = "";
    [ObservableProperty] private int _progress;
    [ObservableProperty] private string _hoursText = "";
    // shop
    [ObservableProperty] private string _shopName = "";
    [ObservableProperty] private string _quantity = "";
    [ObservableProperty] private string _unit = "";
    [ObservableProperty] private string _price = "";
    [ObservableProperty] private bool _staple;
    [ObservableProperty] private string _lapseText = "";
    [ObservableProperty] private string _lapseUnitKey = "DAYS";
    [ObservableProperty] private DateTime? _expiry;
    [ObservableProperty] private long? _productId;
    [ObservableProperty] private string _productLinkText = "";
    [ObservableProperty] private string _cheapestHint = "";
    [ObservableProperty] private string _lastPriceHint = "";
    [ObservableProperty] private string _error = "";
    public ObservableCollection<Product> ProductMatches { get; } = new();
    [ObservableProperty] private bool _showProductMatches;

    public ObservableCollection<ToggleChip> Weekdays { get; } = new();
    public ObservableCollection<ToggleChip> OrdChips { get; } = new();
    public List<string> GroupOptions { get; }
    public List<string> ShopOptions { get; }
    public string[] Platforms => Constants.PlatformOptions;
    public string[] Units => Constants.Units;
    public string[] RepeatModes { get; } = { "DAILY", "WEEKLY", "MONTHLY_DAY", "MONTHLY_ORD", "QUARTERLY", "HALFYEARLY", "YEARLY", "EVERY_N", "SPACED" };
    public string GroupLabel => Tab == Tab.LEARN ? "Topic" : "Group";
    public string SnoozeLabel => Alerts.SnoozeMinLabel(Alerts.SnoozeMinutes(_s.Repo.Settings));
    public bool IsMuted => AlertType == Constants.AlertMuted;
    public bool IsWeekly => RepeatMode == "WEEKLY";
    public bool IsMonthlyDay => RepeatMode == "MONTHLY_DAY";
    public bool IsMonthlyOrd => RepeatMode == "MONTHLY_ORD";
    public bool IsEveryN => RepeatMode == "EVERY_N";
    public bool IsRepeat => SchedKind == "REPEAT";
    public bool IsNone => SchedKind == "NONE";
    public bool HasSchedule => SchedKind != "NONE";
    public bool HasComingUp => ComingUpText.Length > 0;
    public bool HasMissed => MissedText.Length > 0;
    public bool HasCheapest => CheapestHint.Length > 0;
    public bool HasLastPrice => LastPriceHint.Length > 0;
    public bool HasProductLink => ProductId != null;
    public string RepeatModeLabel(string m) => m switch { "DAILY" => "Daily", "WEEKLY" => "Weekly", "MONTHLY_DAY" => "Monthly (dates)", "MONTHLY_ORD" => "Monthly (weekday)", "QUARTERLY" => "Quarterly", "HALFYEARLY" => "Half-yearly", "YEARLY" => "Yearly", "EVERY_N" => "Every N", "SPACED" => "Spaced (3·7·14·30 days)", _ => m };
    public List<KeyValuePair<string, string>> RepeatModeOptions => RepeatModes.Select(m => new KeyValuePair<string, string>(m, RepeatModeLabel(m))).ToList();
    public List<KeyValuePair<string, string>> PriorityOptions { get; } = new() { new("", "No priority"), new("LOW", "Low"), new("MEDIUM", "Medium"), new("HIGH", "High"), new("URGENT", "Urgent") };
    public List<KeyValuePair<string, string>> UnitOptions { get; } = new() { new("D", "day(s)"), new("W", "week(s)"), new("M", "month(s)") };
    public List<KeyValuePair<string, string>> LapseUnitOptions { get; } = new() { new("DAYS", "days"), new("MONTHS", "months") };

    public ItemEditorViewModel(AppServices s, MainViewModel main, Item? item, Tab tab)
    {
        _s = s; _main = main; _snapshot = item; Tab = item?.Tab ?? tab;
        var st = s.Repo.Settings;
        var used = s.Repo.Items.Where(i => i.Tab == Tab && i.DeletedAt == null).Select(i => Tab == Tab.LEARN ? i.Topic : i.Group).Where(g => !string.IsNullOrWhiteSpace(g)).Select(g => g!);
        var configured = Tab == Tab.LEARN ? st.LearnTopics : Tab == Tab.SHOP ? st.ShopGroups : st.TasksGroups;
        GroupOptions = configured.Concat(used).Distinct(StringComparer.OrdinalIgnoreCase).OrderBy(x => x).ToList();
        ShopOptions = s.Repo.ActiveShops.Select(x => x.Name).ToList();
        string[] dn = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };
        for (var d = 1; d <= 7; d++) Weekdays.Add(new ToggleChip { Label = dn[d - 1], Value = d, Changed = UpdatePreview });
        string[] ords = { "First", "Second", "Third", "Fourth", "Last" };
        for (var o = 1; o <= 5; o++) for (var d = 1; d <= 7; d++) OrdChips.Add(new ToggleChip { Label = $"{ords[o - 1]} {dn[d - 1]}", Value = o * 10 + d, Changed = UpdatePreview });

        if (item != null)
        {
            Title = item.Title; Notes = item.Notes; Group = (Tab == Tab.LEARN ? item.Topic : item.Group) ?? "";
            PriorityKey = item.Priority?.ToString() ?? ""; AlertType = item.AlertType; Personal = item.Personal;
            SchedKind = Alerts.KindOf(item).ToString();
            if (item.DueAt is long due) { var l = Time.ToLocal(due); Date = l.Date; TimeText = l.ToString("HH:mm"); HasTime = item.DueHasTime; }
            else { Date = null; HasTime = Alerts.NewDueTimedFor(Tab, st); TimeText = MinutesToText(st.DefaultDueMinutes); }
            if (item.RepeatMode != "OFF") RepeatMode = item.RepeatMode;
            foreach (var c in Weekdays) c.IsOn = item.RepeatMode == "WEEKLY" && item.RepeatDays.Contains(c.Value);
            MonthDaysText = item.RepeatMode == "MONTHLY_DAY" ? string.Join(",", item.RepeatDays) : "";
            var pats = item.RepeatOrdList.Count > 0 ? item.RepeatOrdList : new List<int> { item.RepeatOrd * 10 + item.RepeatDow };
            foreach (var c in OrdChips) c.IsOn = item.RepeatMode == "MONTHLY_ORD" && pats.Contains(c.Value);
            RepeatN = item.RepeatN; RepeatUnit = item.RepeatUnit; RepeatCountText = item.RepeatCount?.ToString() ?? "";
            Platform = item.Platform ?? ""; Url = item.Url ?? ""; Progress = item.Progress; HoursText = item.HoursSpent > 0 ? item.HoursSpent.ToString("0.##", CultureInfo.InvariantCulture) : "";
            ShopName = (item.ShopId is long sid ? s.Repo.Shop(sid)?.Name : null) ?? item.ShopName ?? ""; Quantity = item.Quantity ?? ""; Unit = item.Unit ?? ""; Price = item.Price ?? ""; Staple = item.Staple;
            LapseText = item.LapseValue?.ToString() ?? ""; LapseUnitKey = item.LapseUnit?.ToString() ?? "DAYS";
            Expiry = item.ExpiryAt is long ex ? Time.ToLocal(ex).Date : null;
            ProductId = item.ProductId; ProductLinkText = s.Repo.Product(item.ProductId)?.Name ?? "";
            var lp = ShopMath.LastPrice(item);
            LastPriceHint = lp is double p ? $"Last: ₹{ShopMath.TrimNum(p)}" : "";
            MissedText = string.Join("\n", Recurrence.MissedRows(item).Select(m => "Missed · " + s.DayTime(m)));
            ComingUpText = string.Join("\n", Alerts.ComingUp(item, Clock.Now()).Select(r => r.What + (r.At is long a ? " · " + s.DayTime(a) : "")));
        }
        else
        {
            SchedKind = Alerts.SchedDefaultFor(st, Tab).ToString();
            var due = Alerts.DefaultNewDue(Tab, st);
            HasTime = Alerts.NewDueTimedFor(Tab, st);
            if (due is long d) { var l = Time.ToLocal(d); Date = l.Date; TimeText = l.ToString("HH:mm"); } else { Date = DateTime.Today; TimeText = MinutesToText(st.DefaultDueMinutes); }
            if (SchedKind == "REPEAT") RepeatMode = "DAILY";
            Weekdays[Math.Clamp(Recurrence.IsoDow(DateTime.Today) - 1, 0, 6)].IsOn = true;
            if (Tab == Tab.SHOP) { var def = s.Repo.DefaultShop; ShopName = def?.Name ?? ""; }
        }
        UpdatePreview();
        UpdateCheapest();
    }

    private static string MinutesToText(int mins) => $"{mins / 60 % 24:00}:{mins % 60:00}";

    partial void OnSchedKindChanged(string value) { OnPropertyChanged(nameof(IsRepeat)); OnPropertyChanged(nameof(IsNone)); OnPropertyChanged(nameof(HasSchedule)); UpdatePreview(); }
    partial void OnRepeatModeChanged(string value) { OnPropertyChanged(nameof(IsWeekly)); OnPropertyChanged(nameof(IsMonthlyDay)); OnPropertyChanged(nameof(IsMonthlyOrd)); OnPropertyChanged(nameof(IsEveryN)); UpdatePreview(); }
    partial void OnDateChanged(DateTime? value) => UpdatePreview();
    partial void OnTimeTextChanged(string value) => UpdatePreview();
    partial void OnHasTimeChanged(bool value) => UpdatePreview();
    partial void OnMonthDaysTextChanged(string value) => UpdatePreview();
    partial void OnRepeatNChanged(int value) => UpdatePreview();
    partial void OnRepeatUnitChanged(string value) => UpdatePreview();
    partial void OnAlertTypeChanged(string value) => OnPropertyChanged(nameof(IsMuted));
    partial void OnComingUpTextChanged(string value) => OnPropertyChanged(nameof(HasComingUp));
    partial void OnMissedTextChanged(string value) => OnPropertyChanged(nameof(HasMissed));
    partial void OnCheapestHintChanged(string value) => OnPropertyChanged(nameof(HasCheapest));
    partial void OnLastPriceHintChanged(string value) => OnPropertyChanged(nameof(HasLastPrice));
    partial void OnProductIdChanged(long? value) => OnPropertyChanged(nameof(HasProductLink));
    partial void OnTitleChanged(string value) { if (IsShop) { UpdateProductMatches(); UpdateCheapest(); } }
    partial void OnUnitChanged(string value) { if (IsShop) UpdateCheapest(); }

    [RelayCommand] private void SetSched(string kind) => SchedKind = kind;
    [RelayCommand] private void SetAlert(string t) => AlertType = t;
    [RelayCommand] private void SetPriority(string p) => PriorityKey = p;
    [RelayCommand] private void ToggleTime() => HasTime = !HasTime;
    [RelayCommand] private void PickToday() => Date = DateTime.Today;
    [RelayCommand] private void PickTomorrow() => Date = DateTime.Today.AddDays(1);
    [RelayCommand] private void PickNextWeek() => Date = DateTime.Today.AddDays(7);
    [RelayCommand] private void ClearDate() => Date = null;
    [RelayCommand] private void Unlink() { ProductId = null; ProductLinkText = ""; }
    [RelayCommand] private void PickProduct(Product p)
    {
        ProductId = p.Id; ProductLinkText = p.Name; Title = p.Name; ShowProductMatches = false;
        if (!string.IsNullOrWhiteSpace(p.DefaultUnit)) Unit = p.DefaultUnit!;
        var live = _s.Repo.ActiveShops.Select(x => x.Id).ToHashSet();
        var best = ShopMath.CheapestLink(_s.Repo.LinksFor(p.Id), live);
        if (best != null && _s.Repo.Shop(best.ShopId) is { } shop) ShopName = shop.Name;
        UpdateCheapest();
    }
    [RelayCommand] private void HideMatches() => ShowProductMatches = false;

    private void UpdateProductMatches()
    {
        ProductMatches.Clear();
        var q = Title.Trim();
        if (q.Length < 2 || ProductId != null) { ShowProductMatches = false; return; }
        foreach (var p in _s.Repo.ActiveProducts.Where(p => ShopMath.ProductMatches(p, q)).Take(6)) ProductMatches.Add(p);
        ShowProductMatches = ProductMatches.Count > 0 && !ProductMatches.Any(p => string.Equals(p.Name, q, StringComparison.OrdinalIgnoreCase) && ProductMatches.Count == 1 && ProductId == p.Id);
    }

    private void UpdateCheapest()
    {
        if (!IsShop || !_s.Repo.Settings.ShopCheapestHint) { CheapestHint = ""; return; }
        string hint = "";
        if (ProductId is long pid)
        {
            var live = _s.Repo.ActiveShops.Select(x => x.Id).ToHashSet();
            var best = ShopMath.CheapestLink(_s.Repo.LinksFor(pid), live);
            if (best != null && _s.Repo.Shop(best.ShopId) is { } shop)
                hint = best.LastUnitPrice > 0 ? $"Cheapest: {shop.Name} · ₹{ShopMath.FmtUnitPrice(best.LastUnitPrice)}/{_s.Repo.Product(pid)?.DefaultUnit ?? "unit"}" : $"Cheapest: {shop.Name} · ₹{ShopMath.TrimNum(best.LastPrice)}";
        }
        if (hint.Length == 0)
        {
            var rec = ShopMath.CheapestShop(_s.Repo.Items, Title, string.IsNullOrWhiteSpace(Unit) ? null : Unit);
            if (rec != null) hint = $"Cheapest so far: {rec.Shop} · ₹{ShopMath.FmtUnitPrice(rec.UnitPrice)}/{rec.Unit} ({Time.FormatDate(rec.At)})";
        }
        CheapestHint = hint;
    }

    private (long? dueAt, bool ok) ComputeDue()
    {
        if (Date is not DateTime d) return (null, true);
        var mins = HasTime && TimeSpan.TryParseExact(TimeText.Trim(), new[] { @"h\:mm", @"hh\:mm", @"h\:mm tt", @"hh\:mm tt" }, CultureInfo.InvariantCulture, out var ts) ? (int)ts.TotalMinutes
                 : HasTime && DateTime.TryParse(TimeText.Trim(), CultureInfo.InvariantCulture, DateTimeStyles.NoCurrentDateDefault, out var dt) ? dt.Hour * 60 + dt.Minute
                 : HasTime ? -1 : _s.Repo.Settings.DefaultDueMinutes;
        if (mins < 0) return (null, false);
        return (Time.AtMinuteOfDay(DateOnly.FromDateTime(d), mins), true);
    }

    private (List<int> days, List<int> ordList) PatternSets()
    {
        var days = RepeatMode == "WEEKLY" ? Weekdays.Where(c => c.IsOn).Select(c => c.Value).ToList()
                 : RepeatMode == "MONTHLY_DAY" ? MonthDaysText.Split(new[] { ',', ' ', ';' }, StringSplitOptions.RemoveEmptyEntries).Select(t => int.TryParse(t, out var v) ? v : 0).Where(v => v is >= 1 and <= 31).Distinct().OrderBy(v => v).ToList()
                 : new List<int>();
        var ord = RepeatMode == "MONTHLY_ORD" ? OrdChips.Where(c => c.IsOn).Select(c => c.Value).ToList() : new List<int>();
        return (days, ord);
    }

    private void UpdatePreview()
    {
        if (SchedKind != "REPEAT") { PreviewText = ""; return; }
        var (due, ok) = ComputeDue();
        if (!ok || due is not long anchor) { PreviewText = "Pick a start date and time."; return; }
        var (days, ord) = PatternSets();
        var list = Recurrence.Preview(RepeatMode, days, Math.Max(1, RepeatN), RepeatUnit, ord.FirstOrDefault() / 10 is var o && o > 0 ? o : 1, ord.FirstOrDefault() % 10 is var w && w > 0 ? w : 1, ord, anchor, 3, anchor);
        PreviewText = list.Count == 0 ? "No future occurrence." : "Next: " + string.Join("  ·  ", list.Select(_s.DayTime));
    }

    [RelayCommand] private void Cancel() => _main.CloseEditorCommand.Execute(null);

    [RelayCommand] private void Delete()
    {
        if (_snapshot == null) return;
        var live = _s.Repo.Item(_snapshot.Id) ?? _snapshot;
        _s.Repo.Upsert(Engine.SoftDelete(live, Clock.Now()));
        _main.Ack("Moved to Recently deleted", () => { var cur = _s.Repo.Item(live.Id); if (cur != null) _s.Repo.Upsert(Engine.Restore(cur)); });
        _main.CloseEditorCommand.Execute(null);
    }

    [RelayCommand] private void Save()
    {
        Error = "";
        var title = Title.Trim();
        if (title.Length == 0) { Error = "Give it a name."; return; }
        var (due, ok) = ComputeDue();
        if (!ok) { Error = "Time should look like 18:30 or 6:30 PM."; return; }
        if (SchedKind != "NONE" && due == null && Tab == Tab.SHOP) { Error = "A buy item needs a date (or set Repeat)."; return; }
        // N16: base = the LIVE record, never the open-time snapshot.
        var live = _snapshot != null ? (_s.Repo.Item(_snapshot.Id) ?? _snapshot) : new Item { Id = Ids.Next(), Tab = Tab };
        var (days, ord) = PatternSets();
        var item = live with
        {
            Title = title, Notes = Notes.Trim(),
            Group = Tab == Tab.LEARN ? live.Group : (string.IsNullOrWhiteSpace(Group) ? null : Group.Trim()),
            Topic = Tab == Tab.LEARN ? (string.IsNullOrWhiteSpace(Group) ? null : Group.Trim()) : live.Topic,
            Priority = Enum.TryParse<Priority>(PriorityKey, out var pr) ? pr : null,
            AlertType = AlertType, Personal = Personal,
        };
        if (SchedKind == "NONE") item = Alerts.ClearSchedule(item);
        else
        {
            var repeat = SchedKind == "REPEAT";
            var count = int.TryParse(RepeatCountText.Trim(), out var rc) ? Recurrence.SanitizeRepeatCount(rc) : null;
            item = item with
            {
                DueAt = due, DueHasTime = HasTime,
                RepeatMode = repeat ? RepeatMode : "OFF",
                RepeatDays = repeat ? days : new List<int>(),
                RepeatN = repeat ? Math.Max(1, RepeatN) : 1,
                RepeatUnit = repeat ? RepeatUnit : "D",
                RepeatOrdList = repeat && RepeatMode == "MONTHLY_ORD" ? ord : new List<int>(),
                RepeatOrd = repeat && ord.Count > 0 ? ord[0] / 10 : live.RepeatOrd,
                RepeatDow = repeat && ord.Count > 0 ? ord[0] % 10 : live.RepeatDow,
                RepeatCount = repeat ? count : null,
                RepeatDone = repeat ? live.RepeatDone : 0,
            };
            if (repeat && RepeatMode == "WEEKLY" && days.Count == 0) { Error = "Pick at least one weekday."; return; }
            if (repeat && RepeatMode == "MONTHLY_ORD" && ord.Count == 0) { Error = "Pick at least one weekday pattern."; return; }
            // A repeat's first occurrence is the first pattern hit on/after the chosen start.
            if (repeat && due is long anchor)
            {
                var first = Recurrence.Preview(RepeatMode, days, item.RepeatN, item.RepeatUnit, item.RepeatOrd, item.RepeatDow, item.RepeatOrdList, anchor, 1, anchor, item.SpacedStep, Math.Min(anchor, Clock.Now()) - 1).FirstOrDefault();
                if (first > 0) item = item with { DueAt = first };
            }
            // A due that moved clears a snooze aimed at the old time (Android: Save re-arms from the new due).
            if (live.DueAt != item.DueAt) item = item with { SnoozedUntil = null };
        }
        if (Tab == Tab.LEARN)
            item = item with
            {
                Platform = string.IsNullOrWhiteSpace(Platform) ? null : Platform.Trim(), Url = string.IsNullOrWhiteSpace(Url) ? null : Url.Trim(),
                Progress = Math.Clamp(Progress, 0, 100), HoursSpent = double.TryParse(HoursText, NumberStyles.Float, CultureInfo.InvariantCulture, out var h) ? Math.Max(0, h) : 0,
            };
        if (Tab == Tab.SHOP)
        {
            var shop = _s.Repo.ShopByName(ShopName);
            item = item with
            {
                ShopName = string.IsNullOrWhiteSpace(ShopName) ? null : ShopName.Trim(), ShopId = shop?.Id,
                Quantity = string.IsNullOrWhiteSpace(Quantity) ? null : Quantity.Trim(), Unit = string.IsNullOrWhiteSpace(Unit) ? null : Unit.Trim(),
                Price = string.IsNullOrWhiteSpace(Price) ? null : Price.Replace("₹", "").Trim(), Staple = Staple,
                LapseValue = int.TryParse(LapseText.Trim(), out var lv) && lv > 0 ? lv : null,
                LapseUnit = int.TryParse(LapseText.Trim(), out var lv2) && lv2 > 0 ? (Enum.TryParse<LapseUnit>(LapseUnitKey, out var lu) ? lu : Core.Models.LapseUnit.DAYS) : null,
                ExpiryAt = Expiry is DateTime ex ? Time.ToMs(ex.Date) : null,
                ProductId = ProductId,
            };
        }
        var saved = _s.Repo.Upsert(item);
        // New group/topic typed → remembered in Settings (Android seeds groups on save too).
        var g = Tab == Tab.LEARN ? saved.Topic : saved.Group;
        if (!string.IsNullOrWhiteSpace(g) && !GroupOptions.Contains(g, StringComparer.OrdinalIgnoreCase))
            _s.Repo.UpdateSettings(st => { if (Tab == Tab.LEARN) st.LearnTopics = st.LearnTopics.Append(g).Distinct().ToList(); else if (Tab == Tab.SHOP) st.ShopGroups = st.ShopGroups.Append(g).Distinct().ToList(); else st.TasksGroups = st.TasksGroups.Append(g).Distinct().ToList(); });
        _main.Ack(IsNew ? "Added" : "Saved", null);
        _main.CloseEditorCommand.Execute(null);
    }

}
