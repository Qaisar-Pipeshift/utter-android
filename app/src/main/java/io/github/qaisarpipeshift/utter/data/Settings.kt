package io.github.qaisarpipeshift.utter.data

import io.github.qaisarpipeshift.utter.core.Preset
import io.github.qaisarpipeshift.utter.core.Presets

/** Theme choice; [SYSTEM] follows the phone. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Handy's pink palette, or Material You dynamic colour on Android 12+. */
enum class Palette { HANDY, DYNAMIC }

/** Panel height presets (docs/spec.md §4; dp values live in the panel UI). */
enum class PanelHeight { COMPACT, NORMAL, TALL }

/** Per-app default action for a dictation started in that package. */
enum class RuleAction { DEFAULT, RAW, CLEAN }

/** A whole-word, case-insensitive find → replace applied after STT and before cleanup. */
data class Replacement(val from: String, val to: String)

/** Per-package behaviour (docs/spec.md §15.3). Packages appear here only after Utter was used in them. */
data class AppRule(
    val pkg: String,
    val action: RuleAction = RuleAction.DEFAULT,
    val presetId: String? = null,
    val autoSend: Boolean = false,
    val block: Boolean = false,
    val historyOff: Boolean = false,
)

/**
 * Every user setting (docs/spec.md §7, §15). Immutable and Android-free so it round-trips through [SettingsJson]
 * and is safe to hand to the IME's synchronous reads.
 *
 * The API key is deliberately absent: it lives encrypted in [SecretStore], never in this model or its JSON.
 */
data class Settings(
    val baseUrl: String = "",
    val tapHandsFree: Boolean = true,
    val holdThresholdMs: Int = 300,
    val slideGestures: Boolean = true,
    val live: Boolean = true,
    val liveInField: Boolean = false,
    val autoStart: Boolean = false,
    val duckAudio: Boolean = true,
    val sounds: Boolean = false,
    val tailMs: Int = 0,
    val micId: Int? = null,
    val haptics: Boolean = true,
    val speculativeClean: Boolean = false,
    val smartSpacing: Boolean = true,
    val trailingSpace: Boolean = false,
    val capitalize: Boolean = true,
    val removeFillers: Boolean = true,
    val customFillers: List<String> = emptyList(),
    val spokenPunctuation: Boolean = true,
    val replacements: List<Replacement> = emptyList(),
    val vocabulary: List<String> = emptyList(),
    val presets: List<Preset> = Presets.defaults,
    val defaultPresetId: String = Presets.CLEAN_ID,
    val cleanModel: String = "",
    val cleanTimeoutMs: Long = 15_000L,
    val voiceEdit: Boolean = true,
    val autoEnter: Boolean = false,
    val copyToClipboard: Boolean = false,
    val returnToPrevious: Boolean = true,
    val appRules: List<AppRule> = emptyList(),
    val seenApps: Map<String, String> = emptyMap(),
    val historyOn: Boolean = true,
    val historyLimit: Int = 200,
    val keepAudioDays: Int = 0,
    val notch: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val palette: Palette = Palette.HANDY,
    val panelHeight: PanelHeight = PanelHeight.NORMAL,
    val ttsVoice: String = "aiden",
    val onboardingDone: Boolean = false,
    val prewarm: Boolean = true,
)
