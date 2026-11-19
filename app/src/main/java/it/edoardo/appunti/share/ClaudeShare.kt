package it.edoardo.appunti.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import it.edoardo.appunti.ReceiveTextActivity
import it.edoardo.appunti.app
import it.edoardo.appunti.data.Note
import it.edoardo.appunti.data.KIND_IMAGE
import it.edoardo.appunti.data.KIND_PDF
import it.edoardo.appunti.data.PromptKind
import it.edoardo.appunti.data.SummaryLevel
import it.edoardo.appunti.data.Template
import it.edoardo.appunti.data.Transcript
import java.io.File

/**
 * Manda prompt + trascrizione all'app Claude con il menu Condividi di Android.
 * L'app Claude non accetta file audio: si manda solo testo (nel messaggio o in un .txt).
 */
object ClaudeShare {
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    /** Oltre questa lunghezza la trascrizione va in un file .txt allegato. */
    private const val MAX_INLINE_CHARS = 12_000

    /** Aggiunto a tutte le richieste tranne la domanda libera: risposte pronte da salvare. */
    private const val NO_CHATTER =
        "Rispondi solo con il contenuto richiesto: niente frasi introduttive, niente commenti, " +
            "proposte o domande alla fine."

    /** Prompt completo per un tipo di richiesta (con la domanda, per [PromptKind.ASK] e [PromptKind.MULTI]). */
    fun fullPrompt(context: Context, kind: PromptKind, question: String? = null): String {
        val prefs = context.app.prefs
        var prompt = prefs.prompt(kind)
        if (question != null) {
            prompt = if (prompt.contains("{domanda}")) prompt.replace("{domanda}", question.trim())
            else "$prompt\n\nDomanda: ${question.trim()}"
        }
        if (kind == PromptKind.TRANSLATE) {
            val lang = prefs.translateTarget
            prompt = if (prompt.contains("{lingua}")) prompt.replace("{lingua}", lang)
            else "$prompt\n\nLingua di arrivo: $lang."
        }
        if (kind == PromptKind.SUMMARY) {
            prefs.summaryLevel.instruction.takeIf { it.isNotEmpty() }?.let { prompt = "$prompt\n$it" }
        }
        return if (kind == PromptKind.ASK || kind == PromptKind.MULTI) prompt else "$prompt\n$NO_CHATTER"
    }

    private fun remember(context: Context, noteId: Long, kindKey: String) {
        val prefs = context.app.prefs
        prefs.lastSentNoteId = noteId
        prefs.lastSentKind = kindKey
        prefs.lastSentAt = System.currentTimeMillis()
    }

    /** Nuova chat in Claude con richiesta + trascrizione. */
    fun share(context: Context, note: Note, kind: PromptKind, question: String? = null) {
        val label = if (kind == PromptKind.TRANSLATE) "Traduzione in ${context.app.prefs.translateTarget}" else kind.label
        send(context, note, label, fullPrompt(context, kind, question), kind.key)
    }

    /** Nuova chat in Claude con un modello personale + trascrizione. */
    fun shareTemplate(context: Context, note: Note, template: Template) {
        send(context, note, template.name, "${template.prompt.trim()}\n$NO_CHATTER", template.key)
    }

    /** Riassunto con un livello di dettaglio diverso da quello scelto nelle Impostazioni. */
    fun shareSummary(context: Context, note: Note, level: SummaryLevel) {
        var prompt = context.app.prefs.prompt(PromptKind.SUMMARY)
        if (level.instruction.isNotEmpty()) prompt = "$prompt\n${level.instruction}"
        send(context, note, "Riassunto ${level.label.lowercase()}", "$prompt\n$NO_CHATTER", PromptKind.SUMMARY.key)
    }

