using System.Net.Http.Json;
using System.Text;
using System.Text.Json.Nodes;
using Remindly.Core.Json;
using Remindly.Core.Models;
using Remindly.Core.Store;
using Remindly.Core.Sync;

namespace Remindly.Core.Tests;

/// <summary>
/// End-to-end sync contract against a local Firestore emulator (set FIRESTORE_EMULATOR_HOST, e.g.
/// 127.0.0.1:8080). "The phone" is simulated with the emulator's REST API writing EXACTLY the
/// envelope Android writes: { json: "<gson>", updatedAt, schemaVer: 71 }.
/// Skipped automatically when no emulator is running.
/// </summary>
public class EmulatorSyncTests : IDisposable
{
    private static readonly string? Host = Environment.GetEnvironmentVariable("FIRESTORE_EMULATOR_HOST");
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "remindly-emu-" + Guid.NewGuid().ToString("N"));
    private readonly HttpClient _http = new();
    private readonly FirebaseConfig _cfg = new() { ProjectId = "remindly-test", ApiKey = "x" };
    private readonly string _uid = "uid-" + Guid.NewGuid().ToString("N")[..8];

    public void Dispose() { try { Directory.Delete(_dir, true); } catch { } }

    private string DocUrl(string col, string id) => $"http://{Host}/v1/projects/{_cfg.ProjectId}/databases/(default)/documents/users/{_uid}/{col}/{id}";

    /// <summary>What Android's SyncRepo.pushItems does, through REST.</summary>
    private async Task PhoneWrites<T>(string col, string id, T record, long updatedAt, int schemaVer = 71)
    {
        var body = new JsonObject
        {
            ["fields"] = new JsonObject
            {
                ["json"] = new JsonObject { ["stringValue"] = Wire.ToJson(record) },
                ["updatedAt"] = new JsonObject { ["integerValue"] = updatedAt.ToString() },
                ["schemaVer"] = new JsonObject { ["integerValue"] = schemaVer.ToString() },
            },
        };
        var resp = await _http.PatchAsync(DocUrl(col, id), new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"));
        resp.EnsureSuccessStatusCode();
    }

    private async Task PhoneWritesSettings(string json, long updatedAt, int schemaVer)
    {
        var body = new JsonObject { ["fields"] = new JsonObject {
            ["json"] = new JsonObject { ["stringValue"] = json },
            ["updatedAt"] = new JsonObject { ["integerValue"] = updatedAt.ToString() },
            ["schemaVer"] = new JsonObject { ["integerValue"] = schemaVer.ToString() } } };
        (await _http.PatchAsync(DocUrl("settings", "app"), new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"))).EnsureSuccessStatusCode();
    }

    private async Task<(string? json, long updatedAt, int schemaVer)?> PhoneReads(string col, string id)
    {
        var resp = await _http.GetAsync(DocUrl(col, id));
        if (!resp.IsSuccessStatusCode) return null;
        var o = JsonNode.Parse(await resp.Content.ReadAsStringAsync())!.AsObject()["fields"]!.AsObject();
        return (o["json"]?["stringValue"]?.GetValue<string>(), long.Parse(o["updatedAt"]!["integerValue"]!.GetValue<string>()), int.Parse(o["schemaVer"]!["integerValue"]!.GetValue<string>()));
    }

    private (Repository repo, FirestoreSync sync, FirebaseAuth auth) Boot()
    {
        var repo = new Repository(Path.Combine(_dir, "data")); repo.Load();
        var auth = new FirebaseAuth(_cfg, Path.Combine(_dir, "secure"));
        auth.UseTestSession(_uid, "test@example.com");
        var sync = new FirestoreSync(repo, auth, _cfg, Path.Combine(_dir, "state")) { EmulatorEndpoint = Host, EmulatorToken = "owner" };
        sync.Log = (m, e) => Console.WriteLine($"[sync] {m} {e?.Message}");
        return (repo, sync, auth);
    }

    private static async Task WaitUntil(Func<bool> cond, int timeoutMs = 15000)
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        while (!cond()) { if (sw.ElapsedMilliseconds > timeoutMs) throw new TimeoutException("condition not met"); await Task.Delay(100); }
    }

    [SkippableFact] public async Task Phone_record_arrives_live_and_Windows_writes_land_in_the_phone_format()
    {
        Skip.If(Host == null, "no emulator");
        // Phone has one item before Windows ever connects.
        await PhoneWrites("items", "101", new Item { Id = 101, Title = "From phone", Tab = Tab.SHOP, UpdatedAt = 1000, CreatedAt = 900 }, 1000);
        var (repo, sync, _) = Boot();
        sync.SetEnabled(true);
        await WaitUntil(() => repo.Item(101) != null);
        Assert.Equal("From phone", repo.Item(101)!.Title);
        await WaitUntil(() => sync.State == SyncState.Live);

        // Windows edits → the phone sees the Gson envelope with schemaVer 71 and the full field set.
        var edited = repo.Upsert(repo.Item(101)! with { Title = "Edited on PC" });
        await WaitUntil(() => sync.PendingCount == 0);
        var cloud = await PhoneReads("items", "101");
        Assert.NotNull(cloud);
        Assert.Equal(71, cloud!.Value.schemaVer);
        Assert.Equal(edited.UpdatedAt, cloud.Value.updatedAt);
        var asPhone = Wire.FromJson<Item>(cloud.Value.json!)!;
        Assert.Equal("Edited on PC", asPhone.Title);
        Assert.Equal(Tab.SHOP, asPhone.Tab);
        Assert.Contains("\"missedAt\":[]", cloud.Value.json);

        // Phone writes a NEWER version → applied; an OLDER one → ignored.
        await PhoneWrites("items", "101", asPhone with { Title = "Phone wins", UpdatedAt = edited.UpdatedAt + 5 }, edited.UpdatedAt + 5);
        await WaitUntil(() => repo.Item(101)!.Title == "Phone wins");
        await PhoneWrites("items", "101", asPhone with { Title = "Stale", UpdatedAt = 1 }, 1);
        await Task.Delay(1500);
        Assert.Equal("Phone wins", repo.Item(101)!.Title);

        // Tombstone from the phone → Bin on Windows.
        var now = repo.Item(101)!.UpdatedAt + 10;
        await PhoneWrites("items", "101", repo.Item(101)! with { DeletedAt = now, UpdatedAt = now }, now);
        await WaitUntil(() => repo.Item(101)!.DeletedAt != null);
        sync.Dispose();
    }

    [SkippableFact] public async Task Reconcile_pushes_only_local_records_the_cloud_lacks_or_that_are_newer()
    {
        Skip.If(Host == null, "no emulator");
        await PhoneWrites("items", "1", new Item { Id = 1, Title = "cloud-newer", UpdatedAt = 5000 }, 5000);
        var (repo, sync, _) = Boot();
        // Local has a STALE copy of 1 and a brand-new 2 (created offline before first sign-in).
        Clock.Provider = () => 100;
        repo.Upsert(new Item { Id = 1, Title = "local-stale" });
        repo.Upsert(new Item { Id = 2, Title = "local-only" });
        Clock.Provider = () => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        sync.SetEnabled(true);
        await WaitUntil(() => sync.State == SyncState.Live && sync.PendingCount == 0);
        await Task.Delay(500);
        Assert.Equal("cloud-newer", repo.Item(1)!.Title);                       // cloud won locally
        Assert.Equal("cloud-newer", Wire.FromJson<Item>((await PhoneReads("items", "1"))!.Value.json!)!.Title);   // and was NOT clobbered
        Assert.Equal("local-only", Wire.FromJson<Item>((await PhoneReads("items", "2"))!.Value.json!)!.Title);    // new local record pushed
        sync.Dispose();
    }

    [SkippableFact] public async Task Settings_document_merges_and_rejects_older_schema_writers()
    {
        Skip.If(Host == null, "no emulator");
        var (repo, sync, _) = Boot();
        sync.SetEnabled(true);
        await WaitUntil(() => sync.State == SyncState.Live);
        var newer = repo.Settings.SettingsUpdatedAt + 1000;
        await PhoneWritesSettings($$"""{"ver":41,"theme":"DARK","tasksGroups":["Phone"],"settingsUpdatedAt":{{newer}},"swipeHaptic":false}""", newer, 71);
        await WaitUntil(() => repo.Settings.Theme == "DARK");
        Assert.Contains("Phone", repo.Settings.TasksGroups);
        Assert.False(repo.Settings.GetBool("swipeHaptic", true));            // unknown-to-Windows field carried through
        // An older-schema settings doc is never adopted (Sync.kt rule).
        await PhoneWritesSettings($$"""{"ver":30,"theme":"LIGHT","settingsUpdatedAt":{{newer + 1}}}""", newer + 1, 60);
        await Task.Delay(1500);
        Assert.Equal("DARK", repo.Settings.Theme);
        // Windows settings edit lands in the cloud with schemaVer 71 and cloudSync stripped.
        repo.UpdateSettings(s => { s.Theme = "SYSTEM"; s.CloudSync = true; });
        await WaitUntil(() => sync.PendingCount == 0);
        var cloud = await PhoneReads("settings", "app");
        var j = JsonNode.Parse(cloud!.Value.json!)!.AsObject();
        Assert.Equal("SYSTEM", j["theme"]!.GetValue<string>());
        Assert.False(j["cloudSync"]!.GetValue<bool>());
        Assert.False(j["swipeHaptic"]!.GetValue<bool>());
        Assert.Equal(71, cloud.Value.schemaVer);
        sync.Dispose();
    }

    [SkippableFact] public async Task N29_catalogue_collections_sync_both_ways()
    {
        Skip.If(Host == null, "no emulator");
        await PhoneWrites("products", "7", new Product { Id = 7, Name = "Rice", DefaultUnit = "kg", UpdatedAt = 10 }, 10);
        await PhoneWrites("productlinks", "7_3", new ProductLink { ProductId = 7, ShopId = 3, LastPrice = 440, LastUnitPrice = 88, LastAt = 10 }, 10);
        var (repo, sync, _) = Boot();
        sync.SetEnabled(true);
        await WaitUntil(() => repo.Product(7) != null && repo.LinksFor(7).Any());
        repo.Upsert(new City { Id = 5, Name = "Kalyan" });
        repo.Upsert(new Chain { Id = 6, Name = "D-Mart" });
        await WaitUntil(() => sync.State == SyncState.Live && sync.PendingCount == 0);
        Assert.Equal("Kalyan", Wire.FromJson<City>((await PhoneReads("cities", "5"))!.Value.json!)!.Name);
        Assert.Equal("D-Mart", Wire.FromJson<Chain>((await PhoneReads("chains", "6"))!.Value.json!)!.Name);
        sync.Dispose();
    }
}
