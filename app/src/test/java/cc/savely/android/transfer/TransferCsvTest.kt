package cc.savely.android.transfer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class TransferCsvTest {
    @Test fun quotedCommaSemicolonNewlineAndUnicodeRoundTrip() {
        val rows = listOf(JSONObject().put("description", "  Café, \"cena\";\nseconda riga  ").put("amount", BigDecimal("12.50")),
            JSONObject().put("description", "Ultima").put("amount", JSONObject.NULL))
        val parsed = TransferCsv.parseRows(TransferCsv.serialize(rows))
        val descriptionIndex = parsed[0].indexOf("description")
        assertEquals("  Café, \"cena\";\nseconda riga  ", parsed[1][descriptionIndex])
        assertEquals("Ultima", parsed[2][descriptionIndex])
        assertEquals("", parsed[2][parsed[0].indexOf("amount")])
    }

    @Test fun nestedJsonIsExportedAsJsonAndNotObjectObject() {
        val nested = JSONObject().put("value", JSONArray().put(true).put(JSONObject.NULL).put("a,b"))
        val csv = TransferCsv.serialize(listOf(JSONObject().put("metadata", nested)))
        val reparsed = JSONObject(TransferCsv.parseRows(csv)[1][0])
        assertTrue(reparsed.getJSONArray("value").getBoolean(0))
        assertTrue(reparsed.getJSONArray("value").isNull(1))
        assertEquals("a,b", reparsed.getJSONArray("value").getString(2))
    }

    @Test fun revolutImportsOnlyCompletedCardPaymentsAndPreservesCurrency() {
        val csv = "Type,State,Completed Date,Description,Amount,Currency\n" +
            "Card Payment,COMPLETED,2026-10-10 17:13:00,Pranzo,-12.505,USD\n" +
            "Card Payment,PENDING,2026-10-10,Pending,-2,EUR\n" +
            "Transfer,COMPLETED,2026-10-10,Transfer,100,EUR"
        val result = TransferCsv.parse(csv, ImportFormat.REVOLUT)
        assertEquals(1, result.transactions.size)
        assertEquals(2, result.skipped)
        val transaction = result.transactions.single()
        assertEquals("USD", transaction.currency)
        assertEquals("expense", transaction.type)
        assertEquals(BigDecimal("12.51"), transaction.amount)
        assertEquals(LocalDate.of(2026, 10, 10), transaction.date)
        assertNull(transaction.exchangeRateEur)
        assertFails { transaction.body("user", "category") }
        assertEquals("USD", transaction.body("user", "category", BigDecimal("0.91")).getString("currency"))
    }

    @Test fun normalizedRequiresPositiveAmountExplicitTypeAndStrictDate() {
        val csv = "date,description,amount,currency,type\n" +
            "2026-10-10,Valid,5.20,EUR,income\n" +
            "2026-10-10,Negative,-5,EUR,expense\n" +
            "2026-02-30,Impossible,1,EUR,expense\n" +
            "2026-10-10,Wrong,1,XYZ,expense"
        val result = TransferCsv.parse(csv, ImportFormat.NORMALIZED)
        assertEquals(1, result.transactions.size)
        assertEquals(listOf(3, 4, 5), result.problems.map { it.row })
        assertEquals(BigDecimal.ONE, result.transactions.single().exchangeRateEur)
    }

    @Test fun bankSupportsSignedAmountItalianDatesAndExplicitCurrency() {
        val result = TransferCsv.parse("Report conto\nData;Descrizione;Importo;Valuta\n10/10/2026;Spesa;-1.234,56;CHF\n11/10/2026;Stipendio;2500,00;CHF",
            ImportFormat.BANK, "EUR")
        assertEquals(2, result.transactions.size)
        assertEquals(BigDecimal("1234.56"), result.transactions[0].amount)
        assertEquals("expense", result.transactions[0].type)
        assertEquals("income", result.transactions[1].type)
        assertTrue(result.transactions.all { it.currency == "CHF" })
        assertTrue(result.transactions.all { it.exchangeRateEur == null })
    }

    @Test fun bankSplitColumnsExcludeKnownTransfersAndRetainInvestmentForIsinPreview() {
        val input = "Data,Tipo,Descrizione,Entrata,Uscita\n" +
            "2026-10-10,Carta,Pagamento,,12.50\n" +
            "2026-10-10,TRASFERIMENTO,Conto,100,\n" +
            "2026-10-10,Carta,SPORT. AUT. prelievo,,20\n" +
            "2026-10-10,Buy Trade DE0000000000,Titolo,,100"
        val result = TransferCsv.parse(input, ImportFormat.BANK)
        assertEquals(2, result.transactions.size)
        assertEquals(2, result.skipped)
        assertEquals(0, result.problems.size)
        assertEquals("DE0000000000", result.transactions.last().isin)
        assertEquals("investment", result.transactions.last().type)
        assertEquals(BigDecimal("100"), result.transactions.last().amount)
    }

    @Test fun noDateFallbackNoSilentZeroAndInvalidQuotesAreRejected() {
        assertFails { TransferCsv.parseRows("name,amount\n\"unfinished,2") }
        assertFails { TransferCsv.parseRows("name,amount\nabc\"quoted,2") }
        assertFails { TransferCsv.parseRows("name,amount\n\"closed\"oops,2") }
        val result = TransferCsv.parse("date,description,amount,currency,type\n,Missing,3,EUR,expense\n2026-10-10,Bad,not-money,EUR,expense",
            ImportFormat.NORMALIZED)
        assertEquals(0, result.transactions.size)
        assertEquals(2, result.problems.size)
    }

    @Test fun byteAndRecordLimitsAreEnforced() {
        assertFails { TransferCsv.parseRows("x".repeat(TransferCsv.MAX_BYTES + 1)) }
        assertFails { TransferCsv.parseRows("header\n" + "row\n".repeat(TransferCsv.MAX_ROWS + 1)) }
        assertEquals(TransferCsv.MAX_ROWS + 1, TransferCsv.parseRows("header\n" + "row\n".repeat(TransferCsv.MAX_ROWS)).size)
    }

    @Test fun bomCrLfAndQuotedDelimitersDoNotCorruptHeaderDetection() {
        val parsed = TransferCsv.parseRows("\uFEFFname;note\r\n\"a,b,c\";\"x;y\"\r\n")
        assertEquals(listOf("name", "note"), parsed[0])
        assertEquals(listOf("a,b,c", "x;y"), parsed[1])
    }

    @Test fun duplicatesUseCurrencyTypeAndFileOccurrences() {
        val first = ImportTransaction(2, "Café", LocalDate.of(2026, 10, 10), BigDecimal("12.50"), "EUR", "expense")
        val second = first.copy(row = 3, amount = BigDecimal("12.500"))
        val otherCurrency = first.copy(row = 4, currency = "USD")
        val otherType = first.copy(row = 5, type = "income")
        assertEquals(setOf(3), TransferRepository.markDuplicates(listOf(first, second, otherCurrency, otherType), emptySet()))
        assertEquals(setOf(2, 3), TransferRepository.markDuplicates(listOf(first, second, otherCurrency, otherType), setOf(first.duplicateKey())))
    }

    @Test fun decimalsDoNotUseFloatingPointOrAcceptGarbage() {
        assertEquals(BigDecimal("1234.56"), TransferCsv.decimal("1.234,56"))
        assertEquals(BigDecimal("1234.56"), TransferCsv.decimal("1,234.56"))
        assertEquals(BigDecimal("0.10"), TransferCsv.decimal("0.10"))
        assertFails { TransferCsv.decimal("12 EUR") }
        assertFails { TransferCsv.decimal("NaN") }
        assertFails { TransferCsv.decimal("1e100") }
    }

    @Test fun exportScopesCannotIncludeOtherUsersOrUnrelatedCouples() {
        assertEquals("user_id=eq.user-1", TransferRepository.participantScope("transactions", "user-1"))
        assertTrue(TransferRepository.participantScope("couple_connection_requests", "user-1").contains("sender_id.eq.user-1"))
        assertTrue(TransferRepository.participantScope("couple_connections", "user-1").contains("user_b.eq.user-1"))
        assertFails { TransferRepository.participantScope("profiles", "user-1") }
        assertFails { TransferRepository.participantScope("shared_expenses", "user-1") }
        TransferRepository.validateExportOwner("transactions", JSONObject().put("user_id", "user-1"), "user-1", emptySet())
        assertFails { TransferRepository.validateExportOwner("transactions", JSONObject().put("user_id", "user-2"), "user-1", emptySet()) }
        TransferRepository.validateExportOwner("shared_expenses", JSONObject().put("connection_id", "ours"), "user-1", setOf("ours"))
        assertFails { TransferRepository.validateExportOwner("shared_expenses", JSONObject().put("connection_id", "other"), "user-1", setOf("ours")) }
        assertEquals(25, TransferRepository.USER_TABLES.size)
        assertEquals(25, TransferRepository.USER_TABLES.distinct().size)
    }

    private fun assertFails(block: () -> Unit) {
        try { block(); fail("Expected TransferException") }
        catch (_: TransferException) { }
    }
}
