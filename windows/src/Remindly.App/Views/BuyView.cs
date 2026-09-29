using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using Remindly.App.Services;
using Remindly.Core;

namespace Remindly.App.Views;

/// <summary>
/// 2.11 — the Buy tab opens on your LISTS. Each card shows what is left to buy, progress, where the
/// items are bought, an estimated total and the shopping day. Inside a list everything you add
/// belongs to it; items can be grouped by date, shop, category or priority; the list can be shared
/// ("Groceries:-" + numbered lines) by copy or one click to WhatsApp. Buy Now shows every open item
/// from every list, grouped by list, optionally for one shop.
/// </summary>
public sealed class BuyView : DockPanel, IPage
{
    private long? _open;            // null = Lists screen
    private bool _doneView;
    private string? _buyNowShop;
    private readonly ContentControl _body = new() { Focusable = false };

    public BuyView()
    {
        Children.Add(_body);
    }

    public void OpenList(long id)
    {
        _open = id;
        _doneView = false;
        Refresh();
    }

    public void BackToLists()
    {
        _open = null;
        Refresh();
    }

    public void Refresh()
    {
        var state = AppState.Current;
        if (_open is long id && id >= 0 && state.List(id) == null) _open = null; // deleted meanwhile
        _body.Content = _open == null ? ListsScreen(state) : ListPage(state, _open.Value);
    }

    // ═════════════════════════ Lists screen ═════════════════════════

    private UIElement ListsScreen(AppState state)
    {
        long now = state.Now;
        var s = state.Settings;
        var shopNames = state.ShopNamesById();
        var stats = state.Lists.ToDictionary(l => l.Id, l => ShopLists.Stats(state.ItemsIn(l.Id), shopNames, l.UpdatedAt));
        var sorted = ShopLists.Sorted(state.Settings.ShopLists, s.ListSort, l => stats.TryGetValue(l.Id, out var st) ? st.LastActivity : 0);
        var unsorted = state.ItemsIn(ShopLists.UnsortedListId);
        int toBuy = state.LiveItems(Tab.SHOP).Count(i => !i.Done);

        var root = new DockPanel();
        var bar = Ui.Columns("*,Auto",
            Ui.Stack(Ui.H1("Buy"), Ui.Sub($"{sorted.Count} list(s) · {toBuy} to buy")),
            Ui.Row(
                Ui.Sub("Sort "),
                Ui.Chip("Recent", "listsort", s.ListSort == "RECENT", () => state.UpdateSettings(x => x with { ListSort = "RECENT" })),
                Ui.Chip("A–Z", "listsort", s.ListSort == "AZ", () => state.UpdateSettings(x => x with { ListSort = "AZ" })),
                Ui.Chip("Custom", "listsort", s.ListSort == "CUSTOM", () => state.UpdateSettings(x => x with { ListSort = "CUSTOM" })),
                new Border { Width = 12 },
                Ui.Btn("🛍 Buy Now", () => OpenList(ShopLists.BuyNowListId), "Every open item from every list, grouped by list"),
                Ui.Primary("+ New list", () => ListEditor.OpenFor(null))));
        bar.Margin = new Thickness(0, 0, 0, 12);
        SetDock(bar, Dock.Top);
        root.Children.Add(bar);

        var wrap = new WrapPanel();
        if (sorted.Count == 0 && unsorted.Count == 0)
        {
            var empty = Ui.Stack(Ui.EmptyState("Make a list first",
                "Groceries, Monthly stock, a party… then add items inside it. Each list gets its own share, shopping day and usual shop."));
            var nb = Ui.Primary("+ New list", () => ListEditor.OpenFor(null));
            nb.HorizontalAlignment = HorizontalAlignment.Center;
            empty.Children.Add(nb);
            root.Children.Add(empty);
            return root;
        }
        foreach (var l in sorted) wrap.Children.Add(ListCard(state, l, stats[l.Id], now, s.ListSort == "CUSTOM"));
        if (unsorted.Count > 0)
            wrap.Children.Add(ListCard(state, new ShopList { Id = ShopLists.UnsortedListId, Name = "Unsorted", Icon = "🗂" },
                ShopLists.Stats(unsorted, shopNames), now, false));
        root.Children.Add(Ui.Scroll(wrap));
        return root;
    }

