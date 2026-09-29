using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using Remindly.Core;

namespace Remindly.App.Services;

/// <summary>
/// Small builders for the code-built screens. Lists are redrawn from AppState on every change, so
/// the screens are plain functions of the data — no bindings to get out of sync.
/// </summary>
public static class Ui
{
    public static Brush Res(string key) => (Brush)Application.Current.Resources[key];

    public static Brush Hex(string hex) => (SolidColorBrush)new BrushConverter().ConvertFromString(hex)!;

    public static TextBlock Text(string text, double size = 14, FontWeight? weight = null, Brush? color = null, bool wrap = true) => new()
    {
        Text = text,
        FontSize = size,
        FontWeight = weight ?? FontWeights.Normal,
        Foreground = color ?? Res("InkBrush"),
        TextWrapping = wrap ? TextWrapping.Wrap : TextWrapping.NoWrap,
        TextTrimming = wrap ? TextTrimming.None : TextTrimming.CharacterEllipsis,
        VerticalAlignment = VerticalAlignment.Center,
    };

    public static TextBlock Sub(string text) => Text(text, 12.5, color: Res("InkSubtleBrush"));

    public static TextBlock Header(string text) => new() { Text = text, Style = (Style)Application.Current.Resources["GroupHeader"] };

    public static TextBlock H1(string text) => new() { Text = text, Style = (Style)Application.Current.Resources["H1"] };

    public static TextBlock H2(string text) => new() { Text = text, Style = (Style)Application.Current.Resources["H2"] };

    public static Button Btn(string content, Action onClick, string? tip = null, string style = "")
    {
        var b = new Button { Content = content, ToolTip = tip };
        if (style.Length > 0) b.Style = (Style)Application.Current.Resources[style];
        b.Click += (_, _) => onClick();
        return b;
    }

    public static Button Primary(string content, Action onClick, string? tip = null) => Btn(content, onClick, tip, "Primary");

    public static Button IconBtn(string glyph, Action onClick, string tip) => Btn(glyph, onClick, tip, "Icon");

    public static RadioButton Chip(string content, string group, bool isChecked, Action onChecked)
    {
        var r = new RadioButton { Content = content, GroupName = group, IsChecked = isChecked, Style = (Style)Application.Current.Resources["Chip"] };
        r.Checked += (_, _) => onChecked();
        return r;
    }

