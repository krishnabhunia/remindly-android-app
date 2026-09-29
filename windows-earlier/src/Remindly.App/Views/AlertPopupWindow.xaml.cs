using System.Windows;
using System.Windows.Threading;
using Remindly.App.Services;
using Remindly.Core.Logic;
using Remindly.Core.Models;

namespace Remindly.App.Views;

/// <summary>Bottom-right notification card (Notify / Ring). Buttons mirror the phone's notification actions.</summary>
public partial class AlertPopupWindow : Window
{
    private readonly AlertEvent _ev;
    private readonly DispatcherTimer? _autoStop;

    public AlertPopupWindow(AlertEvent ev, List<AlertEvent>? summary = null)
    {
        InitializeComponent();
        _ev = ev;
        var s = App.Services;
        var ring = ev.Types.Contains('R');
        Glyph.Text = ring ? "🔊" : "🔔";
        Kicker.Text = "Remindly · " + (ev.Source switch { AlertSource.Call => "Calls", AlertSource.Expiry => "Buy · expiry", AlertSource.Summary => "While you were away", _ => ev.Tab?.Label() ?? "Reminder" });
        TitleText.Text = ev.Title;
        Sub.Text = ev.Source == AlertSource.Summary ? ev.Subtitle : $"{ev.Subtitle} · {s.Relative(ev.FireAt)}";
        if (ev.Source == AlertSource.Summary)
        {
            DoneBtn.Visibility = Visibility.Collapsed; SnoozeBtn.Visibility = Visibility.Collapsed; QuietBtn.Visibility = Visibility.Collapsed;
            OpenBtn.Content = ev.Kind == "test" ? "Close" : "Open Scheduled alerts";
            if (summary != null) { List.ItemsSource = summary.Take(8).Select(x => $"• {x.Title} — {s.Relative(x.FireAt)}").Concat(summary.Count > 8 ? new[] { $"…and {summary.Count - 8} more" } : Array.Empty<string>()).ToList(); List.Visibility = Visibility.Visible; }
            if (ev.Kind == "test" && ring) { App.Services.Alerts.Sounds.PlayLoop("R"); StopBtn.Visibility = Visibility.Visible; }
        }
        else
        {
            SnoozeBtn.Content = "Snooze " + s.Alerts.SnoozeLabel;
            QuietBtn.Content = "Quiet " + s.Alerts.SnoozeLabel;
            if (ring) StopBtn.Visibility = Visibility.Visible;
            if (ev.Source == AlertSource.Call) { DoneBtn.Content = "Called"; OpenBtn.Content = "Open Calls"; }
        }
        var seconds = ring ? s.Repo.Settings.RingRingSeconds : 0;
        if (ring && seconds > 0)
        {
            _autoStop = new DispatcherTimer { Interval = TimeSpan.FromSeconds(Alerts.CoerceRingSeconds(seconds)) };
            _autoStop.Tick += (_, _) => { _autoStop.Stop(); s.Alerts.Sounds.Stop(); };
            _autoStop.Start();
        }
        Closed += (_, _) => { _autoStop?.Stop(); if (ring) s.Alerts.Sounds.Stop(); };
        // A plain Notify card slides away by itself after a while, like a toast; Ring stays until acted on.
        if (!ring && ev.Source != AlertSource.Summary)
        {
            var t = new DispatcherTimer { Interval = TimeSpan.FromSeconds(25) };
            t.Tick += (_, _) => { t.Stop(); Close(); };
            t.Start();
        }
    }

    private void OnClose(object sender, RoutedEventArgs e) => Close();
    private void OnDone(object sender, RoutedEventArgs e) { if (_ev.Kind != "test") App.Services.Alerts.Done(_ev); Close(); }
    private void OnSnooze(object sender, RoutedEventArgs e) { if (_ev.Kind != "test") App.Services.Alerts.Snooze(_ev); Close(); }
    private void OnQuiet(object sender, RoutedEventArgs e) { if (_ev.Kind != "test") App.Services.Alerts.Quiet(_ev); Close(); }
    private void OnStop(object sender, RoutedEventArgs e) { App.Services.Alerts.Sounds.Stop(); StopBtn.Visibility = Visibility.Collapsed; }
    private void OnOpen(object sender, RoutedEventArgs e)
    {
        var app = (App)Application.Current;
        if (_ev.Kind == "test") { Close(); return; }
        app.ShowMain();
        var main = App.MainVm;
        if (_ev.Source == AlertSource.Summary) main.NavigateTo("scheduled");
        else if (_ev.Source == AlertSource.Call) { main.NavigateTo("calls"); var c = App.Services.Repo.Call(_ev.RecordId); if (c != null) main.OpenCallEditor(c); }
        else { var it = App.Services.Repo.Item(_ev.RecordId); if (it != null) { main.NavigateTo(it.Tab switch { Tab.SHOP => "buy", Tab.LEARN => "learn", _ => "tasks" }); main.OpenItemEditor(it, it.Tab); } }
        Close();
    }
}
