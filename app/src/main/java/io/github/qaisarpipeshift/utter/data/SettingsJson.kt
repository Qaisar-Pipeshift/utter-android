package io.github.qaisarpipeshift.utter.data

import io.github.qaisarpipeshift.utter.core.Preset
import io.github.qaisarpipeshift.utter.core.Presets
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON codec for [Settings] (docs/plan.md Task 2). Uses `org.json` because it ships with Android.
 *
 * Decoding is deliberately forgiving: unknown keys are ignored, missing keys take defaults, and a wrong type falls
 * back to that key's default instead of throwing. Settings are user data — a corrupt preference must never brick
 * the keyboard.
 */
object SettingsJson {

    fun encode(settings: Settings): String = JSONObject().apply {
        put("baseUrl", settings.baseUrl)
        put("tapHandsFree", settings.tapHandsFree)
        put("holdThresholdMs", settings.holdThresholdMs)
        put("slideGestures", settings.slideGestures)
        put("live", settings.live)
        put("liveInField", settings.liveInField)
        put("autoStart", settings.autoStart)
        put("duckAudio", settings.duckAudio)
        put("sounds", settings.sounds)
        put("tailMs", settings.tailMs)
        put("micId", settings.micId ?: JSONObject.NULL)
        put("haptics", settings.haptics)
        put("speculativeClean", settings.speculativeClean)
        put("smartSpacing", settings.smartSpacing)
        put("trailingSpace", settings.trailingSpace)
        put("capitalize", settings.capitalize)
        put("removeFillers", settings.removeFillers)
        put("customFillers", JSONArray(settings.customFillers))
        put("spokenPunctuation", settings.spokenPunctuation)
        put("replacements", JSONArray().apply {
            settings.replacements.forEach { replacement ->
                put(JSONObject().put("from", replacement.from).put("to", replacement.to))
            }
        })
        put("vocabulary", JSONArray(settings.vocabulary))
        put("presets", JSONArray().apply {
            settings.presets.forEach { preset ->
                put(
                    JSONObject()
                        .put("id", preset.id)
                        .put("name", preset.name)
                        .put("prompt", preset.prompt)
                        .put("builtIn", preset.builtIn),
                )
            }
        })
        put("defaultPresetId", settings.defaultPresetId)
        put("cleanModel", settings.cleanModel)
        put("cleanTimeoutMs", settings.cleanTimeoutMs)
        put("voiceEdit", settings.voiceEdit)
        put("autoEnter", settings.autoEnter)
        put("copyToClipboard", settings.copyToClipboard)
        put("returnToPrevious", settings.returnToPrevious)
        put("appRules", JSONArray().apply {
            settings.appRules.forEach { rule ->
                put(
                    JSONObject()
                        .put("pkg", rule.pkg)
                        .put("action", rule.action.name.lowercase())
                        .put("presetId", rule.presetId ?: JSONObject.NULL)
                        .put("autoSend", rule.autoSend)
                        .put("block", rule.block)
                        .put("historyOff", rule.historyOff),
                )
            }
        })
        put("seenApps", JSONObject().apply {
            settings.seenApps.forEach { (pkg, label) -> put(pkg, label) }
        })
        put("historyOn", settings.historyOn)
        put("historyLimit", settings.historyLimit)
        put("keepAudioDays", settings.keepAudioDays)
        put("notch", settings.notch)
        put("theme", settings.theme.name.lowercase())
        put("palette", settings.palette.name.lowercase())
        put("panelHeight", settings.panelHeight.name.lowercase())
        put("ttsVoice", settings.ttsVoice)
        put("onboardingDone", settings.onboardingDone)
        put("prewarm", settings.prewarm)
    }.toString()

