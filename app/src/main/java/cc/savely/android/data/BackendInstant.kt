package cc.savely.android.data

import java.time.Instant
import java.time.OffsetDateTime

/** PostgREST emits explicit offsets; Android 12 Instant.parse rejects these offsets.
 * Parse the offset before normalizing to UTC, retaining subsecond precision and rejecting local dates.
 */
internal fun parseBackendInstant(value: String): Instant = OffsetDateTime.parse(value).toInstant()
