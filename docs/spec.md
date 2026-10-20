# Utter — spec

Android voice keyboard for a self-hosted dictation server. Handy's idea, on a phone: speak, text lands in the field.

| | |
|---|---|
| Status | Draft for review — 2026-10-20 |
| App / package | **Utter** · `io.github.qaisarpipeshift.utter` |
| Repo | public `Qaisar-Pipeshift/utter-android` |
| Android | min 9 (API 28) · target = latest stable SDK in CI |
| Server | existing dictation-api — **no server changes** |
| Daily keyboard | **Gboard** — Utter must work seamlessly next to it |
| Target phones | OnePlus 15 (OxygenOS 16, Android 16) · a Samsung Galaxy (One UI, Samsung Keyboard or Gboard) |

## 0 · Prior art (checked 2026-10-20) — and the decision rule

| App | License · ★ · last push | Live text | LLM cleanup | Gboard 🌐 flag | Fit |
|---|---|---|---|---|---|
| **Whisper++** `shekohex/whisperpp` | GPL-3.0 · 9 · 2026-10-15 | ✅ OpenAI Realtime at `wss://<base>/v1/realtime?…&intent=transcription`, GA format, falls back to upload | ✅ OpenAI-compatible | ✅ `supportsSwitchingToNextInputMethod="true"` | **closest**: 5.7 MB, Keystore keys, history, music pause; 12-h WorkManager update check; no Live Update |
| Voice Keyboard `rustemar/voice-keyboard` (F-Droid) | MIT · 20 · 2026-10-15 | ❌ (waveform only) | ✅ | ✅ (voice subtype) | upload-only |
| Whisper To Input `j3soon/whisper-to-input` | GPL-3.0 · 140 · 2025-12-21 | ❌ | ❌ | ✅ | sends `response_format=text` → our vLLM route answers JSON (known Hermes trap) |
| voiceflow-keyboard `yutungh/…` | MIT · 2 · 2026-08-30 | ❌ | ✅ | ❌ flag absent → Gboard 🌐 skips it | no |

**Rule:** Qais trials Whisper++ against the server first (D1–D9 in §12).
Pass → **fork Whisper++** and add only the deltas this spec owns (§3b notch Live Update, §8 server-specific failure handling).
Fail → **build Utter** from this spec.

**Trial result 2026-10-20 (OnePlus 15, Whisper++ nightly `bd0a8f8`) → FAIL → build Utter:**

| Finding | Evidence |
|---|---|
| Every dictation from the keyboard ends in "Recording error" (`history_reason_recorder`); Retry from the app works | ingress log: only the app-side retries reached the server, all HTTP 200 — keyboard attempts never left the phone |
| Live mode cannot work with this server unpatched | client always requests `server_vad`; on stop it commits only after a `speech_started` event this server never sends → sends `clear`, drops the take, waits for timeout |
| Swipe-back gesture ignored in its settings screens; only the on-screen back button works | Qais, device |
| Heavy for the use case: 13 providers, modes, insights, 12-h update worker, self-update permission | manifest + source |

## 1 · Goal and success criteria

| # | Criterion | Measured by |
|---|---|---|
| G1 | Gboard → Utter → text in field → back to Gboard in ≤ 3 taps | device check D1–D3 |
| G2 | Battery: Utter < 1 % per day, 0 min background use | Android Settings → Battery, after 24 h |
| G3 | Never loses a dictation (network drop, server down, field closed) | failure table §8, device checks D6–D8 |
| G4 | Nothing secret or deployment-specific in the repo | CI gates §10 |
| G5 | Thin: APK ≤ 5 MB (build path; fork path ≈ 5.7 MB accepted), 4 permissions | CI artifact size, manifest gate |
| G6 | Clean, Handy-like look | §9 tokens, Qais's eye |
| G7 | Recording status in the notch: OnePlus Live Alerts capsule / Samsung Now Bar | device checks D11–D12 |
| G8 | Same experience on Samsung (Samsung Keyboard or Gboard) | device check D12 |

**Not in v1 (YAGNI):** on-device model · accounts · analytics · translations (English UI only) · in-app updater · custom words / keyterms · "clean while I pause" · Play Store.

## 2 · How it works

