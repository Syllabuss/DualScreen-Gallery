package it.ayngallery

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import kotlin.math.sin

/** Read-only examples: no files, players, audio sessions or library mutations. */
@Composable
internal fun PlaybackSettingsPreview(category: String, prefs: GalleryPreferences) {
    Crossfade(category, animationSpec = tween(if (prefs.reducedMotion) 0 else 220), label = "playback-example") { kind ->
        val colors = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize().background(colors.background).testTag("playback-example:$kind")) {
            if (kind == "MUSIC") {
                val phase = if (prefs.reducedMotion) 0f else {
                    val loop = rememberInfiniteTransition(label = "example-music")
                    val value by loop.animateFloat(0f, 6.283185f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "example-beat")
                    value
                }
                val accent = if (prefs.musicColorMode == "CUSTOM") Color(prefs.musicBarsColor.toColorInt()) else colors.primary
                val backdrop = if (prefs.musicColorMode == "CUSTOM") Color(prefs.musicBackgroundColor.toColorInt()) else colors.background
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(backdrop)
                    repeat(30) { index ->
                        val x = (index + .5f) * size.width / 30f
                        val height = size.height * (.18f + .4f * (sin(phase + index * .65f) + 1f) / 2f)
                        drawLine(accent.copy(alpha = .38f), Offset(x, size.height), Offset(x, size.height - height), size.width / 70f)
                    }
                }
                Row(Modifier.align(Alignment.Center).fillMaxWidth().padding(32.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    val disc = prefs.musicCoverEffect in setOf(1, 3)
                    Surface(Modifier.weight(1f).aspectRatio(1f).graphicsLayer {
                        if (disc) rotationZ = phase * 57.29578f
                        if (prefs.musicCoverEffect == 2) { scaleX = .88f + .16f * sin(phase); scaleY = scaleX }
                    }, shape = if (disc) CircleShape else GalleryShape, color = colors.surfaceVariant) {
                        Box(contentAlignment = Alignment.Center) {
                            ExampleLandscape(Modifier.fillMaxSize())
                            if (prefs.musicCoverEffect == 1) Surface(Modifier.align(Alignment.Center).size(18.dp), shape = CircleShape, color = backdrop) {}
                        }
                    }
                    Row(Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                        GalleryIcon(R.drawable.ph_skip_back, null)
                        Surface(shape = CircleShape, color = accent, contentColor = if (accent.luminance() > .45f) Color.Black else Color.White) {
                            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { GalleryIcon(R.drawable.ph_play, null, Modifier.size(30.dp)) }
                        }
                        GalleryIcon(R.drawable.ph_skip_forward, null)
                    }
                }
                Row(Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FavoriteHeart(false, ""); GalleryIcon(R.drawable.ph_dots_three, null)
                }
                Column(Modifier.align(Alignment.CenterEnd).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    GalleryIcon(R.drawable.ph_speaker_high, null)
                    Box(Modifier.padding(top = 8.dp).width(4.dp).height(80.dp).background(accent, CircleShape))
                }
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    if (kind == "VIDEO") drawRect(Color.Black) else drawImageBackdrop(prefs, colors.background)
                    val landscape = Path().apply {
                        moveTo(size.width * .08f, size.height * .8f)
                        lineTo(size.width * .36f, size.height * .32f)
                        lineTo(size.width * .55f, size.height * .61f)
                        lineTo(size.width * .72f, size.height * .4f)
                        lineTo(size.width * .92f, size.height * .8f); close()
                    }
                    drawPath(landscape, Brush.verticalGradient(listOf(colors.primary.copy(alpha = .65f), colors.secondary.copy(alpha = .3f))))
                    drawCircle(colors.secondary.copy(alpha = .6f), size.minDimension * .07f, Offset(size.width * .72f, size.height * .24f))
                }
                if (kind == "VIDEO") {
                    GalleryIcon(R.drawable.ph_play, null, Modifier.align(Alignment.Center).size(56.dp))
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        GalleryIcon(R.drawable.ph_speaker_high, null)
                        Text("0:42 / 3:00", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Box(Modifier.weight(1f).height(4.dp).background(colors.primary, CircleShape))
                        GalleryIcon(R.drawable.ph_closed_captioning, null); GalleryIcon(R.drawable.ph_translate, null)
                        GalleryIcon(R.drawable.ph_gear_six, null)
                    }
                }
                if (kind == "IMAGE") {
                    if (prefs.photoNavigator) {
                        Surface(Modifier.align(Alignment.BottomEnd).padding(12.dp).width(150.dp).height(92.dp).testTag("example-navigator"),
                            shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(2.dp, colors.primary)) {
                            Box { ExampleLandscape(Modifier.fillMaxSize())
                                Box(Modifier.align(Alignment.Center).size(48.dp).background(colors.primary.copy(alpha = .2f))) }
                        }
                    }
                    if (prefs.gifFrameCount) Surface(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = if (prefs.photoNavigator) 112.dp else 12.dp)
                        .testTag("example-frame-count"), shape = GalleryShape, color = colors.surface.copy(alpha = .9f)) {
                        Text(if (prefs.gifFrameCounter) "12 / 28" else "28", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
                    }
                    if (prefs.gifFrameSelector) Surface(Modifier.align(Alignment.BottomStart).padding(12.dp).testTag("example-filmstrip"), shape = GalleryShape) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            GalleryIcon(R.drawable.ph_pause, null)
                            repeat(3) { ExampleLandscape(Modifier.size(32.dp).clip(RoundedCornerShape(4.dp))) }
                            GalleryIcon(R.drawable.ph_caret_right, null)
                        }
                    }
                }
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FavoriteHeart(false, "")
                    if (kind == "IMAGE") GalleryIcon(R.drawable.ph_arrow_clockwise, null)
                    Spacer(Modifier.weight(1f))
                    if (kind == "VIDEO") GalleryIcon(R.drawable.ph_moon_stars, null)
                    if (kind == "IMAGE" && prefs.previewPinEnabled) GalleryIcon(R.drawable.ph_push_pin, null)
                    if (kind == "IMAGE" && prefs.comparisonEnabled) GalleryIcon(R.drawable.ph_browsers, null)
                    GalleryIcon(R.drawable.ph_dots_three, null)
                }
            }
            Surface(Modifier.align(Alignment.TopCenter).padding(top = 68.dp).testTag("playback-example-label"), shape = RoundedCornerShape(12.dp),
                color = colors.inverseSurface.copy(alpha = .85f), contentColor = colors.inverseOnSurface) {
                Text(localized(R.string.settings_preview_example, "Preview example"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun ExampleLandscape(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.background(colors.surfaceVariant)) {
        val mountain = Path().apply {
            moveTo(0f, size.height); lineTo(size.width * .35f, size.height * .3f)
            lineTo(size.width * .55f, size.height * .6f); lineTo(size.width * .75f, size.height * .4f)
            lineTo(size.width, size.height); close()
        }
        drawPath(mountain, Brush.verticalGradient(listOf(colors.primary, colors.secondary)))
        drawCircle(colors.secondary, size.minDimension * .1f, Offset(size.width * .74f, size.height * .2f))
    }
}
