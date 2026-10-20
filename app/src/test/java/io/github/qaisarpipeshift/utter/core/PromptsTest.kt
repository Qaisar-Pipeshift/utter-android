package io.github.qaisarpipeshift.utter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptsTest {

    @Test
    fun `clean message appends the transcript tags exactly`() {
        assertEquals(
            "Fix it.\n\n<TRANSCRIPT>\nhello world\n</TRANSCRIPT>",
            Prompts.cleanMessage("Fix it.", "hello world"),
        )
    }

    @Test
    fun `clean message keeps an empty transcript between the tags`() {
        assertEquals("P\n\n<TRANSCRIPT>\n\n</TRANSCRIPT>", Prompts.cleanMessage("P", ""))
    }

    @Test
    fun `clean message preserves a multiline transcript`() {
        assertEquals(
            "P\n\n<TRANSCRIPT>\nline one\nline two\n</TRANSCRIPT>",
            Prompts.cleanMessage("P", "line one\nline two"),
        )
    }

    @Test
    fun `clean message leaves the prompt untouched`() {
        assertEquals("Keep ${'$'} and <b>.\n\n<TRANSCRIPT>\nx\n</TRANSCRIPT>", Prompts.cleanMessage("Keep ${'$'} and <b>.", "x"))
    }

    @Test
    fun `clean message handles an empty prompt`() {
        assertEquals("\n\n<TRANSCRIPT>\nx\n</TRANSCRIPT>", Prompts.cleanMessage("", "x"))
    }

    @Test
    fun `clean message does not escape tags inside the transcript`() {
        assertEquals(
            "P\n\n<TRANSCRIPT>\n<TRANSCRIPT>x</TRANSCRIPT>\n</TRANSCRIPT>",
            Prompts.cleanMessage("P", "<TRANSCRIPT>x</TRANSCRIPT>"),
        )
    }

    @Test
    fun `edit message wraps instruction and text`() {
        assertEquals(
            "Apply the instruction to the text. Return only the edited text.\n" +
                "<INSTRUCTION>make it formal</INSTRUCTION>\n" +
                "<TEXT>hi there</TEXT>",
            Prompts.editMessage("make it formal", "hi there"),
        )
    }

    @Test
    fun `edit message handles an empty instruction`() {
        assertEquals(
            "Apply the instruction to the text. Return only the edited text.\n" +
                "<INSTRUCTION></INSTRUCTION>\n" +
                "<TEXT>hi</TEXT>",
            Prompts.editMessage("", "hi"),
        )
    }

    @Test
    fun `edit message preserves multiline selection`() {
        assertEquals(
            "Apply the instruction to the text. Return only the edited text.\n" +
                "<INSTRUCTION>shorten</INSTRUCTION>\n" +
                "<TEXT>one\ntwo</TEXT>",
            Prompts.editMessage("shorten", "one\ntwo"),
        )
    }

    @Test
    fun `edit message handles an empty selection`() {
        assertEquals(
            "Apply the instruction to the text. Return only the edited text.\n" +
                "<INSTRUCTION>do it</INSTRUCTION>\n" +
                "<TEXT></TEXT>",
            Prompts.editMessage("do it", ""),
        )
    }

    @Test
    fun `edit message preserves a multiline instruction`() {
        assertEquals(
            "Apply the instruction to the text. Return only the edited text.\n" +
                "<INSTRUCTION>first\nsecond</INSTRUCTION>\n" +
                "<TEXT>body</TEXT>",
            Prompts.editMessage("first\nsecond", "body"),
        )
    }

    @Test
    fun `edit message preserves quotes in the selection`() {
        assertEquals(
            "Apply the instruction to the text. Return only the edited text.\n" +
                "<INSTRUCTION>keep</INSTRUCTION>\n" +
                "<TEXT>\"quoted\"</TEXT>",
            Prompts.editMessage("keep", "\"quoted\""),
        )
    }

    @Test
    fun `closed think block is removed`() {
        assertEquals("Hello", Prompts.stripModelNoise("<think>x</think>Hello"))
        assertEquals("Hello", Prompts.stripModelNoise("<think>line one\nline two</think>\nHello"))
    }

    @Test
    fun `think block in the middle is removed`() {
        assertEquals("Before After", Prompts.stripModelNoise("Before <think>x</think>After"))
    }

    @Test
    fun `a leading unclosed think section leaves nothing`() {
        assertNull(Prompts.stripModelNoise("<think>still thinking"))
    }

    @Test
    fun `echoed transcript and text tags are removed`() {
        assertEquals("Hello", Prompts.stripModelNoise("<TRANSCRIPT>\nHello\n</TRANSCRIPT>"))
        assertEquals("Hello", Prompts.stripModelNoise("<text>Hello</text>"))
    }

    @Test
    fun `surrounding quotes are removed`() {
        assertEquals("Hello", Prompts.stripModelNoise("\"Hello\""))
        assertEquals("Hello", Prompts.stripModelNoise("'Hello'"))
        assertEquals("Hello", Prompts.stripModelNoise("“Hello”"))
    }

    @Test
    fun `quotes around echoed tags are removed too`() {
        assertEquals("Hello", Prompts.stripModelNoise("\"<TRANSCRIPT>Hello</TRANSCRIPT>\""))
    }

    @Test
    fun `blank output becomes null`() {
        assertNull(Prompts.stripModelNoise(""))
        assertNull(Prompts.stripModelNoise("   \n\t"))
        assertNull(Prompts.stripModelNoise("<TRANSCRIPT>\n</TRANSCRIPT>"))
    }

    @Test
    fun `ordinary text is returned trimmed`() {
        assertEquals("Hello there", Prompts.stripModelNoise("  Hello there\n"))
    }
}
