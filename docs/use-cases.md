# Proactive Agent — Use Case Document

Nov 12, 2026 · Source doc: https://claude.ai/artifact/R8qbP2zCunx7zbYninegPZ

## Overview

A proactive personal agent for Motorola and Lenovo phone users that remembers your life and shows up on its own: it rings you at the right moment, or pops a bubble with exactly what you need.

- **Who it's for:** personal users, not businesses. Most agent bots today are built for commercial workflows; this one is built for everyday life.
- **What it does differently:** it doesn't wait to be asked. Time and location triggers wake it up, a memory layer gives it context, and it reaches you through a ring-first call or an on-screen bubble.
- **The pitch to Motorola:** Gemini answers when you ask. This agent knows your documents, notes and plans, knows where you are, and comes to you. That's a daily reason to choose a Moto phone, and a daily habit that drives usage of the Qira app.
- **Hackathon constraint:** built from scratch as a standalone Android app with no OEM permissions or Motorola APIs. OEM-level integration later makes it faster and more battery-efficient, but the demo must work without it.

## Core engine

```
 Triggers                     Agent                        Surfaces
 ┌───────────────────┐       ┌──────────────────────┐      ┌──────────────────┐
 │ Time (alarms)     │       │ LLM runs only        │      │ Ring-first call  │
 │ Location (geofence)│ ───▶ │ when a trigger fires │ ───▶ │ Bubble overlay   │
 │ New screenshot    │       └──────────┬───────────┘      │ Silent filing    │
 │ User speaks       │                  │ reads/writes     └──────────────────┘
 └───────────────────┘       ┌──────────▼───────────┐
                             │ Memory: gallery,     │
                             │ notes, checklists,   │
                             │ journal, albums      │
                             └──────────────────────┘
```

Triggers are plain Android code, so they cost nothing while waiting. When one fires, the agent pulls context from memory, decides what to do, and reaches the user by a ring-first call, a bubble, or by quietly filing something. A declined call is also a signal: the agent snoozes and retries, or falls back to a notification.

## Memory model

One memory layer, several item types, all tagged and grouped into albums. Albums mix types: the album is the context, the type decides how an item is shown.

| Item type | How it gets in | What the agent extracts |
| --- | --- | --- |
| Gallery | Photos, screenshots, tickets, bills, documents saved or shared to the app | Text, dates, places, people, what it is (ticket, bill, prescription) |
| Note | Typed, or spoken during a call | Topic, people, dates mentioned |
| Checklist | Created from voice ("pack charger, passport, earphones") | Items, due date, which album it belongs to |
| Journal | Written automatically from the night check-in call | Day summary, mood words the user said, linked photos |

**Tags and albums.** Every item gets tags (place, person, date, event). The agent groups related items into albums on its own: saving a flight ticket creates "Delhi trip, Dec 14", and a hotel booking saved later lands in the same album. The user can also say "save this to my Delhi trip."

**Example album, Delhi trip:** the ticket image, a packing checklist, a note with the hotel address, and the journal entry from that day.

## Adaptive surfaces

The same memory is shown differently depending on the moment. For the hackathon, each item type has a fixed template; later the agent outputs a small JSON layout the app renders with those same components, so it can pre-prepare the UI per situation.

| Moment | Surface | What shows |
| --- | --- | --- |
| Arriving at the airport | Bubble overlay, tap to expand | Boarding pass card + unticked packing items |
| Morning check-in call | Voice, with a card on screen | Today's checklist, read out and ticked by voice |
| "What did I do last Sunday?" | Full screen timeline | Journal entries + that day's photos |
| Opening a chat with a friend | Bubble overlay | Note card with pending items for that person |
| Searching memory | Gallery grid | Matching images and documents |

Templates to build: card, list, timeline, gallery grid, and the incoming-call screen.

## Hero use case: morning and night check-in calls

An opt-in daily ritual: the phone rings like an incoming call at a time the user picks, and a short voice conversation runs with full memory context. Rituals drive retention, and this one touches every memory type.

**Morning call (about 60 seconds)**

1. Phone rings at the set time; user accepts (declining snoozes it, or falls back to a notification).
2. Agent covers today: plans, anything due, reminders, and relevant albums ("your flight is at 6, boarding pass is ready").
3. Agent reads the day's checklist; the user ticks or adds items by voice.
4. A card stays on screen with today's list after the call ends.

**Night call (about 60 seconds)**

