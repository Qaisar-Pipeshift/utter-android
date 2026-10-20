package io.github.qaisarpipeshift.utter.data

import io.github.qaisarpipeshift.utter.core.Presets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    @Test
    fun `round trip keeps every exported field`() {
        val settings = Settings(
            baseUrl = "https://speech.example",
            live = false,
            vocabulary = listOf("Utter", "Qais"),
            defaultPresetId = "formal",
            historyLimit = 50,
            keepAudioDays = 1,
        )

        val restored = Backup.decode(Backup.encode(settings, now = 1234L))

        assertEquals("https://speech.example", restored?.baseUrl)
        assertEquals(false, restored?.live)
        assertEquals(listOf("Utter", "Qais"), restored?.vocabulary)
        assertEquals("formal", restored?.defaultPresetId)
        assertEquals(50, restored?.historyLimit)
        assertEquals(1, restored?.keepAudioDays)
    }

    @Test
    fun `api key is never in the export`() {
        val json = Backup.encode(Settings(), now = 1L)

        assertFalse(json.contains("apiKey", ignoreCase = true))
        assertFalse(json.contains("secret", ignoreCase = true))
    }

    @Test
    fun `bare settings json is accepted`() {
        val restored = Backup.decode(SettingsJson.encode(Settings(baseUrl = "https://bare.example")))

        assertEquals("https://bare.example", restored?.baseUrl)
    }

    @Test
    fun `garbage and unrelated json are rejected`() {
        assertNull(Backup.decode(null))
        assertNull(Backup.decode(""))
        assertNull(Backup.decode("not json"))
        assertNull(Backup.decode("{}"))
        assertNull(Backup.decode("""{"foo":"bar"}"""))
    }

    @Test
    fun `summary counts changed settings`() {
        val current = Settings(baseUrl = "https://one.example", live = true)
        val same = Backup.summary(current, current)
        assertEquals(0, same.changed)

        val changed = Backup.summary(current, current.copy(baseUrl = "https://two.example", live = false))
        assertEquals(2, changed.changed)
        assertEquals("https://two.example", changed.imported.baseUrl)
    }

    @Test
    fun `summary flags only a real server host change`() {
        val current = Settings(baseUrl = "https://one.example")

        // Same host with a path or a port is the same server: the key goes to the same place.
        assertNull(Backup.summary(current, Settings(baseUrl = "https://one.example/v1")).newHost)
        assertNull(Backup.summary(current, Settings(baseUrl = "https://ONE.example")).newHost)

        val moved = Backup.summary(current, Settings(baseUrl = "https://two.example"))
        assertEquals("two.example", moved.newHost)

        // A backup with no URL at all keeps the current server silently.
        assertNull(Backup.summary(current, Settings(baseUrl = "")).newHost)
        assertNull(Backup.summary(Settings(), Settings(baseUrl = "")).newHost)
    }

    @Test
    fun `host extraction tolerates blank and junk urls`() {
        assertNull(Backup.hostOf(""))
        assertNull(Backup.hostOf("   "))
        assertNull(Backup.hostOf("not a url"))
        assertEquals("speech.example", Backup.hostOf("https://speech.example:8443/v1"))
    }

    @Test
    fun `presets survive the round trip`() {
        val custom = Presets.defaults + io.github.qaisarpipeshift.utter.core.Preset(
            id = "mine",
            name = "Mine",
            prompt = "Return only the text.",
            builtIn = false,
        )
        val restored = Backup.decode(Backup.encode(Settings(presets = custom), now = 9L))

        assertTrue(restored!!.presets.any { it.id == "mine" && it.name == "Mine" })
    }
}
