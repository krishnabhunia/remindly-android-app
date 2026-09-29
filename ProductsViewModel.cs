using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Remindly.App.Services;
using Remindly.App.Views;
using Remindly.Core.Logic;
using Remindly.Core.Models;

namespace Remindly.App.ViewModels;

public sealed partial class ProductRowViewModel : ObservableObject
{
    private readonly ProductsViewModel _list;
    public Product Product { get; }
    public string Name => Product.Name;
    public string Category => Product.Category ?? "";
    public string Unit => Product.DefaultUnit ?? "";
    public string Shops { get; }
    public string Best { get; }
    public bool HasBest => Best.Length > 0;
    public string Note => Product.Note ?? "";
    public ProductRowViewModel(ProductsViewModel list, Product p, AppServices s)
    {
        _list = list; Product = p;
        var links = s.Repo.LinksFor(p.Id).ToList();
        var live = s.Repo.ActiveShops.Select(x => x.Id).ToHashSet();
        var n = links.Count(l => live.Contains(l.ShopId));
        Shops = n == 0 ? "not linked to a shop yet" : n == 1 ? "at 1 shop" : $"at {n} shops";
        var best = ShopMath.CheapestLink(links, live);
        Best = best != null && s.Repo.Shop(best.ShopId) is { } shop
            ? (best.LastUnitPrice > 0 ? $"Cheapest: {shop.Name} · ₹{ShopMath.FmtUnitPrice(best.LastUnitPrice)}/{p.DefaultUnit ?? "unit"}" : $"Cheapest: {shop.Name} · ₹{ShopMath.TrimNum(best.LastPrice)}")
            : "";
    }
    [RelayCommand] private void Edit() => _list.Edit(this);
    [RelayCommand] private void Delete() => _list.Delete(this);
    [RelayCommand] private void AddToBuy() => _list.AddToBuy(this);
}

public sealed partial class CategoryChipViewModel : ObservableObject
{
    private readonly ProductsViewModel _list;
    public string Name { get; }
    [ObservableProperty] private bool _isOn;
    public CategoryChipViewModel(string name, bool on, ProductsViewModel list) { Name = name; _isOn = on; _list = list; }
    [RelayCommand] private void Pick() => _list.PickCategoryCommand.Execute(Name);
}

/// <summary>Products tab — your own catalogue with the cheapest-shop line (v1.90 "Products").</summary>
public sealed partial class ProductsViewModel : ObservableObject
{
    private readonly AppServices _s;
    private readonly MainViewModel _main;
    private readonly DispatcherTimer _debounce;
    public ObservableCollection<ProductRowViewModel> Rows { get; } = new();
    public ObservableCollection<CategoryChipViewModel> Categories { get; } = new();
    [ObservableProperty] private string _search = "";
    [ObservableProperty] private string? _category;
    [ObservableProperty] private string _summary = "";
    [ObservableProperty] private bool _isEmpty;

    public ProductsViewModel(AppServices s, MainViewModel main)
    {
        _s = s; _main = main;
        _debounce = new DispatcherTimer(DispatcherPriority.Background, s.Dispatcher) { Interval = TimeSpan.FromMilliseconds(120) };
        _debounce.Tick += (_, _) => { _debounce.Stop(); Rebuild(); };
        s.Repo.Changed += c => { if (c is "products" or "productlinks" or "shops") _debounce.Start(); };
        Rebuild();
    }

    partial void OnSearchChanged(string value) => _debounce.Start();
    partial void OnCategoryChanged(string? value) => _debounce.Start();
    [RelayCommand] private void PickCategory(string? c) => Category = c == Category ? null : c;

    public void Rebuild()
    {
        Rows.Clear();
        var cats = _s.Repo.Categories.ToList();
        Categories.Clear(); foreach (var c in cats) Categories.Add(new CategoryChipViewModel(c, string.Equals(c, Category, StringComparison.OrdinalIgnoreCase), this));
        if (Category != null && !cats.Contains(Category, StringComparer.OrdinalIgnoreCase)) Category = null;
        var list = _s.Repo.ActiveProducts.Where(p => ShopMath.ProductMatches(p, Search) && (Category == null || string.Equals(p.Category, Category, StringComparison.OrdinalIgnoreCase))).ToList();
        foreach (var p in list) Rows.Add(new ProductRowViewModel(this, p, _s));
        Summary = $"{list.Count} product(s)";
        IsEmpty = list.Count == 0;
    }

    [RelayCommand] private void Add() => Edit(null);

    public void Edit(ProductRowViewModel? row)
    {
        var p = row?.Product ?? new Product { Id = Ids.Next() };
        var vm = new ProductEditorViewModel(_s, p, isNew: row == null);
        new ProductEditorDialog(vm) { Owner = System.Windows.Application.Current.MainWindow }.ShowDialog();
    }

    public void Delete(ProductRowViewModel row)
    {
        if (!Dialogs.Confirm($"Delete \"{row.Name}\" from your products? Buy items that referenced it keep their text.", destructive: true)) return;
        _s.Repo.DeleteProduct(row.Product.Id);
    }

