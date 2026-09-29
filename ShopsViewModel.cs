using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Remindly.App.Services;
using Remindly.App.Views;
using Remindly.Core.Logic;
using Remindly.Core.Models;

namespace Remindly.App.ViewModels;

public sealed partial class ShopRowViewModel : ObservableObject
{
    private readonly ShopsViewModel _list;
    public Shop Shop { get; }
    public string Name => Shop.Name;
    public string Tag { get; }
    public bool IsChain { get; }
    public bool IsDefault => Shop.IsDefault;
    public string Geo { get; }
    public bool HasGeofence => Shop.HasGeofence;
    public string Arrive { get; }
    public string Pending { get; }
    public ShopRowViewModel(ShopsViewModel list, Shop s, AppServices svc)
    {
        _list = list; Shop = s;
        IsChain = s.ChainId != null;
        Tag = IsChain ? "Chain" : "Local";
        Geo = s.HasGeofence ? $"{Heal.RadiusLabel(s.Radius)} · {s.Lat:0.####}, {s.Lng:0.####}" : "No geofence";
        Arrive = s.HasGeofence ? "On arrival: " + Alerts.AlertTypesLabel(s.ArriveTypes) + (s.ArriveTypes == null ? " (" + Alerts.AlertTypesLabel(svc.Repo.Settings.ShopArriveTypes) + ")" : "") : "";
        var n = Alerts.BuyNowItems(svc.Repo.Items, s).Count;
        Pending = n == 0 ? "" : $"{n} to buy";
    }
    [RelayCommand] private void Edit() => _list.Edit(this);
    [RelayCommand] private void MakeDefault() => _list.MakeDefault(this);
    [RelayCommand] private void Delete() => _list.Delete(this);
    [RelayCommand] private void BuyNow() => _list.BuyNow(this);
}

public sealed partial class CityGroupViewModel : ObservableObject
{
    public long? CityId { get; init; }
    public string Label { get; init; } = "";
    [ObservableProperty] private bool _isExpanded = true;
    public ObservableCollection<ShopRowViewModel> Shops { get; } = new();
    public string CountLabel => Shops.Count.ToString();
    public bool IsReal => CityId != null;
}

/// <summary>Shops by City (Unassigned last), Chains as a side list; per-shop geofence + arrival alert (v1.90 / v2.05).</summary>
public sealed partial class ShopsViewModel : ObservableObject
{
    private readonly AppServices _s;
    private readonly MainViewModel _main;
    private readonly DispatcherTimer _debounce;
    public ObservableCollection<CityGroupViewModel> Cities { get; } = new();
    public ObservableCollection<Chain> Chains { get; } = new();
    public ObservableCollection<City> CityList { get; } = new();
    [ObservableProperty] private string _summary = "";
    [ObservableProperty] private string _search = "";
    [ObservableProperty] private bool _isEmpty;
    [ObservableProperty] private string _catalogueNote = "";

    public ShopsViewModel(AppServices s, MainViewModel main)
    {
        _s = s; _main = main;
        _debounce = new DispatcherTimer(DispatcherPriority.Background, s.Dispatcher) { Interval = TimeSpan.FromMilliseconds(120) };
        _debounce.Tick += (_, _) => { _debounce.Stop(); Rebuild(); };
        s.Repo.Changed += c => { if (c is "shops" or "cities" or "chains" or "items" or "settings" or "prefs") _debounce.Start(); };
        s.Sync.StateChanged += () => s.Dispatcher.BeginInvoke(() => _debounce.Start());
        Rebuild();
    }

    partial void OnSearchChanged(string value) => _debounce.Start();

