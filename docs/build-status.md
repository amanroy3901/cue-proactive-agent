# Cue — Build status

Nov 13, 2026 · Built and tested overnight on the motorola razr fold 2026 (Android 17) · Companion docs: [architecture.md](architecture.md) (revision 6), [ux-flows.md](ux-flows.md), [prd.md](prd.md)

## How to run it

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

Then open Cue, finish the three-step onboarding, and go to **Settings → Debug panel and trigger simulator**:

1. **Load demo data** creates the Delhi trip (ticket, hotel, packing list, note), Dr. Rao's prescription and lab report, the AC bill with a warranty, an electricity bill, a café screenshot, Rahul's and Priya's open items, a gym streak and two journal entries. Everything goes through the real extraction, album and planner code.
2. **Ring now** fires any call: Morning, Night, Leave for flight, Medicine, Gym check, Warranty, Practice, Parent's medicine, Missed dose retry.
3. **Arrive at** fires a geofence: Airport, Home, Office, Gym, Clinic.
4. **Preview a bubble** shows what a bubble looks like even while the phone is locked.
5. **On-device model** loads LFM3 and switches calls to it, or back to rules only.

**Online by default:** calls use Gemini Live. Put `GEMINI_API_KEY=...` in `local.properties` (not committed) before building. **Settings → Work offline** switches calls to the on-device LFM3 model or the rules.

The phone is already set up: app installed with all permissions, demo data loaded, LFM3 model files copied to `/sdcard/Android/data/com.cue.app/files/`, and Home set to the phone's real location.

On a call you can talk, or tap the keyboard button and type.

## What Cue can do by voice (online)

Every app's data can be read, added to, changed and removed on a call. Reads and saves return numbers (#14) so corrections change the same entry.

| App | Read | Add | Change | Remove |
| --- | --- | --- | --- | --- |
| Lists | `list_read` (one), `lists_read` (all) | `checklist_add` (naming a new list creates it), `list_create` | `checklist_tick`, `checklist_untick`, `checklist_edit`, `list_rename` | `checklist_remove` (one, or everything done), `list_delete` |
| Habits | `habits_read` | `habit_add`, `habit_checkin` | `habit_rename` | `habit_remove` |
| People | `people_read` | `pending_add` | `pending_update` (by number, or by who and what), `pending_done`, `pending_resolve` | `pending_remove` |
| Reminders and calls | `reminders_read` | `reminder_set`, `dose_schedule` | `reminder_move`, `reminder_edit`, `checkin_time` (daily calls), `practice_schedule`, `event_move` (flight) | `reminder_cancel` |
| Notes | `notes_read` | `note_save` | `note_edit` | `note_remove` |
| Journal | `journal_lookup` | `journal_add` | | |
| Papers | `papers_read` (with details when few match), `paper_read`, `album_search` | (screenshots and shares) | `item_move` (into an album) | |
| Albums | `albums_read`, `album_open` | `album_create` | `item_move` | |
| Renewals | `renewals_read` | `renewal_remind` | | |
| Focus | `focus_read` | `focus_start` | | `focus_stop` |
| Anything | | | | `forget` (by description) |
| Call | | | | `end_call` (only after a goodbye) |

