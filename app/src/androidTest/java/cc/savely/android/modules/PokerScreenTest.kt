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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** Audited source: Savely 1cfcf629, PokerHourlyEarnings.tsx addEarning/yearlyData.
 * Real Compose touch and Android TLS, synthetic ownership fixtures; no deployed RLS claim.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PokerScreenTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(90)
    private val effectDispatcher = StandardTestDispatcher()
    // Queue HTTP resumptions for the test clock/UI thread. The default unconfined test
    // dispatcher can resume the test frame from OkHttp while Android is measuring.
    @get:Rule(order = 1) val compose = createComposeRule(effectContext = effectDispatcher)
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private lateinit var focus: FocusManager
    private var keyboard: SoftwareKeyboardController? = null
    private val rows = ConcurrentHashMap<String, JSONObject>()
    private val posts = CopyOnWriteArrayList<JSONObject>()
    private val patches = CopyOnWriteArrayList<JSONObject>()
    private val patchedIds = CopyOnWriteArrayList<String>()
    private val hourlyReads = AtomicInteger()
    private val rakebackReads = AtomicInteger()
    private val denied = AtomicInteger()
    private val foreignScopeRequests = AtomicInteger()
    private val blockPost = AtomicBoolean(false)
    private val releasePost = CountDownLatch(1)
    @Volatile private var fixtureDeal = 0.6
    private var touchDiagnostic = "No pointer target sampled"
    private val month = "${LocalDate.now().year}-05"
    private val firstDay = "$month-01"

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Savely Poker UI fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            // The first test deliberately gates its response for up to 20 seconds.
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
        server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            dispatcher = fixture()
            start()
        }
        val base = server.url("/").toString()
        val session = Session("poker-device-access", "poker-device-refresh",
            System.currentTimeMillis() / 1000 + 3600, OWNER, "poker-ui@example.test")
        api = SupabaseApi(BackendConfig(base, "public-test-key", base), MemorySessionStore(session), client)
        rows[FOREIGN_ROW] = earning(FOREIGN_ROW, FOREIGN_OWNER, "999999", "999999", "999999")
    }

    @After fun cleanup() {
        releasePost.countDown()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun hourlySignedDecimalsConvertMinutesPersistGrossRatesAndApplyDealOnlyToDisplayOnce() {
        blockPost.set(true)
        mountHourly()
        addForm()
        replace(MONTH, month)
        replace(MINUTES, "150")
        replace(NET_WON, "-123,75")
        replace(NET_EV, "246,00")
        // Two real taps before the first HTTP response: disabled UI plus loading guard must prevent duplicates.
        touch(button("Salva"), taps = 2)
        await("One in-flight save disables the confirm button") {
            posts.size == 1 && compose.onAllNodes(button("Salva")).fetchSemanticsNodes().singleOrNull()
                ?.config?.contains(SemanticsProperties.Disabled) == true
        }
        assertEquals(1, posts.size)
        releasePost.countDown()
        awaitSaved()
        val body = posts.single()
        assertEquals(OWNER, body.getString("user_id"))
        assertEquals(firstDay, body.getString("date"))
        assertNumber(body, "hours_played", "2.5")
        assertNumber(body, "profit_loss", "-123.75")
        assertNumber(body, "net_won_ev", "246.00")
        assertNumber(body, "hourly_rate", "-49.5")
        assertNumber(body, "hourly_rate_ev", "98.4")
        val persisted = rows[CREATED_ROW]!!
        listOf("hours_played", "profit_loss", "net_won_ev", "hourly_rate", "hourly_rate_ev").forEach { key ->
            assertNumber(persisted, key, body.get(key).toString())
        }

        // Reload from stored rows, then assert the annual aggregate and per-record net-of-deal display.
        refresh()
        list().performScrollToIndex(1)
        compose.onNode(hasText("2h 30m · Net Won", substring = true)).assertIsDisplayed()
            .assertTextContains("Net Won ${euro("-123.75")} · EV ${euro("246.00")}", substring = true)
            .assertTextContains("Orario dopo deal ${euro("-29.70")} · EV/h ${euro("59.04")}", substring = true)
        list().performScrollToIndex(2)
        compose.onNodeWithText("Mese (AAAA-MM): $month").assertIsDisplayed()
        compose.onNodeWithText("Orario dopo deal ${euro("-29.70")} · EV ${euro("59.04")}").assertIsDisplayed()
        assertEquals(1, posts.size)
        assertScope()
    }

    @Test fun hourlyRejectsInvalidDecimalAndNegativeMinutesThenPersistsZeroRatesAndRefusesDuplicateMonth() {
        mountHourly()
        addForm()
        replace(MONTH, month)
        replace(MINUTES, "60")
        replace(NET_WON, "1,2,3")
        touch(button("Salva"))
        scrollVisible(hasText("Inserisci un numero valido")).assertIsDisplayed()
        assertEquals(0, posts.size)
        replace(NET_WON, "-12,50")
        replace(NET_EV, "3,75")
        replace(MINUTES, "-1")
        touch(button("Salva"))
        scrollVisible(hasText("Tempo giocato (minuti): valore negativo")).assertIsDisplayed()
        assertEquals(0, posts.size)
        replace(MINUTES, "0")
        touch(button("Salva"))
        awaitSaved()
        val body = posts.single()
        assertNumber(body, "hours_played", "0")
        assertNumber(body, "hourly_rate", "0")
        assertNumber(body, "hourly_rate_ev", "0")
        assertNumber(body, "profit_loss", "-12.50")
        assertNumber(body, "net_won_ev", "3.75")
        assertNumber(rows[CREATED_ROW]!!, "hourly_rate", "0")
        assertNumber(rows[CREATED_ROW]!!, "hourly_rate_ev", "0")
        refresh()
        list().performScrollToIndex(1)
        compose.onNode(hasText("0h 00m · Net Won", substring = true)).assertIsDisplayed()
            .assertTextContains("Orario dopo deal ${euro("0")} · EV/h ${euro("0")}", substring = true)

        addForm()
        replace(MONTH, month)
        replace(MINUTES, "60")
        replace(NET_WON, "10,25")
        touch(button("Salva"))
        scrollVisible(hasText("Esiste già un record per questo mese")).assertIsDisplayed()
        assertEquals("A known duplicate month must not issue another POST", 1, posts.size)
        touch(button("Annulla"))
        assertEquals(1, rows.values.count { it.getString("user_id") == OWNER })
        assertScope()
    }

    @Test fun hourlyExactQuarterTieDisplaysLiteralHalfUpRateWithoutChangingStoredGrossValues() {
        fixtureDeal = 0.5
        mountHourly()
        addForm()
        replace(MONTH, month)
        replace(MINUTES, "60")
        replace(NET_WON, "0,25")
        replace(NET_EV, "-0,25")
        touch(button("Salva"))
        awaitSaved()
        val body = posts.single()
        assertEquals(OWNER, body.getString("user_id"))
        assertEquals(firstDay, body.getString("date"))
        assertNumber(body, "hours_played", "1")
        assertNumber(body, "hourly_rate", "0.25")
        assertNumber(body, "hourly_rate_ev", "-0.25")
        assertNumber(rows[CREATED_ROW]!!, "profit_loss", "0.25")
        assertNumber(rows[CREATED_ROW]!!, "net_won_ev", "-0.25")
        refresh()
        list().performScrollToIndex(1)
        // Literal expectations, independently captured from the web's toFixed(2), not generated by a formatter.
        compose.onNode(hasText("1h 00m · Net Won", substring = true)).assertIsDisplayed()
            .assertTextContains("Orario dopo deal 0,13", substring = true)
            .assertTextContains("EV/h -0,13", substring = true)
        list().performScrollToIndex(2)
        compose.onNode(hasText("Orario dopo deal 0,13", substring = true) and
            hasText(" · EV -0,13", substring = true)).assertIsDisplayed()
        assertEquals(1, posts.size)
        assertScope()
    }

    @Test fun hourlyEditKeepsMonthReadOnlyAndPatchPreservesDateOwnerIdAndOtherMonth() {
        val otherId = "33000000-0000-4000-8000-000000000006"
        val otherMonth = "${LocalDate.now().year}-04"
        rows[CREATED_ROW] = earning(CREATED_ROW, OWNER, "1", "20", "30").put("fixture_metadata", "preserved")
        rows[otherId] = earning(otherId, OWNER, "3", "40", "50").put("date", "$otherMonth-01")
        val otherBefore = rows[otherId]!!.toString()
        mountHourly()
        list().performScrollToIndex(2)
        val editCurrent = button("Modifica") and hasAnyAncestor(
            hasAnyDescendant(hasText("$MONTH: $month")) and !hasAnyDescendant(hasText("$MONTH: $otherMonth")))
        scrollVisible(editCurrent); touch(editCurrent)
        compose.onNode(hasText(MONTH) and hasText(month)).assertIsDisplayed().assertIsEnabled()
            .assert(!hasSetTextAction())
        replace(MINUTES, "120")
        replace(NET_WON, "30,00")
        replace(NET_EV, "-10,00")
        val readsBefore = hourlyReads.get()
        touch(button("Salva"))
        await("Edited record reloaded and dialog closed") {
            patches.size == 1 && hourlyReads.get() >= readsBefore + 2 &&
                compose.onAllNodes(button("Salva")).fetchSemanticsNodes().isEmpty()
        }
        clearInput(); list().performScrollToIndex(0)
        scrollVisible(button("Aggiungi"))
        await("Reloaded record and deal enable the visible header") { enabled(button("Aggiungi")) }
        compose.onNode(button("Aggiungi")).assertIsDisplayed().assertIsEnabled()
        val body = patches.single()
        assertEquals(listOf(CREATED_ROW), patchedIds.toList())
        assertEquals(setOf("hours_played", "profit_loss", "net_won_ev", "hourly_rate", "hourly_rate_ev"), body.keys().asSequence().toSet())
        assertFalse(body.has("date")); assertFalse(body.has("id")); assertFalse(body.has("user_id"))
        assertNumber(body, "hours_played", "2"); assertNumber(body, "profit_loss", "30")
        assertNumber(body, "net_won_ev", "-10"); assertNumber(body, "hourly_rate", "15"); assertNumber(body, "hourly_rate_ev", "-5")
        val persisted = rows[CREATED_ROW]!!
        assertEquals(firstDay, persisted.getString("date")); assertEquals(OWNER, persisted.getString("user_id"))
        assertEquals(CREATED_ROW, persisted.getString("id")); assertEquals("preserved", persisted.getString("fixture_metadata"))
        assertEquals(otherBefore, rows[otherId]!!.toString())
        clearInput(); list().performScrollToIndex(2)
        scrollVisible(hasText("$MONTH: $month")).assertIsDisplayed()
        scrollVisible(hasText("Orario dopo deal ${euro("9")} · EV ${euro("-3")}")).assertIsDisplayed()
        assertEquals(0, posts.size); assertEquals(1, patches.size)
        assertScope()
    }

    @Test fun hourlyMonthlyDetailsExcludePreviousYearWhileBothAnnualSummariesRemain() {
        val previousYear = LocalDate.now().year - 1
        val previousId = "33000000-0000-4000-8000-000000000006"
        rows[CREATED_ROW] = earning(CREATED_ROW, OWNER, "1", "20", "30")
        rows[previousId] = earning(previousId, OWNER, "2", "50", "60")
            .put("date", "$previousYear-05-01")
        val before = rows.mapValues { it.value.toString() }
        mountHourly()
        refresh()
        list().performScrollToIndex(1)
        compose.onNodeWithText(LocalDate.now().year.toString()).assertIsDisplayed()
        compose.onNode(hasText("1h 00m · Net Won", substring = true)).assertIsDisplayed()
            .assertTextContains("Net Won ${euro("20")}", substring = true)
            .assertTextContains("Orario dopo deal ${euro("12")}", substring = true)
        list().performScrollToIndex(2)
        compose.onNodeWithText(previousYear.toString()).assertIsDisplayed()
        compose.onNode(hasText("2h 00m · Net Won", substring = true)).assertIsDisplayed()
            .assertTextContains("Net Won ${euro("50")}", substring = true)
            .assertTextContains("Orario dopo deal ${euro("15")}", substring = true)
        list().performScrollToIndex(4)
        compose.onNodeWithText("$MONTH: $month").assertIsDisplayed()
        scrollVisible(button("Modifica")).assertIsDisplayed()
        compose.onAllNodes(button("Modifica")).assertCountEquals(1)
        compose.onNodeWithText("$MONTH: $previousYear-05").assertDoesNotExist()
        compose.onNodeWithText("Tempo giocato (minuti): 120.0").assertDoesNotExist()
        assertEquals(before, rows.mapValues { it.value.toString() })
        assertEquals(0, posts.size)
        assertScope()
    }

    @Test fun rakebackMonthlyDetailsExcludePreviousYearWithoutLosingYearlyTotals() {
        val previousYear = LocalDate.now().year - 1
        val previousId = "33000000-0000-4000-8000-000000000006"
        rows[CREATED_ROW] = JSONObject().put("id", CREATED_ROW).put("user_id", OWNER).put("date", firstDay)
            .put("rake_generated", 100).put("rakeback_received", 25).put("created_at", firstDay)
        rows[previousId] = JSONObject().put("id", previousId).put("user_id", OWNER).put("date", "$previousYear-05-01")
            .put("rake_generated", 200).put("rakeback_received", 60).put("created_at", "$previousYear-05-01")
        val before = rows.mapValues { it.value.toString() }
        mountPoker()
        touch(button("Rakeback"))
        await("Owner rakeback loaded") { rakebackReads.get() >= 2 && enabled(button("Aggiungi")) }
        val beforeRefresh = rakebackReads.get()
        touch(button("Aggiorna"))
        await("Explicit rakeback owner GET completed") { rakebackReads.get() >= beforeRefresh + 2 && enabled(button("Aggiungi")) }
        list().performScrollToIndex(1)
        compose.onNodeWithText(LocalDate.now().year.toString()).assertIsDisplayed()
        compose.onNodeWithText("Rake ${euro("100")} · ricevuto ${euro("25")} · 25,0%").assertIsDisplayed()
        list().performScrollToIndex(2)
        compose.onNodeWithText(previousYear.toString()).assertIsDisplayed()
        compose.onNodeWithText("Rake ${euro("200")} · ricevuto ${euro("60")} · 30,0%").assertIsDisplayed()
        list().performScrollToIndex(4)
        compose.onNodeWithText("$MONTH: $month").assertIsDisplayed()
        scrollVisible(button("Modifica")).assertIsDisplayed()
        compose.onAllNodes(button("Modifica")).assertCountEquals(1)
        compose.onNodeWithText("$MONTH: $previousYear-05").assertDoesNotExist()
        compose.onNodeWithText("Rake generato (€): 200").assertDoesNotExist()
        assertEquals(before, rows.mapValues { it.value.toString() })
        assertEquals(0, posts.size)
        assertScope()
    }

    private fun mountPoker() {
        compose.setContent {
            val localFocus = LocalFocusManager.current
            val localKeyboard = LocalSoftwareKeyboardController.current
            SideEffect { focus = localFocus; keyboard = localKeyboard }
            SavelyTheme("light") { ModuleScreen(api, "poker", Modifier.fillMaxSize()) }
        }
    }
    private fun mountHourly() {
        mountPoker()
        touch(button("Guadagno orario"))
        await("Owner hourly collection loaded") { hourlyReads.get() > 0 && enabled(button("Aggiungi")) }
        assertEquals(0, posts.size)
    }
    private fun addForm() {
        clearInput()
        list().performScrollToIndex(0)
        touch(button("Aggiungi"))
        compose.onNodeWithText(MONTH).assertIsDisplayed()
    }
    private fun awaitSaved() {
        await("Saved record read back and dialog closed") {
            posts.size == 1 && hourlyReads.get() >= 3 && enabled(button("Aggiungi")) &&
                compose.onAllNodes(button("Salva")).fetchSemanticsNodes().isEmpty()
        }
    }
    private fun refresh() {
        val before = hourlyReads.get()
        clearInput()
        list().performScrollToIndex(0)
        touch(button("Aggiorna"))
        await("Explicit GET refresh completed") { hourlyReads.get() >= before + 2 && enabled(button("Aggiungi")) }
    }
    private fun assertScope() {
        assertEquals("No missing/foreign owner filters or foreign-owner POSTs", 0, foreignScopeRequests.get())
        assertEquals("Only permitted synthetic endpoints and methods", 0, denied.get())
        assertNumber(rows[FOREIGN_ROW]!!, "profit_loss", "999999")
        compose.onAllNodesWithText("999999", substring = true).assertCountEquals(0)
    }
    private fun assertNumber(body: JSONObject, key: String, expected: String) {
        assertTrue("$key must use a JSON number", body.get(key) is Number)
        assertEquals("$key must match audited units and sign", 0,
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
        scrollVisible(hasText(label)).performTextReplacement(value)
    }
    private fun scrollVisible(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        var swipes = 0
        await("Visible scroll target: $matcher") {
            val node = compose.onNode(matcher).fetchSemanticsNode()
            val scrollable = generateSequence(node.parent) { it.parent }.firstOrNull {
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
            }
            val viewport = scrollable?.boundsInRoot
            val targetY = node.positionInRoot.y + node.size.height / 2f
            if (compose.onNode(matcher).isDisplayed() &&
                (viewport == null || targetY in viewport.top..viewport.bottom)) true
            else {
                check(scrollable != null && swipes < 8) {
                    "Poker target could not be exposed: $matcher, targetY=$targetY, viewport=$viewport, swipes=$swipes"
                }
                // performScrollTo repeatedly calls ScrollBy before queued effects are drained.
                // A real gesture lets the UI/test clock complete each scroll before the next check.
                compose.onNode(SemanticsMatcher("Scrollable ancestor ${scrollable.id}") {
                    it.id == scrollable.id
                }).performTouchInput {
                    if (targetY > scrollable.boundsInRoot.center.y) swipeUp() else swipeDown()
                }
                swipes++
                false
            }
        }
        return compose.onNode(matcher).assertIsDisplayed()
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
        try {
            compose.waitUntil(15_000) {
                // A queued launch/HTTP response can precede the first observable snapshot update.
                compose.runOnUiThread { effectDispatcher.scheduler.runCurrent() }
                predicate()
            }
        }
        catch (failure: ComposeTimeoutException) {
            val trees = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.map { index ->
                runCatching { compose.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToString() }
                    .getOrElse { "Semantics unavailable: ${it.message}" }
            }
            throw AssertionError("Poker fixture timeout: $label, clock=${compose.mainClock.currentTime}, " +
                "posts=${posts.size}, patches=${patches.size}, hourlyReads=${hourlyReads.get()}, denied=${denied.get()}, foreign=${foreignScopeRequests.get()}\n" +
                "$touchDiagnostic\n${trees.joinToString("\n")}", failure)
        }
    }

    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return reject()
            if (request.getHeader("Authorization") != "Bearer poker-device-access" ||
                request.getHeader("apikey") != "public-test-key") return reject()
            val table = url.pathSegments.last()
            if (request.method == "POST") {
                if (table != "poker_hourly_earnings") return reject()
                val body = JSONObject(request.body.readUtf8())
                if (body.optString("user_id") != OWNER) { foreignScopeRequests.incrementAndGet(); return reject() }
                posts.add(JSONObject(body.toString()))
                if (posts.size != 1) return json(409, JSONObject().put("message", "Synthetic duplicate POST"))
                val persisted = JSONObject(body.toString()).put("id", CREATED_ROW).put("created_at", "2026-05-01T00:00:00Z")
                rows[CREATED_ROW] = persisted
                if (blockPost.get() && !releasePost.await(20, TimeUnit.SECONDS))
                    return json(503, JSONObject().put("message", "Synthetic test response gate expired"))
                return json(201, JSONArray().put(persisted))
            }
            if (request.method == "PATCH") {
                if (table != "poker_hourly_earnings" || url.queryParameterNames != setOf("id")) return reject()
                val id = url.queryParameter("id")?.removePrefix("eq.") ?: return reject()
                val existing = rows[id]
                if (id != CREATED_ROW || existing?.optString("user_id") != OWNER) {
                    foreignScopeRequests.incrementAndGet(); return reject()
                }
                val body = JSONObject(request.body.readUtf8())
                if (body.keys().asSequence().toSet() != setOf("hours_played", "profit_loss", "net_won_ev", "hourly_rate", "hourly_rate_ev")) return reject()
                if (patches.isNotEmpty()) return reject()
                patches.add(JSONObject(body.toString())); patchedIds.add(id)
                val persisted = JSONObject(existing.toString())
                body.keys().forEach { key -> persisted.put(key, body.get(key)) }
                rows[id] = persisted
                return json(200, JSONArray().put(persisted))
            }
            if (request.method != "GET") return reject()
            if (url.queryParameter("user_id") != "eq.$OWNER") { foreignScopeRequests.incrementAndGet(); return reject() }
            return when (table) {
                "poker_hourly_earnings" -> {
                    if (url.queryParameter("limit") != "500" || url.queryParameter("order") != "created_at.desc") return reject()
                    val offset = url.queryParameter("offset")?.toIntOrNull()?.takeIf { it >= 0 } ?: return reject()
                    hourlyReads.incrementAndGet()
                    json(200, JSONArray(rows.values.filter { it.getString("user_id") == OWNER }
                        .sortedByDescending { it.getString("date") }.drop(offset).take(500)))
                }
                "poker_rakeback" -> {
                    if (url.queryParameter("limit") != "500" || url.queryParameter("order") != "created_at.desc") return reject()
                    val offset = url.queryParameter("offset")?.toIntOrNull()?.takeIf { it >= 0 } ?: return reject()
                    rakebackReads.incrementAndGet()
                    json(200, JSONArray(rows.values.filter { it.getString("user_id") == OWNER }
                        .sortedByDescending { it.getString("date") }.drop(offset).take(500)))
                }
                "poker_next_cut" -> {
                    if (url.queryParameter("limit") != "1" || url.queryParameter("order") != "created_at.asc") return reject()
                    json(200, JSONArray().put(JSONObject().put("id", DEAL_ROW).put("user_id", OWNER)
                        .put("deal", fixtureDeal).put("amount", 0).put("profit_loss", 0).put("name", "Deal sintetico")))
                }
                "transactions", "poker_manual_expenses" -> {
                    if (url.queryParameter("limit") != "500" || url.queryParameter("order") != "id.asc" ||
                        url.queryParameter("offset") != "0") return reject()
                    json(200, JSONArray())
                }
                else -> reject()
            }
        }
    }
    private fun earning(id: String, owner: String, hours: String, won: String, ev: String) = JSONObject()
        .put("id", id).put("user_id", owner).put("date", firstDay).put("hours_played", BigDecimal(hours))
        .put("profit_loss", BigDecimal(won)).put("net_won_ev", BigDecimal(ev))
        .put("hourly_rate", 1).put("hourly_rate_ev", 1).put("created_at", "2026-05-01T00:00:00Z")
    private fun json(status: Int, body: Any) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun reject(): MockResponse { denied.incrementAndGet(); return json(403, JSONObject()) }
    private companion object {
        const val OWNER = "33000000-0000-4000-8000-000000000001"
        const val FOREIGN_OWNER = "33000000-0000-4000-8000-000000000002"
        const val CREATED_ROW = "33000000-0000-4000-8000-000000000003"
        const val FOREIGN_ROW = "33000000-0000-4000-8000-000000000004"
        const val DEAL_ROW = "33000000-0000-4000-8000-000000000005"
        const val MONTH = "Mese (AAAA-MM)"
        const val MINUTES = "Tempo giocato (minuti)"
        const val NET_WON = "Net Won (€)"
        const val NET_EV = "Net Won EV (€)"
    }
}
