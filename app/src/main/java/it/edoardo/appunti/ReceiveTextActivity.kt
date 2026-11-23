package it.edoardo.appunti

import it.edoardo.appunti.ui.icon
import it.edoardo.appunti.data.Status
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.material3.Surface
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import android.graphics.BitmapFactory
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.edoardo.appunti.data.Note
import it.edoardo.appunti.data.NotesRepo
import it.edoardo.appunti.data.PromptKind
import it.edoardo.appunti.data.Transcript
import it.edoardo.appunti.ui.AppCard
import it.edoardo.appunti.ui.AppuntiTheme
import it.edoardo.appunti.ui.GradientButton
import kotlinx.coroutines.launch

/** Riceve un testo condiviso (di solito una risposta di Claude) e lo salva in una nota. */
class ReceiveTextActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val send = intent?.takeIf { it.action == Intent.ACTION_SEND }
        val image: Uri? = send?.takeIf { it.type?.startsWith("image/") == true || it.type == "application/pdf" }?.let { streamUri(it) }
        val text = send?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim() ?: ""
        if (image == null && text.isEmpty()) {
            Toast.makeText(this, "Niente da salvare", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        /** Salva il contenuto ricevuto (immagine o testo) nella nota indicata. */
        suspend fun saveTo(noteId: Long, kind: String?, explicit: Boolean = false) {
            if (image != null) NotesRepo.saveFile(applicationContext, noteId, image, kind)
            else NotesRepo.saveResponse(applicationContext, noteId, text, kind, explicit)
        }

        setContent {
            AppuntiTheme {
                val scope = rememberCoroutineScope()
                val prefs = app.prefs
                var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
                var recentId by remember { mutableStateOf<Long?>(null) }
                // Scelte dell'utente: nota (-1 = nuova) e sezione.
                var noteId by remember { mutableStateOf<Long?>(null) }
                val lastKind = PromptKind.fromKey(prefs.lastSentKind)
                var section by remember { mutableStateOf(lastKind?.takeIf { it in NotesRepo.SECTIONS }) }
                var otherChosen by remember { mutableStateOf(false) }
                var saving by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val list = app.db.notes().recent()
                    val r = prefs.recentSentNoteId()?.let { app.db.notes().get(it) }
                    notes = listOfNotNull(r) + list.filter { it.id != r?.id }
                    recentId = r?.id
                    noteId = r?.id
                    // Niente di inviato di recente e nessuna sezione nota: per un testo si propone "Altro".
                    val fromTemplate = prefs.template(prefs.lastSentKind) != null
                    if (section == null && (fromTemplate || (lastKind != null && lastKind != PromptKind.ASK)) && r != null) otherChosen = true
                }
                val thumb = remember(image) {
                    try {
                        image?.let { u ->
                            contentResolver.openInputStream(u)?.use {
                                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 })
                            }
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                val target = notes.firstOrNull { it.id == noteId }
                val sectionName = section?.let { sectionLabel(it) } ?: if (otherChosen) "Altro" else null
                val ready = noteId != null && sectionName != null && !saving

                fun save() {
                    val chosenNote = noteId ?: return
                    saving = true
                    // "Altro": mantiene il tipo della richiesta (Azioni, Email…) se non è una delle tre sezioni.
                    // Per i modelli personali si tiene il nome del modello.
                    val kind = section?.key ?: lastKind?.takeIf { it !in NotesRepo.SECTIONS && it != PromptKind.ASK }?.key
                        ?: prefs.template(prefs.lastSentKind)?.key
                    scope.launch {
                        val id = if (chosenNote == NEW_NOTE) {
                            val title = if (image != null) "Grafica " + Transcript.date(System.currentTimeMillis())
                            else text.lineSequence().map { it.trim().trimStart('#', '*', ' ') }.firstOrNull { it.isNotBlank() }?.take(60) ?: "Nota"
                            app.db.notes().insert(Note(title = title, createdAt = System.currentTimeMillis(), audioPath = null, status = Status.TEXT))
                        } else chosenNote
                        saveTo(id, kind, explicit = section != null || otherChosen)
                        val title = target?.title ?: "nuova nota"
                        Toast.makeText(this@ReceiveTextActivity, "Salvato in \"$title\" → $sectionName", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            title = { Text("Salva in MeNoter") },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        )
                    },
                    bottomBar = {
                        Surface(shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                                Text(
                                    when {
                                        sectionName == null -> "Scegli la sezione (passo 1)"
                                        noteId == null -> "Scegli la nota (passo 2)"
                                        else -> "Andrà in: ${if (noteId == NEW_NOTE) "nuova nota" else "\"${target?.title}\""} → $sectionName"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(8.dp))
                                GradientButton(
                                    text = if (saving) "Salvo…" else "Salva",
                                    icon = Icons.Default.Check,
                                    onClick = { save() },
                                    enabled = ready,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    },
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Che cosa si sta salvando
                        item {
                            AppCard {
                                Text(
                                    if (image != null) "Stai salvando un'immagine" + (if (thumb == null) " o un PDF" else "")
                                    else "Stai salvando un testo",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(6.dp))
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).clip(RoundedCornerShape(10.dp)),
                                    )
                                } else if (image == null) {
                                    Text(text, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                                        item { StepTitle("1", "In quale sezione") }
                        item {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NotesRepo.SECTIONS.forEach { k ->
                                    FilterChip(
                                        selected = section == k,
                                        onClick = { section = k; otherChosen = false },
                                        label = { Text(sectionLabel(k)) },
                                        leadingIcon = { Icon(k.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    )
                                }
                                FilterChip(
                                    selected = otherChosen,
                                    onClick = { section = null; otherChosen = true },
                                    label = { Text("Altro") },
                                )
                            }
                        }

                        item { StepTitle("2", "In quale nota") }
                        item {
                            NoteChoice(
                                title = "+ Nuova nota",
                                subtitle = null,
                                badge = null,
                                selected = noteId == NEW_NOTE,
                                onClick = { noteId = NEW_NOTE },
                            )
                        }
                        items(notes, key = { it.id }) { note ->
                            NoteChoice(
                                title = note.title,
                                subtitle = Transcript.date(note.createdAt),
                                badge = if (note.id == recentId) "Ultima inviata a Claude" else null,
                                selected = noteId == note.id,
                                onClick = { noteId = note.id },
                            )
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun streamUri(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)
}

private const val NEW_NOTE = -1L

private fun sectionLabel(kind: PromptKind) = when (kind) {
    PromptKind.SUMMARY -> "Riassunto"
    PromptKind.MINDMAP -> "Mappa"
    PromptKind.INFOGRAPHIC -> "Infografica"
    else -> kind.label
}

@Composable
private fun StepTitle(n: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(n, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun NoteChoice(title: String, subtitle: String?, badge: String?, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) cs.primaryContainer else cs.surfaceContainerLowest)
            .border(2.dp, if (selected) cs.primary else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            if (badge != null) Text(badge, style = MaterialTheme.typography.labelSmall, color = cs.primary)
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (selected) cs.onPrimaryContainer else cs.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}