    public void AddToBuy(ProductRowViewModel row)
    {
        var p = row.Product; var s = _s.Repo.Settings;
        var live = _s.Repo.ActiveShops.Select(x => x.Id).ToHashSet();
        var best = ShopMath.CheapestLink(_s.Repo.LinksFor(p.Id), live);
        var shop = best != null ? _s.Repo.Shop(best.ShopId) : _s.Repo.DefaultShop;
        var due = Alerts.DefaultNewDue(Tab.SHOP, s);
        _s.Repo.Upsert(new Item { Id = Ids.Next(), Tab = Tab.SHOP, Title = p.Name, Unit = p.DefaultUnit, ProductId = p.Id, ShopId = shop?.Id, ShopName = shop?.Name, DueAt = due, DueHasTime = due != null && Alerts.NewDueTimedFor(Tab.SHOP, s) });
        _main.Ack($"Added \"{p.Name}\" to Buy", null);
    }
}

public sealed partial class ProductLinkRowViewModel : ObservableObject
{
    public Shop Shop { get; init; } = null!;
    [ObservableProperty] private bool _isOn;
    [ObservableProperty] private string _priceText = "";
    [ObservableProperty] private string _unitPriceText = "";
    public string LastAt { get; init; } = "";
}

/// <summary>Product editor dialog VM: name, category, unit, note + the shop links with last prices.</summary>
public sealed partial class ProductEditorViewModel : ObservableObject
{
    private readonly AppServices _s;
    public Product Original { get; }
    public bool IsNew { get; }
    public string Heading => IsNew ? "New product" : "Edit product";
    [ObservableProperty] private string _name = "";
    [ObservableProperty] private string _category = "";
    [ObservableProperty] private string _unit = "";
    [ObservableProperty] private string _note = "";
    [ObservableProperty] private string _error = "";
    public ObservableCollection<ProductLinkRowViewModel> Links { get; } = new();
    public List<string> CategoryOptions { get; }
    public string[] Units => Constants.Units;
    public bool Saved { get; private set; }

    public ProductEditorViewModel(AppServices s, Product p, bool isNew)
    {
        _s = s; Original = p; IsNew = isNew;
        Name = p.Name; Category = p.Category ?? ""; Unit = p.DefaultUnit ?? ""; Note = p.Note ?? "";
        CategoryOptions = s.Repo.Categories.ToList();
        var links = s.Repo.LinksFor(p.Id).ToDictionary(l => l.ShopId);
        foreach (var shop in s.Repo.ActiveShops)
        {
            links.TryGetValue(shop.Id, out var l);
            Links.Add(new ProductLinkRowViewModel
            {
                Shop = shop, IsOn = l != null,
                PriceText = l != null && l.LastPrice > 0 ? ShopMath.TrimNum(l.LastPrice) : "",
                UnitPriceText = l != null && l.LastUnitPrice > 0 ? ShopMath.FmtUnitPrice(l.LastUnitPrice) : "",
                LastAt = l != null && l.LastAt > 0 ? Time.FormatDate(l.LastAt) : "",
            });
        }
    }

    public bool TrySave()
    {
        Error = "";
        var name = Name.Trim();
        if (name.Length == 0) { Error = "Give the product a name."; return false; }
        var live = _s.Repo.Products.FirstOrDefault(x => x.Id == Original.Id) ?? Original;
        _s.Repo.Upsert(live with { Name = name, Category = string.IsNullOrWhiteSpace(Category) ? null : Category.Trim(), DefaultUnit = string.IsNullOrWhiteSpace(Unit) ? null : Unit.Trim(), Note = string.IsNullOrWhiteSpace(Note) ? null : Note.Trim(), DeletedAt = null });
        var existing = _s.Repo.LinksFor(Original.Id).ToDictionary(l => l.ShopId);
        foreach (var row in Links)
        {
            double? price = double.TryParse(row.PriceText.Replace("₹", ""), NumberStyles.Float, CultureInfo.InvariantCulture, out var pv) && pv > 0 ? pv : null;
            double? up = double.TryParse(row.UnitPriceText.Replace("₹", ""), NumberStyles.Float, CultureInfo.InvariantCulture, out var uv) && uv > 0 ? uv : null;
            existing.TryGetValue(row.Shop.Id, out var prev);
            if (row.IsOn)
            {
                var changed = prev == null || (price ?? 0) != prev.LastPrice || (up ?? 0) != prev.LastUnitPrice;
                if (changed) _s.Repo.SetLink(Original.Id, row.Shop.Id, true, price, up);
            }
            else if (prev != null) _s.Repo.SetLink(Original.Id, row.Shop.Id, false);
        }
        Saved = true;
        return true;
    }

    public void Delete() { if (!IsNew) { _s.Repo.DeleteProduct(Original.Id); Saved = true; } }
}

