using System.Windows;
using System.Windows.Controls;
using Remindly.App.Services;
using Remindly.Core;

namespace Remindly.App.Views;

/// <summary>Shops: registered shops grouped by city (chain branches show their chain). Geofences stay on the phone.</summary>
public sealed class ShopsView : DockPanel, IPage
{
    private readonly StackPanel _list = new();
    private readonly TextBlock _count = Ui.Sub("");

    public ShopsView()
    {
        var bar = Ui.Columns("*,Auto", Ui.Stack(Ui.H1("Shops"), _count), Ui.Primary("+ Shop", () => ShopEditor.OpenFor(null)));
        bar.Margin = new Thickness(0, 0, 0, 10);
        SetDock(bar, Dock.Top);
        Children.Add(bar);
        Children.Add(Ui.Scroll(_list));
    }

    public void Refresh()
    {
        var state = AppState.Current;
        var shops = state.Shops;
        var open = state.LiveItems(Tab.SHOP).Where(i => !i.Done).ToList();
        _count.Text = $"{shops.Count} shop(s) · arrival alerts and geofences are set on the phone";
        _list.Children.Clear();
        if (shops.Count == 0)
        {
            _list.Children.Add(Ui.EmptyState("No shops yet", "Add the shops you buy from; Buy items and lists can then point at them, and Buy Now can show one shop's items."));
            return;
        }
        foreach (var g in shops.GroupBy(s => state.CityName(s.CityId)).OrderBy(g => g.Key.Length == 0).ThenBy(g => g.Key))
        {
            _list.Children.Add(Ui.Header(g.Key.Length == 0 ? "No city" : g.Key));
            foreach (var s in g)
            {
                int n = open.Count(i => i.ShopId == s.Id || string.Equals(i.ShopName?.Trim(), s.Name, StringComparison.OrdinalIgnoreCase));
                var tags = new WrapPanel();
                if (s.IsDefault) tags.Children.Add(Ui.Tag("Default", Ui.Res("AccentInkBrush"), Ui.Res("AccentSoftBrush")));
                var chain = state.ChainName(s.ChainId);
                tags.Children.Add(Ui.Tag(chain.Length > 0 ? "Chain · " + chain : "Local", Ui.Res("InkSubtleBrush"), Ui.Res("SurfaceBrush")));
                if (s.Lat != null) tags.Children.Add(Ui.Tag("📍 Geofence", Ui.Res("SuccessBrush"), Ui.Res("SuccessSoftBrush")));
                var meta = Ui.Sub((string.IsNullOrWhiteSpace(s.Area) ? "" : s.Area + " · ") + (n == 0 ? "nothing to buy here" : $"{n} item(s) to buy here"));
                var actions = Ui.Row(
                    Ui.IconBtn("🛍", () => { App.Current.Main.Buy.OpenList(ShopLists.BuyNowListId); }, "Buy Now"),
                    Ui.IconBtn("✎", () => ShopEditor.OpenFor(s), "Edit"),
                    Ui.IconBtn("🗑", () =>
                    {
                        if (!Ui.Confirm($"Delete the shop \"{s.Name}\"? Items keep the shop name as text.")) return;
                        state.DeleteShop(s);
                        App.Current.Main.Snack($"Deleted {s.Name}", () => state.UpsertShop(s));
                    }, "Delete"));
                _list.Children.Add(Ui.Card(Ui.Columns("*,Auto", Ui.Stack(Ui.Row(Ui.Text(s.Name, 15, FontWeights.SemiBold), new Border { Width = 8 }, tags), meta), actions), () => ShopEditor.OpenFor(s)));
            }
        }
    }
}

public sealed class ShopEditor : EditorWindow
{
    private readonly Shop _orig;
    private readonly bool _isNew;
    private readonly TextBox _name = new(), _area = new();
    private readonly ComboBox _city, _chain;
    private readonly CheckBox _default = new() { Content = "Default shop for new Buy items" };

    public static void OpenFor(Shop? s) => new ShopEditor(s ?? new Shop { Id = Ids.Next() }, s == null).Open();

