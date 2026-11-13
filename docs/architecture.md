# Cue — Architecture

Nov 13, 2026 · Status: as built, revision 5 · Companion docs: [build-status.md](build-status.md), [prd.md](prd.md), [ux-flows.md](ux-flows.md), [use-cases.md](use-cases.md), [lfm3-omni-spike.md](lfm3-omni-spike.md)

**What changed in revision 2:** the agent's brain is now **LiquidAI LFM3-Omni-2B running on the phone**, not a cloud LLM. One model hears the user, reasons, and answers in speech, with no network. This rewrites sections 2, 3, 6 to 9, 11, 13, 14, 16, 17 and 21, and adjusts the rest. A cloud LLM remains only as an optional helper that is off by default.

**What changed in revision 3:** the app is split into independent modules with published contracts, and every model sits behind one **brain contract** (section 3). If the on-device model does not work out, a cloud provider is switched in behind the same API without touching the rest of the app.

**What changed in revision 4:** the build decisions are fixed (package `com.cue.app`, Room 2.8 with KSP, a hand-written `CueGraph` service locator instead of Koin, compileSdk 36). **RulesBrain**, a deterministic intent parser with Android speech recognition and text-to-speech, is now the always-available brain and the guaranteed demo path; Lfm3Brain is used when its model files are present. The full use-case set from [ux-flows.md](ux-flows.md) is in scope: health, life admin, people memory, accountability calls, arrival moments, care mode (family circle) and English practice, plus the Apps and pinning model. New triggers, tables and tools are added (sections 4, 8, 9), a debug panel can fire every trigger (section 20), and section 25 maps every use case to its components.

**What changed in revision 5 (as built, tested on the razr fold 2026):** the app exists and runs every flow on the phone. Where the build differs from the sections below, this list wins:

| Topic | As built |
| --- | --- |
| Modules | As section 3.1, plus `:ui` (the Bloom-with-depth design system shared by `:call`, `:surfaces` and `:app`). The rules provider lives in `:brain:rules`. Entities and the `CueDao` contract are declared in `:domain` with Room's annotations (`room-common`, pure JVM); only `:memory` opens the database |
| How LFM3 joins a call | As the model card documents (docs.liquid.ai, LFM3-Omni-2B), with three prompts. **Hearing:** the call records the user's own voice (16 kHz, microphone, an energy check ends the turn) and the model transcribes it with the card's ASR prompt `Perform ASR in English.` (user turn = the audio only, text out). **Acting:** the rules parser reads that transcript, so ticks, reminders, flight moves and the daily call time stay exact. **Talking:** a voice-chat turn (system turn ending `Respond with interleaved text and audio.`) whose user turn is the same audio, so the model hears the user, not a transcript; the model is told it cannot change anything itself, so it never claims an action. **Fixed lines** (the opening, "Are you there?", confirmations) use the card's TTS prompt `Perform TTS in American English using a female voice.` The prompts need different system turns and the model has one state, so the chat's state is saved after each answer (about 170 ms) and put back before the next (under 10 ms). Speech plays through a one-second buffer like the spike app's player, with nothing cut off. The night journal is written by LFM3 in text mode. Without the model, the phone's recognizer (falls back from en-IN to en-US when the en-IN pack is missing) and the best installed offline voice take over |
| Why not the phone's recognizer with LFM3 | Tried first. On the razr fold the on-device English (India) pack is not installed, so every listen failed (error 13) and the call never heard the user; and sending the model a transcript loses what the spike showed works: the model hearing the voice itself |
| LFM3 measured in Cue | First load 43 s (includes the one-time 3.2 GB section copy); later loads 16 s. Language model on CPU, speech decoder on GPU. First audio 1.3 to 2.0 s after the user's text; whole answers 5 to 14 s, heard while streaming. The model is preloaded when the app opens with calls set to use it, released on memory pressure |
| Ring | Call-style notification with a full-screen intent and `FLAG_INSISTENT`; verified over the lock screen. 30 s timeout counts as missed: retry in 10 min (15 for medicine), notification after two misses. Default quiet hours 11 PM to 6 AM turn rings into silent notifications; the debug simulator overrides quiet hours |
| Bubbles on a locked phone | Android never draws overlays over the lock screen, so a locked phone gets a notification now and the bubble on unlock. The debug panel has bubble previews that render the same component in-app |
| Debug builds | `MainActivity` shows over the lock screen in debug builds only, so the app can be tested on a PIN-locked phone. Release builds do not |
| Not built | Cloud provider (placeholder only), database encryption, relay server, generated UI |

This document describes how Cue is built for the hackathon: every component, how they connect, where processing runs, and what happens in each situation the phone can be in. Items marked **(spike)** are settled by the risk spike in section 21. Numbers marked **measured** come from the spike on the demo phone ([lfm3-omni-spike.md](lfm3-omni-spike.md)); numbers marked **not measured** are still open.

---

## 1. Constraints that shape the design

| Constraint | Consequence |
| --- | --- |
| No OEM permissions, no Motorola or Qira APIs | Only standard Android APIs a sideloaded app can get |
| The model runs on the phone and needs about 5.2 GB of memory (measured) | It cannot stay loaded. It is loaded shortly before it is needed and unloaded straight after |
| First load took 48 seconds (measured; later loads not measured) | Loading must start well before the phone rings |
| The LiteRT build of the model has a 4,096-token context | The prompt, the memory context and the whole conversation must fit in a small budget |
| It is a 2-billion-parameter model | It makes mistakes on details and its tool calling is weak, so plain code checks everything and a second pass catches what was missed |
| Ring first, never talk unannounced | The microphone and voice start only after the user accepts |
| Must work when the app is in the background and the phone is idle | Wake-ups come from the system (alarms, geofences, content jobs), not from a process we keep alive |
| Hackathon timeline | No backend, no accounts, no sync; one Android app |
| Personal data (tickets, prescriptions, bills) | Everything stays on the phone by default |

**Demo phone:** motorola razr fold 2026, Snapdragon SM8845, Android 17.

## 2. System overview

```
┌──────────────────────────── ANDROID SYSTEM ────────────────────────────┐
│ Alarms  Geofences  Screenshots  Share sheet  Notifications  User/Talk │
└──────┬─────────────┬─────────────┬───────────────┬──────────────┬──────┘
       │             │             │               │              │
┌──────▼─────────────▼─────────────▼───────────────▼──────────────▼──────┐
│ 1. TRIGGER LAYER  (plain code, no model)                               │
│    Receivers turn system callbacks into TriggerEvents, saved to the DB │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ TriggerEvent
┌──────────────────────────────────▼─────────────────────────────────────┐
│ 2. SITUATION ENGINE  (plain code, no model)                            │
│    Reads device state + user settings + history → picks a surface:     │
│    RING · BUBBLE · NOTIFY · SILENT · DEFER                             │
└───────┬───────────────────┬───────────────────────────┬────────────────┘
        │ RING              │ BUBBLE / NOTIFY           │ SILENT
┌───────▼────────┐  ┌───────▼────────────┐   ┌──────────▼───────────────┐
│ 3. CALL SYSTEM │  │ 5. SURFACES        │   │ 6. INGEST PIPELINE       │
│ load model →   │  │ bubble overlay,    │   │ copy → read text (OCR) → │
│ ring → accept  │  │ notification, card │   │ pattern rules → album,   │
│ → voice loop   │  │ (no model needed)  │   │ model enriches later     │
└───────┬────────┘  └───────▲────────────┘   └──────────┬───────────────┘
        │ audio             │ SurfaceSpec               │ text
┌───────▼───────────────────┴───────────────────────────▼────────────────┐
│ 4. AGENT CORE                                                          │
│    ContextBuilder → BrainRouter → RulesBrain or Lfm3Brain (on phone)   │
│    → tool calls → validated → executed                                 │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ reads / writes
┌──────────────────────────────────▼─────────────────────────────────────┐
│ 7. MEMORY LAYER  (Room database + files in app-private storage)        │
│ items · lists · albums · places · people · habits · reminders · triggers│
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ album or item changed
┌──────────────────────────────────▼─────────────────────────────────────┐
│ 8. PLANNER + SCHEDULER  (plain code)                                   │
│    Works out which alarms and geofences should exist, registers them   │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   └────────► back to the Android system
```

The loop closes at the bottom: saving a ticket changes memory, the planner derives new triggers from it, and those triggers later wake the agent.

### Where processing runs

Everything runs on the phone.

| Work | Done by | Needs the model loaded? |
| --- | --- | --- |
| Waiting for triggers | Android system | No |
| Choosing the surface | Plain code | No |
| Reading text from images (OCR) | ML Kit | No |
| First-pass fields from a document (dates, times, airport codes, amounts, booking references) | Pattern rules in plain code | No |
| Album matching | Plain code first; the model only for unclear cases | Usually no |
| Bubble and notification content | Plain code from the database | No |
| Hearing the user, conversation, spoken replies | LFM3-Omni-2B, or RulesBrain with Android speech recognition and text-to-speech | LFM3 only |
| Enriching a document (title, kind, extra fields) | LFM3-Omni-2B in text mode | Yes |
| Writing the journal, catching missed tasks after a call | LFM3-Omni-2B in text mode | Yes |
| Storage and search | Room database | No |

