using System.Text.Json;

namespace MeNoter.Core;

/// <summary>Dati di una nota, salvati in note.json nella sua cartella.</summary>
public class NoteMeta
{
    public string Title { get; set; } = "";
    public DateTime CreatedAt { get; set; } = DateTime.Now;
    public double DurationSec { get; set; }
    /// <summary>registrazione, da trascrivere, trascrizione, pronta, errore.</summary>
    public string Status { get; set; } = "da trascrivere";
    public string? MicName { get; set; }
    public string? OutputName { get; set; }
    /// <summary>Ultima richiesta mandata a Claude (per dare il nome alla risposta incollata).</summary>
    public string? LastRequest { get; set; }
    /// <summary>True se ci sono le due tracce separate (Io / Altri).</summary>
    public bool TwoTracks { get; set; }
}

/// <summary>
/// Una nota è una cartella: note.json, audio.wav (le due tracce mescolate),
/// io.wav e altri.wav (le tracce separate), trascrizione.txt e la cartella "risposte".
/// Tutto in file normali: si aprono anche senza MeNoter e si sincronizzano con OneDrive.
/// </summary>
public class Note
{
    public string Folder { get; }
    public NoteMeta Meta { get; private set; }

    public Note(string folder, NoteMeta meta)
    {
        Folder = folder;
        Meta = meta;
    }

    public string MetaPath => Path.Combine(Folder, "note.json");
    public string AudioPath => Path.Combine(Folder, "audio.wav");
    public string MicPath => Path.Combine(Folder, "io.wav");
    public string SysPath => Path.Combine(Folder, "altri.wav");
    public string TranscriptPath => Path.Combine(Folder, "trascrizione.txt");
    public string PreviousTranscriptPath => Path.Combine(Folder, "trascrizione-precedente.txt");
    public string LogPath => Path.Combine(Folder, "trascrizione-log.txt");
    public string ResponsesDir => Path.Combine(Folder, "risposte");
    public string RequestsDir => Path.Combine(Folder, "per-claude");

    public string Transcript => File.Exists(TranscriptPath) ? File.ReadAllText(TranscriptPath) : "";

    public bool HasTranscript => File.Exists(TranscriptPath) && new FileInfo(TranscriptPath).Length > 0;

    public void Save()
    {
        File.WriteAllText(MetaPath, JsonSerializer.Serialize(Meta, new JsonSerializerOptions { WriteIndented = true }));
    }

    public void Reload()
    {
        var m = NoteStore.ReadMeta(MetaPath);
        if (m != null) Meta = m;
    }

    public static readonly string[] ImageExtensions = { ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp" };

    /// <summary>Risposte di Claude salvate (testi .md e immagini), dalla più vecchia.</summary>
    public List<string> Responses() =>
        Directory.Exists(ResponsesDir)
            ? Directory.GetFiles(ResponsesDir)
                .Where(f => f.EndsWith(".md", StringComparison.OrdinalIgnoreCase) || IsImage(f))
                .OrderBy(f => File.GetCreationTime(f)).ToList()
            : new List<string>();

    public static bool IsImage(string path) =>
        ImageExtensions.Contains(Path.GetExtension(path).ToLowerInvariant());

    /// <summary>Nome libero per una nuova risposta (testo o immagine) con l'etichetta della richiesta.</summary>
    public string NewResponsePath(string label, string extension)
    {
        Directory.CreateDirectory(ResponsesDir);
        return Path.Combine(ResponsesDir, $"{NoteStore.Safe(label)} {DateTime.Now:yyyy-MM-dd HH.mm.ss}{extension}");
    }

    public string SaveResponse(string label, string text)
    {
        var path = NewResponsePath(label, ".md");
        File.WriteAllText(path, text.Trim() + Environment.NewLine);
        return path;
    }

    public string Subtitle
    {
        get
        {
            var parts = new List<string> { Meta.CreatedAt.ToString("ddd d MMM yyyy, HH:mm", NoteStore.Italian) };
            if (Meta.DurationSec > 0) parts.Add(NoteStore.FormatTime(TimeSpan.FromSeconds(Meta.DurationSec)));
            if (Meta.Status != "pronta") parts.Add(Meta.Status);
            return string.Join(" · ", parts);
        }
    }

    public override string ToString() => Meta.Title;
}

public static class NoteStore
{
    public static readonly System.Globalization.CultureInfo Italian = new("it-IT");

    public static Note Create(string root, string title)
    {
        var now = DateTime.Now;
        var name = $"{now:yyyy-MM-dd HH.mm} - {Safe(title)}";
        var folder = Path.Combine(root, name);
        int i = 2;
        while (Directory.Exists(folder)) folder = Path.Combine(root, $"{name} ({i++})");
        Directory.CreateDirectory(folder);
        var note = new Note(folder, new NoteMeta { Title = title, CreatedAt = now });
        note.Save();
        return note;
    }

    public static List<Note> List(string root)
    {
        var list = new List<Note>();
        if (!Directory.Exists(root)) return list;
        foreach (var dir in Directory.GetDirectories(root))
        {
            var meta = ReadMeta(Path.Combine(dir, "note.json"));
            if (meta != null) list.Add(new Note(dir, meta));
        }
        return list.OrderByDescending(n => n.Meta.CreatedAt).ToList();
    }

    public static NoteMeta? ReadMeta(string path)
    {
        try
        {
            return File.Exists(path) ? JsonSerializer.Deserialize<NoteMeta>(File.ReadAllText(path)) : null;
        }
        catch
        {
            return null;
        }
    }

    public static string Safe(string s)
    {
        var bad = Path.GetInvalidFileNameChars();
        var clean = new string(s.Select(c => bad.Contains(c) ? ' ' : c).ToArray()).Trim();
        if (clean.Length > 60) clean = clean[..60].Trim();
        return clean.Length == 0 ? "Nota" : clean;
    }

    public static string FormatTime(TimeSpan t) =>
        t.TotalHours >= 1 ? $"{(int)t.TotalHours}:{t.Minutes:00}:{t.Seconds:00}" : $"{t.Minutes:00}:{t.Seconds:00}";
}
