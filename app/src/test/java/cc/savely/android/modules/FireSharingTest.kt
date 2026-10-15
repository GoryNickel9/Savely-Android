package cc.savely.android.modules

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class FireSharingTest {
    @Test fun bothCalculatorsRoundTripAllTenValuesWithoutChangingFractionalRates() {
        val original = FireParameters(FireCalculations.Inputs(currentAge = 41, retirementAge = 67,
            currentSavings = 98765.43, annualContribution = 12345.67, annualIncome = 65432.10,
            expectedReturn = 0.05125, inflationRate = 0.0275, withdrawalRate = 0.035,
            annualExpenses = 32100.55), 15800.75)
        for (calculator in FireCalculator.entries) {
            val link = FireSharing.link(ORIGIN, calculator, original)
            val parsed = FireSharing.preview(ORIGIN, link, FireParameters())
            assertEquals(calculator.path, link.toHttpUrl().encodedPath)
            assertEquals(calculator, parsed.calculator)
            assertEquals(original, parsed.parameters)
            assertTrue(parsed.inheritedKeys.isEmpty())
            assertTrue(parsed.unsupportedKeys.isEmpty())
        }
    }

    @Test fun defaultsAreExplicitSoRecipientStorageCannotOverrideTheSharedConfiguration() {
        val link = FireSharing.link(ORIGIN, FireCalculator.STANDARD, FireParameters()).toHttpUrl()
        assertEquals(setOf("age", "retire", "savings", "contrib", "income", "return", "inflation", "swr", "expenses", "parttime"), link.queryParameterNames)
        assertEquals("30", link.queryParameter("age"))
        assertEquals("55", link.queryParameter("retire"))
        assertEquals("100000.0", link.queryParameter("savings"))
        assertEquals("24000.0", link.queryParameter("contrib"))
        assertEquals("72000.0", link.queryParameter("income"))
        assertEquals("0.07", link.queryParameter("return"))
        assertEquals("0.03", link.queryParameter("inflation"))
        assertEquals("0.04", link.queryParameter("swr"))
        assertEquals("48000.0", link.queryParameter("expenses"))
        assertEquals("20000.0", link.queryParameter("parttime"))
        val recipient = FireParameters(FireCalculations.Inputs(currentSavings = 5.0, annualExpenses = 6.0), 7.0)
        assertEquals(FireParameters(), FireSharing.preview(ORIGIN, link.toString(), recipient).parameters)
    }

    @Test fun partialLinkKeepsExplicitPreviewBaselineAndDisclosesUnsupportedWebKeys() {
        val baseline = FireParameters(FireCalculations.Inputs(currentAge = 45, annualIncome = 81000.0), 13500.0)
        val before = baseline.encode()
        val preview = FireSharing.preview(ORIGIN, "$ORIGIN/fire/barista?expenses=30000&swr=0.04&portfolio=900000&years=35", baseline)
        assertEquals(FireCalculator.BARISTA, preview.calculator)
        assertEquals(30000.0, preview.parameters.inputs.annualExpenses, 0.0)
        assertEquals(45, preview.parameters.inputs.currentAge)
        assertEquals(81000.0, preview.parameters.inputs.annualIncome, 0.0)
        assertEquals(13500.0, preview.parameters.partTimeIncome, 0.0)
        assertEquals(listOf("age", "retire", "savings", "contrib", "income", "return", "inflation", "parttime"), preview.inheritedKeys)
        assertEquals(listOf("portfolio", "years"), preview.unsupportedKeys)
        assertEquals(before, baseline.encode())
    }

    @Test fun encodedExponentsAndOpaqueUnsupportedKeysAreDecodedOnce() {
        val preview = FireSharing.preview(ORIGIN,
            "$ORIGIN/fire/standard?savings=1e%2B3&return=-1e-2&%7B%7Bamount%7D%7D=opaque%26token%3Dvalue&parttime=0", FireParameters())
        assertEquals(1000.0, preview.parameters.inputs.currentSavings, 0.0)
        assertEquals(-0.01, preview.parameters.inputs.expectedReturn, 0.0)
        assertEquals(0.0, preview.parameters.partTimeIncome, 0.0)
        assertEquals(listOf("{{amount}}"), preview.unsupportedKeys)
        val shared = FireSharing.link(ORIGIN, preview.calculator, preview.parameters).toHttpUrl()
        assertEquals("-0.01", shared.queryParameter("return"))
        assertFalse(shared.queryParameterNames.contains("{{amount}}"))
        assertFalse(shared.toString().contains("token"))
    }

    @Test fun originAndRouteMustMatchConfiguredWebsiteWithOnlyBrandedAliasAllowed() {
        assertEquals(FireCalculator.STANDARD, FireSharing.preview(ORIGIN,
            "https://www.savely.cc/fire/standard/", FireParameters()).calculator)
        assertEquals(FireCalculator.BARISTA, FireSharing.preview("https://example.test:8443",
            "https://example.test:8443/fire/barista?age=40", FireParameters()).calculator)
        assertEquals(40, FireSharing.preview("http://localhost:1234",
            "http://localhost:1234/fire/standard?age=40", FireParameters()).parameters.inputs.currentAge)
        for (url in listOf("https://savely.cc.evil.test/fire/standard", "https://evil.test/fire/standard",
            "http://savely.cc/fire/standard", "https://savely.cc:8443/fire/standard",
            "https://user:password@savely.cc/fire/standard", "$ORIGIN/fire", "$ORIGIN/poker", "/fire/standard")) {
            assertTrue(url, runCatching { FireSharing.preview(ORIGIN, url, FireParameters()) }.isFailure)
        }
    }

    @Test fun duplicateEmptyMalformedNonFiniteAndInvalidDomainValuesAreRejected() {
        for (query in listOf("age=30&age=31", "age=30&%61ge=31", "expenses=", "expenses=12oops", "expenses=1,5",
            "expenses=NaN", "expenses=Infinity", "expenses=1e309", "age=30.5", "retire=121", "age=-1",
            "savings=-1", "parttime=-1", "return=-1", "inflation=-1", "return=1e308", "swr=1e308")) {
            assertTrue(query, runCatching { FireSharing.preview(ORIGIN, "$ORIGIN/fire/standard?$query", FireParameters()) }.isFailure)
        }
    }

    @Test fun emptyQueryUsesOnlyLocalBaselineAndInputSizeIsBounded() {
        val baseline = FireParameters(FireCalculations.Inputs(currentSavings = 123456.0))
        val preview = FireSharing.preview(ORIGIN, "  $ORIGIN/fire/standard  ", baseline)
        assertEquals(baseline, preview.parameters)
        assertEquals(10, preview.inheritedKeys.size)
        assertTrue(runCatching { FireSharing.preview(ORIGIN, "$ORIGIN/fire/standard?extra=" + "a".repeat(8192), baseline) }.isFailure)
    }

    @Test fun invalidSourceAndConfiguredOriginCannotProduceAUsableShareLink() {
        assertTrue(runCatching { FireSharing.link(ORIGIN, FireCalculator.STANDARD,
            FireParameters(FireCalculations.Inputs(currentSavings = -1.0))) }.isFailure)
        assertTrue(runCatching { FireSharing.link(ORIGIN, FireCalculator.BARISTA, FireParameters(partTimeIncome = Double.NaN)) }.isFailure)
        assertTrue(runCatching { FireSharing.link(ORIGIN, FireCalculator.STANDARD,
            FireParameters(FireCalculations.Inputs(expectedReturn = 1e308))) }.isFailure)
        for (origin in listOf("https://savely.cc/fire/standard", "https://savely.cc/?key=value", "http://example.test", "https://user:pass@savely.cc")) {
            assertTrue(origin, runCatching { FireSharing.link(origin, FireCalculator.STANDARD, FireParameters()) }.isFailure)
        }
    }

    private companion object { const val ORIGIN = "https://savely.cc" }
}