```
 Any app's text field (Gboard showing)
   │  tap 🌐 on Gboard   (or hold space → pick Utter)
   ▼
 Utter panel (InputMethodService)
   │  tap 🎙 (raw)  or  ✨ (cleaned)
   ▼
 Recorder: AudioRecord 16 kHz mono PCM16, 100 ms chunks ──► full take kept in RAM (≤ 295 s ≈ 9.4 MB)
   │
   ├─ Live ON  : WSS  /v1/realtime ─ preview text while speaking ─ commit at stop → final
   │             └─ any failure → POST /v1/transcribe/live with the full take
   ├─ Live OFF : POST /v1/transcribe/live once at stop
   └─ ✨ only  : POST /v1/chat/completions (cleanup prompt) on the final text
   ▼
 commitText() into the field ──► switchToPreviousInputMethod() → Gboard again
```

## 3 · Gboard integration (the part that makes it seamless)

| Requirement | Why (evidence) |
|---|---|
| `method.xml`: `android:supportsSwitchingToNextInputMethod="true"` | Gboard is switching-aware; AOSP `InputMethodSubtypeSwitchingController` rotates a switching-aware IME only through **other switching-aware IMEs** (`mSwitchingAwareRotationList`), so without this flag Gboard's 🌐 skips Utter (verified in AOSP main, 2026-10-20) |
| One subtype, `imeSubtypeMode="voice"`, `isAuxiliary="false"` | Auxiliary subtypes are excluded from the rotation list (`!subtype.isAuxiliary()` in `getSortedInputMethodAndSubtypeList`) |
| After insert: `switchToPreviousInputMethod()` (setting, default on) | Returns to Gboard with no tap. `public final boolean`, present in AOSP `InputMethodService` |
| 🌐 key on the Utter panel: tap = `switchToPreviousInputMethod()`, long-press = `InputMethodManager.showInputMethodPicker()` | Manual way back / to any keyboard |
| Setup step tells Qais: Gboard shows 🌐 once a second keyboard is enabled; hold-space is the fallback | Android 17 adds a Gboard option to hide 🌐 (Android Authority) |

Gboard's own mic key stays Google's (hardcoded; FUTO lists Gboard as incompatible) — the 🌐 path is the way in.

