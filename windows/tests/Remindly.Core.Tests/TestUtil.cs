using Remindly.Core;

namespace Remindly.Core.Tests;

internal static class T
{
    /// <summary>Fixed zone (no DST) so every test gives the same answer on any runner.</summary>
    public static readonly TimeZoneInfo Zone = TimeZoneInfo.CreateCustomTimeZone("IST-test", TimeSpan.FromHours(5.5), "IST", "IST");

    public static long At(int y, int mo, int d, int h = 0, int mi = 0) => Clock.FromLocal(new DateTime(y, mo, d, h, mi, 0), Zone);

    public static DateTime Local(long ms) => Clock.ToLocal(ms, Zone);

    private static long _id = 1000;

    public static Item Buy(string title, long? listId = null, string? group = null, bool done = false, Priority? pri = null,
        string? qty = null, string? unit = null, string? price = null, string? shop = null, long created = 1) => new()
    {
        Id = ++_id, Tab = Tab.SHOP, Title = title, ListId = listId, Group = group, Done = done, Priority = pri,
        Quantity = qty, Unit = unit, Price = price, ShopName = shop, CreatedAt = created, UpdatedAt = created,
        DoneAt = done ? created : null,
    };

    public static ShopList List(long id, string name, int order = 0, bool pinned = false, long created = 1) =>
        new() { Id = id, Name = name, Order = order, Pinned = pinned, CreatedAt = created, UpdatedAt = created };
}
