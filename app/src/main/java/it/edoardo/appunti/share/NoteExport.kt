package it.edoardo.appunti.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import it.edoardo.appunti.data.ClaudeResponse
import it.edoardo.appunti.data.KIND_IMAGE
import it.edoardo.appunti.data.KIND_PDF
import it.edoardo.appunti.data.Note
import it.edoardo.appunti.data.PromptKind
import it.edoardo.appunti.data.Transcript
import it.edoardo.appunti.data.file
import it.edoardo.appunti.ui.MindMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Esporta una nota intera (trascrizione, riassunto, mappe, infografiche, altre risposte e,
 * a richiesta, l'audio) come file da salvare su OneDrive o in un'altra app.
 */
object NoteExport {

    const val ONEDRIVE = "com.microsoft.skydrive"

    fun oneDriveInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(ONEDRIVE, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun safe(name: String) =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "Nota" }

    private fun sectionName(r: ClaudeResponse): String = when {
        r.kind == KIND_IMAGE -> "Immagine"
        r.kind == KIND_PDF -> "PDF"
        r.kind == PromptKind.MINDMAP.key -> "Mappa concettuale"
        r.kind == PromptKind.INFOGRAPHIC.key -> "Infografica"
        else -> PromptKind.fromKey(r.kind)?.label ?: "Risposta di Claude"
    }

    /** Prepara i file della nota in una cartella temporanea e li restituisce. */
    suspend fun build(context: Context, note: Note, responses: List<ClaudeResponse>, includeAudio: Boolean): List<File> =
        withContext(Dispatchers.IO) {
            val root = File(context.cacheDir, "share").apply { mkdirs() }
            root.listFiles()?.filter { it.isDirectory && it.name.startsWith("export-") }?.forEach { it.deleteRecursively() }
            val dir = File(root, "export-${note.id}-${System.currentTimeMillis()}").apply { mkdirs() }
            val t = safe(note.title)
            val files = mutableListOf<File>()
            val used = mutableSetOf<String>()
            fun unique(base: String, ext: String): File {
                var name = "$t - $base.$ext"
                var i = 2
                while (!used.add(name.lowercase())) name = "$t - $base ($i).$ext".also { i++ }
                return File(dir, name)
            }

            // Pagina riepilogativa, da aprire con qualsiasi browser anche da OneDrive.
            val page = unique("Nota completa", "html")
            page.writeText(htmlPage(note, responses))
            files += page

            val transcript = Transcript.readable(note)
            if (note.segments.isNotBlank()) {
                files += unique("Trascrizione", "txt").apply { writeText(transcript) }
            }

            // Ordine: le tre sezioni, poi tutto il resto.
            val order = listOf(PromptKind.SUMMARY.key, PromptKind.MINDMAP.key, PromptKind.INFOGRAPHIC.key)
            val sorted = responses.sortedWith(compareBy({ order.indexOf(it.kind).let { i -> if (i < 0) 9 else i } }, { it.createdAt }))
            for (r in sorted) {
                val name = sectionName(r)
                r.file()?.let { path ->
                    val src = File(path)
                    if (src.exists()) {
                        val dest = unique(name, src.extension.ifBlank { "png" })
                        src.copyTo(dest, overwrite = true)
                        files += dest
                    }
                }
                val text = r.text.takeIf { r.filePath != null || (r.kind != KIND_IMAGE && r.kind != KIND_PDF) }
                if (!text.isNullOrBlank()) {
                    val html = if (r.kind == PromptKind.INFOGRAPHIC.key) ResponseCleaner.extractHtml(text) else null
                    files += if (html != null) unique(name, "html").apply { writeText(ResponseCleaner.asPage(html)) }
                    else unique(name, "md").apply { writeText(text) }
                }
            }

            if (includeAudio) note.audioPath?.let { File(it) }?.takeIf { it.exists() }?.let { src ->
                val dest = unique("Audio", src.extension.ifBlank { "wav" })
                // Collegamento invece di copia: l'audio può essere grande.
                try {
                    Files.createLink(dest.toPath(), src.toPath())
                } catch (_: Exception) {
                    src.copyTo(dest, overwrite = true)
                }
                files += dest
            }
            files
        }

    /** Un unico .zip con tutti i file, per le app che accettano un solo file. */
    suspend fun zip(context: Context, note: Note, files: List<File>): File = withContext(Dispatchers.IO) {
        val out = File(files.first().parentFile, "${safe(note.title)} - MeNoter.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            files.forEach { f ->
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        out
    }

    private fun uris(context: Context, files: List<File>): ArrayList<Uri> =
        ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })

    private fun multiple(context: Context, files: List<File>, subject: String): Intent {
        val list = uris(context, files)
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, list)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newUri(context.contentResolver, files.first().name, list.first()).apply {
                list.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun single(context: Context, file: File, subject: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Manda i file all'app OneDrive, che chiede in quale cartella caricarli.
     * Restituisce false se OneDrive non è installato.
     */
    suspend fun toOneDrive(context: Context, note: Note, files: List<File>, asZip: Boolean): Boolean {
        if (!oneDriveInstalled(context)) return false
        val pm = context.packageManager
        val intent = if (asZip) single(context, zip(context, note, files), note.title)
        else multiple(context, files, note.title)
        intent.setPackage(ONEDRIVE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(pm) != null) {
            context.startActivity(intent)
            return true
        }
        // Se OneDrive non accetta più file insieme, si manda un unico zip.
        val z = single(context, zip(context, note, files), note.title).apply {
            setPackage(ONEDRIVE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (z.resolveActivity(pm) == null) return false
        context.startActivity(z)
        return true
    }

    /** Condivisione con qualsiasi app (Drive, email, Files…). */
    suspend fun toAnyApp(context: Context, note: Note, files: List<File>, asZip: Boolean) {
        val intent = if (asZip) single(context, zip(context, note, files), note.title) else multiple(context, files, note.title)
        context.startActivity(Intent.createChooser(intent, "Salva la nota completa").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- Pagina HTML ----------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun inline(s: String): String =
        esc(s).replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>").replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)\\*"), "<i>$1</i>")
            .replace(Regex("`([^`]+)`"), "<code>$1</code>")

    /** Markdown semplice (titoli, elenchi, grassetto) in HTML. */
    fun markdownToHtml(md: String): String {
        val out = StringBuilder()
        var para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out.append("<p>").append(para.toString().trim()).append("</p>\n")
            para = StringBuilder()
        }
        for (raw in md.lines()) {
            val line = raw.trimEnd()
            val indent = line.length - line.trimStart().length
            val t = line.trim()
            when {
                t.isEmpty() -> flush()
                t.startsWith("#") -> {
                    flush()
                    val level = t.takeWhile { it == '#' }.length.coerceIn(1, 4)
                    out.append("<h${level + 2}>").append(inline(t.trimStart('#', ' '))).append("</h${level + 2}>\n")
                }
                t.matches(Regex("^([-*+]|\\d+[.)])\\s+.*")) -> {
                    flush()
                    val body = t.replaceFirst(Regex("^([-*+]|\\d+[.)])\\s+"), "")
                    val num = Regex("^(\\d+)[.)]").find(t)?.groupValues?.get(1)
                    out.append("<div class=\"li\" style=\"margin-left:${indent / 2 * 18 + 4}px\">")
                        .append(if (num != null) "$num. " else "• ").append(inline(body)).append("</div>\n")
                }
                t.matches(Regex("^-{3,}$")) -> { flush(); out.append("<hr>\n") }
                else -> para.append(inline(t)).append("<br>")
            }
        }
        flush()
        return out.toString()
    }

    private fun imageTag(path: String): String? = try {
        val f = File(path)
        if (!f.exists() || f.extension.lowercase() == "pdf") null else {
            val mime = when (f.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
            "<img src=\"data:$mime;base64,${Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)}\">"
        }
    } catch (_: Throwable) {
        null
    }

    private fun htmlPage(note: Note, responses: List<ClaudeResponse>): String {
        val sb = StringBuilder()
        sb.append(
            """<!doctype html><html lang="it"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(note.title)}</title>
<style>
body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;max-width:900px;margin:0 auto;padding:24px;color:#1b1b2f;line-height:1.5;background:#f6f7fb}
h1{margin:0 0 4px;font-size:28px} .meta{color:#666;margin-bottom:20px}
nav a{display:inline-block;margin:0 8px 8px 0;padding:6px 12px;border-radius:16px;background:#e7e9ff;color:#4255FF;text-decoration:none;font-size:14px}
section{background:#fff;border-radius:16px;padding:20px;margin:18px 0;box-shadow:0 1px 4px rgba(0,0,0,.06)}
section h2{margin:0 0 10px;color:#4255FF;font-size:20px} .when{color:#888;font-size:12px;margin-bottom:10px}
img{max-width:100%;border-radius:10px;display:block;margin:8px 0} svg{max-width:100%;height:auto}
.li{margin:2px 0} .tr p{margin:0 0 10px} .ts{color:#4255FF;font-size:12px;font-weight:600}
iframe{width:100%;height:600px;border:1px solid #ddd;border-radius:10px} code{background:#f0f0f5;padding:1px 4px;border-radius:4px}
.file{color:#666;font-style:italic}
</style></head><body>
"""
        )
        sb.append("<h1>${esc(note.title)}</h1><div class=\"meta\">")
            .append(esc(listOfNotNull(Transcript.date(note.createdAt), note.durationMs.takeIf { it > 0 }?.let { Transcript.time(it) }).joinToString(" · ")))
            .append(" · esportata da MeNoter</div>\n")

        val order = listOf(PromptKind.SUMMARY.key, PromptKind.MINDMAP.key, PromptKind.INFOGRAPHIC.key)
        val sorted = responses.sortedWith(compareBy({ order.indexOf(it.kind).let { i -> if (i < 0) 9 else i } }, { it.createdAt }))
        sb.append("<nav>")
        sorted.forEachIndexed { i, r -> sb.append("<a href=\"#r$i\">${esc(sectionName(r))}</a>") }
        if (note.segments.isNotBlank()) sb.append("<a href=\"#trascrizione\">Trascrizione</a>")
        sb.append("</nav>\n")

        sorted.forEachIndexed { i, r ->
            sb.append("<section id=\"r$i\"><h2>${esc(sectionName(r))}</h2><div class=\"when\">${esc(Transcript.date(r.createdAt))}</div>\n")
            val file = r.file()
            if (file != null) {
                sb.append(imageTag(file) ?: "<p class=\"file\">Allegato: ${esc(File(file).name)} (salvato insieme a questa pagina)</p>")
            }
            val text = r.text.takeIf { r.filePath != null || (r.kind != KIND_IMAGE && r.kind != KIND_PDF) }
            if (!text.isNullOrBlank()) {
                val html = if (r.kind == PromptKind.INFOGRAPHIC.key) ResponseCleaner.extractHtml(text) else null
                when {
                    html != null -> sb.append("<iframe srcdoc=\"${esc(ResponseCleaner.asPage(html))}\"></iframe>")
                    else -> {
                        // Mappa senza immagine: la si disegna dall'elenco.
                        if (file == null && r.kind == PromptKind.MINDMAP.key && MindMap.looksLikeMap(text)) {
                            val page = MindMap.html(MindMap.normalize(text), note.title)
                            val start = page.indexOf("<svg")
                            val end = page.lastIndexOf("</svg>")
                            if (start >= 0 && end > start) sb.append(page.substring(start, end + 6))
                        }
                        sb.append(markdownToHtml(text))
                    }
                }
            }
            sb.append("</section>\n")
        }

        if (note.segments.isNotBlank()) {
            sb.append("<section id=\"trascrizione\" class=\"tr\"><h2>Trascrizione</h2>\n")
            Transcript.paragraphs(Transcript.parse(note.segments)).forEach { p ->
                sb.append("<p><span class=\"ts\">${Transcript.time(p.startMs)}</span><br>${esc(p.text)}</p>\n")
            }
            sb.append("</section>\n")
        }
        sb.append("</body></html>")
        return sb.toString()
    }
}
