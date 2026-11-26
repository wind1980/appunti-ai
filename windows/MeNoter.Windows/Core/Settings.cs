using System.Text.Json;

namespace MeNoter.Core;

/// <summary>Impostazioni salvate in %LOCALAPPDATA%\MeNoter\settings.json.</summary>
public class Settings
{
    /// <summary>Microfono scelto (id del dispositivo Windows); null = predefinito per le chiamate.</summary>
    public string? MicId { get; set; }

    /// <summary>Uscita audio da registrare (casse, cuffie, auricolari BT); null = predefinita per le chiamate.</summary>
    public string? OutputId { get; set; }

    /// <summary>Modello whisper: base, small, medium.</summary>
    public string Model { get; set; } = "small";

    /// <summary>Lingua della trascrizione: "it", "en"… oppure "auto".</summary>
    public string Language { get; set; } = "it";

    /// <summary>Cartella delle note; null = OneDrive\MeNoter se c'è OneDrive, altrimenti Documenti\MeNoter.</summary>
    public string? NotesFolder { get; set; }

    /// <summary>Apre l'app Claude per desktop (se installata) invece di claude.ai nel browser.</summary>
    public bool OpenClaudeDesktop { get; set; } = true;

    /// <summary>Con l'app Claude per desktop, incolla da sola la richiesta nella chat (poi si preme Invio).</summary>
    public bool AutoPaste { get; set; } = true;

    /// <summary>Trascrive solo i tratti con voce (più veloce). Se manca del testo, si disattiva.</summary>
    public bool SkipSilence { get; set; } = true;

    /// <summary>Servizi su cui copiare le note: "google", "onedrive", "box" (vuoto = nessuna sincronizzazione).</summary>
    public List<string> SyncEnabled { get; set; } = new();

    /// <summary>Cartella scelta a mano per un servizio (se vuota si usa quella trovata da sola).</summary>
    public Dictionary<string, string> SyncFolders { get; set; } = new();

    /// <summary>Ingrandimento del testo di risposte e trascrizione (1 = normale).</summary>
    public double TextZoom { get; set; } = 1.0;

    /// <summary>Trascrive da sola appena finisce la registrazione.</summary>
    public bool AutoTranscribe { get; set; } = true;

    public static string AppDataDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MeNoter");

    public static string ModelsDir => Path.Combine(AppDataDir, "modelli");

    private static string FilePath => Path.Combine(AppDataDir, "settings.json");

    public static Settings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
        }
        catch
        {
            // File rovinato: si riparte dalle impostazioni predefinite.
        }
        return new Settings();
    }

    public void Save()
    {
        Directory.CreateDirectory(AppDataDir);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }

    public string ResolveNotesFolder()
    {
        if (!string.IsNullOrWhiteSpace(NotesFolder)) return NotesFolder!;
        var oneDrive = Environment.GetEnvironmentVariable("OneDrive");
        var root = !string.IsNullOrEmpty(oneDrive) && Directory.Exists(oneDrive)
            ? oneDrive
            : Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
        return Path.Combine(root, "MeNoter");
    }
}
