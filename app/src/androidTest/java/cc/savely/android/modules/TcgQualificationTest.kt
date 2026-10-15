package cc.savely.android.modules

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.visual.CommonVisualArtifacts
import cc.savely.android.visual.CommonVisualContent
import cc.savely.android.visual.CommonVisualProfile
import coil.Coil
import coil.ImageLoader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Real production forms, API and image decoding with synthetic loopback TLS only. */
@RunWith(AndroidJUnit4::class)
class TcgQualificationTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeout: Timeout = Timeout.seconds(300)
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private lateinit var images: ImageLoader
    private lateinit var previousImages: ImageLoader
    private val rows = ConcurrentHashMap<String, JSONObject>()
    private val writes = CopyOnWriteArrayList<Write>()
    private val priceReads = CopyOnWriteArrayList<Pair<String, String>>()
    private val denied = AtomicInteger()
    private val imageReads = AtomicInteger()
    private val listReads = AtomicInteger()
    private val httpSequence = AtomicInteger()
    private val httpCompleted = AtomicInteger()
    private val httpFailed = AtomicInteger()
    private val traffic = CopyOnWriteArrayList<String>()
    private val priceMode = AtomicInteger(1)
    private var hideInput: () -> Unit = {}
    private var diagnosticProfile = CommonVisualProfile.IT_LIGHT
    private var diagnosticScenario = "unmounted"
    private val day = LocalDate.now().toString()
    private data class Write(val method: String, val id: String, val body: JSONObject)
    private val imageUrl get() = server.url("/images/card.png").toString()

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Synthetic TCG qualification")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); dispatcher = fixture(); start() }
        val origin = server.url("/")
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .eventListenerFactory { call ->
                val id = httpSequence.incrementAndGet()
                val scope = httpScope(call)
                object : EventListener() {
                    override fun callStart(call: Call) { traffic.add("$id START $scope") }
                    override fun responseHeadersEnd(call: Call, response: Response) { traffic.add("$id HEADERS status=${response.code}") }
                    override fun callEnd(call: Call) { httpCompleted.incrementAndGet(); traffic.add("$id END") }
                    override fun callFailed(call: Call, ioe: IOException) {
                        httpFailed.incrementAndGet(); traffic.add("$id FAILED ${ioe.javaClass.simpleName}")
                    }
                }
            }
            .addInterceptor { chain ->
                val url = chain.request().url
                if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port) {
                    denied.incrementAndGet(); throw IOException("TCG fixture refuses foreign transport")
                }
                chain.proceed(chain.request())
            }.build()
        api = SupabaseApi(BackendConfig(origin.toString(), "tcg-public-fixture", origin.toString()),
            MemorySessionStore(Session("tcg-qualification", "unused", Long.MAX_VALUE, OWNER, "tcg@fixture.invalid")), client)
        val context = ApplicationProvider.getApplicationContext<Context>()
        previousImages = Coil.imageLoader(context)
        images = ImageLoader.Builder(context).okHttpClient(client).build()
        Coil.setImageLoader(images)
    }

    @After fun cleanup() {
        Coil.setImageLoader(previousImages); images.shutdown()
        api.close(); server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }

    @Test fun allThreeCatalogFormsPreserveConditionsLanguagesAndImagesAcrossPositiveNullAndUnavailableQuotes() {
        profiles("catalog", prepare = { reset() }) { en, profile ->
            val categories = listOf(Triple("magic", "Magic", "JP"), Triple("pokemon", "Pokémon", "ZHS"), Triple("yugioh", "Yu-Gi-Oh", "KOR"))
            val conditions = listOf("heavily_played", "damaged", "lightly_played")
            categories.forEachIndexed { index, (category, label, language) ->
                if (index != 0) click(button(label))
                ready(en)
                click(button(if (en) "Search CardTrader" else "Cerca su CardTrader"))
                input(if (en) "Card name" else "Nome carta", "QA $category")
                compose.onNode(button(if (en) "Search" else "Cerca") and hasAnyAncestor(isDialog())).performClick()
                val choice = button("QA $category · qa set")
                await("Catalog result is available") { compose.onAllNodes(choice).fetchSemanticsNodes().size == 1 }
                compose.onNode(choice).performScrollTo().performClick()
                compose.waitForIdle()
                CatalogRepository.CONDITIONS.forEach { condition ->
                    compose.onNode(button(condition.replace('_', ' '))).performScrollTo().assertExists()
                }
                listOf("EN", "IT", "JP", "DE", "FR", "ES", "PT", "KOR", "ZHS").forEach { code ->
                    compose.onNode(button(code)).performScrollTo().assertExists()
                }
                compose.onNode(button(conditions[index].replace('_', ' '))).performScrollTo().performClick().assertIsSelected()
                compose.onNode(button(language)).performScrollTo().performClick().assertIsSelected()
                input(if (en) "Quantity" else "Quantità", "0")
                input(if (en) "Purchase price per unit (€)" else "Prezzo acquisto unitario (€)", "12,50")
                dialogSave(en)
                compose.onNodeWithText(if (en) "The minimum quantity is 1" else "La quantità minima è 1").performScrollTo().assertIsDisplayed()
                assertEquals(index, writes.size); assertEquals(index, priceReads.size)
                input(if (en) "Quantity" else "Quantità", (index + 2).toString())
                priceMode.set(if (index == 0) 1 else if (index == 1) 0 else 503)
                dialogSave(en)
                awaitClosed(en)
                val saved = writes.last()
                assertEquals("POST", saved.method); assertEquals(OWNER, saved.body.getString("user_id"))
                assertEquals(category, saved.body.getString("category")); assertEquals("QA $category", saved.body.getString("name"))
                assertEquals("42", saved.body.getString("card_id")); assertEquals("qa set", saved.body.getString("set_code"))
                assertEquals(language, saved.body.getString("language")); assertEquals(conditions[index], saved.body.getString("condition"))
                assertEquals(day, saved.body.getString("purchase_date")); assertEquals(imageUrl, saved.body.getString("image_url"))
                number(saved.body, "quantity", (index + 2).toString()); number(saved.body, "purchase_price", "12.5")
                if (index == 0) number(saved.body, "current_price", "12.5") else assertTrue(saved.body.isNull("current_price"))
                assertEquals("42" to conditions[index], priceReads.last())
                val image = hasContentDescription(if (en) "Cover of QA $category" else "Copertina di QA $category")
                show(image)
                await("The actual production AsyncImage has decoded the local magenta PNG") {
                    val bitmap = compose.onNode(image).captureToImage().asAndroidBitmap()
                    try { bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) == PixelColor.MAGENTA } finally { bitmap.recycle() }
                }
                if (index == 2) capture("catalog-created-image", profile, image)
            }
            assertEquals(3, writes.size); assertEquals(3, priceReads.size); assertTrue(imageReads.get() > 0)
            val removedId = writes.last().id
            val otherCategories = rows.filterKeys { it != removedId }.mapValues { it.value.toString() }
            click(button(if (en) "Delete" else "Elimina"))
            compose.onNode(button(if (en) "Cancel" else "Annulla") and hasAnyAncestor(isDialog())).performClick()
            compose.waitForIdle()
            assertEquals(3, writes.size); assertTrue(rows.containsKey(removedId))
            click(button(if (en) "Delete" else "Elimina"))
            compose.onNode(button(if (en) "Delete" else "Elimina") and hasAnyAncestor(isDialog())).performClick()
            await("Only the confirmed Yugioh UUID is removed") { !rows.containsKey(removedId) && compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
            ready(en)
            assertEquals(4, writes.size); assertEquals("DELETE", writes.last().method); assertEquals(removedId, writes.last().id)
            assertEquals(otherCategories, rows.mapValues { it.value.toString() })
            assertEquals(0, denied.get())
        }
    }

    @Test fun emptyOverviewAndQuantityPieRemainHonestWhenMarketValueWeightsAreCompletelyDifferent() {
        profiles("overview", prepare = { reset() }) { en, profile ->
            ready(en); click(button(if (en) "Overview" else "Panoramica"))
            val empty = if (en) "The collection is empty." else "La collezione è vuota."
            show(hasText(empty)); capture("empty-overview", profile, hasText(empty))
            show(hasText(if (en) "Add cards to see the distribution" else "Aggiungi carte per vedere la distribuzione"))
            compose.onAllNodesWithTag("overview-total-EUR").assertCountEquals(0)
            compose.onAllNodesWithTag("tcg-game-distribution-magic").assertCountEquals(0)
            listOf(Triple("magic", 90, 1), Triple("pokemon", 9, 100), Triple("yugioh", 1, 10000)).forEachIndexed { index, (category, quantity, current) ->
                val id = UUID.nameUUIDFromBytes("tcg-quantity-$index".toByteArray()).toString()
                rows[id] = card(id, category, "QA $category", "EUR").put("quantity", quantity).put("purchase_price", 1).put("current_price", current)
            }
            click(button(if (en) "Update" else "Aggiorna"))
            val total = hasAnyAncestor(hasTestTag("overview-total-EUR"))
            show(total and hasText(if (en) "Investment: 100,00\u00a0€" else "Investimento: 100,00\u00a0€"))
            show(total and hasText(if (en) "Value: 10.990,00\u00a0€" else "Valore: 10.990,00\u00a0€"))
            listOf(Triple("magic", "Magic", "90"), Triple("pokemon", "Pokémon", "9"), Triple("yugioh", "Yu-Gi-Oh", "1")).forEach { (game, name, quantity) ->
                val expected = if (en) "$name: $quantity pieces · $quantity.0%" else "$name: $quantity pezzi · $quantity,0%"
                show(hasTestTag("tcg-game-distribution-$game")); compose.onNodeWithTag("tcg-game-distribution-$game").assertTextEquals(expected)
            }
            val graphic = hasContentDescription(if (en) "Distribution by Game" else "Distribuzione per Gioco")
            capture("quantity-pie", profile, graphic)
            capture("quantity-pie-details", profile, hasTestTag("tcg-game-distribution-pokemon"))
            click(button("Magic")); ready(en)
            input(if (en) "Search by name or set" else "Cerca per nome o set", "absent synthetic name")
            show(hasText(if (en) "No items match the filters. Use Reset filters to show the collection."
                else "Nessun elemento corrisponde ai filtri. Usa Azzera filtri per mostrare la collezione."))
            compose.onAllNodesWithText("QA magic", substring = true).assertCountEquals(0)
            click(button(if (en) "Reset filters" else "Azzera filtri"))
            show(hasText(if (en) "Card name: QA magic" else "Nome carta: QA magic"))
            assertTrue(writes.isEmpty()); assertTrue(priceReads.isEmpty()); assertEquals(0, denied.get())
        }
    }

    @Test fun manualUsdPricePreservesItsUnitAndOnlyEuroCardCanUseTheAutomaticService() {
        profiles("manual", prepare = {
            reset()
            rows[USD] = card(USD, "magic", "QA USD", "USD").put("quantity", 2).put("purchase_price", 10)
                .put("current_price", 12.5).put("card_id", "42").put("image_url", imageUrl).put("fixture_metadata", "preserved")
            rows[EUR] = card(EUR, "magic", "QA EUR", "EUR").put("purchase_price", 5).put("current_price", JSONObject.NULL).put("card_id", "43")
        }) { en, profile ->
            ready(en)
            val auto = if (en) "Refresh CardTrader price" else "Aggiorna prezzo CardTrader"
            val usdAuto = inRow("QA USD", button(auto))
            show(usdAuto); compose.onNode(usdAuto).assertIsNotEnabled()
            assertTrue(priceReads.isEmpty())
            click(inRow("QA USD", button(if (en) "Edit" else "Modifica")))
            input(if (en) "Current price per unit (USD) (optional)" else "Prezzo attuale unitario (USD) (facoltativo)", "13,75")
            dialogSave(en); awaitClosed(en)
            assertEquals(1, writes.size); assertEquals("PATCH", writes.single().method); assertEquals(USD, writes.single().id)
            assertFalse(writes.single().body.has("currency")); assertFalse(writes.single().body.has("image_url")); assertFalse(writes.single().body.has("card_id"))
            assertEquals("USD", rows.getValue(USD).getString("currency")); assertEquals(imageUrl, rows.getValue(USD).getString("image_url"))
            assertEquals("preserved", rows.getValue(USD).getString("fixture_metadata")); number(rows.getValue(USD), "current_price", "13.75")
            assertTrue(priceReads.isEmpty())
            val unchangedUsd = rows.getValue(USD).toString()
            val unchangedEuro = rows.getValue(EUR).toString()
            for (mode in listOf(2, 3, 4, 5)) {
                priceMode.set(mode)
                val readsBefore = priceReads.size
                click(inRow("QA EUR", button(auto)))
                quoteHeader() // Add is a Lazy header; keep the original enabled/read-count predicate observable.
                await("Exactly one invalid quote request has completed before inspecting its error") {
                    priceReads.size == readsBefore + 1 && enabled(button(if (en) "Add" else "Aggiungi"))
                }
                val message = if (mode == 4) {
                    if (en) "Invalid received price" else "Prezzo ricevuto non valido"
                } else if (en) "Invalid prices response" else "Risposta prezzi non valida"
                show(hasText(message))
                assertEquals(1, writes.size)
                assertEquals(unchangedEuro, rows.getValue(EUR).toString())
                assertEquals(unchangedUsd, rows.getValue(USD).toString())
            }
            priceMode.set(0)
            click(inRow("QA EUR", button(auto)))
            show(hasText(if (en) "No price is available for this card" else "Nessun prezzo disponibile per questa carta"))
            assertEquals(1, writes.size); assertTrue(rows.getValue(EUR).isNull("current_price"))
            priceMode.set(1)
            click(inRow("QA EUR", button(auto)))
            quoteHeader()
            await("One confirmed automatic price PATCH") { writes.size == 2 && enabled(button(if (en) "Add" else "Aggiungi")) }
            assertEquals(EUR, writes.last().id); assertEquals(setOf("current_price"), writes.last().body.keys().asSequence().toSet())
            number(rows.getValue(EUR), "current_price", "12.5")
            assertEquals(List(6) { "43" to "near_mint" }, priceReads.toList())
            show(usdAuto); compose.onNode(usdAuto).assertIsNotEnabled()
            capture("usd-manual-eur-automatic", profile, usdAuto)
            assertEquals(0, denied.get())
        }
    }

    private fun reset() {
        rows.clear(); writes.clear(); priceReads.clear(); denied.set(0); imageReads.set(0); priceMode.set(1)
        listReads.set(0)
        images.memoryCache?.clear(); images.diskCache?.clear()
    }
    private fun card(id: String, category: String, name: String, currency: String) = JSONObject().put("id", id).put("user_id", OWNER)
        .put("category", category).put("name", name).put("currency", currency).put("set_code", "qa set").put("collector_number", JSONObject.NULL)
        .put("card_id", JSONObject.NULL).put("image_url", JSONObject.NULL).put("condition", "near_mint").put("language", "EN")
        .put("quantity", 1).put("purchase_price", 1).put("current_price", 1).put("purchase_date", day).put("notes", JSONObject.NULL)
        .put("created_at", "2026-06-15T00:00:00Z")
    private fun json(code: Int, body: Any) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun reject(): MockResponse { denied.incrementAndGet(); return json(403, JSONObject()) }
    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return reject()
            if (url.encodedPath == "/images/card.png") {
                if (request.method != "GET" || url.queryParameterNames.isNotEmpty() || request.getHeader("Authorization") != null || request.getHeader("apikey") != null) return reject()
                imageReads.incrementAndGet()
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(PixelColor.MAGENTA) }
                val bytes = ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle(); stream.toByteArray() }
                return MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
            }
            if (request.getHeader("Authorization") != "Bearer tcg-qualification" || request.getHeader("apikey") != "tcg-public-fixture") return reject()
            if (url.encodedPath == "/functions/v1/cardtrader-proxy") {
                if (request.method != "GET") return reject()
                return when (url.queryParameter("action")) {
                    "search" -> {
                        if (url.queryParameterNames != setOf("action", "name")) return reject()
                        val name = url.queryParameter("name")?.takeIf { it in setOf("QA magic", "QA pokemon", "QA yugioh") } ?: return reject()
                        json(200, JSONArray().put(JSONObject().put("id", "42").put("name", name).put("set", JSONObject().put("name", "qa set")).put("image", imageUrl)))
                    }
                    "prices" -> {
                        if (url.queryParameterNames != setOf("action", "id", "condition")) return reject()
                        val id = url.queryParameter("id")?.takeIf { it in setOf("42", "43") } ?: return reject()
                        val condition = url.queryParameter("condition")?.takeIf { it in CatalogRepository.CONDITIONS } ?: return reject()
                        priceReads.add(id to condition)
                        if (priceMode.get() == 503) json(503, JSONObject()) else {
                            val mode = priceMode.get()
                            val dto = JSONObject().put("quoteContractVersion", 2)
                                .put("blueprintId", if (mode == 3) "44" else id)
                                .put("currency", if (mode == 2) "USD" else "EUR").put("minorUnit", 2)
                                .put("ctZeroLowPriceCents", if (mode == 0) JSONObject.NULL else if (mode == 4) 0.5 else 1250)
                                .put("totalListings", if (mode == 0) 0 else 1)
                            if (mode == 5) dto.remove("quoteContractVersion")
                            json(200, dto)
                        }
                    }
                    else -> reject()
                }
            }
            if (url.encodedPath != "/rest/v1/tgc_cards") return reject()
            if (request.method == "GET") {
                if (url.queryParameter("user_id") != "eq.$OWNER" || url.queryParameter("limit") != "500") return reject()
                val category = url.queryParameter("category")?.removePrefix("eq.")
                if (category != null && category !in CollectionKind.TCG.categories) return reject()
                val expected = setOf("user_id", "order", "limit", "offset") + if (category == null) emptySet() else setOf("category")
                if (url.queryParameterNames != expected || url.queryParameter("order") != if (category == null) "id.asc" else "created_at.desc") return reject()
                val offset = url.queryParameter("offset")?.toIntOrNull()?.takeIf { it >= 0 } ?: return reject()
                listReads.incrementAndGet()
                return json(200, JSONArray(rows.values.filter { category == null || it.getString("category") == category }.sortedBy { it.getString("id") }.drop(offset).take(500)))
            }
            if (request.method == "POST") {
                val body = JSONObject(request.body.readUtf8()); val id = body.optString("id")
                if (url.queryParameterNames.isNotEmpty() || body.optString("user_id") != OWNER || body.optString("category") !in CollectionKind.TCG.categories ||
                    runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not() || rows.containsKey(id)) return reject()
                val saved = JSONObject(body.toString()).put("currency", "EUR").put("created_at", "2026-06-15T00:00:00Z")
                rows[id] = saved; writes.add(Write("POST", id, body)); return json(201, JSONArray().put(saved))
            }
            if (request.method !in setOf("PATCH", "DELETE") || url.queryParameterNames != setOf("id")) return reject()
            val id = url.queryParameter("id")?.removePrefix("eq.") ?: return reject()
            val existing = rows[id]?.takeIf { it.getString("user_id") == OWNER } ?: return reject()
            if (request.method == "DELETE") {
                rows.remove(id); writes.add(Write("DELETE", id, JSONObject())); return json(200, JSONArray().put(existing))
            }
            val body = JSONObject(request.body.readUtf8())
            if (body.has("id") || body.has("user_id") || body.has("currency")) return reject()
            body.keys().forEach { existing.put(it, body.get(it)) }
            writes.add(Write("PATCH", id, body)); return json(200, JSONArray().put(existing))
        }
    }

    private fun profiles(scenario: String, prepare: () -> Unit, verify: (Boolean, CommonVisualProfile) -> Unit) {
        diagnosticScenario = scenario
        val active = mutableStateOf<CommonVisualProfile?>(null)
        compose.setContent { active.value?.let { profile -> key(profile) { CommonVisualContent(profile) {
            val focus = LocalFocusManager.current; val keyboard = LocalSoftwareKeyboardController.current
            SideEffect { hideInput = { focus.clearFocus(force = true); keyboard?.hide() } }
            ModuleScreen(api, "tcg", Modifier.fillMaxSize())
        } } } }
        listOf(CommonVisualProfile.IT_LIGHT, CommonVisualProfile.EN_DARK_LARGE).forEach { profile ->
            diagnosticProfile = profile
            prepare(); compose.runOnIdle { active.value = profile }; compose.waitForIdle()
            try { verify(profile == CommonVisualProfile.EN_DARK_LARGE, profile) }
            catch (failure: Throwable) {
                throw AssertionError("TCG $scenario ${profile.suffix} failed\n${diagnostics()}", failure)
            }
            finally { compose.runOnIdle { hideInput(); active.value = null }; compose.waitForIdle(); hideInput = {} }
        }
    }
    private fun number(body: JSONObject, key: String, expected: String) { assertTrue(body.get(key) is Number); assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(body.get(key).toString()))) }
    private fun button(label: String) = hasText(label) and hasClickAction()
    private fun inRow(name: String, matcher: SemanticsMatcher): SemanticsMatcher {
        val other = if (name == "QA USD") "QA EUR" else "QA USD"
        return matcher and hasAnyAncestor(hasAnyDescendant(hasText(name, substring = true)) and
            !hasAnyDescendant(hasText(other, substring = true)))
    }
    private fun enabled(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
    private fun await(label: String, condition: () -> Boolean) { try { compose.waitUntil(15_000, condition) } catch (failure: ComposeTimeoutException) { throw AssertionError("$label; writes=${writes.size}, priceReads=${priceReads.size}, imageReads=${imageReads.get()}, denied=${denied.get()}", failure) } }
    private fun quoteHeader() {
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.waitForIdle()
    }
    private fun httpScope(call: Call): String {
        val request = call.request()
        val url = request.url
        val route = when (url.encodedPath) {
            "/rest/v1/tgc_cards" -> "cards"
            "/functions/v1/cardtrader-proxy" -> "proxy"
            "/images/card.png" -> "image"
            else -> "OTHER"
        }
        val action = url.queryParameter("action")?.takeIf { it in setOf("search", "prices") }
        val category = url.queryParameter("category")?.takeIf { it in CollectionKind.TCG.categories.map { "eq.$it" } }
        val offset = url.queryParameter("offset")?.toIntOrNull()
        val ownerBound = url.queryParameter("user_id") == "eq.$OWNER"
        return "profile=${diagnosticProfile.suffix} method=${request.method} route=$route action=$action category=$category offset=$offset ownerBound=$ownerBound"
    }
    private fun diagnostics(): String {
        val headers = runCatching {
            listOf("Aggiungi", "Add", "Aggiorna", "Update").joinToString("\n") { label ->
                val nodes = compose.onAllNodes(button(label)).fetchSemanticsNodes()
                "$label nodes=${nodes.size} " + nodes.take(3).joinToString { node ->
                    "disabled=${node.config.contains(SemanticsProperties.Disabled)} bounds=${node.boundsInRoot} " +
                        "position=${node.positionInRoot} size=${node.size} " +
                        "placed=${generateSequence(node.layoutInfo) { it.parentInfo }.map { it.isPlaced }.toList()}"
                }
            }
        }.getOrElse { "Header diagnostic unavailable: ${it.javaClass.simpleName}" }
        val roots = runCatching {
            val collection = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            collection.fetchSemanticsNodes().indices.take(4).joinToString("\n") { index ->
                runCatching { collection[index].printToString().take(16_000) }
                    .getOrElse { "Root unavailable: ${it.javaClass.simpleName}" }
            }
        }.getOrElse { "Semantics unavailable: ${it.javaClass.simpleName}" }
        // Synthetic fixtures only. Capture diagnostics must never replace the original failure.
        val image = runCatching {
            CommonVisualArtifacts.capture(compose, "diagnostic-v12-$diagnosticScenario-${diagnosticProfile.suffix}",
                diagnosticProfile, clockInjected = false).name
        }.getOrElse { "Capture unavailable: ${it.javaClass.simpleName}" }
        return "writes=${writes.size} priceReads=${priceReads.size} listReads=${listReads.get()} " +
            "httpCompleted=${httpCompleted.get()} httpFailed=${httpFailed.get()} imageReads=${imageReads.get()} denied=${denied.get()}\n" +
            "$headers\nHTTP (START/HEADERS/END; no headers, bodies or search terms):\n${traffic.takeLast(100).joinToString("\n")}\n$roots\n$image"
    }
    private fun ready(en: Boolean) { show(button(if (en) "Add" else "Aggiungi")); await("Enabled collection header") { enabled(button(if (en) "Add" else "Aggiungi")) } }
    private fun input(label: String, value: String) { compose.onNodeWithText(label).performScrollTo().performTextReplacement(value); compose.waitForIdle() }
    private fun dialogSave(en: Boolean) { compose.runOnIdle { hideInput() }; compose.onNode(button(if (en) "Save" else "Salva") and hasAnyAncestor(isDialog())).performClick(); compose.waitForIdle() }
    private fun awaitClosed(en: Boolean) { await("Form closed") { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }; ready(en) }
    private fun click(matcher: SemanticsMatcher) { compose.runOnIdle { hideInput() }; show(matcher); compose.onNode(matcher).assertIsEnabled().performClick(); compose.waitForIdle() }
    private fun bounds(matcher: SemanticsMatcher): Rect { val node = compose.onNode(matcher).fetchSemanticsNode(); return Rect(node.positionInRoot.x, node.positionInRoot.y, node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height) }
    private fun show(matcher: SemanticsMatcher) {
        val present = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
        if (generateSequence(present?.parent) { it.parent }.any { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }) { compose.onNode(matcher).performScrollTo().assertIsDisplayed(); return }
        val list = compose.onNode(hasScrollToIndexAction())
        await("Refreshed target is present") { try { list.performScrollToNode(matcher); true } catch (failure: AssertionError) {
            val expected = "No node found that matches ${matcher.description} in scrollable container"
            if (failure.message != expected && failure.message?.startsWith("$expected\nSemantics of the node:\n") != true) throw failure
            false
        } }
        compose.waitForIdle()
        repeat(10) {
            val target = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
            if (target == null) { list.performScrollToNode(matcher); compose.waitForIdle() } else {
                val rect = bounds(matcher); var viewport = bounds(hasTestTag(CommonVisualArtifacts.VIEWPORT))
                generateSequence(target.parent) { it.parent }.filter { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) || it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }.forEach { viewport = viewport.intersect(it.boundsInRoot) }
                assertTrue("Target fits its actual scroll viewport", rect.width <= viewport.width && rect.height <= viewport.height)
                if (rect.left >= viewport.left && rect.right <= viewport.right && rect.top >= viewport.top && rect.bottom <= viewport.bottom) { compose.onNode(matcher).assertIsDisplayed(); return }
                list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, (rect.center.y - viewport.center.y).coerceIn(-viewport.height / 3f, viewport.height / 3f)) }; compose.waitForIdle()
            }
        }
        throw AssertionError("TCG target did not fully enter its viewport: $matcher")
    }
    private fun capture(name: String, profile: CommonVisualProfile, matcher: SemanticsMatcher) { show(matcher); CommonVisualArtifacts.capture(compose, "v12-$name-${profile.suffix}", profile, clockInjected = false) }
    private companion object {
        const val OWNER = "51000000-0000-4000-8000-000000000001"
        const val USD = "51000000-0000-4000-8000-000000000002"
        const val EUR = "51000000-0000-4000-8000-000000000003"
    }
}
