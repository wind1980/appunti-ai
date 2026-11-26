using System.Diagnostics;
using NAudio.CoreAudioApi;
using NAudio.Dsp;
using NAudio.Wave;

namespace MeNoter.Audio;

/// <summary>
/// Registra una chiamata su due tracce, a 16 kHz mono (il formato di whisper):
/// il microfono scelto ("Io") e l'audio che esce dal dispositivo scelto ("Altri"),
/// preso in loopback, quindi senza nessuna integrazione con Teams, Webex o Meet.
/// Le due tracce restano allineate al tempo anche quando nessuno parla
/// (in loopback Windows non manda dati durante il silenzio).
/// </summary>
public sealed class CallRecorder : IDisposable
{
    public const int Rate = 16000;

    private readonly object _gate = new();
    private readonly Track _mic;
    private readonly Track _sys;
    private readonly WasapiCapture _micCapture;
    private readonly WasapiLoopbackCapture _sysCapture;
    private readonly Stopwatch _clock = new();
    private DateTime _lastFlush = DateTime.UtcNow;
    private bool _stopped;

    public bool Paused { get; private set; }

    public TimeSpan Elapsed => _clock.Elapsed;

    /// <summary>Livelli (0..1) di microfono e audio della chiamata, per gli indicatori.</summary>
    public event Action<float, float>? Levels;

    /// <summary>Errore del dispositivo (es. auricolari BT scollegati durante la chiamata).</summary>
    public event Action<string>? DeviceError;

    public CallRecorder(MMDevice micDevice, MMDevice outputDevice, string micPath, string sysPath)
    {
        _micCapture = new WasapiCapture(micDevice) { ShareMode = AudioClientShareMode.Shared };
        _sysCapture = new WasapiLoopbackCapture(outputDevice);
        _mic = new Track(micPath, _micCapture.WaveFormat);
        _sys = new Track(sysPath, _sysCapture.WaveFormat);
        _micCapture.DataAvailable += (_, e) => OnData(_mic, e);
        _sysCapture.DataAvailable += (_, e) => OnData(_sys, e);
        _micCapture.RecordingStopped += (_, e) => OnStopped(e, "microfono");
        _sysCapture.RecordingStopped += (_, e) => OnStopped(e, "uscita audio");
    }

    public void Start()
    {
        _sysCapture.StartRecording();
        _micCapture.StartRecording();
        _clock.Start();
    }

    public void Pause()
    {
        lock (_gate)
        {
            Align();
            Paused = true;
            _clock.Stop();
        }
    }

    public void Resume()
    {
        lock (_gate)
        {
            Paused = false;
            _clock.Start();
        }
    }

    /// <summary>Ferma e chiude i file. Restituisce la durata registrata.</summary>
    public TimeSpan Stop()
    {
        lock (_gate)
        {
            if (_stopped) return _clock.Elapsed;
            _stopped = true;
            _clock.Stop();
        }
        try { _micCapture.StopRecording(); } catch { }
        try { _sysCapture.StopRecording(); } catch { }
        lock (_gate)
        {
            // Le due tracce finiscono insieme.
            long end = Math.Max(_mic.Samples, _sys.Samples);
            _mic.PadTo(end);
            _sys.PadTo(end);
            _mic.Dispose();
            _sys.Dispose();
        }
        return _clock.Elapsed;
    }

    private void OnData(Track track, WaveInEventArgs e)
    {
        float micLevel, sysLevel;
        lock (_gate)
        {
            if (_stopped || e.BytesRecorded == 0) return;
            if (Paused)
            {
                track.MeasureOnly(e.Buffer, e.BytesRecorded);
            }
            else
            {
                track.Add(e.Buffer, e.BytesRecorded);
                // Il microfono manda dati di continuo: si usa per tenere al passo l'altra traccia.
                if (track == _mic) Align();
                if ((DateTime.UtcNow - _lastFlush).TotalSeconds > 10)
                {
                    // Aggiorna l'intestazione dei file: se il PC si spegne, l'audio fin qui resta leggibile.
                    _mic.Flush();
                    _sys.Flush();
                    _lastFlush = DateTime.UtcNow;
                }
            }
            micLevel = _mic.TakePeak();
            sysLevel = _sys.TakePeak();
        }
        Levels?.Invoke(micLevel, sysLevel);
    }

    /// <summary>Riempie di silenzio la traccia rimasta indietro rispetto all'orologio (con 0,3 s di margine).</summary>
    private void Align()
    {
        long expected = (long)(_clock.Elapsed.TotalSeconds * Rate) - Rate * 3 / 10;
        if (expected <= 0) return;
        _sys.PadTo(expected);
        _mic.PadTo(expected);
    }