    public void Rebuild()
    {
        Cities.Clear(); Chains.Clear(); CityList.Clear();
        var q = Search.Trim();
        var shops = _s.Repo.ActiveShops.Where(s => q.Length == 0 || s.Name.Contains(q, StringComparison.OrdinalIgnoreCase)).ToList();
        foreach (var c in _s.Repo.ActiveCities)
        {
            CityList.Add(c);
            var g = new CityGroupViewModel { CityId = c.Id, Label = c.Name };
            foreach (var s in shops.Where(s => s.CityId == c.Id)) g.Shops.Add(new ShopRowViewModel(this, s, _s));
            if (g.Shops.Count > 0 || q.Length == 0) Cities.Add(g);
        }
        var un = new CityGroupViewModel { CityId = null, Label = "Unassigned" };
        var cityIds = _s.Repo.ActiveCities.Select(c => c.Id).ToHashSet();
        foreach (var s in shops.Where(s => s.CityId == null || !cityIds.Contains(s.CityId.Value))) un.Shops.Add(new ShopRowViewModel(this, s, _s));
        if (un.Shops.Count > 0) Cities.Add(un);
        foreach (var ch in _s.Repo.ActiveChains) Chains.Add(ch);
        Summary = $"{shops.Count} shop(s) · {CityList.Count} cit{(CityList.Count == 1 ? "y" : "ies")} · {Chains.Count} chain(s)";
        IsEmpty = shops.Count == 0 && q.Length == 0;
        var denied = _s.Sync.Collections.Values.Any(c => c.PermissionDenied);
        CatalogueNote = denied ? "Cities, chains and products stay on this PC until the Android 2.9 Firestore rules are merged (see README)." : "";
    }

    [RelayCommand] private void AddShop() => Edit(null);
    [RelayCommand] private void AddCity()
    {
        var name = TextPrompt.Ask("New city", "City name", "");
        if (string.IsNullOrWhiteSpace(name)) return;
        _s.Repo.Upsert(new City { Id = Ids.Next(), Name = name.Trim() });
    }
    [RelayCommand] private void RenameCity(CityGroupViewModel g)
    {
        if (g.CityId is not long id) return;
        var c = _s.Repo.CityOf(id); if (c == null) return;
        var name = TextPrompt.Ask("Rename city", "City name", c.Name);
        if (string.IsNullOrWhiteSpace(name)) return;
        _s.Repo.Upsert(c with { Name = name.Trim() });
    }
    [RelayCommand] private void DeleteCity(CityGroupViewModel g)
    {
        if (g.CityId is not long id) return;
        if (!Dialogs.Confirm($"Delete the city \"{g.Label}\"? Its shops move to Unassigned.", destructive: true)) return;
        _s.Repo.DeleteCity(id);
    }
    [RelayCommand] private void AddChain()
    {
        var name = TextPrompt.Ask("New chain", "Chain name (e.g. D-Mart)", "");
        if (string.IsNullOrWhiteSpace(name)) return;
        _s.Repo.Upsert(new Chain { Id = Ids.Next(), Name = name.Trim() });
    }
    [RelayCommand] private void RenameChain(Chain ch)
    {
        var name = TextPrompt.Ask("Rename chain", "Chain name", ch.Name);
        if (string.IsNullOrWhiteSpace(name)) return;
        _s.Repo.Upsert(ch with { Name = name.Trim() });
    }
    [RelayCommand] private void DeleteChain(Chain ch)
    {
        var branches = _s.Repo.ActiveShops.Count(s => s.ChainId == ch.Id);
        if (branches == 0) { if (Dialogs.Confirm($"Delete the chain \"{ch.Name}\"?", destructive: true)) _s.Repo.DeleteChain(ch.Id, true); return; }
        var keep = System.Windows.MessageBox.Show($"\"{ch.Name}\" has {branches} branch shop(s).\n\nYes = keep the branches as local shops\nNo = delete the branches too\nCancel = do nothing", "Delete chain", System.Windows.MessageBoxButton.YesNoCancel, System.Windows.MessageBoxImage.Warning);
        if (keep == System.Windows.MessageBoxResult.Cancel) return;
        _s.Repo.DeleteChain(ch.Id, keep == System.Windows.MessageBoxResult.Yes);
    }
    /// <summary>"＋ add to city": one branch per chosen city, auto-named "Chain – City".</summary>
    [RelayCommand] private void AddBranch(Chain ch)
    {
        var cities = _s.Repo.ActiveCities.ToList();
        if (cities.Count == 0) { Dialogs.Info("Add a city first."); return; }
        var pick = TextPrompt.Choose("Add a branch of " + ch.Name, "City", cities.Select(c => c.Name).ToList());
        if (pick == null) return;
        var city = cities.First(c => c.Name == pick);
        var vm = new ShopEditorViewModel(_s, new Shop { Id = Ids.Next(), Name = ShopMath.BranchAutoName(ch.Name, city.Name), CityId = city.Id, ChainId = ch.Id, Radius = Heal.SnapRadius(_s.Repo.Settings.ShopNewRadius) }, isNew: true);
        new ShopEditorDialog(vm) { Owner = System.Windows.Application.Current.MainWindow }.ShowDialog();
    }

