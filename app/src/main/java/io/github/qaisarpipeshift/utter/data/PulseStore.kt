package io.github.qaisarpipeshift.utter.data

import io.github.qaisarpipeshift.utter.core.PulseIds
import io.github.qaisarpipeshift.utter.core.PulseRecords
import io.github.qaisarpipeshift.utter.core.PulseTypes
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The per-source sampling cursors and throttle timestamp; persisted so a restart never re-samples. */
data class PulseMeta(
    val usageCursor: Long = 0,
    val dailyDay: Long = 0,
    val netDay: Long = 0,
    val lastSampleAt: Long = 0,
)

/**
 * Pulse's local store (docs/spec.md §18.2): JSON lines in `filesDir/pulse/`, one file per batch.
 *
 * Why file batches instead of one big log: the push unit is a batch — the engine sends closed batches oldest
 * first, deletes them on a 2xx, and keeps them on any failure, so retries are idempotent (unique record ids plus
 * the server's dedupe). The open batch carries a `.part` suffix and is never pushed. The 100 MB cap drops the
 * oldest closed batch and appends a `store_dropped` record (docs/spec.md §18.2). Every operation runs under one
 * [Mutex] on IO — notification callbacks arrive on the main thread and must never block it.
 */
class PulseStore(
    private val dir: File,
    private val ids: PulseIds,
    private val capBytes: () -> Long = { DEFAULT_CAP_MB * BYTES_PER_MB },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    /** Appends records to the open batch. False when the disk write failed — the engine must not advance cursors. */
    suspend fun append(records: List<JSONObject>): Boolean = withContext(io) {
        if (records.isEmpty()) return@withContext true
        mutex.withLock { appendLocked(records) }
    }

    /** Seals the open batch so the next push can send it; an empty open batch is deleted. */
    suspend fun closeBatch() = withContext(io) {
        mutex.withLock {
            val open = openBatchLocked()
            if (open != null) {
                if (open.length() == 0L) open.delete() else open.renameTo(closedNameOf(open))
            }
        }
    }

    /** Closed batch files, oldest first — the push order (docs/spec.md §18.2). */
    suspend fun closedBatches(): List<File> = withContext(io) {
        mutex.withLock { closedBatchesLocked() }
    }

    /** The batch's non-blank lines, in record order. */
    suspend fun readBatch(batch: File): List<String> = withContext(io) {
        mutex.withLock {
            try {
                batch.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            } catch (_: IOException) {
                emptyList()
            }
        }
    }

    /** Removes a batch after every record in it was accepted. */
    suspend fun deleteBatch(batch: File) = withContext(io) {
        mutex.withLock { batch.delete() }
    }

    suspend fun meta(): PulseMeta = withContext(io) {
        mutex.withLock { readMetaLocked() }
    }

    suspend fun saveMeta(meta: PulseMeta) = withContext(io) {
        mutex.withLock {
            try {
                if (!dir.exists()) dir.mkdirs()
                atomicWrite(File(dir, META_FILE), JSONObject()
                    .put("usageCursor", meta.usageCursor)
                    .put("dailyDay", meta.dailyDay)
                    .put("netDay", meta.netDay)
                    .put("lastSampleAt", meta.lastSampleAt)
                    .toString())
            } catch (_: IOException) {
                // Lost cursors only re-sample what Android still keeps; never fatal.
            }
        }
    }

    // ---- locked internals ----

    private fun appendLocked(records: List<JSONObject>): Boolean = try {
        if (!dir.exists()) dir.mkdirs()
        val open = openBatchLocked() ?: newOpenBatchLocked()
        open.appendText(records.joinToString(separator = "\n", postfix = "\n") { it.toString() }, Charsets.UTF_8)
        if (lineCountLocked(open) >= BATCH_RECORDS) open.renameTo(closedNameOf(open))
        enforceCapLocked()
        true
    } catch (_: IOException) {
        false
    }

    private fun enforceCapLocked() {
        val cap = capBytes()
        if (cap <= 0) return
        var droppedBytes = 0L
        var droppedFiles = 0
        while (totalBytesLocked() > cap) {
            val oldest = closedBatchesLocked().firstOrNull() ?: break
            val size = oldest.length()
            if (!oldest.delete()) break
            droppedBytes += size
            droppedFiles++
        }
        if (droppedFiles == 0) return
        val record = PulseRecords.storeDropped(ids, files = droppedFiles, bytes = droppedBytes)
        try {
            val open = openBatchLocked() ?: newOpenBatchLocked()
            open.appendText(record.toString() + "\n", Charsets.UTF_8)
        } catch (_: IOException) {
            // The dropped batches are already gone; the record is best-effort like every write here.
        }
    }

    private fun openBatchLocked(): File? = dir.listFiles()
        ?.firstOrNull { it.isFile && it.name.endsWith(OPEN_SUFFIX) }

    private fun newOpenBatchLocked(): File {
        val next = (allBatchesLocked().maxOfOrNull { it.seq } ?: 0) + 1
        return File(dir, "b-$next$OPEN_SUFFIX")
    }

    private fun closedBatchesLocked(): List<File> = allBatchesLocked()
        .filter { it.open == false }
        .sortedBy { it.seq }
        .map { it.file }

    private data class Named(val file: File, val seq: Int, val open: Boolean?)

    private fun allBatchesLocked(): List<Named> = dir.listFiles()
        ?.mapNotNull { file ->
            val name = file.name
            when {
                file.isFile && name.endsWith(CLOSED_SUFFIX) -> Named(file, seqOf(name), false)
                file.isFile && name.endsWith(OPEN_SUFFIX) -> Named(file, seqOf(name), true)
                else -> null
            }
        }
        .orEmpty()

    private fun seqOf(name: String): Int =
        name.removePrefix("b-").removeSuffix(OPEN_SUFFIX).removeSuffix(CLOSED_SUFFIX).toIntOrNull() ?: 0

    private fun closedNameOf(open: File): File = File(dir, open.name.removeSuffix(OPEN_SUFFIX) + CLOSED_SUFFIX)

    private fun lineCountLocked(file: File): Int = try {
        file.readLines(Charsets.UTF_8).count { it.isNotBlank() }
    } catch (_: IOException) {
        BATCH_RECORDS // unreadable: close it so the push path deals with it instead of growing forever
    }

    private fun totalBytesLocked(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun readMetaLocked(): PulseMeta {
        val file = File(dir, META_FILE)
        if (!file.exists()) return PulseMeta()
        val root = try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            return PulseMeta()
        }
        return PulseMeta(
            usageCursor = root.optLong("usageCursor", 0),
            dailyDay = root.optLong("dailyDay", 0),
            netDay = root.optLong("netDay", 0),
            lastSampleAt = root.optLong("lastSampleAt", 0),
        )
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(dir, "$META_FILE.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) tmp.delete()
        }
    }

    companion object {
        /** A batch closes itself at this many records so one push stays well inside the 512-record wire cap. */
        const val BATCH_RECORDS = 256

        const val DEFAULT_CAP_MB = 100
        const val BYTES_PER_MB = 1024L * 1024
        const val OPEN_SUFFIX = ".jsonl.part"
        const val CLOSED_SUFFIX = ".jsonl"
        const val META_FILE = "meta.json"
    }
}
