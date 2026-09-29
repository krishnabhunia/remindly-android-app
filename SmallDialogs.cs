using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace Remindly.App.Views;

/// <summary>Code-built small dialogs (PIN, text prompt, choose from list) — no XAML needed.</summary>
internal static class DialogChrome
{
    public static Window Make(string title, UIElement body, out Button ok, out Button cancel, double width = 380)
    {
        var w = new Window
        {
            Title = title, Width = width, SizeToContent = SizeToContent.Height, ResizeMode = ResizeMode.NoResize,
            Style = (Style)Application.Current.Resources["Window.Dialog"],
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Owner = Application.Current.Windows.OfType<Window>().FirstOrDefault(x => x.IsActive) ?? Application.Current.MainWindow,
            Icon = Application.Current.MainWindow?.Icon,
        };
        var root = new StackPanel { Margin = new Thickness(22) };
        root.Children.Add(body);
        var row = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
        cancel = new Button { Content = "Cancel", Style = (Style)Application.Current.Resources["Button.Secondary"], MinWidth = 90, Margin = new Thickness(0, 0, 8, 0), IsCancel = true };
        ok = new Button { Content = "OK", Style = (Style)Application.Current.Resources["Button.Primary"], MinWidth = 90, IsDefault = true };
        row.Children.Add(cancel); row.Children.Add(ok);
        root.Children.Add(row);
        w.Content = root;
        var c = cancel; c.Click += (_, _) => { w.DialogResult = false; w.Close(); };
        return w;
    }
}

public static class PinDialog
{
    /// <summary>Asks for a PIN (digits). With confirm=true asks twice. Returns null on cancel.</summary>
    public static string? Ask(string prompt, bool confirm = false)
    {
        var body = new StackPanel();
        body.Children.Add(new TextBlock { Text = prompt, Style = (Style)Application.Current.Resources["Text.Body"], Margin = new Thickness(0, 0, 0, 10) });
        var p1 = new PasswordBox { MaxLength = 8, FontSize = 18, Padding = new Thickness(10, 6, 10, 6), MinHeight = 38 };
        body.Children.Add(p1);
        PasswordBox? p2 = null;
        if (confirm)
        {
            body.Children.Add(new TextBlock { Text = "Repeat the PIN", Style = (Style)Application.Current.Resources["Text.Caption"], Margin = new Thickness(0, 10, 0, 4) });
            p2 = new PasswordBox { MaxLength = 8, FontSize = 18, Padding = new Thickness(10, 6, 10, 6), MinHeight = 38 };
            body.Children.Add(p2);
        }
        var err = new TextBlock { Foreground = Brushes.IndianRed, Margin = new Thickness(0, 8, 0, 0), Visibility = Visibility.Collapsed };
        body.Children.Add(err);
        var w = DialogChrome.Make("Personal PIN", body, out var ok, out _, 340);
        string? result = null;
        ok.Click += (_, _) =>
        {
            var v = p1.Password.Trim();
            if (v.Length < 4 || !v.All(char.IsDigit)) { err.Text = "4–8 digits."; err.Visibility = Visibility.Visible; return; }
            if (p2 != null && p2.Password.Trim() != v) { err.Text = "The two PINs differ."; err.Visibility = Visibility.Visible; return; }
            result = v; w.DialogResult = true; w.Close();
        };
        w.Loaded += (_, _) => p1.Focus();
        w.ShowDialog();
        return result;
    }
}

public static class TextPrompt
{
    public static string? Ask(string title, string label, string initial)
    {
        var body = new StackPanel();
        body.Children.Add(new TextBlock { Text = label.ToUpperInvariant(), Style = (Style)Application.Current.Resources["Text.Label"], Margin = new Thickness(0, 0, 0, 6) });
        var tb = new TextBox { Text = initial, Style = (Style)Application.Current.Resources["TextBox.Base"] };
        body.Children.Add(tb);
        var w = DialogChrome.Make(title, body, out var ok, out _);
        string? result = null;
        ok.Click += (_, _) => { result = tb.Text; w.DialogResult = true; w.Close(); };
        w.Loaded += (_, _) => { tb.Focus(); tb.SelectAll(); };
        w.ShowDialog();
        return result;
    }

    public static string? Choose(string title, string label, List<string> options)
    {
        var body = new StackPanel();
        body.Children.Add(new TextBlock { Text = label.ToUpperInvariant(), Style = (Style)Application.Current.Resources["Text.Label"], Margin = new Thickness(0, 0, 0, 6) });
        var cb = new ComboBox { ItemsSource = options, SelectedIndex = options.Count > 0 ? 0 : -1 };
        body.Children.Add(cb);
        var w = DialogChrome.Make(title, body, out var ok, out _);
        string? result = null;
        ok.Click += (_, _) => { result = cb.SelectedItem as string; w.DialogResult = true; w.Close(); };
        w.ShowDialog();
        return result;
    }
}
