package io.github.qaisarpipeshift.utter.audio

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Start/stop beeps (docs/spec.md §15.1, docs/plan.md Task 6). Off by default; the controller passes the setting.
 *
 * Why a short tone instead of a sound file: `ToneGenerator` needs no asset, no media player and no audio focus. Each
 * beep creates a generator and releases it once the tone has played, so nothing holds an `AudioTrack` while idle.
 */
class Cues(private val enabled: () -> Boolean) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cue played before the mic opens. */
    fun start() = play(ToneGenerator.TONE_PROP_BEEP)

    /** Cue played after the mic is released, so it is never recorded. */
    fun stop() = play(ToneGenerator.TONE_PROP_ACK)

    private fun play(tone: Int) {
        if (!enabled()) return
        val generator = try {
            ToneGenerator(AudioManager.STREAM_SYSTEM, VOLUME)
        } catch (_: RuntimeException) {
            return
        }
        val started = try {
            generator.startTone(tone, TONE_MS)
        } catch (_: RuntimeException) {
            false
        }
        if (!started) {
            generator.release()
            return
        }
        scope.launch {
            delay(TONE_MS + RELEASE_DELAY_MS)
            try {
                generator.release()
            } catch (_: Exception) {
                // Already gone.
            }
        }
    }

    private companion object {
        const val VOLUME = 60
        const val TONE_MS = 120
        const val RELEASE_DELAY_MS = 150L
    }
}
