package cc.savely.android.modules

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.ui.SavelyTheme
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.Timeout
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Shared RecordForm: blank Manga range uses ordinary INSERT, not the resumable series plan. */
@RunWith(AndroidJUnit4::class)
class RecordFormUncertaintyScreenTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(90)
    @get:Rule(order = 1) val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private lateinit var focus: FocusManager
    private var keyboard: SoftwareKeyboardController? = null
    private val rows = ConcurrentHashMap<String, JSONObject>()
    private val posts = CopyOnWriteArrayList<JSONObject>()
    private val reads = AtomicInteger()
    private val denied = AtomicInteger()
    private val failReads = AtomicBoolean(false)
    private val releasePost = CountDownLatch(1)
    private var responseLost = false
    private lateinit var foreignBefore: String

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("V11 shared Manga form fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); dispatcher = fixture(); start() }
        val origin = server.url("/")
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val url = chain.request().url
                check(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port)
                chain.proceed(chain.request())
            }.build()
        api = SupabaseApi(BackendConfig(origin.toString(), "v11-shared-key", origin.toString()),
            MemorySessionStore(Session("v11-shared-access", "v11-shared-refresh", Long.MAX_VALUE, OWNER,
                "shared-form@example.invalid")), client)
        rows[FOREIGN_ROW] = JSONObject().put("id", FOREIGN_ROW).put("user_id", FOREIGN_OWNER).put("category", "manga")
            .put("title", "FOREIGN_MANGA_MUST_NOT_APPEAR").put("quantity", 1).put("purchase_price", 999999)
            .put("reselling_value", 999999).put("created_at", "2026-06-01T00:00:00Z")
        foreignBefore = rows[FOREIGN_ROW]!!.toString()
    }

    @After fun cleanup() {
        releasePost.countDown()
        api.close()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun blankMangaRangeCommitted503ClosesDialogAndRequiresCompleteRefreshWithoutReplay() = uncertain(false)
    @Test fun blankMangaRangeLostResponseClosesDialogAndRequiresCompleteRefreshWithoutReplay() = uncertain(true)

    private fun uncertain(lost: Boolean) {
        responseLost = lost
        compose.setContent {
            val localFocus = LocalFocusManager.current
            val localKeyboard = LocalSoftwareKeyboardController.current
            SideEffect { focus = localFocus; keyboard = localKeyboard }
            SavelyTheme("light") { ModuleScreen(api, "libreria", Modifier.fillMaxSize()) }
        }
        touch(button("Manga"))
        await { reads.get() > 0 && enabled(button("Aggiungi")) }
        touch(button("Aggiungi"))
        val range = compose.onNode(hasSetTextAction() and hasText("Numero volume o intervallo (facoltativo)"))
        range.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        replace("Titolo", TITLE)
        replace("Prezzo acquisto unitario (€) (facoltativo)", "0,01")
        replace("Valore rivendita unitario (€) (facoltativo)", "0,02")
        clearInput()
        failReads.set(true)
        val readBefore = reads.get()
        touch(button("Salva"), taps = 2)
        await { posts.size == 1 && !enabled(button("Salva")) }
        compose.onNode(button("Salva")).assertIsNotEnabled()
        releasePost.countDown()
        await { compose.onAllNodes(button("Salva")).fetchSemanticsNodes().isEmpty() }
        assertEquals("No implicit reload after uncertain INSERT", readBefore, reads.get())
        compose.onAllNodesWithText("Riprendi serie").assertCountEquals(0)
        header()
        compose.onNode(button("Aggiungi")).assertIsNotEnabled()
        scroll(hasText(BLOCKED)).assertIsDisplayed()
        header(); touch(button("Aggiorna"))
        await { reads.get() == readBefore + 1 && enabled(button("Aggiorna")) }
        compose.onNode(button("Aggiungi")).assertIsNotEnabled()
        scroll(hasText(BLOCKED)).assertIsDisplayed()
        assertEquals(1, posts.size)
        failReads.set(false)
        header(); touch(button("Aggiorna"))
        await { reads.get() >= readBefore + 3 && enabled(button("Aggiungi")) }
        compose.onAllNodesWithText(BLOCKED).assertCountEquals(0)
        scroll(hasText("Titolo: $TITLE")).assertIsDisplayed()
        assertEquals(1, posts.size)
        assertEquals(1, rows.values.count { it.getString("user_id") == OWNER })
        val body = posts.single()
        assertEquals(TITLE, body.getString("title"))
        assertEquals("manga", body.getString("category"))
        assertEquals(1, body.getInt("quantity"))
        assertEquals(0, BigDecimal("0.01").compareTo(BigDecimal(body.get("purchase_price").toString())))
        assertEquals(0, BigDecimal("0.02").compareTo(BigDecimal(body.get("reselling_value").toString())))
        assertTrue(UUID.fromString(body.getString("id")).toString() == body.getString("id"))
        assertEquals(body.getString("id"), rows[body.getString("id")]!!.getString("id"))
        assertEquals(foreignBefore, rows[FOREIGN_ROW]!!.toString())
        compose.onAllNodesWithText("FOREIGN_MANGA_MUST_NOT_APPEAR", substring = true).assertCountEquals(0)
        assertEquals(0, denied.get())
    }

    private fun header() { clearInput(); compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0) }
    private fun scroll(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        clearInput()
        if (compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty())
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }
    private fun replace(label: String, value: String) = scroll(hasText(label) and hasSetTextAction()).performTextReplacement(value)
    private fun clearInput() = compose.runOnIdle { focus.clearFocus(force = true); keyboard?.hide() }
    private fun button(value: String) = hasText(value, substring = false) and hasClickAction()
    private fun enabled(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
        ?.let { !it.config.contains(SemanticsProperties.Disabled) } == true
    private fun await(predicate: () -> Boolean) = compose.waitUntil(15_000, predicate)
    private fun touch(matcher: SemanticsMatcher, taps: Int = 1) {
        clearInput()
        var previous: Rect? = null
        var stable = 0
        compose.waitUntil(15_000) {
            compose.waitForIdle(); compose.mainClock.advanceTimeByFrame()
            val node = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
            val bounds = node?.boundsInRoot
            val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().map { it.boundsInRoot }
            val ready = node != null && !node.config.contains(SemanticsProperties.Disabled) && bounds != null &&
                bounds.width > 0 && bounds.height > 0 && roots.any { it.contains(bounds.center) } && compose.onNode(matcher).isDisplayed()
            stable = if (ready && bounds == previous) stable + 1 else 0
            previous = bounds
            stable >= 2
        }
        compose.onNode(matcher).assertIsEnabled().assertIsDisplayed().performTouchInput {
            repeat(taps) { click(); advanceEventTime(40) }
        }
    }

    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return reject()
            if (request.getHeader("Authorization") != "Bearer v11-shared-access" || request.getHeader("apikey") != "v11-shared-key" ||
                url.pathSegments != listOf("rest", "v1", "library_items")) return reject()
            if (request.method == "GET") {
                if (url.queryParameterNames != setOf("user_id", "order", "category", "limit", "offset") ||
                    url.queryParameter("user_id") != "eq.$OWNER" || url.queryParameter("order") != "created_at.desc" ||
                    url.queryParameter("limit") != "500" || url.queryParameter("category") !in setOf("eq.libri", "eq.fumetti", "eq.manga")) return reject()
                val offset = url.queryParameter("offset")?.toIntOrNull()?.takeIf { it >= 0 } ?: return reject()
                if (url.queryParameter("category") != "eq.manga") return json(200, JSONArray())
                reads.incrementAndGet()
                if (failReads.get()) return json(503, JSONObject())
                val page = rows.values.filter { it.getString("user_id") == OWNER }
                    .sortedByDescending { it.getString("created_at") }.drop(offset).take(500)
                return json(200, JSONArray(page))
            }
            if (request.method == "POST" && url.queryParameterNames.isEmpty()) {
                val body = JSONObject(request.body.readUtf8())
                if (body.optString("user_id") != OWNER || body.optString("category") != "manga" || body.optString("title") != TITLE ||
                    !runCatching { UUID.fromString(body.getString("id")).toString() == body.getString("id") }.getOrDefault(false)) return reject()
                posts.add(JSONObject(body.toString()))
                if (rows.putIfAbsent(body.getString("id"), JSONObject(body.toString()).put("created_at", "2026-06-01T00:00:00Z")) != null)
                    return json(409, JSONObject())
                if (!releasePost.await(20, TimeUnit.SECONDS)) return json(503, JSONObject())
                return if (responseLost) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else json(503, JSONObject())
            }
            return reject() // No PATCH, DELETE, RPC or non-local endpoint is permitted.
        }
    }
    private fun json(status: Int, body: Any) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun reject(): MockResponse { denied.incrementAndGet(); return json(403, JSONObject()) }
    private companion object {
        const val OWNER = "71100000-0000-4000-8000-000000000001"
        const val FOREIGN_OWNER = "71100000-0000-4000-8000-000000000002"
        const val FOREIGN_ROW = "71100000-0000-4000-8000-000000000003"
        const val TITLE = "Manga senza intervallo"
        const val BLOCKED = "Le modifiche sono bloccate finché un aggiornamento dei dati non riesce."
    }
}
