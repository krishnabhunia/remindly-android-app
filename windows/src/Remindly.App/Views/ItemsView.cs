using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Remindly.App.Services;
using Remindly.Core;

namespace Remindly.App.Views;

/// <summary>Tasks and Learn: quick add, Active / Done, grouped by day, tick to complete (with Undo).</summary>
public sealed class ItemsView : DockPanel, IPage
{
    private readonly Tab _tab;
    private readonly StackPanel _list = new();
    private readonly TextBlock _count = Ui.Sub("");
    private readonly TextBox _quick;
    private readonly TextBox _search;
    private bool _doneView;

    public ItemsView(Tab tab)
    {
        _tab = tab;
        LastChildFill = true;
        var noun = tab == Tab.LEARN ? "something to learn" : "a task";
        _quick = Ui.Input(placeholder: $"Add {noun} — type and press Enter", onEnter: QuickAdd);
        _search = Ui.Input(placeholder: "Search", width: 200);
        _search.TextChanged += (_, _) => Refresh();

        var title = Ui.Stack(Ui.H1(TabNames.Title(tab)), _count);
        var right = Ui.Row(
            Ui.Chip("Active", "view" + tab, true, () => { _doneView = false; Refresh(); }),
            Ui.Chip("Done", "view" + tab, false, () => { _doneView = true; Refresh(); }),
            new Border { Width = 12 },
            _search,
            new Border { Width = 8 },
            Ui.Primary("+ New", () => ItemEditor.New(_tab), "New with all details (Ctrl+N)"));
        var bar = Ui.Columns("*,Auto", title, right);
        bar.Margin = new Thickness(0, 0, 0, 10);
        SetDock(bar, Dock.Top);
        Children.Add(bar);

        var add = Ui.Columns("*,Auto", _quick, Ui.Btn("Add", QuickAdd));
        ((Button)add.Children[1]).Margin = new Thickness(8, 0, 0, 0);
        add.Margin = new Thickness(0, 0, 0, 8);
        SetDock(add, Dock.Top);
        Children.Add(add);

        Children.Add(Ui.Scroll(_list));
        KeyDown += (_, e) =>
        {
            if (e.Key == System.Windows.Input.Key.N && System.Windows.Input.Keyboard.Modifiers == System.Windows.Input.ModifierKeys.Control)
            {
                ItemEditor.New(_tab);
                e.Handled = true;
            }
        };
    }

    private void QuickAdd()
    {
        var text = _quick.Text.Trim();
        if (text.Length == 0) return;
        var state = AppState.Current;
        if (ItemRules.DupActiveMatch(state.Data.Items, _tab, text) && !Ui.Confirm($"\"{text}\" is already on your {TabNames.Title(_tab)} list. Add it again?")) return;
        state.Upsert(state.NewItem(_tab, text));
        _quick.Clear();
        App.Current.Main.Snack($"Added to {TabNames.Title(_tab)}");
    }

    public void Refresh()
    {
        var state = AppState.Current;
        long now = state.Now;
        var q = _search.Text.Trim();
        var live = state.LiveItems(_tab).ToList();
        var shown = live.Where(i => i.Done == _doneView)
            .Where(i => q.Length == 0 || i.Title.Contains(q, StringComparison.OrdinalIgnoreCase) || i.Notes.Contains(q, StringComparison.OrdinalIgnoreCase)
                || (i.Topic ?? "").Contains(q, StringComparison.OrdinalIgnoreCase))
            .ToList();
        int open = live.Count(i => !i.Done);
        int today = ItemRules.PendingTodayCount(live, _tab, now);
        _count.Text = $"{open} active · {today} due today or overdue · {live.Count(i => i.Done)} done";

        _list.Children.Clear();
        if (shown.Count == 0)
        {
            _list.Children.Add(Ui.EmptyState(_doneView ? "Nothing done yet" : "All clear",
                _doneView ? "Ticked items land here. Recurring ones come back on their next day." : "Type above and press Enter, or use + New for dates, repeats and priority."));
            return;
        }
        foreach (var g in ItemRules.GroupByDay(shown, _doneView, now))
        {
            var header = Ui.Header($"{g.Label} · {g.Items.Count}");
            if (g.Key == "overdue") header.Foreground = Ui.Res("DangerBrush");
            _list.Children.Add(header);
            foreach (var i in g.Items) _list.Children.Add(ItemCard(i, now));
        }
    }

