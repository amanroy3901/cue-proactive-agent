# Cue — End-to-End UX Flows

Nov 13, 2026 · Status: build spec · Companion docs: [prd.md](prd.md), [architecture.md](architecture.md), [use-cases.md](use-cases.md)

This document describes every flow Cue supports, end to end: what starts it, what the situation engine decides, which screens appear, what Cue says, which tools the agent calls, and which database rows are read and written. It is the spec the app is built and tested against.

Screens are named after the Round 3 "Bloom with depth" artboards on the design canvas (https://claude.ai/artifact/Q7JAtviuJ3UcRsWPvc8CNM). Table and tool names match [architecture.md](architecture.md) revision 4.

**Demo timeline used throughout.** Today is Saturday 13 December. The Delhi flight is **Sunday 14 December at 6:00 PM**. The morning call is at **7:30 AM**, the night call at **10:00 PM**.

---

## 0. Shared conventions

### Screens

| Screen name | What it is |
| --- | --- |
| Home | Agent-first home: next call, Cue's sentence, today's items, habit strip |
| Incoming call | Full-screen ring: Cue orb, purpose, two context chips, Pick up / Decline / Ring me in 10 minutes |
| In call | Live conversation bubbles plus the card being edited |
| After call | Summary of what was saved, next call time. Dark variant after the night call |
| Call times | Onboarding step that sets the morning and night call times |
| Apps | All apps with pin buttons, pinned section (max 3) |
| Albums | Album list with a featured upcoming album |
| Album detail | One album: hero card (boarding pass, prescription, bill), lists, notes, journal |
| Bubble | Overlay card over any other app |
| Journal | Day entries written from night calls |
| Search | Memory search with filters and earlier questions |
| Lists, Habits, Reminders, Papers, Renewals, Focus, Practice | App screens reached from Apps or the bar |
| Debug panel | Hidden screen with the trigger simulator and logs |

### Situation engine outcomes

`RING` · `BUBBLE` · `NOTIFY` · `SILENT` · `DEFER(n min)`. Rules are in architecture section 5. Every flow below states which outcome it normally gets and what changes it.

### Brain availability

Every flow names what happens in three cases:

| Case | Meaning |
| --- | --- |
| **LFM3** | Lfm3Brain loaded: free conversation, spoken replies from the model |
| **Rules** | RulesBrain: deterministic intent parser, Android speech recognition and text-to-speech. Always available, offline, instant |
| **No brain** | Neither can run (no mic permission, recognizer unavailable): scripted briefing read by text-to-speech, tap-to-tick card, voice note saved for later |

RulesBrain is the guaranteed demo path. Every flow below works on it.

### Notation

- `tool(args)` is an agent tool call, validated and executed by `:agent`.
- **Reads** and **Writes** list database access, using the table names from architecture section 9.
- "Cue:" and "You:" are spoken turns.

---

## 1. Morning and night check-in calls (hero)

### 1a. Night call, 10:00 PM

| Step | Detail |
| --- | --- |
| Trigger | Alarm-clock alarm `trigger.purpose = NIGHT_CALL`, 10:00 PM. Pre-load alarm at 9:58:30 PM |
| Decision | `RING`. `DEFER(10)` if on a real call; `NOTIFY` (silent) in Do Not Disturb or quiet hours |
| Pre-load | Brain prepared; context pack built; opening line synthesized |
| Screens | Incoming call → In call → After call (dark) |

**Reads before the ring:** `album` active within 3 days, `checklist_entry` unticked for today and tomorrow, `reminder` due tomorrow, `habit` with an open day, latest `item` of type JOURNAL.

**Conversation (demo script):**

| # | Turn | Tool calls | Writes |
| --- | --- | --- | --- |
| 1 | Cue: "Hi Aman. How was today? Anything to remember for tomorrow? Your Delhi flight is at six." | none | `call_session` (ANSWERED) |
| 2 | You: "Pack charger and passport, and remember the hotel is near Connaught Place." | `checklist_add(["charger","passport"])`, `note_save("Hotel is near Connaught Place")` | `checklist_entry` ×2 into the Delhi trip list, `item` (NOTE) + `album_item` |
| 3 | Cue: "Got it. Charger and passport are on your Delhi list, and I saved the hotel note. Anything else?" | none | |
| 4 | You: "No, that's it." | none | |
| 5 | Cue: "Sleep well. I'll ring you at 7:30." Call ends | none | `call_session` (ENDED) |

**After the call (File job):** transcribe the user's clips (LFM3) or reuse the recognizer text (Rules); write the journal entry; catch missed tasks.

| Writes | Detail |
| --- | --- |
| `item` (JOURNAL) | "Packed for Delhi, booked the cab. Ready, a little rushed." + mood words; linked to today's photos via `item_tag` (date) |
| `call_session.filing_state` | DONE |

**After call (dark) screen** shows: 2 things to pack, a note, today's journal, "I'll ring you at 7:30 AM".

### 1b. Morning call, 7:30 AM

| Step | Detail |
| --- | --- |
| Trigger | Alarm-clock alarm `MORNING_CALL` 7:30 AM; pre-load at 7:28:30 AM |
| Decision | `RING` |
| Screens | Incoming call (sunrise) → In call → After call (light) → Home |

| # | Turn | Tool calls | Writes |
| --- | --- | --- | --- |
| 1 | Cue: "Good morning. Your flight to Delhi is at 6 this evening and your pass is ready. Three things left to pack: charger, passport, earphones." | none | |
| 2 | You: "I've packed the charger." | `checklist_tick("charger")` | `checklist_entry.checked = 1` |
| 3 | Cue: "Ticked. Passport and earphones are left. I'll remind you to leave at 3:40." | none | |
| 4 | You: "Thanks." Call ends | | |

**Reads:** last night's journal summary line, the numbered unticked list, today's `reminder` rows, `trigger` LEAVE_BY.

### 1c. Declines and edge cases

| Case | Behaviour |
| --- | --- |
| Declined | `trigger.snooze_count++`, ring again in 10 minutes; after 2 declines a `NOTIFY` with the briefing as text |
| Not answered in 30 s | Counts as one decline |
| "Ring me in 10 minutes" | Same as decline but does not count toward the limit |
| Real phone call during the ring | Ring cancelled, `DEFER(10)` |
| Real phone call during the conversation | Call ends, File job runs on what was said |
| User silent for 10 s | Cue: "Are you there?" then ends politely |
| Model unavailable | Rules path; if speech recognition also fails, scripted briefing + tap-to-tick card |
| Phone was off at 7:30 | On boot: missed by under 30 min → ring now; otherwise notification |

---

## 2. Screenshot brain

| Step | Detail |
| --- | --- |
| Trigger | New MediaStore image under `Screenshots/` (content-URI WorkManager job) |
| Decision | `SILENT` |
| Screens | None at capture. Later: Search, Albums, Album detail |

**Pipeline and queries:**

| Step | Reads | Writes |
| --- | --- | --- |
| Copy + hash | `item.content_hash` (dedupe) | file in `files/media/`, `item` (GALLERY, ingest_state COPIED) |
| OCR (ML Kit) | | `item.body` = OCR text, `item_fts` |
| Fast fields (pattern rules) | airport table | `item.fields_json`, `tag` + `item_tag` (date, place, amount, booking ref) |
| Album assign | `album` overlapping date/place/tag | `album_item` or new `album` |
| Planner reconcile | albums, items | `trigger` rows (if the item is an anchor) |
| Enrich (next model window) | | `item.title`, `item.kind`, more fields |

**Retrieval:**

| You (Search or Talk) | Tool | Reads | Result |
| --- | --- | --- | --- |
| "What was that café I saved last week?" | `album_search(query="café", from=-7d)` | `item_fts MATCH 'café'` filtered by `created_at` | Search screen with 2 results; Cue reads the top one |

**Edge cases:** duplicate screenshot dropped by hash; screenshot with no text stays a plain gallery item searchable by date; "Select photos" permission makes new screenshots invisible, so onboarding asks for full access and Search shows a banner when it is missing.

---

## 3. Save from any app (share sheet)

| Step | Detail |
| --- | --- |
| Trigger | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` of `image/*` or `application/pdf` from WhatsApp, Gmail, Drive, Files |
| Decision | `SILENT` + a small confirmation toast/notification "Saved to Delhi trip" |
| Screens | Share sheet → Cue's save sheet (shows detected album, "Change album" button) → back to the source app |

| Reads | Writes |
| --- | --- |
| Same pipeline as screenshot brain; PDFs rendered page 1–3 for OCR | `item` (source SHARE), file copy (the URI grant is temporary, so copy at once) |

**Voice "save this":** said in a call or via Talk while another app is visible. Optional Accessibility screenshot (P1). Without it Cue says "Share it to me and I'll file it."

---

## 4. Travel companion (demo scenario)

### 4a. Ticket to album

| Step | Detail |
| --- | --- |
| Trigger | Screenshot or share of the e-ticket |
| Fast fields | `from=BLR`, `to=DEL`, `depart=2026-12-14T18:00 +05:30`, `flight=6E 2134`, `pnr`, `gate`, `seat` |
| Album | No match + anchor → new `album` "Delhi trip, Dec 14" (kind TRIP, start 14 Dec, end = return date or +3 days) |
| Planner | `trigger` LEAVE_BY at depart − 2h20m (3:40 PM), `trigger` GEOFENCE on BLR airport (radius 1 km), time backup at depart − 1h30m, `trigger` TRIP_PREP ring at 7:30 PM the night before (merged into the night call) |

### 4b. Hotel booking and ID copy filed in

| Input | Matching rule | Writes |
| --- | --- | --- |
| Hotel booking PDF for 14–16 Dec, Delhi | Date overlap + city tag "Delhi" | `album_item` into Delhi trip |
| Aadhaar/passport photo | No date; kind ID | Stays in Papers; linked to the trip only when you say "add my passport to the Delhi trip" (`album_assign`) |

### 4c. Travel day: "Leave in 20 minutes"

| Step | Detail |
| --- | --- |
| Trigger | `LEAVE_BY` reminder at 3:40 PM fires a reminder at 3:20 PM ("leave in 20 minutes") |
| Decision | `RING` (short call) or `NOTIFY` if in DND |
| Turns | Cue: "Leave in 20 minutes to make your 6 PM flight. Passport and earphones are still unticked." You: "Passport's in my bag." → `checklist_tick("passport")`. Cue: "Great. Have a good flight." |

Lead time is fixed (configurable in settings, default 2h20m before departure for domestic). Live traffic is out of scope.

### 4d. Airport arrival bubble

| Step | Detail |
| --- | --- |
| Trigger | Geofence ENTER on BLR (or Trigger simulator → "Arrive at airport") |
| Decision | `BUBBLE` if unlocked; `NOTIFY` on the lock screen otherwise, bubble on unlock |
| Screen | Bubble: "You made it. Here's your pass." Boarding pass card, unticked items as buttons, Thanks / Open trip |
| Reads | `album` where place = BLR and date = today, its anchor `item.fields_json`, unticked `checklist_entry` |
| Writes | `trigger_event` (outcome SHOWN); ticks if tapped |
| No brain needed | Yes, built from local data only |

### 4e. "My flight moved to 9"

| # | Turn | Tool | Writes |
| --- | --- | --- | --- |
| 1 | You (Talk or in a call): "My flight moved to 9." | `event_move(album="Delhi trip", new_time="21:00")` | `item.event_start` = 2026-12-14 21:00 |
| 2 | Planner reconcile | | `trigger` LEAVE_BY → 6:40 PM, airport backup → 7:30 PM; old alarms cancelled |
| 3 | Cue: "Done. Your flight is now at 9. I'll tell you to leave at 6:40." | | |

Edge: "moved to 9" with no AM/PM → the time closest after the old time (21:00). Ambiguous date ("moved to tomorrow") → asks once.

---

## 5. Doctor and health visits

### 5a. Grouping

| Input | Fast fields | Album |
| --- | --- | --- |
| Prescription photo from Dr. Rao | doctor name, date, medicine lines (name, dose, frequency, days) | "Dr. Rao visits" (kind HEALTH), keyed by doctor name tag |
| Lab report PDF | lab name, patient name, date | Same doctor album if the referring doctor matches, else a per-person album "Mom's health" when the patient name is a known `person` |
| Insurance card | insurer, policy number, expiry | Papers; expiry → Renewals (flow 6) |

### 5b. Clinic arrival bubble

| Step | Detail |
| --- | --- |
| Trigger | Geofence ENTER on `place` kind CLINIC (set by the user, or from the address on a prescription via geocoder) |
| Decision | `BUBBLE` |
| Screen | Bubble: "At Dr. Rao's clinic. Latest reports:" latest 2 items of the album as cards, "Open album" |
| Reads | `album` linked to the place, latest `item` rows ordered by date |

### 5c. "Take this twice a day for five days"

| # | Turn | Tool | Writes |
| --- | --- | --- | --- |
| 1 | You: "Take this twice a day for five days." (in a call, or Talk after viewing a prescription) | `dose_schedule(medicine="Azithral 500", per_day=2, days=5, times=["09:00","21:00"])` | 10 `reminder` rows kind DOSE; `habit` "Azithral course" with 10 expected logs |
| 2 | Cue: "I'll remind you at 9 AM and 9 PM until Wednesday." | | |

If the medicine name is unclear ("this"), it uses the most recently viewed or saved prescription; if none, it asks "Which medicine?".

### 5d. Dose reminder and missed dose

| Step | Decision | Detail |
| --- | --- | --- |
| Dose time | `NOTIFY` with Taken / Snooze | Taken → `habit_log` |
| +30 min not marked taken | `RING` | Cue: "Did you take your Azithral?" You: "Yes" → `habit_checkin("Azithral course")`; "Not yet" → snooze 15 min |
| Second miss in care-recipient mode | Escalate | See family circle (flow 10) |

---

## 6. Life admin: bills, warranties, renewals

### 6a. Bill becomes a warranty record

| Step | Detail |
| --- | --- |
| Trigger | Photo or screenshot of an invoice |
| Fast fields | merchant, product ("Voltas 1.5T AC"), purchase date, amount, warranty years (pattern "warranty 1 year", default per category table) |
| Writes | `item` kind BILL, `fields_json.expiry`, album "Home bills"; `reminder` kind EXPIRY at expiry − 30 days and − 3 days |

### 6b. "Where's my AC bill?"

| Turn | Tool | Reads |
| --- | --- | --- |
| You: "Where's my AC bill?" | `album_search(query="AC bill")` | `item_fts MATCH 'AC OR air conditioner'` + kind BILL |
| Cue: "Here it is: Voltas AC, bought 20 March 2025 for ₹38,500. Warranty until 20 March 2026." Search screen opens on the item | | |

### 6c. Expiry ring

| Step | Detail |
| --- | --- |
| Trigger | `reminder` EXPIRY due (warranty, insurance, PUC, vehicle insurance, passport) |
| Decision | `RING` at the next morning call slot (merged into the morning briefing); standalone `NOTIFY` otherwise |
| Turn | Cue: "Your AC warranty ends in 30 days. Want me to remind you a week before as well?" |
| Screens | Renewals app lists everything with an expiry, soonest first |

---

## 7. People memory

| Step | Detail |
| --- | --- |
| Trigger (learn) | `NotificationListenerService.onNotificationPosted` for messaging apps (WhatsApp, Messages, Telegram, Gmail) |
| Extraction | Pattern rules on the message text: requests ("can you send", "please share", "don't forget"), money ("you owe", "₹500", "pay me"), meetings ("let's meet", "call me") |
| Writes | `person` (by notification title / conversation title), `pending_item(person_id, text, source_app, status=OPEN)` |
| Trigger (surface) | Next notification from that person while an OPEN item exists |
| Decision | `BUBBLE` (unlocked, not on a call); max once per person per hour |
| Screen | Bubble: "Rahul · 1 open: You said you'd send the deck." Buttons Done / Later |
| Writes on Done | `pending_item.status = DONE` (`pending_resolve`) |
| By voice | "What do I owe Priya?" → `album_search` over `pending_item` → "You owe Priya ₹500 from Tuesday." "I paid Priya" → `pending_resolve` |

**Limits stated to the user:** Cue only sees notification text, not opened chats, so the bubble comes when that person messages you, not when you open the chat. Consent screen explains this before notification access is requested. Message text never leaves the phone.

---

## 8. Accountability calls

| # | Turn | Tool | Writes |
| --- | --- | --- | --- |
| 1 | You: "Call me at 6 and check if I went to the gym." | `reminder_set(text="Did you go to the gym?", when="18:00", surface=RING, kind=ACCOUNTABILITY, habit="Gym")` | `reminder` ACCOUNTABILITY, `habit` "Gym" if new |
| 2 | Cue: "Okay, I'll call you at 6." | | |
| 3 | 6:00 PM: `RING` | | |
| 4 | Cue: "Did you make it to the gym today?" You: "Yes." | `habit_checkin("Gym", done=true)` | `habit_log` |
| 5 | Cue: "Nice, that's 7 days in a row." | | |

"No" → `habit_checkin(done=false)` and Cue: "Okay. Want me to check tomorrow at the same time?" → `reminder_set` repeating. Habits app shows the streak; Home shows the habit strip.

---

## 9. Arrival moments

| Step | Detail |
| --- | --- |
| Setup | Home, office and gym places set on a map in onboarding (`place` kind HOME/OFFICE/GYM) |
| Linking items to places | "When I get home, remind me to pay the electricity bill" → `reminder_set(text, place="HOME")`; tasks mentioning "at home/office" get the place automatically |
| Trigger | Geofence ENTER, 100 m radius, dwell 1 min |
| Decision | `BUBBLE` if there is at least one OPEN item for that place, else `SILENT` |
| Screen | Bubble: "Home. You said you'd pay the electricity bill." Done / Later |
| Writes | `reminder.status = DONE` on Done; one-hour cooldown per place |

Gym arrival also auto-logs the gym habit (`habit_checkin("Gym")` with source GEOFENCE), so the 6 PM accountability call says "I saw you at the gym, nice, 7 days."

---

## 10. Family circle (care-recipient mode)

Runs on the parent's phone. Single-device for the hackathon; no backend.

| Step | Detail |
| --- | --- |
| Setup | Settings → Care mode: recipient name ("Mom"), caregiver name and phone number, medicines (from a prescription photo or voice) |
| Dose time | `RING`: "Good morning. Did you take your BP tablet?" Large Yes / Not yet buttons, slower speech rate |
| Yes | `habit_checkin` |
| Missed (declined, unanswered or "not yet" twice) | `trigger_event` outcome MISSED ×2 → escalation |
| Escalation | SMS via `SmsManager` to the caregiver: "Cue: Mom hasn't confirmed her 9 AM BP tablet. 2 calls missed." Only if `SEND_SMS` granted and a number is configured; otherwise a notification on the parent's phone tells them to call their son |
| Writes | `reminder` DOSE, `habit_log`, `trigger_event`, `escalation` log row in `trigger_event.outcome` |

---

## 11. Daily English speaking practice

| Step | Detail |
| --- | --- |
| Setup | Practice app → "Daily practice at 8:30 PM, 5 minutes" → `reminder` kind PRACTICE, repeating |
| Trigger | `RING` at 8:30 PM |
| Screens | Incoming call (Practice) → In call (practice mode: bubbles + a "Tips" card) → After call (practice summary) |

**With LFM3 (real conversation):** system prompt switches to tutor mode: short questions, one gentle correction per turn, never interrupt.

| # | Turn |
| --- | --- |
| 1 | Cue: "Let's talk for five minutes. What did you do this evening?" |
| 2 | You: "I go to market and buyed vegetables." |
| 3 | Cue: "Nice! A small fix: 'I went to the market and bought vegetables.' What did you buy?" |

**With RulesBrain (scripted lesson):** 5 fixed prompts per day from a bundled list, the recognizer transcribes the answer, and a small rule set flags common errors (past tense of irregular verbs, missing articles, "he don't"). Feedback is read aloud from templates.

**After call:** "Today: 4 answers, 2 corrections (went, bought). Streak 3 days." Writes `habit_log` for "Practice", a NOTE with the corrections.

---

## 12. Apps and pinning, agent-first home

| Element | Behaviour |
| --- | --- |
| Home | Top card: next call countdown + Talk now. Cue's sentence (generated from the context pack). Today's card (the most relevant album's unticked items). Habit strip. Nothing else |
| Bottom bar | Home · pinned app 1 · **Talk** (centre) · pinned app 2 · Apps. Up to 3 pins; with 3 pins the third replaces Apps' neighbour and Apps stays at the right edge |
| Default pins | Lists, Habits |
| Apps screen | Pinned section (2 of 3) + All apps grid: Journal, Albums, Reminders, Papers, Renewals, Focus, Practice, Search |
| Pin / unpin | Pin icon on each tile; writes DataStore `pinned_apps` |
| Talk | Opens an in-app voice session (same In call screen, no ring) |
| Reads | Each tile's status line is one cheap query (e.g. `COUNT(checklist_entry WHERE checked=0 AND due=today)`) |

Navigation is mostly through Cue: "show my journal" opens Journal, "open the Delhi trip" opens Album detail.

---

## 13. Onboarding

1. Welcome: "I remember your stuff and call you when it matters."
2. Call times (screen Call times) → writes settings, planner creates MORNING_CALL and NIGHT_CALL triggers.
3. Permissions one at a time with fallbacks (architecture section 15): notifications, microphone, exact alarms, full-screen notifications, battery unrestricted, photos (full), location then "all the time", overlay, notification access (people memory, optional), SMS (care mode only).
4. Places: home, office, gym on a map (skippable).
5. Done → Home with a first Cue sentence.

---

## 14. Debug panel and trigger simulator

Every trigger can be fired by hand so the demo never depends on real GPS or real time.

| Button | Fires |
| --- | --- |
| Ring morning call / night call now | MORNING_CALL / NIGHT_CALL |
| Pre-load brain | pre-load alarm |
| Arrive at airport / home / office / gym / clinic | geofence ENTER for that place |
| New screenshot (sample ticket / prescription / bill / café) | ingest of a bundled sample image |
| Leave-by reminder | LEAVE_BY |
| Dose due / dose missed ×2 | DOSE flow + escalation |
| Accountability call | ACCOUNTABILITY |
| Practice call | PRACTICE |
| Message from Rahul | simulated notification into the people-memory pipeline |
| Expiry due | EXPIRY |
| Reset demo data | wipe + seed the demo dataset |

It also shows the event log, brain status (Rules / LFM3 loaded / unavailable), per-turn timings and permission status.

---

## 15. Three-minute demo script (travel + check-ins)

1. Share or screenshot the Delhi ticket → Albums shows "Delhi trip, Dec 14" with the ticket.
2. Debug: "Ring night call now" → pick up → say the packing line → After call (dark) shows list, note, journal.
3. Debug: "Ring morning call now" → "I've packed the charger" → tick appears live.
4. Debug: "Arrive at airport" → bubble with boarding pass and unticked items.
5. Talk: "My flight moved to 9" → Cue confirms the new leave time.
6. Close: "It remembers, it knows where you are, and it comes to you."

Backup: the same steps on RulesBrain in airplane mode.
