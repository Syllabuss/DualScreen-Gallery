package it.ayngallery

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

@Composable
internal fun FrameTransition(id: String, effect: String, content: @Composable (String) -> Unit) {
    AnimatedContent(id, transitionSpec = {
        when (effect) {
            "LEFT" -> slideInHorizontally(tween(420)) { it } togetherWith slideOutHorizontally(tween(420)) { -it }
            "RIGHT" -> slideInHorizontally(tween(420)) { -it } togetherWith slideOutHorizontally(tween(420)) { it }
            "UP" -> slideInVertically(tween(420)) { it } togetherWith slideOutVertically(tween(420)) { -it }
            "DOWN" -> slideInVertically(tween(420)) { -it } togetherWith slideOutVertically(tween(420)) { it }
            "NONE" -> fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            else -> fadeIn(tween(420)) togetherWith fadeOut(tween(420))
        }
    }, label = "frame-transition") { key ->
        val turn by transition.animateFloat(transitionSpec = { tween(420) }, label = "frame-flip") { state ->
            if (effect != "ROTATE") 0f else when (state) {
                EnterExitState.PreEnter -> 90f
                EnterExitState.Visible -> 0f
                EnterExitState.PostExit -> -90f
            }
        }
        androidx.compose.foundation.layout.Box(Modifier.graphicsLayer { rotationY = turn; cameraDistance = 16f * density }) { content(key) }
    }
}
