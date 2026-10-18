package it.ayngallery

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

internal object TvLiveSource {
    /** Metadata check only. No media segments, keys, decoder or hidden playback. */
    suspend fun verify(url: String) {
        val bytes = TvNetwork.bytes(url, 1024 * 1024)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require(text.startsWith("#EXTM3U") && (text.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF:") } ||
            text.lineSequence().any { it.startsWith("#EXT-X-TARGETDURATION:") })) { "Unsupported TV manifest" }
        require(!text.lineSequence().any { it.trim() == "#EXT-X-ENDLIST" || it.trim() == "#EXT-X-PLAYLIST-TYPE:VOD" }) { "Not a live source" }
    }
}

/** Also applied to MediaSession: a DVR-capable HLS window must still behave as linear TV. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class TvLiveCommands(player: Player) : ForwardingPlayer(player) {
    private val blocked = setOf(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
        Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_MEDIA_ITEM)
    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon().apply { blocked.forEach(::remove) }.build()
    override fun isCommandAvailable(command: Int) = command !in blocked && super.isCommandAvailable(command)
    override fun seekTo(positionMs: Long) {}
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {}
    override fun seekToDefaultPosition() {}
    override fun seekToDefaultPosition(mediaItemIndex: Int) {}
    override fun seekToNext() {}
    override fun seekToPrevious() {}
    override fun seekToNextMediaItem() {}
    override fun seekToPreviousMediaItem() {}
    override fun seekBack() {}
    override fun seekForward() {}
    override fun setPlaybackSpeed(speed: Float) {}
    override fun setPlaybackParameters(playbackParameters: androidx.media3.common.PlaybackParameters) {}
    override fun setTrackSelectionParameters(parameters: androidx.media3.common.TrackSelectionParameters) {}
}
