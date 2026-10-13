package cc.savely.android.finance

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.ui.SavelyTheme
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
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Compose forms/touches and localhost TLS. No external API, credentials or production rows. */
@RunWith(AndroidJUnit4::class)
class FinanceCategoryBudgetCrudTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var store: MemorySessionStore
    private lateinit var api: SupabaseApi
    private val lock = Any()
    private val categories = linkedMapOf<String, JSONObject>()
    private val transactions = linkedMapOf<String, JSONObject>()
    private val budgets = linkedMapOf<String, JSONObject>()
    private val section = mutableStateOf("categories")
    private val accountKey = mutableStateOf(A)
    private val refreshKey = mutableStateOf(0)
    private val posts = AtomicInteger()
    private val patches = AtomicInteger()
    private val deletes = AtomicInteger()
    private val denied = AtomicInteger()
    private val categorySoftDeletes = AtomicInteger()
    private val detachAttempts = AtomicInteger()
    private val createdId = AtomicReference<String>()
    private val lastBody = AtomicReference<String>()
    private val failSecondDetach = AtomicBoolean(false)
    private val gatePost = AtomicBoolean(false)
    private val postEntered = CountDownLatch(1)
    private val releasePost = CountDownLatch(1)
    private val gateNextARead = AtomicBoolean(false)
    private val readEntered = CountDownLatch(1)
    private val releaseRead = CountDownLatch(1)
    private val lateReadReturned = AtomicBoolean(false)
    private val bReads = AtomicInteger()
    private val aReads = AtomicInteger()
    // Category deletion must never rewrite the shared snapshot or the partner's original ledger.
    private val sharedSnapshot = JSONObject().put("id", SHARE).put("created_by", A)
        .put("couple_category_name", "Spese QA").put("original_tx_id", TX1)
    private lateinit var foreignBefore: String
    private lateinit var shareBefore: String

    @Before fun setup() {
        synchronized(lock) {
            categories[CAT] = category(CAT, A, "Spese QA")
            categories[FREE_CAT] = category(FREE_CAT, A, "Libera QA")
            categories[INCOME_CAT] = category(INCOME_CAT, A, "Entrate QA", "income")
            categories[B_CAT] = category(B_CAT, B, "Solo B QA")
            transactions[TX1] = transaction(TX1, A, "10.00")
            transactions[TX2] = transaction(TX2, A, "5.00")
            transactions[B_TX] = transaction(B_TX, B, "99.00")
            budgets[BUDGET] = JSONObject().put("id", BUDGET).put("user_id", A)
                .put("category_id", CAT).put("amount", "30.00").put("currency", "EUR")
                .put("month", 1).put("year", 2000)
            foreignBefore = transactions.getValue(B_TX).toString()
            shareBefore = sharedSnapshot.toString()
        }
        val certificate = HeldCertificate.Builder().commonName("Savely category budget fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        val served = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server = MockWebServer().apply {
            useHttps(served.sslSocketFactory(), false)
            dispatcher = fixtureDispatcher()
            start()
        }
        store = MemorySessionStore(session(A))
        val url = server.url("/").toString()
        api = SupabaseApi(BackendConfig(url, "public-fixture-key", url), store, client)
    }

    @After fun teardown() {
        releasePost.countDown()
        releaseRead.countDown()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test(timeout = 90_000) fun categoryCrudDetachesOnlyOwnTransactionsPreservesSharedSnapshotAndLeavesBudgetEditable() {
        mount("categories")
        visible(hasText("Spese QA"))
        touch(hasText("Nuova categoria"))
        touch(hasText("Salva"))
        visible(hasText("Inserisci il nome"))
        assertEquals(0, posts.get())
        replace("Nome", "Nuova QA")
        replace("Colore #RRGGBB", "not-a-color")
        touch(hasText("Salva"))
        visible(hasText("Colore non valido: usa #RRGGBB"))
        assertEquals(0, posts.get())
        replace("Colore #RRGGBB", "#22c55e")
        touch(hasText("Salva"))
        await("category persisted and form closed") { posts.get() == 1 && absent(hasText("Nuovo elemento")) }
        val newId = requireNotNull(createdId.get())
        assertEquals(newId, UUID.fromString(newId).toString())
        synchronized(lock) {
            val saved = categories.getValue(newId)
            assertEquals(A, saved.getString("user_id"))
            assertEquals("Nuova QA", saved.getString("name"))
            assertEquals("expense", saved.getString("type"))
            assertEquals("#22c55e", saved.getString("color"))
        }
        touch(hasContentDescription("Modifica Nuova QA"))
        replace("Nome", "Categoria aggiornata")
        touch(hasText("Salva"))
        await("category edited on the same UUID") { patches.get() == 1 && absent(hasText("Modifica elemento")) }
        visible(hasText("Categoria aggiornata"))
        assertEquals("Categoria aggiornata", synchronized(lock) { categories.getValue(newId).getString("name") })

        touch(hasContentDescription("Elimina Spese QA"))
        visible(hasText("Eliminare Spese QA?"))
        touch(hasText("Annulla"))
        assertEquals(0, detachAttempts.get())
        assertEquals(0, categorySoftDeletes.get())
        touch(hasContentDescription("Elimina Spese QA"))
        touch(hasText("Elimina", substring = false))
        await("category removed only after both own detaches") { categorySoftDeletes.get() == 1 }
        synchronized(lock) {
            assertTrue(transactions.getValue(TX1).isNull("category_id"))
            assertTrue(transactions.getValue(TX2).isNull("category_id"))
            assertFalse(categories.getValue(CAT).isNull("deleted_at"))
            assertEquals(CAT, budgets.getValue(BUDGET).getString("category_id"))
        }
        assertEquals(2, detachAttempts.get())
        assertEquals(0, deletes.get()) // Categories use soft deletion, not DELETE.
        assertForeignAndSharedUnchanged()

        compose.runOnIdle { section.value = "budget" }
        visible(hasText("Categoria rimossa"))
        touch(hasContentDescription("Modifica Categoria rimossa"))
        replace("Importo", "0")
        touch(hasText("Salva"))
        await("removed-category budget still editable") { synchronized(lock) { budgets.getValue(BUDGET).getString("amount") == "0.00" } }
        val body = JSONObject(requireNotNull(lastBody.get()))
        assertEquals(setOf("amount"), body.keys().asSequence().toSet())
        synchronized(lock) {
            val saved = budgets.getValue(BUDGET)
            assertEquals(BUDGET, saved.getString("id"))
            assertEquals(CAT, saved.getString("category_id"))
            assertEquals("EUR", saved.getString("currency"))
            assertEquals(1, saved.getInt("month"))
            assertEquals(2000, saved.getInt("year"))
        }
        assertForeignAndSharedUnchanged()
        assertEquals(0, denied.get())
    }

    @Test(timeout = 90_000) fun budgetCrudValidatesBeforeSubmitGatesDuplicateTouchAndPreservesIdentityAndCurrency() {
        mount("budget")
        visible(hasContentDescription("Riepilogo budget EUR"))
        touch(hasText("Nuovo budget"))
        touch(hasText("Categoria: Seleziona"))
        compose.onNode(hasText("Entrate QA") and hasAnyAncestor(isPopup())).assertDoesNotExist()
        compose.onNode(hasText("Spese QA") and hasAnyAncestor(isPopup())).assertDoesNotExist() // Already has a budget.
        touch(hasText("Libera QA"))
        replace("Importo", "bad")
        touch(hasText("Salva"))
        visible(hasText("Inserisci un importo numerico"))
        assertEquals(0, posts.get())
        replace("Importo", "-0,01")
        touch(hasText("Salva"))
        visible(hasText("Importo non negativo richiesto"))
        assertEquals(0, posts.get())
        replace("Importo", "12,50")
        gatePost.set(true)
        touch(hasText("Salva"))
        await("one POST in flight") { postEntered.count == 0L }
        val busy = compose.onNodeWithText("Salvataggio…").assertIsNotEnabled().assertIsDisplayed()
        busy.performTouchInput { click(); click() }
        assertEquals(1, posts.get())
        releasePost.countDown()
        await("budget create completed") { absent(hasText("Nuovo elemento")) }
        val newId = requireNotNull(createdId.get())
        synchronized(lock) {
            val saved = budgets.getValue(newId)
            assertEquals(A, saved.getString("user_id"))
            assertEquals(FREE_CAT, saved.getString("category_id"))
            assertEquals("12.50", saved.getString("amount"))
            assertEquals("EUR", saved.getString("currency"))
            assertEquals(1, saved.getInt("month"))
            assertEquals(2000, saved.getInt("year"))
        }
        touch(hasContentDescription("Modifica Libera QA"))
        replace("Importo", "20,75")
        touch(hasText("Salva"))
        await("budget update closed") { patches.get() == 1 && absent(hasText("Modifica elemento")) }
        val updated = JSONObject(requireNotNull(lastBody.get()))
        assertEquals(setOf("amount"), updated.keys().asSequence().toSet())
        assertEquals("20.75", updated.getString("amount"))
        assertEquals(newId, synchronized(lock) { budgets.getValue(newId).getString("id") })
        visible(hasText("20,75", substring = true))
        touch(hasContentDescription("Elimina Libera QA"))
        visible(hasText("Eliminare budget?"))
        touch(hasText("Annulla"))
        assertEquals(0, deletes.get())
        touch(hasContentDescription("Elimina Libera QA"))
        touch(hasText("Elimina", substring = false))
        await("only the created budget deleted") { deletes.get() == 1 && absent(hasText("Eliminare budget?")) }
        synchronized(lock) {
            assertFalse(budgets.containsKey(newId))
            assertTrue(budgets.containsKey(BUDGET))
        }
        assertEquals(1, posts.get())
        assertEquals(1, patches.get())
        assertForeignAndSharedUnchanged()
        assertEquals(0, denied.get())
    }

    @Test(timeout = 90_000) fun partialCategoryDetachKeepsCategoryShowsConfirmedPrefixAndRequiresExplicitRefreshWithoutRetry() {
        failSecondDetach.set(true)
        mount("categories")
        touch(hasContentDescription("Elimina Spese QA"))
        touch(hasText("Elimina", substring = false))
        val error = hasText("Rimozione interrotta: 1 transazioni scollegate con conferma.", substring = true)
        visible(error)
        visible(hasText("Le modifiche sono bloccate finché un aggiornamento dei dati non riesce."))
        compose.onNodeWithText("Nuova categoria").assertIsNotEnabled()
        assertEquals(2, detachAttempts.get())
        assertEquals(0, categorySoftDeletes.get())
        synchronized(lock) {
            assertTrue(transactions.getValue(TX1).isNull("category_id"))
            assertEquals(CAT, transactions.getValue(TX2).getString("category_id"))
            assertTrue(categories.getValue(CAT).isNull("deleted_at"))
        }
        compose.waitForIdle()
        assertEquals("No blind automatic retry", 2, detachAttempts.get())
        failSecondDetach.set(false)
        touch(hasContentDescription("Aggiorna i dati"))
        await("explicit successful refresh unblocks forms") {
            val node = compose.onAllNodesWithText("Nuova categoria").fetchSemanticsNodes().singleOrNull()
            node != null && !node.config.contains(SemanticsProperties.Disabled)
        }
        assertEquals("Refresh must not retry the deletion", 2, detachAttempts.get())
        assertEquals(0, categorySoftDeletes.get())
        visible(hasText("Spese QA"))
        assertForeignAndSharedUnchanged()
        assertEquals(0, denied.get())
    }

    @Test(timeout = 90_000) fun switchingOwnerDropsCategoryDraftAndRejectsLateOldOwnerReadWithoutSubmittingToTheNewAccount() {
        mount("categories")
        visible(hasText("Spese QA"))
        val oldRevision = runBlocking { api.sessionOwnerRevision() }
        gateNextARead.set(true)
        touch(hasContentDescription("Aggiorna i dati"))
        await("old-owner refresh pending") { readEntered.count == 0L }
        touch(hasText("Nuova categoria"))
        replace("Nome", "Draft privato A")
        // Sync the actual API owner revision, rather than merely modifying the backing store.
        store.save(session(B))
        val newRevision = runBlocking {
            assertEquals(B, api.session()?.userId)
            api.sessionOwnerRevision()
        }
        assertNotEquals(oldRevision, newRevision)
        compose.runOnIdle { accountKey.value = B }
        await("new owner read dispatched") { bReads.get() > 0 }
        releaseRead.countDown()
        await("old-owner reply released") { lateReadReturned.get() }
        visible(hasText("Solo B QA"))
        await("stale category form removed") { absent(hasText("Nuovo elemento")) }
        compose.onNodeWithText("Draft privato A").assertDoesNotExist()
        compose.onNodeWithText("Spese QA").assertDoesNotExist()
        touch(hasText("Nuova categoria"))
        assertEquals("The new owner's form must start without the previous draft", "",
            compose.onNode(hasText("Nome") and hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        touch(hasText("Salva"))
        visible(hasText("Inserisci il nome"))
        assertEquals(0, posts.get())
        assertEquals(0, patches.get())
        assertEquals(0, deletes.get())
        synchronized(lock) {
            assertEquals(listOf("Solo B QA"), categories.values.filter { it.getString("user_id") == B }.map { it.getString("name") })
        }
        assertForeignAndSharedUnchanged()
        assertEquals(0, denied.get())
    }

    @Test(timeout = 90_000) fun sameSessionTokenRefreshKeepsCategoryDraftButNewLoginOfSameUserDropsItBeforeAnyWrite() {
        store.save(jwtSession(SID1, "original"))
        val originalRevision = runBlocking {
            assertEquals(A, api.session()?.userId)
            api.sessionOwnerRevision()
        }
        accountKey.value = "$A:$originalRevision"
        mount("categories")
        visible(hasText("Spese QA"))
        touch(hasText("Nuova categoria"))
        replace("Nome", "Draft stessa sessione")
        val readsBeforeRefresh = aReads.get()
        store.save(jwtSession(SID1, "refreshed"))
        val refreshedRevision = runBlocking {
            assertEquals(A, api.session()?.userId)
            api.sessionOwnerRevision()
        }
        assertEquals("Changing the access token within the same SID must preserve the accepted owner", originalRevision, refreshedRevision)
        compose.runOnIdle {
            accountKey.value = "$A:$refreshedRevision"
            refreshKey.value++
        }
        await("same-session refresh reads completed") { aReads.get() >= readsBeforeRefresh + 3 }
        visible(hasText("Nuovo elemento"))
        assertEquals("Draft stessa sessione", nameValue())
        assertEquals(0, posts.get())

        store.save(jwtSession(SID2, "replacement"))
        val replacementRevision = runBlocking {
            assertEquals(A, api.session()?.userId)
            api.sessionOwnerRevision()
        }
        assertNotEquals("A new login SID must invalidate the original form even for the same UID", originalRevision, replacementRevision)
        compose.runOnIdle { accountKey.value = "$A:$replacementRevision" }
        await("new SID removes the old form") { absent(hasText("Nuovo elemento")) }
        visible(hasText("Spese QA"))
        compose.onNodeWithText("Draft stessa sessione").assertDoesNotExist()
        touch(hasText("Nuova categoria"))
        assertEquals("", nameValue())
        touch(hasText("Salva"))
        visible(hasText("Inserisci il nome"))
        assertEquals(0, posts.get())
        assertEquals(0, patches.get())
        assertEquals(0, deletes.get())
        assertForeignAndSharedUnchanged()
        assertEquals(0, denied.get())
    }

    private fun nameValue() = compose.onNode(hasText("Nome") and hasSetTextAction())
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun mount(route: String) {
        section.value = route
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, section.value, Modifier.fillMaxSize(), refreshKey = refreshKey.value,
                accountKey = accountKey.value, today = TODAY) }
        }
    }

    private fun replace(label: String, value: String) {
        val matcher = hasText(label) and hasSetTextAction()
        visible(matcher)
        compose.onNode(matcher).performTextReplacement(value)
    }

    private fun absent(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()

    private fun visible(matcher: SemanticsMatcher) {
        await("composed target $matcher") {
            if (absent(matcher)) {
                val list = compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().singleOrNull()
                if (list != null) {
                    try {
                        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
                    } catch (failure: AssertionError) {
                        // A loaded lazy list can exist before its asynchronous rows arrive. Retry
                        // only Compose's exact missing-descendant result under the same deadline;
                        // layout, ambiguous matches and other assertions still fail immediately.
                        val expected = "No node found that matches ${matcher.description} in scrollable container"
                        if (failure.message?.substringBefore('\n') != expected) throw failure
                    }
                }
            }
            !absent(matcher)
        }
        val node = compose.onNode(matcher).fetchSemanticsNode()
        if (generateSequence(node.parent) { it.parent }.any { it.config.contains(SemanticsActions.ScrollBy) }) {
            compose.onNode(matcher).performScrollTo()
        }
        compose.waitForIdle()
        compose.onNode(matcher).assertIsDisplayed()
    }

    private fun touch(matcher: SemanticsMatcher) {
        visible(matcher)
        var previous: Rect? = null
        var stable = 0
        await("stable enabled pointer target $matcher") {
            val node = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
            val bounds = node?.boundsInRoot
            val inside = bounds != null && bounds.width > 0 && bounds.height > 0 &&
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().any { root ->
                    val viewport = root.boundsInRoot
                    bounds.left >= viewport.left && bounds.top >= viewport.top && bounds.right <= viewport.right && bounds.bottom <= viewport.bottom
                }
            stable = if (inside && node != null && !node.config.contains(SemanticsProperties.Disabled) && bounds == previous) stable + 1 else 0
            previous = bounds
            stable >= 3
        }
        compose.onNode(matcher).assertIsEnabled().assertIsDisplayed().performTouchInput { click() }
    }

    private fun await(label: String, predicate: () -> Boolean) {
        try { compose.waitUntil(15_000, predicate) }
        catch (failure: ComposeTimeoutException) {
            val trees = compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.map { index ->
                compose.onAllNodes(isRoot())[index].printToString()
            }.joinToString("\n")
            throw AssertionError("Category/Budget fixture: $label; POST=${posts.get()}, PATCH=${patches.get()}, DELETE=${deletes.get()}, detach=${detachAttempts.get()}, denied=${denied.get()}\n$trees", failure)
        }
    }

    private fun assertForeignAndSharedUnchanged() = synchronized(lock) {
        assertEquals(foreignBefore, transactions.getValue(B_TX).toString())
        assertEquals(shareBefore, sharedSnapshot.toString())
    }

    private fun fixtureDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return reject()
            if (request.getHeader("apikey") != "public-fixture-key") return reject()
            val actor = when (request.getHeader("Authorization")) {
                "Bearer category-budget-a" -> A
                "Bearer category-budget-b" -> B
                "Bearer ${jwtSession(SID1, "original").accessToken}",
                "Bearer ${jwtSession(SID1, "refreshed").accessToken}",
                "Bearer ${jwtSession(SID2, "replacement").accessToken}" -> A
                else -> return reject()
            }
            val table = url.pathSegments.last()
            if (table !in setOf("categories", "budgets", "transactions", "profiles")) return reject()
            if (request.method != "POST" && url.queryParameter("user_id") != "eq.$actor") return reject()
            if (request.method == "GET") {
                if (actor == B) bReads.incrementAndGet()
                if (actor == A) aReads.incrementAndGet()
                val rows = synchronized(lock) {
                    if (table == "profiles") listOf(JSONObject().put("default_currency", "EUR"))
                    else {
                        val source = when (table) { "categories" -> categories; "budgets" -> budgets; else -> transactions }
                        val id = url.queryParameter("id")?.removePrefix("eq.")
                        val category = url.queryParameter("category_id")?.removePrefix("eq.")
                        val filtered = source.values.filter {
                            it.getString("user_id") == actor && (id == null || it.getString("id") == id) &&
                                (category == null || !it.isNull("category_id") && it.getString("category_id") == category) &&
                                (url.queryParameter("deleted_at") != "is.null" || it.isNull("deleted_at"))
                        }.sortedBy { it.getString("id") }
                        val offset = url.queryParameter("offset")?.toIntOrNull() ?: 0
                        val limit = url.queryParameter("limit")?.toIntOrNull() ?: filtered.size
                        if (offset < 0 || limit < 0 || limit > 500) return reject()
                        filtered.drop(offset).take(limit).map { JSONObject(it.toString()) }
                    }
                }
                if (actor == A && table == "categories" && gateNextARead.compareAndSet(true, false)) {
                    readEntered.countDown()
                    if (!releaseRead.await(18, TimeUnit.SECONDS)) return json("{}", 503)
                    lateReadReturned.set(true)
                }
                return json(JSONArray(rows).toString())
            }
            if (actor != A) return reject()
            val body = if (request.method == "DELETE") JSONObject() else JSONObject(request.body.readUtf8())
            return synchronized(lock) {
                if (request.method == "POST") {
                    if (table !in setOf("categories", "budgets") || body.optString("user_id") != A) return reject()
                    val id = body.getString("id")
                    if (UUID.fromString(id).toString() != id) return reject()
                    val source = if (table == "categories") categories else budgets
                    if (source.containsKey(id)) return reject()
                    posts.incrementAndGet()
                    source[id] = JSONObject(body.toString()).put("deleted_at", JSONObject.NULL)
                    createdId.set(id)
                    lastBody.set(body.toString())
                    if (gatePost.compareAndSet(true, false)) {
                        postEntered.countDown()
                        if (!releasePost.await(18, TimeUnit.SECONDS)) return json("{}", 503)
                    }
                    return json(JSONArray().put(source.getValue(id)).toString())
                }
                val id = url.queryParameter("id")?.removePrefix("eq.") ?: return reject()
                val source = when (table) { "categories" -> categories; "budgets" -> budgets; "transactions" -> transactions; else -> return reject() }
                val row = source[id] ?: return reject()
                if (row.getString("user_id") != actor) return reject()
                if (request.method == "DELETE") {
                    if (table != "budgets") return reject()
                    deletes.incrementAndGet()
                    source.remove(id)
                    return json(JSONArray().put(row).toString())
                }
                if (request.method != "PATCH") return reject()
                patches.incrementAndGet()
                if (table == "transactions") {
                    if (url.queryParameter("category_id") != "eq.$CAT" || row.optString("category_id") != CAT ||
                        body.keys().asSequence().toSet() != setOf("category_id") || !body.isNull("category_id")) return reject()
                    detachAttempts.incrementAndGet()
                    if (id == TX2 && failSecondDetach.get()) return json("{\"message\":\"synthetic detach failure\"}", 503)
                }
                body.keys().forEach { key -> row.put(key, body.get(key)) }
                lastBody.set(body.toString())
                if (table == "categories" && body.has("deleted_at")) {
                    categorySoftDeletes.incrementAndGet()
                    return MockResponse().setResponseCode(204).setHeader("Content-Range", "0-0/1")
                }
                json(JSONArray().put(row).toString())
            }
        }
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun reject(): MockResponse { denied.incrementAndGet(); return json("{}", 403) }
    private fun session(owner: String) = Session(if (owner == A) "category-budget-a" else "category-budget-b",
        "synthetic-refresh-$owner", Long.MAX_VALUE, owner, "fixture@example.test")
    private fun jwtSession(sid: String, signature: String): Session {
        fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        val token = "${encode("{}")}.${encode(JSONObject().put("sub", A).put("session_id", sid).toString())}.$signature"
        return Session(token, "synthetic-refresh-$signature", Long.MAX_VALUE, A, "fixture@example.test")
    }
    private fun category(id: String, owner: String, name: String, type: String = "expense") = JSONObject()
        .put("id", id).put("user_id", owner).put("name", name).put("type", type)
        .put("icon", "📦").put("color", "#22c55e").put("deleted_at", JSONObject.NULL)
    private fun transaction(id: String, owner: String, amount: String) = JSONObject()
        .put("id", id).put("user_id", owner).put("type", "expense").put("amount", amount).put("currency", "EUR")
        .put("category_id", CAT).put("date", "2026-06-01").put("description", "Transazione QA $id")
        .put("exchange_rate_eur", "1").put("deleted_at", JSONObject.NULL)

    companion object {
        private val TODAY = LocalDate.of(2026, 6, 15)
        private const val A = "11111111-1111-4111-8111-111111111111"
        private const val B = "22222222-2222-4222-8222-222222222222"
        private const val CAT = "33333333-3333-4333-8333-333333333333"
        private const val FREE_CAT = "44444444-4444-4444-8444-444444444444"
        private const val INCOME_CAT = "55555555-5555-4555-8555-555555555555"
        private const val B_CAT = "66666666-6666-4666-8666-666666666666"
        private const val TX1 = "77777777-7777-4777-8777-777777777771"
        private const val TX2 = "77777777-7777-4777-8777-777777777772"
        private const val B_TX = "77777777-7777-4777-8777-777777777773"
        private const val BUDGET = "88888888-8888-4888-8888-888888888888"
        private const val SHARE = "99999999-9999-4999-8999-999999999999"
        private const val SID1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"
        private const val SID2 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2"
    }
}
