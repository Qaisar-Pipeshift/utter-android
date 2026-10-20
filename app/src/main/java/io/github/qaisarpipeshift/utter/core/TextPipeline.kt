package io.github.qaisarpipeshift.utter.core

import io.github.qaisarpipeshift.utter.data.Replacement
import io.github.qaisarpipeshift.utter.data.Settings

/**
 * Pure text normalization between speech recognition and insertion (docs/spec.md §15.2, §17).
 *
 * [raw] turns spoken punctuation and filler words into clean text, then applies the user's replacements.
 * [forField] adapts the result to the target field type (a URL must not get spaces or capitals, a phone number
 * must not get letters). No Android types, so every rule is unit-tested on the JVM.
 */
object TextPipeline {

    fun raw(text: String, s: Settings): String {
        var out = text
        if (s.spokenPunctuation) out = applySpokenPunctuation(out)
        if (s.removeFillers) out = removeFillers(out, s.customFillers)
        out = applyReplacements(out, s.replacements)
        return out.trim()
    }

    fun forField(text: String, kind: FieldKind, s: Settings): String {
        val trimmed = text.trim()
        return when (kind) {
            FieldKind.TEXT, FieldKind.EMAIL_SUBJECT ->
                if (s.capitalize) TextOps.capitalizeIfSentenceStart(null, trimmed) else trimmed

            FieldKind.URI -> trimmed.lowercase().filterNot { it.isWhitespace() }

            FieldKind.EMAIL -> trimmed.lowercase()
                .filterNot { it.isWhitespace() }
                .replace("at", "@")
                .replace("dot", ".")

            FieldKind.NUMBER -> trimmed.filter { it.isDigit() || it == '.' || it == '-' }

            FieldKind.PHONE -> {
                val digits = trimmed.filter { it.isDigit() }
                if (trimmed.startsWith("+")) "+$digits" else digits
            }

            FieldKind.PASSWORD -> trimmed
        }
    }

    private data class SpokenRule(val phrase: String, val symbol: String, val eatSpaceAfter: Boolean = false)

    // Longest phrases first so "exclamation point" is not partially consumed by a shorter rule.
    private val SPOKEN_RULES = listOf(
        SpokenRule("exclamation point", "!"),
        SpokenRule("exclamation mark", "!"),
        SpokenRule("question mark", "?"),
        SpokenRule("new paragraph", "\n\n", eatSpaceAfter = true),
        SpokenRule("close bracket", ")"),
        SpokenRule("open bracket", "(", eatSpaceAfter = true),
        SpokenRule("full stop", "."),
        SpokenRule("semicolon", ";"),
        SpokenRule("new line", "\n", eatSpaceAfter = true),
        SpokenRule("period", "."),
        SpokenRule("colon", ":"),
        SpokenRule("comma", ","),
    )

    private fun applySpokenPunctuation(text: String): String {
        var out = text
        for (rule in SPOKEN_RULES) {
            val trailing = if (rule.eatSpaceAfter) "\\s*" else ""
            val regex = Regex(
                "\\s*\\b" + Regex.escape(rule.phrase) + "\\b" + trailing,
                RegexOption.IGNORE_CASE,
            )
            out = regex.replace(out) { rule.symbol }
        }
        return out
    }

    private val BASE_FILLERS = listOf("um", "umm", "uh", "uhh", "er", "erm", "ah", "hmm")

    private fun removeFillers(text: String, custom: List<String>): String {
        val fillers = (BASE_FILLERS + custom)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .sortedByDescending { it.length }
        if (fillers.isEmpty()) return collapseSpaces(text)

        val alternatives = fillers.joinToString("|") { Regex.escape(it) }
        val regex = Regex(
            "(?<![\\p{L}\\p{N}_])(?:$alternatives)(?![\\p{L}\\p{N}_])\\s*,?\\s*",
            RegexOption.IGNORE_CASE,
        )
        return collapseSpaces(regex.replace(text, ""))
    }

    private fun collapseSpaces(text: String): String {
        var out = text.replace(Regex("[ \\t]{2,}"), " ")
        out = out.replace(Regex(" +([,.;:!?)])")) { match -> match.groupValues[1] }
        return out.trim()
    }

    private fun applyReplacements(text: String, replacements: List<Replacement>): String {
        val usable = replacements
            .filter { it.from.isNotBlank() }
            .sortedByDescending { it.from.length }
        var out = text
        for (replacement in usable) {
            val regex = Regex(
                "(?<![\\p{L}\\p{N}_])" + Regex.escape(replacement.from) + "(?![\\p{L}\\p{N}_])",
                RegexOption.IGNORE_CASE,
            )
            out = regex.replace(out) { replacement.to }
        }
        return out
    }
}
