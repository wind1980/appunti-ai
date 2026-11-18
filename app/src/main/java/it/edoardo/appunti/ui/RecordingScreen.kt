package it.edoardo.appunti.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.edoardo.appunti.audio.RecState
import it.edoardo.appunti.audio.RecorderService
import it.edoardo.appunti.data.Transcript
import kotlinx.coroutines.delay

/** Schermata a tutto schermo durante la registrazione: timer, livello del microfono, pausa e fine. */
@Composable
fun RecordingScreen(rec: RecState) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (rec !is RecState.Active) {
            Spacer(Modifier.weight(1f))
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Salvataggio della registrazione…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            return@Column
        }

        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(rec.paused, rec.resumedAt) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(200)
            }
        }
        val levels = remember { mutableStateListOf<Float>().apply { repeat(BARS) { add(0f) } } }
        LaunchedEffect(rec.level, rec.paused) {
            levels.removeAt(0)
            levels.add(if (rec.paused) 0f else rec.level)
        }

        Spacer(Modifier.height(24.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (rec.paused) cs.surfaceVariant else cs.errorContainer)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (rec.paused) cs.onSurfaceVariant else cs.error),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                if (rec.paused) "In pausa" else "Registrazione in corso",
                style = MaterialTheme.typography.labelLarge,
                color = if (rec.paused) cs.onSurfaceVariant else cs.onErrorContainer,
            )
        }

        Spacer(Modifier.weight(1f))
        Text(
            Transcript.time(rec.elapsed(now)),
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            color = if (rec.paused) cs.onSurfaceVariant else cs.onBackground,
        )
        Spacer(Modifier.height(32.dp))
        LevelBars(levels, Modifier.fillMaxWidth().height(96.dp), active = !rec.paused)
        Spacer(Modifier.height(24.dp))
        Text(
            if (rec.paused) "Il microfono è spento. Riprendi quando vuoi: l'audio continua nello stesso file."
            else "Puoi bloccare lo schermo o usare altre app: la registrazione continua.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundAction(
                icon = if (rec.paused) Icons.Default.Mic else Icons.Default.Pause,
                label = if (rec.paused) "Riprendi" else "Pausa",
                size = 72.dp,
                filled = false,
                onClick = { if (rec.paused) RecorderService.resume(context) else RecorderService.pause(context) },
            )
            RoundAction(
                icon = Icons.Default.Check,
                label = "Termina e trascrivi",
                size = 88.dp,
                filled = true,
                onClick = { RecorderService.stop(context) },
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

private const val BARS = 36

@Composable
private fun RoundAction(icon: ImageVector, label: String, size: Dp, filled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .then(
                    if (filled) Modifier.background(BrandGradient)
                    else Modifier.background(cs.surfaceContainerLowest).border(2.dp, cs.outlineVariant, CircleShape)
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (filled) Color.White else cs.primary, modifier = Modifier.size(size * 0.42f))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
    }
}
