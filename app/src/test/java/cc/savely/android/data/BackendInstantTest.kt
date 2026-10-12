package cc.savely.android.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.format.DateTimeParseException

class BackendInstantTest {
    @Test fun zuluTimestampRetainsCompatibility() {
        assertEquals(Instant.parse("2026-10-12T07:25:17.496Z"), parseBackendInstant("2026-10-12T07:25:17.496Z"))
    }

    @Test fun postgresUtcOffsetRetainsMicroseconds() {
        val value = parseBackendInstant("2026-10-12T07:25:10.918551+00:00")
        assertEquals(Instant.parse("2026-10-12T07:25:10.918551Z"), value)
        assertEquals(918_551_000, value.nano)
    }

    @Test fun positiveAndNegativeOffsetsRepresentTheSameInstant() {
        val expected = Instant.parse("2026-10-12T07:25:10.918551Z")
        assertEquals(expected, parseBackendInstant("2026-10-12T09:25:10.918551+02:00"))
        assertEquals(expected, parseBackendInstant("2026-10-12T03:55:10.918551-03:30"))
    }

    @Test fun offsetCanCrossUtcDateBoundaryWithoutTruncatingNanoseconds() {
        assertEquals(Instant.parse("2025-12-31T22:30:00.123456789Z"),
            parseBackendInstant("2026-01-01T00:30:00.123456789+02:00"))
    }

    @Test fun localMalformedAndImpossibleTimestampsAreRejected() {
        for (value in listOf("not-an-instant", "", "2026-10-12T07:25:10.918551", "2026-13-12T07:25:10Z",
            "2026-10-12T07:25:10.9185511234+00:00", "2026-10-12T07:25:10+25:00")) {
            assertThrows(value, DateTimeParseException::class.java) { parseBackendInstant(value) }
        }
    }

    @Test fun cooldownAndArchiveOrderingUseNormalizedInstants() {
        val updated = parseBackendInstant("2026-10-12T09:25:10.918551+02:00")
        assertTrue(updated.plusSeconds(86_400) <= parseBackendInstant("2026-10-13T07:25:10.918551Z"))
        assertFalse(updated.plusSeconds(86_400) <= parseBackendInstant("2026-10-13T07:25:10.918550+00:00"))
        assertTrue(parseBackendInstant("2026-10-12T07:25:17.496+00:00") > updated)
    }
}
