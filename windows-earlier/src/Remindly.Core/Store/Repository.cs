using System.Text.Json.Nodes;
using Remindly.Core.Json;
using Remindly.Core.Logic;
using Remindly.Core.Models;

namespace Remindly.Core.Store;

public static class Collections
{
    public const string Items = "items", Calls = "calls", Places = "places", Shops = "shops";
    public const string Cities = "cities", Chains = "chains", Products = "products", ProductLinks = "productlinks";
    public const string Settings = "settings";
    public static readonly string[] Core = { Items, Calls, Places, Shops };
    public static readonly string[] Catalogue = { Cities, Chains, Products, ProductLinks };   // N29
    public static readonly string[] All = Core.Concat(Catalogue).ToArray();
}

/// <summary>
/// The local mirror of every Android store, in one object. JSON files use the SAME names and
/// shapes as the phone's filesDir (items.json …), so a phone backup drops straight in.
///
/// Mutations stamp updatedAt = now (ItemStore.upsert semantics) and raise <see cref="LocalWrite"/>
/// for the sync layer; <see cref="ApplyRemote"/> folds cloud records with LWW and raises only
/// <see cref="Changed"/>. All calls are expected on one thread (the UI dispatcher) — the sync
/// layer marshals through <see cref="Invoke"/>.
/// </summary>
public sealed class Repository
{
    public string DataDir { get; }
    public LocalPrefs Prefs { get; private set; } = new();
    public AppSettings Settings { get; private set; } = new();

    private List<Item> _items = new();
    private List<CallReminder> _calls = new();
    private List<GeoPlace> _places = new();
    private List<Shop> _shops = new();
    private List<City> _cities = new();
    private List<Chain> _chains = new();
    private List<Product> _products = new();
    private List<ProductLink> _links = new();

    public IReadOnlyList<Item> Items => _items;
    public IReadOnlyList<CallReminder> Calls => _calls;
    public IReadOnlyList<GeoPlace> Places => _places;
    public IReadOnlyList<Shop> Shops => _shops;
    public IReadOnlyList<City> Cities => _cities;
    public IReadOnlyList<Chain> Chains => _chains;
    public IReadOnlyList<Product> Products => _products;
    public IReadOnlyList<ProductLink> Links => _links;

    /// <summary>Raised after any collection changes (argument = collection name, "settings", or "prefs").</summary>
    public event Action<string>? Changed;
    /// <summary>Raised for a LOCAL write that the cloud must receive: (collection, docId, wire json, updatedAt).</summary>
    public event Action<string, string, string, long>? LocalWrite;
    /// <summary>Raised when settings change locally (payload already stripped with ForSync()).</summary>
    public event Action<AppSettings>? LocalSettingsWrite;
    /// <summary>Marshals a callback onto the owning thread. Set by the app; defaults to direct call.</summary>
    public Action<Action> Invoke { get; set; } = a => a();
    public Action<string, Exception?>? Log { get; set; }

    public Repository(string dataDir) { DataDir = dataDir; }

    private string PathOf(string name) => Path.Combine(DataDir, name + ".json");

    // ── load / save ────────────────────────────────────────────────────────────────────────

    public void Load()
    {
        Directory.CreateDirectory(DataDir);
        Prefs = LocalPrefs.Load(PathOf("local"));
        if (Prefs.DeviceTag is < 1 or > 0xFFF) { Prefs.DeviceTag = Ids.RandomTag(); Prefs.Save(PathOf("local")); }
        Ids.InitTag(Prefs.DeviceTag);

        _items = LoadList<Item>(Collections.Items, Heal.Item);
        _calls = LoadList<CallReminder>(Collections.Calls, Heal.Call);
        _places = LoadList<GeoPlace>(Collections.Places, Heal.Place);
        _shops = LoadList<Shop>(Collections.Shops, Heal.Shop);
        _cities = LoadList<City>(Collections.Cities, Heal.City);
        _chains = LoadList<Chain>(Collections.Chains, Heal.Chain);
        _products = LoadList<Product>(Collections.Products, Heal.Product);
        _links = LoadList<ProductLink>(Collections.ProductLinks, Heal.Link);
        try
        {
            var p = PathOf(Collections.Settings);
            Settings = File.Exists(p) ? Heal.Settings(AppSettings.Parse(File.ReadAllText(p))) : Heal.Settings(new AppSettings());
        }
        catch (Exception ex) { Log?.Invoke("settings.json failed to parse — defaults used", ex); Settings = Heal.Settings(new AppSettings()); }
    }

