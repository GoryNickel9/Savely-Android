package cc.savely.android.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class CoupleCalculationsTest {
    @Test fun validatesTheExactWebCoupleCodeAlphabet() {
        assertEquals("ABCD2345", normalizedCoupleCode(" abcd2345 "))
        listOf("", "ABCD234", "ABCD23456", "OABCD234", "IABCD234", "LABCD234", "0ABCD234", "1ABCD234", "ABC!2345").forEach {
            expectInvalid { normalizedCoupleCode(it) }
        }
    }

    @Test fun splitPreviewPreservesTheActualSqlOddCentRuleInsteadOfInventingAConservedSplit() {
        val even = coupleSplit(BigDecimal("100.00"))
        assertEquals(BigDecimal("50.00"), even.creator)
        assertEquals(BigDecimal.ZERO.setScale(2), even.roundingDifference)
        val odd = coupleSplit(BigDecimal("1.01"))
        assertEquals(BigDecimal("0.51"), odd.creator)
        assertEquals(BigDecimal("0.51"), odd.partner)
        assertEquals(BigDecimal("0.01"), odd.roundingDifference)
        val tiny = coupleSplit(BigDecimal("0.01"))
        assertEquals(BigDecimal("0.01"), tiny.partner)
    }

    @Test fun customSplitConservesTotalAndRejectsInvalidBoundariesAndPrecision() {
        val split = coupleSplit(BigDecimal("1.01"), couplePartnerAmount("0,50"))
        assertEquals(BigDecimal("0.51"), split.creator)
        assertEquals(BigDecimal("0.50"), split.partner)
        assertEquals(BigDecimal("1.01"), split.creator + split.partner)
        listOf("0", "-1", "1e2", "0.001", "NaN", "10000000000000", "").forEach { expectInvalid { couplePartnerAmount(it) } }
        expectInvalid { coupleSplit(BigDecimal("1"), BigDecimal("1")) }
        expectInvalid { coupleSplit(BigDecimal("1"), BigDecimal("2")) }
        expectInvalid { coupleSplit(BigDecimal("1"), BigDecimal("0.001")) }
        expectInvalid { coupleSplit(BigDecimal("-1")) }
    }

    @Test fun medianGroupsMonthsBeforeOrderingExcludesDeletedAndDoesNotInventEmptyMonths() {
        val rows = JSONArray()
            .put(row("2026-01-01", "10", "EUR", "Casa"))
            .put(row("2026-01-15", "20", "EUR", "Casa"))
            .put(row("2026-04-01", "50", "EUR", "Casa"))
            .put(row("2026-02-01", "5000", "EUR", "Casa", deleted = true))
            .put(row("2026-01-01", "999", "USD", "Casa"))
            .put(row("2026-01-01", "9", "EUR", "Spesa"))
        assertEquals(BigDecimal("40.00"), medianMonthlyCoupleShare(rows, "EUR", "Casa"))
        assertEquals(BigDecimal("999.00"), medianMonthlyCoupleShare(rows, "USD", "Casa"))
        assertEquals(BigDecimal("44.50"), medianMonthlyCoupleShare(rows, "EUR"))
        assertEquals(BigDecimal("0.00"), medianMonthlyCoupleShare(rows, "GBP"))
    }

    @Test fun medianRoundsHalfUpAndKeepsLargeAmountsExact() {
        val halfCent = JSONArray().put(row("2026-01-01", "0.01", "EUR", null)).put(row("2026-02-01", "0.02", "EUR", null))
        assertEquals(BigDecimal("0.02"), medianMonthlyCoupleShare(halfCent, "EUR"))
        val large = JSONArray().put(row("2026-01-01", "9999999999999.01", "EUR", null))
        assertEquals(BigDecimal("9999999999999.01"), medianMonthlyCoupleShare(large, "EUR"))
    }

    @Test fun categoriesComeFromLiveSharesAndExcludeAlreadyBudgetedCategories() {
        val shares = JSONArray().put(row("2026-01-01", "1", "EUR", "Casa"))
            .put(row("2026-01-01", "1", "EUR", "Cibo"))
            .put(row("2026-01-01", "1", "EUR", "Cibo"))
            .put(row("2026-01-01", "1", "EUR", "Rimossa", deleted = true))
            .put(row("2026-01-01", "1", "EUR", null))
        val budgets = JSONArray().put(JSONObject().put("couple_category_name", "Casa"))
        assertEquals(listOf("Cibo"), availableCoupleCategories(shares, budgets))
    }

    private fun row(date: String, amount: String, currency: String, category: String?, deleted: Boolean = false) =
        CoupleRepositoryTest.share(CoupleRepositoryTest.ROW1, date, amount, currency, category, deleted)
    private fun expectInvalid(block: () -> Unit) { try { block(); fail("Expected invalid input") } catch (_: IllegalArgumentException) { } }
}
