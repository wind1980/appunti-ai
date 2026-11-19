package it.edoardo.appunti.ui

import android.app.DatePickerDialog
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import it.edoardo.appunti.app
import it.edoardo.appunti.data.Note
import it.edoardo.appunti.data.TaskItem
import it.edoardo.appunti.data.Tasks
import it.edoardo.appunti.data.Transcript
import it.edoardo.appunti.share.ClaudeShare
import it.edoardo.appunti.share.ExportFormat
import it.edoardo.appunti.share.ExportOptions
import it.edoardo.appunti.share.Exporter
import kotlinx.coroutines.launch
import java.util.Calendar

// ---------------- Attività ----------------

/** Riga di un'attività: casella, testo, responsabile, scadenza, promemoria. */
@Composable
fun TaskRow(task: TaskItem, noteTitle: String?, onEdit: () -> Unit, onOpenNote: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val overdue = !task.done && task.dueAt != null && task.dueAt < System.currentTimeMillis()
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = task.done,
            onCheckedChange = { checked -> scope.launch { context.app.db.notes().setTaskDone(task.id, checked) } },
        )
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onEdit)
                .padding(top = 12.dp, bottom = 6.dp, end = 4.dp),
        ) {
            Text(
                task.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (task.done) cs.onSurfaceVariant else cs.onSurface,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
            )
            val chips = buildList {
                task.owner?.let { add(it to cs.secondary) }
                task.dueAt?.let { add(Tasks.formatDate(it) to if (overdue) cs.error else cs.primary) }
            }
            if (chips.isNotEmpty() || (onOpenNote != null && noteTitle != null)) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    chips.forEach { (text, color) -> StatusChip(text, color) }
                }
                if (onOpenNote != null && noteTitle != null) {
                    Text(
                        noteTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.primary,
                        modifier = Modifier.padding(top = 4.dp).clickable(onClick = onOpenNote),
                        maxLines = 1,
                    )
                }
            }
        }
        if (!task.done) IconButton(onClick = { Tasks.addReminder(context, task, noteTitle) }) {
            Icon(Icons.Default.Event, contentDescription = "Promemoria nel calendario", tint = cs.onSurfaceVariant)
        }
    }
}

/** Sezione "Attività" in cima al Riepilogo AI. */
@Composable
fun TasksSection(note: Note, tasks: List<TaskItem>, onAdd: () -> Unit, onEdit: (TaskItem) -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Attività", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            val open = tasks.count { !it.done }
            if (tasks.isNotEmpty()) Text(
                if (open == 0) "tutte fatte" else "$open da fare",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Aggiungi attività") }
        }
        tasks.forEach { t -> TaskRow(t, note.title, onEdit = { onEdit(t) }) }
    }
}

/** Modifica (o creazione, se [task] è null) di un'attività. */
@Composable
fun TaskEditDialog(noteId: Long, task: TaskItem?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(task?.text ?: "") }
    var owner by remember { mutableStateOf(task?.owner ?: "") }
    var dueAt by remember { mutableStateOf(task?.dueAt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "Nuova attività" else "Modifica attività") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Cosa fare") }, minLines = 2)
                OutlinedTextField(value = owner, onValueChange = { owner = it }, label = { Text("Responsabile") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickDate(context, dueAt) { dueAt = it } }) {
                        Icon(Icons.Default.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(dueAt?.let { "Scadenza: " + Tasks.formatDate(it) } ?: "Aggiungi scadenza")
                    }
                    if (dueAt != null) IconButton(onClick = { dueAt = null }) {
                        Icon(Icons.Default.Clear, contentDescription = "Togli scadenza")
                    }
                }
                if (task != null) TextButton(onClick = {
                    scope.launch { context.app.db.notes().deleteTask(task) }
                    onDismiss()
                }) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Elimina attività", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isBlank()) {
                    onDismiss()
                    return@TextButton
                }
                scope.launch {
                    val dao = context.app.db.notes()
                    val o = owner.trim().ifBlank { null }
                    if (task == null) {
                        dao.insertTask(TaskItem(noteId = noteId, text = text.trim(), owner = o, dueAt = dueAt, createdAt = System.currentTimeMillis()))
                    } else {
                        dao.updateTask(task.copy(text = text.trim(), owner = o, dueAt = dueAt))
                    }
                }
                onDismiss()
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