    public static StackPanel Row(params UIElement[] children)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        foreach (var c in children) p.Children.Add(c);
        return p;
    }

    public static StackPanel Stack(params UIElement[] children)
    {
        var p = new StackPanel();
        foreach (var c in children) p.Children.Add(c);
        return p;
    }

    public static Border Card(UIElement child, Action? onDoubleClick = null)
    {
        var b = new Border { Child = child, Style = (Style)Application.Current.Resources["Card"] };
        if (onDoubleClick != null)
            b.MouseLeftButtonDown += (_, e) => { if (e.ClickCount == 2) { onDoubleClick(); e.Handled = true; } };
        return b;
    }

    /// <summary>A small rounded tag ("Urgent", "Weekly · Mon").</summary>
    public static Border Tag(string text, Brush fg, Brush bg) => new()
    {
        Background = bg,
        CornerRadius = new CornerRadius(6),
        Padding = new Thickness(6, 1, 6, 1),
        Margin = new Thickness(0, 0, 6, 0),
        VerticalAlignment = VerticalAlignment.Center,
        Child = new TextBlock { Text = text, FontSize = 11.5, Foreground = fg, FontWeight = FontWeights.SemiBold },
    };

    public static Border? PriorityTag(Priority? p) => p switch
    {
        Priority.URGENT => Tag("Urgent", Res("DangerBrush"), Res("DangerSoftBrush")),
        Priority.HIGH => Tag("High", Res("AmberBrush"), Res("AmberSoftBrush")),
        Priority.LOW => Tag("Low", Res("InkSubtleBrush"), Res("SurfaceBrush")),
        _ => null,
    };

    /// <summary>Grid with star/auto columns: "*,Auto,Auto".</summary>
    public static Grid Columns(string spec, params UIElement[] children)
    {
        var g = new Grid();
        foreach (var part in spec.Split(','))
        {
            var t = part.Trim();
            g.ColumnDefinitions.Add(new ColumnDefinition
            {
                Width = t == "Auto" ? GridLength.Auto : t.EndsWith('*') ? new GridLength(t.Length == 1 ? 1 : double.Parse(t[..^1], System.Globalization.CultureInfo.InvariantCulture), GridUnitType.Star) : new GridLength(double.Parse(t, System.Globalization.CultureInfo.InvariantCulture)),
            });
        }
        for (int i = 0; i < children.Length; i++)
        {
            Grid.SetColumn(children[i], Math.Min(i, g.ColumnDefinitions.Count - 1));
            g.Children.Add(children[i]);
        }
        return g;
    }

    public static CheckBox Check(bool isChecked, Action<bool> onChange, string? tip = null)
    {
        var c = new CheckBox { IsChecked = isChecked, ToolTip = tip, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 10, 0) };
        c.LayoutTransform = new ScaleTransform(1.25, 1.25);
        c.Click += (_, _) => onChange(c.IsChecked == true);
        return c;
    }

    public static CheckBox Setting(string label, bool isChecked, Action<bool> onChange, string? hint = null)
    {
        var c = new CheckBox { IsChecked = isChecked, Margin = new Thickness(0, 4, 0, 4) };
        c.Content = hint == null ? Text(label) : Stack(Text(label), Sub(hint));
        c.Click += (_, _) => onChange(c.IsChecked == true);
        return c;
    }

    public static TextBox Input(string text = "", string? placeholder = null, Action? onEnter = null, double width = double.NaN)
    {
        var t = new TextBox { Text = text, Width = width };
        if (placeholder != null) SetPlaceholder(t, placeholder);
        if (onEnter != null) t.KeyDown += (_, e) => { if (e.Key == Key.Enter) { onEnter(); e.Handled = true; } };
        return t;
    }

    /// <summary>Grey hint text shown while the box is empty.</summary>
    public static void SetPlaceholder(TextBox t, string placeholder)
    {
        var hint = new VisualBrush
        {
            Stretch = Stretch.None,
            AlignmentX = AlignmentX.Left,
            AlignmentY = AlignmentY.Center,
            Visual = new TextBlock { Text = placeholder, Foreground = Res("InkHintBrush"), Margin = new Thickness(8, 0, 0, 0), FontSize = 14 },
        };
        void Update() => t.Background = string.IsNullOrEmpty(t.Text) ? hint : Brushes.White;
        t.TextChanged += (_, _) => Update();
        Update();
    }

    /// <summary>Opens a context menu under a button (the ⋮ menus).</summary>
    public static Button MenuButton(string glyph, string tip, params (string Header, Action? Click)[] entries)
    {
        var b = IconBtn(glyph, () => { }, tip);
        b.Click += (_, _) =>
        {
            var m = new ContextMenu { PlacementTarget = b, Placement = PlacementMode.Bottom };
            foreach (var (h, a) in entries)
            {
                if (h == "-") { m.Items.Add(new Separator()); continue; }
                var mi = new MenuItem { Header = h, IsEnabled = a != null };
                if (a != null) mi.Click += (_, _) => a();
                m.Items.Add(mi);
            }
            m.IsOpen = true;
        };
        return b;
    }

    public static ScrollViewer Scroll(UIElement content) => new()
    {
        Content = content,
        VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
        HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
        Padding = new Thickness(0, 0, 8, 0),
    };

    public static Border EmptyState(string title, string hint) => new()
    {
        Padding = new Thickness(20, 40, 20, 40),
        Child = Stack(
            new TextBlock { Text = title, FontSize = 16, FontWeight = FontWeights.SemiBold, HorizontalAlignment = HorizontalAlignment.Center, Foreground = Res("InkSubtleBrush") },
            new TextBlock { Text = hint, FontSize = 13, HorizontalAlignment = HorizontalAlignment.Center, Foreground = Res("InkHintBrush"), Margin = new Thickness(0, 6, 0, 0), TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center }),
    };

    public static bool Confirm(string text, string title = "Remindly")
    {
        if (App.IsSmokeTest) return true;
        return MessageBox.Show(Application.Current.MainWindow, text, title, MessageBoxButton.OKCancel, MessageBoxImage.Question) == MessageBoxResult.OK;
    }

    public static void Info(string text, string title = "Remindly")
    {
        if (App.IsSmokeTest) return;
        MessageBox.Show(Application.Current.MainWindow, text, title, MessageBoxButton.OK, MessageBoxImage.Information);
    }

    /// <summary>Meta line for a card: "Today · 09:00 AM · Weekly · Mon · notes".</summary>
    public static string DueText(long? dueAt, bool hasTime, long now)
    {
        if (dueAt is not long d) return "";
        var day = ItemRules.DayLabel(Clock.LocalDate(d), Clock.LocalDate(now));
        return hasTime ? $"{day} · {Clock.FormatTime(d)}" : day;
    }
}
