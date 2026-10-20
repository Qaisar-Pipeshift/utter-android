package io.github.qaisarpipeshift.utter

import android.app.Application

/**
 * Process entry point. The dependency graph is built lazily so the IME process only constructs what it touches,
 * and unit tests can instantiate the pieces they need directly instead of going through this singleton.
 */
class UtterApp : Application() {
    val graph: Graph by lazy { Graph(this) }
}
