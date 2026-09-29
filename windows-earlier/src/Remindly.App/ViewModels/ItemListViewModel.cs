using System.Collections.ObjectModel;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Remindly.App.Services;
using Remindly.App.Views;
using Remindly.Core.Logic;
using Remindly.Core.Models;

namespace Remindly.App.ViewModels;

/// <summary>One card in a list (Tasks / Learn / Buy). Presentation only; actions route to the list VM.</summary>
public sealed partial class ItemRowViewModel : ObservableObject
{
    private readonly ItemListViewModel _list;
    public Item Item { get; }
    public long Id => Item.Id;
    public ItemRowViewModel(ItemListViewModel list, Item item, AppServices s, bool personalUnlocked)
    {
        _list = list; Item = item;
        var now = Clock.Now();
        IsLocked = item.Personal && !personalUnlocked;
        Title = IsLocked ? "Personal item" : item.Title;
        IsDone = item.Done;
        IsOverdue = !item.Done && Alerts.IsOverdueDay(item.DueAt, now);
        IsToday = !item.Done && item.DueAt is long d && Time.LocalDate(d) == Time.LocalDate(now);
        var parts = new List<string>();
        if (item.DueAt is long due && s.Repo.Settings.CardShowDateTime)
            parts.Add(item.DueHasTime ? s.Relative(due) : s.Relative(due, false));
        if (item.RepeatMode != "OFF" && s.Repo.Settings.CardShowRepeat) parts.Add("↻ " + Recurrence.RepeatLabel(item));
        if (item.Tab == Tab.SHOP)
        {
            var q = string.Join(" ", new[] { item.Quantity, item.Unit }.Where(x => !string.IsNullOrWhiteSpace(x)));
            if (q.Length > 0) parts.Add(q);
            if (!string.IsNullOrWhiteSpace(item.Price)) parts.Add("₹" + item.Price!.Replace("₹", "").Trim());
            var shop = item.ShopId is long sid ? s.Repo.Shop(sid)?.Name : null;
            shop ??= item.ShopName;
            if (!string.IsNullOrWhiteSpace(shop)) parts.Add("@ " + shop);
            if (item.Staple) parts.Add("staple");
            if (item.ExpiryAt is long ex) parts.Add("expires " + Time.FormatDate(ex));
            if (item.LapseValue is int lv && lv > 0 && item.LapseUnit is LapseUnit lu) parts.Add("every " + Recurrence.LapseLabel(lv, lu));
        }
        if (item.Tab == Tab.LEARN)
        {
            if (!string.IsNullOrWhiteSpace(item.Platform)) parts.Add(item.Platform!);
            if (item.Progress > 0) parts.Add(item.Progress + "%");
            if (item.HoursSpent > 0) parts.Add(item.HoursSpent.ToString("0.#") + " h");
            HasUrl = !string.IsNullOrWhiteSpace(item.Url);
        }
        var grp = item.Tab == Tab.LEARN ? item.Topic : item.Group;
        if (!string.IsNullOrWhiteSpace(grp)) parts.Add("#" + grp);
        Subtitle = IsLocked ? "Unlock with the PIN to see this item" : string.Join("  ·  ", parts);
        if (!string.IsNullOrWhiteSpace(item.Notes) && !IsLocked) Notes = item.Notes.Length > 140 ? item.Notes[..140] + "…" : item.Notes;
        ShowPriority = Alerts.ShowPriorityTag(item.Priority, s.Repo.Settings) && s.Repo.Settings.CardShowPriority;
        PriorityLabel = item.Priority?.Label() ?? "";
        PriorityKey = item.Priority?.ToString() ?? "";
        AlertGlyph = Alerts.AlertTypeIcon(item.AlertType);
        ShowAlert = s.Repo.Settings.CardShowAlertType && item.AlertType is "A" or "R" || item.IsMuted;
        var cu = Alerts.ComingUp(item, now).FirstOrDefault(r => r.Kind is ComingUpKind.SNOOZE or ComingUpKind.RETURNS or ComingUpKind.LAPSE_RETURN);
        ComingUp = cu?.At is long at ? $"{cu.What} · {s.Relative(at)}" : "";
        MissedCount = item.MissedAt.Count;
        ProgressPct = item.Progress;
        ShowProgress = item.Tab == Tab.LEARN && item.Progress > 0;
    }

