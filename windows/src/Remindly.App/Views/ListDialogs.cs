using System.Windows;
using System.Windows.Controls;
using Remindly.App.Services;
using Remindly.Core;

namespace Remindly.App.Views;

/// <summary>New list / Edit list: name, icon, usual shop, shopping day (reminder at 9 AM), Private, pinned.</summary>
public sealed class ListEditor : EditorWindow
{
    private readonly ShopList? _orig;
    private readonly TextBox _name = new();
    private readonly ComboBox _icon;
    private readonly ComboBox _shop;
    private readonly CheckBox _hasDay = new() { Content = "Shopping day" };
    private readonly DatePicker _day = new() { Width = 150, Margin = new Thickness(10, 0, 0, 0) };
    private readonly CheckBox _private = new() { Content = "Private — every item in this list is Personal" };
    private readonly CheckBox _pinned = new() { Content = "Pin to the top" };

    public static void OpenFor(ShopList? l) => new ListEditor(l).Open();

    private ListEditor(ShopList? l) : base(l == null ? "New list" : "Edit list", 460)
    {
        _orig = l;
        var state = AppState.Current;
        _name.Text = l?.Name ?? "";
        Field("Name", _name);
        _icon = Combo(ShopLists.SuggestedIcons, l?.Icon ?? "🛒", editable: true);
        _icon.FontSize = 18;
        Field("Icon (pick one or type any emoji)", _icon);
        _shop = Combo(new[] { "(none)" }.Concat(state.Shops.Select(s => s.Name)), state.Shops.FirstOrDefault(s => s.Id == l?.UsualShopId)?.Name ?? "(none)");
        Field("Usual shop (new items get it)", _shop);
        _hasDay.IsChecked = l?.ShoppingDay != null;
        _day.SelectedDate = l?.ShoppingDay is long d ? Clock.LocalDate(d) : DateTime.Today.AddDays(1);
        _day.IsEnabled = _hasDay.IsChecked == true;
        _hasDay.Click += (_, _) => _day.IsEnabled = _hasDay.IsChecked == true;
        Field("A reminder at 9 AM that day", Ui.Row(_hasDay, _day));
        _private.IsChecked = l?.Personal == true;
        _pinned.IsChecked = l?.Pinned == true;
        _private.Margin = new Thickness(0, 12, 0, 0);
        Form.Children.Add(_private);
        Form.Children.Add(_pinned);
        AddButtons(l == null ? "Create" : "Save");
        Loaded += (_, _) => { _name.Focus(); _name.SelectAll(); };
    }

    protected override bool Save()
    {
        var state = AppState.Current;
        var name = _name.Text.Trim();
        if (name.Length == 0) { ShowWarning("Give the list a name."); return false; }
        var shop = state.Shops.FirstOrDefault(s => s.Name == _shop.SelectedItem as string);
        long? day = _hasDay.IsChecked == true && _day.SelectedDate is DateTime dt ? Clock.FromLocal(dt.Date.AddHours(12)) : null;
        var icon = string.IsNullOrWhiteSpace(_icon.Text) ? null : _icon.Text.Trim();
        if (_orig == null)
        {
            var l = state.CreateList(new ShopList { Name = name, Icon = icon, UsualShopId = shop?.Id, ShoppingDay = day, Personal = _private.IsChecked == true, Pinned = _pinned.IsChecked == true });
            App.Current.Main.Snack($"List \"{l.Name}\" created");
            App.Current.Main.Buy.OpenList(l.Id);
        }
        else
        {
            state.UpdateList(_orig with { Name = name, Icon = icon, UsualShopId = shop?.Id, ShoppingDay = day, Personal = _private.IsChecked == true, Pinned = _pinned.IsChecked == true });
            App.Current.Main.Snack("List saved");
        }
        return true;
    }
}

/// <summary>Delete a list: keep its items in Unsorted (default), move them to another list, or delete them too. Undo-able.</summary>
public sealed class DeleteListWindow : EditorWindow
{
    private readonly ShopList _list;
    private readonly Action _after;
    private readonly RadioButton _keep = new() { Content = "Keep the items — they wait in Unsorted", IsChecked = true, Margin = new Thickness(0, 4, 0, 4) };
    private readonly RadioButton _move = new() { Content = "Move the items to", Margin = new Thickness(0, 4, 0, 4) };
    private readonly RadioButton _delete = new() { Content = "Delete the items too (Bin, 30 days)", Margin = new Thickness(0, 4, 0, 4) };
    private readonly ComboBox _target = new() { Width = 220, Margin = new Thickness(24, 0, 0, 6), DisplayMemberPath = "Name" };

    public static void OpenFor(ShopList l, Action after) => new DeleteListWindow(l, after).Open();

    private DeleteListWindow(ShopList l, Action after) : base($"Delete \"{l.Name}\"?", 440)
    {
        _list = l;
        _after = after;
        var state = AppState.Current;
        int n = state.ItemsIn(l.Id).Count;
        Form.Children.Add(Ui.Sub(n == 0 ? "The list is empty." : $"It holds {n} item(s). What should happen to them?"));
        Form.Children.Add(_keep);
        var others = state.Lists.Where(x => x.Id != l.Id).ToList();
        foreach (var o in others) _target.Items.Add(o);
        if (others.Count > 0) { _target.SelectedIndex = 0; Form.Children.Add(_move); Form.Children.Add(_target); }
        Form.Children.Add(_delete);
        AddButtons("Delete list");
    }

