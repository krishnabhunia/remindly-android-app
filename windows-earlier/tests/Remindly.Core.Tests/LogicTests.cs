using Remindly.Core.Logic;
using Remindly.Core.Models;

namespace Remindly.Core.Tests;

public class IdsTests
{
    [Fact] public void Compose_puts_tag_in_high_bits_and_is_monotonic()
    {
        var a = Ids.Compose(1_700_000_000_000L, 0, 0x5A5);
        Assert.Equal(0x5A5, Ids.TagOf(a));
        Assert.Equal(1_700_000_000_000L, a & ((1L << 44) - 1));
        var b = Ids.Compose(1_700_000_000_000L, a, 0x5A5);   // same millisecond → prev + 1
        Assert.Equal(a + 1, b);
        Assert.Equal(0, Ids.TagOf(1_700_000_000_000L));      // legacy plain-millis id is untagged
    }

    [Fact] public void Two_tags_never_collide_on_the_same_millisecond()
    {
        var x = Ids.Compose(1_700_000_000_000L, 0, 1);
        var y = Ids.Compose(1_700_000_000_000L, 0, 2);
        Assert.NotEqual(x, y);
    }
}

public class RecurrenceTests
{
    private static readonly TimeZoneInfo Ist = TimeZoneInfo.FindSystemTimeZoneById("Asia/Kolkata");
    private static long Ms(int y, int m, int d, int hh, int mm) { Time.Zone = Ist; return Time.ToMs(new DateTime(y, m, d, hh, mm, 0)); }
    private static DateTime L(long ms) { Time.Zone = Ist; return Time.ToLocal(ms); }

    [Fact] public void Daily_advances_one_day_keeping_the_clock_time()
    {
        var due = Ms(2026, 9, 16, 18, 0);
        var i = new Item { Id = 1, DueAt = due, RepeatMode = "DAILY" };
        var next = Recurrence.NextOccurrence(i, due)!.Value;
        Assert.Equal(new DateTime(2026, 9, 17, 18, 0, 0), L(next));
    }

    [Fact] public void Weekly_on_Mon_Wed_skips_to_the_next_pattern_day()
    {
        var due = Ms(2026, 9, 16, 9, 30);   // Wednesday
        var i = new Item { Id = 1, DueAt = due, RepeatMode = "WEEKLY", RepeatDays = new() { 1, 3 } };
        var next = L(Recurrence.NextOccurrence(i, due)!.Value);
        Assert.Equal(DayOfWeek.Monday, next.DayOfWeek);
        Assert.Equal(new DateTime(2026, 9, 21, 9, 30, 0), next);
    }

    [Fact] public void MonthlyDay_31_clamps_to_short_months()
    {
        var due = Ms(2026, 1, 31, 10, 0);
        var i = new Item { Id = 1, DueAt = due, RepeatMode = "MONTHLY_DAY", RepeatDays = new() { 31 } };
        var next = L(Recurrence.NextOccurrence(i, due)!.Value);
        Assert.Equal(new DateTime(2026, 2, 28, 10, 0, 0), next);
    }

    [Fact] public void MonthlyOrd_last_Saturday()
    {
        var due = Ms(2026, 9, 1, 8, 0);
        var i = new Item { Id = 1, DueAt = due, RepeatMode = "MONTHLY_ORD", RepeatOrdList = new() { 56 } };   // 5 = Last, 6 = Sat
        var next = L(Recurrence.NextOccurrence(i, due)!.Value);
        Assert.Equal(new DateTime(2026, 9, 26, 8, 0, 0), next);
    }

    [Fact] public void EveryN_weeks_and_spaced_ladder()
    {
        var due = Ms(2026, 9, 16, 7, 0);
        var i = new Item { Id = 1, DueAt = due, RepeatMode = "EVERY_N", RepeatN = 2, RepeatUnit = "W" };
        Assert.Equal(new DateTime(2026, 9, 30, 7, 0, 0), L(Recurrence.NextOccurrence(i, due)!.Value));
        var sp = new Item { Id = 2, DueAt = due, RepeatMode = "SPACED", SpacedStep = 1 };
        Assert.Equal(due + 7 * 86_400_000L, Recurrence.NextOccurrence(sp, due));
    }

