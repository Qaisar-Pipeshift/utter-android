package io.github.qaisarpipeshift.utter.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextOpsTest {
    @Test fun emptyFieldGetsNoSpace() = assertEquals("", TextOps.leadingSpace("", "Hello"))
    @Test fun nullBeforeGetsNoSpace() = assertEquals("", TextOps.leadingSpace(null, "Hello"))
    @Test fun afterWordGetsSpace() = assertEquals(" ", TextOps.leadingSpace("Hi", "there"))
    @Test fun afterSpaceGetsNone() = assertEquals("", TextOps.leadingSpace("Hi ", "there"))
    @Test fun afterNewlineGetsNone() = assertEquals("", TextOps.leadingSpace("Hi\n", "there"))
    @Test fun afterOpenParenGetsNone() = assertEquals("", TextOps.leadingSpace("(", "aside"))
    @Test fun punctuationAttachesLeft() = assertEquals("", TextOps.leadingSpace("Hi", ", there"))
    @Test fun emptyTextGetsNone() = assertEquals("", TextOps.leadingSpace("Hi", ""))

    @Test fun capitalizeAtFieldStart() =
        assertEquals("Hello", TextOps.capitalizeIfSentenceStart(null, "hello"))

    @Test fun capitalizeAfterEmptyBefore() =
        assertEquals("Hello", TextOps.capitalizeIfSentenceStart("", "hello"))

    @Test fun capitalizeAfterPeriod() =
        assertEquals("Hello", TextOps.capitalizeIfSentenceStart("Done. ", "hello"))

    @Test fun capitalizeAfterQuestionMark() =
        assertEquals("Hello", TextOps.capitalizeIfSentenceStart("Why?  ", "hello"))

    @Test fun capitalizeAfterNewline() =
        assertEquals("Hello", TextOps.capitalizeIfSentenceStart("line\n", "hello"))

    @Test fun noCapitalizeMidSentence() =
        assertEquals("hello", TextOps.capitalizeIfSentenceStart("Hi ", "hello"))

    @Test fun capitalizeKeepsExistingCapital() =
        assertEquals("Hello", TextOps.capitalizeIfSentenceStart(null, "Hello"))

    @Test fun capitalizeLeavesLeadingDigit() =
        assertEquals("42 hello", TextOps.capitalizeIfSentenceStart(null, "42 hello"))

    @Test fun capitalizeLeavesEmptyText() =
        assertEquals("", TextOps.capitalizeIfSentenceStart("Done. ", ""))

    @Test fun wordCountOfBlankIsZero() = assertEquals(0, TextOps.wordCount("   "))

    @Test fun wordCountOfEmptyIsZero() = assertEquals(0, TextOps.wordCount(""))

    @Test fun wordCountCountsWords() = assertEquals(3, TextOps.wordCount("one two three"))

    @Test fun wordCountCollapsesWhitespace() = assertEquals(2, TextOps.wordCount("one\n\ttwo "))

    @Test fun wordCountOfOneWordIsOne() = assertEquals(1, TextOps.wordCount("hello"))

    @Test fun wordCountIgnoresExtraSpaces() = assertEquals(2, TextOps.wordCount("one    two"))

    @Test fun wordCountCountsPunctuationAsPartOfWords() = assertEquals(2, TextOps.wordCount("Hi, there!"))
}
