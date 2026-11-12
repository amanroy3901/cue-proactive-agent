package com.cue.brain.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentsTest {
    private fun calls(text: String, mode: String = "TALK") = Intents.parse(text, mode, 0).calls

    @Test fun packAndRememberSplitIntoTwoTools() {
        val c = calls("pack charger and passport, and remember the hotel is near Connaught Place", "CHECKIN_NIGHT")
        assertEquals(listOf("checklist_add", "note_save"), c.map { it.name })
        assertEquals("charger and passport", c[0].args["entries"])
        assertEquals("the hotel is near Connaught Place", c[1].args["text"])
    }

    @Test fun tickFromPackedSentence() {
        val c = calls("I have packed the passport", "CHECKIN_MORNING")
        assertEquals("checklist_tick", c.single().name)
        assertEquals("passport", c.single().args["entry"])
    }

    @Test fun flightMoved() = assertEquals("9", calls("my flight moved to 9").single().args["when"])

    @Test fun doseSchedule() {
        val c = calls("take this twice a day for five days").single()
        assertEquals("dose_schedule", c.name)
        assertEquals("2", c.args["per_day"])
        assertEquals("five", c.args["days"])
    }

    @Test fun accountabilityCall() {
        val c = calls("call me at 6 and check if I went to the gym").single()
        assertEquals("reminder_set", c.name)
        assertEquals("yes", c.args["check"])
        assertEquals("at 6", c.args["when"])
    }

    @Test fun plainReminder() {
        val c = calls("remind me at 9 to call Mom").single()
        assertEquals("call mom", c.args["text"])
        assertEquals("at 9", c.args["when"])
    }

    @Test fun peopleMemory() {
        val c = calls("Rahul asked for the deck").single()
        assertEquals("pending_add", c.name)
        assertEquals("rahul", c.args["person"])
    }

    @Test fun search() = assertEquals("ac bill", calls("where's my AC bill").single().args["query"])

    @Test fun doseAnswerEndsCall() {
        val p = Intents.parse("yes I took it", "DOSE", 0)
        assertEquals("dose_taken", p.calls.single().name)
        assertTrue(p.end)
    }

    @Test fun goodbyeEndsNightCall() {
        val p = Intents.parse("good night", "CHECKIN_NIGHT", 0)
        assertTrue(p.end)
        assertTrue(p.calls.isEmpty())
    }

    @Test fun practiceCorrects() {
        val fix = Practice.fix("yesterday I go to the market")
        assertEquals("yesterday I went", fix?.second)
    }
}