    [Fact] public void Off_returns_null_and_preview_gives_three_future_dates()
    {
        Assert.Null(Recurrence.NextOccurrence(new Item { Id = 1, DueAt = 1, RepeatMode = "OFF" }, 0));
        var now = Ms(2026, 9, 16, 12, 0);
        var p = Recurrence.Preview("DAILY", Array.Empty<int>(), 1, "D", 1, 1, Array.Empty<int>(), Ms(2026, 9, 10, 18, 0), 3, null, 0, now);
        Assert.Equal(3, p.Count);
        Assert.All(p, t => Assert.True(t > now));
        Assert.Equal(new DateTime(2026, 9, 16, 18, 0, 0), L(p[0]));
    }

    [Fact] public void RollMissedRepeats_moves_due_to_today_and_logs_misses_newest_first()
    {
        var now = Ms(2026, 9, 16, 12, 0);
        var i = new Item { Id = 1, DueAt = Ms(2026, 9, 13, 20, 0), RepeatMode = "DAILY" };
        var rolled = Recurrence.RollMissedRepeats(new[] { i }, now);
        var r = Assert.Single(rolled);
        Assert.Equal(new DateTime(2026, 9, 16, 20, 0, 0), L(r.DueAt!.Value));
        Assert.Equal(3, r.MissedAt.Count);
        Assert.Equal(Ms(2026, 9, 15, 20, 0), r.MissedAt[0]);
        Assert.Equal(Ms(2026, 9, 13, 20, 0), r.MissedAt[2]);
    }

    [Fact] public void Live_snooze_is_never_rolled()
    {
        var now = Ms(2026, 9, 16, 12, 0);
        var i = new Item { Id = 1, DueAt = Ms(2026, 9, 13, 20, 0), RepeatMode = "DAILY", SnoozedUntil = now + 60_000 };
        Assert.Empty(Recurrence.RollMissedRepeats(new[] { i }, now));
    }

    [Fact] public void ResurrectDue_walks_done_repeats_back_when_the_day_arrives()
    {
        var now = Ms(2026, 9, 16, 0, 5);
        var due = new Item { Id = 1, Done = true, RepeatMode = "DAILY", DueAt = Ms(2026, 9, 16, 18, 0) };
        var later = new Item { Id = 2, Done = true, RepeatMode = "DAILY", DueAt = Ms(2026, 9, 17, 18, 0) };
        var r = Recurrence.ResurrectDue(new[] { due, later }, now);
        Assert.Single(r);
        Assert.False(r[0].Done);
    }

    [Fact] public void ComputeReturnAt_months()
    {
        var from = Ms(2026, 1, 31, 9, 0);
        Assert.Equal(new DateTime(2026, 2, 28, 9, 0, 0), L(Recurrence.ComputeReturnAt(from, 1, LapseUnit.MONTHS)));
        Assert.Equal("1 day", Recurrence.LapseLabel(1, LapseUnit.DAYS));
        Assert.Equal("3 months", Recurrence.LapseLabel(3, LapseUnit.MONTHS));
    }
}

public class MergeTests
{
    [Fact] public void Newer_stamp_wins_ties_go_to_incoming_and_tombstones_propagate()
    {
        var local = new List<Item> { new() { Id = 1, Title = "old", UpdatedAt = 100 }, new() { Id = 2, Title = "keep", UpdatedAt = 500 } };
        var incoming = new[]
        {
            new Item { Id = 1, Title = "new", UpdatedAt = 200 },
            new Item { Id = 2, Title = "stale", UpdatedAt = 400 },
            new Item { Id = 3, Title = "deleted-elsewhere", UpdatedAt = 300, DeletedAt = 300 },
        };
        var (merged, changed, applied) = Merge.Fold(local, incoming, i => i.Id, Merge.StampOf);
        Assert.True(changed);
        Assert.Equal("new", merged.First(i => i.Id == 1).Title);
        Assert.Equal("keep", merged.First(i => i.Id == 2).Title);
        Assert.NotNull(merged.First(i => i.Id == 3).DeletedAt);
        Assert.Equal(2, applied.Count);
    }