private fun pickDate(context: Context, current: Long?, onPick: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { if (current != null) timeInMillis = current }
    DatePickerDialog(context, { _, y, m, d ->
        val c = Calendar.getInstance()
        c.set(y, m, d, 9, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        onPick(c.timeInMillis)
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
}

// ---------------- Esportazione ----------------

@Composable
fun ExportDialog(note: Note, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(ExportFormat.PDF) }
    var responses by remember { mutableStateOf(true) }
    var tasks by remember { mutableStateOf(true) }
    var transcript by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Esporta nota") },
        text = {
            Column {
                ExportFormat.entries.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { format = f },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = format == f, onClick = { format = f })
                        Text(f.label)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Includi", style = MaterialTheme.typography.titleSmall)
                CheckRow("Risposte di Claude e mappe", responses) { responses = it }
                CheckRow("Attività", tasks) { tasks = it }
                CheckRow("Trascrizione", transcript) { transcript = it }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Poi scegli dove mandarla: Drive, email, WhatsApp, Notion, OneNote, File…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val file = Exporter.export(
                            context, note, format,
                            ExportOptions(responses = responses, tasks = tasks, transcript = transcript, images = responses),
                        )
                        ClaudeShare.shareFile(context, file, format.mime, note.title)
                        onDismiss()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Esportazione non riuscita: ${e.message}", Toast.LENGTH_LONG).show()
                        busy = false
                    }
                }
            }) { Text(if (busy) "Preparo…" else "Esporta") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

// ---------------- Ricerca nella trascrizione ----------------

@Composable
fun TranscriptSearchField(query: String, matches: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        placeholder = { Text("Cerca nella trascrizione") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$matches", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, contentDescription = "Cancella ricerca") }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedBorderColor = Color.Transparent,
        ),
    )
}

/** Testo con le parole cercate evidenziate (senza distinguere maiuscole e minuscole). */
fun highlight(text: String, query: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val q = query.trim()
    if (q.isEmpty()) return@buildAnnotatedString
    var i = text.indexOf(q, ignoreCase = true)
    while (i >= 0) {
        addStyle(SpanStyle(background = color, fontWeight = FontWeight.SemiBold), i, i + q.length)
        i = text.indexOf(q, i + q.length, ignoreCase = true)
    }
}

// ---------------- Interlocutori ----------------

/** Intervento della trascrizione divisa per interlocutore: "[mm:ss] **Nome:** testo". */
data class Turn(val startMs: Long?, val speaker: String?, val text: String)

private val TURN = Regex("^\\s*(?:[-*•]\\s*)?(?:\\[(\\d{1,2}(?::\\d{2}){1,2})]\\s*)?(?:\\*\\*([^*:]{1,40}):?\\*\\*:?|([A-ZÀ-Ý][\\p{L} .'-]{0,30}):)\\s*(.+)$")

fun parseTurns(markdown: String): List<Turn> = markdown.lines().mapNotNull { line ->
    if (line.isBlank() || line.trim() == "(continua)") return@mapNotNull null
    val m = TURN.find(line)
    if (m == null) {
        Turn(null, null, line.trim().replace("**", ""))
    } else {
        val speaker = (m.groupValues[2].ifEmpty { m.groupValues[3] }).trim().trimEnd(':')
        Turn(parseTime(m.groupValues[1]), speaker, m.groupValues[4].trim())
    }
}

private fun parseTime(t: String): Long? {
    if (t.isEmpty()) return null
    val parts = t.split(':').mapNotNull { it.toLongOrNull() }
    if (parts.isEmpty()) return null
    return parts.fold(0L) { acc, v -> acc * 60 + v } * 1000
}

private val SPEAKER_COLORS = listOf(
    Color(0xFF4255FF), Color(0xFFE0592A), Color(0xFF16A36A), Color(0xFF8A5CFF),
    Color(0xFFC2185B), Color(0xFF00838F), Color(0xFF8D6E00), Color(0xFF5D4037),
)

/** Trascrizione divisa per interlocutore, con il nome colorato e il minuto che porta all'audio. */
@Composable
fun SpeakerTurn(turn: Turn, colorIndex: Int, query: String, onSeek: ((Long) -> Unit)?) {
    val color = SPEAKER_COLORS[colorIndex % SPEAKER_COLORS.size]
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            turn.speaker?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
            }
            turn.startMs?.let { ms ->
                Text(
                    Transcript.time(ms),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .let { m -> if (onSeek != null) m.clickable { onSeek(ms) } else m }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        SelectionContainer {
            Text(highlight(turn.text, query, MaterialTheme.colorScheme.tertiaryContainer), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
