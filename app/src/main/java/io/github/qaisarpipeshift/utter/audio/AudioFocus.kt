package io.github.qaisarpipeshift.utter.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Transient ducking while a take records (docs/spec.md §15.1, docs/plan.md Task 6).
 *
 * Why duck instead of pause: the user is talking over their music, not stopping it; a short duck keeps the context
 * audible. Every call is best-effort — a refused or failing focus request must never stop a dictation.
 */
class AudioFocus(context: Context) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var request: AudioFocusRequest? = null

    fun request() {
        val manager = audioManager ?: return
        if (request != null) return
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(false)
                .build()
            request = focusRequest
            manager.requestAudioFocus(focusRequest)
        } catch (_: Exception) {
            // A focus refusal only means other audio keeps playing; the take is unaffected.
            request = null
        }
    }

    fun abandon() {
        val manager = audioManager ?: return
        val focusRequest = request ?: return
        request = null
        try {
            manager.abandonAudioFocusRequest(focusRequest)
        } catch (_: Exception) {
            // Nothing to undo.
        }
    }
}