    [Fact] public void Stamp_falls_back_to_createdAt_when_updatedAt_is_zero()
    {
        Assert.Equal(77, Merge.Stamp(0, 77));
        Assert.Equal(99, Merge.Stamp(99, 77));
    }

    [Fact] public void Older_schema_writer_overlays_only_the_keys_it_carries()
    {
        var local = """{"id":1,"title":"a","alertType":"A","missedAt":[1,2]}""";
        var remote = """{"id":1,"title":"b"}""";                      // an old writer that never knew alertType
        var json = Merge.OverlayIfOlder(remote, 52, local, out var kept);
        var o = System.Text.Json.Nodes.JsonNode.Parse(json)!.AsObject();
        Assert.Equal("b", o["title"]!.GetValue<string>());
        Assert.Equal("A", o["alertType"]!.GetValue<string>());
        Assert.Contains("alertType", kept);
        Assert.Contains("missedAt", kept);
        // Same-or-newer schema: remote taken verbatim.
        Assert.Equal(remote, Merge.OverlayIfOlder(remote, 71, local, out _));
    }

    [Fact] public void Settings_merge_keeps_device_local_fields_and_unions_groups()
    {
        var local = AppSettings.Parse("""{"settingsUpdatedAt":10,"cloudSync":true,"lastSyncAt":5,"tasksGroups":["Home"],"theme":"DARK"}""");
        var remote = AppSettings.Parse("""{"settingsUpdatedAt":20,"cloudSync":false,"lastSyncAt":0,"tasksGroups":["Work"],"theme":"LIGHT"}""");
        var m = Merge.MergeSettings(local, remote);
        Assert.Equal("LIGHT", m.Theme);
        Assert.True(m.CloudSync);
        Assert.Equal(5, m.LastSyncAt);
        Assert.Equal(new[] { "Home", "Work" }, m.TasksGroups);
        Assert.Same(local, Merge.MergeSettings(local, AppSettings.Parse("""{"settingsUpdatedAt":3}""")));   // older remote ignored
    }
}

public class ShopMathTests
{
    [Fact] public void Checkout_derives_the_third_value_and_the_discount()
    {
        var c = ShopMath.Compute(88, 5, null, 420);
        Assert.Equal(440, c.Cost);
        Assert.Equal(20, c.DiscountAmt);
        Assert.Equal(4.5455, Math.Round(c.DiscountPct!.Value, 4));
        Assert.Equal(88, ShopMath.Compute(null, 5, 440, null).UnitPrice);
        Assert.Equal(5, ShopMath.Compute(88, null, 440, null).Qty);
        Assert.Null(ShopMath.Compute(null, 0, 440, null).UnitPrice);   // zero qty guards the divide
    }

    [Fact] public void Cheapest_shop_normalises_kg_to_g_and_ignores_incomparable_units()
    {
        var items = new[]
        {
            new Item { Id = 1, Tab = Tab.SHOP, Title = "Rice", PriceHistory = new()
            {
                new PricePoint { At = 1, Shop = "A", Unit = "kg", UnitPrice = 80 },     // 0.080 /g
                new PricePoint { At = 2, Shop = "B", Unit = "g", UnitPrice = 0.075 },   // 0.075 /g  ← cheapest
                new PricePoint { At = 3, Shop = "C", Unit = "pcs", UnitPrice = 1 },     // different family
            } },
        };
        var best = ShopMath.CheapestShop(items, "rice", "kg");
        Assert.Equal("B", best!.Shop);
        Assert.Null(ShopMath.CheapestShop(items, "nothing"));
    }

    [Fact] public void TrimNum_and_PushPurchase_cap()
    {
        Assert.Equal("2", ShopMath.TrimNum(2.0));
        Assert.Equal("2.5", ShopMath.TrimNum(2.5));
        var h = new List<PricePoint>();
        for (var i = 0; i < 15; i++) h = ShopMath.PushPrice(h, i, i);
        Assert.Equal(12, h.Count);
        Assert.Equal(3, h[0].At);
    }