    public string Title { get; }
    public string Subtitle { get; }
    public string Notes { get; } = "";
    public bool IsDone { get; }
    public bool IsOverdue { get; }
    public bool IsToday { get; }
    public bool IsLocked { get; }
    public bool ShowPriority { get; }
    public string PriorityLabel { get; }
    public string PriorityKey { get; }
    public string AlertGlyph { get; }
    public bool ShowAlert { get; }
    public string ComingUp { get; }
    public bool HasComingUp => ComingUp.Length > 0;
    public int MissedCount { get; }
    public bool HasMissed => MissedCount > 0;
    public bool HasUrl { get; }
    public int ProgressPct { get; }
    public bool ShowProgress { get; }
    public bool HasNotes => Notes.Length > 0;

    [RelayCommand] private void Toggle() => _list.ToggleDone(this);
    [RelayCommand] private void Edit() => _list.Edit(this);
    [RelayCommand] private void Snooze() => _list.Snooze(this);
    [RelayCommand] private void Quiet() => _list.Quiet(this);
    [RelayCommand] private void Delete() => _list.Delete(this);
    [RelayCommand] private void Mute() => _list.Mute(this);
    [RelayCommand] private void OpenUrl() => Dialogs.OpenUrl(Item.Url);
    [RelayCommand] private void CancelSnooze() => _list.CancelSnooze(this);
}

public sealed partial class ItemGroupViewModel : ObservableObject
{
    public string Key { get; init; } = "";
    public string Label { get; init; } = "";
    public string Meta { get; init; } = "";
    public bool IsOverdue { get; init; }
    public bool IsToday { get; init; }
    [ObservableProperty] private bool _isExpanded = true;
    public ObservableCollection<ItemRowViewModel> Items { get; } = new();
    public int Count => Items.Count;
    public string CountLabel => Items.Count.ToString();
    public bool ShowHeaderCheck { get; init; }
    public bool AllDone { get; init; }
    private readonly ItemListViewModel _list;
    public ItemGroupViewModel(ItemListViewModel list) { _list = list; }
    [RelayCommand] private void CompleteAll() => _list.CompleteGroup(this);
}

/// <summary>Tasks · Learn · Buy — Active | Done views, DATE / GROUP / PRIORITY sort, search, quick add (Android ListScreens.kt).</summary>
public sealed partial class ItemListViewModel : ObservableObject
{
    private readonly AppServices _s;
    private readonly MainViewModel _main;
    private readonly DispatcherTimer _debounce;
    public Tab Tab { get; }
    public string Title => Tab.Label();
    public string AddPlaceholder => Tab switch { Tab.SHOP => "Add to your buy list… (Enter)", Tab.LEARN => "Add something to learn… (Enter)", _ => "Add a task… (Enter)" };
    public bool IsShop => Tab == Tab.SHOP;
    public bool IsLearn => Tab == Tab.LEARN;

    [ObservableProperty] private bool _showDone;
    [ObservableProperty] private string _sort = "DATE";
    [ObservableProperty] private string _search = "";
    [ObservableProperty] private string _quickAdd = "";
    [ObservableProperty] private string _summary = "";
    [ObservableProperty] private string _emptyText = "";
    [ObservableProperty] private bool _isEmpty;
    [ObservableProperty] private bool _buyNowActive;
    [ObservableProperty] private string _buyNowLabel = "";
    public ObservableCollection<ItemGroupViewModel> Groups { get; } = new();
    public string[] SortOptions { get; } = { "DATE", "GROUP", "PRIORITY" };
    public string SortLabel => Sort switch { "GROUP" => IsLearn ? "By topic" : IsShop ? "By shop" : "By group", "PRIORITY" => "By priority", _ => "By date" };

    public ItemListViewModel(AppServices s, MainViewModel main, Tab tab)
    {
        _s = s; _main = main; Tab = tab;
        Sort = Alerts.SortOf(s.Repo.Settings, tab);
        _debounce = new DispatcherTimer(DispatcherPriority.Background, s.Dispatcher) { Interval = TimeSpan.FromMilliseconds(120) };
        _debounce.Tick += (_, _) => { _debounce.Stop(); Rebuild(); };
        s.Repo.Changed += c => { if (c is "items" or "settings" or "shops" or "prefs") _debounce.Start(); };
        main.PropertyChanged += (_, e) => { if (e.PropertyName == nameof(MainViewModel.PersonalUnlocked)) _debounce.Start(); };
        Rebuild();
    }

