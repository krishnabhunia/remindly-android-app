namespace Remindly.Core.Logic;

/// <summary>
/// Port of Ids (Model.kt v1.34): a 12-bit per-install device tag in the HIGH bits of every new
/// id, 44 bits of epoch-millis below, monotonic within a device. Tag 0 = legacy/untagged. Two
/// devices on one account can never mint the same id, which is what makes LWW merge safe.
/// </summary>
public static class Ids
{
    private const int TimeBits = 44;
    private const long TimeMask = (1L << TimeBits) - 1;
    private static long _last;
    private static readonly object Gate = new();

    public static int DeviceTag { get; private set; }

    public static void InitTag(int tag) => DeviceTag = Math.Clamp(tag, 0, 0xFFF);

    /// <summary>Pure composer (unit-tested): tag in high bits, millis in low bits, monotonic.</summary>
    public static long Compose(long now, long prev, int tag)
    {
        var base_ = (((long)tag & 0xFFF) << TimeBits) | (now & TimeMask);
        return base_ > prev ? base_ : prev + 1;
    }

    public static int TagOf(long id) => (int)((id >>> TimeBits) & 0xFFF);

    public static long Next()
    {
        lock (Gate)
        {
            var c = Compose(Models.Clock.Now(), _last, DeviceTag);
            _last = c;
            return c;
        }
    }

    /// <summary>A fresh random tag in 1..4095 (Android: ThreadLocalRandom.nextInt(1, 0x1000)).</summary>
    public static int RandomTag() => Random.Shared.Next(1, 0x1000);
}