    protected override bool Save()
    {
        var state = AppState.Current;
        var snap = state.TakeSnapshot();
        var mode = _move.IsChecked == true ? ListDeleteMode.MOVE_TO : _delete.IsChecked == true ? ListDeleteMode.DELETE_ITEMS : ListDeleteMode.KEEP_UNSORTED;
        state.DeleteList(_list, mode, _target.SelectedItem as ShopList);
        _after();
        App.Current.Main.Snack($"Deleted \"{_list.Name}\"", () => state.Restore(snap));
        return true;
    }
}

/// <summary>Pick one list (merge into…).</summary>
public sealed class PickListWindow : EditorWindow
{
    private readonly ListBox _box = new() { MinHeight = 160, MaxHeight = 320, DisplayMemberPath = "Name" };
    private readonly Action<ShopList> _picked;

    public static void Pick(string title, List<ShopList> lists, Action<ShopList> picked) => new PickListWindow(title, lists, picked).Open();

    private PickListWindow(string title, List<ShopList> lists, Action<ShopList> picked) : base(title, 380)
    {
        _picked = picked;
        foreach (var l in lists) _box.Items.Add(l);
        if (lists.Count > 0) _box.SelectedIndex = 0;
        Form.Children.Add(_box);
        AddButtons("OK");
    }

    protected override bool Save()
    {
        if (_box.SelectedItem is not ShopList l) return false;
        _picked(l);
        return true;
    }
}

/// <summary>
/// Sharing a list (Android 2.11 format): "Groceries:-", a blank line, then "1. Milk - 2 / L - Urgent".
/// Copy puts it on the clipboard; WhatsApp opens wa.me with the text (WhatsApp Desktop or Web).
/// The preview lets you change what is included for this share (defaults live in Settings).
/// </summary>
public sealed class ShareWindow : EditorWindow
{
    private readonly long _listId;
    private readonly CheckBox _bought = new() { Content = "Include bought items" };
    private readonly CheckBox _qty = new() { Content = "Quantity / type" };
    private readonly CheckBox _urgent = new() { Content = "\" - Urgent\" on urgent items" };
    private readonly CheckBox _boughtTag = new() { Content = "\" - Bought\" on bought items" };
    private readonly TextBox _suffix = new() { Width = 60 };
    private readonly TextBox _preview = new() { IsReadOnly = true, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, MinHeight = 160, MaxHeight = 300, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, FontFamily = new System.Windows.Media.FontFamily("Consolas"), VerticalContentAlignment = VerticalAlignment.Top };
    private readonly TextBlock _note = Ui.Sub("");

    public static (string Text, int Hidden) TextFor(long listId, ListShareOpts? opts = null)
    {
        var state = AppState.Current;
        var items = state.ItemsIn(listId);
        var name = listId >= 0 ? state.List(listId)?.Name ?? "List" : "Unsorted";
        var list = listId >= 0 ? state.List(listId) : null;
        // Personal items stay private unless the whole list is Private (then sharing it is the point).
        var hidden = list?.Personal == true ? 0 : items.Count(i => i.Personal);
        var shareable = list?.Personal == true ? items : items.Where(i => !i.Personal).ToList();
        var ordered = ShopLists.ShareOrderFor(shareable, state.Settings.ListInnerGroup, state.Data.Products);
        return (ShopLists.ShareText(name, ordered, opts ?? ShopLists.ShareOptsOf(state.Settings)), hidden);
    }

    public static void Copy(long listId)
    {
        var (text, hidden) = TextFor(listId);
        try { Clipboard.SetText(text); } catch (Exception ex) { Log.Warn("Clipboard: " + ex.Message); }
        App.Current.Main.Snack("List copied — paste it anywhere" + (hidden > 0 ? $" ({hidden} personal item(s) left out)" : ""));
    }

    public static void WhatsApp(long listId) => InstallInfo.OpenUrl("https://wa.me/?text=" + Uri.EscapeDataString(TextFor(listId).Text));

    public static void OpenFor(long listId) => new ShareWindow(listId).Open();

    private ShareWindow(long listId) : base("Share the list", 520)
    {
        _listId = listId;
        var s = AppState.Current.Settings;
        _bought.IsChecked = s.ShareIncludeDone;
        _qty.IsChecked = s.ShareIncludeQty;
        _urgent.IsChecked = s.ShareUrgentTag;
        _boughtTag.IsChecked = s.ShareBoughtTag;
        _suffix.Text = s.ShareHeadingSuffix;
        foreach (var c in new[] { _bought, _qty, _urgent, _boughtTag }) { c.Click += (_, _) => Render(); Form.Children.Add(c); }
        _suffix.TextChanged += (_, _) => Render();
        Field("Text after the list name", _suffix);
        Field("Preview", _preview);
        Form.Children.Add(_note);
        AddButtons("💬 WhatsApp", Ui.Btn("📤 Copy", () =>
        {
            try { Clipboard.SetText(_preview.Text); } catch { }
            App.Current.Main.Snack("List copied");
            Close();
        }));
        Loaded += (_, _) => Render();
    }

    private ListShareOpts Opts() => new(_bought.IsChecked == true, _urgent.IsChecked == true, _boughtTag.IsChecked == true, _qty.IsChecked == true, _suffix.Text);

    private void Render()
    {
        var (text, hidden) = TextFor(_listId, Opts());
        _preview.Text = text;
        _note.Text = hidden > 0 ? $"{hidden} personal item(s) are left out." : "Defaults for these options: Settings → Sharing a list.";
    }

    protected override bool Save()
    {
        InstallInfo.OpenUrl("https://wa.me/?text=" + Uri.EscapeDataString(_preview.Text));
        return true;
    }
}
