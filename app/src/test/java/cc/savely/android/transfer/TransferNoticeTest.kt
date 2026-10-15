package cc.savely.android.transfer

import androidx.lifecycle.ViewModelStore
import cc.savely.android.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class TransferNoticeTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SupabaseApi
    private lateinit var store: MemorySessionStore
    private lateinit var model: TransferViewModel
    private val models = ViewModelStore()
    private val failRead = AtomicBoolean(false)
    private val mutations = AtomicInteger()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method != "GET") { mutations.incrementAndGet(); return MockResponse().setResponseCode(403).setBody("{}") }
                    assertEquals("Bearer notice-access", request.getHeader("Authorization"))
                    if (failRead.get()) return MockResponse().setResponseCode(403).setBody("{}")
                    val url = request.requestUrl!!
                    val data = if (url.encodedPath == "/rest/v1/transactions" && url.queryParameter("id") == null)
                        """[{"id":"record-1","user_id":"user-1","amount":1.00}]""" else "[]"
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(data)
                }
            }
            start()
        }
        store = MemorySessionStore(Session("notice-access", "notice-refresh", System.currentTimeMillis() / 1000 + 3600, "user-1", "notice@example.test"))
        api = SupabaseApi(BackendConfig(server.url("/").toString(), "notice-public", server.url("/").toString()), store)
        model = TransferViewModel(api).also { models.put("notice", it) }
    }

    @After fun cleanup() {
        models.clear()
        api.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test fun failedReadReplacesTranslatedSuccessAndKeepsTheAccount() = runTest {
        val session = store.load()
        model.prepareExport()
        val ready = idle()
        assertEquals("{{rows}} righe in {{tables}} tabelle. Scegli dove salvare il CSV.", ready.messageNotice?.template)
        assertEquals(mapOf("rows" to 1, "tables" to 25), ready.messageNotice?.values?.toMap())
        failRead.set(true)
        model.prepareExport()
        val failed = idle()
        assertNotNull(failed.message)
        assertNull(failed.messageNotice)
        assertNull(failed.progressNotice)
        assertNull(failed.export)
        assertEquals(session, store.load())
        assertEquals(0, mutations.get())
    }

    @Test fun savingAndLaterFailureKeepTheCorrectNoticeWithoutBackendWrites() = runTest {
        model.prepareExport()
        val ready = idle()
        var written = ""
        model.saveExport { written = it }
        val saved = idle()
        assertEquals(ready.export?.csv, written)
        assertEquals("CSV salvato: {{rows}} righe da {{tables}} tabelle.", saved.messageNotice?.template)
        model.saveExport { throw IOException("Synthetic output failure") }
        val failed = idle()
        assertNull(failed.messageNotice)
        assertNull(failed.progressNotice)
        assertNotNull(failed.export)
        assertEquals(0, mutations.get())
    }

    @Test fun externalSaveMessageClearsOldTemplateValues() = runTest {
        model.prepareExport()
        idle()
        model.exportSaved("Documento chiuso.")
        assertEquals("Documento chiuso.", model.state.value.message)
        assertNull(model.state.value.messageNotice)
        assertEquals(0, mutations.get())
    }

    private suspend fun idle(): TransferState = model.state.first { !it.busy }
}
