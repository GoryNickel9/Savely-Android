package cc.savely.android.finance

import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import cc.savely.android.data.ApiException
import cc.savely.android.data.ApiError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceFormOwnerTest {
    private val userA = "11111111-1111-4111-8111-111111111111"
    private val userB = "22222222-2222-4222-8222-222222222222"
    private val sidA = "33333333-3333-4333-8333-333333333333"
    private val sidB = "44444444-4444-4444-8444-444444444444"

    private fun session(user: String, sid: String, signature: String = "first"): Session {
        fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        val token = "${encode("{}")}.${encode("""{"sub":"$user","session_id":"$sid"}""")}.$signature"
        return Session(token, "synthetic-refresh", Long.MAX_VALUE, user, "fixture@example.test")
    }

    @Test fun `loaded form rejects a replacement user before any network write or reload`() = runBlocking {
        rejectsReplacement(session(userB, sidB))
    }

    @Test fun `loaded form rejects a new session for the same user before any network write or reload`() = runBlocking {
        rejectsReplacement(session(userA, sidB))
    }

    private suspend fun rejectsReplacement(replacement: Session) {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse().setBody("[]")) }
            val store = MemorySessionStore(session(userA, sidA))
            val url = server.url("/").toString()
            val api = SupabaseApi(BackendConfig(url, "synthetic-public-key", url), store)
            val repository = FinanceRepository(api)
            repository.load("categories")
            val revision = api.sessionOwnerRevision()
            store.save(replacement)
            api.session()
            assertTrue(revision != api.sessionOwnerRevision())
            val operations: List<suspend () -> Unit> = listOf(
                { repository.saveCategory(null, "Draft from A", TransactionType.EXPENSE, "📦", "#6b7280") },
                { repository.saveBudget(null, sidA, "3.00") },
                { repository.delete("transactions", sidA) },
                { repository.load("categories"); Unit },
            )
            operations.forEach { operation ->
                val failure = runCatching { operation() }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertEquals("Account cambiato. Aggiorna i dati prima di continuare.", failure?.message)
            }
            assertEquals("Stale form callbacks cannot use the replacement owner's token", 2, server.requestCount)
        }
    }

    @Test fun `refresh of the same accepted session keeps a loaded form usable`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(4) { server.enqueue(MockResponse().setBody("[]")) }
            val store = MemorySessionStore(session(userA, sidA))
            val url = server.url("/").toString()
            val api = SupabaseApi(BackendConfig(url, "synthetic-public-key", url), store)
            val repository = FinanceRepository(api)
            repository.load("categories")
            val revision = api.sessionOwnerRevision()
            store.save(session(userA, sidA, "refreshed"))
            api.session()
            assertEquals(revision, api.sessionOwnerRevision())
            assertEquals(userA, repository.load("categories").userId)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun `late first page is rejected before pagination or profile can use a replacement session`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl?.pathSegments?.last() == "categories" &&
                        request.requestUrl?.queryParameter("offset") == "0") {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        return MockResponse().setBody("""[{"id":"$sidA","name":"Old owner category","type":"expense","icon":"📦","color":"#6b7280"}]""")
                    }
                    return MockResponse().setBody("[]")
                }
            }
            server.start()
            val store = MemorySessionStore(session(userA, sidA))
            val url = server.url("/").toString()
            val api = SupabaseApi(BackendConfig(url, "synthetic-public-key", url), store)
            val repository = FinanceRepository(api)
            val pending = async(Dispatchers.Default) { runCatching { repository.load("categories") } }
            try {
                assertTrue("The original read must be in flight", entered.await(10, TimeUnit.SECONDS))
                store.save(session(userB, sidB))
                assertEquals(userB, api.session()?.userId)
                release.countDown()
                val failure = pending.await().exceptionOrNull()
                assertTrue(failure is ApiException)
                assertEquals(ApiError.PERMISSION, (failure as ApiException).kind)
                assertEquals("No second read may use B's credentials for A's query", 1, server.requestCount)
                assertEquals("Bearer ${session(userA, sidA).accessToken}", server.takeRequest().getHeader("Authorization"))
                assertEquals("A rejected old read must retain B's accepted session", userB, api.session()?.userId)
            } finally { release.countDown() }
        }
    }
}
