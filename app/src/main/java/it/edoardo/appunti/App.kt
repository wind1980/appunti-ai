package it.edoardo.appunti

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.webkit.WebView
import it.edoardo.appunti.data.AppDatabase
import it.edoardo.appunti.data.Prefs

class App : Application() {

    val db: AppDatabase by lazy { AppDatabase.build(this) }
    val prefs: Prefs by lazy { Prefs(this) }

    override fun onCreate() {
        super.onCreate()
        // Per salvare come immagine l'intera mappa o infografica, non solo la parte visibile.
        WebView.enableSlowWholeDocumentDraw()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Registrazione", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSCRIPTION, "Trascrizione", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Chiamate", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Proposta di registrare una chiamata e avvisi su cosa si riesce a registrare"
            }
        )
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_TRANSCRIPTION = "transcription"
        const val CHANNEL_CALLS = "calls"
    }
}

val Context.app: App get() = applicationContext as App
