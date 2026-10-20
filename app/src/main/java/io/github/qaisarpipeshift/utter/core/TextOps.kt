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

    /**
     * Capitalises the first letter of [text] when it starts a sentence: at the start of the field, or when the last
     * non-space character before the cursor is `.`, `!`, `?` or a newline. Everything else is left untouched.
     */
    fun capitalizeIfSentenceStart(before: CharSequence?, text: String): String {
        if (text.isEmpty()) return text
        val previous = lastNonSpace(before)
        if (previous != null && previous !in SENTENCE_END) return text
        return text.replaceFirstChar { first ->
            if (first.isLowerCase()) first.titlecase() else first.toString()
        }
    }

    /** Last character that is not a plain space or tab; a newline still counts as a sentence boundary. */
    private fun lastNonSpace(before: CharSequence?): Char? {
        if (before == null) return null
        var index = before.length - 1
        while (index >= 0 && (before[index] == ' ' || before[index] == '\t' || before[index] == '\r')) {
            index--
        }
        return if (index >= 0) before[index] else null
    }

    /** Number of whitespace-separated words; blank text has zero. */
    fun wordCount(text: String): Int {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) 0 else trimmed.split(Regex("\\s+")).size
    }

    private const val OPENERS = "([{\"'“‘"
    private const val ATTACH_LEFT = ".,;:!?)]}"
    private const val SENTENCE_END = ".!?\n"
}
