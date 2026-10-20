package io.github.qaisarpipeshift.utter.data

import io.github.qaisarpipeshift.utter.core.Preset
import io.github.qaisarpipeshift.utter.core.Presets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsJsonTest {

    private val custom = Settings(
        baseUrl = "https://dictation.example.test",
        tapHandsFree = false,
        holdThresholdMs = 450,
        slideGestures = false,
        live = false,
        liveInField = true,
        autoStart = true,
        duckAudio = false,
        sounds = true,
        tailMs = 250,
        micId = 42,
        haptics = false,
        speculativeClean = true,
        smartSpacing = false,
        trailingSpace = true,
        capitalize = false,
        removeFillers = false,
        customFillers = listOf("you know", "sort of"),
        spokenPunctuation = false,
        replacements = listOf(Replacement("my email", "x@y.z"), Replacement("new york city", "NYC")),
        vocabulary = listOf("Utter", "Pipeshift"),
        presets = listOf(
            Presets.defaults.first(),
            Preset("mine", "Mine", "Do the thing. Return only the resulting text.", false),
        ),
        defaultPresetId = "mine",
        cleanModel = "cleanup",
        cleanTimeoutMs = 4_000L,
        voiceEdit = false,
        autoEnter = true,
        copyToClipboard = true,
        returnToPrevious = false,
        appRules = listOf(
            AppRule(
                pkg = "com.example.chat",
                action = RuleAction.CLEAN,
                presetId = "formal",
                autoSend = true,
                block = false,
                historyOff = true,
            ),
        ),
        seenApps = mapOf("com.example.chat" to "Example Chat", "com.example.notes" to "Notes"),
        historyOn = false,
        historyLimit = 0,
        keepAudioDays = 7,
        notch = false,
        theme = ThemeMode.LIGHT,
        palette = Palette.DYNAMIC,
        panelHeight = PanelHeight.TALL,
        ttsVoice = "nova",
        onboardingDone = true,
        prewarm = false,
    )

    @Test
    fun `round trip keeps every field`() {
        assertEquals(custom, SettingsJson.decode(SettingsJson.encode(custom)))
    }

    @Test
    fun `round trip with a null mic id`() {
        val settings = custom.copy(micId = null)
        assertEquals(settings, SettingsJson.decode(SettingsJson.encode(settings)))
    }

    @Test
    fun `null decodes to defaults`() {
        assertEquals(Settings(), SettingsJson.decode(null))
    }

    @Test
    fun `blank decodes to defaults`() {
        assertEquals(Settings(), SettingsJson.decode("   "))
    }

    @Test
    fun `empty object decodes to defaults`() {
        assertEquals(Settings(), SettingsJson.decode("{}"))
    }

    @Test
    fun `malformed json decodes to defaults`() {
        assertEquals(Settings(), SettingsJson.decode("{not json"))
        assertEquals(Settings(), SettingsJson.decode("[]"))
    }

    @Test
    fun `wrong types fall back to the default for that key`() {
        val decoded = SettingsJson.decode(
            """{"live":"yes","holdThresholdMs":"soon","micId":"x","theme":7,
               "presets":"nope","historyLimit":null,"seenApps":[1],"appRules":{}}""",
        )
        assertEquals(Settings(), decoded)
    }

    @Test
    fun `unknown keys are ignored`() {
        val decoded = SettingsJson.decode("""{"futureKey":{"nested":true},"another":1}""")
        assertEquals(Settings(), decoded)
    }

    @Test
    fun `present keys override only themselves`() {
        val decoded = SettingsJson.decode("""{"theme":"dark","holdThresholdMs":700}""")
        assertEquals(Settings().copy(theme = ThemeMode.DARK, holdThresholdMs = 700), decoded)
    }

    @Test
    fun `enum names decode case-insensitively`() {
        val decoded = SettingsJson.decode("""{"theme":"Light","palette":"dynamic","panelHeight":"compact"}""")
        assertEquals(ThemeMode.LIGHT, decoded.theme)
        assertEquals(Palette.DYNAMIC, decoded.palette)
        assertEquals(PanelHeight.COMPACT, decoded.panelHeight)
    }

    @Test
    fun `invalid preset entries are skipped`() {
        val decoded = SettingsJson.decode(
            """{"presets":[{"id":"x","name":"X","prompt":"p","builtIn":true},{"id":""},{"name":"no id"}]}""",
        )
        assertEquals(listOf(Preset("x", "X", "p", true)), decoded.presets)
    }

    @Test
    fun `the api key is never part of settings json`() {
        val json = SettingsJson.encode(custom)
        assertFalse(json.contains("apiKey"))
        assertFalse(json.contains("api_key"))
    }

    @Test
    fun `numbers are clamped to the UI ranges`() {
        val decoded = SettingsJson.decode(
            """{"holdThresholdMs":5,"tailMs":9000,"cleanTimeoutMs":1,"historyLimit":-3,"keepAudioDays":99}""",
        )
        assertEquals(150, decoded.holdThresholdMs)
        assertEquals(500, decoded.tailMs)
        assertEquals(3_000L, decoded.cleanTimeoutMs)
        assertEquals(0, decoded.historyLimit)
        assertEquals(7, decoded.keepAudioDays)
    }

    @Test
    fun `an empty preset list falls back to the built-ins`() {
        assertEquals(Presets.defaults, SettingsJson.decode("""{"presets":[]}""").presets)
    }
}