    private Border ListCard(AppState state, ShopList l, ListStats st, long now, bool customSort)
    {
        bool real = l.Id >= 0;
        var head = Ui.Columns("Auto,*,Auto",
            new TextBlock { Text = l.Icon ?? "🛒", FontSize = 24, Margin = new Thickness(0, 0, 10, 0), VerticalAlignment = VerticalAlignment.Center },
            Ui.Stack(
                Ui.Row(Ui.Text(l.Name, 16, FontWeights.SemiBold, wrap: false), l.Pinned ? Ui.Text(" 📌", 13) : new TextBlock()),
                Ui.Sub(ShopLists.CardSubtitle(st, now))),
            real ? ListMenuButton(state, l) : new TextBlock());

        int total = st.ToBuy + st.Done;
        var progress = new ProgressBar
        {
            Maximum = Math.Max(1, total), Value = st.Done, Height = 5, Margin = new Thickness(0, 8, 0, 6),
            Foreground = Ui.Res("SuccessBrush"), Background = Ui.Res("SurfaceBrush"), BorderThickness = new Thickness(0),
        };
        var info = new WrapPanel();
        if (st.Shops.Count > 0) info.Children.Add(Ui.Tag("🏪 " + string.Join(" · ", st.Shops), Ui.Res("InkSubtleBrush"), Ui.Res("SurfaceBrush")));
        if (ShopLists.EstLabel(st.EstTotal) is string est) info.Children.Add(Ui.Tag(est, Ui.Res("SuccessBrush"), Ui.Res("SuccessSoftBrush")));
        if (l.ShoppingDay is long day) info.Children.Add(Ui.Tag("🗓 " + Clock.FormatDay(day), Ui.Res("AmberBrush"), Ui.Res("AmberSoftBrush")));
        if (l.Personal) info.Children.Add(Ui.Tag("🔒 Private", Ui.Res("InkSubtleBrush"), Ui.Res("SurfaceBrush")));
        if (real && state.Settings.ShopDefaultListId == l.Id) info.Children.Add(Ui.Tag("Default", Ui.Res("AccentInkBrush"), Ui.Res("AccentSoftBrush")));

        var body = Ui.Stack(head, progress, info);
        if (customSort && real)
        {
            var mv = Ui.Row(Ui.IconBtn("▲", () => state.MoveList(l, up: true), "Move up"), Ui.IconBtn("▼", () => state.MoveList(l, up: false), "Move down"));
            mv.HorizontalAlignment = HorizontalAlignment.Right;
            body.Children.Add(mv);
        }
        var card = Ui.Card(body);
        card.Width = 320;
        card.Margin = new Thickness(0, 0, 12, 12);
        card.Cursor = Cursors.Hand;
        card.MouseLeftButtonUp += (_, e) =>
        {
            if (e.OriginalSource is DependencyObject d && FindParent<Button>(d) != null) return;
            OpenList(l.Id);
        };
        if (real) card.ContextMenu = ListContextMenu(state, l);
        return card;
    }

    private static T? FindParent<T>(DependencyObject d) where T : DependencyObject
    {
        while (d != null)
        {
            if (d is T t) return t;
            d = d is System.Windows.Media.Visual or System.Windows.Media.Media3D.Visual3D
                ? System.Windows.Media.VisualTreeHelper.GetParent(d) ?? LogicalTreeHelper.GetParent(d)
                : LogicalTreeHelper.GetParent(d);
        }
        return null;
    }

    // ═════════════════════════ list menu ═════════════════════════

    private (string, Action?)[] ListActions(AppState state, ShopList l) => new (string, Action?)[]
    {
        ("💬 Send to WhatsApp", () => ShareWindow.WhatsApp(l.Id)),
        ("📤 Share (copy the text)", () => ShareWindow.Copy(l.Id)),
        ("👁 Preview and change what is shared…", () => ShareWindow.OpenFor(l.Id)),
        ("-", null),
        (l.Pinned ? "Unpin" : "📌 Pin", () => state.TogglePin(l)),
        ("✎ Edit list (name, icon, shop, day, private)…", () => ListEditor.OpenFor(l)),
        ("⭐ Make default list", state.Settings.ShopDefaultListId == l.Id ? null : () => state.UpdateSettings(s => ShopLists.MirrorIntoSettings(s with { ShopDefaultListId = l.Id }))),
        ("⧉ Duplicate", () =>
        {
            var copy = state.DuplicateList(l);
            App.Current.Main.Snack($"Made \"{copy.Name}\"");
        }),
        ("↺ Restart (bought items back to To buy)", () =>
        {
            var snap = state.TakeSnapshot();
            int n = state.RestartList(l.Id);
            App.Current.Main.Snack(n == 0 ? "Nothing was bought yet" : $"{n} item(s) back to To buy", n == 0 ? null : () => state.Restore(snap));
        }),
        ("✓ Mark all bought", () =>
        {
            var snap = state.TakeSnapshot();
            int n = state.MarkAllBought(l.Id);
            App.Current.Main.Snack(n == 0 ? "Everything is already bought" : $"{n} item(s) marked bought", n == 0 ? null : () => state.Restore(snap));
        }),
        ("⇢ Merge into another list…", state.Lists.Count < 2 ? null : () => PickListWindow.Pick($"Merge \"{l.Name}\" into…", state.Lists.Where(x => x.Id != l.Id).ToList(), target =>
        {
            var snap = state.TakeSnapshot();
            state.MergeList(l, target);
            if (_open == l.Id) _open = target.Id;
            App.Current.Main.Snack($"Merged into {target.Name}", () => state.Restore(snap));
        })),
        ("-", null),
        ("🗑 Delete list…", () => DeleteListWindow.OpenFor(l, () => { if (_open == l.Id) BackToLists(); })),
    };

