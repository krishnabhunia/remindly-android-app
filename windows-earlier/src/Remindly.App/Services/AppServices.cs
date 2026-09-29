using System.Globalization;
using System.Windows;
using System.Windows.Threading;
using Remindly.Core.Logic;
using Remindly.Core.Models;
using Remindly.Core.Store;
using Remindly.Core.Sync;

namespace Remindly.App.Services;

/// <summary>Paths + file log (mirrors Android's Error Logs card: every swallowed failure is written down).</summary>
public static class AppPaths
{
    public const string ProductName = "Remindly";
    public static string Root => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Remindly");
    public static string Data => Path.Combine(Root, "data");
    public static string Secure => Path.Combine(Root, "secure");
    public static string State => Path.Combine(Root, "state");
    public static string LogFile => Path.Combine(Root, "remindly.log");
    public static string Version => typeof(AppPaths).Assembly.GetName().Version?.ToString(3) ?? "1.0.0";
}

public static class Log
{
    private static readonly object Gate = new();
    public static void Info(string msg) => Write("INFO", msg, null);
    public static void Warn(string msg) => Write("WARN", msg, null);
    public static void Error(string msg, Exception? ex) => Write("ERROR", msg, ex);
    private static void Write(string level, string msg, Exception? ex)
    {
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(AppPaths.Root);
                var fi = new FileInfo(AppPaths.LogFile);
                if (fi.Exists && fi.Length > 2_000_000) File.Move(AppPaths.LogFile, AppPaths.LogFile + ".1", true);
                File.AppendAllText(AppPaths.LogFile, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} [{level}] {msg}{(ex != null ? " — " + Describe(ex) : "")}{Environment.NewLine}");
            }
        }
        catch { /* logging must never throw */ }
    }

    /// <summary>
    /// One-line summary followed by the whole exception chain (inner exceptions + stack traces).
    /// XAML failures in particular only name the real culprit (the missing key / bad setter) in an
    /// inner exception, so a bare <c>ex.Message</c> is useless for diagnosis.
    /// </summary>
    public static string Describe(Exception ex)
    {
        var sb = new System.Text.StringBuilder();
        sb.Append(ex.GetType().Name).Append(": ").Append(ex.Message);
        if (ex is System.Windows.Markup.XamlParseException xpe && (xpe.LineNumber > 0 || xpe.BaseUri != null))
            sb.Append($" [xaml {xpe.BaseUri} line {xpe.LineNumber}:{xpe.LinePosition}]");
        var depth = 0;
        for (var inner = ex.InnerException; inner != null && depth < 8; inner = inner.InnerException, depth++)
            sb.Append(Environment.NewLine).Append(new string(' ', 4 * (depth + 1))).Append("caused by ").Append(inner.GetType().Name).Append(": ").Append(inner.Message);
        sb.Append(Environment.NewLine).Append(ex.StackTrace);
        return sb.ToString();
    }
    public static string Tail(int lines = 200)
    {
        try { return File.Exists(AppPaths.LogFile) ? string.Join(Environment.NewLine, File.ReadAllLines(AppPaths.LogFile).TakeLast(lines)) : ""; } catch { return ""; }
    }
}

/// <summary>Composition root. One instance for the process; view models reach it via App.Services.</summary>
public sealed class AppServices : IDisposable
{
    public Repository Repo { get; }
    public FirebaseConfig Firebase { get; }
    public FirebaseAuth Auth { get; }
    public FirestoreSync Sync { get; }
    public AlertEngine Alerts { get; }
    public Housekeeping Housekeeping { get; }
    public Dispatcher Dispatcher { get; }
    public TrayService? Tray { get; set; }

    public AppServices(Dispatcher dispatcher)
    {
        Dispatcher = dispatcher;
        Directory.CreateDirectory(AppPaths.Data);
        Repo = new Repository(AppPaths.Data)
        {
            Invoke = a => { if (dispatcher.CheckAccess()) a(); else dispatcher.BeginInvoke(a); },
            Log = (m, e) => Log.Error("STORE " + m, e),
        };
        Repo.Load();
        Firebase = FirebaseConfig.Load();
        Auth = new FirebaseAuth(Firebase, AppPaths.Secure) { Log = (m, e) => Log.Error("AUTH " + m, e) };
        Sync = new FirestoreSync(Repo, Auth, Firebase, AppPaths.State) { Log = (m, e) => Log.Error("SYNC " + m, e) };
        Alerts = new AlertEngine(this);
        Housekeeping = new Housekeeping(this);
        Sync.SetEnabled(Repo.Settings.CloudSync);
    }