The design keeps the model out of everything that must be instant or must work at any moment. Only three jobs need it, and each is scheduled around the cost of loading it.

## 3. Tech stack and project layout

| Area | Choice |
| --- | --- |
| Language and UI | Kotlin 2.2.21, Jetpack Compose, Material 3 |
| Package | `com.cue.app` |
| Build | AGP 8.9.1, Gradle 8.11.1, JDK 17 target (built with the Android Studio JDK 21); Kotlin compiler flag `-Xskip-metadata-version-check` (LiteRT 2.3.0 ships newer Kotlin metadata) |
| Android versions | minSdk 31 (Android 12), compileSdk 36, targetSdk 35, **arm64-v8a only** |
| On-device model runtime | LiteRT 2.3.0 (`litert` and `litert-gpu`) |
| Model | LFM3-Omni-2B, LiteRT build from Hugging Face `litert-community/LFM3-Omni-2B-LiteRT`, 4 files, 4.0 GB |
| Async | Coroutines and Flow |
| Database | Room 2.8 (SQLite) with KSP, plus a full-text search table |
| Settings | DataStore |
| Background jobs | WorkManager |
| Dependency injection | Hand-written service locator `CueGraph` in `:app` (no DI framework, for the hackathon) |
| Serialization, time | kotlinx.serialization, kotlinx-datetime |
| Images | Coil |
| OCR | ML Kit Text Recognition (bundled Latin model) |
| Location | Google Play services Location (Geofencing API) |
| Voice for RulesBrain and fallbacks | Android `SpeechRecognizer` (on-device where available) and `TextToSpeech` |

**Gradle and manifest entries the model needs** (from the spike):

- `packaging.jniLibs.useLegacyPackaging = true` (the GPU accelerator is opened by file path)
- `androidResources.noCompress += json, bin, wav`
- Manifest: `android:largeHeap="true"`, and `<uses-native-library>` entries for `libOpenCL.so`, `libOpenCL-pixel.so`, `libvndksupport.so` and `libcdsprpc.so`, all `required="false"`
- Assets carried over from the sample: `bundle_sections.json`, `codec_window_f32.bin`

### 3.1 Modules

The app is split into small modules, each with one job, its own data, and a published contract. This is the microservice idea applied inside one app: the modules are Gradle modules in one process, not network services, because separate processes would cost memory and speed for no gain here.

| Module | Owns | Depends on | Android? |
| --- | --- | --- | --- |
| `:domain` | Data types (Item, Album, Tag, Place, Trigger, TriggerEvent, SurfaceSpec) and the contracts other modules implement: `MemoryRepository`, `TriggerScheduler`, `SurfacePresenter`, `Clock` | nothing | No |
| `:brain:api` | The brain contract: `Brain`, `BrainSession`, `BrainEvent`, `ToolSpec`, `ToolCall`, `ToolResult`, `BrainCapabilities`, and the shared `ToolLoop` | nothing | No |
| `:brain:ondevice` | LFM3-Omni-2B provider: `ModelHost` plus the sample's engine (`Lfm3OmniEngine`, `Conversation`, `SpeechGenerator`, `LmRunner`, `Parts`, `Lfm3Tokenizer`), its prompt format and its tool-call parser | `:brain:api` | Yes |
| `:brain:cloud` | Cloud provider. Built only if needed; an empty placeholder until then | `:brain:api` | Yes |
| `:brain:rules` | **RulesBrain**: deterministic intent parser used as the always-available provider and in tests. Holds the phrase rules for every tool and the scripted English lesson | `:brain:api` | No |
| `:agent` | All decision logic: Orchestrator, ContextBuilder, tool definitions and their handlers, prompts, SituationEngine, Planner, AlbumAssigner, pattern rules | `:domain`, `:brain:api` | No |
| `:memory` | Room database, DAOs, file store. The only module that touches the database | `:domain` | Yes |
| `:triggers` | Alarms, geofences, screenshot job, share target, boot receiver, trigger simulator. The only module that touches AlarmManager and geofencing | `:domain` | Yes |
| `:call` | Ringing, call screen, call service, microphone and speaker | `:domain`, `:agent` | Yes |
| `:ingest` | Ingest worker, OCR, PDF rendering | `:domain`, `:agent` | Yes |
| `:surfaces` | Bubble overlay, notifications, Compose templates. The only module that draws outside the app | `:domain` | Yes |
| `:app` | Wiring (`CueGraph`), `BrainRouter`, app screens, onboarding, debug panel, people-memory listener, care-mode SMS | everything | Yes |

```
                         ┌────────┐
                         │  :app  │  wiring only: picks the implementation for every contract
                         └───┬────┘
      ┌──────────┬───────────┼───────────┬────────────┬──────────────────────┐
      ▼          ▼           ▼           ▼            ▼                      ▼
  :triggers   :memory    :surfaces    :call  ──►   :agent  ◄──  :ingest   :brain:ondevice
      │          │           │           │            │                   :brain:cloud
      │          │           │           │            │                   :brain:rules
      ▼          ▼           ▼           ▼            ▼                      │
  ┌───────────────────────────────────────────┐   ┌──────────────┐           │
  │                 :domain                   │   │  :brain:api  │ ◄─────────┘
  └───────────────────────────────────────────┘   └──────────────┘
```

### 3.2 Rules that keep the modules independent

1. **A module exposes contracts and data types only.** Implementations are `internal`.
2. **No module reaches into another's internals.** Only `:memory` touches Room. Only `:brain:*` providers touch a model or a model API. Other modules ask through the contract.
3. **Dependencies point inward.** Android modules depend on `:agent`, `:domain` and `:brain:api`, never on each other's implementations. Nothing depends on `:app`.
4. **`:brain:api` knows nothing about Cue.** It has no idea what an album or a checklist is. It is a general "talk to a model, with tools" service, which is what makes it swappable.
5. **`:agent` knows nothing about which model is running.** It reads `BrainCapabilities` and adapts; it never checks for a provider by name.
6. **Swapping an implementation is one binding in `:app`.** No other module changes.

`:domain`, `:brain:api`, `:brain:rules` and `:agent` import no Android classes, which also keeps the door open for Kotlin Multiplatform.

### 3.3 The brain contract

This is the API every model provider implements, on the phone or in the cloud. It sits at the level of **a voice turn**, not at the level of "send text, get text", because the on-device model takes audio directly while a cloud text model needs speech recognition and text-to-speech around it. Each provider hides that difference.

```kotlin
interface Brain {
    val capabilities: BrainCapabilities
    suspend fun prepare(profile: LoadProfile): PrepareResult   // on-device: load the model. cloud: open the connection
    fun openSession(config: SessionConfig): BrainSession       // rules, tools, context pack, spoken or text replies
    suspend fun complete(request: FieldsRequest): FieldsResult // one-shot text job: File, Enrich
    suspend fun transcribe(clips: List<AudioClip>): List<String>
    suspend fun release()
}

interface BrainSession {
    fun send(input: TurnInput): Flow<BrainEvent>   // TurnInput: Audio | Text | ToolResults
    val contextUsage: StateFlow<ContextUsage>
    suspend fun close()
}

sealed interface BrainEvent {
    data class Text(val delta: String) : BrainEvent
    data class Audio(val pcm24k: ShortArray) : BrainEvent
    data class ToolCallRequested(val call: ToolCall) : BrainEvent
    data class TurnDone(val timings: Timings, val usage: ContextUsage) : BrainEvent
    data class Failed(val reason: BrainFailure) : BrainEvent
}

data class BrainCapabilities(
    val contextTokens: Int,        // 4,096 on-device; far larger in the cloud
    val maxTools: Int,             // how many tools the provider handles reliably
    val hearsAudio: Boolean,
    val speaks: Boolean,
    val needsNetwork: Boolean,
    val prepareTime: Duration,     // drives the pre-load lead before a ring
    val promptStyle: PromptStyle,  // COMPACT for small models, FULL for large ones
)
```

**How tool calls flow.** Tools are defined once and work the same with every provider.

```
:agent        defines each tool once: ToolSpec (name, description, parameters) + a handler
   │ ToolSpec list
   ▼
:brain:api    ToolLoop sends the turn and watches the events
   │
   ▼
provider      writes the tools into the prompt in its own format, runs the model,
              and reads the model's tool calls back out of its own format
   │ BrainEvent.ToolCallRequested(name, arguments): the same shape from every provider
   ▼
:agent        ToolExecutor validates the call and runs the handler against :memory
   │ ToolResult
   ▼
:brain:api    ToolLoop returns the result to the session and the model continues
```

