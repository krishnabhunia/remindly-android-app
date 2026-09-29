using System.Media;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using Remindly.App.Services;
using Remindly.Core;

namespace Remindly.App.Views;

/// <summary>
/// The reminder card that pops up bottom-right (Android: the notification / full-screen alarm).
/// Ring and Alarm types keep sounding until an action is taken (max 2 minutes).
/// </summary>
public sealed class AlertWindow : Window
{
    private readonly DueAlert _alert;
    private SoundPlayer? _player;
    private readonly DispatcherTimer _ringStop = new() { Interval = TimeSpan.FromMinutes(2) };

    public AlertWindow(DueAlert alert, int slot)
    {
        _alert = alert;
        Title = "Remindly reminder";
        Width = 380;
        SizeToContent = SizeToContent.Height;
        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        Topmost = true;
        ShowInTaskbar = true;
        ShowActivated = false;
        FontFamily = new FontFamily("Segoe UI");

        var state = AppState.Current;
        int snooze = state.Settings.SnoozeMinutes;
        var kindLabel = alert.Kind switch { AlertKind.CALL => "📞 Call-back", AlertKind.SHOPPING_DAY => "🛒 Shopping day", _ => "⏰ Reminder" };

        var buttons = new WrapPanel { Margin = new Thickness(0, 12, 0, 0) };
        switch (alert.Kind)
        {
            case AlertKind.ITEM:
                buttons.Children.Add(Ui.Primary("✓ Done", () => Act(() =>
                {
                    if (state.Item(alert.RecordId) is Item i && !i.Done) App.Current.Main.Snack(state.Complete(i));
                })));
                buttons.Children.Add(Ui.Btn($"Snooze {snooze} min", () => Act(() =>
                {
                    if (state.Item(alert.RecordId) is Item i) state.Snooze(i, snooze);
                })));
                buttons.Children.Add(Ui.Btn("Open", () => Act(() =>
                {
                    if (state.Item(alert.RecordId) is Item i) App.Current.Main.OpenItem(i);
                })));
                break;
            case AlertKind.CALL:
                var call = state.LiveCalls().FirstOrDefault(c => c.Id == alert.RecordId);
                if (call != null)
                {
                    buttons.Children.Add(Ui.Primary("📞 Call", () => Act(() => InstallInfo.OpenUrl("tel:" + CallText.NormalizePhone(call.Number)))));
                    buttons.Children.Add(Ui.Btn("WhatsApp", () => Act(() => InstallInfo.OpenUrl(CallText.WhatsAppUrl(call.Number, call.Message)))));
                    buttons.Children.Add(Ui.Btn("✓ Done", () => Act(() => App.Current.Main.Snack(state.CompleteCall(call)))));
                    buttons.Children.Add(Ui.Btn($"Snooze {snooze} min", () => Act(() => state.SnoozeCall(call, snooze))));
                }
                break;
            case AlertKind.SHOPPING_DAY:
                buttons.Children.Add(Ui.Primary("Open list", () => Act(() => App.Current.Main.OpenList(alert.RecordId))));
                break;
        }

        var close = Ui.IconBtn("✕", () => Act(() => { }), "Dismiss");
        close.HorizontalAlignment = HorizontalAlignment.Right;
        close.VerticalAlignment = VerticalAlignment.Top;

        var body = new StackPanel();
        body.Children.Add(Ui.Columns("*,Auto", Ui.Text(kindLabel, 12, FontWeights.SemiBold, Ui.Res("AccentInkBrush")), close));
        body.Children.Add(Ui.Text(alert.Title, 17, FontWeights.SemiBold));
        if (!string.IsNullOrWhiteSpace(alert.Body)) body.Children.Add(Ui.Sub(alert.Body));
        body.Children.Add(Ui.Sub("Due " + Clock.FormatDayTime(alert.FireAt)));
        body.Children.Add(buttons);

        Content = new Border
        {
            Background = Brushes.White,
            BorderBrush = Ui.Res("AccentBrush"),
            BorderThickness = new Thickness(2),
            CornerRadius = new CornerRadius(12),
            Padding = new Thickness(16, 10, 12, 14),
            Margin = new Thickness(8),
            Effect = new System.Windows.Media.Effects.DropShadowEffect { BlurRadius = 16, ShadowDepth = 2, Opacity = 0.25 },
            Child = body,
        };

        Loaded += (_, _) => { PlaceAt(slot); StartSound(); };
        Closed += (_, _) => StopSound();
        _ringStop.Tick += (_, _) => StopSound();
    }

    /// <summary>Stacks the cards upwards from the bottom-right corner of the work area.</summary>
    public void PlaceAt(int slot)
    {
        var wa = SystemParameters.WorkArea;
        UpdateLayout();
        double h = ActualHeight > 0 ? ActualHeight : 190;
        Left = wa.Right - Width - 8;
        Top = Math.Max(wa.Top, wa.Bottom - (h + 4) * (slot + 1) - 8);
    }

    private void Act(Action a)
    {
        StopSound();
        try { a(); } catch (Exception ex) { Log.Error("Alert action failed", ex); }
        Close();
    }

    private void StartSound()
    {
        if (App.IsSmokeTest) return;
        try
        {
            if (_alert.Ring)
            {
                var wav = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "Media", "Alarm01.wav");
                if (File.Exists(wav))
                {
                    _player = new SoundPlayer(wav);
                    _player.PlayLooping();
                    _ringStop.Start();
                    return;
                }
            }
            SystemSounds.Asterisk.Play();
        }
        catch (Exception ex) { Log.Warn("Alert sound: " + ex.Message); }
    }

    private void StopSound()
    {
        _ringStop.Stop();
        try { _player?.Stop(); _player?.Dispose(); } catch { }
        _player = null;
    }
}
