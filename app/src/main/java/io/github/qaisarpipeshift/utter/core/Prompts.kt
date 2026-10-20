package io.github.qaisarpipeshift.utter.core

/**
 * Prompt assembly and model-output cleanup (docs/spec.md §6 cleanup call, docs/plan.md Task 3).
 *
 * The cleanup model is instructed to answer with text only, but real models wrap answers in think blocks, echo the
 * input tags, or quote the whole reply. [stripModelNoise] removes those wrappers so the field receives plain text.
 */
object Prompts {

    /** Wraps [transcript] in the tags the built-in presets refer to. */
    fun cleanMessage(presetPrompt: String, transcript: String): String =
        presetPrompt + "\n\n<TRANSCRIPT>\n" + transcript + "\n</TRANSCRIPT>"

    /** Voice edit: apply a spoken instruction to the selected text. */
    fun editMessage(instruction: String, selection: String): String =
        "Apply the instruction to the text. Return only the edited text.\n" +
            "<INSTRUCTION>" + instruction + "</INSTRUCTION>\n" +
            "<TEXT>" + selection + "</TEXT>"

    /** Removes reasoning blocks, echoed tags and surrounding quotes; returns null when nothing usable remains. */
    fun stripModelNoise(out: String): String? {
        var text = THINK_BLOCK.replace(out, "")
        val unclosed = OPEN_THINK.find(text)
        if (unclosed != null) {
            // An unclosed think section runs to the end of the reply; nothing after it is answer text.
            text = text.substring(0, unclosed.range.first)
        }
        text = TRANSCRIPT_TAG.replace(text, "")
        text = TEXT_TAG.replace(text, "")
        text = text.trim()
        while (text.length >= 2 && isQuoted(text)) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text.ifBlank { null }
    }

    private val THINK_BLOCK = Regex("(?is)<think\\b[^>]*>.*?</think\\s*>")
    private val OPEN_THINK = Regex("(?i)<think\\b[^>]*>")
    private val TRANSCRIPT_TAG = Regex("(?i)</?transcript\\s*>")
    private val TEXT_TAG = Regex("(?i)</?text\\s*>")

    private fun isQuoted(text: String): Boolean {
        val first = text.first()
        val last = text.last()
        return (first == '"' && last == '"') ||
            (first == '\'' && last == '\'') ||
            (first == '“' && last == '”') ||
            (first == '‘' && last == '’')
    }
}
