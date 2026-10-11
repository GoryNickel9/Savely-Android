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
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Savely 1cfcf629: fumoCrud.computeDerived and FumoCrudPage create/update.
 * TLS and real Compose touch use synthetic owners/rows; deployed RLS is outside this fixture.
 */
@RunWith(AndroidJUnit4::class)
class FumoScreenTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(90)
    @get:Rule(order = 1) val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private lateinit var focus: FocusManager
    private var keyboard: SoftwareKeyboardController? = null
    private val rows = ConcurrentHashMap<String, JSONObject>()
    private val posts = CopyOnWriteArrayList<JSONObject>()
    private val patches = CopyOnWriteArrayList<JSONObject>()
    private val patchedIds = CopyOnWriteArrayList<String>()
    private val liquidReads = AtomicInteger()
    private val denied = AtomicInteger()
    private val foreignScopeRequests = AtomicInteger()
    private val blockPost = AtomicBoolean(false)
    private val releasePost = CountDownLatch(1)
    private var touchDiagnostic = "No pointer target sampled"
    private val year = LocalDate.now().year
    private val arrival = "$year-06-01"
    private val finish = "$year-06-11"
    private val beforeArrival = "$year-05-31"

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Savely Fumo UI fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
        server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            dispatcher = fixture()
            start()
        }
        val base = server.url("/").toString()
        val session = Session("fumo-device-access", "fumo-device-refresh",
            System.currentTimeMillis() / 1000 + 3600, OWNER, "fumo-ui@example.test")
        api = SupabaseApi(BackendConfig(base, "public-test-key", base), MemorySessionStore(session), client)
        rows[FOREIGN_ROW] = openLiquid(FOREIGN_ROW, FOREIGN_OWNER).put("costo", 999999).put("millilitri", 999999)
    }

    @After fun cleanup() {
        releasePost.countDown()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun liquidRejectsMalformedDecimalAndEndBeforeArrivalThenSingleSubmitPersistsDerivedValues() {
        blockPost.set(true)
        mountLiquid()
        addForm()
        replace(COST, "1,2,3")
        replace(QUANTITY, "25")
        replace(ARRIVAL, arrival)
        replace(FINISH, finish)
        touch(button("Salva"))
        compose.onNodeWithText("Inserisci un numero valido").performScrollTo().assertIsDisplayed()
        assertEquals(0, posts.size)
        assertEquals(0, patches.size)

        replace(COST, "12,50")
        replace(FINISH, beforeArrival)
        touch(button("Salva"))
        compose.onNodeWithText("La data di fine precede l'arrivo").performScrollTo().assertIsDisplayed()
        assertEquals("Invalid dates must not reach the transport", 0, posts.size)
        assertEquals(0, patches.size)
        replace(FINISH, finish)
        val readsBefore = liquidReads.get()
        // The response is gated so both normal pointer taps precede successful completion.
        touch(button("Salva"), taps = 2)
        await("One in-flight save disables the confirm button") {
            posts.size == 1 && compose.onAllNodes(button("Salva")).fetchSemanticsNodes().singleOrNull()
                ?.config?.contains(SemanticsProperties.Disabled) == true
        }
        assertEquals(1, posts.size)
        releasePost.countDown()
        awaitSaved(readsBefore, postCount = 1, patchCount = 0)
        assertEquals(OWNER, posts.single().getString("user_id"))
        assertCompleted(posts.single())
        val stored = rows[OWN_ROW]!!
        assertEquals(OWN_ROW, stored.getString("id"))
        assertCompleted(stored)
        refresh()
        assertCompletedDisplay()
        assertEquals(1, posts.size)
        assertEquals(0, patches.size)
        assertEquals(1, rows.values.count { it.getString("user_id") == OWNER })
        assertScope()
    }

    @Test fun liquidCompletesThenClearsEndAndEveryDerivedFieldOnSamePersistedRecord() {
        rows[OWN_ROW] = openLiquid(OWN_ROW, OWNER).put("fixture_metadata", "preserve-unexposed-value")
        mountLiquid()
        assertOpen(rows[OWN_ROW]!!)
        editForm()
        replace(FINISH, finish)
        var readsBefore = liquidReads.get()
        touch(button("Salva"))
        awaitSaved(readsBefore, postCount = 0, patchCount = 1)
        assertEquals(OWN_ROW, patchedIds.single())
        assertCompleted(patches.single())
        assertCompleted(rows[OWN_ROW]!!)
        refresh()
        assertCompletedDisplay()

        editForm()
        replace(FINISH, "")
        readsBefore = liquidReads.get()
        touch(button("Salva"))
        awaitSaved(readsBefore, postCount = 0, patchCount = 2)
        assertEquals(listOf(OWN_ROW, OWN_ROW), patchedIds.toList())
        assertOpen(patches.last())
        val stored = rows[OWN_ROW]!!
        assertOpen(stored)
        assertEquals(OWN_ROW, stored.getString("id"))
        assertEquals(OWNER, stored.getString("user_id"))
        assertEquals("preserve-unexposed-value", stored.getString("fixture_metadata"))
        patches.forEach { body ->
            assertFalse("The form must not overwrite unexposed metadata", body.has("fixture_metadata"))
            assertFalse("An edit must retain the backend ID", body.has("id"))
            assertFalse("An edit must retain the owner", body.has("user_id"))
        }
        refresh()
        list().performScrollToIndex(2)
        compose.onNodeWithText("$ARRIVAL: $arrival").assertIsDisplayed()
        compose.onAllNodesWithText("Data fine (AAAA-MM-GG): $finish").assertCountEquals(0)
        compose.onAllNodes(hasText("10 giorni ·", substring = true)).assertCountEquals(0)
        assertEquals(0, posts.size)
        assertEquals(2, patches.size)
        assertEquals(1, rows.values.count { it.getString("user_id") == OWNER })
        assertScope()
    }

    private fun mountLiquid() {
        compose.setContent {
            val localFocus = LocalFocusManager.current
            val localKeyboard = LocalSoftwareKeyboardController.current
            SideEffect { focus = localFocus; keyboard = localKeyboard }
            SavelyTheme("light") { ModuleScreen(api, "fumo", Modifier.fillMaxSize()) }
        }
        touch(button("Liquido"))
        await("Owner liquid collection loaded") { liquidReads.get() > 0 && enabled(button("Aggiungi")) }
    }
    private fun addForm() {
        clearInput()
        list().performScrollToIndex(0)
        touch(button("Aggiungi"))
        compose.onNodeWithText(COST).assertIsDisplayed()
    }
    private fun editForm() {
        clearInput()
        list().performScrollToIndex(2)
        // This fixture has exactly one current-year row belonging to the signed-in account.
        compose.onNode(button("Modifica")).performScrollTo()
        touch(button("Modifica"))
        compose.onNodeWithText(COST).assertIsDisplayed()
    }
    private fun awaitSaved(readsBefore: Int, postCount: Int, patchCount: Int) {
        await("Mutation persisted, collection reloaded and dialog closed") {
            posts.size == postCount && patches.size == patchCount && liquidReads.get() >= readsBefore + 2 &&
                compose.onAllNodes(button("Salva")).fetchSemanticsNodes().isEmpty()
        }
        clearInput()
        list().performScrollToIndex(0)
        await("Reloaded collection enables its header") { enabled(button("Aggiungi")) }
    }
    private fun refresh() {
        val before = liquidReads.get()
        clearInput()
        list().performScrollToIndex(0)
        touch(button("Aggiorna"))
        await("Explicit owner GET refresh completed") { liquidReads.get() >= before + 2 && enabled(button("Aggiungi")) }
    }
    private fun assertCompletedDisplay() {
        clearInput()
        list().performScrollToIndex(2)
        compose.onNodeWithText("$ARRIVAL: $arrival").assertIsDisplayed()
        compose.onNodeWithText("Data fine (AAAA-MM-GG): $finish").assertIsDisplayed()
        compose.onNodeWithText("10 giorni · 2,50/giorno · ${euro("1.25")}/giorno · ${euro("37.50")}/mese").assertIsDisplayed()
    }
    private fun assertBase(body: JSONObject) {
        assertNumber(body, "costo", "12.50")
        assertNumber(body, "millilitri", "25")
        assertEquals(arrival, body.getString("data_arrivo"))
        assertFalse("Liquido must use data_arrivo, not data_acquisto", body.has("data_acquisto"))
        assertFalse("Liquido must use millilitri, not grammi", body.has("grammi"))
    }
    private fun assertCompleted(body: JSONObject) {
        assertBase(body)
        assertEquals(finish, body.getString("data_finito"))
        assertNumber(body, "giorni_durata", "10")
        assertNumber(body, "millilitri_al_giorno", "2.5")
        assertNumber(body, "euro_al_giorno", "1.25")
        assertNumber(body, "costo_mensile", "37.5")
    }
    private fun assertOpen(body: JSONObject) {
        assertBase(body)
        listOf("data_finito", "giorni_durata", "millilitri_al_giorno", "euro_al_giorno", "costo_mensile").forEach { key ->
            assertTrue("$key must be present to clear a previously persisted value", body.has(key))
            assertSame("$key must be explicit JSON null", JSONObject.NULL, body.get(key))
        }
    }
    private fun assertScope() {
        assertEquals("No missing/foreign owner filters or writes to foreign rows", 0, foreignScopeRequests.get())
        assertEquals("Only permitted synthetic endpoints and methods", 0, denied.get())
        assertNumber(rows[FOREIGN_ROW]!!, "costo", "999999")
        assertNumber(rows[FOREIGN_ROW]!!, "millilitri", "999999")
        compose.onAllNodesWithText("999999", substring = true).assertCountEquals(0)
    }
    private fun assertNumber(body: JSONObject, key: String, expected: String) {
        assertTrue("$key must use a JSON number", body.get(key) is Number)
        assertEquals("$key must match the audited quantity and units", 0,
            BigDecimal(expected).compareTo(BigDecimal(body.get(key).toString())))
    }
    private fun euro(amount: String) = NumberFormat.getCurrencyInstance(Locale.ITALY).format(BigDecimal(amount))
    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun button(label: String) = hasText(label, substring = false) and hasClickAction()
    private fun enabled(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()?.let {
            !it.config.contains(SemanticsProperties.Disabled)
        } == true
    private fun clearInput() { compose.runOnIdle { focus.clearFocus(force = true); keyboard?.hide() } }
    private fun replace(label: String, value: String) {
        clearInput()
        compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
    }
    private fun touch(matcher: SemanticsMatcher, taps: Int = 1) {
        clearInput()
        var previous: Rect? = null
        var previousViewports: List<Rect>? = null
        var stableFrames = 0
        await("Stable pointer target: $matcher") {
            compose.mainClock.advanceTimeByFrame()
            val node = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
            val bounds = node?.boundsInRoot
            val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().map { it.boundsInRoot }
            val viewports = generateSequence(node?.parent) { it.parent }.filter {
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange) ||
                    it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
            }.map { it.boundsInRoot }.toList()
            val ready = node != null && !node.config.contains(SemanticsProperties.Disabled) && bounds != null &&
                bounds.width > 0 && bounds.height > 0 && roots.any { it.contains(bounds.center) } &&
                viewports.all { it.contains(bounds.center) } && compose.onNode(matcher).isDisplayed()
            stableFrames = if (ready && bounds == previous && viewports == previousViewports) stableFrames + 1 else 0
            previous = bounds
            previousViewports = viewports
            touchDiagnostic = "target=$matcher, bounds=$bounds, viewports=$viewports, stableFrames=$stableFrames"
            stableFrames >= 2
        }
        compose.onNode(matcher).assertIsEnabled().assertIsDisplayed().performTouchInput {
            repeat(taps) { click(); advanceEventTime(40) }
        }
    }
    private fun await(label: String, predicate: () -> Boolean) {
        try { compose.waitUntil(15_000, predicate) }
        catch (failure: ComposeTimeoutException) {
            val trees = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.map { index ->
                runCatching { compose.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToString() }
                    .getOrElse { "Semantics unavailable: ${it.message}" }
            }
            throw AssertionError("Fumo fixture timeout: $label, clock=${compose.mainClock.currentTime}, " +
                "posts=${posts.size}, patches=${patches.size}, liquidReads=${liquidReads.get()}, " +
                "denied=${denied.get()}, foreign=${foreignScopeRequests.get()}\n" +
                "$touchDiagnostic\n${trees.joinToString("\n")}", failure)
        }
    }

    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return reject()
            if (request.getHeader("Authorization") != "Bearer fumo-device-access" ||
                request.getHeader("apikey") != "public-test-key" ||
                url.pathSegments.take(2) != listOf("rest", "v1")) return reject()
            val table = url.pathSegments.last()
            if (request.method == "POST") {
                if (table != "liquido_sigaretta" || url.queryParameterNames.isNotEmpty()) return reject()
                val body = JSONObject(request.body.readUtf8())
                if (body.optString("user_id") != OWNER) { foreignScopeRequests.incrementAndGet(); return reject() }
                posts.add(JSONObject(body.toString()))
                if (posts.size != 1 || rows.containsKey(OWN_ROW))
                    return json(409, JSONObject().put("message", "Synthetic duplicate POST"))
                val persisted = JSONObject(body.toString()).put("id", OWN_ROW).put("created_at", "${arrival}T00:00:00Z")
                rows[OWN_ROW] = persisted
                if (blockPost.get() && !releasePost.await(20, TimeUnit.SECONDS))
                    return json(503, JSONObject().put("message", "Synthetic test response gate expired"))
                return json(201, JSONArray().put(persisted))
            }
            if (request.method == "PATCH") {
                if (table != "liquido_sigaretta" || url.queryParameterNames != setOf("id")) return reject()
                val id = url.queryParameter("id")?.removePrefix("eq.") ?: return reject()
                val existing = rows[id]
                if (id != OWN_ROW || existing?.optString("user_id") != OWNER) {
                    foreignScopeRequests.incrementAndGet()
                    return reject()
                }
                val body = JSONObject(request.body.readUtf8())
                if (body.has("user_id") && body.optString("user_id") != OWNER) {
                    foreignScopeRequests.incrementAndGet()
                    return reject()
                }
                patchedIds.add(id)
                patches.add(JSONObject(body.toString()))
                val persisted = JSONObject(existing.toString())
                body.keys().forEach { key -> persisted.put(key, body.get(key)) }
                rows[id] = persisted
                return json(200, JSONArray().put(persisted))
            }
            if (request.method != "GET" || table !in listOf("cbd", "liquido_sigaretta")) return reject()
            if (url.queryParameter("user_id") != "eq.$OWNER") { foreignScopeRequests.incrementAndGet(); return reject() }
            if (url.queryParameter("limit") != "500" || url.queryParameter("order") != "created_at.desc") return reject()
            val offset = url.queryParameter("offset")?.toIntOrNull()?.takeIf { it >= 0 } ?: return reject()
            if (table == "cbd") return json(200, JSONArray())
            liquidReads.incrementAndGet()
            return json(200, JSONArray(rows.values.filter { it.getString("user_id") == OWNER }
                .sortedByDescending { it.getString("created_at") }.drop(offset).take(500)))
        }
    }
    private fun openLiquid(id: String, owner: String) = JSONObject()
        .put("id", id).put("user_id", owner).put("costo", BigDecimal("12.50")).put("millilitri", 25)
        .put("data_arrivo", arrival).put("data_finito", JSONObject.NULL).put("giorni_durata", JSONObject.NULL)
        .put("millilitri_al_giorno", JSONObject.NULL).put("euro_al_giorno", JSONObject.NULL)
        .put("costo_mensile", JSONObject.NULL).put("created_at", "${arrival}T00:00:00Z")
    private fun json(status: Int, body: Any) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun reject(): MockResponse { denied.incrementAndGet(); return json(403, JSONObject()) }
    private companion object {
        const val OWNER = "34000000-0000-4000-8000-000000000001"
        const val FOREIGN_OWNER = "34000000-0000-4000-8000-000000000002"
        const val OWN_ROW = "34000000-0000-4000-8000-000000000003"
        const val FOREIGN_ROW = "34000000-0000-4000-8000-000000000004"
        const val COST = "Costo (€)"
        const val QUANTITY = "Millilitri"
        const val ARRIVAL = "Data arrivo/acquisto (AAAA-MM-GG)"
        const val FINISH = "Data fine (AAAA-MM-GG) (facoltativo)"
    }
}
