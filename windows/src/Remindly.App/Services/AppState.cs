using Remindly.Core;

namespace Remindly.App.Services;

/// <summary>A point-in-time copy used for Undo (records are immutable, so copying the lists is enough).</summary>
public sealed record Snapshot(List<Item> Items, List<CallReminder> Calls, AppSettings Settings);

/// <summary>
/// The single owner of Remindly's data on this PC. Every change goes through here, is saved at once
/// (atomic write) and raises <see cref="Changed"/> so the screens redraw.
/// </summary>
public sealed class AppState
{
    public static AppState Current { get; private set; } = null!;

    public RemindlyData Data { get; private set; }
    public event Action? Changed;

    private AppState(RemindlyData d) => Data = d;

    public static AppState Load()
    {
        Current = new AppState(DataStore.Load());
        Current.Save();
        return Current;
    }

    public AppSettings Settings => Data.Settings;
    public long Now => Clock.NowMs();

    public void Save()
    {
        try { DataStore.Save(Data); }
        catch (Exception ex) { Log.Error("Save failed", ex); }
    }

    private void Commit()
    {
        Save();
        Changed?.Invoke();
    }

    public Snapshot TakeSnapshot() => new(Data.Items.ToList(), (Data.Calls ?? new()).ToList(), Data.Settings);

    public void Restore(Snapshot s)
    {
        Data.Items = s.Items.ToList();
        Data.Calls = s.Calls.ToList();
        Data.Settings = s.Settings;
        Commit();
    }

    // ───────────────────────── items ─────────────────────────

    public IEnumerable<Item> LiveItems(Tab tab) => Data.Items.Where(i => i.Tab == tab && i.DeletedAt == null);

    public Item? Item(long id) => Data.Items.FirstOrDefault(i => i.Id == id);

    public void Upsert(Item item) => UpsertMany(new[] { item });

    public void UpsertMany(IEnumerable<Item> items)
    {
        var now = Now;
        var byId = new Dictionary<long, int>();
        for (int k = 0; k < Data.Items.Count; k++) byId[Data.Items[k].Id] = k;
        foreach (var raw in items)
        {
            var item = raw with { UpdatedAt = now };
            if (byId.TryGetValue(item.Id, out var idx)) Data.Items[idx] = item;
            else { Data.Items.Add(item); byId[item.Id] = Data.Items.Count - 1; }
        }
        Commit();
    }

    public Item NewItem(Tab tab, string title)
    {
        var now = Now;
        return new Item { Id = Ids.Next(), Tab = tab, Title = title.Trim(), CreatedAt = now, UpdatedAt = now, Priority = tab == Tab.SHOP ? Priority.MEDIUM : null };
    }

    public string Complete(Item item)
    {
        var r = ItemRules.Complete(item, Now);
        Upsert(r.Item);
        return r.Message;
    }

    public void Revive(Item item) => Upsert(ItemRules.Revive(item, Now));

    public void Snooze(Item item, int minutes) => Upsert(item with { SnoozedUntil = Reminders.SnoozeTarget(Now, minutes) });

    public void SoftDelete(IEnumerable<Item> items) => UpsertMany(items.Select(i => i with { DeletedAt = Now }));

    public void RestoreFromBin(Item item) => Upsert(item with { DeletedAt = null });

    public void DeleteForever(Item item)
    {
        Data.Items.RemoveAll(i => i.Id == item.Id);
        Commit();
    }

    // ───────────────────────── calls ─────────────────────────

    public IEnumerable<CallReminder> LiveCalls() => (Data.Calls ?? new()).Where(c => c.DeletedAt == null);

    public void UpsertCall(CallReminder c)
    {
        Data.Calls ??= new();
        var rec = c with { UpdatedAt = Now };
        int idx = Data.Calls.FindIndex(x => x.Id == c.Id);
        if (idx >= 0) Data.Calls[idx] = rec; else Data.Calls.Add(rec);
        Commit();
    }

    public string CompleteCall(CallReminder c)
    {
        var r = ItemRules.CompleteCall(c, Now);
        UpsertCall(r.Record);
        return r.Message;
    }

    public void SnoozeCall(CallReminder c, int minutes) => UpsertCall(c with { SnoozedUntil = Reminders.SnoozeTarget(Now, minutes) });

    public void DeleteCallForever(CallReminder c)
    {
        Data.Calls?.RemoveAll(x => x.Id == c.Id);
        Commit();
    }

    // ───────────────────────── lists (2.11) ─────────────────────────

    public List<ShopList> Lists => ShopLists.Live(Data.Settings.ShopLists);

    public ShopList? List(long id) => Lists.FirstOrDefault(l => l.Id == id);

