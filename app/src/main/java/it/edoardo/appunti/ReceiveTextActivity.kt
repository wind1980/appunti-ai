package it.edoardo.appunti

import android.content.Intent
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
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Nessun testo da salvare", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            AppuntiTheme {
                val scope = rememberCoroutineScope()
                var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
                var recent by remember { mutableStateOf<Note?>(null) }
                val prefs = app.prefs
                LaunchedEffect(Unit) {
                    notes = app.db.notes().recent()
                    recent = prefs.recentSentNoteId()?.let { app.db.notes().get(it) }
                }

                fun done(message: String) {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    finish()
                }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            title = { Text("Salva in MeNoter") },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        )
                    },
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Text(
                                text,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        recent?.let { target ->
                            item {
                                val kind = PromptKind.fromKey(prefs.lastSentKind)
                                AppCard(color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("Nota da cui hai scritto a Claude", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(target.title, style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(12.dp))
                                    GradientButton(
                                        text = if (kind != null && kind != PromptKind.ASK) "Salva come ${kind.label}" else "Salva qui",
                                        icon = Icons.Default.Check,
                                        onClick = {
                                            scope.launch {
                                                NotesRepo.saveResponse(applicationContext, target.id, text, prefs.lastSentKind)
                                                done("Salvato in \"${target.title}\"")
                                            }
                                        },
                                    )
                                }
                            }
                            item {
                                Text("Oppure scegli un'altra nota", style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                        item {
                            AppCard(onClick = {
                                scope.launch {
                                    NotesRepo.addTextNote(applicationContext, text)
                                    done("Salvato in una nuova nota")
                                }
                            }) {
                                Text("+ Nuova nota", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        items(notes.filter { it.id != recent?.id }, key = { it.id }) { note ->
                            AppCard(onClick = {
                                scope.launch {
                                    NotesRepo.saveResponse(applicationContext, note.id, text)
                                    done("Salvato in \"${note.title}\"")
                                }
                            }) {
                                Text(note.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Transcript.date(note.createdAt), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
