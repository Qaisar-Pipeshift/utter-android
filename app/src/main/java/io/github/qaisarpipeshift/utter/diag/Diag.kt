package io.github.qaisarpipeshift.utter.diag

import android.util.Log
import io.github.qaisarpipeshift.utter.BuildConfig
import java.time.Instant
import java.util.ArrayDeque

/**
 * Small in-memory diagnostics ring buffer (docs/spec.md §10, docs/plan.md Task 1).
 *
 * Why: the Diagnostics screen and bug reports need a short history of event kinds, sizes, HTTP codes and timings.
 * It deliberately carries no text, audio or key material — callers pass only metadata. The buffer is bounded, so a
 * dump is cheap and synchronous while the keyboard is visible.
 */
class Diag(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /** One captured event. [tMs] is wall-clock time in milliseconds. */
    data class Event(val tMs: Long, val kind: String, val detail: String)

    private val lock = Any()
    private val events = ArrayDeque<Event>(capacity)

    /** Appends an event, evicting the oldest one once [capacity] is reached. */
    fun event(kind: String, detail: String = "") {
        val entry = Event(clock(), kind, detail.take(MAX_DETAIL))
        synchronized(lock) {
            if (events.size >= capacity) events.removeFirst()
            events.addLast(entry)
        }
        if (BuildConfig.DEBUG) {
            Log.i(TAG, if (entry.detail.isEmpty()) entry.kind else "${entry.kind} ${entry.detail}")
        }
    }

    /** Oldest-to-newest copy of the buffer. */
    fun snapshot(): List<Event> = synchronized(lock) { events.toList() }

    /** One line per event: ISO-8601 instant, kind, then detail when present. */
    fun dump(): String = snapshot().joinToString("\n") { entry ->
        val detail = if (entry.detail.isEmpty()) "" else " ${entry.detail}"
        "${Instant.ofEpochMilli(entry.tMs)} ${entry.kind}$detail"
    }

    private companion object {
        const val TAG = "Utter"
        const val DEFAULT_CAPACITY = 100
        const val MAX_DETAIL = 120
    }
}
