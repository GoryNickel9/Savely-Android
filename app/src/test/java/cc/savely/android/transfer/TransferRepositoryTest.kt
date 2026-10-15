package cc.savely.android.transfer

import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TransferRepositoryTest {
    @Test fun paginatesUntilEmptyEvenIfBackendCapsBelowRequestedLimit() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("Bearer access", request.getHeader("Authorization"))
                if (request.requestUrl?.encodedPath == "/rest/v1/transactions") {
                    assertEquals("eq.user-1", request.requestUrl?.queryParameter("user_id"))
                    val cursor = request.requestUrl?.queryParameter("id")
                    val record = when (cursor) {
                        null -> JSONObject().put("id", "record-1").put("user_id", "user-1").put("amount", "1.20")
                        "gt.record-1" -> JSONObject().put("id", "record-2").put("user_id", "user-1").put("amount", "2.30")
                        "gt.record-2" -> null
                        else -> error("Unexpected pagination cursor")
                    }
                    return response(if (record == null) "[]" else JSONArray().put(record).toString())
                }
                return response("[]")
            }
        }
        server.start()
        try {
            val result = repository(server).export()
            assertEquals(2, result.counts["transactions"])
            assertEquals(25, result.counts.size)
            assertEquals(3, TransferCsv.parseRows(result.csv).size)
            assertTrue(result.csv.contains("record-2"))
            assertTrue(server.requestCount > 20)
        } finally { server.shutdown() }
    }

    @Test fun oneFailedTableAbortsExportInsteadOfProducingSilentPartialFile() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl?.encodedPath == "/rest/v1/categories") MockResponse().setResponseCode(403).setBody("{}")
                else response("[]")
        }
        server.start()
        try {
            try { repository(server).export(); fail("Partial export accepted") }
            catch (error: cc.savely.android.data.ApiException) { assertEquals(403, error.status) }
        } finally { server.shutdown() }
    }

    private fun repository(server: MockWebServer): TransferRepository {
        val store = MemorySessionStore(Session("access", "refresh", System.currentTimeMillis() / 1000 + 3600, "user-1", "person@example.com"))
        return TransferRepository(SupabaseApi(BackendConfig(server.url("/").toString(), "public-key", server.url("/").toString()), store))
    }
    private fun response(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}
