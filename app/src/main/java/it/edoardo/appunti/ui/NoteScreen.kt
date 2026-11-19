package it.edoardo.appunti.ui

import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Description
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import it.edoardo.appunti.share.ResponseCleaner
import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.edoardo.appunti.app
import it.edoardo.appunti.data.ClaudeResponse
import it.edoardo.appunti.data.Note
import it.edoardo.appunti.data.NotesRepo
import it.edoardo.appunti.data.PromptKind
import it.edoardo.appunti.data.Status
import it.edoardo.appunti.data.SummaryLevel
import it.edoardo.appunti.data.TaskItem
import it.edoardo.appunti.data.Template
import it.edoardo.appunti.data.TRANSLATE_LANGUAGES
import it.edoardo.appunti.data.Transcript
import it.edoardo.appunti.share.ClaudeShare
import it.edoardo.appunti.whisper.Models
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Azioni della barra Claude, nell'ordine in cui compaiono. */
private val BAR_ACTIONS = listOf(
    PromptKind.SUMMARY, PromptKind.ACTIONS, PromptKind.SPEAKERS, PromptKind.MINDMAP, PromptKind.INFOGRAPHIC,
    PromptKind.TRANSLATE, PromptKind.EMAIL,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(
    id: Long,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onOpenResponse: (Long) -> Unit,
    initialQuery: String = "",
) {
    val context = LocalContext.current
    val dao = context.app.db.notes()
    val scope = rememberCoroutineScope()

    val note by remember(id) { dao.observe(id) }.collectAsStateWithLifecycle(initialValue = null)
    val responses by remember(id) { dao.observeResponses(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val tasks by remember(id) { dao.observeTasks(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    // Ricerca nella trascrizione (arriva già compilata se la nota è stata aperta dalla ricerca nell'elenco).
    var transcriptQuery by rememberSaveable { mutableStateOf(initialQuery) }
    var showSpeakers by rememberSaveable { mutableStateOf(true) }
    var editingTask by remember { mutableStateOf<TaskItem?>(null) }
    var addingTask by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val n = note ?: run {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val paragraphs = remember(n.segments) { Transcript.paragraphs(Transcript.parse(n.segments)) }
    val hasText = paragraphs.isNotEmpty()

    // 0 = Riepilogo AI, 1 = Trascrizione. Si apre sulla trascrizione finché non ci sono risposte.
    // 0 = Riepilogo AI, 1 = Grafica, 2 = Trascrizione. Si apre sulla trascrizione finché non ci sono risposte.
    var tab by rememberSaveable { mutableIntStateOf(-1) }
    val selectedTab = if (tab >= 0) tab else if (responses.isEmpty() || initialQuery.isNotBlank()) 2 else 0
    // La trascrizione divisa per interlocutore si vede nella scheda Trascrizione.
    val speakersResponse = responses.firstOrNull { it.kind == PromptKind.SPEAKERS.key }
    val textResponses = responses.filter { viewKindOf(it) == null && it.kind != PromptKind.SPEAKERS.key }
    val visualResponses = responses.filter { viewKindOf(it) != null }
    val addFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                NotesRepo.saveFile(context, n.id, uri)
                tab = 1
            } catch (e: Exception) {
                Toast.makeText(context, "Impossibile aggiungere il file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val player = rememberPlayer(n.audioPath)
    val prefs = context.app.prefs

    // Al ritorno da Claude: se negli appunti c'è una risposta nuova, la si propone con un tocco.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeTick++
        onPauseOrDispose { }
    }
    var clip by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resumeTick, responses.size) {
        clip = null
        if (prefs.recentSentNoteId() != n.id) return@LaunchedEffect
        delay(600) // Android lascia leggere gli appunti solo quando la finestra ha il focus
        val text = readClipboard(context)?.trim()
        clip = text?.takeIf {
            it.length >= 30 && it.hashCode() != prefs.handledClipHash && responses.none { r -> r.text == it }
        }
    }

    fun send(kind: PromptKind, question: String? = null) {
        if (!hasText) {
            Toast.makeText(context, "La trascrizione non è ancora pronta", Toast.LENGTH_SHORT).show()
            return
        }
        // Sempre una richiesta completa (comando + trascrizione): Claude non torna sulla chat
        // precedente quando lo si riapre da fuori, quindi ogni invio deve bastare da solo.
        ClaudeShare.share(context, n, kind, question)
        // Al ritorno si è già sulla scheda giusta: Grafica per mappe e infografiche,
        // Trascrizione per gli interlocutori, Riepilogo per il resto.
        tab = when (kind) {
            PromptKind.MINDMAP, PromptKind.INFOGRAPHIC -> 1
            PromptKind.SPEAKERS -> 2
            else -> 0
        }
    }

    fun sendTemplate(t: Template) {
        if (!hasText) {
            Toast.makeText(context, "La trascrizione non è ancora pronta", Toast.LENGTH_SHORT).show()
            return
        }
        ClaudeShare.shareTemplate(context, n, t)
        tab = 0
    }

    fun sendSummary(level: SummaryLevel) {
        if (!hasText) {
            Toast.makeText(context, "La trascrizione non è ancora pronta", Toast.LENGTH_SHORT).show()
            return
        }
        ClaudeShare.shareSummary(context, n, level)
        tab = 0
    }

    var pasteMenu by remember { mutableStateOf(false) }

    /** Salva un testo copiato da Claude con il tipo scelto (null = testo normale). */
    fun saveAs(text: String, kind: PromptKind?, kindKey: String? = kind?.key) {
        scope.launch {
            val ok = NotesRepo.saveResponse(context, n.id, text, kindKey, strict = kind == PromptKind.INFOGRAPHIC)
            if (!ok) {
                Toast.makeText(
                    context,
                    "Negli appunti non c'è il codice dell'infografica. In Claude apri l'infografica (artefatto) e usa Copia, poi riprova.",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                clip = null
                tab = when (kind) {
                    PromptKind.MINDMAP, PromptKind.INFOGRAPHIC -> 1
                    PromptKind.SPEAKERS -> 2
                    else -> 0
                }
                val label = kind?.let { typeLabel(it) } ?: prefs.template(kindKey)?.name
                Toast.makeText(context, "Salvata" + (label?.let { " come $it" } ?: ""), Toast.LENGTH_SHORT).show()
                if (kind == PromptKind.ACTIONS) {
                    Toast.makeText(context, "Le attività sono in cima al Riepilogo: spuntale o aggiungi un promemoria", Toast.LENGTH_LONG).show()
                }
                if (kind == PromptKind.MINDMAP && !MindMap.looksLikeMap(text)) {
                    Toast.makeText(context, "Non sembra una mappa a livelli: la vedrai come testo", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(n.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Altro") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rinomina") }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Esporta (PDF, Word, TXT)…") }, onClick = { menu = false; exporting = true })
                        if (hasText) {
                            DropdownMenuItem(
                                text = { Text("Copia trascrizione") },
                                onClick = { menu = false; ClaudeShare.copy(context, n.title, Transcript.readable(n)) },
                            )
                            DropdownMenuItem(
                                text = { Text("Condividi trascrizione…") },
                                onClick = { menu = false; ClaudeShare.shareText(context, n.title, Transcript.readable(n)) },
                            )
                        }
                        if (n.audioPath != null && hasText && n.status == Status.DONE) {
                            val precise = context.app.prefs.preciseModelPath
                            val label = Models.labelFor(precise)
                            DropdownMenuItem(
                                text = { Text(if (label != null) "Migliora con $label" else "Migliora trascrizione…") },
                                onClick = {
                                    menu = false
                                    if (precise == null) {
                                        Toast.makeText(context, "Scegli prima il modello per \"Migliora\" nelle Impostazioni", Toast.LENGTH_LONG).show()
                                        onSettings()
                                    } else {
                                        scope.launch { NotesRepo.improve(context, n.id) }
                                    }
                                },
                            )
                        }
                        if (n.audioPath != null && n.status != Status.RUNNING && n.status != Status.QUEUED) DropdownMenuItem(
                            text = { Text("Trascrivi di nuovo") },
                            onClick = { menu = false; scope.launch { NotesRepo.retranscribe(context, n.id) } },
                        )
                        DropdownMenuItem(text = { Text("Elimina nota") }, onClick = { menu = false; confirmDelete = true })
                    }
                },
            )
        },
        bottomBar = {
            ClaudeBar(
                enabled = hasText,
                player = player,
                onAction = { send(it) },
                onAsk = { q -> send(PromptKind.ASK, q) },
                onTemplate = { sendTemplate(it) },
                onSummaryLevel = { sendSummary(it) },
                onManageTemplates = onSettings,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    buildList {
                        add(Transcript.date(n.createdAt))
                        if (n.durationMs > 0) add(Transcript.time(n.durationMs))
                        Models.labelFor(n.modelName)?.let { add(it) }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { StatusCard(n, onSettings) }
            clip?.let { text ->
                item {
                    ClipboardProposal(
                        raw = text,
                        kind = PromptKind.fromKey(prefs.lastSentKind),
                        templateName = prefs.template(prefs.lastSentKind)?.name,
                        // L'infografica viene riconosciuta da sola se c'è il codice HTML.
                        onSave = {
                            val last = prefs.lastSentKind
                            val k = PromptKind.fromKey(last)?.takeIf { it != PromptKind.ASK && it != PromptKind.INFOGRAPHIC }
                            saveAs(text, k, k?.key ?: prefs.template(last)?.key)
                        },
                        onSaveAs = { kind -> saveAs(text, kind) },
                        onIgnore = {
                            prefs.handledClipHash = text.hashCode()
                            clip = null
                        },
                    )
                }
            }
            item {
                SegmentedTabs(
                    labels = listOf("Riepilogo AI", "Grafica", "Trascrizione"),
                    selected = selectedTab,
                    onSelect = { tab = it },
                )
            }

            if (selectedTab == 0) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (responses.isEmpty()) "" else "Risposte di Claude",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            TextButton(onClick = { pasteMenu = true }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Incolla come…")
                            }
                            PasteKindMenu(expanded = pasteMenu, onDismiss = { pasteMenu = false }) { kind ->
                                pasteMenu = false
                                val text = readClipboard(context)
                                if (text.isNullOrBlank()) {
                                    Toast.makeText(context, "Gli appunti sono vuoti: in Claude copia prima la risposta", Toast.LENGTH_LONG).show()
                                } else {
                                    saveAs(text, kind)
                                }
                            }
                        }
                    }
                }
                if (tasks.isNotEmpty()) item(key = "tasks") {
                    TasksSection(n, tasks, onAdd = { addingTask = true }, onEdit = { editingTask = it })
                }
                if (textResponses.isEmpty()) item { EmptySummary(hasText) { send(PromptKind.SUMMARY) } }
                if (tasks.isEmpty() && textResponses.isNotEmpty()) item {
                    TextButton(onClick = { addingTask = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Aggiungi un'attività")
                    }
                }
                items(textResponses, key = { "r${it.id}" }) { r -> ResponseCard(n, r, onOpen = { onOpenResponse(r.id) }) }
            } else if (selectedTab == 1) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (visualResponses.isEmpty()) "" else "Mappe, infografiche e file",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { addFile.launch(arrayOf("image/*", "application/pdf")) }) {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Aggiungi immagine o PDF")
                        }
                    }
                }
                if (visualResponses.isEmpty()) item {
                    EmptyGraphics(
                        hasText = hasText,
                        onMap = { send(PromptKind.MINDMAP) },
                        onInfographic = { send(PromptKind.INFOGRAPHIC) },
                    )
                }
                items(visualResponses, key = { "v${it.id}" }) { r -> ResponseCard(n, r, onOpen = { onOpenResponse(r.id) }) }
            } else {
                if (!hasText) item {
                    Text(
                        if (n.status == Status.RUNNING || n.status == Status.QUEUED) "Il testo comparirà qui man mano che la trascrizione procede."
                        else "Nessuna trascrizione.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                val q = transcriptQuery.trim()
                val turns = if (speakersResponse != null && showSpeakers) parseTurns(speakersResponse.text) else null
                if (hasText) item(key = "search") {
                    val matches = when {
                        q.isEmpty() -> 0
                        turns != null -> turns.count { it.text.contains(q, ignoreCase = true) }
                        else -> paragraphs.count { it.text.contains(q, ignoreCase = true) }
                    }
                    TranscriptSearchField(transcriptQuery, matches) { transcriptQuery = it }
                }
                if (speakersResponse != null) item(key = "speakers-toggle") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            SegmentedTabs(
                                labels = listOf("Con interlocutori", "Originale"),
                                selected = if (showSpeakers) 0 else 1,
                                onSelect = { showSpeakers = it == 0 },
                            )
                        }
                        if (showSpeakers) IconButton(onClick = { scope.launch { NotesRepo.deleteResponse(context, speakersResponse) } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Elimina la versione con interlocutori",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (turns != null) {
                    val speakerIndex = turns.mapNotNull { it.speaker }.distinct().withIndex().associate { it.value to it.index }
                    val shown = if (q.isEmpty()) turns else turns.filter { it.text.contains(q, ignoreCase = true) }
                    items(shown.size, key = { "t$it" }) { i ->
                        val t = shown[i]
                        SpeakerTurn(t, speakerIndex[t.speaker] ?: 0, q, player?.let { pl -> { ms: Long -> pl.seekAndPlay(ms) } })
                    }
                } else {
                    val shown = if (q.isEmpty()) paragraphs else paragraphs.filter { it.text.contains(q, ignoreCase = true) }
                    if (q.isNotEmpty() && shown.isEmpty()) item {
                        Text("Nessun risultato per \"$q\".", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(shown.size) { i ->
                        val p = shown[i]
                        Column {
                            Text(
                                Transcript.time(p.startMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .let { m -> if (player != null) m.clickable { player.seekAndPlay(p.startMs) } else m }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            SelectionContainer {
                                Text(
                                    highlight(p.text, q, MaterialTheme.colorScheme.tertiaryContainer),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
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
    if (addingTask || editingTask != null) {
        TaskEditDialog(n.id, editingTask) {
            addingTask = false
            editingTask = null
        }
    }
    if (exporting) ExportDialog(n) { exporting = false }
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

private fun readClipboard(context: Context): String? =
    try {
        context.getSystemService<ClipboardManager>()?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    } catch (e: Exception) {
        null
    }

@Composable
private fun ClipboardProposal(
    raw: String,
    kind: PromptKind?,
    templateName: String?,
    onSave: () -> Unit,
    onSaveAs: (PromptKind?) -> Unit,
    onIgnore: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val cleaned = remember(raw) { ResponseCleaner.clean(raw) }
    val text = if (cleaned.isHtml) "Infografica (pagina HTML)" else cleaned.text
    AppCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ContentPaste, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Hai copiato una risposta di Claude", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(6.dp))
        Text(text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(12.dp))
        Column {
            GradientButton(
                when {
                    cleaned.isHtml -> "Salva infografica"
                    kind != null && kind != PromptKind.ASK && kind != PromptKind.INFOGRAPHIC -> "Salva come ${typeLabel(kind)}"
                    templateName != null -> "Salva come $templateName"
                    else -> "Salva nella nota"
                },
                Icons.Default.ContentPaste, onSave,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { menu = true }) { Text("Altro tipo…") }
                PasteKindMenu(expanded = menu, onDismiss = { menu = false }) { k ->
                    menu = false
                    onSaveAs(k)
                }
            }
            TextButton(onClick = onIgnore) { Text("Ignora") }
            }
        }
    }
}

private fun typeLabel(kind: PromptKind) = when (kind) {
    PromptKind.MINDMAP -> "mappa concettuale"
    PromptKind.INFOGRAPHIC -> "infografica"
    PromptKind.SPEAKERS -> "trascrizione con interlocutori"
    else -> kind.label.lowercase()
}

/** Tipi tra cui scegliere quando si incolla una risposta di Claude (null = testo normale). */
private val PASTE_KINDS = listOf(
    PromptKind.SUMMARY, PromptKind.ACTIONS, PromptKind.SPEAKERS, PromptKind.MINDMAP, PromptKind.INFOGRAPHIC, PromptKind.TRANSLATE, PromptKind.EMAIL,
)

@Composable
private fun PasteKindMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (PromptKind?) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        PASTE_KINDS.forEach { k ->
            DropdownMenuItem(
                text = { Text(typeLabel(k).replaceFirstChar { it.uppercase() }) },
                leadingIcon = { Icon(k.icon(), contentDescription = null) },
                onClick = { onPick(k) },
            )
        }
        DropdownMenuItem(
            text = { Text("Altro testo") },
            leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
            onClick = { onPick(null) },
        )
    }
}

/** Barra fissa in basso: lettore audio, funzioni Claude a un tocco e domanda libera. */
@Composable
private fun ClaudeBar(
    enabled: Boolean,
    player: Player?,
    onAction: (PromptKind) -> Unit,
    onAsk: (question: String) -> Unit,
    onTemplate: (Template) -> Unit,
    onSummaryLevel: (SummaryLevel) -> Unit,
    onManageTemplates: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    var question by rememberSaveable { mutableStateOf("") }
    var langMenu by remember { mutableStateOf(false) }
    var templatesMenu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(top = 8.dp, bottom = 10.dp),
        ) {
            if (player != null) PlayerBar(player)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BAR_ACTIONS.forEach { kind ->
                    if (kind == PromptKind.TRANSLATE) {
                        // Traduci: prima si sceglie la lingua (resta l'ultima usata).
                        Box {
                            ActionTile(kind.icon(), kind.label, enabled) { langMenu = true }
                            DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                                TRANSLATE_LANGUAGES.forEach { lang ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                lang.replaceFirstChar { it.uppercase() } +
                                                    if (lang == prefs.translateTarget) "  ✓" else "",
                                            )
                                        },
                                        onClick = {
                                            langMenu = false
                                            prefs.translateTarget = lang
                                            onAction(kind)
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        ActionTile(kind.icon(), kind.label, enabled) { onAction(kind) }
                    }
                }
                // Modelli: riassunto breve o dettagliato e i modelli personali.
                Box {
                    ActionTile(Icons.Default.LibraryBooks, "Modelli", enabled) { templatesMenu = true }
                    DropdownMenu(expanded = templatesMenu, onDismissRequest = { templatesMenu = false }) {
                        SummaryLevel.entries.forEach { level ->
                            DropdownMenuItem(
                                text = { Text("Riassunto ${level.label.lowercase()}") },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                                onClick = { templatesMenu = false; onSummaryLevel(level) },
                            )
                        }
                        prefs.templates.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.name) },
                                leadingIcon = { Icon(Icons.Default.LibraryBooks, contentDescription = null) },
                                onClick = { templatesMenu = false; onTemplate(t) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Crea o modifica modelli…") },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = { templatesMenu = false; onManageTemplates() },
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Chiedi a Claude su questa nota…") },
                    shape = RoundedCornerShape(28.dp),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (question.isNotBlank()) { onAsk(question); question = "" }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(BrandGradient)
                        .clickable {
                            if (question.isBlank()) {
                                Toast.makeText(context, "Scrivi prima la domanda per Claude", Toast.LENGTH_SHORT).show()
                            } else {
                                onAsk(question)
                                question = ""
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Chiedi a Claude", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val isSel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSel) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyGraphics(hasText: Boolean, onMap: () -> Unit, onInfographic: () -> Unit) {
    AppCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(BrandGradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.AccountTree, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("Mappe e infografiche", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Claude crea un'immagine PNG. In Claude tocca il file creato, poi Condividi → \"Salva in MeNoter\". " +
                    "Se l'hai scaricato, usa \"Aggiungi immagine o PDF\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientButton("Mappa", Icons.Default.AccountTree, onMap, enabled = hasText)
                GradientButton("Infografica", Icons.Default.Dashboard, onInfographic, enabled = hasText)
            }
        }
    }
}

@Composable
private fun EmptySummary(hasText: Boolean, onSummarize: () -> Unit) {
    AppCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(BrandGradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("Nessun riepilogo ancora", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                if (hasText) "Tocca Riassumi: si apre Claude con la trascrizione già pronta, tu premi Invia. " +
                    "Poi copia la risposta, torna qui e tocca \"Incolla come…\"."
                else "Quando la trascrizione è pronta potrai mandarla a Claude da qui.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            GradientButton("Riassumi con Claude", Icons.Default.AutoAwesome, onSummarize, enabled = hasText)
        }
    }
}

@Composable
private fun StatusCard(n: Note, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    when (n.status) {
        Status.QUEUED, Status.RUNNING -> AppCard(color = cs.primaryContainer) {
            val improving = n.improveModel != null
            val label = Models.labelFor(n.improveModel)
            Text(
                when {
                    improving && n.status == Status.QUEUED ->
                        if (context.app.prefs.improveOnlyCharging) "Miglioramento con $label: parte quando metti il telefono in carica"
                        else "Miglioramento con $label in coda"
                    improving -> "Miglioramento con $label: ${n.progress}%"
                    n.status == Status.QUEUED -> "In coda per la trascrizione"
                    else -> "Trascrizione sul telefono: ${n.progress}%"
                },
                style = MaterialTheme.typography.titleSmall,
                color = cs.onPrimaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            if (n.status == Status.RUNNING) {
                LinearProgressIndicator(
                    progress = { n.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    if (improving) "Intanto puoi usare la trascrizione attuale."
                    else "Puoi chiudere l'app: la trascrizione continua.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { scope.launch { NotesRepo.cancel(context, n.id) } }) { Text("Annulla") }
            }
        }
        Status.ERROR -> AppCard(color = cs.errorContainer) {
            Text("Trascrizione non riuscita", style = MaterialTheme.typography.titleSmall, color = cs.onErrorContainer)
            Text(n.error ?: "Errore sconosciuto", color = cs.onErrorContainer)
            Row {
                TextButton(onClick = { scope.launch { NotesRepo.resume(context, n.id) } }) { Text("Riprova") }
                TextButton(onClick = onSettings) { Text("Impostazioni") }
            }
        }
        Status.CANCELLED -> AppCard {
            Text("Trascrizione annullata", style = MaterialTheme.typography.titleSmall)
            Row {
                if (n.processedSamples > 0) {
                    TextButton(onClick = { scope.launch { NotesRepo.resume(context, n.id) } }) { Text("Riprendi") }
                }
                TextButton(onClick = { scope.launch { NotesRepo.retranscribe(context, n.id) } }) { Text("Ricomincia") }
            }
        }
        Status.DONE -> if (n.error != null) {
            Text(n.error, color = cs.error, style = MaterialTheme.typography.bodySmall)
        }
        else -> Unit
    }
}

@Composable
private fun ResponseCard(note: Note, r: ClaudeResponse, onOpen: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = viewKindOf(r)
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                (when (view) {
                    ViewKind.IMAGE -> "Immagine · "
                    ViewKind.PDF -> "PDF · "
                    else -> "Claude · "
                }) + Transcript.date(r.createdAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val chip = PromptKind.fromKey(r.kind)?.label
                ?: r.kind?.takeIf { it.startsWith(Template.TEMPLATE_PREFIX) }?.removePrefix(Template.TEMPLATE_PREFIX)
            chip?.let { StatusChip(it, MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.height(10.dp))
        when (view) {
            ViewKind.IMAGE -> {
                val bmp = remember(r.text) { decodeThumb(r.text) }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Immagine salvata",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onOpen),
                    )
                } else {
                    Text("Immagine non trovata", color = MaterialTheme.colorScheme.error)
                }
            }
            ViewKind.PDF -> {
                val bmp = remember(r.text) { PdfPages.firstPage(r.text) }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Prima pagina del PDF",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onOpen),
                    )
                } else {
                    Text("PDF non leggibile", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                GradientButton("Apri PDF", Icons.Default.Description, onOpen)
            }
            ViewKind.HTML -> {
                Text(
                    "Infografica pronta: aprila a schermo intero, ingrandiscila o salvala come immagine.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                GradientButton("Apri infografica", Icons.Default.Dashboard, onOpen)
            }
            ViewKind.MAP -> {
                GradientButton("Apri mappa concettuale", Icons.Default.AccountTree, onOpen)
                Spacer(Modifier.height(12.dp))
                SelectionContainer { MarkdownText(r.text) }
            }
            null -> SelectionContainer { MarkdownText(r.text) }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (view == ViewKind.IMAGE || view == ViewKind.PDF) {
                IconButton(onClick = {
                    ClaudeShare.shareFile(context, File(r.text), if (view == ViewKind.PDF) "application/pdf" else "image/*", note.title)
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Condividi", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                IconButton(onClick = { ClaudeShare.copy(context, note.title, r.text) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copia", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { ClaudeShare.shareText(context, note.title, r.text) }) {
                    Icon(Icons.Default.Share, contentDescription = "Condividi (Notion, Docs, email…)", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (view != ViewKind.IMAGE && view != ViewKind.PDF) Box {
                var typeMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { typeMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Mostra come…", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    DropdownMenuItem(text = { Text("Mostra come mappa concettuale") }, onClick = {
                        typeMenu = false
                        if (MindMap.looksLikeMap(r.text)) {
                            scope.launch { context.app.db.notes().setResponseKind(r.id, PromptKind.MINDMAP.key) }
                        } else {
                            Toast.makeText(context, "Non è un elenco a livelli: chiedi di nuovo la Mappa a Claude", Toast.LENGTH_LONG).show()
                        }
                    })
                    DropdownMenuItem(text = { Text("Mostra come infografica") }, onClick = {
                        typeMenu = false
                        val html = ResponseCleaner.extractHtml(r.text)
                        if (html != null) {
                            scope.launch { context.app.db.notes().setResponseKind(r.id, PromptKind.INFOGRAPHIC.key) }
                        } else {
                            Toast.makeText(context, "In questa risposta non c'è il codice di un'infografica", Toast.LENGTH_LONG).show()
                        }
                    })
                    DropdownMenuItem(text = { Text("Mostra come testo") }, onClick = {
                        typeMenu = false
                        scope.launch { context.app.db.notes().setResponseKind(r.id, null) }
                    })
                }
            }
            IconButton(onClick = { scope.launch { NotesRepo.deleteResponse(context, r) } }) {
                Icon(Icons.Default.Delete, contentDescription = "Elimina", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Anteprima ridotta di un'immagine salvata (circa 1080 px di larghezza). */
private fun decodeThumb(path: String): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 1080) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
} catch (e: Throwable) {
    null
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
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { player.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (player.playing) "Pausa" else "Ascolta",
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.width(8.dp))
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
            "${Transcript.time(player.position.toLong())} / ${Transcript.time(player.duration.toLong())}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
