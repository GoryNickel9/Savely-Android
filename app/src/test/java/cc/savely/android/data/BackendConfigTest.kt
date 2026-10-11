package cc.savely.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendConfigTest {
    @Test fun exactProductionOriginUsesCanonicalApiButKeepsCallbackOrigin() {
        for (origin in listOf("https://savely.cc", "https://savely.cc/", "https://savely.cc:443/")) {
            val config = config(origin)
            assertEquals("https://www.savely.cc/", config.websiteApiUrl().toString())
            assertEquals("https://savely.cc/", config.websiteUrl().toString())
            assertEquals(origin, config.webUrl)
        }
    }

    @Test fun canonicalProductionOriginIsUnchanged() {
        val config = config("https://www.savely.cc/")
        assertEquals(config.websiteUrl(), config.websiteApiUrl())
        assertEquals("https://www.savely.cc/", config.websiteApiUrl().toString())
    }

    @Test fun customLocalAndNonDefaultPortOriginsAreNeverRewritten() {
        for (origin in listOf("https://example.test", "https://bank.savely.cc", "https://savely.cc:8443",
            "https://savely.cc.example.test", "http://localhost:12345", "http://127.0.0.1:12345", "http://[::1]:12345")) {
            val config = config(origin)
            assertEquals(config.websiteUrl(), config.websiteApiUrl())
        }
    }

    private fun config(webOrigin: String) = BackendConfig("https://backend.example.test", "public-key", webOrigin)
}