    private Button ListMenuButton(AppState state, ShopList l) => Ui.MenuButton("⋮", "List menu", ListActions(state, l));

    private ContextMenu ListContextMenu(AppState state, ShopList l)
    {
        var m = new ContextMenu();
        foreach (var (h, a) in ListActions(state, l))
        {
            if (h == "-") { m.Items.Add(new Separator()); continue; }
            var mi = new MenuItem { Header = h, IsEnabled = a != null };
            if (a != null) mi.Click += (_, _) => a();
            m.Items.Add(mi);
        }
        return m;
    }

    // ═════════════════════════ inside a list ═════════════════════════

    private UIElement ListPage(AppState state, long id)
    {
        long now = state.Now;
        bool buyNow = id == ShopLists.BuyNowListId, unsorted = id == ShopLists.UnsortedListId;
        var list = id >= 0 ? state.List(id) : null;
        var name = buyNow ? "Buy Now" : unsorted ? "Unsorted" : list!.Name;
        var icon = buyNow ? "🛍" : unsorted ? "🗂" : list!.Icon ?? "🛒";
        var items = state.ItemsIn(id);
        if (buyNow) items = items.Where(i => !i.Done).ToList();
        var shopNames = state.ShopNamesById();
        var st = ShopLists.Stats(items, shopNames, list?.UpdatedAt ?? 0);

        var root = new DockPanel();
        var titleBlock = Ui.Stack(
            Ui.Row(new TextBlock { Text = icon, FontSize = 24, Margin = new Thickness(0, 0, 10, 0) }, Ui.H1(name)),
            Ui.Sub(buyNow ? "Every open item from every list" + (_buyNowShop != null ? $" at {_buyNowShop}" : "") : ShopLists.CardSubtitle(st, now)
                + (ShopLists.EstLabel(st.EstTotal) is string e ? "  ·  " + e : "")));
        var right = Ui.Row();
        if (!buyNow)
        {
            right.Children.Add(Ui.Btn("📤 Share", () => ShareWindow.Copy(id), "Copy the list as text"));
            var wa = Ui.Btn("💬 WhatsApp", () => ShareWindow.WhatsApp(id), "Send to WhatsApp (right-click: preview and options)");
            wa.MouseRightButtonUp += (_, ev) => { ShareWindow.OpenFor(id); ev.Handled = true; };
            right.Children.Add(wa);
        }
        if (list != null) right.Children.Add(ListMenuButton(state, list));
        var back = Ui.Btn("← Lists", BackToLists);
        back.VerticalAlignment = VerticalAlignment.Top;
        back.Margin = new Thickness(0, 4, 14, 0);
        var bar = Ui.Columns("Auto,*,Auto", back, titleBlock, right);
        bar.Margin = new Thickness(0, 0, 0, 10);
        SetDock(bar, Dock.Top);
        root.Children.Add(bar);

        // view + grouping row
        var group = state.Settings.ListInnerGroup;
        var controls = Ui.Row();
        if (!buyNow)
        {
            controls.Children.Add(Ui.Chip("To buy", "buyview", !_doneView, () => { _doneView = false; Refresh(); }));
            controls.Children.Add(Ui.Chip("Bought", "buyview", _doneView, () => { _doneView = true; Refresh(); }));
            controls.Children.Add(new Border { Width = 16 });
            controls.Children.Add(Ui.Sub("Group by "));
            var groupBox = new ComboBox { Width = 130, Margin = new Thickness(6, 0, 0, 0) };
            foreach (var g in new[] { "Date", "Shop", "Category", "Priority", "None" }) groupBox.Items.Add(g);
            groupBox.SelectedItem = group switch { "SHOP" => "Shop", "CATEGORY" => "Category", "PRIORITY" => "Priority", "NONE" => "None", _ => "Date" };
            groupBox.SelectionChanged += (_, _) =>
            {
                var code = ((string)groupBox.SelectedItem).ToUpperInvariant();
                state.UpdateSettings(x => x with { ListInnerGroup = code });
            };
            controls.Children.Add(groupBox);
        }
        else
        {
            var shops = items.Select(i => i.ShopId is long sid && shopNames.TryGetValue(sid, out var sn) ? sn : i.ShopName)
                .Where(n => !string.IsNullOrWhiteSpace(n)).Select(n => n!.Trim()).Distinct(StringComparer.OrdinalIgnoreCase).OrderBy(n => n).ToList();
            controls.Children.Add(Ui.Sub("Shop "));
            var shopBox = new ComboBox { Width = 200, Margin = new Thickness(6, 0, 0, 0) };
            shopBox.Items.Add("All shops");
            foreach (var sname in shops) shopBox.Items.Add(sname);
            shopBox.SelectedItem = _buyNowShop != null && shops.Contains(_buyNowShop) ? _buyNowShop : "All shops";
            shopBox.SelectionChanged += (_, _) => { _buyNowShop = shopBox.SelectedItem as string == "All shops" ? null : shopBox.SelectedItem as string; Refresh(); };
            controls.Children.Add(shopBox);
            if (_buyNowShop != null)
            {
                var here = items.Where(i => string.Equals(ShopOf(i, shopNames), _buyNowShop, StringComparison.OrdinalIgnoreCase)).ToList();
                controls.Children.Add(new Border { Width = 12 });
                controls.Children.Add(Ui.Btn($"✓ Bought everything here ({here.Count})", () =>
                {
                    var snap = state.TakeSnapshot();
                    state.UpsertMany(here.Select(i => ItemRules.Complete(i, state.Now).Item));
                    App.Current.Main.Snack($"{here.Count} item(s) bought at {_buyNowShop}", () => state.Restore(snap));
                }));
            }
        }
        controls.Margin = new Thickness(0, 0, 0, 8);
        SetDock(controls, Dock.Top);
        root.Children.Add(controls);

        // quick add (not in Buy Now)
        if (!buyNow && !_doneView) root.Children.Add(QuickAdd(state, id, list, items));

        // the items
        var body = new StackPanel();
        var shown = buyNow
            ? (_buyNowShop == null ? items : items.Where(i => string.Equals(ShopOf(i, shopNames), _buyNowShop, StringComparison.OrdinalIgnoreCase)).ToList())
            : items.Where(i => i.Done == _doneView).ToList();
        if (shown.Count == 0)
            body.Children.Add(Ui.EmptyState(_doneView ? "Nothing bought yet" : buyNow ? "Nothing to buy" : "This list is empty",
                _doneView ? "Ticked items land here. Restart the list from its menu to buy them again." : "Type an item above and press Enter."));
        else
        {
            var groups = buyNow ? ShopLists.ListGroupsOf(shown, state.Settings.ShopLists) : Grouped(shown, group, state);
            foreach (var (gname, gitems) in groups)
            {
                if (groups.Count > 1 || group != "NONE" || buyNow) body.Children.Add(Ui.Header($"{gname} · {gitems.Count}"));
                foreach (var i in gitems) body.Children.Add(ItemsView.ItemCard(i, now, showList: buyNow));
            }
        }
        root.Children.Add(Ui.Scroll(body));
        return root;
    }

