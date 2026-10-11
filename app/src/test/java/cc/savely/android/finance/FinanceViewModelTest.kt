package cc.savely.android.finance

import androidx.lifecycle.viewModelScope
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.SupabaseApi
import cc.savely.android.data.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceViewModelTest {
    @Test fun `busy guard rejects second submit before coroutine starts and while first waits`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FinanceRepository(SupabaseApi(BackendConfig("https://fixture.invalid/", "public-test-key", "https://fixture.invalid/"), MemorySessionStore()))
        val model = FinanceViewModel(repository, "recurring")
        val release = CompletableDeferred<Unit>()
        var calls = 0
        try {
            model.mutate({ calls++; release.await(); error("fixture failure") }) {}
            model.mutate({ calls++ }) {}
            assertTrue(model.state.value.saving)
            assertEquals(0, calls)
            runCurrent()
            assertEquals(1, calls)
            model.mutate({ calls++ }) {}
            runCurrent()
            assertEquals(1, calls)
            release.complete(Unit)
            runCurrent()
            assertFalse(model.state.value.saving)
            assertEquals("fixture failure", model.state.value.error)
            model.mutate({ calls++; error("second fixture failure") }) {}
            runCurrent()
            assertEquals(2, calls)
            assertFalse(model.state.value.saving)
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun `process button duplicate dispatch causes one due query and reports zero work`() = runBlocking {
        val server = MockWebServer().apply { start() }
        Dispatchers.setMain(Dispatchers.Unconfined)
        val url = server.url("/").toString()
        val user = "30000000-0000-4000-8000-000000000001"
        val repository = FinanceRepository(SupabaseApi(BackendConfig(url, "public-test-key", url),
            MemorySessionStore(Session("access", "refresh", Long.MAX_VALUE, user, "vm@example.test"))))
        val model = FinanceViewModel(repository, "recurring")
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[]").setBodyDelay(200, TimeUnit.MILLISECONDS))
            repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[]")) }
            model.processDueRecurring()
            assertTrue(model.state.value.saving)
            model.processDueRecurring()
            withTimeout(10_000) { model.state.first { it.updatedAt != null } }
            assertFalse(model.state.value.saving)
            assertNull(model.state.value.error)
            assertEquals(RecurringProcessingResult().message, model.state.value.message)
            assertEquals(5, server.requestCount)
            val requests = (0 until 5).map { server.takeRequest() }
            assertEquals(1, requests.count { it.requestUrl!!.queryParameter("next_due_date")?.startsWith("lte.") == true })
            requests.forEach { assertEquals("eq.$user", it.requestUrl!!.queryParameter("user_id")) }
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
            server.shutdown()
        }
    }
}
