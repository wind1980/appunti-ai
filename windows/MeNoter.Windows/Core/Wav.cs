using NAudio.Wave;

namespace MeNoter.Core;

/// <summary>Lettura, mescolanza e conversione dei file audio (sempre 16 kHz mono 16 bit).</summary>
public static class Wav
{
    public static readonly WaveFormat Format = new(16000, 16, 1);

    public static float[] Read(string path)
    {
        using var reader = new WaveFileReader(path);
        var sp = reader.ToSampleProvider();
        var all = new List<float>((int)Math.Min(int.MaxValue, reader.SampleCount));
        var buf = new float[16000];
        int n;
        while ((n = sp.Read(buf, 0, buf.Length)) > 0)
            for (int i = 0; i < n; i++) all.Add(buf[i]);
        return all.ToArray();
    }

    public static void Write(string path, float[] samples)
    {
        using var writer = new WaveFileWriter(path, Format);
        writer.WriteSamples(samples, 0, samples.Length);
    }

    /// <summary>Le due tracce in un unico file da ascoltare (e da importare nell'app Android).</summary>
    public static void Mix(string a, string b, string output)
    {
        var x = Read(a);
        var y = Read(b);
        var len = Math.Max(x.Length, y.Length);
        var mix = new float[len];
        for (int i = 0; i < len; i++)
        {
            float v = (i < x.Length ? x[i] : 0) + (i < y.Length ? y[i] : 0);
            mix[i] = Math.Clamp(v, -1f, 1f);
        }
        Write(output, mix);
    }

    /// <summary>Qualsiasi audio o video leggibile da Windows (mp3, m4a, mp4, wav…) → WAV 16 kHz mono.</summary>
    public static TimeSpan Convert(string input, string output)
    {
        using var reader = new MediaFoundationReader(input);
        using var resampler = new MediaFoundationResampler(reader, Format) { ResamplerQuality = 60 };
        WaveFileWriter.CreateWaveFile(output, resampler);
        return reader.TotalTime;
    }

    public static float Rms(float[] s, int from, int to)
    {
        from = Math.Clamp(from, 0, s.Length);
        to = Math.Clamp(to, from, s.Length);
        if (to == from) return 0;
        double sum = 0;
        for (int i = from; i < to; i++) sum += s[i] * s[i];
        return (float)Math.Sqrt(sum / (to - from));
    }
}
