using System.Globalization;
using System.Text;

namespace Remindly.Core;

public sealed record ListSeed(List<ShopList> Lists, List<Item> Items, long? DefaultListId);

public sealed record ListStats(int ToBuy, int Done, double? EstTotal, List<string> Shops, long LastActivity);

public enum ListDeleteMode { KEEP_UNSORTED, MOVE_TO, DELETE_ITEMS }

public sealed record ListShareOpts(bool IncludeBought, bool UrgentTag, bool BoughtTag, bool QtyType, string Suffix = ":-");

/// <summary>
/// 2.11 (N48) — the Buy tab LISTS FIRST. A port of Android's ShopLists.kt: list records, which list an
/// item belongs to, the groups→lists migration, card stats, ordering, list actions and the share text.
/// All pure, all unit-tested.
/// </summary>
public static class ShopLists
{
    /// <summary>The virtual "Unsorted" list: Buy items whose list is missing or deleted.</summary>
    public const long UnsortedListId = -1L;
    /// <summary>The cross-list Buy Now view.</summary>
    public const long BuyNowListId = -2L;
    /// <summary>The shopping-day reminder fires at 09:00 local on that day.</summary>
    public const int ShoppingDayHour = 9;

    public static readonly string[] SuggestedIcons = { "🛒", "🥦", "🍎", "🥛", "🍞", "🧴", "🧹", "💊", "🎉", "🏠", "📦", "🐾", "👶", "🎁" };

    public static ShopList Heal(ShopList l) => l with
    {
        Name = (l.Name ?? "").Trim(),
        Icon = string.IsNullOrWhiteSpace(l.Icon) ? null : l.Icon.Trim(),
    };

    public static List<ShopList> Live(IEnumerable<ShopList> lists) =>
        lists.Where(l => l.DeletedAt == null && !string.IsNullOrWhiteSpace(l.Name)).ToList();

