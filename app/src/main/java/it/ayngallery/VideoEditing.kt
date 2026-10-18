package it.ayngallery

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max

data class VideoRecipe(val duration: Long, val start: Long = 0, val end: Long = duration,
    val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f,
    val height: Int = 720, val mute: Boolean = false, val allowGame: Boolean = false) {
    init {
        require(duration in 250..(Long.MAX_VALUE / 1000 - 1000) && start in 0..duration && end in 250..duration && start <= end - 250) { localized(R.string.text_choose_a_segment_of_at_least_0_25_seconds_within_3023d9, "Scegli un segmento di almeno 0,25 secondi entro la durata del video") }
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } && right - left >= .005f && bottom - top >= .005f) { localized(R.string.text_invalid_video_crop_6c7d96, "Ritaglio video non valido") }
        require(height in setOf(720, 1080)) { localized(R.string.text_unsupported_resolution_b28db9, "Risoluzione non supportata") }
    }
    val length get() = end - start
    fun crop() = ImageRecipe(left = left, top = top, right = right, bottom = bottom)
    fun encode() = JSONObject().put("v", 1).put("type", "VIDEO").put("duration", duration).put("start", start).put("end", end)
        .put("left", left).put("top", top).put("right", right).put("bottom", bottom).put("height", height).put("mute", mute).put("allowGame", allowGame).toString()
    companion object {
        fun millis(text: String, duration: Long): Long {
            val seconds = text.trim().replace(',', '.').toDouble()
            require(seconds.isFinite() && seconds >= 0 && seconds <= duration / 1000.0) { localized(R.string.text_enter_seconds_within_the_content_duration_3fa3be, "Inserisci i secondi entro la durata del contenuto") }
            return (seconds * 1000).toLong()
        }
        fun decode(raw: String): VideoRecipe {
            require(raw.length <= 4096)
            val root = JSONObject(raw); require(root.getInt("v") == 1 && root.getString("type") == "VIDEO") { localized(R.string.text_unsupported_video_recipe_393281, "Ricetta video non supportata") }
            return VideoRecipe(root.getLong("duration"), root.getLong("start"), root.getLong("end"), root.getDouble("left").toFloat(),
                root.getDouble("top").toFloat(), root.getDouble("right").toFloat(), root.getDouble("bottom").toFloat(), root.getInt("height"), root.getBoolean("mute"), root.getBoolean("allowGame"))
        }
    }
}

fun segmentTime(milliseconds: Long) = formatTime(milliseconds) + "," + (milliseconds % 1000).toString().padStart(3, '0')

data class VideoInfo(val duration: Long, val width: Int, val height: Int, val audio: Boolean)

