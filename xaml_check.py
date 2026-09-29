#!/usr/bin/env python3
"""Static sanity checks for the WPF project that the compiler cannot do:
 - every StaticResource/DynamicResource key is defined somewhere
 - Light and Dark themes define identical key sets
 - every {Binding Path} root exists as a property/command on some view model
 - Run elements bound without Mode=OneWay (common runtime error source)
"""
import re, sys, pathlib

root = pathlib.Path(__file__).resolve().parents[1] / "src" / "Remindly.App"
xaml_files = list(root.rglob("*.xaml"))
cs_files = list(root.rglob("*.cs")) + list((root.parent / "Remindly.Core").rglob("*.cs"))

defined = set()
for f in xaml_files:
    text = f.read_text(encoding="utf-8")
    defined |= set(re.findall(r'x:Key="([^"]+)"', text))
# implicit type-keyed styles referenced as {StaticResource {x:Type CheckBox}}
defined |= {"{x:Type CheckBox}", "{x:Type ProgressBar}", "{x:Type ToolTip}", "{x:Type ComboBox}"}

problems = 0
for f in xaml_files:
    text = f.read_text(encoding="utf-8")
    for kind, key in re.findall(r'\{(StaticResource|DynamicResource)\s+([^}]+)\}', text):
        key = key.strip()
        if key.startswith("{x:Type"):
            continue
        if key not in defined:
            print(f"[resource] {f.name}: {kind} '{key}' is not defined anywhere")
            problems += 1

# Theme parity
light = set(re.findall(r'x:Key="([^"]+)"', (root / "Themes" / "Light.xaml").read_text()))
dark = set(re.findall(r'x:Key="([^"]+)"', (root / "Themes" / "Dark.xaml").read_text()))
for k in sorted(light ^ dark):
    print(f"[theme] key only in one theme: {k}")
    problems += 1

# Binding roots vs view-model members
members = set()
for f in cs_files:
    text = f.read_text(encoding="utf-8")
    members |= set(re.findall(r'public\s+[\w<>?,\[\] ()]+\s+(\w+)\s*(?:\{|=>|;)', text))            # properties
    for fld in re.findall(r'\[ObservableProperty\][^;]*?\s_(\w+)\s*(?:=|;)', text, flags=re.S):
        members.add(fld[0].upper() + fld[1:])
    for m in re.findall(r'\[RelayCommand[^\]]*\]\s*(?:private|public|internal)?\s*(?:async\s+)?[\w<>?]+\s+(\w+)\s*\(', text):
        name = m[:-5] if m.endswith("Async") else m
        members.add(name + "Command")
    # records / tuple names are rare; also add record positional params
    for rec in re.findall(r'record\s+\w+\s*\(([^)]*)\)', text):
        for part in rec.split(","):
            toks = part.strip().split()
            if len(toks) >= 2: members.add(toks[-1])
# CollectionViewGroup members used in group headers
members |= {"Name", "ItemCount", "Items", "Count", "PlacementTarget", "DataContext", "WindowState", "Foreground", "IsExpanded", "SelectedItem", "IsMouseOver"}

for f in xaml_files:
    text = f.read_text(encoding="utf-8")
    for b in re.findall(r'\{Binding\s+([^}]*)\}', text):
        b = b.strip()
        if not b or b.startswith(("RelativeSource", "Path=", "Converter", "Mode", "ElementName")):
            m = re.search(r'Path=([\w.]+)', b)
            path = m.group(1) if m else None
        else:
            path = b.split(",")[0].strip()
        if not path:
            continue
        roots = path.split(".")
        for seg in roots:
            seg = re.sub(r'\[.*\]', '', seg)
            if seg and seg not in members:
                print(f"[binding] {f.name}: '{path}' – segment '{seg}' not found on any view model")
                problems += 1
                break

