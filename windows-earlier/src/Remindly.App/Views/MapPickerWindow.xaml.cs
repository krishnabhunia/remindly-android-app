using System.Globalization;
using System.Reflection;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using Remindly.App.Services;
using Remindly.Core.Logic;

namespace Remindly.App.Views;

/// <summary>OSM map picker (Leaflet in WebView2): click/drag the pin, search via Nominatim, radius drawn live — the phone's OSM fallback path.</summary>
public partial class MapPickerWindow : Window
{
    private double? _lat, _lng;
    private float _radius;

    public MapPickerWindow(double? lat, double? lng, float radius, string name)
    {
        InitializeComponent();
        _lat = lat; _lng = lng; _radius = Heal.SnapRadius(radius);
        RadiusBox.ItemsSource = Heal.RadiusStops; RadiusBox.SelectedItem = _radius;
        Loaded += async (_, _) => await InitAsync(lat, lng, name);
    }

    private async Task InitAsync(double? lat, double? lng, string name)
    {
        try
        {
            await Web.EnsureCoreWebView2Async();
            var html = ReadHtml();
            var init = JsonSerializer.Serialize(new { lat = lat ?? 19.2437, lng = lng ?? 73.1355, radius = _radius, hasPin = lat != null && lng != null, name });
            html = html.Replace("const init = window.__init ||", $"window.__init = {init}; const init = window.__init ||");
            Web.CoreWebView2.WebMessageReceived += (_, e) =>
            {
                try
                {
                    var o = JsonDocument.Parse(e.WebMessageAsJson).RootElement;
                    if (o.GetProperty("kind").GetString() == "pin")
                    {
                        _lat = o.GetProperty("lat").GetDouble(); _lng = o.GetProperty("lng").GetDouble();
                        Coords.Text = $"{_lat:0.######}, {_lng:0.######}"; UseBtn.IsEnabled = true;
                    }
                }
                catch { }
            };
            Web.NavigateToString(html);
            if (lat != null && lng != null) { Coords.Text = $"{lat:0.######}, {lng:0.######}"; UseBtn.IsEnabled = true; }
        }
        catch (Exception ex)
        {
            Log.Error("WebView2 unavailable", ex);
            Web.Visibility = Visibility.Collapsed; Fallback.Visibility = Visibility.Visible;
        }
    }

    private static string ReadHtml()
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("map.html")!;
        using var r = new StreamReader(s); return r.ReadToEnd();
    }

    private async void OnRadiusChanged(object sender, SelectionChangedEventArgs e)
    {
        if (RadiusBox.SelectedItem is float r) { _radius = r; try { if (Web.CoreWebView2 != null) await Web.CoreWebView2.ExecuteScriptAsync($"window.setRadius && window.setRadius({r.ToString(CultureInfo.InvariantCulture)})"); } catch { } }
    }

    private void OnUse(object sender, RoutedEventArgs e) { DialogResult = _lat != null; Close(); }
    private void OnCancel(object sender, RoutedEventArgs e) { DialogResult = false; Close(); }

    public static (double lat, double lng, float radius)? Pick(double? lat, double? lng, float radius, string name)
    {
        var w = new MapPickerWindow(lat, lng, radius, name) { Owner = Application.Current.Windows.OfType<Window>().FirstOrDefault(x => x.IsActive) ?? Application.Current.MainWindow };
        return w.ShowDialog() == true && w._lat is double a && w._lng is double b ? (a, b, w._radius) : null;
    }
}
