using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace MeNoter.Audio;

/// <summary>
/// "Prova audio": ascolta microfono e uscita scelti senza registrare, solo per vedere
/// gli indicatori muoversi (utile con cuffie e auricolari Bluetooth prima della chiamata).
/// </summary>
public sealed class LevelMonitor : IDisposable
{
    private readonly WasapiCapture _mic;
    private readonly WasapiLoopbackCapture _sys;
    private float _micPeak, _sysPeak;
    private readonly object _gate = new();

    public LevelMonitor(MMDevice micDevice, MMDevice outputDevice)
    {
        _mic = new WasapiCapture(micDevice) { ShareMode = AudioClientShareMode.Shared };
        _sys = new WasapiLoopbackCapture(outputDevice);
        _mic.DataAvailable += (_, e) => { lock (_gate) _micPeak = Math.Max(_micPeak, Peak(e, _mic.WaveFormat)); };
        _sys.DataAvailable += (_, e) => { lock (_gate) _sysPeak = Math.Max(_sysPeak, Peak(e, _sys.WaveFormat)); };
        _mic.StartRecording();
        _sys.StartRecording();
    }

    /// <summary>Livelli massimi dall'ultima lettura (0..1).</summary>
    public (float Mic, float Sys) Take()
    {
        lock (_gate)
        {
            var r = (Math.Min(1f, _micPeak), Math.Min(1f, _sysPeak));
            _micPeak = 0;
            _sysPeak = 0;
            return r;
        }
    }

    internal static float Peak(WaveInEventArgs e, WaveFormat f)
    {
        float peak = 0;
        int bps = f.BitsPerSample / 8;
        bool isFloat = f.Encoding == WaveFormatEncoding.IeeeFloat
                       || (f.Encoding == WaveFormatEncoding.Extensible && f.BitsPerSample == 32);
        for (int i = 0; i + bps <= e.BytesRecorded; i += bps)
        {
            float v = isFloat && bps == 4 ? BitConverter.ToSingle(e.Buffer, i)
                : bps == 2 ? BitConverter.ToInt16(e.Buffer, i) / 32768f
                : bps == 4 ? BitConverter.ToInt32(e.Buffer, i) / 2147483648f
                : 0f;
            v = Math.Abs(v);
            if (v > peak) peak = v;
        }
        return peak;
    }

    public void Dispose()
    {
        try { _mic.StopRecording(); } catch { }
        try { _sys.StopRecording(); } catch { }
        _mic.Dispose();
        _sys.Dispose();
    }
}
