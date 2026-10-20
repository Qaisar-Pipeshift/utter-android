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

Deps allowed: AndroidX core-ktx, activity-compose, Compose BOM (material3, material-icons-extended — R8 strips unused), lifecycle (runtime/viewmodel-compose), navigation-compose (predictive back), datastore-preferences, OkHttp (+ mockwebserver for tests), kotlinx-coroutines, kotlinx-serialization-json, desugar_jdk_libs. **Not allowed:** WorkManager, Room/KSP, Firebase/analytics, Gson, Ktor. Anything else → ask first.

Toolchain (pinned from a project whose CI was green 2026-10-15): Gradle 9.8.1 · AGP 9.4.1 (built-in Kotlin) · Kotlin 2.4.21 · Compose BOM 2026.09.00 · compileSdk 37 · targetSdk 36 · minSdk 28 · JDK 17 · OkHttp 5.5.0 · coroutines 1.11.0 · lifecycle 2.11.0 · navigation-compose 2.10.2 · datastore 1.2.1 · activity-compose 1.13.0 · core-ktx 1.19.1.

License GPL-3.0 (same as Whisper++, so its code may be borrowed with attribution in About → Licenses).

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

## 15 · Feature catalog (v1 = everything below)

Source: **H** = Handy (desktop, our fork) · **W** = Whisper++ · **N** = new for Utter. Priority = build order (P1 first). Every feature is on-demand only — none adds background work.

### 15.1 Dictation (panel)

