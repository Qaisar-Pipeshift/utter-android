# Utter — decision brief (v0.1.3, 2026-10-20)

## What was built

Utter is an Android voice keyboard (input method) for a self-hosted dictation server. From Gboard or Samsung
Keyboard you switch to Utter, speak, see live text, and the result lands in the field before Utter hands back to your
keyboard. Optional LLM clean-up (presets), voice edit of a selection, history with retry, insights, per-app rules, a
notch Live Update while recording, process-text, voice-note transcription, read aloud and a Quick Settings tile. Signed
APKs are built and published by CI on every push to `main`; nothing deployment-specific is in the repo.

```
 dev push ─► CI gates (gitleaks · hygiene · manifest · battery · resources · kotlin comments)
          ─► lint ─► 260+ JVM tests ─► debug + release builds
 main push ─► same gates ─► signed release (apksigner verified) ─► GitHub release vX.Y.Z (+ sha256)
```

| Fact | Value |
|---|---|
| Release | v0.1.3 · `utter-0.1.3.apk` 3.68 MB · sha256 `eb923b2a…1b06efb0` (full value on the release page) |
| Signing cert | CN=Utter · SHA-256 `1a3d82ea…e364ebbc` |
| Permissions | INTERNET · ACCESS_NETWORK_STATE · RECORD_AUDIO · POST_NOTIFICATIONS · POST_PROMOTED_NOTIFICATIONS |
| Background work | none (IME + QS tile only bound while visible; gate-enforced) |

## Decisions

| Choice | Why | Rejected |
|---|---|---|
| Build Utter (own app) | Whisper++ trial failed: keyboard recordings never reached the server, live mode incompatible (server_vad), no back-swipe, heavy | Fork Whisper++; Voice Keyboard; Whisper To Input (`response_format=text` trap) |
| Voice IME, switching-aware, non-auxiliary subtype | Gboard's 🌐 rotates only through switching-aware IMEs (AOSP) | Accessibility bubble (battery, restricted settings) |
| Live = server `/v1/realtime` with `turn_detection: null` + commit | Same protocol Handy Remote uses; finals are offline-quality | Client server_vad |
| Upload fallback with the full take on any live failure | Never lose a dictation | Fail the take |
| Pending take file deleted only after insert / no-speech / explicit discard | Never lose a dictation | In-memory takes |
| API key AES-GCM in AndroidKeyStore, decrypted value cached in memory | No main-thread Keystore per take | EncryptedSharedPreferences (deprecated) |
| Spoken "period" not converted | It is an ordinary word far more often | Convert |
| ACCESS_NETWORK_STATE added (5th permission) | Offline chip at record start; normal permission, no battery cost | Treat offline as unreachable |
| GPL-3.0 | Allows borrowing from Whisper++ | MIT |
| CI as the only compiler; worker never pushes (pre-push guard) | Host is read-only; public repo needs review before push | Local SDK install |

## Verification

| Check | Result |
|---|---|
| CI on `2e6525b` | gates 0 findings · lint clean · unit tests pass · release APK built |
| Release run | signed, `apksigner verify` OK, fingerprint matches the keystore |
| Independent review (Sonnet, whole app) | 11 defects found (1 critical: mic left open when stop raced start) → all fixed + 1 race found by CI (live terminal event) |
| Contract test vs live server | written, **skipped** (needs `UTTER_TEST_URL` / `UTTER_TEST_KEY` secrets) |
| On-device | **not yet** — first device test is Qais's install |

## Risks / assumptions

- Recording from inside an IME on OxygenOS 16 and One UI is assumed to work (FUTO Voice Input does it); first device check.
- Gboard 🌐 one-tap reach depends on Gboard build; hold-space / nav-bar picker are fallbacks.
- OnePlus Live Alerts for third-party apps are off per app until enabled; Samsung Now Bar support depends on One UI 8.
- Platform-edge classes (InputOps, PcmPlayer, SecretStore, QuickRecorderActivity) have no JVM tests; covered by review + device checks.

## Deviations from the spec (declared)

| Deviation | Reason |
|---|---|
| 5 permissions, not 4 | ACCESS_NETWORK_STATE for the offline check |
| Cleanup prompts use appended `<TRANSCRIPT>` tags, no `${output}` | One wrapper for every preset |
| Capitalization applied at insert time with cursor context, not in the text pipeline | Needs the text before the cursor |
| Read aloud from the panel stops when the keyboard hides | Its only Stop control disappears with the panel |
| Settings import keeps the current server unless the user explicitly picks the imported host | A backup must not silently redirect the API key |
| Contract test only runs when secrets are configured | Key stays off GitHub until Qais decides |
