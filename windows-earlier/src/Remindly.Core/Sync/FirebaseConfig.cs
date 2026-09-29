using System.Reflection;
using System.Text.Json.Nodes;

namespace Remindly.Core.Sync;

/// <summary>
/// Firebase project identity, read from the embedded google-services.json (the same file that
/// ships inside the Android APK — a Firebase Web API key is a public project identifier, not a
/// secret; access is governed by Firestore security rules + the user's ID token).
/// </summary>
public sealed class FirebaseConfig
{
    public string ProjectId { get; init; } = "";
    public string ProjectNumber { get; init; } = "";
    public string ApiKey { get; init; } = "";
    /// <summary>Web client (oauth_client type 3) — informational; desktop sign-in uses its own client.</summary>
    public string? WebClientId { get; init; }
    public string FirestoreDatabase => $"projects/{ProjectId}/databases/(default)";
    public string DocumentsRoot => FirestoreDatabase + "/documents";

    private static FirebaseConfig? _cached;

    public static FirebaseConfig Load()
    {
        if (_cached != null) return _cached;
        var asm = Assembly.GetExecutingAssembly();
        var name = asm.GetManifestResourceNames().FirstOrDefault(n => n.EndsWith("google-services.json", StringComparison.OrdinalIgnoreCase))
                   ?? throw new InvalidOperationException("google-services.json is not embedded");
        using var s = asm.GetManifestResourceStream(name)!;
        using var r = new StreamReader(s);
        return _cached = Parse(r.ReadToEnd());
    }

    public static FirebaseConfig Parse(string json)
    {
        var root = JsonNode.Parse(json)!.AsObject();
        var info = root["project_info"]!.AsObject();
        var client = root["client"]!.AsArray()[0]!.AsObject();
        var apiKey = client["api_key"]?.AsArray().FirstOrDefault()?["current_key"]?.GetValue<string>() ?? "";
        var web = client["oauth_client"]?.AsArray().FirstOrDefault(o => o?["client_type"]?.GetValue<int>() == 3)?["client_id"]?.GetValue<string>();
        return new FirebaseConfig
        {
            ProjectId = info["project_id"]!.GetValue<string>(),
            ProjectNumber = info["project_number"]?.GetValue<string>() ?? "",
            ApiKey = apiKey,
            WebClientId = web,
        };
    }
}