    partial void OnShowDoneChanged(bool value) => Rebuild();
    partial void OnSearchChanged(string value) => _debounce.Start();
    partial void OnSortChanged(string value)
    {
        OnPropertyChanged(nameof(SortLabel));
        if (Alerts.SortOf(_s.Repo.Settings, Tab) != value)
            _s.Repo.UpdateSettings(st => { if (Tab == Tab.SHOP) st.ShopSort = value; else if (Tab == Tab.LEARN) st.LearnSort = value; else st.TasksSort = value; });
        Rebuild();
    }

    [RelayCommand] private void CycleSort() => Sort = SortOptions[(Array.IndexOf(SortOptions, Sort) + 1) % SortOptions.Length];
    [RelayCommand] private void ShowActive() => ShowDone = false;
    [RelayCommand] private void ShowDoneList() => ShowDone = true;
    [RelayCommand] private void HideBuyNow() { _s.Repo.Prefs.BuyNowShopId = null; _s.Repo.SavePrefs(); Rebuild(); }

    public void Rebuild()
    {
        var repo = _s.Repo; var s = repo.Settings; var now = Clock.Now();
        var unlocked = _main.PersonalUnlocked;
        Shop? buyNowShop = Tab == Tab.SHOP && repo.Prefs.BuyNowShopId is long bn ? repo.Shop(bn) : null;
        BuyNowActive = buyNowShop != null && !ShowDone;
        BuyNowLabel = buyNowShop != null ? "Buy Now · " + buyNowShop.Name : "";
        IEnumerable<Item> src = repo.Items.Where(i => i.Tab == Tab && i.DeletedAt == null && i.Done == ShowDone);
        if (BuyNowActive) src = Alerts.BuyNowItems(repo.Items, buyNowShop);
        var q = Search.Trim();
        if (q.Length > 0) src = src.Where(i => !i.Personal || unlocked).Where(i => i.Title.Contains(q, StringComparison.OrdinalIgnoreCase) || i.Notes.Contains(q, StringComparison.OrdinalIgnoreCase) || (i.Group?.Contains(q, StringComparison.OrdinalIgnoreCase) ?? false) || (i.ShopName?.Contains(q, StringComparison.OrdinalIgnoreCase) ?? false) || (i.Topic?.Contains(q, StringComparison.OrdinalIgnoreCase) ?? false));
        var list = src.ToList();
        Groups.Clear();
        var today = Time.LocalDate(now);
        ItemGroupViewModel Group(string key, string label, IEnumerable<Item> items, string meta = "", bool overdue = false, bool isToday = false)
        {
            var g = new ItemGroupViewModel(this) { Key = key, Label = label, Meta = meta, IsOverdue = overdue, IsToday = isToday, ShowHeaderCheck = s.GroupHeaderCheck, AllDone = ShowDone };
            foreach (var it in items) g.Items.Add(new ItemRowViewModel(this, it, _s, unlocked));
            return g;
        }
        if (Sort == "GROUP")
        {
            Func<Item, string?> keyOf = Tab == Tab.LEARN ? i => i.Topic : Tab == Tab.SHOP ? i => (i.ShopId is long sid ? repo.Shop(sid)?.Name : null) ?? i.ShopName : i => i.Group;
            foreach (var (key, items) in Grouping.ByGroup(list, keyOf, Tab == Tab.SHOP ? "No shop" : Tab == Tab.LEARN ? "No topic" : "No group"))
                Groups.Add(Group(key, key, items.OrderBy(i => Grouping.Basis(i, ShowDone)), Tab == Tab.SHOP ? ShopMath.SpendLabel(items).TrimStart(' ', '·', ' ') : ""));
        }
        else if (Sort == "PRIORITY")
        {
            foreach (var g in list.GroupBy(i => Alerts.RankOf(i.Priority)).OrderBy(g => g.Key))
            {
                var label = g.Key switch { 0 => "Urgent", 1 => "High", 3 => "Low", _ => "Medium" };
                Groups.Add(Group("p" + g.Key, label, g.OrderBy(i => Grouping.Basis(i, ShowDone))));
            }
        }
        else
        {
            // DATE: overdue first (Active), then day buckets ascending; Done descending by completion.
            if (!ShowDone)
            {
                var overdue = list.Where(i => Alerts.IsOverdueDay(i.DueAt, now)).OrderBy(i => i.DueAt).ToList();
                if (overdue.Count > 0) Groups.Add(Group("overdue", "Overdue", overdue, ShopMath.SpendLabel(overdue).TrimStart(' ', '·', ' '), overdue: true));
                var rest = list.Where(i => !Alerts.IsOverdueDay(i.DueAt, now));
                foreach (var y in Grouping.BuildYearGroups(rest, false, i => i.Id, i => Grouping.Basis(i, false)))
                    foreach (var m in y.Months) foreach (var d in m.Days)
                    {
                        var date = DateOnly.ParseExact(d.Key, "yyyy-MM-dd");
                        var label = date == today ? "Today · " + d.Label : date == today.AddDays(1) ? "Tomorrow · " + d.Label : d.Label + (date.Year != today.Year ? " " + date.Year : "");
                        var undated = d.Items.All(i => i.DueAt == null);
                        Groups.Add(Group(d.Key, undated ? "No date · added " + d.Label : label, d.Items, Tab == Tab.SHOP ? ShopMath.SpendLabel(d.Items).TrimStart(' ', '·', ' ') : "", isToday: date == today));
                    }
            }
            else
                foreach (var y in Grouping.BuildYearGroups(list, true, i => i.Id, i => Grouping.Basis(i, true)))
                    foreach (var m in y.Months) foreach (var d in m.Days)
                    {
                        var date = DateOnly.ParseExact(d.Key, "yyyy-MM-dd");
                        Groups.Add(Group(d.Key, (date == today ? "Today · " : "") + d.Label + (date.Year != today.Year ? " " + date.Year : ""), d.Items, Tab == Tab.SHOP ? ShopMath.SpendLabel(d.Items).TrimStart(' ', '·', ' ') : ""));
                    }
        }
        var n = list.Count;
        Summary = ShowDone ? $"{n} done" : $"{n} active" + (Tab == Tab.SHOP ? ShopMath.SpendLabel(list) : "");
        IsEmpty = n == 0;
        EmptyText = IsEmpty ? (q.Length > 0 ? "Nothing matches your search." : ShowDone ? "Nothing here yet — completed items land here." : Tab switch { Tab.SHOP => "Your buy list is empty. Type above to add something.", Tab.LEARN => "Nothing to learn yet. Add a course, a book, a skill.", _ => "All clear. Add a task above." }) : "";
    }

