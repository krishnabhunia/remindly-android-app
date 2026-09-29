using Remindly.Core;
using Xunit;

namespace Remindly.Core.Tests;

/// <summary>The 2.11 (N48) Lists-first rules — same expectations as Android's V211Test.</summary>
public class ShopListsTests
{
    [Fact]
    public void Seed_id_is_identical_to_android_fnv1a()
    {
        // Value computed with Android's seedListId (FNV-1a 64, lower-cased, 0x7E << 56 range).
        Assert.Equal(9115914915876812164L, ShopLists.SeedId("Groceries"));
        Assert.Equal(ShopLists.SeedId("Groceries"), ShopLists.SeedId("  groceries "));
        Assert.Equal(0x7E, (int)((ulong)ShopLists.SeedId("Party") >> 56));
    }

    [Fact]
    public void Unique_name_counts_up()
    {
        var lists = new List<ShopList> { T.List(1, "Groceries"), T.List(2, "Groceries 2") };
        Assert.Equal("groceries 3", ShopLists.UniqueName("groceries", lists)); // keeps the typed case, like Android
        Assert.Equal("Party", ShopLists.UniqueName("Party", lists));
        Assert.Equal("List", ShopLists.UniqueName("  ", lists));
    }

    [Fact]
    public void Item_belongs_by_id_then_by_group_name_else_unsorted()
    {
        var lists = new List<ShopList> { T.List(1, "Groceries"), T.List(2, "Party") with { DeletedAt = 5 } };
        Assert.Equal(1L, ShopLists.ListIdOf(T.Buy("Milk", listId: 1), lists));
        Assert.Equal(1L, ShopLists.ListIdOf(T.Buy("Milk", group: "groceries"), lists));
        Assert.Null(ShopLists.ListIdOf(T.Buy("Cake", listId: 2, group: "Party"), lists));
        Assert.Null(ShopLists.ListIdOf(T.Buy("Loose"), lists));
    }

    [Fact]
    public void Items_in_list_unsorted_and_buy_now()
    {
        var lists = new List<ShopList> { T.List(1, "Groceries") };
        var items = new List<Item> { T.Buy("Milk", 1), T.Buy("Loose"), T.Buy("Gone", 1) with { DeletedAt = 3 }, new Item { Id = 9, Tab = Tab.TASKS, Title = "task" } };
        Assert.Single(ShopLists.ItemsIn(items, 1, lists));
        Assert.Equal("Loose", ShopLists.ItemsIn(items, ShopLists.UnsortedListId, lists).Single().Title);
        Assert.Equal(2, ShopLists.ItemsIn(items, ShopLists.BuyNowListId, lists).Count);
    }

    [Fact]
    public void Seed_turns_groups_into_lists_and_stamps_items()
    {
        var items = new List<Item> { T.Buy("Milk", group: "Groceries"), T.Buy("Balloons", group: "party "), T.Buy("Loose") };
        var seed = ShopLists.Seed(items, new(), new() { "Groceries" }, new() { ["Groceries"] = "🥦" }, new() { "Groceries" }, "Groceries", null, 100);
        Assert.Equal(2, seed.Lists.Count);
        var groceries = seed.Lists.Single(l => l.Name == "Groceries");
        Assert.Equal(ShopLists.SeedId("Groceries"), groceries.Id);
        Assert.Equal("🥦", groceries.Icon);
        Assert.Equal(groceries.Id, seed.DefaultListId);
        Assert.Equal(2, seed.Items.Count); // Loose stays Unsorted and unchanged
        Assert.All(seed.Items, i => Assert.NotNull(i.ListId));
        Assert.Equal("party", seed.Items.Single(i => i.Title == "Balloons").Group);
    }

    [Fact]
    public void Seed_is_idempotent()
    {
        var items = new List<Item> { T.Buy("Milk", group: "Groceries") };
        var first = ShopLists.Seed(items, new(), new(), new(), new(), "", null, 100);
        var stamped = items.Select(i => first.Items.FirstOrDefault(c => c.Id == i.Id) ?? i).ToList();
        var second = ShopLists.Seed(stamped, first.Lists, new(), new(), new(), "", first.DefaultListId, 200);
        Assert.Empty(second.Items);
        Assert.Equal(first.Lists.Count, second.Lists.Count);
    }

    [Fact]
    public void Duplicate_names_collapse_to_the_oldest()
    {
        var lists = new List<ShopList> { T.List(5, "Groceries", created: 50), T.List(3, "groceries", created: 10) };
        var healed = ShopLists.DedupeNames(lists, 99);
        Assert.Null(healed.Single(l => l.Id == 3).DeletedAt);
        Assert.Equal(99, healed.Single(l => l.Id == 5).DeletedAt);
    }

