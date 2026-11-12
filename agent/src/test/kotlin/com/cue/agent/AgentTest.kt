package com.cue.agent

import com.cue.domain.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AgentTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private val now = at(2026, 11, 13, 0, 35)

    @Test fun bareHourPicksNextOccurrence() {
        assertEquals(at(2026, 11, 13, 9, 0), TextKit.parseWhen("at 9", now, zone))
        assertEquals(at(2026, 11, 13, 18, 0), TextKit.parseWhen("at 6 pm", now, zone))
        assertEquals(now + 20 * 60_000, TextKit.parseWhen("in 20 minutes", now, zone))
    }

    @Test fun flightMoveKeepsEveningHalf() {
        val dep = at(2026, 11, 14, 18, 0)
        assertEquals(at(2026, 11, 14, 21, 0), TextKit.moveTime(dep, "9", zone))
    }

    @Test fun ticketFields() {
        val ex = FastFields.extract("IndiGo\nBOARDING PASS\nPNR: K7XQ2M\nFlight 6E 2134\nBLR Bengaluru to DEL Delhi\nDate: 14 Nov 2026\nDeparture 18:00\nGate 14 Seat 12A", now, zone)
        assertEquals(ItemKind.TICKET, ex.kind)
        assertEquals("BLR", ex.fields["from"]); assertEquals("DEL", ex.fields["to"])
        assertEquals("K7XQ2M", ex.fields["pnr"]); assertEquals("14", ex.fields["gate"]); assertEquals("12A", ex.fields["seat"])
        assertEquals(at(2026, 11, 14, 18, 0), ex.eventStart)
    }

    @Test fun billWarrantyExpiry() {
        val ex = FastFields.extract("Cool Air Electronics\nTax Invoice\nItem: LG 1.5 Ton Split Air Conditioner\nAmount: Rs 42,990\nDate: 8 Dec 2025\n1 year comprehensive warranty", now, zone)
        assertEquals(ItemKind.BILL, ex.kind)
        assertEquals("AC bill", ex.title)
        assertNotNull(ex.expiresAt)
        assertEquals(at(2026, 12, 8, 10, 0), ex.expiresAt)
    }

    @Test fun prescription() {
        val ex = FastFields.extract("Rao Family Clinic\nDr. Suresh Rao\nPatient: Aman\nRx\nTab Amoxicillin 500 mg twice a day for 5 days", now, zone)
        assertEquals(ItemKind.PRESCRIPTION, ex.kind)
        assertEquals("Suresh Rao", ex.fields["doctor"])
        assertTrue(ex.anchor)
    }

    @Test fun peopleParser() {
        assertEquals("Send the deck before tomorrow", PeopleParser.pending("Can you send the deck before tomorrow?"))
        assertEquals("You owe 500 for dinner", PeopleParser.pending("You owe me 500 for dinner"))
    }

    @Test fun listSplittingDropsArticles() = assertEquals(listOf("Sunscreen", "Hat"), TextKit.splitList("sunscreen and a hat"))

    @Test fun situation() {
        assertEquals(Decision.Notify(silent = true), SituationEngine.forRing(DeviceState(inQuietHours = true), 0))
        assertEquals(Decision.Defer(10), SituationEngine.forRing(DeviceState(onPhoneCall = true), 0))
        assertEquals(Decision.BubbleOnUnlock, SituationEngine.forBubble(DeviceState(locked = true), hasContent = true, shownRecently = false))
        assertEquals(Decision.Silent, SituationEngine.forBubble(DeviceState(), hasContent = false, shownRecently = false))
    }
}