| Feature | Src | Default | P |
|---|---|---|---|
| 🎙 raw / ✨ cleaned buttons (Handy's two shortcuts) | H | — | P1 |
| Tap = hands-free start/stop · hold = push-to-talk (hold threshold 300 ms, adjustable 150–800) | H W | both on | P1 |
| While holding: slide ← cancel · slide ↑ lock hands-free | W | on | P2 |
| Live text (WSS) with committed (solid) + tentative (faded) | H | on | P1 |
| Live text shown in panel strip / field (composing) | N | panel | P2 |
| Waveform ring (10 Hz amplitude, animated only while recording) + elapsed timer | W | on | P1 |
| Auto-start recording when panel opens | N | off | P2 |
| Max take 295 s, warning haptic at 280 s | N | — | P1 |
| Pause / duck other audio while recording (transient audio focus) | H W | on | P2 |
| Start/stop sounds (start cue before mic opens, stop cue after mic closes — never recorded) | H | off | P3 |
| Keep recording after release 0–500 ms (Handy's extra buffer; 500 ms caused the chime bug) | H | 0 ms | P3 |
| Microphone: Auto / built-in / wired-USB headset (`AudioRecord.setPreferredDevice`) | H | Auto | P3 |
| Haptics: press, start, stop, cancel, lock, error | W | on | P1 |
| Clean while I pause (speculative cleanup at each all-final pause, used only if final text identical) | H | off | P3 |

### 15.2 Text and editing

| Feature | Src | Default | P |
|---|---|---|---|
| Smart spacing (leading space when needed) · trailing space | H | on · off | P1 |
| Capitalize first letter at sentence start | N | on | P2 |
| Filler-word removal for raw text (um, uh, er, ah + custom list) | H | on | P2 |
| Word replacements (find → replace, whole-word, case-insensitive) applied before cleanup | W | — | P2 |
| Vocabulary / custom words → server hints (`keyterms_prompt` on upload, `transcription.prompt` on live) | H W | — | P2 |
| Cleanup presets: Clean (default, Handy prompt) · Formal · Casual · Bullet list · Email · Translate → English · custom (CRUD, reorder) | H W | Clean | P2 |
| Long-press ✨ → preset picker (sheet in panel) | W | — | P2 |
| **Voice edit**: text selected in the field → ✨ becomes "Edit selection": speak an instruction, LLM rewrites the selection, replaces it (undoable) | W | on | P3 |
| Undo last insert (↶) · re-insert last result (⟲ chip) | N | — | P1 |
| Auto-Enter / send after insert | H | off | P1 |
| Also copy result to clipboard | H | off | P2 |
| Return to previous keyboard after insert | N | on | P1 |

### 15.3 Per-app rules and privacy

| Feature | Src | Default | P |
|---|---|---|---|
| Secure fields (password, OTP, `IME_FLAG_NO_PERSONALIZED_LEARNING` incognito) → mic disabled, history off | W | always | P1 |
| App rules (per package): default action raw / preset · auto-send · block dictation · history off; app list built from apps Utter was used in (no `QUERY_ALL_PACKAGES`) | W | — | P3 |
| History of an incognito field never stored | W | always | P1 |

### 15.4 History, retry and insights

| Feature | Src | Default | P |
|---|---|---|---|
| Pending takes queue: failed take saved as PCM in app cache, Retry / Discard in panel + app, survives IME/process death, auto-purged after 24 h | W | — | P1 |
| History: searchable, grouped by day; shows raw + cleaned, app, duration, latency; tap = copy; swipe = delete | H W | on | P2 |
| History limit 50 / 200 / 1000 / forever | H | 200 | P2 |
| Keep audio with history: never / 1 day / 7 days (replay + re-transcribe) | H | never | P3 |
| Insights: words, minutes spoken, time saved (vs 40 wpm typing), dictations per day (7-day bars, Compose Canvas), top apps | W | — | P3 |

### 15.5 Server, status, settings app

| Feature | Src | Default | P |
|---|---|---|---|
| Onboarding wizard (6 steps, §7) with progress dots | H W | — | P1 |
| Server: URL + key; **Check** runs 4 probes with ✓/✗ + ms: reach `/warm` · key `/v2/transcript` · live socket handshake · cleanup model `/v1/models` | H W | — | P1 |
| Cleanup model: picked from `/v1/models` (fallback: free text) · timeout | H | first listed | P2 |
| Home dashboard: server status card, quick toggles (Live, Notch, Auto-return), today's stats, "Try it" field | N | — | P2 |
| Notch Live Update (§3b) + OEM setup guide | N | on | P2 |
| Appearance: theme system/light/dark · palette Handy pink / Material You dynamic · panel height compact/normal/tall | H W | system · Handy · normal | P2 |
| Backup / restore settings as JSON via Storage Access Framework (API key never exported) | W | — | P3 |
| Diagnostics: last 100 events (state changes, HTTP codes, timings, Android error codes — **never text or audio**), Copy diagnostics | H | — | P2 |
| About: version + commit, What's new (release notes bundled), Check for update (one GitHub API call on tap → opens the release page), licenses | H W | — | P3 |
| Edge-to-edge, predictive back on every screen, TalkBack labels, large-font safe | W | — | P1 |

### 15.6 Explicitly NOT built (declared)

Multiple providers (one server only) · on-device models · Auto-Learn dictionary · translations of the UI · in-app APK self-update · tablet layouts beyond responsive Compose · language selection (server auto-detects; measured WER uses auto).

## 16 · Screens

```
App (MainActivity, navigation-compose, predictive back)
 ├─ Onboarding: Server → Mic → Notifications → Enable keyboard → Try it → Tips for this phone
 └─ Home ─┬─ Server            (URL, key, Check ×4, cleanup model, timeouts)
          ├─ Dictation         (gestures, live, auto-start, audio focus, sounds, mic, max length, speculative)
          ├─ Text & cleanup    (spacing, capitalize, fillers, presets CRUD, voice edit, auto-enter, clipboard, auto-return)
          ├─ Dictionary        (vocabulary, replacements)
          ├─ App rules         (per-app action, auto-send, block, history off)
          ├─ History           (search, pending takes, retention, keep audio)
          ├─ Insights          (stats, 7-day bars, top apps)
          ├─ Appearance        (theme, palette, panel height, haptics)
          ├─ Notch status      (toggle, OEM guide)
          ├─ Backup            (export / import)
          ├─ Diagnostics       (events, copy)
          └─ About             (version, what's new, update check, licenses, privacy)

Panel (IME) states: Setup needed · Secure field · Idle · Recording(hands-free | holding) · Finishing · Result chips · Failed(+Retry) · Preset sheet · Voice edit
```

Style: Handy tokens (§9) as the default palette; 16 dp radius cards; one accent; generous spacing; icons from material-icons-extended (rounded set); motion only on state change (≤ 250 ms), none while idle.