    /// <summary>Time display follows the synced "Time format" setting (PHONE = this PC's clock setting).</summary>
    public bool SystemIs24h => !CultureInfo.CurrentCulture.DateTimeFormat.ShortTimePattern.Contains('t');
    public string Time(long ms) => Core.Logic.Time.FormatTime(ms, Repo.Settings.TimeFormat, SystemIs24h);
    public string DayTime(long ms) => Core.Logic.Time.FormatDayTime(ms, Repo.Settings.TimeFormat, SystemIs24h);
    public string DateTimeText(long ms) => Core.Logic.Time.FormatDateTime(ms, Repo.Settings.TimeFormat, SystemIs24h);

    /// <summary>"Today 6:00 PM" / "Tomorrow …" / "Wed, 16 Sep · 6:00 PM" / "Yesterday" — relative day label.</summary>
    public string Relative(long ms, bool withTime = true)
    {
        var d = Core.Logic.Time.LocalDate(ms); var today = Core.Logic.Time.LocalDate(Clock.Now());
        var day = d == today ? "Today" : d == today.AddDays(1) ? "Tomorrow" : d == today.AddDays(-1) ? "Yesterday" : Core.Logic.Time.FormatDay(ms);
        return withTime ? day + " · " + Time(ms) : day;
    }

    public void Dispose()
    {
        try { Alerts.Dispose(); } catch { }
        try { Housekeeping.Dispose(); } catch { }
        try { Sync.Dispose(); } catch { }
        try { Tray?.Dispose(); } catch { }
    }
}

/// <summary>
/// The midnight/boot sweeps the phone runs (Receivers.kt TYPE_MIDNIGHT + TYPE_LAPSE), executed on
/// this PC too — every step is idempotent and merges cleanly through LWW if both devices run it.
/// </summary>
public sealed class Housekeeping : IDisposable
{
    private readonly AppServices _s;
    private readonly DispatcherTimer _timer;

    public Housekeeping(AppServices s)
    {
        _s = s;
        _timer = new DispatcherTimer(DispatcherPriority.Background, s.Dispatcher) { Interval = TimeSpan.FromMinutes(1) };
        _timer.Tick += (_, _) => Run();
        _timer.Start();
        Run();
    }

    public void Run()
    {
        try
        {
            var now = Clock.Now();
            var repo = _s.Repo;
            // TYPE_LAPSE: Done shop items whose lapse has elapsed come back to Active.
            foreach (var i in repo.Items.Where(i => i.Done && i.DeletedAt == null && i.ReturnAt is long r && r <= now).ToList())
                repo.Upsert(Engine.LapseReturn(i, now));
            // Midnight-class sweeps: once per local day (also on start-up, like the phone's app-open catch-up).
            var today = Core.Logic.Time.StartOfDay(now);
            if (repo.Prefs.LastMidnightSweep < today)
            {
                foreach (var i in Recurrence.ResurrectDue(repo.Items, now)) repo.Upsert(i);
                foreach (var i in Recurrence.ClearStaleOos(repo.Items, now)) repo.Upsert(i);
                foreach (var c in Recurrence.ResurrectCallsDue(repo.Calls, now)) repo.Upsert(c);
                foreach (var i in Recurrence.RollMissedRepeats(repo.Items, now)) { repo.Upsert(i); Log.Info($"ROLL '{i.Title}' → {Core.Logic.Time.FormatDate(i.DueAt!.Value)} ({i.MissedAt.Count} missed logged)"); }
                // Done auto-clear (per-tab days; 0 = off): soft-delete done items older than N days.
                foreach (var tab in new[] { Tab.TASKS, Tab.SHOP, Tab.LEARN })
                {
                    var days = Alerts.ClearFor(repo.Settings, tab);
                    if (days <= 0) continue;
                    var cutoff = now - days * 86_400_000L;
                    foreach (var i in repo.Items.Where(i => i.Tab == tab && i.Done && i.DeletedAt == null && (i.DoneAt ?? 0) < cutoff && i.RepeatMode == "OFF").ToList())
                        repo.Upsert(Engine.SoftDelete(i, now));
                }
                var purged = repo.PurgeBin(now);
                if (purged > 0) Log.Info($"Bin purge: {purged} row(s) older than 30 days");
                repo.Prefs.FiredKeys = repo.Prefs.FiredKeys.Where(k => long.TryParse(k.Split(':').Last(), out var t) && t > now - 7L * 86_400_000L).ToList();
                repo.Prefs.LastMidnightSweep = today;
                repo.SavePrefs();
            }
        }
        catch (Exception ex) { Log.Error("housekeeping", ex); }
    }

    public void Dispose() => _timer.Stop();
}
