using System.Text.Json.Nodes;
using Remindly.Core.Json;
using Remindly.Core.Models;

namespace Remindly.Core.Tests;

/// <summary>
/// The Windows app writes records the phone must read back with Gson. These tests pin the wire
/// SHAPE to the Kotlin data classes in Remindly Android 2.8 (Model.kt) — every field, by name.
/// </summary>
public class WireParityTests
{
    private static readonly string[] KotlinItem = "id, tab, title, notes, createdAt, dueAt, priority, alertType, snoozedUntil, quantity, price, shopName, lapseValue, lapseUnit, expiryAt, personal, platform, url, topic, missedAt, dueHasTime, repeatMode, repeatDays, repeatN, repeatUnit, repeatOrd, repeatDow, repeatOrdList, repeatCount, repeatDone, updatedAt, deletedAt, progress, spacedStep, hoursSpent, oosAt, unit, productId, shopId, staple, priceHistory, calEventId, group, done, doneAt, returnAt".Split(", ");
    private static readonly string[] KotlinCall = "id, number, name, alertType, snoozedUntil, message, firstName, lastName, company, source, createdAt, lastMissedAt, note, updatedAt, deletedAt, repeatMode, repeatDays, repeatN, repeatUnit, repeatOrd, repeatDow, repeatOrdList, recurAt, label, savedGroups, repeatCount, repeatDone, calEventId, missedCount, done, doneAt, clearedNote, nagAt, nagFired".Split(", ");
    private static readonly string[] KotlinPlace = "id, name, lat, lng, radius, trigger, enabled, lastFired, groupFilter, updatedAt, deletedAt".Split(", ");
    private static readonly string[] KotlinShop = "id, name, area, lat, lng, radius, isDefault, cityId, chainId, arriveTypes, lastArriveFired, updatedAt, deletedAt".Split(", ");
    private static readonly string[] KotlinCity = "id, name, updatedAt, deletedAt".Split(", ");
    private static readonly string[] KotlinProduct = "id, name, category, defaultUnit, note, updatedAt, deletedAt".Split(", ");
    private static readonly string[] KotlinLink = "productId, shopId, lastPrice, lastUnitPrice, lastAt, deletedAt, updatedAt".Split(", ");   // updatedAt added in Android 2.9 (N29)
    private static readonly string[] KotlinPricePoint = "at, price, shop, qty, unit, unitPrice, paid, discountPct".Split(", ");

    private static HashSet<string> Keys(string json) => JsonNode.Parse(json)!.AsObject().Select(kv => kv.Key).ToHashSet();

    [Fact] public void Item_writes_every_Kotlin_field_and_nothing_else()
        => Assert.Equal(KotlinItem.ToHashSet(), Keys(Wire.ToJson(new Item { Id = 1, Title = "x" })));

    [Fact] public void CallReminder_writes_every_Kotlin_field()
        => Assert.Equal(KotlinCall.ToHashSet(), Keys(Wire.ToJson(new CallReminder { Id = 1, Number = "9" })));

    [Fact] public void GeoPlace_Shop_City_Product_Link_PricePoint_shapes()
    {
        Assert.Equal(KotlinPlace.ToHashSet(), Keys(Wire.ToJson(new GeoPlace { Id = 1 })));
        Assert.Equal(KotlinShop.ToHashSet(), Keys(Wire.ToJson(new Shop { Id = 1 })));
        Assert.Equal(KotlinCity.ToHashSet(), Keys(Wire.ToJson(new City { Id = 1 })));
        Assert.Equal(KotlinCity.ToHashSet(), Keys(Wire.ToJson(new Chain { Id = 1 })));
        Assert.Equal(KotlinProduct.ToHashSet(), Keys(Wire.ToJson(new Product { Id = 1 })));
        Assert.Equal(KotlinLink.ToHashSet(), Keys(Wire.ToJson(new ProductLink { ProductId = 1, ShopId = 2 })));
        Assert.Equal(KotlinPricePoint.ToHashSet(), Keys(Wire.ToJson(new PricePoint())));
    }

    [Fact] public void Enums_travel_as_Kotlin_names()
    {
        var j = JsonNode.Parse(Wire.ToJson(new Item { Id = 1, Tab = Tab.SHOP, Priority = Priority.URGENT, LapseUnit = LapseUnit.MONTHS }))!.AsObject();
        Assert.Equal("SHOP", j["tab"]!.GetValue<string>());
        Assert.Equal("URGENT", j["priority"]!.GetValue<string>());
        Assert.Equal("MONTHS", j["lapseUnit"]!.GetValue<string>());
        var p = JsonNode.Parse(Wire.ToJson(new GeoPlace { Id = 1, Trigger = TriggerType.ARRIVE }))!.AsObject();
        Assert.Equal("ARRIVE", p["trigger"]!.GetValue<string>());
        var c = JsonNode.Parse(Wire.ToJson(new CallReminder { Id = 1, Source = CallSource.AUTO }))!.AsObject();
        Assert.Equal("AUTO", c["source"]!.GetValue<string>());
    }

