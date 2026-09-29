using System.Text.Encodings.Web;
using Remindly.Core.Models;

namespace Remindly.Core.Json;

/// <summary>
/// One serializer configuration for everything that must match Gson byte-for-byte in SHAPE
/// (not in key order — Firestore/Gson never depend on order):
///   camelCase keys · enums by NAME · nulls written (Android pushes with serializeNulls) ·
///   unknown keys preserved through [JsonExtensionData].
/// </summary>
public static class Wire
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
        NumberHandling = JsonNumberHandling.AllowReadingFromString,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        Converters = { new JsonStringEnumConverter(), new LenientBoolConverter(), new LenientNullableEnumFactory() },
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
    };

    public static readonly JsonSerializerOptions Pretty = new(Options) { WriteIndented = true };

    public static string ToJson<T>(T value) => JsonSerializer.Serialize(value, Options);
    public static T? FromJson<T>(string json) => JsonSerializer.Deserialize<T>(json, Options);

    /// <summary>Parse a Gson array file (items.json etc.), skipping records that fail to parse.</summary>
    public static List<T> ParseArrayLenient<T>(string json, Func<T, T>? heal = null)
    {
        var out_ = new List<T>();
        if (string.IsNullOrWhiteSpace(json)) return out_;
        using var doc = JsonDocument.Parse(json, new JsonDocumentOptions { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip });
        if (doc.RootElement.ValueKind != JsonValueKind.Array) return out_;
        foreach (var el in doc.RootElement.EnumerateArray())
        {
            try
            {
                var v = el.Deserialize<T>(Options);
                if (v is null) continue;
                out_.Add(heal != null ? heal(v) : v);
            }
            catch { /* one bad record must never hide the rest */ }
        }
        return out_;
    }
}

/// <summary>Gson tolerates 0/1 and "true"/"false" for booleans in hand-edited backups; so do we.</summary>
internal sealed class LenientBoolConverter : JsonConverter<bool>
{
    public override bool Read(ref Utf8JsonReader r, Type t, JsonSerializerOptions o) => r.TokenType switch
    {
        JsonTokenType.True => true,
        JsonTokenType.False => false,
        JsonTokenType.Number => r.GetDouble() != 0,
        JsonTokenType.String => bool.TryParse(r.GetString(), out var b) && b,
        _ => false,
    };
    public override void Write(Utf8JsonWriter w, bool v, JsonSerializerOptions o) => w.WriteBooleanValue(v);
}

/// <summary>An unknown enum NAME from a newer Android build must not crash the parse; nullable enums fall back to null.</summary>
internal sealed class LenientNullableEnumFactory : JsonConverterFactory
{
    public override bool CanConvert(Type t) => Nullable.GetUnderlyingType(t)?.IsEnum == true;
    public override JsonConverter CreateConverter(Type t, JsonSerializerOptions o)
    {
        var inner = Nullable.GetUnderlyingType(t)!;
        return (JsonConverter)Activator.CreateInstance(typeof(Conv<>).MakeGenericType(inner))!;
    }
    private sealed class Conv<TEnum> : JsonConverter<TEnum?> where TEnum : struct, Enum
    {
        public override TEnum? Read(ref Utf8JsonReader r, Type t, JsonSerializerOptions o)
        {
            if (r.TokenType == JsonTokenType.Null) return null;
            if (r.TokenType == JsonTokenType.String && Enum.TryParse<TEnum>(r.GetString(), true, out var e)) return e;
            if (r.TokenType == JsonTokenType.Number && r.TryGetInt32(out var i) && Enum.IsDefined(typeof(TEnum), i)) return (TEnum)Enum.ToObject(typeof(TEnum), i);
            return null;
        }
        public override void Write(Utf8JsonWriter w, TEnum? v, JsonSerializerOptions o)
        {
            if (v is null) w.WriteNullValue(); else w.WriteStringValue(v.Value.ToString());
        }
    }
}

/// <summary>Firestore document envelope used by every synced record.</summary>
public sealed record WireDoc(string Json, long UpdatedAt, int SchemaVer)
{
    public static WireDoc Of<T>(T record, long updatedAt) => new(Wire.ToJson(record), updatedAt, Constants.SyncSchema);
}
