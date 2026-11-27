package it.edoardo.appunti.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings

/** Dove esce l'audio della chiamata. Solo con il vivavoce il microfono sente anche gli altri. */
enum class CallRoute(val label: String) {
    SPEAKER("vivavoce"),
    EARPIECE("auricolare del telefono"),
    WIRED("cuffie con il filo"),
    BLUETOOTH("auricolari o auto Bluetooth"),
    UNKNOWN("uscita sconosciuta"),
}

/** Stato della chiamata durante la registrazione, mostrato nella schermata e nelle notifiche. */
data class CallStatus(
    val inCall: Boolean,
    val route: CallRoute,
    val accessibilityOn: Boolean,
    val silenced: Boolean,
    val app: String?,
) {
    /** La voce degli altri arriva al microfono solo se esce dal vivavoce. */
    val othersAudible: Boolean get() = !inCall || route == CallRoute.SPEAKER
}

/**
 * Cosa sa fare (e cosa no) un'app normale durante le chiamate, secondo le regole di Android:
 * - durante una chiamata solo un servizio di accessibilità può registrare dal microfono insieme all'app
 *   della chiamata (le altre app ricevono silenzio): per questo MeNoter ha il suo servizio di accessibilità;
 * - la voce degli altri (Teams, WhatsApp, Meet, Webex, telefono) non si può prendere in digitale:
 *   Android la riserva alle app di sistema. Il microfono la sente solo se esce dal vivavoce.
 */
object CallMonitor {

    /** App di chiamata riconosciute (pacchetto → nome). */
    val CALL_APPS = mapOf(
        "com.microsoft.teams" to "Teams",
        "com.microsoft.skype.teams.ipphone" to "Teams",
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.google.android.apps.meetings" to "Meet",
        "com.google.android.apps.tachyon" to "Meet",
        "com.google.android.gm" to "Meet (Gmail)",
        "com.cisco.webex.meetings" to "Webex",
        "com.cisco.wx2.android" to "Webex",
        "us.zoom.videomeetings" to "Zoom",
        "com.skype.raider" to "Skype",
        "org.telegram.messenger" to "Telegram",
        "com.google.android.dialer" to "Telefono",
        "com.samsung.android.incallui" to "Telefono",
        "com.samsung.android.dialer" to "Telefono",
        "com.android.incallui" to "Telefono",
        "com.android.dialer" to "Telefono",
        "com.android.server.telecom" to "Telefono",
    )

    fun inCall(am: AudioManager): Boolean =
        am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION

    fun route(am: AudioManager): CallRoute {
        if (Build.VERSION.SDK_INT >= 31) {
            val type = am.communicationDevice?.type ?: return legacyRoute(am)
            return when (type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> CallRoute.SPEAKER
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> CallRoute.EARPIECE
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> CallRoute.WIRED
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_HEARING_AID -> CallRoute.BLUETOOTH
                else -> CallRoute.UNKNOWN
            }
        }
        return legacyRoute(am)
    }

    @Suppress("DEPRECATION")
    private fun legacyRoute(am: AudioManager): CallRoute = when {
        am.isSpeakerphoneOn -> CallRoute.SPEAKER
        am.isBluetoothScoOn -> CallRoute.BLUETOOTH
        am.isWiredHeadsetOn -> CallRoute.WIRED
        else -> CallRoute.EARPIECE
    }

    /**
     * Mette (o toglie) il vivavoce della chiamata in corso. Funziona con quasi tutte le app di chiamata;
     * se l'app della chiamata riprende il controllo dell'audio, si può rifare dal pulsante.
     */
    @Suppress("DEPRECATION")
    fun setSpeaker(context: Context, on: Boolean): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    val speaker = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    } ?: return false
                    am.setCommunicationDevice(speaker)
                } else {
                    am.clearCommunicationDevice()
                    true
                }
            } else {
                am.isSpeakerphoneOn = on
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** True se il servizio di accessibilità di MeNoter è attivo (serve per registrare durante le chiamate). */
    fun accessibilityOn(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(context, CallCaptureService::class.java)
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it)?.let { c -> c == me } ?: false
        }
    }

    fun accessibilitySettings(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Dettagli dell'app MeNoter, dove su Android 13+ si sbloccano le "impostazioni con limitazioni". */
    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
