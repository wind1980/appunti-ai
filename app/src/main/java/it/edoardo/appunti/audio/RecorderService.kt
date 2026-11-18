package it.edoardo.appunti.audio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import it.edoardo.appunti.App
import it.edoardo.appunti.MainActivity
import it.edoardo.appunti.R
import it.edoardo.appunti.data.NotesRepo
import it.edoardo.appunti.data.Transcript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

sealed interface RecState {
    data object Idle : RecState

    /**
     * Registrazione aperta, in corso o in pausa.
     * [elapsedBeforeMs] è il tempo registrato fino all'ultima ripresa; [resumedAt] è il momento
     * della ripresa in tempo di sistema ([SystemClock.elapsedRealtime]).
     */
    data class Active(
        val paused: Boolean,
        val elapsedBeforeMs: Long,
        val resumedAt: Long,
        val level: Float,
    ) : RecState {
        fun elapsed(now: Long = SystemClock.elapsedRealtime()): Long =
            elapsedBeforeMs + if (paused) 0 else (now - resumedAt)
    }

    data object Saving : RecState
}

/**
 * Registra dal microfono in WAV 16 kHz mono come servizio in primo piano,
 * così continua anche a schermo spento o con l'app in background.
 * La registrazione si può mettere in pausa e riprendere quante volte si vuole:
 * finisce (e va in trascrizione) solo con "Termina".
 */
class RecorderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var elapsedBeforeMs = 0L
    @Volatile private var resumedAt = 0L
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> finish()
        }
        return START_NOT_STICKY
    }

    private fun pending(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, RecorderService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, App.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
        val elapsed = elapsedBeforeMs + if (paused) 0 else SystemClock.elapsedRealtime() - resumedAt
        if (paused) {
            b.setContentTitle("Registrazione in pausa")
                .setContentText("Registrati ${Transcript.time(elapsed)} · riprendi quando vuoi")
                .setShowWhen(false)
                .addAction(0, "Riprendi", pending(ACTION_RESUME, 2))
        } else {
            b.setContentTitle("Registrazione in corso")
                .setContentText("Puoi mettere in pausa e riprendere")
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() - elapsed)
                .addAction(0, "Pausa", pending(ACTION_PAUSE, 3))
        }
        b.addAction(0, "Termina e trascrivi", pending(ACTION_STOP, 1))
        return b.build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun publishState(level: Float = 0f) {
        _state.value = RecState.Active(paused, elapsedBeforeMs, resumedAt, level)
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        paused = false
        elapsedBeforeMs = 0L
        resumedAt = SystemClock.elapsedRealtime()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)

        val dir = File(filesDir, "audio").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = File(dir, "rec-$stamp.wav")
        val createdAt = System.currentTimeMillis()

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, SAMPLE_RATE) // almeno mezzo secondo di audio
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2
            )
        } catch (e: Exception) {
            abort()
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            abort()
            return
        }

        running = true
        publishState()

        thread = Thread {
            var dataBytes = 0L
            var capturing = false
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.setLength(0)
                    Wav.writeHeader(raf, 0)
                    raf.seek(Wav.HEADER_SIZE.toLong())
                    val buf = ShortArray(bufSize / 2)
                    val bytes = ByteArray(buf.size * 2)
                    var lastHeader = SystemClock.elapsedRealtime()
                    while (running) {
                        if (paused) {
                            if (capturing) {
                                // In pausa il microfono si spegne davvero (sparisce l'indicatore verde).
                                recorder.stop()
                                capturing = false
                                val p = raf.filePointer
                                Wav.writeHeader(raf, dataBytes)
                                raf.seek(p)
                            }
                            Thread.sleep(50)
                            continue
                        }
                        if (!capturing) {
                            recorder.startRecording()
                            capturing = true
                        }
                        val n = recorder.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        var peak = 0
                        for (i in 0 until n) {
                            val v = buf[i].toInt()
                            bytes[2 * i] = (v and 0xff).toByte()
                            bytes[2 * i + 1] = ((v shr 8) and 0xff).toByte()
                            peak = maxOf(peak, abs(v))
                        }
                        raf.write(bytes, 0, n * 2)
                        dataBytes += n * 2
                        if (!paused) publishState(peak / 32768f)
                        // Aggiorna l'intestazione ogni 10 s: se il telefono si spegne il file resta leggibile
                        // e viene recuperato alla prossima apertura dell'app.
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastHeader > 10_000) {
                            val p = raf.filePointer
                            Wav.writeHeader(raf, dataBytes)
                            raf.seek(p)
                            lastHeader = now
                        }
                    }
                    if (capturing) recorder.stop()
                    Wav.writeHeader(raf, dataBytes)
                }
            } catch (e: Exception) {
                // Errore di scrittura (es. memoria piena): si salva quello che c'è.
                running = false
            } finally {
                try {
                    recorder.release()
                } catch (_: Exception) {
                }
            }
            val durationMs = dataBytes / 2 * 1000 / SAMPLE_RATE
            scope.launch {
                if (durationMs >= 1000) {
                    val title = "Registrazione " + SimpleDateFormat("d MMM HH:mm", Locale.ITALIAN).format(Date(createdAt))
                    NotesRepo.addAudioNote(applicationContext, title, file, createdAt, durationMs)
                } else {
                    file.delete()
                }
                _state.value = RecState.Idle
                ServiceCompat.stopForeground(this@RecorderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.apply {
            name = "Registratore"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun pause() {
        if (!running || paused) return
        elapsedBeforeMs += SystemClock.elapsedRealtime() - resumedAt
        paused = true
        publishState()
        updateNotification()
    }

    @SuppressLint("MissingPermission")
    private fun resume() {
        if (!running || !paused) return
        resumedAt = SystemClock.elapsedRealtime()
        paused = false
        publishState()
        updateNotification()
    }

    private fun abort() {
        running = false
        _state.value = RecState.Idle
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finish() {
        if (!running) {
            stopSelf()
            return
        }
        _state.value = RecState.Saving
        paused = false
        running = false
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "it.edoardo.appunti.START"
        const val ACTION_PAUSE = "it.edoardo.appunti.PAUSE"
        const val ACTION_RESUME = "it.edoardo.appunti.RESUME"
        const val ACTION_STOP = "it.edoardo.appunti.STOP"

        private val _state = MutableStateFlow<RecState>(RecState.Idle)
        val state: StateFlow<RecState> = _state

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, RecorderService::class.java).setAction(ACTION_START)
            )
        }

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, RecorderService::class.java).setAction(action))
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stop(context: Context) = send(context, ACTION_STOP)
    }
}
