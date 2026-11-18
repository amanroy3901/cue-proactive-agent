package com.cue.agent

import com.cue.domain.ItemKind
import com.cue.domain.PlaceKind
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** A bundled airport table. Coordinates never come from a model. */
object Airports {
    data class Airport(val code: String, val city: String, val lat: Double, val lng: Double)

    val all = listOf(
        Airport("BLR", "Bengaluru", 13.1986, 77.7066), Airport("DEL", "Delhi", 28.5562, 77.1000),
        Airport("BOM", "Mumbai", 19.0896, 72.8656), Airport("MAA", "Chennai", 12.9941, 80.1709),
        Airport("HYD", "Hyderabad", 17.2403, 78.4294), Airport("CCU", "Kolkata", 22.6547, 88.4467),
        Airport("GOI", "Goa", 15.3808, 73.8314), Airport("PNQ", "Pune", 18.5821, 73.9197),
        Airport("COK", "Kochi", 10.1520, 76.4019), Airport("AMD", "Ahmedabad", 23.0772, 72.6347),
        Airport("JAI", "Jaipur", 26.8242, 75.8122), Airport("LKO", "Lucknow", 26.7606, 80.8893),
        Airport("IXC", "Chandigarh", 30.6735, 76.7885), Airport("GAU", "Guwahati", 26.1061, 91.5859),
        Airport("SXR", "Srinagar", 33.9871, 74.7742), Airport("TRV", "Thiruvananthapuram", 8.4821, 76.9201),
        Airport("DXB", "Dubai", 25.2532, 55.3657), Airport("SIN", "Singapore", 1.3644, 103.9915),
        Airport("LHR", "London", 51.4700, -0.4543),
    )
    private val byCode = all.associateBy { it.code }
    private val byCity = all.associateBy { it.city.lowercase(Locale.ROOT) }

    fun code(code: String): Airport? = byCode[code.uppercase(Locale.ROOT)]
    fun city(name: String): Airport? = byCity[name.lowercase(Locale.ROOT)]
        ?: when (name.lowercase(Locale.ROOT)) { "bangalore" -> byCode["BLR"]; "new delhi" -> byCode["DEL"]; "bombay" -> byCode["BOM"]; "madras" -> byCode["MAA"]; "calcutta" -> byCode["CCU"]; else -> null }
}

/** What plain-code pattern rules could read off a document. */
data class Extracted(
    val kind: String,
    val title: String,
    val fields: Map<String, String>,
    val eventStart: Long? = null,
    val expiresAt: Long? = null,
    val anchor: Boolean = false,
    val personLabel: String? = null,
    val placeKind: String? = null,
)

/**
 * Phase-one extraction: plain code, no model. Finds tickets, hotels, IDs, prescriptions, lab reports, insurance,
 * bills with warranties and vehicle papers in OCR text or a shared file's text.
 */
object FastFields {
    /**
     * A money figure as printed or as OCR read it, made plain: "1,200.00", "1.200.00", "1,20,000" and "1.200" all
     * mean what an Indian receipt means. Separators followed by three digits group thousands; one or two digits at
     * the end are paise.
     */
    fun amount(raw: String): String? {
        val groups = raw.trim().split(',', '.').filter { it.isNotEmpty() }
        if (groups.isEmpty() || groups.any { g -> g.any { !it.isDigit() } }) return null
        val last = groups.last()
        val (whole, paise) = if (groups.size > 1 && last.length in 1..2) groups.dropLast(1).joinToString("") to last else groups.joinToString("") to ""
        return whole.trimStart('0').ifEmpty { "0" } + if (paise.isNotEmpty()) ".$paise" else ""
    }

