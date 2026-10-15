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
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.visual.CommonVisualArtifacts
import cc.savely.android.visual.CommonVisualContent
import cc.savely.android.visual.CommonVisualProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Pinned FumoCrudPage forms; loopback TLS models uncertain delivery, never deployed RLS. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class FumoQualificationScreenTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(120)
    private val effectDispatcher = StandardTestDispatcher()
    // Keep frame/effect resumptions queued until the UI thread drains the scheduler.
    @get:Rule(order = 1) val compose = createComposeRule(effectContext = effectDispatcher)
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private lateinit var store: MemorySessionStore
    private lateinit var focus: FocusManager
    private var keyboard: SoftwareKeyboardController? = null
    private var profile = CommonVisualProfile.IT_LIGHT
    private var table = "cbd"
    private val originalAccess = fixtureAccess("34110000-0000-4000-8000-000000000101")
    private val rows = ConcurrentHashMap<String, JSONObject>()
    private val posts = CopyOnWriteArrayList<JSONObject>()
    private val patches = CopyOnWriteArrayList<JSONObject>()
    private val reads = AtomicInteger()
    private val successfulEndPages = AtomicInteger()
    private val denied = AtomicInteger()
    private val failReads = AtomicBoolean(false)
    private val gatePatch = AtomicBoolean(false)
    private val releasePatch = CountDownLatch(1)
    private var postFailure = ""
    private val year = LocalDate.now().year
    private val arrival = "$year-06-01"
    private val finish = "$year-06-11"
    private var foreignSnapshot = ""
    private var touchDiagnostic = "No pointer target sampled"
    private var captureDiagnostic = "No capture geometry sampled"
    private var savedEndPages = 0

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("V11 synthetic Fumo fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); dispatcher = fixture(); start() }
        val origin = server.url("/")
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                check(chain.request().url.scheme == origin.scheme && chain.request().url.host == origin.host &&
                    chain.request().url.port == origin.port) { "Fixture refuses external transport" }
                chain.proceed(chain.request())
            }.build()
        store = MemorySessionStore(Session(originalAccess, "v11-refresh", Long.MAX_VALUE, OWNER, "v11@example.invalid"))
        api = SupabaseApi(BackendConfig(origin.toString(), "v11-public-key", origin.toString()), store, client)
        rows[FOREIGN_ROW] = row(FOREIGN_ROW, FOREIGN_OWNER).put("costo", 999999).put("grammi", 999999)
        foreignSnapshot = rows[FOREIGN_ROW]!!.toString()
    }

    @After fun cleanup() {
        releasePatch.countDown()
        api.close()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun cbdItalianPreservesSavedLegacyUntilExplicitEditThenClearsEndAndDerivedFields() =
        legacyEditAndClear("cbd", CommonVisualProfile.IT_LIGHT)

    @Test fun thcEnglishLargePreservesSavedLegacyUntilExplicitEditThenClearsEndAndDerivedFields() =
        legacyEditAndClear("thc", CommonVisualProfile.EN_DARK_LARGE)

    @Test fun committed503CannotReplayAndFailedRefreshCannotUnlockTheForm() =
        uncertainCreate("503", CommonVisualProfile.IT_LIGHT)

    @Test fun lostResponseCannotReplayAndExplicitSuccessfulRefreshRevealsOneRecord() =
        uncertainCreate("lost", CommonVisualProfile.EN_DARK_LARGE)

    @Test fun sameUserReplacementLoginCannotSubmitAnAlreadyOpenFumoDraft() {
        rows[OWN_ROW] = row(OWN_ROW, OWNER)
        mount("cbd", CommonVisualProfile.IT_LIGHT)
        val before = rows[OWN_ROW]!!.toString()
        scroll(button("Modifica")); touch(button("Modifica"))
        replace("Costo (€)", "50")
        val previousRevision = api.sessionOwnerRevision()
        assertNotNull(previousRevision)
        runBlocking {
            store.save(Session(fixtureAccess("34110000-0000-4000-8000-000000000102"), "replacement-refresh", Long.MAX_VALUE, OWNER, "v11@example.invalid"))
            // Observe the external store replacement before invoking the original form callback.
            api.session()
        }
        assertNotEquals("The synthetic login must actually change the observed owner revision", previousRevision, api.sessionOwnerRevision())
        touch(button("Salva"))
        scroll(hasText("Account cambiato. Riapri la schermata.")).assertIsDisplayed()
        assertEquals(0, posts.size)
        assertEquals(0, patches.size)
        assertEquals(before, rows[OWN_ROW]!!.toString())
        assertScope()
    }

    private fun legacyEditAndClear(selected: String, selectedProfile: CommonVisualProfile) {
        rows[OWN_ROW] = row(OWN_ROW, OWNER).put("giorni_durata", 20)
            .put("grammi_al_giorno", 1.25).put("euro_al_giorno", 0.625).put("costo_mensile", 18.75)
            .put("fixture_metadata", "preserve opaque {{text}}")
        val original = rows[OWN_ROW]!!.toString()
        mount(selected, selectedProfile)
        scroll(hasText(detail("20", "1,25", "0,63\u00a0€", "18,75\u00a0€"))).assertIsDisplayed()
        capture("legacy-$selected")
        assertEquals("Reading a finished row must not repair its persisted derivatives", original, rows[OWN_ROW]!!.toString())
        assertEquals(0, patches.size)
        scroll(button(word("Modifica", "Edit"))); touch(button(word("Modifica", "Edit")))
        replace(word("Costo (€)", "Cost (€)"), "25")
        touch(button(word("Salva", "Save")))
        awaitSaved(1)
        assertEquals("An unrelated edit must retain the exact backend end timestamp", "${finish}T00:00:00+00:00",
            patches.single().getString("data_finito"))
        assertEquals("${finish}T00:00:00+00:00", rows[OWN_ROW]!!.getString("data_finito"))
        assertDerived(rows[OWN_ROW]!!, "10", "2.5", "2.5", "75")
        assertEquals("preserve opaque {{text}}", rows[OWN_ROW]!!.getString("fixture_metadata"))
        scroll(button(word("Modifica", "Edit"))); touch(button(word("Modifica", "Edit")))
        replace(arrivalLabel(), "$year-02-30")
        touch(button(word("Salva", "Save")))
        scroll(hasText(word("Formato data non valido", "Invalid date format"))).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText(arrivalLabel())).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        assertEquals(1, patches.size)
        replace(arrivalLabel(), arrival)
        replace(finishLabel(), "$year-05-31")
        touch(button(word("Salva", "Save")))
        scroll(hasText(word("La data di fine precede l'arrivo", "The end date precedes the arrival date"))).assertIsDisplayed()
        assertEquals(1, patches.size)
        replace(finishLabel(), finish)
        replace(word("Costo (€)", "Cost (€)"), "12.50")
        gatePatch.set(true)
        touch(button(word("Salva", "Save")), taps = 2)
        await("Exactly one pending PATCH with disabled Save") {
            patches.size == 2 && compose.onAllNodes(button(word("Salva", "Save"))).fetchSemanticsNodes()
                .singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == true
        }
        releasePatch.countDown()
        awaitSaved(2)
        assertDerived(rows[OWN_ROW]!!, "10", "2.5", "1.25", "37.5")
        scroll(hasText(detail("10", "2,50", "1,25\u00a0€", "37,50\u00a0€"))).assertIsDisplayed()
        assertEquals("preserve opaque {{text}}", rows[OWN_ROW]!!.getString("fixture_metadata"))
        scroll(button(word("Modifica", "Edit"))); touch(button(word("Modifica", "Edit")))
        replace(finishLabel(), "")
        touch(button(word("Salva", "Save")))
        awaitSaved(3)
        val stored = rows[OWN_ROW]!!
        assertEquals(OWN_ROW, stored.getString("id"))
        assertEquals(arrival, stored.getString("data_acquisto"))
        number(stored, "costo", "12.5"); number(stored, "grammi", "25")
        listOf("data_finito", "giorni_durata", "grammi_al_giorno", "euro_al_giorno", "costo_mensile").forEach {
            assertTrue("Clearing $it must send explicit JSON null", patches.last().has(it))
            assertSame(JSONObject.NULL, patches.last().get(it))
            assertSame(JSONObject.NULL, stored.get(it))
        }
        patches.forEach { assertFalse(it.has("id")); assertFalse(it.has("user_id")) }
        scroll(hasText("${arrivalLabel()}: $arrival")).assertIsDisplayed()
        capture("open-$selected")
        assertEquals(0, posts.size)
        assertScope()
    }

    private fun uncertainCreate(failure: String, selectedProfile: CommonVisualProfile) {
        postFailure = failure
        mount("cbd", selectedProfile)
        touch(button(word("Aggiungi", "Add")))
        replace(word("Costo (€)", "Cost (€)"), "1,50")
        replace(word("Grammi", "Grams"), "12")
        replace(arrivalLabel(), arrival)
        failReads.set(true)
        val before = reads.get()
        touch(button(word("Salva", "Save")))
        await("Uncertain save closes its form without issuing a read or replay") {
            posts.size == 1 && compose.onAllNodes(button(word("Salva", "Save"))).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(before, reads.get())
        header()
        compose.onNode(button(word("Aggiungi", "Add"))).assertIsNotEnabled()
        scroll(hasText(blocked())).assertIsDisplayed()
        capture("$failure-blocked")
        header(); touch(button(word("Aggiorna", "Update")))
        await("Explicit failed GET completes while retaining the write block") {
            reads.get() == before + 1 && enabled(button(word("Aggiorna", "Update")))
        }
        compose.onNode(button(word("Aggiungi", "Add"))).assertIsNotEnabled()
        scroll(hasText(blocked())).assertIsDisplayed()
        assertEquals(1, posts.size)
        assertEquals(0, patches.size)
        failReads.set(false)
        header(); touch(button(word("Aggiorna", "Update")))
        await("Successful complete owner GET reveals exactly the committed row") {
            reads.get() >= before + 3 && enabled(button(word("Aggiungi", "Add")))
        }
        compose.onAllNodesWithText(blocked()).assertCountEquals(0)
        scroll(hasText("${arrivalLabel()}: $arrival")).assertIsDisplayed()
        assertEquals(1, rows.values.count { it.getString("user_id") == OWNER })
        val insertedId = posts.single().getString("id")
        assertEquals(insertedId, rows[insertedId]!!.getString("id"))
        number(rows[insertedId]!!, "costo", "1.5")
        number(rows[insertedId]!!, "grammi", "12")
        assertEquals(1, posts.size)
        assertEquals(0, patches.size)
        assertScope()
    }

    private fun mount(selected: String, selectedProfile: CommonVisualProfile) {
        table = selected; profile = selectedProfile
        compose.setContent { CommonVisualContent(profile) {
            val localFocus = LocalFocusManager.current
            val localKeyboard = LocalSoftwareKeyboardController.current
            SideEffect { focus = localFocus; keyboard = localKeyboard }
            ModuleScreen(api, "fumo", Modifier.fillMaxSize())
        } }
        if (selected != "cbd") touch(button("THC"))
        await("Complete personal $selected snapshot loaded") { reads.get() > 0 && enabled(button(word("Aggiungi", "Add"))) }
        savedEndPages = successfulEndPages.get()
    }

    private fun awaitSaved(count: Int) {
        await("PATCH persisted and dialog closed") {
            patches.size == count && compose.onAllNodes(button(word("Salva", "Save"))).fetchSemanticsNodes().isEmpty()
        }
        header()
        await("Fresh complete owner read enables editing") {
            successfulEndPages.get() > savedEndPages && enabled(button(word("Aggiungi", "Add")))
        }
        savedEndPages = successfulEndPages.get()
    }
    private fun header() { clearInput(); compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0) }
    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun scroll(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        clearInput()
        var swipes = 0
        var nextLazyIndex = 0
        await("Visible scroll target: $matcher") {
            val nodes = compose.onAllNodes(matcher).fetchSemanticsNodes()
            check(nodes.size <= 1) { "Ambiguous Fumo scroll target: $matcher" }
            val node = nodes.singleOrNull()
            if (node == null) {
                // Lazy items disappear from semantics. Inspect each item rather than flinging
                // past a short count/summary row; no test depends on its particular index.
                check(nextLazyIndex < 32) { "Fumo target was not composed after $nextLazyIndex items: $matcher" }
                list().performScrollToIndex(nextLazyIndex++)
                settleScroll()
                return@await false
            }
            val scrollable = generateSequence(node.parent) { it.parent }.firstOrNull {
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
            }
            val viewport = scrollable?.boundsInRoot
            val targetY = node.positionInRoot.y + node.size.height / 2f
            if (compose.onNode(matcher).isDisplayed() &&
                (viewport == null || targetY in viewport.top..viewport.bottom)) true
            else {
                check(scrollable != null && swipes < 8) {
                    "Fumo target could not be exposed: $matcher, targetY=$targetY, viewport=$viewport, swipes=$swipes"
                }
                // Unlike performScrollTo's internal loop, a gesture yields between queued effects.
                compose.onNode(SemanticsMatcher("Scrollable ancestor ${scrollable.id}") {
                    it.id == scrollable.id
                }).performTouchInput {
                    if (targetY > scrollable.boundsInRoot.center.y) swipeUp() else swipeDown()
                }
                swipes++
                settleScroll()
                false
            }
        }
        return compose.onNode(matcher).assertIsDisplayed()
    }
    private fun settleScroll() {
        var previous: Float? = null
        var stableFrames = 0
        await("Fumo scroll settled") {
            compose.mainClock.advanceTimeByFrame()
            val current = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            stableFrames = if (current == previous) stableFrames + 1 else 0
            previous = current
            stableFrames >= 2
        }
    }
    private fun replace(label: String, value: String) {
        scroll(hasText(label) and hasSetTextAction()).assertIsDisplayed().performTextReplacement(value)
    }
    private fun clearInput() { compose.runOnIdle { focus.clearFocus(force = true); keyboard?.hide() } }
    private fun button(label: String) = hasText(label, substring = false) and hasClickAction()
    private fun enabled(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
        ?.let { !it.config.contains(SemanticsProperties.Disabled) } == true
    private fun touch(matcher: SemanticsMatcher, taps: Int = 1) {
        clearInput()
        var previous: Rect? = null
        var previousViewports: List<Rect>? = null
        var stableFrames = 0
        await("Stable real pointer target: $matcher") {
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
            previous = bounds; previousViewports = viewports
            touchDiagnostic = "target=$matcher bounds=$bounds viewports=$viewports stable=$stableFrames"
            stableFrames >= 2
        }
        compose.onNode(matcher).assertIsEnabled().assertIsDisplayed().performTouchInput {
            repeat(taps) { click(); advanceEventTime(40) }
        }
    }
    private fun await(label: String, predicate: () -> Boolean) {
        try { compose.waitUntil(15_000) {
            compose.runOnUiThread { effectDispatcher.scheduler.runCurrent() }
            predicate()
        } }
        catch (failure: ComposeTimeoutException) {
            throw AssertionError("V11 $label; reads=${reads.get()} posts=${posts.size} patches=${patches.size} " +
                "denied=${denied.get()}; $touchDiagnostic; $captureDiagnostic", failure)
        }
    }
    private fun capture(state: String): java.io.File {
        clearInput()
        var previous: List<Rect>? = null
        var stableFrames = 0
        await("IME hidden and actual safe drawing geometry stable before capture: $state") {
            compose.mainClock.advanceTimeByFrame()
            val viewport = compose.onNodeWithTag(CommonVisualArtifacts.VIEWPORT).fetchSemanticsNode()
            var current: List<Rect>? = null
            var ready = false
            compose.runOnUiThread {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull()
                val decor = activity?.window?.decorView
                val insets = decor?.let(ViewCompat::getRootWindowInsets)
                if (decor == null || insets == null) {
                    captureDiagnostic = "capture=$state resumed=${activity != null} rootInsets=${insets != null}"
                } else {
                    val location = IntArray(2).also(decor::getLocationInWindow)
                    val window = Rect(location[0].toFloat(), location[1].toFloat(),
                        (location[0] + decor.width).toFloat(), (location[1] + decor.height).toFloat())
                    // Same inset types and one-pixel tolerance as CommonVisualArtifacts.capture.
                    val safeInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
                    val safe = Rect(window.left + safeInsets.left, window.top + safeInsets.top,
                        window.right - safeInsets.right, window.bottom - safeInsets.bottom)
                    val bounds = viewport.boundsInWindow
                    val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                    current = listOf(window, safe, bounds)
                    ready = !imeVisible && !decor.isLayoutRequested && decor.width > 0 && decor.height > 0 &&
                        bounds.width > 0 && bounds.height > 0 && bounds.left.isFinite() && bounds.top.isFinite() &&
                        bounds.right.isFinite() && bounds.bottom.isFinite() &&
                        bounds.left >= safe.left - 1f && bounds.top >= safe.top - 1f &&
                        bounds.right <= safe.right + 1f && bounds.bottom <= safe.bottom + 1f
                    captureDiagnostic = "capture=$state ime=$imeVisible layoutRequested=${decor.isLayoutRequested} " +
                        "window=$window insets=$safeInsets safe=$safe viewport=$bounds size=${viewport.size} ready=$ready"
                }
            }
            stableFrames = if (ready) { if (current == previous) stableFrames + 1 else 1 } else 0
            previous = current
            stableFrames >= 2
        }
        // Keep the shared capture's physical-screen and safeDrawing assertions unchanged.
        return try { CommonVisualArtifacts.capture(compose, "v11-$state-${profile.suffix}", profile) }
        catch (failure: Exception) { throw AssertionError("Fumo capture failed: $captureDiagnostic", failure) }
    }
    private fun word(italian: String, english: String) = if (profile == CommonVisualProfile.IT_LIGHT) italian else english
    private fun arrivalLabel() = word("Data arrivo/acquisto (AAAA-MM-GG)", "Arrival/purchase date (YYYY-MM-DD)")
    private fun finishLabel() = word("Data fine (AAAA-MM-GG) (facoltativo)", "End date (YYYY-MM-DD) (optional)")
    private fun blocked() = word("Le modifiche sono bloccate finché un aggiornamento dei dati non riesce.",
        "Changes are blocked until the data refresh succeeds.")
    private fun detail(days: String, quantity: String, daily: String, monthly: String) = word(
        "$days giorni · $quantity/giorno · $daily/giorno · $monthly/mese",
        "$days days · $quantity/day · $daily/day · $monthly/month")
    private fun assertDerived(row: JSONObject, days: String, quantity: String, daily: String, monthly: String) {
        number(row, "giorni_durata", days); number(row, "grammi_al_giorno", quantity)
        number(row, "euro_al_giorno", daily); number(row, "costo_mensile", monthly)
    }
    private fun number(row: JSONObject, key: String, value: String) {
        assertTrue(row.get(key) is Number)
        assertEquals(key, 0, BigDecimal(value).compareTo(BigDecimal(row.get(key).toString())))
    }
    private fun assertScope() {
        assertEquals(0, denied.get())
        assertEquals(foreignSnapshot, rows[FOREIGN_ROW]!!.toString())
        compose.onAllNodesWithText("999999", substring = true).assertCountEquals(0)
    }

    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return reject()
            if (request.getHeader("Authorization") != "Bearer $originalAccess" || request.getHeader("apikey") != "v11-public-key" ||
                url.pathSegments.take(2) != listOf("rest", "v1")) return reject()
            val requestedTable = url.pathSegments.last()
            if (requestedTable !in listOf("cbd", "thc", "liquido_sigaretta")) return reject()
            if (request.method == "GET") {
                if (url.queryParameterNames != setOf("user_id", "order", "limit", "offset") ||
                    url.queryParameter("user_id") != "eq.$OWNER" ||
                    url.queryParameter("order") != "created_at.desc" || url.queryParameter("limit") != "500") return reject()
                val offset = url.queryParameter("offset")?.toIntOrNull()?.takeIf { it >= 0 } ?: return reject()
                if (requestedTable != table) return json(200, JSONArray())
                reads.incrementAndGet()
                if (failReads.get()) return json(503, JSONObject())
                val page = rows.values.filter { it.getString("user_id") == OWNER }
                    .sortedByDescending { it.getString("created_at") }.drop(offset).take(500)
                if (page.isEmpty()) successfulEndPages.incrementAndGet()
                return json(200, JSONArray(page))
            }
            if (requestedTable != table) return reject()
            if (request.method == "POST" && url.queryParameterNames.isEmpty()) {
                val body = JSONObject(request.body.readUtf8())
                if (body.optString("user_id") != OWNER) return reject()
                posts.add(JSONObject(body.toString()))
                val id = body.getString("id")
                if (rows.putIfAbsent(id, JSONObject(body.toString()).put("created_at", "${arrival}T00:00:00Z")) != null)
                    return json(409, JSONObject())
                return if (postFailure == "lost") MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    else json(503, JSONObject())
            }
            if (request.method == "PATCH" && url.queryParameterNames == setOf("id") &&
                url.queryParameter("id") == "eq.$OWN_ROW" && rows[OWN_ROW]?.getString("user_id") == OWNER) {
                val body = JSONObject(request.body.readUtf8())
                if (body.has("id") || body.has("user_id")) return reject()
                patches.add(JSONObject(body.toString()))
                val updated = JSONObject(rows[OWN_ROW]!!.toString())
                body.keys().forEach { updated.put(it, body.get(it)) }
                rows[OWN_ROW] = updated
                if (gatePatch.get() && !releasePatch.await(20, TimeUnit.SECONDS)) return json(503, JSONObject())
                return json(200, JSONArray().put(updated))
            }
            return reject()
        }
    }
    private fun fixtureAccess(sid: String): String {
        val claims = JSONObject().put("sub", OWNER).put("session_id", sid).toString()
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray(Charsets.UTF_8))
        return "e30.$payload.fixture"
    }
    private fun row(id: String, owner: String) = JSONObject().put("id", id).put("user_id", owner)
        .put("costo", 12.5).put("grammi", 25).put("data_acquisto", arrival).put("data_finito", "${finish}T00:00:00+00:00")
        .put("giorni_durata", JSONObject.NULL).put("grammi_al_giorno", JSONObject.NULL)
        .put("euro_al_giorno", JSONObject.NULL).put("costo_mensile", JSONObject.NULL)
        .put("created_at", "${arrival}T00:00:00Z")
    private fun json(status: Int, body: Any) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun reject(): MockResponse { denied.incrementAndGet(); return json(403, JSONObject()) }
    private companion object {
        const val OWNER = "34110000-0000-4000-8000-000000000001"
        const val FOREIGN_OWNER = "34110000-0000-4000-8000-000000000002"
        const val OWN_ROW = "34110000-0000-4000-8000-000000000003"
        const val FOREIGN_ROW = "34110000-0000-4000-8000-000000000004"
    }
}
