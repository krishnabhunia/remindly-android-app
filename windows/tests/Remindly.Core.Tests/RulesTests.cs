using Remindly.Core;
using Xunit;

namespace Remindly.Core.Tests;

public class ItemRulesTests
{
    [Fact]
    public void One_off_goes_to_done()
    {
        var item = new Item { Id = 1, Tab = Tab.TASKS, Title = "Pay rent", DueAt = T.At(2026, 9, 29, 9), SnoozedUntil = 5 };
        var r = ItemRules.Complete(item, T.At(2026, 9, 29, 10), T.Zone);
        Assert.True(r.Item.Done);
        Assert.Null(r.Item.SnoozedUntil);
        Assert.Equal("Moved to Done", r.Message);
    }

    [Fact]
    public void Recurring_moves_due_to_the_next_occurrence_and_returns_that_day()
    {
        var item = new Item { Id = 1, Tab = Tab.TASKS, Title = "Gym", DueAt = T.At(2026, 9, 29, 7), RepeatMode = "DAILY" };
        var r = ItemRules.Complete(item, T.At(2026, 9, 29, 8), T.Zone);
        Assert.True(r.Item.Done);
        Assert.Equal(new DateTime(2026, 9, 30, 7, 0, 0), T.Local(r.Item.DueAt!.Value));
        Assert.Equal(1, r.Item.RepeatDone);
        Assert.Empty(ItemRules.ResurrectDue(new[] { r.Item }, T.At(2026, 9, 29, 23), T.Zone));
        Assert.False(ItemRules.ResurrectDue(new[] { r.Item }, T.At(2026, 9, 30, 0, 1), T.Zone).Single().Done);
    }

    [Fact]
    public void Exhausted_repeat_finishes_and_never_returns()
    {
        var item = new Item { Id = 1, Tab = Tab.TASKS, Title = "Course", DueAt = T.At(2026, 9, 29, 7), RepeatMode = "DAILY", RepeatCount = 2, RepeatDone = 1 };
        var r = ItemRules.Complete(item, T.At(2026, 9, 29, 8), T.Zone);
        Assert.Equal("Done · repeat finished", r.Message);
        Assert.Empty(ItemRules.ResurrectDue(new[] { r.Item }, T.At(2026, 12, 1), T.Zone));
    }

    [Fact]
    public void Bought_item_stamps_price_and_arms_lapse_return()
    {
        var item = T.Buy("Rice", 1, price: "₹1,200") with { LapseValue = 1, LapseUnit = LapseUnit.MONTHS };
        var r = ItemRules.Complete(item, T.At(2026, 9, 29, 10), T.Zone);
        Assert.Equal(1200.0, r.Item.PriceHistory.Single().Price);
        Assert.Equal(new DateTime(2026, 10, 29, 10, 0, 0), T.Local(r.Item.ReturnAt!.Value));
        Assert.False(ItemRules.ResurrectDue(new[] { r.Item }, T.At(2026, 10, 29, 11), T.Zone).Single().Done);
    }

    [Fact]
    public void Price_history_keeps_twelve()
    {
        var item = T.Buy("Milk", price: "50") with { PriceHistory = Enumerable.Range(0, 12).Select(i => new PricePoint { At = i }).ToList() };
        var r = ItemRules.Complete(item, 99, T.Zone);
        Assert.Equal(12, r.Item.PriceHistory.Count);
        Assert.Equal(99, r.Item.PriceHistory[^1].At);
    }

    [Fact]
    public void Recurring_call_moves_to_the_next_call()
    {
        var c = new CallReminder { Id = 3, Number = "+91 98300 12345", RecurAt = T.At(2026, 9, 29, 18), RepeatMode = "WEEKLY", RepeatDays = new() { 2 } };
        var r = ItemRules.CompleteCall(c, T.At(2026, 9, 29, 18, 5), T.Zone);
        Assert.Equal(new DateTime(2026, 10, 6, 18, 0, 0), T.Local(r.Record.RecurAt!.Value));
        Assert.False(ItemRules.ResurrectCallsDue(new[] { r.Record }, T.At(2026, 10, 6, 0, 5), T.Zone).Single().Done);
    }

    [Fact]
    public void Duplicate_warning_ignores_done_and_self()
    {
        var items = new List<Item> { new() { Id = 1, Tab = Tab.TASKS, Title = "Call bank" }, new() { Id = 2, Tab = Tab.TASKS, Title = "Old", Done = true } };
        Assert.True(ItemRules.DupActiveMatch(items, Tab.TASKS, " call BANK"));
        Assert.False(ItemRules.DupActiveMatch(items, Tab.TASKS, "call bank", exceptId: 1));
        Assert.False(ItemRules.DupActiveMatch(items, Tab.TASKS, "old"));
        Assert.False(ItemRules.DupActiveMatch(items, Tab.LEARN, "call bank"));
    }

