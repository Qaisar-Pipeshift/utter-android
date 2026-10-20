package io.github.qaisarpipeshift.utter.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * PCM16LE helpers shared by the recorder, the live socket and the WAV writer (docs/plan.md Task 6).
 *
 * Why pure: the amplitude that drives the waveform ring and the duration that feeds history are arithmetic, so they
 * are unit-tested on the JVM instead of being eyeballed on a phone.
 */
object Pcm {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SAMPLE = 2

    /** Silence floor of the display scale: anything quieter than this reads as 0. */
    private const val FLOOR_DB = 60.0

    /**
     * Root-mean-square level of [pcm16le], mapped to 0..1 on a 60 dB log scale. A linear level looks almost flat on
     * screen — speech sits around -20 dB — so the ring would barely move without the log mapping.
     */
    fun rms(pcm16le: ByteArray): Float {
        if (pcm16le.size < BYTES_PER_SAMPLE) return 0f
        var sum = 0.0
        var samples = 0
        var index = 0
        while (index + 1 < pcm16le.size) {
            val low = pcm16le[index].toInt() and 0xFF
            val high = pcm16le[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort() / 32768.0
            sum += sample * sample
            samples++
            index += BYTES_PER_SAMPLE
        }
        if (samples == 0) return 0f
        val linear = sqrt(sum / samples)
        if (linear <= 0.0) return 0f
        val db = 20.0 * log10(linear)
        return ((db + FLOOR_DB) / FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
    }

    /** Duration of [pcm16le] in milliseconds at [rate] samples per second. */
    fun durationMs(pcm16le: ByteArray, rate: Int = SAMPLE_RATE): Long =
        durationMs(pcm16le.size.toLong(), rate)

    /** Duration of [byteCount] bytes of mono PCM16 in milliseconds; used for files without loading them. */
    fun durationMs(byteCount: Long, rate: Int = SAMPLE_RATE): Long {
        val bytesPerMs = rate.toLong() * BYTES_PER_SAMPLE / 1000
        return if (bytesPerMs <= 0) 0 else byteCount / bytesPerMs
    }

    /** 44-byte little-endian WAV header for raw PCM16 data of [dataBytes] bytes (history replay). */
    fun wavHeader(dataBytes: Int, rate: Int = SAMPLE_RATE, channels: Int = 1): ByteArray {
        val bitsPerSample = 16
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = rate * blockAlign
        val out = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(36 + dataBytes)
        out.put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16)
        out.putShort(1) // PCM
        out.putShort(channels.toShort())
        out.putInt(rate)
        out.putInt(byteRate)
        out.putShort(blockAlign.toShort())
        out.putShort(bitsPerSample.toShort())
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(dataBytes)
        return out.array()
    }

    private const val WAV_HEADER_BYTES = 44
}
