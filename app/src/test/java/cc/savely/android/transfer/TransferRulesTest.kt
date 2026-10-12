package cc.savely.android.transfer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class TransferRulesTest {
    @Test fun bankIsinCarriesLiteralPriceDateAndCreatesQuantityOneWithoutAssumingShares() {
        val row = TransferCsv.parse("Data;Tipo;Nota;Uscita\n12/10/2026;Buy Trade IE00BK5BQT80 Vanguard All-World;Acquisto;123,45", ImportFormat.BANK).transactions.single()
        assertEquals("IE00BK5BQT80", row.isin)
        assertEquals("Vanguard All-World", row.investmentName)
        val body = TransferRules.assetBody(row, TransferIsinMapping(isin = row.isin!!, symbol = "vwce", name = "Vanguard All-World"), "owner", "uuid")
        assertEquals(1, body.getInt("quantity"))
        assertEquals("123.45", body.get("purchase_price").toString())
        assertEquals("2026-10-12", body.getString("purchase_date"))
        assertEquals("VWCE", body.getString("symbol"))
        assertEquals("Importato da banca - ISIN: IE00BK5BQT80", body.getString("notes"))
        assertFalse(body.has("current_price"))
    }

    @Test fun investmentsWithoutIsinAreProblemsAndNeverExpenses() {
        val result = TransferCsv.parse("Data,Tipo,Descrizione,Importo\n2026-10-12,Savings plan,ETF,-10", ImportFormat.BANK)
        assertTrue(result.transactions.isEmpty())
        assertEquals(1, result.problems.size)
        assertTrue(result.problems.single().message.contains("ISIN"))
    }

    @Test fun interestRulesMatchBothBanksButNeverRewriteExpensesOrGenericInterest() {
        val result = TransferCsv.parse("Data,Descrizione,Importo\n2026-10-12,SPESE ACCREDITO A FRONTE DEL SALDO MENSILE,1.25\n2026-10-12,Interessi interest payment,2.50\n2026-10-12,Interessi interest payment,-2.50\n2026-10-12,Interessi vari,3", ImportFormat.BANK)
        assertEquals(listOf(true, true, false, false), result.transactions.map { it.bankInterest })
        assertEquals(listOf("1.25", "2.50", "2.50", "3"), result.transactions.map { it.amount.toPlainString() })
    }

    @Test fun bankSubstringMappingAndRevolutExactSourceMappingDoNotCrossTypes() {
        val expense = ImportTransaction(2, "PAGAMENTO Supermercato Roma", LocalDate.of(2026, 10, 12), BigDecimal("12.50"), "EUR", "expense")
        val context = ImportContext("owner", listOf(ImportCategory("food", "Cibo", "expense"), ImportCategory("salary", "Stipendio", "income")), emptySet(), emptyMap(), listOf(
            TransferCategoryMapping("1", "supermercato", "salary", "shared"),
            TransferCategoryMapping("2", "supermercato", "food", "shared"),
            TransferCategoryMapping("3", expense.description, "food", "revolut")))
        assertEquals("2", TransferRules.matchingCategory(expense, ImportFormat.BANK, context)?.id)
        assertEquals("3", TransferRules.matchingCategory(expense, ImportFormat.REVOLUT, context)?.id)
        assertNull(TransferRules.matchingCategory(expense.copy(description = "Supermercato"), ImportFormat.REVOLUT, context))
    }

    @Test fun keywordAndNoteComposeLiteralDescriptionAndRejectBlankKeyword() {
        assertEquals("Supermercato - spesa famiglia", TransferRules.description(" Supermercato ", " spesa famiglia "))
        assertEquals("Interessi", TransferRules.description("Interessi", "  "))
        fails { TransferRules.description(" ", "Nota") }
        fails { TransferRules.description("x".repeat(1001), "") }
    }

    @Test fun categoryAndIsinDraftsValidateBeforeAnyPayloadCanBeWritten() {
        assertEquals("Nuova", TransferCategoryDraft(name = " Nuova ", type = "expense").body("owner").getString("name"))
        fails { TransferCategoryDraft(name = "", type = "expense").body("owner") }
        fails { TransferCategoryDraft(name = "X", type = "investment").body("owner") }
        fails { TransferCategoryDraft(name = "X", type = "income", color = "red").body("owner") }
        fails { TransferIsinMapping(isin = "IE00BK5BQT80", symbol = "", name = "ETF").body("owner") }
        fails { TransferIsinMapping(isin = "bad", symbol = "VWCE", name = "ETF").body("owner") }
        fails { TransferIsinMapping(isin = "IE00BK5BQT80", symbol = "A B", name = "ETF").body("owner") }
    }

    @Test fun reconciliationRequiresEveryFieldNullAndOwnerButAllowsUnknownMetadata() {
        val expected = JSONObject().put("id", "same").put("user_id", "owner").put("price", BigDecimal("12.50")).put("cover", JSONObject.NULL)
        val actual = JSONObject(expected.toString()).put("price", 12.5).put("created_at", "server metadata")
        assertTrue(TransferRules.payloadMatches(actual, expected))
        assertFalse(TransferRules.payloadMatches(JSONObject(actual.toString()).put("user_id", "other"), expected))
        assertFalse(TransferRules.payloadMatches(JSONObject(actual.toString()).put("price", 12.51), expected))
        assertFalse(TransferRules.payloadMatches(JSONObject(actual.toString()).put("cover", "null"), expected))
        assertFalse(TransferRules.payloadMatches(JSONObject(actual.toString()).apply { remove("cover") }, expected))
    }

    @Test fun transactionCsvExportCanBeReimportedWithoutChangingUnicodeNoteAmountCurrencyOrRate() {
        val transaction = JSONObject().put("entity", "transactions").put("date", "2026-10-12").put("description", "Shop - Famiglia, €\n\"nota\"")
            .put("amount", BigDecimal("12.50")).put("currency", "EUR").put("type", "expense").put("category_id", "owned-category")
            .put("exchange_rate_eur", BigDecimal.ONE).put("notes", JSONObject.NULL)
        val category = JSONObject().put("entity", "categories").put("name", "Owned category").put("type", "expense")
        val imported = TransferCsv.parse(TransferCsv.serialize(listOf(transaction, category)), ImportFormat.NORMALIZED)
        assertEquals(1, imported.skipped)
        assertTrue(imported.problems.isEmpty())
        val row = imported.transactions.single()
        assertEquals("Shop - Famiglia, €\n\"nota\"", row.description)
        assertEquals(BigDecimal("12.50"), row.amount)
        assertEquals("EUR", row.currency)
        assertEquals(BigDecimal.ONE, row.exchangeRateEur)
        assertEquals("owned-category", row.categoryId)
    }

    private fun fails(block: () -> Any?) { try { block(); fail("Invalid draft accepted") } catch (_: TransferException) { } }
}