    private static string? ShopOf(Item i, Dictionary<long, string> shopNames) =>
        (i.ShopId is long sid && shopNames.TryGetValue(sid, out var sn) ? sn : i.ShopName)?.Trim();

    private static List<(string Name, List<Item> Items)> Grouped(List<Item> items, string mode, AppState state)
    {
        long Basis(Item i) => i.DueAt ?? i.CreatedAt;
        switch (mode)
        {
            case "SHOP": return ShopLists.ShopGroupsOf(items).Select(g => (g.Name, g.Items.OrderBy(Basis).ToList())).ToList();
            case "CATEGORY": return ShopLists.CategoryGroupsOf(items, state.Data.Products.GroupBy(p => p.Id).ToDictionary(g => g.Key, g => g.First())).Select(g => (g.Name, g.Items.OrderBy(Basis).ToList())).ToList();
            case "PRIORITY":
                return items.GroupBy(i => PriorityNames.Rank(i.Priority)).OrderBy(g => g.Key)
                    .Select(g => (PriorityNames.Label(g.First().Priority), g.OrderBy(Basis).ToList())).ToList();
            case "NONE": return new() { ("All", items.OrderBy(i => i.CreatedAt).ThenBy(i => i.Id).ToList()) };
            default:
                return ItemRules.GroupByDay(items, false, state.Now).Select(g => (g.Label, g.Items)).ToList();
        }
    }

