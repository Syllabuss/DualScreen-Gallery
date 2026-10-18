package it.ayngallery

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class AudioRecipe(val duration: Long, val start: Long = 0, val end: Long = duration,
    val bitrate: Int = 192, val allowGame: Boolean = false, val format: String = "M4A", val sourceKind: String = "AUDIO") {
    init {
        require(duration in 250..(Long.MAX_VALUE / 1000 - 1000) && start in 0..duration && end in 250..duration && start <= end - 250) { localized(R.string.text_choose_a_segment_of_at_least_0_25_seconds_within_eb10b3, "Scegli un segmento di almeno 0,25 secondi entro la durata del brano") }
        require(bitrate in setOf(128, 192, 256)) { localized(R.string.text_unsupported_audio_quality_2af31b, "Qualità audio non supportata") }
        require(format in setOf("M4A", "MP3") && sourceKind in setOf("AUDIO", "VIDEO"))
    }
    val length get() = end - start
    val extension get() = if (format == "MP3") "mp3" else "m4a"
    val mime get() = if (format == "MP3") "audio/mpeg" else "audio/mp4"
    fun encode() = JSONObject().put("v", 1).put("type", "AUDIO").put("duration", duration).put("start", start).put("end", end)
        .put("bitrate", bitrate).put("allowGame", allowGame).put("format", format).put("sourceKind", sourceKind).toString()
    companion object {
        fun decode(raw: String): AudioRecipe {
            require(raw.length <= 4096)
            val root = JSONObject(raw); require(root.getInt("v") == 1 && root.getString("type") == "AUDIO") { localized(R.string.text_unsupported_audio_recipe_d81e86, "Ricetta audio non supportata") }
            return AudioRecipe(root.getLong("duration"), root.getLong("start"), root.getLong("end"), root.getInt("bitrate"), root.getBoolean("allowGame"),
                root.optString("format", "M4A"), root.optString("sourceKind", "AUDIO"))
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
object AudioEditing {
    fun inspect(context: Context, uri: Uri): Long {
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(context, uri)
            require(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") { localized(R.string.text_the_file_has_no_readable_audio_track_bc530e, "Il file non contiene una traccia audio leggibile") }
            return (reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0).also {
                require(it > 0) { localized(R.string.text_audio_duration_unavailable_3d0271, "Durata audio non disponibile") }
            }
        } finally { reader.release() }
    }
    fun clipping(recipe: AudioRecipe) = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(recipe.start).setEndPositionMs(recipe.end).build()
    fun editedItem(uri: Uri, recipe: AudioRecipe): EditedMediaItem = EditedMediaItem.Builder(MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping(recipe)).build())
        .setRemoveVideo(true).build()
    suspend fun render(context: Context, uri: Uri, recipe: AudioRecipe, output: File, check: suspend () -> Unit, progress: suspend (Int?) -> Unit) {
        require(withContext(Dispatchers.IO) { inspect(context, uri) } == recipe.duration) { localized(R.string.text_the_source_duration_has_changed_reopen_the_edito_be0a12, "La durata della sorgente è cambiata: riapri l'editor") }
        if (recipe.format == "MP3") {
            withContext(Dispatchers.IO) { Mp3Editing.render(context, uri, recipe, output, check, progress) }
            return
        }
        val result = VideoEditing.encode(context, editedItem(uri, recipe), output, check, progress, recipe.bitrate * 1000)
        require(result.audioConversionProcess == ExportResult.CONVERSION_PROCESS_TRANSCODED) { localized(R.string.text_audio_encoding_could_not_be_verified_output_not_681245, "Ricodifica audio non verificata: risultato non pubblicato") }
        check()
        withContext(Dispatchers.IO) { VideoEditing.verify(output, recipe.length, false, true, check) }
    }
}
