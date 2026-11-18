package it.edoardo.appunti.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.edoardo.appunti.app
import it.edoardo.appunti.audio.RecState
import it.edoardo.appunti.audio.RecorderService
import it.edoardo.appunti.data.Note
import it.edoardo.appunti.data.NotesRepo
import it.edoardo.appunti.data.Status
import it.edoardo.appunti.data.Transcript
import it.edoardo.appunti.whisper.Models
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val dao = context.app.db.notes()
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    val notesFlow = remember(query) { if (query.isBlank()) dao.observeAll() else dao.search(query.trim()) }
    val notes by notesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val rec by RecorderService.state.collectAsStateWithLifecycle()
    // Ricalcolato a ogni ritorno sulla schermata (es. dopo le Impostazioni).
    val hasModel = context.app.prefs.modelPath != null

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            RecorderService.start(context)
        } else {
            Toast.makeText(context, "Serve il permesso del microfono per registrare", Toast.LENGTH_LONG).show()
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                onOpen(NotesRepo.importAudio(context, uri))
            } catch (e: Exception) {
                Toast.makeText(context, "Impossibile importare: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun startRecording() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) RecorderService.start(context) else permissions.launch(needed.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appunti AI") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Impostazioni") }
                },
            )
        },
        floatingActionButton = {
            when (rec) {
                is RecState.Idle -> ExtendedFloatingActionButton(
                    onClick = { startRecording() },
                    icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                    text = { Text("Registra") },
                )
                else -> Unit
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { RecordingBanner(rec) }
            if (!hasModel) item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth().clickable { onSettings() },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Manca il modello di trascrizione", fontWeight = FontWeight.SemiBold)
                        Text("Tocca qui e scaricane uno (consigliato: Small) per trascrivere sul telefono.")
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Cerca nelle note") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Cancella ricerca")
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { pickFile.launch(arrayOf("audio/*", "video/*", "application/ogg")) }) {
                        Icon(Icons.Default.AudioFile, contentDescription = "Importa audio")
                    }
                }
            }
            if (notes.isEmpty()) item {
                Text(
                    if (query.isBlank()) "Nessuna nota. Registra qualcosa, oppure condividi un audio da WhatsApp o dal registratore con \"Trascrivi con Appunti\"."
                    else "Nessun risultato.",
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(notes, key = { it.id }) { note -> NoteRow(note) { onOpen(note.id) } }
        }
    }
}

@Composable
private fun RecordingBanner(rec: RecState) {
    val context = LocalContext.current
    when (rec) {
        is RecState.Active -> {
            var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
            LaunchedEffect(rec.paused, rec.resumedAt) {
                while (true) {
                    now = SystemClock.elapsedRealtime()
                    delay(500)
                }
            }
            val container = if (rec.paused) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
            Card(colors = CardDefaults.cardColors(containerColor = container)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        if (rec.paused) "Registrazione in pausa" else "Registrazione in corso",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(Transcript.time(rec.elapsed(now)), style = MaterialTheme.typography.headlineSmall)
                    if (!rec.paused) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (rec.level * 3f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                        )
                    } else {
                        Text(
                            "Il microfono è spento. Riprendi quando vuoi: la registrazione continua nello stesso file.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (rec.paused) {
                            Button(onClick = { RecorderService.resume(context) }) {
                                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Riprendi")
                            }
                        } else {
                            OutlinedButton(onClick = { RecorderService.pause(context) }) {
                                Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Pausa")
                            }
                        }
                        Button(
                            onClick = { RecorderService.stop(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Termina e trascrivi")
                        }
                    }
                }
            }
        }
        is RecState.Saving -> Card {
            Text("Salvataggio della registrazione…", Modifier.padding(16.dp))
        }
        RecState.Idle -> Unit
    }
}

@Composable
private fun NoteRow(note: Note, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(note.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = buildList {
                add(Transcript.date(note.createdAt))
                if (note.durationMs > 0) add(Transcript.time(note.durationMs))
                Models.labelFor(note.modelName)?.let { add(it) }
            }.joinToString(" · ")
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatusLine(note)
        }
    }
}

@Composable
fun StatusLine(note: Note) {
    when (note.status) {
        Status.QUEUED -> Text(
            if (note.improveModel != null) "Miglioramento in attesa" else "In coda per la trascrizione",
            style = MaterialTheme.typography.bodySmall,
        )
        Status.RUNNING -> Box(Modifier.padding(top = 6.dp)) {
            Column {
                Text(
                    (if (note.improveModel != null) "Miglioramento " else "Trascrizione ") + "${note.progress}%",
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(progress = { note.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
        Status.ERROR -> Text(
            "Errore: ${note.error ?: "sconosciuto"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Status.CANCELLED -> Text("Trascrizione annullata", style = MaterialTheme.typography.bodySmall)
        else -> Unit
    }
}
