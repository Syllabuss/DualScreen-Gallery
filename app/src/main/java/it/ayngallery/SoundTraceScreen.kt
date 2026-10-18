package it.ayngallery

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
internal fun SoundTraceScreen(app: GalleryApp) {
    val preview by app.sounds.preview.collectAsState()
    val prefs by app.preferences.state.collectAsState()
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(preview?.cue, preview?.startedAt, prefs.reducedMotion) {
        progress = if (prefs.reducedMotion && preview != null) 1f else 0f
        val current = preview ?: return@LaunchedEffect
        if (current.startedAt == 0L || prefs.reducedMotion) return@LaunchedEffect
        while (progress < 1f) {
            withFrameNanos { progress = ((SystemClock.elapsedRealtime() - current.startedAt).toFloat() / current.duration).coerceIn(0f, 1f) }
        }
    }
    val color = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize().padding(32.dp).testTag("sound-trace"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(localized(R.string.sounds_title, "Suoni"), style = MaterialTheme.typography.titleLarge)
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val centre = size.height / 2
            drawLine(color.copy(alpha = .16f), Offset(0f, centre), Offset(size.width, centre), 1.dp.toPx())
            val samples = preview?.samples.orEmpty()
            val count = (samples.size * progress).toInt().coerceAtMost(samples.size)
            val peak = samples.maxOfOrNull { abs(it) }?.coerceAtLeast(.05f) ?: 1f
            if (count > 1) {
                val path = Path()
                for (i in 0 until count) {
                    val x = size.width * i / (samples.size - 1).coerceAtLeast(1)
                    val y = centre - samples[i] / peak * size.height * .32f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(2.dp.toPx()))
                val last = Offset(size.width * (count - 1) / (samples.size - 1).coerceAtLeast(1), centre - samples[count - 1] / peak * size.height * .32f)
                drawCircle(color.copy(alpha = .18f), 9.dp.toPx(), last)
                drawCircle(color, 3.dp.toPx(), last)
            }
        }
        Text(localized(R.string.sound_preview_hint, "Premi Play accanto a un suono per ascoltarlo e vedere il suo tracciato."),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
