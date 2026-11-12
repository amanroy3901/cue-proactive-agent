# Proactive Agent — Hackathon Project Context

Handoff from a planning chat. Read this first, then the docs: `docs/prd.md` (one-page PRD), `docs/ux-flows.md` (every flow end to end: triggers, screens, agent turns, tool calls, database reads/writes), `docs/use-cases.md` (use cases), `docs/architecture.md` (architecture, revision 4), `docs/lfm3-omni-spike.md` (on-device model spike: paths, build setup, measurements). The product name is **Cue**.

**Where this file and the architecture disagree, the architecture wins.** It was revised after the user chose an on-device model: the agent's brain is LiquidAI LFM3-Omni-2B running on the phone, not a cloud LLM, and there is no always-on foreground service.

## What we're building
A proactive personal AI agent for Motorola/Lenovo phone users, as an Android app. It remembers the user's life (documents, notes, checklists, journal) and shows up on its own: it **rings like an incoming call** at the right moment, or pops a **bubble overlay** with exactly what's needed.

- Personal use, not business. Pitch to Motorola: "Gemini answers when you ask; this agent knows your stuff, knows where you are, and comes to you." Drives daily usage of the Qira app.
- Built **from scratch** as a standalone Android app. No OEM permissions, no Motorola APIs, no Qira infra (limited company credits). OEM integration is a future pitch point, not a dependency.

## Decisions made
- **Ring first, never talk unannounced.** Proactive triggers show an incoming-call screen; the user accepts before any voice starts. Declined call = snooze/retry or fall back to a notification.
- **Triggers are plain Android code** (alarms, geofences, file watchers). The LLM runs only when a trigger fires or the user speaks, which keeps credit cost low.
- **Memory layer = 4 item types** (gallery, note, checklist, journal), all tagged (place, person, date, event) and auto-grouped into **albums that mix types**. Example: "Delhi trip" album holds ticket image + packing checklist + hotel note + that day's journal.
- **Adaptive surfaces:** each item type has a fixed template for the hackathon (card, list, timeline, gallery grid, incoming-call screen). Later: agent emits a JSON layout rendered with the same components (generated UI). Don't build generated UI now.
- **Hero use case: morning and night check-in calls** (~60s each). Morning: today's plan + checklist ticked by voice. Night: user talks; agent files tasks → checklist, facts → notes, day → journal.
- **Demo scenario: travel** (ticket → album → night call → morning call → airport geofence bubble with boarding pass). Open question in the doc whether to switch to health visits.

