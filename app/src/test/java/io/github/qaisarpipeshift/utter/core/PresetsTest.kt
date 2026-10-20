package io.github.qaisarpipeshift.utter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {

    @Test
    fun `ids are unique`() {
        val ids = Presets.defaults.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `the clean preset is present and uses the stable id`() {
        assertEquals("clean", Presets.CLEAN_ID)
        assertTrue(Presets.defaults.any { it.id == Presets.CLEAN_ID })
    }

    @Test
    fun `every preset has a name and a prompt`() {
        Presets.defaults.forEach { preset ->
            assertTrue("blank name for ${preset.id}", preset.name.isNotBlank())
            assertTrue("blank prompt for ${preset.id}", preset.prompt.isNotBlank())
        }
    }

    @Test
    fun `every prompt asks for only the resulting text`() {
        Presets.defaults.forEach { preset ->
            assertTrue(
                "prompt ${preset.id} must end with the return instruction",
                preset.prompt.trimEnd().endsWith("Return only the resulting text."),
            )
        }
    }

    @Test
    fun `all defaults are marked built in`() {
        assertTrue(Presets.defaults.all { it.builtIn })
    }

    @Test
    fun `the documented preset ids all exist`() {
        val ids = Presets.defaults.map { it.id }.toSet()
        assertEquals(
            setOf("clean", "formal", "casual", "bullets", "email", "to_english", "hinglish"),
            ids,
        )
    }

    @Test
    fun `clean prompt mentions the transcript tags`() {
        val clean = Presets.defaults.first { it.id == Presets.CLEAN_ID }
        assertTrue(clean.prompt.contains("<TRANSCRIPT>"))
    }
}
