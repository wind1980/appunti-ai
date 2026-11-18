package it.edoardo.appunti.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import it.edoardo.appunti.app
import it.edoardo.appunti.audio.SAMPLE_RATE
import it.edoardo.appunti.audio.Wav
import it.edoardo.appunti.share.ResponseCleaner
import it.edoardo.appunti.ui.MindMap
import it.edoardo.appunti.whisper.WhisperNative
import it.edoardo.appunti.work.TranscriptionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tipo di risposta per le immagini salvate (il testo è il percorso del file). */
const val KIND_IMAGE = "image"

object NotesRepo {

    private fun workName(id: Long) = "trascrizione-$id"

    suspend fun addAudioNote(context: Context, title: String, file: File, createdAt: Long, durationMs: Long): Long {
        val id = context.app.db.notes().insert(
            Note(title = title, createdAt = createdAt, audioPath = file.absolutePath, durationMs = durationMs, status = Status.QUEUED)
        )
        enqueue(context, id)
        return id
    }

    /** Copia nell'app un audio o video condiviso da un'altra app e lo mette in coda. */
    suspend fun importAudio(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        var name = "Audio importato"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val ext = name.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: "audio"
        val dir = File(context.filesDir, "audio").apply { mkdirs() }
        val dest = File(dir, "imp-${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            dest.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
        val title = name.substringBeforeLast('.').ifBlank { "Audio importato" }
        addAudioNote(context, title, dest, System.currentTimeMillis(), 0)
    }

    fun enqueue(context: Context, id: Long, improveModelPath: String? = null, onlyCharging: Boolean = false) {
        val data = if (improveModelPath != null) {
            workDataOf(
                TranscriptionWorker.KEY_NOTE_ID to id,
                TranscriptionWorker.KEY_IMPROVE to true,
                TranscriptionWorker.KEY_MODEL_PATH to improveModelPath,
            )
        } else {
            workDataOf(TranscriptionWorker.KEY_NOTE_ID to id)
        }
        val req = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(data)
            .addTag(TranscriptionWorker.TAG)
            .apply { if (onlyCharging) setConstraints(Constraints.Builder().setRequiresCharging(true).build()) }
            .build()
        // Un lavoro per nota; il worker usa un lucchetto globale, quindi le trascrizioni
        // girano comunque una alla volta.
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, req)
    }

    suspend fun retranscribe(context: Context, id: Long) {
        val dao = context.app.db.notes()
        dao.setImprove(id, null)
        dao.setSegments(id, "", 0)
        dao.setStatus(id, Status.QUEUED, 0, null)
        enqueue(context, id)
    }

    /**
     * Ritrascrive con il modello "preciso" scelto nelle Impostazioni. Il testo attuale resta
     * finché il nuovo non è pronto. Restituisce false se non c'è un modello preciso.
     */
    suspend fun improve(context: Context, id: Long): Boolean {
        val prefs = context.app.prefs
        val model = prefs.preciseModelPath ?: return false
        val dao = context.app.db.notes()
        dao.setImprove(id, File(model).name)
        dao.setStatus(id, Status.QUEUED, 0, null)
        enqueue(context, id, improveModelPath = model, onlyCharging = prefs.improveOnlyCharging)
        return true
    }

    suspend fun resume(context: Context, id: Long) {
        context.app.db.notes().setStatus(id, Status.QUEUED, 0, null)
        enqueue(context, id)
    }

    suspend fun cancel(context: Context, id: Long) {
        val dao = context.app.db.notes()
        val note = dao.get(id) ?: return
        val wasRunning = note.status == Status.RUNNING
        // Prima lo stato, così il worker capisce che è stato l'utente ad annullare.
        if (note.improveModel != null) {
            // Annullare "Migliora" lascia la trascrizione di prima.
            dao.setImprove(id, null)
            dao.setStatus(id, Status.DONE, 100, null)
        } else {
            dao.setStatus(id, Status.CANCELLED, 0, null)
        }
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        if (wasRunning) WhisperNative.cancel()
    }