| Concern | Who handles it |
| --- | --- |
| What tools exist and what they do | `:agent` |
| Checking arguments and executing against memory | `:agent` (`ToolExecutor`) |
| The loop: send turn, receive calls, return results, stop conditions, write limits | `:brain:api` (`ToolLoop`), shared by all providers |
| Writing tools into the prompt; parsing the model's tool-call format | Each provider |
| Getting structured fields back (File, Enrich) | Each provider, behind `complete()`: labelled lines on-device, structured JSON output in the cloud. The caller gets the same `FieldsResult` either way |

### 3.4 Providers

| | RulesBrain (always available) | Lfm3Brain (on-device model) | Cloud text + phone speech | Cloud realtime voice |
| --- | --- | --- | --- | --- |
| Module | `:brain:rules` | `:brain:ondevice` | `:brain:cloud` | `:brain:cloud` |
| Hears the user | Android `SpeechRecognizer` | The model takes audio | Android speech recognizer | The service takes audio |
| Understands | Deterministic phrase rules per tool ("pack X and Y", "I've packed X", "remember …", "call me at 6 and check …", "take this twice a day for five days", "my flight moved to 9", "where's my …") | The model, with `<tool_call>` text parsed by the provider | The API's tool calls | The API's tool calls |
| Speaks | Android `TextToSpeech`, replies from templates | The model produces audio | Android text-to-speech | The service produces audio |
| Free conversation | No (scripted turns; scripted English lesson) | Yes | Yes | Yes |
| `prepare()` | Instant | Loads 5.2 GB; up to 48 s measured | Opens a connection | Opens a connection |
| Needs network | No | No | Yes | Yes |
| When used | Default for every job until Lfm3Brain passes its checks; always the fallback | When the model files are in `/sdcard/Android/data/com.cue.app/files/` and memory, battery and heat checks pass | Off | Off |

All providers produce the same `BrainEvent` stream, so `:call`, `:agent` and `:ingest` cannot tell them apart.

### 3.5 Switching provider

A `BrainRouter` in `:app` holds one setting per job:

| Job | Default | Can be switched to |
| --- | --- | --- |
| Converse (calls, Talk) | Lfm3Brain if available, else RulesBrain | Cloud text + phone speech, or cloud realtime voice |
| File (after a call) | Lfm3Brain if available, else RulesBrain (rule-based journal from the turns) | Cloud text |
| Enrich (documents) | Lfm3Brain if available, else pattern rules only | Cloud text |
| Practice (English) | Lfm3Brain (real conversation), else RulesBrain scripted lesson | Cloud text |

- Changing a setting is a switch in the debug panel. No other module is rebuilt or edited.
- Jobs switch independently. The likely partial move, if the 2B model files poorly, is cloud for File and Enrich while calls stay on the phone.
- **What adapts by itself** through `BrainCapabilities`: the size of the context pack, how many tools are offered (the five core tools on-device; more, such as memory search, with a large model), the prompt style, and the pre-load lead before a ring.
- **What a move to the cloud still costs**, outside the code: an API key kept behind a small relay server, a changed privacy statement (section 16), a network dependency (the "brain not available" fallbacks in section 13 then also cover "no network"), and credits.
- **Contract tests.** One scripted test suite (the same spoken phrases, the same expected tool calls and fields) runs against every provider. A provider that passes it can be switched in.

The engine code in `:brain:ondevice` comes from Google's `photo_voice_chat` LiteRT sample (Apache-2.0). The model weights have a separate license; see section 16.

## 4. Trigger layer

Every trigger is plain Android code. A receiver's only job is to write a `TriggerEvent` row and hand it to the situation engine. Writing the row first means an event survives a crash or a killed process and can be replayed.

| Trigger | Android mechanism | Wakes a dead process? | Notes |
| --- | --- | --- | --- |
| Check-in call at a set time | `AlarmManager.setAlarmClock()` | Yes | Treated by Android as a user alarm, so it fires on time even in Doze |
| Model pre-load before a call | `setExactAndAllowWhileIdle()`, 90 seconds before the ring | Yes | New in revision 2; see section 6 |
| Other timed reminders | `setExactAndAllowWhileIdle()` | Yes | Exact, allowed while idle |
| Arriving at a place | Geofencing API → broadcast | Yes | Entry events only; typical delay of a few minutes in the background |
| New screenshot | WorkManager job with a content-URI trigger on MediaStore | Yes | No always-running watcher needed |
| File shared from another app | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` activity | Yes | Copy the file at once; the access grant is temporary |
| User opens the app or taps the bubble | Normal activity start | Yes | |
| Message notification (people memory) | `NotificationListenerService` | Yes (bound by the system once access is granted) | Messaging apps only; text stays on the phone |
| Dose reminder and missed-dose check | `setExactAndAllowWhileIdle()`; a follow-up alarm 30 min later checks `habit_log` | Yes | Missed twice in care mode → caregiver SMS |
| Accountability call | Alarm-clock alarm from a `reminder` kind ACCOUNTABILITY | Yes | Rings like a check-in call |
| Leave-by, expiry and practice reminders | Exact alarms from `reminder` kinds LEAVE_BY, EXPIRY, PRACTICE | Yes | Derived by the planner |
| Arrival at home, office, gym, clinic, airport | Geofencing API, one geofence per `place` row | Yes | 100 m (150 m airports), dwell 1 min |
| Caregiver escalation | `SmsManager` (`SEND_SMS`) | n/a | Off until a caregiver number is configured |
| Simulated trigger | Debug panel → `TriggerSimulator` | n/a | Fires any trigger above by hand; the demo never depends on real GPS or real time |

**Things that silently remove triggers, and the fix**

| Cause | What is lost | Fix |
| --- | --- | --- |
| Phone reboot | All alarms and geofences | `BOOT_COMPLETED` receiver runs the scheduler's reconcile step |
| App update | Alarms | `MY_PACKAGE_REPLACED` receiver → reconcile |
| Time zone or clock change | Alarms now point at the wrong moment | `TIMEZONE_CHANGED` and `TIME_SET` receivers → reconcile |
| Location turned off, or Play services data cleared | Geofences | Handle the "geofence not available" error; re-register when location comes back |
| User force-stops the app | Everything, until the app is opened again | Cannot be fixed by code; reconcile on next launch and tell the user |

**Exact-alarm permission.** On Android 14 and newer, `SCHEDULE_EXACT_ALARM` is off by default for new installs. Declare `USE_EXACT_ALARM` (granted automatically; fine for a sideloaded build) and check `canScheduleExactAlarms()` at onboarding, sending the user to the settings page if needed.

**Geofence details.** Needs fine location plus background location ("Allow all the time"), requested as two separate steps. Limit of 100 geofences per app. Radius of at least 150 m for airports and 100 m for home or office. Each time-bound geofence has a time-based backup: if the airport geofence has not fired by a set time before departure, a timed trigger fires the same event.

**Screenshot details.** Needs the images permission with full access; if the user picks "Select photos", new screenshots are invisible to the app, so onboarding must explain this. The job filters MediaStore rows whose path contains `Screenshots`, waits until the file is fully written, and ignores anything older than the last processed timestamp.

**Deduplication.** Every event carries a `dedupe_key` (trigger id + time bucket, or the file's content hash). A second event with the same key is dropped.

## 5. Situation engine

A plain function in `:agent`: `decide(event, deviceState, settings, history) → SurfaceDecision`. No model. This is where "the right moment" is enforced.

**Inputs read at decision time**

| Input | Source |
| --- | --- |
| Screen locked or unlocked | `KeyguardManager` |
| User is on a real phone call | `AudioManager.mode` |
| Do Not Disturb, ringer mode | `NotificationManager`, `AudioManager` |
| Battery level, battery saver, phone temperature state | `BatteryManager`, `PowerManager` |
| Free memory | `ActivityManager` |
| Quiet hours, call times, snooze limits | User settings |
| Declines and surfaces shown today | `call_session` and `trigger_event` history |

**Rules**

| Situation | Decision |
| --- | --- |
| Scheduled check-in call, phone free | RING |
| Any ring while the user is on a real phone call | DEFER 10 minutes, then re-check |
| Any ring during Do Not Disturb or quiet hours | NOTIFY silently; never ring |
| Ring declined | Snooze 10 minutes; after 2 declines, NOTIFY and stop for that slot |
| Ring not answered in 30 seconds | Treated as declined once; same snooze path |
| Call time, but the phone is hot, the battery is under 15%, or memory is too low for the model | RING with the scripted briefing; do not load the model |
| Arrival event, phone unlocked | BUBBLE |
| Arrival event, phone locked | NOTIFY on the lock screen; show the BUBBLE on unlock |
| Arrival event with nothing relevant in memory | SILENT (log only) |
| Message from a person with open pending items | BUBBLE, at most once per person per hour; NOTIFY if locked |
| Dose due | NOTIFY with Taken / Snooze; RING 30 min later if not taken |
| Dose missed twice in care mode | Caregiver SMS (if configured), else NOTIFY on the recipient's phone |
| Accountability or practice time | RING |
| Expiry due | Merged into the next morning call; NOTIFY if the morning call is off |
| Leave-by time | RING (short) |
| New screenshot or shared file | SILENT filing; a small "Saved to Delhi trip" confirmation only for shared files |
| Two events at once | One surface at a time. Priority: active call > ring > bubble > notification. The lower one is queued and shown after |
| Same bubble already shown in the last hour | Drop |
| Overlay permission missing | BUBBLE becomes NOTIFY |
| Full-screen call permission missing | RING becomes a heads-up call notification |

## 6. Call system (ring first)

### Loading before ringing

The model takes time to load and cannot stay in memory, so every scheduled call has two alarms:

1. **Pre-load alarm, 90 seconds before the ring.** Starts `CallService` as a foreground service, loads the model, feeds it the prompt and the memory context, and has it generate the opening briefing as audio.
2. **Ring alarm, at the set time.** Shows the incoming call.

The 90-second lead is a starting value. The first load took 48 seconds (measured), and that included a one-time file copy. Later loads are not measured yet; the spike sets the real lead time.

If the model is not ready when the ring alarm fires, the phone rings anyway. If the user accepts before the model is ready, Cue speaks a scripted briefing with Android's built-in voice and switches to the model when it finishes loading.

### Ringing

**Primary approach (spike confirms):** a call-style notification with a full-screen intent, on a high-importance notification channel whose sound is the ringtone.

- The system itself plays the ringtone and vibration from the notification channel, so ringing does not compete with the model for the processor.
- When the phone is locked or the screen is off, the full-screen intent opens `CallActivity` over the lock screen. When the phone is in use, Android shows a heads-up banner with Answer and Decline, the same as a real call.
- The notification times out after 30 seconds, which counts as a missed call.
- Check `canUseFullScreenIntent()` at onboarding.
- The demo phone folds: the call screen must be checked on both the cover screen and the inner screen.

**Alternative (spike compares):** Android's Telecom framework through the Jetpack Core-Telecom library (a self-managed call). Adopt it only if the notification approach fails the ring test or audio routing is poor.

### Why ring-first also solves a system restriction

Android does not let an app start microphone capture from the background. Because Cue only starts listening after the user taps Answer, the app is in the foreground at that moment and microphone access is allowed.

### Call lifecycle

```
SCHEDULED ─pre-load alarm─► PREPARING ─ring alarm─► RINGING ─accept─► IN_CALL ─hang up─► FILING ─► UNLOAD ─► DONE
                               │                       │                 │
                               │                       ├─decline / 30 s──┼─► UNLOAD ─► SNOOZED ─(max 2)─► NOTIFIED
                               │                       │                 │
                               │                       └─real phone call arrives─► cancel ring, UNLOAD, DEFER
                               │
                               └─ model cannot load ─► still RINGING, with a scripted briefing
