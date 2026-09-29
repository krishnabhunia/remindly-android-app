using Remindly.Core;
using Xunit;

namespace Remindly.Core.Tests;

[Collection("storage")]
public class StorageTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "remindly-tests-" + Guid.NewGuid().ToString("N"));
    private readonly string _prevRoot = AppPaths.DataRoot;

    public StorageTests() => AppPaths.DataRoot = _dir;

    public void Dispose()
    {
        AppPaths.DataRoot = _prevRoot;
        try { Directory.Delete(_dir, true); } catch { }
    }

    /// <summary>A trimmed Android 2.11 backup, as Gson writes it (nulls, enum names, extra fields Windows ignores).</summary>
    private const string AndroidBackup = """
    {
      "version": 2, "app": "Remindly", "exportedAt": 1727600000000,
      "items": [
        {"id": 11, "tab": "SHOP", "title": "Milk", "notes": "", "createdAt": 1727500000000, "priority": "URGENT",
         "alertType": "N", "quantity": "2", "unit": "L", "group": "Groceries", "listId": null, "repeatMode": "OFF",
         "repeatDays": [], "repeatOrdList": [], "missedAt": null, "priceHistory": [], "done": false, "updatedAt": 1727500000000,
         "someFutureField": {"x": 1}},
        {"id": 12, "tab": "TASKS", "title": "Pay rent", "createdAt": 1727500000000, "dueAt": 1727600000000, "dueHasTime": true,
         "repeatMode": "MONTHLY_DAY", "repeatDays": [1], "repeatUnit": null, "done": false, "updatedAt": 1727500000000},
        {"id": 13, "tab": "LEARN", "title": "Kotlin course", "platform": "Udemy", "progress": 40, "createdAt": 1, "updatedAt": 1}
      ],
      "places": [{"id": 5, "name": "Home", "lat": 22.5, "lng": 88.3, "radius": 150.0, "trigger": "ARRIVE"}],
      "calls": [{"id": 21, "number": "+919830012345", "name": "Asha", "source": "MANUAL", "createdAt": 1, "updatedAt": 1, "repeatMode": "OFF"}],
      "settings": {"ver": 43, "fontScale": 1.0, "shopGroups": ["Groceries"], "groupIcons": {"Groceries": "🥦"},
                   "shopLists": [], "shareIncludeDone": true, "shareHeadingSuffix": " -", "updateWifiOnly": true}
    }
    """;

    [Fact]
    public void Android_backup_parses_and_heals_into_lists()
    {
        var blob = DataStore.Parse(AndroidBackup)!;
        Assert.NotNull(blob);
        Assert.True(DataStore.LooksLikeAndroid(blob));
        var d = DataStore.Heal(blob, 1727600000000);
        var milk = d.Items.Single(i => i.Id == 11);
        Assert.Equal(ShopLists.SeedId("Groceries"), milk.ListId);
        Assert.Equal(Priority.URGENT, milk.Priority);
        Assert.Empty(milk.MissedAt);
        Assert.Equal("D", d.Items.Single(i => i.Id == 12).RepeatUnit);
        Assert.Equal("🥦", d.Settings.ShopLists.Single().Icon);
        Assert.Equal(" -", d.Settings.ShareHeadingSuffix);
        Assert.Equal("Asha", d.Calls!.Single().Display);
        Assert.InRange(d.Settings.DeviceTag, 1, 0xFFF);
    }

    [Fact]
    public void Not_a_backup_is_rejected()
    {
        Assert.Null(DataStore.Parse("{\"hello\": 1}"));
        Assert.Null(DataStore.Parse("not json"));
    }

    [Fact]
    public void Save_and_load_round_trip_with_daily_backup()
    {
        var d = DataStore.Load();
        d.Items.Add(new Item { Id = 1, Tab = Tab.TASKS, Title = "One", CreatedAt = 1 });
        DataStore.Save(d);
        d.Items.Add(new Item { Id = 2, Tab = Tab.TASKS, Title = "Two", CreatedAt = 2 });
        DataStore.Save(d);
        var back = DataStore.Load();
        Assert.Equal(new[] { "One", "Two" }, back.Items.Select(i => i.Title).ToArray());
        Assert.Single(Directory.GetFiles(AppPaths.BackupDir, "remindly-data-*.json"));
        Assert.Contains("\"tab\": \"TASKS\"", File.ReadAllText(AppPaths.DataFile));
    }

    [Fact]
    public void Broken_file_falls_back_to_the_newest_backup()
    {
        var d = DataStore.Load();
        d.Items.Add(new Item { Id = 1, Tab = Tab.TASKS, Title = "Kept", CreatedAt = 1 });
        DataStore.Save(d);
        DataStore.Save(d); // creates the daily backup
        File.WriteAllText(AppPaths.DataFile, "{ broken");
        var back = DataStore.Load();
        Assert.Equal("Kept", back.Items.Single().Title);
    }

    [Fact]
    public void Import_merges_latest_wins_and_is_idempotent()
    {
        var current = DataStore.Heal(new RemindlyData(), 1727600000000);
        current.Items.Add(new Item { Id = 12, Tab = Tab.TASKS, Title = "Pay rent (edited on PC)", UpdatedAt = 1727700000000 });
        var first = DataStore.MergeInto(current, DataStore.Parse(AndroidBackup)!, 1727600000000);
        Assert.True(first.FromAndroid);
        Assert.Equal(2, first.Items);   // 11 and 13 are new; 12 is older on the phone
        Assert.Equal(1, first.Calls);
        Assert.Equal(1, first.Lists);
        Assert.Equal("Pay rent (edited on PC)", current.Items.Single(i => i.Id == 12).Title);
        var again = DataStore.MergeInto(current, DataStore.Parse(AndroidBackup)!, 1727600000000);
        Assert.Equal(0, again.Items);
        Assert.Equal(0, again.Lists);
        Assert.Single(current.Settings.ShopLists);
    }

    [Fact]
    public void Duplicate_ids_keep_the_newest_copy()
    {
        var d = new RemindlyData
        {
            Items = new()
            {
                new Item { Id = 7, Title = "old", UpdatedAt = 1 },
                new Item { Id = 8, Title = "other", UpdatedAt = 1 },
                new Item { Id = 7, Title = "new", UpdatedAt = 5 },
            },
        };
        var healed = DataStore.Heal(d, 10);
        Assert.Equal(new[] { "new", "other" }, healed.Items.Select(i => i.Title).ToArray());
    }

    [Fact]
    public void Bin_is_purged_after_thirty_days()
    {
        long now = 100L * 24 * 3600_000;
        var d = new RemindlyData
        {
            Items = new()
            {
                new Item { Id = 1, Title = "old", DeletedAt = now - ItemRules.BinKeepMs - 1 },
                new Item { Id = 2, Title = "recent", DeletedAt = now - 1000 },
            },
        };
        Assert.Equal("recent", DataStore.Heal(d, now).Items.Single().Title);
    }
}
