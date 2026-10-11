package cc.savely.android.finance

import androidx.lifecycle.viewModelScope
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class PositionCloseViewModelTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private fun model(server: MockWebServer): FinanceViewModel {
        val url = server.url("/").toString()
        return FinanceViewModel(FinanceRepository(SupabaseApi(BackendConfig(url, "public-test-key", url),
            MemorySessionStore(Session("access", "refresh", Long.MAX_VALUE, owner, "fixture@example.test")))), "portfolio")
    }
    @Test fun `uncertain writes block further mutations until a successful refresh even after read failure`() = runBlocking {
        val server = MockWebServer().apply { start() }
        Dispatchers.setMain(Dispatchers.Unconfined)
        val model = model(server)
        try {
            val failure = PositionCloseException(1, 2, "uncertain", IllegalStateException("fixture"))
            model.mutate({ throw failure }) {}
            assertTrue(model.state.value.reconciliationRequired)
            assertSame(failure, model.state.value.positionCloseFailure)
            var calls = 0
            model.mutate({ calls++ }) {}
            assertEquals(0, calls)
            server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
            model.refresh()
            withTimeout(10_000) { model.state.first { !it.loading && it.error != null } }
            assertTrue(model.state.value.reconciliationRequired)
            assertSame(failure, model.state.value.positionCloseFailure)
            model.mutate({ calls++ }) {}
            assertEquals(0, calls)
            repeat(6) { server.enqueue(MockResponse().setBody("[]")) }
            model.refresh()
            withTimeout(10_000) { model.state.first { it.updatedAt != null && !it.loading } }
            assertFalse(model.state.value.reconciliationRequired)
            assertNull(model.state.value.positionCloseFailure)
            model.mutate({ calls++; error("stop before refresh") }) {}
            assertEquals(1, calls)
            assertEquals(7, server.requestCount)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain(); server.shutdown() }
    }
    @Test fun `confirmed close also blocks stale editing when automatic refresh fails`() = runBlocking {
        val server = MockWebServer().apply { start() }
        Dispatchers.setMain(Dispatchers.Unconfined)
        val model = model(server)
        try {
            val today = LocalDate.now()
            val asset = FinanceAsset("22222222-2222-4222-8222-222222222222", "Synthetic", "SYN", FinanceAssetType.STOCK,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, "EUR", today.minusDays(10), null, null, "")
            fun row(closed: Boolean) = JSONObject().put("id", asset.id).put("user_id", owner).put("name", "Synthetic")
                .put("symbol", "SYN").put("type", "stock").put("quantity", "1").put("purchase_price", "10")
                .put("current_price", "10").put("currency", "EUR").put("purchase_date", asset.purchaseDate.toString())
                .put("sold_at", if (closed) today.toString() else JSONObject.NULL).put("sold_price", if (closed) "12" else JSONObject.NULL)
            listOf(false, true).forEach { server.enqueue(MockResponse().setBody(JSONArray().put(row(it)).toString())) }
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            var successes = 0
            model.closePositions(listOf(asset), today.toString(), "12") { successes++ }
            withTimeout(10_000) { model.state.first { !it.loading && !it.saving && it.error != null } }
            assertEquals(1, successes)
            assertTrue(model.state.value.reconciliationRequired)
            assertNull(model.state.value.positionCloseFailure)
            var edits = 0
            model.mutate({ edits++ }) {}
            assertEquals(0, edits)
            assertEquals(3, server.requestCount)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain(); server.shutdown() }
    }
}