# Run bindings must be OneWay
for f in xaml_files:
    text = f.read_text(encoding="utf-8")
    for run in re.findall(r'<Run\s+Text="\{Binding[^}]*\}"[^>]*/>', text):
        if "Mode=OneWay" not in run:
            print(f"[run] {f.name}: Run binding without Mode=OneWay: {run[:80]}")
            problems += 1

# duplicate attribute + property-element (e.g. Style attr and <X.Style>) – real XML parse
import xml.etree.ElementTree as ET
for f in xaml_files:
    tree = ET.parse(f)
    for el in tree.iter():
        tag = el.tag.split('}')[-1]
        for child in el:
            ctag = child.tag.split('}')[-1]
            if ctag.startswith(tag + ".") and ctag[len(tag)+1:] in el.attrib:
                print(f"[dup] {f.name}: <{tag}> sets '{ctag[len(tag)+1:]}' twice")
                problems += 1

# ── Setter.Property must be a DependencyProperty of the (ambient) target type ──────────────────────
# WPF only reports this at RUN time, on the first lookup of the style ("Set property
# 'System.Windows.Setter.Property' threw an exception" → every later StaticResource to that key fails
# with "Provide value on 'System.Windows.StaticResourceExtension' threw an exception").  The 1.0.0
# release shipped exactly that bug (Window.WindowStartupLocation is a plain CLR property) and every
# dialog window was unusable.  wpf-dps.json is reflected from the .NET 8 WPF reference assemblies by
# build/DpDump (own + inherited + attached DependencyProperty names per public type).
import json
dp_table = json.loads((pathlib.Path(__file__).resolve().parent / "wpf-dps.json").read_text())
PRES = "{http://schemas.microsoft.com/winfx/2006/xaml/presentation}"

def type_name(raw):
    raw = raw.strip()
    m = re.match(r'\{x:Type\s+([\w:]+)\}', raw)
    if m: raw = m.group(1)
    return raw.split(":")[-1]

def has_dp(tname, prop):
    dps = dp_table.get(tname)
    return None if dps is None else (prop in dps)

def check_setter(setter, target_type, where, fname):
    global problems
    prop = setter.get("Property")
    if not prop:
        return
    if "." in prop:                                   # qualified: Owner.Prop (attached or explicit)
        owner, p = prop.rsplit(".", 1)
        owner = owner.split(":")[-1]
        ok = has_dp(owner, p)
        if ok is False:
            print(f"[setter] {fname}: {where}: '{prop}' – {owner} has no DependencyProperty '{p}'")
            problems += 1
        return
    if target_type is None:
        print(f"[setter] {fname}: {where}: unqualified Setter Property='{prop}' with no TargetType (WPF cannot resolve it)")
        problems += 1
        return
    ok = has_dp(target_type, prop)
    if ok is False:
        print(f"[setter] {fname}: {where}: '{prop}' is NOT a DependencyProperty of {target_type} (plain CLR property? set it on the element instead)")
        problems += 1

def named_element_types(template_el):
    """x:Name → element type inside a ControlTemplate/DataTemplate (for TargetName setters)."""
    names = {}
    for el in template_el.iter():
        n = el.get("{http://schemas.microsoft.com/winfx/2006/xaml}Name") or el.get("Name")
        if n and not el.tag.split('}')[-1].startswith(("Setter", "Trigger", "Condition")):
            names[n] = el.tag.split('}')[-1]
    return names

TEMPLATES = ("ControlTemplate", "DataTemplate", "ItemsPanelTemplate", "HierarchicalDataTemplate")

