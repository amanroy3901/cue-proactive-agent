# Cue — Build status

Nov 13, 2026 · Built and tested overnight on the motorola razr fold 2026 (Android 17) · Companion docs: [architecture.md](architecture.md) (revision 5), [ux-flows.md](ux-flows.md), [prd.md](prd.md)

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

The phone is already set up: app installed with all permissions, demo data loaded, LFM3 model files copied to `/sdcard/Android/data/com.cue.app/files/`, calls set to use LFM3, and Home set to the phone's real location.

On a call you can talk, or tap the keyboard button and type.

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
| Unit tests | 19 JVM tests for phrase parsing, time parsing, ticket/bill/prescription extraction, people parsing and situation rules, all passing | `./gradlew :agent:test :brain:rules:test` |

## Not verified, and why

- **Real speech.** I couldn't speak to the phone. The on-device recognizer and text-to-speech start correctly and handle silence; accuracy on Indian-accented English is untested.
- **A real geofence arrival.** The phone stayed in one place all night. Geofences are registered and the arrival path is tested through the simulator. Home is set to the phone's real location; Office, Gym and Clinic are still placeholders, so set them in Settings → Places.
- **A bubble on screen over another app.** The phone was locked (PIN), and Android hides overlays under the lock screen. The bubble window is created; unlock the phone after an arrival to see it.
- **A real WhatsApp message.** The listener is bound; only a simulated message was tested.
- **Caregiver SMS.** Deliberately not sent; set a caregiver number in Settings to enable it.
- **An overnight ring test (spike S1) and heat over a long LFM3 call.** Not run.

## Known issues

- LFM3 still loads its vision encoder (about 12 s and 650 MB) though calls never use images.
- LFM3 answers can run long (up to about 14 s of speech); a per-turn "one short sentence" instruction is in place but the small model doesn't always follow it.
- Speech replies from rules and LFM3 use two different voices (Android text-to-speech for exact confirmations, LFM3 for conversation).
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
| Git | Two local commits on `main`; nothing pushed |