**Samsung Keyboard** has no 🌐 / hold-space switcher. Way in = navigation-bar keyboard button
(Settings → General management → Keyboard list and default → *Keyboard button on navigation bar*) → pick Utter.
Way back = the same auto-return (`switchToPreviousInputMethod()` works for any previous IME). Samsung + Gboard = same as §3.
Install on Samsung: **Auto Blocker** (Settings → Security and privacy → Auto Blocker) blocks sideloaded APKs on One UI 6.1.1+ — turn it off for the install. Setup wizard shows the right tip per keyboard (detected from the previous IME's package).

## 3b · Notch status — Android 16 Live Update

```
 tap 🎙 ─► post ongoing notif ─► OnePlus: Live Alerts capsule around the camera  ● 0:12
                                 Samsung: Now Bar / status-bar chip               ● 0:12
 stop   ─► same notif → "Transcribing…" ─► inserted / failed ─► notif cancelled (Failed: "Tap to retry" 10 s)
```

| Item | Value |
|---|---|
| API | `NotificationCompat.Builder.requestPromotedOngoing(true)`; ongoing; content title set; Standard style; `setUsesChronometer(true)` + `setWhen(start)`; no custom view; not a group summary |
| Channel | "Recording status", importance DEFAULT, no sound, no vibration |
| Permissions | `POST_NOTIFICATIONS` (runtime, wizard asks) · `POST_PROMOTED_NOTIFICATIONS` (manifest) |
| Lifetime | only Recording → Finishing (+10 s on Failed); **zero cost when idle** |
| Android < 16 / not promoted | plain ongoing notification — same content, no capsule |
| OnePlus | third-party Live Alerts are **off per app by default**: Settings → Notifications & Quick Settings → Live Alerts → Utter on (wizard deep-links + tip) |
| Samsung | One UI 8 shows Live Updates from third-party apps in the Now Bar; older One UI → plain notification |
| Setting | "Notch status" — default On |
| Status-bar chip text (`setShortCriticalText`) | use only if present in the compile SDK (unconfirmed in docs) |
| Tap action | opens nothing (`setContentIntent` = null) — the panel is already on screen |

## 4 · Panel

```
┌──────────────────────────────────────────────────────┐
│  status / live text strip              [ Retry ]     │  ← 1–2 lines, scrolls to the end
│                                                      │
│      [ ⌫ ]           (   🎙   )           [ ✨ ]      │  ← 🎙 raw · ✨ cleaned · ⌫ hold = repeat
│                                                      │
│  [ 🌐 ]  [ ⚙ ]   [      space      ]  [ ↶ ]  [ ⏎ ]   │  ← ↶ undo last insert
└──────────────────────────────────────────────────────┘
   height ≈ 220 dp · waveform ring around 🎙 only while recording
```

State machine (one dictation at a time):

```
            tap 🎙/✨  (or panel opens with Auto-start on)
   Idle ───────────────────────────────► Recording ──tap again / hold released / 295 s──► Finishing
    ▲                                       │ ⌫ while recording = cancel (discard)             │
    │                                       ▼                                                 │ ok
    ├────────────────────────────────── Idle                                                  ▼
    │                                                                         Inserted ──► auto-return (setting)
    │   Retry tapped (same take)                                                  
    └──── Failed (take kept in RAM, Retry chip) ◄──────────── fail ───────────────┘
```

| Panel event | Behavior |
|---|---|
| Panel hidden while Recording (`onFinishInputView`) | Mic released at once. Take finishes. Same field back (package + fieldId) → insert; else → clipboard + history + toast "Copied — field was closed" |
| New take while Failed | Old take dropped (only one kept) |
| Insert | `commitText(text)`; smart spacing adds a leading space if the char before the cursor is not whitespace/start |
| ↶ Undo | Deletes the last insert only if `getTextBeforeCursor(len)` still equals it; else greyed out |
| ⏎ | `performEditorAction` for the field's action (send/search/go), else newline |
| Auto-Enter (setting) | ⏎ after insert |

## 5 · Components

| File (`app/src/main/java/io/github/qaisarpipeshift/utter/`) | Does | Depends on |
|---|---|---|
| `ime/UtterIme.kt` | `InputMethodService`: lifecycle, Compose host (sets ViewTree lifecycle/savedState owners), insert, undo, switch back | core, ui |
| `ime/Panel.kt` | Compose panel UI | core state |
| `core/Dictation.kt` | State machine §4; live → upload fallback; retry; one coroutine scope per service, cancelled in `onDestroy` | audio, net |
| `core/TextOps.kt` | Smart spacing, undo match, cleanup prompt fill — pure functions | — |
| `audio/Recorder.kt` | `AudioRecord` (`VOICE_RECOGNITION` source), 16 kHz mono PCM16, 1600-sample chunks → bounded channel (50 chunks) + full-take buffer; released on stop/cancel/hide | — |
| `net/Http.kt` | One OkHttp client: connect 5 s, read 60 s, pool 1 idle conn × 30 s, WS ping 10 s (only while open) | OkHttp |
| `net/Upload.kt` | `POST /v1/transcribe/live` | Http |
| `net/Live.kt` | `WSS /v1/realtime` | Http |
| `net/Cleanup.kt` | `POST /v1/chat/completions` | Http |
| `net/Check.kt` | `GET /v2/transcript` (key check), `GET /warm` | Http |
| `notify/LiveStatus.kt` | §3b Live Update notification: post / update / cancel | core state |
| `data/Settings.kt` | SharedPreferences + defaults §7 | — |
| `data/SecretStore.kt` | API key: AES-256-GCM, key in AndroidKeyStore (non-exportable); ciphertext + IV in prefs | — |
| `data/History.kt` | Last 50 texts, JSON in `filesDir`, atomic write | — |
| `ui/MainActivity.kt` | First-run setup wizard + settings screens | data, net |
| `ui/Theme.kt` | §9 tokens, light/dark | — |

Deps allowed: AndroidX core, activity-compose, Compose BOM (material3), lifecycle, OkHttp, kotlinx-coroutines, kotlinx-serialization-json. Anything else → ask first.

## 6 · Wire protocol (exact — matches the Handy fork and the server code)

All requests: header `Authorization: <key>` (the ingress accepts the bare key or `Bearer <key>`). Base URL = Settings value, trailing `/` stripped.

| Call | Request | Response handling |
|---|---|---|
| Key check | `GET /v2/transcript` | 200 → "Connected, key accepted" · 401 → "Key rejected" · else → "Server error HTTP n" · IO → "Can't reach server" |
| Pre-warm | `GET /warm` (no auth) at record start, upload mode only, fire-and-forget | ignore result |
| Upload | `POST /v1/transcribe/live` multipart: part `config` (`application/json`) `{"sample_rate":16000,"channels":1,"llm":null}` then part `audio` (`audio/pcm`, filename `audio.pcm`, raw S16LE mono) | JSON `{text, llm_error, …}`: `llm_error=="no speech"` → nothing inserted, "No speech" · `llm_error=="speech-to-text unavailable"` → **Failed** (never insert `text`, it is a placeholder) · non-2xx → Failed · else insert `text` |
| Live | `wss://<base>/v1/realtime?intent=transcription` | see below |
| Cleanup | `POST /v1/chat/completions` `{"model":"cleanup","messages":[{"role":"user","content":<prompt with ${output} replaced>}]}` — no temperature (server defaults 0), no stream | `choices[0].message.content` trimmed; empty or any failure → insert raw + hint "not cleaned"; timeout 15 s |

Live socket sequence:

```
client → {"type":"session.update","session":{"type":"transcription","audio":{"input":{
           "format":{"type":"audio/pcm","rate":16000},"transcription":{"model":"qwen3-asr"},"turn_detection":null}}}}
client → {"type":"input_audio_buffer.append","audio":"<base64 S16LE, 100 ms>"}   … every chunk
server → {"type":"transcription.preview","committed":"…","tentative":"…"}         → live strip / composing text
client → {"type":"input_audio_buffer.commit"}                                     at stop
server → {"type":"conversation.item.input_audio_transcription.completed","transcript":"…"}  → final, close socket
server → {"type":"error","error":{"message":"…buffer too small…"}} after commit    → no speech (empty)
any other error / close / 20 s without final / send backlog > 50 chunks           → upload fallback with full take
```

Connect failure at start → keep recording locally; upload at stop (no preview).

## 7 · Settings

| Setting | Default | Notes |
|---|---|---|
| Server URL | *(empty)* | must start `https://`; cleartext blocked by network security config |
| API key | *(empty)* | masked + "show"; SecretStore; never logged |
| Test connection | button | key check §6 |
| Mic mode | Tap to start / stop | or Hold to talk |
| Live dictation | On | Off = one upload at stop |
| Live text shown in | Panel strip | or Field (underlined composing text, replaced by final) |
| Auto-start on open | Off | panel starts recording when shown |
| Cleanup prompt | Handy's default "Improve Transcriptions" prompt (MIT, attributed) | editable, must contain `${output}`, Reset button |
| Return to previous keyboard after insert | On | |
| Smart spacing | On | |
| Auto-Enter after insert | Off | |
| Haptics | On | `performHapticFeedback` (no VIBRATE permission) · **no sounds** |
| Notch status | On | §3b Live Update while recording |
| Pre-warm connection | On | `GET /warm` at record start (upload mode) |
| History | On (50) | list, tap = copy, Clear all |
| Theme | System | Light / Dark |
| About | — | version, "Open releases" (browser), licenses |

First-run wizard: ① Server URL + key + Test → ② Mic permission → ③ Notification permission (skippable) → ④ Enable Utter (`ACTION_INPUT_METHOD_SETTINGS`) → ⑤ Try it (test field + picker button) → ⑥ Tips for this phone: Gboard 🌐 / hold space · Samsung Keyboard nav-bar button · OnePlus Live Alerts toggle.

## 8 · Failure handling

| Failure | User sees | Take |
|---|---|---|
| No network / connect > 5 s | "Can't reach server" + **Retry** | kept |
| Live socket drops / errors / stalls | nothing (silent upload fallback) | uploaded |
| Server STT down (`speech-to-text unavailable`) | "Speech-to-text unavailable" + **Retry** | kept |
| Cleanup fails / empty | raw text inserted + "not cleaned" | done |
| 401 | "Key rejected" + Settings button | kept |
| No speech | "No speech" (1.5 s) | dropped |
| 295 s reached | auto-stop, then normal finish (server cap 300 s) | — |
| Mic permission missing | panel shows "Grant mic" → opens wizard step ② | — |
| Field closed mid-take | clipboard + history + toast | done |
| Server/URL not set | panel shows "Set up Utter" → wizard | — |

## 9 · Look and feel (Handy tokens)

| Token | Light | Dark |
|---|---|---|
| background | `#fbfbfb` | `#2c2b29` |
| text | `#0f0f0f` | `#fbfbfb` |
| accent (mic, active) | `#da5893` | `#da5893` |
| accent soft (ring, chips) | `#faa2ca` | `#f28cbb` |
| muted text | `#6e6e6e` | `#a3a09a` |
| warning | `#d97706` | `#fbbf24` |
| error | `#dc2626` | `#f87171` |

Flat, rounded (16 dp), one accent, no gradients, no idle animation; waveform ring only while recording.

## 10 · Public-repo, security and battery rules — declared rule → CI gate

| Rule | Gate (fails the build) |
|---|---|
| No secrets in tree or history | `gitleaks` (full history) |
| No deployment-specific strings (company domains, IPs, key-like hex ≥ 32) | `scripts/check_public_hygiene.sh` (grep allow-list) |
| Permissions = `INTERNET`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `POST_PROMOTED_NOTIFICATIONS` only | `scripts/check_manifest.sh` |
| No background work: no `Service` except the IME, no `WakeLock`, `WorkManager`, `AlarmManager`, `JobScheduler`, `foregroundServiceType`, `BOOT_COMPLETED`, `Timer`/polling loops | `scripts/check_battery_rules.sh` (grep) |
| Key never leaves the app: `allowBackup=false` + data-extraction rules exclude everything; no `Log` of key/text/audio; R8 strips `Log.d/v/i` in release | manifest gate + grep + proguard rule |
| HTTPS only | network security config `cleartextTrafficPermitted=false` |
| Supply chain: GitHub Actions pinned by commit SHA; signing secrets only in the release job; PRs from forks never see secrets | workflow review |

Runtime battery rules: mic open only Recording; socket and notch notification only Recording → Finishing; one idle HTTP conn ≤ 30 s; no work while the panel is hidden except finishing a take (bounded by timeouts). Fork path: Whisper++'s 12-h update worker removed (Obtainium instead).

## 11 · Build, CI, release

```
push / PR ──► gitleaks ─► hygiene ─► manifest ─► battery ─► lint ─► unit tests ─► assembleRelease
                                                                                    │
                         push to main (origin repo only) ──► sign (secrets) ─► GitHub release v0.1.<run>
                                                                                 utter-0.1.<run>.apk + .sha256
```

| Item | Value |
|---|---|
| versionCode / name | `github.run_number` / `0.1.<run>` |
| Signing secrets | `UTTER_KEYSTORE_B64`, `UTTER_KEYSTORE_PASSWORD`, `UTTER_KEY_ALIAS`, `UTTER_KEY_PASSWORD` — keystore generated once, kept outside the repo; **losing it = uninstall to update** |
| Contract-test secrets (optional) | `UTTER_TEST_URL`, `UTTER_TEST_KEY` — contract job skipped when absent |
| Updates on phone | Obtainium tracking the repo's releases (no app code) |
| Build toolchain | Gradle wrapper + version catalog, latest stable AGP/Kotlin/Compose at build time, pinned |

## 12 · Testing and verification

| Layer | What | Where |
|---|---|---|
| Unit (JVM) | multipart body bytes · live event parser (fixtures = §6 events) · response classifier (ok / no speech / STT down / 401) · prompt fill · smart spacing · undo match · URL validation · PCM chunking | CI every push |
| Contract | key check 200/401; upload + live on speech made by the server's own `/v1/audio/speech` (no audio fixture in repo) → non-empty text; silence → "no speech"; cleanup returns text | CI on main, when contract secrets set |
| Device (Qais) | D1 🌐 on Gboard reaches Utter in 1 tap · D2 hold-space path · D3 auto-return to Gboard · D4 live preview while speaking · D5 ✨ cleaned text · D6 airplane mode → Retry works after · D7 kill Wi-Fi mid-take → still inserted · D8 switch app mid-take → clipboard · D9 Undo · D10 24 h battery < 1 % · D11 OnePlus: Live Alerts capsule shows ● timer while recording · D12 Samsung: install (Auto Blocker), Samsung Keyboard nav-bar switch → Utter → auto-return, Now Bar chip · D13 swipe-back gesture works on every settings screen (`android:enableOnBackInvokedCallback="true"`, predictive back) | release APK |

## 13 · Risks to verify on device

| Risk | Fallback |
|---|---|
| Gboard 🌐 rotation differs on his build (Android 17 `imeSwitcherRevamp` uses one list) | hold-space / nav-bar keyboard picker |
| `AudioRecord` from an IME blocked on his OEM build | FUTO Voice Input records from an IME — if blocked, a transparent recording activity (v1.1) |
| Composing-text preview misbehaves in some apps | default is panel strip |
| Recorder fails inside the IME on OxygenOS (Whisper++ did) | Recorder errors are surfaced with the Android error code; take kept; D1 is the first device check |
| OnePlus doesn't promote the notification to a Live Alert | plain notification; Live Alerts per-app toggle tip |
| Samsung Now Bar ignores third-party Live Updates on that One UI version | plain notification |

## 14 · Work plan

| Who | Does |
|---|---|
| Opus (orchestrator) | spec, plan, review every diff, run gates, verify CI, decision brief |
| `claude-ds` worker (herdr pane) | implements plan tasks; commits locally, never pushes |
| GitHub CI | compiler + gates (no Android SDK on the host) |
| Qais | approves repo creation + secrets, installs APK, device checks D1–D10 |