    private List<T> LoadList<T>(string name, Func<T, T> heal)
    {
        try
        {
            var p = PathOf(name);
            return File.Exists(p) ? Wire.ParseArrayLenient(File.ReadAllText(p), heal) : new List<T>();
        }
        catch (Exception ex) { Log?.Invoke($"{name}.json failed to parse", ex); return new List<T>(); }
    }

    private void SaveList<T>(string name, IReadOnlyList<T> list)
    {
        try
        {
            var p = PathOf(name); var tmp = p + ".tmp";
            File.WriteAllText(tmp, Wire.ToJson(list));
            File.Move(tmp, p, overwrite: true);
        }
        catch (Exception ex) { Log?.Invoke($"{name}.json save failed", ex); }
    }

    public void SavePrefs() { try { Prefs.Save(PathOf("local")); Changed?.Invoke("prefs"); } catch (Exception ex) { Log?.Invoke("local.json save failed", ex); } }

    private void SaveSettings()
    {
        try
        {
            var p = PathOf(Collections.Settings); var tmp = p + ".tmp";
            File.WriteAllText(tmp, Settings.ToJson());
            File.Move(tmp, p, overwrite: true);
        }
        catch (Exception ex) { Log?.Invoke("settings.json save failed", ex); }
    }

    // ── generic helpers ────────────────────────────────────────────────────────────────────

    private void Emit<T>(string col, T rec, long id, long updatedAt) => LocalWrite?.Invoke(col, id.ToString(), Wire.ToJson(rec), updatedAt);

    // ── items ──────────────────────────────────────────────────────────────────────────────

    public Item? Item(long id) => _items.FirstOrDefault(i => i.Id == id);

    public Item Upsert(Item item)
    {
        var stamped = item with { UpdatedAt = Clock.Now() };
        _items = _items.Where(i => i.Id != stamped.Id).Append(stamped).ToList();
        SaveList(Collections.Items, _items);
        Changed?.Invoke(Collections.Items);
        Emit(Collections.Items, stamped, stamped.Id, stamped.UpdatedAt);
        return stamped;
    }

    /// <summary>Hard delete (Bin → "Delete forever"). The cloud keeps its tombstone; we drop the local row.</summary>
    public void DeleteForever(Item item)
    {
        var tomb = item with { DeletedAt = item.DeletedAt ?? Clock.Now(), UpdatedAt = Clock.Now() };
        Emit(Collections.Items, tomb, tomb.Id, tomb.UpdatedAt);
        _items = _items.Where(i => i.Id != item.Id).ToList();
        SaveList(Collections.Items, _items);
        Changed?.Invoke(Collections.Items);
    }

    // ── calls ──────────────────────────────────────────────────────────────────────────────

    public CallReminder? Call(long id) => _calls.FirstOrDefault(c => c.Id == id);

    public CallReminder Upsert(CallReminder r)
    {
        var stamped = r with { UpdatedAt = Clock.Now() };
        _calls = _calls.Where(c => c.Id != stamped.Id).Append(stamped).ToList();
        SaveList(Collections.Calls, _calls);
        Changed?.Invoke(Collections.Calls);
        Emit(Collections.Calls, stamped, stamped.Id, stamped.UpdatedAt);
        return stamped;
    }

    public void DeleteForever(CallReminder r)
    {
        var tomb = r with { DeletedAt = r.DeletedAt ?? Clock.Now(), UpdatedAt = Clock.Now() };
        Emit(Collections.Calls, tomb, tomb.Id, tomb.UpdatedAt);
        _calls = _calls.Where(c => c.Id != r.Id).ToList();
        SaveList(Collections.Calls, _calls);
        Changed?.Invoke(Collections.Calls);
    }

    // ── places ─────────────────────────────────────────────────────────────────────────────

