package io.github.qaisarpipeshift.utter.core

import io.github.qaisarpipeshift.utter.data.AppRule
import io.github.qaisarpipeshift.utter.data.RuleAction

/** What the main mic button starts for a package once its app rule is applied. */
data class MicTake(val action: TakeAction, val presetId: String?)

/**
 * Per-package rule resolution shared by the IME, the controller and the input layer (docs/spec.md §15.3).
 *
 * Why pure functions over the stored [AppRule] list: the same questions — is dictation blocked here, does the main
 * mic clean, does a finished insert send, may this take be stored — are asked at four different edges, and any
 * difference between them is either a privacy bug (a blocked app that still records) or a silent behaviour change
 * (a take cleaned with the wrong preset). One resolver keeps those answers identical and testable on the JVM.
 */
object AppRules {
    /** The rule for [pkg], or null when the user has not configured that app. */
    fun ruleFor(rules: List<AppRule>, pkg: String): AppRule? = rules.firstOrNull { it.pkg == pkg }

    /** True when the app turns dictation off; the panel then shows its calm blocked state and the mic never opens. */
    fun blocks(rule: AppRule?): Boolean = rule?.block == true

    /** True when a finished insert presses the field's action, exactly like the global auto-enter setting. */
    fun autoSend(rule: AppRule?): Boolean = rule?.autoSend == true

    /** History and stats stay off for incognito fields and for apps the user excluded. */
    fun historyAllowed(rule: AppRule?, incognito: Boolean): Boolean = !incognito && rule?.historyOff != true

    /**
     * The main mic button's take: Raw by default, the rule's preset when the app asks for cleaned text.
     *
     * The sparkle button is deliberately not routed through here — ✨ always keeps its own preset sheet, whatever the
     * app rule says, so an app-level default can never take away the explicit choice.
     */
    fun micTake(rule: AppRule?): MicTake = when (rule?.action) {
        RuleAction.CLEAN -> MicTake(TakeAction.CLEAN, rule?.presetId)
        RuleAction.RAW, RuleAction.DEFAULT, null -> MicTake(TakeAction.RAW, null)
    }
}
