using System.Collections.Concurrent;
using Google.Api.Gax.Grpc;
using Google.Cloud.Firestore.V1;
using Google.Protobuf;
using Grpc.Core;
using Remindly.Core.Models;
using Remindly.Core.Store;

namespace Remindly.Core.Sync;

public enum SyncState { Off, SignedOut, Connecting, Live, Offline, Error }

public sealed class CollectionStatus
{
    public string Name { get; init; } = "";
    public bool Current { get; set; }
    public int DocCount { get; set; }
    public bool PermissionDenied { get; set; }
    public string? LastError { get; set; }
}

/// <summary>
/// Live Firestore mirror of the Android SyncRepo, over gRPC with the user's Firebase ID token:
///   • one Listen stream carrying a query target per collection + the settings document
///   • remote → local: batches applied at every consistent snapshot boundary through LWW
///   • local → remote: Commit of the {json, updatedAt, schemaVer} envelope, queued while offline
///   • first connect: reconcile — push only local records that are NEWER than (or absent from)
///     the cloud, so a fresh install can never clobber the phone's data with stale copies
///   • N29 catalogue collections are opt-in and degrade gracefully when the rules are not there yet
/// </summary>
public sealed class FirestoreSync : IDisposable
{
    private readonly Repository _repo;
    private readonly FirebaseAuth _auth;
    private readonly FirebaseConfig _cfg;
    private readonly string _pendingPath;
    private FirestoreClient? _client;
    private CancellationTokenSource? _cts;
    private Task? _loop;
    private readonly SemaphoreSlim _flushGate = new(1, 1);
    private readonly ConcurrentDictionary<string, PendingWrite> _pending = new();
    private readonly ConcurrentDictionary<string, long> _cloudStamp = new();   // "col/id" → updatedAt seen from the cloud
    private readonly HashSet<string> _reconciled = new();
    private long _cloudSettingsStamp = -1;
    private int _backoffMs = 1000;
    private volatile bool _initialSnapshot;   // all core targets CURRENT at least once this session

    public string? EmulatorEndpoint { get; init; }   // e.g. "127.0.0.1:8080" — tests only
    public string? EmulatorToken { get; init; }      // "owner" — tests only

    public SyncState State { get; private set; } = SyncState.Off;
    public string? LastError { get; private set; }
    public long LastAppliedAt { get; private set; }
    public long LastPushedAt { get; private set; }
    public int PendingCount => _pending.Count;
    public Dictionary<string, CollectionStatus> Collections { get; } = new();
    public event Action? StateChanged;
    public Action<string, Exception?>? Log { get; set; }
    public bool Enabled { get; private set; }

    private sealed record PendingWrite(string Col, string Id, string Json, long Stamp, bool IsSettings);

    public FirestoreSync(Repository repo, FirebaseAuth auth, FirebaseConfig cfg, string stateDir)
    {
        _repo = repo; _auth = auth; _cfg = cfg;
        _pendingPath = Path.Combine(stateDir, "pending-writes.json");
        foreach (var c in Store.Collections.All) Collections[c] = new CollectionStatus { Name = c };
        Collections[Store.Collections.Settings] = new CollectionStatus { Name = Store.Collections.Settings };
        LoadPending();
        _repo.LocalWrite += OnLocalWrite;
        _repo.LocalSettingsWrite += OnLocalSettingsWrite;
        _auth.Changed += () => { if (!_auth.IsSignedIn) Stop(SyncState.SignedOut); else if (Enabled) Start(); };
    }

    private string Root => $"{_cfg.DocumentsRoot}/users/{_auth.Uid}";
    private bool CatalogueOn => _repo.Prefs.CatalogueSyncEnabled;

    // ── lifecycle ─────────────────────────────────────────────────────────────────────────

    /// <summary>Called with the (device-local) Cloud sync toggle. Idempotent.</summary>
    public void SetEnabled(bool on)
    {
        Enabled = on;
        if (!on) { Stop(SyncState.Off); return; }
        if (!_auth.IsSignedIn) { SetState(SyncState.SignedOut); return; }
        Start();
    }

    private void Start()
    {
        if (_loop is { IsCompleted: false }) return;
        _cts = new CancellationTokenSource();
        var ct = _cts.Token;
        _reconciled.Clear();
        _initialSnapshot = false;
        _loop = Task.Run(() => RunAsync(ct), ct);
    }

