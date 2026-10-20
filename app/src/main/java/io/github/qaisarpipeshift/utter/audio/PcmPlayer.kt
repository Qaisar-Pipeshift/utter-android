package io.github.qaisarpipeshift.utter.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.delay

/**
 * Streaming playback for read-aloud PCM chunks (docs/spec.md §17, docs/plan.md Task 6).
 *
 * Why `MODE_STREAM`: chunks arrive from the network while the user is already listening, so the player starts on the
 * first chunk instead of waiting for the whole clip. [drain] lets the last buffered frames play out before [stop]
 * releases the track, so the end of a clip is never cut off.
 *
 * Why the `stopped` latch: a cancelled stream can still deliver one last chunk after [stop]; without the latch that
 * chunk would open a fresh `AudioTrack` that nobody owns or ever releases. [begin] arms the next playback, so every
 * playback path calls `stop()` + `begin()` before its first chunk.
 */
class PcmPlayer {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var rate = 0
    private var framesWritten = 0L

    @Volatile
    private var stopped = false

    /** Arms a new playback after [stop]; until this is called, [write] refuses chunks. */
    fun begin() {
        synchronized(lock) { stopped = false }
    }

    /** Writes one PCM16 mono chunk; false when playback is stopped or the track failed and was released. */
    fun write(pcm16le: ByteArray, sampleRate: Int): Boolean {
        if (pcm16le.isEmpty()) return true
        val audioTrack = synchronized(lock) {
            if (stopped) return false
            if (track == null || rate != sampleRate) {
                releaseLocked()
                rate = sampleRate
                framesWritten = 0
                track = create(sampleRate)
            }
            track
        } ?: return false
        val written = try {
            audioTrack.write(pcm16le, 0, pcm16le.size)
        } catch (_: IllegalStateException) {
            stop()
            return false
        }
        if (written < 0) {
            stop()
            return false
        }
        synchronized(lock) {
            if (track === audioTrack) framesWritten += written / BYTES_PER_SAMPLE
        }
        return true
    }

    /**
     * Waits for the frames already written to be played, then returns, so the caller can release the track without
     * losing the clip's tail. Bounded by the clip's own duration plus [DRAIN_GRACE_MS]; a [stop] or a new track ends
     * the wait immediately.
     */
    suspend fun drain() {
        val (current, frames, deadline) = synchronized(lock) {
            val open = track
            if (stopped || open == null || rate <= 0 || framesWritten <= 0) return
            Triple(
                open,
                framesWritten,
                System.currentTimeMillis() + framesWritten * MILLIS_PER_SECOND / rate + DRAIN_GRACE_MS,
            )
        }
        while (System.currentTimeMillis() < deadline) {
            val done = synchronized(lock) {
                stopped || track !== current || playedFrames(current) >= frames
            }
            if (done) return
            delay(POLL_MS)
        }
    }

    /** Stops and releases the track and latches the player; safe twice or without playback. */
    fun stop() {
        synchronized(lock) {
            stopped = true
            releaseLocked()
        }
    }

    private fun playedFrames(audioTrack: AudioTrack): Long = try {
        // The counter is unsigned 32-bit and wraps; a read-aloud clip never comes close to 2^32 frames.
        audioTrack.playbackHeadPosition.toLong() and HEAD_MASK
    } catch (_: IllegalStateException) {
        // Stopped or released between the check and the read: nothing is playing, so nothing to wait for.
        Long.MAX_VALUE
    }

    private fun releaseLocked() {
        framesWritten = 0
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
        const val BYTES_PER_SAMPLE = 2
        const val MILLIS_PER_SECOND = 1_000L
        const val DRAIN_GRACE_MS = 500L
        const val POLL_MS = 50L
        const val HEAD_MASK = 0xFFFFFFFFL
    }
}
