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

    /// <summary>Numeri della trascrizione, scritti in trascrizione-log.txt per capire cosa è successo.</summary>
    public class Report
    {
        public List<string> Lines { get; } = new();
        public void Add(string line) => Lines.Add(line);
        public override string ToString() => string.Join(Environment.NewLine, Lines);
    }

    public static async Task<List<Segment>> RunAsync(
        WhisperModel model,
        string language,
        IReadOnlyList<(string Path, string Speaker)> tracks,
        bool skipSilence,
        Report report,
        IProgress<double> progress,
        CancellationToken ct)
    {
        var result = new List<Segment>();
        // Tutti i core tranne uno, così il PC resta usabile.
        int threads = Math.Clamp(Environment.ProcessorCount - 1, 2, 16);
        report.Add($"{DateTime.Now:yyyy-MM-dd HH:mm:ss}  modello {model.Key}, lingua {language}, {threads} core, " +
                   (skipSilence ? "salto dei silenzi attivo" : "audio intero"));
        using var factory = WhisperFactory.FromPath(model.FilePath);

        // Prima si cercano i tratti con voce in ogni traccia: il lavoro di whisper si misura su quelli.
        var prepared = tracks.Select(t =>
        {
            var samples = Wav.Read(t.Path);
            var (compact, map, threshold) = skipSilence
                ? Compact(samples)
                : (samples, new List<Piece> { new(0, 0, samples.Length) }, 0f);
            report.Add($"Traccia {(t.Speaker.Length > 0 ? t.Speaker : "audio")}: {samples.Length / 16000.0:0} s, " +
                       $"con voce {compact.Length / 16000.0:0} s, livello max {Wav.Rms(samples, 0, samples.Length):0.0000}, soglia {threshold:0.0000}");
            return (t.Speaker, Samples: samples, Compact: compact, Map: map, Threshold: threshold);
        }).ToList();
        double totalWork = Math.Max(1, prepared.Sum(p => p.Compact.Length));
        double doneWork = 0;

        foreach (var (speaker, samples, compact, map, threshold) in prepared)
        {
            int kept = 0, quiet = 0, invented = 0;
            if (compact.Length >= 8000) // almeno mezzo secondo di voce
            {
                using var processor = factory.CreateBuilder()
                    .WithLanguage(language)
                    .WithThreads(threads)
                    .Build();
                using var stream = WavStream(compact);
                // Sotto questo livello una frase è quasi sempre inventata da whisper sul silenzio.
                float minRms = Math.Max(0.0008f, threshold * 0.4f);
                await foreach (var seg in processor.ProcessAsync(stream, ct))
                {
                    progress.Report((doneWork + Math.Min(compact.Length, seg.End.TotalSeconds * 16000)) / totalWork);
                    var text = seg.Text.Trim();
                    if (!Keep(text))
                    {
                        invented++;
                        continue;
                    }
                    var start = ToOriginal(map, seg.Start);
                    var end = ToOriginal(map, seg.End);
                    if (end <= start) end = start + (seg.End - seg.Start);
                    var rms = Wav.Rms(samples, (int)(start.TotalSeconds * 16000), (int)(end.TotalSeconds * 16000));
                    if (rms < minRms)
                    {
                        quiet++;
                        continue;
                    }
                    kept++;
                    result.Add(new Segment(start, end, speaker, text));
                }
            }
            doneWork += compact.Length;
            report.Add($"  {(speaker.Length > 0 ? speaker : "audio")}: {kept} frasi tenute, {quiet} scartate perché troppo basse, {invented} scartate perché vuote o inventate");
        }
        progress.Report(1);
        int before = result.Count;
        var final = RemoveEcho(result).OrderBy(s => s.Start).ToList();
        report.Add($"Eco tolta: {before - final.Count} frasi. Totale: {final.Count} frasi.");
        return final;
    }

    /// <summary>Pezzo di voce: dove sta nell'audio compattato e dove nell'originale (in campioni).</summary>
    private readonly record struct Piece(int CompactStart, int OriginalStart, int Length);

    /// <summary>
    /// Tiene solo i tratti con voce (con margine abbondante) e li mette uno dopo l'altro, separati da 0,3 s
    /// di silenzio. La soglia è prudente e si adatta al volume della traccia: le chiamate arrivano spesso
    /// a volume basso, e una voce bassa non deve mai essere scambiata per silenzio.
    /// </summary>
    private static (float[] Compact, List<Piece> Map, float Threshold) Compact(float[] s)
    {
        const int win = 8000; // finestre da 0,5 s
        int windows = s.Length / win + 1;
        var rms = new float[windows];
        for (int w = 0; w < windows; w++) rms[w] = Wav.Rms(s, w * win, (w + 1) * win);
        var sorted = rms.OrderBy(v => v).ToArray();
        float floor = sorted[(int)(sorted.Length * 0.2)];   // rumore di fondo
        float loud = sorted[(int)(sorted.Length * 0.95)];   // voce piena
        // Appena sopra il rumore di fondo, e comunque molto sotto il volume della voce.
        float threshold = Math.Max(0.0012f, Math.Min(floor * 2.5f + 0.0005f, loud * 0.25f));

        var regions = new List<(int Start, int End)>();
        for (int w = 0; w < windows; w++)
        {
            if (rms[w] < threshold) continue;
            int a = Math.Max(0, w * win - 2 * win);              // 1 s prima
            int b = Math.Min(s.Length, (w + 1) * win + 2 * win); // 1 s dopo
            if (regions.Count > 0 && a <= regions[^1].End + 2 * 16000) // pause sotto 2 s: stesso tratto
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
        return (compact, map, threshold);
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
