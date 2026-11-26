using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;
using Whisper.net;

namespace MeNoter.Core;

/// <summary>Modelli whisper scaricabili (una volta sola, poi restano sul PC).</summary>
public record WhisperModel(string Key, string Label, int SizeMb)
{
    public static readonly WhisperModel[] All =
    {
        new("base", "Base – veloce, meno preciso", 142),
        new("small", "Small – consigliato", 466),
        new("medium", "Medium – più preciso, lento", 1530),
    };

    public string FilePath => Path.Combine(Settings.ModelsDir, $"ggml-{Key}.bin");
    public bool Downloaded => File.Exists(FilePath);
    public override string ToString() => $"{Label} ({SizeMb} MB)";

    public static WhisperModel ByKey(string key) => All.FirstOrDefault(m => m.Key == key) ?? All[1];

    public async Task DownloadAsync(IProgress<double> progress, CancellationToken ct)
    {
        Directory.CreateDirectory(Settings.ModelsDir);
        var url = $"https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-{Key}.bin";
        var part = FilePath + ".part";
        using var http = new HttpClient { Timeout = TimeSpan.FromHours(2) };
        using var resp = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct);
        resp.EnsureSuccessStatusCode();
        long total = resp.Content.Headers.ContentLength ?? (long)SizeMb * 1024 * 1024;
        await using (var src = await resp.Content.ReadAsStreamAsync(ct))
        await using (var dst = File.Create(part))
        {
            var buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = await src.ReadAsync(buf, ct)) > 0)
            {
                await dst.WriteAsync(buf.AsMemory(0, n), ct);
                done += n;
                progress.Report((double)done / total);
            }
        }
        File.Move(part, FilePath, true);
    }
}

/// <summary>Frase trascritta, con chi parla ("Io", "Altri" o vuoto per un audio importato).</summary>
public record Segment(TimeSpan Start, TimeSpan End, string Speaker, string Text);

/// <summary>Trascrizione sul PC con whisper.cpp (Whisper.net): nessun audio esce dal computer.</summary>
public static class Transcriber
{
    // Frasi che whisper "inventa" sul silenzio.
    private static readonly string[] Hallucinations =
    {
        "sottotitoli", "amara.org", "grazie per la visione", "iscriviti al canale", "thank you for watching",
        "sottotitolato", "a cura di qtss",
    };

    public static async Task<List<Segment>> RunAsync(
        WhisperModel model,
        string language,
        IReadOnlyList<(string Path, string Speaker)> tracks,
        IProgress<double> progress,
        CancellationToken ct)
    {
        var result = new List<Segment>();
        // Tutti i core tranne uno, così il PC resta usabile.
        int threads = Math.Clamp(Environment.ProcessorCount - 1, 2, 16);
        using var factory = WhisperFactory.FromPath(model.FilePath);

        // Prima si cercano i tratti con voce in ogni traccia: il lavoro di whisper si misura su quelli.
        var prepared = tracks.Select(t =>
        {
            var samples = Wav.Read(t.Path);
            var (compact, map) = Compact(samples);
            return (t.Speaker, Samples: samples, Compact: compact, Map: map);
        }).ToList();
        double totalWork = Math.Max(1, prepared.Sum(p => p.Compact.Length));
        double doneWork = 0;

        foreach (var (speaker, samples, compact, map) in prepared)
        {
            if (compact.Length < 16000) continue; // meno di un secondo di voce: niente da trascrivere
            using var processor = factory.CreateBuilder()
                .WithLanguage(language)
                .WithThreads(threads)
                .Build();
            using var stream = WavStream(compact);
            await foreach (var seg in processor.ProcessAsync(stream, ct))
            {
                progress.Report((doneWork + Math.Min(compact.Length, seg.End.TotalSeconds * 16000)) / totalWork);
                var text = seg.Text.Trim();
                if (!Keep(text)) continue;
                var start = ToOriginal(map, seg.Start);
                var end = ToOriginal(map, seg.End);
                if (end < start) end = start + (seg.End - seg.Start);
                // Niente frasi su tratti quasi silenziosi: sono quasi sempre inventate.
                var rms = Wav.Rms(samples, (int)(start.TotalSeconds * 16000), (int)(end.TotalSeconds * 16000));
                if (rms < 0.004) continue;
                result.Add(new Segment(start, end, speaker, text));
            }
            doneWork += compact.Length;
        }
        progress.Report(1);
        return RemoveEcho(result).OrderBy(s => s.Start).ToList();
    }

    /// <summary>Pezzo di voce: dove sta nell'audio compattato e dove nell'originale (in campioni).</summary>
    private readonly record struct Piece(int CompactStart, int OriginalStart, int Length);

