package io.github.qaisarpipeshift.utter.data

import io.github.qaisarpipeshift.utter.core.DayStats
import io.github.qaisarpipeshift.utter.core.Stats
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Thirty-day insights counters, one JSON file of per-day buckets (docs/plan.md Task 7).
 *
 * Why a single file: the data is tiny (30 days of counters), so a read-modify-write of one file under one [Mutex] is
 * simpler and safer than appending events. Writes are best-effort — losing a day's counters must never fail a take.
 */
class StatsStore(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> LocalDate = { LocalDate.now() },
) {
    private val mutex = Mutex()
    private val file = File(dir, FILE_NAME)

    /** Counts one finished take into today's bucket and drops buckets older than [RETENTION_DAYS]. */
    suspend fun record(pkg: String, words: Int, speechMs: Long) {
        withContext(io) {
            mutex.withLock {
                val today = clock()
                val days = load().toMutableMap()
                val current = days[today] ?: DayStats(today)
                days[today] = current.copy(
                    takes = current.takes + 1,
                    words = current.words + words.coerceAtLeast(0),
                    speechMs = current.speechMs + speechMs.coerceAtLeast(0),
                    pkgCounts = if (pkg.isBlank()) {
                        current.pkgCounts
                    } else {
                        current.pkgCounts + (pkg to (current.pkgCounts[pkg] ?: 0) + 1)
                    },
                )
                save(prune(days, today))
            }
        }
    }

    /** All stored buckets, oldest first. */
    suspend fun days(): List<DayStats> = withContext(io) {
        mutex.withLock { load().values.sortedBy { it.date } }
    }

    /** Totals, today and the 7-day series for the Insights screen. */
    suspend fun summary(): Stats.Summary = withContext(io) {
        mutex.withLock { Stats.summary(load().values, clock()) }
    }

    private fun load(): Map<LocalDate, DayStats> {
        if (!file.exists()) return emptyMap()
        val text = try {
            file.readText()
        } catch (_: IOException) {
            return emptyMap()
        }
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return emptyMap()
        }
        val out = HashMap<LocalDate, DayStats>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val date = try {
                LocalDate.parse(key)
            } catch (_: DateTimeParseException) {
                continue
            }
            val item = root.optJSONObject(key) ?: continue
            val pkgCounts = LinkedHashMap<String, Int>()
            val counts = item.optJSONObject("pkgCounts")
            if (counts != null) {
                val pkgs = counts.keys()
                while (pkgs.hasNext()) {
                    val pkg = pkgs.next()
                    pkgCounts[pkg] = counts.optInt(pkg, 0)
                }
            }
            out[date] = DayStats(
                date = date,
                takes = item.optInt("takes", 0),
                words = item.optInt("words", 0),
                speechMs = item.optLong("speechMs", 0),
                pkgCounts = pkgCounts,
            )
        }
        return out
    }

    private fun save(days: Map<LocalDate, DayStats>) {
        val root = JSONObject()
        for (day in days.values) {
            val pkgCounts = JSONObject()
            for ((pkg, count) in day.pkgCounts) pkgCounts.put(pkg, count)
            root.put(
                day.date.toString(),
                JSONObject()
                    .put("takes", day.takes)
                    .put("words", day.words)
                    .put("speechMs", day.speechMs)
                    .put("pkgCounts", pkgCounts),
            )
        }
        writeAtomic(root.toString())
    }

    private fun writeAtomic(text: String) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (_: IOException) {
            // Insights are not worth failing a dictation over.
        }
    }

    private fun prune(days: Map<LocalDate, DayStats>, today: LocalDate): Map<LocalDate, DayStats> {
        val oldest = today.minusDays((RETENTION_DAYS - 1).toLong())
        return days.filterKeys { !it.isBefore(oldest) && !it.isAfter(today) }
    }

    private companion object {
        const val FILE_NAME = "stats.json"
        const val RETENTION_DAYS = 30
    }
}
