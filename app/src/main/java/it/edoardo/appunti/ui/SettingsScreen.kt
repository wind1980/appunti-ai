package it.edoardo.appunti.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Button
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
import it.edoardo.appunti.data.SummaryLevel
import it.edoardo.appunti.data.Template
import it.edoardo.appunti.whisper.ModelState
import it.edoardo.appunti.whisper.Models
import it.edoardo.appunti.whisper.WhisperNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import it.edoardo.appunti.audio.CallMonitor
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val scope = rememberCoroutineScope()

    var activeModel by remember { mutableStateOf(prefs.modelPath) }
    var preciseModel by remember { mutableStateOf(prefs.preciseModelPath) }
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
            item { CallSettings() }
            item { HorizontalDivider() }
            item {
                Text("Modelli di trascrizione", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Si scaricano una volta (serve internet), poi la trascrizione funziona offline. " +
                        "Il modello \"principale\" trascrive subito ogni registrazione: meglio uno veloce, come Small. " +
                        "Quello \"per Migliora\" rifà la trascrizione con più precisione quando lo chiedi dalla nota.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Models.catalog.forEach { info ->
                item(key = info.fileName) {
                    val state = remember(tick) { Models.state(context, info) }
                    val path = Models.file(context, info).absolutePath
                    val isActive = activeModel == path
                    val isPrecise = preciseModel == path
                    Card(
                        colors = if (isActive || isPrecise) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
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
                                ModelState.Ready -> FlowRow {
                                    if (isActive) {
                                        TextButton(onClick = {}, enabled = false) { Text("✓ Principale") }
                                    } else {
                                        TextButton(onClick = { prefs.modelPath = path; activeModel = path }) { Text("Usa come principale") }
                                    }
                                    if (isPrecise) {
                                        TextButton(onClick = { prefs.preciseModelPath = null; preciseModel = null }) { Text("✓ Per Migliora (togli)") }
                                    } else {
                                        TextButton(onClick = { prefs.preciseModelPath = path; preciseModel = path }) { Text("Usa per Migliora") }
                                    }
                                    if (!isActive && !isPrecise) {
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
                        val isPrecise = preciseModel == f.absolutePath
                        Text("${f.name} · ${f.length() / 1_048_576} MB")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isActive) Text("✓ Principale  ", color = MaterialTheme.colorScheme.primary)
                            else TextButton(onClick = { prefs.modelPath = f.absolutePath; activeModel = f.absolutePath }) { Text("Principale") }
                            if (isPrecise) Text("✓ Per Migliora", color = MaterialTheme.colorScheme.primary)
                            else TextButton(onClick = { prefs.preciseModelPath = f.absolutePath; preciseModel = f.absolutePath }) { Text("Per Migliora") }
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
                Text("Velocità", style = MaterialTheme.typography.titleMedium)
            }
            item {
                var skip by remember { mutableStateOf(prefs.skipSilence) }
                val vadState = remember(tick) { Models.state(context, Models.vad) }
                SwitchRow(
                    title = "Salta silenzi e pause",
                    subtitle = when (vadState) {
                        ModelState.Ready -> "Trascrive solo le parti parlate. Molto utile per riunioni e registrazioni con pause."
                        is ModelState.Downloading -> "Download del rilevatore di voce: ${vadState.percent}%"
                        else -> "Serve il rilevatore di voce (circa 1 MB): si scarica da solo, oppure tocca Scarica."
                    },
                    checked = skip,
                    onChange = {
                        skip = it
                        prefs.skipSilence = it
                        if (it) Models.ensureVad(context)
                    },
                )
                if (skip && (vadState is ModelState.Missing || vadState is ModelState.Failed)) {
                    TextButton(onClick = { Models.download(context, Models.vad); tick++ }) { Text("Scarica il rilevatore di voce") }
                }
            }
            item {
                var retry by remember { mutableStateOf(prefs.retryHardParts) }
                SwitchRow(
                    title = "Ritenta i pezzi difficili",
                    subtitle = "Se un pezzo è disturbato Whisper lo rifà più volte: un po' più preciso, ma anche molto più lento. Consigliato spento.",
                    checked = retry,
                    onChange = { retry = it; prefs.retryHardParts = it },
                )
            }
            item {
                var charging by remember { mutableStateOf(prefs.improveOnlyCharging) }
                SwitchRow(
                    title = "\"Migliora\" solo in carica",
                    subtitle = "Il miglioramento con il modello preciso parte quando metti il telefono in carica.",
                    checked = charging,
                    onChange = { charging = it; prefs.improveOnlyCharging = it },
                )
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
                SwitchRow(
                    title = "Manda sempre la trascrizione come file",
                    subtitle = "Altrimenti va nel messaggio, e diventa un file .txt solo se è lunga.",
                    checked = asFile,
                    onChange = { asFile = it; prefs.alwaysAsFile = it },
                )
            }
            item {
                var level by remember { mutableStateOf(prefs.summaryLevel) }
                Text("Livello del riassunto", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Quello del pulsante \"Riassunto\". Gli altri livelli sono sempre nel pulsante \"Modelli\" della nota.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SummaryLevel.entries.forEach { l ->
                        Row(
                            Modifier.selectable(selected = level == l, onClick = { level = l; prefs.summaryLevel = l }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = level == l, onClick = { level = l; prefs.summaryLevel = l })
                            Text(l.label)
                        }
                    }
                }
            }
            item { HorizontalDivider() }
            item { TemplatesEditor() }
            item { HorizontalDivider() }
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

/** Modelli personali: un nome e il comando da mandare a Claude con la trascrizione. */
@Composable
private fun TemplatesEditor() {
    val context = LocalContext.current
    val prefs = context.app.prefs
    var templates by remember { mutableStateOf(prefs.templates) }
    var editing by remember { mutableStateOf<Int?>(null) } // -1 = nuovo
    Column {
        Text("Modelli personali", style = MaterialTheme.typography.titleMedium)
        Text(
            "Comandi tuoi per Claude (es. \"Verbale\", \"Appunti di lezione\", \"Colloquio\"). " +
                "Li trovi nella nota, nel pulsante \"Modelli\".",
            style = MaterialTheme.typography.bodySmall,
        )
        templates.forEachIndexed { i, t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.name, fontWeight = FontWeight.SemiBold)
                    Text(t.prompt, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                TextButton(onClick = { editing = i }) { Text("Modifica") }
            }
        }
        if (templates.isEmpty()) TextButton(onClick = {
            templates = DEFAULT_TEMPLATES
            prefs.templates = templates
        }) { Text("Aggiungi i modelli di esempio") }
        OutlinedButton(onClick = { editing = -1 }) { Text("Nuovo modello") }
    }

    editing?.let { index ->
        val current = templates.getOrNull(index)
        var name by remember(index) { mutableStateOf(current?.name ?: "") }
        var prompt by remember(index) { mutableStateOf(current?.prompt ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (current == null) "Nuovo modello" else "Modifica modello") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, label = { Text("Nome") }, singleLine = true)
                    OutlinedTextField(
                        value = prompt, onValueChange = { prompt = it }, label = { Text("Cosa deve fare Claude") },
                        minLines = 5,
                    )
                    if (current != null) TextButton(onClick = {
                        templates = templates.filterIndexed { i, _ -> i != index }
                        prefs.templates = templates
                        editing = null
                    }) { Text("Elimina modello", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = Template(name.trim(), prompt.trim())
                    if (t.name.isNotEmpty() && t.prompt.isNotEmpty()) {
                        templates = if (current == null) templates + t
                        else templates.mapIndexed { i, old -> if (i == index) t else old }
                        prefs.templates = templates
                    }
                    editing = null
                }) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annulla") } },
        )
    }
}

private val DEFAULT_TEMPLATES = listOf(
    Template(
        "Verbale",
        "Scrivi il verbale formale della riunione nella trascrizione qui sotto (o nel file allegato): data, " +
            "partecipanti citati, ordine del giorno, discussione per punti, decisioni, azioni con responsabile e scadenza. " +
            "Italiano formale, con titoli ed elenchi.",
    ),
    Template(
        "Appunti di lezione",
        "Trasforma la trascrizione di questa lezione (qui sotto o nel file allegato) in appunti di studio: concetti chiave " +
            "con spiegazione breve, definizioni, esempi, formule o date importanti, e alla fine 5 domande di ripasso con risposta.",
    ),
    Template(
        "Punti chiave",
        "Elenca in 5-8 punti le informazioni più importanti della trascrizione qui sotto (o nel file allegato), " +
            "una riga per punto, ognuna con il minuto [mm:ss].",
    ),
)

/**
 * Registrazione delle chiamate (Teams, WhatsApp, Meet, Webex, telefono): servizio di accessibilità,
 * proposta di registrare a inizio chiamata e vivavoce automatico.
 */
@Composable
private fun CallSettings() {
    val context = LocalContext.current
    val prefs = context.app.prefs
    var a11y by remember { mutableStateOf(CallMonitor.accessibilityOn(context)) }
    // Si ricontrolla di continuo: l'utente lo attiva nelle impostazioni di Android e torna qui.
    LaunchedEffect(Unit) {
        while (true) {
            a11y = CallMonitor.accessibilityOn(context)
            delay(1500)
        }
    }
    var offer by remember { mutableStateOf(prefs.offerCallRecording) }
    var autoSpeaker by remember { mutableStateOf(prefs.autoSpeaker) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Registrare le chiamate", style = MaterialTheme.typography.titleMedium)
        Text(
            "Teams, WhatsApp, Meet, Webex, Zoom e telefono. Durante una chiamata Android lascia usare il microfono solo " +
                "all'app della chiamata e ai servizi di accessibilità: per questo MeNoter ne ha uno, che non legge e non " +
                "tocca nulla sullo schermo. La voce degli altri si registra solo quando esce dal vivavoce: con auricolare, " +
                "cuffie, auricolari Bluetooth o auto Android non la lascia prendere a nessuna app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (a11y) {
            Text("✓ Servizio di accessibilità attivo", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        } else {
            Button(onClick = { context.startActivity(CallMonitor.accessibilitySettings()) }) {
                Text("Attiva «MeNoter – registrazione delle chiamate»")
            }
            Text(
                "Se l'interruttore è grigio (Android 13 e successivi, app installata fuori dal Play Store): " +
                    "Impostazioni › App › MeNoter › menu ⋮ in alto › «Consenti impostazioni con limitazioni», poi riprova.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { context.startActivity(CallMonitor.appDetails(context)) }) { Text("Apri le informazioni di MeNoter") }
        }
        SwitchRow(
            title = "Proponi di registrare quando inizia una chiamata",
            subtitle = "Una notifica con il pulsante Registra (serve il servizio di accessibilità).",
            checked = offer,
            onChange = { offer = it; prefs.offerCallRecording = it },
        )
        SwitchRow(
            title = "Vivavoce automatico nelle chiamate registrate",
            subtitle = "Con auricolare, cuffie o Bluetooth passa da solo al vivavoce per registrare anche gli altri " +
                "(in auto l'audio esce dal telefono, non dalle casse). Se è spento arriva un avviso con il pulsante Vivavoce. " +
                "A fine chiamata si torna come prima.",
            checked = autoSpeaker,
            onChange = { autoSpeaker = it; prefs.autoSpeaker = it },
        )
    }
}