    fun extract(raw: String, now: Long, zone: ZoneId, fileName: String = ""): Extracted {
        val text = raw.replace('\n', ' ').replace(Regex("\\s+"), " ")
        val lower = (text + " " + fileName).lowercase(Locale.ROOT)
        val fields = linkedMapOf<String, String>()
        val date = TextKit.parseDate(text, now, zone)
        val time = TextKit.parseTimeOfDay(text)

        fun has(vararg words: String) = words.any { lower.contains(it) }

        // Flight tickets and boarding passes.
        val codes = Regex("\\b([A-Z]{3})\\b").findAll(text).map { it.groupValues[1] }.filter { Airports.code(it) != null }.distinct().toList()
        if (has("boarding pass", "flight", "pnr", "e-ticket", "airlines", "indigo", "air india", "vistara", "akasa", "spicejet", "gate", "departure") && (codes.size >= 2 || has("boarding pass"))) {
            val from = codes.getOrNull(0)
            val to = codes.getOrNull(1)
            from?.let { fields["from"] = it }
            to?.let { fields["to"] = it }
            Regex("\\b(6E|AI|UK|QP|SG|EK|SQ|BA)[ -]?(\\d{2,4})\\b").find(text)?.let { fields["flight"] = it.value.replace(" ", "") }
            Regex("(?i)gate[:\\s]*([A-Z]?\\d{1,3}[A-Z]?)").find(text)?.let { fields["gate"] = it.groupValues[1] }
            Regex("(?i)seat[:\\s]*(\\d{1,2}[A-F])").find(text)?.let { fields["seat"] = it.groupValues[1] }
            Regex("(?i)pnr[:\\s]*([A-Z0-9]{6})").find(text)?.let { fields["pnr"] = it.groupValues[1] }
            val start = date?.let { d -> time?.let { TextKit.at(d, it, zone) } ?: TextKit.at(d, 9 * 60, zone) }
            val city = to?.let { Airports.code(it)?.city } ?: "trip"
            return Extracted(ItemKind.TICKET, "Flight ${from ?: ""} to ${to ?: ""}".replace("  ", " ").trim(), fields, start, anchor = true, placeKind = PlaceKind.AIRPORT)
                .copy(fields = fields + ("city" to city))
        }
        if (has("hotel", "check-in", "check in", "booking.com", "oyo", "reservation", "stay")) {
            val nameLine = raw.lineSequence().map { it.trim() }.firstOrNull { l -> l.length in 3..40 && !Regex("(?i)booking|confirmation|check|reservation|invoice").containsMatchIn(l) }
            (nameLine ?: Regex("(?i)(?:stay at)\\s+([A-Z][\\w&' ]{2,30})").find(text)?.groupValues?.get(1))?.let { fields["hotel"] = it.trim() }
            val start = date?.let { TextKit.at(it, 14 * 60, zone) }
            return Extracted(ItemKind.HOTEL, fields["hotel"] ?: "Hotel booking", fields, start)
        }
        if (has("aadhaar", "passport no", "republic of india", "permanent account number", "driving licence", "driving license", "voter")) {
            return Extracted(ItemKind.ID_DOC, when {
                has("passport") -> "Passport copy"; has("aadhaar") -> "Aadhaar card"; has("permanent account") -> "PAN card"; else -> "ID document"
            }, fields)
        }
        // Health.
        val doctor = Regex("(?i)dr\\.?\\s+([A-Z][a-z]+(?:\\s[A-Z][a-z]+)?)").find(text)?.groupValues?.get(1)
        val patient = Regex("(?i)(?:patient|name)[:\\s]+(?:mr\\.?|mrs\\.?|ms\\.?)?\\s*([A-Z][a-z]+)").find(text)?.groupValues?.get(1)
        if (has("rx", "prescription", "tablet", " tab ", "capsule", "mg", "twice a day", "once daily", "bd", "tds", "syrup")) {
            doctor?.let { fields["doctor"] = it }
            Regex("(?i)(?:tab\\.?|tablet|cap\\.?|capsule|syrup)\\s+([A-Z][A-Za-z0-9-]+(?:\\s\\d+\\s?mg)?)").find(text)?.let { fields["medicine"] = it.groupValues[1] }
            return Extracted(ItemKind.PRESCRIPTION, "Prescription" + (doctor?.let { " · Dr. $it" } ?: ""), fields, date?.let { TextKit.at(it, 10 * 60, zone) },
                anchor = true, personLabel = patient, placeKind = PlaceKind.CLINIC)
        }
        if (has("lab report", "haemoglobin", "hemoglobin", "blood test", "cbc", "lipid", "thyroid", "pathology", "diagnostics")) {
            doctor?.let { fields["doctor"] = it }
            return Extracted(ItemKind.LAB_REPORT, "Lab report" + (date?.let { " · ${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3)} ${it.dayOfMonth}" } ?: ""), fields,
                personLabel = patient, anchor = doctor != null, placeKind = PlaceKind.CLINIC)
        }
        if (has("policy", "insurance", "premium", "insured")) {
            val exp = expiryDate(text, now, zone)
            val vehicle = has("vehicle", "motor", "car", "two wheeler", "bike")
            return Extracted(if (vehicle) ItemKind.VEHICLE else ItemKind.INSURANCE,
                if (vehicle) "Vehicle insurance" else if (has("health")) "Health insurance card" else "Insurance policy", fields,
                expiresAt = exp?.let { TextKit.at(it, 10 * 60, zone) })
        }
        if (has("registration certificate", "puc", "pollution under control", "rc book")) {
            val exp = expiryDate(text, now, zone)
            return Extracted(ItemKind.VEHICLE, if (has("puc", "pollution")) "PUC certificate" else "Vehicle registration", fields,
                expiresAt = exp?.let { TextKit.at(it, 10 * 60, zone) })
        }
        if (has("invoice", "bill", "gst", "receipt", "total", "amount", "warranty", "tax invoice")) {
            val product = listOf("air conditioner" to "AC", " ac " to "AC", "split ac" to "AC", "refrigerator" to "Fridge", "fridge" to "Fridge",
                "washing machine" to "Washing machine", "television" to "TV", " tv" to "TV", "laptop" to "Laptop", "phone" to "Phone",
                "microwave" to "Microwave", "electricity" to "Electricity", "water purifier" to "Water purifier")
                .firstOrNull { lower.contains(it.first) }?.second
            product?.let { fields["product"] = it }
            // The total line first; any other money figure only if there is none. OCR often reads 1,200.00 as 1.200.00.
            val num = "\\s*[:₹]?\\s*(?:rs\\.?|₹|inr)?\\s*(\\d[\\d,.]*\\d|\\d)"
            (Regex("(?i)\\b(?:grand total|total amount|net amount|amount payable|total)$num").findAll(text).lastOrNull()
                ?: Regex("(?i)(?:\\brs\\.?|₹|\\binr\\b|\\bamount\\b)$num").find(text))?.let { m -> amount(m.groupValues[1])?.let { fields["amount"] = it } }
            val warranty = Regex("(?i)(\\d+|one|two|three|five)\\s*(year|yr|month)s?\\s*(?:comprehensive\\s*)?warranty").find(text)
                ?: Regex("(?i)warranty[:\\s]*(\\d+|one|two|three|five)\\s*(year|yr|month)").find(text)
            var exp: Long? = null
            if (warranty != null) {
                val n = TextKit.number(warranty.groupValues[1]) ?: 1
                val purchase = date ?: TextKit.local(now, zone).toLocalDate()
                val until = if (warranty.groupValues[2].startsWith("m")) purchase.plusMonths(n.toLong()) else purchase.plusYears(n.toLong())
                fields["warranty"] = "$n ${if (warranty.groupValues[2].startsWith("m")) "month" else "year"}${if (n > 1) "s" else ""}"
                exp = TextKit.at(until, 10 * 60, zone)
            }
            val isUtility = product == "Electricity" || has("electricity", "bescom", "due date")
            if (isUtility) {
                val due = Regex("(?i)due date[:\\s]*([\\w ,/-]{6,14})").find(text)?.let { TextKit.parseDate(it.groupValues[1], now, zone) }
                return Extracted(ItemKind.BILL, "Electricity bill", fields, eventStart = due?.let { TextKit.at(it, 10 * 60, zone) }, placeKind = PlaceKind.HOME)
            }
            // A receipt starts with the shop's name: "BAGA BEACH SHACK" → "Baga Beach Shack bill".
            val shop = if (product != null) null else raw.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?.takeIf { l -> l.length in 3..32 && l.count { it.isLetter() } >= l.length * 0.7 && !Regex("(?i)invoice|bill|receipt|gst|tax").containsMatchIn(l) }
                ?.split(Regex("\\s+"))?.joinToString(" ") { w -> w.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() } }
            shop?.let { fields["shop"] = it }
            return Extracted(ItemKind.BILL, product?.let { "$it bill" } ?: shop?.let { "$it bill" } ?: "Bill", fields, expiresAt = exp)
        }
        if (has("cafe", "café", "restaurant", "bistro", "menu", "coffee")) {
            val name = Regex("([A-Z][\\w'&]+(?:\\s[A-Z][\\w'&]+){0,3})\\s*(?:Cafe|Café|Restaurant|Bistro|Coffee)").find(text)?.value
            return Extracted(ItemKind.PLACE_SAVE, name ?: "Café", fields)
        }
        val firstLine = raw.lineSequence().map { it.trim() }.firstOrNull { it.length in 3..40 } ?: "Screenshot"
        return Extracted(ItemKind.SCREENSHOT, firstLine, fields)
    }

    private fun expiryDate(text: String, now: Long, zone: ZoneId): LocalDate? {
        val m = Regex("(?i)(?:valid (?:till|until|upto)|expir\\w*(?: on)?|to|end date)[:\\s]*([\\w ,/.-]{6,16})").findAll(text).mapNotNull { TextKit.parseDate(it.groupValues[1], now, zone) }.lastOrNull()
        return m
    }
}
