package it.edoardo.appunti.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import it.edoardo.appunti.app
import it.edoardo.appunti.data.ClaudeResponse
import it.edoardo.appunti.data.KIND_IMAGE
import it.edoardo.appunti.data.KIND_PDF
import it.edoardo.appunti.data.Note
import it.edoardo.appunti.data.PromptKind
import it.edoardo.appunti.data.TaskItem
import it.edoardo.appunti.data.Tasks
import it.edoardo.appunti.data.Template
import it.edoardo.appunti.data.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Formati di esportazione di una nota. */
enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    PDF("PDF", "pdf", "application/pdf"),
    DOCX("Word (.docx)", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    TXT("Testo (.txt)", "txt", "text/plain"),
    MD("Markdown (.md)", "md", "text/markdown"),
}

/** Cosa includere nell'esportazione. */
data class ExportOptions(
    val responses: Boolean = true,
    val tasks: Boolean = true,
    val transcript: Boolean = true,
    val images: Boolean = true,
)

/**
 * Esporta una nota (risposte di Claude, attività, trascrizione e immagini) in PDF, Word, TXT o Markdown.
 * Tutto avviene sul telefono, senza servizi esterni.
 */
object Exporter {

    /** Blocco di testo da impaginare. */
    private sealed class Block {
        data class Heading(val text: String, val level: Int) : Block()
        data class Para(val text: String, val bullet: String? = null, val indent: Int = 0, val small: Boolean = false) : Block()
        data class Image(val path: String) : Block()
        object Rule : Block()
    }

