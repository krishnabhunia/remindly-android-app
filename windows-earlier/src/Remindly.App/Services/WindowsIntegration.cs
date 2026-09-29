using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Windows;
using Microsoft.Win32;

namespace Remindly.App.Services;

/// <summary>Start-with-Windows via HKCU\...\Run (no admin needed) — points at THIS exe with --minimized.</summary>
public static class StartupRegistration
{
    private const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string Name = "Remindly";

    public static bool IsEnabled()
    {
        try { using var k = Registry.CurrentUser.OpenSubKey(Key); return k?.GetValue(Name) is string s && s.Length > 0; } catch { return false; }
    }

    public static void Set(bool on)
    {
        try
        {
            using var k = Registry.CurrentUser.CreateSubKey(Key)!;
            if (on) k.SetValue(Name, $"\"{Environment.ProcessPath}\" --minimized");
            else k.DeleteValue(Name, false);
        }
        catch (Exception ex) { Log.Error("startup registration", ex); }
    }
}

/// <summary>Local Personal-PIN (never synced; the phone keeps its own). SHA-256 with a random salt.</summary>
public static class Pin
{
    public static (string hash, string salt) Make(string pin)
    {
        var salt = Convert.ToBase64String(RandomNumberGenerator.GetBytes(16));
        return (Hash(pin, salt), salt);
    }
    public static string Hash(string pin, string salt) => Convert.ToBase64String(SHA256.HashData(Encoding.UTF8.GetBytes(salt + ":" + pin.Trim())));
    public static bool Verify(string pin, string? hash, string? salt) => hash != null && salt != null && Hash(pin, salt) == hash;
}

/// <summary>Thin wrappers around MessageBox / shell so view models stay free of UI plumbing.</summary>
public static class Dialogs
{
    private static Window? Owner => Application.Current?.Windows.OfType<Window>().FirstOrDefault(w => w.IsActive) ?? Application.Current?.MainWindow;

    public static bool Confirm(string message, string title = AppPaths.ProductName, bool destructive = false)
    {
        var owner = Owner;
        var icon = destructive ? MessageBoxImage.Warning : MessageBoxImage.Question;
        var r = owner != null ? MessageBox.Show(owner, message, title, MessageBoxButton.YesNo, icon, MessageBoxResult.No)
                              : MessageBox.Show(message, title, MessageBoxButton.YesNo, icon, MessageBoxResult.No);
        return r == MessageBoxResult.Yes;
    }

    public static void Info(string message, string title = AppPaths.ProductName)
    {
        var owner = Owner;
        if (owner != null) MessageBox.Show(owner, message, title, MessageBoxButton.OK, MessageBoxImage.Information);
        else MessageBox.Show(message, title, MessageBoxButton.OK, MessageBoxImage.Information);
    }

    public static void Error(string message, string title = AppPaths.ProductName)
    {
        var owner = Owner;
        if (owner != null) MessageBox.Show(owner, message, title, MessageBoxButton.OK, MessageBoxImage.Error);
        else MessageBox.Show(message, title, MessageBoxButton.OK, MessageBoxImage.Error);
    }

    public static void OpenUrl(string? url)
    {
        if (string.IsNullOrWhiteSpace(url)) return;
        try
        {
            if (!url.Contains("://") && !url.StartsWith("tel:") && !url.StartsWith("mailto:")) url = "https://" + url;
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch (Exception ex) { Error("Could not open: " + ex.Message); }
    }

    public static void OpenFolder(string path)
    {
        try { Process.Start(new ProcessStartInfo("explorer.exe", $"\"{path}\"") { UseShellExecute = true }); } catch (Exception ex) { Error(ex.Message); }
    }

    public static void CopyToClipboard(string? text) { if (!string.IsNullOrEmpty(text)) try { Clipboard.SetText(text); } catch { } }
}
