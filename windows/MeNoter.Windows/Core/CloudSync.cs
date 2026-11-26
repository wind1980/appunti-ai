namespace MeNoter.Core;

/// <summary>
/// Copia facoltativa delle note (audio originale, tracce, trascrizione, risposte e immagini di Claude)
/// in Google Drive, OneDrive e Box. Non usa API né password: scrive nella cartella che l'app del servizio
/// (Google Drive per desktop, OneDrive, Box Drive) tiene sincronizzata con il cloud.
/// La copia è in una sola direzione: dal PC al cloud. Nel cloud non si cancella mai nulla.
/// </summary>
public static class CloudSync
{
    public record Service(string Key, string Name, string App, Func<string?> Detect);

    public static readonly Service[] Services =
    {
        new("google", "Google Drive", "Google Drive per desktop", DetectGoogleDrive),
        new("onedrive", "OneDrive", "OneDrive", DetectOneDrive),
        new("box", "Box", "Box Drive", DetectBox),
    };

    public static string? DetectOneDrive()
    {
        foreach (var v in new[] { "OneDriveConsumer", "OneDrive", "OneDriveCommercial" })
        {
            var p = Environment.GetEnvironmentVariable(v);
            if (!string.IsNullOrEmpty(p) && Directory.Exists(p)) return p;
        }
        return null;
    }

    /// <summary>Google Drive per desktop crea un'unità (di solito G:) con "Il mio Drive" / "My Drive".</summary>
    public static string? DetectGoogleDrive()
    {
        string[] names = { "Il mio Drive", "My Drive", "Mon Drive", "Mi unidad", "Meine Ablage" };
        try
        {
            foreach (var d in DriveInfo.GetDrives())
            {
                try
                {
                    if (!d.IsReady) continue;
                    foreach (var n in names)
                    {
                        var p = Path.Combine(d.RootDirectory.FullName, n);
                        if (Directory.Exists(p)) return p;
                    }
                }
                catch
                {
                    // Unità non leggibile: si passa alla prossima.
                }
            }
        }
        catch
        {
        }
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        foreach (var n in new[] { "Google Drive", "My Drive", "Il mio Drive" })
        {
            var p = Path.Combine(home, n);
            if (Directory.Exists(p)) return p;
        }
        return null;
    }

    public static string? DetectBox()
    {
        var p = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Box");
        return Directory.Exists(p) ? p : null;
    }

    /// <summary>Cartelle di destinazione attive (ognuna con dentro la sottocartella MeNoter).</summary>
    public static List<(string Name, string Folder)> Targets(Settings s)
    {
        var list = new List<(string, string)>();
        foreach (var svc in Services)
        {
            if (!s.SyncEnabled.Contains(svc.Key)) continue;
            var root = s.SyncFolders.TryGetValue(svc.Key, out var f) && !string.IsNullOrWhiteSpace(f) ? f : svc.Detect();
            if (string.IsNullOrWhiteSpace(root)) continue;
            list.Add((svc.Name, Path.Combine(root, "MeNoter")));
        }
        return list;
    }

    public record Result(int Copied, List<string> Errors);

    /// <summary>
    /// Copia nelle destinazioni i file nuovi o cambiati di ogni nota. Salta la nota indicata
    /// (quella in registrazione, i cui file stanno ancora crescendo) e il cestino di MeNoter.
    /// </summary>
    public static Result Run(string notesRoot, IEnumerable<(string Name, string Folder)> targets, string? skipFolder, CancellationToken ct)
    {
        int copied = 0;
        var errors = new List<string>();
        if (!Directory.Exists(notesRoot)) return new Result(0, errors);
        var source = Path.GetFullPath(notesRoot).TrimEnd('\\');
        foreach (var (name, folder) in targets)
        {
            try
            {
                var target = Path.GetFullPath(folder).TrimEnd('\\');
                // Se le note sono già in quella cartella (es. OneDrive\MeNoter) non c'è niente da copiare.
                if (string.Equals(target, source, StringComparison.OrdinalIgnoreCase)) continue;
                if (target.StartsWith(source + "\\", StringComparison.OrdinalIgnoreCase)) continue;
                Directory.CreateDirectory(target);
                foreach (var noteDir in Directory.GetDirectories(source))
                {
                    ct.ThrowIfCancellationRequested();
                    var dirName = Path.GetFileName(noteDir);
                    if (dirName == "_cestino") continue;
                    if (skipFolder != null && string.Equals(Path.GetFullPath(noteDir).TrimEnd('\\'),
                            Path.GetFullPath(skipFolder).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase)) continue;
                    copied += CopyChanged(noteDir, Path.Combine(target, dirName), ct);
                }
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex)
            {
                errors.Add($"{name}: {ex.Message}");
            }
        }
        return new Result(copied, errors);
    }

    private static int CopyChanged(string from, string to, CancellationToken ct)
    {
        int n = 0;
        Directory.CreateDirectory(to);
        foreach (var file in Directory.GetFiles(from))
        {
            ct.ThrowIfCancellationRequested();
            var name = Path.GetFileName(file);
            if (name.EndsWith(".part", StringComparison.OrdinalIgnoreCase)) continue;
            var dest = Path.Combine(to, name);
            var src = new FileInfo(file);
            var dst = new FileInfo(dest);
            if (dst.Exists && dst.Length == src.Length && dst.LastWriteTimeUtc >= src.LastWriteTimeUtc.AddSeconds(-2)) continue;
            // Prima un file temporaneo, poi il nome giusto: l'app del cloud non carica mai un file a metà.
            var tmp = dest + ".menoter-tmp";
            File.Copy(file, tmp, true);
            File.Move(tmp, dest, true);
            File.SetLastWriteTimeUtc(dest, src.LastWriteTimeUtc);
            n++;
        }
        foreach (var dir in Directory.GetDirectories(from))
            n += CopyChanged(dir, Path.Combine(to, Path.GetFileName(dir)), ct);
        return n;
    }
}
