package it.edoardo.appunti.ui

import kotlin.math.max

/**
 * Trasforma l'elenco annidato di Claude (# tema, - rami, sotto-punti rientrati) in una mappa
 * concettuale ad albero, disegnata come SVG dentro una pagina HTML: si apre nel visualizzatore,
 * si ingrandisce con due dita e si salva come immagine.
 */
object MindMap {

    private class Node(val text: String) {
        val children = mutableListOf<Node>()
        var depth = 0
        var lines: List<String> = emptyList()
        var w = 0f
        var h = 0f
        var x = 0f
        var y = 0f
        var color = ""
    }

    private val palette = listOf("#4255FF", "#8A5CFF", "#F2803A", "#16A36A", "#E5484D", "#0EA5E9", "#D946EF", "#C99A06")

    /** true se il testo ha la forma di una mappa: almeno 3 punti di elenco. */
    fun looksLikeMap(markdown: String): Boolean =
        markdown.lines().count { Regex("^\\s*([-*•]|\\d+[.)])\\s+").containsMatchIn(it) } >= 3

    private fun parse(markdown: String, fallbackTitle: String): Node {
        var root: Node? = null
        // Pila di (livello, nodo). Il tema centrale ha livello -1.
        val stack = ArrayDeque<Pair<Int, Node>>()
        var headingBase = 0
        val bullet = Regex("^(\\s*)(?:[-*•]|\\d+[.)])\\s+(.*)$")
        for (raw in markdown.lines()) {
            if (raw.isBlank()) continue
            val heading = Regex("^(#+)\\s+(.*)$").find(raw.trim())
            val level: Int
            val text: String
            if (heading != null) {
                val n = heading.groupValues[1].length
                text = clean(heading.groupValues[2])
                if (root == null || (n == 1 && root.children.isEmpty())) {
                    root = Node(text)
                    stack.clear()
                    stack.addLast(-1 to root)
                    continue
                }
                level = n - 2
                headingBase = level + 1
            } else {
                val b = bullet.find(raw) ?: continue
                val indent = b.groupValues[1].replace("\t", "  ").length
                level = headingBase + indent / 2
                text = clean(b.groupValues[2])
            }
            if (root == null) {
                root = Node(fallbackTitle)
                stack.addLast(-1 to root)
            }
            while (stack.size > 1 && stack.last().first >= level) stack.removeLast()
            val node = Node(text)
            stack.last().second.children += node
            stack.addLast(level to node)
        }
        return root ?: Node(fallbackTitle)
    }

    private fun clean(s: String) = s.replace("**", "").replace("`", "").trim().trimEnd(':')

    private fun wrap(text: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            line = if (line.isEmpty()) word else if (line.length + 1 + word.length <= max) "$line $word" else {
                out += line
                word
            }
        }
        if (line.isNotEmpty()) out += line
        return out.ifEmpty { listOf("") }
    }

    private fun fontSize(depth: Int) = when (depth) { 0 -> 20f; 1 -> 16f; else -> 14f }

    private fun prepare(n: Node, depth: Int, color: String) {
        n.depth = depth
        n.color = color
        val fs = fontSize(depth)
        n.lines = wrap(n.text, if (depth == 0) 18 else 26)
        n.w = n.lines.maxOf { it.length } * fs * 0.56f + 32f
        n.h = n.lines.size * fs * 1.3f + 18f
        n.children.forEachIndexed { i, c -> prepare(c, depth + 1, if (depth == 0) palette[i % palette.size] else color) }
    }

    fun html(markdown: String, title: String): String {
        val root = parse(markdown, title)
        prepare(root, 0, "#4255FF")

        // Larghezza di ogni colonna = nodo più largo a quella profondità.
        val colW = mutableMapOf<Int, Float>()
        fun widths(n: Node) {
            colW[n.depth] = max(colW[n.depth] ?: 0f, n.w)
            n.children.forEach(::widths)
        }
        widths(root)
        val maxDepth = colW.keys.max()
        val colX = FloatArray(maxDepth + 1)
        for (d in 1..maxDepth) colX[d] = colX[d - 1] + colW.getValue(d - 1) + 64f

        // Posizione verticale: le foglie una sotto l'altra, i genitori al centro dei figli.
        var cursor = 0f
        fun place(n: Node) {
            n.x = colX[n.depth] + 24f
            if (n.children.isEmpty()) {
                n.y = cursor + n.h / 2f
                cursor += n.h + 14f
            } else {
                n.children.forEach(::place)
                n.y = (n.children.first().y + n.children.last().y) / 2f
            }
        }
        place(root)
        val width = colX[maxDepth] + colW.getValue(maxDepth) + 48f
        val height = max(cursor, root.h) + 24f

        val svg = StringBuilder()
        fun links(n: Node) {
            for (c in n.children) {
                val x1 = n.x + n.w
                val x2 = c.x
                val mx = (x1 + x2) / 2f
                svg.append("""<path d="M$x1 ${n.y + 12} C$mx ${n.y + 12}, $mx ${c.y + 12}, $x2 ${c.y + 12}" stroke="${c.color}" stroke-width="2.2" fill="none" stroke-opacity="0.7"/>""")
                links(c)
            }
        }
        fun nodes(n: Node) {
            val fs = fontSize(n.depth)
            val top = n.y + 12 - n.h / 2f
            val (fill, stroke, ink, weight) = when (n.depth) {
                0 -> listOf("url(#g)", "none", "#FFFFFF", "700")
                1 -> listOf(n.color, "none", "#FFFFFF", "600")
                else -> listOf("#FFFFFF", n.color, "#1A1D29", "400")
            }
            svg.append("""<rect x="${n.x}" y="$top" width="${n.w}" height="${n.h}" rx="14" fill="$fill" stroke="$stroke" stroke-width="1.6"/>""")
            val lh = fs * 1.3f
            val firstBaseline = n.y + 12 - (n.lines.size - 1) * lh / 2f + fs * 0.35f
            svg.append("""<text x="${n.x + n.w / 2f}" font-size="$fs" font-weight="$weight" fill="$ink" text-anchor="middle">""")
            n.lines.forEachIndexed { i, l ->
                svg.append("""<tspan x="${n.x + n.w / 2f}" y="${firstBaseline + i * lh}">${esc(l)}</tspan>""")
            }
            svg.append("</text>")
            n.children.forEach(::nodes)
        }
        links(root)
        nodes(root)

        return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=${width.toInt()}, user-scalable=yes">
<style>html,body{margin:0;background:#F4F5FA;font-family:sans-serif}</style></head><body>
<svg xmlns="http://www.w3.org/2000/svg" width="${width.toInt()}" height="${height.toInt()}" viewBox="0 0 ${width.toInt()} ${height.toInt()}">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#4255FF"/><stop offset="1" stop-color="#8A5CFF"/></linearGradient></defs>
$svg</svg></body></html>"""
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
