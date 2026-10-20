package io.github.qaisarpipeshift.utter.core

/** What the user asked the take to do (docs/plan.md §2). */
enum class TakeAction { RAW, CLEAN, EDIT_SELECTION }

/**
 * One dictation request as the panel builds it (docs/plan.md §2).
 *
 * [presetId] selects the cleanup preset for [TakeAction.CLEAN] (null = the user's default); [selection] carries the
 * text selected in the field for [TakeAction.EDIT_SELECTION]. The field is captured here, not read later, because
 * the input connection can change while the user is speaking.
 */
data class TakeRequest(
    val action: TakeAction,
    val presetId: String?,
    val field: FieldInfo,
    val selection: String?,
)