    private ShopEditor(Shop s, bool isNew) : base(isNew ? "New shop" : "Edit shop", 440)
    {
        _orig = s;
        _isNew = isNew;
        var state = AppState.Current;
        _name.Text = s.Name;
        Field("Name", _name);
        _city = Combo(state.Data.Cities.Where(c => c.DeletedAt == null).Select(c => c.Name).OrderBy(n => n), state.CityName(s.CityId), editable: true);
        _chain = Combo(state.Data.Chains.Where(c => c.DeletedAt == null).Select(c => c.Name).OrderBy(n => n), state.ChainName(s.ChainId), editable: true);
        Form.Children.Add(TwoColumns(Labeled("City", _city), Labeled("Chain (D-Mart, Reliance…) — empty = local shop", _chain)));
        _area.Text = s.Area ?? "";
        Field("Area / landmark / PIN", _area);
        _default.IsChecked = s.IsDefault;
        _default.Margin = new Thickness(0, 10, 0, 0);
        Form.Children.Add(_default);
        AddButtons(isNew ? "Add" : "Save");
        Loaded += (_, _) => _name.Focus();
    }

    protected override bool Save()
    {
        var state = AppState.Current;
        var name = _name.Text.Trim();
        if (name.Length == 0) { ShowWarning("Give the shop a name."); return false; }
        if (state.Shops.Any(x => x.Id != _orig.Id && string.Equals(x.Name, name, StringComparison.OrdinalIgnoreCase))) { ShowWarning($"There is already a shop called {name}."); return false; }
        var city = _city.Text.Trim().Length > 0 ? state.CityNamed(_city.Text) : null;
        var chain = _chain.Text.Trim().Length > 0 ? state.ChainNamed(_chain.Text) : null;
        state.UpsertShop(_orig with { Name = name, Area = string.IsNullOrWhiteSpace(_area.Text) ? null : _area.Text.Trim(), CityId = city?.Id, ChainId = chain?.Id, IsDefault = _default.IsChecked == true });
        if (!_isNew && _orig.Name != name)
        {
            // Items that named the old shop follow the rename.
            state.UpsertMany(state.LiveItems(Tab.SHOP).Where(i => i.ShopId == _orig.Id || string.Equals(i.ShopName?.Trim(), _orig.Name, StringComparison.OrdinalIgnoreCase))
                .Select(i => i with { ShopName = name, ShopId = _orig.Id }).ToList());
        }
        App.Current.Main.Snack(_isNew ? $"Added {name}" : "Saved");
        return true;
    }
}

/// <summary>Products: the catalogue Buy items link to (suggestions while typing, category grouping).</summary>
public sealed class ProductsView : DockPanel, IPage
{
    private readonly StackPanel _list = new();
    private readonly TextBlock _count = Ui.Sub("");
    private readonly TextBox _search = Ui.Input(placeholder: "Search products", width: 220);

    public ProductsView()
    {
        _search.TextChanged += (_, _) => Refresh();
        var bar = Ui.Columns("*,Auto", Ui.Stack(Ui.H1("Products"), _count), Ui.Row(_search, new Border { Width = 8 }, Ui.Primary("+ Product", () => ProductEditor.OpenFor(null))));
        bar.Margin = new Thickness(0, 0, 0, 10);
        SetDock(bar, Dock.Top);
        Children.Add(bar);
        Children.Add(Ui.Scroll(_list));
    }