def walk_setters(el, scope, fname):
    """scope = ('style', target_type, label) | ('template', target_type, names, label) | None.
    A Setter is resolved against its NEAREST enclosing Style or template – exactly the ambient
    TargetType WPF's DependencyPropertyConverter uses."""
    tag = el.tag.split('}')[-1]
    if tag == "Style":
        tt = type_name(el.get("TargetType") or "") or None
        key = el.get("{http://schemas.microsoft.com/winfx/2006/xaml}Key") or f"implicit {tt}"
        if tt and tt not in dp_table and ":" not in (el.get("TargetType") or ""):
            print(f"[setter] {fname}: Style {key}: unknown TargetType '{tt}'")
            globals()["problems"] += 1
        scope = ("style", tt, f"Style {key}")
    elif tag in TEMPLATES:
        tt = type_name(el.get("TargetType") or "") or None if tag == "ControlTemplate" else None
        scope = ("template", tt, named_element_types(el), f"{tag} {tt or el.get('DataType') or ''}".strip())
    elif tag == "Setter" and scope is not None:
        tn = el.get("TargetName")
        if scope[0] == "style":
            if tn is None:
                check_setter(el, scope[1], scope[2], fname)
        else:
            _, tt, names, label = scope
            if tn is not None:
                et = names.get(tn)
                if et is None:
                    print(f"[setter] {fname}: {label}: Setter TargetName='{tn}' names no element")
                    globals()["problems"] += 1
                elif et in dp_table:
                    check_setter(el, et, f"{label} → {tn}", fname)
            elif tt is not None:                      # untargeted setter in a ControlTemplate trigger
                check_setter(el, tt, label, fname)
    for ch in el:
        walk_setters(ch, scope, fname)

for f in xaml_files:
    walk_setters(ET.parse(f).getroot(), None, f.name)

# ── Trigger.Property and TemplateBinding must also name DependencyProperties ──────────────────────
def walk_triggers(el, scope, fname):
    global problems
    tag = el.tag.split('}')[-1]
    if tag == "Style":
        scope = ("style", type_name(el.get("TargetType") or "") or None, el.get("{http://schemas.microsoft.com/winfx/2006/xaml}Key") or "implicit")
    elif tag in TEMPLATES:
        scope = ("template", type_name(el.get("TargetType") or "") or None if tag == "ControlTemplate" else None, named_element_types(el))
    elif tag in ("Trigger", "Condition") and scope is not None and el.get("Property"):
        prop = el.get("Property"); sn = el.get("SourceName")
        if "." in prop:
            owner, p = prop.rsplit(".", 1)
            if has_dp(owner.split(":")[-1], p) is False:
                print(f"[trigger] {fname}: {tag} Property='{prop}' – no such DependencyProperty"); problems += 1
        else:
            tt = scope[2].get(sn) if (scope[0] == "template" and sn) else scope[1]
            if tt is not None and has_dp(tt, prop) is False:
                print(f"[trigger] {fname}: {tag} Property='{prop}' is NOT a DependencyProperty of {tt}"); problems += 1
    if scope is not None and scope[0] == "template" and scope[1] is not None:
        for attr, val in el.attrib.items():
            m = re.match(r'\{TemplateBinding\s+([\w.]+)', val.strip())
            if m:
                prop = m.group(1)
                if "." in prop:
                    owner, p = prop.rsplit(".", 1); ok = has_dp(owner.split(":")[-1], p)
                else:
                    ok = has_dp(scope[1], prop)
                if ok is False:
                    print(f"[templatebinding] {fname}: {{TemplateBinding {prop}}} – not a DependencyProperty of {scope[1]}"); problems += 1
    for ch in el:
        walk_triggers(ch, scope, fname)

for f in xaml_files:
    walk_triggers(ET.parse(f).getroot(), None, f.name)

# ── StaticResource lexical ordering inside resource dictionaries ───────────────────────────────────
# Per the WPF docs a StaticResource inside a ResourceDictionary may only reference keys defined
# lexically BEFORE it: earlier in the same file, or in a dictionary merged earlier in App.xaml.
app_xaml = (root / "App.xaml").read_text(encoding="utf-8")
merge_order = re.findall(r'<ResourceDictionary\s+Source="([^"]+)"', app_xaml)

def merged_sources(text):
    return re.findall(r'<ResourceDictionary\s+Source="([^"]+)"', text)