@androidx.annotation.OptIn(UnstableApi::class)
object VideoEditing {
    fun inspect(context: Context, uri: Uri): VideoInfo {
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(context, uri)
            require(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { localized(R.string.text_the_file_has_no_readable_video_track_6b8fdc, "Il file non contiene una traccia video leggibile") }
            val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            require(duration > 0 && width > 0 && height > 0) { localized(R.string.text_video_duration_or_dimensions_unavailable_007d54, "Durata o dimensioni del video non disponibili") }
            val rotated = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() in setOf(90, 270)
            return VideoInfo(duration, if (rotated) height else width, if (rotated) width else height,
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
        } finally { reader.release() }
    }
    fun poster(context: Context, uri: Uri, info: VideoInfo, timeMs: Long = 0): ImagePreview {
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(context, uri)
            val factor = minOf(1.0, 1280.0 / max(info.width, info.height))
            val timeUs = timeMs.coerceIn(0, info.duration) * 1000
            val bitmap = reader.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST,
                max(1, (info.width * factor).toInt()), max(1, (info.height * factor).toInt())) ?: throw IOException(localized(R.string.text_preview_frame_unavailable_3d562e, "Fotogramma di anteprima non disponibile"))
            return ImagePreview(bitmap, Size(info.width, info.height))
        } finally { reader.release() }
    }
    fun effects(recipe: VideoRecipe): List<Effect> = listOf(Crop(2 * recipe.left - 1, 2 * recipe.right - 1, 1 - 2 * recipe.bottom, 1 - 2 * recipe.top),
        Presentation.createForHeight(recipe.height))
    fun mediaItem(uri: Uri, recipe: VideoRecipe): MediaItem = MediaItem.Builder().setUri(uri)
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(recipe.start).setEndPositionMs(recipe.end).build()).build()
    fun editedItem(uri: Uri, recipe: VideoRecipe): EditedMediaItem = EditedMediaItem.Builder(mediaItem(uri, recipe))
        .setRemoveAudio(recipe.mute).setEffects(Effects(emptyList(), effects(recipe))).build()
    /** Re-encode every retained track, including matching AVC/AAC inputs. No trim optimization or compressed passthrough. */
    fun fullEncoder(context: Context, audioBitrate: Int = 0): Codec.EncoderFactory {
        val builder = DefaultEncoderFactory.Builder(context).setEnableFallback(false)
        if (audioBitrate > 0) builder.setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(audioBitrate).build())
        val native = builder.build()
        return object : Codec.EncoderFactory by native {
            override fun audioNeedsEncoding() = true
            override fun videoNeedsEncoding() = true
        }
    }
    fun withoutPersonalMetadata(native: Muxer.Factory): Muxer.Factory = object : Muxer.Factory by native {
        override fun create(path: String): Muxer {
            val output = native.create(path)
            return object : Muxer by output {
                override fun addMetadataEntry(metadataEntry: Metadata.Entry) = Unit
                override fun addTrack(format: Format) = output.addTrack(format.buildUpon().setMetadata(null).build())
            }
        }
    }
    suspend fun render(context: Context, uri: Uri, recipe: VideoRecipe, output: File, check: suspend () -> Unit, progress: suspend (Int?) -> Unit) {
        val info = withContext(Dispatchers.IO) { inspect(context, uri) }
        require(info.duration == recipe.duration) { localized(R.string.text_the_source_duration_has_changed_reopen_the_edito_be0a12, "La durata della sorgente è cambiata: riapri l'editor") }
        val result = encode(context, editedItem(uri, recipe), output, check, progress)
        require(result.videoConversionProcess == ExportResult.CONVERSION_PROCESS_TRANSCODED &&
            (recipe.mute || !info.audio || result.audioConversionProcess == ExportResult.CONVERSION_PROCESS_TRANSCODED)) { localized(R.string.text_track_encoding_unverified_output_not_published_13ac53, "Ricodifica delle tracce non verificata: risultato non pubblicato") }
        check()
        withContext(Dispatchers.IO) { verify(output, recipe.length, true, info.audio && !recipe.mute, check) }
    }
    /** Shared asynchronous native encoder for audio/video exports; all API calls use its application looper. */
    suspend fun encode(context: Context, item: EditedMediaItem, output: File, check: suspend () -> Unit,
        progress: suspend (Int?) -> Unit, audioBitrate: Int = 0): ExportResult {
        val completed = CompletableDeferred<ExportResult>()
        var transformer: Transformer? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                transformer = Transformer.Builder(context).setEncoderFactory(fullEncoder(context, audioBitrate))
                    .setMuxerFactory(withoutPersonalMetadata(DefaultMuxer.Factory()))
                    .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .experimentalSetTrimOptimizationEnabled(false).setEnsureFileStartsOnVideoFrameEnabled(true)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { completed.complete(exportResult) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            completed.completeExceptionally(IOException(localized(R.string.text_encoding_failed_1_s_codec_or_parameters_may_be_u_8f1cb9, "Codifica non riuscita (%1\$s). Codec o parametri potrebbero non essere supportati; originale conservato.", exportException.errorCodeName), exportException))
                        }
                    }).build()
                transformer!!.start(item, output.absolutePath)
            }
            var previous: Int? = null
            while (!completed.isCompleted) {
                check()
                val percent = withContext(Dispatchers.Main.immediate) {
                    val holder = ProgressHolder()
                    if (transformer!!.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress.coerceIn(0, 99) else null
                }
                if (percent != previous) { progress(percent); previous = percent }
                delay(500)
            }
            return completed.await()
        } finally { withContext(NonCancellable + Dispatchers.Main.immediate) { transformer?.cancel() } }
    }
    /** Check all encoded track timestamps, not only the container's advertised duration. */
    suspend fun verify(file: File, length: Long, video: Boolean, audio: Boolean, check: suspend () -> Unit, audioMime: String = MimeTypes.AUDIO_AAC) {
        require(file.length() > 0) { localized(R.string.text_encoding_did_not_produce_a_file_8b1592, "La codifica non ha prodotto un file") }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var videos = 0; var audios = 0
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME)
                when (mime) { MimeTypes.VIDEO_H264 -> videos++; audioMime -> audios++; else -> throw IOException(localized(R.string.text_unexpected_output_track_1_s_af782c, "Traccia di uscita inattesa: %1\$s", mime)) }
                val duration = format.getLong(MediaFormat.KEY_DURATION) / 1000
                require(abs(duration - length) <= 500) { localized(R.string.text_exported_duration_does_not_match_the_segment_5feb72, "La durata esportata non corrisponde al segmento") }
                extractor.selectTrack(track)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                var samples = 0; var maximum = -1L
                while (extractor.sampleTime >= 0) {
                    if (samples % 250 == 0) check()
                    val time = extractor.sampleTime
                    require(time <= (length + 250) * 1000) { localized(R.string.text_samples_beyond_the_chosen_segment_output_not_pub_af4d09, "Campioni oltre il segmento scelto: risultato non pubblicato") }
                    maximum = maxOf(maximum, time); samples++
                    if (!extractor.advance()) break
                }
                require(samples > 0 && maximum >= maxOf(0, length - 500) * 1000) { localized(R.string.text_exported_track_incomplete_79fd24, "Traccia esportata incompleta") }
                extractor.unselectTrack(track)
            }
            require(videos == (if (video) 1 else 0) && audios == (if (audio) 1 else 0)) { localized(R.string.text_video_audio_tracks_do_not_match_options_429d73, "Tracce video/audio non corrispondenti alle opzioni") }
        } finally { extractor.release() }
    }
}
