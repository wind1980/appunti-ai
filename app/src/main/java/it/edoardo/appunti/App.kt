package it.edoardo.appunti

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import it.edoardo.appunti.data.AppDatabase
import it.edoardo.appunti.data.Prefs

class App : Application() {

    val db: AppDatabase by lazy { AppDatabase.build(this) }
    val prefs: Prefs by lazy { Prefs(this) }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Registrazione", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSCRIPTION, "Trascrizione", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_TRANSCRIPTION = "transcription"
    }
}

val Context.app: App get() = applicationContext as App
