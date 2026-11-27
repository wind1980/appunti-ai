package it.edoardo.appunti.audio

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import it.edoardo.appunti.App
import it.edoardo.appunti.MainActivity
import it.edoardo.appunti.R
import it.edoardo.appunti.app

/**
 * Servizio di accessibilità di MeNoter. Non legge lo schermo e non tocca nulla: serve perché Android,
 * durante una chiamata, lascia registrare dal microfono solo l'app della chiamata e i servizi di
 * accessibilità. Guarda solo quale app è in primo piano (per dare il nome alla chiamata) e, quando inizia
 * una chiamata, propone di registrarla con una notifica.
 */
class CallCaptureService : AccessibilityService() {

    private var am: AudioManager? = null
    private var offeredThisCall = false
    private var modeListener: Any? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val audio = getSystemService(AudioManager::class.java)
        am = audio
        if (Build.VERSION.SDK_INT >= 31) {
            val l = AudioManager.OnModeChangedListener { checkCall() }
            audio.addOnModeChangedListener(mainExecutor, l)
            modeListener = l
        }
        checkCall()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in CallMonitor.CALL_APPS) lastCallApp = pkg
        checkCall()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 31) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let { am?.removeOnModeChangedListener(it) }
        }
        super.onDestroy()
    }

    private fun checkCall() {
        val audio = am ?: return
        val inCall = CallMonitor.inCall(audio)
        val nm = getSystemService(NotificationManager::class.java)
        if (!inCall) {
            if (offeredThisCall) nm.cancel(OFFER_ID)
            offeredThisCall = false
            return
        }
        if (offeredThisCall || !app.prefs.offerCallRecording) return
        if (RecorderService.state.value !is RecState.Idle) return
        offeredThisCall = true
        val appName = currentAppName()
        val open = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_RECORD_CALL)
                .putExtra(MainActivity.EXTRA_CALL_APP, appName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            OFFER_ID,
            NotificationCompat.Builder(this, App.CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Chiamata in corso" + (appName?.let { " su $it" } ?: ""))
                .setContentText("Tocca per registrarla con MeNoter come promemoria")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60_000L)
                .setContentIntent(open)
                .addAction(0, "Registra", open)
                .build(),
        )
    }

    companion object {
        const val OFFER_ID = 40

        /** Ultima app di chiamata vista in primo piano. */
        @Volatile var lastCallApp: String? = null

        fun currentAppName(): String? = lastCallApp?.let { CallMonitor.CALL_APPS[it] }
    }
}
