package it.edoardo.appunti.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

@Composable
fun NotesListScreen(onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val rec by RecorderService.state.collectAsStateWithLifecycle()
    if (rec !is RecState.Idle) {
        RecordingScreen(rec)
        return
    }

    val context = LocalContext.current
    val dao = context.app.db.notes()
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var whatsappHelp by remember { mutableStateOf(false) }
    val notesFlow = remember(query) { if (query.isBlank()) dao.observeAll() else dao.search(query.trim()) }
    val notes by notesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("MeNoter", style = MaterialTheme.typography.headlineMedium)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Impostazioni", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            HeroCard(
                onRecord = { startRecording() },
                onImport = { pickFile.launch(arrayOf("audio/*", "video/*", "application/ogg")) },
                onWhatsApp = { whatsappHelp = true },
            )
        }

        if (!hasModel) item {
            AppCard(onClick = onSettings, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Default.Download, Color.White.copy(alpha = 0.6f), MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Scarica il modello di trascrizione", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("Una volta sola, poi funziona offline. Consigliato: Small.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                placeholder = { Text("Cerca in note, trascrizioni e risposte") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Cancella ricerca")
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
        }

        item {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                Text(if (query.isBlank()) "Le tue note" else "Risultati", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                if (notes.isNotEmpty()) Text("${notes.size}", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (notes.isEmpty()) item {
            Text(
                if (query.isBlank()) "Ancora nessuna nota. Registra una riunione o importa un vocale: la trascrizione avviene qui sul telefono."
                else "Nessun risultato.",
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(notes, key = { it.id }) { note -> NoteCard(note) { onOpen(note.id) } }
    }

    if (whatsappHelp) {
        AlertDialog(
            onDismissRequest = { whatsappHelp = false },
            title = { Text("Trascrivere un vocale") },
            text = {
                Text("In WhatsApp (o nel registratore del telefono) tieni premuto il vocale, tocca Condividi e scegli \"Trascrivi con MeNoter\". La nota si apre qui da sola.")
            },
            confirmButton = { TextButton(onClick = { whatsappHelp = false }) { Text("Ho capito") } },
        )
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..12 -> "Buongiorno"
    in 13..17 -> "Buon pomeriggio"
    else -> "Buonasera"
}

@Composable
private fun HeroCard(onRecord: () -> Unit, onImport: () -> Unit, onWhatsApp: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(BrandGradient)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nuova nota", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Registra una riunione o un appunto vocale. Puoi mettere in pausa quando vuoi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(onClick = onRecord),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Registra", tint = Brand, modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroChip(Icons.Default.UploadFile, "Importa audio", onImport)
            HeroChip(Icons.Default.AudioFile, "Da WhatsApp", onWhatsApp)
        }
    }
}

@Composable
private fun HeroChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun NoteCard(note: Note, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (icon, container, tint) = when {
        note.audioPath == null -> Triple(Icons.Default.Description, cs.tertiaryContainer, cs.tertiary)
        File(note.audioPath).name.startsWith("rec-") -> Triple(Icons.Default.Mic, cs.primaryContainer, cs.primary)
        else -> Triple(Icons.Default.AudioFile, cs.secondaryContainer, cs.secondary)
    }
    AppCard(onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, container, tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = buildList {
                    add(Transcript.date(note.createdAt))
                    if (note.durationMs > 0) add(Transcript.time(note.durationMs))
                }.joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                StatusLine(note)
            }
        }
    }
}

@Composable
fun StatusLine(note: Note) {
    val cs = MaterialTheme.colorScheme
    val improving = note.improveModel != null
    when (note.status) {
        Status.QUEUED -> StatusChip(if (improving) "Miglioramento in attesa" else "In coda", cs.onSurfaceVariant)
        Status.RUNNING -> Column {
            StatusChip((if (improving) "Miglioramento " else "Trascrizione ") + "${note.progress}%", cs.primary)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { note.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
            )
        }
        Status.ERROR -> StatusChip("Errore", cs.error)
        Status.CANCELLED -> StatusChip("Annullata", cs.onSurfaceVariant)
        Status.TEXT -> StatusChip("Testo", cs.tertiary)
        Status.DONE -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip("Pronta", SuccessGreen)
            Models.labelFor(note.modelName)?.let { StatusChip(it, cs.onSurfaceVariant) }
        }
        else -> Unit
    }
}