    [Fact]
    public void Merge_is_latest_wins_per_id()
    {
        var local = new List<ShopList> { T.List(1, "A") with { UpdatedAt = 10 }, T.List(2, "B") with { UpdatedAt = 50 } };
        var incoming = new List<ShopList> { T.List(1, "A2") with { UpdatedAt = 20 }, T.List(2, "B-old") with { UpdatedAt = 5 }, T.List(3, "C") };
        var merged = ShopLists.Merge(local, incoming);
        Assert.Equal(new[] { "A2", "B", "C" }, merged.Select(l => l.Name).ToArray());
    }

    [Fact]
    public void Mirror_keeps_task_group_icons_and_writes_list_registry()
    {
        var s = new AppSettings
        {
            ShopLists = new() { T.List(1, "Groceries", 1) with { Icon = "🥦" }, T.List(2, "Party", 0) },
            ShopDefaultListId = 1,
            GroupIcons = new() { ["Work"] = "💼", ["groceries"] = "old" },
        };
        var m = ShopLists.MirrorIntoSettings(s);
        Assert.Equal(new[] { "Party", "Groceries" }, m.ShopGroups.ToArray());
        Assert.Equal("💼", m.GroupIcons["Work"]);
        Assert.Equal("🥦", m.GroupIcons["Groceries"]);
        Assert.False(m.GroupIcons.ContainsKey("groceries"));
        Assert.Equal("Groceries", m.ShopDefaultGroup);
    }

    [Fact]
    public void Stats_count_estimate_and_top_shops()
    {
        var items = new List<Item>
        {
            T.Buy("Milk", 1, price: "60", shop: "D-Mart"),
            T.Buy("Rice", 1, qty: "5", unit: "kg", shop: "D-Mart") with { PriceHistory = new() { new PricePoint { UnitPrice = 80, Unit = "kg" } } },
            T.Buy("Eggs", 1, shop: "Local"),
            T.Buy("Bread", 1, done: true, price: "40"),
        };
        var st = ShopLists.Stats(items, new Dictionary<long, string>());
        Assert.Equal(3, st.ToBuy);
        Assert.Equal(1, st.Done);
        Assert.Equal(460.0, st.EstTotal);
        Assert.Equal(new[] { "D-Mart", "Local" }, st.Shops.ToArray());
        Assert.Equal("≈ ₹460", ShopLists.EstLabel(st.EstTotal));
        Assert.Equal("≈ ₹1,234.50", ShopLists.EstLabel(1234.5));
        Assert.Null(ShopLists.EstLabel(null));
    }

    [Fact]
    public void Sort_pins_first_then_mode()
    {
        var lists = new List<ShopList> { T.List(1, "b", 2), T.List(2, "A", 1), T.List(3, "c", 0, pinned: true) };
        Assert.Equal(new long[] { 3, 2, 1 }, ShopLists.Sorted(lists, "AZ", _ => 0).Select(l => l.Id).ToArray());
        Assert.Equal(new long[] { 3, 2, 1 }, ShopLists.Sorted(lists, "CUSTOM", _ => 0).Select(l => l.Id).ToArray());
        Assert.Equal(new long[] { 3, 1, 2 }, ShopLists.Sorted(lists, "RECENT", l => l.Id == 1 ? 100 : 5).Select(l => l.Id).ToArray());
    }

    [Fact]
    public void Move_order_stays_inside_the_pinned_band()
    {
        var lists = new List<ShopList> { T.List(1, "a", 0, pinned: true), T.List(2, "b", 1), T.List(3, "c", 2) };
        var moved = ShopLists.MoveOrder(lists, 3, up: true, now: 9);
        Assert.Equal(new long[] { 1, 3, 2 }, ShopLists.Sorted(moved, "CUSTOM", _ => 0).Select(l => l.Id).ToArray());
        Assert.Same(lists, ShopLists.MoveOrder(lists, 2, up: true, now: 9)); // cannot cross into the pinned band
    }

    [Fact]
    public void Other_list_warning_and_recent_suggestions()
    {
        var lists = new List<ShopList> { T.List(1, "Groceries"), T.List(2, "Party") };
        var items = new List<Item> { T.Buy("Milk", 1), T.Buy("Chips", 2, done: true, created: 5), T.Buy("Chocolate", 2, done: true, created: 9), T.Buy("Cheese", 2) };
        Assert.Equal("Groceries", ShopLists.OtherListHolding(items, " milk ", 2, lists)!.Name);
        Assert.Null(ShopLists.OtherListHolding(items, "milk", 1, lists));
        var recent = ShopLists.RecentInList(ShopLists.ItemsIn(items, 2, lists), "ch");
        Assert.Equal(new[] { "Chocolate", "Chips" }, recent.Select(i => i.Title).ToArray());
    }

