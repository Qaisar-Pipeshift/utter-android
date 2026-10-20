package io.github.qaisarpipeshift.utter.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One finished dictation in the history (docs/spec.md §15.4, docs/plan.md Task 7).
 *
 * [finalText] is the text that was inserted (after pipeline and cleanup); [raw] is the recogniser's output. Both are
 * kept so the History screen can show what cleanup changed. Incognito takes never reach this store.
 */
data class HistoryEntry(
    val id: String,
    val ts: Long,
    val pkg: String,
    val appLabel: String,
    val raw: String,
    val finalText: String,
    val action: String,
    val presetName: String?,
    val durationMs: Long,
    val latencyMs: Long,
    val words: Int,
)

/**
 * Append-only history as JSON lines (docs/plan.md Task 7).
 *
 * Why JSON lines: the common operation is one append at the end of a take, which costs one line; search and prune
 * read the whole file, which is bounded by the retention limit. Every mutation runs under one [Mutex] on IO, and
 * rewrites go through a temp file plus `renameTo`, so a crash mid-prune cannot truncate the history. A corrupt line
 * is skipped, never fatal.
 */
class HistoryStore(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val file = File(dir, FILE_NAME)

    suspend fun add(entry: HistoryEntry) {
        withContext(io) {
            mutex.withLock {
                try {
                    if (!dir.exists()) dir.mkdirs()
                    file.appendText(encode(entry) + "\n", Charsets.UTF_8)
                } catch (_: IOException) {
                    // History is best-effort; the dictation already succeeded.
                }
            }
        }
    }

    /** Entries newest first, optionally filtered by a case-insensitive substring of raw, final text or app label. */
    suspend fun list(query: String = ""): List<HistoryEntry> = withContext(io) {
        mutex.withLock {
            load().filter { matches(it, query) }.sortedByDescending { it.ts }
        }
    }

    suspend fun delete(id: String) {
        withContext(io) {
            mutex.withLock { rewrite(load().filterNot { it.id == id }) }
        }
    }

    suspend fun clear() {
        withContext(io) {
            mutex.withLock { file.delete() }
        }
    }

    /** Keeps only the newest [limit] entries; 0 or less means "keep everything". */
    suspend fun prune(limit: Int) {
        if (limit <= 0) return
        withContext(io) {
            mutex.withLock {
                rewrite(load().sortedByDescending { it.ts }.take(limit))
            }
        }
    }

    private fun load(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        val lines = try {
            file.readLines(Charsets.UTF_8)
        } catch (_: IOException) {
            return emptyList()
        }
        val out = ArrayList<HistoryEntry>(lines.size)
        for (line in lines) {
            if (line.isBlank()) continue
            out.add(parse(line) ?: continue)
        }
        return out
    }

    private fun matches(entry: HistoryEntry, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return entry.raw.contains(needle, ignoreCase = true) ||
            entry.finalText.contains(needle, ignoreCase = true) ||
            entry.appLabel.contains(needle, ignoreCase = true)
    }

    private fun rewrite(entries: List<HistoryEntry>) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { writer ->
                for (entry in entries) {
                    writer.write(encode(entry))
                    writer.newLine()
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (_: IOException) {
            // Best-effort, like add().
        }
    }

    private fun encode(entry: HistoryEntry): String = JSONObject()
        .put("id", entry.id)
        .put("ts", entry.ts)
        .put("pkg", entry.pkg)
        .put("appLabel", entry.appLabel)
        .put("raw", entry.raw)
        .put("final", entry.finalText)
        .put("action", entry.action)
        .put("presetName", entry.presetName ?: JSONObject.NULL)
        .put("durationMs", entry.durationMs)
        .put("latencyMs", entry.latencyMs)
        .put("words", entry.words)
        .toString()

    private fun parse(line: String): HistoryEntry? {
        val root = try {
            JSONObject(line)
        } catch (_: Exception) {
            return null
        }
        val id = root.optString("id")
        if (id.isBlank()) return null
        return HistoryEntry(
            id = id,
            ts = root.optLong("ts", 0),
            pkg = root.optString("pkg"),
            appLabel = root.optString("appLabel"),
            raw = root.optString("raw"),
            finalText = root.optString("final"),
            action = root.optString("action"),
            presetName = root.opt("presetName") as? String,
            durationMs = root.optLong("durationMs", 0),
            latencyMs = root.optLong("latencyMs", 0),
            words = root.optInt("words", 0),
        )
    }

    private companion object {
        const val FILE_NAME = "history.jsonl"
    }
}
