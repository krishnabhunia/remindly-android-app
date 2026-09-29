namespace Remindly.Core;

public sealed record CompleteResult(Item Item, string Message);

public sealed record DayGroup<T>(string Key, string Label, List<T> Items);

/// <summary>
/// Completing, reviving and binning records — the same rules as Android's Engine.completeNow,
/// resurrectDue and the 30-day Bin.
/// </summary>
public static class ItemRules
{
    public const long BinKeepMs = 30L * 24 * 60 * 60 * 1000;

    /// <summary>
    /// Tick an item. A recurring item goes to Done with its due date moved to the next occurrence
    /// (it walks back into Active on that day); an exhausted repeat finishes in Done; a Buy item
    /// stamps its price history and arms its lapse-return.
    /// </summary>
    public static CompleteResult Complete(Item item, long now, TimeZoneInfo? zone = null)
    {
        if (item.Done) return new CompleteResult(item, "Already done");
        var baseItem = item with { SnoozedUntil = null, UpdatedAt = now };
        List<PricePoint> Stamped() => item.Tab == Tab.SHOP && ShopLists.ParseNum(item.Price) is double p
            ? item.PriceHistory.Append(new PricePoint { At = now, Price = p, Shop = item.ShopName ?? "", Unit = item.Unit ?? "" }).TakeLast(12).ToList()
            : item.PriceHistory;

        if (item.RepeatMode != "OFF")
        {
            int tally = item.RepeatDone + 1;
            if (Recurrence.Exhausted(item.RepeatCount, tally))
                return new CompleteResult(baseItem with { Done = true, DoneAt = now, RepeatDone = tally, ReturnAt = null }, "Done · repeat finished");
            var next = Recurrence.NextOccurrence(item, now, zone);
            if (next is long n)
            {
                return new CompleteResult(baseItem with
                {
                    Done = true, DoneAt = now, DueAt = n, DueHasTime = true, ReturnAt = null, RepeatDone = tally,
                    SpacedStep = item.RepeatMode == "SPACED" ? item.SpacedStep + 1 : item.SpacedStep,
                    PriceHistory = Stamped(),
                }, "Done · returns " + Clock.FormatDayTime(n));
            }
        }
        var updated = baseItem with { Done = true, DoneAt = now, ReturnAt = null, PriceHistory = Stamped() };
        if (item.Tab == Tab.SHOP && item.LapseValue is int lv && lv > 0 && item.LapseUnit is LapseUnit lu)
        {
            var ret = Recurrence.ComputeReturnAt(now, lv, lu, zone);
            return new CompleteResult(updated with { ReturnAt = ret }, "Bought · back on the list " + Clock.FormatDay(ret));
        }
        return new CompleteResult(updated, item.Tab == Tab.SHOP ? "Bought" : "Moved to Done");
    }

    /// <summary>Back from Done to Active.</summary>
    public static Item Revive(Item item, long now) => item with { Done = false, DoneAt = null, ReturnAt = null, UpdatedAt = now };

    /// <summary>
    /// Done records that must come back to Active now: a recurring item whose next-occurrence DAY has
    /// arrived, and a bought item whose lapse-return time has passed.
    /// </summary>
    public static List<Item> ResurrectDue(IEnumerable<Item> items, long now, TimeZoneInfo? zone = null)
    {
        var today = Clock.LocalDate(now, zone);
        return items.Where(i => i.Done && i.DeletedAt == null && (
                (i.RepeatMode != "OFF" && i.DueAt is long d && !(Clock.LocalDate(d, zone) > today) && !Recurrence.Exhausted(i.RepeatCount, i.RepeatDone))
                || (i.ReturnAt is long r && r <= now)))
            .Select(i => i with { Done = false, DoneAt = null, ReturnAt = null, UpdatedAt = now })
            .ToList();
    }

    public static List<CallReminder> ResurrectCallsDue(IEnumerable<CallReminder> calls, long now, TimeZoneInfo? zone = null)
    {
        var today = Clock.LocalDate(now, zone);
        return calls.Where(r => r.Done && r.DeletedAt == null && r.RepeatMode != "OFF" && r.RecurAt is long d
                && !(Clock.LocalDate(d, zone) > today) && !Recurrence.Exhausted(r.RepeatCount, r.RepeatDone))
            .Select(r => r with { Done = false, DoneAt = null, UpdatedAt = now })
            .ToList();
    }

