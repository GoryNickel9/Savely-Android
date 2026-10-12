package cc.savely.android.finance

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class FinanceExchangeRatesTest {
    @Test fun `public quote rounds SQL six decimals without Supabase or account headers`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder().followRedirects(false).build()
        try {
            server.enqueue(MockResponse().setBody("""{"base":"USD","rates":{"EUR":0.9155555}}"""))
            val rates = FinanceExchangeRates(client, server.url("/v1/latest"))
            assertEquals(BigDecimal("0.915556"), rates.toEuro("USD"))
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/latest", request.requestUrl!!.encodedPath)
            assertEquals(setOf("base", "symbols"), request.requestUrl!!.queryParameterNames)
            assertEquals("USD", request.requestUrl!!.queryParameter("base"))
            assertEquals("EUR", request.requestUrl!!.queryParameter("symbols"))
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("apikey")); assertNull(request.getHeader("Cookie"))
            assertEquals(BigDecimal.ONE, rates.toEuro("EUR"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun `redirected public quote is rejected without following or substituting a rate`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val redirectTarget = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
        try {
            redirectTarget.enqueue(MockResponse().setBody("""{"base":"USD","rates":{"EUR":0.91}}"""))
            server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", redirectTarget.url("/v1/latest?base=USD&symbols=EUR")))
            val rates = FinanceExchangeRates(client, server.url("/v1/latest"))
            val failure = runCatching { rates.toEuro("USD") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("Cambio automatico non disponibile. Inserisci il cambio manualmente.", failure!!.message)
            assertEquals(1, server.requestCount)
            assertEquals(0, redirectTarget.requestCount)
            val request = server.takeRequest()
            assertEquals("/v1/latest?base=USD&symbols=EUR", request.path)
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("apikey")); assertNull(request.getHeader("Cookie"))
        } finally {
            server.shutdown(); redirectTarget.shutdown()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    @Test fun `missing wrong negative and failed quotes never silently substitute one`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder().followRedirects(false).build()
        try {
            val rates = FinanceExchangeRates(client, server.url("/latest"))
            val fixtures = listOf("""{"base":"GBP","rates":{"EUR":0.91}}""", """{"base":"USD","rates":{}}""", """{"base":"USD","rates":{"EUR":-1}}""",
                """{"base":"USD","rates":{"EUR":0}}""", "[]")
            fixtures.forEach { body ->
                server.enqueue(MockResponse().setBody(body))
                val failure = runCatching { rates.toEuro("USD") }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertEquals("Cambio automatico non disponibile. Inserisci il cambio manualmente.", failure!!.message)
            }
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(runCatching { rates.toEuro("USD") }.isFailure)
            assertEquals(6, server.requestCount)
        } finally { server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
