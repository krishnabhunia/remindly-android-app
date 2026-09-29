using Remindly.Core;

namespace Remindly.App.Services;

/// <summary>
/// One Remindly per Windows session. The mutex name is what Setup looks for (CheckForMutexes
/// "Remindly.SingleInstance") to know that a previous version is running. Two named events let a
/// second start bring the window forward, and let Setup (via "Remindly.exe --exit") ask the running
/// copy to save and close before its files are replaced.
/// </summary>
public sealed class SingleInstance : IDisposable
{
    public const string MutexName = @"Local\Remindly.SingleInstance";
    private const string ActivateName = @"Local\Remindly.Activate";
    private const string ExitName = @"Local\Remindly.Exit";

    private readonly Mutex _mutex;
    private readonly EventWaitHandle _activate;
    private readonly EventWaitHandle _exit;
    private readonly CancellationTokenSource _stop = new();

    private SingleInstance(Mutex m)
    {
        _mutex = m;
        _activate = new EventWaitHandle(false, EventResetMode.AutoReset, ActivateName);
        _exit = new EventWaitHandle(false, EventResetMode.AutoReset, ExitName);
    }

    public static SingleInstance? TryAcquire()
    {
        var m = new Mutex(false, MutexName);
        bool owned;
        try { owned = m.WaitOne(0); }
        catch (AbandonedMutexException) { owned = true; } // a previous copy crashed: the lock is ours now
        if (!owned) { m.Dispose(); return null; }
        return new SingleInstance(m);
    }

    public static bool IsRunning()
    {
        if (!Mutex.TryOpenExisting(MutexName, out var m)) return false;
        m.Dispose();
        return true;
    }

    public static void SignalActivate()
    {
        try { if (EventWaitHandle.TryOpenExisting(ActivateName, out var e)) using (e) e.Set(); }
        catch (Exception ex) { Log.Warn("Activate signal failed: " + ex.Message); }
    }

    /// <summary>Asks the running copy to save and exit; true once its lock is gone (or nothing was running).</summary>
    public static bool RequestExit(TimeSpan timeout)
    {
        if (!IsRunning()) return true;
        try { if (EventWaitHandle.TryOpenExisting(ExitName, out var e)) using (e) e.Set(); }
        catch (Exception ex) { Log.Warn("Exit signal failed: " + ex.Message); }
        var until = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < until)
        {
            if (!IsRunning()) return true;
            Thread.Sleep(200);
        }
        return !IsRunning();
    }

    /// <summary>Listens for the two signals on a background thread; the callbacks run on that thread.</summary>
    public void Listen(Action onActivate, Action onExit)
    {
        var t = new Thread(() =>
        {
            var handles = new WaitHandle[] { _activate, _exit, _stop.Token.WaitHandle };
            while (true)
            {
                int i = WaitHandle.WaitAny(handles);
                if (i == 0) onActivate();
                else if (i == 1) { onExit(); }
                else return;
            }
        }) { IsBackground = true, Name = "Remindly.SingleInstance" };
        t.Start();
    }

    public void Dispose()
    {
        _stop.Cancel();
        try { _mutex.ReleaseMutex(); } catch { /* released on another thread or already gone */ }
        _mutex.Dispose();
        _activate.Dispose();
        _exit.Dispose();
    }
}
