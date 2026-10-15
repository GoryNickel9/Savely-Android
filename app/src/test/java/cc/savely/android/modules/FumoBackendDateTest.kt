package cc.savely.android.modules

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/** Actual deployed TIMESTAMPTZ serialization; no network, device or credentials. */
class FumoBackendDateTest {
    @Test fun deployedTimestampFallbackKeepsTheSavedRowAndDoesNotInventDailyQuantity() {
        val row = JSONObject().put("data_acquisto", "2026-06-01")
            .put("data_finito", "2026-06-11T00:00:00+00:00").put("grammi", 20).put("costo", 10)
            .put("giorni_durata", JSONObject.NULL).put("grammi_al_giorno", JSONObject.NULL)
            .put("euro_al_giorno", JSONObject.NULL).put("costo_mensile", JSONObject.NULL)
        val before = row.toString()
        val result = fumoDisplayedDerived(row, false)
        assertEquals(10L, result.days)
        assertNull(result.quantityPerDay)
        assertEquals(1.0, result.euroPerDay!!, 0.0)
        assertEquals(30.0, result.monthlyCost!!, 0.0)
        assertEquals(before, row.toString())
    }

    @Test fun fallbackUsesCeilingOfElapsedInstantsRatherThanCalendarDateSubstrings() {
        val expected = linkedMapOf(
            "2026-06-11" to 10L,
            "2026-06-11T00:00:00Z" to 10L,
            "2026-06-11T12:00:00+00:00" to 11L,
            "2026-06-11T00:00:00+02:00" to 10L,
            "2026-06-11T23:00:00-02:00" to 12L)
        expected.forEach { (end, days) ->
            assertEquals(end, days, fumoLegacyDerived("2026-06-01", end, 20.0, 10.0).days)
        }
        // Across the Rome clock change, two calendar dates can be 25 elapsed hours apart.
        assertEquals(2L, fumoLegacyDerived("2026-10-25T00:00:00+02:00",
            "2026-10-26T00:00:00+01:00", 20.0, 10.0).days)
    }

    @Test fun backendMicrosecondsAreTruncatedToTheSameMillisecondPrecisionAsJavascriptDate() {
        assertEquals(10L, fumoLegacyDerived("2026-06-01", "2026-06-11T00:00:00.000999+00:00", 20.0, 10.0).days)
        assertEquals(11L, fumoLegacyDerived("2026-06-01", "2026-06-11T00:00:00.001001+00:00", 20.0, 10.0).days)
        assertEquals(FumoCalculations.Derived(null, null, null, null),
            fumoLegacyDerived("2026-06-01", "2026-06-01T00:00:00.000999+00:00", 20.0, 10.0))
    }

    @Test fun editorUsesLocalDayButAnUnchangedDayPreservesTheExactOriginalOffsetAndFraction() {
        val original = "2026-06-10T23:00:00.123456+00:00"
        val rome = ZoneId.of("Europe/Rome")
        assertEquals("2026-06-11", fumoEndDateForEditor(original, rome))
        assertEquals("2026-06-10", fumoEndDateForEditor(original, ZoneId.of("UTC")))
        assertEquals(original, fumoEndValueForSave(original, "2026-06-11", rome))
        assertEquals("2026-06-11", fumoEndDateForEditor("2026-06-11", ZoneId.of("America/Los_Angeles")))
        assertEquals("2026-06-11", fumoEndValueForSave("2026-06-11", "2026-06-11", rome))
    }

    @Test fun onlyExplicitDateReplacementOrRemovalDropsTheOriginalTimestamp() {
        val original = "2026-06-11T12:00:00.123456+00:00"
        val rome = ZoneId.of("Europe/Rome")
        assertEquals("2026-06-12", fumoEndValueForSave(original, "2026-06-12", rome))
        assertNull(fumoEndValueForSave(original, "", rome))
        val retained = fumoEndValueForSave(original, "2026-06-11", rome)
        val recalculated = fumoLegacyDerived("2026-06-01", retained, 44.0, 22.0)
        assertEquals(11L, recalculated.days)
        assertEquals(4.0, recalculated.quantityPerDay!!, 0.0)
        assertEquals(2.0, recalculated.euroPerDay!!, 0.0)
        assertEquals(60.0, recalculated.monthlyCost!!, 0.0)
        // LA shows the prior civil day, but the retained end is after UTC DATE arrival.
        val la = ZoneId.of("America/Los_Angeles")
        val valid = "2026-06-11T01:00:00Z"
        assertEquals("2026-06-10", fumoEndDateForEditor(valid, la))
        val savedValid = fumoEndValueForSave(valid, "2026-06-10", la)
        assertEquals(valid, savedValid)
        assertTrue(fumoEndNotBeforeArrival("2026-06-11", savedValid))
        assertEquals(1L, fumoLegacyDerived("2026-06-11", savedValid, 2.0, 1.0).days)
        // Rome shows the same civil day, but this retained end precedes UTC DATE arrival.
        val invalid = "2026-06-10T23:00:00Z"
        assertEquals("2026-06-11", fumoEndDateForEditor(invalid, rome))
        assertFalse(fumoEndNotBeforeArrival("2026-06-11",
            fumoEndValueForSave(invalid, "2026-06-11", rome)))
        assertTrue(fumoEndNotBeforeArrival("2026-06-11", "2026-06-11"))
    }

    @Test fun malformedTimestampWithoutOffsetAndNonFiniteInputsFailRatherThanBeingSilentlyTruncated() {
        listOf("2026-06-11T00:00:00", "2026-02-30", "2026-06-11garbage", " 2026-06-11").forEach { end ->
            assertThrows(RuntimeException::class.java) { fumoLegacyDerived("2026-06-01", end, 1.0, 1.0) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            fumoLegacyDerived("2026-06-01", "2026-06-11", Double.NaN, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fumoLegacyDerived("2026-06-01", "2026-06-11", 1.0, Double.POSITIVE_INFINITY)
        }
    }
}