    fun decode(json: String?): Settings {
        if (json.isNullOrBlank()) return Settings()
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return Settings()
        }
        return Settings(
            baseUrl = root.string("baseUrl", ""),
            tapHandsFree = root.bool("tapHandsFree", true),
            holdThresholdMs = root.int("holdThresholdMs", 300),
            slideGestures = root.bool("slideGestures", true),
            live = root.bool("live", true),
            liveInField = root.bool("liveInField", false),
            autoStart = root.bool("autoStart", false),
            duckAudio = root.bool("duckAudio", true),
            sounds = root.bool("sounds", false),
            tailMs = root.int("tailMs", 0),
            micId = root.intOrNull("micId"),
            haptics = root.bool("haptics", true),
            speculativeClean = root.bool("speculativeClean", false),
            smartSpacing = root.bool("smartSpacing", true),
            trailingSpace = root.bool("trailingSpace", false),
            capitalize = root.bool("capitalize", true),
            removeFillers = root.bool("removeFillers", true),
            customFillers = root.stringList("customFillers", emptyList()),
            spokenPunctuation = root.bool("spokenPunctuation", true),
            replacements = root.replacements("replacements"),
            vocabulary = root.stringList("vocabulary", emptyList()),
            presets = root.presetList("presets", Presets.defaults),
            defaultPresetId = root.string("defaultPresetId", Presets.CLEAN_ID),
            cleanModel = root.string("cleanModel", ""),
            cleanTimeoutMs = root.long("cleanTimeoutMs", 15_000L),
            voiceEdit = root.bool("voiceEdit", true),
            autoEnter = root.bool("autoEnter", false),
            copyToClipboard = root.bool("copyToClipboard", false),
            returnToPrevious = root.bool("returnToPrevious", true),
            appRules = root.appRules("appRules"),
            seenApps = root.seenApps("seenApps"),
            historyOn = root.bool("historyOn", true),
            historyLimit = root.int("historyLimit", 200),
            keepAudioDays = root.int("keepAudioDays", 0),
            notch = root.bool("notch", true),
            theme = themeOf(root.string("theme", "")),
            palette = paletteOf(root.string("palette", "")),
            panelHeight = panelHeightOf(root.string("panelHeight", "")),
            ttsVoice = root.string("ttsVoice", "aiden"),
            onboardingDone = root.bool("onboardingDone", false),
            prewarm = root.bool("prewarm", true),
        )
    }

    private fun themeOf(name: String): ThemeMode =
        ThemeMode.values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ThemeMode.SYSTEM

    private fun paletteOf(name: String): Palette =
        Palette.values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Palette.HANDY

    private fun panelHeightOf(name: String): PanelHeight =
        PanelHeight.values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: PanelHeight.NORMAL
}

private fun JSONObject.string(key: String, fallback: String): String = opt(key) as? String ?: fallback

private fun JSONObject.bool(key: String, fallback: Boolean): Boolean = opt(key) as? Boolean ?: fallback

private fun JSONObject.int(key: String, fallback: Int): Int = (opt(key) as? Number)?.toInt() ?: fallback

private fun JSONObject.long(key: String, fallback: Long): Long = (opt(key) as? Number)?.toLong() ?: fallback

private fun JSONObject.intOrNull(key: String): Int? = (opt(key) as? Number)?.toInt()

private fun JSONObject.stringList(key: String, fallback: List<String>): List<String> {
    val array = optJSONArray(key) ?: return fallback
    val out = ArrayList<String>(array.length())
    for (index in 0 until array.length()) {
        val value = array.opt(index) as? String ?: continue
        out.add(value)
    }
    return out
}

private fun JSONObject.replacements(key: String): List<Replacement> {
    val array = optJSONArray(key) ?: return emptyList()
    val out = ArrayList<Replacement>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val from = item.opt("from") as? String ?: continue
        val to = item.opt("to") as? String ?: continue
        if (from.isNotBlank()) out.add(Replacement(from, to))
    }
    return out
}

private fun JSONObject.presetList(key: String, fallback: List<Preset>): List<Preset> {
    val array = optJSONArray(key) ?: return fallback
    val out = ArrayList<Preset>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val id = item.string("id", "")
        if (id.isBlank()) continue
        out.add(
            Preset(
                id = id,
                name = item.string("name", id),
                prompt = item.string("prompt", ""),
                builtIn = item.bool("builtIn", false),
            ),
        )
    }
    return out
}

private fun JSONObject.appRules(key: String): List<AppRule> {
    val array = optJSONArray(key) ?: return emptyList()
    val out = ArrayList<AppRule>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val pkg = item.string("pkg", "")
        if (pkg.isBlank()) continue
        out.add(
            AppRule(
                pkg = pkg,
                action = RuleAction.values().firstOrNull {
                    it.name.equals(item.string("action", ""), ignoreCase = true)
                } ?: RuleAction.DEFAULT,
                presetId = item.opt("presetId") as? String,
                autoSend = item.bool("autoSend", false),
                block = item.bool("block", false),
                historyOff = item.bool("historyOff", false),
            ),
        )
    }
    return out
}

private fun JSONObject.seenApps(key: String): Map<String, String> {
    val objectValue = optJSONObject(key) ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    val keys = objectValue.keys()
    while (keys.hasNext()) {
        val pkg = keys.next()
        val label = objectValue.opt(pkg) as? String ?: continue
        out[pkg] = label
    }
    return out
}