    public GeoPlace Upsert(GeoPlace p)
    {
        var stamped = p with { UpdatedAt = Clock.Now() };
        _places = _places.Where(x => x.Id != stamped.Id).Append(stamped).ToList();
        SaveList(Collections.Places, _places);
        Changed?.Invoke(Collections.Places);
        Emit(Collections.Places, stamped, stamped.Id, stamped.UpdatedAt);
        return stamped;
    }

    // ── shops ──────────────────────────────────────────────────────────────────────────────

    public IEnumerable<Shop> ActiveShops => _shops.Where(s => s.DeletedAt == null).OrderBy(s => s.Name.ToLowerInvariant());
    public Shop? Shop(long id) => _shops.FirstOrDefault(s => s.Id == id && s.DeletedAt == null);
    public Shop? ShopByName(string name) { var k = name.Trim(); return ActiveShops.FirstOrDefault(s => string.Equals(s.Name.Trim(), k, StringComparison.OrdinalIgnoreCase)); }
    public Shop? DefaultShop => ActiveShops.FirstOrDefault(s => s.IsDefault);

    /// <summary>ShopStore.upsert — single-default invariant: promoting one demotes the others (each demotion syncs).</summary>
    public Shop Upsert(Shop shop)
    {
        var now = Clock.Now();
        var list = _shops.Where(s => s.Id != shop.Id).ToList();
        var demoted = new List<Shop>();
        if (shop.IsDefault)
            list = list.Select(s => { if (s.IsDefault && s.DeletedAt == null) { var d = s with { IsDefault = false, UpdatedAt = now }; demoted.Add(d); return d; } return s; }).ToList();
        var stamped = shop with { UpdatedAt = now };
        _shops = list.Append(stamped).ToList();
        SaveList(Collections.Shops, _shops);
        Changed?.Invoke(Collections.Shops);
        foreach (var d in demoted) Emit(Collections.Shops, d, d.Id, d.UpdatedAt);
        Emit(Collections.Shops, stamped, stamped.Id, stamped.UpdatedAt);
        return stamped;
    }

    public void DeleteShop(long id)
    {
        var s = _shops.FirstOrDefault(x => x.Id == id);
        if (s == null) return;
        Upsert(s with { DeletedAt = Clock.Now(), IsDefault = false });
    }

    // ── cities / chains ────────────────────────────────────────────────────────────────────

    public IEnumerable<City> ActiveCities => _cities.Where(c => c.DeletedAt == null).OrderBy(c => c.Name.ToLowerInvariant());
    public IEnumerable<Chain> ActiveChains => _chains.Where(c => c.DeletedAt == null).OrderBy(c => c.Name.ToLowerInvariant());
    public City? CityOf(long? id) => id is long i ? _cities.FirstOrDefault(c => c.Id == i && c.DeletedAt == null) : null;
    public Chain? ChainOf(long? id) => id is long i ? _chains.FirstOrDefault(c => c.Id == i && c.DeletedAt == null) : null;

    public City Upsert(City c)
    {
        var stamped = c with { UpdatedAt = Clock.Now() };
        _cities = _cities.Where(x => x.Id != stamped.Id).Append(stamped).ToList();
        SaveList(Collections.Cities, _cities);
        Changed?.Invoke(Collections.Cities);
        Emit(Collections.Cities, stamped, stamped.Id, stamped.UpdatedAt);
        return stamped;
    }

    /// <summary>shopsAfterCityDelete: the city is tombstoned; its shops move to Unassigned.</summary>
    public void DeleteCity(long id)
    {
        var c = _cities.FirstOrDefault(x => x.Id == id);
        if (c == null) return;
        Upsert(c with { DeletedAt = Clock.Now() });
        foreach (var s in _shops.Where(s => s.CityId == id && s.DeletedAt == null).ToList()) Upsert(s with { CityId = null });
    }

    public Chain Upsert(Chain c)
    {
        var stamped = c with { UpdatedAt = Clock.Now() };
        _chains = _chains.Where(x => x.Id != stamped.Id).Append(stamped).ToList();
        SaveList(Collections.Chains, _chains);
        Changed?.Invoke(Collections.Chains);
        Emit(Collections.Chains, stamped, stamped.Id, stamped.UpdatedAt);
        return stamped;
    }

