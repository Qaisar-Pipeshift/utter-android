package io.github.qaisarpipeshift.utter.core

import io.github.qaisarpipeshift.utter.data.Replacement
import io.github.qaisarpipeshift.utter.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class TextPipelineTest {

    // ---- raw(): spoken punctuation, fillers, replacements ----

    @Test
    fun `spoken punctuation becomes symbols with the preceding space removed`() {
        assertEquals("hello, world", TextPipeline.raw("hello comma world", Settings()))
        assertEquals("what?", TextPipeline.raw("what question mark", Settings()))
        assertEquals("one; two", TextPipeline.raw("one semicolon two", Settings()))
        assertEquals("note: read this", TextPipeline.raw("note colon read this", Settings()))
    }

    @Test
    fun `spoken sentence marks`() {
        assertEquals(
            "Hi! Yes: okay.",
            TextPipeline.raw("Hi exclamation mark Yes colon okay period", Settings()),
        )
        assertEquals("Stop. no wait, now.", TextPipeline.raw("Stop full stop no wait comma now full stop", Settings()))
    }

    @Test
    fun `spoken new line and new paragraph`() {
        assertEquals("line one\nline two", TextPipeline.raw("line one new line line two", Settings()))
        assertEquals("first\n\nsecond", TextPipeline.raw("first new paragraph second", Settings()))
    }

    @Test
    fun `spoken brackets`() {
        assertEquals("(hello)", TextPipeline.raw("open bracket hello close bracket", Settings()))
    }

    @Test
    fun `spoken punctuation is case-insensitive and whole-word only`() {
        assertEquals("Hello, world", TextPipeline.raw("Hello COMMA world", Settings()))
        assertEquals("commander", TextPipeline.raw("commander", Settings()))
    }

    @Test
    fun `spoken punctuation can be disabled`() {
        val settings = Settings(spokenPunctuation = false)
        assertEquals("hello comma world", TextPipeline.raw("hello comma world", settings))
    }

    @Test
    fun `the documented filler example loses its leading filler`() {
        assertEquals("so I think", TextPipeline.raw("um, so I think", Settings()))
    }

    @Test
    fun `fillers inside a sentence are removed`() {
        assertEquals("I think this is fine", TextPipeline.raw("I um think uh this is fine", Settings()))
    }

    @Test
    fun `custom fillers are removed`() {
        val settings = Settings(customFillers = listOf("you know"))
        assertEquals("I think it works", TextPipeline.raw("I you know think it works", settings))
    }

    @Test
    fun `filler removal can be disabled`() {
        val settings = Settings(removeFillers = false)
        assertEquals("um, so", TextPipeline.raw("um, so", settings))
    }

    @Test
    fun `replacement is whole-word and case-insensitive`() {
        val settings = Settings(
            spokenPunctuation = false,
            removeFillers = false,
            replacements = listOf(Replacement("my email", "x@y.z")),
        )
        assertEquals("send it to x@y.z", TextPipeline.raw("send it to My Email", settings))
    }

    @Test
    fun `replacement does not touch substrings`() {
        val settings = Settings(
            spokenPunctuation = false,
            removeFillers = false,
            replacements = listOf(Replacement("cat", "dog")),
        )
        assertEquals("concatenate the dog", TextPipeline.raw("concatenate the cat", settings))
    }

    @Test
    fun `longest replacement wins`() {
        val settings = Settings(
            spokenPunctuation = false,
            removeFillers = false,
            replacements = listOf(
                Replacement("new york", "NY"),
                Replacement("new york city", "NYC"),
            ),
        )
        assertEquals("I love NYC", TextPipeline.raw("I love new york city", settings))
    }

    @Test
    fun `replacement text is literal`() {
        val settings = Settings(
            spokenPunctuation = false,
            removeFillers = false,
            replacements = listOf(Replacement("cost", "\$5")),
        )
        assertEquals("price is \$5", TextPipeline.raw("price is cost", settings))
    }

    // ---- forField(): smart field adaptation ----

    @Test
    fun `text field capitalizes the first letter`() {
        assertEquals("Hello there", TextPipeline.forField("hello there", FieldKind.TEXT, Settings()))
        assertEquals("hello there", TextPipeline.forField("hello there", FieldKind.TEXT, Settings(capitalize = false)))
    }

    @Test
    fun `capitalization only changes the first letter`() {
        assertEquals("Hello WORLD", TextPipeline.forField("hello WORLD", FieldKind.TEXT, Settings()))
    }

    @Test
    fun `capitalization leaves a leading digit or capital alone`() {
        assertEquals("42 apples", TextPipeline.forField("42 apples", FieldKind.TEXT, Settings()))
        assertEquals("Hello", TextPipeline.forField("Hello", FieldKind.TEXT, Settings()))
    }

    @Test
    fun `email subject is capitalized`() {
        assertEquals(
            "Lunch tomorrow",
            TextPipeline.forField("lunch tomorrow", FieldKind.EMAIL_SUBJECT, Settings()),
        )
    }

    @Test
    fun `uri is lowercased with whitespace removed and no invented dots`() {
        assertEquals(
            "wwwdotexampledotcom",
            TextPipeline.forField("w w w dot example dot com", FieldKind.URI, Settings()),
        )
        assertEquals(
            "https://example.com/a",
            TextPipeline.forField("HTTPS://Example.com/A", FieldKind.URI, Settings()),
        )
    }

    @Test
    fun `email spells at and dot and strips spaces`() {
        assertEquals(
            "john@example.com",
            TextPipeline.forField("John at Example dot com", FieldKind.EMAIL, Settings()),
        )
        assertEquals("a@b.co", TextPipeline.forField("A @ B . CO", FieldKind.EMAIL, Settings()))
    }

    @Test
    fun `number keeps only digits dot and minus`() {
        assertEquals("12345.6-7", TextPipeline.forField("12,345.6-7", FieldKind.NUMBER, Settings()))
        assertEquals("", TextPipeline.forField("abc", FieldKind.NUMBER, Settings()))
    }

    @Test
    fun `phone keeps digits and a leading plus`() {
        assertEquals("+919876543210", TextPipeline.forField("+91 98765 43210", FieldKind.PHONE, Settings()))
        assertEquals("919876543210", TextPipeline.forField("91 (98765) 43210", FieldKind.PHONE, Settings()))
    }

    @Test
    fun `password text is left untouched`() {
        assertEquals("Secret 123", TextPipeline.forField("Secret 123", FieldKind.PASSWORD, Settings()))
    }
}