    /// <summary>Quick add with product suggestions, "recently bought in this list" and the other-list warning.</summary>
    private UIElement QuickAdd(AppState state, long id, ShopList? list, List<Item> listItems)
    {
        var box = Ui.Input(placeholder: $"Add to {(list?.Name ?? "Unsorted")} — type and press Enter");
        var suggestions = new WrapPanel { Margin = new Thickness(0, 4, 0, 0) };
        var warn = Ui.Sub("");
        warn.Foreground = Ui.Res("AmberBrush");

        void Add(string title, Product? product = null, Item? like = null)
        {
            title = title.Trim();
            if (title.Length == 0) return;
            var item = state.NewItem(Tab.SHOP, title) with
            {
                ListId = list?.Id, Group = list?.Name, Personal = list?.Personal == true,
                ProductId = product?.Id ?? like?.ProductId, Unit = product?.DefaultUnit ?? like?.Unit,
                Quantity = like?.Quantity, ShopName = like?.ShopName, ShopId = like?.ShopId, Price = like?.Price,
            };
            if (list?.UsualShopId is long sid && item.ShopName == null && state.Shops.FirstOrDefault(s => s.Id == sid) is Shop us)
                item = item with { ShopId = us.Id, ShopName = us.Name };
            state.Upsert(item);
            App.Current.Main.Snack($"Added to {list?.Name ?? "Unsorted"}", () => state.DeleteForever(item));
            Dispatcher.BeginInvoke(() => FocusQuickAdd());
        }

        box.TextChanged += (_, _) =>
        {
            suggestions.Children.Clear();
            var q = box.Text.Trim();
            var other = ShopLists.OtherListHolding(state.Data.Items, q, list?.Id, state.Settings.ShopLists);
            warn.Text = other != null ? $"\"{q}\" is already on your {other.Name} list." : "";
            foreach (var r in ShopLists.RecentInList(listItems, q))
                suggestions.Children.Add(Ui.Btn("↺ " + r.Title, () => { box.Clear(); Add(r.Title, like: r); }, "Recently bought in this list"));
            foreach (var p in ShopLists.ProductSuggestions(q, state.Products).Where(p => !suggestions.Children.OfType<Button>().Any(b => (b.Content as string)?.EndsWith(p.Name) == true)))
                suggestions.Children.Add(Ui.Btn("＋ " + p.Name + (p.Category != null ? $" ({p.Category})" : ""), () => { box.Clear(); Add(p.Name, product: p); }, "From your products"));
        };
        box.KeyDown += (_, e) =>
        {
            if (e.Key != Key.Enter) return;
            var t = box.Text;
            box.Clear();
            Add(t, product: state.Products.FirstOrDefault(p => string.Equals(p.Name, t.Trim(), StringComparison.OrdinalIgnoreCase)));
            e.Handled = true;
        };
        box.Name = "QuickAddBox";
        var addRow = Ui.Columns("*,Auto,Auto", box,
            Ui.Btn("Add", () => { var t = box.Text; box.Clear(); Add(t); }),
            Ui.Btn("More…", () => ItemEditor.New(Tab.SHOP, list?.Id ?? ShopLists.UnsortedListId, box.Text.Trim()), "Quantity, price, shop, product, return-after…"));
        ((Button)addRow.Children[1]).Margin = new Thickness(8, 0, 6, 0);
        var panel = Ui.Stack(addRow, warn, suggestions);
        panel.Margin = new Thickness(0, 0, 0, 8);
        SetDock(panel, Dock.Top);
        return panel;
    }

    private void FocusQuickAdd()
    {
        if (_body.Content is DependencyObject root && FindChild<TextBox>(root, "QuickAddBox") is TextBox tb) tb.Focus();
    }

    private static T? FindChild<T>(DependencyObject parent, string name) where T : FrameworkElement
    {
        for (int i = 0; i < System.Windows.Media.VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var c = System.Windows.Media.VisualTreeHelper.GetChild(parent, i);
            if (c is T t && t.Name == name) return t;
            if (FindChild<T>(c, name) is T found) return found;
        }
        return null;
    }
}