    // ── actions ──

    [RelayCommand] private void Add()
    {
        var title = QuickAdd.Trim();
        if (title.Length == 0) { _main.OpenItemEditor(null, Tab); return; }
        var s = _s.Repo.Settings;
        var kind = Alerts.SchedDefaultFor(s, Tab);
        var due = kind == SchedKind.NONE ? null : Alerts.DefaultNewDue(Tab, s);
        var item = new Item
        {
            Id = Ids.Next(), Tab = Tab, Title = title, DueAt = due, DueHasTime = due != null && Alerts.NewDueTimedFor(Tab, s),
            ShopName = Tab == Tab.SHOP ? _s.Repo.DefaultShop?.Name : null, ShopId = Tab == Tab.SHOP ? _s.Repo.DefaultShop?.Id : null,
        };
        if (Alerts.DupActiveMatch(_s.Repo.Items, Tab, title) && !Dialogs.Confirm($"\"{title}\" is already on your active list. Add it again?")) return;
        _s.Repo.Upsert(item);
        QuickAdd = "";
    }

    [RelayCommand] private void AddFull() => _main.OpenItemEditor(null, Tab);

    public void Edit(ItemRowViewModel row)
    {
        if (row.IsLocked) { _main.TogglePersonalCommand.Execute(null); return; }
        _main.OpenItemEditor(_s.Repo.Item(row.Id), Tab);
    }