    public List<Item> ItemsIn(long listId) => ShopLists.ItemsIn(Data.Items, listId, Data.Settings.ShopLists);

    private void SetLists(List<ShopList> lists, bool clearDefault = false)
    {
        var s = Data.Settings with { ShopLists = lists };
        if (clearDefault) s = s with { ShopDefaultListId = null };
        Data.Settings = ShopLists.MirrorIntoSettings(s);
    }

    public ShopList CreateList(ShopList draft)
    {
        var now = Now;
        var list = draft with
        {
            Id = Ids.Next(), Name = ShopLists.UniqueName(draft.Name, Data.Settings.ShopLists),
            Order = ShopLists.NextOrder(Data.Settings.ShopLists), CreatedAt = now, UpdatedAt = now,
        };
        SetLists(Data.Settings.ShopLists.Append(list).ToList());
        Commit();
        return list;
    }

    /// <summary>Saves an edited list: a rename re-labels its items, Private on/off flips their Personal flag.</summary>
    public void UpdateList(ShopList edited)
    {
        var now = Now;
        var old = Data.Settings.ShopLists.FirstOrDefault(l => l.Id == edited.Id);
        if (old == null) return;
        var name = string.Equals(old.Name, edited.Name.Trim(), StringComparison.OrdinalIgnoreCase)
            ? edited.Name.Trim()
            : ShopLists.UniqueName(edited.Name, Data.Settings.ShopLists.Where(l => l.Id != edited.Id));
        var saved = edited with { Name = name, UpdatedAt = now };
        var items = ItemsIn(saved.Id);
        var changed = new Dictionary<long, Item>();
        if (name != old.Name) foreach (var i in ShopLists.ItemsAfterRename(items, saved, name)) changed[i.Id] = i;
        if (saved.Personal != old.Personal)
            foreach (var i in ShopLists.ItemsAfterPrivacy(items.Select(x => changed.TryGetValue(x.Id, out var c) ? c : x), saved.Personal)) changed[i.Id] = i;
        SetLists(Data.Settings.ShopLists.Select(l => l.Id == saved.Id ? saved : l).ToList());
        if (changed.Count > 0) UpsertMany(changed.Values); else Commit();
    }

    public void TogglePin(ShopList l) => UpdateList(l with { Pinned = !l.Pinned });

    public void MoveList(ShopList l, bool up)
    {
        SetLists(ShopLists.MoveOrder(Data.Settings.ShopLists, l.Id, up, Now));
        Commit();
    }

    public void DeleteList(ShopList l, ListDeleteMode mode, ShopList? target)
    {
        var now = Now;
        var items = ItemsIn(l.Id);
        SetLists(Data.Settings.ShopLists.Select(x => x.Id == l.Id ? x with { DeletedAt = now, UpdatedAt = now } : x).ToList(),
            clearDefault: Data.Settings.ShopDefaultListId == l.Id);
        var after = ShopLists.ItemsAfterDelete(items, mode, target);
        if (mode == ListDeleteMode.DELETE_ITEMS) after = after.Select(i => i with { DeletedAt = now }).ToList();
        if (target is { Personal: true } && mode == ListDeleteMode.MOVE_TO) after = after.Select(i => i with { Personal = true }).ToList();
        UpsertMany(after);
    }

    public ShopList DuplicateList(ShopList l)
    {
        var copy = CreateList(l with { Name = ShopLists.UniqueName(l.Name + " copy", Data.Settings.ShopLists), Pinned = false });
        UpsertMany(ShopLists.DuplicateItems(ItemsIn(l.Id), copy, Ids.Next, Now));
        return copy;
    }

    public int RestartList(long listId)
    {
        var targets = ShopLists.RestartTargets(ItemsIn(listId));
        UpsertMany(targets.Select(i => ItemRules.Revive(i, Now)));
        return targets.Count;
    }

    public int MarkAllBought(long listId)
    {
        var targets = ShopLists.MarkAllTargets(ItemsIn(listId));
        var now = Now;
        UpsertMany(targets.Select(i => ItemRules.Complete(i, now).Item));
        return targets.Count;
    }

    /// <summary>Merge: every item moves into [into], then the source list is deleted.</summary>
    public void MergeList(ShopList from, ShopList into) => DeleteList(from, ListDeleteMode.MOVE_TO, into);

    /// <summary>Moves a Buy item: into a Private list it becomes Personal; out of one it stops being Personal.</summary>
    public void MoveItemToList(Item item, ShopList? list)
    {
        var from = ShopLists.ListIdOf(item, Data.Settings.ShopLists) is long f ? List(f) : null;
        bool personal = list?.Personal == true || (item.Personal && from?.Personal != true);
        Upsert(item with { ListId = list?.Id, Group = list?.Name, Personal = personal });
    }