    private void Stop(SyncState to)
    {
        try { _cts?.Cancel(); } catch { }
        _cts = null; _loop = null;
        foreach (var c in Collections.Values) c.Current = false;
        SetState(to);
    }

    private void SetState(SyncState s, string? err = null)
    {
        State = s; LastError = err;
        StateChanged?.Invoke();
    }

    public void Dispose() { Stop(SyncState.Off); }

    private FirestoreClient BuildClient()
    {
        var b = new FirestoreClientBuilder();
        if (EmulatorEndpoint != null) { b.Endpoint = EmulatorEndpoint; b.ChannelCredentials = ChannelCredentials.Insecure; }
        else b.ChannelCredentials = new SslCredentials();
        return b.Build();
    }

    private async Task<CallSettings> AuthSettingsAsync(CancellationToken ct)
    {
        var token = EmulatorToken ?? await _auth.GetIdTokenAsync(ct);
        return CallSettings.FromHeader("Authorization", "Bearer " + token)
            .MergedWith(CallSettings.FromHeader("google-cloud-resource-prefix", _cfg.FirestoreDatabase))
            .MergedWith(CallSettings.FromCancellationToken(ct));
    }

    // ── the listen loop ───────────────────────────────────────────────────────────────────

    private async Task RunAsync(CancellationToken ct)
    {
        _client ??= BuildClient();
        while (!ct.IsCancellationRequested)
        {
            try
            {
                SetState(SyncState.Connecting);
                var settings = await AuthSettingsAsync(ct);
                using var stream = _client.Listen(settings);
                var targets = ActiveTargets();
                foreach (var (id, col) in targets)
                    await stream.WriteAsync(new ListenRequest { Database = _cfg.FirestoreDatabase, AddTarget = TargetFor(id, col) });

                var buffer = new Dictionary<string, List<(string json, int ver, long upd)>>();
                var pendingDeletes = new Dictionary<string, HashSet<string>>();
                var settingsBuf = new (string? json, int ver, long upd)?[] { null };
                var responses = stream.GetResponseStream();
                await foreach (var resp in responses.WithCancellation(ct))
                {
                    switch (resp.ResponseTypeCase)
                    {
                        case ListenResponse.ResponseTypeOneofCase.TargetChange:
                        {
                            var tc = resp.TargetChange;
                            if (tc.TargetChangeType == TargetChange.Types.TargetChangeType.Current)
                            {
                                foreach (var tid in tc.TargetIds) if (targets.TryGetValue(tid, out var col)) Collections[col].Current = true;
                                if (tc.TargetIds.Count == 0) foreach (var col in targets.Values) Collections[col].Current = true;
                            }
                            // A consistent snapshot boundary: apply what we have buffered.
                            var boundary = tc.ResumeToken.Length > 0 || (tc.TargetIds.Count == 0 && tc.TargetChangeType is TargetChange.Types.TargetChangeType.NoChange or TargetChange.Types.TargetChangeType.Current);
                            if (boundary)
                            {
                                if (buffer.Count > 0 || settingsBuf[0] != null) await FlushBufferAsync(buffer, settingsBuf, ct);
                                if (targets.Values.All(c => Collections[c].Current)) { _initialSnapshot = true; if (State != SyncState.Live) { _backoffMs = 1000; SetState(SyncState.Live); } }
                                await ReconcileIfReadyAsync(targets, ct);
                            }
                            if (tc.TargetChangeType == TargetChange.Types.TargetChangeType.Remove)
                            {
                                var msg = tc.Cause?.Message ?? "target removed";
                                foreach (var tid in tc.TargetIds)
                                    if (targets.TryGetValue(tid, out var col))
                                    {
                                        var denied = tc.Cause?.Code == (int)StatusCode.PermissionDenied;
                                        Collections[col].PermissionDenied = denied; Collections[col].LastError = msg;
                                        Log?.Invoke($"listen target '{col}' removed: {msg}", null);
                                    }
                                StateChanged?.Invoke();
                            }
                            break;
                        }
                        case ListenResponse.ResponseTypeOneofCase.DocumentChange:
                        {
                            var d = resp.DocumentChange.Document;
                            var (col, id) = SplitName(d.Name);
                            if (col == null) break;
                            var json = d.Fields.TryGetValue("json", out var jv) ? jv.StringValue : null;
                            var ver = d.Fields.TryGetValue("schemaVer", out var sv) ? (int)sv.IntegerValue : 0;
                            var upd = d.Fields.TryGetValue("updatedAt", out var uv) ? uv.IntegerValue : 0;
                            if (json == null) break;
                            if (col == Store.Collections.Settings) { if (id == "app") settingsBuf[0] = (json, ver, upd); break; }
                            _cloudStamp[$"{col}/{id}"] = upd;
                            if (!buffer.TryGetValue(col, out var list)) buffer[col] = list = new();
                            list.Add((json, ver, upd));
                            Collections[col].DocCount++;
                            break;
                        }
                        case ListenResponse.ResponseTypeOneofCase.DocumentDelete:
                        case ListenResponse.ResponseTypeOneofCase.DocumentRemove:
                            // The phone never deletes documents (tombstones only); nothing to mirror locally.
                            break;
                        case ListenResponse.ResponseTypeOneofCase.Filter:
                            // Count mismatch → the server asks us to re-sync this target. Simplest correct move: restart the stream.
                            throw new RpcException(new Status(StatusCode.Aborted, "filter mismatch — resync"));
                    }
                }
                throw new RpcException(new Status(StatusCode.Unavailable, "listen stream ended"));
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
            catch (RpcException ex) when (ex.StatusCode == StatusCode.PermissionDenied)
            {
                Log?.Invoke("Firestore PERMISSION_DENIED — check the security rules for this uid", ex);
                SetState(SyncState.Error, "Permission denied by Firestore rules. " + ex.Status.Detail);
                await Task.Delay(TimeSpan.FromSeconds(60), ct).ContinueWith(_ => { });
            }
            catch (Exception ex) when (!ct.IsCancellationRequested)
            {
                var offline = ex is RpcException r && r.StatusCode is StatusCode.Unavailable or StatusCode.DeadlineExceeded or StatusCode.Aborted || ex is HttpRequestException;
                Log?.Invoke(offline ? "sync offline — reconnecting" : "sync error — reconnecting", ex);
                SetState(offline ? SyncState.Offline : SyncState.Error, ex.Message);
                try { await Task.Delay(_backoffMs, ct); } catch { return; }
                _backoffMs = Math.Min(_backoffMs * 2, 60_000);
            }
        }
    }

    private Dictionary<int, string> ActiveTargets()
    {
        var t = new Dictionary<int, string>();
        var id = 1;
        foreach (var c in Store.Collections.Core) t[id++] = c;
        if (CatalogueOn) foreach (var c in Store.Collections.Catalogue) if (!Collections[c].PermissionDenied) t[id++] = c;
        t[id++] = Store.Collections.Settings;
        return t;
    }

    private Target TargetFor(int id, string col)
    {
        if (col == Store.Collections.Settings)
            return new Target { TargetId = id, Documents = new Target.Types.DocumentsTarget { Documents = { $"{Root}/settings/app" } } };
        return new Target
        {
            TargetId = id,
            Query = new Target.Types.QueryTarget
            {
                Parent = Root,
                StructuredQuery = new StructuredQuery { From = { new StructuredQuery.Types.CollectionSelector { CollectionId = col } } },
            },
        };
    }

    private (string? col, string id) SplitName(string name)
    {
        // projects/p/databases/(default)/documents/users/{uid}/{col}/{id}
        var idx = name.IndexOf("/documents/users/", StringComparison.Ordinal);
        if (idx < 0) return (null, "");
        var rest = name[(idx + "/documents/users/".Length)..].Split('/');
        return rest.Length >= 3 ? (rest[1], rest[2]) : (null, "");
    }

    private Task FlushBufferAsync(Dictionary<string, List<(string json, int ver, long upd)>> buffer, (string? json, int ver, long upd)?[] settingsBuf, CancellationToken ct)
    {
        var snapshot = buffer.ToDictionary(kv => kv.Key, kv => kv.Value.ToList());
        buffer.Clear();
        var s = settingsBuf[0]; settingsBuf[0] = null;
        var tcs = new TaskCompletionSource();
        _repo.Invoke(() =>
        {
            try
            {
                foreach (var (col, docs) in snapshot)
                {
                    var n = _repo.ApplyRemote(col, docs.Select(d => (d.json, d.ver)), out var warnings, out var localNewer);
                    foreach (var w in warnings) Log?.Invoke("SYNC " + w, null);
                    if (n > 0) LastAppliedAt = Clock.Now();
                    // Self-heal: the cloud holds an OLDER copy than this PC (a race the phone lost) — re-push ours.
                    foreach (var (id, json, stamp) in localNewer) Enqueue(new PendingWrite(col, id, json, stamp, false));
                }
                if (s is { json: not null } sv)
                {
                    if (sv.ver < Constants.SyncSchema)
                        Log?.Invoke($"settings document from older sync schema v{sv.ver} — not adopted", null);
                    else
                    {
                        _cloudSettingsStamp = sv.upd;
                        try { _repo.ApplyRemoteSettings(AppSettings.Parse(sv.json!)); LastAppliedAt = Clock.Now(); }
                        catch (Exception ex) { Log?.Invoke("settings decode failed", ex); }
                    }
                }
            }
            catch (Exception ex) { Log?.Invoke("apply remote failed", ex); }
            finally { tcs.TrySetResult(); StateChanged?.Invoke(); }
        });
        return tcs.Task.ContinueWith(_ => FlushPendingAsync(ct), ct).Unwrap();
    }

    /// <summary>After a collection is CURRENT for the first time: push local records the cloud lacks or that are newer.</summary>
    private async Task ReconcileIfReadyAsync(Dictionary<int, string> targets, CancellationToken ct)
    {
        var ready = targets.Values.Where(c => Collections[c].Current && !_reconciled.Contains(c)).ToList();
        if (ready.Count == 0) return;
        foreach (var c in ready) _reconciled.Add(c);
        var tcs = new TaskCompletionSource();
        _repo.Invoke(() =>
        {
            try
            {
                foreach (var (col, id, json, stamp) in _repo.AllForPush(includeCatalogue: CatalogueOn))
                {
                    if (!ready.Contains(col)) continue;
                    if (_cloudStamp.TryGetValue($"{col}/{id}", out var cloud) && cloud >= stamp) continue;
                    if (stamp <= 0 && _cloudStamp.ContainsKey($"{col}/{id}")) continue;
                    Enqueue(new PendingWrite(col, id, json, stamp, false));
                }
                if (ready.Contains(Store.Collections.Settings) && _repo.Settings.SettingsUpdatedAt > _cloudSettingsStamp && _repo.Settings.SettingsUpdatedAt > 0)
                    Enqueue(new PendingWrite(Store.Collections.Settings, "app", _repo.Settings.ForSync().ToJson(), _repo.Settings.SettingsUpdatedAt, true));
            }
            finally { tcs.TrySetResult(); }
        });
        await tcs.Task;
        _ = FlushPendingAsync(ct);
    }

    // ── local → remote ────────────────────────────────────────────────────────────────────

    private void OnLocalWrite(string col, string id, string json, long stamp)
    {
        if (Store.Collections.Catalogue.Contains(col) && !CatalogueOn) return;
        Enqueue(new PendingWrite(col, id, json, stamp, false));
        _ = FlushPendingAsync(_cts?.Token ?? CancellationToken.None);
    }

    private void OnLocalSettingsWrite(AppSettings s)
    {
        Enqueue(new PendingWrite(Store.Collections.Settings, "app", s.ToJson(), s.SettingsUpdatedAt, true));
        _ = FlushPendingAsync(_cts?.Token ?? CancellationToken.None);
    }

    private void Enqueue(PendingWrite w)
    {
        _pending[$"{w.Col}/{w.Id}"] = w;
        SavePending();
        StateChanged?.Invoke();
    }

    /// <summary>Commit queued writes in batches. Safe to call often; no-ops when offline/signed out.</summary>
    public async Task FlushPendingAsync(CancellationToken ct)
    {
        if (!Enabled || !_auth.IsSignedIn || _pending.IsEmpty) return;
        if (!_initialSnapshot) return;   // never push before we know what the cloud holds (a stale queued write must not clobber the phone)
        if (!await _flushGate.WaitAsync(0, ct)) return;
        try
        {
            _client ??= BuildClient();
            while (!_pending.IsEmpty && !ct.IsCancellationRequested)
            {
                // Drop queued writes the cloud has already superseded (LWW applies to the queue too).
                foreach (var w in _pending.Values.ToList())
                    if (!w.IsSettings && _cloudStamp.TryGetValue($"{w.Col}/{w.Id}", out var cs) && cs > w.Stamp) _pending.TryRemove($"{w.Col}/{w.Id}", out _);
                    else if (w.IsSettings && _cloudSettingsStamp > w.Stamp) _pending.TryRemove($"{w.Col}/{w.Id}", out _);
                if (_pending.IsEmpty) { SavePending(); break; }
                var batch = _pending.Values.Take(200).ToList();
                var writes = batch.Where(w => !(Store.Collections.Catalogue.Contains(w.Col) && Collections[w.Col].PermissionDenied)).Select(w => new Write
                {
                    Update = new Document
                    {
                        Name = $"{Root}/{w.Col}/{w.Id}",
                        Fields =
                        {
                            ["json"] = new Value { StringValue = w.Json },
                            ["updatedAt"] = new Value { IntegerValue = w.Stamp },
                            ["schemaVer"] = new Value { IntegerValue = Constants.SyncSchema },
                        },
                    },
                }).ToList();
                if (writes.Count == 0) { foreach (var w in batch) _pending.TryRemove($"{w.Col}/{w.Id}", out _); SavePending(); break; }
                try
                {
                    var settings = await AuthSettingsAsync(ct);
                    await _client.CommitAsync(new CommitRequest { Database = _cfg.FirestoreDatabase, Writes = { writes } }, settings);
                    foreach (var w in batch)
                    {
                        _pending.TryRemove($"{w.Col}/{w.Id}", out _);
                        if (w.IsSettings) _cloudSettingsStamp = Math.Max(_cloudSettingsStamp, w.Stamp);
                        else _cloudStamp[$"{w.Col}/{w.Id}"] = w.Stamp;
                    }
                    LastPushedAt = Clock.Now();
                    SavePending();
                    StateChanged?.Invoke();
                }
                catch (RpcException ex) when (ex.StatusCode == StatusCode.PermissionDenied)
                {
                    // Most likely the N29 rules are not merged yet: park the catalogue writes, keep the core ones.
                    var cat = batch.Where(w => Store.Collections.Catalogue.Contains(w.Col)).ToList();
                    if (cat.Count > 0 && cat.Count < batch.Count) { foreach (var c in cat.Select(w => w.Col).Distinct()) { Collections[c].PermissionDenied = true; Collections[c].LastError = ex.Status.Detail; } continue; }
                    if (cat.Count == batch.Count) { foreach (var c in cat.Select(w => w.Col).Distinct()) { Collections[c].PermissionDenied = true; Collections[c].LastError = ex.Status.Detail; } Log?.Invoke("catalogue push denied — Firestore rules for cities/chains/products/productlinks not merged yet", ex); StateChanged?.Invoke(); break; }
                    Log?.Invoke("push denied", ex); SetState(SyncState.Error, "Push denied: " + ex.Status.Detail); break;
                }
                catch (Exception ex) when (!ct.IsCancellationRequested)
                {
                    Log?.Invoke("push failed — will retry", ex);
                    break;   // the listen loop's reconnect will call us again
                }
            }
        }
        finally { _flushGate.Release(); }
    }

    private void LoadPending()
    {
        try
        {
            if (!File.Exists(_pendingPath)) return;
            var list = JsonSerializer.Deserialize<List<PendingWrite>>(File.ReadAllText(_pendingPath)) ?? new();
            foreach (var w in list) _pending[$"{w.Col}/{w.Id}"] = w;
        }
        catch { /* ignore */ }
    }

    private void SavePending()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_pendingPath)!);
            File.WriteAllText(_pendingPath, JsonSerializer.Serialize(_pending.Values.ToList()));
        }
        catch { /* ignore */ }
    }

    /// <summary>Human status line for the sidebar.</summary>
    public string StatusLine() => State switch
    {
        SyncState.Off => "Cloud sync off",
        SyncState.SignedOut => "Signed out",
        SyncState.Connecting => "Connecting…",
        SyncState.Live => PendingCount > 0 ? $"Live · {PendingCount} pending" : "Live",
        SyncState.Offline => PendingCount > 0 ? $"Offline · {PendingCount} queued" : "Offline · retrying",
        _ => "Sync error",
    };
}
