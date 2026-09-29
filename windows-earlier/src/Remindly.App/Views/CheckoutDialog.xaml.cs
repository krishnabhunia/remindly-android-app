using System.Windows;
using Remindly.App.ViewModels;
using Remindly.Core.Models;

namespace Remindly.App.Views;

public partial class CheckoutDialog : Window
{
    private readonly CheckoutViewModel _vm;
    public CheckoutDialog(Item item) { InitializeComponent(); _vm = new CheckoutViewModel(App.Services, item); DataContext = _vm; }
    private void OnComplete(object sender, RoutedEventArgs e) { _vm.Complete(); DialogResult = true; Close(); }
    private void OnSkip(object sender, RoutedEventArgs e) { _vm.Skip(); DialogResult = true; Close(); }
    private void OnCancel(object sender, RoutedEventArgs e) { DialogResult = false; Close(); }
}
