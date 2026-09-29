using Remindly.Core.Json;
using Remindly.Core.Logic;
using Remindly.Core.Models;
using Remindly.Core.Store;

namespace Remindly.Core.Tests;

public class RepositoryTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "remindly-tests-" + Guid.NewGuid().ToString("N"));
    public void Dispose() { try { Directory.Delete(_dir, true); } catch { } }

    private Repository NewRepo() { var r = new Repository(_dir); r.Load(); return r; }

    [Fact] public void Upsert_stamps_updatedAt_persists_and_emits_a_local_write()
    {
        var repo = NewRepo();
        var writes = new List<(string col, string id, string json, long stamp)>();
        repo.LocalWrite += (c, i, j, s) => writes.Add((c, i, j, s));
        Clock.Provider = () => 1_000;
        var saved = repo.Upsert(new Item { Id = 42, Title = "Milk", Tab = Tab.SHOP });
        Clock.Provider = () => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        Assert.Equal(1_000, saved.UpdatedAt);
        var w = Assert.Single(writes);
        Assert.Equal(("items", "42"), (w.col, w.id));
        Assert.Contains("\"title\":\"Milk\"", w.json);
        // survives a reload from the same files the phone uses (items.json)
        Assert.True(File.Exists(Path.Combine(_dir, "items.json")));
        var again = NewRepo();
        Assert.Equal("Milk", again.Item(42)!.Title);
        Assert.InRange(again.Prefs.DeviceTag, 1, 0xFFF);
    }

    [Fact] public void ApplyRemote_uses_LWW_and_does_not_echo_a_local_write()
    {
        var repo = NewRepo();
        repo.Upsert(new Item { Id = 1, Title = "local", UpdatedAt = 0 });
        var localStamp = repo.Item(1)!.UpdatedAt;
        var writes = 0; repo.LocalWrite += (_, _, _, _) => writes++;
        var newer = Wire.ToJson(new Item { Id = 1, Title = "phone", UpdatedAt = localStamp + 10 });
        var older = Wire.ToJson(new Item { Id = 2, Title = "old-phone", UpdatedAt = 5, CreatedAt = 5 });
        var n = repo.ApplyRemote(Collections.Items, new[] { (newer, 71), (older, 71) }, out var warnings);
        Assert.Equal(2, n);
        Assert.Equal("phone", repo.Item(1)!.Title);
        Assert.Equal("old-phone", repo.Item(2)!.Title);
        Assert.Equal(0, writes);
        Assert.Empty(warnings);
        // a stale remote copy loses
        var stale = Wire.ToJson(new Item { Id = 1, Title = "stale", UpdatedAt = localStamp - 1 });
        repo.ApplyRemote(Collections.Items, new[] { (stale, 71) }, out _);
        Assert.Equal("phone", repo.Item(1)!.Title);
    }

    [Fact] public void ApplyRemote_from_an_older_schema_keeps_local_fields_and_warns()
    {
        var repo = NewRepo();
        repo.Upsert(new Item { Id = 1, Title = "a", AlertType = "A" });
        var stamp = repo.Item(1)!.UpdatedAt + 1;
        var oldWriter = $$"""{"id":1,"tab":"TASKS","title":"b","updatedAt":{{stamp}},"createdAt":1}""";
        repo.ApplyRemote(Collections.Items, new[] { (oldWriter, 52) }, out var warnings);
        Assert.Equal("b", repo.Item(1)!.Title);
        Assert.Equal("A", repo.Item(1)!.AlertType);
        Assert.Contains(warnings, w => w.Contains("older sync schema v52"));
    }

    [Fact] public void Shop_default_is_single_and_demotions_sync()
    {
        var repo = NewRepo();
        var writes = new List<string>(); repo.LocalWrite += (c, i, _, _) => writes.Add($"{c}/{i}");
        repo.Upsert(new Shop { Id = 1, Name = "A", IsDefault = true });
        repo.Upsert(new Shop { Id = 2, Name = "B", IsDefault = true });
        Assert.False(repo.Shop(1)!.IsDefault);
        Assert.True(repo.Shop(2)!.IsDefault);
        Assert.Equal(new[] { "shops/1", "shops/1", "shops/2" }, writes);
        repo.DeleteShop(2);
        Assert.Null(repo.Shop(2));
        Assert.NotNull(repo.Shops.First(s => s.Id == 2).DeletedAt);
    }

    [Fact] public void Product_links_key_and_checkout_write_back()
    {
        var repo = NewRepo();
        repo.Upsert(new Product { Id = 10, Name = "Rice", DefaultUnit = "kg" });
        repo.Upsert(new Shop { Id = 20, Name = "D-Mart" });
        repo.SetLink(10, 20, true, 440, 88);
        var l = Assert.Single(repo.LinksFor(10));
        Assert.Equal("10_20", l.Key);
        repo.RecordPrice(10, 20, 450, 90, 999);
        Assert.Equal(90, repo.LinksFor(10).Single().LastUnitPrice);
        repo.DeleteProduct(10);
        Assert.Empty(repo.LinksFor(10));
        Assert.Empty(repo.ActiveProducts);
    }

    [Fact] public void Settings_update_bumps_stamp_and_strips_device_local_fields_for_sync()
    {
        var repo = NewRepo();
        AppSettings? pushed = null; repo.LocalSettingsWrite += s => pushed = s;
        repo.UpdateSettings(s => { s.Theme = "DARK"; s.CloudSync = true; s.LastSyncAt = 77; });
        Assert.Equal("DARK", repo.Settings.Theme);
        Assert.True(repo.Settings.SettingsUpdatedAt > 0);
        Assert.NotNull(pushed);
        Assert.False(pushed!.CloudSync);
        Assert.Equal(0, pushed.LastSyncAt);
        Assert.Equal("DARK", pushed.Theme);
    }

    [Fact] public void Phone_data_backup_imports_with_merge_semantics()
    {
        var repo = NewRepo();
        repo.Upsert(new Item { Id = 1, Title = "mine" });
        var blob = """
        {"kind":"remindly-data","items":[{"id":1,"tab":"TASKS","title":"phone","updatedAt":1,"createdAt":1},{"id":9,"tab":"LEARN","title":"course","createdAt":5}],
         "calls":[{"id":3,"number":"9876543210","source":"MANUAL","createdAt":2}],"places":[],"tasksGroups":["Home"],"shopGroups":[],"learnTopics":["AI"]}
        """;
        var msg = repo.ImportJson(blob);
        Assert.Equal("mine", repo.Item(1)!.Title);           // local is newer → kept
        Assert.Equal("course", repo.Item(9)!.Title);
        Assert.Single(repo.Calls);
        Assert.Contains("Home", repo.Settings.TasksGroups);
        Assert.Contains("AI", repo.Settings.LearnTopics);
        Assert.Contains("items", msg);
        // export → import round trip is idempotent
        var exported = repo.ExportDataJson();
        var before = repo.Items.Count;
        repo.ImportJson(exported);
        Assert.Equal(before, repo.Items.Count);
    }

    [Fact] public void Bin_purge_drops_rows_older_than_30_days()
    {
        var repo = NewRepo();
        var now = 100L * 24 * 3600 * 1000;
        repo.Upsert(new Item { Id = 1, Title = "fresh", DeletedAt = now - 1000 });
        repo.Upsert(new Item { Id = 2, Title = "ancient", DeletedAt = now - Constants.BinKeepMs - 1 });
        Assert.Equal(1, repo.PurgeBin(now));
        Assert.NotNull(repo.Item(1)); Assert.Null(repo.Item(2));
    }

    [Fact] public void Grouping_by_year_month_day_orders_and_labels()
    {
        Time.Zone = TimeZoneInfo.FindSystemTimeZoneById("Asia/Kolkata");
        long D(int m, int d) => Time.ToMs(new DateTime(2026, m, d, 9, 0, 0));
        var items = new[] { new Item { Id = 1, DueAt = D(9, 16) }, new Item { Id = 2, DueAt = D(9, 16) }, new Item { Id = 3, DueAt = D(10, 2) }, new Item { Id = 4, DueAt = D(8, 1) } };
        var g = Grouping.BuildYearGroups(items, false, i => i.Id, i => Grouping.Basis(i, false));
        Assert.Single(g);
        Assert.Equal(new[] { "August", "September", "October" }, g[0].Months.Select(m => m.Label));
        Assert.Equal(2, g[0].Months[1].Days[0].Items.Count);
        Assert.Equal("Wed, 16 Sep", g[0].Months[1].Days[0].Label);
        Assert.Equal(4, g[0].Count);
    }
}
