package cc.savely.android.transfer

import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Rejects unexpected paths, account scopes, credentials and every write other than one owned transaction. */
internal class TransferTlsFixture : AutoCloseable {
    val owner = "00000000-0000-4000-8000-000000000001"
    val category = "00000000-0000-4000-8000-000000000010"
    val foreign = JSONObject().put("id", "00000000-0000-4000-8000-000000000099")
        .put("user_id", "00000000-0000-4000-8000-000000000002").put("description", "Foreign private transaction")
        .put("date", "2026-10-10").put("amount", "999.00").put("currency", "EUR").put("type", "expense")
    val foreignSnapshot = foreign.toString()
    val posts = AtomicInteger()
    val reads = AtomicInteger()
    val readTables: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val denied = AtomicInteger()
    val otherMutations = AtomicInteger()
    val postStarted = CountDownLatch(1)
    val releasePost = CountDownLatch(1)
    val postFinished = CountDownLatch(1)
    @Volatile var failTable: String? = null
    @Volatile var ambiguousCommit = false
    @Volatile var gatePost = true
    private val lock = Any()
    private val records = mutableListOf(existing(), foreign)
    private val server = MockWebServer()
    private val client: OkHttpClient
    val store = MemorySessionStore(Session("transfer-fixture-access", "transfer-fixture-refresh",
        System.currentTimeMillis() / 1000 + 3600, owner, "transfer@example.test"))
    val api: SupabaseApi

    init {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return reject()
                val table = url.encodedPath.removePrefix("/rest/v1/")
                if (!url.encodedPath.startsWith("/rest/v1/") || table !in TransferRepository.USER_TABLES ||
                    request.getHeader("Authorization") != "Bearer transfer-fixture-access" ||
                    request.getHeader("apikey") != "transfer-public-fixture") return reject()
                if (request.method == "GET") {
                    val validScope = when (table) {
                        "couple_connections" -> url.queryParameter("or") == "(user_a.eq.$owner,user_b.eq.$owner)"
                        "couple_connection_requests" -> url.queryParameter("or") == "(sender_id.eq.$owner,receiver_id.eq.$owner)"
                        "couple_budgets", "shared_expenses" -> false // No connection exists in this fixture.
                        else -> url.queryParameter("user_id") == "eq.$owner"
                    }
                    if (!validScope || url.queryParameter("select") != "*" || url.queryParameter("order") != "id.asc" ||
                        url.queryParameter("limit") != "500") return reject()
                    if (table in setOf("categories", "transactions") && url.queryParameter("deleted_at")?.let { it != "is.null" } == true) return reject()
                    reads.incrementAndGet()
                    readTables += table
                    if (failTable == table) return json(503, JSONObject().put("error", "synthetic unavailable").toString())
                    val cursor = url.queryParameter("id")?.removePrefix("gt.")
                    val own = when (table) {
                        "categories" -> listOf(JSONObject().put("id", category).put("user_id", owner).put("name", "Spese fixture").put("type", "expense"))
                        "transactions" -> synchronized(lock) { records.map { JSONObject(it.toString()) } }
                        else -> emptyList()
                    }.filter { it.optString("user_id") == owner && (cursor == null || it.getString("id") > cursor) }
                        .sortedBy { it.getString("id") }
                    return json(200, JSONArray(own).toString())
                }
                if (request.method == "POST" && table == "transactions") {
                    val body = runCatching { JSONObject(request.body.readUtf8()) }.getOrElse { return reject() }
                    if (body.optString("user_id") != owner || body.optString("category_id") != category ||
                        body.optString("type") != "expense" || body.optString("currency") !in setOf("EUR", "USD") ||
                        !Regex("[0-9a-fA-F-]{36}").matches(body.optString("id")) ||
                        body.opt("amount")?.toString()?.toBigDecimalOrNull()?.signum() != 1 ||
                        body.opt("exchange_rate_eur")?.toString()?.toBigDecimalOrNull()?.signum() != 1) return reject()
                    posts.incrementAndGet()
                    postStarted.countDown()
                    if (gatePost && !releasePost.await(15, TimeUnit.SECONDS)) return json(504, "{}")
                    synchronized(lock) {
                        if (records.any { it.getString("id") == body.getString("id") }) return json(409, "{}")
                        records += JSONObject(body.toString())
                    }
                    postFinished.countDown()
                    return if (ambiguousCommit) json(503, "{}") else json(201, JSONArray().put(body).toString())
                }
                otherMutations.incrementAndGet()
                return reject()
            }
        }
        server.start()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        api = SupabaseApi(BackendConfig(server.url("/").toString(), "transfer-public-fixture", server.url("/").toString()), store, client)
    }

    fun stored(): List<JSONObject> = synchronized(lock) { records.filter { it.optString("user_id") == owner }.map { JSONObject(it.toString()) } }

    override fun close() {
        releasePost.countDown()
        api.close()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun existing() = JSONObject().put("id", "00000000-0000-4000-8000-000000000020")
        .put("user_id", owner).put("description", "Già presente").put("date", "2026-10-10")
        .put("amount", "5.25").put("currency", "EUR").put("type", "expense").put("category_id", category)
        .put("exchange_rate_eur", "1").put("notes", JSONObject.NULL)
        .put("metadata", JSONObject().put("source", "fixture").put("symbols", "€\n\"quote\""))

    private fun reject(): MockResponse { denied.incrementAndGet(); return json(403, "{}") }
    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
}
