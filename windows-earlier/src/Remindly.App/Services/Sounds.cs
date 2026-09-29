using System.Media;
using System.Reflection;

namespace Remindly.App.Services;

/// <summary>Plays the embedded ring/alarm/notify WAVs. One player at a time; looping until stopped.</summary>
public sealed class Sounds : IDisposable
{
    private SoundPlayer? _player;
    private readonly object _gate = new();

    private static Stream Open(string name)
    {
        var asm = Assembly.GetExecutingAssembly();
        return asm.GetManifestResourceStream(name) ?? throw new FileNotFoundException(name);
    }

    public void PlayLoop(string which)
    {
        lock (_gate)
        {
            Stop();
            try
            {
                var p = new SoundPlayer(Open(which == "A" ? "alarm.wav" : "ring.wav"));
                p.Load();
                p.PlayLooping();
                _player = p;
            }
            catch (Exception ex) { Log.Error("sound", ex); }
        }
    }

    public void PlayOnce(string name)
    {
        try { using var p = new SoundPlayer(Open(name)); p.Load(); p.Play(); }
        catch (Exception ex) { Log.Error("sound", ex); }
    }

    public void Stop()
    {
        lock (_gate)
        {
            try { _player?.Stop(); _player?.Dispose(); } catch { }
            _player = null;
        }
    }

    public void Dispose() => Stop();
}
