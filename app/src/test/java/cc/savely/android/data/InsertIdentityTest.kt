package cc.savely.android.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class InsertIdentityTest {
    @Test fun financialAndModuleRowsCarryClientUuidWithoutMutatingDraft() {
        for (table in listOf("transactions", "portfolio_assets", "tgc_cards", "library_items", "liquido_sigaretta", "poker_next_cut", "shared_expenses")) {
            val draft = JSONObject().put("name", "unchanged").put("notes", JSONObject.NULL)
            val result = insertPayload(table, draft)
            assertFalse(draft.has("id"))
            assertEquals(result.getString("id"), UUID.fromString(result.getString("id")).toString())
            assertEquals("unchanged", result.getString("name"))
            assertTrue(result.isNull("notes"))
        }
    }

    @Test fun checkpointUuidIsPreservedAndUnknownSchemasRemainUntouched() {
        val draft = JSONObject().put("id", UUID.randomUUID().toString())
        assertSame(draft, insertPayload("library_items", draft))
        val other = JSONObject().put("id", 17)
        assertSame(other, insertPayload("unrelated_table", other))
        assertSame(other, insertPayload("login_activity", other))
    }

    @Test fun malformedKnownIdentityCannotBeWritten() {
        for (id in listOf(JSONObject.NULL, 17, "", "1-1-1-1-1", "malformed")) {
            val error = runCatching { insertPayload("transactions", JSONObject().put("id", id)) }.exceptionOrNull()
            assertTrue(error is ApiException && error.kind == ApiError.VALIDATION)
        }
    }
}