    internal static Border ItemCard(Item i, long now, bool showList = false)
    {
        var state = AppState.Current;
        var check = Ui.Check(i.Done, isChecked =>
        {
            var before = i;
            if (isChecked)
            {
                var msg = state.Complete(i);
                App.Current.Main.Snack(msg, () => state.Upsert(before));
            }
            else
            {
                state.Revive(i);
                App.Current.Main.Snack("Back to Active", () => state.Upsert(before));
            }
        }, i.Done ? "Back to Active" : (i.Tab == Tab.SHOP ? "Bought" : "Done"));

        var titleRow = new WrapPanel();
        var title = Ui.Text(i.Title, 15, FontWeights.SemiBold);
        if (i.Done) { title.TextDecorations = TextDecorations.Strikethrough; title.Foreground = Ui.Res("InkSubtleBrush"); }
        title.Margin = new Thickness(0, 0, 8, 0);
        titleRow.Children.Add(title);
        if (Ui.PriorityTag(i.Priority) is Border pt) titleRow.Children.Add(pt);
        if (Recurrence.Label(i) is string rep) titleRow.Children.Add(Ui.Tag("↻ " + rep, Ui.Res("AccentInkBrush"), Ui.Res("AccentSoftBrush")));
        if (i.Personal) titleRow.Children.Add(Ui.Tag("🔒 Personal", Ui.Res("InkSubtleBrush"), Ui.Res("SurfaceBrush")));
        if (i.AlertType == "OFF") titleRow.Children.Add(Ui.Tag("🔕 Muted", Ui.Res("InkSubtleBrush"), Ui.Res("SurfaceBrush")));
        if (i.SnoozedUntil is long sn && sn > now) titleRow.Children.Add(Ui.Tag("💤 " + Clock.FormatTime(sn), Ui.Res("AmberBrush"), Ui.Res("AmberSoftBrush")));

        var meta = new List<string>();
        var due = Ui.DueText(i.DueAt, i.DueHasTime, now);
        if (due.Length > 0) meta.Add((i.Done && i.RepeatMode != "OFF" ? "Returns " : "") + due);
        if (i.Tab == Tab.SHOP)
        {
            if (ShopLists.QtySegment(i) is string qs) meta.Add(qs);
            if (!string.IsNullOrWhiteSpace(i.ShopName)) meta.Add("🏪 " + i.ShopName);
            if (ShopLists.ParseNum(i.Price) is double p && p > 0) meta.Add("₹" + p.ToString("#,##0.##", System.Globalization.CultureInfo.InvariantCulture));
            if (showList) meta.Add("📋 " + (ShopLists.ListIdOf(i, state.Settings.ShopLists) is long lid ? state.List(lid)?.Name : "Unsorted"));
            if (i.ReturnAt is long ra) meta.Add("back " + Clock.FormatDay(ra));
        }
        if (i.Tab == Tab.LEARN)
        {
            if (!string.IsNullOrWhiteSpace(i.Platform)) meta.Add(i.Platform!);
            if (!string.IsNullOrWhiteSpace(i.Topic)) meta.Add(i.Topic!);
            if (i.Progress > 0) meta.Add($"{i.Progress}%");
            if (i.HoursSpent > 0) meta.Add($"{i.HoursSpent:0.#} h");
        }
        if (!string.IsNullOrWhiteSpace(i.Notes)) meta.Add(i.Notes.Trim().Replace('\n', ' '));
        var metaText = Ui.Sub(string.Join("  ·  ", meta));
        if (!i.Done && i.DueAt is long d && d < now && i.Tab != Tab.SHOP) metaText.Foreground = Ui.Res("DangerBrush");

        var mid = Ui.Stack(titleRow);
        if (meta.Count > 0) mid.Children.Add(metaText);
        if (i.Tab == Tab.LEARN && i.Progress > 0)
            mid.Children.Add(new ProgressBar { Value = i.Progress, Maximum = 100, Height = 4, Margin = new Thickness(0, 5, 0, 0), Foreground = Ui.Res("AccentBrush"), Background = Ui.Res("SurfaceBrush"), BorderThickness = new Thickness(0) });

        var actions = Ui.Row();
        if (!string.IsNullOrWhiteSpace(i.Url)) actions.Children.Add(Ui.IconBtn("🔗", () => InstallInfo.OpenUrl(i.Url!), "Open link"));
        if (!i.Done && i.DueAt != null)
            actions.Children.Add(Ui.IconBtn("💤", () =>
            {
                state.Snooze(i, state.Settings.SnoozeMinutes);
                App.Current.Main.Snack($"Snoozed {state.Settings.SnoozeMinutes} min", () => state.Upsert(i));
            }, $"Snooze {state.Settings.SnoozeMinutes} min"));
        actions.Children.Add(Ui.IconBtn("✎", () => ItemEditor.Edit(i), "Edit"));
        actions.Children.Add(Ui.IconBtn("🗑", () =>
        {
            state.SoftDelete(new[] { i });
            App.Current.Main.Snack("Moved to the Bin", () => state.Upsert(i));
        }, "Delete (to the Bin for 30 days)"));

        var grid = Ui.Columns("Auto,*,Auto", check, mid, actions);
        var card = Ui.Card(grid, () => ItemEditor.Edit(i));
        if (i.Personal) card.BorderBrush = Ui.Res("AccentSoftBrush");
        return card;
    }
}