/// <summary>Checkout calculator (Shop Phase B): unit price × qty = cost; paid → discount; writes the purchase + product price memory.</summary>
public sealed partial class CheckoutViewModel : ObservableObject
{
    private readonly AppServices _s;
    public Item Item { get; }
    [ObservableProperty] private string _shop = "";
    [ObservableProperty] private string _unitPrice = "";
    [ObservableProperty] private string _qty = "";
    [ObservableProperty] private string _cost = "";
    [ObservableProperty] private string _paid = "";
    [ObservableProperty] private string _unit = "";
    [ObservableProperty] private string _summary = "";
    public List<string> ShopOptions { get; }
    public string[] Units => Constants.Units;
    public string LastHint { get; }
    private bool _busy;

    public CheckoutViewModel(AppServices s, Item item)
    {
        _s = s; Item = item;
        ShopOptions = s.Repo.ActiveShops.Select(x => x.Name).ToList();
        Shop = (item.ShopId is long sid ? s.Repo.Shop(sid)?.Name : null) ?? item.ShopName ?? s.Repo.DefaultShop?.Name ?? "";
        Qty = item.Quantity ?? ""; Cost = item.Price?.Replace("₹", "").Trim() ?? ""; Unit = item.Unit ?? "";
        var lp = ShopMath.LastPrice(item);
        LastHint = lp is double p ? $"Last time: ₹{ShopMath.TrimNum(p)}" + (item.PriceHistory.Count > 0 && !string.IsNullOrWhiteSpace(item.PriceHistory[^1].Shop) ? " at " + item.PriceHistory[^1].Shop : "") : "";
        Recompute(null);
    }

    private static double? P(string s) => double.TryParse(s.Replace("₹", "").Replace(",", ""), NumberStyles.Float, CultureInfo.InvariantCulture, out var v) ? v : null;

    partial void OnUnitPriceChanged(string value) => Recompute("u");
    partial void OnQtyChanged(string value) => Recompute("q");
    partial void OnCostChanged(string value) => Recompute("c");
    partial void OnPaidChanged(string value) => Recompute("p");

    private void Recompute(string? edited)
    {
        if (_busy) return;
        _busy = true;
        try
        {
            var u = P(UnitPrice); var q = P(Qty); var c = P(Cost);
            // The field just edited is authoritative; derive the one the user has NOT typed.
            if (edited == "u" || edited == "q") { if (u != null && q != null) { c = u * q; Cost = ShopMath.TrimNum(c.Value); } }
            else if (edited == "c") { if (c != null && q != null && q != 0 && u == null) { u = c / q; UnitPrice = ShopMath.TrimNum(u.Value); } else if (c != null && u != null && u != 0 && q == null) { q = c / u; Qty = ShopMath.TrimNum(q.Value); } else if (c != null && q != null && q != 0) { u = c / q; UnitPrice = ShopMath.TrimNum(u.Value); } }
            var calc = ShopMath.Compute(u, q, c, P(Paid));
            Summary = calc.Cost is double cc
                ? $"Cost ₹{ShopMath.TrimNum(cc)}" + (calc.DiscountAmt is double da && calc.Paid != null ? $" · paid ₹{ShopMath.TrimNum(calc.Paid.Value)} · saved ₹{ShopMath.TrimNum(da)} ({calc.DiscountPct:0.#}%)" : "")
                : "Enter any two of unit price · quantity · cost.";
        }
        finally { _busy = false; }
    }

    public ShopCalc Calc => ShopMath.Compute(P(UnitPrice), P(Qty), P(Cost), P(Paid));

    /// <summary>finishShopComplete: apply, record the product price, then complete WITHOUT the legacy re-stamp.</summary>
    public void Complete()
    {
        var live = _s.Repo.Item(Item.Id) ?? Item;
        var now = Clock.Now();
        var calc = Calc;
        var enriched = Engine.ApplyCheckout(live, Shop, calc, string.IsNullOrWhiteSpace(Unit) ? null : Unit.Trim(), now);
        var shop = _s.Repo.ShopByName(Shop);
        if (shop != null) enriched = enriched with { ShopId = shop.Id };
        if (live.ProductId is long pid && shop != null) _s.Repo.RecordPrice(pid, shop.Id, calc.Cost ?? 0.0, calc.UnitPrice ?? 0.0, now);
        var done = Engine.Complete(enriched, now, shopStamp: false);
        _s.Repo.Upsert(done.Updated);
        App.MainVm.Ack(done.ReturnsAt is long ra ? $"{done.Ack} {_s.DayTime(ra)}" : done.Ack, () => _s.Repo.Upsert(live));
    }

    /// <summary>Complete without recording a price (the phone offers the same "Skip").</summary>
    public void Skip()
    {
        var live = _s.Repo.Item(Item.Id) ?? Item;
        var done = Engine.Complete(live, Clock.Now());
        _s.Repo.Upsert(done.Updated);
        App.MainVm.Ack(done.Ack, () => _s.Repo.Upsert(live));
    }
}
