using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Remindly.App.Views;
using Remindly.Core;

namespace Remindly.App.Services;

/// <summary>
/// "Remindly.exe --smoke-test &lt;folder&gt; --data-dir &lt;temp&gt;" (run by CI on windows-latest): seeds sample data,
/// opens every screen, editor and the reminder card, exercises the main flows, saves a PNG of each and
/// writes smoke-result.txt. Exit code 0 = everything opened without an exception.
/// </summary>
public static class SmokeTest
{
    private static string _dir = "";
    private static readonly List<string> Report = new();
    private static int _failures;

    public static void Fail(Exception ex)
    {
        _failures++;
        Report.Add("FAIL " + ex);
    }

    public static void Run(MainWindow main, string dir)
    {
        _dir = Path.GetFullPath(dir);
        Directory.CreateDirectory(_dir);
        main.Dispatcher.BeginInvoke(async () =>
        {
            try { await RunAsync(main); }
            catch (Exception ex) { Fail(ex); }
            Finish();
        });
    }

    private static async Task RunAsync(MainWindow main)
    {
        var state = AppState.Current;
        Seed(state);
        main.WindowStartupLocation = WindowStartupLocation.Manual;
        main.Left = 0;
        main.Top = 0;
        main.Width = 1200;
        main.Height = 820;
        main.Show();
        await Idle();

        foreach (var tab in main.AllTabs)
        {
            main.Go(tab);
            await Idle();
            Capture(main, "tab-" + ((string)tab.Header).ToLowerInvariant());
        }

        // Buy: inside a list, Bought view, Buy Now
        var groceries = state.Lists.First(l => l.Name == "Groceries");
        main.Go(main.TabFor(Tab.SHOP));
        main.Buy.OpenList(groceries.Id);
        await Idle();
        Capture(main, "buy-list-groceries");
        state.UpdateSettings(s => s with { ListInnerGroup = "SHOP" });
        await Idle();
        Capture(main, "buy-list-by-shop");
        main.Buy.OpenList(ShopLists.BuyNowListId);
        await Idle();
        Capture(main, "buy-now");
        main.Buy.BackToLists();

        // Flows through the same code the buttons use
        var milk = state.ItemsIn(groceries.Id).First(i => i.Title == "Milk");
        Check(state.Complete(milk).StartsWith("Bought"), "complete a Buy item");
        var text = ShareWindow.TextFor(groceries.Id).Text;
        Check(text.StartsWith("Groceries:-\n\n1. "), "share text format: " + text.Replace("\n", "\\n"));
        var snap = state.TakeSnapshot();
        state.DeleteList(state.Lists.First(l => l.Name == "Party"), ListDeleteMode.KEEP_UNSORTED, null);
        Check(state.ItemsIn(ShopLists.UnsortedListId).Any(i => i.Title == "Balloons"), "delete list keeps items in Unsorted");
        state.Restore(snap);
        Check(state.Lists.Any(l => l.Name == "Party"), "undo delete list");
        Check(state.RestartList(groceries.Id) >= 1, "restart list");
        var gym = state.LiveItems(Tab.TASKS).First(i => i.RepeatMode == "DAILY");
        Check(state.Complete(gym).StartsWith("Done · returns"), "complete a recurring task");

        // Every editor window
        await ShowAndCapture(() => ItemEditor.New(Tab.TASKS), "editor-task-new");
        await ShowAndCapture(() => ItemEditor.Edit(state.LiveItems(Tab.SHOP).First()), "editor-buy-item");
        await ShowAndCapture(() => ItemEditor.Edit(state.LiveItems(Tab.LEARN).First()), "editor-learn-item");
        await ShowAndCapture(() => CallEditor.OpenFor(state.LiveCalls().First()), "editor-call");
        await ShowAndCapture(() => ListEditor.OpenFor(groceries), "editor-list");
        await ShowAndCapture(() => ShareWindow.OpenFor(groceries.Id), "share-preview");
        await ShowAndCapture(() => DeleteListWindow.OpenFor(groceries, () => { }), "delete-list");
        await ShowAndCapture(() => ShopEditor.OpenFor(state.Shops.First()), "editor-shop");
        await ShowAndCapture(() => ProductEditor.OpenFor(state.Products.First()), "editor-product");

        // The reminder card
        var alert = new AlertWindow(new DueAlert(AlertKind.ITEM, gym.Id, state.Now, "Gym", "Tasks · leg day", "smoke", Ring: true), 0);
        alert.Show();
        await Idle();
        Capture(alert, "alert-card");
        alert.Close();

        // Every alert the seeded data produces must be computable
        Check(Reminders.Due(state.Data, state.Now, new HashSet<string>()).Count >= 1, "due alerts computed");
        Check(File.Exists(AppPaths.DataFile), "data saved");
    }

