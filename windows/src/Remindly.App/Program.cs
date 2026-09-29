using Remindly.App.Services;
using Remindly.Core;

namespace Remindly.App;

/// <summary>Command-line switches.</summary>
public sealed class StartOptions
{
    /// <summary>Start hidden in the notification area (Windows start-up, relaunch after an update).</summary>
    public bool Tray { get; private set; }
    /// <summary>Ask the running Remindly to save and close (used by Setup before replacing files).</summary>
    public bool Exit { get; private set; }
    /// <summary>Started by Setup / the portable swap after an update: say so once.</summary>
    public bool Updated { get; private set; }
    /// <summary>CI: open every screen, save screenshots into this folder, exit 0 on success.</summary>
    public string? SmokeTest { get; private set; }
    public string? DataDir { get; private set; }

    public static StartOptions Parse(string[] args)
    {
        var o = new StartOptions();
        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i].ToLowerInvariant())
            {
                case "--tray": o.Tray = true; break;
                case "--exit": o.Exit = true; break;
                case "--updated": o.Updated = true; break;
                case "--smoke-test": o.SmokeTest = i + 1 < args.Length ? args[++i] : Path.GetTempPath(); break;
                case "--data-dir": if (i + 1 < args.Length) o.DataDir = args[++i]; break;
            }
        }
        return o;
    }
}

public static class Program
{
    [STAThread]
    public static int Main(string[] args)
    {
        var opts = StartOptions.Parse(args);
        if (opts.DataDir != null) AppPaths.DataRoot = Path.GetFullPath(opts.DataDir);

        if (opts.Exit)
            return SingleInstance.RequestExit(TimeSpan.FromSeconds(20)) ? 0 : 1;

        SingleInstance? instance = null;
        if (opts.SmokeTest == null)
        {
            instance = SingleInstance.TryAcquire();
            // After an update the new copy can start while the old one is still saving and closing: wait for it.
            for (int i = 0; instance == null && opts.Updated && i < 100; i++)
            {
                Thread.Sleep(200);
                instance = SingleInstance.TryAcquire();
            }
            if (instance == null)
            {
                // Already running (maybe hidden in the notification area): bring it forward instead.
                SingleInstance.SignalActivate();
                return 0;
            }
        }

        try
        {
            Log.Info($"Remindly {Core.Updates.UpdateService.CurrentVersion.ToString(3)} starting ({(InstallInfo.IsInstalledMode() ? "installed" : "portable")}) {string.Join(' ', args)}");
            var app = new App(opts, instance);
            app.InitializeComponent();
            return app.Run();
        }
        catch (Exception ex)
        {
            Log.Error("Fatal", ex);
            if (opts.SmokeTest != null) return 3;
            System.Windows.MessageBox.Show("Remindly hit an unexpected error and has to close.\n\n" + ex.Message + "\n\nDetails: " + AppPaths.LogFile,
                "Remindly", System.Windows.MessageBoxButton.OK, System.Windows.MessageBoxImage.Error);
            return 2;
        }
        finally
        {
            instance?.Dispose();
        }
    }
}
