using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace Remindly.Core.Sync;

/// <summary>
/// At-rest protection for the OAuth client secret and refresh tokens. On Windows this is DPAPI
/// (CurrentUser scope — only this Windows account on this PC can read it, mirroring the phone's
/// EncryptedSharedPreferences). Off Windows (tests / Linux CI) it falls back to plain files.
/// </summary>
public static class SecureStore
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("Remindly.Windows.v1");

    public static void Write(string path, string plaintext)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var bytes = Encoding.UTF8.GetBytes(plaintext);
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
            bytes = ProtectedData.Protect(bytes, Entropy, DataProtectionScope.CurrentUser);
        var tmp = path + ".tmp";
        File.WriteAllBytes(tmp, bytes);
        File.Move(tmp, path, overwrite: true);
    }

    public static string? Read(string path)
    {
        try
        {
            if (!File.Exists(path)) return null;
            var bytes = File.ReadAllBytes(path);
            if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
                bytes = ProtectedData.Unprotect(bytes, Entropy, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(bytes);
        }
        catch { return null; }
    }

    public static void Delete(string path) { try { if (File.Exists(path)) File.Delete(path); } catch { /* ignore */ } }
}
