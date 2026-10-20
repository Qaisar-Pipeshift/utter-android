# Utter Implementation Plan

> **For agentic workers:** implement task by task, in order. Steps use checkbox (`- [ ]`) syntax. The spec is
> `docs/spec.md` — read §2–§17 before Task 1. When this plan and the spec disagree, stop and ask the orchestrator.

**Goal:** the Utter voice keyboard of `docs/spec.md` — rich, beautiful, robust, near-zero battery.

**Architecture:** one Android app module. The IME (`ime/`) and the settings app (`ui/`) share a small manual
dependency graph (`Graph`). Pure logic (`core/`, `net/LiveProtocol`, `data/*Json`) has no Android types, so it is
JVM-unit-tested. Android edges (AudioRecord, InputConnection, notifications) are thin and behind interfaces.

**Tech stack:** Kotlin (AGP 9 built-in), Jetpack Compose Material 3, navigation-compose, DataStore, OkHttp 5,
coroutines, platform `org.json`. Versions: `gradle/libs.versions.toml` (do not change; no new deps).

---

## 0 · Rules for the worker (read every time you resume)

| Rule | Detail |
|---|---|
| Scope | Only files under this repo. Never touch the server, `~/.claude`, other repos. |
| No build tools here | There is no Android SDK on this host. **CI is the compiler.** So: write code that compiles first time — exact imports, no guessed APIs. If unsure an API exists at compileSdk 37, use the plain platform API you are sure of. |
| Tests first | For every pure function / class with logic: write the JVM test (`app/src/test/...`) in the same commit. Tests use JUnit 4 + `org.json` + `kotlinx-coroutines-test` + `mockwebserver`. |
| Gates before commit | `python3 -I scripts/check_public_hygiene.py && python3 -I scripts/check_manifest.py && python3 -I scripts/check_battery_rules.py` must all print 0 findings. |
| Commits | One commit per task (or per numbered step group), Conventional Commits, body ends with `Co-Authored-By: DeepSeek V4.1 Flash (claude-ds) <noreply@anthropic.com>`. **Never push.** Report `TASK <n> DONE <short-sha>` and stop; the orchestrator reviews, pushes `dev`, and returns CI errors. |
| Battery | No WorkManager/Alarm/JobScheduler/WakeLock/Timer/foreground service/network callbacks/`while(true)`. Animations only while recording. |
| Privacy | Never log text, audio or the key. Diagnostics (`diag/Diag.kt`) records event kinds, sizes, codes and ms only. `android.util.Log` is allowed only inside `diag/Diag.kt`. |
| Concurrency | UI + controller state on `Dispatchers.Main.immediate`; disk/network on `Dispatchers.IO`; every long call cancellable; no shared mutable state outside the controller. |
| Strings | User-facing text in `res/values/strings.xml` (English only). |
| Style | Kotlin official style, 120 cols, KDoc on every public class explaining *why*. Small focused files. |

## 1 · File map (final)

```
app/src/main/java/io/github/qaisarpipeshift/utter/
  UtterApp.kt                 Application: builds Graph lazily
  Graph.kt                    manual DI: settings, secrets, api, history, pending, stats, diag, liveStatus
  core/  TextOps.kt (exists)  TextPipeline.kt  FieldKind.kt  Presets.kt  PanelState.kt
         DictationController.kt  Take.kt  Stats.kt  Prompts.kt
  audio/ Recorder.kt  Pcm.kt  AudioFocus.kt  Cues.kt  PcmPlayer.kt  Mics.kt
  net/   Http.kt  Api.kt  ApiResult.kt  LiveProtocol.kt  LiveSession.kt
  data/  Settings.kt  SettingsJson.kt  SettingsRepo.kt  SecretStore.kt  HistoryStore.kt  PendingStore.kt
         StatsStore.kt  Backup.kt  SetupLink.kt
  notify/LiveStatus.kt
  diag/  Diag.kt
  ime/   UtterIme.kt (exists)  InputOps.kt  Panel.kt (rewrite)  panel/MicButton.kt  panel/Waveform.kt
         panel/KeyRow.kt  panel/StatusStrip.kt  panel/PresetSheet.kt
  ui/    theme/Theme.kt (exists, extend)  MainActivity.kt (moves here)  AppNav.kt
         components/{SectionCard,SettingRow,SwitchRow,ChoiceRow,StatusPill,EmptyState}.kt
         onboarding/OnboardingScreen.kt
         screens/{Home,Server,Dictation,TextCleanup,Dictionary,AppRules,History,Insights,Appearance,Notch,Backup,
                  Diagnostics,About,ShareSetup}Screen.kt
  extras/ProcessTextActivity.kt  ShareAudioActivity.kt  DictateTileService.kt  QuickRecorderActivity.kt
```

## 2 · Shared contracts (exact signatures — later tasks rely on these names)