    /// <summary>shopsAfterChainDelete: keep branches as local shops, or tombstone them too.</summary>
    public void DeleteChain(long id, bool keepBranches)
    {
        var c = _chains.FirstOrDefault(x => x.Id == id);
        if (c == null) return;
        var now = Clock.Now();
        Upsert(c with { DeletedAt = now });
        foreach (var s in _shops.Where(s => s.ChainId == id && s.DeletedAt == null).ToList())
            Upsert(keepBranches ? s with { ChainId = null } : s with { ChainId = null, DeletedAt = now, IsDefault = false });
    }

    // ── products / links ───────────────────────────────────────────────────────────────────

    public IEnumerable<Product> ActiveProducts => _products.Where(p => p.DeletedAt == null).OrderBy(p => p.Name.ToLowerInvariant());
    public Product? Product(long? id) => id is long i ? _products.FirstOrDefault(p => p.Id == i && p.DeletedAt == null) : null;
    public IEnumerable<string> Categories => ActiveProducts.Select(p => p.Category).Where(c => !string.IsNullOrWhiteSpace(c)).Select(c => c!).Distinct(StringComparer.OrdinalIgnoreCase).OrderBy(c => c.ToLowerInvariant());
    public IEnumerable<ProductLink> LinksFor(long productId) => _links.Where(l => l.ProductId == productId && l.DeletedAt == null);

    public Product Upsert(Product p)
    {
        var stamped = p with { UpdatedAt = Clock.Now() };
        _products = _products.Where(x => x.Id != stamped.Id).Append(stamped).ToList();
        SaveList(Collections.Products, _products);
        Changed?.Invoke(Collections.Products);
        Emit(Collections.Products, stamped, stamped.Id, stamped.UpdatedAt);
        return stamped;
    }

    public void DeleteProduct(long id)
    {
        var p = _products.FirstOrDefault(x => x.Id == id);
        if (p == null) return;
        var now = Clock.Now();
        Upsert(p with { DeletedAt = now });
        var touched = new List<ProductLink>();
        _links = _links.Select(l => { if (l.ProductId == id && l.DeletedAt == null) { var d = l with { DeletedAt = now, UpdatedAt = now }; touched.Add(d); return d; } return l; }).ToList();
        if (touched.Count > 0)
        {
            SaveList(Collections.ProductLinks, _links);
            Changed?.Invoke(Collections.ProductLinks);
            foreach (var l in touched) LocalWrite?.Invoke(Collections.ProductLinks, l.Key, Wire.ToJson(l), Merge.LinkStamp(l));
        }
    }

    /// <summary>ProductStore.setLink — turn a product↔shop link on/off, optionally with prices.</summary>
    public void SetLink(long productId, long shopId, bool on, double? price = null, double? unitPrice = null)
    {
        var now = Clock.Now();
        var prev = _links.FirstOrDefault(l => l.ProductId == productId && l.ShopId == shopId);
        ProductLink next;
        if (on)
            next = new ProductLink
            {
                ProductId = productId, ShopId = shopId,
                LastPrice = price ?? prev?.LastPrice ?? 0.0,
                LastUnitPrice = unitPrice ?? prev?.LastUnitPrice ?? 0.0,
                LastAt = price != null || unitPrice != null ? now : (prev?.LastAt ?? 0L),
                DeletedAt = null, UpdatedAt = now, Extra = prev?.Extra,
            };
        else
        {
            if (prev == null) return;
            next = prev with { DeletedAt = now, UpdatedAt = now };
        }
        _links = _links.Where(l => !(l.ProductId == productId && l.ShopId == shopId)).Append(next).ToList();
        SaveList(Collections.ProductLinks, _links);
        Changed?.Invoke(Collections.ProductLinks);
        LocalWrite?.Invoke(Collections.ProductLinks, next.Key, Wire.ToJson(next), Merge.LinkStamp(next));
    }