    public static ShopList? Named(IEnumerable<ShopList> lists, string? name)
    {
        var n = (name ?? "").Trim();
        if (n.Length == 0) return null;
        return Live(lists).FirstOrDefault(l => string.Equals(l.Name, n, StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>"Groceries" → "Groceries 2" (then 3, 4 …) when a live list already uses the name.</summary>
    public static string UniqueName(string baseName, IEnumerable<ShopList> lists)
    {
        var all = lists.ToList();
        var b = string.IsNullOrWhiteSpace(baseName) ? "List" : baseName.Trim();
        if (Named(all, b) == null) return b;
        int n = 2;
        while (Named(all, $"{b} {n}") != null) n++;
        return $"{b} {n}";
    }

    /// <summary>
    /// Seeded lists get an id derived from the NAME (FNV-1a 64 of the lower-cased UTF-8 name) in the
    /// 0x7E&lt;&lt;56 range — identical to Android, so the same group migrated on both devices is ONE list.
    /// </summary>
    public static long SeedId(string name)
    {
        unchecked
        {
            ulong h = 0xcbf29ce484222325UL;
            foreach (var b in Encoding.UTF8.GetBytes(name.Trim().ToLowerInvariant()))
            {
                h ^= b;
                h *= 0x100000001b3UL;
            }
            return (0x7EL << 56) | (long)(h & ((1UL << 56) - 1));
        }
    }

    /// <summary>The live list an item belongs to: its listId when live, else a live list named like its group, else null (Unsorted).</summary>
    public static long? ListIdOf(Item item, IEnumerable<ShopList> lists)
    {
        var live = Live(lists);
        if (item.ListId is long id && live.Any(l => l.Id == id)) return id;
        return Named(live, item.Group)?.Id;
    }

    /// <summary>Buy items shown for [listId] (a real list, UnsortedListId, or BuyNowListId = all).</summary>
    public static List<Item> ItemsIn(IEnumerable<Item> items, long listId, IEnumerable<ShopList> lists)
    {
        var ls = lists.ToList();
        return items.Where(i => i.Tab == Tab.SHOP && i.DeletedAt == null).Where(i => listId switch
        {
            BuyNowListId => true,
            UnsortedListId => ListIdOf(i, ls) == null,
            _ => ListIdOf(i, ls) == listId,
        }).ToList();
    }

    // ───────────────────────── migration / healing ─────────────────────────

    /// <summary>
    /// Idempotent upgrade step (every start and after every import): Buy group names become lists,
    /// duplicate names collapse to the oldest, items get listId + group stamped, and the old default
    /// group becomes the default list. The returned Items are ONLY the changed ones.
    /// </summary>
    public static ListSeed Seed(List<Item> items, List<ShopList> lists, List<string> groupNames, Dictionary<string, string> icons,
        List<string> groupOrder, string defaultGroup, long? currentDefault, long now)
    {
        var outLists = lists.ToList();
        var shopItems = items.Where(i => i.Tab == Tab.SHOP && i.DeletedAt == null).ToList();
        var names = new List<(string Key, string Name)>();
        foreach (var raw in groupNames.Concat(shopItems.Select(i => i.Group).Where(g => g != null)!))
        {
            var n = raw!.Trim();
            if (n.Length > 0 && names.All(x => x.Key != n.ToLowerInvariant())) names.Add((n.ToLowerInvariant(), n));
        }
        int nextOrder = (outLists.Count == 0 ? -1 : outLists.Max(l => l.Order)) + 1;
        foreach (var (_, n) in names)
        {
            if (Named(outLists, n) != null) continue;
            int ord = groupOrder.FindIndex(g => string.Equals(g, n, StringComparison.OrdinalIgnoreCase));
            long id = SeedId(n);
            var prior = outLists.FirstOrDefault(l => l.Id == id);
            if (prior != null) outLists.Remove(prior);
            icons.TryGetValue(n, out var icon);
            outLists.Add(new ShopList
            {
                Id = id,
                Name = n,
                Icon = string.IsNullOrWhiteSpace(icon) ? null : icon,
                Order = ord >= 0 ? ord : nextOrder++,
                CreatedAt = prior is { CreatedAt: > 0 } ? prior.CreatedAt : now,
                UpdatedAt = now,
            });
        }
        var healed = DedupeNames(outLists, now);
        var changed = new List<Item>();
        foreach (var i in items)
        {
            if (i.Tab != Tab.SHOP || i.DeletedAt != null) continue;
            var id = ListIdOf(i, healed);
            if (id == null)
            {
                if (i.ListId != null) changed.Add(i with { ListId = null });
                continue;
            }
            var name = healed.First(l => l.Id == id).Name;
            if (i.ListId != id || i.Group != name) changed.Add(i with { ListId = id, Group = name });
        }
        long? def = currentDefault is long d && Live(healed).Any(l => l.Id == d) ? d : Named(healed, defaultGroup)?.Id;
        return new ListSeed(healed, changed, def);
    }

    /// <summary>Live lists sharing a name (case-insensitive) collapse to the oldest; the rest are tombstoned.</summary>
    public static List<ShopList> DedupeNames(List<ShopList> lists, long now)
    {
        var losers = Live(lists).GroupBy(l => l.Name.ToLowerInvariant()).Where(g => g.Count() > 1)
            .SelectMany(g => g.OrderBy(l => l.CreatedAt).ThenBy(l => l.Id).Skip(1)).Select(l => l.Id).ToHashSet();
        if (losers.Count == 0) return lists;
        return lists.Select(l => losers.Contains(l.Id) ? l with { DeletedAt = now, UpdatedAt = now } : l).ToList();
    }

    /// <summary>Per-id latest-wins merge (backup import). Tombstones win when they are newer.</summary>
    public static List<ShopList> Merge(IEnumerable<ShopList> local, IEnumerable<ShopList> incoming)
    {
        var byId = new Dictionary<long, ShopList>();
        var order = new List<long>();
        foreach (var l in local) { if (!byId.ContainsKey(l.Id)) order.Add(l.Id); byId[l.Id] = l; }
        foreach (var inc in incoming)
        {
            if (!byId.TryGetValue(inc.Id, out var ex)) { order.Add(inc.Id); byId[inc.Id] = inc; }
            else if (inc.UpdatedAt >= ex.UpdatedAt) byId[inc.Id] = inc;
        }
        return order.Select(id => byId[id]).ToList();
    }

    /// <summary>The settings mirror kept for older Android versions / Classic view: group registry, order and icons follow the lists.</summary>
    public static AppSettings MirrorIntoSettings(AppSettings s)
    {
        var live = Live(s.ShopLists).OrderBy(l => l.Order).ThenBy(l => l.Name.ToLowerInvariant()).ToList();
        var names = live.Select(l => l.Name).ToList();
        var listNames = live.Select(l => l.Name.ToLowerInvariant()).ToHashSet();
        var icons = s.GroupIcons.Where(kv => !listNames.Contains(kv.Key.ToLowerInvariant())).ToDictionary(kv => kv.Key, kv => kv.Value);
        foreach (var l in live) if (l.Icon != null) icons[l.Name] = l.Icon;
        var defName = s.ShopDefaultListId is long d ? live.FirstOrDefault(l => l.Id == d)?.Name ?? "" : "";
        return s with { ShopGroups = names, ShopGroupOrder = names, GroupIcons = icons, ShopDefaultGroup = defName };
    }

    // ───────────────────────── the Lists screen ─────────────────────────

    public static ListStats Stats(IEnumerable<Item> listItems, IReadOnlyDictionary<long, string> shopsById, long listUpdatedAt = 0)
    {
        var live = listItems.Where(i => i.DeletedAt == null).ToList();
        var open = live.Where(i => !i.Done).ToList();
        double total = 0;
        bool any = false;
        foreach (var i in open)
        {
            if (EstPriceOf(i) is double p) { total += p; any = true; }
        }
        var shops = open
            .Select(i => (i.ShopId is long sid && shopsById.TryGetValue(sid, out var sn) ? sn : i.ShopName)?.Trim())
            .Where(s => !string.IsNullOrWhiteSpace(s))
            .GroupBy(s => s!).OrderByDescending(g => g.Count()).Select(g => g.Key).Take(2).ToList();
        long last = live.Select(i => Math.Max(Math.Max(i.UpdatedAt, i.CreatedAt), i.DoneAt ?? 0)).Append(listUpdatedAt).Max();
        return new ListStats(open.Count, live.Count(i => i.Done), any ? total : null, shops, last);
    }

    /// <summary>A to-buy item's expected cost: its price, else last unit price × quantity.</summary>
    public static double? EstPriceOf(Item i)
    {
        if (ParseNum(i.Price) is double p && p > 0) return p;
        var last = i.PriceHistory.LastOrDefault(pp => pp.UnitPrice > 0 && !string.IsNullOrWhiteSpace(pp.Unit));
        if (last == null) return null;
        double q = ParseNum(i.Quantity) is double qq && qq > 0 ? qq : 1.0;
        return last.UnitPrice * q;
    }

    public static double? ParseNum(string? s)
    {
        var t = (s ?? "").Replace(",", "").Replace("₹", "").Trim();
        return double.TryParse(t, NumberStyles.Float, CultureInfo.InvariantCulture, out var v) && double.IsFinite(v) ? v : null;
    }

    /// <summary>"≈ ₹640" — whole rupees when round, else two decimals.</summary>
    public static string? EstLabel(double? v) => v is double d
        ? "≈ ₹" + (d == Math.Floor(d) ? d.ToString("#,##0", CultureInfo.InvariantCulture) : d.ToString("#,##0.00", CultureInfo.InvariantCulture))
        : null;

    /// <summary>Pinned first, then by the sort chip: RECENT (latest activity), AZ, CUSTOM (own order).</summary>
    public static List<ShopList> Sorted(IEnumerable<ShopList> lists, string mode, Func<ShopList, long> lastActivity)
    {
        var live = Live(lists);
        IOrderedEnumerable<ShopList> q = live.OrderBy(l => !l.Pinned);
        q = mode switch
        {
            "AZ" => q.ThenBy(l => l.Name.ToLowerInvariant(), StringComparer.Ordinal),
            "CUSTOM" => q.ThenBy(l => l.Order).ThenBy(l => l.Name.ToLowerInvariant(), StringComparer.Ordinal),
            _ => q.ThenByDescending(lastActivity).ThenBy(l => l.Name.ToLowerInvariant(), StringComparer.Ordinal),
        };
        return q.ToList();
    }

    /// <summary>CUSTOM order: move [id] one place up/down (within its pinned/unpinned band); renumbers 0..n.</summary>
    public static List<ShopList> MoveOrder(List<ShopList> lists, long id, bool up, long now)
    {
        var ordered = Sorted(lists, "CUSTOM", _ => 0L);
        int i = ordered.FindIndex(l => l.Id == id);
        if (i < 0) return lists;
        int j = up ? i - 1 : i + 1;
        if (j < 0 || j >= ordered.Count || ordered[j].Pinned != ordered[i].Pinned) return lists;
        (ordered[i], ordered[j]) = (ordered[j], ordered[i]);
        var newOrder = ordered.Select((l, idx) => (l.Id, idx)).ToDictionary(x => x.Id, x => x.idx);
        return lists.Select(l => newOrder.TryGetValue(l.Id, out var o) && o != l.Order ? l with { Order = o, UpdatedAt = now } : l).ToList();
    }

    public static int NextOrder(IEnumerable<ShopList> lists)
    {
        var live = Live(lists);
        return (live.Count == 0 ? -1 : live.Max(l => l.Order)) + 1;
    }

    // ───────────────────────── inside a list ─────────────────────────

    /// <summary>Another LIVE list already holding an open item with this title (case-insensitive).</summary>
    public static ShopList? OtherListHolding(IEnumerable<Item> items, string title, long? currentListId, IEnumerable<ShopList> lists)
    {
        var t = title.Trim().ToLowerInvariant();
        if (t.Length == 0) return null;
        var ls = lists.ToList();
        var hit = items.FirstOrDefault(i => i.Tab == Tab.SHOP && !i.Done && i.DeletedAt == null && i.Title.Trim().ToLowerInvariant() == t
            && ListIdOf(i, ls) is long l && l != currentListId);
        if (hit == null) return null;
        var hid = ListIdOf(hit, ls);
        return Live(ls).FirstOrDefault(l => l.Id == hid);
    }

    /// <summary>"Recently bought in this list" — bought titles (newest first) matching the typed text, skipping open ones.</summary>
    public static List<Item> RecentInList(IEnumerable<Item> listItems, string query, int limit = 4)
    {
        var q = query.Trim().ToLowerInvariant();
        if (q.Length == 0) return new();
        var all = listItems.ToList();
        var open = all.Where(i => !i.Done && i.DeletedAt == null).Select(i => i.Title.Trim().ToLowerInvariant()).ToHashSet();
        return all.Where(i => i.Done && i.DeletedAt == null)
            .OrderByDescending(i => i.DoneAt ?? i.UpdatedAt)
            .DistinctBy(i => i.Title.Trim().ToLowerInvariant())
            .Where(i => { var t = i.Title.Trim().ToLowerInvariant(); return !open.Contains(t) && t.Contains(q); })
            .OrderBy(i => i.Title.Trim().ToLowerInvariant().StartsWith(q) ? 0 : 1)
            .Take(limit).ToList();
    }

    /// <summary>Product suggestions while typing: prefix matches first, then contains; case-insensitive.</summary>
    public static List<Product> ProductSuggestions(string query, IEnumerable<Product> products, int limit = 6)
    {
        var q = query.Trim().ToLowerInvariant();
        if (q.Length < 2) return new();
        var live = products.Where(p => p.DeletedAt == null).ToList();
        var starts = live.Where(p => p.Name.ToLowerInvariant().StartsWith(q)).OrderBy(p => p.Name.ToLowerInvariant());
        var contains = live.Where(p => !p.Name.ToLowerInvariant().StartsWith(q)
                && (p.Name.ToLowerInvariant().Contains(q) || (p.Category ?? "").ToLowerInvariant().Contains(q)))
            .OrderBy(p => p.Name.ToLowerInvariant());
        return starts.Concat(contains).Take(limit).ToList();
    }

    /// <summary>Group By Shop; "No Shop" last.</summary>
    public static List<(string Name, List<Item> Items)> ShopGroupsOf(IEnumerable<Item> items)
    {
        var by = items.GroupBy(i => string.IsNullOrWhiteSpace(i.ShopName) ? null : i.ShopName!.Trim()).ToList();
        var named = by.Where(g => g.Key != null).OrderBy(g => g.Key!.ToLowerInvariant()).Select(g => (g.Key!, g.ToList()));
        var none = by.Where(g => g.Key == null).Select(g => ("No Shop", g.ToList()));
        return named.Concat(none).ToList();
    }

    /// <summary>Group By Category (from the linked Product); "No category" last.</summary>
    public static List<(string Name, List<Item> Items)> CategoryGroupsOf(IEnumerable<Item> items, IReadOnlyDictionary<long, Product> productsById)
    {
        string? Cat(Item i) => i.ProductId is long pid && productsById.TryGetValue(pid, out var p) && !string.IsNullOrWhiteSpace(p.Category) ? p.Category!.Trim() : null;
        var by = items.GroupBy(Cat).ToList();
        var named = by.Where(g => g.Key != null).OrderBy(g => g.Key!.ToLowerInvariant()).Select(g => (g.Key!, g.ToList()));
        var none = by.Where(g => g.Key == null).Select(g => ("No category", g.ToList()));
        return named.Concat(none).ToList();
    }

    /// <summary>The cross-list Buy Now view: items grouped under their list (custom order), Unsorted last.</summary>
    public static List<(string Name, List<Item> Items)> ListGroupsOf(IEnumerable<Item> items, IEnumerable<ShopList> lists)
    {
        var ls = lists.ToList();
        var by = items.GroupBy(i => ListIdOf(i, ls) ?? UnsortedListId).ToDictionary(g => g.Key, g => g.ToList());
        var result = new List<(string, List<Item>)>();
        foreach (var l in Sorted(ls, "CUSTOM", _ => 0L))
            if (by.TryGetValue(l.Id, out var v)) result.Add(((l.Icon != null ? l.Icon + " " : "") + l.Name, v));
        if (by.TryGetValue(UnsortedListId, out var u)) result.Add(("Unsorted", u));
        return result;
    }

    // ───────────────────────── list actions ─────────────────────────

    public static List<Item> ItemsAfterDelete(IEnumerable<Item> listItems, ListDeleteMode mode, ShopList? target) => mode switch
    {
        ListDeleteMode.KEEP_UNSORTED => listItems.Select(i => i with { ListId = null, Group = null }).ToList(),
        ListDeleteMode.MOVE_TO => target == null
            ? listItems.Select(i => i with { ListId = null, Group = null }).ToList()
            : listItems.Select(i => i with { ListId = target.Id, Group = target.Name }).ToList(),
        _ => listItems.ToList(),
    };

    public static List<Item> ItemsAfterRename(IEnumerable<Item> listItems, ShopList list, string newName) =>
        listItems.Select(i => i with { ListId = list.Id, Group = newName.Trim() }).ToList();

    /// <summary>Private list ON → every item Personal; OFF → none.</summary>
    public static List<Item> ItemsAfterPrivacy(IEnumerable<Item> listItems, bool personal) =>
        listItems.Where(i => i.Personal != personal).Select(i => i with { Personal = personal }).ToList();

    public static List<Item> DuplicateItems(IEnumerable<Item> listItems, ShopList newList, Func<long> newId, long now) =>
        listItems.Where(i => i.DeletedAt == null).Select(i => i with
        {
            Id = newId(), ListId = newList.Id, Group = newList.Name, Done = false, DoneAt = null,
            SnoozedUntil = null, ReturnAt = null, CreatedAt = now, UpdatedAt = now, CalEventId = null,
            Personal = i.Personal || newList.Personal,
        }).ToList();

    /// <summary>Restart: the bought items go back to To buy.</summary>
    public static List<Item> RestartTargets(IEnumerable<Item> listItems) => listItems.Where(i => i.Done && i.DeletedAt == null).ToList();

    public static List<Item> MarkAllTargets(IEnumerable<Item> listItems) => listItems.Where(i => !i.Done && i.DeletedAt == null).ToList();

    // ───────────────────────── sharing ─────────────────────────

    public static ListShareOpts ShareOptsOf(AppSettings s) =>
        new(s.ShareIncludeDone, s.ShareUrgentTag, s.ShareBoughtTag, s.ShareIncludeQty, s.ShareHeadingSuffix);

    /// <summary>"5 / kg" · "3" (no unit) · null (no quantity → the segment is dropped).</summary>
    public static string? QtySegment(Item i)
    {
        var q = (i.Quantity ?? "").Trim();
        if (q.Length == 0) return null;
        var u = (i.Unit ?? "").Trim();
        return u.Length == 0 ? q : $"{q} / {u}";
    }

    public static string ShareLine(int n, Item i, ListShareOpts o)
    {
        var sb = new StringBuilder().Append(n).Append(". ").Append(i.Title.Trim());
        if (o.QtyType && QtySegment(i) is string q) sb.Append(" - ").Append(q);
        if (o.UrgentTag && i.Priority == Priority.URGENT) sb.Append(" - Urgent");
        if (o.BoughtTag && i.Done) sb.Append(" - Bought");
        return sb.ToString();
    }

    /// <summary>
    /// The share format (identical to Android 2.11):
    ///   List_Name:-
    ///   (blank line)
    ///   1. Item - Quantity / Type - Urgent - Bought
    /// Open lines first in on-screen order, bought lines last (only when included). Prices, shop and notes are never written.
    /// </summary>
    public static string ShareText(string listName, IEnumerable<Item> items, ListShareOpts o)
    {
        var live = items.Where(i => i.DeletedAt == null).ToList();
        var rows = live.Where(i => !i.Done).Concat(o.IncludeBought ? live.Where(i => i.Done) : Enumerable.Empty<Item>()).ToList();
        var head = listName.Trim() + o.Suffix;
        if (rows.Count == 0) return head;
        return head + "\n\n" + string.Join("\n", rows.Select((i, idx) => ShareLine(idx + 1, i, o)));
    }

    /// <summary>The on-screen order a list is shared in (mirrors the list page's grouping for the same key).</summary>
    public static List<Item> ShareOrderFor(IEnumerable<Item> items, string mode, IEnumerable<Product> products)
    {
        var live = items.Where(i => i.DeletedAt == null).ToList();
        long Basis(Item i) => i.DueAt ?? i.CreatedAt;
        return mode switch
        {
            "SHOP" => ShopGroupsOf(live).SelectMany(g => g.Items.OrderBy(Basis)).ToList(),
            "CATEGORY" => CategoryGroupsOf(live, products.GroupBy(p => p.Id).ToDictionary(g => g.Key, g => g.First())).SelectMany(g => g.Items.OrderBy(Basis)).ToList(),
            "PRIORITY" => live.OrderBy(i => PriorityNames.Rank(i.Priority)).ThenBy(Basis).ToList(),
            "NONE" => live.OrderBy(i => i.CreatedAt).ThenBy(i => i.Id).ToList(),
            _ => live.OrderBy(Basis).ToList(),
        };
    }

    // ───────────────────────── shopping day ─────────────────────────

    public static long ShoppingDayFireAt(long dayMs, TimeZoneInfo? zone = null) =>
        Clock.FromLocal(Clock.LocalDate(dayMs, zone).AddHours(ShoppingDayHour), zone);

    public static List<(ShopList List, long FireAt)> UpcomingShoppingDays(IEnumerable<ShopList> lists, long now, TimeZoneInfo? zone = null) =>
        Live(lists).Where(l => l.ShoppingDay != null)
            .Select(l => (l, ShoppingDayFireAt(l.ShoppingDay!.Value, zone)))
            .Where(x => x.Item2 > now).ToList();

    // ───────────────────────── card wording ─────────────────────────

    /// <summary>"just now" · "5 min ago" · "2 h ago" · "yesterday" · "3 d ago" · "12 Aug".</summary>
    public static string RelativeAgo(long then, long now, TimeZoneInfo? zone = null)
    {
        if (then <= 0) return "";
        long d = now - then;
        if (d < 60_000L) return "just now";
        if (d < 3_600_000L) return $"{d / 60_000L} min ago";
        if (d < 86_400_000L) return $"{d / 3_600_000L} h ago";
        if (d < 2 * 86_400_000L) return "yesterday";
        if (d < 7 * 86_400_000L) return $"{d / 86_400_000L} d ago";
        return Clock.ToLocal(then, zone).ToString("d MMM", CultureInfo.InvariantCulture);
    }

    /// <summary>"5 to buy · 2 done · updated 2 h ago" / "All bought · …" / "Empty · …".</summary>
    public static string CardSubtitle(ListStats st, long now, TimeZoneInfo? zone = null)
    {
        var head = st.ToBuy == 0 && st.Done == 0 ? "Empty" : st.ToBuy == 0 ? "All bought" : $"{st.ToBuy} to buy · {st.Done} done";
        var ago = RelativeAgo(st.LastActivity, now, zone);
        return ago.Length == 0 ? head : $"{head} · updated {ago}";
    }
}