## In scope for the hackathon (updated 2026-11-13)
Every use case in `docs/ux-flows.md`: check-in calls (hero), screenshot brain, save from any app, travel companion (demo), doctor and health visits, life admin, people memory (notification listener), accountability calls, arrival moments, family circle (care mode on the parent's phone, SMS to a caregiver after two missed doses), English speaking practice, and the Apps + pinning model.

## Out of scope for now
Agent-generated UI, OEM integration, any backend or account sync, multi-device family circle.

## Design direction (chosen 2026-11-13)
Round 3 "Bloom with depth" on the design canvas https://claude.ai/artifact/Q7JAtviuJ3UcRsWPvc8CNM (page Round 3, `r3-bloom2-*` artboards). Colour roles: deep teal = Cue and its actions (trust, calm), coral/apricot = time-sensitive, warm cream base, one colour per app (Lists teal, Habits marigold, Journal coral, Albums periwinkle, Reminders rose, Papers sand, Renewals mint, Focus violet). Depth: three surface levels, colour-tinted shadows, glossy icon chips, frosted bottom bar; dark screen after the night call. Font: Plus Jakarta Sans.

## Build decisions (2026-11-13)
- App source lives in the project root (this folder). Package `com.cue.app`.
- Kotlin 2.2.21, AGP 8.9.1, Gradle 8.11.1, minSdk 31, compileSdk 36, arm64-v8a only, LiteRT 2.3.0, Room 2.8 with KSP, Jetpack Compose.
- DI: hand-written service locator `CueGraph` in `:app` (not Koin).
- Brains: **RulesBrain** (`:brain:fake`, deterministic intent parser + Android SpeechRecognizer/TextToSpeech) is always available and the guaranteed demo path; **Lfm3Brain** (`:brain:ondevice`) runs when model files are in `/sdcard/Android/data/com.cue.app/files/`; `:brain:cloud` is a placeholder. `BrainRouter` picks per job.
- Debug panel with a trigger simulator for every trigger; the demo never depends on real GPS or real time.
- Demo timeline: flight Sun 14 Dec 6:00 PM, morning call 7:30 AM, night call 10:00 PM.

## Android implementation notes (no OEM access)
| Need | Approach |
| --- | --- |
| Time triggers | AlarmManager exact alarms (exact-alarm permission on newer Android) |
| Location triggers | Geofencing API |
| Ring like a call | Self-managed ConnectionService or full-screen intent notification |
| Bubble | Draw-over-other-apps (SYSTEM_ALERT_WINDOW) |
| Stay alive | Foreground service + ask user to exempt from battery optimization in onboarding |
| Screenshot brain | Watch the screenshots folder (media permission) |
| Storage | App-private storage for the memory layer (no MediaStore/Downloads needed) |

### "Save this PDF / save this" from other apps
- **Primary path: register as a share target** (ACTION_SEND / SEND_MULTIPLE for `application/pdf` and `image/*`). Works in WhatsApp, Gmail, Drive, Files; we get the real file via content URI.
- **Voice "save this":** AccessibilityService only for a narrow, user-initiated action: read screen text or `takeScreenshot()` (API 30+) and file it.
- **Do NOT** rely on clicking "Download" buttons by label (brittle across app versions/locales) or reading Chrome's URL bar + DownloadManager (fails for WhatsApp/Gmail/Drive: private content URIs or authenticated links).
- **Play policy:** Google Play prohibits using the Accessibility API to let an app autonomously initiate, plan and execute actions (reinforced Oct 2025). Keep Accessibility out of the proactive path. Fine for a sideloaded hackathon build.
- **Demo gotcha:** Android 13+ blocks enabling Accessibility / notification access for sideloaded apps until the user taps App info → ⋮ → "Allow restricted settings". Do this on the demo phone beforehand.

## Biggest risks (do a risk spike before UI work)
1. Call screen ringing reliably while the app is backgrounded / phone idle.
2. Voice latency low enough to feel like a live call.
3. Battery optimization killing the background service.

## Process we agreed on
1. One-page PRD (draft: `docs/prd.md`) → 2. Use cases (done: `docs/use-cases.md`) → 3. Architecture (draft: `docs/architecture.md`) → 4. Risk spike → 5. Key screens → 6. Build in vertical slices, one use case end to end first → 7. Demo prep with a backup recording.

## Decided by the user (2026-11-12)
- **Model: LiquidAI LFM3-Omni-2B on the phone** (LiteRT 2.3.0 build). One model does speech in, reasoning and speech out. No cloud LLM by default, no credits, everything works in airplane mode.
- Demo phone: motorola razr fold 2026, Snapdragon SM8845, Android 17.

- **Modular, microservice-style architecture with a swappable model.** Independent Gradle modules with published contracts (`:domain`, `:brain:api`, `:brain:ondevice`, `:brain:cloud`, `:brain:fake`, `:agent`, `:memory`, `:triggers`, `:call`, `:ingest`, `:surfaces`, `:app`). Every model sits behind one brain contract in `:brain:api`, which also owns the tool-call loop, so a cloud provider can replace the on-device model per job without touching other modules. Details and rules: `docs/architecture.md` section 3. When writing code: no module imports another's internals; only `:memory` touches Room; only `:brain:*` providers touch a model; `:agent` never checks which provider is running.

## Consequences the design is built around
- The model needs about 5.2 GB of memory and took 48 s to load the first time, so it is loaded 90 s before each call (a pre-load alarm) and unloaded right after. It never stays resident.
- 4,096-token context in the LiteRT build: five small tools, a small context pack, 3-minute call cap.
- Small model, weak tool calling: plain code validates everything; a post-call filing pass catches what in-call tool calls missed; document fields come from OCR plus pattern rules first, and the model only enriches later.
- Measured reply delay (2.7 s to first sound) misses the original 1.5 s target; proposed new target is 2.5 s to first word with an instant listening cue.
- Model weights are under the LFM Open License v1.0 (free commercial use only under $10M annual revenue): a Motorola product needs an agreement with Liquid AI.

## Proposed, awaiting the user's confirmation
- Stack: Kotlin + Jetpack Compose, Android only. No Rust or C++ of our own.
- Demo scenario: travel.
- Revised reply-delay target (above).

## Still undecided
- Whether on-device voice passes the spike; fallbacks are in `docs/architecture.md` section 23.
- Whether an optional cloud helper is needed for post-call filing and document enrichment (off by default).
- Ring mechanism: call-style notification with full-screen intent vs Core-Telecom (risk spike S1).

## Next step
Build order is `docs/architecture.md` section 22: every flow on RulesBrain first, tested on the connected razr fold, then Lfm3Brain integration (risk spike S2 checklist in section 21).