    /// <summary>ProductStore.recordPrice — checkout write-back.</summary>
    public void RecordPrice(long productId, long shopId, double price, double unitPrice, long at)
    {
        if (price <= 0.0 && unitPrice <= 0.0) return;
        var prev = _links.FirstOrDefault(l => l.ProductId == productId && l.ShopId == shopId);
        var next = ShopMath.MergedLinkPrice(prev, productId, shopId, price, unitPrice, at);
        _links = _links.Where(l => !(l.ProductId == productId && l.ShopId == shopId)).Append(next).ToList();
        SaveList(Collections.ProductLinks, _links);
        Changed?.Invoke(Collections.ProductLinks);
        LocalWrite?.Invoke(Collections.ProductLinks, next.Key, Wire.ToJson(next), Merge.LinkStamp(next));
    }

    // ── settings ───────────────────────────────────────────────────────────────────────────

    /// <summary>SettingsStore.update: edit, bump settingsUpdatedAt, persist, push.</summary>
    public void UpdateSettings(Action<AppSettings> edit)
    {
        var next = Settings.With(edit);
        next.SettingsUpdatedAt = Clock.Now();
        Settings = next;
        SaveSettings();
        Changed?.Invoke(Collections.Settings);
        LocalSettingsWrite?.Invoke(next.ForSync());
    }

    public void ApplyRemoteSettings(AppSettings remote)
    {
        var merged = Merge.MergeSettings(Settings, Heal.Settings(remote));
        if (ReferenceEquals(merged, Settings)) return;
        Settings = merged;
        SaveSettings();
        Changed?.Invoke(Collections.Settings);
    }

    // ── remote application (LWW) ──────────────────────────────────────────────────────────

    /// <summary>Fold remote records into a collection. Returns how many records were applied.</summary>
    public int ApplyRemote(string collection, IEnumerable<(string json, int schemaVer)> docs, out List<string> warnings)
        => ApplyRemote(collection, docs, out warnings, out _);

