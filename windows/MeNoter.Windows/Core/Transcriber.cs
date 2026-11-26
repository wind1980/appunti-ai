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
        using var factory = WhisperFactory.FromPath(model.FilePath);
        for (int t = 0; t < tracks.Count; t++)
        {
            var (path, speaker) = tracks[t];
            var samples = Wav.Read(path);
            double totalSec = Math.Max(1, samples.Length / 16000.0);
            // Traccia muta (es. microfono spento per tutta la chiamata): si salta.
            if (Wav.Rms(samples, 0, samples.Length) < 0.0005) continue;

            using var processor = factory.CreateBuilder().WithLanguage(language).Build();
            await using var stream = File.OpenRead(path);
            await foreach (var seg in processor.ProcessAsync(stream, ct))
            {
                progress.Report((t + Math.Min(1, seg.End.TotalSeconds / totalSec)) / tracks.Count);
                var text = seg.Text.Trim();
                if (!Keep(text)) continue;
                // Niente frasi su tratti quasi silenziosi: sono quasi sempre inventate.
                var rms = Wav.Rms(samples, (int)(seg.Start.TotalSeconds * 16000), (int)(seg.End.TotalSeconds * 16000));
                if (rms < 0.004) continue;
                result.Add(new Segment(seg.Start, seg.End, speaker, text));
            }
        }
        progress.Report(1);
        return RemoveEcho(result).OrderBy(s => s.Start).ToList();
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