    public void Refresh()
    {
        var state = AppState.Current;
        var q = _search.Text.Trim();
        var all = state.Products;
        var shown = all.Where(p => q.Length == 0 || p.Name.Contains(q, StringComparison.OrdinalIgnoreCase) || (p.Category ?? "").Contains(q, StringComparison.OrdinalIgnoreCase)).ToList();
        _count.Text = $"{all.Count} product(s)";
        _list.Children.Clear();
        if (shown.Count == 0)
        {
            _list.Children.Add(Ui.EmptyState(all.Count == 0 ? "No products yet" : "No match", "Products are suggested while you add Buy items, and group a list By category."));
            return;
        }
        var items = state.LiveItems(Tab.SHOP).ToList();
        foreach (var g in shown.GroupBy(p => p.Category ?? "").OrderBy(g => g.Key.Length == 0).ThenBy(g => g.Key))
        {
            _list.Children.Add(Ui.Header(g.Key.Length == 0 ? "No category" : g.Key));
            foreach (var p in g)
            {
                var lastBuy = items.Where(i => i.ProductId == p.Id).SelectMany(i => i.PriceHistory).OrderByDescending(pp => pp.At).FirstOrDefault();
                var meta = new List<string>();
                if (p.DefaultUnit != null) meta.Add("per " + p.DefaultUnit);
                if (lastBuy != null && lastBuy.Price > 0) meta.Add($"last ₹{lastBuy.Price:#,##0.##}" + (lastBuy.Shop.Length > 0 ? " at " + lastBuy.Shop : ""));
                if (!string.IsNullOrWhiteSpace(p.Note)) meta.Add(p.Note!);
                var actions = Ui.Row(
                    Ui.IconBtn("＋", () =>
                    {
                        var list = state.Settings.ShopDefaultListId is long d ? state.List(d) : null;
                        state.Upsert(state.NewItem(Tab.SHOP, p.Name) with { ProductId = p.Id, Unit = p.DefaultUnit, ListId = list?.Id, Group = list?.Name, Personal = list?.Personal == true });
                        App.Current.Main.Snack($"Added {p.Name} to {list?.Name ?? "Unsorted"}");
                    }, "Add to the default list"),
                    Ui.IconBtn("✎", () => ProductEditor.OpenFor(p), "Edit"),
                    Ui.IconBtn("🗑", () =>
                    {
                        state.DeleteProduct(p);
                        App.Current.Main.Snack($"Deleted {p.Name}", () => state.UpsertProduct(p));
                    }, "Delete"));
                _list.Children.Add(Ui.Card(Ui.Columns("*,Auto", Ui.Stack(Ui.Text(p.Name, 15, FontWeights.SemiBold), Ui.Sub(string.Join("  ·  ", meta))), actions), () => ProductEditor.OpenFor(p)));
            }
        }
    }
}

public sealed class ProductEditor : EditorWindow
{
    private readonly Product _orig;
    private readonly bool _isNew;
    private readonly TextBox _name = new(), _note = new();
    private readonly ComboBox _category, _unit;

    public static void OpenFor(Product? p) => new ProductEditor(p ?? new Product { Id = Ids.Next() }, p == null).Open();

    private ProductEditor(Product p, bool isNew) : base(isNew ? "New product" : "Edit product", 420)
    {
        _orig = p;
        _isNew = isNew;
        var state = AppState.Current;
        _name.Text = p.Name;
        Field("Name", _name);
        _category = Combo(state.Products.Select(x => x.Category).Where(c => c != null).Distinct().OrderBy(c => c)!, p.Category ?? "", editable: true);
        _unit = Combo(new[] { "", "kg", "g", "L", "ml", "pcs", "dozen", "pack", "strip", "box", "bottle" }, p.DefaultUnit ?? "", editable: true);
        Form.Children.Add(TwoColumns(Labeled("Category", _category), Labeled("Default unit", _unit)));
        _note.Text = p.Note ?? "";
        Field("Note", _note);
        AddButtons(isNew ? "Add" : "Save");
        Loaded += (_, _) => _name.Focus();
    }

    protected override bool Save()
    {
        var name = _name.Text.Trim();
        if (name.Length == 0) { ShowWarning("Give the product a name."); return false; }
        string? N(string? t) => string.IsNullOrWhiteSpace(t) ? null : t.Trim();
        AppState.Current.UpsertProduct(_orig with { Name = name, Category = N(_category.Text), DefaultUnit = N(_unit.Text), Note = N(_note.Text) });
        App.Current.Main.Snack(_isNew ? $"Added {name}" : "Saved");
        return true;
    }
}
