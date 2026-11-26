using System.Diagnostics;

namespace MeNoter.Core;

/// <summary>Richiesta a Claude: stessi comandi dell'app Android, così le risposte sono uguali.</summary>
public record ClaudeRequest(string Key, string Label, string Prompt)
{
    /// <summary>Icona del riquadro, come nella barra dell'app Android.</summary>
    public string Icon => Key switch
    {
        "summary" => "✨",
        "actions" => "☑",
        "speakers" => "👥",
        "mindmap" => "🧭",
        "infographic" => "📊",
        "email" => "✉",
        "translate" => "🌐",
        _ => "💬",
    };

    private const string NoChatter =
        "Rispondi solo con il contenuto richiesto: niente frasi introduttive, niente commenti, proposte o domande alla fine.";

    private const string TwoTracksNote =
        "Nota: la trascrizione viene da una chiamata registrata sul PC. \"Io\" è chi ha registrato, " +
        "\"Altri\" sono tutti gli altri partecipanti (possono essere più persone).";

    public static readonly ClaudeRequest[] All =
    {
        new("summary", "Riassunto",
            """
            Sei il mio assistente per gli appunti. Dalla trascrizione qui sotto (o nel file allegato) prepara:
            1. Un riassunto di 5-10 righe.
            2. Le decisioni prese.
            3. Le azioni da fare, con responsabile e scadenza se vengono citati.
            4. Le domande rimaste aperte.
            La trascrizione è automatica e può contenere errori: correggili dal contesto, senza inventare nulla. Quando citi un punto preciso indica il minuto [mm:ss]. Rispondi in italiano, con titoli ed elenchi puntati.
            Comincia la risposta con una riga "# " seguita da un titolo breve per questa nota (massimo 6 parole).
            """),
        new("actions", "Azioni",
            """
            Dalla trascrizione qui sotto (o nel file allegato) estrai solo le cose da fare.
            Scrivile come lista di caselle da spuntare, UNA PER RIGA, esattamente in questo formato:
            - [ ] cosa fare — Responsabile: Nome — Scadenza: gg/mm/aaaa — [mm:ss]
            Togli "Responsabile" o "Scadenza" se non vengono citati; [mm:ss] è il minuto in cui se ne parla. Non aggiungere titoli né altro testo. Rispondi in italiano.
            """),
        new("speakers", "Interlocutori",
            """
            Nella trascrizione qui sotto (o nel file allegato) gli interventi "Altri" possono essere di persone diverse. Riscrivila indicando chi parla.
            - Riconosci i cambi di voce dal contenuto (domande e risposte, saluti, nomi usati, ruoli).
            - Usa i nomi veri quando si capiscono dal contesto, altrimenti "Persona 1", "Persona 2"…; "Io" resta "Io".
            - Formato: un intervento per riga, esattamente così: [mm:ss] **Nome:** testo
            - Non riassumere e non saltare nulla: riporta tutto il testo, correggendo solo gli errori evidenti di trascrizione.
            - Se la trascrizione è troppo lunga per una sola risposta, fermati a fine intervento e scrivi "(continua)": ti chiederò il resto.
            """),
        new("mindmap", "Mappa",
            """
            Crea una mappa concettuale della trascrizione qui sotto (o nel file allegato): tema centrale al centro, da 4 a 7 rami principali, al massimo 3 livelli, testi brevi (massimo 6 parole per punto).
            1. Disegnala come FILE IMMAGINE PNG scaricabile (usa l'esecuzione del codice, per esempio Python con matplotlib): sfondo chiaro, un colore diverso per ogni ramo, testo leggibile.
            2. Nel messaggio scrivi solo la stessa mappa come elenco puntato annidato in Markdown:
            # Tema centrale
            - Ramo principale
              - Sotto-punto
            """),
        new("infographic", "Infografica",
            """
            Crea un'infografica che riassuma la trascrizione qui sotto (o nel file allegato): titolo, numeri o fatti chiave, decisioni, prossimi passi.
            Creala come FILE IMMAGINE PNG scaricabile (usa l'esecuzione del codice, per esempio Python con matplotlib o Pillow), in formato verticale per smartphone (circa 1080 px di larghezza), colori vivaci, testo grande e leggibile, in italiano. Nel messaggio scrivi solo una riga che dice che il file è pronto.
            """),
        new("email", "Email",
            """
            Scrivi una email di follow-up per i partecipanti a questa riunione (trascrizione qui sotto o nel file allegato): ringraziamento breve, punti principali discussi, decisioni, prossimi passi con responsabili e scadenze. Tono professionale ma cordiale, in italiano. Proponi anche l'oggetto.
            """),
        new("translate", "Traduci in inglese",
            """
            Traduci in inglese la trascrizione qui sotto (o nel file allegato), mantenendo i paragrafi e i minuti [mm:ss]. Correggi gli errori evidenti di trascrizione senza cambiare il senso.
            """),
    };

    public static ClaudeRequest Ask(string question) => new("ask", "Domanda",
        "Rispondi alla mia domanda usando la trascrizione qui sotto (o nel file allegato). " +
        "Quando citi un punto preciso indica il minuto [mm:ss]. Rispondi in italiano.\n\nDomanda: " + question.Trim());

