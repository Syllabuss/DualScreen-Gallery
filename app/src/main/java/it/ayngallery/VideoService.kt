package it.ayngallery

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider

/** Publish the existing video player without replacing the music controller's queue. */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoService : MediaSessionService() {
    private val app get() = application as GalleryApp
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).setNotificationId(1002)
            .setChannelId("video_playback").setChannelName(R.string.video_stick_category).build())
        app.video.notificationService = this
        updatePlayer(app.video.notificationPlayer())
        if (session == null) stopSelf()
    }

    internal fun updatePlayer(player: Player?) {
        if (player != null && session?.player === player) return
        session?.let { removeSession(it); it.release() }; session = null
        if (player == null) return
        val open = PendingIntent.getActivity(this, 12,
            Intent(this, LauncherActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setId("video").setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (controller.packageName != packageName && !controller.isTrusted)
                        return MediaSession.ConnectionResult.reject()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(player.availableCommands.buildUpon()
                            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM).build()).build()
                }
            }).build().also(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session.takeIf { controllerInfo.packageName == packageName || controllerInfo.isTrusted }

    override fun onDestroy() {
        if (app.video.notificationService === this) app.video.notificationService = null
        session?.let { removeSession(it); it.release() }; session = null
        // VideoPlayback owns the decoder and its gallery/PiP surface.
        super.onDestroy()
    }
}
