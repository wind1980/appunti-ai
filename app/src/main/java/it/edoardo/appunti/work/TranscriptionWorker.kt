package it.edoardo.appunti.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import it.edoardo.appunti.App
import it.edoardo.appunti.R
import it.edoardo.appunti.app
import it.edoardo.appunti.audio.AudioDecoder
import it.edoardo.appunti.audio.SAMPLE_RATE
import it.edoardo.appunti.data.Status
import it.edoardo.appunti.data.Transcript
import it.edoardo.appunti.whisper.TranscribeListener
import it.edoardo.appunti.whisper.WhisperEngine
import it.edoardo.appunti.whisper.WhisperNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/**
 * Trascrive l'audio di una nota con whisper.cpp, a blocchi di 5 minuti.
 * Dopo ogni blocco salva il testo: se il sistema interrompe il lavoro, riparte da lì.
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val noteId = inputData.getLong(KEY_NOTE_ID, -1)

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val dao = ctx.app.db.notes()
        val prefs = ctx.app.prefs

        val first = dao.get(noteId) ?: return Result.success()
        if (first.status == Status.CANCELLED) return Result.success()

        val audio = first.audioPath?.let(::File)?.takeIf { it.exists() } ?: run {
            dao.setStatus(noteId, Status.ERROR, 0, "File audio non trovato")
            return Result.failure()
        }
        val modelPath = prefs.modelPath ?: run {
            dao.setStatus(noteId, Status.ERROR, 0, "Nessun modello Whisper: scaricane uno in Impostazioni, poi tocca Riprova.")
            return Result.failure()
        }

        tryForeground(first.title, -1)
        // Un solo modello in memoria: le trascrizioni girano una alla volta.
        return lock.withLock { transcribe(audio, modelPath) }
    }

    private suspend fun transcribe(audio: File, modelPath: String): Result {
        val ctx = applicationContext
        val dao = ctx.app.db.notes()
        val prefs = ctx.app.prefs
        val note = dao.get(noteId) ?: return Result.success()
        if (note.status == Status.CANCELLED) return Result.success()

        dao.setStatus(noteId, Status.RUNNING, note.progress, null)
        val progress = AtomicInteger(note.progress)
        val pcmFile = File(ctx.cacheDir, "pcm/$noteId.pcm").apply { parentFile?.mkdirs() }

        try {
            coroutineScope {
                val poller = launch {
                    var last = -1
                    while (isActive) {
                        delay(1500)
                        val p = progress.get()
                        if (p != last) {
                            dao.setProgress(noteId, p)
                            tryForeground(note.title, p)
                            last = p
                        }
                    }
                }
                try {
                    val resuming = note.processedSamples > 0
                    val src = withContext(Dispatchers.IO) {
                        AudioDecoder.prepare(
                            audio, pcmFile,
                            onProgress = { f -> if (!resuming) progress.set((f * 5).toInt()) },
                            isCancelled = { isStopped },
                        )
                    }
                    val total = src.numSamples
                    if (total < SAMPLE_RATE / 2) error("L'audio è vuoto o troppo corto")
                    if (note.durationMs == 0L) dao.setDuration(noteId, total * 1000 / SAMPLE_RATE)

                    val segments = if (resuming) Transcript.parse(note.segments).toMutableList() else mutableListOf()
                    var pos = if (resuming) note.processedSamples.coerceIn(0, total) else 0L

                    val language = prefs.language
                    val threads = prefs.threads
                    val hint = prefs.whisperHint.ifBlank { null }
                    val ptr = withContext(Dispatchers.Default) { WhisperEngine.load(modelPath) }
                    val buf = FloatArray(CHUNK_SAMPLES.toInt())

                    while (pos < total) {
                        if (isStopped) throw CancellationException("Interrotto")
                        var end = min(total, pos + CHUNK_SAMPLES)
                        if (end < total) end = src.quietestPoint(end - CUT_SEARCH_SAMPLES, end)
                        val n = (end - pos).toInt()
                        withContext(Dispatchers.IO) { src.read(pos, n, buf) }

                        val base = pos
                        val listener = object : TranscribeListener {
                            override fun onProgress(percent: Int) {
                                val done = base + n * (percent / 100.0)
                                progress.set((5 + 95.0 * done / total).toInt().coerceIn(0, 99))
                            }

                            override fun isCancelled(): Boolean = this@TranscriptionWorker.isStopped
                        }
                        val bytes = withContext(Dispatchers.Default) {
                            WhisperNative.transcribe(ptr, buf, n, language, threads, hint, listener)
                        }
                        if (bytes == null) {
                            if (isStopped || dao.get(noteId)?.status == Status.CANCELLED) throw CancellationException("Annullato")
                            error("La trascrizione non è riuscita")
                        }
                        segments += WhisperEngine.parse(bytes, base * 1000 / SAMPLE_RATE)
                        pos = end
                        dao.setSegments(noteId, Transcript.serialize(segments), pos)
                        progress.set((5 + 95.0 * pos / total).toInt().coerceIn(0, 99))
                    }
                    dao.setStatus(noteId, Status.DONE, 100, null)
                } finally {
                    poller.cancel()
                }
            }
            pcmFile.delete()
            return Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val cur = dao.get(noteId)
                // Se non è stato l'utente ad annullare, il sistema ha interrotto il lavoro:
                // resta in coda e WorkManager lo riprende dal punto salvato.
                if (cur != null && cur.status != Status.CANCELLED) {
                    dao.setStatus(noteId, Status.QUEUED, cur.progress, null)
                }
            }
            throw e
        } catch (e: Throwable) {
            dao.setStatus(noteId, Status.ERROR, progress.get(), e.message ?: e.toString())
            return Result.failure()
        } finally {
            WhisperEngine.release()
        }
    }

    private suspend fun tryForeground(title: String, percent: Int) {
        val n = NotificationCompat.Builder(applicationContext, App.CHANNEL_TRANSCRIPTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Trascrizione: $title")
            .setContentText(if (percent < 0) "In attesa…" else "$percent%")
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val notifId = 1000 + (noteId % 100_000).toInt()
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, n)
        }
        try {
            setForeground(info)
        } catch (e: Exception) {
            // Con l'app in background Android può vietare il servizio in primo piano:
            // il lavoro continua comunque e, se viene interrotto, riparte dal blocco salvato.
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, App.CHANNEL_TRANSCRIPTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Trascrizione in corso")
            .setSilent(true)
            .build()
        val notifId = 1000 + (noteId % 100_000).toInt()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, n)
        }
    }

    companion object {
        const val KEY_NOTE_ID = "noteId"
        const val TAG = "trascrizione"
        private val lock = Mutex()
        private const val CHUNK_SAMPLES = SAMPLE_RATE * 300L          // 5 minuti
        private const val CUT_SEARCH_SAMPLES = SAMPLE_RATE * 3L       // cerca il taglio negli ultimi 3 s
    }
}
