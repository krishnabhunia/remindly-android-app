using System.Diagnostics;
using Microsoft.Win32;
using Remindly.Core;
using Remindly.Core.Updates;

namespace Remindly.App.Services;

/// <summary>Installed (by Setup) or portable, the exe's location, and the Windows start-up entry.</summary>
public static class InstallInfo
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValue = "Remindly";

    public static string ExePath => Environment.ProcessPath ?? Path.Combine(AppContext.BaseDirectory, UpdateLogic.PortableExeName);
    public static string ExeDirectory => Path.GetDirectoryName(ExePath) ?? AppContext.BaseDirectory;

    /// <summary>The folder Setup recorded (HKCU for a per-user install, HKLM for all users), or null.</summary>
    public static string? InstalledDir()
    {
        foreach (var root in new[] { Registry.CurrentUser, Registry.LocalMachine })
        {
            try
            {
                using var k = root.OpenSubKey(@"Software\Remindly");
                if (k?.GetValue("InstallDir") is string s && !string.IsNullOrWhiteSpace(s)) return s;
            }
            catch { /* ignore */ }
        }
        return null;
    }

    /// <summary>True when this exe runs from the folder Setup installed → updates go through the new Setup.</summary>
    public static bool IsInstalledMode()
    {
        var dir = InstalledDir();
        if (dir == null) return false;
        static string Norm(string s) => s.Trim().Replace('/', '\\').TrimEnd('\\').ToLowerInvariant();
        return Norm(dir) == Norm(ExeDirectory);
    }

    public static string ModeLabel => IsInstalledMode() ? "Installed" : "Portable";

    public static bool StartsWithWindows()
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey);
            return k?.GetValue(RunValue) is string;
        }
        catch { return false; }
    }

    public static void SetStartWithWindows(bool on)
    {
        try
        {
            using var k = Registry.CurrentUser.CreateSubKey(RunKey, true);
            if (on) k.SetValue(RunValue, $"\"{ExePath}\" --tray");
            else if (k.GetValue(RunValue) != null) k.DeleteValue(RunValue, false);
        }
        catch (Exception ex) { Log.Warn("Start-with-Windows change failed: " + ex.Message); }
    }

    public static void OpenUrl(string url)
    {
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
        catch (Exception ex) { Log.Warn($"Could not open {url}: {ex.Message}"); }
    }
}
