package cc.savely.android.modules

import cc.savely.android.data.ExactJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Proposed V12 regression fixtures. Not compiled or executed in the S02 host verification. */
class CardTraderPriceContractTest {
    private fun dto(cents: Any = 1250, listings: Int = 1) = JSONObject()
        .put("quoteContractVersion", 2).put("blueprintId", "42").put("currency", "EUR").put("minorUnit", 2)
        .put("ctZeroLowPriceCents", cents).put("totalListings", listings)
    private fun parse(value: JSONObject) = CatalogRepository.cardPriceFromResponse(value, "42")
    private fun rejects(value: JSONObject) {
        assertTrue("An unsafe DTO must never become an automatic price", runCatching { parse(value) }.isFailure)
    }

    @Test fun exactEuroCentsCoverZeroOneCentOrdinaryAndSqlMaximum() {
        assertEquals(0.0, parse(dto(0))!!, 0.0)
        assertEquals(0.01, parse(dto(1))!!, 0.0)
        assertEquals(12.50, parse(dto(1250))!!, 0.0)
        assertEquals(99_999_999.99, parse(dto(9_999_999_999L))!!, 0.0)
    }

    @Test fun nullRequiresAnExplicitCentsFieldAndNoListings() {
        assertNull(parse(dto(JSONObject.NULL, 0)))
        rejects(dto(JSONObject.NULL, 1))
        rejects(dto(JSONObject.NULL, 0).apply { remove("ctZeroLowPriceCents") })
    }

    @Test fun unitlessLegacyAndFutureUnknownVersionAreRejected() {
        rejects(JSONObject("""{"ctZeroLowPriceCents":1250,"totalListings":1}"""))
        rejects(dto().put("quoteContractVersion", 1))
        rejects(dto().put("quoteContractVersion", 3))
        rejects(dto().put("quoteContractVersion", "2"))
    }

    @Test fun explicitEurAndMinorUnitTwoCannotBeBorrowedFromFormattedText() {
        rejects(dto().put("currency", "USD").put("ctZeroLowPrice", "€12.50"))
        rejects(dto().put("currency", JSONObject.NULL))
        rejects(dto().put("minorUnit", 0))
        rejects(dto().apply { remove("currency") })
        // The display-only provider string is never parsed or used as unit evidence.
        assertEquals(12.50, parse(dto().put("ctZeroLowPrice", "$99.99"))!!, 0.0)
    }

    @Test fun exactStringBlueprintMustMatchTheRequestedCanonicalIdentifier() {
        rejects(dto().put("blueprintId", "43"))
        rejects(dto().put("blueprintId", 42))
        rejects(dto().put("blueprintId", "042"))
        assertTrue(runCatching { CatalogRepository.cardPriceFromResponse(dto(), "legacy-id") }.isFailure)
    }

    @Test fun negativeFractionStringBooleanAndOverflowCentsCannotBeCoerced() {
        for (value in listOf<Any>(-1, "1250", false, 10_000_000_000L, java.math.BigDecimal("0.5"))) rejects(dto(value))
        val exactFraction = ExactJson.parse("""{"quoteContractVersion":2,"blueprintId":"42","currency":"EUR","minorUnit":2,"ctZeroLowPriceCents":1250.0000000000000001,"totalListings":1}""") as JSONObject
        rejects(exactFraction)
    }

    @Test fun populatedPriceRequiresPositiveIntegralListingCount() {
        rejects(dto(listings = 0))
        rejects(dto(listings = -1))
        rejects(dto().put("totalListings", "1"))
        rejects(dto().put("totalListings", java.math.BigDecimal("1.5")))
        rejects(dto().put("totalListings", Long.MAX_VALUE))
    }

    @Test fun exactJsonKeepsTinyFractionsThatDoubleWouldRoundAway() {
        val exactVersion = ExactJson.parse("""{"quoteContractVersion":2.0000000000000001,"blueprintId":"42","currency":"EUR","minorUnit":2,"ctZeroLowPriceCents":1250,"totalListings":1}""") as JSONObject
        rejects(exactVersion)
        val valid = ExactJson.parse("""{"quoteContractVersion":2,"blueprintId":"42","currency":"EUR","minorUnit":2,"ctZeroLowPriceCents":1250,"totalListings":1}""") as JSONObject
        assertEquals(12.50, parse(valid)!!, 0.0)
    }
}
