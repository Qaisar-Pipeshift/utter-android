package io.github.qaisarpipeshift.utter

import android.content.Context
import io.github.qaisarpipeshift.utter.data.HistoryStore
import io.github.qaisarpipeshift.utter.data.PendingStore
import io.github.qaisarpipeshift.utter.data.SecretStore
import io.github.qaisarpipeshift.utter.data.SettingsRepo
import io.github.qaisarpipeshift.utter.data.StatsStore
import io.github.qaisarpipeshift.utter.diag.Diag
import io.github.qaisarpipeshift.utter.net.Api
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
}
