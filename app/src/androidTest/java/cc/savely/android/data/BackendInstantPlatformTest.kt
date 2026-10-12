package cc.savely.android.data

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Pure platform capability diagnostic: synthetic timestamps only, no session or network access. */
@RunWith(AndroidJUnit4::class)
class BackendInstantPlatformTest {
    @Test fun explicitPostgresOffsetsPreserveTheSameInstantOnTheAndroidPlatform() {
        val cases = listOf(
            Case("utc_microseconds", "2026-10-12T07:25:10.918551+00:00", "2026-10-12T07:25:10.918551Z"),
            Case("utc_milliseconds", "2026-10-12T07:25:17.496+00:00", "2026-10-12T07:25:17.496Z"),
            Case("positive_offset", "2026-10-12T09:25:10.918551+02:00", "2026-10-12T07:25:10.918551Z"),
            Case("negative_offset", "2026-10-12T03:55:10.918551-03:30", "2026-10-12T07:25:10.918551Z"),
            Case("zulu", "2026-10-12T07:25:10.918551Z", "2026-10-12T07:25:10.918551Z"),
        )
        val observations = JSONArray()
        for (case in cases) {
            val expected = Instant.parse(case.expectedUtc)
            val converted = OffsetDateTime.parse(case.input).toInstant()
            assertEquals(case.name, expected, converted)
            assertEquals("Microsecond precision must remain exact", expected.nano, converted.nano)
            assertEquals("The production backend parser must work on this Android platform", expected, parseBackendInstant(case.input))
            val native = runCatching { Instant.parse(case.input) }
            if (native.isSuccess) assertEquals(case.name, expected, native.getOrThrow())
            else assertTrue("Unsupported platform format must fail explicitly", native.exceptionOrNull() is DateTimeParseException)
            observations.put(JSONObject().put("case", case.name).put("instant_parse_accepted", native.isSuccess)
                .put("instant_parse_failure_type", native.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("offset_date_time_correct", converted == expected).put("backend_parser_correct", parseBackendInstant(case.input) == expected))
        }
        for (invalid in listOf("not-an-instant", "2026-10-12T07:25:10.918551", "2026-13-12T07:25:10Z")) {
            assertThrows(DateTimeParseException::class.java) { OffsetDateTime.parse(invalid).toInstant() }
            assertThrows(DateTimeParseException::class.java) { parseBackendInstant(invalid) }
        }
        val report = JSONObject().put("sdk_int", Build.VERSION.SDK_INT).put("scope", "synthetic-platform-only-no-backend")
            .put("observations", observations)
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("backend-instant-platform-${Build.VERSION.SDK_INT}.json").writeText(report.toString())
    }

    private data class Case(val name: String, val input: String, val expectedUtc: String)
}
