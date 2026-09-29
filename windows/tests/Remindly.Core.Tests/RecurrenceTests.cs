using Remindly.Core;
using Xunit;

namespace Remindly.Core.Tests;

public class RecurrenceTests
{
    private static Item Rep(string mode, long due, Action<ItemBuilder>? f = null)
    {
        var b = new ItemBuilder { Item = new Item { Id = 1, Tab = Tab.TASKS, Title = "x", DueAt = due, RepeatMode = mode } };
        f?.Invoke(b);
        return b.Item;
    }

    public sealed class ItemBuilder { public Item Item = new(); }

    [Fact]
    public void Off_has_no_next() =>
        Assert.Null(Recurrence.NextOccurrence(Rep("OFF", T.At(2026, 9, 29, 9)), T.At(2026, 9, 29, 10), T.Zone));

    [Fact]
    public void Daily_moves_to_the_next_day_same_time()
    {
        var next = Recurrence.NextOccurrence(Rep("DAILY", T.At(2026, 9, 29, 9)), T.At(2026, 9, 29, 10), T.Zone);
        Assert.Equal(new DateTime(2026, 9, 30, 9, 0, 0), T.Local(next!.Value));
    }

    [Fact]
    public void Daily_catches_up_past_many_missed_days()
    {
        var next = Recurrence.NextOccurrence(Rep("DAILY", T.At(2026, 9, 1, 7, 30)), T.At(2026, 9, 29, 8), T.Zone);
        Assert.Equal(new DateTime(2026, 9, 30, 7, 30, 0), T.Local(next!.Value));
    }

    [Fact]
    public void Weekly_picks_the_next_selected_weekday()
    {
        // 29-Sep-2026 is a Tuesday. Weekly on Mon + Thu → Thursday 1-Oct.
        var item = Rep("WEEKLY", T.At(2026, 9, 29, 18), b => b.Item = b.Item with { RepeatDays = new() { 1, 4 } });
        var next = Recurrence.NextOccurrence(item, T.At(2026, 9, 29, 19), T.Zone);
        Assert.Equal(new DateTime(2026, 10, 1, 18, 0, 0), T.Local(next!.Value));
        var after = Recurrence.NextOccurrence(item with { DueAt = next }, next!.Value, T.Zone);
        Assert.Equal(new DateTime(2026, 10, 5, 18, 0, 0), T.Local(after!.Value));
    }

    [Fact]
    public void Monthly_day_set_clamps_to_short_months()
    {
        var item = Rep("MONTHLY_DAY", T.At(2026, 1, 31, 9), b => b.Item = b.Item with { RepeatDays = new() { 31 } });
        var next = Recurrence.NextOccurrence(item, T.At(2026, 1, 31, 10), T.Zone);
        Assert.Equal(new DateTime(2026, 2, 28, 9, 0, 0), T.Local(next!.Value));
    }

    [Fact]
    public void Monthly_day_set_takes_the_later_day_in_the_same_month()
    {
        var item = Rep("MONTHLY_DAY", T.At(2026, 9, 1, 9), b => b.Item = b.Item with { RepeatDays = new() { 1, 15 } });
        var next = Recurrence.NextOccurrence(item, T.At(2026, 9, 1, 10), T.Zone);
        Assert.Equal(new DateTime(2026, 9, 15, 9, 0, 0), T.Local(next!.Value));
    }

    [Fact]
    public void Monthly_ordinal_last_friday()
    {
        // Last Friday (ord 5, dow 5). Sept 2026's last Friday is 25-Sep; October's is 30-Oct.
        var item = Rep("MONTHLY_ORD", T.At(2026, 9, 25, 20), b => b.Item = b.Item with { RepeatOrdList = new() { 55 } });
        var next = Recurrence.NextOccurrence(item, T.At(2026, 9, 25, 21), T.Zone);
        Assert.Equal(new DateTime(2026, 10, 30, 20, 0, 0), T.Local(next!.Value));
    }

