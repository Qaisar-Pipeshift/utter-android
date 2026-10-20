package io.github.qaisarpipeshift.utter.core

/** What kind of text a field expects (docs/spec.md §17 "Smart field types"). */
enum class FieldKind { TEXT, EMAIL_SUBJECT, URI, EMAIL, NUMBER, PHONE, PASSWORD }

/** The editor action to show on the panel's enter key. */
enum class EnterAction { NEWLINE, SEND, SEARCH, GO, NEXT, DONE }

/** Parsed metadata for the field Utter is attached to. Android-free so it can be unit-tested. */
data class FieldInfo(
    val kind: FieldKind,
    val incognito: Boolean,
    val pkg: String,
    val fieldId: Int,
    val actionLabel: EnterAction,
)

/**
 * Maps `EditorInfo` bits to [FieldInfo]. The numeric constants are copied from `android.text.InputType` and
 * `android.view.inputmethod.EditorInfo` (API 37) and documented here, so this stays a plain JVM object and the
 * mapping is testable without an Android runtime.
 */
object FieldInfos {

    // android.text.InputType
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TYPE_CLASS_PHONE = 0x00000003
    private const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    private const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    private const val TYPE_TEXT_VARIATION_URI = 0x00000010
    private const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x00000020
    private const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0x000000d0
    private const val TYPE_TEXT_VARIATION_EMAIL_SUBJECT = 0x00000030
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010

    // android.view.inputmethod.EditorInfo
    private const val IME_MASK_ACTION = 0x000000ff
    private const val IME_ACTION_SEND = 0x00000004
    private const val IME_ACTION_SEARCH = 0x00000003
    private const val IME_ACTION_GO = 0x00000002
    private const val IME_ACTION_NEXT = 0x00000005
    private const val IME_ACTION_DONE = 0x00000006
    private const val IME_FLAG_NO_ENTER_ACTION = 0x40000000
    private const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x10000000

    fun from(inputType: Int, imeOptions: Int, pkg: String?, fieldId: Int): FieldInfo {
        val kind = when (inputType and TYPE_MASK_CLASS) {
            TYPE_CLASS_TEXT -> when (inputType and TYPE_MASK_VARIATION) {
                TYPE_TEXT_VARIATION_PASSWORD,
                TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldKind.PASSWORD

                TYPE_TEXT_VARIATION_URI -> FieldKind.URI
                TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL

                TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> FieldKind.EMAIL_SUBJECT
                else -> FieldKind.TEXT
            }

            TYPE_CLASS_NUMBER ->
                if ((inputType and TYPE_MASK_VARIATION) == TYPE_NUMBER_VARIATION_PASSWORD) {
                    FieldKind.PASSWORD
                } else {
                    FieldKind.NUMBER
                }

            TYPE_CLASS_PHONE -> FieldKind.PHONE
            else -> FieldKind.TEXT
        }
        return FieldInfo(
            kind = kind,
            incognito = (imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING) != 0,
            pkg = pkg ?: "",
            fieldId = fieldId,
            actionLabel = enterAction(imeOptions),
        )
    }

    private fun enterAction(imeOptions: Int): EnterAction {
        if (imeOptions and IME_FLAG_NO_ENTER_ACTION != 0) return EnterAction.NEWLINE
        return when (imeOptions and IME_MASK_ACTION) {
            IME_ACTION_SEND -> EnterAction.SEND
            IME_ACTION_SEARCH -> EnterAction.SEARCH
            IME_ACTION_GO -> EnterAction.GO
            IME_ACTION_NEXT -> EnterAction.NEXT
            IME_ACTION_DONE -> EnterAction.DONE
            else -> EnterAction.NEWLINE
        }
    }
}
