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
            val rates = FinanceExchangeRates(client, server.url("/latest"))
            assertEquals(BigDecimal("0.915556"), rates.toEuro("USD"))
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("USD", request.requestUrl!!.queryParameter("from"))
            assertEquals("EUR", request.requestUrl!!.queryParameter("to"))
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("apikey")); assertNull(request.getHeader("Cookie"))
            assertEquals(BigDecimal.ONE, rates.toEuro("EUR"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
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