    public void Edit(ShopRowViewModel? row)
    {
        var shop = row?.Shop ?? new Shop { Id = Ids.Next(), Radius = Heal.SnapRadius(_s.Repo.Settings.ShopNewRadius) };
        var vm = new ShopEditorViewModel(_s, shop, isNew: row == null);
        new ShopEditorDialog(vm) { Owner = System.Windows.Application.Current.MainWindow }.ShowDialog();
    }
    public void MakeDefault(ShopRowViewModel row) => _s.Repo.Upsert(row.Shop with { IsDefault = true });
    public void Delete(ShopRowViewModel row)
    {
        var n = Alerts.BuyNowItems(_s.Repo.Items, row.Shop).Count;
        if (!Dialogs.Confirm($"Delete \"{row.Name}\"?" + (n > 0 ? $"\n\n{n} buy item(s) keep their text and lose the link." : ""), destructive: true)) return;
        _s.Repo.DeleteShop(row.Shop.Id);
        if (_s.Repo.Prefs.BuyNowShopId == row.Shop.Id) { _s.Repo.Prefs.BuyNowShopId = null; _s.Repo.SavePrefs(); }
    }
    /// <summary>Manual "Buy Now" for this shop (the phone arms it by geofence; a PC doesn't move).</summary>
    public void BuyNow(ShopRowViewModel row)
    {
        _s.Repo.Prefs.BuyNowShopId = row.Shop.Id; _s.Repo.SavePrefs();
        _main.NavigateTo("buy");
    }
}

/// <summary>Shop editor dialog VM: name, city, chain, geofence (map picker / lat-lng), radius stop, arrival alert, default.</summary>
public sealed partial class ShopEditorViewModel : ObservableObject
{
    private readonly AppServices _s;
    public Shop Original { get; }
    public bool IsNew { get; }
    public string Heading => IsNew ? "New shop" : "Edit shop";
    [ObservableProperty] private string _name = "";
    [ObservableProperty] private City? _city;
    [ObservableProperty] private Chain? _chain;
    [ObservableProperty] private string _latText = "";
    [ObservableProperty] private string _lngText = "";
    [ObservableProperty] private float _radius = 150f;
    [ObservableProperty] private bool _isDefault;
    [ObservableProperty] private bool _followDefaultArrive = true;
    [ObservableProperty] private bool _arriveN = true;
    [ObservableProperty] private bool _arriveR;
    [ObservableProperty] private bool _arriveA;
    [ObservableProperty] private string _error = "";
    public List<City?> CityOptions { get; }
    public List<Chain?> ChainOptions { get; }
    public float[] RadiusStops => Heal.RadiusStops;
    public string RadiusLabel => Heal.RadiusLabel(Radius);
    public bool HasGeo => double.TryParse(LatText, NumberStyles.Float, CultureInfo.InvariantCulture, out _) && double.TryParse(LngText, NumberStyles.Float, CultureInfo.InvariantCulture, out _);
    public string ArriveExplain => FollowDefaultArrive ? "Follows Shops default: " + Alerts.AlertTypesLabel(_s.Repo.Settings.ShopArriveTypes) : Alerts.AlertTypesLabel((ArriveN ? "N" : "") + (ArriveR ? "R" : "") + (ArriveA ? "A" : ""));
    public bool Saved { get; private set; }