```

- **PREPARING:** load the model, run ingest enrichment that was waiting for a loaded model (section 11), build the context pack, generate the opening briefing audio.
- **IN_CALL:** `CallService` adds the `phoneCall` and `microphone` foreground types when the user accepts, and shows an ongoing call notification, so the call continues if the user leaves the screen.
- **FILING:** runs right after hang-up while the model is still loaded (section 8).
- **UNLOAD:** frees the 5.2 GB as soon as filing ends, or at once on decline.
- A snoozed call unloads the model and pre-loads again 90 seconds before the retry.
- Hard cap of 3 minutes per call, to stay inside the context budget and limit heat.

### Audio

| Concern | Handling |
| --- | --- |
| Recording | 16 kHz mono, one clip per user turn, up to 30 seconds (the model's input format) |
| Playback | 24 kHz audio streamed from the model as it is generated |
| Echo (the agent hearing itself) | Voice-communication audio mode with the system echo canceller; v1 is half-duplex: the app does not listen while it speaks |
| Earpiece, speaker, Bluetooth, wired | Earpiece by default with a proximity sensor; speaker toggle on screen; follow the connected headset automatically |
| Real phone call arrives mid-conversation | Audio focus is lost → stop, end the Cue call, run FILING on what was said |
| Another app playing music | Request transient audio focus; music pauses and resumes |

## 7. On-device model and voice loop

### The model

LFM3-Omni-2B is one model that takes text, audio, images and video and answers in text, speech, or text and speech interleaved. There is no separate speech recognizer and no separate text-to-speech.

| Fact | Value | Source |
| --- | --- | --- |
| Size | 2.17 billion parameters; 4.0 GB on disk in 4 files | Liquid docs; spike |
| Context | 4,096 tokens in the LiteRT build (the model itself supports 32K) | Spike; Liquid docs |
| Audio in / out | 16 kHz in, 24 kHz out | Liquid docs |
| Modes, chosen by the system prompt | Spoken chat ("Respond with interleaved text and audio"), text only ("Respond with text"), transcription ("Perform ASR in English"), read-aloud ("Perform TTS in English using a female voice") | Liquid docs |
| Tool calling | Supported, in a `<tool_call><function=…><parameter=…>` text format. Liquid's own tool-calling score is 52, against 72 for Gemma 4 E4B | Hugging Face model card |
| Reasoning mode | None | Liquid docs |
| Spoken languages | Includes British and American English. **Hindi is not a speech output language.** Indian English is not mentioned | Liquid docs |
| Text languages | Include English and Hindi | Liquid docs |
| Memory when loaded | About 5.2 GB | Measured |
| Where it runs on the demo phone | Language model and audio encoder on CPU; speech decoder and vision encoder on GPU; NPU not used | Measured |
| First load | 48.4 seconds, including a one-time file copy | Measured |
| Typed question → first sound | 2.7 seconds, followed by 0.7 to 1.3 seconds of near-silence before the first word | Measured; sample README |
| Spoken question → first sound | Not measured on the demo phone. 2.0 to 2.2 seconds on a Galaxy S26 | Sample README |
| Speech generation speed | 0.75 seconds of work per second of speech on a Galaxy S26; not measured on the demo phone | Sample README |

### Model host

`ModelHost` lives inside `:brain:ondevice` and is the only component that touches the engine. It is what implements the brain contract's `prepare()`, sessions and `release()` for this provider. Everything else goes through the contract.

- **One model, one job at a time.** Jobs queue by priority: call turn > post-call filing > document enrichment.
- **Load on demand, unload when idle.** A job requests a load; the host unloads 60 seconds after the queue empties, or at once when a call ends.
- **Checks before loading:** free memory (the engine itself checks before each stage), battery level, and phone temperature state. If any check fails, the job is refused and the caller uses its fallback.
- **Two load shapes (spike):** a full load for calls, and a text-only load (language model without the speech and vision parts) for filing and enrichment. Whether the text-only load is meaningfully smaller and faster is not measured yet.
- **Always inside a foreground service,** so Android does not kill a 5 GB process mid-job.

### Voice loop

```
mic ─► 16 kHz clip ─► end of speech detected ─► LFM3-Omni-2B
                                                    │
                          ┌─────────────────────────┴───────────────────────┐
                          │ text tokens                 │ 24 kHz audio      │
                          ▼                             ▼                   │
                 tool-call parser + agent's      speaker (streamed)         │
                 side of the transcript                                     │
                          │                                                 │
                          └── the conversation continues in the model's ────┘
                              state, so each turn only costs its own tokens