    private fun send(context: Context, note: Note, label: String, prompt: String, kindKey: String) {
        val prefs = context.app.prefs
        val transcript = Transcript.readable(note)
        val header = "${note.title} – ${Transcript.date(note.createdAt)}"
        val asFile = prefs.alwaysAsFile || transcript.length > MAX_INLINE_CHARS
        val intent = if (asFile) {
            // L'app Claude scarta il testo del messaggio quando c'è un allegato:
            // il comando va quindi in cima al file, prima della trascrizione.
            val body = "RICHIESTA PER CLAUDE – ${label.uppercase()}\n\n$prompt\n\n" +
                "==========\nTRASCRIZIONE – $header\n\n$transcript"
            fileIntent(context, "$label - ${note.title}", body, note.title)
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, note.title)
                putExtra(Intent.EXTRA_TEXT, "$prompt\n\n---\nTRASCRIZIONE – $header\n\n$transcript")
            }
        }
        remember(context, note.id, kindKey)
        open(context, intent)
    }

    /**
     * Domanda su più note: crea il testo con tutte le note scelte (riassunto più recente + trascrizione)
     * e lo manda a Claude come file. [targetNoteId] è la nota in cui salvare la risposta.
     * Restituisce il numero di caratteri inviati.
     */
    suspend fun shareMulti(context: Context, targetNoteId: Long, notes: List<Note>, question: String): Int {
        val dao = context.app.db.notes()
        val prompt = fullPrompt(context, PromptKind.MULTI, question)
        val body = buildString {
            append("RICHIESTA PER CLAUDE – DOMANDA SU ${notes.size} NOTE\n\n").append(prompt).append("\n\n")
            for (n in notes) {
                append("=== NOTA: ").append(n.title).append(" – ").append(Transcript.date(n.createdAt)).append(" ===\n\n")
                dao.latestResponse(n.id, PromptKind.SUMMARY.key)?.let {
                    append("RIASSUNTO:\n").append(it.trim()).append("\n\n")
                }
                val transcript = Transcript.readable(n)
                if (transcript.isNotBlank()) {
                    append("TRASCRIZIONE:\n").append(transcript).append("\n\n")
                } else {
                    // Nota di solo testo: si mandano le risposte salvate.
                    dao.responsesOf(n.id).filter { it.kind != KIND_IMAGE && it.kind != KIND_PDF }.forEach {
                        append(it.text.trim()).append("\n\n")
                    }
                }
            }
        }
        val intent = fileIntent(context, "Domanda su ${notes.size} note", body, "Domanda su ${notes.size} note")
        remember(context, targetNoteId, PromptKind.MULTI.key)
        open(context, intent)
        return body.length
    }

    private fun fileIntent(context: Context, fileName: String, body: String, subject: String): Intent {
        val file = writeTxt(context, fileName, body)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            // Se un giorno l'app Claude userà anche il messaggio, arriverà questo.
            putExtra(Intent.EXTRA_TEXT, "Segui la richiesta scritta all'inizio del file allegato.")
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun open(context: Context, intent: Intent) {
        // Claude si apre nella sua finestra: la chat resta aperta lì e si può riprendere.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent(intent).setPackage(CLAUDE_PACKAGE))
        } catch (e: ActivityNotFoundException) {
            // App Claude non installata o non accetta questo tipo: si apre il menu di scelta.
            val chooser = Intent.createChooser(intent, "Invia a…").apply {
                putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    arrayOf(ComponentName(context, ReceiveTextActivity::class.java))
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }

    fun copy(context: Context, label: String, text: String, toast: Boolean = true) {
        // Il testo copiato dall'app non va riproposto come "risposta di Claude".
        context.app.prefs.handledClipHash = text.trim().hashCode()
        context.getSystemService<ClipboardManager>()!!.setPrimaryClip(ClipData.newPlainText(label, text))
        if (toast) Toast.makeText(context, "Copiato", Toast.LENGTH_SHORT).show()
    }

    /** Condivide un testo qualsiasi (es. una risposta salvata) con le altre app: Notion, Docs, email. */
    fun shareText(context: Context, subject: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Condividi").apply {
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(context, ReceiveTextActivity::class.java))
            )
        })
    }

    /** Condivide un file (immagine PNG, pagina HTML) con le altre app. */
    fun shareFile(context: Context, file: File, mime: String, subject: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Condividi").apply {
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(context, ReceiveTextActivity::class.java))
            )
        })
    }

    fun shareDir(context: Context): File = File(context.cacheDir, "share").apply { mkdirs() }

    private fun writeTxt(context: Context, name: String, text: String): File {
        val dir = shareDir(context)
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
        val safe = name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60).ifBlank { "trascrizione" }
        return File(dir, "$safe.txt").apply { writeText(text) }
    }
}
