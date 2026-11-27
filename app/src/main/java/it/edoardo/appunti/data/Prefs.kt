package it.edoardo.appunti.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
La trascrizione è automatica e può contenere errori: correggili dal contesto, senza inventare nulla. Quando citi un punto preciso indica il minuto [mm:ss]. Rispondi in italiano, con titoli ed elenchi puntati.
Comincia la risposta con una riga "# " seguita da un titolo breve per questa nota (massimo 6 parole)."""
    ),
    ACTIONS(
        "actions", "Azioni",
        """Dalla trascrizione qui sotto (o nel file allegato) estrai solo le cose da fare.
Scrivile come lista di caselle da spuntare, UNA PER RIGA, esattamente in questo formato:
- [ ] cosa fare — Responsabile: Nome — Scadenza: gg/mm/aaaa — [mm:ss]
Togli "Responsabile" o "Scadenza" se non vengono citati; [mm:ss] è il minuto in cui se ne parla. Non aggiungere titoli né altro testo. Rispondi in italiano."""
    ),
    MINDMAP(
        "mindmap", "Mappa",
        """Crea una mappa concettuale della trascrizione qui sotto (o nel file allegato): tema centrale al centro, da 4 a 7 rami principali, al massimo 3 livelli, testi brevi (massimo 6 parole per punto).
1. Disegnala come FILE IMMAGINE PNG scaricabile (usa l'esecuzione del codice, per esempio Python con matplotlib): sfondo chiaro, un colore diverso per ogni ramo, testo leggibile su telefono.
2. Nel messaggio scrivi solo la stessa mappa come elenco puntato annidato in Markdown:
# Tema centrale
- Ramo principale
  - Sotto-punto"""
    ),
    INFOGRAPHIC(
        "infographic", "Infografica",
        """Crea un'infografica che riassuma la trascrizione qui sotto (o nel file allegato): titolo, numeri o fatti chiave, decisioni, prossimi passi.
Creala come FILE IMMAGINE PNG scaricabile (usa l'esecuzione del codice, per esempio Python con matplotlib o Pillow), in formato verticale per smartphone (circa 1080 px di larghezza), colori vivaci, testo grande e leggibile, in italiano. Nel messaggio scrivi solo una riga che dice che il file è pronto."""
    ),
    TRANSLATE(
        "translate", "Traduci",
        """Traduci in {lingua} la trascrizione qui sotto (o nel file allegato), mantenendo i paragrafi e i minuti [mm:ss]. Correggi gli errori evidenti di trascrizione senza cambiare il senso."""
    ),
    EMAIL(
        "email", "Email",
        """Scrivi una email di follow-up per i partecipanti a questa riunione (trascrizione qui sotto o nel file allegato): ringraziamento breve, punti principali discussi, decisioni, prossimi passi con responsabili e scadenze. Tono professionale ma cordiale, in italiano. Proponi anche l'oggetto."""
    ),
    SPEAKERS(
        "speakers", "Interlocutori",
        """La trascrizione qui sotto (o nel file allegato) è automatica e non indica chi parla. Riscrivila dividendola per interlocutore.
- Riconosci i cambi di voce dal contenuto (domande e risposte, saluti, nomi usati, ruoli).
- Usa i nomi veri quando si capiscono dal contesto, altrimenti "Persona 1", "Persona 2"…
- Formato: un intervento per riga, esattamente così: [mm:ss] **Nome:** testo
- Non riassumere e non saltare nulla: riporta tutto il testo, correggendo solo gli errori evidenti di trascrizione.
- Se la trascrizione è troppo lunga per una sola risposta, fermati a fine intervento e scrivi "(continua)": ti chiederò il resto."""
    ),
    MULTI(
        "multi", "Domanda su più note",
        """Nel file allegato ci sono più note (trascrizioni di registrazioni, con eventuali riassunti), separate da righe "=== NOTA ... ===". Rispondi alla mia domanda usando solo il contenuto di queste note. Per ogni informazione indica da quale nota viene (titolo e data) e, se c'è, il minuto [mm:ss]. Se le note non contengono la risposta, dillo.

Domanda: {domanda}"""
    ),
    ASK(
        "ask", "Domanda libera",
        """Ecco la trascrizione di una registrazione (qui sotto o nel file allegato). Rispondi alla mia domanda usando solo quello che c'è nella trascrizione e indica i minuti [mm:ss] da cui prendi le informazioni. Poi potrò farti altre domande nella stessa chat.

Domanda: {domanda}"""
    );

    companion object {
        fun fromKey(key: String?): PromptKind? = entries.firstOrNull { it.key == key }
    }
}