    [Fact]
    public void Share_text_is_krishnas_format()
    {
        var items = new List<Item>
        {
            T.Buy("Milk", 1, qty: "2", unit: "L", pri: Priority.URGENT),
            T.Buy("Bread", 1),
            T.Buy("Eggs", 1, qty: "12", done: true),
        };
        var opts = new ListShareOpts(IncludeBought: true, UrgentTag: true, BoughtTag: true, QtyType: true);
        Assert.Equal("Groceries:-\n\n1. Milk - 2 / L - Urgent\n2. Bread\n3. Eggs - 12 - Bought", ShopLists.ShareText("Groceries ", items, opts));
        var noBought = opts with { IncludeBought = false, QtyType = false };
        Assert.Equal("Groceries:-\n\n1. Milk - Urgent\n2. Bread", ShopLists.ShareText("Groceries", items, noBought));
        Assert.Equal("Empty:-", ShopLists.ShareText("Empty", new List<Item>(), opts));
    }

    [Fact]
    public void Delete_modes()
    {
        var target = T.List(7, "Monthly");
        var items = new List<Item> { T.Buy("Milk", 1, group: "Groceries") };
        Assert.Null(ShopLists.ItemsAfterDelete(items, ListDeleteMode.KEEP_UNSORTED, null).Single().ListId);
        var moved = ShopLists.ItemsAfterDelete(items, ListDeleteMode.MOVE_TO, target).Single();
        Assert.Equal((7L, "Monthly"), (moved.ListId!.Value, moved.Group!));
        Assert.Equal(items[0], ShopLists.ItemsAfterDelete(items, ListDeleteMode.DELETE_ITEMS, target).Single());
    }

    [Fact]
    public void Duplicate_restart_and_mark_all()
    {
        var nl = T.List(8, "Copy") with { Personal = true };
        var items = new List<Item> { T.Buy("Milk", 1, done: true), T.Buy("Bread", 1) };
        long id = 500;
        var dup = ShopLists.DuplicateItems(items, nl, () => ++id, 77);
        Assert.All(dup, i => { Assert.False(i.Done); Assert.Equal(8, i.ListId); Assert.True(i.Personal); Assert.Equal(77, i.CreatedAt); });
        Assert.Equal("Milk", ShopLists.RestartTargets(items).Single().Title);
        Assert.Equal("Bread", ShopLists.MarkAllTargets(items).Single().Title);
    }

    [Fact]
    public void Shopping_day_fires_at_nine()
    {
        long day = T.At(2026, 10, 3, 15, 40);
        Assert.Equal(new DateTime(2026, 10, 3, 9, 0, 0), T.Local(ShopLists.ShoppingDayFireAt(day, T.Zone)));
        var lists = new List<ShopList> { T.List(1, "A") with { ShoppingDay = day }, T.List(2, "B") with { ShoppingDay = T.At(2026, 9, 1) } };
        Assert.Equal(1, ShopLists.UpcomingShoppingDays(lists, T.At(2026, 9, 29), T.Zone).Single().List.Id);
    }

    [Fact]
    public void Card_wording()
    {
        long now = T.At(2026, 9, 29, 12);
        Assert.Equal("just now", ShopLists.RelativeAgo(now - 5_000, now, T.Zone));
        Assert.Equal("5 min ago", ShopLists.RelativeAgo(now - 5 * 60_000, now, T.Zone));
        Assert.Equal("yesterday", ShopLists.RelativeAgo(now - 30 * 3_600_000L, now, T.Zone));
        Assert.Equal("12 Aug", ShopLists.RelativeAgo(T.At(2026, 8, 12, 10), now, T.Zone));
        Assert.Equal("5 to buy · 2 done · updated 2 h ago", ShopLists.CardSubtitle(new ListStats(5, 2, null, new(), now - 2 * 3_600_000L), now, T.Zone));
        Assert.Equal("All bought", ShopLists.CardSubtitle(new ListStats(0, 2, null, new(), 0), now, T.Zone));
        Assert.Equal("Empty", ShopLists.CardSubtitle(new ListStats(0, 0, null, new(), 0), now, T.Zone));
    }

    [Fact]
    public void Share_order_follows_the_inner_grouping()
    {
        var items = new List<Item> { T.Buy("b", 1, shop: "Zed", created: 1), T.Buy("a", 1, shop: "Alpha", created: 2), T.Buy("c", 1, pri: Priority.URGENT, created: 3) };
        Assert.Equal(new[] { "a", "b", "c" }, ShopLists.ShareOrderFor(items, "SHOP", new List<Product>()).Select(i => i.Title).ToArray());
        Assert.Equal("c", ShopLists.ShareOrderFor(items, "PRIORITY", new List<Product>()).First().Title);
    }

    [Fact]
    public void Product_suggestions_prefix_first()
    {
        var ps = new List<Product> { new() { Id = 1, Name = "Oat milk" }, new() { Id = 2, Name = "Milk" }, new() { Id = 3, Name = "Soap", Category = "milky" } };
        Assert.Equal(new[] { "Milk", "Oat milk", "Soap" }, ShopLists.ProductSuggestions("mil", ps).Select(p => p.Name).ToArray());
        Assert.Empty(ShopLists.ProductSuggestions("m", ps));
    }
}
