package cc.savely.android.modules

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/** Pure Android java.time boundary using the actual deployed end-column JSON format. */
@RunWith(AndroidJUnit4::class)
class FumoBackendDatePlatformTest {
    @Test fun timestampOffsetsAndMicrosecondsUseMillisecondCeilAndUnchangedEditorValueIsPreserved() {
        val original = "2026-06-11T12:00:00.123456+00:00"
        assertEquals(11L, fumoLegacyDerived("2026-06-01", original, 22.0, 11.0).days)
        assertEquals(10L, fumoLegacyDerived("2026-06-01", "2026-06-11T00:00:00.000999+00:00", 22.0, 11.0).days)
        assertEquals(12L, fumoLegacyDerived("2026-06-01", "2026-06-11T23:00:00-02:00", 22.0, 11.0).days)
        assertTrue(fumoEndNotBeforeArrival("2026-06-11", fumoEndValueForSave("2026-06-11T01:00:00Z",
            "2026-06-10", ZoneId.of("America/Los_Angeles"))))
        assertFalse(fumoEndNotBeforeArrival("2026-06-11", fumoEndValueForSave("2026-06-10T23:00:00Z",
            "2026-06-11", ZoneId.of("Europe/Rome"))))
        assertEquals(original, fumoEndValueForSave(original,
            fumoEndDateForEditor(original, ZoneId.of("Europe/Rome")), ZoneId.of("Europe/Rome")))
    }
}