    suspend fun export(context: Context, note: Note, format: ExportFormat, options: ExportOptions = ExportOptions()): File =
        withContext(Dispatchers.IO) {
            val dao = context.app.db.notes()
            val responses = if (options.responses || options.images) dao.responsesOf(note.id) else emptyList()
            val tasks = if (options.tasks) dao.tasksOf(note.id) else emptyList()
            val blocks = blocks(context, note, responses, tasks, options)
            val safe = note.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60).ifBlank { "nota" }
            val file = File(ClaudeShare.shareDir(context), "$safe.${format.ext}")
            when (format) {
                ExportFormat.PDF -> writePdf(file, blocks)
                ExportFormat.DOCX -> writeDocx(file, blocks)
                ExportFormat.TXT -> file.writeText(plain(blocks))
                ExportFormat.MD -> file.writeText(markdown(blocks))
            }
            file
        }

    private fun responseLabel(context: Context, r: ClaudeResponse): String =
        PromptKind.fromKey(r.kind)?.label
            ?: r.kind?.takeIf { it.startsWith(Template.TEMPLATE_PREFIX) }?.removePrefix(Template.TEMPLATE_PREFIX)
            ?: "Risposta di Claude"

    private fun blocks(
        context: Context,
        note: Note,
        responses: List<ClaudeResponse>,
        tasks: List<TaskItem>,
        options: ExportOptions,
    ): List<Block> {
        val out = mutableListOf<Block>()
        out += Block.Heading(note.title, 0)
        out += Block.Para(
            buildList {
                add(Transcript.date(note.createdAt))
                if (note.durationMs > 0) add("durata " + Transcript.time(note.durationMs))
            }.joinToString(" · "),
            small = true,
        )
        if (options.responses) {
            for (r in responses) {
                if (r.kind == KIND_IMAGE || r.kind == KIND_PDF || r.kind == PromptKind.INFOGRAPHIC.key) continue
                // Le attività hanno la loro sezione, aggiornata con le spunte.
                if (r.kind == PromptKind.ACTIONS.key && options.tasks && tasks.isNotEmpty()) continue
                out += Block.Rule
                out += Block.Heading(responseLabel(context, r), 1)
                out += markdownBlocks(r.text)
            }
        }
        if (options.tasks && tasks.isNotEmpty()) {
            out += Block.Rule
            out += Block.Heading("Attività", 1)
            for (t in tasks) {
                val details = listOfNotNull(t.owner, t.dueAt?.let { "entro " + Tasks.formatDate(it) })
                val text = t.text + if (details.isEmpty()) "" else " (" + details.joinToString(", ") + ")"
                out += Block.Para(text, bullet = if (t.done) "☑" else "☐")
            }
        }
        if (options.images) {
            val images = responses.filter { it.kind == KIND_IMAGE && File(it.text).exists() }
            if (images.isNotEmpty()) {
                out += Block.Rule
                out += Block.Heading("Mappe e immagini", 1)
                images.forEach { out += Block.Image(it.text) }
            }
        }
        if (options.transcript) {
            val paragraphs = Transcript.paragraphs(Transcript.parse(note.segments))
            if (paragraphs.isNotEmpty()) {
                out += Block.Rule
                out += Block.Heading("Trascrizione", 1)
                paragraphs.forEach { out += Block.Para("[${Transcript.time(it.startMs)}] ${it.text}") }
            }
        }
        return out
    }

    /** Markdown semplice (quello delle risposte di Claude) → blocchi. */
    private fun markdownBlocks(md: String): List<Block> {
        val check = Regex("^(\\s*)[-*•]\\s+\\[([ xX])]\\s+(.*)$")
        val bullet = Regex("^(\\s*)[-*•]\\s+(.*)$")
        val numbered = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
        val out = mutableListOf<Block>()
        for (raw in md.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val c = check.find(line)
            val b = bullet.find(line)
            val n = numbered.find(line)
            out += when {
                line.startsWith("### ") -> Block.Heading(strip(line.removePrefix("### ")), 3)
                line.startsWith("## ") -> Block.Heading(strip(line.removePrefix("## ")), 2)
                line.startsWith("# ") -> Block.Heading(strip(line.removePrefix("# ")), 2)
                line.trim() == "---" || line.trim() == "***" -> continue
                c != null -> Block.Para(strip(c.groupValues[3]), if (c.groupValues[2].isBlank()) "☐" else "☑", c.groupValues[1].length / 2)
                b != null -> Block.Para(strip(b.groupValues[2]), "•", b.groupValues[1].length / 2)
                n != null -> Block.Para(strip(n.groupValues[3]), "${n.groupValues[2]}.", n.groupValues[1].length / 2)
                line.startsWith("> ") -> Block.Para(strip(line.removePrefix("> ")), indent = 1)
                else -> Block.Para(strip(line))
            }
        }
        return out
    }

    private fun strip(s: String) = s.replace("**", "").replace("__", "").replace("`", "")

    // ---------- Testo ----------

    private fun plain(blocks: List<Block>): String = buildString {
        for (b in blocks) when (b) {
            is Block.Heading -> {
                if (isNotEmpty()) append('\n')
                append(b.text).append('\n')
                if (b.level <= 1) append("=".repeat(b.text.length.coerceAtMost(60))).append('\n')
            }
            is Block.Para -> {
                append("  ".repeat(b.indent))
                b.bullet?.let { append(it).append(' ') }
                append(b.text).append('\n')
            }
            is Block.Image -> append("[immagine: ").append(File(b.path).name).append("]\n")
            Block.Rule -> append('\n')
        }
    }

    private fun markdown(blocks: List<Block>): String = buildString {
        for (b in blocks) when (b) {
            is Block.Heading -> append('\n').append("#".repeat(b.level + 1)).append(' ').append(b.text).append("\n\n")
            is Block.Para -> {
                append("  ".repeat(b.indent))
                when (b.bullet) {
                    null -> append(if (b.small) "_${b.text}_" else b.text).append("\n\n")
                    "☐" -> append("- [ ] ").append(b.text).append('\n')
                    "☑" -> append("- [x] ").append(b.text).append('\n')
                    "•" -> append("- ").append(b.text).append('\n')
                    else -> append(b.bullet).append(' ').append(b.text).append('\n')
                }
            }
            is Block.Image -> append("![](").append(File(b.path).name).append(")\n\n")
            Block.Rule -> append("\n---\n")
        }
    }.trim() + "\n"

    // ---------- PDF ----------

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 48

    private fun writePdf(file: File, blocks: List<Block>) {
        val doc = PdfDocument()
        val width = PAGE_W - 2 * MARGIN
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            y = MARGIN.toFloat()
        }

        fun paint(size: Float, bold: Boolean = false, color: Int = Color.rgb(28, 28, 36)) = TextPaint().apply {
            isAntiAlias = true
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

        /** Scrive un testo andando a capo e cambiando pagina quando serve. */
        fun drawText(text: String, p: TextPaint, left: Float, w: Int, spaceAfter: Float) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, p, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .build()
            for (i in 0 until layout.lineCount) {
                val lineH = (layout.getLineBottom(i) - layout.getLineTop(i)).toFloat()
                if (y + lineH > PAGE_H - MARGIN) newPage()
                val line = text.substring(layout.getLineStart(i), layout.getLineEnd(i)).trimEnd('\n')
                page!!.canvas.drawText(line, left, y + (layout.getLineBaseline(i) - layout.getLineTop(i)), p)
                y += lineH
            }
            y += spaceAfter
        }

        newPage()
        val body = paint(11f)
        val small = paint(9.5f, color = Color.rgb(110, 110, 125))
        val brand = Color.rgb(66, 85, 255)
        for (b in blocks) when (b) {
            is Block.Heading -> {
                val p = when (b.level) {
                    0 -> paint(20f, bold = true)
                    1 -> paint(15f, bold = true, color = brand)
                    2 -> paint(13f, bold = true)
                    else -> paint(12f, bold = true)
                }
                // Un titolo non resta da solo in fondo alla pagina.
                if (y + p.textSize * 3 > PAGE_H - MARGIN) newPage()
                y += if (b.level == 0) 0f else 6f
                drawText(b.text, p, MARGIN.toFloat(), width, 6f)
            }
            is Block.Para -> {
                val p = if (b.small) small else body
                val indent = 16f * b.indent
                if (b.bullet != null) {
                    val bulletW = 16f
                    if (y + p.textSize * 1.5f > PAGE_H - MARGIN) newPage()
                    page!!.canvas.drawText(b.bullet, MARGIN + indent, y + p.textSize, paint(p.textSize, bold = true, color = brand))
                    drawText(b.text, p, MARGIN + indent + bulletW, (width - indent - bulletW).toInt(), 4f)
                } else {
                    drawText(b.text, p, MARGIN + indent, (width - indent).toInt(), 7f)
                }
            }
            is Block.Image -> {
                val bmp = decode(b.path, 1400) ?: continue
                val scale = width.toFloat() / bmp.width
                var h = bmp.height * scale
                var w = width.toFloat()
                val maxH = (PAGE_H - 2 * MARGIN).toFloat()
                if (h > maxH) {
                    w *= maxH / h
                    h = maxH
                }
                if (y + h > PAGE_H - MARGIN) newPage()
                val dst = Rect(MARGIN, y.toInt(), (MARGIN + w).toInt(), (y + h).toInt())
                page!!.canvas.drawBitmap(bmp, null, dst, null)
                bmp.recycle()
                y += h + 12f
            }
            Block.Rule -> {
                if (y + 20 > PAGE_H - MARGIN) newPage()
                y += 8f
                page!!.canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, paint(1f, color = Color.rgb(220, 222, 235)))
                y += 12f
            }
        }
        page?.let { doc.finishPage(it) }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private fun decode(path: String, maxW: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Throwable) {
        null
    }

    // ---------- Word (.docx) ----------

    private fun writeDocx(file: File, blocks: List<Block>) {
        val body = StringBuilder()
        for (b in blocks) when (b) {
            is Block.Heading -> {
                val size = when (b.level) { 0 -> 36; 1 -> 30; 2 -> 26; else -> 24 }
                val color = if (b.level == 1) "<w:color w:val=\"4255FF\"/>" else ""
                body.append("<w:p><w:pPr><w:spacing w:before=\"240\" w:after=\"120\"/><w:keepNext/></w:pPr>")
                    .append("<w:r><w:rPr><w:b/>").append(color).append("<w:sz w:val=\"$size\"/></w:rPr>")
                    .append(run(b.text)).append("</w:r></w:p>")
            }
            is Block.Para -> {
                val left = 360 * b.indent + if (b.bullet != null) 360 else 0
                body.append("<w:p><w:pPr><w:spacing w:after=\"80\"/>")
                if (left > 0) body.append("<w:ind w:left=\"$left\"").append(if (b.bullet != null) " w:hanging=\"360\"" else "").append("/>")
                body.append("</w:pPr>")
                if (b.bullet != null) body.append("<w:r>").append(run(b.bullet + "\t")).append("</w:r>")
                body.append("<w:r>")
                if (b.small) body.append("<w:rPr><w:i/><w:color w:val=\"6E6E7D\"/><w:sz w:val=\"19\"/></w:rPr>")
                body.append(run(b.text)).append("</w:r></w:p>")
            }
            is Block.Image -> body.append("<w:p><w:r>").append(run("[immagine: ${File(b.path).name}]")).append("</w:r></w:p>")
            Block.Rule -> body.append(
                "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"DCDEEB\"/></w:pBdr></w:pPr></w:p>"
            )
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:lang w:val="it-IT"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="80" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style></w:styles>"""
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        val docRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rels)
            put("word/_rels/document.xml.rels", docRels)
            put("word/document.xml", document)
            put("word/styles.xml", styles)
        }
    }

    /** Testo di un "run" Word: tabulazioni e spazi conservati, caratteri speciali protetti. */
    private fun run(text: String): String {
        val sb = StringBuilder()
        text.split('\t').forEachIndexed { i, part ->
            if (i > 0) sb.append("<w:tab/>")
            if (part.isNotEmpty()) sb.append("<w:t xml:space=\"preserve\">").append(xml(part)).append("</w:t>")
        }
        return sb.toString()
    }

    private fun xml(s: String) = buildString {
        for (ch in s) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            // Caratteri di controllo non ammessi in XML.
            ch.code < 0x20 && ch != '\n' && ch != '\r' -> Unit
            else -> append(ch)
        }
    }
}