    private void OnStopped(StoppedEventArgs e, string what)
    {
        if (e.Exception != null && !_stopped)
            DeviceError?.Invoke($"Il dispositivo ({what}) si è interrotto: {e.Exception.Message}");
    }

    public void Dispose()
    {
        Stop();
        _micCapture.Dispose();
        _sysCapture.Dispose();
    }

    /// <summary>Una traccia: conversione in mono, ricampionamento a 16 kHz e scrittura WAV a 16 bit.</summary>
    private sealed class Track : IDisposable
    {
        private readonly WaveFileWriter _writer;
        private readonly WaveFormat _source;
        private readonly WdlResampler _resampler;
        private readonly bool _isFloat;
        private float _peak;
        private float[] _out = new float[4096];
        private byte[] _bytes = new byte[8192];

        public long Samples { get; private set; }

        public Track(string path, WaveFormat source)
        {
            _source = source;
            // In modalità condivisa WASAPI dà quasi sempre float a 32 bit (anche come "Extensible").
            _isFloat = source.Encoding == WaveFormatEncoding.IeeeFloat
                       || (source.Encoding == WaveFormatEncoding.Extensible && source.BitsPerSample == 32);
            _writer = new WaveFileWriter(path, new WaveFormat(Rate, 16, 1));
            _resampler = new WdlResampler();
            _resampler.SetMode(true, 2, false);
            _resampler.SetFilterParms();
            _resampler.SetFeedMode(true); // si passano i campioni in ingresso
            _resampler.SetRates(source.SampleRate, Rate);
        }

        public void MeasureOnly(byte[] buffer, int bytes) => ToMono(buffer, bytes);

        public void Add(byte[] buffer, int bytes)
        {
            var mono = ToMono(buffer, bytes);
            int frames = mono.Length;
            if (frames == 0) return;
            _resampler.ResamplePrepare(frames, 1, out float[] inBuffer, out int inOffset);
            Array.Copy(mono, 0, inBuffer, inOffset, frames);
            int maxOut = (int)((long)frames * Rate / _source.SampleRate) + 64;
            if (_out.Length < maxOut) _out = new float[maxOut];
            int produced = _resampler.ResampleOut(_out, 0, frames, maxOut, 1);
            Write(_out, produced);
        }

        public void PadTo(long target)
        {
            if (Samples >= target) return;
            var zeros = new float[4096];
            while (Samples < target)
            {
                int n = (int)Math.Min(zeros.Length, target - Samples);
                Write(zeros, n);
            }
        }

        public float TakePeak()
        {
            float p = _peak;
            _peak = 0;
            return Math.Min(1f, p);
        }

        public void Flush() => _writer.Flush();

        private void Write(float[] samples, int count)
        {
            if (_bytes.Length < count * 2) _bytes = new byte[count * 2];
            for (int i = 0; i < count; i++)
            {
                float v = Math.Clamp(samples[i], -1f, 1f);
                short s = (short)(v * short.MaxValue);
                _bytes[2 * i] = (byte)(s & 0xFF);
                _bytes[2 * i + 1] = (byte)((s >> 8) & 0xFF);
            }
            _writer.Write(_bytes, 0, count * 2);
            Samples += count;
        }

        private float[] ToMono(byte[] buffer, int bytes)
        {
            int channels = Math.Max(1, _source.Channels);
            int bytesPerSample = Math.Max(1, _source.BitsPerSample / 8);
            int frames = bytes / (bytesPerSample * channels);
            var mono = new float[frames];
            for (int f = 0; f < frames; f++)
            {
                float sum = 0;
                for (int c = 0; c < channels; c++)
                {
                    int i = (f * channels + c) * bytesPerSample;
                    sum += ReadSample(buffer, i, bytesPerSample);
                }
                float v = sum / channels;
                mono[f] = v;
                float a = Math.Abs(v);
                if (a > _peak) _peak = a;
            }
            return mono;
        }

        private float ReadSample(byte[] b, int i, int bytesPerSample)
        {
            if (_isFloat && bytesPerSample == 4) return BitConverter.ToSingle(b, i);
            return bytesPerSample switch
            {
                2 => BitConverter.ToInt16(b, i) / 32768f,
                3 => ((b[i] << 8) | (b[i + 1] << 16) | (b[i + 2] << 24)) / 2147483648f,
                4 => BitConverter.ToInt32(b, i) / 2147483648f,
                _ => (b[i] - 128) / 128f,
            };
        }

        public void Dispose() => _writer.Dispose();
    }
}
