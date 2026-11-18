package it.edoardo.appunti.ui

import android.content.ClipboardManager
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.edoardo.appunti.app
import it.edoardo.appunti.data.ClaudeResponse
import it.edoardo.appunti.data.Note
import it.edoardo.appunti.data.NotesRepo
import it.edoardo.appunti.data.PromptKind
import it.edoardo.appunti.data.Status
import it.edoardo.appunti.data.Transcript
import it.edoardo.appunti.share.ClaudeShare
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteScreen(id: Long, onBack: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val dao = context.app.db.notes()
    val scope = rememberCoroutineScope()

    val note by remember(id) { dao.observe(id) }.collectAsStateWithLifecycle(initialValue = null)
    val responses by remember(id) { dao.observeResponses(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val n = note ?: run {
        Scaffold { p -> Text("Caricamento…", Modifier.padding(p).padding(16.dp)) }
        return
    }
    val paragraphs = remember(n.segments) { Transcript.paragraphs(Transcript.parse(n.segments)) }
    val hasText = paragraphs.isNotEmpty()

    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val player = rememberPlayer(n.audioPath)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(n.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Altro") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rinomina") }, onClick = { menu = false; renaming = true })
                        if (hasText) DropdownMenuItem(
                            text = { Text("Condividi trascrizione…") },
                            onClick = {
                                menu = false
                                ClaudeShare.shareText(context, n.title, Transcript.readable(n))
                            },
                        )
                        if (n.audioPath != null && n.status != Status.RUNNING && n.status != Status.QUEUED) DropdownMenuItem(
                            text = { Text("Trascrivi di nuovo") },
                            onClick = { menu = false; scope.launch { NotesRepo.retranscribe(context, n.id) } },
                        )
                        DropdownMenuItem(text = { Text("Elimina nota") }, onClick = { menu = false; confirmDelete = true })
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    buildList {
                        add(Transcript.date(n.createdAt))
                        if (n.durationMs > 0) add(Transcript.time(n.durationMs))
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { StatusCard(n, onSettings) }
            if (player != null) item { PlayerBar(player) }

            if (hasText) item {
                Column {
                    Text("Manda a Claude", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PromptKind.entries.forEach { kind ->
                            FilledTonalButton(onClick = { ClaudeShare.share(context, n, kind) }) { Text(kind.label) }
                        }
                        OutlinedButton(onClick = { ClaudeShare.copy(context, n.title, Transcript.readable(n)) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Copia testo")
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Risposte di Claude", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val text = context.getSystemService<ClipboardManager>()?.primaryClip
                            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (text.isNullOrBlank()) {
                            Toast.makeText(context, "Gli appunti sono vuoti: copia prima la risposta in Claude", Toast.LENGTH_LONG).show()
                        } else {
                            scope.launch { NotesRepo.saveResponse(context, n.id, text) }
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Incolla")
                    }
                }
                if (responses.isEmpty()) Text(
                    "In Claude copia la risposta e tocca \"Incolla\", oppure seleziona il testo e scegli Condividi → \"Salva in Appunti\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(responses, key = { "r${it.id}" }) { r -> ResponseCard(n, r) }

            if (hasText) {
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Trascrizione", style = MaterialTheme.typography.titleSmall)
                }
                items(paragraphs.size) { i ->
                    val p = paragraphs[i]
                    Column {
                        Text(
                            Transcript.time(p.startMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 2.dp).let { m ->
                                if (player != null) m.clickable { player.seekAndPlay(p.startMs) } else m
                            },
                        )
                        SelectionContainer { Text(p.text, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(n.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rinomina") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    if (title.isNotBlank()) scope.launch { dao.rename(n.id, title.trim()) }
                }) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Annulla") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminare la nota?") },
            text = { Text("Verranno eliminati audio, trascrizione e risposte salvate.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        NotesRepo.delete(context, n)
                        onBack()
                    }
                }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun StatusCard(n: Note, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    when (n.status) {
        Status.QUEUED, Status.RUNNING -> Card {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (n.status == Status.QUEUED) "In coda per la trascrizione" else "Trascrizione sul telefono: ${n.progress}%",
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                if (n.status == Status.RUNNING) {
                    LinearProgressIndicator(progress = { n.progress / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    "Il testo compare qui man mano. Puoi chiudere l'app: la trascrizione continua.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = { scope.launch { NotesRepo.cancel(context, n.id) } }) { Text("Annulla") }
            }
        }
        Status.ERROR -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text("Trascrizione non riuscita", fontWeight = FontWeight.SemiBold)
                Text(n.error ?: "Errore sconosciuto")
                Row {
                    TextButton(onClick = { scope.launch { NotesRepo.resume(context, n.id) } }) { Text("Riprova") }
                    TextButton(onClick = onSettings) { Text("Impostazioni") }
                }
            }
        }
        Status.CANCELLED -> Card {
            Column(Modifier.padding(16.dp)) {
                Text("Trascrizione annullata", fontWeight = FontWeight.SemiBold)
                Row {
                    if (n.processedSamples > 0) {
                        TextButton(onClick = { scope.launch { NotesRepo.resume(context, n.id) } }) { Text("Riprendi") }
                    }
                    TextButton(onClick = { scope.launch { NotesRepo.retranscribe(context, n.id) } }) { Text("Ricomincia") }
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun ResponseCard(note: Note, r: ClaudeResponse) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                Transcript.date(r.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            SelectionContainer { Text(r.text, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { ClaudeShare.copy(context, note.title, r.text) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copia")
                }
                IconButton(onClick = { ClaudeShare.shareText(context, note.title, r.text) }) {
                    Icon(Icons.Default.Share, contentDescription = "Condividi (Notion, Docs, email…)")
                }
                IconButton(onClick = { scope.launch { context.app.db.notes().deleteResponse(r) } }) {
                    Icon(Icons.Default.Delete, contentDescription = "Elimina")
                }
            }
        }
    }
}

/** Lettore audio minimo per riascoltare la registrazione e saltare ai paragrafi. */
class Player(val mp: MediaPlayer) {
    var playing by mutableStateOf(false)
    var position by mutableIntStateOf(0)
    val duration: Int = mp.duration.coerceAtLeast(1)

    fun toggle() {
        if (mp.isPlaying) mp.pause() else mp.start()
        playing = mp.isPlaying
    }

    fun seekAndPlay(ms: Long) {
        mp.seekTo(ms.toInt())
        position = ms.toInt()
        if (!mp.isPlaying) mp.start()
        playing = true
    }
}

@Composable
private fun rememberPlayer(path: String?): Player? {
    val player = remember(path) {
        val f = path?.let(::File)?.takeIf { it.exists() } ?: return@remember null
        try {
            Player(MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
            })
        } catch (e: Exception) {
            null
        }
    }
    DisposableEffect(player) {
        if (player != null) player.mp.setOnCompletionListener { player.playing = false }
        onDispose { player?.mp?.release() }
    }
    LaunchedEffect(player, player?.playing) {
        while (player != null && player.playing) {
            player.position = player.mp.currentPosition
            delay(250)
        }
    }
    return player
}

@Composable
private fun PlayerBar(player: Player) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    Card {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.toggle() }) {
                Icon(
                    if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (player.playing) "Pausa" else "Ascolta",
                )
            }
            Slider(
                value = if (dragging) dragValue else player.position.toFloat() / player.duration,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    dragging = false
                    val ms = (dragValue * player.duration).toInt()
                    player.mp.seekTo(ms)
                    player.position = ms
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                Transcript.time(player.position.toLong()),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}