    /**
     * Registrazioni rimaste senza nota (app chiusa dal sistema o telefono spento durante
     * la registrazione): le trasforma in note e le mette in coda.
     */
    suspend fun recoverOrphans(context: Context) = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "audio")
        val files = dir.listFiles { f -> f.name.startsWith("rec-") && f.name.endsWith(".wav") } ?: return@withContext
        val known = context.app.db.notes().audioPaths().toSet()
        for (f in files) {
            if (f.absolutePath in known) continue
            val header = Wav.readHeader(f)
            val durationMs = (header?.dataBytes ?: 0L) / 2 * 1000 / SAMPLE_RATE
            if (durationMs < 1000) {
                f.delete()
                continue
            }
            val title = "Registrazione recuperata " +
                SimpleDateFormat("d MMM HH:mm", Locale.ITALIAN).format(Date(f.lastModified()))
            addAudioNote(context, title, f, f.lastModified(), durationMs)
        }
    }

    suspend fun delete(context: Context, note: Note) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(note.id))
        if (note.status == Status.RUNNING) WhisperNative.cancel()
        val images = context.app.db.notes().imagePaths(note.id)
        withContext(Dispatchers.IO) {
            images.forEach { File(it).delete() }
            note.audioPath?.let { File(it).delete() }
            File(context.cacheDir, "pcm/${note.id}.pcm").delete()
        }
        context.app.db.notes().delete(note.id)
    }

    /**
     * Salva una risposta di Claude nella nota. Se è un riassunto e la nota ha ancora un titolo
     * automatico ("Registrazione …" o il nome di un file), usa il titolo proposto da Claude.
     */
    suspend fun saveResponse(context: Context, noteId: Long, text: String, kind: String? = null, strict: Boolean = false): Boolean {
        // Il testo originale (quello negli appunti) è "gestito": non va riproposto.
        context.app.prefs.handledClipHash = text.trim().hashCode()
        val cleaned = ResponseCleaner.clean(text)
        var clean = cleaned.text
        var finalKind = kind
        when {
            cleaned.isHtml -> finalKind = PromptKind.INFOGRAPHIC.key
            // Infografica chiesta esplicitamente ma negli appunti non c'è codice HTML: non si salva.
            kind == PromptKind.INFOGRAPHIC.key && strict -> return false
            kind == PromptKind.INFOGRAPHIC.key -> finalKind = null
            else -> {
                // Mappa scritta da Claude in formato mermaid: diventa l'elenco che MeNoter disegna.
                val norm = MindMap.normalize(clean)
                if (norm != clean) {
                    clean = norm
                    finalKind = PromptKind.MINDMAP.key
                }
            }
        }
        val dao = context.app.db.notes()
        dao.insertResponse(
            ClaudeResponse(noteId = noteId, createdAt = System.currentTimeMillis(), text = clean, kind = finalKind)
        )
        if (kind == PromptKind.SUMMARY.key) {
            val note = dao.get(noteId) ?: return true
            val heading = clean.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?.takeIf { it.startsWith("#") }?.trimStart('#', ' ')?.trim()?.trim('*')
            if (heading != null && heading.length in 3..60 && isAutoTitle(note.title)) dao.rename(noteId, heading)
        }
        return true
    }

    /** Immagine condivisa (es. screenshot di un grafico di Claude) salvata nella nota. */
    suspend fun saveImage(context: Context, noteId: Long, uri: Uri) = withContext(Dispatchers.IO) {
        val type = context.contentResolver.getType(uri) ?: "image/png"
        val ext = when {
            type.contains("png") -> "png"
            type.contains("webp") -> "webp"
            else -> "jpg"
        }
        val dir = File(context.filesDir, "images").apply { mkdirs() }
        val dest = File(dir, "img-${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
        context.app.db.notes().insertResponse(
            ClaudeResponse(noteId = noteId, createdAt = System.currentTimeMillis(), text = dest.absolutePath, kind = KIND_IMAGE)
        )
    }

    /** Nuova nota che contiene solo un'immagine condivisa. */
    suspend fun addImageNote(context: Context, uri: Uri): Long {
        val title = "Immagine " + SimpleDateFormat("d MMM HH:mm", Locale.ITALIAN).format(Date())
        val id = context.app.db.notes().insert(
            Note(title = title, createdAt = System.currentTimeMillis(), audioPath = null, status = Status.TEXT)
        )
        saveImage(context, id, uri)
        return id
    }

    suspend fun deleteResponse(context: Context, r: ClaudeResponse) {
        if (r.kind == KIND_IMAGE) withContext(Dispatchers.IO) { File(r.text).delete() }
        context.app.db.notes().deleteResponse(r)
    }

    /** Titoli messi dall'app o nomi di file, che conviene sostituire con un titolo vero. */
    private fun isAutoTitle(title: String): Boolean =
        title.startsWith("Registrazione") || title == "Audio importato" || !title.trim().contains(' ')

    /** Nota senza audio, creata da un testo condiviso (es. una risposta di Claude). */
    suspend fun addTextNote(context: Context, text: String): Long {
        val firstLine = text.lineSequence().map { it.trim().trimStart('#', '*', ' ') }.firstOrNull { it.isNotBlank() } ?: "Nota"
        val id = context.app.db.notes().insert(
            Note(title = firstLine.take(60), createdAt = System.currentTimeMillis(), audioPath = null, status = Status.TEXT)
        )
        saveResponse(context, id, text, context.app.prefs.lastSentKind)
        return id
    }
}