    /// <summary>
    /// Fold remote records into a collection. <paramref name="localNewer"/> lists records where THIS
    /// device holds a newer copy than the cloud delivered (id, wire json, stamp) so the caller can re-push.
    /// </summary>
    public int ApplyRemote(string collection, IEnumerable<(string json, int schemaVer)> docs, out List<string> warnings, out List<(string id, string json, long stamp)> localNewer)
    {
        warnings = new List<string>();
        localNewer = new List<(string, string, long)>();
        var w = warnings; var ln = localNewer;
        switch (collection)
        {
            case Collections.Items:
            {
                var byId = _items.ToDictionary(i => i.Id);
                var inc = Decode(docs, w, (Item probe) => byId.TryGetValue(probe.Id, out var l) ? l : null, Heal.Item);
                var (merged, changed, applied) = Merge.Fold(_items, inc, i => i.Id, Merge.StampOf);
                foreach (var r in inc) { var ex = byId.GetValueOrDefault(r.Id); if (ex != null && Merge.StampOf(ex) > Merge.StampOf(r)) ln.Add((r.Id.ToString(), Wire.ToJson(ex), Merge.StampOf(ex))); }
                if (changed) { _items = merged; SaveList(Collections.Items, _items); Changed?.Invoke(Collections.Items); }
                return applied.Count;
            }
            case Collections.Calls:
            {
                var byId = _calls.ToDictionary(i => i.Id);
                var inc = Decode(docs, w, (CallReminder probe) => byId.TryGetValue(probe.Id, out var l) ? l : null, Heal.Call);
                var (merged, changed, applied) = Merge.Fold(_calls, inc, i => i.Id, Merge.StampOf);
                foreach (var r in inc) { var ex = byId.GetValueOrDefault(r.Id); if (ex != null && Merge.StampOf(ex) > Merge.StampOf(r)) ln.Add((r.Id.ToString(), Wire.ToJson(ex), Merge.StampOf(ex))); }
                if (changed) { _calls = merged; SaveList(Collections.Calls, _calls); Changed?.Invoke(Collections.Calls); }
                return applied.Count;
            }
            case Collections.Places:
            {
                var byId = _places.ToDictionary(i => i.Id);
                var inc = Decode(docs, w, (GeoPlace probe) => byId.TryGetValue(probe.Id, out var l) ? l : null, Heal.Place);
                var (merged, changed, applied) = Merge.Fold(_places, inc, i => i.Id, Merge.StampOf);
                foreach (var r in inc) { var ex = byId.GetValueOrDefault(r.Id); if (ex != null && Merge.StampOf(ex) > Merge.StampOf(r)) ln.Add((r.Id.ToString(), Wire.ToJson(ex), Merge.StampOf(ex))); }
                if (changed) { _places = merged; SaveList(Collections.Places, _places); Changed?.Invoke(Collections.Places); }
                return applied.Count;
            }
            case Collections.Shops:
            {
                var byId = _shops.ToDictionary(i => i.Id);
                var inc = Decode(docs, w, (Shop probe) => byId.TryGetValue(probe.Id, out var l) ? l : null, Heal.Shop);
                var (merged, changed, applied) = Merge.Fold(_shops, inc, i => i.Id, Merge.StampOf);
                foreach (var r in inc) { var ex = byId.GetValueOrDefault(r.Id); if (ex != null && Merge.StampOf(ex) > Merge.StampOf(r)) ln.Add((r.Id.ToString(), Wire.ToJson(ex), Merge.StampOf(ex))); }
                if (changed) { _shops = merged; SaveList(Collections.Shops, _shops); Changed?.Invoke(Collections.Shops); }
                return applied.Count;
            }
            case Collections.Cities:
            {
                var byId = _cities.ToDictionary(i => i.Id);
                var inc = Decode(docs, w, (City probe) => byId.TryGetValue(probe.Id, out var l) ? l : null, Heal.City);
                var (merged, changed, applied) = Merge.Fold(_cities, inc, i => i.Id, Merge.StampOf);
                foreach (var r in inc) { var ex = byId.GetValueOrDefault(r.Id); if (ex != null && Merge.StampOf(ex) > Merge.StampOf(r)) ln.Add((r.Id.ToString(), Wire.ToJson(ex), Merge.StampOf(ex))); }
                if (changed) { _cities = merged; SaveList(Collections.Cities, _cities); Changed?.Invoke(Collections.Cities); }
                return applied.Count;
            }
            case Collections.Chains:
            {
                var byId = _chains.ToDictionary(i => i.Id);
                var inc = Decode(docs, w, (Chain probe) => byId.TryGetValue(probe.Id, out var l) ? l : null, Heal.Chain);
                var (merged, changed, applied) = Merge.Fold(_chains, inc, i => i.Id, Merge.StampOf);
                foreach (var r in inc) { var ex = byId.GetValueOrDefault(r.Id); if (ex != null && Merge.StampOf(ex) > Merge.StampOf(r)) ln.Add((r.Id.ToString(), Wire.ToJson(ex), Merge.StampOf(ex))); }
                if (changed) { _chains = merged; SaveList(Collections.Chains, _chains); Changed?.Invoke(Collections.Chains); }
                return applied.Count;
            }
            case Collections.Products:
            {
                var byId = _products.ToDictionary(i => i.Id);
                var inc = Decode(docs, w, (Product probe) => byId.TryGetValue(probe.Id, out var l) ? l : null, Heal.Product);
                var (merged, changed, applied) = Merge.Fold(_products, inc, i => i.Id, Merge.StampOf);
                foreach (var r in inc) { var ex = byId.GetValueOrDefault(r.Id); if (ex != null && Merge.StampOf(ex) > Merge.StampOf(r)) ln.Add((r.Id.ToString(), Wire.ToJson(ex), Merge.StampOf(ex))); }
                if (changed) { _products = merged; SaveList(Collections.Products, _products); Changed?.Invoke(Collections.Products); }
                return applied.Count;
            }
            case Collections.ProductLinks:
            {
                var byKey = _links.ToDictionary(l => l.Key);
                var inc = Decode(docs, w, (ProductLink probe) => byKey.TryGetValue(probe.Key, out var l) ? l : null, Heal.Link);
                var cur = _links.ToDictionary(l => l.Key);
                var applied = 0; var changed = false;
                foreach (var l in inc)
                {
                    if (!cur.TryGetValue(l.Key, out var ex) || Merge.LinkStamp(l) >= Merge.LinkStamp(ex))
                    {
                        if (ex == null || Wire.ToJson(ex) != Wire.ToJson(l)) { cur[l.Key] = l; changed = true; }
                        applied++;
                    }
                    else ln.Add((ex.Key, Wire.ToJson(ex), Merge.LinkStamp(ex)));
                }
                if (changed) { _links = cur.Values.ToList(); SaveList(Collections.ProductLinks, _links); Changed?.Invoke(Collections.ProductLinks); }
                return applied;
            }
            default:
                return 0;
        }
    }

