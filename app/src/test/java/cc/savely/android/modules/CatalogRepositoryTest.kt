package cc.savely.android.modules

import cc.savely.android.data.BackendConfig
import cc.savely.android.data.ApiException
import cc.savely.android.data.ApiError
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CatalogRepositoryTest {
    private lateinit var backend: MockWebServer
    private lateinit var books: MockWebServer
    private lateinit var repository: CatalogRepository

    @Before fun setup() {
        backend = MockWebServer().apply { start() }
        books = MockWebServer().apply { start() }
        val session = Session("access", "refresh", Long.MAX_VALUE, "user-1", "person@example.com")
        val api = SupabaseApi(BackendConfig(backend.url("/").toString(), "public-key"), MemorySessionStore(session))
        repository = CatalogRepository(api, booksEndpoint = books.url("/books/v1/volumes"))
    }
    @After fun teardown() { backend.shutdown(); books.shutdown() }

    @Test fun bookSearchEncodesQueryDeduplicatesAndNeverSendsAuth(): Unit = runBlocking {
        books.enqueue(json("""{"items":[{"id":"book-1","volumeInfo":{"title":"Dune","authors":["Frank Herbert"],"publishedDate":"1965-06","imageLinks":{"thumbnail":"http://example.com/cover"}}},{"id":"book-1","volumeInfo":{"title":"Duplicate"}},{"id":"broken"}]}"""))
        val results = repository.searchBooks("Dune & sequel", false)
        assertEquals(1, results.size)
        val request = books.takeRequest()
        assertEquals("intitle:Dune & sequel", request.requestUrl!!.queryParameter("q"))
        assertEquals("it", request.requestUrl!!.queryParameter("langRestrict"))
        assertEquals("20", request.requestUrl!!.queryParameter("maxResults"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("apikey"))
        assertEquals(0, backend.requestCount)
        val seed = CatalogRepository.bookSeed(results.single())
        assertEquals("book-1", seed.getString("api_id"))
        assertEquals(1965, seed.getInt("year"))
        assertEquals("Frank Herbert", seed.getString("author"))
        assertEquals("https://example.com/cover", seed.getString("cover_image"))
    }

    @Test fun comicsSearchUsesDocumentedFallback(): Unit = runBlocking {
        books.enqueue(json("""{"items":[]}"""))
        books.enqueue(json("""{"items":[{"id":"comic-1","volumeInfo":{"title":"Comic"}}]}"""))
        assertEquals(1, repository.searchBooks("Comic", true).size)
        val first = books.takeRequest().requestUrl!!
        val second = books.takeRequest().requestUrl!!
        assertEquals("intitle:Comic+subject:fumetti", first.queryParameter("q"))
        assertEquals("it", first.queryParameter("langRestrict"))
        assertEquals("intitle:Comic", second.queryParameter("q"))
        assertNull(second.queryParameter("langRestrict"))
    }

    @Test fun cardSearchUsesAuthenticatedProxyAndDoesNotShipPrivateApiKey(): Unit = runBlocking {
        backend.enqueue(json("""[{"id":"42","name":"Black Lotus","set":{"name":"Alpha"},"image":"https://example.com/card"}]"""))
        val cards = repository.searchCards("Black Lotus & Foil")
        val request = backend.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/functions/v1/cardtrader-proxy", request.requestUrl!!.encodedPath)
        assertEquals("search", request.requestUrl!!.queryParameter("action"))
        assertEquals("Black Lotus & Foil", request.requestUrl!!.queryParameter("name"))
        assertEquals("Bearer access", request.getHeader("Authorization"))
        assertEquals("public-key", request.getHeader("apikey"))
        assertEquals("42", CatalogRepository.cardSeed(cards.single()).getString("card_id"))
        assertEquals("Alpha", CatalogRepository.cardSeed(cards.single()).getString("set_code"))
    }

    @Test fun cardPriceConvertsCentsAndPreservesUnavailableAsNull(): Unit = runBlocking {
        backend.enqueue(json("""{"quoteContractVersion":2,"blueprintId":"42","currency":"EUR","minorUnit":2,"ctZeroLowPriceCents":1234,"totalListings":5}"""))
        backend.enqueue(json("""{"quoteContractVersion":2,"blueprintId":"43","currency":"EUR","minorUnit":2,"ctZeroLowPriceCents":null,"totalListings":0}"""))
        assertEquals(12.34, repository.cardPrice("42", "near_mint")!!, 1e-12)
        val request = backend.takeRequest().requestUrl!!
        assertEquals("prices", request.queryParameter("action"))
        assertEquals("42", request.queryParameter("id"))
        assertEquals("near_mint", request.queryParameter("condition"))
        assertNull(repository.cardPrice("43", "damaged"))
        backend.takeRequest()
    }

    @Test fun priceRejectsInvalidConditionBeforeNetwork(): Unit = runBlocking {
        try { repository.cardPrice("42", "unknown"); fail("Expected invalid condition") }
        catch (_: IllegalArgumentException) { assertEquals(0, backend.requestCount) }
    }

    @Test fun invalidBlueprintIsRejectedBeforeAnyProxyRequest(): Unit = runBlocking {
        for (id in listOf("", "042", "legacy-uuid", "-1", "42.5", "1000000000000000")) {
            assertTrue(runCatching { repository.cardPrice(id, "near_mint") }.isFailure)
        }
        assertEquals(0, backend.requestCount)
    }

    @Test fun unsafeSuccessfulDtoCannotBecomeAPriceOrTriggerAnotherRequest(): Unit = runBlocking {
        for (body in listOf(
            """{"ctZeroLowPriceCents":1250,"totalListings":1}""",
            """{"quoteContractVersion":2,"blueprintId":"42","currency":"USD","minorUnit":2,"ctZeroLowPriceCents":1250,"totalListings":1}""",
            """{"quoteContractVersion":2,"blueprintId":"43","currency":"EUR","minorUnit":2,"ctZeroLowPriceCents":1250,"totalListings":1}""",
            """{"quoteContractVersion":2,"blueprintId":"42","currency":"EUR","minorUnit":2,"ctZeroLowPriceCents":0.5,"totalListings":1}""",
        )) {
            backend.enqueue(json(body))
            assertTrue(runCatching { repository.cardPrice("42", "near_mint") }.isFailure)
            assertEquals("GET", backend.takeRequest().method)
        }
        assertEquals(4, backend.requestCount)
        assertEquals(0, books.requestCount)
    }

    @Test fun bookHttpFailureIsAnErrorAndNotAnEmptyCollection(): Unit = runBlocking {
        books.enqueue(json("""{"error":"upstream"}""", 503))
        try { repository.searchBooks("Dune", false); fail("Expected HTTP failure") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("503")) }
    }

    @Test fun failedCardPriceDoesNotBecomeZeroOrWriteToBackend(): Unit = runBlocking {
        backend.enqueue(json("""{"error":"Private API service failure"}""", 503))
        try { repository.cardPrice("42", "near_mint"); fail("Expected service failure") }
        catch (error: ApiException) { assertEquals(ApiError.SERVER, error.kind) }
        val request = backend.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(1, backend.requestCount)
        assertEquals(0, books.requestCount)
    }

    @Test fun unknownBookYearAndMissingMetadataStayNull() {
        val seed = CatalogRepository.bookSeed(JSONObject("""{"id":"x","volumeInfo":{"title":"Unknown","publishedDate":"unknown"}}"""))
        assertTrue(seed.isNull("year"))
        assertTrue(seed.isNull("author"))
        assertTrue(seed.isNull("cover_image"))
    }

    @Test fun oversizedOrInvalidBookResponsesFailWithoutReturningAnEmptyCollection(): Unit = runBlocking {
        for (body in listOf("x".repeat(2_097_153), "[]", "{broken")) {
            books.enqueue(json(body))
            assertTrue(runCatching { repository.searchBooks("Dune", false) }.isFailure)
        }
        assertEquals(3, books.requestCount)
        assertEquals(0, backend.requestCount)
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
}