```kotlin
// core/FieldKind.kt
enum class FieldKind { TEXT, EMAIL_SUBJECT, URI, EMAIL, NUMBER, PHONE, PASSWORD }
data class FieldInfo(val kind: FieldKind, val incognito: Boolean, val pkg: String, val fieldId: Int,
                     val actionLabel: EnterAction)
enum class EnterAction { NEWLINE, SEND, SEARCH, GO, NEXT, DONE }
object FieldInfos { fun from(inputType: Int, imeOptions: Int, pkg: String?, fieldId: Int): FieldInfo }

// core/Presets.kt
data class Preset(val id: String, val name: String, val prompt: String, val builtIn: Boolean)
object Presets { val defaults: List<Preset>; const val CLEAN_ID = "clean" }

// core/Take.kt
enum class TakeAction { RAW, CLEAN, EDIT_SELECTION }
data class TakeRequest(val action: TakeAction, val presetId: String?, val field: FieldInfo, val selection: String?)

// core/PanelState.kt
sealed interface PanelState {
    data object SetupNeeded : PanelState
    data object MicPermissionNeeded : PanelState
    data object SecureField : PanelState
    data class Idle(val result: ResultChip?, val pendingCount: Int, val offline: Boolean) : PanelState
    data class Recording(val action: TakeAction, val locked: Boolean, val holding: Boolean, val elapsedMs: Long,
                         val amplitude: Float, val committed: String, val tentative: String) : PanelState
    data class Finishing(val action: TakeAction) : PanelState
    data class Failed(val reason: FailReason, val takeId: String) : PanelState
}
data class ResultChip(val text: String, val cleaned: Boolean, val notCleaned: Boolean, val latencyMs: Long,
                      val canUndo: Boolean, val canSwap: Boolean)
enum class FailReason { OFFLINE, UNREACHABLE, KEY_REJECTED, STT_DOWN, SERVER_ERROR, RECORDER, TOO_SHORT }

// net/ApiResult.kt
sealed interface SttResult {
    data class Text(val text: String, val latencyMs: Long) : SttResult
    data object NoSpeech : SttResult
    data object SttDown : SttResult
    data object Unauthorized : SttResult
    data class Http(val code: Int) : SttResult
    data class Network(val kind: String) : SttResult          // "timeout" | "dns" | "connect" | "io"
}
sealed interface CleanResult { data class Text(val text: String) : CleanResult; data class Failed(val why: String) : CleanResult }
data class Probe(val ok: Boolean, val ms: Long, val detail: String)

// net/Api.kt  (all suspend, all on IO, all cancellable)
class Api(private val http: Http, private val config: () -> ServerConfig?) {
    suspend fun warm(): Probe
    suspend fun checkKey(): Probe
    suspend fun probeLive(): Probe
    suspend fun models(): Result<List<String>>
    suspend fun upload(pcm: java.io.File, keyterms: List<String>): SttResult
    suspend fun transcribeFile(bytes: ByteArray, filename: String, mime: String): SttResult
    suspend fun clean(model: String, message: String, timeoutMs: Long): CleanResult
    suspend fun speech(text: String, voice: String, sink: suspend (ByteArray, Int) -> Unit): Result<Unit>  // (chunk, sampleRate)
}
data class ServerConfig(val baseUrl: String, val apiKey: String)

// net/LiveSession.kt
sealed interface LiveEvent {
    data class Preview(val committed: String, val tentative: String) : LiveEvent
    data class Final(val text: String) : LiveEvent
    data object NoSpeech : LiveEvent
    data class Failed(val why: String) : LiveEvent
}
interface LiveHandle { fun send(pcm16le: ByteArray): Boolean; fun commit(); fun cancel(); val events: kotlinx.coroutines.flow.Flow<LiveEvent> }

// audio/Recorder.kt
interface TakeRecorder {
    /** Opens the mic, writes PCM16LE 16 kHz mono to [out], calls [onChunk] every 100 ms. Returns false if the mic failed to open. */
    suspend fun start(out: java.io.File, onChunk: (ByteArray, Float) -> Unit): RecorderStart
    suspend fun stop(): Long          // returns duration ms; mic released before return
    fun cancel()
}
sealed interface RecorderStart { data object Ok : RecorderStart; data class Error(val code: Int, val why: String) : RecorderStart }
```

## 3 · Tasks

Priority follows spec §15/§17 (P1 → P3). Each task ends with tests passing in CI and a commit.

### Task 1 — Diagnostics + Graph + Application

**Files:** `diag/Diag.kt`, `Graph.kt`, `UtterApp.kt`, manifest `android:name=".UtterApp"`; test `diag/DiagTest.kt`.
- [ ] `Diag`: thread-safe ring buffer (capacity 100) of `Event(tMs: Long, kind: String, detail: String)`; `fun event(kind, detail = "")`; `fun snapshot(): List<Event>`; `fun dump(): String` (one line per event, ISO time). `detail` is truncated to 120 chars. Uses `android.util.Log.i("Utter", ...)` only in debug (`BuildConfig.DEBUG`).
- [ ] `Graph`: `class Graph(app: Context)` with `val` lazies for every service in §2 (fill as tasks land). `UtterApp.graph` lateinit; `UtterIme`/activities get it via `(applicationContext as UtterApp).graph`.
- [ ] Tests: capacity eviction (101 events → first dropped), truncation, dump format.
- [ ] Commit `feat(diag): event ring buffer and app graph`.