    [Fact] public void UnitFamily_and_parse_price()
    {
        Assert.Equal(("WEIGHT", 1000.0), ShopMath.UnitFamily("KG"));
        Assert.Equal(("COUNT", 12.0), ShopMath.UnitFamily("dozen"));
        Assert.Equal(("strip", 1.0), ShopMath.UnitFamily("strip"));
        Assert.Equal(1450.5, ShopMath.ParsePrice("₹1,450.50"));
        Assert.Null(ShopMath.ParsePrice("abc"));
    }
}

public class EngineTests
{
    private static readonly TimeZoneInfo Ist = TimeZoneInfo.FindSystemTimeZoneById("Asia/Kolkata");
    private static long Ms(int y, int m, int d, int hh, int mm) { Time.Zone = Ist; return Time.ToMs(new DateTime(y, m, d, hh, mm, 0)); }

    [Fact] public void Completing_a_recurring_item_advances_due_and_sits_in_Done()
    {
        var now = Ms(2026, 9, 16, 19, 0);
        var i = new Item { Id = 1, DueAt = Ms(2026, 9, 16, 18, 0), RepeatMode = "DAILY", SnoozedUntil = now + 1000 };
        var r = Engine.Complete(i, now);
        Assert.True(r.Updated.Done);
        Assert.Null(r.Updated.SnoozedUntil);
        Assert.Equal(Ms(2026, 9, 17, 18, 0), r.Updated.DueAt);
        Assert.Equal(1, r.Updated.RepeatDone);
        Assert.Equal("Done · returns", r.Ack);
    }

    [Fact] public void Exhausted_repeat_finishes_in_Done_without_advancing()
    {
        var now = Ms(2026, 9, 16, 19, 0);
        var i = new Item { Id = 1, DueAt = Ms(2026, 9, 16, 18, 0), RepeatMode = "DAILY", RepeatCount = 2, RepeatDone = 1 };
        var r = Engine.Complete(i, now);
        Assert.Equal("Done · repeat finished", r.Ack);
        Assert.Equal(Ms(2026, 9, 16, 18, 0), r.Updated.DueAt);
    }

    [Fact] public void Shop_completion_stamps_price_history_and_arms_lapse_return()
    {
        var now = Ms(2026, 9, 16, 19, 0);
        var i = new Item { Id = 1, Tab = Tab.SHOP, Price = "₹450", LapseValue = 7, LapseUnit = LapseUnit.DAYS };
        var r = Engine.Complete(i, now);
        Assert.Single(r.Updated.PriceHistory);
        Assert.Equal(450, r.Updated.PriceHistory[0].Price);
        Assert.Equal(Recurrence.ComputeReturnAt(now, 7, LapseUnit.DAYS), r.Updated.ReturnAt);
        var back = Engine.LapseReturn(r.Updated, now + 1);
        Assert.False(back.Done); Assert.Null(back.ReturnAt); Assert.Null(back.DueAt);
    }

    [Fact] public void Checkout_apply_writes_the_enriched_purchase()
    {
        var i = new Item { Id = 1, Tab = Tab.SHOP, Title = "Rice", Unit = "kg" };
        var calc = ShopMath.Compute(88, 5, null, 420);
        var e = Engine.ApplyCheckout(i, "D-Mart", calc, null, 123);
        Assert.Equal("D-Mart", e.ShopName);
        Assert.Equal("5", e.Quantity);
        Assert.Equal("440", e.Price);
        Assert.Equal(420, e.PriceHistory[0].Paid);
        Assert.Equal("kg", e.PriceHistory[0].Unit);
    }

    [Fact] public void Mute_and_quiet_follow_N44_and_N13()
    {
        var i = new Item { Id = 1, AlertType = "A", SnoozedUntil = 5 };
        var m = Engine.Mute(i);
        Assert.True(m.IsMuted); Assert.Null(m.SnoozedUntil);
        Assert.Equal("A", Engine.Unmute(m, "A").AlertType);
        Assert.Equal("N", Engine.Unmute(m, "garbage").AlertType);
        var q = Alerts.QuietDemote(i, 999);
        Assert.Equal("N", q.AlertType); Assert.Equal(999, q.SnoozedUntil);
    }