Tested on the phone by typing into live calls: the Oscar→Muskan style correction (updated #8, no duplicate), a new Grocery list and "add butter too" going to it, moving items off the Delhi list into a new Chhath Puja list, "what's my PNR and seat" (one step), focus start and stop, deleting a list, deleting a person's entry by description, and hanging up on "bye".

## What was tested on the phone

Every row below was run on the device and its log or screenshot checked. "Typed" means I typed the user's lines into the call screen, because I can't speak to the phone; speech recognition itself ran (on-device) but did not hear real speech.

| Use case | What happened | How tested |
| --- | --- | --- |
| Onboarding | Name, call times, nine permissions with reasons and fallbacks | Tapped through |
| Morning call | Rang over the lock screen; briefing from the database (flight, packing list, warranty); "I have packed the passport" ticked it live; "remind me at 9 to call Mom" set a ring; "my flight moved to 9" moved the flight and both derived reminders; "pack sunscreen and a hat" added both; "bye" ended; summary listed all four | Simulator, typed |
| Night call | Dark theme; "pack the adapter… remember the cab is at 3:30" added and noted; free talk answered by LFM3; journal written by LFM3 at hang-up | Simulator, typed, on LFM3 |
| Leave for flight | Rang with the unticked items; ended cleanly | Simulator, typed |
| Medicine dose | "yes I took it" recorded the dose and hung up | Simulator, typed |
| Missed dose | Declined twice: notification, and the caregiver escalation ran (skipped the SMS because no number is set) | Simulator, declined |
| Medicine schedule | "take this twice a day for five days" created 10 dose reminders from the saved prescription | Talk, typed |
| Accountability | "call me at 6 pm and check if I went to the gym" set a checking call; the gym call logged "yes I went" and reported a 7-day streak | Talk and simulator |
| Warranty | Rang 7 days before expiry; "yes" sets a second reminder the day before | Simulator, typed |
| English practice | Rules: corrected "yesterday I go". LFM3: voiced the correction itself, then kept the conversation going; first audio 1.3 to 2.0 s | Simulator, typed, both brains |
| Search | "where is my AC bill" found it in Home bills | Talk, typed |
| Journal lookup | "what did I do yesterday" read the entry back | Talk, typed |
| People memory | "Rahul asked for the deck" saved; a simulated WhatsApp message from Rahul queued the bubble with the open item (phone locked, so notification now, bubble on unlock); notification listener is bound to the real system | Talk, simulator, `dumpsys` |
| Screenshot brain | A real PNG saved to Pictures/Screenshots was picked up by the watcher, read by ML Kit OCR (151 characters) and filed to the Delhi trip in about 0.3 s; the same booking saved twice is now one ticket | Real MediaStore insert |
| Save from any app | A prescription shared as text was read and filed to a new "Dr. Meera Iyer visits" album | Real share intent |
| Arrival bubbles | Airport (pass and unticked items), Home (electricity bill), Clinic (latest reports), Rahul (open items) all render; geofences for Home, Office, Gym, Rao Clinic and Bengaluru airport are registered with Play services | Bubble previews, logs |
| Apps and pinning | Pinned apps show in the bottom bar; pin/unpin limited to three | Screens |
| All app screens | Home, Apps, Lists, Habits, Journal, Albums, album detail, Reminders, Papers, Renewals, People, Practice, Focus, Search, Settings, Debug | Screenshots |
| Barge-in | Cue read a 19 s answer with nobody talking and did not interrupt itself; then "Wait, stop. What time is my flight?" said over an answer stopped the speech and the model within about 0.3 s, and the model answered "Your flight to Delhi is at six PM tomorrow" | Speech played from the Mac |
| Unit tests | 19 JVM tests for phrase parsing, time parsing, ticket/bill/prescription extraction, people parsing and situation rules, all passing | `./gradlew :agent:test :brain:rules:test` |

## The guided demo (Nov 18)

Ask Cue → **▶ Run the full demo** (or **▶ Demo** in the header) runs the whole story in one screen. It loads the demo week, turns the Delhi trip into a full Space (budget ₹25,000, split with Riya), and shows a "Demo · next" button above the input box. The 12 steps:
1. Morning call
2. What the call changed
3. A dinner bill filed into the trip as an expense
4. "What's left to pack?" (Gemini screen)
5. Plan the wedding by questions
6. A photo for the wedding
7. Airport bubble
8. Rahul's WhatsApp bubble
9. Medicine call
10. Night call
11. "What do you know about me?" with a week-at-a-glance screen
12. Finish

Results show in the chat as screens Cue composes from its own components. Calls and bubbles open over the chat and come back to it. The button hides while planning questions are open. Steps live in `AskSession.tourSteps` (`app/Ask.kt`); demo images are drawn on the phone (`Demo.drawPicture`). Run twice on the emulator from empty data.

## A small build to share (Nov 18)

`./gradlew :app:assembleDemo` builds `app/build/outputs/apk/demo/app-demo.apk`, about 19 MB instead of 88 MB. It behaves like debug (same signing, so it installs over a debug build and keeps the data; shows over the lock screen; debug panel), with R8 code shrinking, unused resources removed and compressed code. Keep rules are in `app/proguard-rules.pro`. The Gemini key is built in, so share it privately only. Tested on the emulator: launch, Ask Cue with a generated screen, the share sheet, a Space.

## Less friction: one way in for files, planning as a conversation (Nov 18)

- **Inbox** (`agent/Inbox.kt`): every photo, file or shared text, whether it comes from chat, a call or another app's share sheet, is read and filed into the right space by plain rules. In order of weight: what the user typed with it, the space the conversation is about, dates, places and people, then the kind of document. If Cue is sure, it files with Undo; if not, it asks with one-tap choices. A bill filed into a space that tracks money becomes an expense there.
- **Share sheet** (`app/Filing.kt`, `ShareActivity`): sharing to Cue shows a small sheet with the result. It closes by itself when Cue was sure; otherwise it offers Move or Undo.
- **Ask Cue**: a + button for camera, photos and files. History survives restarts. Saying "I'm planning…" starts up to 3 tap-to-answer questions (when, one question from the model specific to the goal, what to keep track of), then a preview, then the space. The model's dates and names are kept only if the user said them.
- **Calls**: an Add photo button files the picture into the call's space and tells Cue, and Gemini Live sees the image.
- **Spaces**: a one-line summary at the top (days left, money, who owes whom, next stop, items left).
- Tested on the emulator: share, move, the expense, wedding planning by chat, a photo in chat, a photo during a call. Not yet tested on the razr.

## Not verified, and why

- **Real speech (partly).** Since the Nov 13 morning fix, calls on LFM3 hear the user's own voice. Verified with speech played from the Mac and with Aman talking on live calls: the model transcribed "Can you add a haircut in the list?" and "Call me at 11pm every day." and both actions ran. "Aadhaar card" was heard as "other arcade" / "adha card", so Indian words can be misheard.
- **A real geofence arrival.** The phone stayed in one place all night. Geofences are registered and the arrival path is tested through the simulator. Home is set to the phone's real location; Office, Gym and Clinic are still placeholders, so set them in Settings → Places.
- **A bubble on screen over another app.** The phone was locked (PIN), and Android hides overlays under the lock screen. The bubble window is created; unlock the phone after an arrival to see it.
- **A real WhatsApp message.** The listener is bound; only a simulated message was tested.
- **Caregiver SMS.** Deliberately not sent; set a caregiver number in Settings to enable it.
- **An overnight ring test (spike S1) and heat over a long LFM3 call.** Not run.

## Known issues

- LFM3 still loads its vision encoder (about 12 s and 650 MB) though calls never use images.
- LFM3 answers can run long (up to about 10 s of speech); the system turn asks for one or two short sentences, and the small model doesn't always follow it.
- A call turn on LFM3 takes about 3 s before the first sound: transcription 0.5–2.8 s, then the answer's first audio 1.0–1.8 s, then the model's own quiet lead-in (up to about 0.8 s).
- Online calls with speech played from a Mac speaker a metre away were often misheard (the room was noisy and the Mac quieter than a person at the phone); Aman's own voice was heard word for word. Indian names and words can still be misheard.
- Gemini sometimes reaches for the wrong app's tool or tries to hang up right after greeting; plain-code checks catch these (see architecture revision 6), but a wrong guess can still need a second try.
- Barge-in on the offline path works only when the LFM3 model is on the call. With the rules brain (Android's voice), the call still listens only after Cue finishes speaking.
- Barge-in needs the user to be clearly louder than Cue's own voice at the microphone (about twice its leak). On the razr fold, Cue's voice leaks back at 0.15–0.45 of its level even with the echo canceller on. Speaking at the phone works; speaking softly from across the room while Cue is loud may not interrupt. The first word or two said over Cue can be lost: "Wait, stop. What time is my flight?" reached the model as "What time is my flight?".
- Old alarms from wiped test data stay registered until they fire; they find no reminder and do nothing.
- Album titles use the doctor's name when known ("Dr. Meera Iyer visits") even when the patient is a family member.

## Where things are

| What | Where |
| --- | --- |
| App source | Project root, one Gradle module per architecture section 3 |
| LFM3 engine (ported from the spike sample, Apache-2.0) | `brain/ondevice/src/main/java/com/cue/brain/ondevice/lfm/` |
| Model files on the phone | `/sdcard/Android/data/com.cue.app/files/` (4.0 GB) plus unpacked sections in the app's internal storage (3.2 GB) |
| Clickable flow prototype (all 15 flows) | https://claude.ai/artifact/3CfgK3oiGpiTiMRoYSAT9r |
| Design canvas (Round 3, Bloom with depth) | https://claude.ai/artifact/Q7JAtviuJ3UcRsWPvc8CNM |
| Git | https://github.com/amanroy3901/cue-proactive-agent (`main`) |