### Task 2 — Settings model, JSON, repository, secret store

**Files:** `data/Settings.kt`, `data/SettingsJson.kt`, `data/SettingsRepo.kt`, `data/SecretStore.kt`, `core/Presets.kt`; tests `data/SettingsJsonTest.kt`, `core/PresetsTest.kt`.
- [ ] `Settings` data class — every setting in spec §7 and §15 with the stated defaults: `baseUrl=""`, `tapHandsFree=true`, `holdThresholdMs=300`, `slideGestures=true`, `live=true`, `liveInField=false`, `autoStart=false`, `duckAudio=true`, `sounds=false`, `tailMs=0`, `micId=null`, `haptics=true`, `speculativeClean=false`, `smartSpacing=true`, `trailingSpace=false`, `capitalize=true`, `removeFillers=true`, `customFillers=emptyList()`, `spokenPunctuation=true`, `replacements: List<Replacement(from,to)>`, `vocabulary: List<String>`, `presets=Presets.defaults`, `defaultPresetId="clean"`, `cleanModel=""` (empty = first from /v1/models), `cleanTimeoutMs=15000`, `voiceEdit=true`, `autoEnter=false`, `copyToClipboard=false`, `returnToPrevious=true`, `appRules: List<AppRule>`, `seenApps: Map<String,String>` (pkg→label), `historyOn=true`, `historyLimit=200` (0 = forever), `keepAudioDays=0`, `notch=true`, `theme=SYSTEM|LIGHT|DARK`, `palette=HANDY|DYNAMIC`, `panelHeight=COMPACT|NORMAL|TALL` (NORMAL), `ttsVoice="aiden"`, `onboardingDone=false`.
- [ ] `SettingsJson.encode(Settings): String` / `decode(String?): Settings` — `org.json`; unknown keys ignored, missing keys → defaults, wrong types → default for that key (never throw). **The API key is never in Settings.**
- [ ] `SettingsRepo(context)`: DataStore preferences, one string key `settings_json`; `val flow: Flow<Settings>`; `suspend fun update(f: (Settings) -> Settings)`; `fun current(): Settings` (last emitted value cached in a `@Volatile` field for the IME's synchronous reads).
- [ ] `SecretStore(context)`: AES/GCM/NoPadding with a 256-bit key in `AndroidKeyStore` alias `utter_api_key_v1`; ciphertext+IV base64 in `SharedPreferences("secret", MODE_PRIVATE)`; `save(key)`, `load(): String?` (null on any crypto error → Diag event `secret_unreadable`), `clear()`.
- [ ] `Presets.defaults` (ids stable): `clean` (Handy prompt from spec §7 adapted: transcript arrives inside `<TRANSCRIPT>` tags), `formal`, `casual`, `bullets`, `email`, `to_english` ("Translate the transcript into natural English…"), `hinglish` ("Write the transcript in romanized Hindi (Hinglish)…"). Every prompt ends with "Return only the resulting text."
- [ ] Tests: round-trip all fields; defaults on `"{}"` and on `null`; wrong type tolerated; presets ids unique and prompts non-empty.
- [ ] Commit `feat(data): settings model, JSON codec, DataStore repo, Keystore secret`.

### Task 3 — Field kinds and text pipeline

**Files:** `core/FieldKind.kt`, `core/TextPipeline.kt`, `core/Prompts.kt`; extend `core/TextOps.kt`; tests `core/FieldKindTest.kt`, `core/TextPipelineTest.kt`, `core/PromptsTest.kt`.
- [ ] `FieldInfos.from`: `TYPE_CLASS_TEXT` + variation PASSWORD/VISIBLE_PASSWORD/WEB_PASSWORD → PASSWORD; URI → URI; EMAIL_ADDRESS/WEB_EMAIL_ADDRESS → EMAIL; EMAIL_SUBJECT → EMAIL_SUBJECT; `TYPE_CLASS_NUMBER` (+ NUMBER_VARIATION_PASSWORD → PASSWORD) → NUMBER; `TYPE_CLASS_PHONE` → PHONE; else TEXT. `incognito = imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING != 0`. Enter action from `imeOptions and IME_MASK_ACTION` (SEND/SEARCH/GO/NEXT/DONE) unless `IME_FLAG_NO_ENTER_ACTION` → NEWLINE. Use the `android.text.InputType` / `EditorInfo` constant **values as Kotlin consts copied with comments** so the JVM test needs no Android runtime.
- [ ] `TextPipeline.raw(text, s: Settings): String` order: spoken punctuation (if on) → fillers (if on) → replacements. `TextPipeline.forField(text, kind, s): String`: TEXT/EMAIL_SUBJECT → capitalize first letter if on; URI/EMAIL → lowercase, remove all whitespace, "at"→"@" and "dot"→"." only for EMAIL; NUMBER → digits, `.`, `-` only; PHONE → digits and leading `+`.
- [ ] Spoken punctuation (case-insensitive, whole words, also removes the space before the symbol): comma→`,` · full stop|period→`.` · question mark→`?` · exclamation mark|exclamation point→`!` · colon→`:` · semicolon→`;` · new line→`\n` · new paragraph→`\n\n` · open bracket→`(` · close bracket→`)`. Fillers: um, umm, uh, uhh, er, erm, ah, hmm + custom; remove with adjacent comma; collapse double spaces.
- [ ] Replacements: whole-word, case-insensitive, longest `from` first, `from` may contain spaces ("my email" → "x@y.z").
- [ ] `TextOps`: add `capitalizeIfSentenceStart(before: CharSequence?, text)` (start, or prev non-space char in `.!?\n`), `wordCount(text)`.
- [ ] `Prompts.cleanMessage(presetPrompt, transcript)` = prompt + `"\n\n<TRANSCRIPT>\n" + transcript + "\n</TRANSCRIPT>"`; `Prompts.editMessage(instruction, selection)` = "Apply the instruction to the text. Return only the edited text.\n<INSTRUCTION>…</INSTRUCTION>\n<TEXT>…</TEXT>"; `Prompts.stripModelNoise(out)` removes `<think>…</think>` blocks, a leading unclosed `<think>` section, echoed `<TRANSCRIPT>`/`<TEXT>` tags, surrounding quotes; returns null if blank.
- [ ] Tests: ≥ 6 cases per function, including "um, so I think" → "so I think", "send it to my email" with replacement, URL field "w w w dot example dot com" stays as typed but spaces removed (do not invent dots — only remove whitespace and lowercase for URI), phone "+91 98765 43210" → "+919876543210", `<think>x</think>Hello` → "Hello".
- [ ] Commit `feat(core): field kinds, text pipeline, prompts`.

### Task 4 — HTTP, API and live protocol (pure parts first)

**Files:** `net/Http.kt`, `net/ApiResult.kt`, `net/Api.kt`, `net/LiveProtocol.kt`; tests `net/LiveProtocolTest.kt`, `net/ApiTest.kt` (MockWebServer).
- [ ] `Http`: one `OkHttpClient` — connect 5 s, read 60 s, write 60 s, `ConnectionPool(1, 30, SECONDS)`, `pingInterval(10 s)` on a derived client used only for WebSockets, `retryOnConnectionFailure(true)`. Helper `suspend fun Call.await(): Response` that cancels the call when the coroutine is cancelled.
- [ ] Base URL normalisation: trim, strip trailing `/`, strip a trailing `/v1`; `https://` required (reject others with a clear error). Auth header: `Authorization: Bearer <key>`.
- [ ] `upload`: multipart exactly as spec §6 (`config` = `{"sample_rate":16000,"channels":1,"llm":null,"keyterms_prompt":[…]}`, then `audio` part `audio/pcm`). Map: 200 + `llm_error=="no speech"` → NoSpeech; 200 + `llm_error=="speech-to-text unavailable"` → SttDown; 200 + text → Text; 401/403 → Unauthorized; other → Http(code); IOException → Network(kind).
- [ ] `transcribeFile`: `POST /v1/audio/transcriptions` multipart `file`, `model=qwen3-asr`, `response_format=json` → `{"text"}`; blank → NoSpeech.
- [ ] `clean`: `POST /v1/chat/completions` `{"model":m,"messages":[{"role":"user","content":message}]}` (no temperature, no stream), per-call timeout via `callTimeout`; content through `Prompts.stripModelNoise`; null → Failed("empty").
- [ ] `models`: `GET /v1/models` → `data[].id`. `checkKey`: `GET /v2/transcript` (200 ok / 401 "Key rejected"). `warm`: `GET /warm` no auth. `probeLive`: open the socket, send `session.update`, ok when `session.updated` arrives within 5 s, then close.
- [ ] `speech`: `POST /v1/audio/speech` `{"model":"qwen3-tts","voice":v,"input":text,"response_format":"pcm"}`; sample rate from header `X-Audio-Sample-Rate` (default 24000); stream body in 8 KiB chunks to `sink`.
- [ ] `LiveProtocol` (pure): `sessionUpdate(rate=16000, prompt: String?)` (GA shape of spec §6, `turn_detection: null`, `transcription.model = "qwen3-asr"`, `transcription.prompt` = vocabulary joined by ", " when non-empty); `append(pcm): String` (base64 NO_WRAP via `java.util.Base64`); `COMMIT`; `parse(json): LiveEvent?` (preview / completed / error "buffer too small" after commit → NoSpeech / other error → Failed / `…transcription.failed` → Failed; ignore others).
- [ ] Tests (MockWebServer): upload request body contains both parts in order and the JSON config; each response mapping; 401; timeout → Network("timeout"); clean strips think; base URL normalisation table (`https://h/`, `https://h/v1`, `https://h/v1/`, `http://h` rejected).
- [ ] Commit `feat(net): API client, live protocol, result mapping`.

### Task 5 — LiveSession

**Files:** `net/LiveSession.kt`; test `net/LiveSessionTest.kt` (MockWebServer WebSocket).
- [ ] `fun Api.openLive(prompt: String?): LiveHandle` — `wss://<base>/v1/realtime?intent=transcription` (build the URL as `https://…`, OkHttp upgrades). Audio sent before `session.updated` is queued (max 50 chunks; overflow → Failed("backlog")). `send` returns false once failed/closed. `events` is a `Channel(capacity = 64)`-backed flow; on overflow keep only the latest Preview (previews are superseded).
- [ ] After `commit()`: Final or NoSpeech must arrive within 20 s → else Failed("final timeout"). Any close/error → Failed. `cancel()` sends `input_audio_buffer.clear` then closes with 1000.
- [ ] Tests: happy path (preview, completed); error before commit → Failed; "buffer too small" after commit → NoSpeech; final timeout with a virtual clock; backlog overflow.
- [ ] Commit `feat(net): live WebSocket session with timeouts`.

### Task 6 — Recorder, PCM, audio focus, mics, cues

**Files:** `audio/Recorder.kt`, `audio/Pcm.kt`, `audio/AudioFocus.kt`, `audio/Mics.kt`, `audio/Cues.kt`, `audio/PcmPlayer.kt`; test `audio/PcmTest.kt`.
- [ ] `AndroidRecorder(context, settings)` implements `TakeRecorder`: `AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, max(minBuf, 3200*4))`; `setPreferredDevice` when `micId` set and present; check `state == STATE_INITIALIZED` and `startRecording()` then `recordingState == RECORDSTATE_RECORDING` → else `RecorderStart.Error(code, why)`. Read loop on a dedicated `Dispatchers.IO` coroutine: `while (isActive && running)`, 1600 samples per read; negative read codes → stop with error recorded in Diag; write to `BufferedOutputStream(FileOutputStream(out), 64 KiB)`; `onChunk(bytes, Pcm.rms(bytes))`. `stop()` reads the optional tail (`tailMs`), stops, **releases**, flushes, closes, returns duration. `cancel()` same but deletes the file.
- [ ] `Pcm.rms(pcm16le): Float` normalised 0..1 (log-scaled for display), `Pcm.durationMs(bytes, rate)`, `Pcm.wavHeader(...)` (for "keep audio" replay).
- [ ] `AudioFocus`: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with `USAGE_ASSISTANT`/`CONTENT_TYPE_SPEECH`; request before start, abandon after stop/cancel; failures ignored (never block recording).
- [ ] `Mics.list(context)`: built-in, wired headset, USB headset/device (id, label). `Cues`: `ToneGenerator(STREAM_SYSTEM, 60)` short beeps; start cue → 150 ms delay → mic opens; stop cue after release. `PcmPlayer`: `AudioTrack` streaming (mode STREAM) for TTS chunks; `stop()` releases.
- [ ] Tests: rms of silence = 0, full-scale ≈ 1; duration math; WAV header bytes.
- [ ] Commit `feat(audio): recorder, focus, mics, cues, PCM player`.

### Task 7 — Stores: history, pending takes, stats

**Files:** `data/HistoryStore.kt`, `data/PendingStore.kt`, `data/StatsStore.kt`, `core/Stats.kt`; tests for each (temp dirs).
- [ ] `HistoryEntry(id, ts, pkg, appLabel, raw, final, action, presetName, durationMs, latencyMs, words)`; `HistoryStore(dir)`: JSON-lines `history.jsonl`; `add` appends (fsync not needed); `list(query)` newest first, case-insensitive search over raw+final+appLabel; `delete(id)`, `clear()`, `prune(limit)` rewrite via temp file + atomic `renameTo`; all under one `Mutex`; corrupt lines skipped.
- [ ] `PendingStore(cacheDir)`: `takes/<id>.pcm` + `<id>.json` (`createdAt, action, presetId, pkg, fieldKind, durationMs, lastError`); `newTake(meta): File`; `list()`; `meta(id)`; `update(id, lastError)`; `delete(id)`; `purge(olderThanMs = 24h)` (call on IME create + app start); `keepAudio(id, days)` moves the pcm to `filesDir/audio/` (history replay) and `purgeAudio(days)`.
- [ ] `StatsStore(filesDir)`: per-day buckets `{date: {takes, words, speechMs, pkgCounts}}` for 30 days; `record(...)`; `Stats.summary(...)`: totals, today, 7-day series, time saved = words/40 wpm − speech time (≥ 0), top 3 apps.
- [ ] Tests: add/list/search/delete/prune/corrupt line; pending create/list/purge by age; stats math incl. day rollover.
- [ ] Commit `feat(data): history, pending takes, stats stores`.

### Task 8 — DictationController (the heart; most tests)

**Files:** `core/DictationController.kt`; test `core/DictationControllerTest.kt` with fakes for `TakeRecorder`, a `Transcriber` interface (`fun live(prompt): LiveHandle?`, `suspend fun upload(file, keyterms): SttResult`), `Cleaner` (`suspend fun clean(msg): CleanResult`), `Inserter` (`fun insert(text, meta)`, `fun preview(text)`, `fun clearPreview()`), `Clock`, and the real `PendingStore` on a temp dir.
- [ ] Exposes `val state: StateFlow<PanelState>`; methods `start(req)`, `stop()`, `cancel()`, `lock()`, `retry(takeId)`, `discard(takeId)`, `undo()`, `swap()`; runs on an injected `CoroutineScope` (Main in app, `TestScope` in tests).
- [ ] Flow per spec §4/§6/§8: offline check (injected `isOnline()`) → start recorder into a pending file → (live on) open live, forward chunks; previews → state + `Inserter.preview` when `liveInField` → stop: recorder.stop, live.commit, await Final ≤ 20 s → else upload the same file → result mapping → pipeline (raw/clean/edit) → `Inserter.insert` → history/stats → delete pending (or keep audio) → `Idle(result)`. Clean failure → insert raw with `notCleaned = true`. NoSpeech → Idle with toast-like chip "No speech", pending deleted. STT down / network / 401 / http → `Failed(reason, takeId)`, pending kept with `lastError`.
- [ ] Auto-stop at 295 000 ms; warning event at 280 000 ms (`PanelState.Recording.elapsedMs` drives the UI; the controller emits `Haptic.WARNING` through an injected `(Haptic) -> Unit`).
- [ ] Too-short takes (< 300 ms) → discard silently. Starting while Finishing → ignored. Hidden panel (`onPanelHidden()`) while Recording → stop and finish; insertion goes through `Inserter` which falls back to clipboard when the field is gone (the IME decides).
- [ ] Speculative clean (setting): on Preview with empty `tentative` and committed unchanged for ≥ 900 ms → launch one clean of committed (cancel previous); at Final, use it only if `final == committed`.
- [ ] Tests (≥ 15): live happy path; live failure → upload; live final timeout → upload; upload SttDown → Failed + pending kept; retry success → pending deleted; discard; NoSpeech; cancel deletes pending; 401; offline start → still records, Failed(OFFLINE) at stop; auto-stop at 295 s; too short; clean failure → raw + notCleaned; edit selection uses `Prompts.editMessage`; speculative reuse vs mismatch; panel hidden mid-take.
- [ ] Commit `feat(core): dictation controller state machine`.

### Task 9 — IME panel UI + input ops

**Files:** `ime/InputOps.kt`, `ime/Panel.kt` (rewrite), `ime/panel/{MicButton,Waveform,KeyRow,StatusStrip,PresetSheet}.kt`, update `ime/UtterIme.kt`.
- [ ] `InputOps(ime)`: implements `Inserter` with `currentInputConnection`: `insert` = `beginBatchEdit` → `TextOps.leadingSpace` + capitalization + trailing space → `commitText(…, 1)` → `endBatchEdit`; remembers `LastInsert(text, pkg, fieldId)`; when no connection or a different field → copy to clipboard (`ClipboardManager`) + Diag `field_closed` + return false. `preview(text)` = `setComposingText`; `clearPreview()` = `setComposingText("",1)` + `finishComposingText()`. `undo()`/`swap()` only when `getTextBeforeCursor(len, 0) == last.text`. `enter(field)` = `performEditorAction` or `sendDownUpKeyEvents(KEYCODE_ENTER)`. `backspace()` = `sendDownUpKeyEvents(KEYCODE_DEL)`; `deleteWord()` deletes back to previous whitespace using `getTextBeforeCursor(64, 0)`. `selection()` = `getSelectedText(0)`.
- [ ] `UtterIme`: build `FieldInfo` in `onStartInputView`; record `seenApps`; create the controller once per service; call `controller.onPanelHidden()` in `onFinishInputView`; purge pending on create; `returnToPrevious` after a successful insert; secure field → `PanelState.SecureField`; key not set → `SetupNeeded`; no RECORD_AUDIO → `MicPermissionNeeded` (button opens MainActivity onboarding at the mic step).
- [ ] Layout (spec §4 drawing; heights COMPACT 196 / NORMAL 228 / TALL 268 dp, plus nav-bar inset): status strip (committed text solid, tentative 50 % alpha, auto-scroll to end; chips: result "· 140 ms", ✨ Clean it / Raw swap, ↶, Retry/Discard, "No speech", offline); centre row ⌫ · 🎙 (72 dp) · ✨ (56 dp); bottom row 🌐 · ⚙ · space (wide) · ↶ · ⏎ (icon by `EnterAction`). When a selection exists and voice edit is on, ✨ shows "Edit" label.
- [ ] `MicButton` gestures with `pointerInput`: down → haptic, start pressing timer; release < `holdThresholdMs` → toggle hands-free (start or stop); held ≥ threshold → push-to-talk until release; while holding: drag x < −96 dp → cancel (red ✕ hint appears from −32 dp), drag y < −72 dp → lock (🔒 hint). Long-press ✨ while Idle → `PresetSheet` (list of presets; tap = start CLEAN with that preset).
- [ ] `Waveform`: ring of 32 bars around the mic driven by the last 32 amplitudes, `animateFloatAsState` (≤ 120 ms); composed only in `Recording`. Elapsed `m:ss` under the mic; turns warning colour at 280 s.
- [ ] Haptics via `LocalView.current.performHapticFeedback`: press `KEYBOARD_TAP`, start `CONFIRM`, stop `CONFIRM`, cancel `REJECT`, lock `LONG_PRESS`, warning `CLOCK_TICK`; all off when `haptics=false`.
- [ ] Commit `feat(ime): panel UI, gestures, input ops`.

### Task 10 — Notch status (Live Update)

**Files:** `notify/LiveStatus.kt`; wire into controller via an injected `(PanelState) -> Unit` observer in `UtterIme`.
- [ ] Channel `recording_status` (IMPORTANCE_DEFAULT, no sound/vibration, `setShowBadge(false)`). Notification: small icon = mic vector, content title "Listening…" / "Transcribing…" / "Dictation failed — open Utter to retry", `setOngoing(true)`, `setOnlyAlertOnce(true)`, `setUsesChronometer(true)` + `setWhen(startWallClock)` while recording, no content intent, category `CATEGORY_PROGRESS`… On API ≥ 36 add `Notification.EXTRA_REQUEST_PROMOTED_ONGOING = true` via `addExtras(Bundle)`. Cancel on Idle; Failed shows for 10 s then cancels (use the controller's scope `delay`, not a Timer).
- [ ] Skip silently when `notch=false` or `NotificationManagerCompat.areNotificationsEnabled()` is false.
- [ ] Commit `feat(notify): notch live status while recording`.

### Task 11 — App shell: theme, navigation, components

**Files:** move `MainActivity.kt` → `ui/MainActivity.kt` (update manifest), `ui/AppNav.kt`, `ui/components/*.kt`, extend `ui/theme/Theme.kt` (typography: larger titles, `SemiBold`; shapes 16 dp; dynamic colour when `palette=DYNAMIC` on API 31+ via `dynamicLightColorScheme`; theme setting respected).
- [ ] Navigation-compose `NavHost` with routes for every screen in spec §16; predictive back works by default with `enableOnBackInvokedCallback` — do **not** intercept back except for unsaved dialogs. Top app bar per screen with back arrow; large title on Home.
- [ ] Components: `SectionCard(title, content)`, `SwitchRow(title, subtitle, checked, onChange)`, `ChoiceRow(title, options, selected)`, `SettingRow(title, value, onClick)`, `StatusPill(ok/warn/error, text)`, `EmptyState(icon, title, body)`. Generous padding (16/20 dp), rounded icons from `Icons.Rounded.*`.
- [ ] Commit `feat(ui): app shell, navigation, components`.

### Task 12 — Onboarding + Server screen + setup link

**Files:** `ui/onboarding/OnboardingScreen.kt`, `ui/screens/ServerScreen.kt`, `ui/screens/ShareSetupScreen.kt`, `data/SetupLink.kt` (+ test), manifest intent filter `utter://setup` on MainActivity.
- [ ] Wizard steps (progress dots, Back/Next, skippable where marked): ① Server URL + key + **Check** (4 probes rendered as a checklist with ✓/✗ + ms) ② Microphone (runtime permission; explain why) ③ Notifications (API 33+, skippable) ④ Enable Utter (`ACTION_INPUT_METHOD_SETTINGS`; detect enabled via `InputMethodManager.enabledInputMethodList`) ⑤ Try it (a text field + "Switch keyboard" button → `showInputMethodPicker`) ⑥ Tips for this phone: Gboard 🌐 / hold space · Samsung Keyboard nav-bar button (show when `Build.MANUFACTURER` is samsung) · OnePlus Live Alerts toggle (when oneplus) with deep link to app notification settings.
- [ ] `SetupLink.build(url, key)` → `utter://setup?u=<urlenc>&k=<urlenc>`; `parse(uri)`; tests round-trip + rejects non-https. Opening a link shows a confirm dialog with the host, never the key.
- [ ] Server screen: URL, key (masked, show/hide), Check, cleanup model dropdown (from `/v1/models`, free-text fallback), timeouts, "Share setup" (warning card: the link contains your key).
- [ ] Commit `feat(ui): onboarding, server settings, setup link`.

### Task 13 — Home, Dictation, Text & cleanup, Dictionary, Appearance, Notch screens

- [ ] Home: status card (server reachable + latency, key ok, live ok — cached last Check, "Check now"), quick toggles (Live, Notch, Return to previous keyboard), today's stats (takes, words, time saved), pending takes banner, "Try it" field, grid of section tiles.
- [ ] Dictation: every §15.1 setting (hold threshold slider 150–800 ms, mic list from `Mics.list`, tail ms slider 0–500 with a caution text referencing the stop-chime issue).
- [ ] Text & cleanup: §15.2 toggles; presets list (add/edit/delete/reorder via up/down buttons, built-ins editable but resettable), default preset, voice edit toggle.
- [ ] Dictionary: vocabulary chips (add/remove), replacements list (from → to).
- [ ] Appearance: theme, palette (Handy / Material You), panel height with a live mini preview, haptics.
- [ ] Notch: toggle; status (notification permission, Live Alerts hint), OEM guide cards (OnePlus, Samsung).
- [ ] Commit `feat(ui): home and settings screens`.

### Task 14 — History, Insights, App rules, Backup, Diagnostics, About

- [ ] History: search field, pending takes section on top (Retry/Discard), day headers, item shows final (raw in expandable), app label, duration, latency; tap copy, swipe to delete, menu "Clear all" with confirm; retention + keep-audio settings; play kept audio via `PcmPlayer`.
- [ ] Insights: totals, 7-day bar chart drawn with `Canvas` (accent bars, labels), top apps, time saved.
- [ ] App rules: list from `seenApps`; per app: action (Raw / preset), auto-send, block, history off.
- [ ] Backup: export via `ActivityResultContracts.CreateDocument("application/json")`, import via `OpenDocument`; key excluded; import shows a diff summary then applies.
- [ ] Diagnostics: event list (monospace), own CPU time (`Process.getElapsedCpuTime()`), takes since start, Copy button.
- [ ] About: version + `BuildConfig.GIT_SHA`, What's new (bundled `res/raw/whats_new.txt`), "Check for update" (one `GET https://api.github.com/repos/Qaisar-Pipeshift/utter-android/releases/latest` on tap → compare `tag_name` with `v<versionName>` → open the release page), licenses (Whisper++ GPL-3.0 credit, Handy MIT credit for the prompt), privacy statement.
- [ ] Commit `feat(ui): history, insights, app rules, backup, diagnostics, about`.

### Task 15 — Extras: process text, voice notes, read aloud, tile

**Files:** `extras/*.kt`, manifest entries (all `exported` as Android requires; tile service with `BIND_QUICK_SETTINGS_TILE`), translucent theme `Theme.Utter.Dialog`.
- [ ] `ProcessTextActivity` (`ACTION_PROCESS_TEXT`, `text/plain`, label "Utter"): bottom sheet with presets + "Read aloud"; result returned with `EXTRA_PROCESS_TEXT` unless `EXTRA_PROCESS_TEXT_READONLY`, else copied + toast.
- [ ] `ShareAudioActivity` (`ACTION_SEND`, `audio/*`): duration via `MediaMetadataRetriever` (> 300 s → refuse with message), size ≤ 25 MB, `Api.transcribeFile`, result card with Copy / Share / Clean / Save to history.
- [ ] Read aloud: `Api.speech` → `PcmPlayer`; panel 🔊 appears in the status strip when a selection exists; Stop button; voice from settings.
- [ ] `DictateTileService` → `QuickRecorderActivity` (translucent, mic + live text, result to clipboard + history). Use `startActivityAndCollapse(PendingIntent)` on API 34+, the `Intent` overload below.
- [ ] Commit `feat(extras): process-text, voice notes, read aloud, quick tile`.

### Task 16 — Polish pass and contract test

- [ ] Accessibility: every icon has `contentDescription`; touch targets ≥ 48 dp; font scale 1.3 does not clip the panel.
- [ ] `app/src/test/.../ContractTest.kt` (skipped unless env `UTTER_TEST_URL` + `UTTER_TEST_KEY`): key check 200, `/warm`, models non-empty, TTS "testing one two three" → PCM → `upload` → text contains "testing"; 1 s silence → NoSpeech; clean returns non-empty.
- [ ] Commit `test: live-server contract test (opt-in)`.

## 4 · Orchestrator review checklist (per task)

Validity (does it do what the spec says?) · compile-risk scan (imports, API levels) · battery rules · privacy (no text/key in Diag/logs) · concurrency (state only on Main; IO off main; cancellation) · resource release (AudioRecord, AudioTrack, sockets, files) · tests meaningful.
