package cc.savely.android.modules

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.visual.CommonVisualArtifacts
import cc.savely.android.visual.CommonVisualContent
import cc.savely.android.visual.CommonVisualProfile
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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

/** Synthetic loopback TLS through the real API and ModuleScreen; no deployed DB/RLS qualification. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PokerQualificationTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(120)
    private val effectDispatcher = StandardTestDispatcher()
    @get:Rule(order = 1) val compose = createComposeRule(effectContext = effectDispatcher)
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private lateinit var peer: SupabaseApi
    private lateinit var primaryStore: MemorySessionStore
    private val tokens = ConcurrentHashMap.newKeySet<String>()
    private val today = LocalDate.now()
    private val tables = ConcurrentHashMap<String, ConcurrentHashMap<String, JSONObject>>()
    private val mutations = CopyOnWriteArrayList<Mutation>()
    private val denied = AtomicInteger()
    private val nextPostStatus = AtomicInteger(201)
    private val nextReadFailures = AtomicInteger()
    private var hideInput: () -> Unit = {}
    @Volatile private var duplicateGate: CountDownLatch? = null
    private data class Mutation(val method: String, val table: String, val id: String, val body: JSONObject)

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Synthetic Poker qualification")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); dispatcher = fixture(); start() }
        val origin = server.url("/")
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val target = chain.request().url
                if (target.scheme != origin.scheme || target.host != origin.host || target.port != origin.port) {
                    denied.incrementAndGet(); throw IOException("Poker fixture refuses foreign transport")
                }
                chain.proceed(chain.request())
            }.build()
        val config = BackendConfig(origin.toString(), "poker-fixture-public", origin.toString())
        val primaryToken = token(SID, "initial"); val peerToken = token(PEER_SID, "peer")
        tokens.add(primaryToken); tokens.add(peerToken)
        primaryStore = MemorySessionStore(session(primaryToken))
        api = SupabaseApi(config, primaryStore, client)
        peer = SupabaseApi(config, MemorySessionStore(session(peerToken)), client)
    }

    @After fun cleanup() {
        duplicateGate?.let { while (it.count > 0) it.countDown() }
        api.close(); peer.close(); server.shutdown()
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }

    @Test fun nextCutUsesGlobalPopulatedMonthMedianManualCostsAndLiteralTargetsInBothProfiles() {
        profiles(prepare = { seed(nextCut = true) }) { en, profile ->
            ready(if (en) "Save Next Cut" else "Salva Next Cut")
            metric(if (en) "Monthly expenses (median + manual expenses)" else "Spesa mensile (mediana + spese manuali)", "275,00\u00a0€")
            metric(if (en) "Amount needed for the next cut" else "Quanto manca al prossimo cut", "900,00\u00a0€")
            capture("next-cut-median", profile, hasText("900,00\u00a0€"))
            input("Deal (0–1)", "0")
            click(button(if (en) "Save Next Cut" else "Salva Next Cut"))
            show(hasText(if (en) "Deal must be greater than 0 and at most 1" else "Il deal deve essere maggiore di 0 e massimo 1"))
            assertTrue(mutations.isEmpty())
            input("Deal (0–1)", "1e-320")
            click(button(if (en) "Save Next Cut" else "Salva Next Cut"))
            show(hasText(if (en) "Enter a valid number" else "Inserisci un numero valido"))
            assertTrue("Overflow must be rejected before HTTP", mutations.isEmpty())
            input("Deal (0–1)", "0,5")
            input(if (en) "Current profit / loss (€)" else "Profit / Loss attuale (€)", "600")
            click(button(if (en) "Save Next Cut" else "Salva Next Cut"))
            await("One PATCH") { mutations.size == 1 }
            ready(if (en) "Save Next Cut" else "Salva Next Cut")
            val mutation = mutations.single()
            assertEquals("PATCH", mutation.method); assertEquals("poker_next_cut", mutation.table); assertEquals(NEXT, mutation.id)
            assertEquals(setOf("amount", "deal", "profit_loss"), mutation.body.keys().asSequence().toSet())
            number(mutation.body, "amount", "275"); number(mutation.body, "deal", "0.5"); number(mutation.body, "profit_loss", "600")
            metric(if (en) "Amount needed for the next cut" else "Quanto manca al prossimo cut", "-50,00\u00a0€")
            assertEquals(0, denied.get())
        }
    }

    @Test fun committedNextCutInsert503RequiresSuccessfulReadBeforeAnyFurtherSaveAndNeverReplaysPost() {
        profiles(prepare = { seed(nextCut = false); nextPostStatus.set(503) }) { en, profile ->
            val save = button(if (en) "Save Next Cut" else "Salva Next Cut")
            ready(if (en) "Save Next Cut" else "Salva Next Cut")
            click(save)
            val pending = if (en) "The save result is uncertain. Refresh the data before trying again."
                else "Esito del salvataggio incerto. Aggiorna i dati prima di riprovare."
            show(hasText(pending))
            show(save); compose.onNode(save).assertIsNotEnabled()
            assertEquals(1, mutations.size); assertEquals("POST", mutations.single().method)
            val inserted = mutations.single().id
            assertEquals(OWNER, rows("poker_next_cut").getValue(inserted).getString("user_id"))
            nextReadFailures.set(1)
            click(button(if (en) "Update" else "Aggiorna"))
            await("Failed refresh has completed") { enabled(button(if (en) "Update" else "Aggiorna")) }
            show(save); compose.onNode(save).assertIsNotEnabled()
            show(hasText(pending))
            assertEquals(1, mutations.size)
            capture("next-cut-pending-refresh", profile, hasText(pending))
            click(button(if (en) "Update" else "Aggiorna"))
            ready(if (en) "Save Next Cut" else "Salva Next Cut")
            compose.onAllNodesWithText(pending).assertCountEquals(0)
            input(if (en) "Current profit / loss (€)" else "Profit / Loss attuale (€)", "1")
            click(save)
            await("Explicit save updates the reconciled UUID") { mutations.size == 2 }
            ready(if (en) "Save Next Cut" else "Salva Next Cut")
            assertEquals(listOf("POST", "PATCH"), mutations.map { it.method })
            assertEquals(listOf(inserted, inserted), mutations.map { it.id })
            assertEquals(1, rows("poker_next_cut").size)
            assertEquals(0, denied.get())
        }
    }

    @Test fun manualAndRakebackCrudPreserveImmutableMonthAndWeightedYearSummariesInBothProfiles() {
        profiles(prepare = { seed(nextCut = true); rows("poker_manual_expenses").clear() }) { en, profile ->
            ready(if (en) "Save Next Cut" else "Salva Next Cut")
            click(button(if (en) "Manual expenses" else "Spese manuali"))
            ready(if (en) "Add" else "Aggiungi")
            add(en)
            input(if (en) "Name" else "Nome", "QA Manual {{currency}}")
            input(if (en) "Monthly amount (€)" else "Importo mensile (€)", "-1")
            dialogSave(en)
            compose.onNodeWithText(if (en) "Monthly amount (€): negative value" else "Importo mensile (€): valore negativo").performScrollTo().assertIsDisplayed()
            assertTrue(mutations.isEmpty())
            input(if (en) "Monthly amount (€)" else "Importo mensile (€)", "25")
            dialogSave(en)
            awaitClosed(en)
            val manualId = mutations.single().id
            assertEquals("poker_manual_expenses", mutations.single().table)
            rowAction(en, "QA Manual {{currency}}", edit = true)
            input(if (en) "Monthly amount (€)" else "Importo mensile (€)", "30")
            dialogSave(en); awaitClosed(en)
            number(rows("poker_manual_expenses").getValue(manualId), "amount", "30")
            rowAction(en, "QA Manual {{currency}}", edit = false)
            compose.onNodeWithText(if (en) "Cancel" else "Annulla").performClick()
            assertEquals(2, mutations.size)
            rowAction(en, "QA Manual {{currency}}", edit = false)
            compose.onNode(button(if (en) "Delete" else "Elimina") and hasAnyAncestor(isDialog())).performClick()
            await("Manual deletion completed") { !rows("poker_manual_expenses").containsKey(manualId) && enabled(button(if (en) "Add" else "Aggiungi")) }
            click(button("Rakeback")); ready(if (en) "Add" else "Aggiungi")
            add(en)
            val month = "${today.year}-05"
            input(if (en) "Month (YYYY-MM)" else "Mese (AAAA-MM)", month)
            input(if (en) "Generated rake (€)" else "Rake generato (€)", "100")
            input(if (en) "Rakeback received (€)" else "Rakeback ricevuto (€)", "25")
            dialogSave(en); awaitClosed(en)
            val rakeId = mutations.last().id
            val originalDate = "$month-01"
            assertEquals(originalDate, rows("poker_rakeback").getValue(rakeId).getString("date"))
            val currentSummary = if (en) "Rake 100,00\u00a0€ · received 25,00\u00a0€ · 25,0%" else "Rake 100,00\u00a0€ · ricevuto 25,00\u00a0€ · 25,0%"
            show(hasText(currentSummary))
            val priorSummary = if (en) "Rake 400,00\u00a0€ · received 100,00\u00a0€ · 25,0%" else "Rake 400,00\u00a0€ · ricevuto 100,00\u00a0€ · 25,0%"
            show(hasText(priorSummary))
            capture("rakeback-two-years", profile, hasText(priorSummary))
            rowAction(en, originalDate, edit = true, month = month)
            compose.onNode(hasText(if (en) "Month (YYYY-MM)" else "Mese (AAAA-MM)") and hasText(month)).assert(!hasSetTextAction())
            input(if (en) "Rakeback received (€)" else "Rakeback ricevuto (€)", "30")
            dialogSave(en); awaitClosed(en)
            val patch = mutations.last()
            assertEquals("PATCH", patch.method); assertEquals(rakeId, patch.id)
            assertEquals(setOf("rake_generated", "rakeback_received"), patch.body.keys().asSequence().toSet())
            assertEquals(originalDate, rows("poker_rakeback").getValue(rakeId).getString("date"))
            assertEquals(OWNER, rows("poker_rakeback").getValue(rakeId).getString("user_id"))
            rowAction(en, originalDate, edit = false, month = month)
            compose.onNode(button(if (en) "Delete" else "Elimina") and hasAnyAncestor(isDialog())).performClick()
            await("Current month deleted but prior year preserved") { !rows("poker_rakeback").containsKey(rakeId) }
            ready(if (en) "Add" else "Aggiungi")
            assertEquals(2, rows("poker_rakeback").size)
            assertEquals(listOf("POST", "PATCH", "DELETE", "POST", "PATCH", "DELETE"), mutations.map { it.method })
            click(button(if (en) "Hourly earnings" else "Guadagno orario")); ready(if (en) "Add" else "Aggiungi")
            add(en)
            input(if (en) "Month (YYYY-MM)" else "Mese (AAAA-MM)", month)
            input(if (en) "Time played (minutes)" else "Tempo giocato (minuti)", "60")
            input("Net Won (€)", "0,25")
            input("Net Won EV (€)", "-0,25")
            dialogSave(en); awaitClosed(en)
            val quarter = mutations.last()
            assertEquals("poker_hourly_earnings", quarter.table)
            number(quarter.body, "hours_played", "1"); number(quarter.body, "hourly_rate", "0.25")
            number(quarter.body, "hourly_rate_ev", "-0.25")
            val quarterText = if (en) "Hourly after deal 0,13\u00a0€ · EV/h -0,13\u00a0€" else "Orario dopo deal 0,13\u00a0€ · EV/h -0,13\u00a0€"
            val quarterSummary = hasText(quarterText, substring = true)
            showFragment(quarterSummary, quarterText)
            CommonVisualArtifacts.capture(compose, "v10-quarter-ties-${profile.suffix}", profile, clockInjected = false)
            assertEquals(7, mutations.size)
            assertEquals(0, denied.get())
        }
    }

    @Test fun twoIndependentClientPrechecksCanBothInsertTheSameMonthWithoutAServerUniqueConstraint() = runBlocking {
        seed(nextCut = true); rows("poker_hourly_earnings").clear()
        duplicateGate = CountDownLatch(2)
        val day = "${today.year}-05-01"
        val inserted = listOf(api, peer).mapIndexed { index, actor -> async(Dispatchers.IO) {
            val found = actor.select("poker_hourly_earnings", "user_id=eq.$OWNER&date=eq.$day&select=id")
            assertEquals(0, found.length())
            actor.insert("poker_hourly_earnings", JSONObject().put("user_id", OWNER).put("date", day)
                .put("hours_played", 1).put("profit_loss", index + 1).put("hourly_rate", index + 1)
                .put("net_won_ev", 0).put("hourly_rate_ev", 0)).getString("id")
        } }.awaitAll()
        assertEquals(2, inserted.toSet().size)
        assertEquals(2, rows("poker_hourly_earnings").values.count { it.getString("date") == day && it.getString("user_id") == OWNER })
        assertEquals(listOf("POST", "POST"), mutations.map { it.method })
        assertEquals(0, denied.get())
        // This proves the precheck race under an unconstrained fixture, not deployed-schema behaviour.
    }

    @Test fun rotatingTokenForSameSessionPreservesSaveButNewSessionForSameUidCannotUseLoadedDraft() {
        profiles(prepare = {
            seed(nextCut = true)
            val initial = token(SID, "profile-reset"); tokens.add(initial)
            runBlocking { primaryStore.save(session(initial)); api.session() }
        }) { en, _ ->
            val save = button(if (en) "Save Next Cut" else "Salva Next Cut")
            ready(if (en) "Save Next Cut" else "Salva Next Cut")
            val refreshed = token(SID, "rotated"); tokens.add(refreshed)
            runBlocking { primaryStore.save(session(refreshed)); api.session() }
            input(if (en) "Current profit / loss (€)" else "Profit / Loss attuale (€)", "600")
            click(save)
            await("Same-session rotated token can save") { mutations.size == 1 && enabled(save) }
            assertEquals("PATCH", mutations.single().method)
            // Ensure the post-save read has actually published its value before changing the owner boundary.
            metric(if (en) "Amount needed for the next cut" else "Quanto manca al prossimo cut", "-50,00\u00a0€")
            val replacement = token(NEW_SID, "new-login"); tokens.add(replacement)
            runBlocking { primaryStore.save(session(replacement)); api.session() }
            input(if (en) "Current profit / loss (€)" else "Profit / Loss attuale (€)", "601")
            click(save)
            show(hasText(if (en) "Account changed. Reopen the screen." else "Account cambiato. Riapri la schermata."))
            assertEquals("A new login for the same UID must not dispatch the old draft", 1, mutations.size)
            number(rows("poker_next_cut").getValue(NEXT), "profit_loss", "600")
            assertEquals(0, denied.get())
        }
    }

    private fun seed(nextCut: Boolean) {
        tables.clear(); mutations.clear(); denied.set(0); nextReadFailures.set(0); nextPostStatus.set(201)
        if (nextCut) rows("poker_next_cut")[NEXT] = base(NEXT).put("name", "Next Cut").put("amount", 0).put("deal", 0.5).put("profit_loss", -350)
        val expenses = listOf(today.minusDays(730) to 9999, LocalDate.of(today.year - 1, 1, 1) to 100,
            LocalDate.of(today.year - 1, 1, 31) to 50, LocalDate.of(today.year - 1, 3, 1) to 250,
            LocalDate.of(today.year + 1, 1, 1) to 300)
        expenses.forEachIndexed { index, (day, amount) -> val id = UUID.nameUUIDFromBytes("poker-expense-$index".toByteArray()).toString()
            rows("transactions")[id] = base(id).put("date", day.toString()).put("amount", amount).put("type", "expense") }
        rows("poker_manual_expenses")[MANUAL] = base(MANUAL).put("name", "QA Existing Manual").put("amount", 25)
        listOf(100 to 10, 300 to 90).forEachIndexed { index, (rake, received) ->
            val id = UUID.nameUUIDFromBytes("poker-prior-$index".toByteArray()).toString()
            rows("poker_rakeback")[id] = base(id).put("date", "${today.year - 1}-${if (index == 0) "01" else "03"}-01")
                .put("rake_generated", rake).put("rakeback_received", received)
        }
    }

    private fun rows(table: String) = tables.getOrPut(table) { ConcurrentHashMap() }
    private fun token(sid: String, nonce: String): String = "fixture.${Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject().put("session_id", sid).toString().toByteArray(Charsets.UTF_8))}.$nonce"
    private fun session(token: String) = Session(token, "unused", Long.MAX_VALUE, OWNER, "poker@fixture.invalid")
    private fun base(id: String) = JSONObject().put("id", id).put("user_id", OWNER).put("created_at", "2026-01-01T00:00:00Z")
    private fun json(code: Int, body: Any) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun reject(): MockResponse { denied.incrementAndGet(); return json(403, JSONObject()) }
    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return reject()
            val table = url.pathSegments.lastOrNull() ?: return reject()
            if (url.encodedPath != "/rest/v1/$table" || table !in setOf("poker_next_cut", "poker_manual_expenses", "transactions", "poker_rakeback", "poker_hourly_earnings") ||
                request.getHeader("Authorization")?.startsWith("Bearer ") != true ||
                request.getHeader("Authorization")?.removePrefix("Bearer ") !in tokens || request.getHeader("apikey") != "poker-fixture-public") return reject()
            if (request.method == "GET") {
                if (url.queryParameter("user_id") != "eq.$OWNER") return reject()
                if (table == "poker_hourly_earnings" && url.queryParameter("select") == "id") {
                    if (url.queryParameterNames != setOf("user_id", "date", "select") || url.queryParameter("date") != "eq.${today.year}-05-01") return reject()
                    val gate = duplicateGate ?: return reject(); gate.countDown()
                    if (!gate.await(10, TimeUnit.SECONDS)) return json(503, JSONObject())
                    return json(200, JSONArray())
                }
                if (table == "poker_next_cut") {
                    if (url.queryParameterNames != setOf("user_id", "order", "limit") || url.queryParameter("limit") != "1" || url.queryParameter("order") != "created_at.asc") return reject()
                    if (nextReadFailures.getAndUpdate { maxOf(0, it - 1) } > 0) return json(503, JSONObject())
                    return json(200, JSONArray(rows(table).values.sortedBy { it.getString("id") }.take(1)))
                }
                val orders = when (table) {
                    "poker_rakeback", "poker_hourly_earnings" -> setOf("created_at.desc")
                    // Next Cut reads by id; the record list explicitly orders the same table by creation.
                    "poker_manual_expenses" -> setOf("id.asc", "created_at.desc")
                    else -> setOf("id.asc")
                }
                if (url.queryParameterNames != setOf("user_id", "order", "limit", "offset") || url.queryParameter("limit") != "500" || url.queryParameter("order") !in orders) return reject()
                val offset = url.queryParameter("offset")?.toIntOrNull()?.takeIf { it >= 0 } ?: return reject()
                return json(200, JSONArray(rows(table).values.sortedBy { it.getString("id") }.drop(offset).take(500)))
            }
            if (table == "transactions") return reject()
            if (request.method == "POST") {
                if (url.queryParameterNames.isNotEmpty()) return reject()
                val body = JSONObject(request.body.readUtf8())
                val id = body.optString("id")
                if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not() || body.optString("user_id") != OWNER || rows(table).containsKey(id)) return reject()
                val stored = JSONObject(body.toString()).put("created_at", "2026-01-01T00:00:00Z")
                rows(table)[id] = stored; mutations.add(Mutation("POST", table, id, body))
                val status = if (table == "poker_next_cut") nextPostStatus.getAndSet(201) else 201
                return if (status == 201) json(201, JSONArray().put(stored)) else json(status, JSONObject())
            }
            if (url.queryParameterNames != setOf("id")) return reject()
            val id = url.queryParameter("id")?.removePrefix("eq.") ?: return reject()
            val existing = rows(table)[id]?.takeIf { it.getString("user_id") == OWNER } ?: return reject()
            if (request.method == "PATCH") {
                val body = JSONObject(request.body.readUtf8())
                if (body.has("user_id") || body.has("id") || (table in setOf("poker_hourly_earnings", "poker_rakeback") && body.has("date"))) return reject()
                body.keys().forEach { key -> existing.put(key, body.get(key)) }
                mutations.add(Mutation("PATCH", table, id, body)); return json(200, JSONArray().put(existing))
            }
            if (request.method == "DELETE") {
                rows(table).remove(id); mutations.add(Mutation("DELETE", table, id, JSONObject())); return json(200, JSONArray().put(existing))
            }
            return reject()
        }
    }

    private fun profiles(prepare: () -> Unit, verify: (Boolean, CommonVisualProfile) -> Unit) {
        val active = mutableStateOf<CommonVisualProfile?>(null)
        compose.setContent { active.value?.let { profile -> key(profile) {
            CommonVisualContent(profile) {
                val focus = LocalFocusManager.current; val keyboard = LocalSoftwareKeyboardController.current
                SideEffect { hideInput = { focus.clearFocus(force = true); keyboard?.hide() } }
                ModuleScreen(api, "poker", Modifier.fillMaxSize())
            }
        } } }
        listOf(CommonVisualProfile.IT_LIGHT, CommonVisualProfile.EN_DARK_LARGE).forEach { profile ->
            prepare(); compose.runOnIdle { active.value = profile }; compose.waitForIdle()
            try { verify(profile == CommonVisualProfile.EN_DARK_LARGE, profile) }
            finally { compose.runOnIdle { hideInput(); active.value = null }; compose.waitForIdle(); hideInput = {} }
        }
    }

    private fun number(body: JSONObject, key: String, value: String) {
        assertTrue(body.get(key) is Number); assertEquals(0, BigDecimal(value).compareTo(BigDecimal(body.get(key).toString())))
    }
    private fun button(text: String) = hasText(text) and hasClickAction()
    private fun enabled(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
    private fun await(label: String, ready: () -> Boolean) {
        try { compose.waitUntil(15_000) {
            compose.runOnUiThread { effectDispatcher.scheduler.runCurrent() }
            ready()
        } } catch (failure: ComposeTimeoutException) {
            val tree = compose.onRoot().printToString().take(16_000)
            throw AssertionError("$label; mutations=${mutations.size}, denied=${denied.get()}\n$tree", failure)
        }
    }
    private fun ready(label: String) {
        val target = button(label)
        show(target)
        await("Enabled visible $label") { enabled(target) }
        compose.onNode(target).assertIsDisplayed().assertIsEnabled()
    }
    private fun input(label: String, value: String) {
        compose.runOnIdle { hideInput() }
        val field = hasText(label) and hasSetTextAction()
        if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()) show(field)
        compose.onNode(field).performScrollTo().performTextReplacement(value)
        compose.waitForIdle()
    }
    private fun click(matcher: SemanticsMatcher) { compose.runOnIdle { hideInput() }; show(matcher); compose.onNode(matcher).assertIsEnabled().performClick(); compose.waitForIdle() }
    private fun add(en: Boolean) { click(button(if (en) "Add" else "Aggiungi")); compose.onNode(isDialog()).assertExists() }
    private fun dialogSave(en: Boolean) {
        compose.onNode(button(if (en) "Save" else "Salva") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnUiThread { effectDispatcher.scheduler.runCurrent() }
        compose.waitForIdle()
    }
    private fun awaitClosed(en: Boolean) { await("Dialog closed and records refreshed") { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }; ready(if (en) "Add" else "Aggiungi") }
    private fun rowAction(en: Boolean, name: String, edit: Boolean, month: String? = null) {
        val fingerprint = hasText(if (month == null) name else "${if (en) "Month (YYYY-MM)" else "Mese (AAAA-MM)"}: $month", substring = month == null)
        click(button(if (edit) { if (en) "Edit" else "Modifica" } else { if (en) "Delete" else "Elimina" }) and hasAnyAncestor(hasAnyDescendant(fingerprint)))
    }
    private fun metric(label: String, value: String) = show(hasText(value) and hasAnyAncestor(hasAnyDescendant(hasText(label))))
    private fun bounds(matcher: SemanticsMatcher): Rect {
        val node = compose.onNode(matcher).fetchSemanticsNode()
        return Rect(node.positionInRoot.x, node.positionInRoot.y, node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)
    }
    private fun show(matcher: SemanticsMatcher) {
        await("The localized screen has mounted its list") {
            compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size == 1
        }
        val present = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
        val horizontal = generateSequence(present?.parent) { it.parent }
            .firstOrNull { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
        if (horizontal != null) {
            var steps = 0
            await("Horizontal target is fully visible: ${matcher.description}") {
                val node = compose.onNode(matcher).fetchSemanticsNode()
                val rect = Rect(node.positionInRoot.x, node.positionInRoot.y,
                    node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)
                val frame = horizontal.boundsInRoot.intersect(bounds(hasTestTag(CommonVisualArtifacts.VIEWPORT)))
                if (rect.left >= frame.left && rect.right <= frame.right) true
                else {
                    check(steps++ < 24) { "Horizontal target exceeds bounded scrolling: ${matcher.description}" }
                    compose.onNode(SemanticsMatcher("Horizontal ancestor ${horizontal.id}") { it.id == horizontal.id })
                        .performSemanticsAction(SemanticsActions.ScrollBy) {
                            it((rect.center.x - frame.center.x).coerceIn(-frame.width / 4f, frame.width / 4f), 0f)
                        }
                    false
                }
            }
            compose.onNode(matcher).assertIsDisplayed()
            return
        }
        val list = compose.onNode(hasScrollToIndexAction())
        // Scroll one step at a time; ScrollToNode can loop while queued HTTP effects need draining.
        list.performScrollToIndex(0)
        var previous: Pair<Int, Rect>? = null
        var stable = 0
        var gestures = 0
        await("Stable fully visible target: ${matcher.description}") {
            compose.mainClock.advanceTimeByFrame()
            try {
                val target = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
                // Lazy semantics can retain an unplaced item with an obsolete on-screen position.
                if (target == null || generateSequence(target.layoutInfo) { it.parentInfo }.any { !it.isPlaced }) {
                    check(gestures < 24) { "Target not composed after bounded scroll: ${matcher.description}\n${compose.onRoot().printToString().take(16_000)}" }
                    val visibleList = list.fetchSemanticsNode().boundsInRoot
                    list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, visibleList.height / 4f) }
                    gestures++; stable = 0; false
                }
                else {
                    val rect = Rect(target.positionInRoot.x, target.positionInRoot.y,
                        target.positionInRoot.x + target.size.width, target.positionInRoot.y + target.size.height)
                    var viewport = bounds(hasTestTag(CommonVisualArtifacts.VIEWPORT))
                    val ancestors = generateSequence(target.parent) { it.parent }.filter {
                        it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                    }.toList()
                    ancestors.forEach { viewport = viewport.intersect(it.boundsInRoot) }
                    check(rect.width <= viewport.width && rect.height <= viewport.height) {
                        "Target exceeds viewport: ${matcher.description}, rect=$rect, viewport=$viewport"
                    }
                    val identity = SemanticsMatcher("Stable target ${target.id}") { it.id == target.id }
                    val inside = rect.left >= viewport.left && rect.right <= viewport.right &&
                        rect.top >= viewport.top && rect.bottom <= viewport.bottom
                    val visible = inside && compose.onNode(identity).isDisplayed()
                    val sample = target.id to rect
                    stable = if (visible && sample == previous) stable + 1 else 0
                    previous = sample
                    if (!inside) {
                        val scrollable = ancestors.firstOrNull()
                        check(scrollable != null && gestures < 24) {
                            "Target cannot enter viewport: ${matcher.description}, rect=$rect, viewport=$viewport"
                        }
                        compose.onNode(SemanticsMatcher("Scrollable ancestor ${scrollable.id}") { it.id == scrollable.id })
                            .performSemanticsAction(SemanticsActions.ScrollBy) {
                                it(0f, (rect.center.y - viewport.center.y).coerceIn(-viewport.height / 4f, viewport.height / 4f))
                            }
                        gestures++
                    }
                    stable >= 2
                }
            } catch (failure: AssertionError) {
                // An HTTP refresh can replace lazy semantics between two snapshots.
                val message = failure.message.orEmpty()
                if (!message.startsWith("No node found that matches ${matcher.description} in scrollable container") &&
                    !message.contains("Failed to retrieve a node at index '0'")) throw failure
                stable = 0
                false
            }
        }
        compose.onNode(matcher).assertIsDisplayed()
    }
    /** The long record Text can exceed one viewport; verify every character of the requested literal. */
    private fun showFragment(matcher: SemanticsMatcher, fragment: String) {
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(0)
        var steps = 0
        var previous: Rect? = null
        var stable = 0
        await("Fully visible literal: $fragment") {
            val node = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
            val frame = list.fetchSemanticsNode().boundsInRoot.intersect(bounds(hasTestTag(CommonVisualArtifacts.VIEWPORT)))
            if (node == null || generateSequence(node.layoutInfo) { it.parentInfo }.any { !it.isPlaced }) {
                check(steps++ < 24) { "Literal not placed within bounded scroll" }
                list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, frame.height / 4f) }
                false
            } else {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNode(matcher).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                assertEquals(1, layouts.size)
                val layout = layouts.single()
                val value = layout.layoutInput.text.text
                val start = value.indexOf(fragment)
                assertTrue(start >= 0 && value.lastIndexOf(fragment) == start)
                val boxes = (start until start + fragment.length).filter { !value[it].isWhitespace() }
                    .map { layout.getBoundingBox(it) }
                val rect = Rect(node.positionInRoot.x + boxes.minOf { it.left }, node.positionInRoot.y + boxes.minOf { it.top },
                    node.positionInRoot.x + boxes.maxOf { it.right }, node.positionInRoot.y + boxes.maxOf { it.bottom })
                check(rect.width <= frame.width && rect.height <= frame.height) { "Literal exceeds the real viewport: $rect / $frame" }
                val inside = rect.left >= frame.left && rect.right <= frame.right && rect.top >= frame.top && rect.bottom <= frame.bottom
                stable = if (inside && rect == previous && compose.onNode(matcher).isDisplayed()) stable + 1 else 0
                previous = rect
                if (!inside) {
                    check(steps++ < 24) { "Literal cannot enter viewport" }
                    list.performSemanticsAction(SemanticsActions.ScrollBy) {
                        it(0f, (rect.center.y - frame.center.y).coerceIn(-frame.height / 4f, frame.height / 4f))
                    }
                }
                stable >= 2
            }
        }
        compose.onNode(matcher).assertIsDisplayed().assertTextContains(fragment, substring = true)
    }
    private fun capture(name: String, profile: CommonVisualProfile, matcher: SemanticsMatcher) {
        show(matcher); CommonVisualArtifacts.capture(compose, "v10-$name-${profile.suffix}", profile, clockInjected = false)
    }

    private companion object {
        const val OWNER = "41000000-0000-4000-8000-000000000001"
        const val NEXT = "41000000-0000-4000-8000-000000000002"
        const val MANUAL = "41000000-0000-4000-8000-000000000003"
        const val SID = "41000000-0000-4000-8000-000000000004"
        const val PEER_SID = "41000000-0000-4000-8000-000000000005"
        const val NEW_SID = "41000000-0000-4000-8000-000000000006"
    }
}
