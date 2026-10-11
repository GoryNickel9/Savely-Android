package cc.savely.android.modules

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ModuleRecordValuesTest {
    @Test fun mangaCoverAcceptsAbsoluteWebUrlsAndPreservesEscapedPathAndQuery() {
        assertEquals("http://example.test/cover%20one.png?v=2#front",
            mangaCoverUrl("  http://example.test/cover%20one.png?v=2#front  "))
        assertEquals("HTTPS://localhost:8443/cover.png", mangaCoverUrl("HTTPS://localhost:8443/cover.png"))
    }

    @Test fun mangaCoverRejectsMalformedRelativeUnsafeOrCredentialedUrls() {
        listOf("/cover.png", "https://", "https://example.test/a b.png", "javascript:alert(1)",
            "file:///tmp/cover.png", "data:image/png;base64,a", "https://user:secret@example.test/a.png",
            "https://example.test:0/a.png", "https://example.test:65536/a.png").forEach { value ->
            assertThrows("Invalid URL $value", IllegalArgumentException::class.java) { mangaCoverUrl(value) }
        }
    }

    @Test fun mangaCoverEmptyDraftIsNullRatherThanTextOrZero() {
        assertNull(mangaCoverUrl(""))
        assertNull(mangaCoverUrl("   "))
        val payload = JSONObject().put("cover_image", mangaCoverUrl("") ?: JSONObject.NULL)
        assertTrue(payload.has("cover_image"))
        assertSame(JSONObject.NULL, payload.get("cover_image"))
    }

    @Test fun mangaVolumeCreationCopiesValidatedCoverApiMetadataAndExplicitNullPrices() {
        val base = JSONObject().put("title", "Serie copertina").put("quantity", 2)
            .put("cover_image", mangaCoverUrl("http://localhost:8443/cover.png"))
            .put("api_id", "manga-api-42").put("author", JSONObject.NULL).put("publisher", JSONObject.NULL)
            .put("year", JSONObject.NULL).put("notes", JSONObject.NULL)
            .put("purchase_price", JSONObject.NULL).put("reselling_value", JSONObject.NULL)
        val before = base.toString()
        val owner = "36000000-0000-4000-8000-000000000001"
        val plan = MangaVolumes.prepare(owner, base, listOf(1, 2))
        plan.volumes.forEachIndexed { index, volume ->
            val body = volume.body()
            assertEquals("Serie copertina Vol. ${index + 1}", body.getString("title"))
            assertEquals("http://localhost:8443/cover.png", body.getString("cover_image"))
            assertEquals("manga-api-42", body.getString("api_id"))
            assertEquals(owner, body.getString("user_id"))
            assertEquals(1, body.getInt("quantity"))
            listOf("author", "publisher", "year", "notes", "purchase_price", "reselling_value")
                .forEach { assertSame(JSONObject.NULL, body.get(it)) }
        }
        assertEquals(before, base.toString())
        assertEquals(plan.volumes.map { it.payload }, MangaVolumeBatch.decode(plan.encode()).volumes.map { it.payload })
        base.put("cover_image", mangaCoverUrl("") ?: JSONObject.NULL)
        assertSame(JSONObject.NULL, MangaVolumes.prepare(owner, base, listOf(3)).volumes.single().body().get("cover_image"))
    }

    @Test fun bothPokerDetailsKeepOnlyCurrentYearWithoutMutatingAnyOwnersOrSummaries() {
        val rows = listOf(
            JSONObject().put("id", "old").put("date", "2025-05-01").put("user_id", "owner").put("profit_loss", 50),
            JSONObject().put("id", "current").put("date", "2026-05-01").put("user_id", "owner").put("profit_loss", 20),
            JSONObject().put("id", "future").put("date", "2027-05-01").put("user_id", "owner").put("profit_loss", 30))
        val before = rows.map(JSONObject::toString)
        listOf("poker_hourly_earnings", "poker_rakeback").forEach { table ->
            val detail = currentYearDetails(table, rows, 2026)
            assertEquals(listOf("current"), detail.map { it.getString("id") })
            assertSame(rows[1], detail.single())
            assertEquals(3, rows.size)
            assertEquals(100.0, rows.sumOf { it.getDouble("profit_loss") }, 0.0)
        }
        assertEquals(before, rows.map(JSONObject::toString))
    }

    @Test fun detailFilterPreservesExistingFumoRulesAndDoesNotFilterOtherCollections() {
        val old = JSONObject().put("id", "old").put("data_acquisto", "2025-06-01").put("data_arrivo", "2025-06-01")
        val current = JSONObject().put("id", "current").put("data_acquisto", "2026-06-01").put("data_arrivo", "2026-06-01")
        val rows = listOf(old, current)
        listOf("thc", "liquido_sigaretta").forEach { table ->
            assertEquals(listOf(current), currentYearDetails(table, rows, 2026))
        }
        listOf("cbd", "tgc_cards", "library_items", "poker_next_cut", "poker_manual_expenses").forEach {
            assertSame(rows, currentYearDetails(it, rows, 2026))
        }
    }

    @Test fun fumoDailyDetailDisplaysTwoDecimalsAndDailyEuroCostFromLiteralFixture() {
        val derived = FumoCalculations.derived(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 29), 10.0, 17.0)
        assertEquals("28 giorni · 0,36/giorno · 0,61\u00a0€/giorno · 18,21\u00a0€/mese", fumoDetail(derived))
    }

    @Test fun fumoDailyDetailUsesWebTieRoundingAndKeepsMissingQuantityDistinctFromZero() {
        assertEquals("2 giorni · 0,13/giorno · 0,13\u00a0€/giorno · 3,75\u00a0€/mese",
            fumoDetail(FumoCalculations.Derived(2, 0.125, 0.125, 3.75)))
        assertEquals("2 giorni · 0,30/giorno · 0,30\u00a0€/giorno · 9,15\u00a0€/mese",
            fumoDetail(FumoCalculations.Derived(2, 0.305, 0.305, 9.15)))
        assertEquals("10 giorni · -/giorno · -/giorno · 0,00\u00a0€/mese",
            fumoDetail(FumoCalculations.Derived(10, null, 0.0, 0.0)))
        assertNull(fumoDetail(FumoCalculations.Derived(null, null, null, null)))
    }
}
