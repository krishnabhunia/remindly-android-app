using System.Globalization;
using Remindly.Core.Models;

namespace Remindly.Core.Logic;

public sealed record ShopCalc(double? UnitPrice, double? Qty, double? Cost, double? Paid, double? DiscountAmt, double? DiscountPct);
public sealed record ShopRec(string Shop, double PerBase, string Unit, double UnitPrice, long At, string Family);

/// <summary>Port of the Shop Phase B/C/D pure maths (Model.kt).</summary>
public static class ShopMath
{
    /// <summary>Given any two of {unitPrice, qty, cost} the third is derived; paid → discount.</summary>
    public static ShopCalc Compute(double? unitPriceIn, double? qtyIn, double? costIn, double? paidIn)
    {
        var u = unitPriceIn; var q = qtyIn; var c = costIn;
        if (u != null && q != null && c == null) c = u * q;
        else if (q != null && c != null && u == null && q != 0.0) u = c / q;
        else if (u != null && c != null && q == null && u != 0.0) q = c / u;
        if (c == null && u != null && q != null) c = u * q;
        var amt = c != null && paidIn != null ? c - paidIn : null;
        var pct = c != null && c > 0.0 && paidIn != null ? (c - paidIn) / c * 100.0 : null;
        return new ShopCalc(u, q, c, paidIn, amt, pct);
    }

    public static List<PricePoint> PushPurchase(IReadOnlyList<PricePoint> history, PricePoint point)
        => history.Append(point).TakeLast(12).ToList();

    public static List<PricePoint> PushPrice(IReadOnlyList<PricePoint> history, long at, double price)
        => PushPurchase(history, new PricePoint { At = at, Price = price });

    /// <summary>unit → (family, factor to the family's base unit). Unknown units are their own family.</summary>
    public static (string family, double factor) UnitFamily(string unit) => unit.Trim().ToLowerInvariant() switch
    {
        "kg" => ("WEIGHT", 1000.0),
        "g" => ("WEIGHT", 1.0),
        "l" => ("VOLUME", 1000.0),
        "ml" => ("VOLUME", 1.0),
        "dozen" => ("COUNT", 12.0),
        "pcs" => ("COUNT", 1.0),
        var u => (u.Length == 0 ? "?" : u, 1.0),
    };

    /// <summary>cheapestShop: lowest per-base unit price across a Buy item's purchase history.</summary>
    public static ShopRec? CheapestShop(IEnumerable<Item> items, string name, string? preferUnit = null)
    {
        var key = name.Trim().ToLowerInvariant();
        if (key.Length == 0) return null;
        var recs = items.Where(i => i.Tab == Tab.SHOP && i.DeletedAt == null && i.Title.Trim().ToLowerInvariant() == key)
            .SelectMany(i => i.PriceHistory)
            .Where(pp => !string.IsNullOrWhiteSpace(pp.Shop) && pp.UnitPrice > 0.0 && !string.IsNullOrWhiteSpace(pp.Unit))
            .Select(pp => { var (fam, f) = UnitFamily(pp.Unit); return new ShopRec(pp.Shop, pp.UnitPrice / f, pp.Unit, pp.UnitPrice, pp.At, fam); })
            .ToList();
        if (recs.Count == 0) return null;
        string? family = null;
        if (preferUnit != null) { var fam = UnitFamily(preferUnit).family; if (recs.Any(r => r.Family == fam)) family = fam; }
        family ??= recs.MaxBy(r => r.At)!.Family;
        return recs.Where(r => r.Family == family).OrderBy(r => r.PerBase).ThenByDescending(r => r.At).First();
    }

    public static List<(string shop, List<Item> items)> ShopGroupsOf(IEnumerable<Item> items)
    {
        var by = items.GroupBy(i => string.IsNullOrWhiteSpace(i.ShopName) ? null : i.ShopName!.Trim()).ToList();
        var named = by.Where(g => g.Key != null).OrderBy(g => g.Key!.ToLowerInvariant()).Select(g => (g.Key!, g.ToList())).ToList();
        var none = by.FirstOrDefault(g => g.Key == null);
        if (none != null) named.Add(("No Shop", none.ToList()));
        return named;
    }

