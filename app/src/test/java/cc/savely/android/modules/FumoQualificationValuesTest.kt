package cc.savely.android.modules

import cc.savely.android.data.ApiError
import cc.savely.android.data.ApiException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** Pinned FumoCrudPage call-site semantics; no transport or device is needed. */
class FumoQualificationValuesTest {
    @Test fun annualMonthlyAndPerGramTiesUseTheSameBinaryRoundingAsWebToFixed() {
        assertEquals("0,13\u00a0€", fumoMoney(1.50 / 12))
        assertEquals("0,30\u00a0€", fumoMoney(0.305))
        assertEquals("1,00\u00a0€", fumoMoney(1.005))
        assertEquals("0,00\u00a0€", fumoMoney(0.0))
        assertThrows(IllegalArgumentException::class.java) { fumoMoney(Double.NaN) }
    }

    @Test fun onlyExplicitHttpRejectionAllowsCorrectionWithoutReconciliation() {
        listOf(400, 401, 403, 404, 409, 422, 429).forEach { status ->
            assertFalse("An explicit rejection must remain correctable: $status",
                recordMutationRequiresRefresh(ApiException(ApiError.PERMISSION, "rejected", status)))
        }
        listOf(200, 201, 408, 500, 503).forEach { status ->
            assertTrue(recordMutationRequiresRefresh(ApiException(ApiError.RESPONSE, "uncertain", status)))
        }
        // A local postflight owner guard can fail after a successful remote commit.
        assertTrue(recordMutationRequiresRefresh(ApiException(ApiError.PERMISSION, "owner changed")))
        assertTrue(recordMutationRequiresRefresh(IOException("response lost")))
        assertTrue(recordMutationRequiresRefresh(CancellationException("cancelled after dispatch")))
    }

    @Test fun zeroSavedFieldsFollowTheActiveWebRendererRatherThanAnUnusedPureHelper() {
        val row = base().put("giorni_durata", 10).put("grammi_al_giorno", 0)
            .put("euro_al_giorno", 0).put("costo_mensile", 0)
        val before = row.toString()
        val nonzeroCost = fumoDetailValues(fumoDisplayedDerived(row, false))!!
        assertEquals(10L, nonzeroCost.days)
        assertEquals("0,00", nonzeroCost.quantity)
        assertEquals("-", nonzeroCost.dailyCost)
        assertEquals("4,50\u00a0€", nonzeroCost.monthlyCost)
        assertEquals(before, row.toString())
        row.put("costo", 0)
        val zeroCost = fumoDetailValues(fumoDisplayedDerived(row, false))!!
        assertEquals("-", zeroCost.monthlyCost)
        assertEquals("-", zeroCost.dailyCost)
        assertEquals("0,00", zeroCost.quantity)
        row.put("grammi", JSONObject.NULL).put("giorni_durata", 0)
        assertEquals(0.0, fumoQuantity(row), 0.0)
        val legacyNullQuantity = fumoDetailValues(fumoDisplayedDerived(row, false))!!
        assertNull(legacyNullQuantity.days)
        assertEquals("0,00", legacyNullQuantity.quantity)
        assertEquals("-", legacyNullQuantity.monthlyCost)
    }

    @Test fun anOpenLegacyRowRetainsSavedColumnsButExplicitSaveOrSameDayFinishClearsThem() {
        val row = base().put("data_finito", JSONObject.NULL).put("giorni_durata", JSONObject.NULL)
            .put("grammi_al_giorno", 2).put("euro_al_giorno", 0.125).put("costo_mensile", 3.75)
        val before = row.toString()
        val displayed = fumoDetailValues(fumoDisplayedDerived(row, false))!!
        assertNull(displayed.days)
        assertEquals("2,00", displayed.quantity)
        assertEquals("0,13\u00a0€", displayed.dailyCost)
        assertEquals("3,75\u00a0€", displayed.monthlyCost)
        assertEquals(before, row.toString())
        val start = LocalDate.of(2026, 6, 1)
        listOf<LocalDate?>(null, start).forEach { finish ->
            assertEquals(FumoCalculations.Derived(null, null, null, null),
                FumoCalculations.derived(start, finish, 12.0, 1.5))
        }
    }

    private fun base() = JSONObject().put("costo", 1.5).put("grammi", 12)
        .put("data_acquisto", "2026-06-01").put("data_finito", "2026-06-11")
}
