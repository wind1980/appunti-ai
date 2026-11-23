package it.edoardo.appunti.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.edoardo.appunti.app
import it.edoardo.appunti.data.TaskItem

/** Tutte le attività di tutte le note: prima quelle da fare (le scadute in cima), poi quelle fatte. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(onBack: () -> Unit, onOpenNote: (Long) -> Unit) {
    val context = LocalContext.current
    val dao = context.app.db.notes()
    val tasks by remember { dao.observeAllTasks() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val notes by remember { dao.observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val titles = remember(notes) { notes.associate { it.id to it.title } }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaskItem?>(null) }
    val open = tasks.filter { !it.done }
    val done = tasks.filter { it.done }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Attività") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (tasks.isEmpty()) item {
                Text(
                    "Nessuna attività. In una nota tocca \"Azioni\": Claude elenca le cose da fare e, quando salvi la risposta, " +
                        "diventano attività da spuntare, con responsabile, scadenza e promemoria nel calendario.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            if (open.isNotEmpty()) item {
                AppCard {
                    Text("Da fare (${open.size})", style = MaterialTheme.typography.titleSmall)
                    open.forEach { t ->
                        TaskRow(t, titles[t.noteId], onEdit = { editing = t }, onOpenNote = { onOpenNote(t.noteId) })
                    }
                }
            }
            if (done.isNotEmpty()) item {
                TextButton(onClick = { showDone = !showDone }) {
                    Text(if (showDone) "Nascondi le attività fatte" else "Mostra le attività fatte (${done.size})")
                }
            }
            if (showDone && done.isNotEmpty()) item {
                AppCard {
                    done.forEach { t ->
                        TaskRow(t, titles[t.noteId], onEdit = { editing = t }, onOpenNote = { onOpenNote(t.noteId) })
                    }
                }
            }
        }
    }

    editing?.let { t -> TaskEditDialog(t.noteId, t) { editing = null } }
}