    /// <summary>
    /// Tiene solo i tratti con voce (con un po' di margine) e li mette uno dopo l'altro, separati da 0,3 s
    /// di silenzio. In una riunione ognuna delle due tracce è in silenzio gran parte del tempo,
    /// quindi whisper lavora su molto meno audio.
    /// </summary>
    private static (float[] Compact, List<Piece> Map) Compact(float[] s)
    {
        const int win = 8000; // finestre da 0,5 s
        int windows = s.Length / win + 1;
        var rms = new float[windows];
        for (int w = 0; w < windows; w++) rms[w] = Wav.Rms(s, w * win, (w + 1) * win);
        // Soglia: sopra il rumore di fondo della traccia, ma mai sotto un minimo.
        var sorted = rms.OrderBy(v => v).ToArray();
        float floor = sorted[(int)(sorted.Length * 0.2)];
        float threshold = Math.Max(0.006f, floor * 3);

        var regions = new List<(int Start, int End)>();
        for (int w = 0; w < windows; w++)
        {
            if (rms[w] < threshold) continue;
            int a = Math.Max(0, w * win - win);              // 0,5 s prima
            int b = Math.Min(s.Length, (w + 1) * win + win); // 0,5 s dopo
            if (regions.Count > 0 && a <= regions[^1].End + 16000) // pause sotto 1 s: stesso tratto
                regions[^1] = (regions[^1].Start, Math.Max(regions[^1].End, b));
            else
                regions.Add((a, b));
        }

        const int gap = 4800; // 0,3 s di silenzio tra un tratto e l'altro
        var map = new List<Piece>();
        int length = regions.Sum(r => r.End - r.Start + gap);
        var compact = new float[Math.Max(0, length)];
        int pos = 0;
        foreach (var (a, b) in regions)
        {
            Array.Copy(s, a, compact, pos, b - a);
            map.Add(new Piece(pos, a, b - a));
            pos += b - a + gap;
        }
        return (compact, map);
    }

    private static TimeSpan ToOriginal(List<Piece> map, TimeSpan compactTime)
    {
        int c = (int)(compactTime.TotalSeconds * 16000);
        var piece = map.LastOrDefault(p => p.CompactStart <= c);
        if (piece.Length == 0) return compactTime;
        int offset = Math.Min(c - piece.CompactStart, piece.Length);
        return TimeSpan.FromSeconds((piece.OriginalStart + offset) / 16000.0);
    }

    /// <summary>WAV 16 kHz mono 16 bit in memoria, il formato che legge whisper.</summary>
    private static MemoryStream WavStream(float[] samples)
    {
        var ms = new MemoryStream();
        using (var writer = new NAudio.Wave.WaveFileWriter(new NAudio.Utils.IgnoreDisposeStream(ms), Wav.Format))
            writer.WriteSamples(samples, 0, samples.Length);
        ms.Position = 0;
        return ms;
    }

    private static bool Keep(string text)
    {
        if (text.Length < 2) return false;
        if ((text.StartsWith('[') && text.EndsWith(']')) || (text.StartsWith('(') && text.EndsWith(')'))) return false;
        var lower = text.ToLowerInvariant();
        return !Hallucinations.Any(h => lower.Contains(h));
    }

    /// <summary>
    /// Con le casse (senza cuffie) il microfono riprende anche gli altri: le frasi di "Io" che ripetono
    /// quasi uguali una frase di "Altri" nello stesso momento sono eco e si tolgono.
    /// </summary>
    private static List<Segment> RemoveEcho(List<Segment> segs)
    {
        var others = segs.Where(s => s.Speaker == "Altri").ToList();
        if (others.Count == 0) return segs;
        return segs.Where(s =>
        {
            if (s.Speaker != "Io") return true;
            var words = Words(s.Text);
            if (words.Count == 0) return false;
            foreach (var o in others)
            {
                if (o.End < s.Start - TimeSpan.FromSeconds(1) || o.Start > s.End + TimeSpan.FromSeconds(1)) continue;
                var ow = Words(o.Text);
                int common = words.Count(ow.Contains);
                if (common >= Math.Max(2, words.Count * 0.6)) return false;
            }
            return true;
        }).ToList();
    }

    private static HashSet<string> Words(string s) =>
        Regex.Split(s.ToLowerInvariant(), @"[^\p{L}\p{N}]+").Where(w => w.Length > 2).ToHashSet();

    /// <summary>Testo leggibile: "[mm:ss] Io: …", frasi vicine della stessa persona unite in un paragrafo.</summary>
    public static string Format(List<Segment> segs)
    {
        var sb = new StringBuilder();
        Segment? current = null;
        var text = new StringBuilder();
        void FlushParagraph()
        {
            if (current == null) return;
            sb.Append('[').Append(NoteStore.FormatTime(current.Start)).Append("] ");
            if (current.Speaker.Length > 0) sb.Append(current.Speaker).Append(": ");
            sb.AppendLine(text.ToString().Trim()).AppendLine();
            text.Clear();
        }
        TimeSpan lastEnd = TimeSpan.Zero;
        foreach (var s in segs)
        {
            bool same = current != null && s.Speaker == current.Speaker
                        && s.Start - lastEnd < TimeSpan.FromSeconds(2) && text.Length < 700;
            if (!same)
            {
                FlushParagraph();
                current = s;
            }
            text.Append(' ').Append(s.Text);
            lastEnd = s.End;
        }
        FlushParagraph();
        return sb.ToString().TrimEnd() + Environment.NewLine;
    }
}
