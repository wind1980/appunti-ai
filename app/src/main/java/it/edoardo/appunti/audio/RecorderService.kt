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
import it.edoardo.appunti.app
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
        /** Chiamata in corso durante la registrazione (null se non c'è una chiamata). */
        val call: CallStatus? = null,
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
    private var title: String? = null

    // Chiamate: stato controllato ogni secondo, avvisi solo quando cambia qualcosa.
    @Volatile private var callStatus: CallStatus? = null
    @Volatile private var silencedBySystem = false
    @Volatile private var zeroSince = 0L
    @Volatile private var lastAlertKey: String? = null
    @Volatile private var speakerSetByUs = false
    private var monitorJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                title = intent.getStringExtra(EXTRA_TITLE)
                start()
            }
            ACTION_SPEAKER -> speakerOn()
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
        _state.value = RecState.Active(paused, elapsedBeforeMs, resumedAt, level, callStatus)
    }

    /** Tempo registrato finora (senza le pause), in millisecondi. */
    private fun activeMs(): Long = elapsedBeforeMs + if (paused) 0 else SystemClock.elapsedRealtime() - resumedAt

    // ---------------- Microfono ----------------

    /**
     * Apre il microfono. In chiamata si usa la sorgente "riconoscimento vocale", quella che Android
     * lascia ai servizi di accessibilità insieme alla chiamata; fuori dalle chiamate il microfono normale.
     */
    @SuppressLint("MissingPermission")
    private fun openRecorder(bufSize: Int, inCall: Boolean): AudioRecord? {
        val source = if (inCall) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.MIC
        return try {
            val r = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return null
            }
            if (Build.VERSION.SDK_INT >= 29) {
                // Android avvisa quando dà silenzio a MeNoter perché un'altra app ha la priorità sul microfono.
                r.registerAudioRecordingCallback(java.util.concurrent.Executors.newSingleThreadExecutor(),
                    object : android.media.AudioManager.AudioRecordingCallback() {
                        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) {
                            val mine = configs.firstOrNull { it.clientAudioSessionId == r.audioSessionId }
                            silencedBySystem = mine?.isClientSilenced ?: false
                        }
                    })
            }
            r
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- Chiamate ----------------

    /** Ogni secondo: c'è una chiamata? dove esce l'audio? MeNoter riceve davvero l'audio? */
    private fun startMonitor() {
        val am = getSystemService(android.media.AudioManager::class.java)
        monitorJob = scope.launch {
            var wasInCall = false
            while (running) {
                val inCall = CallMonitor.inCall(am)
                val silent = !paused && (silencedBySystem ||
                    (zeroSince > 0 && SystemClock.elapsedRealtime() - zeroSince > 4000))
                val status = if (inCall || silent) CallStatus(
                    inCall = inCall,
                    route = if (inCall) CallMonitor.route(am) else CallRoute.UNKNOWN,
                    accessibilityOn = CallMonitor.accessibilityOn(applicationContext),
                    silenced = silent,
                    app = CallCaptureService.currentAppName(),
                ) else null
                callStatus = status
                if (inCall && !wasInCall) getSystemService(NotificationManager::class.java).cancel(CallCaptureService.OFFER_ID)
                // Fine chiamata: se avevamo messo noi il vivavoce, si torna come prima.
                if (!inCall && wasInCall && speakerSetByUs) {
                    CallMonitor.setSpeaker(applicationContext, false)
                    speakerSetByUs = false
                }
                wasInCall = inCall
                if (status != null && status.inCall && !status.othersAudible && !paused &&
                    app.prefs.autoSpeaker && status.accessibilityOn
                ) speakerOn()
                alert(status)
                if (!paused) publishState((_state.value as? RecState.Active)?.level ?: 0f)
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun speakerOn() {
        if (CallMonitor.setSpeaker(applicationContext, true)) speakerSetByUs = true
    }

    /** Un avviso per volta, solo quando la situazione cambia; sparisce quando è risolta. */
    private fun alert(status: CallStatus?) {
        val nm = getSystemService(NotificationManager::class.java)
        val (key, heading, body) = when {
            status == null || paused -> Triple(null, "", "")
            status.inCall && !status.accessibilityOn -> Triple(
                "a11y",
                "MeNoter registra solo silenzio durante la chiamata",
                "Android lo permette solo ai servizi di accessibilità: attiva «MeNoter – registrazione delle chiamate».",
            )
            status.silenced -> Triple(
                "silenced",
                "MeNoter non riceve audio",
                "Un'altra app sta usando il microfono in esclusiva. La registrazione continua e riprende da sola appena possibile.",
            )
            status.inCall && !status.othersAudible -> Triple(
                "route-${status.route}",
                "Registro solo la tua voce",
                "Con ${status.route.label} gli altri non si sentono dal microfono. Metti il vivavoce per registrare anche loro.",
            )
            else -> Triple(null, "", "")
        }
        if (key == lastAlertKey) return
        lastAlertKey = key
        if (key == null) {
            nm.cancel(ALERT_ID)
            return
        }
        val open = PendingIntent.getActivity(
            this, 20, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, App.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(heading)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .setContentIntent(open)
        when (key) {
            "a11y" -> b.addAction(
                0, "Attiva",
                PendingIntent.getActivity(this, 21, CallMonitor.accessibilitySettings(), PendingIntent.FLAG_IMMUTABLE)
            )
            "silenced" -> Unit
            else -> b.addAction(0, "Vivavoce", pending(ACTION_SPEAKER, 22))
        }
        nm.notify(ALERT_ID, b.build())
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
        val am = getSystemService(android.media.AudioManager::class.java)
        var recorderInCall = CallMonitor.inCall(am)
        var recorder: AudioRecord = openRecorder(bufSize, recorderInCall) ?: run {
            abort()
            return
        }

        running = true
        publishState()
        startMonitor()

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
                    val zeros = ByteArray(SAMPLE_RATE / 5 * 2) // 0,2 s di silenzio
                    var lastHeader = SystemClock.elapsedRealtime()

                    /** Silenzio fino al minuto giusto: se il microfono si è fermato, i tempi restano veri. */
                    fun padTo(targetBytes: Long) {
                        while (dataBytes < targetBytes) {
                            val n = minOf(zeros.size.toLong(), targetBytes - dataBytes).toInt() and 0x7ffffffe
                            if (n <= 0) break
                            raf.write(zeros, 0, n)
                            dataBytes += n
                        }
                    }

                    /** Chiude e riapre il microfono (cambio di uscita, inizio o fine chiamata, errore). */
                    fun reopen(inCall: Boolean) {
                        try {
                            if (capturing) recorder.stop()
                        } catch (_: Exception) {
                        }
                        try {
                            recorder.release()
                        } catch (_: Exception) {
                        }
                        capturing = false
                        var r: AudioRecord? = null
                        var attempts = 0
                        while (running && r == null) {
                            r = openRecorder(bufSize, inCall)
                            if (r == null) {
                                // Microfono occupato: si riprova, intanto il tempo scorre in silenzio.
                                padTo(activeMs() * SAMPLE_RATE / 1000 * 2 - SAMPLE_RATE / 5 * 2)
                                Thread.sleep(if (attempts++ < 10) 300 else 1000)
                            }
                        }
                        if (r != null) recorder = r
                        recorderInCall = inCall
                    }

                    while (running) {
                        if (paused) {
                            if (capturing) {
                                // In pausa il microfono si spegne davvero (sparisce l'indicatore verde).
                                try {
                                    recorder.stop()
                                } catch (_: Exception) {
                                }
                                capturing = false
                                val p = raf.filePointer
                                Wav.writeHeader(raf, dataBytes)
                                raf.seek(p)
                            }
                            zeroSince = 0
                            Thread.sleep(50)
                            continue
                        }
                        // Inizio o fine di una chiamata: si riapre il microfono con la sorgente adatta.
                        val inCallNow = CallMonitor.inCall(am)
                        if (inCallNow != recorderInCall) reopen(inCallNow)
                        if (!running) break
                        if (!capturing) {
                            try {
                                recorder.startRecording()
                                capturing = true
                            } catch (e: Exception) {
                                reopen(inCallNow)
                                continue
                            }
                        }
                        val n = recorder.read(buf, 0, buf.size)
                        if (n < 0) {
                            // Microfono perso (cambio di dispositivo, Bluetooth, servizio audio riavviato): si riapre.
                            reopen(CallMonitor.inCall(am))
                            continue
                        }
                        if (n == 0) continue
                        var peak = 0
                        for (i in 0 until n) {
                            val v = buf[i].toInt()
                            bytes[2 * i] = (v and 0xff).toByte()
                            bytes[2 * i + 1] = ((v shr 8) and 0xff).toByte()
                            peak = maxOf(peak, abs(v))
                        }
                        // Silenzio assoluto per alcuni secondi = Android ci sta dando silenzio.
                        zeroSince = if (peak == 0) (if (zeroSince == 0L) SystemClock.elapsedRealtime() else zeroSince) else 0L
                        raf.write(bytes, 0, n * 2)
                        dataBytes += n * 2
                        // Se il microfono è rimasto fermo, si recupera il tempo perso con silenzio.
                        val expected = activeMs() * SAMPLE_RATE / 1000 * 2
                        if (expected - dataBytes > SAMPLE_RATE) padTo(expected - SAMPLE_RATE / 5 * 2)
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
                    if (capturing) {
                        try {
                            recorder.stop()
                        } catch (_: Exception) {
                        }
                    }
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
                    val noteTitle = (title ?: "Registrazione") + " " +
                        SimpleDateFormat("d MMM HH:mm", Locale.ITALIAN).format(Date(createdAt))
                    NotesRepo.addAudioNote(applicationContext, noteTitle, file, createdAt, durationMs)
                } else {
                    file.delete()
                }
                _state.value = RecState.Idle
                endCallHelpers()
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

    /** Fine registrazione: niente più avvisi e, se l'avevamo messo noi, via il vivavoce. */
    private fun endCallHelpers() {
        monitorJob?.cancel()
        getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        lastAlertKey = null
        if (speakerSetByUs) {
            CallMonitor.setSpeaker(applicationContext, false)
            speakerSetByUs = false
        }
        callStatus = null
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
        private const val ALERT_ID = 41
        const val ACTION_SPEAKER = "it.edoardo.appunti.SPEAKER"
        const val EXTRA_TITLE = "title"
        const val ACTION_START = "it.edoardo.appunti.START"
        const val ACTION_PAUSE = "it.edoardo.appunti.PAUSE"
        const val ACTION_RESUME = "it.edoardo.appunti.RESUME"
        const val ACTION_STOP = "it.edoardo.appunti.STOP"

        private val _state = MutableStateFlow<RecState>(RecState.Idle)
        val state: StateFlow<RecState> = _state

        fun start(context: Context, title: String? = null) {
            ContextCompat.startForegroundService(
                context, Intent(context, RecorderService::class.java).setAction(ACTION_START).putExtra(EXTRA_TITLE, title)
            )
        }

        /** Mette il vivavoce della chiamata in corso (pulsante nella schermata di registrazione). */
        fun speaker(context: Context) = send(context, ACTION_SPEAKER)

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, RecorderService::class.java).setAction(action))
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stop(context: Context) = send(context, ACTION_STOP)
    }
}