    public ShopEditorViewModel(AppServices s, Shop shop, bool isNew)
    {
        _s = s; Original = shop; IsNew = isNew;
        CityOptions = new List<City?> { null }.Concat(s.Repo.ActiveCities).ToList();
        ChainOptions = new List<Chain?> { null }.Concat(s.Repo.ActiveChains).ToList();
        Name = shop.Name; City = s.Repo.CityOf(shop.CityId); Chain = s.Repo.ChainOf(shop.ChainId);
        LatText = shop.Lat?.ToString("0.######", CultureInfo.InvariantCulture) ?? ""; LngText = shop.Lng?.ToString("0.######", CultureInfo.InvariantCulture) ?? "";
        Radius = Heal.SnapRadius(shop.Radius); IsDefault = shop.IsDefault;
        var at = Heal.NormalizeAlertTypes(shop.ArriveTypes);
        FollowDefaultArrive = at == null;
        if (at != null) { ArriveN = at.Contains('N'); ArriveR = at.Contains('R'); ArriveA = at.Contains('A'); }
    }

    partial void OnRadiusChanged(float value) => OnPropertyChanged(nameof(RadiusLabel));
    partial void OnLatTextChanged(string value) => OnPropertyChanged(nameof(HasGeo));
    partial void OnLngTextChanged(string value) => OnPropertyChanged(nameof(HasGeo));
    partial void OnFollowDefaultArriveChanged(bool value) => OnPropertyChanged(nameof(ArriveExplain));
    partial void OnArriveNChanged(bool value) => OnPropertyChanged(nameof(ArriveExplain));
    partial void OnArriveRChanged(bool value) => OnPropertyChanged(nameof(ArriveExplain));
    partial void OnArriveAChanged(bool value) => OnPropertyChanged(nameof(ArriveExplain));

    [RelayCommand] private void ClearGeo() { LatText = ""; LngText = ""; }
    [RelayCommand] private void PickOnMap()
    {
        double.TryParse(LatText, NumberStyles.Float, CultureInfo.InvariantCulture, out var lat);
        double.TryParse(LngText, NumberStyles.Float, CultureInfo.InvariantCulture, out var lng);
        var r = MapPickerWindow.Pick(HasGeo ? lat : null, HasGeo ? lng : null, Radius, Name);
        if (r is { } hit) { LatText = hit.lat.ToString("0.######", CultureInfo.InvariantCulture); LngText = hit.lng.ToString("0.######", CultureInfo.InvariantCulture); Radius = Heal.SnapRadius(hit.radius); }
    }

    public bool TrySave()
    {
        Error = "";
        var name = Name.Trim();
        if (name.Length == 0) { Error = "Give the shop a name."; return false; }
        var dup = _s.Repo.ActiveShops.FirstOrDefault(x => x.Id != Original.Id && string.Equals(x.Name.Trim(), name, StringComparison.OrdinalIgnoreCase));
        if (dup != null) { Error = "Another shop already has this name."; return false; }
        double? lat = null, lng = null;
        if (LatText.Trim().Length + LngText.Trim().Length > 0)
        {
            if (!double.TryParse(LatText, NumberStyles.Float, CultureInfo.InvariantCulture, out var la) || !double.TryParse(LngText, NumberStyles.Float, CultureInfo.InvariantCulture, out var ln) || Math.Abs(la) > 90 || Math.Abs(ln) > 180)
            { Error = "Latitude/longitude look wrong (e.g. 19.2437, 73.1355)."; return false; }
            lat = la; lng = ln;
        }
        var live = _s.Repo.Shops.FirstOrDefault(x => x.Id == Original.Id) ?? Original;
        var shop = live with
        {
            Name = name, CityId = City?.Id, ChainId = Chain?.Id, Lat = lat, Lng = lng, Radius = Heal.SnapRadius(Radius), IsDefault = IsDefault,
            ArriveTypes = FollowDefaultArrive ? null : (ArriveN ? "N" : "") + (ArriveR ? "R" : "") + (ArriveA ? "A" : ""),
            DeletedAt = null,
        };
        _s.Repo.Upsert(shop);
        Saved = true;
        return true;
    }

    public void Delete()
    {
        if (IsNew) return;
        _s.Repo.DeleteShop(Original.Id);
        Saved = true;
    }
}
