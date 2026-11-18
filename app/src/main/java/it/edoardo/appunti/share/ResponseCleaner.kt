package it.edoardo.appunti.share

/**
 * Ripulisce il testo copiato da Claude prima di salvarlo:
 * - toglie le frasi di apertura ("Ecco il riassunto:") e di chiusura ("Vuoi che…?");
 * - se dentro c'è una pagina HTML (infografica), tiene solo quella.
 * È prudente: toglie una riga solo se il resto della risposta ha comunque una struttura
 * (titoli, elenchi), così un testo semplice non viene mai svuotato.
 */
object ResponseCleaner {

    data class Result(val text: String, val isHtml: Boolean)

    private val htmlFence = Regex("```[ \\t]*(?:html|HTML|svg|xml)?[ \\t]*\\r?\\n([\\s\\S]*?)```")

    private val intro = listOf(
        "ecco", "certo", "perfetto", "ottimo", "ho preparato", "ho creato", "ho letto", "ho analizzato",
        "di seguito", "qui sotto", "qui di seguito", "va bene", "bene,", "sure", "here",
    )
    private val outro = listOf(
        "vuoi", "se vuoi", "se preferisci", "se desideri", "se ti serve", "se hai", "fammi sapere", "fammi",
        "posso ", "spero", "dimmi", "hai bisogno", "fatemi sapere", "let me know", "would you", "if you",
        "nota:", "n.b.", "buon lavoro",
    )

    fun clean(raw: String): Result {
        val t = raw.trim()
        extractHtml(t)?.let { return Result(it, true) }

        var lines = t.lines()

        // Frasi di apertura: al massimo due righe, solo se dopo c'è contenuto strutturato.
        repeat(2) {
            val i = lines.indexOfFirst { it.isNotBlank() }
            if (i < 0) return@repeat
            val l = lines[i].trim()
            val restStructured = lines.drop(i + 1).any { isStructural(it) }
            val looksIntro = l.endsWith(":") || intro.any { l.lowercase().startsWith(it) }
            if (!isStructural(l) && restStructured && looksIntro && l.length < 240) {
                lines = lines.drop(i + 1)
            }
        }

        // Frasi di chiusura: ultimi paragrafi che sono offerte, domande o commenti.
        repeat(3) {
            lines = trimEnd(lines)
            if (lines.isEmpty()) return@repeat
            val start = lines.indexOfLast { it.isBlank() } + 1
            val para = lines.subList(start, lines.size)
            val first = para.first().trim()
            val joined = para.joinToString(" ").trim()
            val before = lines.subList(0, start)
            val looksOutro = joined.endsWith("?") || outro.any { first.lowercase().startsWith(it) }
            if (!isStructural(first) && looksOutro && before.any { it.isNotBlank() }) {
                lines = before
            }
        }
        lines = trimEnd(lines)
        val out = lines.joinToString("\n").trim()
        return Result(out.ifEmpty { t }, false)
    }

    /** Tiene solo la pagina HTML (o l'SVG) se la risposta ne contiene una. */
    fun extractHtml(t: String): String? {
        htmlFence.findAll(t).map { it.groupValues[1].trim() }.firstOrNull { looksHtml(it) }?.let { return it }
        val lower = t.lowercase()
        val start = listOf(lower.indexOf("<!doctype html"), lower.indexOf("<html")).filter { it >= 0 }.minOrNull()
        val end = lower.lastIndexOf("</html>")
        if (start != null && end > start) return t.substring(start, end + "</html>".length)
        val s2 = lower.indexOf("<svg")
        val e2 = lower.lastIndexOf("</svg>")
        if (s2 >= 0 && e2 > s2) return t.substring(s2, e2 + "</svg>".length)
        return null
    }

    private fun looksHtml(s: String): Boolean {
        val l = s.lowercase()
        return l.contains("<html") || l.contains("<!doctype") || l.startsWith("<svg") || (l.contains("<body") && l.contains("</"))
    }

    private fun trimEnd(lines: List<String>): List<String> {
        var end = lines.size
        while (end > 0) {
            val l = lines[end - 1].trim()
            if (l.isEmpty() || l == "---" || l == "***" || l == "___") end-- else break
        }
        return lines.subList(0, end)
    }

    private fun isStructural(line: String): Boolean {
        val l = line.trim()
        if (l.isEmpty()) return false
        return l.startsWith("#") || l.startsWith("- ") || l.startsWith("* ") || l.startsWith("• ") ||
            l.startsWith("|") || l.startsWith(">") || l.startsWith("```") || l.startsWith("**") ||
            Regex("^\\d+[.)]\\s").containsMatchIn(l) || l.lowercase().startsWith("oggetto:")
    }
}
