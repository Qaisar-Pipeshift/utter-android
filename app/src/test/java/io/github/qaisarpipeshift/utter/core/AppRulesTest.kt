package io.github.qaisarpipeshift.utter.core

import io.github.qaisarpipeshift.utter.data.AppRule
import io.github.qaisarpipeshift.utter.data.RuleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRulesTest {

    private val app = "com.example.app"
    private val other = "com.example.other"

    @Test
    fun `rule lookup is by exact package`() {
        val rule = AppRule(app, action = RuleAction.CLEAN, presetId = "formal")
        val rules = listOf(rule)

        assertEquals(rule, AppRules.ruleFor(rules, app))
        assertNull(AppRules.ruleFor(rules, other))
    }

    @Test
    fun `an app without a rule is not blocked`() {
        assertFalse(AppRules.blocks(null))
        assertFalse(AppRules.blocks(AppRule(app)))
        assertTrue(AppRules.blocks(AppRule(app, block = true)))
    }

    @Test
    fun `auto-send comes only from the app rule`() {
        assertFalse(AppRules.autoSend(null))
        assertFalse(AppRules.autoSend(AppRule(app)))
        assertTrue(AppRules.autoSend(AppRule(app, autoSend = true)))
    }

    @Test
    fun `incognito always excludes history even when the app keeps it`() {
        assertFalse(AppRules.historyAllowed(AppRule(app), incognito = true))
        assertFalse(AppRules.historyAllowed(AppRule(app, historyOff = true), incognito = false))
        assertTrue(AppRules.historyAllowed(AppRule(app), incognito = false))
        assertTrue(AppRules.historyAllowed(null, incognito = false))
    }

    @Test
    fun `the mic stays raw by default and for raw rules`() {
        assertEquals(MicTake(TakeAction.RAW, null), AppRules.micTake(null))
        assertEquals(MicTake(TakeAction.RAW, null), AppRules.micTake(AppRule(app)))
        assertEquals(MicTake(TakeAction.RAW, null), AppRules.micTake(AppRule(app, action = RuleAction.RAW)))
    }

    @Test
    fun `a clean rule gives the mic its preset`() {
        val rule = AppRule(app, action = RuleAction.CLEAN, presetId = "formal")

        assertEquals(MicTake(TakeAction.CLEAN, "formal"), AppRules.micTake(rule))
    }

    @Test
    fun `a clean rule without a preset still cleans with the default preset`() {
        assertEquals(
            MicTake(TakeAction.CLEAN, null),
            AppRules.micTake(AppRule(app, action = RuleAction.CLEAN, presetId = null)),
        )
    }
}