    private List<T> Decode<T>(IEnumerable<(string json, int schemaVer)> docs, List<string> warnings, Func<T, T?> findLocal, Func<T, T> heal) where T : class
    {
        var out_ = new List<T>();
        foreach (var (json, ver) in docs)
        {
            try
            {
                var probe = Wire.FromJson<T>(json);
                if (probe == null) continue;
                var local = findLocal(probe);
                var effective = json;
                if (local != null && ver < Constants.SyncSchema)
                {
                    effective = Merge.OverlayIfOlder(json, ver, Wire.ToJson(local), out var kept);
                    if (kept.Count > 0) warnings.Add($"{typeof(T).Name} from older sync schema v{ver} — kept local {kept.Count} field(s): {string.Join(",", kept)}");
                }
                var rec = Wire.FromJson<T>(effective);
                if (rec != null) out_.Add(heal(rec));
            }
            catch (Exception ex) { warnings.Add($"merge {typeof(T).Name} failed — record skipped: {ex.Message}"); }
        }
        return out_;
    }

    /// <summary>Every record as (collection, id, json, stamp) — the initial "push everything" after sign-in.</summary>
    public IEnumerable<(string col, string id, string json, long stamp)> AllForPush(bool includeCatalogue)
    {
        foreach (var i in _items) yield return (Collections.Items, i.Id.ToString(), Wire.ToJson(i), i.UpdatedAt);
        foreach (var c in _calls) yield return (Collections.Calls, c.Id.ToString(), Wire.ToJson(c), c.UpdatedAt);
        foreach (var p in _places) yield return (Collections.Places, p.Id.ToString(), Wire.ToJson(p), p.UpdatedAt);
        foreach (var s in _shops) yield return (Collections.Shops, s.Id.ToString(), Wire.ToJson(s), s.UpdatedAt);
        if (!includeCatalogue) yield break;
        foreach (var c in _cities) yield return (Collections.Cities, c.Id.ToString(), Wire.ToJson(c), c.UpdatedAt);
        foreach (var c in _chains) yield return (Collections.Chains, c.Id.ToString(), Wire.ToJson(c), c.UpdatedAt);
        foreach (var p in _products) yield return (Collections.Products, p.Id.ToString(), Wire.ToJson(p), p.UpdatedAt);
        foreach (var l in _links) yield return (Collections.ProductLinks, l.Key, Wire.ToJson(l), Merge.LinkStamp(l));
    }

    // ── backup / restore (phone-compatible) ───────────────────────────────────────────────

    public string ExportDataJson()
    {
        var blob = new DataBlob
        {
            Items = _items.ToList(), Calls = _calls.ToList(), Places = _places.ToList(),
            TasksGroups = Settings.TasksGroups, ShopGroups = Settings.ShopGroups, LearnTopics = Settings.LearnTopics,
            Shops = _shops.ToList(), Cities = _cities.ToList(), Chains = _chains.ToList(), Products = _products.ToList(), ProductLinks = _links.ToList(),
        };
        return JsonSerializer.Serialize(blob, Wire.Pretty);
    }

    public string ExportSettingsJson() => new JsonObject { ["kind"] = "remindly-settings", ["settings"] = Settings.Raw.DeepClone() }.ToJsonString(new JsonSerializerOptions { WriteIndented = true });