    [Fact] public void Nulls_are_written_explicitly_like_serializeNulls()
    {
        var j = JsonNode.Parse(Wire.ToJson(new Item { Id = 1 }))!.AsObject();
        Assert.True(j.ContainsKey("dueAt"));
        Assert.Null(j["dueAt"]);
        Assert.True(j.ContainsKey("priority"));
        Assert.Null(j["priority"]);
        // non-null Kotlin lists must never be null on the wire (healItem relies on it)
        Assert.Equal(JsonValueKind.Array, j["missedAt"]!.GetValueKind());
        Assert.Equal(JsonValueKind.Array, j["repeatDays"]!.GetValueKind());
        Assert.Equal(JsonValueKind.Array, j["priceHistory"]!.GetValueKind());
    }

    [Fact] public void Gson_record_from_the_phone_parses_and_round_trips_unknown_fields()
    {
        // A realistic Android 2.8 item (floats like 0.0, nested purchase, plus a hypothetical future field).
        const string phone = """
        {"id":123456789,"tab":"SHOP","title":"Basmati Rice","notes":"","createdAt":1757990000000,"dueAt":1758045600000,"priority":"HIGH","alertType":"R","snoozedUntil":null,
         "quantity":"5","price":"450","shopName":"D-Mart – Kalyan","lapseValue":1,"lapseUnit":"MONTHS","expiryAt":null,"personal":false,"platform":null,"url":null,"topic":null,
         "missedAt":[],"dueHasTime":true,"repeatMode":"OFF","repeatDays":[],"repeatN":1,"repeatUnit":"D","repeatOrd":1,"repeatDow":1,"repeatOrdList":[],"repeatCount":null,"repeatDone":0,
         "updatedAt":1757990001000,"deletedAt":null,"progress":0,"spacedStep":0,"hoursSpent":0.0,"oosAt":null,"unit":"kg","productId":555,"shopId":777,"staple":true,
         "priceHistory":[{"at":1757000000000,"price":440.0,"shop":"D-Mart – Kalyan","qty":5.0,"unit":"kg","unitPrice":88.0,"paid":420.0,"discountPct":4.545454545454546}],
         "calEventId":null,"group":"Grocery","done":false,"doneAt":null,"returnAt":null,"futureFieldFromAndroid3":{"x":1}}
        """;
        var item = Wire.FromJson<Item>(phone)!;
        Assert.Equal(Tab.SHOP, item.Tab);
        Assert.Equal(Priority.HIGH, item.Priority);
        Assert.Equal(LapseUnit.MONTHS, item.LapseUnit);
        Assert.Equal("kg", item.Unit);
        Assert.Single(item.PriceHistory);
        Assert.Equal(88.0, item.PriceHistory[0].UnitPrice);
        Assert.True(item.Staple);
        var back = JsonNode.Parse(Wire.ToJson(item with { Title = "Basmati Rice 5kg" }))!.AsObject();
        Assert.Equal("Basmati Rice 5kg", back["title"]!.GetValue<string>());
        Assert.Equal(1, back["futureFieldFromAndroid3"]!["x"]!.GetValue<int>());   // preserved, not dropped
        Assert.Equal(KotlinItem.Append("futureFieldFromAndroid3").ToHashSet(), Keys(back.ToJsonString()));
    }

    [Fact] public void Unknown_enum_name_from_a_newer_phone_does_not_crash_the_parse()
    {
        var item = Wire.FromJson<Item>("""{"id":1,"tab":"TASKS","title":"t","priority":"CRITICAL"}""")!;
        Assert.Null(item.Priority);
        Assert.Equal("t", item.Title);
    }

    [Fact] public void Lenient_array_parse_skips_one_bad_record_and_keeps_the_rest()
    {
        var list = Wire.ParseArrayLenient<Item>("""[{"id":1,"tab":"TASKS","title":"a"},{"id":"not-a-number-at-all","tab":7},{"id":3,"tab":"LEARN","title":"c"}]""");
        Assert.Equal(2, list.Count);
        Assert.Equal(new long[] { 1, 3 }, list.Select(i => i.Id).ToArray());
    }

    [Fact] public void AppSettings_keeps_every_unknown_field_through_an_edit()
    {
        var s = AppSettings.Parse("""{"ver":41,"theme":"DARK","tCardSwipe":true,"swipeVelocityDp":125,"tabGestureOv":{"tCardSwipe":"OFF"},"calendarIds":[1,2],"settingsUpdatedAt":5}""");
        var edited = s.With(x => { x.Theme = "LIGHT"; x.TasksGroups = new List<string> { "Home" }; });
        var j = JsonNode.Parse(edited.ToJson())!.AsObject();
        Assert.Equal("LIGHT", j["theme"]!.GetValue<string>());
        Assert.True(j["tCardSwipe"]!.GetValue<bool>());
        Assert.Equal(125, j["swipeVelocityDp"]!.GetValue<int>());
        Assert.Equal("OFF", j["tabGestureOv"]!["tCardSwipe"]!.GetValue<string>());
        Assert.Equal(2, j["calendarIds"]!.AsArray().Count);
        Assert.Equal("Home", j["tasksGroups"]![0]!.GetValue<string>());
        Assert.Equal("DARK", s.Theme);   // original untouched (copy-on-write)
    }

    [Fact] public void WireDoc_envelope_uses_schema_71()
    {
        var d = WireDoc.Of(new Item { Id = 1 }, 42);
        Assert.Equal(71, d.SchemaVer);
        Assert.Equal(42, d.UpdatedAt);
    }
}