    private static void Seed(AppState state)
    {
        long now = state.Now;
        var groceries = state.CreateList(new ShopList { Name = "Groceries", Icon = "🥦", Pinned = true, ShoppingDay = now });
        var party = state.CreateList(new ShopList { Name = "Party", Icon = "🎉" });
        state.CreateList(new ShopList { Name = "Monthly stock", Icon = "📦", Personal = true });
        var dmart = new Shop { Id = Ids.Next(), Name = "D-Mart", CityId = state.CityNamed("Kolkata").Id, ChainId = state.ChainNamed("D-Mart").Id, IsDefault = true };
        state.UpsertShop(dmart);
        state.UpsertShop(new Shop { Id = Ids.Next(), Name = "Local kirana", CityId = state.CityNamed("Kolkata").Id });
        var milkP = new Product { Id = Ids.Next(), Name = "Milk", Category = "Dairy", DefaultUnit = "L" };
        state.UpsertProduct(milkP);
        state.UpsertProduct(new Product { Id = Ids.Next(), Name = "Basmati rice", Category = "Grains", DefaultUnit = "kg" });
        Item Buy(string t, ShopList l, string? qty = null, string? unit = null, string? price = null, Priority? p = null, string? shop = null) =>
            state.NewItem(Tab.SHOP, t) with { ListId = l.Id, Group = l.Name, Quantity = qty, Unit = unit, Price = price, Priority = p ?? Priority.MEDIUM, ShopName = shop, Personal = l.Personal };
        state.UpsertMany(new[]
        {
            Buy("Milk", groceries, "2", "L", "60", Priority.URGENT, "D-Mart") with { ProductId = milkP.Id },
            Buy("Basmati rice", groceries, "5", "kg", "450", shop: "D-Mart"),
            Buy("Eggs", groceries, "12", shop: "Local kirana"),
            Buy("Balloons", party, "30"),
            Buy("Cake", party, price: "900", p: Priority.HIGH),
            state.NewItem(Tab.SHOP, "Batteries"),
        });
        state.UpsertMany(new[]
        {
            state.NewItem(Tab.TASKS, "Pay electricity bill") with { DueAt = now - 3_600_000L, Priority = Priority.URGENT, Notes = "CESC portal" },
            state.NewItem(Tab.TASKS, "Gym") with { DueAt = now + 3_600_000L, RepeatMode = "DAILY", AlertType = "R" },
            state.NewItem(Tab.TASKS, "Call the plumber") with { DueAt = now + 26 * 3_600_000L },
            state.NewItem(Tab.TASKS, "Plan the weekend trip"),
            state.NewItem(Tab.LEARN, "LangGraph agents course") with { Platform = "Udemy", Progress = 40, HoursSpent = 6.5, Url = "https://www.udemy.com", RepeatMode = "SPACED", DueAt = now + 86_400_000L },
            state.NewItem(Tab.LEARN, "Neo4j Cypher tuning") with { Topic = "Graphs" },
        });
        state.UpsertCall(new CallReminder { Id = Ids.Next(), FirstName = "Asha", LastName = "Rao", Name = "Asha Rao", Number = "+91 98300 12345", Source = CallSource.MANUAL, CreatedAt = now, RecurAt = now - 60_000L, Message = "Calling you back shortly" });
        state.UpsertCall(new CallReminder { Id = Ids.Next(), Name = "Bank", Number = "1800 123 456", Source = CallSource.MANUAL, CreatedAt = now, RecurAt = now + 86_400_000L, RepeatMode = "WEEKLY", RepeatDays = new() { 1 } });
    }

    private static async Task ShowAndCapture(Action open, string name)
    {
        var before = Application.Current.Windows.Cast<Window>().ToHashSet();
        open();
        await Idle();
        var w = Application.Current.Windows.Cast<Window>().FirstOrDefault(x => !before.Contains(x));
        if (w == null) { Report.Add($"FAIL {name}: no window opened"); _failures++; return; }
        Capture(w, name);
        w.Close();
        await Idle();
    }

    private static async Task Idle()
    {
        await Task.Delay(250);
        await Dispatcher.Yield(DispatcherPriority.ApplicationIdle);
    }

    private static void Check(bool ok, string what)
    {
        Report.Add((ok ? "ok   " : "FAIL ") + what);
        if (!ok) _failures++;
    }

    private static void Capture(Window w, string name)
    {
        try
        {
            if (w.Content is not FrameworkElement root) { Report.Add($"FAIL {name}: no content"); _failures++; return; }
            root.UpdateLayout();
            int width = (int)Math.Ceiling(root.ActualWidth), height = (int)Math.Ceiling(root.ActualHeight);
            if (width <= 0 || height <= 0) { Report.Add($"FAIL {name}: empty layout"); _failures++; return; }
            var dv = new DrawingVisual();
            using (var dc = dv.RenderOpen())
            {
                dc.DrawRectangle(w.Background is SolidColorBrush b && b.Color.A > 0 ? b : Brushes.White, null, new Rect(0, 0, width, height));
                dc.DrawRectangle(new VisualBrush(root), null, new Rect(0, 0, width, height));
            }
            var bmp = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
            bmp.Render(dv);
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(bmp));
            using var fs = File.Create(Path.Combine(_dir, name + ".png"));
            enc.Save(fs);
            Report.Add($"ok   screenshot {name} ({width}x{height})");
        }
        catch (Exception ex) { Fail(ex); }
    }

    private static void Finish()
    {
        Report.Insert(0, $"Remindly {Core.Updates.UpdateService.CurrentVersion.ToString(3)} smoke test — {(_failures == 0 ? "PASSED" : _failures + " FAILURE(S)")}");
        File.WriteAllLines(Path.Combine(_dir, "smoke-result.txt"), Report);
        Application.Current.Shutdown(_failures == 0 ? 0 : 1);
    }
}