    /// <summary>Format a Double as an integer string when whole (2.0 → "2"), else 2 decimals.</summary>
    public static string TrimNum(double d) => d == Math.Truncate(d) ? ((long)d).ToString(CultureInfo.InvariantCulture) : (Math.Round(d * 100.0) / 100.0).ToString(CultureInfo.InvariantCulture);

    public static double? ParsePrice(string? s)
    {
        if (string.IsNullOrWhiteSpace(s)) return null;
        var t = s.Replace(",", "").Replace("₹", "").Trim();
        return double.TryParse(t, NumberStyles.Float, CultureInfo.InvariantCulture, out var v) ? v : null;
    }

    public static double ShopSpend(IEnumerable<Item> items) => items.Sum(i => ParsePrice(i.Price) ?? 0.0);

    public static string SpendLabel(IEnumerable<Item> items)
    {
        var v = ShopSpend(items);
        if (v <= 0.0) return "";
        return " · ₹" + (v == Math.Floor(v) ? v.ToString("#,##0", CultureInfo.InvariantCulture) : v.ToString("#,##0.00", CultureInfo.InvariantCulture));
    }

    public static double? LastPrice(Item i) => i.PriceHistory.Count == 0 ? null : i.PriceHistory[^1].Price;

    /// <summary>mergedLinkPrice: checkout write-back into a product↔shop link.</summary>
    public static ProductLink MergedLinkPrice(ProductLink? prev, long productId, long shopId, double price, double unitPrice, long at) => new()
    {
        ProductId = productId, ShopId = shopId,
        LastPrice = price > 0.0 ? price : prev?.LastPrice ?? 0.0,
        LastUnitPrice = unitPrice > 0.0 ? unitPrice : prev?.LastUnitPrice ?? 0.0,
        LastAt = at, DeletedAt = null, UpdatedAt = at,
        Extra = prev?.Extra,
    };

    /// <summary>cheapestLink: the live link with the lowest known unit price (falls back to total price).</summary>
    public static ProductLink? CheapestLink(IEnumerable<ProductLink> links, ISet<long> liveShopIds)
    {
        var live = links.Where(l => l.DeletedAt == null && liveShopIds.Contains(l.ShopId)).ToList();
        var byUnit = live.Where(l => l.LastUnitPrice > 0).OrderBy(l => l.LastUnitPrice).ThenByDescending(l => l.LastAt).FirstOrDefault();
        if (byUnit != null) return byUnit;
        return live.Where(l => l.LastPrice > 0).OrderBy(l => l.LastPrice).ThenByDescending(l => l.LastAt).FirstOrDefault();
    }

    public static bool ProductMatches(Product p, string q)
    {
        var t = q.Trim();
        if (t.Length == 0) return true;
        return p.Name.Contains(t, StringComparison.OrdinalIgnoreCase) || (p.Category?.Contains(t, StringComparison.OrdinalIgnoreCase) ?? false);
    }

    public static string BranchAutoName(string chain, string city)
    {
        var c = chain.Trim(); var t = city.Trim();
        return c.Length == 0 ? t : t.Length == 0 ? c : $"{c} – {t}";
    }

    /// <summary>fmtExact: unit prices show 4 decimals ("0.0625") so ₹0.0625/g never renders as ₹0.</summary>
    public static string FmtUnitPrice(double v) => v.ToString("0.####", CultureInfo.InvariantCulture);

    /// <summary>itemUnitPrice: (per-unit price, unit) derived from the typed qty/price when both present.</summary>
    public static (double perUnit, string unit)? ItemUnitPrice(Item i)
    {
        var p = ParsePrice(i.Price);
        var q = ParsePrice(i.Quantity);
        if (p == null || q == null || q <= 0 || string.IsNullOrWhiteSpace(i.Unit)) return null;
        return (p.Value / q.Value, i.Unit!);
    }
}
