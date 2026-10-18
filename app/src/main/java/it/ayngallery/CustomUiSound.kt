package it.ayngallery

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil

internal data class CustomUiSound(val wav: ByteArray, val duration: Long, val samples: List<Float>) {
    companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
        fun read(stream: InputStream): CustomUiSound {
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32768)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(count > 0) { "Audio provider returned no data" }
                require(bytes.size() + count <= MAX_BYTES) { localized(R.string.sound_custom_invalid, "Scegli un file WAV valido, lungo al massimo 6 secondi.") }
                bytes.write(buffer, 0, count)
            }
            return decode(bytes.toByteArray())
        }
        fun decode(bytes: ByteArray): CustomUiSound {
            fun valid(condition: Boolean) = require(condition) { localized(R.string.sound_custom_invalid, "Scegli un file WAV valido, lungo al massimo 6 secondi.") }
            valid(bytes.size in 44..MAX_BYTES)
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
            valid(tag(0) == "RIFF" && tag(8) == "WAVE")
            val end = 8L + (input.getInt(4).toLong() and 0xffffffffL)
            valid(end == bytes.size.toLong())
            var offset = 12
            var format = 0; var channels = 0; var rate = 0; var bits = 0; var stride = 0
            var dataOffset = -1; var dataSize = 0
            while (offset + 8 <= bytes.size) {
                val size = input.getInt(offset + 4).toLong() and 0xffffffffL
                val next = offset + 8L + size + (size and 1)
                valid(next <= end)
                when (tag(offset)) {
                    "fmt " -> {
                        valid(size >= 16 && format == 0)
                        format = input.getShort(offset + 8).toInt() and 0xffff
                        channels = input.getShort(offset + 10).toInt() and 0xffff
                        rate = input.getInt(offset + 12)
                        stride = input.getShort(offset + 20).toInt() and 0xffff
                        bits = input.getShort(offset + 22).toInt() and 0xffff
                        valid(channels in 1..2 && rate in 8000..192000 &&
                            ((format == 1 && bits in setOf(8, 16, 24, 32)) || (format == 3 && bits == 32)))
                        valid(stride == channels * bits / 8 && input.getInt(offset + 16) == rate * stride)
                    }
                    "data" -> { valid(dataOffset < 0); dataOffset = offset + 8; dataSize = size.toInt() }
                }
                offset = next.toInt()
            }
            valid(offset == bytes.size && format != 0 && dataOffset >= 0 && dataSize > 0 && dataSize % stride == 0)
            val frames = dataSize / stride
            valid(frames.toLong() <= rate * 6L)
            fun sample(frame: Int, channel: Int): Double {
                val at = dataOffset + frame * stride + channel * bits / 8
                val value = when {
                    format == 3 -> input.getFloat(at).toDouble()
                    bits == 8 -> ((bytes[at].toInt() and 255) - 128) / 128.0
                    bits == 16 -> input.getShort(at) / 32768.0
                    bits == 24 -> (((bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or (bytes[at + 2].toInt() shl 16))) / 8388608.0
                    else -> input.getInt(at) / 2147483648.0
                }
                valid(value.isFinite())
                return value.coerceIn(-1.0, 1.0)
            }
            // A six-second mono 22.05 kHz cue fits comfortably in SoundPool's decoded sample budget.
            val outputFrames = maxOf(1, (frames.toLong() * 22050 / rate).toInt())
            val pcm = ShortArray(outputFrames)
            for (i in pcm.indices) {
                val from = (i.toLong() * frames / outputFrames).toInt()
                val until = maxOf(from + 1, ((i + 1L) * frames / outputFrames).toInt()).coerceAtMost(frames)
                var sum = 0.0
                for (frame in from until until) for (channel in 0 until channels) sum += sample(frame, channel)
                pcm[i] = (sum / ((until - from) * channels) * 32767).toInt().coerceIn(-32768, 32767).toShort()
            }
            val wav = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(22050).putInt(44100).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(pcm.size * 2)
            pcm.forEach(wav::putShort)
            val samples = List(256) { bin ->
                val from = bin * pcm.size / 256
                val until = maxOf(from + 1, (bin + 1) * pcm.size / 256).coerceAtMost(pcm.size)
                (from until until).maxByOrNull { abs(pcm[it].toInt()) }?.let { pcm[it] / 32768f } ?: 0f
            }
            return CustomUiSound(wav.array(), ceil(pcm.size * 1000.0 / 22050).toLong(), samples)
        }
    }
}