    [Fact]
    public void Monthly_ordinal_second_monday()
    {
        var item = Rep("MONTHLY_ORD", T.At(2026, 9, 14, 8), b => b.Item = b.Item with { RepeatOrdList = new() { 21 } });
        var next = Recurrence.NextOccurrence(item, T.At(2026, 9, 14, 9), T.Zone);
        Assert.Equal(new DateTime(2026, 10, 12, 8, 0, 0), T.Local(next!.Value));
    }

    [Theory]
    [InlineData("D", 3, 2026, 10, 2)]
    [InlineData("W", 2, 2026, 10, 13)]
    [InlineData("M", 1, 2026, 10, 29)]
    public void Every_n_units(string unit, int n, int y, int m, int d)
    {
        var item = Rep("EVERY_N", T.At(2026, 9, 29, 9), b => b.Item = b.Item with { RepeatN = n, RepeatUnit = unit });
        var next = Recurrence.NextOccurrence(item, T.At(2026, 9, 29, 10), T.Zone);
        Assert.Equal(new DateTime(y, m, d, 9, 0, 0), T.Local(next!.Value));
    }

    [Theory]
    [InlineData("QUARTERLY", 12)]
    [InlineData("HALFYEARLY", 3)]
    [InlineData("YEARLY", 9)]
    public void Long_period_modes(string mode, int expectedMonth)
    {
        var next = Recurrence.NextOccurrence(Rep(mode, T.At(2026, 9, 29, 9)), T.At(2026, 9, 29, 10), T.Zone);
        Assert.Equal(expectedMonth, T.Local(next!.Value).Month);
    }

    [Fact]
    public void Spaced_uses_the_revisit_ladder()
    {
        var item = Rep("SPACED", T.At(2026, 9, 1, 9), b => b.Item = b.Item with { SpacedStep = 1 });
        var next = Recurrence.NextOccurrence(item, T.At(2026, 9, 2, 9), T.Zone);
        Assert.Equal(new DateTime(2026, 9, 8, 9, 0, 0), T.Local(next!.Value)); // gap 7 days
        Assert.Equal(30, Recurrence.SpacedGapDays(99));
    }

    [Fact]
    public void Labels_match_android()
    {
        Assert.Equal("Weekly · Mon,Thu", Recurrence.Label(Rep("WEEKLY", 0, b => b.Item = b.Item with { RepeatDays = new() { 4, 1 } })));
        Assert.Equal("Monthly · same day", Recurrence.Label(Rep("MONTHLY_DAY", 0)));
        Assert.Equal("Last Fri + First Mon", Recurrence.Label(Rep("MONTHLY_ORD", 0, b => b.Item = b.Item with { RepeatOrdList = new() { 55, 11 } })));
        Assert.Equal("Every 2 week(s)", Recurrence.Label(Rep("EVERY_N", 0, b => b.Item = b.Item with { RepeatN = 2, RepeatUnit = "W" })));
        Assert.Null(Recurrence.Label(Rep("OFF", 0)));
    }

    [Fact]
    public void Preview_starts_on_the_anchor_day_for_day_set_modes()
    {
        var item = Rep("WEEKLY", T.At(2026, 10, 1, 9), b => b.Item = b.Item with { RepeatDays = new() { 4 } }); // Thursday
        var p = Recurrence.Preview(item, T.At(2026, 9, 29, 12), 3, T.Zone);
        Assert.Equal(new[] { 1, 8, 15 }, p.Select(x => T.Local(x).Day).ToArray());
    }

    [Fact]
    public void Repeat_count_rules()
    {
        Assert.True(Recurrence.Exhausted(3, 3));
        Assert.False(Recurrence.Exhausted(null, 50));
        Assert.Null(Recurrence.SanitizeCount(1));
        Assert.Equal(5, Recurrence.SanitizeCount(5));
    }
}
