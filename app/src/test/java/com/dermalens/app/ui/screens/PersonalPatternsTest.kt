package com.dermalens.app.ui.screens

import com.dermalens.app.data.model.ScanRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalPatternsTest {

    private fun scan(id: Int, itch: Int, pain: Int) =
        ScanRecord(scanId = id, groupId = 1, condition = "Eczema", confidence = 80f, itchLevel = itch, painLevel = pain)

    @Test
    fun learnsTriggerLinkedToHigherItch() {
        val scans = listOf(scan(1, 3, 0), scan(2, 2, 0), scan(3, 0, 0), scan(4, 1, 0))
        val triggers = mapOf(1 to listOf("Heat or sweating"), 2 to listOf("Heat or sweating"), 3 to listOf("Sun exposure"))

        val learned = PersonalPatterns.learn(scans, triggers)

        val heat = learned.patterns.single { it.trigger == "Heat or sweating" && it.measure == "Itch" }
        assertEquals(2.5, heat.avgWith, 0.001)
        assertEquals(0.5, heat.avgWithout, 0.001)
        assertEquals(2, heat.timesNoted)
        assertTrue(learned.matching(listOf("Heat or sweating")).isNotEmpty())
    }

    @Test
    fun needsAtLeastTwoScansWithTheTrigger() {
        val scans = listOf(scan(1, 3, 3), scan(2, 0, 0))
        val learned = PersonalPatterns.learn(scans, mapOf(1 to listOf("Heat or sweating")))
        assertTrue(learned.patterns.isEmpty())
    }

    @Test
    fun ignoresSmallDifferencesAndNotSure() {
        val scans = listOf(scan(1, 1, 1), scan(2, 1, 1), scan(3, 1, 1))
        val triggers = mapOf(1 to listOf("Stress or lack of sleep", CheckInOptions.NOT_SURE), 2 to listOf("Stress or lack of sleep", CheckInOptions.NOT_SURE))
        val learned = PersonalPatterns.learn(scans, triggers)
        assertTrue(learned.patterns.isEmpty())
        assertEquals(listOf("Stress or lack of sleep"), learned.usualTriggers)
    }

    @Test
    fun skipsUnansweredScans() {
        // -1 = scan saved before check-ins existed; must not count as "None".
        val scans = listOf(scan(1, 3, -1), scan(2, 3, -1), scan(3, -1, -1))
        val learned = PersonalPatterns.learn(scans, mapOf(1 to listOf("Heat or sweating"), 2 to listOf("Heat or sweating")))
        assertTrue(learned.patterns.isEmpty()) // nothing answered without the trigger to compare against
    }

    @Test
    fun checkInRoundTripsThroughTheRoute() {
        val original = SymptomCheckIn("Face", "1 to 4 weeks", 2, 0, "Better", listOf("Heat or sweating", "Sun exposure"))
        assertEquals(original, SymptomCheckIn.decode(original.encode()))
    }
}

class SpotStatusTest {
    private fun entry(id: Int, spots: Int?, itch: Int, pain: Int, feels: String = "", clear: Boolean = false) =
        ScanEntry(scanId = id, date = "", confidence = 80f, notes = "", imagePath = "x", rawDate = id.toLong(), isClear = clear,
            spotCount = spots, itchLevel = itch, painLevel = pain, feelsVsLast = feels)
    private fun track(vararg e: ScanEntry, clear: Boolean = false) =
        ConditionTrack("Acne Vulgaris", androidx.compose.ui.graphics.Color.Red, "", e.toList(), groupId = 1, latestClear = clear)

    @Test fun improvingWhenMostSignalsDrop() =
        assertEquals(SpotStatus.IMPROVING, assessSpot(track(entry(1, 3, 3, 2), entry(2, 1, 1, 0, "Better")))?.status)

    @Test fun worseningWhenMostSignalsRise() =
        assertEquals(SpotStatus.WORSENING, assessSpot(track(entry(1, 1, 0, 0), entry(2, 3, 2, 0, "Worse")))?.status)

    @Test fun stableOnATie() =
        assertEquals(SpotStatus.STABLE, assessSpot(track(entry(1, 2, 2, 1), entry(2, 1, 3, 1)))?.status)

    @Test fun clearWhenLatestFoundNothing() =
        assertEquals(SpotStatus.CLEAR, assessSpot(track(entry(1, 2, 2, 1), entry(2, 0, 0, 0, clear = true), clear = true))?.status)

    @Test fun noVerdictFromOneScan() =
        assertEquals(null, assessSpot(track(entry(1, 2, 2, 1))))
}
