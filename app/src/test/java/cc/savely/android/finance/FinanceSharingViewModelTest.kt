package cc.savely.android.finance

import androidx.lifecycle.viewModelScope
import cc.savely.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceSharingViewModelTest {
    @Test fun `partial finance writes cannot repeat until a successful read even if refresh fails`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val url = server.url("/").toString()
        val api = SupabaseApi(BackendConfig(url, "fixture", url), MemorySessionStore(Session("access", "refresh", Long.MAX_VALUE,
            "11111111-1111-4111-8111-111111111111", "qa@example.test")))
        Dispatchers.setMain(Dispatchers.Unconfined)
        val model = FinanceViewModel(FinanceRepository(api), "transactions")
        try {
            val failure = TransactionSharingException("22222222-2222-4222-8222-222222222222", true, IllegalStateException("fixture"))
            model.mutate({ throw failure }) {}
            assertTrue(model.state.value.reconciliationRequired)
            var attempts = 0
            model.mutate({ attempts++ }) {}
            assertEquals(0, attempts)
            server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
            model.refresh()
            withTimeout(10_000) { model.state.first { !it.loading && it.error != null } }
            assertTrue(model.state.value.reconciliationRequired)
            model.mutate({ attempts++ }) {}
            assertEquals(0, attempts)
            repeat(3) { server.enqueue(MockResponse().setBody("[]")) }
            model.refresh()
            withTimeout(10_000) { model.state.first { !it.loading && it.updatedAt != null } }
            assertFalse(model.state.value.reconciliationRequired)
            model.mutate({ attempts++; error("stop before another read") }) {}
            assertEquals(1, attempts)
            model.mutate({ throw FinancePendingRefreshException("partial category detach", IllegalStateException("fixture")) }) {}
            assertTrue(model.state.value.reconciliationRequired)
            model.mutate({ attempts++ }) {}
            assertEquals(1, attempts)
            assertEquals(4, server.requestCount)
            repeat(4) { assertEquals("GET", server.takeRequest().method) }
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain(); api.close(); server.shutdown() }
    }
}
