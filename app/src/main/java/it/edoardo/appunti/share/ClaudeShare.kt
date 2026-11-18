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

    /** [question] serve solo per [PromptKind.ASK]: sostituisce {domanda} nel prompt. */
    fun share(context: Context, note: Note, kind: PromptKind, question: String? = null) {
        val prefs = context.app.prefs
        val transcript = Transcript.readable(note)
        var prompt = prefs.prompt(kind)
        if (question != null) {
            prompt = if (prompt.contains("{domanda}")) prompt.replace("{domanda}", question.trim())
            else "$prompt\n\nDomanda: ${question.trim()}"
        }
        val header = "${note.title} – ${Transcript.date(note.createdAt)}"
        val asFile = prefs.alwaysAsFile || transcript.length > MAX_INLINE_CHARS

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, note.title)
            if (asFile) {
                val file = writeTxt(context, note, "$header\n\n$transcript")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "$prompt\n\n(La trascrizione è nel file allegato \"${file.name}\".)")
                clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                putExtra(Intent.EXTRA_TEXT, "$prompt\n\n---\n$header\n\n$transcript")
            }
        }

        // Ricorda nota e tipo di richiesta: quando la risposta torna, l'app sa dove metterla.
        prefs.lastSentNoteId = note.id
        prefs.lastSentKind = kind.key
        prefs.lastSentAt = System.currentTimeMillis()

        try {
            context.startActivity(Intent(intent).setPackage(CLAUDE_PACKAGE))
        } catch (e: ActivityNotFoundException) {
            // App Claude non installata o non accetta questo tipo: si apre il menu di scelta.
            val chooser = Intent.createChooser(intent, "Invia a…").apply {
                putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    arrayOf(ComponentName(context, ReceiveTextActivity::class.java))
                )
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

    /**
     * Domanda successiva nella chat di Claude già aperta: copia solo la domanda e riapre Claude,
     * senza mandare di nuovo tutta la trascrizione in una chat nuova.
     */
    fun continueInChat(context: Context, note: Note, question: String) {
        copy(context, note.title, question.trim(), toast = false)
        val prefs = context.app.prefs
        prefs.lastSentNoteId = note.id
        prefs.lastSentKind = PromptKind.ASK.key
        prefs.lastSentAt = System.currentTimeMillis()
        val launch = context.packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE)
        if (launch == null) {
            Toast.makeText(context, "App Claude non trovata: la domanda è negli appunti", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(context, "Domanda copiata: in Claude tieni premuto e incolla", Toast.LENGTH_LONG).show()
        context.startActivity(launch)
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

    private fun writeTxt(context: Context, note: Note, text: String): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
        val safe = note.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(50).ifBlank { "trascrizione" }
        return File(dir, "$safe.txt").apply { writeText(text) }
    }
}
