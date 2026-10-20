package io.github.qaisarpipeshift.utter.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.github.qaisarpipeshift.utter.core.EnterAction
import io.github.qaisarpipeshift.utter.core.FieldInfos
import io.github.qaisarpipeshift.utter.core.FieldKind
import io.github.qaisarpipeshift.utter.core.InsertMeta
import io.github.qaisarpipeshift.utter.core.Inserter
import io.github.qaisarpipeshift.utter.core.TextOps
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.diag.Diag

/**
 * Everything the keyboard does to the field (docs/spec.md §4, §15.2, §17; docs/plan.md Task 9).
 *
 * Why one class between the controller and the `InputConnection`: the controller decides *what* to insert, this
 * decides *how* it lands — smart spacing, sentence-start capitalisation, trailing space, auto-enter, the clipboard
 * copy and the undo/swap match. The controller never sees an Android type, and every rule here has exactly one
 * place to read.
 *
 * Field safety: an insert is only committed when the current editor is still the take's field. A closed field, an
 * app switch or a field-type change (especially into a password field) copies the text instead, so a dictation can
 * never land in the wrong place. A voice edit commits only while the same selection is still selected.
 */
class InputOps(
    private val ime: InputMethodService,
    private val settings: () -> Settings,
    private val diag: Diag,
    private val onInserted: () -> Unit = {},
) : Inserter {

    /** The last committed insert, for undo and swap; only a text that is still exactly before the cursor can match. */
    private data class Last(val text: String, val pkg: String, val fieldId: Int)

    private var last: Last? = null

    override fun insert(text: String, meta: InsertMeta): Boolean {
        val connection = ime.currentInputConnection
        val editor = ime.currentInputEditorInfo
        if (connection == null || editor == null || !sameField(meta, editor)) {
            copy(text)
            diag.event("field_closed")
            return false
        }
        val snapshot = settings()
        if (meta.replaceSelection) return replaceSelection(connection, text, meta, snapshot)

        val field = fieldOf(editor)
        val before = connection.getTextBeforeCursor(BEFORE_LIMIT, 0)
        var out = text
        if (spacesAllowed(field.kind)) {
            // Capitalise before adding the leading space: the helper looks at the text's first character, which the
            // space would otherwise be. The space itself depends on the unchanged character before the cursor.
            if (snapshot.capitalize) out = TextOps.capitalizeIfSentenceStart(before, out)
            if (snapshot.smartSpacing) out = TextOps.leadingSpace(before, out) + out
            if (snapshot.trailingSpace && out.isNotEmpty() && !out.last().isWhitespace()) out += " "
        }
        if (!commit(connection, out)) {
            copy(text)
            diag.event("commit_failed")
            return false
        }
        last = Last(out, field.pkg, field.fieldId)
        if (snapshot.copyToClipboard) copy(text)
        if (snapshot.autoEnter) enter(meta.field.actionLabel)
        onInserted()
        return true
    }

    /**
     * A voice edit replaces the selection only when it is still the exact text the user spoke over. Any other state —
     * a collapsed cursor, a new selection, a different field — would overwrite text the user did not ask to touch.
     */
    private fun replaceSelection(
        connection: InputConnection,
        text: String,
        meta: InsertMeta,
        snapshot: Settings,
    ): Boolean {
        val selected = connection.getSelectedText(0)?.toString()
        if (meta.selection == null || selected != meta.selection) {
            copy(text)
            diag.event("selection_lost")
            return false
        }
        if (!commit(connection, text)) {
            copy(text)
            diag.event("commit_failed")
            return false
        }
        // A replaced selection has no "previous text" to restore; undo stays unavailable for this insert.
        last = null
        if (snapshot.copyToClipboard) copy(text)
        if (snapshot.autoEnter) enter(meta.field.actionLabel)
        onInserted()
        return true
    }

    override fun preview(text: String) {
        ime.currentInputConnection?.setComposingText(text, 1)
    }

    override fun clearPreview() {
        val connection = ime.currentInputConnection ?: return
        connection.setComposingText("", 1)
        connection.finishComposingText()
    }

    override fun undo(): Boolean {
        val connection = ime.currentInputConnection ?: return false
        val previous = last ?: return false
        if (connection.getTextBeforeCursor(previous.text.length, 0)?.toString() != previous.text) return false
        connection.beginBatchEdit()
        val deleted = try {
            connection.deleteSurroundingText(previous.text.length, 0)
        } finally {
            connection.endBatchEdit()
        }
        if (deleted) last = null
        return deleted
    }

    override fun swap(text: String, meta: InsertMeta): Boolean {
        val connection = ime.currentInputConnection ?: return false
        val editor = ime.currentInputEditorInfo ?: return false
        val previous = last ?: return false
        if (!sameField(meta, editor)) return false
        if (connection.getTextBeforeCursor(previous.text.length, 0)?.toString() != previous.text) return false
        connection.beginBatchEdit()
        val swapped = try {
            connection.deleteSurroundingText(previous.text.length, 0) && connection.commitText(text, 1)
        } finally {
            connection.endBatchEdit()
        }
        if (swapped) last = Last(text, previous.pkg, previous.fieldId)
        return swapped
    }

    /** Types one space on the panel's space key. */
    fun space() {
        ime.currentInputConnection?.commitText(" ", 1)
    }

    /** Runs the field's editor action (send / search / go / next / done), or a newline when it has none. */
    fun enter(action: EnterAction) {
        val connection = ime.currentInputConnection ?: return
        when (action) {
            EnterAction.NEWLINE -> connection.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            EnterAction.SEND -> connection.performEditorAction(EditorInfo.IME_ACTION_SEND)
            EnterAction.SEARCH -> connection.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
            EnterAction.GO -> connection.performEditorAction(EditorInfo.IME_ACTION_GO)
            EnterAction.NEXT -> connection.performEditorAction(EditorInfo.IME_ACTION_NEXT)
            EnterAction.DONE -> connection.performEditorAction(EditorInfo.IME_ACTION_DONE)
        }
    }

    /** Deletes one character; the panel repeats this while backspace is held. */
    fun backspace() {
        ime.currentInputConnection?.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    /** Deletes back to the start of the previous word (map for a future long-press-backspace gesture). */
    fun deleteWord() {
        val connection = ime.currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(WORD_LOOKBEHIND, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        var end = before.length
        while (end > 0 && before[end - 1].isWhitespace()) end--
        while (end > 0 && !before[end - 1].isWhitespace()) end--
        val count = before.length - end
        if (count <= 0) return
        connection.beginBatchEdit()
        try {
            connection.deleteSurroundingText(count, 0)
        } finally {
            connection.endBatchEdit()
        }
    }

    /** The text selected in the field right now, or null for a collapsed cursor; feeds the voice-edit gesture. */
    fun selection(): String? =
        ime.currentInputConnection?.getSelectedText(0)?.toString()?.takeIf { it.isNotEmpty() }

    // ---- helpers ----

    private fun commit(connection: InputConnection, text: String): Boolean {
        connection.beginBatchEdit()
        return try {
            connection.commitText(text, 1)
        } finally {
            connection.endBatchEdit()
        }
    }

    private fun fieldOf(editor: EditorInfo) = FieldInfos.from(
        editor.inputType,
        editor.imeOptions,
        editor.packageName,
        editor.fieldId,
    )

    /**
     * Whether the editor still belongs to the take: same package and, when both sides know it, the same field id.
     * A password field never accepts a dictation, even when the ids are unknown.
     */
    private fun sameField(meta: InsertMeta, editor: EditorInfo): Boolean {
        val current = fieldOf(editor)
        if (current.kind == FieldKind.PASSWORD) return false
        if (meta.field.pkg.isNotEmpty() && current.pkg.isNotEmpty() && meta.field.pkg != current.pkg) return false
        if (meta.field.fieldId >= 0 && current.fieldId >= 0 && meta.field.fieldId != current.fieldId) return false
        return true
    }

    /** Spaces and capitals belong to prose; a URL, an email address, a number or a phone number must stay exact. */
    private fun spacesAllowed(kind: FieldKind): Boolean = when (kind) {
        FieldKind.TEXT, FieldKind.EMAIL_SUBJECT -> true
        FieldKind.URI, FieldKind.EMAIL, FieldKind.NUMBER, FieldKind.PHONE, FieldKind.PASSWORD -> false
    }

    private fun copy(text: String) {
        val manager = ime.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
    }

    private companion object {
        const val BEFORE_LIMIT = 64
        const val WORD_LOOKBEHIND = 64
        const val CLIP_LABEL = "Utter"
    }
}