    // ───────────────────────── shops & products ─────────────────────────

    public List<Shop> Shops => Data.Shops.Where(s => s.DeletedAt == null).OrderBy(s => s.Name.ToLowerInvariant()).ToList();
    public List<Product> Products => Data.Products.Where(p => p.DeletedAt == null).OrderBy(p => p.Name.ToLowerInvariant()).ToList();

    public void UpsertShop(Shop s)
    {
        var rec = s with { UpdatedAt = Now };
        if (rec.IsDefault) Data.Shops = Data.Shops.Select(x => x.Id != rec.Id && x.IsDefault ? x with { IsDefault = false, UpdatedAt = Now } : x).ToList();
        int idx = Data.Shops.FindIndex(x => x.Id == s.Id);
        if (idx >= 0) Data.Shops[idx] = rec; else Data.Shops.Add(rec);
        Commit();
    }

    public void DeleteShop(Shop s) => UpsertShop(s with { DeletedAt = Now, IsDefault = false });

    public void UpsertProduct(Product p)
    {
        var rec = p with { UpdatedAt = Now };
        int idx = Data.Products.FindIndex(x => x.Id == p.Id);
        if (idx >= 0) Data.Products[idx] = rec; else Data.Products.Add(rec);
        Commit();
    }

    public void DeleteProduct(Product p) => UpsertProduct(p with { DeletedAt = Now });

    public City CityNamed(string name)
    {
        var n = name.Trim();
        var c = Data.Cities.FirstOrDefault(x => x.DeletedAt == null && string.Equals(x.Name, n, StringComparison.OrdinalIgnoreCase));
        if (c != null) return c;
        c = new City { Id = Ids.Next(), Name = n, UpdatedAt = Now };
        Data.Cities.Add(c);
        return c;
    }

    public Chain ChainNamed(string name)
    {
        var n = name.Trim();
        var c = Data.Chains.FirstOrDefault(x => x.DeletedAt == null && string.Equals(x.Name, n, StringComparison.OrdinalIgnoreCase));
        if (c != null) return c;
        c = new Chain { Id = Ids.Next(), Name = n, UpdatedAt = Now };
        Data.Chains.Add(c);
        return c;
    }

    public string CityName(long? id) => id is long v ? Data.Cities.FirstOrDefault(c => c.Id == v)?.Name ?? "" : "";
    public string ChainName(long? id) => id is long v ? Data.Chains.FirstOrDefault(c => c.Id == v)?.Name ?? "" : "";

    public Dictionary<long, string> ShopNamesById() => Data.Shops.Where(s => s.DeletedAt == null).GroupBy(s => s.Id).ToDictionary(g => g.Key, g => g.First().Name);

    // ───────────────────────── settings, alerts, import ─────────────────────────

    public void UpdateSettings(Func<AppSettings, AppSettings> f)
    {
        Data.Settings = f(Data.Settings);
        Commit();
    }

    /// <summary>Settings that do not redraw anything (update clock, last mode…).</summary>
    public void UpdateSettingsQuiet(Func<AppSettings, AppSettings> f)
    {
        Data.Settings = f(Data.Settings);
        Save();
    }

    public HashSet<string> FiredSet() => Data.FiredKeys.ToHashSet();

    public void MarkFired(IEnumerable<string> keys)
    {
        Data.FiredKeys = Reminders.TrimFired(Data.FiredKeys.Concat(keys).Distinct().ToList());
        Save();
    }

    /// <summary>Walks due recurring items / returning staples back into Active. True when anything changed.</summary>
    public bool Sweep()
    {
        var items = ItemRules.ResurrectDue(Data.Items, Now);
        var calls = ItemRules.ResurrectCallsDue(Data.Calls ?? new(), Now);
        if (items.Count == 0 && calls.Count == 0) return false;
        foreach (var c in calls)
        {
            int idx = Data.Calls!.FindIndex(x => x.Id == c.Id);
            if (idx >= 0) Data.Calls[idx] = c;
        }
        if (items.Count > 0) UpsertMany(items); else Commit();
        return true;
    }

    public ImportSummary Import(RemindlyData blob)
    {
        var r = DataStore.MergeInto(Data, blob, Now);
        Commit();
        return r;
    }

    public void Export(string path)
    {
        var copy = Json.Deserialize(Json.Serialize(Data))!;
        copy.ExportedAt = Now;
        File.WriteAllText(path, Json.Serialize(copy));
    }
}
