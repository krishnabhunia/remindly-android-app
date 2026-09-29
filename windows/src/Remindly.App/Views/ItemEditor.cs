using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using Remindly.App.Services;
using Remindly.Core;

namespace Remindly.App.Views;

/// <summary>The item editor for Tasks, Learn and Buy — due date, reminder type, repeat, priority and the tab's own fields.</summary>
public sealed class ItemEditor : EditorWindow
{
    private static readonly string[] Priorities = { "None", "Low", "Medium", "High", "Urgent" };
    private static readonly string[] AlertTypes = { "Notify", "Ring", "Muted" };
    private static readonly (string Code, string Label)[] RepeatModes =
    {
        ("OFF", "Does not repeat"), ("DAILY", "Daily"), ("WEEKLY", "Weekly on…"), ("MONTHLY_DAY", "Monthly on day(s)…"),
        ("MONTHLY_ORD", "Monthly on the…"), ("QUARTERLY", "Quarterly"), ("HALFYEARLY", "Half-yearly"), ("YEARLY", "Yearly"),
        ("EVERY_N", "Every N days / weeks / months"), ("SPACED", "Spaced revision (3 · 7 · 14 · 30 days)"),
    };
    private static readonly string[] Units = { "", "kg", "g", "L", "ml", "pcs", "dozen", "pack", "strip", "box", "bottle" };
    private static readonly string[] Platforms = { "Online", "Offline", "Internet", "LinkedIn Learning", "Udemy" };
    private static readonly string[] WeekDays = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };
    private static readonly string[] Ords = { "First", "Second", "Third", "Fourth", "Last" };

    private readonly Item _orig;
    private readonly bool _isNew;
    private readonly TextBox _title = new();
    private readonly TextBox _notes = new() { AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, MinHeight = 60, MaxHeight = 140, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalContentAlignment = VerticalAlignment.Top };
    private readonly ComboBox _priority;
    private readonly CheckBox _hasDue = new() { Content = "Due date" };
    private readonly DatePicker _date = new() { Width = 150 };
    private readonly CheckBox _hasTime = new() { Content = "at", Margin = new Thickness(12, 0, 6, 0) };
    private readonly TextBox _time = new() { Width = 70 };
    private readonly ComboBox _alert;
    private readonly ComboBox _repeat;
    private readonly StackPanel _repeatExtra = new();
    private readonly CheckBox[] _weekDays = WeekDays.Select(d => new CheckBox { Content = d, Margin = new Thickness(0, 0, 10, 0) }).ToArray();
    private readonly TextBox _monthDays = new();
    private readonly ComboBox _ord = Combo(Ords, "First");
    private readonly ComboBox _ordDow = Combo(WeekDays, "Mon");
    private readonly TextBox _everyN = new() { Width = 60 };
    private readonly ComboBox _everyUnit = Combo(new[] { "days", "weeks", "months" }, "days");
    private readonly TextBox _repeatCount = new() { Width = 60 };
    private readonly TextBlock _preview = Ui.Sub("");
    private readonly CheckBox _personal = new() { Content = "Personal (hidden in reminders)" };
    // Buy
    private ComboBox? _list;
    private TextBox? _qty;
    private ComboBox? _unit;
    private TextBox? _price;
    private ComboBox? _shop;
    private ComboBox? _product;
    private TextBox? _lapse;
    private ComboBox? _lapseUnit;
    private CheckBox? _staple;
    // Learn
    private ComboBox? _platform;
    private TextBox? _url;
    private TextBox? _topic;
    private Slider? _progress;
    private TextBox? _hours;

    public static void New(Tab tab, long? listId = null, string title = "")
    {
        var state = AppState.Current;
        var item = state.NewItem(tab, title);
        if (tab == Tab.SHOP)
        {
            var list = listId is long l && l >= 0 ? state.List(l) : (listId == null && state.Settings.ShopDefaultListId is long d ? state.List(d) : null);
            item = item with { ListId = list?.Id, Group = list?.Name, Personal = list?.Personal == true };
            if (list?.UsualShopId is long sid && state.Shops.FirstOrDefault(s => s.Id == sid) is Shop us) item = item with { ShopId = us.Id, ShopName = us.Name };
        }
        new ItemEditor(item, isNew: true).Open();
    }

    public static void Edit(Item item) => new ItemEditor(item, isNew: false).Open();

    private ItemEditor(Item item, bool isNew) : base((isNew ? "New " : "Edit ") + (item.Tab switch { Tab.SHOP => "Buy item", Tab.LEARN => "Learn item", _ => "task" }), 560)
    {
        _orig = item;
        _isNew = isNew;
        var state = AppState.Current;

        _title.Text = item.Title;
        Field("Title", _title);
        _title.TextChanged += (_, _) => CheckDuplicate();

        if (item.Tab == Tab.SHOP) BuildBuy(item, state);
        if (item.Tab == Tab.LEARN) BuildLearn(item);

        Field("Notes", _notes);
        _notes.Text = item.Notes;

        _priority = Combo(Priorities, PriorityNames.Label(item.Priority));
        _alert = Combo(AlertTypes, item.AlertType == "OFF" ? "Muted" : item.AlertType.Contains('R') || item.AlertType.Contains('A') ? "Ring" : "Notify");
        Form.Children.Add(TwoColumns(Labeled("Priority", _priority), Labeled("Reminder", _alert)));

        Section("When");
        var local = item.DueAt is long due ? Clock.ToLocal(due) : DateTime.Today.AddHours(state.Settings.DefaultDueHour);
        _hasDue.IsChecked = item.DueAt != null;
        _date.SelectedDate = local.Date;
        _hasTime.IsChecked = item.DueAt == null || item.DueHasTime;
        _time.Text = local.ToString("HH:mm", CultureInfo.InvariantCulture);
        Form.Children.Add(Ui.Row(_hasDue, new Border { Width = 10 }, _date, _hasTime, _time));
        foreach (var c in new Control[] { _date, _time }) c.IsEnabled = _hasDue.IsChecked == true;
        _hasDue.Click += (_, _) => { _date.IsEnabled = _time.IsEnabled = _hasDue.IsChecked == true; UpdatePreview(); };
        _date.SelectedDateChanged += (_, _) => UpdatePreview();
        _time.TextChanged += (_, _) => UpdatePreview();

        _repeat = new ComboBox();
        foreach (var (_, label) in RepeatModes) _repeat.Items.Add(label);
        _repeat.SelectedIndex = Math.Max(0, Array.FindIndex(RepeatModes, m => m.Code == item.RepeatMode));
        _repeat.SelectionChanged += (_, _) => { BuildRepeatExtra(); UpdatePreview(); };
        Field("Repeat", _repeat);
        Form.Children.Add(_repeatExtra);
        // repeat details from the item
        foreach (var d in item.RepeatDays.Where(d => d is >= 1 and <= 7)) _weekDays[d - 1].IsChecked = true;
        _monthDays.Text = string.Join(",", item.RepeatDays.Where(d => d is >= 1 and <= 31));
        var pat = item.RepeatOrdList.Count > 0 ? item.RepeatOrdList[0] : item.RepeatOrd * 10 + item.RepeatDow;
        _ord.SelectedIndex = Math.Clamp(pat / 10 - 1, 0, 4);
        _ordDow.SelectedIndex = Math.Clamp(pat % 10 - 1, 0, 6);
        _everyN.Text = item.RepeatN.ToString(CultureInfo.InvariantCulture);
        _everyUnit.SelectedIndex = item.RepeatUnit switch { "W" => 1, "M" => 2, _ => 0 };
        _repeatCount.Text = item.RepeatCount?.ToString(CultureInfo.InvariantCulture) ?? "";
        foreach (var cb in _weekDays) cb.Click += (_, _) => UpdatePreview();
        foreach (var tb in new[] { _monthDays, _everyN }) tb.TextChanged += (_, _) => UpdatePreview();
        _ord.SelectionChanged += (_, _) => UpdatePreview();
        _ordDow.SelectionChanged += (_, _) => UpdatePreview();
        _everyUnit.SelectionChanged += (_, _) => UpdatePreview();
        BuildRepeatExtra();
        Form.Children.Add(_preview);

        _personal.IsChecked = item.Personal;
        _personal.Margin = new Thickness(0, 12, 0, 0);
        Form.Children.Add(_personal);

        var extra = new List<Button>();
        if (!isNew)
        {
            var del = Ui.Btn("Delete", () =>
            {
                state.SoftDelete(new[] { _orig });
                App.Current.Main.Snack("Moved to the Bin", () => state.Upsert(_orig));
                Close();
            }, style: "Danger");
            extra.Add(del);
        }
        AddButtons(isNew ? "Add" : "Save", extra.ToArray());
        Loaded += (_, _) => { _title.Focus(); _title.SelectAll(); UpdatePreview(); CheckDuplicate(); };
    }

    private void BuildBuy(Item item, AppState state)
    {
        var lists = state.Lists.OrderBy(l => l.Order).ThenBy(l => l.Name).ToList();
        _list = new ComboBox { DisplayMemberPath = "Label" };
        _list.Items.Add(new ListChoice(null, "Unsorted"));
        foreach (var l in lists) _list.Items.Add(new ListChoice(l, (l.Icon != null ? l.Icon + " " : "") + l.Name));
        var current = ShopLists.ListIdOf(item, state.Settings.ShopLists) ?? item.ListId;
        _list.SelectedItem = _list.Items.Cast<ListChoice>().FirstOrDefault(c => c.List?.Id == current) ?? _list.Items[0];
        _list.SelectionChanged += (_, _) => CheckDuplicate();
        Field("List", _list);

        _product = Combo(state.Products.Select(p => p.Name), state.Products.FirstOrDefault(p => p.Id == item.ProductId)?.Name ?? "", editable: true);
        _product.SelectionChanged += (_, _) =>
        {
            if (_product.SelectedItem is string pn && state.Products.FirstOrDefault(p => p.Name == pn) is Product p)
            {
                if (string.IsNullOrWhiteSpace(_title.Text)) _title.Text = p.Name;
                if (_unit != null && string.IsNullOrWhiteSpace(_unit.Text) && p.DefaultUnit != null) _unit.Text = p.DefaultUnit;
            }
        };
        _qty = new TextBox { Text = item.Quantity ?? "" };
        _unit = Combo(Units, item.Unit ?? "", editable: true);
        Form.Children.Add(TwoColumns(Labeled("Quantity", _qty), Labeled("Unit", _unit)));
        _price = new TextBox { Text = item.Price ?? "" };
        _shop = Combo(state.Shops.Select(s => s.Name), item.ShopName ?? "", editable: true);
        Form.Children.Add(TwoColumns(Labeled("Price (₹)", _price), Labeled("Shop", _shop)));
        Field("Product (from your catalogue)", _product);
        _lapse = new TextBox { Text = item.LapseValue?.ToString(CultureInfo.InvariantCulture) ?? "", Width = 60 };
        _lapseUnit = Combo(new[] { "days", "months" }, item.LapseUnit == LapseUnit.MONTHS ? "months" : "days");
        Field("After it is bought, put it back on the list after", Ui.Row(_lapse, new Border { Width = 8 }, _lapseUnit));
        _staple = new CheckBox { Content = "Staple (bought regularly)", IsChecked = item.Staple, Margin = new Thickness(0, 8, 0, 0) };
        Form.Children.Add(_staple);
    }

    private void BuildLearn(Item item)
    {
        _platform = Combo(Platforms, item.Platform ?? "", editable: true);
        _topic = new TextBox { Text = item.Topic ?? "" };
        Form.Children.Add(TwoColumns(Labeled("Platform", _platform), Labeled("Topic", _topic)));
        _url = new TextBox { Text = item.Url ?? "" };
        Field("Link", _url);
        _progress = new Slider { Minimum = 0, Maximum = 100, Value = item.Progress, TickFrequency = 5, IsSnapToTickEnabled = true, Width = 220 };
        var pct = Ui.Sub($"{item.Progress}%");
        _progress.ValueChanged += (_, _) => pct.Text = $"{(int)_progress.Value}%";
        _hours = new TextBox { Text = item.HoursSpent > 0 ? item.HoursSpent.ToString("0.#", CultureInfo.InvariantCulture) : "", Width = 70 };
        Form.Children.Add(TwoColumns(Labeled("Progress", Ui.Row(_progress, new Border { Width = 8 }, pct)), Labeled("Hours spent", _hours)));
    }

    private sealed record ListChoice(ShopList? List, string Label);

    private string RepeatCode => RepeatModes[Math.Max(0, _repeat.SelectedIndex)].Code;

    private void BuildRepeatExtra()
    {
        _repeatExtra.Children.Clear();
        switch (RepeatCode)
        {
            case "WEEKLY":
                var wp = new WrapPanel { Margin = new Thickness(0, 6, 0, 0) };
                foreach (var cb in _weekDays) { (cb.Parent as Panel)?.Children.Remove(cb); wp.Children.Add(cb); }
                _repeatExtra.Children.Add(wp);
                break;
            case "MONTHLY_DAY":
                (_monthDays.Parent as Panel)?.Children.Remove(_monthDays);
                _repeatExtra.Children.Add(Labeled("Day(s) of the month, e.g. 1,15 (empty = the due date's day)", _monthDays));
                break;
            case "MONTHLY_ORD":
                foreach (var c in new UIElement[] { _ord, _ordDow }) ((c as FrameworkElement)?.Parent as Panel)?.Children.Remove(c);
                _repeatExtra.Children.Add(Labeled("On the", Ui.Row(_ord, new Border { Width = 8 }, _ordDow)));
                break;
            case "EVERY_N":
                foreach (var c in new UIElement[] { _everyN, _everyUnit }) ((c as FrameworkElement)?.Parent as Panel)?.Children.Remove(c);
                _repeatExtra.Children.Add(Labeled("Every", Ui.Row(_everyN, new Border { Width = 8 }, _everyUnit)));
                break;
        }
        if (RepeatCode != "OFF")
        {
            (_repeatCount.Parent as Panel)?.Children.Remove(_repeatCount);
            _repeatExtra.Children.Add(Labeled("End after this many times (empty = never ends)", _repeatCount));
        }
    }

    /// <summary>The draft with the form's schedule (null when the date / time is invalid).</summary>
    private Item? ScheduleDraft(Item baseItem, out string? error)
    {
        error = null;
        long? dueAt = null;
        bool hasTime = _hasTime.IsChecked == true;
        string mode = RepeatCode;
        if (_hasDue.IsChecked == true || mode != "OFF")
        {
            var date = _date.SelectedDate ?? DateTime.Today;
            var hour = AppState.Current.Settings.DefaultDueHour;
            var tod = TimeSpan.FromHours(hour);
            if (hasTime)
            {
                if (!TimeSpan.TryParseExact(_time.Text.Trim(), new[] { @"h\:mm", @"hh\:mm", @"h\.mm", "hhmm" }, CultureInfo.InvariantCulture, out tod) || tod.TotalHours >= 24)
                {
                    error = "Time must look like 09:30 (24-hour).";
                    return null;
                }
            }
            dueAt = Clock.FromLocal(date.Date + tod);
        }
        var days = mode switch
        {
            "WEEKLY" => _weekDays.Select((c, i) => (c, i)).Where(x => x.c.IsChecked == true).Select(x => x.i + 1).ToList(),
            "MONTHLY_DAY" => _monthDays.Text.Split(',', ' ', ';').Select(s => int.TryParse(s.Trim(), out var d) ? d : 0).Where(d => d is >= 1 and <= 31).Distinct().OrderBy(d => d).ToList(),
            _ => new List<int>(),
        };
        if (mode == "WEEKLY" && days.Count == 0 && dueAt is long da) days.Add(Recurrence.IsoDow(Clock.ToLocal(da)));
        int n = int.TryParse(_everyN.Text.Trim(), out var nn) && nn > 0 ? Math.Min(nn, 999) : 1;
        var ordPat = (Math.Max(0, _ord.SelectedIndex) + 1) * 10 + Math.Max(0, _ordDow.SelectedIndex) + 1;
        return baseItem with
        {
            DueAt = dueAt,
            DueHasTime = dueAt == null || hasTime,
            RepeatMode = mode,
            RepeatDays = days,
            RepeatN = n,
            RepeatUnit = _everyUnit.SelectedIndex switch { 1 => "W", 2 => "M", _ => "D" },
            RepeatOrdList = mode == "MONTHLY_ORD" ? new List<int> { ordPat } : new List<int>(),
            RepeatOrd = ordPat / 10,
            RepeatDow = ordPat % 10,
            RepeatCount = Recurrence.SanitizeCount(int.TryParse(_repeatCount.Text.Trim(), out var rc) ? rc : null),
        };
    }

    private void UpdatePreview()
    {
        if (!IsLoaded) return;
        var d = ScheduleDraft(_orig, out var err);
        if (err != null) { _preview.Text = err; return; }
        if (d == null || d.RepeatMode == "OFF") { _preview.Text = d?.DueAt is long due ? "Due " + Clock.FormatDayTime(due) : "No due date — it stays on the list until you tick it."; return; }
        var next = Recurrence.Preview(d, AppState.Current.Now);
        _preview.Text = next.Count == 0 ? "" : "Next: " + string.Join("  ·  ", next.Select(Clock.FormatDayTime));
    }

    private void CheckDuplicate()
    {
        var state = AppState.Current;
        var t = _title.Text.Trim();
        if (t.Length == 0) { ShowWarning(null); return; }
        if (_orig.Tab == Tab.SHOP && _list != null)
        {
            var cur = (_list.SelectedItem as ListChoice)?.List?.Id;
            var other = ShopLists.OtherListHolding(state.Data.Items.Where(i => i.Id != _orig.Id), t, cur, state.Settings.ShopLists);
            ShowWarning(other != null ? $"\"{t}\" is already on your {other.Name} list." : null);
            return;
        }
        ShowWarning(ItemRules.DupActiveMatch(state.Data.Items, _orig.Tab, t, _orig.Id) ? $"\"{t}\" is already on your {TabNames.Title(_orig.Tab)} list." : null);
    }

    protected override bool Save()
    {
        var state = AppState.Current;
        var title = _title.Text.Trim();
        if (title.Length == 0) { ShowWarning("Give it a title."); _title.Focus(); return false; }
        var d = ScheduleDraft(_orig, out var err);
        if (d == null) { ShowWarning(err); return false; }
        var pr = Array.IndexOf(Priorities, _priority.SelectedItem as string ?? "None");
        d = d with
        {
            Title = title,
            Notes = _notes.Text.Trim(),
            Priority = pr switch { 1 => Priority.LOW, 2 => Priority.MEDIUM, 3 => Priority.HIGH, 4 => Priority.URGENT, _ => null },
            AlertType = (_alert.SelectedItem as string) switch { "Ring" => "R", "Muted" => "OFF", _ => "N" },
            Personal = _personal.IsChecked == true,
            // A new schedule starts clean: no leftover snooze from the old one.
            SnoozedUntil = d.DueAt != _orig.DueAt ? null : _orig.SnoozedUntil,
        };
        if (_orig.Tab == Tab.SHOP)
        {
            var list = (_list?.SelectedItem as ListChoice)?.List;
            var shopName = _shop?.Text.Trim() ?? "";
            var shop = state.Shops.FirstOrDefault(s => string.Equals(s.Name, shopName, StringComparison.OrdinalIgnoreCase));
            var prodName = _product?.Text.Trim() ?? "";
            var product = prodName.Length == 0 ? null : state.Products.FirstOrDefault(p => string.Equals(p.Name, prodName, StringComparison.OrdinalIgnoreCase));
            if (product == null && prodName.Length > 0)
            {
                product = new Product { Id = Ids.Next(), Name = prodName, DefaultUnit = string.IsNullOrWhiteSpace(_unit?.Text) ? null : _unit!.Text.Trim() };
                state.UpsertProduct(product);
            }
            int? lapse = int.TryParse(_lapse?.Text.Trim(), out var lv) && lv > 0 ? Math.Min(lv, 3650) : null;
            d = d with
            {
                ListId = list?.Id,
                Group = list?.Name,
                Personal = d.Personal || list?.Personal == true,
                Quantity = string.IsNullOrWhiteSpace(_qty?.Text) ? null : _qty!.Text.Trim(),
                Unit = string.IsNullOrWhiteSpace(_unit?.Text) ? null : _unit!.Text.Trim(),
                Price = string.IsNullOrWhiteSpace(_price?.Text) ? null : _price!.Text.Trim(),
                ShopName = shopName.Length == 0 ? null : shop?.Name ?? shopName,
                ShopId = shop?.Id,
                ProductId = product?.Id,
                LapseValue = lapse,
                LapseUnit = lapse == null ? null : _lapseUnit?.SelectedIndex == 1 ? LapseUnit.MONTHS : LapseUnit.DAYS,
                Staple = _staple?.IsChecked == true,
            };
        }
        if (_orig.Tab == Tab.LEARN)
        {
            d = d with
            {
                Platform = string.IsNullOrWhiteSpace(_platform?.Text) ? null : _platform!.Text.Trim(),
                Url = string.IsNullOrWhiteSpace(_url?.Text) ? null : _url!.Text.Trim(),
                Topic = string.IsNullOrWhiteSpace(_topic?.Text) ? null : _topic!.Text.Trim(),
                Progress = (int)(_progress?.Value ?? 0),
                HoursSpent = double.TryParse(_hours?.Text.Trim(), NumberStyles.Float, CultureInfo.InvariantCulture, out var h) && h >= 0 ? h : 0,
            };
        }
        state.Upsert(d);
        if (!_isNew && _orig.Tab == Tab.SHOP && d.ListId != _orig.ListId)
        {
            var name = d.ListId is long lid ? state.List(lid)?.Name ?? "Unsorted" : "Unsorted";
            App.Current.Main.Snack($"Moved to {name}", () => state.Upsert(_orig));
        }
        else App.Current.Main.Snack(_isNew ? $"Added \"{title}\"" : "Saved");
        return true;
    }
}
