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
}
