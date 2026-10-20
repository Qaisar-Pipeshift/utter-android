package io.github.qaisarpipeshift.utter

import android.content.Context
import io.github.qaisarpipeshift.utter.data.SecretStore
import io.github.qaisarpipeshift.utter.data.SettingsRepo
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

    /**
     * Reads settings and the key on every call so a URL or key change applies to the next request. Null means the
     * keyboard is not set up yet — the panel shows the wizard instead of attempting a request.
     */
    val api: Api by lazy {
        Api(Http()) {
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
