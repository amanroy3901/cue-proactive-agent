# Cue — One-Page PRD

Nov 13, 2026 · Status: build spec, revision 3 · Companion docs: [ux-flows.md](ux-flows.md) (every flow in detail), [use-cases.md](use-cases.md), [architecture.md](architecture.md), [lfm3-omni-spike.md](lfm3-omni-spike.md)

## Problem

Phone assistants wait to be asked. People forget to ask, so the ticket stays buried in the gallery, the packing list stays in their head, and the reminder never gets set. Most agent products today target business workflows, not everyday life.

## Product in one line

Cue is a personal agent for Motorola and Lenovo phones that remembers your documents, notes and plans, and comes to you at the right moment: it rings like an incoming call, or pops a bubble with exactly what you need.

## Target user

A Motorola phone owner who saves tickets, bills and screenshots, plans by memory, and would rather talk for a minute than type. Also their parents: Cue can run in care mode on an older family member's phone. Personal use only.

## Why Motorola should care

Gemini answers when you ask. Cue knows your stuff, knows where you are, and shows up on its own. It runs entirely on the phone: tickets, bills and conversations never leave the device. Twice-a-day calls and daily screenshot filing make it a habit, not a feature, which is a daily reason to pick a Moto phone and drives Qira usage.

## Use cases (all in scope for the build)

Hero: **morning and night check-in calls**, shown through the **travel** demo. Every other use case rides on the same engine: a trigger, memory, then a call or a bubble. Full flows are in [ux-flows.md](ux-flows.md).

| # | Use case | Trigger | Surface |
| --- | --- | --- | --- |
| 1 | Morning and night check-in calls | Time (7:30 AM, 10:00 PM) | Ring-first call |
| 2 | Screenshot brain | New screenshot | Silent filing, search later |
| 3 | Save from any app | Share sheet | Silent filing |
| 4 | Travel companion | Ticket saved; leave-by time; airport geofence; "my flight moved to 9" | Album, ring, bubble |
| 5 | Doctor and health visits | Prescription saved; clinic geofence; dose times | Album, bubble, dose reminders, missed-dose call |
| 6 | Life admin | Bill saved; expiry dates | Renewals, ring before expiry, "where's my AC bill?" |
| 7 | People memory | Messaging notifications | Bubble when that person messages you |
| 8 | Accountability calls | "Call me at 6 and check if I went to the gym" | Ring, habit streak |
| 9 | Arrival moments | Home / office / gym geofence | Bubble with items for that place |
| 10 | Family circle (care mode) | Medicine times on a parent's phone | Ring; SMS to the caregiver after two misses |
| 11 | English speaking practice | Daily practice time | 5-minute ring-first call |
| 12 | Apps and pinning | User | Agent-first home, Apps screen, up to 3 pinned apps in the bar |

## Apps and pinning

Home is the agent: the next call, Cue's sentence, today's items, the habit strip. Everything else is an app Cue runs: Lists, Habits, Journal, Albums, Reminders, Papers, Renewals, Focus, Practice, Search. The bottom bar is Home, up to 3 pinned apps, Talk in the centre, and Apps. Most navigation happens by asking Cue ("open the Delhi trip").

## Visual direction

Round 3 "Bloom with depth" on the design canvas. Colours have roles: **deep teal** is Cue (trust, calm, privacy) and its actions; **coral and apricot** mark anything time-sensitive (the next call, the morning call); a **warm cream** base instead of white; **one colour per app** (Lists teal, Habits marigold, Journal coral, Albums periwinkle, Reminders rose, Papers sand, Renewals mint, Focus violet). Depth comes from three surface levels, colour-tinted shadows, glossy icon chips and a frosted bottom bar. The screen after the night call is dark.

## Goals for the hackathon

1. Prove the engine: trigger → agent → surface, on a stock Motorola phone with no OEM access.
2. Show the hero ritual: morning and night check-in calls with full memory context.
3. Run the 3-minute travel demo end to end, twice in a row, with no manual rescue.
4. Every other use case above works end to end on the phone, triggered for real or from the debug panel.

## Demo success criteria

| Measure | Target |
| --- | --- |
| Call rings with the screen off and the app in the background | 19 of 20 attempts, within 5 seconds of the set time |
| Opening briefing after the user accepts | starts at once (prepared before the ring) |
| Voice reply delay (user stops talking → agent's first word) | RulesBrain: under 1 second. LFM3: listening cue within 0.3 s, first word within 2.5 s |
| Ticket screenshot → album with trip details | under 10 seconds, without loading the model |
| Night call → checklist, note and journal entry in the album | all three, every run |
| Airport arrival → bubble with boarding pass and unticked items | every run |
| Network | the whole demo runs in airplane mode |

## Key product rules

- **Ring first, never talk unannounced.** No voice until the user accepts.
- **A declined call is a signal.** Snooze and retry, then fall back to a notification.
- **Triggers are plain code.** The model is loaded only for a call or to process saved documents.
- **Everything stays on the phone.** The only exception is the caregiver SMS in care mode, which the user sets up explicitly.
- **The ring never depends on the model.** RulesBrain is always available; if even that fails, a scripted briefing is read aloud.

## Decisions

| Decision | Choice |
| --- | --- |
| Agent model | LiquidAI LFM3-Omni-2B on the phone (Lfm3Brain), with RulesBrain as the always-available path |
| Swappable model | Every model behind one contract in its own module, including tool calls |
| App structure | Independent Gradle modules with published contracts |
| Stack | Kotlin 2.2.21 + Jetpack Compose, Android only, minSdk 31, package `com.cue.app` |
| Demo scenario | Travel |
| Backend | None |
| Demo phone | motorola razr fold 2026, Android 17 |
| Visual direction | Round 3 Bloom with depth |

## Open decisions

1. **Reply-delay target on LFM3.** 2.5 s to the first word proposed; confirm on the phone.
2. **Cloud provider for any job.** Allowed per job; off by default.
3. **Model license.** Free commercial use only under $10M annual revenue; a Motorola product needs an agreement with Liquid AI.

## Top risks

1. Ringing reliably while the app is in the background or the phone is idle.
2. The on-device model: reply delay, 5.2 GB of memory, load time, heat, accuracy.
3. Battery optimization stopping background work.
4. Sensitive permissions (notification access, SMS, overlay) that users may refuse; every one has a fallback.

## Milestones

1. PRD, flows and architecture (done)
2. Build all flows on RulesBrain, test on the phone
3. Integrate Lfm3Brain and measure
4. Demo prep with a backup recording
