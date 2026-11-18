package it.edoardo.appunti.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Segment(val startMs: Long, val endMs: Long, val text: String)

data class Paragraph(val startMs: Long, val text: String)

object Transcript {

    fun parse(raw: String): List<Segment> = raw.lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val t0 = parts[0].toLongOrNull() ?: return@mapNotNull null
            val t1 = parts[1].toLongOrNull() ?: t0
            val text = parts[2].trim()
            if (text.isEmpty()) null else Segment(t0, t1, text)
        }
        .toList()

    fun serialize(segments: List<Segment>): String = buildString {
        for (s in segments) {
            append(s.startMs).append('\t').append(s.endMs).append('\t')
            append(s.text.replace('\t', ' ').replace('\n', ' ')).append('\n')
        }
    }

    /**
     * Unisce i segmenti in paragrafi: si va a capo dopo una pausa lunga
     * o quando il paragrafo supera circa 500 caratteri.
     */
    fun paragraphs(segments: List<Segment>): List<Paragraph> {
        val out = mutableListOf<Paragraph>()
        var start = -1L
        var lastEnd = 0L
        val sb = StringBuilder()
        for (s in segments) {
            val pause = s.startMs - lastEnd
            if (sb.isNotEmpty() && (pause > 2_000 || sb.length > 500)) {
                out += Paragraph(start, sb.toString())
                sb.clear()
            }
            if (sb.isEmpty()) start = s.startMs else sb.append(' ')
            sb.append(s.text)
            lastEnd = s.endMs
        }
        if (sb.isNotEmpty()) out += Paragraph(start, sb.toString())
        return out
    }

    /** Testo leggibile con i minuti, da mandare a Claude o copiare. */
    fun readable(note: Note): String =
        paragraphs(parse(note.segments)).joinToString("\n\n") { "[${time(it.startMs)}] ${it.text}" }

    fun time(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    fun date(millis: Long): String =
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ITALIAN).format(Date(millis))
}
