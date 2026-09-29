using System.Windows;
using Remindly.App.Services;
using Remindly.App.ViewModels;

namespace Remindly.App.Views;

public partial class ShopEditorDialog : Window
{
    private readonly ShopEditorViewModel _vm;
    public ShopEditorDialog(ShopEditorViewModel vm) { InitializeComponent(); _vm = vm; DataContext = vm; }
    private void OnSave(object sender, RoutedEventArgs e) { if (_vm.TrySave()) { DialogResult = true; Close(); } }
    private void OnCancel(object sender, RoutedEventArgs e) { DialogResult = false; Close(); }
    private void OnDelete(object sender, RoutedEventArgs e)
    {
        if (!Dialogs.Confirm($"Delete \"{_vm.Name}\"? Buy items keep their text and lose the link.", destructive: true)) return;
        _vm.Delete(); DialogResult = true; Close();
    }
}

public partial class ProductEditorDialog : Window
{
    private readonly ProductEditorViewModel _vm;
    public ProductEditorDialog(ProductEditorViewModel vm) { InitializeComponent(); _vm = vm; DataContext = vm; }
    private void OnSave(object sender, RoutedEventArgs e) { if (_vm.TrySave()) { DialogResult = true; Close(); } }
    private void OnCancel(object sender, RoutedEventArgs e) { DialogResult = false; Close(); }
    private void OnDelete(object sender, RoutedEventArgs e)
    {
        if (!Dialogs.Confirm($"Delete \"{_vm.Name}\" from your products?", destructive: true)) return;
        _vm.Delete(); DialogResult = true; Close();
    }
}
