using System.Collections.Specialized;
using System.Diagnostics;

namespace MeNoter.Core;

/// <summary>Richiesta a Claude: stessi comandi dell'app Android, così le risposte sono uguali.</summary>
public record ClaudeRequest(string Key, string Label, string Prompt)
{
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
/// prepara un file .txt con il comando in cima e la trascrizione, lo mette negli Appunti di Windows
/// e apre Claude. Lì basta Ctrl+V e Invio.
/// </summary>
public static class ClaudeBridge
{
    public static string PrepareFile(Note note, ClaudeRequest request)
    {
        Directory.CreateDirectory(note.RequestsDir);
        var header = $"{note.Meta.Title} – {note.Meta.CreatedAt.ToString("d MMMM yyyy, HH:mm", NoteStore.Italian)}";
        var body =
            $"RICHIESTA PER CLAUDE – {request.Label.ToUpperInvariant()}\n\n{request.FullPrompt(note.Meta.TwoTracks)}\n\n" +
            $"==========\nTRASCRIZIONE – {header}\n\n{note.Transcript}";
        var path = Path.Combine(note.RequestsDir, $"{NoteStore.Safe(request.Label)} - {NoteStore.Safe(note.Meta.Title)}.txt");
        File.WriteAllText(path, body.Replace("\r\n", "\n").Replace("\n", Environment.NewLine));
        return path;
    }

    /// <summary>File negli Appunti (si incolla in Claude come allegato) e Claude aperto.</summary>
    public static void Send(string filePath, bool desktopApp)
    {
        var files = new StringCollection { filePath };
        System.Windows.Clipboard.SetFileDropList(files);
        Open(desktopApp);
    }

    public static void Open(bool desktopApp)
    {
        if (desktopApp)
        {
            try
            {
                // L'app Claude per desktop registra il protocollo claude://
                Process.Start(new ProcessStartInfo("claude://claude.ai/new") { UseShellExecute = true });
                return;
            }
            catch
            {
                // App non installata: si apre il browser.
            }
        }
        Process.Start(new ProcessStartInfo("https://claude.ai/new") { UseShellExecute = true });
    }
}
