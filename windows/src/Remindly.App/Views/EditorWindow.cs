using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Remindly.App.Services;

namespace Remindly.App.Views;

/// <summary>Shared chrome for the editors: a scrolling form, Save / Cancel, Esc / Ctrl+Enter.</summary>
public abstract class EditorWindow : Window
{
    protected readonly StackPanel Form = new() { Margin = new Thickness(20, 12, 20, 12) };
    private readonly StackPanel _buttons = new() { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(20, 8, 14, 14) };
    protected readonly TextBlock Warning = new() { Foreground = Brushes.DarkGoldenrod, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(20, 0, 20, 0), Visibility = Visibility.Collapsed };

    protected EditorWindow(string title, double width = 520)
    {
        Title = title;
        Width = width;
        SizeToContent = SizeToContent.Height;
        MaxHeight = SystemParameters.WorkArea.Height - 40;
        ResizeMode = ResizeMode.CanResizeWithGrip;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        ShowInTaskbar = false;
        FontFamily = new FontFamily("Segoe UI");
        FontSize = 14;
        Background = Ui.Res("CardBrush");
        if (Application.Current.MainWindow is { IsVisible: true } owner && owner != this) Owner = owner;

        var root = new DockPanel();
        var head = new Border { Background = Ui.Res("AccentSoftBrush"), Padding = new Thickness(20, 12, 20, 12), Child = Ui.Text(title, 17, FontWeights.SemiBold, Ui.Res("AccentInkBrush")) };
        DockPanel.SetDock(head, Dock.Top);
        root.Children.Add(head);
        DockPanel.SetDock(_buttons, Dock.Bottom);
        root.Children.Add(_buttons);
        DockPanel.SetDock(Warning, Dock.Bottom);
        root.Children.Add(Warning);
        root.Children.Add(Ui.Scroll(Form));
        Content = root;

        KeyDown += (_, e) =>
        {
            if (e.Key == System.Windows.Input.Key.Escape) { Close(); e.Handled = true; }
            if (e.Key == System.Windows.Input.Key.Enter && System.Windows.Input.Keyboard.Modifiers == System.Windows.Input.ModifierKeys.Control) { TrySave(); e.Handled = true; }
        };
    }

    protected void AddButtons(string saveText = "Save", params Button[] extra)
    {
        foreach (var b in extra) _buttons.Children.Add(b);
        _buttons.Children.Add(Ui.Btn("Cancel", Close));
        var save = Ui.Primary(saveText, TrySave);
        save.IsDefault = false;
        save.Margin = new Thickness(0);
        _buttons.Children.Add(save);
    }

    private void TrySave()
    {
        if (Save()) Close();
    }

    /// <summary>Validate and store; false keeps the window open.</summary>
    protected abstract bool Save();

    protected void Field(string label, UIElement control)
    {
        Form.Children.Add(new TextBlock { Text = label, Style = (Style)Application.Current.Resources["FieldLabel"] });
        Form.Children.Add(control);
    }

    protected void Section(string title) => Form.Children.Add(Ui.H2(title));

    protected static ComboBox Combo(IEnumerable<string> items, string? selected, bool editable = false)
    {
        var c = new ComboBox { IsEditable = editable, IsTextSearchEnabled = true };
        foreach (var i in items) c.Items.Add(i);
        if (editable) c.Text = selected ?? "";
        else c.SelectedItem = selected != null && c.Items.Contains(selected) ? selected : (c.Items.Count > 0 ? c.Items[0] : null);
        return c;
    }

    protected static Grid TwoColumns(UIElement a, UIElement b)
    {
        var g = Ui.Columns("*,12,*", a, new Border(), b);
        return g;
    }

    protected static StackPanel Labeled(string label, UIElement c) =>
        Ui.Stack(new TextBlock { Text = label, Style = (Style)Application.Current.Resources["FieldLabel"] }, c);

    protected void ShowWarning(string? text)
    {
        Warning.Text = text ?? "";
        Warning.Visibility = string.IsNullOrEmpty(text) ? Visibility.Collapsed : Visibility.Visible;
    }

    public void Open()
    {
        if (App.IsSmokeTest) { Show(); return; }
        ShowDialog();
    }
}
