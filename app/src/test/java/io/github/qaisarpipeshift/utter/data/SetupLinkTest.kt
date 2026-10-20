package io.github.qaisarpipeshift.utter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupLinkTest {

    private val url = "https://voice.example.test/api"
    private val key = "k3y+/= &value"

    @Test fun roundTripKeepsUrlAndKey() {
        val link = SetupLink.build(url, key)
        assertEquals(SetupLink.Data(url, key), SetupLink.parse(link!!))
    }

    @Test fun linkUsesTheDocumentedSchemeAndHost() {
        val link = SetupLink.build(url, key)!!
        assertTrue(link.startsWith("utter://setup?u="))
    }

    @Test fun trailingSlashAndV1AreNormalised() {
        val link = SetupLink.build("https://voice.example.test/v1/", key)!!
        assertEquals("https://voice.example.test", SetupLink.parse(link)!!.url)
    }

    @Test fun buildRejectsCleartextUrl() = assertNull(SetupLink.build("http://voice.example.test", key))

    @Test fun buildRejectsUnknownScheme() = assertNull(SetupLink.build("ftp://voice.example.test", key))

    @Test fun buildRejectsBlankKey() = assertNull(SetupLink.build(url, "   "))

    @Test fun parseRejectsWrongHost() =
        assertNull(SetupLink.parse("utter://other?u=https%3A%2F%2Fvoice.example.test&k=abc"))

    @Test fun parseRejectsWrongScheme() =
        assertNull(SetupLink.parse("https://voice.example.test?u=https%3A%2F%2Fvoice.example.test&k=abc"))

    @Test fun parseRejectsMissingKey() =
        assertNull(SetupLink.parse("utter://setup?u=https%3A%2F%2Fvoice.example.test"))

    @Test fun parseRejectsCleartextInsideTheLink() =
        assertNull(SetupLink.parse("utter://setup?u=http%3A%2F%2Fvoice.example.test&k=abc"))

    @Test fun parseRejectsBrokenEncoding() =
        assertNull(SetupLink.parse("utter://setup?u=https%3A%2F%2Fvoice.example.test&k=%ZZ"))

    @Test fun parseAcceptsTrailingSlashAfterHost() {
        val link = "utter://setup/?u=https%3A%2F%2Fvoice.example.test&k=abc"
        assertEquals(SetupLink.Data("https://voice.example.test", "abc"), SetupLink.parse(link))
    }

    @Test fun parseIgnoresExtraParameters() {
        val link = "utter://setup?u=https%3A%2F%2Fvoice.example.test&x=1&k=abc"
        assertEquals(SetupLink.Data("https://voice.example.test", "abc"), SetupLink.parse(link))
    }

    @Test fun keyWithAmpersandSurvives() {
        val tricky = "a&b=c+d/e"
        val parsed = SetupLink.parse(SetupLink.build(url, tricky)!!)
        assertEquals(tricky, parsed!!.key)
    }

    @Test fun surroundingWhitespaceIsTrimmed() {
        val parsed = SetupLink.parse(SetupLink.build(url, "  $key \n")!!)
        assertEquals(SetupLink.Data(url, key), parsed)
    }
}
