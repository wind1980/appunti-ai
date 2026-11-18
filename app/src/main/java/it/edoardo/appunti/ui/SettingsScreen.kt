package it.edoardo.appunti.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.edoardo.appunti.app
import it.edoardo.appunti.data.PromptKind
import it.edoardo.appunti.whisper.ModelState
import it.edoardo.appunti.whisper.Models
import it.edoardo.appunti.whisper.WhisperNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val scope = rememberCoroutineScope()

    var activeModel by remember { mutableStateOf(prefs.modelPath) }
    var tick by remember { mutableIntStateOf(0) }
    // Aggiorna lo stato dei download ogni secondo.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            Toast.makeText(context, "Copia del modello in corso…", Toast.LENGTH_SHORT).show()
            try {
                val f = withContext(Dispatchers.IO) { Models.import(context, uri) }
                prefs.modelPath = f.absolutePath
                activeModel = f.absolutePath
                Toast.makeText(context, "Modello importato e attivato", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Importazione non riuscita: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Impostazioni") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
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
                Text("Modello di trascrizione", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Si scarica una volta (serve internet), poi la trascrizione funziona offline. " +
                        "Prova prima Small e misura quanto ci mette sul tuo telefono.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Models.catalog.forEach { info ->
                item(key = info.fileName) {
                    val state = remember(tick) { Models.state(context, info) }
                    val path = Models.file(context, info).absolutePath
                    val isActive = activeModel == path
                    Card(
                        colors = if (isActive) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        else CardDefaults.cardColors(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${info.label} · ${info.sizeMb} MB", fontWeight = FontWeight.SemiBold)
                                    Text(info.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            when (state) {
                                is ModelState.Downloading -> {
                                    Spacer(Modifier.height(8.dp))
                                    LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                                    TextButton(onClick = { Models.cancelDownload(context, info) }) { Text("Annulla download (${state.percent}%)") }
                                }
                                ModelState.Ready -> Row {
                                    if (isActive) {
                                        TextButton(onClick = {}, enabled = false) { Text("In uso") }
                                    } else {
                                        TextButton(onClick = { prefs.modelPath = path; activeModel = path }) { Text("Usa questo") }
                                        TextButton(onClick = { File(path).delete(); tick++ }) { Text("Elimina") }
                                    }
                                }
                                ModelState.Missing -> TextButton(onClick = { Models.download(context, info); tick++ }) { Text("Scarica") }
                                is ModelState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(state.reason, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { Models.download(context, info); tick++ }) { Text("Riprova") }
                                }
                            }
                        }
                    }
                }
            }
            item {
                val imported = remember(tick) { Models.imported(context) }
                Column {
                    imported.forEach { f ->
                        val isActive = activeModel == f.absolutePath
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${f.name} · ${f.length() / 1_048_576} MB", modifier = Modifier.weight(1f))
                            if (isActive) Text("In uso", color = MaterialTheme.colorScheme.primary)
                            else TextButton(onClick = { prefs.modelPath = f.absolutePath; activeModel = f.absolutePath }) { Text("Usa") }
                        }
                    }
                    OutlinedButton(onClick = { importModel.launch(arrayOf("*/*")) }) { Text("Importa un modello .bin…") }
                    Text(
                        "Per modelli ggml scaricati altrove, per esempio una versione ottimizzata per l'italiano.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item { HorizontalDivider() }
            item {
                var language by remember { mutableStateOf(prefs.language) }
                Text("Lingua dell'audio", style = MaterialTheme.typography.titleMedium)
                listOf("it" to "Italiano", "en" to "Inglese", "auto" to "Riconosci da sola").forEach { (code, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = language == code, onClick = {
                            language = code
                            prefs.language = code
                        }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = language == code, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
                    }
                }
            }
            item {
                var threads by remember { mutableIntStateOf(prefs.threads) }
                Text("Core del processore usati: $threads", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = threads.toFloat(),
                    onValueChange = { threads = it.toInt() },
                    onValueChangeFinished = { prefs.threads = threads },
                    valueRange = 1f..8f,
                    steps = 6,
                )
                Text(
                    "4 di solito è il valore più veloce. Più core non sempre vuol dire più velocità.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                var hint by remember { mutableStateOf(prefs.whisperHint) }
                Text("Nomi e termini ricorrenti", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = hint,
                    onValueChange = { hint = it; prefs.whisperHint = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Es.: Ilaria, Gaia, Home Assistant, preventivo") },
                )
                Text(
                    "Aiutano Whisper a scrivere bene nomi propri e parole tecniche.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            item { HorizontalDivider() }
            item {
                var asFile by remember { mutableStateOf(prefs.alwaysAsFile) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Manda sempre la trascrizione come file", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Altrimenti va nel messaggio, e diventa un file .txt solo se è lunga.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = asFile, onCheckedChange = { asFile = it; prefs.alwaysAsFile = it })
                }
            }
            item {
                Text("Prompt per Claude", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Il testo che accompagna la trascrizione per ogni pulsante.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            PromptKind.entries.forEach { kind ->
                item(key = "prompt-${kind.key}") {
                    var text by remember { mutableStateOf(prefs.prompt(kind)) }
                    Column {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it; prefs.setPrompt(kind, it) },
                            label = { Text(kind.label) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                        if (text != kind.default) TextButton(onClick = { text = kind.default; prefs.setPrompt(kind, null) }) {
                            Text("Ripristina il testo originale")
                        }
                    }
                }
            }

            item { HorizontalDivider() }
            item {
                val info = remember {
                    try {
                        WhisperNative.systemInfo()
                    } catch (e: Throwable) {
                        "Libreria nativa non caricata: ${e.message}"
                    }
                }
                Text("Informazioni tecniche", style = MaterialTheme.typography.titleSmall)
                Text("whisper.cpp 1.8.5 · $info", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
