package io.github.qaisarpipeshift.utter.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Metadata of a take kept for Retry (docs/plan.md Task 7); the PCM lives next to it in the cache. */
data class PendingMeta(
    val id: String,
    val createdAt: Long,
    val action: String,
    val presetId: String?,
    val pkg: String,
    val fieldKind: String,
    val durationMs: Long = 0,
    val lastError: String? = null,
)

/**
 * Failed takes persisted as `<id>.pcm` + `<id>.json` (docs/spec.md §8, docs/plan.md Task 7).
 *
 * Why the cache: a failed upload must survive an IME restart, and the audio is retry material, not user data — the
 * system may reclaim it under pressure. Anything older than 24 h is purged at startup, so the queue cannot grow.
 * Meta writes are atomic (temp file + `renameTo`), and one [Mutex] serialises every operation.
 */
class PendingStore(
    cacheDir: File,
    filesDir: File = cacheDir,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val takesDir = File(cacheDir, TAKES_DIR)
    private val audioDir = File(filesDir, AUDIO_DIR)

    /** Creates the metadata and returns the file the recorder must write. */
    suspend fun newTake(meta: PendingMeta): File = withContext(io) {
        mutex.withLock {
            val stamped = meta.copy(createdAt = if (meta.createdAt == 0L) now() else meta.createdAt)
            writeMeta(stamped)
            File(takesDir, "${stamped.id}$PCM_SUFFIX")
        }
    }

    /** Where the recorder wrote, or would write, the PCM of [id]; the controller needs it to retry a kept take. */
    fun pcmFile(id: String): File = File(takesDir, "$id$PCM_SUFFIX")

    /** Pending takes, newest first. */
    suspend fun list(): List<PendingMeta> = withContext(io) {
        mutex.withLock { readAll().sortedByDescending { it.createdAt } }
    }

    suspend fun meta(id: String): PendingMeta? = withContext(io) {
        mutex.withLock { readMeta(id) }
    }

    suspend fun update(id: String, lastError: String?, durationMs: Long? = null) {
        withContext(io) {
            mutex.withLock {
                val current = readMeta(id) ?: return@withLock
                writeMeta(current.copy(lastError = lastError, durationMs = durationMs ?: current.durationMs))
            }
        }
    }

    suspend fun delete(id: String) {
        withContext(io) {
            mutex.withLock {
                File(takesDir, "$id$PCM_SUFFIX").delete()
                File(takesDir, "$id$JSON_SUFFIX").delete()
            }
        }
    }

    /** Drops takes older than [olderThanMs] and any stale orphan PCM; called when the IME and the app start. */
    suspend fun purge(olderThanMs: Long = PURGE_AGE_MS) {
        withContext(io) {
            mutex.withLock {
                val cutoff = now() - olderThanMs
                for (meta in readAll()) {
                    if (meta.createdAt < cutoff) {
                        File(takesDir, "${meta.id}$PCM_SUFFIX").delete()
                        File(takesDir, "${meta.id}$JSON_SUFFIX").delete()
                    }
                }
                takesDir.listFiles()?.forEach { file ->
                    if (!file.isFile) return@forEach
                    val orphan = file.name.endsWith(PCM_SUFFIX) && !File(
                        takesDir,
                        file.name.removeSuffix(PCM_SUFFIX) + JSON_SUFFIX,
                    ).exists()
                    if (orphan && file.lastModified() < cutoff) file.delete()
                }
            }
        }
    }

    /**
     * Moves a take's PCM into `filesDir/audio/` for history replay, marks it as kept now, and prunes audio older
     * than [days]; the pending metadata is removed.
     */
    suspend fun keepAudio(id: String, days: Int) {
        if (days <= 0) return
        withContext(io) {
            mutex.withLock {
                val pcm = File(takesDir, "$id$PCM_SUFFIX")
                if (!pcm.exists()) return@withLock
                if (!audioDir.exists()) audioDir.mkdirs()
                val destination = File(audioDir, "$id$PCM_SUFFIX")
                destination.delete()
                if (pcm.renameTo(destination)) {
                    destination.setLastModified(now())
                    File(takesDir, "$id$JSON_SUFFIX").delete()
                }
                pruneAudioLocked(days)
            }
        }
    }

    /** Deletes kept audio older than [days]; 0 or less keeps everything. */
    suspend fun purgeAudio(days: Int) {
        if (days <= 0) return
        withContext(io) {
            mutex.withLock { pruneAudioLocked(days) }
        }
    }

    private fun pruneAudioLocked(days: Int) {
        if (!audioDir.exists()) return
        val cutoff = now() - days.toLong() * DAY_MS
        audioDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun readAll(): List<PendingMeta> = takesDir.listFiles()
        ?.mapNotNull { file ->
            if (file.isFile && file.name.endsWith(JSON_SUFFIX)) readMetaFile(file) else null
        }
        .orEmpty()

    private fun readMeta(id: String): PendingMeta? =
        readMetaFile(File(takesDir, "$id$JSON_SUFFIX"))

    private fun readMetaFile(file: File): PendingMeta? {
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (_: IOException) {
            return null
        }
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        return PendingMeta(
            id = root.optString("id").ifBlank { file.name.removeSuffix(JSON_SUFFIX) },
            createdAt = root.optLong("createdAt", 0),
            action = root.optString("action"),
            presetId = root.opt("presetId") as? String,
            pkg = root.optString("pkg"),
            fieldKind = root.optString("fieldKind"),
            durationMs = root.optLong("durationMs", 0),
            lastError = root.opt("lastError") as? String,
        )
    }

    private fun writeMeta(meta: PendingMeta) {
        try {
            if (!takesDir.exists()) takesDir.mkdirs()
            val root = JSONObject()
                .put("id", meta.id)
                .put("createdAt", meta.createdAt)
                .put("action", meta.action)
                .put("presetId", meta.presetId ?: JSONObject.NULL)
                .put("pkg", meta.pkg)
                .put("fieldKind", meta.fieldKind)
                .put("durationMs", meta.durationMs)
                .put("lastError", meta.lastError ?: JSONObject.NULL)
            val target = File(takesDir, "${meta.id}$JSON_SUFFIX")
            val tmp = File(takesDir, "${meta.id}$JSON_SUFFIX.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) tmp.delete()
            }
        } catch (_: IOException) {
            // A take that cannot be recorded as pending simply cannot be retried; the panel says so.
        }
    }

    private companion object {
        const val TAKES_DIR = "takes"
        const val AUDIO_DIR = "audio"
        const val PCM_SUFFIX = ".pcm"
        const val JSON_SUFFIX = ".json"
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val PURGE_AGE_MS = DAY_MS
    }
}