    /// <summary>Call-back done: a recurring one moves to its next occurrence, a one-off goes to Done.</summary>
    public static CompleteResult<CallReminder> CompleteCall(CallReminder r, long now, TimeZoneInfo? zone = null)
    {
        var baseR = r with { SnoozedUntil = null, UpdatedAt = now };
        if (r.RepeatMode != "OFF")
        {
            int tally = r.RepeatDone + 1;
            if (!Recurrence.Exhausted(r.RepeatCount, tally) && Recurrence.NextOccurrence(r, now, zone) is long n)
                return new(baseR with { Done = true, DoneAt = now, RecurAt = n, RepeatDone = tally }, "Done · next call " + Clock.FormatDayTime(n));
            return new(baseR with { Done = true, DoneAt = now, RepeatDone = tally }, "Done · repeat finished");
        }
        return new(baseR with { Done = true, DoneAt = now }, "Call-back done");
    }

    /// <summary>An open item with the same title already on this tab (the duplicate warning).</summary>
    public static bool DupActiveMatch(IEnumerable<Item> items, Tab tab, string title, long? exceptId = null)
    {
        var t = title.Trim().ToLowerInvariant();
        if (t.Length == 0) return false;
        return items.Any(i => i.Tab == tab && !i.Done && i.DeletedAt == null && i.Id != exceptId && i.Title.Trim().ToLowerInvariant() == t);
    }

    /// <summary>Cards pending today (due today or overdue).</summary>
    public static int PendingTodayCount(IEnumerable<Item> items, Tab tab, long now, TimeZoneInfo? zone = null)
    {
        long tomorrow = Clock.StartOfNextDay(now, zone);
        return items.Count(i => i.Tab == tab && !i.Done && i.DeletedAt == null && i.DueAt is long d && d < tomorrow);
    }

    public static bool IsOverdue(long? dueAt, long now) => dueAt is long d && d < now;

    /// <summary>Tombstones older than 30 days are purged for good.</summary>
    public static long PurgeCutoff(long now) => now - BinKeepMs;

    /// <summary>
    /// The Active list grouped by day: Overdue · Today · Tomorrow · each later day · No date.
    /// Done lists group by completion day, newest first.
    /// </summary>
    public static List<DayGroup<Item>> GroupByDay(IEnumerable<Item> items, bool doneView, long now, TimeZoneInfo? zone = null)
    {
        var list = items.ToList();
        var today = Clock.LocalDate(now, zone);
        if (doneView)
        {
            return list.OrderByDescending(i => i.DoneAt ?? i.UpdatedAt)
                .GroupBy(i => Clock.LocalDate(i.DoneAt ?? i.UpdatedAt, zone))
                .Select(g => new DayGroup<Item>(g.Key.ToString("yyyy-MM-dd"), DayLabel(g.Key, today), g.ToList()))
                .ToList();
        }
        var result = new List<DayGroup<Item>>();
        var overdue = list.Where(i => i.DueAt is long d && Clock.LocalDate(d, zone) < today).OrderBy(i => i.DueAt).ToList();
        if (overdue.Count > 0) result.Add(new DayGroup<Item>("overdue", "Overdue", overdue));
        foreach (var g in list.Where(i => i.DueAt is long d && Clock.LocalDate(d, zone) >= today)
                     .OrderBy(i => i.DueAt).GroupBy(i => Clock.LocalDate(i.DueAt!.Value, zone)))
            result.Add(new DayGroup<Item>(g.Key.ToString("yyyy-MM-dd"), DayLabel(g.Key, today), g.OrderBy(i => i.DueAt).ThenBy(i => PriorityNames.Rank(i.Priority)).ToList()));
        var none = list.Where(i => i.DueAt == null).OrderBy(i => PriorityNames.Rank(i.Priority)).ThenByDescending(i => i.CreatedAt).ToList();
        if (none.Count > 0) result.Add(new DayGroup<Item>("none", "No date", none));
        return result;
    }

    public static string DayLabel(DateTime day, DateTime today)
    {
        if (day == today) return "Today";
        if (day == today.AddDays(1)) return "Tomorrow";
        if (day == today.AddDays(-1)) return "Yesterday";
        return day.ToString(day.Year == today.Year ? "ddd, dd MMM" : "ddd, dd MMM yyyy", System.Globalization.CultureInfo.InvariantCulture);
    }
}

public sealed record CompleteResult<T>(T Record, string Message);
