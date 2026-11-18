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
import it.edoardo.appunti.whisper.WhisperNative
import it.edoardo.appunti.work.TranscriptionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        withContext(Dispatchers.IO) {
            note.audioPath?.let { File(it).delete() }
            File(context.cacheDir, "pcm/${note.id}.pcm").delete()
        }
        context.app.db.notes().delete(note.id)
    }

    suspend fun saveResponse(context: Context, noteId: Long, text: String) {
        context.app.db.notes().insertResponse(
            ClaudeResponse(noteId = noteId, createdAt = System.currentTimeMillis(), text = text.trim())
        )
    }

    /** Nota senza audio, creata da un testo condiviso (es. una risposta di Claude). */
    suspend fun addTextNote(context: Context, text: String): Long {
        val firstLine = text.lineSequence().map { it.trim().trimStart('#', '*', ' ') }.firstOrNull { it.isNotBlank() } ?: "Nota"
        val id = context.app.db.notes().insert(
            Note(title = firstLine.take(60), createdAt = System.currentTimeMillis(), audioPath = null, status = Status.TEXT)
        )
        saveResponse(context, id, text)
        return id
    }
}
