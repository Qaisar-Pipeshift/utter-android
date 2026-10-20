package io.github.qaisarpipeshift.utter.core

/** Pure text helpers used when inserting a result (docs/spec.md §4, §15.2). No Android types: JVM-testable. */
object TextOps {

    /**
     * Prefix for [text] so it does not glue onto the previous word: a space when the character before the cursor
     * is not whitespace and not an opening bracket/quote, and [text] does not start with punctuation that attaches
     * to the left (`.,;:!?)`).
     */
    fun leadingSpace(before: CharSequence?, text: String): String {
        val prev = before?.lastOrNull() ?: return ""
        val first = text.firstOrNull() ?: return ""
        if (prev.isWhitespace() || prev in OPENERS) return ""
        if (first in ATTACH_LEFT) return ""
        return " "
    }

    private const val OPENERS = "([{\"'“‘"
    private const val ATTACH_LEFT = ".,;:!?)]}"
}
