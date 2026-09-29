namespace Remindly.Core;

/// <summary>
/// Record ids, exactly as Android mints them: a per-install device tag (1..4095) in bits 44–55 and
/// the clock in the low 44 bits, monotonic within a device. Two devices can never mint the same id,
/// so records from an Android backup and records made on Windows never collide.
/// </summary>
public static class Ids
{
    private const int TimeBits = 44;
    private const long TimeMask = (1L << TimeBits) - 1;
    private static long _last;
    private static int _deviceTag;

    public static int DeviceTag => _deviceTag;

    public static void InitTag(int tag) => _deviceTag = Math.Clamp(tag, 0, 0xFFF);

    /// <summary>A fresh random tag for a new install (never 0 = the legacy/untagged range).</summary>
    public static int NewDeviceTag() => Random.Shared.Next(1, 0x1000);

    public static long ComposeId(long now, long prev, int tag)
    {
        long b = (((long)tag & 0xFFF) << TimeBits) | (now & TimeMask);
        return b > prev ? b : prev + 1;
    }

    public static int TagOf(long id) => (int)(((ulong)id >> TimeBits) & 0xFFF);

    public static long Next()
    {
        while (true)
        {
            long prev = Interlocked.Read(ref _last);
            long candidate = ComposeId(Clock.NowMs(), prev, _deviceTag);
            if (Interlocked.CompareExchange(ref _last, candidate, prev) == prev) return candidate;
        }
    }
}

/// <summary>Epoch-millisecond helpers (the Android model stores every time as epoch ms).</summary>
public static class Clock
{
    /// <summary>Overridable in tests.</summary>
    public static Func<long> NowMs { get; set; } = () => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public static DateTime ToLocal(long ms, TimeZoneInfo? zone = null) =>
        TimeZoneInfo.ConvertTimeFromUtc(DateTimeOffset.FromUnixTimeMilliseconds(ms).UtcDateTime, zone ?? TimeZoneInfo.Local);

    /// <summary>Local wall-clock time → epoch ms. A time inside a DST gap moves forward an hour.</summary>
    public static long FromLocal(DateTime local, TimeZoneInfo? zone = null)
    {
        var z = zone ?? TimeZoneInfo.Local;
        var t = DateTime.SpecifyKind(local, DateTimeKind.Unspecified);
        if (z.IsInvalidTime(t)) t = t.AddHours(1);
        return new DateTimeOffset(TimeZoneInfo.ConvertTimeToUtc(t, z)).ToUnixTimeMilliseconds();
    }

    public static DateTime LocalDate(long ms, TimeZoneInfo? zone = null) => ToLocal(ms, zone).Date;

    /// <summary>Midnight that starts the local day after the one containing [ms].</summary>
    public static long StartOfNextDay(long ms, TimeZoneInfo? zone = null) => FromLocal(LocalDate(ms, zone).AddDays(1), zone);

    public static long StartOfDay(long ms, TimeZoneInfo? zone = null) => FromLocal(LocalDate(ms, zone), zone);

    public static string FormatDay(long ms) => ToLocal(ms).ToString("ddd, dd MMM", System.Globalization.CultureInfo.InvariantCulture);

    public static string FormatDate(long ms) => ToLocal(ms).ToString("dd MMM yyyy", System.Globalization.CultureInfo.InvariantCulture);

    public static string FormatTime(long ms) => ToLocal(ms).ToString("hh:mm tt", System.Globalization.CultureInfo.InvariantCulture);

    public static string FormatDayTime(long ms) => FormatDay(ms) + " · " + FormatTime(ms);
}
