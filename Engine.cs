using Remindly.Core.Models;

namespace Remindly.Core.Logic;

/// <summary>
/// Pure state transitions ported from Engine.kt (complete / undo / delete / restore / mute /
/// snooze / quiet). Each returns the new record; the Repository stamps updatedAt and persists.
/// </summary>
public static class Engine
{
    public sealed record CompleteResult(Item Updated, string Ack, long? ReturnsAt);

    /// <summary>
    /// completeNow: recurring items advance dueAt to the next occurrence and sit in Done until
    /// the midnight resurrection walks them back; an exhausted repeat finishes in Done; Shop
    /// completions stamp the price history and arm the lapse-return.
    /// </summary>
    public static CompleteResult Complete(Item item, long now, bool shopStamp = true)
    {
        var baseItem = item with { SnoozedUntil = null };
        if (baseItem.RepeatMode != "OFF")
        {
            var tally = baseItem.RepeatDone + 1;
            if (Recurrence.RepeatExhausted(baseItem.RepeatCount, tally))
                return new CompleteResult(baseItem with { Done = true, DoneAt = now, RepeatDone = tally, ReturnAt = null }, "Done · repeat finished", null);
            var next = Recurrence.NextOccurrence(baseItem, now);
            if (next is long n)
            {
                var completed = baseItem with
                {
                    Done = true, DoneAt = now, DueAt = n, DueHasTime = true, ReturnAt = null, RepeatDone = tally,
                    SpacedStep = baseItem.RepeatMode == "SPACED" ? baseItem.SpacedStep + 1 : baseItem.SpacedStep,
                    PriceHistory = baseItem.Tab == Tab.SHOP && shopStamp && ShopMath.ParsePrice(baseItem.Price) is double p
                        ? ShopMath.PushPrice(baseItem.PriceHistory, now, p) : baseItem.PriceHistory,
                };
                return new CompleteResult(completed, "Done · returns", n);
            }
        }
        var updated = baseItem with { Done = true, DoneAt = now, ReturnAt = null };
        if (baseItem.Tab == Tab.SHOP && shopStamp && ShopMath.ParsePrice(baseItem.Price) is double pp)
            updated = updated with { PriceHistory = ShopMath.PushPrice(baseItem.PriceHistory, now, pp) };
        if (baseItem.Tab == Tab.SHOP && baseItem.LapseValue is int lv && lv > 0 && baseItem.LapseUnit is LapseUnit lu)
            updated = updated with { ReturnAt = Recurrence.ComputeReturnAt(now, lv, lu) };
        return new CompleteResult(updated, "Moved to Done", null);
    }

    public static Item UndoDone(Item item) => item with { Done = false, DoneAt = null, ReturnAt = null };

    public static Item SoftDelete(Item item, long now) => item with { DeletedAt = now };
    public static Item Restore(Item item) => item with { DeletedAt = null };

    /// <summary>"Delete this alert" (N44): the record stays, nothing is armed.</summary>
    public static Item Mute(Item item) => item with { AlertType = Constants.AlertMuted, SnoozedUntil = null };
    public static Item Unmute(Item item, string previousType) => item with { AlertType = previousType is "A" or "R" or "N" ? previousType : "N" };

    public static Item Snooze(Item item, long until) => item with { SnoozedUntil = until };
    public static Item CancelSnooze(Item item) => item with { SnoozedUntil = null };

    /// <summary>TYPE_LAPSE: a Done shop item comes back to Active when its lapse elapses.</summary>
    public static Item LapseReturn(Item item, long now) => item with { Done = false, DoneAt = null, ReturnAt = null, CreatedAt = now, DueAt = null, ExpiryAt = null };

    /// <summary>finishShopComplete: apply the checkout calculator to the item and record the purchase.</summary>
    public static Item ApplyCheckout(Item item, string shop, ShopCalc calc, string? unit, long now)
    {
        var point = new PricePoint
        {
            At = now, Price = calc.Cost ?? 0.0, Shop = shop.Trim(), Qty = calc.Qty ?? 0.0,
            Unit = unit ?? item.Unit ?? "", UnitPrice = calc.UnitPrice ?? 0.0, Paid = calc.Paid ?? 0.0, DiscountPct = calc.DiscountPct ?? 0.0,
        };
        return item with
        {
            ShopName = string.IsNullOrWhiteSpace(shop) ? item.ShopName : shop.Trim(),
            Unit = unit ?? item.Unit,
            Quantity = calc.Qty is double q ? ShopMath.TrimNum(q) : item.Quantity,
            Price = calc.Cost is double c ? ShopMath.TrimNum(c) : item.Price,
            PriceHistory = ShopMath.PushPurchase(item.PriceHistory, point),
        };
    }

    // ── calls ──
    public static CallReminder CompleteCall(CallReminder r, long now, string? note = null)
    {
        var done = r with { Done = true, DoneAt = now, SnoozedUntil = null, ClearedNote = note ?? r.ClearedNote, NagAt = null, NagFired = false };
        if (r.RepeatMode != "OFF")
        {
            var tally = r.RepeatDone + 1;
            if (Recurrence.RepeatExhausted(r.RepeatCount, tally)) return done with { RepeatDone = tally, RecurAt = null };
            var next = Recurrence.NextOccurrenceCall(r, now);
            return done with { RepeatDone = tally, RecurAt = next };
        }
        return done;
    }
    public static CallReminder UndoCall(CallReminder r) => r with { Done = false, DoneAt = null };
    public static CallReminder SnoozeCall(CallReminder r, long until) => r with { SnoozedUntil = until };
}