```

**Work needed on top of the sample**

| Gap | Change |
| --- | --- |
| The sample's `Conversation.ask()` refuses to run without a photo or video | Add a voice-only conversation start: system prompt plus audio, no image rows. The sample shows this works at the language-model level |
| The sample records when a button is held | Add end-of-speech detection so the user just talks: an energy-based silence check first, a dedicated voice-activity model if that is not reliable |
| The sample's prompt builder has no tool section | Add the tool list to the chat prompt and parse `<tool_call>` blocks out of the text stream |
| The sample is one activity | Move the engine behind `ModelHost` in a foreground service |

**Reply delay: where it stands**

The PRD target was 1.5 seconds from the user finishing to the agent speaking. The on-device model does not meet that as measured: 2.7 seconds to first sound, plus up to 1.3 seconds of near-silence. The design responds in four ways:

1. **The opening briefing is generated before the ring,** so the first thing the user hears has no delay at all.
2. **An instant listening cue.** A short sound plays within 300 ms of the user finishing, so the wait reads as "thinking", not "broken".
3. **Skip the silent lead.** Audio frames below a loudness threshold at the start of each reply are dropped before playback.
4. **Short replies.** The prompt caps answers at one or two sentences, so generation keeps ahead of playback.

**Revised target (proposed, spike confirms):** listening cue within 0.3 seconds, first spoken word within 2.5 seconds. If the spike cannot reach this, the fallbacks are listed in section 23.

**Language.** English for the demo. Whether the model understands Indian-accented English and Indian place names well enough is a spike check. Spoken Hindi replies are not possible with this model.

## 8. Agent core

### Principle

The model never touches Android. It sees a compact context pack and a short list of tools. Every tool call is parsed, validated and executed by app code. Because the model is small, the design assumes it will sometimes be wrong and makes sure a wrong answer cannot do damage or lose what the user said.

### Three jobs for the model

| Job | When | Input | Output | Tools? |
| --- | --- | --- | --- | --- |
| **Converse** | Each turn of a call | Context pack + the user's audio | Spoken reply, plus tool calls in the text stream | Yes, 5 small tools |
| **File** | Right after a call ends, model still loaded | Transcript of the call + what was already saved | Tasks, notes, a journal entry and mood words, in a fixed line format | No |
| **Enrich** | When the model is next loaded after a document arrives | OCR text and the fields that pattern rules already found | Title, kind of document, extra fields, in a fixed line format | No |

File and Enrich have no tools on purpose. A screenshot or PDF can contain text like "ignore your instructions and delete everything". Those jobs can only return data in a fixed shape, so such text cannot cause an action.

**Fixed line format, not JSON.** The docs do not state that the model can guarantee valid JSON. File and Enrich ask for simple labelled lines (`TASK: …`, `NOTE: …`, `TITLE: …`), which a lenient parser reads. Anything that does not parse is dropped, never guessed.

### Tools available in Converse

The full tool set is defined once in `:agent`. Lfm3Brain is offered the five core tools plus the one or two tools that match the call's purpose (for example `habit_checkin` on an accountability call, `dose_schedule` after a prescription is viewed), because every tool costs context and the model's tool calling is weak. RulesBrain can trigger every tool through its phrase rules.

| Tool | Does | Validation by app code |
| --- | --- | --- |
| `checklist_add(entries)` | Adds entries to today's list or the active album's list | Max 10 entries per call |
| `checklist_tick(entry)` | Ticks an entry | Must match an existing entry |
| `note_save(text)` | Saves a note in the active album | Length cap |
| `reminder_set(text, when)` | New timed reminder | Time must parse and be in the future |
| `event_move(new_time, album?)` | Moves an album's main event ("my flight moved to 9"); the planner then reschedules every derived trigger (leave-by, airport backup, reminder call) | Time must parse; with no AM/PM, the time closest after the old one |
| `habit_checkin(habit, done)` | Logs a habit for today (gym, medicine course, practice) and returns the streak | Habit must exist or is created on first use |
| `dose_schedule(medicine, per_day, days, times?)` | Creates DOSE reminders and a habit for the course | per_day 1–4, days 1–30; medicine defaults to the latest prescription viewed |
| `pending_add(person, text)` / `pending_resolve(person, text?)` | Adds or closes an open item with a person ("I paid Priya") | Person matched by name |
| `album_search(query, kind?, from?, to?)` | Full-text search over items, albums and pending items ("where's my AC bill?") | Max 5 results |
| `reminder_set(text, when, surface, kind?, place?, habit?)` | Extended form of the core tool: kinds DOSE, EXPIRY, ACCOUNTABILITY, LEAVE_BY, PRACTICE; a place instead of a time for arrival reminders | Time in the future or a known place |

- **No ids for the model to copy.** The context pack numbers entries ("3. passport"). The model passes the number or the words, and app code finds the entry by number or closest text match.
- **No search tool.** What the agent needs is put into the context pack up front.
- **No delete tool.** At most 3 writes per turn.
- **Tool calls are best effort.** If the model says "added" without emitting a tool call, or emits a broken one, the File job after the call still catches it from the transcript.

### Context budget

Everything shares 4,096 tokens: the rules, the tools, the memory context, and the whole conversation, including audio in both directions.

| Part | Budget |
| --- | --- |
| Rules and tool list | 500 tokens |
| Context pack | 700 tokens |
| Opening briefing | 200 tokens |
| Conversation (user audio, agent text and speech) | about 2,700 tokens |

How many tokens a minute of conversation uses is not measured yet (spike). The host tracks usage; at 85% the agent wraps up the call.

### Context pack

`ContextBuilder` assembles the smallest text that makes the agent useful, from the database.

| Section | Content | Cap |
| --- | --- | --- |
| Now | Date, time, named place if known | 1 line |
| User | Name | 1 line |
| Trigger | Why the agent woke up ("morning call") | 1 line |
| Active album | The one album most relevant today: title, dates, key facts | 6 lines |
| Due | Numbered unticked entries for today | 10 entries |
| Last night | Summary line from the latest journal entry | 2 lines |

### Getting the user's words

The model hears audio directly, so there is no transcript of the user's side during the call. Each user clip is kept in memory, and right after hang-up the model transcribes the clips in its transcription mode. That text, together with the agent's own text, is the transcript the File job reads. The audio is then discarded.

### Saving during and after a call

- **During the call**, tool calls save tasks and notes as the user says them, so they appear on screen live.
- **After the call**, the File job writes the journal entry and catches anything missed. Each proposed task or note is compared against what was saved during the call and skipped if it is a near duplicate.
- The result is applied in one database transaction and marked on the `call_session` row, so a retry never files twice.
- If the call was cut short (real phone call, crash), the saved clips and text are filed the next time the model is loaded.

### Which provider runs each job

Everything in this section talks to the brain contract from section 3.3, never to a model directly. The three jobs map onto it like this: Converse is a `BrainSession`, File and Enrich are `complete()` calls, and the user's words come from `transcribe()`.

The on-device model is the default for all three. If the spike shows it is not good enough at a job, that job is switched to a cloud provider in the `BrainRouter` (section 3.5); nothing in this section changes. If the cloud is ever switched on, the default cloud model is Claude (`claude-opus-5-5`), and the privacy statement in section 16 changes for the jobs that moved.

The tool list and context budget above are sized for the on-device model. With a larger provider, `:agent` reads the bigger limits from `BrainCapabilities` and offers a fuller context pack and extra tools such as memory search.

## 9. Memory layer

### Storage

- **Room database** in app-private storage for all structured data.
- **Files** under `files/media/`: the original image or PDF (copied in, so memory survives the user deleting the source), plus a thumbnail.
- **Model files** under the app's external files folder, 4.0 GB, plus sections copied out on first start. About 7 GB free is needed on the phone in total.
- **DataStore** for settings.
- Nothing is written to shared storage. No account, no sync.

### Tables

| Table | Key fields | Purpose |
| --- | --- | --- |
| `item` | id, type (GALLERY / NOTE / CHECKLIST / JOURNAL), kind (ticket, bill, prescription…), title, body, source, media_path, thumb_path, fields_json, event_start, event_end, event_zone, created_at, ingest_state | One row per memory item of any type |
| `checklist_entry` | id, item_id, text, checked, position | Rows of a checklist item |
| `tag` | id, kind (PLACE / PERSON / DATE / EVENT), value, normalized | Shared tag vocabulary |
| `item_tag` | item_id, tag_id | Tags on an item |
| `album` | id, title, kind (TRIP / HEALTH / ADMIN / GENERAL), start_at, end_at, place_id, auto_created, status | A context that groups mixed item types |
| `album_item` | album_id, item_id, added_by (AGENT / USER) | Membership |
| `place` | id, label, lat, lng, radius_m, kind (HOME / OFFICE / GYM / CLINIC / AIRPORT) | Named places for geofences |
| `person` | id, display_name, normalized, phone, relation, is_caregiver | People known from notifications, documents and care mode |
| `pending_item` | id, person_id, text, source_app, status (OPEN / DONE), created_at, resolved_at | Things owed to or by a person (people memory) |
| `reminder` | id, kind (GENERAL / DOSE / EXPIRY / ACCOUNTABILITY / LEAVE_BY / PRACTICE), text, due_at or place_id, repeat_rule, surface (RING / NOTIFY / BUBBLE), item_id, album_id, habit_id, status | Every user or derived reminder; the planner turns these into `trigger` rows |
| `habit` | id, name, target_per_day, start_date, end_date, care_mode | Gym, medicine courses, practice |
| `habit_log` | id, habit_id, date, done, source (VOICE / TAP / GEOFENCE) | One row per check-in; streaks are computed from it |
| `trigger` | id, type (TIME / GEOFENCE), purpose, fire_at or time_of_day, place_id, album_id, item_id, enabled, snooze_count | Every alarm and geofence that should exist |
| `trigger_event` | id, trigger_id, fired_at, dedupe_key, state, decision, outcome | Log of everything that fired and what was done |
| `call_session` | id, kind, ring_at, answered_at, ended_at, outcome, transcript_json, filing_state | One row per call |
| `item_fts` | title, body | Full-text search over items |
| `model_run` | job, load_ms, first_sound_ms, tokens_used, memory_mb, battery_temp, at | Speed, memory and heat tracking |

Times are stored as UTC instants plus the event's own time zone, so a flight shows its departure-airport local time wherever the phone is.

### Auto-albums

`AlbumAssigner` runs in plain code as soon as the first-pass fields exist, with no model:

1. Find candidate albums: date range overlaps the item's date (with a one-day margin), or the place matches, or they share a specific tag (booking reference, city, person).
2. Score each candidate on date, place and shared tags.
3. **Clear match** → assign. **No match and the item is an anchor** (ticket, booking, appointment) → create a new album titled from the fields ("Delhi trip, Dec 14"). **Unclear** → leave in the general gallery and mark it for the model to decide during the next Enrich run. **No match, not an anchor** → stays in the general gallery.
4. A direct instruction ("save this to my Delhi trip") always wins.

### Search

Full-text search plus tag and date filters. Stretch: on-device embeddings for search by meaning.

## 10. Planner and scheduler

`Planner` is a pure function: given the current albums, items and settings, it returns the full list of triggers that *should* exist. `Scheduler.reconcile()` compares that list with what is registered and adds, updates or cancels to match.

| Memory state | Derived triggers |
| --- | --- |
| Settings: morning call 7:30, night call 22:00 | Two repeating ring alarms, each with its pre-load alarm 90 seconds earlier |
| Trip album with a flight item | Reminder call before departure; geofence on the departure airport; time-based backup for that geofence |
| Checklist with a due date | Reminder at the due time |
| Bill or document with an expiry date | EXPIRY reminders at expiry − 30 days and − 3 days |
| Prescription with a dose schedule | DOSE reminders, each with a missed-dose check 30 min later |
| Habit with an accountability time | ACCOUNTABILITY ring |
| Practice enabled | PRACTICE ring daily |
| Place rows (home, office, gym, clinic) | One geofence each |
| Care mode with a caregiver number | Escalation armed for DOSE reminders |

Because triggers are always recomputed from memory, "my flight moved to 9" is one field update followed by a reconcile. The same reconcile runs after reboot, app update and time zone change.

**Coordinates never come from the model.** Airports come from a bundled table (airport code → city, latitude, longitude). Home and office are set by the user on a map at onboarding. Other places use Android's geocoder, and if that fails the geofence is skipped and the time-based backup still fires.

**Lead times.** "Leave in 20 minutes" needs live travel time, which is out of scope. The hackathon uses a fixed, configurable lead time before departure.

## 11. Ingest pipeline

Split into a fast phase that never needs the model and an enrichment phase that waits for it. A saved ticket becomes an album within seconds without loading 5 GB.

```
PHASE 1, at once, no model                         PHASE 2, when the model is next loaded
QUEUED ─► COPIED ─► TEXT_READ ─► FAST_FIELDS ─► ALBUM ─► waiting ─► ENRICHED
              │          │            │                      │
              │          │            │                      └─ model output fails checks ─► keep
              │          │            │                         phase 1 result, mark "needs review"
              │          │            └─ nothing found ─► plain gallery item, searchable text
              │          └─ little or no text ─► mark for the model to look at the image
              └─ duplicate (same content hash) ─► drop