    [Fact]
    public void Day_groups_overdue_today_tomorrow_none()
    {
        long now = T.At(2026, 9, 29, 12);
        var items = new List<Item>
        {
            new() { Id = 1, Title = "late", DueAt = T.At(2026, 9, 27, 9) },
            new() { Id = 2, Title = "today", DueAt = T.At(2026, 9, 29, 18) },
            new() { Id = 3, Title = "tomorrow", DueAt = T.At(2026, 9, 30, 8) },
            new() { Id = 4, Title = "someday" },
        };
        var groups = ItemRules.GroupByDay(items, false, now, T.Zone);
        Assert.Equal(new[] { "Overdue", "Today", "Tomorrow", "No date" }, groups.Select(g => g.Label).ToArray());
        Assert.Equal(2, ItemRules.PendingTodayCount(items.Select(i => i with { Tab = Tab.TASKS }), Tab.TASKS, now, T.Zone));
    }
}

public class RemindersTests
{
    private static RemindlyData Data(params Item[] items) => new() { Items = items.ToList() };

    [Fact]
    public void Due_item_fires_once()
    {
        long now = T.At(2026, 9, 29, 9, 1);
        var d = Data(new Item { Id = 1, Tab = Tab.TASKS, Title = "Standup", DueAt = T.At(2026, 9, 29, 9) });
        var fired = new HashSet<string>();
        var a = Reminders.Due(d, now, fired).Single();
        Assert.Equal("Standup", a.Title);
        fired.Add(a.Key);
        Assert.Empty(Reminders.Due(d, now + 60_000, fired));
    }

    [Fact]
    public void Future_muted_done_and_stale_items_do_not_fire()
    {
        long now = T.At(2026, 9, 29, 9);
        var d = Data(
            new Item { Id = 1, Title = "future", DueAt = now + 1 },
            new Item { Id = 2, Title = "muted", DueAt = now - 1, AlertType = "OFF" },
            new Item { Id = 3, Title = "done", DueAt = now - 1, Done = true },
            new Item { Id = 4, Title = "stale", DueAt = now - Reminders.CatchUpWindowMs - 1 });
        Assert.Empty(Reminders.Due(d, now, new HashSet<string>()));
    }

    [Fact]
    public void Snooze_is_a_new_alert()
    {
        long now = T.At(2026, 9, 29, 9, 20);
        var i = new Item { Id = 1, Title = "x", DueAt = T.At(2026, 9, 29, 9), SnoozedUntil = T.At(2026, 9, 29, 9, 10) };
        var fired = new HashSet<string> { Reminders.ItemKey(1, T.At(2026, 9, 29, 9)) };
        Assert.Equal(Reminders.ItemKey(1, i.SnoozedUntil!.Value), Reminders.Due(Data(i), now, fired).Single().Key);
    }

    [Fact]
    public void Personal_items_hide_their_title()
    {
        long now = T.At(2026, 9, 29, 9, 1);
        var a = Reminders.Due(Data(new Item { Id = 1, Title = "Secret", DueAt = now - 1, Personal = true }), now, new HashSet<string>()).Single();
        Assert.Equal("Remindly", a.Title);
    }

    [Fact]
    public void Shopping_day_and_call_alerts()
    {
        long now = T.At(2026, 9, 29, 9, 30);
        var d = new RemindlyData
        {
            Settings = new AppSettings { ShopLists = new() { T.List(1, "Groceries") with { ShoppingDay = T.At(2026, 9, 29) } } },
            Calls = new() { new CallReminder { Id = 5, Number = "123", Name = "Asha", RecurAt = T.At(2026, 9, 29, 9, 15) } },
        };
        var alerts = Reminders.Due(d, now, new HashSet<string>(), T.Zone);
        Assert.Contains(alerts, a => a.Kind == AlertKind.SHOPPING_DAY && a.Title == "Shopping day · Groceries");
        Assert.Contains(alerts, a => a.Kind == AlertKind.CALL && a.Title == "Call back Asha");
        Assert.Equal(now + 10 * 60_000L, Reminders.SnoozeTarget(now, 10));
        Assert.Equal(now + 60_000L, Reminders.SnoozeTarget(now, 0));
    }

    [Fact]
    public void Fired_log_is_capped()
    {
        var keys = Enumerable.Range(0, 700).Select(i => "k" + i).ToList();
        var trimmed = Reminders.TrimFired(keys);
        Assert.Equal(Reminders.FiredKeysCap, trimmed.Count);
        Assert.Equal("k699", trimmed[^1]);
    }
}

public class IdsTests
{
    [Fact]
    public void Compose_packs_the_device_tag_and_stays_monotonic()
    {
        long a = Ids.ComposeId(1_000, 0, 7);
        Assert.Equal(7, Ids.TagOf(a));
        Assert.Equal(a + 1, Ids.ComposeId(1_000, a, 7));
        Assert.Equal(0, Ids.TagOf(1_727_600_000_000L));
    }

    [Fact]
    public void New_device_tag_is_never_zero()
    {
        for (int i = 0; i < 200; i++) Assert.InRange(Ids.NewDeviceTag(), 1, 0xFFF);
    }
}

public class CallTextTests
{
    [Fact]
    public void Display_and_links()
    {
        Assert.Equal("Asha Rao", CallText.DisplayOf("Asha", "Rao", "x", "1"));
        Assert.Equal("+919830012345", CallText.NormalizePhone(" +91 98300-12345 "));
        Assert.Equal("https://wa.me/919830012345?text=Hi%20there", CallText.WhatsAppUrl("+91 98300 12345", "Hi there"));
    }
}