1. Phone rings at the set time; user accepts.
2. Agent asks how the day went and what to remember for tomorrow.
3. The user just talks. The agent files it: tasks become a checklist, facts become notes, the day becomes a journal entry linked to that day's photos.
4. Tomorrow's morning call opens with what was said tonight.

**Why it works:** zero typing, one habit twice a day, and the journal builds itself. It also gives the agent fresh context every day, which makes every other use case smarter.

## Daily-habit use cases

These pass the test of being touched every day without the user asking.

| Use case | Trigger | Surface | Memory used |
| --- | --- | --- | --- |
| Screenshot brain | New screenshot saved | Silent filing; gallery search later | Gallery, tags, albums |
| People memory | Opening a chat with someone | Bubble with pending items for that person | Notes, checklists |
| Arrival moments | Reaching home, office or gym | Bubble: "you said you'd pay the electricity bill" | Checklists, notes |
| Accountability calls | User-set: "call me at 6, check if I went to the gym" | Ring-first call | Checklists, streaks in notes |
| English speaking practice | Daily scheduled call | Ring-first 5-minute voice chat | Notes on progress |

**Screenshot brain** needs no OEM access: the app watches the screenshots folder, reads each image and files it. "What was that café I saved last week?" then just works.

**People memory** relies on notification access to learn what's pending with each person, so it needs a clear consent screen.

## Scenario use cases

Occasional but high-impact moments that ride on the same engine.

**Travel companion.** Saving a flight ticket creates a trip album; hotel bookings and ID copies are filed into it. On travel day the phone rings: "leave in 20 minutes." At the airport, a geofence pops the bubble with the boarding pass. "My flight moved to 9" reschedules every related reminder.

**Doctor and health visits.** Prescriptions, lab reports and insurance cards are grouped per doctor or family member. At the clinic, the bubble shows the latest reports. "Take this twice a day for five days" sets reminders, and a missed dose triggers a call.

**Life admin.** A photographed bill becomes a warranty record with its expiry date. "Where's my AC bill?" surfaces it. The agent rings before warranties, insurance or vehicle papers expire.

**Family circle.** The agent on a parent's phone calls them: "did you take your medicine?" Two missed calls nudge the child. One install pulls in the whole family.

**Save from any app.** The user shares a PDF or image from WhatsApp, Gmail, Drive or Files to the app, or says "save this" while viewing it; the agent files it into the right album.

## Constraints and workarounds

No OEM permissions or Motorola APIs, so every capability uses standard Android APIs a normal app can get.

| Need | Workaround | Risk |
| --- | --- | --- |
| Location triggers | Geofencing API | Delays when the phone is idle |
| Time triggers | Exact alarms (AlarmManager) | Needs the exact-alarm permission on newer Android |
| Ring like a phone call | Self-managed ConnectionService or a full-screen intent | Must ring reliably while the app is in the background |
| Bubble overlay | Draw-over-other-apps permission | Users wary of granting it; needs a clear reason |
| Staying alive | Foreground service | Persistent notification |
| Screenshot filing | Watch the screenshots folder | Media permission |
| Save from other apps | Share target (ACTION_SEND) + narrow Accessibility screenshot for voice | Play policy on Accessibility automation |
| People memory | Notification listener | Sensitive permission; explicit consent |

**Biggest risk:** Android's battery optimization killing the app in the background. Onboarding must ask the user to exempt it.

**Credit limits:** triggers are plain code, so the LLM only runs when a trigger fires or the user speaks, which keeps cost low.

## Hackathon scope and demo script

Build the engine once and show it through the check-in calls, with one scenario on top.

**In scope:** ring-first calls, time and location triggers, memory with all four item types and auto-albums, bubble overlay, fixed templates per type.

**Out of scope for now:** agent-generated UI, people memory, family circle, OEM integration.

**Demo script (about 3 minutes)**

1. Save a flight ticket screenshot; an album appears with the trip details pulled out.
2. Night call rings. Say "pack charger and passport, and remember the hotel is near Connaught Place." A checklist, a note and a journal entry appear in the album.
3. Morning call rings and reads back today's plan and the checklist; tick an item by voice.
4. Simulate arriving at the airport; the bubble pops with the boarding pass and the unticked item.
5. Close on the pitch: it remembers, it knows where you are, and it comes to you.

**Next documents:** one-page PRD, then architecture, then the risk spike on reliable background ringing and voice latency.