def resolve_src(src, base):
    src = src.replace("pack://application:,,,/", "/")
    return (root / src.lstrip("/")) if src.startswith("/") else (base.parent / src)

available = set()
def check_dictionary(path, available):
    """Adds this dictionary's keys to `available` in lexical order, checking StaticResource refs."""
    global problems
    text = path.read_text(encoding="utf-8")
    # nested merges come first (they sit in <ResourceDictionary.MergedDictionaries> at the top)
    for src in merged_sources(text):
        p = resolve_src(src, path)
        if p.exists():
            check_dictionary(p, available)
        else:
            print(f"[order] {path.name} merges '{src}' which does not exist"); problems += 1
    # walk top-level resources in order; a key becomes available once its element CLOSES
    tree = ET.parse(path).getroot()
    for res in tree:
        if res.tag.split('}')[-1] == "ResourceDictionary.MergedDictionaries":
            continue
        blob = ET.tostring(res, encoding="unicode")
        for ref in re.findall(r'\{StaticResource\s+([^}]+)\}', blob):
            ref = ref.strip()
            if ref.startswith("{x:Type") or ref in available:
                continue
            key = res.get("{http://schemas.microsoft.com/winfx/2006/xaml}Key") or res.get("TargetType")
            print(f"[order] {path.name}: resource '{key}' references StaticResource '{ref}' before it is defined "
                  f"(forward reference – WPF resolves these only lexically; move it or use DynamicResource)")
            problems += 1
        key = res.get("{http://schemas.microsoft.com/winfx/2006/xaml}Key")
        if key:
            available.add(key)
        elif res.get("TargetType"):
            available.add("{x:Type " + type_name(res.get("TargetType")) + "}")

for src in merge_order:
    p = resolve_src(src, root / "App.xaml")
    if p.exists():
        check_dictionary(p, available)
    else:
        print(f"[order] App.xaml merges '{src}' which does not exist"); problems += 1


# ── BasedOn compatibility: a Style's TargetType must derive from (or equal) the base style's ─────────
# (run-time error otherwise: "Can only base on a Style with target type that is base type 'X'").
bases = json.loads((pathlib.Path(__file__).resolve().parent / "wpf-bases.json").read_text())
style_tt = {}
for f in xaml_files:
    for el in ET.parse(f).getroot().iter():
        if el.tag.split('}')[-1] == "Style" and el.get("{http://schemas.microsoft.com/winfx/2006/xaml}Key") and el.get("TargetType"):
            style_tt[el.get("{http://schemas.microsoft.com/winfx/2006/xaml}Key")] = type_name(el.get("TargetType"))

def derives(child, parent):
    t = child
    while t is not None:
        if t == parent: return True
        t = bases.get(t)
    return False

for f in xaml_files:
    for el in ET.parse(f).getroot().iter():
        if el.tag.split('}')[-1] != "Style" or not el.get("BasedOn") or not el.get("TargetType"):
            continue
        m = re.match(r'\{StaticResource\s+([^}]+)\}', el.get("BasedOn").strip())
        if not m: continue
        base_key = m.group(1).strip()
        base_tt = style_tt.get(base_key) or (type_name(base_key) if base_key.startswith("{x:Type") else None)
        mine = type_name(el.get("TargetType"))
        if base_tt and mine in bases and base_tt in bases and not derives(mine, base_tt):
            key = el.get("{http://schemas.microsoft.com/winfx/2006/xaml}Key") or f"implicit {mine}"
            print(f"[basedon] {f.name}: Style {key} (TargetType {mine}) is BasedOn '{base_key}' whose TargetType {base_tt} is not a base of {mine}")
            problems += 1

print(f"\n{len(xaml_files)} XAML files, {len(defined)} resource keys, {len(members)} VM members, {len(dp_table)} WPF types checked. Problems: {problems}")
sys.exit(1 if problems else 0)
