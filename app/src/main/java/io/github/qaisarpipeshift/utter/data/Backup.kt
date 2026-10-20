package io.github.qaisarpipeshift.utter.data

import org.json.JSONObject

/**
 * Settings backup as a JSON file (docs/spec.md §15.5, docs/plan.md Task 14).
 *
 * The API key is absent by construction: it lives in [SecretStore], not in [Settings], so exporting settings can
 * never leak it. The wrapper carries a small header for humans and future migrations, while [decode] still accepts
 * a bare settings object. Import is validated before the UI shows its confirmation, so a stray JSON file cannot
 * silently reset every preference to defaults.
 */
object Backup {
    const val FILE_NAME = "utter-settings.json"
    private const val APP = "utter"
    private const val FORMAT = 1

    /** Pretty JSON with a header, the full settings object, and never the key. */
    fun encode(settings: Settings, now: Long = System.currentTimeMillis()): String = JSONObject()
        .put("app", APP)
        .put("format", FORMAT)
        .put("exportedAt", now)
        .put("settings", JSONObject(SettingsJson.encode(settings)))
        .toString(2)

    /** The imported settings, or null when [json] is not an Utter settings backup. */
    fun decode(json: String?): Settings? {
        if (json.isNullOrBlank()) return null
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        val settingsObject = root.optJSONObject("settings") ?: root
        if (!hasKnownKey(settingsObject)) return null
        return SettingsJson.decode(settingsObject.toString())
    }

    /**
     * How many settings the import would change, the decoded settings, and the new server host when the backup points
     * somewhere else. [newHost] is non-null exactly when the user must explicitly accept a different server — the API
     * key would otherwise start going to a host nobody chose.
     */
    data class Summary(
        val changed: Int,
        val imported: Settings,
        val newHost: String? = null,
    )

    fun summary(current: Settings, imported: Settings): Summary {
        val before = JSONObject(SettingsJson.encode(current))
        val after = JSONObject(SettingsJson.encode(imported))
        val keys = LinkedHashSet<String>()
        val beforeKeys = before.keys()
        while (beforeKeys.hasNext()) keys.add(beforeKeys.next())
        val afterKeys = after.keys()
        while (afterKeys.hasNext()) keys.add(afterKeys.next())
        var changed = 0
        for (key in keys) {
            if (before.opt(key)?.toString() != after.opt(key)?.toString()) changed++
        }
        val currentHost = hostOf(current.baseUrl)
        val importedHost = hostOf(imported.baseUrl)
        // A backup with no URL (or the same host) changes nothing about where the key goes.
        val newHost = importedHost?.takeIf { it.lowercase() != currentHost?.lowercase() }
        return Summary(changed, imported, newHost)
    }

    /** The host a base URL points at, or null for a blank or unparsable one; hosts compare case-insensitively. */
    fun hostOf(baseUrl: String): String? {
        if (baseUrl.isBlank()) return null
        return try {
            java.net.URI(baseUrl).host?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun hasKnownKey(objectValue: JSONObject): Boolean {
        val keys = objectValue.keys()
        while (keys.hasNext()) {
            if (keys.next() in knownKeys) return true
        }
        return false
    }

    private val knownKeys: Set<String> by lazy {
        val keys = JSONObject(SettingsJson.encode(Settings())).keys()
        val out = LinkedHashSet<String>()
        while (keys.hasNext()) out.add(keys.next())
        out
    }
}
