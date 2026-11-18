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
import it.edoardo.appunti.data.Segment
import it.edoardo.appunti.data.Status
import it.edoardo.appunti.data.Transcript
import it.edoardo.appunti.whisper.Models
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
 *
 * Modalità normale: dopo ogni blocco salva il testo; se il sistema interrompe il lavoro, riparte da lì.
 * Modalità "Migliora": ritrascrive con un altro modello e sostituisce il testo solo alla fine,
 * così la trascrizione precedente resta leggibile (e mandabile a Claude) nel frattempo.
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val noteId = inputData.getLong(KEY_NOTE_ID, -1)
    private val improve = inputData.getBoolean(KEY_IMPROVE, false)

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val dao = ctx.app.db.notes()
        val prefs = ctx.app.prefs

        val first = dao.get(noteId) ?: return Result.success()
        if (first.status == Status.CANCELLED) return Result.success()
        if (improve && first.improveModel == null) return Result.success() // "Migliora" annullato

        val audio = first.audioPath?.let(::File)?.takeIf { it.exists() } ?: run {
            fail(improve, "File audio non trovato")
            return Result.failure()
        }
        val modelPath = (if (improve) inputData.getString(KEY_MODEL_PATH)?.takeIf { File(it).exists() } else prefs.modelPath)
            ?: run {
                fail(improve, "Nessun modello Whisper: scaricane uno in Impostazioni, poi tocca Riprova.")
                return Result.failure()
            }

        tryForeground(first.title, -1)
        // Un solo modello in memoria: le trascrizioni girano una alla volta.
        return lock.withLock { transcribe(audio, modelPath) }
    }

    private suspend fun fail(improve: Boolean, message: String) {
        val dao = applicationContext.app.db.notes()
        if (improve) {
            // Il testo precedente resta valido: si torna a "fatto" con il messaggio d'errore.
            dao.setImprove(noteId, null)
            dao.setStatus(noteId, Status.DONE, 100, "Miglioramento non riuscito: $message")
        } else {
            dao.setStatus(noteId, Status.ERROR, 0, message)
        }
    }

    private suspend fun transcribe(audio: File, modelPath: String): Result {
        val ctx = applicationContext
        val dao = ctx.app.db.notes()
        val prefs = ctx.app.prefs
        val note = dao.get(noteId) ?: return Result.success()
        if (note.status == Status.CANCELLED) return Result.success()
        if (improve && note.improveModel == null) return Result.success()

        val startProgress = if (improve) 0 else note.progress
        dao.setStatus(noteId, Status.RUNNING, startProgress, null)
        val progress = AtomicInteger(startProgress)
        val pcmFile = File(ctx.cacheDir, "pcm/$noteId.pcm").apply { parentFile?.mkdirs() }
        val modelName = File(modelPath).name

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
                    val resuming = !improve && note.processedSamples > 0
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
                    if (!improve && !resuming) dao.setModelName(noteId, modelName)

                    val segments: MutableList<Segment> =
                        if (resuming) Transcript.parse(note.segments).toMutableList() else mutableListOf()
                    var pos = if (resuming) note.processedSamples.coerceIn(0, total) else 0L

                    val language = prefs.language
                    val threads = prefs.threads
                    val hint = prefs.whisperHint.ifBlank { null }
                    val vadPath = if (prefs.skipSilence) Models.vadPath(ctx) else null
                    val noFallback = !prefs.retryHardParts
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
                            WhisperNative.transcribe(ptr, buf, n, language, threads, hint, vadPath, noFallback, listener)
                        }
                        if (bytes == null) {
                            val cur = dao.get(noteId)
                            if (isStopped || cur?.status == Status.CANCELLED || (improve && cur?.improveModel == null)) {
                                throw CancellationException("Annullato")
                            }
                            error("La trascrizione non è riuscita")
                        }
                        segments += WhisperEngine.parse(bytes, base * 1000 / SAMPLE_RATE)
                        pos = end
                        if (!improve) dao.setSegments(noteId, Transcript.serialize(segments), pos)
                        progress.set((5 + 95.0 * pos / total).toInt().coerceIn(0, 99))
                    }

                    if (improve) {
                        dao.replaceTranscript(noteId, Transcript.serialize(segments), total, modelName)
                    } else {
                        dao.setStatus(noteId, Status.DONE, 100, null)
                    }
                } finally {
                    poller.cancel()
                }
            }
            pcmFile.delete()
            return Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val cur = dao.get(noteId)
                // Se lo stato è ancora "in corso", non è stato l'utente ad annullare ma il sistema
                // a interrompere il lavoro: resta in coda e WorkManager lo riprende.
                if (cur != null && cur.status == Status.RUNNING) {
                    dao.setStatus(noteId, Status.QUEUED, cur.progress, null)
                }
            }
            throw e
        } catch (e: Throwable) {
            fail(improve, e.message ?: e.toString())
            return Result.failure()
        } finally {
            WhisperEngine.release()
        }
    }

    private fun notification(title: String, percent: Int) =
        NotificationCompat.Builder(applicationContext, App.CHANNEL_TRANSCRIPTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle((if (improve) "Miglioramento: " else "Trascrizione: ") + title)
            .setContentText(if (percent < 0) "In attesa…" else "$percent%")
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun foregroundInfo(title: String, percent: Int): ForegroundInfo {
        val notifId = 1000 + (noteId % 100_000).toInt()
        val n = notification(title, percent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, n)
        }
    }

    private suspend fun tryForeground(title: String, percent: Int) {
        try {
            setForeground(foregroundInfo(title, percent))
        } catch (e: Exception) {
            // Con l'app in background Android può vietare il servizio in primo piano:
            // il lavoro continua comunque e, se viene interrotto, riparte (o ricomincia, per "Migliora").
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("in corso", -1)

    companion object {
        const val KEY_NOTE_ID = "noteId"
        const val KEY_IMPROVE = "improve"
        const val KEY_MODEL_PATH = "modelPath"
        const val TAG = "trascrizione"
        private val lock = Mutex()
        private const val CHUNK_SAMPLES = SAMPLE_RATE * 300L          // 5 minuti
        private const val CUT_SEARCH_SAMPLES = SAMPLE_RATE * 3L       // cerca il taglio negli ultimi 3 s
    }
}
