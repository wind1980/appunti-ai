package it.edoardo.appunti.audio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
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
    /** [startedAt] è in tempo di sistema ([SystemClock.elapsedRealtime]). */
    data class Recording(val startedAt: Long, val level: Float) : RecState
    data object Saving : RecState
}

/**
 * Registra dal microfono in WAV 16 kHz mono come servizio in primo piano,
 * così continua anche a schermo spento o con l'app in background.
 */
class RecorderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Registrazione in corso")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Ferma e trascrivi", stop)
            .setUsesChronometer(true)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Tocca \"Ferma e trascrivi\" quando hai finito"), type)

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
        val startedAt = SystemClock.elapsedRealtime()
        _state.value = RecState.Recording(startedAt, 0f)

        thread = Thread {
            var dataBytes = 0L
            try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                Wav.writeHeader(raf, 0)
                raf.seek(Wav.HEADER_SIZE.toLong())
                val buf = ShortArray(bufSize / 2)
                val bytes = ByteArray(buf.size * 2)
                recorder.startRecording()
                var lastHeader = SystemClock.elapsedRealtime()
                while (running) {
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
                    _state.value = RecState.Recording(startedAt, peak / 32768f)
                    // Aggiorna l'intestazione ogni 10 s: se il telefono si spegne il file resta leggibile.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastHeader > 10_000) {
                        val p = raf.filePointer
                        Wav.writeHeader(raf, dataBytes)
                        raf.seek(p)
                        lastHeader = now
                    }
                }
                recorder.stop()
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

    private fun abort() {
        _state.value = RecState.Idle
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stop() {
        if (!running) {
            stopSelf()
            return
        }
        _state.value = RecState.Saving
        running = false
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "it.edoardo.appunti.START"
        const val ACTION_STOP = "it.edoardo.appunti.STOP"

        private val _state = MutableStateFlow<RecState>(RecState.Idle)
        val state: StateFlow<RecState> = _state

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, RecorderService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }
}
