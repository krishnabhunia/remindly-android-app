using System.Threading;

namespace Remindly.App;

/// <summary>
/// Explicit entry point: single-instance mutex + a named event so launching a second copy (or
/// the Start-menu shortcut while we sit in the tray) simply brings the running window forward.
/// </summary>
public static class Program
{
    private static Mutex? _mutex;
    public const string ShowEventName = @"Local\RemindlyWindows.Show";

    [STAThread]
    public static int Main(string[] args)
    {
        bool createdNew;
        try { _mutex = new Mutex(true, @"Local\RemindlyWindows.SingleInstance", out createdNew); }
        catch { createdNew = true; }

        if (!createdNew)
        {
            try { using var ev = EventWaitHandle.OpenExisting(ShowEventName); ev.Set(); } catch { /* first instance not listening */ }
            return 0;
        }

        var app = new App { StartMinimized = args.Any(a => a.Equals("--minimized", StringComparison.OrdinalIgnoreCase)) };
        app.InitializeComponent();
        int rc = app.Run();
        try { _mutex?.ReleaseMutex(); _mutex?.Dispose(); } catch { }
        return rc;
    }
}
