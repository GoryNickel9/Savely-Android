package cc.savely.android.finance

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetWorthModelsTest {
    private val today = LocalDate.of(2026, 6, 15)
    private fun snapshot(id: String, day: String, value: String, components: JSONObject? = null) =
        FinanceSnapshot(id, LocalDate.parse(day), BigDecimal(value), components)
    private fun decimal(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual))
    private val snapshots get() = listOf(snapshot("old", "2025-12-31", "-200"),
        snapshot("april", "2026-04-30", "-160"), snapshot("may-first", "2026-05-17", "-120"),
        snapshot("may-last", "2026-05-31", "-100"), snapshot("june-first", "2026-06-01", "-90"),
        snapshot("june-last", "2026-06-15", "-50"), snapshot("future", "2026-07-01", "25"))

    @Test fun `all year previous month and reversed custom preserve actual boundaries and negative comparisons`() {
        val input = snapshots.reversed()
        val before = input.toList()
        fun inPeriod(period: HistoryPeriod, from: String = "", to: String = ""): ServerNetWorthHistory {
            val range = historyPeriodRange(period, today, from, to)
            return serverNetWorthHistory(input.filter { range == null || it.date in range })!!
        }
        val all = inPeriod(HistoryPeriod.ALL)
        assertEquals(7, all.snapshots.size); decimal("225", all.variation); decimal("112.5", all.variationPercent!!)
        decimal("-200", all.minimum); decimal("25", all.maximum)
        val year = inPeriod(HistoryPeriod.CURRENT_YEAR)
        assertEquals(listOf("april", "may-first", "may-last", "june-first", "june-last"), year.snapshots.map { it.id })
        decimal("110", year.variation); decimal("68.75", year.variationPercent!!)
        val month = inPeriod(HistoryPeriod.PREVIOUS_MONTH)
        assertEquals(listOf("may-first", "may-last"), month.snapshots.map { it.id })
        decimal("20", month.variation); decimal("16.666666666667", month.variationPercent!!)
        val custom = historyPeriodRange(HistoryPeriod.CUSTOM, today, "2026-06-15", "2026-06-01")!!
        assertEquals(DashboardRange(LocalDate.of(2026, 6, 1), today), custom)
        assertEquals(DashboardRange(LocalDate.of(2026, 5, 17), LocalDate.of(2026, 5, 31)), custom.previous())
        val current = inPeriod(HistoryPeriod.CUSTOM, "2026-06-15", "2026-06-01")
        val previous = serverNetWorthHistory(input.filter { it.date in custom.previous() })!!
        assertEquals(listOf("june-first", "june-last"), current.snapshots.map { it.id })
        decimal("40", current.variation); decimal("44.444444444444", current.variationPercent!!)
        decimal("20", previous.variation); decimal("50", current.latest.value - previous.latest.value)
        decimal("50", dashboardPercentChange(current.latest.value, previous.latest.value)!!)
        assertNull(serverNetWorthHistory(input.filter { it.date in DashboardRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)) }))
        assertEquals(before, input)
    }

    @Test fun `zero single empty and missing or malformed components remain distinct without currency inference`() {
        val zero = serverNetWorthHistory(listOf(snapshot("zero", "2026-06-01", "0"), snapshot("next", "2026-06-15", "10")))!!
        decimal("10", zero.variation); assertNull(zero.variationPercent)
        val single = serverNetWorthHistory(listOf(snapshot("single", "2026-06-15", "-0.305")))!!
        assertEquals(1, single.snapshots.size); decimal("0", single.variation); decimal("0", single.variationPercent!!)
        decimal("-0.305", single.minimum); decimal("-0.305", single.maximum)
        assertNull(serverNetWorthHistory(emptyList()))
        assertNull(serverSnapshotComponents(snapshot("missing", "2026-06-15", "1")))
        val partial = snapshot("partial", "2026-06-15", "1", JSONObject().put("cashflow", "0").put("portfolioPL", JSONObject.NULL))
        val components = serverSnapshotComponents(partial)!!
        decimal("0", components.cashflow!!); assertNull(components.portfolioGain); assertNull(components.discountedEstate)
        assertThrows(NumberFormatException::class.java) {
            serverSnapshotComponents(snapshot("bad", "2026-06-15", "1", JSONObject().put("cashflow", "bad")))
        }
    }

    @Test fun `current components keep all time cashflow and separate native currencies excluding cash other and closed gains`() {
        fun tx(id: String, amount: String, type: TransactionType, currency: String, day: String = "2026-06-15") =
            FinanceTransaction(id, type, BigDecimal(amount), currency, LocalDate.parse(day), id, null)
        fun asset(id: String, type: FinanceAssetType, currency: String, quantity: String, purchase: String, current: String, closed: Boolean = false) =
            FinanceAsset(id, id, id, type, BigDecimal(quantity), BigDecimal(purchase), BigDecimal(current), currency,
                today.minusDays(4), if (closed) today else null, if (closed) BigDecimal("999") else null, "")
        val transactions = listOf(tx("eur-in", "100", TransactionType.INCOME, "EUR", "2025-01-01"),
            tx("eur-out", "200", TransactionType.EXPENSE, "EUR"),
            tx("usd-in", "100", TransactionType.INCOME, "USD", "2026-07-01"),
            tx("usd-out", "50", TransactionType.EXPENSE, "USD"))
        val assets = listOf(asset("stock", FinanceAssetType.STOCK, "EUR", "2", "100", "140"),
            asset("estate", FinanceAssetType.REAL_ESTATE, "EUR", "1", "100", "200"),
            asset("cash", FinanceAssetType.CASH, "USD", "1000", "1", "0.5"),
            asset("other", FinanceAssetType.OTHER, "EUR", "1", "1", "999"),
            asset("closed", FinanceAssetType.STOCK, "EUR", "100", "1", "999", true),
            asset("etf", FinanceAssetType.ETF, "USD", "2", "10", "20"))
        val result = netWorthComponents(transactions, assets)
        assertEquals(listOf("EUR", "USD"), result.map { it.currency })
        val eur = result.first(); decimal("-100", eur.cashflow); decimal("80", eur.portfolioGain)
        decimal("150", eur.discountedRealEstate); decimal("130", eur.total)
        val usd = result.last(); decimal("50", usd.cashflow); decimal("20", usd.portfolioGain)
        decimal("0", usd.discountedRealEstate); decimal("70", usd.total)
    }

    @Test fun `snapshot parser permits absent null and object components but rejects malformed outer shapes`() {
        fun row() = JSONObject().put("id", "snapshot").put("date", "2026-06-15").put("net_worth", "-0.305")
        assertNull(snapshotFromJson(row()).components)
        assertNull(snapshotFromJson(row().put("components", JSONObject.NULL)).components)
        val original = JSONObject().put("cashflow", "0").put("portfolioPL", JSONObject.NULL)
        assertSame(original, snapshotFromJson(row().put("components", original)).components)
        listOf<Any>(JSONArray(), "bad", true, 1).forEach { malformed ->
            val error = assertThrows(JSONException::class.java) { snapshotFromJson(row().put("components", malformed)) }
            assertEquals("Componenti dello snapshot non validi.", error.message)
        }
    }
}
