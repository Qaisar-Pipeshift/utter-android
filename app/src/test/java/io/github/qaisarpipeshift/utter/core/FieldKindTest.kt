package io.github.qaisarpipeshift.utter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** InputType/EditorInfo values below are the platform constants, written out so no Android runtime is needed. */
class FieldKindTest {

    private fun kind(inputType: Int): FieldKind = FieldInfos.from(inputType, 0, null, 0).kind

    private fun action(imeOptions: Int): EnterAction = FieldInfos.from(0x00000001, imeOptions, null, 0).actionLabel

    @Test
    fun `plain text is text`() = assertEquals(FieldKind.TEXT, kind(0x00000001))

    @Test
    fun `text password is password`() = assertEquals(FieldKind.PASSWORD, kind(0x00000001 or 0x00000080))

    @Test
    fun `visible password is password`() = assertEquals(FieldKind.PASSWORD, kind(0x00000001 or 0x00000090))

    @Test
    fun `web password is password`() = assertEquals(FieldKind.PASSWORD, kind(0x00000001 or 0x000000e0))

    @Test
    fun `uri variation is uri`() = assertEquals(FieldKind.URI, kind(0x00000001 or 0x00000010))

    @Test
    fun `email variation is email`() = assertEquals(FieldKind.EMAIL, kind(0x00000001 or 0x00000020))

    @Test
    fun `web email variation is email`() = assertEquals(FieldKind.EMAIL, kind(0x00000001 or 0x000000d0))

    @Test
    fun `email subject variation is email subject`() =
        assertEquals(FieldKind.EMAIL_SUBJECT, kind(0x00000001 or 0x00000030))

    @Test
    fun `number class is number`() = assertEquals(FieldKind.NUMBER, kind(0x00000002))

    @Test
    fun `number password is password`() = assertEquals(FieldKind.PASSWORD, kind(0x00000002 or 0x00000010))

    @Test
    fun `phone class is phone`() = assertEquals(FieldKind.PHONE, kind(0x00000003))

    @Test
    fun `unknown class is text`() = assertEquals(FieldKind.TEXT, kind(0x00000000))

    @Test
    fun `incognito comes from the no personalized learning flag`() {
        assertTrue(FieldInfos.from(0x00000001, 0x10000000, "com.example", 3).incognito)
        assertFalse(FieldInfos.from(0x00000001, 0, "com.example", 3).incognito)
    }

    @Test
    fun `package and field id are carried through`() {
        val info = FieldInfos.from(0x00000001, 0, "com.example.chat", 42)
        assertEquals("com.example.chat", info.pkg)
        assertEquals(42, info.fieldId)
    }

    @Test
    fun `a null package becomes empty`() {
        assertEquals("", FieldInfos.from(0x00000001, 0, null, 0).pkg)
    }

    @Test
    fun `send action maps to send`() = assertEquals(EnterAction.SEND, action(0x00000004))

    @Test
    fun `search action maps to search`() = assertEquals(EnterAction.SEARCH, action(0x00000003))

    @Test
    fun `go action maps to go`() = assertEquals(EnterAction.GO, action(0x00000002))

    @Test
    fun `next action maps to next`() = assertEquals(EnterAction.NEXT, action(0x00000005))

    @Test
    fun `done action maps to done`() = assertEquals(EnterAction.DONE, action(0x00000006))

    @Test
    fun `unknown and none actions are newline`() {
        assertEquals(EnterAction.NEWLINE, action(0x00000000))
        assertEquals(EnterAction.NEWLINE, action(0x00000001))
        assertEquals(EnterAction.NEWLINE, action(0x00000007))
    }

    @Test
    fun `no-enter-action flag wins over the action bits`() {
        assertEquals(EnterAction.NEWLINE, action(0x40000000 or 0x00000004))
    }

    @Test
    fun `incognito flag does not disturb the action`() {
        assertEquals(EnterAction.DONE, action(0x10000000 or 0x00000006))
    }
}
