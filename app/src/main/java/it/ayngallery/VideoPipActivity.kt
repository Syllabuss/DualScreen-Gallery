package it.ayngallery

import android.app.PictureInPictureParams
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.core.view.doOnLayout
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

/** A native PiP window borrows the existing player; gallery panels retain their ordinary roles. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPipActivity : ComponentActivity() {
    private val app get() = application as GalleryApp
    private var view: PlayerView? = null
    private val fullscreenRect = Rect()
    private var entered = false
    private var returning = false
    private var mediaId: String? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (app.video.floating && !isFinishing && (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                events.contains(Player.EVENT_VIDEO_SIZE_CHANGED) || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED))) setPictureInPictureParams(parameters())
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((!app.video.floating && !app.video.pipOpening) || app.video.player == null) { finish(); return }
        mediaId = app.video.mediaId
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        view = PlayerView(this).also {
            it.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            it.setBackgroundColor(android.graphics.Color.BLACK)
            it.useController = false
            it.setKeepContentOnPlayerReset(true)
            it.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            it.player = app.video.player
            app.video.attachPip(this)
            setContentView(it)
            it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (!entered && app.video.floating && !isFinishing) setPictureInPictureParams(parameters())
            }
        }
        app.video.player?.addListener(listener)
    }
    private fun parameters(): PictureInPictureParams {
        val player = app.video.player
        val videoSize = player?.videoSize
        val ratio = if (videoSize != null && videoSize.width > 0 && videoSize.height > 0)
            videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height else 16f / 9f
        val playing = player?.playWhenReady == true
        val title = if (playing) localized(R.string.text_pause_9bd4d1, "Pausa") else localized(R.string.text_play_da1576, "Riproduci")
        val action = RemoteAction(Icon.createWithResource(this, if (playing) R.drawable.ph_pause else R.drawable.ph_play), title, title,
            PendingIntent.getBroadcast(this, 0, Intent(this, VideoPipControlsReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        // PiP relayouts use a small window. Keep the fullscreen video bounds for the return animation.
        if (!entered) {
            val rect = Rect()
            val video = view?.videoSurfaceView
            if (video?.getGlobalVisibleRect(rect) != true || rect.isEmpty) view?.getGlobalVisibleRect(rect)
            if (!rect.isEmpty) fullscreenRect.set(rect)
        }
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio.coerceIn(.42f, 2.38f) * 1000).toInt(), 1000))
            .setActions(listOf(action)).apply {
                if (!fullscreenRect.isEmpty) setSourceRectHint(fullscreenRect)
                if (android.os.Build.VERSION.SDK_INT >= 31) setSeamlessResizeEnabled(true)
            }.build()
    }
    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) entered = true
        else if (entered) returnToPanels()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("return_to_gallery", false)) returnToPanels()
    }
    override fun onResume() {
        super.onResume()
        if (entered && !isInPictureInPictureMode) returnToPanels()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !entered) view?.doOnLayout { it.postOnAnimation {
            // Wait for a focused, drawn window before handing its surface to Android PiP.
            if (returning || isFinishing || entered || !hasWindowFocus() || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@postOnAnimation
            try { entered = enterPictureInPictureMode(parameters()) }
            catch (_: RuntimeException) { entered = false }
            if (!entered) {
                returning = true
                app.video.beginPipReturn()
                app.session.fail(localized(R.string.video_pip_unavailable, "La finestra video non è disponibile su questo dispositivo."))
                finish()
            }
        } }
    }
    internal fun returnToPanels() {
        if (returning || isFinishing) return
        returning = true
        app.video.beginPipReturn()
        if (app.session.previewActivity() == null && !app.session.resumeVideoPanels(this))
            startActivity(Intent(this, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
        finish()
    }
    internal fun detachPlayer() { view?.player?.removeListener(listener); view?.player = null }
    override fun onStop() {
        if (entered && !returning && app.playbackInteractive) app.video.dismissPip(this)
        super.onStop()
    }
    override fun onDestroy() {
        detachPlayer()
        if (!returning) app.video.dismissPip(this)
        app.video.pipClosed(this)
        super.onDestroy()
    }
}

class VideoPipControlsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val video = (context.applicationContext as GalleryApp).video
        if (video.floating) {
            if (video.player?.playbackState == Player.STATE_ENDED) video.player?.seekTo(0)
            video.toggle()
        }
    }
}