```

| Step | Detail |
| --- | --- |
| Copy | Stream into `files/media/`, compute a content hash, make a thumbnail |
| Read text | ML Kit OCR on the image. For PDFs, render the first pages to images and OCR them |
| Fast fields | Pattern rules in plain code: dates, times, amounts, airport codes (checked against the bundled table), flight numbers, booking references, and keywords that mark an anchor document ("boarding pass", "invoice", "prescription") |
| Album | `AlbumAssigner` on the fast fields; planner reconcile |
| Enrich | The model, in text mode, proposes a title, the kind of document and extra fields. Each proposed field is checked by plain code (dates must parse, codes must exist) before it is saved. Fields the user edited are never overwritten |

**When phase 2 runs.** Loading the model for every screenshot would cost too much time, memory and battery. Enrichment runs in the next window when the model is loaded anyway or the cost does not matter:

1. During PREPARING before the next call.
2. When the user shares a file while the app is open (a text-only load, with a visible "reading…" state).
3. While the phone is charging and idle.

Bursts are batched: several screenshots within 30 seconds become one job run. The item appears in the gallery as soon as it is copied.

## 12. Surfaces

| Surface | Android mechanism | Template |
| --- | --- | --- |
| Incoming call | Call-style notification + full-screen activity shown over the lock screen | Call screen |
| In-call | `CallActivity` with a live card under the caller view | Call screen + card or list |
| Bubble | Overlay window owned by a short-lived foreground service; a small chip that expands to a card on tap; dismiss by drag | Card + list |
| Notification | Standard notification; fallback for every other surface | — |
| App | Home (today), albums, album detail, timeline, gallery grid, search | All five templates |

**One description for every surface.** Screens are drawn from a `SurfaceSpec`: a small data structure listing blocks (card, checklist, timeline, gallery grid) and the item ids they show. For the hackathon, app code builds the spec from fixed rules per moment. Later, an agent can emit the same structure, which is the planned generated UI, with no change to the rendering code.

**Bubble limits to design around**

- Overlays cannot appear over the lock screen; the situation engine sends a lock-screen notification instead and shows the bubble on unlock.
- The overlay needs the "display over other apps" permission, asked for with a clear reason at onboarding.
- Compose inside an overlay window needs its lifecycle owners set up by hand; build this once in `BubbleService`.
- The bubble removes itself after 2 minutes without interaction.
- The bubble never needs the model.

## 13. Offline behaviour and model availability

**Network.** Cue does not use the network for anything in the hackathon build except the one-time model download in a real release (the demo phone gets the files over USB). Every function works in airplane mode.

**The thing that can be missing is the model.** It may be unable to load because memory is low, the phone is hot, the battery is low, the files are missing, or loading took too long. The contract reports all of these as one "brain not available" result from `prepare()`, and the fallbacks below are written against that result. A cloud provider with no network reports the same thing, so the same fallbacks cover it.

| Function | Model loaded | Model not available |
| --- | --- | --- |
| Triggers firing | Works | Works |
| Ringing | Works | Works |
| Opening briefing | Spoken by the model | Built from a template using database facts ("You have three things today…") and spoken by Android's built-in voice |
| Conversation | Full | RulesBrain handles the call: Android speech recognition, phrase rules for every tool, template replies by text-to-speech. Only if speech recognition is also unavailable: tap-to-tick checklist and a voice note filed later |
| Screenshot and share filing | Phase 1 at once, phase 2 in the next model window | Phase 1 only; phase 2 waits |
| Post-call filing | Right after hang-up | Waits for the next model window |
| Bubble | Works | Works |
| Search | Works | Works |

**Time limits.** Load: 120 seconds, then the fallback. A Converse turn: listening cue at 0.3 seconds, a spoken "one second" at 4 seconds, turn abandoned with an apology at 10 seconds. File: 90 seconds. Enrich: 30 seconds per document.

## 14. Background execution, memory and battery

Cue does not need to stay alive to work. Alarms, geofences and the screenshot job each start the process when needed. Foreground services run only while something is live.

| When | Foreground service | Type |
| --- | --- | --- |
| Model pre-load before a call | `CallService` | `specialUse` |
| A call is in progress | `CallService` | adds `phoneCall` + `microphone` on accept |
| Filing and enrichment with the model loaded | `CallService` or a WorkManager foreground job | `specialUse` |
| A bubble is on screen | `BubbleService` | `specialUse` |
| Otherwise | None | — |

**Why the app is allowed to start work from the background.** Android blocks most background starts, with exemptions for exactly our entry points: an exact alarm firing, a geofence event, a full-screen intent notification, and apps holding the overlay permission or unrestricted battery use.

**Memory.** Loading 5.2 GB pushes other apps out of memory, and if the phone is short on memory Android may refuse or kill the load. The host checks free memory first, loads only inside a foreground service, and unloads the moment the work is done. No persistent keep-alive service: holding the model in the background is not possible, and a warm process without the model gains little.

**Battery and heat.** A call runs the processor and GPU hard for its whole length. Heat over a 5-minute session is not measured yet (spike). Guards: the 3-minute call cap; no model load when Android reports a hot phone or the battery is under 15%; enrichment batches only while charging, during a call's preparation, or when the app is open.

**Battery optimization.** Onboarding asks the user to set Cue to unrestricted battery use. Without it, Android can delay jobs and geofences and, over time, restrict alarms.

## 15. Permissions and onboarding

Asked one at a time, each with one sentence on what it enables, in this order. Every one has a fallback so the app still works without it.

| # | Permission | Enables | If refused |
| --- | --- | --- | --- |
| 1 | Notifications | Everything visible | App works only when opened |
| 2 | Microphone | Talking on calls | Calls become read-only briefings |
| 3 | Exact alarms | Calls at the set time | Calls may be late |
| 4 | Full-screen notifications | Call screen over the lock screen | Heads-up call banner only |
| 5 | Unrestricted battery | Reliable background work and model loading | Delays possible |
| 6 | Photos (full access) | Screenshot filing | Save through the share sheet only |
| 7 | Location, then "Allow all the time" | Arrival moments | Time-based backups only |
| 8 | Display over other apps | Bubble | Notifications instead |
| 9 | Notification access (optional) | People memory | People memory off; everything else works |
| 10 | SMS (care mode only) | Caregiver nudge after two missed doses | Notification on the recipient's phone instead |
| 11 | Accessibility (stretch) | Voice "save this" | Share sheet only |

**Model setup step.** Onboarding also checks that the four model files are present and match their SHA-256 hashes, and that there is enough free storage. On the demo phone the files are pushed over USB in advance. A real release needs a resumable, Wi-Fi-only download of 4 GB here.

**Demo phone preparation.** Grant everything in advance. Push the model and open the app once so the one-time file copy is done. For Accessibility on a sideloaded build, first open App info → ⋮ → "Allow restricted settings". Run the debug panel's check before every rehearsal.

## 16. Privacy, security and license

| Data | Where it lives | What leaves the phone |
| --- | --- | --- |
| Images and PDFs | App-private files | Nothing |
| Text read from documents | Database | Nothing |
| Voice audio | In memory during the call, discarded after filing | Nothing |
| Call transcripts | Database | Nothing |
| Location | Used on the phone for geofences | Nothing |
| Notes, checklists, journal | Database | Nothing |

- **"Your memories never leave your phone"** is true for this build and can go in the pitch. It stops being true for documents and transcripts if the optional cloud helper in section 8 is switched on. One explicit, user-configured exception: in care mode, a short SMS goes to the caregiver's number after two missed doses (no document or transcript content).
- Notification text read for people memory is processed and stored on the phone only.
- The app's data is excluded from cloud backup.
- Logs never contain document text or transcripts in release builds.
- "Delete everything" in settings wipes the database, the files and all registered triggers.
- Content from documents and screenshots is always treated as data. The jobs that read it have no tools (section 8).
- Not built for the hackathon, needed for a real release: database encryption.

**Model license.** The weights are under the LFM Open License v1.0. It allows free commercial use for organizations with under $10 million in annual revenue; at or above that, a separate commercial license from Liquid AI is required. Motorola and Lenovo are above the threshold, so shipping this model in a Motorola product needs an agreement with Liquid AI. Read the full terms at liquid.ai/lfm-license before the pitch, and say this plainly if asked. The engine code from Google's sample is Apache-2.0.

## 17. Compute, battery and storage budget

There is no per-call money cost any more. The scarce resources are now the phone's.

| Resource | Control |
| --- | --- |
| Load time | Pre-load alarm before every call; one-time file copy done at setup |
| Memory (5.2 GB) | Load on demand, unload at once; one job at a time; memory check before loading |
| Context (4,096 tokens) | Fixed budget per part; five small tools; one album in the context pack; wrap up at 85% |
| Heat and battery | 3-minute call cap; no load when hot or under 15% battery; enrichment batched into existing model windows |
| Storage (about 7 GB) | Checked at onboarding; shown in settings |
| Model loads per day | Two calls plus at most one charging-time batch by default |
| Visibility | Every run logged in `model_run`; the debug panel shows load time, reply delay, tokens, memory and temperature |

## 18. Failure and edge-case matrix

| Situation | Behaviour |
| --- | --- |
| Phone is off at call time | On boot, reconcile; if the slot was missed by under 30 minutes, pre-load and ring; otherwise send a notification |
| Phone in Doze | Alarm-clock alarms still fire on time; the pre-load alarm is allowed while idle |
| Model not loaded when the ring alarm fires | Ring anyway; scripted briefing if accepted early; switch to the model when ready |
| Model cannot load (memory, heat, battery, missing files) | Scripted briefing; checklist with tap-to-tick; voice note for later filing |
| Android kills the process while the model is loaded | Call ends; clips and text saved so far are filed in the next model window |
| User on a real call at ring time | Unload, defer 10 minutes |
| Do Not Disturb or quiet hours | Silent notification; no ring; no model load |
| Declined twice | Notification with the briefing as text; no more rings for that slot |
| Reply is slow | Listening cue at 0.3 s; spoken filler at 4 s; apology at 10 s |
| Model emits a broken or invalid tool call | Ignored; the File job recovers the item from the transcript |
| Model says something wrong about the user's data | Facts in the briefing come from the database through the context pack; the on-screen card shows the real data |
| Conversation fills the context | Agent wraps up at 85%; hard stop at 3 minutes |
| End of speech detected too early or too late | Silence threshold tuned in the spike; on-screen "tap when done" button as a backup |
| Speech misunderstood | The user's clip is transcribed again after the call, and the note keeps that text |
| Same screenshot saved twice | Dropped by content hash |
| Screenshot with no useful text | Plain gallery item; marked for the model to look at the image later |
| Pattern rules find the wrong date or code | Model enrichment and plain-code checks may correct it; the user can edit, and edits are never overwritten |
| Model enrichment contradicts the pattern rules | Keep the pattern result, mark "needs review" |
| Item fits two albums | Left in the general gallery until the model decides; the user can move it |
| Geofence fires late or never | Time-based backup trigger fires the same event |
| Geofence fires repeatedly at the boundary | Dedupe key plus a one-hour cooldown per place |
| Location turned off | Geofences paused; backups still fire; banner in the app |
| Time zone changes after landing | Reconcile recomputes local-time alarms; event times keep their own zone |
| Flight time changes | `event_move` → planner reconcile moves every derived trigger |
| Permission revoked later | Detected on next launch and before each use; surface falls back per section 15 |
| Storage almost full | Stop copying new files; notify; existing memory is unaffected |
| User force-stops the app | Nothing fires until the next launch; then reconcile and a notice |
| Two triggers at the same moment | Queue by priority; the second shows after the first ends |
| Phone folded or unfolded mid-call | Call continues; the screen layout follows the active display |

## 19. Demo flows end to end

**1. Save a flight ticket screenshot** (no model)

```
Screenshot saved ─► MediaStore job ─► TriggerEvent ─► SituationEngine: SILENT
 ─► IngestWorker: copy ─► OCR ─► pattern rules
 ─► {anchor: boarding pass, from: BLR, to: DEL, depart: Dec 14 06:00, ref: …}
 ─► AlbumAssigner: no match + anchor ─► new album "Delhi trip, Dec 14"
 ─► Planner: reminder call + airport geofence + backup ─► Scheduler registers them
 ─► UI updates through database flows: album appears
 (later, in the next model window: Enrich adds a cleaner title and extra fields)
