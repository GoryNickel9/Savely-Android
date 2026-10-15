package cc.savely.android.release

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class ReleaseTest {
    @Test fun versionsUseNumericOrderAndStableSupersedesPreview() {
        fun version(raw: String) = AppVersion.parse(raw)!!
        assertTrue(version("1.10.0") > version("1.9.9"))
        assertTrue(version("v0.1.0") > version("0.1.0-alpha"))
        assertTrue(version("0.2.0-alpha") > version("0.1.0"))
        assertEquals(version("v1.2.3"), version("1.2.3"))
        assertEquals(0, version("1.2.3-alpha").compareTo(version("1.2.3-beta")))
    }

    @Test fun invalidVersionsCannotTriggerAnUpdate() {
        listOf("", "latest", "1.2", "1.2.3.4", "01.2.3", "-1.2.3", "1.2.3/../x", "2147483648.1.0", "1.2.3-").forEach {
            assertNull(it, AppVersion.parse(it))
        }
    }

    @Test fun onlyPublishedStableReleaseInTheExpectedRepositoryIsAccepted() {
        val json = validRelease()
        val result = PublishedRelease.parse(json)
        assertEquals("v1.2.3", result.tag)
        assertEquals("Verified changes", result.notes)
        assertEquals(json.getString("html_url"), result.url)
        assertTrue(runCatching { PublishedRelease.parse(validRelease().put("draft", true)) }.isFailure)
        assertTrue(runCatching { PublishedRelease.parse(validRelease().put("prerelease", true)) }.isFailure)
        assertTrue(runCatching { PublishedRelease.parse(validRelease().put("tag_name", "v1.2.3-beta")) }.isFailure)
        assertTrue(runCatching { PublishedRelease.parse(validRelease().put("html_url", "https://github.com/another/repo/releases/tag/v1.2.3")) }.isFailure)
        assertTrue(runCatching { PublishedRelease.parse(validRelease().put("html_url", "javascript:alert(1)")) }.isFailure)
    }

    @Test fun missingBodyIsSafeAndLongNotesAreBounded() {
        assertEquals("", PublishedRelease.parse(validRelease().put("body", JSONObject.NULL)).notes)
        assertEquals(16_384, PublishedRelease.parse(validRelease().put("body", "x".repeat(20_000))).notes.length)
    }

    @Test fun publicCheckSendsNoCredentialsAndNoReleaseIsExplicit() = runBlocking {
        val repository = ReleaseRepository(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("${PublishedRelease.API_URL}/latest", request.url.toString())
            assertNull(request.header("Authorization"))
            assertNull(request.header("apikey"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(404).message("Not Found")
                .body("{}".toResponseBody()).build()
        }.build())
        assertNull(repository.latest(allowPreviews = false))
    }

    @Test fun responseAndErrorsDoNotTurnIntoFalseUpToDateResults() = runBlocking {
        fun repository(code: Int, body: String) = ReleaseRepository(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(body.toResponseBody()).build()
        }.build())
        assertEquals("v1.2.3", repository(200, validRelease().toString()).latest(false)?.tag)
        assertTrue(runCatching { repository(403, "{}").latest(false) }.isFailure)
        assertTrue(runCatching { repository(503, "{}").latest(false) }.isFailure)
        assertTrue(runCatching { repository(200, "[]").latest(false) }.isFailure)
        assertTrue(runCatching { repository(200, "x".repeat(262_145)).latest(false) }.isFailure)
    }

    @Test fun previewsUsePublicListWithoutCredentialsAndPreferNewestNumericVersion() = runBlocking {
        val preview = validRelease().put("tag_name", "v1.3.0-alpha").put("prerelease", true)
            .put("html_url", "${PublishedRelease.RELEASES_URL}/tag/v1.3.0-alpha")
        val body = org.json.JSONArray().put(validRelease()).put(preview).put(validRelease().put("draft", true))
        val repository = ReleaseRepository(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("${PublishedRelease.API_URL}?per_page=20", chain.request().url.toString())
            assertNull(chain.request().header("Authorization"))
            assertNull(chain.request().header("apikey"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(body.toString().toResponseBody()).build()
        }.build())
        assertEquals("v1.3.0-alpha", repository.latest(true)?.tag)
        assertTrue(PublishedRelease.parse(preview, true).version.preview)
        assertTrue(runCatching { PublishedRelease.parse(preview) }.isFailure)
        assertTrue(runCatching { PublishedRelease.parse(validRelease().put("prerelease", true), true) }.isFailure)
    }

    @Test fun emptyPublicPreviewListIsNotAnUpdate() = runBlocking {
        val repository = ReleaseRepository(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body("[]".toResponseBody()).build()
        }.build())
        assertNull(repository.latest(true))
    }

    private fun validRelease() = JSONObject().put("tag_name", "v1.2.3").put("draft", false).put("prerelease", false)
        .put("html_url", "${PublishedRelease.RELEASES_URL}/tag/v1.2.3").put("body", "Verified changes")
}
