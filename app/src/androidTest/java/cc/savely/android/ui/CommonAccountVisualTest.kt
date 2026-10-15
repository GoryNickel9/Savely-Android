package cc.savely.android.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.data.authSessionId
import cc.savely.android.finance.formatMoney
import cc.savely.android.visual.*
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.UUID

/** Native screens, synthetic master inputs and loopback-only reads. No deployed authorization claim. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class CommonAccountVisualTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private lateinit var dataset: JSONObject
    private var backend: CommonVisualBackend? = null
    private var activeAlias = "A"
    private val clock get() = dataset.getString("clock")
    private val actors get() = dataset.getJSONObject("account").getJSONObject("actors")
    private val resources get() = dataset.getJSONObject("account").getJSONObject("resources")

    @Before fun loadInputs() { dataset = CommonVisualData.load() }
    @After fun cleanup() {
        backend?.let { fixture ->
            try { assertEquals("A visual fixture must never submit an unplanned request", 0, fixture.rejected.get()) }
            finally { fixture.close() }
        }
    }

    @Test fun loginItalianLightUsesTheRealDisabledForm() = login(CommonVisualProfile.IT_LIGHT)
    @Test fun loginEnglishDarkAtTwoHundredPercentUsesScrollableRealForm() = login(CommonVisualProfile.EN_DARK_LARGE)

    private fun login(profile: CommonVisualProfile) {
        var submitted = false
        compose.setContent { CommonVisualContent(profile) {
            AuthScreen(AppState(initialized = true), { _, _ -> submitted = true }, { _, _, _ -> submitted = true },
                { submitted = true }, { submitted = true })
        } }
        val label = if (profile.language == AppLanguage.ENGLISH) "Sign in" else "Accedi"
        compose.onNode(hasText(label) and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Email", substring = false).assertExists()
        compose.onNodeWithText("Password", substring = false).assertExists()
        compose.onNodeWithText("Savely", substring = false).performScrollTo().assertIsDisplayed()
        capture("login", profile)
        if (profile.fontScale > 1f) {
            compose.onNode(hasText(label) and hasClickAction()).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            capture("login-submit", profile)
        }
        compose.runOnIdle { assertEquals(false, submitted) }
    }

    @Test fun settingsItalianLightShowsTheSyntheticProfileAndCurrency() {
        openSettings(CommonVisualProfile.IT_LIGHT)
        compose.onNodeWithText("Nome completo", substring = false).assertTextContains(actors.getJSONObject("A").getString("name"))
        compose.onNodeWithText("EUR", substring = false).assertIsSelected()
        compose.onNodeWithText("Profilo", substring = false).assertIsDisplayed()
        capture("settings", CommonVisualProfile.IT_LIGHT)
    }

    @Test fun securityItalianLightShowsFactorsAndCanonicalCurrentSession() = security(CommonVisualProfile.IT_LIGHT)
    @Test fun securityEnglishDarkAtTwoHundredPercentKeepsTheSessionControlsReachable() = security(CommonVisualProfile.EN_DARK_LARGE)

    private fun security(profile: CommonVisualProfile) {
        openSettings(profile)
        val english = profile.language == AppLanguage.ENGLISH
        if (profile.fontScale == 2f) {
            val before = backend!!.allowedRequests.get()
            touchSettingsPane("transfer", requireScroll = true)
            await("Import and export")
            compose.onNodeWithText("Import and export").assertIsDisplayed()
            assertEquals("Opening Data must not start an import or backend operation", before, backend!!.allowedRequests.get())
        }
        touchSettingsPane("security")
        val active = if (english) "Active authenticator" else "Autenticatore attivo"
        await(active)
        compose.onNodeWithText(active, substring = false).assertIsDisplayed()
        scrollSecurity(hasText(if (english) "Incomplete authenticator setup" else "Configurazione autenticatore incompleta"))
        capture("security-factors", profile)
        scrollSecurity(hasText("Synthetic Android A"))
        if (profile.fontScale == 2f) {
            val device = compose.onNodeWithTag("session-device-${backend!!.session.authSessionId()}")
                .assertIsDisplayed().fetchSemanticsNode()
            val viewport = compose.onNodeWithTag(CommonVisualArtifacts.VIEWPORT).fetchSemanticsNode()
            assertTrue("The device name must retain at least 70% of the viewport width at font scale 2",
                device.size.width >= viewport.size.width * 0.70f)
        }
        scrollSecurity(hasText(if (english) "This device" else "Questo dispositivo"))
        compose.onNodeWithTag("revoke-session-${backend!!.session.authSessionId()}").assertIsEnabled()
        capture("security-sessions", profile)
        assertEquals(0, backend!!.rejected.get()) // Enrollment, challenge, DELETE and logout are never allowed.
    }

    private fun openSettings(profile: CommonVisualProfile) {
        val fixture = open("A")
        compose.setContent { CommonVisualContent(profile) {
            SettingsScreen(fixture.api, fixture.session, profile.theme, {}, {}, {})
        } }
        val save = if (profile.language == AppLanguage.ENGLISH) "Save profile" else "Salva profilo"
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(save) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun coupleItalianLightShowsPerCurrencyMediansAndTheBudgetDifference() {
        val fixture = open("B")
        compose.setContent { CommonVisualContent { CoupleScreen(fixture.api) } }
        await("Mediana mensile USD:", substring = true)
        compose.onNodeWithText("Riepilogo budget EUR").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Budget totale: ${money("4.00", "EUR")}").assertIsDisplayed()
        compose.onNodeWithText("Spesa mediana mensile reale: ${money("5.01", "EUR")}").assertIsDisplayed()
        compose.onNodeWithText("Differenza: ${money("1.01", "EUR")}").assertIsDisplayed()
        capture("couple-budget", CommonVisualProfile.IT_LIGHT)
        compose.onNodeWithText("Mediana mensile USD: ${money("2.01", "USD")}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mediana mensile EUR: ${money("5.01", "EUR")}").assertIsDisplayed()
        capture("couple-currencies", CommonVisualProfile.IT_LIGHT)
        compose.onNodeWithText("Mediana mensile EUR: ${money("7.02", "EUR")}").assertDoesNotExist()
    }

    @Test fun adminItalianLightLoadsOnlySyntheticActorsWithoutSendingMutations() {
        val fixture = open("Admin")
        compose.setContent { CommonVisualContent { AdminScreen(fixture.api) } }
        await(actors.getJSONObject("Admin").getString("name"))
        compose.onNodeWithText("Registrazioni abilitate").assertIsDisplayed()
        listOf("A", "B", "Admin").forEach { alias ->
            compose.onNodeWithText(actors.getJSONObject(alias).getString("name"), substring = false).assertIsDisplayed()
        }
        compose.onNodeWithText("Aggiorna utenti").assertIsEnabled()
        capture("admin", CommonVisualProfile.IT_LIGHT)
    }

    @Test fun nonAdminItalianLightShowsTheNativeDenialWithoutAUserList() {
        val fixture = open("A")
        compose.setContent { CommonVisualContent { AdminScreen(fixture.api) } }
        await("Sono necessari i permessi di amministratore.")
        compose.onNodeWithText("Sono necessari i permessi di amministratore.").assertIsDisplayed()
        compose.onNodeWithText("Registrazioni abilitate").assertDoesNotExist()
        compose.onNodeWithText(actors.getJSONObject("B").getString("name")).assertDoesNotExist()
        capture("admin-denied", CommonVisualProfile.IT_LIGHT)
    }

    private fun capture(screen: String, profile: CommonVisualProfile) =
        CommonVisualArtifacts.capture(compose, "account-$screen-${profile.suffix}", profile)
    private fun await(text: String, substring: Boolean = false) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }
    private fun scrollSecurity(matcher: SemanticsMatcher) {
        // Use bounded real gestures instead of ScrollTo's repeated semantic action on a
        // merged, multiline ListItem. Its parent is the production SecurityPane scroller.
        repeat(12) {
            compose.waitForIdle()
            val node = compose.onNode(matcher).fetchSemanticsNode()
            val ancestor = generateSequence(node.parent) { it.parent }
                .first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            val targetY = node.positionInRoot.y + node.size.height / 2f
            val viewport = ancestor.boundsInRoot
            if (compose.onNode(matcher).isDisplayed() && targetY in viewport.top..viewport.bottom) return
            compose.onNode(SemanticsMatcher("Security scroll ancestor") { it.id == ancestor.id })
                .performTouchInput { if (targetY > viewport.center.y) swipeUp() else swipeDown() }
        }
        throw AssertionError("Security scroll target remains outside the viewport: $matcher")
    }
    private fun touchSettingsPane(pane: String, requireScroll: Boolean = false) {
        val target = compose.onNodeWithTag("settings-pane-$pane")
        var previous: Rect? = null
        var stable = 0
        var gestures = 0
        compose.waitUntil(15_000) {
            compose.mainClock.advanceTimeByFrame()
            val node = target.fetchSemanticsNode()
            val ancestor = generateSequence(node.parent) { it.parent }
                .first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
            val centerX = node.positionInRoot.x + node.size.width / 2f
            val viewport = ancestor.boundsInRoot
            if (!target.isDisplayed() || centerX !in viewport.left..viewport.right) {
                check(gestures++ < 8) { "Settings tab is not reachable: $pane" }
                compose.onNode(SemanticsMatcher("Settings tab scroll ancestor") { it.id == ancestor.id })
                    .performTouchInput { if (centerX > viewport.center.x) swipeLeft() else swipeRight() }
                previous = null; stable = 0
                false
            } else {
                val bounds = node.boundsInRoot
                stable = if (bounds == previous) stable + 1 else 0
                previous = bounds
                stable >= 2
            }
        }
        if (requireScroll) assertTrue("Data must be reached using the horizontal Settings scroller", gestures > 0)
        target.assertIsDisplayed().performTouchInput { click(center) }.assertIsSelected()
    }
    private fun money(value: String, currency: String) = formatMoney(BigDecimal(value), currency)
    private fun id(ref: String) = UUID.nameUUIDFromBytes("common-account-$ref".toByteArray(Charsets.UTF_8)).toString()
    private fun open(alias: String): CommonVisualBackend {
        activeAlias = alias
        return CommonVisualBackend(dataset, alias, ::response).also { backend = it }
    }

    private fun response(request: RecordedRequest): okhttp3.mockwebserver.MockResponse? {
        val url = request.requestUrl ?: return null
        val owner = actors.getJSONObject(activeAlias).getString("id")
        fun json(value: Any) = CommonVisualBackend.json(value.toString())
        fun page(rows: JSONArray): okhttp3.mockwebserver.MockResponse {
            val offset = url.queryParameter("offset")?.toIntOrNull() ?: 0
            val limit = url.queryParameter("limit")?.toIntOrNull() ?: 1000
            val cursor = url.queryParameter("id")?.also { require(it.startsWith("gt.")) }?.removePrefix("gt.")
            val ordered = (0 until rows.length()).map { rows.getJSONObject(it) }
                .sortedBy { it.getString("id") }.filter { cursor == null || it.getString("id") > cursor }
            val result = JSONArray()
            ordered.drop(offset).take(limit).forEach(result::put)
            return json(result)
        }
        return when (url.encodedPath) {
            "/rest/v1/profiles" -> if (url.queryParameter("user_id") == "eq.$owner") {
                val actor = actors.getJSONObject(activeAlias)
                json(JSONArray().put(JSONObject().put("id", id("profile-$activeAlias")).put("user_id", owner)
                    .put("full_name", actor.getString("name")).put("default_currency", "EUR")
                    .put("couple_code", actor.optString("coupleCode", ""))
                    .put("permissions", JSONObject(actor.getJSONObject("permissions").toString()))))
            } else null
            "/auth/v1/user" -> json(JSONObject().put("id", owner).put("factors", JSONArray()
                .put(JSONObject().put("id", id("verified-factor")).put("factor_type", "totp").put("status", "verified"))
                .put(JSONObject().put("id", id("pending-factor")).put("factor_type", "totp").put("status", "unverified"))))
            "/rest/v1/rpc/get_active_sessions" -> json(JSONArray()
                .put(JSONObject().put("id", id("activity-current")).put("session_id", backend!!.session.authSessionId())
                    .put("user_agent", "Synthetic Android A").put("created_at", clock))
                .put(JSONObject().put("id", id("activity-other")).put("session_id", id("other-session"))
                    .put("user_agent", "Synthetic Browser A").put("created_at", "2026-06-14T12:00:00+02:00")))
            "/rest/v1/rpc/get_registrations_enabled" -> if (activeAlias == "Admin") json(false) else null
            "/api/admin" -> if (activeAlias == "Admin") {
                val users = JSONArray()
                listOf("A", "B", "Admin").forEach { alias ->
                    val actor = actors.getJSONObject(alias)
                    users.put(JSONObject().put("user_id", actor.getString("id")).put("full_name", actor.getString("name"))
                        .put("email", "visual-${alias.lowercase()}@dataset.invalid").put("permissions", actor.getJSONObject("permissions")))
                }
                json(JSONObject().put("users", users))
            } else null
            "/rest/v1/couple_connections" -> if (url.queryParameter("or") == "(user_a.eq.$owner,user_b.eq.$owner)") {
                page(if (url.queryParameter("revoked_at") == "is.null") JSONArray().put(JSONObject()
                    .put("id", id("connection")).put("user_a", actors.getJSONObject("A").getString("id"))
                    .put("user_b", actors.getJSONObject("B").getString("id")).put("created_at", clock).put("revoked_at", JSONObject.NULL)) else JSONArray())
            } else null
            "/rest/v1/couple_connection_requests" -> if (url.queryParameter("or") == "(sender_id.eq.$owner,receiver_id.eq.$owner)") page(JSONArray()) else null
            "/rest/v1/transactions" -> if (url.queryParameter("user_id") == "eq.$owner") page(JSONArray()) else null
            "/rest/v1/shared_expenses_view" -> if (url.queryParameter("connection_id") == "eq.${id("connection")}") {
                page(JSONArray().put(share("SHARE-EQUAL", "5.01", "5.01")).put(share("SHARE-CUSTOM", "2.01", "4.99")))
            } else null
            "/rest/v1/couple_budgets" -> if (url.queryParameter("connection_id") == "eq.${id("connection")}") {
                val budget = resources.getJSONObject("BUDGET-EUR")
                page(JSONArray().put(JSONObject().put("id", id("budget")).put("connection_id", id("connection"))
                    .put("couple_category_name", budget.getString("category")).put("currency", budget.getString("currency"))
                    .put("amount", budget.getString("amount"))))
            } else null
            else -> null
        }
    }

    private fun share(ref: String, partner: String, creator: String): JSONObject {
        val shared = resources.getJSONObject(ref)
        val txRef = shared.getString("transaction")
        val tx = resources.getJSONObject(txRef)
        return JSONObject().put("id", id(ref)).put("connection_id", id("connection"))
            .put("original_tx_id", id(txRef)).put("created_by", actors.getJSONObject("A").getString("id"))
            .put("total_amount", tx.getString("amount")).put("currency", tx.getString("currency"))
            .put("date", tx.getString("date")).put("description", "Dataset $txRef")
            .put("couple_category_name", tx.getString("category")).put("tx_deleted_at", JSONObject.NULL)
            .put("split_mode", shared.getString("mode")).put("my_share_amount", partner).put("creator_share_amount", creator)
    }
}