```

**2. Night call**

```
Pre-load alarm (T−90 s) ─► CallService loads the model ─► Enrich waiting documents
 ─► context pack ─► model generates the opening line as audio
Ring alarm (T) ─► SituationEngine: RING ─► call notification rings ─► user accepts ─► mic opens
 ─► opening line plays at once
 ─► user: "pack charger and passport, and remember the hotel is near Connaught Place"
 ─► listening cue ─► model: spoken reply + checklist_add + note_save
 ─► tool calls parsed, validated, executed ─► card on the call screen updates live
 ─► hang up ─► transcribe the user's clips ─► File job ─► journal entry linked to today's photos
 ─► unload the model ─► album shows the checklist, the note and the journal entry
```

**3. Morning call**

```
Pre-load alarm ─► model loads ─► context pack includes last night's summary and the numbered list
Ring alarm ─► accept ─► agent reads today's plan and the list
 ─► user: "I've packed the charger" ─► checklist_tick ─► card updates
 ─► hang up ─► File ─► unload ─► today's list stays on screen as a card
```

**4. Arrive at the airport** (no model)

```
Geofence entry (or TriggerSimulator in the demo) ─► TriggerEvent
 ─► SituationEngine: phone unlocked ─► BUBBLE
 ─► SurfaceSpec built from local data: boarding pass card + unticked entries
 ─► BubbleService shows the chip ─► tap ─► expands to the card
