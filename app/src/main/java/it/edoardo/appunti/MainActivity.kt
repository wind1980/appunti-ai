package it.edoardo.appunti

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.edoardo.appunti.data.NotesRepo
import it.edoardo.appunti.ui.AppuntiTheme
import it.edoardo.appunti.ui.NoteScreen
import it.edoardo.appunti.ui.NotesListScreen
import it.edoardo.appunti.ui.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Nota da aprire appena importato un audio condiviso. */
    private val openNote = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)

        setContent {
            AppuntiTheme {
                val nav = rememberNavController()
                val pending = openNote.value
                LaunchedEffect(pending) {
                    if (pending != null) {
                        nav.navigate("note/$pending") { launchSingleTop = true }
                        openNote.value = null
                    }
                }
                NavHost(navController = nav, startDestination = "list") {
                    composable("list") {
                        NotesListScreen(
                            onOpen = { id -> nav.navigate("note/$id") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable(
                        "note/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        NoteScreen(
                            id = entry.arguments?.getLong("id") ?: 0L,
                            onBack = { nav.popBackStack() },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri = (if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }) ?: return
        lifecycleScope.launch {
            try {
                val id = NotesRepo.importAudio(applicationContext, uri)
                openNote.value = id
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Impossibile importare il file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