    public string FullPrompt(bool twoTracks)
    {
        var p = Prompt.Trim();
        if (twoTracks) p += "\n" + TwoTracksNote;
        return Key == "ask" ? p : p + "\n" + NoChatter;
    }
}

/// <summary>
/// Manda una nota all'app Claude per desktop (o a claude.ai) con il tuo account Pro, senza API:
/// mette negli Appunti di Windows il testo con il comando in cima e la trascrizione (Claude, se è lungo,
/// lo trasforma da solo in un allegato), apre Claude e, con l'app desktop, lo incolla da solo.
/// Il testo resta anche in un file .txt nella cartella della nota.
/// </summary>
public static class ClaudeBridge
{
    /// <summary>Solo la trascrizione con la sua intestazione (diventa l'allegato in Claude).</summary>
    public static string BuildTranscript(Note note)
    {
        var header = $"{note.Meta.Title} – {note.Meta.CreatedAt.ToString("d MMMM yyyy, HH:mm", NoteStore.Italian)}";
        return $"TRASCRIZIONE – {header}\n\n{note.Transcript.Replace("\r\n", "\n")}";
    }

    /// <summary>Solo il comando, da scrivere nella chat accanto all'allegato.</summary>
    public static string BuildCommand(Note note, ClaudeRequest request) =>
        $"{request.FullPrompt(note.Meta.TwoTracks)}\n\n(La trascrizione è nel testo allegato qui sopra.)";

    public static string BuildText(Note note, ClaudeRequest request)
    {
        var header = $"{note.Meta.Title} – {note.Meta.CreatedAt.ToString("d MMMM yyyy, HH:mm", NoteStore.Italian)}";
        return $"RICHIESTA PER CLAUDE – {request.Label.ToUpperInvariant()}\n\n{request.FullPrompt(note.Meta.TwoTracks)}\n\n" +
               $"==========\nTRASCRIZIONE – {header}\n\n{note.Transcript.Replace("\r\n", "\n")}";
    }

    public static string PrepareFile(Note note, ClaudeRequest request)
    {
        Directory.CreateDirectory(note.RequestsDir);
        var body = BuildText(note, request);
        var path = Path.Combine(note.RequestsDir, $"{NoteStore.Safe(request.Label)} - {NoteStore.Safe(note.Meta.Title)}.txt");
        File.WriteAllText(path, body.Replace("\r\n", "\n").Replace("\n", Environment.NewLine));
        return path;
    }

    /// <summary>
    /// Apre Claude e gli passa la richiesta. Con l'app desktop e "incolla da solo" attivo fa due incolla:
    /// prima la trascrizione (Claude trasforma da solo un testo lungo in un allegato, come un file .txt),
    /// poi il comando, che resta scritto nel messaggio. Restituisce true se ha incollato: basta premere Invio.
    /// Altrimenti negli Appunti resta tutto in un unico testo (comando + trascrizione) da incollare con Ctrl+V.
    /// </summary>
    public static async Task<bool> SendAsync(Note note, ClaudeRequest request, bool desktopApp, bool autoPaste)
    {
        SetClipboard(BuildText(note, request));
        bool desktop = Open(desktopApp);
        if (!desktop || !autoPaste) return false;
        SetClipboard(BuildTranscript(note));
        if (!await AutoPaste.PasteIntoClaudeAsync()) return false;
        // Il tempo per creare l'allegato, poi il comando.
        await Task.Delay(1500);
        SetClipboard(BuildCommand(note, request));
        await AutoPaste.PasteAgainAsync();
        await Task.Delay(500);
        // Negli Appunti torna il testo completo, nel caso serva incollarlo di nuovo.
        SetClipboard(BuildText(note, request));
        return true;
    }

    // Un'app che sta usando gli Appunti può bloccarli per un attimo: si riprova.
    private static void SetClipboard(string text)
    {
        for (int i = 0; i < 5; i++)
        {
            try
            {
                System.Windows.Clipboard.SetText(text.Replace("\r\n", "\n").Replace("\n", Environment.NewLine));
                return;
            }
            catch (System.Runtime.InteropServices.COMException)
            {
                Thread.Sleep(100);
            }
        }
    }

    /// <summary>Se il protocollo claude:// non è registrato Windows mostrerebbe "scegli un'app": meglio il browser.</summary>
    private static bool DesktopAppInstalled()
    {
        try
        {
            using var key = Microsoft.Win32.Registry.ClassesRoot.OpenSubKey("claude");
            return key != null;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>Apre Esplora file con il file selezionato (per trascinarlo in Claude se l'incolla non va).</summary>
    public static void Reveal(string filePath) =>
        Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{filePath}\"") { UseShellExecute = true });

    /// <summary>Apre una chat nuova. True se ha aperto l'app desktop, false se il browser.</summary>
    public static bool Open(bool desktopApp)
    {
        if (desktopApp && DesktopAppInstalled())
        {
            try
            {
                // L'app Claude per desktop registra il protocollo claude://
                Process.Start(new ProcessStartInfo("claude://claude.ai/new") { UseShellExecute = true });
                return true;
            }
            catch
            {
                // Non si apre: si usa il browser.
            }
        }
        Process.Start(new ProcessStartInfo("https://claude.ai/new") { UseShellExecute = true });
        return false;
    }
}