    /// <summary>Backup.mergeData: nothing existing is deleted; duplicate ids resolve latest-wins. Returns a summary.</summary>
    public string ImportJson(string json)
    {
        var root = JsonNode.Parse(json) as JsonObject ?? throw new InvalidDataException("Not a JSON object");
        var kind = root["kind"]?.GetValue<string>() ?? root["app"]?.GetValue<string>();
        if (kind == "remindly-settings" && root["settings"] is JsonObject so)
        {
            var remote = Heal.Settings(new AppSettings((JsonObject)so.DeepClone()));
            remote.SettingsUpdatedAt = Clock.Now();
            var merged = Merge.MergeSettings(Settings, remote);
            Settings = merged; SaveSettings(); Changed?.Invoke(Collections.Settings); LocalSettingsWrite?.Invoke(merged.ForSync());
            return "Settings imported.";
        }
        var blob = root.Deserialize<DataBlob>(Wire.Options) ?? throw new InvalidDataException("Unrecognised backup");
        var counts = new List<string>();
        void Merge1<T>(string col, List<T> incoming, Func<T, T> heal, Func<T, long> id, Func<T, long> stamp, Func<List<T>> get, Action<List<T>> set)
        {
            if (incoming.Count == 0) return;
            var (merged, changed, applied) = Logic.Merge.Fold(get(), incoming.Select(heal).ToList(), id, stamp);
            if (changed) { set(merged); SaveList(col, merged); Changed?.Invoke(col); foreach (var a in applied) LocalWrite?.Invoke(col, id(a).ToString(), Wire.ToJson(a), stamp(a)); }
            counts.Add($"{applied.Count} {col}");
        }
        Merge1(Collections.Items, blob.Items, Heal.Item, i => i.Id, Merge.StampOf, () => _items, l => _items = l);
        Merge1(Collections.Calls, blob.Calls, Heal.Call, i => i.Id, Merge.StampOf, () => _calls, l => _calls = l);
        Merge1(Collections.Places, blob.Places, Heal.Place, i => i.Id, Merge.StampOf, () => _places, l => _places = l);
        if (blob.Shops != null) Merge1(Collections.Shops, blob.Shops, Heal.Shop, i => i.Id, Merge.StampOf, () => _shops, l => _shops = l);
        if (blob.Cities != null) Merge1(Collections.Cities, blob.Cities, Heal.City, i => i.Id, Merge.StampOf, () => _cities, l => _cities = l);
        if (blob.Chains != null) Merge1(Collections.Chains, blob.Chains, Heal.Chain, i => i.Id, Merge.StampOf, () => _chains, l => _chains = l);
        if (blob.Products != null) Merge1(Collections.Products, blob.Products, Heal.Product, i => i.Id, Merge.StampOf, () => _products, l => _products = l);
        if (blob.ProductLinks != null && blob.ProductLinks.Count > 0)
        {
            var cur = _links.ToDictionary(l => l.Key);
            foreach (var l0 in blob.ProductLinks) { var l = Heal.Link(l0); if (!cur.TryGetValue(l.Key, out var ex) || Merge.LinkStamp(l) >= Merge.LinkStamp(ex)) cur[l.Key] = l; }
            _links = cur.Values.ToList(); SaveList(Collections.ProductLinks, _links); Changed?.Invoke(Collections.ProductLinks);
            counts.Add($"{blob.ProductLinks.Count} productlinks");
        }
        if (blob.TasksGroups.Count + blob.ShopGroups.Count + blob.LearnTopics.Count > 0)
            UpdateSettings(s =>
            {
                s.TasksGroups = s.TasksGroups.Concat(blob.TasksGroups).Distinct().ToList();
                s.ShopGroups = s.ShopGroups.Concat(blob.ShopGroups).Distinct().ToList();
                s.LearnTopics = s.LearnTopics.Concat(blob.LearnTopics).Distinct().ToList();
            });
        return counts.Count == 0 ? "Nothing to import." : "Merged " + string.Join(", ", counts) + ".";
    }

    /// <summary>Bin housekeeping: rows soft-deleted more than 30 days ago are dropped locally.</summary>
    public int PurgeBin(long now)
    {
        var cutoff = now - Constants.BinKeepMs;
        var before = _items.Count + _calls.Count;
        var items = _items.Where(i => !(i.DeletedAt is long d && d < cutoff)).ToList();
        var calls = _calls.Where(c => !(c.DeletedAt is long d && d < cutoff)).ToList();
        if (items.Count != _items.Count) { _items = items; SaveList(Collections.Items, _items); Changed?.Invoke(Collections.Items); }
        if (calls.Count != _calls.Count) { _calls = calls; SaveList(Collections.Calls, _calls); Changed?.Invoke(Collections.Calls); }
        return before - (_items.Count + _calls.Count);
    }
}
