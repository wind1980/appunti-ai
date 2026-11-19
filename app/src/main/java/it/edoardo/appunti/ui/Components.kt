package it.edoardo.appunti.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.edoardo.appunti.data.PromptKind

/** Scheda bianca arrotondata con ombra leggera, lo stile base di tutta l'app. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = color,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** Pulsante con la sfumatura del marchio. */
@Composable
fun GradientButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(BrandGradient)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Etichetta di stato colorata a forma di pillola. */
@Composable
fun StatusChip(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Icona quadrata arrotondata su fondo colorato, usata nelle liste. */
@Composable
fun IconBadge(icon: ImageVector, container: Color, tint: Color, size: Dp = 44.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Pulsante tondo con etichetta della barra delle funzioni Claude. */
@Composable
fun ActionTile(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

fun PromptKind.icon(): ImageVector = when (this) {
    PromptKind.SUMMARY -> Icons.Default.AutoAwesome
    PromptKind.ACTIONS -> Icons.Default.Checklist
    PromptKind.MINDMAP -> Icons.Default.AccountTree
    PromptKind.INFOGRAPHIC -> Icons.Default.Dashboard
    PromptKind.TRANSLATE -> Icons.Default.Translate
    PromptKind.EMAIL -> Icons.Default.Email
    PromptKind.SPEAKERS -> Icons.Default.RecordVoiceOver
    PromptKind.MULTI -> Icons.Default.QuestionAnswer
    PromptKind.ASK -> Icons.Default.QuestionAnswer
}

/** Barre verticali del livello del microfono (le più recenti a destra). */
@Composable
fun LevelBars(levels: List<Float>, modifier: Modifier = Modifier, active: Boolean = true) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val n = levels.size.coerceAtLeast(1)
        val gap = 4.dp.toPx()
        val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(2f)
        levels.forEachIndexed { i, raw ->
            val v = (raw * 4f).coerceIn(0.04f, 1f)
            val h = size.height * v
            val x = i * (barW + gap)
            drawRoundRect(
                color = if (active) on.copy(alpha = 0.35f + 0.65f * (i + 1f) / n) else off,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

/**
 * Mostra il Markdown delle risposte di Claude: titoli, elenchi puntati e numerati,
 * caselle da spuntare, separatori, **grassetto** e `codice`. Le tabelle restano testo.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val bullet = Regex("^(\\s*)[-*•]\\s+(.*)$")
    val check = Regex("^(\\s*)[-*•]\\s+\\[([ xX])]\\s+(.*)$")
    val numbered = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var lastBlank = false
        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                if (!lastBlank) Spacer(Modifier.height(2.dp))
                lastBlank = true
                continue
            }
            lastBlank = false
            val c = check.find(line)
            val b = bullet.find(line)
            val num = numbered.find(line)
            when {
                line.startsWith("### ") -> Text(inline(line.removePrefix("### ")), style = MaterialTheme.typography.titleSmall)
                line.startsWith("## ") -> Text(
                    inline(line.removePrefix("## ")),
                    style = MaterialTheme.typography.titleMedium,
                    color = primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                line.startsWith("# ") -> Text(inline(line.removePrefix("# ")), style = MaterialTheme.typography.titleLarge)
                line.trim() == "---" || line.trim() == "***" -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
                c != null -> Row(Modifier.padding(start = indent(c.groupValues[1]))) {
                    Text(if (c.groupValues[2].isBlank()) "☐" else "☑", color = primary)
                    Spacer(Modifier.width(8.dp))
                    Text(inline(c.groupValues[3]), style = MaterialTheme.typography.bodyMedium)
                }
                b != null -> Row(Modifier.padding(start = indent(b.groupValues[1]))) {
                    Text("•", color = primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(inline(b.groupValues[2]), style = MaterialTheme.typography.bodyMedium)
                }
                num != null -> Row(Modifier.padding(start = indent(num.groupValues[1]))) {
                    Text("${num.groupValues[2]}.", color = primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(inline(num.groupValues[3]), style = MaterialTheme.typography.bodyMedium)
                }
                line.startsWith("> ") -> Text(
                    inline(line.removePrefix("> ")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
                else -> Text(inline(line), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun indent(spaces: String): Dp = (spaces.length / 2 * 16).dp

/** Grassetto (**testo**) e codice (`testo`) dentro una riga. */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append("**"); i += 2
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append('`'); i += 1
                }
            }
            else -> {
                append(text[i]); i += 1
            }
        }
    }
}