    public void ToggleDone(ItemRowViewModel row)
    {
        var item = _s.Repo.Item(row.Id); if (item == null) return;
        if (row.IsLocked) { _main.TogglePersonalCommand.Execute(null); return; }
        if (item.Done)
        {
            _s.Repo.Upsert(Engine.UndoDone(item));
            _main.Ack("Back on your list", () => { var cur = _s.Repo.Item(item.Id); if (cur != null) _s.Repo.Upsert(cur with { Done = true, DoneAt = item.DoneAt, ReturnAt = item.ReturnAt }); });
            return;
        }
        if (item.Tab == Tab.SHOP && _s.Repo.Settings.ShopCheckoutCalc) { App.RequestCheckout(item); return; }
        Complete(item);
    }

    public void Complete(Item item)
    {
        var before = item;
        var r = Engine.Complete(item, Clock.Now());
        _s.Repo.Upsert(r.Updated);
        var ack = r.ReturnsAt is long ra ? $"{r.Ack} {_s.DayTime(ra)}" : r.Ack;
        _main.Ack(ack, () => _s.Repo.Upsert(before));
    }

    public void CompleteGroup(ItemGroupViewModel g)
    {
        var ids = g.Items.Where(r => !r.IsLocked).Select(r => r.Id).ToList();
        if (ids.Count == 0) return;
        var verb = ShowDone ? "Restore" : "Complete";
        if (!Dialogs.Confirm($"{verb} all {ids.Count} item(s) in \"{g.Label}\"?" + (Tab == Tab.SHOP && !ShowDone ? "\n\nThe checkout calculator is skipped for a group." : ""), $"{verb} group")) return;
        var snapshot = ids.Select(id => _s.Repo.Item(id)!).ToList();
        var now = Clock.Now();
        foreach (var it in snapshot) _s.Repo.Upsert(ShowDone ? Engine.UndoDone(it) : Engine.Complete(it, now).Updated);
        _main.Ack($"{verb}d {ids.Count} item(s)", () => { foreach (var it in snapshot) _s.Repo.Upsert(it); });
    }

    public void Snooze(ItemRowViewModel row)
    {
        var item = _s.Repo.Item(row.Id); if (item == null) return;
        var until = Alerts.SnoozeTargetMs(_s.Repo.Settings, Clock.Now());
        _s.Repo.Upsert(Engine.Snooze(item, until));
        _main.Ack(Alerts.SnoozeToast(Alerts.SnoozeMinutes(_s.Repo.Settings), until, _s.Time(until)), () => _s.Repo.Upsert(item));
    }

    public void CancelSnooze(ItemRowViewModel row)
    {
        var item = _s.Repo.Item(row.Id); if (item == null) return;
        _s.Repo.Upsert(Engine.CancelSnooze(item));
    }

    public void Quiet(ItemRowViewModel row)
    {
        var item = _s.Repo.Item(row.Id); if (item == null) return;
        var until = Alerts.SnoozeTargetMs(_s.Repo.Settings, Clock.Now());
        _s.Repo.Upsert(Alerts.QuietDemote(item, until));
        _main.Ack(Alerts.QuietToast(until, _s.Time(until)), () => _s.Repo.Upsert(item));
    }

    public void Mute(ItemRowViewModel row)
    {
        var item = _s.Repo.Item(row.Id); if (item == null) return;
        if (item.IsMuted) { _s.Repo.Upsert(Engine.Unmute(item, "N")); _main.Ack("Alerts restored (Notify)", null); return; }
        var prev = item.AlertType;
        _s.Repo.Upsert(Engine.Mute(item));
        _main.Ack("Muted — the item stays, nothing will ring", () => { var cur = _s.Repo.Item(item.Id); if (cur != null) _s.Repo.Upsert(Engine.Unmute(cur, prev)); });
    }

    public void Delete(ItemRowViewModel row)
    {
        var item = _s.Repo.Item(row.Id); if (item == null) return;
        if (row.IsLocked) { _main.TogglePersonalCommand.Execute(null); return; }
        _s.Repo.Upsert(Engine.SoftDelete(item, Clock.Now()));
        if (_main.Editor is ItemEditorViewModel ed && ed.ItemId == item.Id) _main.Editor = null;
        _main.Ack("Moved to Recently deleted", () => { var cur = _s.Repo.Item(item.Id); if (cur != null) _s.Repo.Upsert(Engine.Restore(cur)); });
    }
}
