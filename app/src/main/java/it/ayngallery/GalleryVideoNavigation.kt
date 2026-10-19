package it.ayngallery

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlin.math.abs

internal fun playbackVolumeIcon(volume: Float): Int = when {
    volume <= 0f -> R.drawable.ph_speaker_slash
    volume <= .5f -> R.drawable.ph_speaker_low
    else -> R.drawable.ph_speaker_high
}

/** Keep Media3 transport controls attached to the gallery's current media context. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class GalleryVideoNavigation(player: Player, private val navigate: (Int) -> Unit, private val seekFeedback: (Int) -> Unit = {}) : ForwardingPlayer(player) {
    var previousAvailable = false
    var nextAvailable = false
    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
        .add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_NEXT).build()
    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_SEEK_TO_PREVIOUS -> previousAvailable || currentPosition > 3000
        Player.COMMAND_SEEK_TO_NEXT -> nextAvailable
        else -> super.isCommandAvailable(command)
    }
    override fun seekToPrevious() {
        if (currentPosition > 3000) seekTo(0) else if (previousAvailable) navigate(-1)
    }
    override fun seekToNext() { if (nextAvailable) navigate(1) }
    override fun seekToPreviousMediaItem() { if (previousAvailable) navigate(-1) }
    override fun seekToNextMediaItem() { if (nextAvailable) navigate(1) }
    override fun seekBack() { seekFeedback(-(seekBackIncrement / 1000).toInt()); super.seekBack() }
    override fun seekForward() { seekFeedback((seekForwardIncrement / 1000).toInt()); super.seekForward() }
}

/** Horizontal swipes on the picture; leave the bottom transport/seek area to Media3. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class GalleryVideoView(context: Context, attrs: android.util.AttributeSet? = null) : PlayerView(context, attrs) {
    var navigate: (Int) -> Unit = {}
    var onPreviewTap: (() -> Unit)? = null
    var onDoubleTapSeek: ((Int) -> Unit)? = null
    private var seekingTap = false
    private val doubleTapDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val direction = videoDoubleTapDirection(e.x / width, e.y / height) ?: return false
            val seek = onDoubleTapSeek ?: return false
            seek(direction)
            seekingTap = true
            return true
        }
    })
    var onPip: (() -> Unit)? = null
        set(value) { field = value; pipButton?.visibility = if (value != null) android.view.View.VISIBLE else android.view.View.GONE }
    private var pipButton: android.widget.ImageButton? = null
    var onSubtitles: (() -> Unit)? = null
        set(value) { field = value; subtitleButton?.visibility = if (value != null) android.view.View.VISIBLE else android.view.View.GONE }
    private var subtitleButton: android.widget.ImageButton? = null
    var onAudio: (() -> Unit)? = null
        set(value) { field = value; audioButton?.visibility = if (value != null) android.view.View.VISIBLE else android.view.View.GONE }
    private var audioButton: android.widget.ImageButton? = null
    var onVolume: ((android.view.View) -> Unit)? = null
        set(value) { field = value; volumeButton?.visibility = if (value != null) android.view.View.VISIBLE else android.view.View.GONE }
    private var volumeButton: android.widget.ImageButton? = null
    private val volumeListener = object : Player.Listener {
        override fun onVolumeChanged(volume: Float) { volumeButton?.setImageResource(playbackVolumeIcon(volume)) }
    }
    override fun setPlayer(player: Player?) {
        getPlayer()?.removeListener(volumeListener)
        super.setPlayer(player)
        player?.addListener(volumeListener)
        volumeButton?.setImageResource(playbackVolumeIcon(player?.volume ?: 1f))
    }
    var onSettings: ((android.view.View) -> Unit)? = null
    override fun onFinishInflate() {
        super.onFinishInflate()
        val gear = findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings) ?: return
        val row = gear.parent as? android.widget.LinearLayout ?: return
        pipButton = android.widget.ImageButton(context, null, 0, androidx.media3.ui.R.style.ExoStyledControls_Button_Bottom).apply {
            setImageResource(R.drawable.ph_frame_corners)
            contentDescription = localized(R.string.video_pip, "Video in finestra")
            visibility = android.view.View.GONE
            setOnClickListener { onPip?.invoke() }
        }
        row.addView(pipButton, row.indexOfChild(gear), android.widget.LinearLayout.LayoutParams(gear.layoutParams))
        subtitleButton = android.widget.ImageButton(context, null, 0, androidx.media3.ui.R.style.ExoStyledControls_Button_Bottom).apply {
            setImageResource(R.drawable.ph_closed_captioning)
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            contentDescription = localized(R.string.video_subtitles, "Sottotitoli")
            visibility = android.view.View.GONE
            setOnClickListener { onSubtitles?.invoke() }
        }
        row.addView(subtitleButton, row.indexOfChild(gear), android.widget.LinearLayout.LayoutParams(gear.layoutParams))
        audioButton = android.widget.ImageButton(context, null, 0, androidx.media3.ui.R.style.ExoStyledControls_Button_Bottom).apply {
            setImageResource(R.drawable.ph_translate)
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            contentDescription = context.getString(androidx.media3.ui.R.string.exo_track_selection_title_audio)
            visibility = android.view.View.GONE
            setOnClickListener { onAudio?.invoke() }
        }
        row.addView(audioButton, row.indexOfChild(gear), android.widget.LinearLayout.LayoutParams(gear.layoutParams))
        val time = findViewById<android.widget.LinearLayout>(androidx.media3.ui.R.id.exo_time)
        volumeButton = android.widget.ImageButton(context, null, 0, androidx.media3.ui.R.style.ExoStyledControls_Button_Bottom).apply {
            setImageResource(playbackVolumeIcon(player?.volume ?: 1f))
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            contentDescription = localized(R.string.video_volume, "Volume")
            visibility = android.view.View.GONE
            setOnClickListener { onVolume?.invoke(it) }
        }
        time.addView(volumeButton, 0, android.widget.LinearLayout.LayoutParams(gear.layoutParams))
        gear.setOnClickListener { anchor -> onSettings?.invoke(anchor) }
    }
    private var startX = 0f
    private var startY = 0f
    private var eligible = false
    private var swiping = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Only the picture's central side bands participate; controls keep their own taps.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && videoDoubleTapDirection(event.x / width, event.y / height) == null) {
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            doubleTapDetector.onTouchEvent(cancel); cancel.recycle()
        } else doubleTapDetector.onTouchEvent(event)
        if (seekingTap) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) seekingTap = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                eligible = !useController || event.y < height * .7f; swiping = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> eligible = false
            MotionEvent.ACTION_MOVE -> if (eligible && !swiping && abs(event.x - startX) > slop * 2 &&
                abs(event.x - startX) > abs(event.y - startY) * 1.5f) {
                swiping = true
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel); cancel.recycle()
            }
            MotionEvent.ACTION_UP -> if (swiping) {
                if (abs(event.x - startX) > width * .12f) navigate(if (event.x < startX) 1 else -1)
                swiping = false; eligible = false
                return true
            } else if (!useController) {
                performClick()
                onPreviewTap?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { swiping = false; eligible = false }
        }
        return if (swiping || !useController) true else super.dispatchTouchEvent(event)
    }
}

internal fun videoDoubleTapDirection(x: Float, y: Float): Int? = when {
    y !in .25f.. .68f -> null
    x in .08f.. .35f -> -1
    x in .65f.. .92f -> 1
    else -> null
}
