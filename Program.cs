// Dumps every public DependencyObject-derived WPF type with its DependencyProperty names
// (own + inherited + attached-property owners) to JSON, for build/xaml_check.py.
using System.Reflection;
using System.Text.Json;

var refDirs = args.Take(args.Length - 1).ToArray();
var outFile = args[^1];
var paths = refDirs.SelectMany(d => Directory.GetFiles(d, "*.dll")).ToArray();
var resolver = new PathAssemblyResolver(paths);
using var mlc = new MetadataLoadContext(resolver, "System.Runtime");

var result = new SortedDictionary<string, SortedSet<string>>();
var baseOf = new Dictionary<string, string?>();
foreach (var asmName in new[] { "WindowsBase", "PresentationCore", "PresentationFramework" })
{
    var asm = mlc.LoadFromAssemblyName(asmName);
    foreach (var t in asm.GetExportedTypes())
    {
        if (!t.IsClass) continue;
        var dps = new SortedSet<string>();
        foreach (var f in t.GetFields(BindingFlags.Public | BindingFlags.Static | BindingFlags.DeclaredOnly))
            if (f.FieldType.Name == "DependencyProperty" && f.Name.EndsWith("Property"))
                dps.Add(f.Name[..^"Property".Length]);
        // also DPs exposed via static public properties (rare) – skip
        if (dps.Count > 0 || IsDependencyObject(t))
        {
            result[t.Name] = dps;
            baseOf[t.Name] = t.BaseType?.Name;
        }
    }
}
// inherit
foreach (var name in result.Keys.ToList())
{
    var b = baseOf.GetValueOrDefault(name);
    while (b != null && result.TryGetValue(b, out var bd))
    {
        result[name].UnionWith(bd);
        b = baseOf.GetValueOrDefault(b);
    }
}
File.WriteAllText(outFile, JsonSerializer.Serialize(result, new JsonSerializerOptions { WriteIndented = false }));
// base-type map (for BasedOn / TargetType compatibility checks)
var bases = new SortedDictionary<string, string?>(baseOf.Where(kv => result.ContainsKey(kv.Key)).ToDictionary(kv => kv.Key, kv => kv.Value));
File.WriteAllText(Path.Combine(Path.GetDirectoryName(Path.GetFullPath(outFile))!, "wpf-bases.json"), JsonSerializer.Serialize(bases));
Console.WriteLine($"{result.Count} types written to {outFile} (+ wpf-bases.json)");

static bool IsDependencyObject(Type t)
{
    for (var b = t; b != null; b = b.BaseType)
        if (b.Name == "DependencyObject") return true;
    return false;
}
