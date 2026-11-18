package it.edoardo.appunti.data

import android.content.Context
import java.io.File

/** Prompt pronti da mandare a Claude insieme alla trascrizione. L'ordine è quello della barra nella nota. */
enum class PromptKind(val key: String, val label: String, val default: String) {
    SUMMARY(
        "summary", "Riassunto",
        """Sei il mio assistente per gli appunti. Dalla trascrizione qui sotto (o nel file allegato) prepara:
1. Un riassunto di 5-10 righe.
2. Le decisioni prese.
3. Le azioni da fare, con responsabile e scadenza se vengono citati.
4. Le domande rimaste aperte.
La trascrizione è automatica e può contenere errori: correggili dal contesto, senza inventare nulla. Quando citi un punto preciso indica il minuto [mm:ss]. Rispondi in italiano, con titoli ed elenchi puntati."""
    ),
    ACTIONS(
        "actions", "Azioni",
        """Dalla trascrizione qui sotto (o nel file allegato) estrai solo le cose da fare, come lista di caselle da spuntare ("- [ ] ..."). Per ognuna indica chi deve farla e la scadenza se vengono citate, e il minuto [mm:ss] in cui se ne parla. Raggruppale per persona. Rispondi in italiano."""
    ),
    MINDMAP(
        "mindmap", "Mappa",
        """Crea una mappa mentale della trascrizione qui sotto (o nel file allegato): tema centrale, 4-7 rami principali e i sotto-punti più importanti. Disegnala come diagramma e poi riportala anche come elenco puntato annidato."""
    ),
    TRANSLATE(
        "translate", "Traduci",
        """Traduci in inglese la trascrizione qui sotto (o nel file allegato), mantenendo i paragrafi e i minuti [mm:ss]. Correggi gli errori evidenti di trascrizione senza cambiare il senso."""
    ),
    EMAIL(
        "email", "Email",
        """Scrivi una email di follow-up per i partecipanti a questa riunione (trascrizione qui sotto o nel file allegato): ringraziamento breve, punti principali discussi, decisioni, prossimi passi con responsabili e scadenze. Tono professionale ma cordiale, in italiano. Proponi anche l'oggetto."""
    ),
    ASK(
        "ask", "Domanda libera",
        """Ecco la trascrizione di una registrazione (qui sotto o nel file allegato). Rispondi alla mia domanda usando solo quello che c'è nella trascrizione e indica i minuti [mm:ss] da cui prendi le informazioni. Poi potrò farti altre domande nella stessa chat.

Domanda: {domanda}"""
    ),
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("appunti", Context.MODE_PRIVATE)

    /** Percorso del modello Whisper attivo (file .bin in formato ggml). */
    var modelPath: String?
        get() = sp.getString("model_path", null)?.takeIf { File(it).exists() }
        set(v) = sp.edit().putString("model_path", v).apply()

    /** Modello più preciso (e lento) usato dal comando "Migliora trascrizione". */
    var preciseModelPath: String?
        get() = sp.getString("precise_model_path", null)?.takeIf { File(it).exists() }
        set(v) = sp.edit().putString("precise_model_path", v).apply()

    /** "Migliora" parte solo quando il telefono è in carica. */
    var improveOnlyCharging: Boolean
        get() = sp.getBoolean("improve_charging", true)
        set(v) = sp.edit().putBoolean("improve_charging", v).apply()

    /** Salta silenzi e pause con il rilevatore di voce. */
    var skipSilence: Boolean
        get() = sp.getBoolean("skip_silence", true)
        set(v) = sp.edit().putBoolean("skip_silence", v).apply()

    /** Ritenta i pezzi difficili a temperatura più alta (più preciso ma molto più lento). */
    var retryHardParts: Boolean
        get() = sp.getBoolean("retry_hard", false)
        set(v) = sp.edit().putBoolean("retry_hard", v).apply()

    /** Codice lingua per Whisper: "it", "en" oppure "auto". */
    var language: String
        get() = sp.getString("language", "it") ?: "it"
        set(v) = sp.edit().putString("language", v).apply()

    var threads: Int
        get() = sp.getInt("threads", defaultThreads())
        set(v) = sp.edit().putInt("threads", v).apply()

    /** Testo di contesto per Whisper: nomi e termini ricorrenti migliorano la trascrizione. */
    var whisperHint: String
        get() = sp.getString("whisper_hint", "") ?: ""
        set(v) = sp.edit().putString("whisper_hint", v).apply()

    /** Manda sempre la trascrizione come file .txt invece che nel testo del messaggio. */
    var alwaysAsFile: Boolean
        get() = sp.getBoolean("always_file", false)
        set(v) = sp.edit().putBoolean("always_file", v).apply()

    fun prompt(kind: PromptKind): String = sp.getString("prompt_${kind.key}", null) ?: kind.default

    fun setPrompt(kind: PromptKind, text: String?) {
        if (text == null || text == kind.default) sp.edit().remove("prompt_${kind.key}").apply()
        else sp.edit().putString("prompt_${kind.key}", text).apply()
    }

    fun downloadId(fileName: String): Long = sp.getLong("dl_$fileName", -1)
    fun setDownloadId(fileName: String, id: Long) = sp.edit().putLong("dl_$fileName", id).apply()

    private fun defaultThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        // Sui telefoni conviene usare solo i core "grandi": 4 è un buon punto di partenza.
        return cores.coerceIn(1, 4)
    }
}
