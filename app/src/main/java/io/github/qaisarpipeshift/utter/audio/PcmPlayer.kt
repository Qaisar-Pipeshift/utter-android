package io.github.qaisarpipeshift.utter.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Streaming playback for read-aloud PCM chunks (docs/spec.md §17, docs/plan.md Task 6).
 *
 * Why `MODE_STREAM`: chunks arrive from the network while the user is already listening, so the player starts on the
 * first chunk instead of waiting for the whole clip. [stop] releases the `AudioTrack`; a write error also stops, so a
 * broken output route never leaves a track holding an audio resource.
 */
class PcmPlayer {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var rate = 0

    /** Writes one PCM16 mono chunk; false when playback failed and the track was released. */
    fun write(pcm16le: ByteArray, sampleRate: Int): Boolean {
        if (pcm16le.isEmpty()) return true
        val audioTrack = synchronized(lock) {
            if (track == null || rate != sampleRate) {
                releaseLocked()
                rate = sampleRate
                track = create(sampleRate)
            }
            track
        } ?: return false
        val written = audioTrack.write(pcm16le, 0, pcm16le.size)
        if (written < 0) {
            stop()
            return false
        }
        return true
    }

    /** Stops and releases the track; safe to call twice or without playback. */
    fun stop() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        val audioTrack = track ?: return
        track = null
        rate = 0
        try {
            audioTrack.stop()
        } catch (_: IllegalStateException) {
            // Never played or already stopped.
        }
        try {
            audioTrack.flush()
        } catch (_: IllegalStateException) {
            // Nothing buffered.
        }
        audioTrack.release()
    }

    private fun create(sampleRate: Int): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBuffer, sampleRate / BYTES_PER_QUARTER_SECOND)
        val audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            return null
        }
        return try {
            audioTrack.play()
            audioTrack
        } catch (_: Exception) {
            audioTrack.release()
            null
        }
    }

    private companion object {
        // 16-bit mono: sampleRate / 2 bytes is a quarter second of audio.
        const val BYTES_PER_QUARTER_SECOND = 2
    }
}
