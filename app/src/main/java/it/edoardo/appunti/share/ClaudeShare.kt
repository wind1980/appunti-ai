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
import it.edoardo.appunti.data.PromptKind
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

    /** Prompt completo per un tipo di richiesta (con la domanda, per [PromptKind.ASK]). */
    fun fullPrompt(context: Context, kind: PromptKind, question: String? = null): String {
        var prompt = context.app.prefs.prompt(kind)
        if (question != null) {
            prompt = if (prompt.contains("{domanda}")) prompt.replace("{domanda}", question.trim())
            else "$prompt\n\nDomanda: ${question.trim()}"
        }
        return if (kind == PromptKind.ASK) prompt else "$prompt\n$NO_CHATTER"
    }

    private fun remember(context: Context, note: Note, kind: PromptKind) {
        val prefs = context.app.prefs
        prefs.lastSentNoteId = note.id
        prefs.lastSentKind = kind.key
        prefs.lastSentAt = System.currentTimeMillis()
    }

    /** Nuova chat in Claude con richiesta + trascrizione. */
    fun share(context: Context, note: Note, kind: PromptKind, question: String? = null) {
        val prefs = context.app.prefs
        val transcript = Transcript.readable(note)
        val prompt = fullPrompt(context, kind, question)
        val header = "${note.title} – ${Transcript.date(note.createdAt)}"
        val asFile = prefs.alwaysAsFile || transcript.length > MAX_INLINE_CHARS

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, note.title)
            if (asFile) {
                // L'app Claude scarta il testo del messaggio quando c'è un allegato:
                // il comando va quindi in cima al file, prima della trascrizione.
                val body = "RICHIESTA PER CLAUDE – ${kind.label.uppercase()}\n\n$prompt\n\n" +
                    "==========\nTRASCRIZIONE – $header\n\n$transcript"
                val file = writeTxt(context, "${kind.label} - ${note.title}", body)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                putExtra(Intent.EXTRA_STREAM, uri)
                // Se un giorno l'app Claude userà anche il messaggio, arriverà questo.
                putExtra(Intent.EXTRA_TEXT, "Segui la richiesta scritta all'inizio del file allegato.")
                clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                putExtra(Intent.EXTRA_TEXT, "$prompt\n\n---\nTRASCRIZIONE – $header\n\n$transcript")
            }
            // Claude si apre nella sua finestra: la chat resta aperta lì e si può riprendere.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        remember(context, note, kind)
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

    /** Condivide immagine (o PDF) e testo insieme: WhatsApp, email e molte app li ricevono entrambi. */
    fun shareFileWithText(context: Context, file: File, text: String?, subject: String) {
        val mime = if (file.extension.lowercase() == "pdf") "application/pdf" else "image/*"
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
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
