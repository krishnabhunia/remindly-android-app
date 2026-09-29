namespace Remindly.Core;

public enum AlertKind { ITEM, CALL, SHOPPING_DAY }

/// <summary>An alert that is due to be shown now.</summary>
public sealed record DueAlert(AlertKind Kind, long RecordId, long FireAt, string Title, string Body, string Key, bool Ring);

/// <summary>
/// Which reminders must fire. Windows has no AlarmManager: a timer asks this every few seconds and
/// shows whatever is due that was not shown before (keys are persisted, so a restart never repeats
/// one). Alerts missed while the PC was off fire once at the next start if they are under a day old.
/// </summary>
public static class Reminders
{
    /// <summary>Alerts older than this at start-up are not shown any more (they stay visible as Overdue cards).</summary>
    public const long CatchUpWindowMs = 24L * 3600_000L;
    public const int FiredKeysCap = 600;

    public static string ItemKey(long id, long at) => $"I{id}@{at}";
    public static string CallKey(long id, long at) => $"C{id}@{at}";
    public static string DayKey(long listId, long at) => $"S{listId}@{at}";

    /// <summary>When an item alerts: its live snooze, else its due time. Muted, done and deleted items never do.</summary>
    public static long? FireTimeOf(Item i)
    {
        if (i.Done || i.DeletedAt != null || i.AlertType == "OFF") return null;
        return i.SnoozedUntil ?? i.DueAt;
    }

    public static long? FireTimeOf(CallReminder r)
    {
        if (r.Done || r.DeletedAt != null || r.AlertType == "OFF") return null;
        return r.SnoozedUntil ?? r.RecurAt;
    }

    public static List<DueAlert> Due(RemindlyData data, long now, ISet<string> fired, TimeZoneInfo? zone = null)
    {
        var outList = new List<DueAlert>();
        long floor = now - CatchUpWindowMs;
        var lists = data.Settings.ShopLists;
        foreach (var i in data.Items)
        {
            if (FireTimeOf(i) is not long at || at > now || at < floor) continue;
            var key = ItemKey(i.Id, at);
            if (fired.Contains(key)) continue;
            var where = i.Tab == Tab.SHOP
                ? (ShopLists.ListIdOf(i, lists) is long lid ? ShopLists.Live(lists).First(l => l.Id == lid).Name : "Unsorted")
                : TabNames.Title(i.Tab);
            var body = i.Personal ? "Personal item" : string.IsNullOrWhiteSpace(i.Notes) ? where : $"{where} · {i.Notes.Trim()}";
            outList.Add(new DueAlert(AlertKind.ITEM, i.Id, at, i.Personal ? "Remindly" : i.Title, body, key, i.AlertType.Contains('R') || i.AlertType.Contains('A')));
        }
        foreach (var r in data.Calls ?? new())
        {
            if (FireTimeOf(r) is not long at || at > now || at < floor) continue;
            var key = CallKey(r.Id, at);
            if (fired.Contains(key)) continue;
            var body = string.IsNullOrWhiteSpace(r.Note) ? r.Number : $"{r.Number} · {r.Note!.Trim()}";
            outList.Add(new DueAlert(AlertKind.CALL, r.Id, at, "Call back " + r.Display, body, key, r.AlertType.Contains('R') || r.AlertType.Contains('A')));
        }
        foreach (var l in ShopLists.Live(lists))
        {
            if (l.ShoppingDay is not long day) continue;
            long at = ShopLists.ShoppingDayFireAt(day, zone);
            if (at > now || at < floor) continue;
            var key = DayKey(l.Id, at);
            if (fired.Contains(key)) continue;
            int open = ShopLists.ItemsIn(data.Items, l.Id, lists).Count(i => !i.Done);
            outList.Add(new DueAlert(AlertKind.SHOPPING_DAY, l.Id, at, $"Shopping day · {l.Name}", open == 0 ? "Nothing left to buy" : $"{open} to buy", key, false));
        }
        return outList.OrderBy(a => a.FireAt).ToList();
    }

    /// <summary>The next fire time after now (the tray tooltip "Next: …").</summary>
    public static long? NextFire(RemindlyData data, long now)
    {
        var times = data.Items.Select(FireTimeOf).Concat((data.Calls ?? new()).Select(FireTimeOf))
            .Concat(ShopLists.UpcomingShoppingDays(data.Settings.ShopLists, now).Select(x => (long?)x.FireAt))
            .Where(t => t is long v && v > now).Select(t => t!.Value).ToList();
        return times.Count == 0 ? null : times.Min();
    }

    /// <summary>Keeps the fired-key log bounded (newest kept).</summary>
    public static List<string> TrimFired(List<string> keys) => keys.Count <= FiredKeysCap ? keys : keys.Skip(keys.Count - FiredKeysCap).ToList();

    public static long SnoozeTarget(long now, int minutes) => now + Math.Clamp(minutes, 1, 24 * 60) * 60_000L;
}
