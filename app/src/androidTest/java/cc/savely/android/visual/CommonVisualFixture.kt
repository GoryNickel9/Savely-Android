package cc.savely.android.visual

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.LocaleList
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.ui.AppLanguage
import cc.savely.android.ui.LanguagePreferences
import cc.savely.android.ui.LocalizationProvider
import cc.savely.android.ui.SavelyTheme
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

enum class CommonVisualProfile(val widthDp: Int, val heightDp: Int, val fontScale: Float,
    val language: AppLanguage, val theme: String, val suffix: String) {
    IT_LIGHT(360, 800, 1f, AppLanguage.ITALIAN, "light", "it-light-360x800-font100"),
    EN_DARK_LARGE(320, 800, 2f, AppLanguage.ENGLISH, "dark", "en-dark-320x800-font200"),
}

/** The viewport is constrained by the real Activity. Metadata records its actual size, not a mock device. */
@Composable
fun CommonVisualContent(profile: CommonVisualProfile = CommonVisualProfile.IT_LIGHT, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context, profile) {
        LanguagePreferences(context.getSharedPreferences("common_visual_language_${profile.language.tag}", Context.MODE_PRIVATE))
            .apply { write(profile.language) }
    }
    val physicalDensity = LocalDensity.current.density
    val original = LocalConfiguration.current
    val configuration = remember(original, profile) { Configuration(original).apply {
        screenWidthDp = profile.widthDp; screenHeightDp = profile.heightDp; fontScale = profile.fontScale
        setLocales(LocaleList(Locale.forLanguageTag(profile.language.tag)))
    } }
    CompositionLocalProvider(LocalDensity provides Density(physicalDensity, profile.fontScale),
        LocalConfiguration provides configuration) {
        LocalizationProvider(preferences) {
            SavelyTheme(profile.theme) {
                Box(Modifier.size(profile.widthDp.dp, profile.heightDp.dp).testTag(CommonVisualArtifacts.VIEWPORT)) {
                    Surface(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }
}

object CommonVisualData {
    fun load(): JSONObject = InstrumentationRegistry.getInstrumentation().context.assets
        .open("static-common-dataset.json").bufferedReader(Charsets.UTF_8).use { reader ->
            val text = reader.readText()
            require(text.length <= 1_000_000) { "Synthetic visual dataset exceeds its fixture limit" }
            JSONObject(text).also { require(it.getInt("schemaVersion") == 1) }
        }

    fun moduleCase(dataset: JSONObject, id: String): JSONObject {
        val cases = dataset.getJSONObject("modules").getJSONArray("cases")
        return (0 until cases.length()).map { cases.getJSONObject(it) }.single { it.getString("id") == id }.getJSONObject("input")
    }
}

object CommonVisualArtifacts {
    const val VIEWPORT = "visual-viewport"

    fun capture(compose: ComposeContentTestRule, name: String,
        profile: CommonVisualProfile = CommonVisualProfile.IT_LIGHT, clockInjected: Boolean = false): File {
        require(Regex("[a-z0-9][a-z0-9_-]{0,119}").matches(name)) { "Invalid synthetic screenshot name" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val display = realDisplayMetrics(context)
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag(VIEWPORT)
        compose.waitUntil(10_000) {
            val candidate = viewport.fetchSemanticsNode()
            val rect = candidate.boundsInWindow
            rect.left.isFinite() && rect.top.isFinite() && rect.right.isFinite() && rect.bottom.isFinite() &&
                rect.left >= 0f && rect.top >= 0f && rect.right <= display.widthPixels && rect.bottom <= display.heightPixels &&
                candidate.size.width > 0 && candidate.size.height > 0 &&
                abs(rect.width - candidate.size.width) <= 1f && abs(rect.height - candidate.size.height) <= 1f
        }
        val node = viewport.fetchSemanticsNode()
        val rootBounds = node.boundsInRoot
        val windowBounds = node.boundsInWindow
        val image = viewport.captureToImage().asAndroidBitmap()
        val directory = File(context.filesDir, "common-dataset-visual").apply { check(mkdirs() || isDirectory) }
        val file = File(directory, "$name.png")
        file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val density = context.resources.displayMetrics.density
        val metadata = JSONObject().put("image", file.name).put("profile", profile.suffix)
            .put("requestedWidthDp", profile.widthDp).put("requestedHeightDp", profile.heightDp)
            .put("actualWidthPx", image.width).put("actualHeightPx", image.height).put("physicalDensity", density)
            .put("displayWidthPx", display.widthPixels).put("displayHeightPx", display.heightPixels)
            .put("boundsInRootPx", bounds(rootBounds)).put("boundsInWindowPx", bounds(windowBounds))
            .put("actualWidthDp", image.width / density).put("actualHeightDp", image.height / density)
            .put("fontScale", profile.fontScale.toDouble()).put("language", profile.language.tag).put("theme", profile.theme)
            .put("sdk", Build.VERSION.SDK_INT).put("device", Build.MODEL)
            .put("inputClock", CommonVisualData.load().getString("clock")).put("clockInjected", clockInjected)
            .put("clockInjectionScope", if (clockInjected) "date/year input only; runtime timestamps remain live"
                else "fixture record dates only; device clock remains live")
            .put("scope", "native-composable-local-synthetic-fixture")
            .put("backendPolicyVerified", false).put("browserParityVerified", false)
        File(directory, "$name.json").writeText(metadata.toString(2), Charsets.UTF_8)
        check(file.length() > 0)
        return file
    }

    private fun bounds(rect: Rect): JSONObject = JSONObject().put("left", rect.left.toDouble())
        .put("top", rect.top.toDouble()).put("right", rect.right.toDouble()).put("bottom", rect.bottom.toDouble())

    @Suppress("DEPRECATION")
    private fun realDisplayMetrics(context: Context): DisplayMetrics = DisplayMetrics().also {
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(it)
    }
}

/** Real API transport with in-memory credentials; only guarded loopback reads may reach the server. */
class CommonVisualBackend(dataset: JSONObject, actorAlias: String,
    handler: (RecordedRequest) -> MockResponse?) : AutoCloseable {
    val rejected = AtomicInteger()
    val allowedRequests = AtomicInteger()
    val actor: JSONObject = dataset.getJSONObject("account").getJSONObject("actors").getJSONObject(actorAlias)
    val session: Session
    val api: SupabaseApi
    private val client: OkHttpClient
    private val server: MockWebServer

    init {
        require(actorAlias in setOf("A", "B", "Admin"))
        val certificate = HeldCertificate.Builder().commonName("Synthetic common visual fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val sid = UUID.nameUUIDFromBytes("common-visual-$actorAlias".toByteArray(Charsets.UTF_8)).toString()
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            JSONObject().put("session_id", sid).put("aal", "aal2").toString().toByteArray(Charsets.UTF_8))
        session = Session("synthetic.$payload.signature", "synthetic-unused-refresh", Long.MAX_VALUE,
            actor.getString("id"), "visual-${actorAlias.lowercase(Locale.ROOT)}@dataset.invalid")
        server = MockWebServer().apply {
            useHttps(tls.sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    val read = request.method == "GET" && (path.startsWith("/rest/v1/") || path == "/auth/v1/user")
                    val readRpc = request.method == "POST" && path in setOf("/rest/v1/rpc/get_active_sessions", "/rest/v1/rpc/get_registrations_enabled") && request.body.clone().readUtf8() == "{}"
                    val readAdmin = request.method == "POST" && path == "/api/admin" && request.body.clone().readUtf8() == "{\"action\":\"get-users\"}"
                    if ((!read && !readRpc && !readAdmin) || request.getHeader("Authorization") != "Bearer ${session.accessToken}") {
                        rejected.incrementAndGet(); return json("{}", 403)
                    }
                    val response = handler(request)
                    return response?.also { allowedRequests.incrementAndGet() }
                        ?: json("{}", 404).also { rejected.incrementAndGet() }
                }
            }
            start()
        }
        val origin = server.url("/")
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val url = chain.request().url
                if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port) {
                    rejected.incrementAndGet(); throw IOException("Synthetic visual fixture refuses foreign transport")
                }
                chain.proceed(chain.request())
            }.build()
        api = SupabaseApi(BackendConfig(origin.toString(), "synthetic-public-key", origin.toString()), MemorySessionStore(session), client)
        runBlocking { check(api.session() == session) }
    }

    override fun close() {
        api.close(); server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }

    companion object {
        fun json(body: String, code: Int = 200): MockResponse = MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json").setBody(body)
    }
}
