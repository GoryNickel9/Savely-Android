package cc.savely.android.ui

import cc.savely.android.data.Session
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AppRulesTest {
    @Test fun navigationPermissionsDefaultToDenied() {
        val public = destinations.filter { it.permission == null }
        assertTrue(public.any { it.route == "transactions" })
        assertFalse(public.any { it.route == "admin" })
        assertEquals(destinations.size, destinations.map { it.route }.distinct().size)
        assertTrue("The modules index is reachable without a module permission", canNavigate("modules", emptyMap()))
        assertTrue(canNavigate("transactions", emptyMap()))
        assertFalse(canNavigate("admin", emptyMap()))
        assertFalse(canNavigate("unknown-route", mapOf("admin" to true)))
        assertTrue(canNavigate("admin", mapOf("admin" to true)))
    }

    @Test fun malformedAssuranceClaimsNeverBypassMfa() {
        assertEquals("aal1", assuranceLevel(Session("bad", "refresh", 10, "id", "email")))
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"aal\":\"aal2\"}".toByteArray())
        assertEquals("aal2", assuranceLevel(Session("header.$payload.signature", "refresh", 10, "id", "email")))
    }

    @Test fun monetaryInputUsesExactDecimals() {
        assertEquals("1234.56", positiveAmount("1234,56").toPlainString())
        assertEquals("0.01", positiveAmount("0.01").toPlainString())
        listOf("NaN", "Infinity", "-1", "0", "0.001", "bad").forEach { raw ->
            assertTrue("must reject $raw", runCatching { positiveAmount(raw) }.isFailure)
        }
    }
}
