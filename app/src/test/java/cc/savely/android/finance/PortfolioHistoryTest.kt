package cc.savely.android.finance

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortfolioHistoryTest {
    private val today = LocalDate.parse("2024-01-05")
    private fun asset(id: String = "a", type: FinanceAssetType = FinanceAssetType.STOCK, quantity: String = "2",
        purchase: String = "100", current: String? = "140", currency: String = "EUR", date: String = "2024-01-01",
        sold: String? = null, soldPrice: String? = null) = FinanceAsset(id, id, "SAME", type, quantity.toBigDecimal(),
        purchase.toBigDecimal(), current?.toBigDecimal(), currency, LocalDate.parse(date), sold?.let(LocalDate::parse), soldPrice?.toBigDecimal(), "")
    private fun price(id: String, date: String, value: String, asset: String = "a") =
        FinancePriceHistory(id, asset, LocalDate.parse(date), value.toBigDecimal())
    private fun snapshot(id: String, date: String, value: String, components: JSONObject? = null) =
        FinanceSnapshot(id, LocalDate.parse(date), value.toBigDecimal(), components)
    private fun decimal(expected: String, actual: BigDecimal) = assertEquals("Expected $expected, got $actual", 0, expected.toBigDecimal().compareTo(actual))
    private fun reconstructed(assets: List<FinanceAsset>, history: List<FinancePriceHistory> = emptyList(), end: LocalDate = today) =
        portfolioHistory(assets, history, PortfolioHistoryMode.RECONSTRUCTED, end)
    private fun recorded(assets: List<FinanceAsset>, history: List<FinancePriceHistory>) =
        portfolioHistory(assets, history, PortfolioHistoryMode.RECORDED, today)

    @Test fun `reconstruction excludes complete closures cash other and future purchases`() {
        val excluded = listOf(asset(type = FinanceAssetType.CASH), asset(type = FinanceAssetType.OTHER),
            asset(sold = "2024-01-03", soldPrice = "130"), asset(date = "2024-01-06"))
        assertTrue(reconstructed(emptyList()).isEmpty())
        assertTrue(reconstructed(excluded).isEmpty())
        assertTrue(recorded(excluded.take(3), listOf(price("p", "2024-01-02", "130"))).isEmpty())
    }

    @Test fun `reconstruction uses calendar day interpolation and current saved endpoint`() {
        val points = reconstructed(listOf(asset()))
        assertEquals(listOf("2024-01-01", "2024-01-02", "2024-01-03", "2024-01-04", "2024-01-05"), points.map { it.date.toString() })
        assertEquals(listOf("200", "220", "240", "260", "280"), points.map { it.value.stripTrailingZeros().toPlainString() })
        points.forEach { decimal("200", it.invested) }
        decimal("80", points.last().profit)
        assertEquals(listOf(1, 1, 1, 1, 0), points.map { it.interpolatedAssets })
        assertEquals(1, points.last().currentAssets)
    }

    @Test fun `exact dated history overrides interpolation but today uses current price not history`() {
        val history = listOf(price("middle", "2024-01-03", "200"), price("today", "2024-01-05", "999"))
        val points = reconstructed(listOf(asset()), history)
        decimal("400", points[2].value)
        assertEquals(1, points[2].recordedAssets)
        assertEquals(0, points[2].interpolatedAssets)
        decimal("260", points[3].value) // No carry-forward from the preceding recorded price.
        decimal("280", points.last().value)
        assertEquals(0, points.last().recordedAssets)
        assertEquals(1, points.last().currentAssets)
    }

    @Test fun `missing current price falls back to purchase without fabricating a market quote`() {
        val points = reconstructed(listOf(asset(current = null)), listOf(price("p", "2024-01-02", "90")))
        assertEquals(listOf("200", "180", "200", "200", "200"), points.map { it.value.stripTrailingZeros().toPlainString() })
        decimal("0", points.last().profit)
    }

    @Test fun `purchase dates change holdings only on their own inclusive calendar day`() {
        val points = reconstructed(listOf(asset(), asset("b", quantity = "3", purchase = "10", current = "20", date = "2024-01-03"),
            asset("future", date = "2024-01-06")))
        decimal("200", points[1].invested)
        decimal("230", points[2].invested)
        decimal("270", points[2].value)
        decimal("340", points.last().value)
        assertEquals(listOf(1, 1, 2, 2, 2), points.map { it.observedAssets })
        assertEquals(2, points.last().currentAssets)
    }

    @Test fun `purchase today gives one current point without division by zero`() {
        val point = reconstructed(listOf(asset(date = "2024-01-05"))).single()
        decimal("200", point.invested)
        decimal("280", point.value)
        assertEquals(1, point.currentAssets)
        assertEquals(0, point.interpolatedAssets)
    }

    @Test fun `leap dates and daylight saving boundaries use local days rather than milliseconds`() {
        val leap = reconstructed(listOf(asset(date = "2024-02-28", quantity = "1", current = "200")), end = LocalDate.parse("2024-03-01"))
        assertEquals(listOf("2024-02-28", "2024-02-29", "2024-03-01"), leap.map { it.date.toString() })
        decimal("150", leap[1].value)
        val dst = reconstructed(listOf(asset(date = "2024-03-29", quantity = "1", current = "140")), end = LocalDate.parse("2024-04-02"))
        assertEquals(listOf("100", "110", "120", "130", "140"), dst.map { it.value.stripTrailingZeros().toPlainString() })
    }

    @Test fun `half cent interpolation and quantities stay unrounded and inputs unchanged`() {
        val row = asset(quantity = "3", purchase = "0.305", current = "0.315", date = "2024-01-01")
        val rows = listOf(row)
        val before = row.copy()
        val points = reconstructed(rows, end = LocalDate.parse("2024-01-03"))
        decimal("0.915", points.first().invested)
        decimal("0.930", points[1].value)
        decimal("0.945", points.last().value)
        assertEquals(before, rows.single())
    }

    @Test fun `sampled reconstruction retains exact web day indices and final day`() {
        val first = LocalDate.parse("2024-01-01")
        val short = reconstructed(listOf(asset()), end = first.plusDays(118))
        assertEquals(119, short.size) // The web floor(n/60) rule is an approximate limit, not a hard sixty cap.
        val even = reconstructed(listOf(asset()), end = first.plusDays(119))
        assertEquals(61, even.size)
        assertEquals(first.plusDays(2), even[1].date)
        assertEquals(first.plusDays(119), even.last().date)
        val uneven = reconstructed(listOf(asset()), end = first.plusDays(181))
        assertEquals(62, uneven.size)
        assertEquals(first.plusDays(180), uneven[uneven.lastIndex - 1].date)
        assertEquals(first.plusDays(181), uneven.last().date)
        assertEquals(uneven.size, uneven.map { it.date }.distinct().size)
    }

    @Test fun `all supported currencies stay independent even with identical instrument symbols`() {
        val rows = supportedCurrencies.map { asset(it, currency = it) }
        val points = reconstructed(rows)
        assertEquals(supportedCurrencies.sorted(), points.map { it.currency }.distinct())
        assertEquals(supportedCurrencies.size * 5, points.size)
        points.filter { it.date == today }.forEach { decimal("280", it.value); decimal("200", it.invested) }
    }

    @Test fun `recorded mode includes only positions observed on each date without zero filling or current override`() {
        val rows = listOf(asset(), asset("b", quantity = "3", purchase = "10", current = "20"),
            asset("closed", sold = "2024-01-03", soldPrice = "120"))
        val prices = listOf(price("a1", "2024-01-01", "110"), price("b2", "2024-01-02", "12", "b"),
            price("a3", "2024-01-03", "120"), price("b3", "2024-01-03", "15", "b"),
            price("now", "2024-01-05", "150"), price("future", "2040-01-01", "160"),
            price("foreign", "2024-01-04", "999", "unrelated"), price("closed", "2024-01-04", "999", "closed"))
        val points = recorded(rows, prices)
        assertEquals(listOf("2024-01-01", "2024-01-02", "2024-01-03", "2024-01-05", "2040-01-01"), points.map { it.date.toString() })
        assertEquals(listOf("200", "30", "230", "200", "200"), points.map { it.invested.stripTrailingZeros().toPlainString() })
        assertEquals(listOf("220", "36", "285", "300", "320"), points.map { it.value.stripTrailingZeros().toPlainString() })
        assertEquals(listOf(1, 1, 2, 1, 1), points.map { it.recordedAssets })
        assertTrue(points.all { it.interpolatedAssets == 0 && it.currentAssets == 0 })
        assertTrue(recorded(rows, emptyList()).isEmpty())
    }

    @Test fun `recorded mode matches the hook before purchase and resolves duplicate day rows by id`() {
        val rows = listOf(asset(quantity = "1"))
        val prices = listOf(price("z", "2023-12-01", "120"), price("a", "2023-12-01", "90"))
        assertEquals(recorded(rows, prices), recorded(rows, prices.reversed()))
        decimal("120", recorded(rows, prices).single().value)
        assertEquals(LocalDate.parse("2023-12-01"), recorded(rows, prices).single().date)
    }

    @Test fun `recorded grouping separates currency and accumulates quantities exactly`() {
        val rows = listOf(asset("eur", quantity = "0.00000001"), asset("usd", quantity = "3", currency = "USD"))
        val prices = listOf(price("usd", "2024-01-02", "12.50", "usd"), price("eur", "2024-01-02", "999999999999.12345678", "eur"))
        val before = prices.map { it.copy() }
        val points = recorded(rows, prices)
        assertEquals(listOf("EUR", "USD"), points.map { it.currency })
        decimal("9999.9999999912345678", points.first().value)
        decimal("37.50", points.last().value)
        assertEquals(before, prices)
    }

    @Test fun `history parser preserves exact decimals and fails malformed required data`() {
        val json = JSONObject().put("id", "p").put("asset_id", "a").put("recorded_at", "2024-02-29")
            .put("price", BigDecimal("999999999999.12345678"))
        decimal("999999999999.12345678", priceHistoryFromJson(json).price)
        assertEquals(LocalDate.parse("2024-02-29"), priceHistoryFromJson(json).date)
        assertThrows(JSONException::class.java) { priceHistoryFromJson(JSONObject()) }
        assertThrows(NumberFormatException::class.java) { priceHistoryFromJson(JSONObject(json.toString()).put("price", "bad")) }
        assertThrows(DateTimeParseException::class.java) { priceHistoryFromJson(JSONObject(json.toString()).put("recorded_at", "2024-02-30")) }
    }

    @Test fun `historical ranges normalize custom input preserve leap month and use same duration comparison`() {
        val date = LocalDate.parse("2024-03-15")
        assertNull(historyPeriodRange(HistoryPeriod.ALL, date, "invalid", "invalid"))
        assertEquals(DashboardRange(LocalDate.parse("2024-01-01"), date), historyPeriodRange(HistoryPeriod.CURRENT_YEAR, date, "", ""))
        assertEquals(DashboardRange(LocalDate.parse("2024-02-01"), LocalDate.parse("2024-02-29")), historyPeriodRange(HistoryPeriod.PREVIOUS_MONTH, date, "", ""))
        val range = historyPeriodRange(HistoryPeriod.CUSTOM, date, "2024-03-15", "2024-03-01")!!
        assertEquals(DashboardRange(LocalDate.parse("2024-03-01"), date), range)
        assertEquals(DashboardRange(LocalDate.parse("2024-02-15"), LocalDate.parse("2024-02-29")), range.previous())
        assertTrue(LocalDate.parse("2024-03-01") in range && date in range)
        assertFalse(LocalDate.parse("2024-03-16") in range)
        listOf("", "2024-2-01", "2024-02-30", "0000-01-01").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { historyPeriodRange(HistoryPeriod.CUSTOM, date, invalid, "2024-03-15") }
        }
    }

    @Test fun `value changes retain negative baselines distinguish missing from zero and one point`() {
        assertNull(historicalValueChange(emptyList()))
        val negative = historicalValueChange(listOf(BigDecimal("-100"), BigDecimal("-50"), BigDecimal.ZERO))!!
        decimal("100", negative.change); decimal("100", negative.percent!!)
        assertNull(historicalValueChange(listOf(BigDecimal.ZERO, BigDecimal.TEN))!!.percent)
        val single = historicalValueChange(listOf(BigDecimal.TEN))!!
        assertEquals(1, single.count); decimal("0", single.change); decimal("0", single.percent!!)
    }

    @Test fun `server history sorts date id deterministically and keeps negative and future values unlabelled`() {
        val rows = listOf(snapshot("z", "2024-01-05", "-20"), snapshot("old", "2024-01-01", "-100"),
            snapshot("a", "2024-01-05", "-50"), snapshot("future", "2040-01-01", "25"))
        val before = rows.map { it.copy() }
        val history = serverNetWorthHistory(rows)!!
        assertEquals(listOf("old", "a", "z", "future"), history.snapshots.map { it.id })
        assertEquals(history, serverNetWorthHistory(rows.reversed()))
        decimal("125", history.variation); decimal("125", history.variationPercent!!)
        decimal("-100", history.minimum); decimal("25", history.maximum)
        assertEquals(before, rows)
        assertNull(serverNetWorthHistory(emptyList()))
        assertEquals(1, serverNetWorthHistory(listOf(rows.first()))!!.snapshots.size)
    }

    @Test fun `server period filtering compares only actual snapshots with no filling missing dates`() {
        val range = historyPeriodRange(HistoryPeriod.CUSTOM, LocalDate.parse("2024-06-15"), "2024-06-01", "2024-06-15")!!
        val rows = listOf(snapshot("p1", "2024-05-17", "80"), snapshot("p2", "2024-05-31", "100"),
            snapshot("c1", "2024-06-01", "110"), snapshot("c2", "2024-06-15", "130"), snapshot("future", "2028-06-15", "999"))
        val current = serverNetWorthHistory(rows.filter { it.date in range })!!
        val previous = serverNetWorthHistory(rows.filter { it.date in range.previous() })!!
        assertEquals(2, current.snapshots.size); assertEquals(2, previous.snapshots.size)
        decimal("20", current.variation); decimal("20", previous.variation)
        decimal("30", current.latest.value - previous.latest.value)
        decimal("30", dashboardPercentChange(current.latest.value, previous.latest.value)!!)
        assertNull(serverNetWorthHistory(rows.filter { it.date in DashboardRange(LocalDate.parse("2024-07-01"), LocalDate.parse("2024-07-31")) }))
    }

    @Test fun `snapshot components distinguish absent null and zero and never mask malformed numbers`() {
        assertNull(serverSnapshotComponents(snapshot("s", "2024-01-01", "1")))
        val partial = serverSnapshotComponents(snapshot("s", "2024-01-01", "1", JSONObject().put("cashflow", "0").put("portfolioPL", JSONObject.NULL)))!!
        decimal("0", partial.cashflow!!); assertNull(partial.portfolioGain); assertNull(partial.discountedEstate)
        val exact = serverSnapshotComponents(snapshot("s", "2024-01-01", "1", JSONObject().put("cashflow", "-123.456789")
            .put("portfolioPL", "0.305").put("realEstateDiscounted", "75.001")))!!
        decimal("-123.456789", exact.cashflow!!); decimal("0.305", exact.portfolioGain!!); decimal("75.001", exact.discountedEstate!!)
        assertThrows(NumberFormatException::class.java) { serverSnapshotComponents(snapshot("s", "2024-01-01", "1", JSONObject().put("cashflow", "bad"))) }
    }

    @Test fun `recorded display rounds half up in selected locale without currency or changing input`() {
        val value = BigDecimal("0.305")
        assertEquals("0,31", formatRecordedValue(value, Locale.ITALY))
        assertEquals("-0,31", formatRecordedValue(value.negate(), Locale.ITALY))
        assertEquals("0.31", formatRecordedValue(value, Locale.US))
        assertEquals("1,234.50", formatRecordedValue(BigDecimal("1234.5"), Locale.US))
        assertEquals(BigDecimal("0.305"), value)
    }

    @Test fun `return excludes cash other closed and estate but estate contributes to separate profit`() {
        val rows = listOf(asset(current = "110"), asset("etf", FinanceAssetType.ETF, "1", "100", "90"),
            asset("crypto", FinanceAssetType.CRYPTO, "1", "10", "20"), asset("bond", FinanceAssetType.BOND, "1", "30", "33"),
            asset("estate", FinanceAssetType.REAL_ESTATE, "1", "100", "200"), asset("cash", FinanceAssetType.CASH, "100", "1", "0.9"),
            asset("other", FinanceAssetType.OTHER, "1", "500", "200"), asset("closed", purchase = "1000", sold = "2024-01-04", soldPrice = "1"),
            asset("usd", quantity = "1", purchase = "10", current = "20", currency = "USD"))
        val result = portfolioReturnSummaries(rows)
        assertEquals(listOf("EUR", "USD"), result.map { it.currency })
        decimal("340", result.first().invested); decimal("363", result.first().investmentValue)
        decimal("23", result.first().investmentGain); decimal("123", result.first().gainIncludingEstate)
        decimal("6.764705882353", result.first().returnPercent!!)
        decimal("100", result.last().returnPercent!!)
    }

    @Test fun `zero investment cost and estate only groups have unavailable return rather than fabricated zero`() {
        val summaries = portfolioReturnSummaries(listOf(asset(purchase = "0", current = "20"),
            asset("estate", FinanceAssetType.REAL_ESTATE, "1", "100", "150", "USD")))
        assertTrue(summaries.all { it.returnPercent == null })
        decimal("40", summaries.first().investmentGain)
        decimal("50", summaries.last().gainIncludingEstate)
        assertTrue(portfolioReturnSummaries(emptyList()).isEmpty())
    }

    @Test fun `invested including estate excludes cash other closures and separates currency without changing return`() {
        val rows = listOf(asset(quantity = "1", purchase = "100", current = "125"),
            asset("estate", FinanceAssetType.REAL_ESTATE, "1", "200", "250"),
            asset("cash", FinanceAssetType.CASH, "50", "1", "1"),
            asset("other", FinanceAssetType.OTHER, "1", "30", "30"),
            asset("closed", quantity = "1", purchase = "999", sold = "2024-01-04", soldPrice = "999"),
            asset("usd", quantity = "1", purchase = "500", current = "600", currency = "USD"))
        val summaries = portfolioReturnSummaries(rows)
        val eur = summaries.single { it.currency == "EUR" }
        decimal("300", eur.investedIncludingEstate)
        decimal("100", eur.invested); decimal("125", eur.investmentValue)
        decimal("25", eur.returnPercent!!); decimal("75", eur.gainIncludingEstate)
        decimal("380", portfolioTotals(rows).single { it.currency == "EUR" }.cost)
        decimal("500", summaries.single { it.currency == "USD" }.investedIncludingEstate)
        decimal("20", summaries.single { it.currency == "USD" }.returnPercent!!)
    }

    @Test fun `allocation separates currencies values foreign cash in EUR and includes only safely priced TCG`() {
        val rows = listOf(asset(quantity = "1", current = "100"), asset("cash", FinanceAssetType.CASH, "100", "1", "0.5", "USD"),
            asset("estate", FinanceAssetType.REAL_ESTATE, "1", "100", "200"), asset("other", FinanceAssetType.OTHER, "1", "50", "50"),
            asset("usd", quantity = "1", current = "500", currency = "USD"), asset("closed", sold = "2024-01-04", soldPrice = "999"))
        val cards = listOf(FinanceTcgCard("priced", "magic", 4, BigDecimal.TEN, BigDecimal("25"), "EUR"),
            FinanceTcgCard("unpriced", "magic", 100, BigDecimal.TEN, null, "EUR"),
            FinanceTcgCard("zero", "magic", 100, BigDecimal.TEN, BigDecimal.ZERO, "EUR"),
            FinanceTcgCard("foreign", "magic", 100, BigDecimal.TEN, BigDecimal("25"), "USD"))
        val allocations = portfolioAllocations(rows, cards)
        val eur = allocations.filter { it.currency == "EUR" }
        assertEquals(listOf(FinanceAssetType.STOCK, FinanceAssetType.CASH, FinanceAssetType.REAL_ESTATE, FinanceAssetType.OTHER, null), eur.map { it.type })
        assertEquals(listOf("100", "50", "200", "50", "100"), eur.map { it.value.stripTrailingZeros().toPlainString() })
        listOf("20", "10", "40", "10", "20").zip(eur).forEach { (expected, allocation) -> decimal(expected, allocation.percent!!) }
        decimal("100", allocations.single { it.currency == "USD" }.percent!!)
    }

    @Test fun `zero allocation total keeps value but makes percentages unavailable`() {
        val rows = portfolioAllocations(listOf(asset(quantity = "0")), emptyList())
        assertEquals(1, rows.size); decimal("0", rows.single().value); assertNull(rows.single().percent)
        assertTrue(portfolioAllocations(emptyList(), emptyList()).isEmpty())
    }
}
