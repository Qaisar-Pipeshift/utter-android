package io.github.qaisarpipeshift.utter.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Settings persistence on DataStore (docs/plan.md Task 2).
 *
 * The IME needs settings synchronously while a key is pressed, but DataStore is asynchronous. [flow] is the source
 * of truth for UI collection and refreshes [current], a volatile cache the keyboard can read without blocking.
 * Until the first emission the defaults apply — never a crash, never a disk read on the main thread.
 */
class SettingsRepo(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    @Volatile
    private var cached: Settings = Settings()

    val flow: Flow<Settings> = store.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map { prefs ->
            SettingsJson.decode(prefs[SETTINGS_KEY]).also { cached = it }
        }

    suspend fun update(f: (Settings) -> Settings) {
        store.edit { prefs ->
            val next = f(SettingsJson.decode(prefs[SETTINGS_KEY]))
            prefs[SETTINGS_KEY] = SettingsJson.encode(next)
            cached = next
        }
    }

    /** Last value read from DataStore, or defaults before the first emission. */
    fun current(): Settings = cached

    private companion object {
        val SETTINGS_KEY = stringPreferencesKey("settings_json")
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
