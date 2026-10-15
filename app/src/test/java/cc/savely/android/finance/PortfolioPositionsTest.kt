package cc.savely.android.finance

import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class PortfolioPositionsTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: FinanceRepository
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val today = LocalDate.now()
    private fun asset(index: Int = 1) = FinanceAsset("22222222-2222-4222-8222-${index.toString().padStart(12, '0')}",
        "Synthetic purchase", "SYN&X", FinanceAssetType.STOCK, BigDecimal("2.00000000"), BigDecimal("10.00"),
        BigDecimal("12.00"), "EUR", today.minusDays(30), null, null, "")
    private fun row(asset: FinanceAsset, nullCurrency: Boolean = false, closed: Boolean = false) = JSONObject()
        .put("id", asset.id).put("user_id", owner).put("name", asset.name).put("symbol", asset.symbol ?: JSONObject.NULL)
        .put("type", asset.type.wire).put("quantity", asset.quantity.toPlainString()).put("purchase_price", asset.purchasePrice.toPlainString())
        .put("current_price", asset.currentPrice).put("currency", if (nullCurrency) JSONObject.NULL else asset.currency)
        .put("purchase_date", asset.purchaseDate.toString()).put("sold_at", if (closed) today.toString() else asset.soldAt ?: JSONObject.NULL)
        .put("sold_price", if (closed) "13.00" else asset.soldPrice ?: JSONObject.NULL)
    private fun enqueue(value: JSONObject) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
        .setBody(JSONArray().put(value).toString()))
    private suspend fun failure(block: suspend () -> Unit): PositionCloseException {
        try { block(); fail("Expected close failure") } catch (error: PositionCloseException) { return error }
        error("Unreachable")
    }
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val url = server.url("/").toString()
        repository = FinanceRepository(SupabaseApi(BackendConfig(url, "public-test-key", url),
            MemorySessionStore(Session("access", "refresh", Long.MAX_VALUE, owner, "fixture@example.test"))))
    }
    @After fun teardown() { server.shutdown() }

    @Test fun `position identity keeps currency type status and missing symbols separate`() {
        val original = asset()
        val groups = portfolioPositionGroups(listOf(original, asset(2).copy(symbol = " syn&x "),
            asset(3).copy(currency = "USD"), asset(4).copy(type = FinanceAssetType.ETF),
            asset(5).copy(soldAt = today, soldPrice = BigDecimal.ONE), asset(6).copy(symbol = null), asset(7).copy(symbol = null)))
        assertEquals(6, groups.size)
        assertEquals(2, groups.first().assets.size)
        assertEquals(0, BigDecimal("4").compareTo(groups.first().quantity))
        assertEquals(0, BigDecimal("40").compareTo(groups.first().cost))
    }
    @Test fun `preflight compares numeric values and original incomplete sale fields`() {
        val original = asset()
        assertTrue(samePositionPurchase(original, original.copy(quantity = BigDecimal("2"), purchasePrice = BigDecimal("10"))))
        assertFalse(samePositionPurchase(original, original.copy(quantity = BigDecimal("3"))))
        assertFalse(samePositionPurchase(original, original.copy(soldAt = today)))
        assertFalse(samePositionPurchase(original, original.copy(soldPrice = BigDecimal.ONE)))
        assertFalse(samePositionPurchase(original, original.copy(soldAt = today, soldPrice = BigDecimal.ONE)))
    }
    @Test fun `all purchases are validated before writes and each patch uses optimistic predicates`() = runBlocking {
        val assets = listOf(asset(), asset(2))
        assets.forEach { enqueue(row(it)) }
        assets.forEach { enqueue(row(it, closed = true)) }
        repository.closeAssetGroup(assets, today.toString(), "13")
        val requests = (0 until 4).map { server.takeRequest() }
        assertEquals(listOf("GET", "GET", "PATCH", "PATCH"), requests.map { it.method })
        requests.forEach { assertEquals("eq.$owner", it.requestUrl!!.queryParameter("user_id")) }
        requests.drop(2).forEach { request ->
            val url = request.requestUrl!!
            assertEquals("eq.SYN&X", url.queryParameter("symbol"))
            assertEquals("eq.EUR", url.queryParameter("currency"))
            assertEquals("eq.stock", url.queryParameter("type"))
            assertEquals("eq.2.00000000", url.queryParameter("quantity"))
            assertEquals("eq.10.00", url.queryParameter("purchase_price"))
            assertEquals("is.null", url.queryParameter("sold_at"))
            assertEquals("is.null", url.queryParameter("sold_price"))
            assertEquals(today.toString(), JSONObject(request.body.readUtf8()).getString("sold_at"))
        }
    }
    @Test fun `nullable legacy EUR currency remains an is null predicate on single close`() = runBlocking {
        val asset = asset()
        enqueue(row(asset, nullCurrency = true)); enqueue(row(asset, nullCurrency = true, closed = true))
        repository.closeAsset(asset, today.toString(), "13")
        assertEquals("GET", server.takeRequest().method)
        assertEquals("is.null", server.takeRequest().requestUrl!!.queryParameter("currency"))
    }
    @Test fun `changed last purchase aborts whole preflight without sending a close`() = runBlocking {
        val assets = listOf(asset(), asset(2))
        enqueue(row(assets[0])); enqueue(row(assets[1]).put("quantity", "3"))
        val error = failure { repository.closeAssetGroup(assets, today.toString(), "13") }
        assertEquals(0, error.confirmed); assertEquals(2, error.total); assertEquals(assets[1].id, error.uncertainId)
        assertFalse(error.attemptedWrite)
        assertEquals(2, server.requestCount)
        repeat(2) { assertEquals("GET", server.takeRequest().method) }
    }
    @Test fun `partial close stops after uncertain response and never retries later purchases`() = runBlocking {
        val assets = listOf(asset(), asset(2), asset(3))
        assets.forEach { enqueue(row(it)) }
        enqueue(row(assets[0], closed = true))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        val error = failure { repository.closeAssetGroup(assets, today.toString(), "13") }
        assertEquals(1, error.confirmed); assertEquals(3, error.total); assertEquals(assets[1].id, error.uncertainId)
        assertTrue(error.attemptedWrite)
        assertEquals(5, server.requestCount)
    }
    @Test fun `lost optimistic race and invalid representation remain uncertain not successful`() = runBlocking {
        val asset = asset()
        enqueue(row(asset))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[]"))
        val error = failure { repository.closeAsset(asset, today.toString(), "13") }
        assertEquals(0, error.confirmed); assertTrue(error.attemptedWrite)
        assertEquals(2, server.requestCount)
    }
    @Test fun `invalid selection dates prices and duplicates fail before any network call`() = runBlocking {
        val asset = asset()
        val cases = listOf<suspend () -> Unit>(
            { repository.closeAssetGroup(listOf(asset, asset), today.toString(), "13") },
            { repository.closeAssetGroup(listOf(asset, asset(2).copy(currency = "USD")), today.toString(), "13") },
            { repository.closeAssetGroup(listOf(asset), today.plusDays(1).toString(), "13") },
            { repository.closeAssetGroup(listOf(asset), today.toString(), "0") },
            { repository.closeAssetGroup(listOf(asset.copy(soldAt = today, soldPrice = BigDecimal.ONE)), today.toString(), "13") })
        cases.forEach { block -> try { block(); fail("Expected invalid selection") } catch (_: IllegalArgumentException) { } }
        assertEquals(0, server.requestCount)
    }
    @Test fun `price history read is owner scoped paginated and failure never produces partial totals`() = runBlocking {
        repeat(5) { server.enqueue(MockResponse().setBody("[]")) }
        enqueue(JSONObject().put("id", "history-1").put("asset_id", asset().id).put("price", "13.25").put("recorded_at", today.toString()))
        server.enqueue(MockResponse().setBody("[]"))
        val data = repository.load("portfolio")
        assertEquals(BigDecimal("13.25"), data.priceHistory.single().price)
        val requests = (0 until 7).map { server.takeRequest() }
        assertEquals(listOf("categories", "profiles", "portfolio_assets", "manual_price_updates", "tgc_cards", "asset_price_history", "asset_price_history"),
            requests.map { it.requestUrl!!.pathSegments.last() })
        requests.takeLast(2).forEach {
            assertEquals("eq.$owner", it.requestUrl!!.queryParameter("user_id"))
            assertEquals("recorded_at.asc,id.asc", it.requestUrl!!.queryParameter("order"))
            assertNull(it.requestUrl!!.queryParameter("deleted_at"))
        }
        assertEquals("1", requests.last().requestUrl!!.queryParameter("offset"))
    }
}
