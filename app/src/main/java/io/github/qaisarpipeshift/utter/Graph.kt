package io.github.qaisarpipeshift.utter

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import io.github.qaisarpipeshift.utter.audio.PcmPlayer
import io.github.qaisarpipeshift.utter.core.Cleaner
import io.github.qaisarpipeshift.utter.core.ResourceReading
import io.github.qaisarpipeshift.utter.core.TakeRetrier
import io.github.qaisarpipeshift.utter.data.HistoryStore
import io.github.qaisarpipeshift.utter.data.PendingStore
import io.github.qaisarpipeshift.utter.data.ResourceLedger
import io.github.qaisarpipeshift.utter.data.SecretStore
import io.github.qaisarpipeshift.utter.data.SettingsRepo
import io.github.qaisarpipeshift.utter.data.StatsStore
import io.github.qaisarpipeshift.utter.diag.Diag
import io.github.qaisarpipeshift.utter.net.Api
import io.github.qaisarpipeshift.utter.net.CleanResult
import io.github.qaisarpipeshift.utter.net.Http
import io.github.qaisarpipeshift.utter.net.ServerConfig

/**
 * Manual dependency graph shared by the IME and the settings app (docs/plan.md §1).
 *
 * Why manual: the app is small and has no annotation processor; a handful of lazy properties is simpler and cheaper
 * than a DI framework. Services are added as their tasks land, and [appContext] keeps every one of them on the
 * application context so nothing leaks an Activity or the IME service.
 */
class Graph(app: Context) {
    val appContext: Context = app.applicationContext

    val diag: Diag = Diag()

    val settings: SettingsRepo by lazy { SettingsRepo(appContext) }

    val secrets: SecretStore by lazy { SecretStore(appContext, diag) }

    /** Last 200 inserts, JSON lines in `filesDir` (docs/spec.md §15.4). */
    val history: HistoryStore by lazy { HistoryStore(appContext.filesDir) }

    /** Failed takes kept in the cache for Retry; kept audio moves to `filesDir/audio/`. */
    val pending: PendingStore by lazy { PendingStore(appContext.cacheDir, appContext.filesDir) }

    /** Thirty-day insights counters. */
    val stats: StatsStore by lazy { StatsStore(appContext.filesDir) }

    /**
     * Thirty-day resource counters (v0.1.4): CPU visible and hidden, mic time, traffic and take counts. Deltas stay
     * in memory between the three disk writes; the counters are read only at the IME's own checkpoints, never by a
     * timer. `Process` and `TrafficStats` live here so the store itself stays Android-free and JVM-testable.
     */
    val ledger: ResourceLedger by lazy {
        ResourceLedger(
            dir = appContext.filesDir,
            reading = {
                ResourceReading(
                    cpuMs = cpuTimeMs(),
                    txBytes = trafficBytes { TrafficStats.getUidTxBytes(Process.myUid()) },
                    rxBytes = trafficBytes { TrafficStats.getUidRxBytes(Process.myUid()) },
                )
            },
        )
    }

    /**
     * The app's one HTTP stack: one connection pool shared by the IME, the Server screen's probes and any other
     * caller, so Utter never keeps more than one idle socket alive (docs/spec.md §10).
     */
    val http: Http by lazy { Http() }

    /**
     * Reads settings and the key on every call so a URL or key change applies to the next request. Null means the
     * keyboard is not set up yet — the panel shows the wizard instead of attempting a request.
     */
    val api: Api by lazy {
        Api(http) {
            val current = settings.current()
            val key = secrets.load()
            if (current.baseUrl.isBlank() || key.isNullOrBlank()) {
                null
            } else {
                ServerConfig(current.baseUrl, key)
            }
        }
    }

    /** Cleanup through the API with the saved model and timeout, shared by the IME and the app's retry path. */
    val cleaner: Cleaner by lazy {
        object : Cleaner {
            override suspend fun clean(message: String): CleanResult {
                val snapshot = settings.current()
                val model = snapshot.cleanModel.ifBlank { DEFAULT_CLEAN_MODEL }
                return api.clean(model, message, snapshot.cleanTimeoutMs)
            }
        }
    }

    /** Retries a pending take from History; uploads the kept PCM and copies the outcome in the UI. */
    val retrier: TakeRetrier by lazy {
        TakeRetrier(
            transcriber = api,
            cleaner = cleaner,
            pending = pending,
            history = history,
            stats = stats,
            diag = diag,
        )
    }

    /** Shared playback for kept history audio; one track at a time, released when playback stops. */
    val pcmPlayer: PcmPlayer by lazy { PcmPlayer() }

    /** Process CPU time; a platform failure must cost a counter, not the keyboard. */
    private fun cpuTimeMs(): Long = try {
        Process.getElapsedCpuTime()
    } catch (_: Exception) {
        0L
    }

    /** Per-uid counters answer -1 (TrafficStats.UNSUPPORTED) on some devices; a missing counter counts as zero. */
    private fun trafficBytes(read: () -> Long): Long = try {
        read().coerceAtLeast(0L)
    } catch (_: Exception) {
        0L
    }

    private companion object {
        /** The server's documented cleanup model (docs/spec.md §6) until the user picks one. */
        const val DEFAULT_CLEAN_MODEL = "cleanup"
    }
}
