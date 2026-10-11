package cc.savely.android.data

import org.junit.Assert.*
import org.junit.Test

class NativeAuthCallbackTest {
    @Test fun exactCallbackAcceptsOneCodeAndRedactsIt() {
        val value = NativeAuthCallback.parse("savely://auth/callback?state=$STATE&code=$CODE")
        assertEquals(STATE, value.state)
        assertEquals(CODE, value.code)
        assertFalse(value.failed)
        assertFalse(value.toString().contains(CODE))
        assertFalse(value.toString().contains(STATE))
    }

    @Test fun malformedOrForeignLinksNeverExposeInputInErrors() {
        val valid = "savely://auth/callback?state=$STATE&code=$CODE"
        val invalid = listOf(valid.replace("savely:", "https:"), valid.replace("auth/", "auth.evil/"),
            valid.replace("auth/", "user@auth/"), valid.replace("auth/", "auth:123/"),
            valid.replace("callback?", "callback/other?"), valid.replace("callback?", "%63allback?"),
            valid + "#access_token=secret", valid + "&code=$CODE", valid + "&state=$STATE", valid + "&access_token=secret",
            valid.replace(STATE, "123"), valid.replace(CODE, "%0Asecret"), valid.replace(CODE, "short"),
            valid + "&error=secret", "savely://auth/callback?state=$STATE", valid + " ", "savely://auth/callback?state=$STATE&error_description=secret")
        invalid.forEach { raw ->
            try { NativeAuthCallback.parse(raw); fail("Invalid input accepted") }
            catch (error: ApiException) {
                assertEquals(ApiError.VALIDATION, error.kind)
                assertFalse(error.message.orEmpty().contains(CODE))
                assertFalse(error.message.orEmpty().contains("secret"))
            }
        }
    }

    @Test fun providerFailureRetainsOnlySafeClassification() {
        val value = NativeAuthCallback.parse("savely://auth/callback?state=$STATE&error=access_denied&error_description=private%20detail")
        assertTrue(value.failed)
        assertNull(value.code)
        assertFalse(value.toString().contains("private"))
    }

    @Test fun challengeMatchesRfc7636AndPendingHasBoundedLifetime() {
        val pending = PendingNativeAuth(STATE, NativeAuthPurpose.RECOVERY, "qa@example.test",
            "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", 100)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pending.challenge)
        assertTrue(pending.isFresh(3700))
        assertFalse(pending.isFresh(3701))
        assertFalse(pending.isFresh(99))
        assertFalse(pending.toString().contains(pending.verifier))
        val generated = PendingNativeAuth.create(NativeAuthPurpose.SIGNUP, " qa@example.test ")
        assertEquals(43, generated.verifier.length)
        assertEquals("qa@example.test", generated.email)
        assertTrue(canonicalUuid(generated.state))
    }
    private companion object {
        const val STATE = "11111111-1111-4111-8111-111111111111"
        const val CODE = "22222222-2222-4222-8222-222222222222"
    }
}
