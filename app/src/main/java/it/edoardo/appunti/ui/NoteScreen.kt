package it.edoardo.appunti.ui

import android.content.ClipboardManager
import android.graphics.Bitmap
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
import it.edoardo.appunti.data.Transcript
import it.edoardo.appunti.share.ClaudeShare
import it.edoardo.appunti.whisper.Models
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Azioni della barra Claude, nell'ordine in cui compaiono. */
private val BAR_ACTIONS = listOf(
    PromptKind.SUMMARY, PromptKind.ACTIONS, PromptKind.MINDMAP, PromptKind.INFOGRAPHIC, PromptKind.TRANSLATE, PromptKind.EMAIL,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(id: Long, onBack: () -> Unit, onSettings: () -> Unit, onOpenResponse: (Long) -> Unit) {
    val context = LocalContext.current
    val dao = context.app.db.notes()
    val scope = rememberCoroutineScope()

    val note by remember(id) { dao.observe(id) }.collectAsStateWithLifecycle(initialValue = null)
    val responses by remember(id) { dao.observeResponses(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val n = note ?: run {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val paragraphs = remember(n.segments) { Transcript.paragraphs(Transcript.parse(n.segments)) }
    val hasText = paragraphs.isNotEmpty()

    // 0 = Riepilogo AI, 1 = Trascrizione. Si apre sulla trascrizione finché non ci sono risposte.
    var tab by rememberSaveable { mutableIntStateOf(-1) }
    val selectedTab = if (tab >= 0) tab else if (responses.isEmpty()) 1 else 0
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
        tab = 0 // al ritorno si è già sulla scheda dove incollare la risposta
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
                        onSave = {
                            scope.launch { NotesRepo.saveResponse(context, n.id, text, prefs.lastSentKind) }
                            clip = null
                            tab = 0
                        },
                        onIgnore = {
                            prefs.handledClipHash = text.hashCode()
                            clip = null
                        },
                    )
                }
            }
            item {
                SegmentedTabs(
                    labels = listOf("Riepilogo AI", "Trascrizione"),
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
                        TextButton(onClick = { pasteResponse(context) { text ->
                            val kind = if (prefs.recentSentNoteId() == n.id) prefs.lastSentKind else null
                            scope.launch { NotesRepo.saveResponse(context, n.id, text, kind) }
                        } }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Incolla risposta")
                        }
                    }
                }
                if (responses.isEmpty()) item { EmptySummary(hasText) { send(PromptKind.SUMMARY) } }
                items(responses, key = { "r${it.id}" }) { r -> ResponseCard(n, r, onOpen = { onOpenResponse(r.id) }) }
            } else {
                if (!hasText) item {
                    Text(
                        if (n.status == Status.RUNNING || n.status == Status.QUEUED) "Il testo comparirà qui man mano che la trascrizione procede."
                        else "Nessuna trascrizione.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                items(paragraphs.size) { i ->
                    val p = paragraphs[i]
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

private fun readClipboard(context: Context): String? =
    try {
        context.getSystemService<ClipboardManager>()?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    } catch (e: Exception) {
        null
    }

@Composable
private fun ClipboardProposal(raw: String, kind: PromptKind?, onSave: () -> Unit, onIgnore: () -> Unit) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientButton(
                when {
                    cleaned.isHtml -> "Salva infografica"
                    kind != null && kind != PromptKind.ASK -> "Salva come ${kind.label}"
                    else -> "Salva nella nota"
                },
                Icons.Default.ContentPaste, onSave,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onIgnore) { Text("Ignora") }
        }
    }
}

private fun pasteResponse(context: Context, onText: (String) -> Unit) {
    val text = readClipboard(context)
    if (text.isNullOrBlank()) {
        Toast.makeText(context, "Gli appunti sono vuoti: in Claude copia prima la risposta", Toast.LENGTH_LONG).show()
    } else {
        onText(text)
        Toast.makeText(context, "Risposta salvata", Toast.LENGTH_SHORT).show()
    }
}

/** Barra fissa in basso: lettore audio, funzioni Claude a un tocco e domanda libera. */
@Composable
private fun ClaudeBar(
    enabled: Boolean,
    player: Player?,
    onAction: (PromptKind) -> Unit,
    onAsk: (question: String) -> Unit,
) {
    val context = LocalContext.current
    var question by rememberSaveable { mutableStateOf("") }
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
                    ActionTile(kind.icon(), kind.label, enabled) { onAction(kind) }
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
                    "Poi copia la risposta, torna qui e tocca \"Incolla risposta\"."
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
                (if (view == ViewKind.IMAGE) "Immagine · " else "Claude · ") + Transcript.date(r.createdAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            PromptKind.fromKey(r.kind)?.let { StatusChip(it.label, MaterialTheme.colorScheme.primary) }
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
            if (view == ViewKind.IMAGE) {
                IconButton(onClick = { ClaudeShare.shareFile(context, File(r.text), "image/*", note.title) }) {
                    Icon(Icons.Default.Share, contentDescription = "Condividi immagine", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                IconButton(onClick = { ClaudeShare.copy(context, note.title, r.text) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copia", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { ClaudeShare.shareText(context, note.title, r.text) }) {
                    Icon(Icons.Default.Share, contentDescription = "Condividi (Notion, Docs, email…)", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
