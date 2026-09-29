using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using Remindly.App.Services;
using Remindly.Core.Logic;
using Remindly.Core.Models;

namespace Remindly.App.Views;

/// <summary>The full-screen alarm card (AlarmActivity on the phone): sound until AlarmRingSeconds (0 = until dismissed).</summary>
public partial class AlarmWindow : Window
{
    private readonly AlertEvent _ev;
    private readonly DispatcherTimer? _autoStop;
    private readonly DispatcherTimer _clock;

    public AlarmWindow(AlertEvent ev)
    {
        InitializeComponent();
        _ev = ev;
        var s = App.Services;
        Kicker.Text = (ev.Kind == "test" ? "TEST · " : "ALARM · ") + (ev.Source switch { AlertSource.Call => "CALLS", AlertSource.Expiry => "BUY", _ => (ev.Tab?.Label() ?? "REMINDER").ToUpperInvariant() });
        TitleText.Text = ev.Title;
        Sub.Text = ev.Kind == "test" ? "Every button just closes this — “Test — no action taken.”" : ev.Subtitle + " · due " + s.Relative(ev.FireAt);
        SnoozeBtn.Content = "Snooze " + s.Alerts.SnoozeLabel; QuietBtn.Content = "Quiet " + s.Alerts.SnoozeLabel;
        if (ev.Source == AlertSource.Call) DoneBtn.Content = "Called";
        if (ev.Source is AlertSource.Item or AlertSource.Expiry && s.Repo.Item(ev.RecordId) is { Priority: { } p } && p != Priority.MEDIUM) { PillText.Text = p.Label(); Pill.Visibility = Visibility.Visible; }
        if (ev.Kind == "test") { PillText.Text = "Urgent"; Pill.Visibility = Visibility.Visible; }
        _clock = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _clock.Tick += (_, _) => Clock.Text = s.Time(Core.Models.Clock.Now());
        _clock.Start(); Clock.Text = s.Time(Core.Models.Clock.Now());
        s.Alerts.Sounds.PlayLoop("A");
        var seconds = s.Repo.Settings.AlarmRingSeconds;
        if (seconds > 0)
        {
            _autoStop = new DispatcherTimer { Interval = TimeSpan.FromSeconds(Alerts.CoerceRingSeconds(seconds)) };
            _autoStop.Tick += (_, _) => { _autoStop.Stop(); s.Alerts.Sounds.Stop(); };
            _autoStop.Start();
        }
        Closed += (_, _) => { _autoStop?.Stop(); _clock.Stop(); s.Alerts.Sounds.Stop(); };
        Loaded += (_, _) => { Activate(); Topmost = true; };
    }

    private void Finish(Action? act)
    {
        if (_ev.Kind != "test") act?.Invoke();
        else App.MainVm.Ack("Test — no action taken", null);
        Close();
    }

    private void OnDone(object sender, RoutedEventArgs e) => Finish(() => App.Services.Alerts.Done(_ev));
    private void OnSnooze(object sender, RoutedEventArgs e) => Finish(() => App.Services.Alerts.Snooze(_ev));
    private void OnQuiet(object sender, RoutedEventArgs e) => Finish(() => App.Services.Alerts.Quiet(_ev));
    private void OnDismiss(object sender, RoutedEventArgs e) => Finish(null);
    /// <summary>Double-click outside the card = Quiet (the phone's double-tap-outside gesture).</summary>
    private void OnRootMouseDown(object sender, MouseButtonEventArgs e) { if (e.ClickCount == 2 && !Card.IsMouseOver) OnQuiet(sender, e); }
}
