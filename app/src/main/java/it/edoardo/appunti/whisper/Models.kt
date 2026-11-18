package it.edoardo.appunti.whisper

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.getSystemService
import it.edoardo.appunti.app
import java.io.File

data class ModelInfo(
    val fileName: String,
    val label: String,
    val sizeMb: Int,
    val description: String,
    val url: String,
    /** Sotto questa dimensione il file è considerato incompleto. */
    val minBytes: Long = sizeMb * 1024L * 1024L * 8 / 10,
)

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val percent: Int) : ModelState
    data object Ready : ModelState
    data class Failed(val reason: String) : ModelState
}

object Models {
    private const val HF = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    val catalog = listOf(
        ModelInfo(
            "ggml-base-q5_1.bin", "Base", 57,
            "Veloce, qualità discreta. Buono per provare.", HF + "ggml-base-q5_1.bin"
        ),
        ModelInfo(
            "ggml-small-q5_1.bin", "Small", 181,
            "Buon compromesso tra velocità e qualità su un telefono recente.", HF + "ggml-small-q5_1.bin"
        ),
        ModelInfo(
            "ggml-large-v3-turbo-q5_0.bin", "Large v3 Turbo", 547,
            "Qualità alta, lento sul telefono. Meglio per registrazioni brevi o con il telefono in carica.",
            HF + "ggml-large-v3-turbo-q5_0.bin"
        ),
        ModelInfo(
            "ggml-large-v3-turbo-it-q5_0.bin", "Large v3 Turbo – italiano", 547,
            "Versione addestrata sull'italiano (LocalAI-io). Stessa velocità del Turbo.",
            "https://huggingface.co/LocalAI-io/whisper-large-v3-turbo-it-ggml/resolve/main/ggml-model-q5_0.bin"
        ),
    )

    /** Rilevatore di voce Silero: serve per saltare silenzi e pause. */
    val vad = ModelInfo(
        "ggml-silero-v6.2.0.bin", "Rilevatore di voce", 1,
        "Fa saltare silenzi e pause: trascrizione più veloce.",
        "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin",
        minBytes = 100_000,
    )

    /** Percorso del rilevatore di voce se è già scaricato, altrimenti null. */
    fun vadPath(context: Context): String? =
        if (state(context, vad) is ModelState.Ready) file(context, vad).absolutePath else null

    /** Scarica il rilevatore di voce se manca (è piccolo, circa 1 MB). */
    fun ensureVad(context: Context) {
        if (state(context, vad) is ModelState.Missing) download(context, vad)
    }

    /** Nome leggibile di un modello a partire dal percorso del file. */
    fun labelFor(path: String?): String? {
        if (path == null) return null
        val name = File(path).name
        return catalog.firstOrNull { it.fileName == name }?.label ?: name.removeSuffix(".bin")
    }

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    fun file(context: Context, info: ModelInfo) = File(dir(context), info.fileName)

    /** Modelli importati a mano dall'utente (file .bin non presenti nel catalogo). */
    fun imported(context: Context): List<File> {
        val known = (catalog + vad).map { it.fileName }.toSet()
        return dir(context).listFiles { f -> f.isFile && f.name.endsWith(".bin") && f.name !in known }
            ?.sortedBy { it.name } ?: emptyList()
    }

    fun download(context: Context, info: ModelInfo) {
        val dm = context.getSystemService<DownloadManager>()!!
        file(context, info).delete()
        val req = DownloadManager.Request(Uri.parse(info.url))
            .setTitle("Modello Whisper ${info.label}")
            .setDescription("${info.sizeMb} MB")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, "models", info.fileName)
        context.app.prefs.setDownloadId(info.fileName, dm.enqueue(req))
    }

    fun cancelDownload(context: Context, info: ModelInfo) {
        val id = context.app.prefs.downloadId(info.fileName)
        if (id >= 0) context.getSystemService<DownloadManager>()!!.remove(id)
        context.app.prefs.setDownloadId(info.fileName, -1)
        file(context, info).delete()
    }

    fun state(context: Context, info: ModelInfo): ModelState {
        val id = context.app.prefs.downloadId(info.fileName)
        if (id >= 0) {
            val dm = context.getSystemService<DownloadManager>()!!
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            context.app.prefs.setDownloadId(info.fileName, -1)
                        }
                        DownloadManager.STATUS_FAILED -> {
                            context.app.prefs.setDownloadId(info.fileName, -1)
                            return ModelState.Failed("Download non riuscito")
                        }
                        else -> {
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            return ModelState.Downloading(pct)
                        }
                    }
                }
            }
        }
        val f = file(context, info)
        return if (f.exists() && f.length() >= info.minBytes) ModelState.Ready else ModelState.Missing
    }

    /** Copia un file .bin scelto dall'utente nella cartella dei modelli. Restituisce il file copiato. */
    fun import(context: Context, uri: Uri): File {
        var name = "modello-importato.bin"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        if (!name.endsWith(".bin")) name += ".bin"
        val dest = File(dir(context), name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        context.contentResolver.openInputStream(uri)!!.use { input ->
            dest.outputStream().use { input.copyTo(it, 1 shl 20) }
        }
        return dest
    }
}