```

The whole demo runs in airplane mode.

## 20. Observability and testing

**Debug panel (built in the first slice, hidden in release builds)**

- **Trigger simulator for every trigger** (the demo never depends on real GPS or real time): morning call, night call, pre-load, arrival at airport / home / office / gym / clinic, new screenshot from bundled samples (ticket, prescription, bill, café), share, leave-by, dose due, dose missed twice (escalation), accountability call, practice call, expiry due, a message from a sample person (people memory), reset and seed demo data.
- Brain status and switch per job: RulesBrain, Lfm3Brain (files found / loaded / failed), cloud (off).
- Load and unload the model by hand; show its state, memory use and the phone's temperature state.
- Event log from `trigger_event`: what fired, what was decided, what happened.
- Per-turn timings: speech end, listening cue, first sound, first word, reply end; context tokens used.
- Raw model text for each turn, including tool-call blocks and whether they parsed.
- Permission, battery-setting and model-file status.
- Registered alarms and geofences compared with what the planner expects.

**Testing**

| Layer | How |
| --- | --- |
| `:agent` (situation engine, planner, album assigner, context builder, pattern rules, tool handlers) | Plain unit tests with a fake clock and the `:brain:rules` provider |
| Brain contract | One scripted suite of phrases, expected tool calls and expected fields, run against every provider. A provider must pass it before it can be switched in |
| Module boundaries | A build check that fails if a module imports another module's internals |
| Model behaviour | The sample's `AutoRun` measuring tool: a scripted plan of spoken phrases, scored for tool calls, accuracy and timing |
| Triggers | `adb shell dumpsys deviceidle force-idle` for Doze; standby-bucket changes; reboot test |
| Ring reliability | Overnight run of scheduled rings with the screen off, with and without the model pre-loading |
| Memory pressure | Load the model with several heavy apps open; confirm the fallback path |
| Demo | Full script twice in a row from a clean install, in airplane mode; backup screen recording |

## 21. Risk spike plan

Three experiments on the demo phone before any screen design. S2 has started; its first numbers are in [lfm3-omni-spike.md](lfm3-omni-spike.md).

| Spike | Question | Method | Pass |
| --- | --- | --- | --- |
| S1 Ring | Does the call ring on time with the screen off, in Doze, with the app swiped away, while the model loads in the background? | 20 scheduled rings overnight with the pre-load alarm; repeat with Core-Telecom if it fails | 19 of 20 within 5 seconds |
| S2 On-device voice | Can LFM3-Omni-2B carry a check-in call? | See the checklist below | See the checklist below |
| S3 Survival | Do triggers survive a day of normal use, and does loading 5.2 GB twice a day cause trouble? | 24 hours with battery setting on and off; one reboot; one time zone change; watch for killed loads | No trigger lost; no failed load |

**S2 checklist**

| # | Check | Pass | Status |
| --- | --- | --- | --- |
| 1 | Voice-only conversation (no photo) works | A spoken question gets a spoken answer | Open |
| 2 | Spoken question → first word | 2.5 seconds or less, median | Open (typed question → first sound: 2.7 s) |
| 3 | Load time on later starts | Sets the pre-load lead; 60 seconds or less | Open (first start: 48.4 s) |
| 4 | Speech generation keeps ahead of playback | No stutter over 20 replies | Open |
| 5 | Heat over a 3-minute call, three calls in a row | Phone does not reach Android's "severe" state | Open |
| 6 | Tool calls from speech: 20 phrases covering the five tools | 16 of 20 correct, and the File job recovers the rest | Open |
| 7 | Understanding Indian-accented English and place names: 20 phrases | 17 of 20 understood | Open |
| 8 | Tokens used per minute of conversation | A 3-minute call fits the 2,700-token conversation budget | Open |
| 9 | File job on 5 sample transcripts | Tasks, notes and journal correct on 4 of 5 | Open |
| 10 | Enrich on 10 real tickets and bills | No wrong field passes the plain-code checks | Open |
| 11 | Text-only load: memory and time | Recorded; decides whether enrichment can run outside call windows | Open |
| 12 | NPU build | Recorded; adopt only if faster | Open (sample README: no faster than GPU on a Galaxy S26) |

Outcomes that change this document: S1 picks the ring mechanism (section 6); S2 sets the pre-load lead, the reply-delay target, the tool list and whether the cloud helper is needed (sections 6 to 8); S3 confirms the load-on-demand design (section 14).

## 22. Build order

All slices run on RulesBrain first, so every flow works end to end before the model is integrated.

| Slice | Delivers | Use cases unlocked |
| --- | --- | --- |
| 0 | All modules with contracts; `:brain:api`; RulesBrain; Room database; `CueGraph`; debug panel and trigger simulator | — |
| 1 | Alarm → ring → accept → In call with Android speech recognition and text-to-speech → After call | Check-in calls |
| 2 | Tools: checklist, note, reminder, event_move, habit_checkin, dose_schedule, pending, album_search; File job (rule-based journal) | Check-ins, accountability, health doses |
| 3 | Share target and screenshot ingest → ML Kit OCR → pattern rules → auto-album → planner | Screenshot brain, save from any app, travel, health, life admin |
| 4 | Planner and scheduler: leave-by, expiry, dose and missed-dose, accountability, practice; geofences per place | Travel, health, life admin, arrival moments |
| 5 | Bubble overlay; notification listener; caregiver SMS | Airport and clinic bubbles, people memory, family circle |
| 6 | Compose screens in the Bloom-with-depth style: Home, Apps with pinning, Albums, Album detail, Journal, Search, Lists, Habits, Reminders, Papers, Renewals, Focus, Practice, onboarding | Apps and pinning |
| 7 | Lfm3Brain behind the contract: voice-only conversation, tool-call parser, pre-load alarm | Free conversation, English practice |
| 8 | Fallbacks, rehearsals in airplane mode, backup recording | Demo safety |

## 23. Open decisions

| # | Decision | Default in this draft | Decided by |
| --- | --- | --- | --- |
| 1 | Reply-delay target | Listening cue in 0.3 s, first word in 2.5 s (was 1.5 s) | You, after spike S2 |
| 2 | If the on-device voice misses its target | Switch the Converse job to a cloud provider in the `BrainRouter` (section 3.5): cloud text with the phone's own speech recognition and voice, or a cloud realtime voice service. `:brain:cloud` has to be built first | Spike S2 |
| 3 | Cloud provider for File and Enrich | Off | Spike S2 checks 9 and 10 |
| 8 | Which cloud provider to build if one is needed | Claude for text jobs; no realtime voice service chosen | Where the company credits are |
| 4 | Ring mechanism | Call notification with full-screen intent | Spike S1 |
| 5 | Model runtime | LiteRT build, as in the spike. Liquid also publishes a GGUF build for llama.cpp, which may use less memory | Only if memory or the 4K context blocks us |
| 6 | Model license for a Motorola product | Not resolved | Liquid AI and Motorola; read the terms before the pitch |
| 7 | Privacy as a pitch line | Now true by default; proposed for the pitch | You |

Resolved since revision 1: demo phone (razr fold 2026, Android 17); LLM provider (on-device LFM3-Omni-2B, so company credits are no longer needed); cheaper cloud model question (no longer applies).

## 24. Later, outside the hackathon

| Topic | How this design gets there |
| --- | --- |
| OEM integration | System-level triggers and surfaces behind the same interfaces. Motorola could also keep the model loaded in a system service and run it on the NPU, which removes the load wait and most of the memory problem. This is the strongest OEM pitch point |
| Larger context | The model supports 32K tokens; a LiteRT export with a larger context would allow richer memory in each call |
| Hindi voice | Not available in this model's speech output; needs a model update or a separate voice |
| Model delivery | First-run download with resume, Wi-Fi only, hash check |
| Generated UI | An agent emits `SurfaceSpec`; rendering code is unchanged |
| Kotlin Multiplatform | `:domain`, `:brain:api` and `:agent` already have no Android imports |
| A better on-device model | A new provider module behind the brain contract; nothing else changes |
| People memory | A notification-listener trigger feeding the same event pipeline, with its own consent screen |
| Family circle | Needs accounts and a small backend |

## 25. Use case to component map

| Use case | Triggers (`:triggers`) | Agent (`:agent`) tools and rules | Memory (`:memory`) tables | Surfaces |
| --- | --- | --- | --- | --- |
| Morning and night check-in calls | Alarm-clock alarms, pre-load alarm | Context pack, `checklist_add`, `checklist_tick`, `note_save`, `reminder_set`, File job | `call_session`, `checklist_entry`, `item` (NOTE, JOURNAL) | Incoming call, In call, After call |
| Screenshot brain | MediaStore content-URI job | Pattern rules, AlbumAssigner, `album_search` | `item`, `item_fts`, `tag`, `album_item` | Search, Albums |
| Save from any app | Share target activity | Same as screenshot brain | Same | Save sheet, notification |
| Travel companion | Ingest, LEAVE_BY reminder, airport geofence + time backup | Planner, `event_move`, `checklist_tick` | `album` (TRIP), `item` (ticket), `reminder`, `trigger` | Ring, Bubble, Album detail |
| Doctor and health visits | Ingest, clinic geofence, DOSE reminders + missed-dose check | `dose_schedule`, `habit_checkin` | `album` (HEALTH), `reminder` (DOSE), `habit`, `habit_log` | Bubble, notification, Ring |
| Life admin | Ingest, EXPIRY reminders | Pattern rules for warranty, `album_search` | `item` (BILL), `reminder` (EXPIRY) | Renewals, Search, morning call |
| People memory | `NotificationListenerService` | Pending-item pattern rules, `pending_add`, `pending_resolve` | `person`, `pending_item` | Bubble |
| Accountability calls | ACCOUNTABILITY alarm | `reminder_set`, `habit_checkin` | `reminder`, `habit`, `habit_log` | Ring, Habits |
| Arrival moments | Geofences per `place` | Place-linked `reminder_set`, gym auto check-in | `place`, `reminder` | Bubble |
| Family circle (care mode) | DOSE reminders, missed-dose check | Escalation rule | `person` (caregiver), `habit_log`, `trigger_event` | Ring, SMS |
| English practice | PRACTICE alarm | Practice mode (Lfm3Brain) or scripted lesson (RulesBrain) | `habit_log`, `item` (NOTE with corrections) | Ring, In call, After call |
| Apps and pinning | User | Navigation intents ("open my journal") | DataStore `pinned_apps` | Home, Apps, bottom bar |