    [Fact] public void Alerts_inherit_rules_and_coming_up_rows()
    {
        var s = AppSettings.Parse("""{"alertsEnabled":false,"tasksAlertsOn":"ON","learnAlertsOn":"INHERIT"}""");
        Assert.True(Alerts.EnabledFor(Tab.TASKS, s));
        Assert.False(Alerts.EnabledFor(Tab.LEARN, s));
        var rows = Alerts.ComingUp(new Item { Id = 1, DueAt = 2000, SnoozedUntil = 1500, AlertType = "R" }, 1000);
        Assert.Equal(ComingUpKind.SNOOZE, rows[0].Kind);
        Assert.Equal("Snoozed · Ring", rows[0].What);
        Assert.Equal(SchedKind.NONE, Alerts.KindOf(new Item { Id = 1 }));
        Assert.Equal(SchedKind.ONCE, Alerts.SchedDefaultFor(AppSettings.Parse("""{"schedDefault":"NONE"}"""), Tab.SHOP));   // Shop clamps NONE→ONCE
    }

    [Fact] public void Default_new_due_follows_tab_and_global_settings()
    {
        var now = Ms(2026, 9, 16, 12, 0);
        var s = AppSettings.Parse("""{"tasksNewDueMode":"INHERIT","globalNewDueMode":"IN_N","globalNewDueDays":3,"globalNewDueMinutes":600,"globalNewDueTimed":false,"defaultDueMinutes":1080}""");
        var due = Alerts.DefaultNewDue(Tab.TASKS, s, now)!.Value;
        Assert.Equal(new DateTime(2026, 9, 19, 18, 0, 0), Time.ToLocal(due));   // date-only → default due time 18:00
        Assert.Null(Alerts.DefaultNewDue(Tab.LEARN, AppSettings.Parse("""{"learnNewDueMode":"OFF"}"""), now));
    }
}

public class HealTests
{
    [Fact] public void Radius_snaps_to_the_13_stops_tie_goes_low()
    {
        Assert.Equal(150f, Heal.SnapRadius(160f));
        Assert.Equal(50f, Heal.SnapRadius(0f));
        Assert.Equal(50f, Heal.SnapRadius(float.NaN));
        Assert.Equal(3000f, Heal.SnapRadius(9999f));
        Assert.Equal(1000f, Heal.SnapRadius(1250f));   // exact tie between 1000 and 1500 → lower
        Assert.Equal("1.5 km", Heal.RadiusLabel(1500f));
        Assert.Equal("2 km", Heal.RadiusLabel(2000f));
        Assert.Equal("150 m", Heal.RadiusLabel(150f));
    }

    [Fact] public void Alert_type_normalisation()
    {
        Assert.Null(Heal.NormalizeAlertTypes(null));
        Assert.Null(Heal.NormalizeAlertTypes("  "));
        Assert.Equal("", Heal.NormalizeAlertTypes(""));       // deliberate silence survives
        Assert.Equal("NA", Heal.NormalizeAlertTypes("xanx"));
        Assert.Equal("Alarm + Notify", Alerts.AlertTypesLabel("AN"));
        Assert.Equal("Silent", Alerts.AlertTypesLabel(""));
        Assert.Equal("Default", Alerts.AlertTypesLabel(null));
    }

    [Fact] public void Heal_item_coats_nulls_from_a_sparse_record()
    {
        var sparse = Remindly.Core.Json.Wire.FromJson<Item>("""{"id":1,"tab":"TASKS","title":null,"alertType":null,"repeatDays":null,"missedAt":null}""")!;
        var h = Heal.Item(sparse);
        Assert.Equal("", h.Title); Assert.Equal("N", h.AlertType); Assert.Empty(h.RepeatDays); Assert.Empty(h.MissedAt);
    }
}