/** Modello personale: un comando per Claude scritto dall'utente (es. "Verbale", "Lezione"). */
data class Template(val name: String, val prompt: String) {
    /** Chiave salvata con le risposte, per mostrare il nome del modello. */
    val key: String get() = TEMPLATE_PREFIX + name

    companion object {
        const val TEMPLATE_PREFIX = "t:"
    }
}

/** Livello di dettaglio del riassunto. */
enum class SummaryLevel(val key: String, val label: String, val instruction: String) {
    SHORT("short", "Breve", "Livello di dettaglio: BREVE. Il riassunto sta in 3-5 righe; per decisioni, azioni e domande solo i punti principali, massimo 3 per sezione."),
    NORMAL("normal", "Normale", ""),
    DETAILED("detailed", "Dettagliato", "Livello di dettaglio: DETTAGLIATO. Dopo il riassunto aggiungi un resoconto per argomento, con un titolo per ogni argomento discusso, i punti di vista emersi, numeri, nomi e date citati.");

    companion object {
        fun fromKey(key: String?): SummaryLevel = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

/** Lingue proposte da "Traduci". */
val TRANSLATE_LANGUAGES = listOf(
    "inglese", "italiano", "francese", "spagnolo", "tedesco", "portoghese",
    "olandese", "polacco", "rumeno", "russo", "arabo", "cinese", "giapponese",
)

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

    /** Quando inizia una chiamata, una notifica propone di registrarla. */
    var offerCallRecording: Boolean
        get() = sp.getBoolean("offer_call_recording", true)
        set(v) = sp.edit().putBoolean("offer_call_recording", v).apply()

    /**
     * Durante una chiamata registrata con auricolare, cuffie o Bluetooth, mette da solo il vivavoce
     * (unico modo per registrare anche gli altri). Se è spento arriva una notifica con il pulsante.
     */
    var autoSpeaker: Boolean
        get() = sp.getBoolean("auto_speaker", false)
        set(v) = sp.edit().putBoolean("auto_speaker", v).apply()

    /** Manda sempre la trascrizione come file .txt invece che nel testo del messaggio. */
    var alwaysAsFile: Boolean
        get() = sp.getBoolean("always_file", false)
        set(v) = sp.edit().putBoolean("always_file", v).apply()

    // Ultimo invio a Claude: serve a proporre la nota giusta quando la risposta torna indietro.
    var lastSentNoteId: Long
        get() = sp.getLong("last_sent_note", -1)
        set(v) = sp.edit().putLong("last_sent_note", v).apply()
    var lastSentKind: String?
        get() = sp.getString("last_sent_kind", null)
        set(v) = sp.edit().putString("last_sent_kind", v).apply()
    var lastSentAt: Long
        get() = sp.getLong("last_sent_at", 0)
        set(v) = sp.edit().putLong("last_sent_at", v).apply()

    /** Nota mandata a Claude nelle ultime 12 ore, se c'è. */
    fun recentSentNoteId(): Long? =
        lastSentNoteId.takeIf { it >= 0 && System.currentTimeMillis() - lastSentAt < 12 * 3_600_000L }

    /** Impronta dell'ultimo testo negli appunti già gestito (salvato, ignorato o copiato dall'app). */
    var handledClipHash: Int
        get() = sp.getInt("handled_clip", 0)
        set(v) = sp.edit().putInt("handled_clip", v).apply()

    /** Livello di dettaglio del pulsante "Riassunto". */
    var summaryLevel: SummaryLevel
        get() = SummaryLevel.fromKey(sp.getString("summary_level", null))
        set(v) = sp.edit().putString("summary_level", v.key).apply()

    /** Lingua di arrivo di "Traduci" (l'ultima scelta). */
    var translateTarget: String
        get() = sp.getString("translate_target", "inglese") ?: "inglese"
        set(v) = sp.edit().putString("translate_target", v).apply()

    /** Modelli personali, nell'ordine in cui compaiono. */
    var templates: List<Template>
        get() = try {
            val arr = JSONArray(sp.getString("templates", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Template(o.getString("name"), o.getString("prompt"))
            }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("name", it.name).put("prompt", it.prompt)) }
            sp.edit().putString("templates", arr.toString()).apply()
        }

    fun template(key: String?): Template? =
        key?.takeIf { it.startsWith(Template.TEMPLATE_PREFIX) }?.let { k -> templates.firstOrNull { it.key == k } }

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
