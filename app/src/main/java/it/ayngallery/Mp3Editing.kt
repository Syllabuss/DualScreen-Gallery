package it.ayngallery

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import de.sciss.jump3r.mp3.*
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/** Stream decoded PCM into LAME; memory usage is independent of the input's duration. */
internal object Mp3Editing {
    suspend fun render(context: Context, uri: Uri, recipe: AudioRecipe, output: File,
        check: suspend () -> Unit, progress: suspend (Int?) -> Unit) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: Mp3PcmEncoder? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error(localized(R.string.text_the_file_has_no_readable_audio_track_bc530e, "Il file non contiene una traccia audio leggibile"))
            var format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(track); extractor.seekTo(recipe.start * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var percent = -1
            var framesWritten = 0L
            output.outputStream().use { stream ->
                suspend fun pcm(buffer: ByteBuffer, timeUs: Long) {
                    check()
                    val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(rate in 8000..192000 && channels in 1..8 && encoding in setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) {
                        localized(R.string.editor_pcm_unsupported, "Formato audio decodificato non supportato")
                    }
                    val stride = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    val frames = buffer.remaining() / stride
                    val first = ceil((recipe.start * 1000 - timeUs) * rate / 1_000_000.0).toInt().coerceIn(0, frames)
                    val last = ceil((recipe.end * 1000 - timeUs) * rate / 1_000_000.0).toInt().coerceIn(first, frames)
                    if (last > first) {
                        val active = encoder ?: Mp3PcmEncoder(rate, if (channels == 1) 1 else 2, recipe.bitrate).also { encoder = it }
                        require(active.inputRate == rate && active.channels == if (channels == 1) 1 else 2)
                        buffer.position(buffer.position() + first * stride)
                        buffer.limit(buffer.position() + (last - first) * stride)
                        active.write(buffer.order(ByteOrder.LITTLE_ENDIAN), channels, encoding == AudioFormat.ENCODING_PCM_FLOAT, stream)
                        framesWritten += last - first
                    }
                    val next = (((timeUs / 1000 - recipe.start).coerceIn(0, recipe.length) * 100) / recipe.length).toInt()
                    if (next != percent) { percent = next; progress(next) }
                }
                if (mime == "audio/raw") {
                    val buffer = ByteBuffer.allocateDirect(131072)
                    while (extractor.sampleTime >= 0 && extractor.sampleTime < recipe.end * 1000) {
                        check(); buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        buffer.position(0); buffer.limit(size); pcm(buffer, extractor.sampleTime)
                        if (!extractor.advance()) break
                    }
                } else {
                    format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    val codec = MediaCodec.createDecoderByType(mime).also { decoder = it }
                    codec.configure(format, null, null, 0); codec.start()
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false; var outputDone = false
                    var lastWork = SystemClock.elapsedRealtime()
                    while (!outputDone) {
                        check()
                        check(SystemClock.elapsedRealtime() - lastWork < 15000) { localized(R.string.editor_decoder_timeout, "La decodifica audio non risponde. Nessun originale modificato.") }
                        if (!inputDone) {
                            val index = codec.dequeueInputBuffer(10000)
                            if (index >= 0) {
                                val buffer = requireNotNull(codec.getInputBuffer(index)); buffer.clear()
                                val time = extractor.sampleTime
                                val size = if (time < 0 || time >= recipe.end * 1000) -1 else extractor.readSampleData(buffer, 0)
                                if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                                else { codec.queueInputBuffer(index, 0, size, time, 0); extractor.advance() }
                                lastWork = SystemClock.elapsedRealtime()
                            }
                        }
                        when (val index = codec.dequeueOutputBuffer(info, 10000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { format = codec.outputFormat; lastWork = SystemClock.elapsedRealtime() }
                            else -> if (index >= 0) {
                                try {
                                    if (info.size > 0) {
                                        val buffer = requireNotNull(codec.getOutputBuffer(index))
                                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                        pcm(buffer, info.presentationTimeUs)
                                    }
                                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                    lastWork = SystemClock.elapsedRealtime()
                                } finally { codec.releaseOutputBuffer(index, false) }
                            }
                        }
                    }
                }
                require(framesWritten > 0) { localized(R.string.text_exported_track_incomplete_79fd24, "Traccia esportata incompleta") }
                check(); requireNotNull(encoder).finish(stream)
            }
            VideoEditing.verify(output, recipe.length, false, true, check, "audio/mpeg")
            progress(100)
        } finally {
            encoder?.close()
            decoder?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }
}

/** Core LAME API only: no desktop javax.sound classes are loaded on Android. */
internal class Mp3PcmEncoder(val inputRate: Int, val channels: Int, bitrate: Int) : Closeable {
    private val lame = Lame()
    private val flags: LameGlobalFlags
    private val left = IntArray(4096)
    private val right = IntArray(4096)
    private val encoded = ByteArray(16384)
    init {
        require(inputRate in 8000..192000 && channels in 1..2 && bitrate in setOf(128, 192, 256))
        val gain = GainAnalysis(); val bits = BitStream(); val presets = Presets()
        val quantizePrivate = QuantizePVT(); val quantize = Quantize(); val vbr = VBRTag()
        val version = Version(); val tags = ID3Tag(); val reservoir = Reservoir(); val takehiro = Takehiro()
        val mpg = MPGLib(); val common = Common(); val api = Interface()
        lame.setModules(gain, bits, presets, quantizePrivate, quantize, vbr, version, tags, mpg)
        bits.setModules(gain, mpg, version, vbr); tags.setModules(bits, version); presets.setModules(lame)
        quantize.setModules(bits, reservoir, quantizePrivate, takehiro)
        quantizePrivate.setModules(takehiro, reservoir, lame.enc.psy)
        reservoir.setModules(bits); takehiro.setModules(quantizePrivate); vbr.setModules(lame, bits, version)
        mpg.setModules(api, common); api.setModules(vbr, common)
        flags = lame.lame_init().apply {
            num_channels = channels; in_samplerate = inputRate; out_samplerate = minOf(inputRate, 48000)
            mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
            brate = bitrate; quality = 3; VBR = VbrMode.vbr_off
            write_id3tag_automatic = false; bWriteVbrTag = false; findReplayGain = false
        }
        tags.id3tag_init(flags)
        check(lame.lame_init_params(flags) == 0)
    }
    fun write(pcm: ByteBuffer, sourceChannels: Int, floating: Boolean, output: OutputStream) {
        val stride = sourceChannels * if (floating) 4 else 2
        require(sourceChannels in 1..8)
        while (pcm.remaining() >= stride) {
            val count = minOf(left.size, pcm.remaining() / stride)
            for (frame in 0 until count) {
                var l = 0.0; var r = 0.0; var normalization = 1.0
                for (channel in 0 until sourceChannels) {
                    val value = if (floating) pcm.float.toDouble() else pcm.short / 32768.0
                    val sample = if (value.isFinite()) value.coerceIn(-1.0, 1.0) else 0.0
                    when {
                        sourceChannels == 1 -> { l = sample; r = sample }
                        channel == 0 -> l = sample
                        channel == 1 -> r = sample
                        channel == 2 -> { l += sample * .707; r += sample * .707; normalization += .707 }
                        channel == 3 -> { l += sample * .25; r += sample * .25; normalization += .25 }
                        channel % 2 == 0 -> { l += sample * .5; normalization += .25 }
                        else -> { r += sample * .5; normalization += .25 }
                    }
                }
                left[frame] = ((l / normalization).coerceIn(-1.0, 1.0) * Int.MAX_VALUE).toInt()
                right[frame] = ((r / normalization).coerceIn(-1.0, 1.0) * Int.MAX_VALUE).toInt()
            }
            val size = lame.lame_encode_buffer_int(flags, left, right, count, encoded, 0, encoded.size)
            check(size >= 0); output.write(encoded, 0, size)
        }
    }
    fun finish(output: OutputStream) { val size = lame.lame_encode_flush(flags, encoded, 0, encoded.size); check(size >= 0); output.write(encoded, 0, size) }
    override fun close() { lame.lame_close(flags) }
}
