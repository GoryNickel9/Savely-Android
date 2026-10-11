package cc.savely.android.transfer

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

class TransferException(message: String) : Exception(message)

enum class ImportFormat(val label: String) { REVOLUT("Revolut"), NORMALIZED("CSV transazioni"), BANK("BBVA / Trade Republic CSV") }

data class ImportTransaction(
    val row: Int,
    val description: String,
    val date: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val type: String,
    val categoryId: String? = null,
    val exchangeRateEur: BigDecimal? = if (currency == "EUR") BigDecimal.ONE else null,
) {
    fun duplicateKey(): String = listOf(description, date.toString(), amount.setScale(2, RoundingMode.HALF_UP).toPlainString(), currency, type).joinToString("\u0000")
    fun body(userId: String, category: String, rate: BigDecimal? = exchangeRateEur): JSONObject {
        if (rate == null || rate.signum() <= 0) throw TransferException("Imposta il cambio EUR prima di importare una valuta estera.")
        return JSONObject().put("user_id", userId).put("description", description).put("date", date.toString())
            .put("amount", amount).put("currency", currency).put("type", type).put("category_id", category)
            .put("exchange_rate_eur", rate)
    }
}

data class ImportProblem(val row: Int, val message: String)
data class ParsedImport(val transactions: List<ImportTransaction>, val problems: List<ImportProblem>, val skipped: Int)

/** RFC-style CSV quoting, bounded input and decimal arithmetic; never performs a backend write. */
object TransferCsv {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_ROWS = 10_000
    val currencies = setOf("EUR", "USD", "GBP", "CHF", "JPY", "CAD", "AUD", "CNY", "IDR")

    fun parseRows(input: String, delimiter: Char = detectDelimiter(input)): List<List<String>> {
        if (input.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw TransferException("Il file supera 5 MiB.")
        if (delimiter != ',' && delimiter != ';') throw TransferException("Separatore CSV non valido.")
        val text = input.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var closedQuote = false
        var index = 0
        fun finishCell() { row += cell.toString(); cell.setLength(0); closedQuote = false }
        fun finishRow() {
            finishCell()
            if (row.any { it.isNotBlank() }) rows += row.toList()
            row.clear()
            if (rows.size > MAX_ROWS + 1) throw TransferException("Il file supera 10.000 righe di dati.")
        }
        while (index < text.length) {
            val char = text[index]
            if (quoted) {
                if (char == '"') {
                    if (text.getOrNull(index + 1) == '"') { cell.append('"'); index++ }
                    else { quoted = false; closedQuote = true }
                } else cell.append(char)
            } else when (char) {
                '"' -> {
                    if (cell.isNotEmpty() || closedQuote) throw TransferException("Virgolette CSV non valide nella riga ${rows.size + 1}.")
                    quoted = true
                }
                delimiter -> finishCell()
                '\r', '\n' -> {
                    if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                    finishRow()
                }
                else -> {
                    if (closedQuote) {
                        if (!char.isWhitespace()) throw TransferException("Testo dopo una cella CSV chiusa nella riga ${rows.size + 1}.")
                    } else cell.append(char)
                }
            }
            index++
        }
        if (quoted) throw TransferException("Cella CSV con virgolette non chiuse.")
        if (cell.isNotEmpty() || row.isNotEmpty() || closedQuote) finishRow()
        return rows
    }

    fun parse(input: String, format: ImportFormat, bankCurrency: String = "EUR"): ParsedImport {
        val rows = parseRows(input)
        if (rows.isEmpty()) throw TransferException("Il file CSV è vuoto.")
        val headerIndex = if (format == ImportFormat.BANK) rows.indexOfFirst { row ->
            row.any { it.lowercase().contains("data") } && row.any { it.lowercase().contains("descrizione") || it.lowercase().contains("nota") }
        } else 0
        if (headerIndex == -1) throw TransferException("Intestazioni del CSV banca non riconosciute.")
        val headers = rows[headerIndex].map { it.trim() }
        if (headers.distinct().size != headers.size) throw TransferException("Il CSV contiene intestazioni duplicate.")
        if (format == ImportFormat.REVOLUT && !headers.containsAll(listOf("Type", "State", "Completed Date", "Description", "Amount", "Currency"))) {
            throw TransferException("Servono Type, State, Completed Date, Description, Amount e Currency.")
        }
        if (format == ImportFormat.NORMALIZED && !headers.containsAll(listOf("date", "description", "amount", "currency", "type"))) {
            throw TransferException("Servono date, description, amount, currency e type.")
        }
        val transactions = mutableListOf<ImportTransaction>()
        val problems = mutableListOf<ImportProblem>()
        var skipped = 0
        for (index in headerIndex + 1 until rows.size) {
            val number = index + 1
            val values = rows[index]
            if (values.size != headers.size) { problems += ImportProblem(number, "Numero di colonne diverso dalle intestazioni."); continue }
            val row = headers.zip(values).toMap()
            try {
                val parsed = when (format) {
                    ImportFormat.REVOLUT -> {
                        if (row["Type"] != "Card Payment" || row["State"] != "COMPLETED") null
                        else create(number, row.getValue("Description"), row.getValue("Completed Date").substringBefore(' '),
                            decimal(row.getValue("Amount")).abs().setScale(2, RoundingMode.HALF_UP), row.getValue("Currency"), "expense")
                    }
                    ImportFormat.NORMALIZED -> {
                        if (row["entity"] != null && row["entity"] != "transactions") null
                        else create(number, row.getValue("description"), row.getValue("date"), decimal(row.getValue("amount")),
                            row.getValue("currency"), row.getValue("type"), row["category_id"]?.takeIf { it.isNotBlank() },
                            row["exchange_rate_eur"]?.takeIf { it.isNotBlank() }?.let(::decimal))
                    }
                    ImportFormat.BANK -> parseBank(number, row, bankCurrency)
                }
                if (parsed == null) skipped++ else transactions += parsed
            } catch (error: TransferException) { problems += ImportProblem(number, error.message ?: "Riga non valida.") }
        }
        return ParsedImport(transactions, problems, skipped)
    }

    fun serialize(rows: List<JSONObject>): String {
        if (rows.isEmpty()) return ""
        val headers = linkedSetOf<String>()
        rows.forEach { row -> row.keys().forEach { headers += it } }
        return buildString {
            append(headers.joinToString(",") { escape(it) })
            for (row in rows) {
                append('\n')
                append(headers.joinToString(",") { escape(jsonValue(row.opt(it))) })
            }
        }
    }

    fun escape(value: String): String = if (value.any { it in ",;\"\r\n" } || value.firstOrNull()?.isWhitespace() == true || value.lastOrNull()?.isWhitespace() == true) {
        "\"${value.replace("\"", "\"\"")}\""
    } else value

    fun jsonValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONObject, is JSONArray -> value.toString()
        is BigDecimal -> value.toPlainString()
        else -> value.toString()
    }

    private fun parseBank(number: Int, row: Map<String, String>, defaultCurrency: String): ImportTransaction? {
        val lower = row.mapKeys { it.key.lowercase() }
        fun field(match: (String) -> Boolean): String? = lower.entries.firstOrNull { match(it.key) }?.value
        val description = field { it.contains("descrizione") || it.contains("nota") } ?: throw TransferException("Descrizione assente.")
        val tipo = field { it.startsWith("tipo") }.orEmpty()
        if (description.uppercase().contains("TRASFERIMENTO") || tipo.uppercase().contains("TRASFERIMENTO") || description.uppercase().contains("SPORT. AUT.")) return null
        if (tipo.lowercase().let { it.contains("saving") || it.contains("buy trade") || it.contains("savings plan") }) {
            throw TransferException("Investimento ISIN non supportato: nessuna posizione sarà creata.")
        }
        val income = field { it.contains("entrata") }
        val expense = field { it.contains("uscita") }
        val signed = if (income == null && expense == null) field { it.contains("importo") } else null
        val amount: BigDecimal
        val type: String
        if (signed != null) {
            val parsed = decimal(signed)
            amount = parsed.abs()
            type = if (parsed.signum() > 0) "income" else "expense"
        } else {
            val incoming = income?.takeIf { it.isNotBlank() }?.let(::decimal) ?: BigDecimal.ZERO
            val outgoing = expense?.takeIf { it.isNotBlank() }?.let(::decimal) ?: BigDecimal.ZERO
            if (incoming.signum() != 0 && outgoing.signum() != 0) throw TransferException("Entrata e uscita entrambe valorizzate.")
            amount = if (incoming.signum() > 0) incoming else outgoing.abs()
            type = if (incoming.signum() > 0) "income" else "expense"
            if (income == null && expense == null) throw TransferException("Colonne Importo oppure Entrata/Uscita assenti.")
        }
        if (amount.signum() == 0) return null
        return create(number, description, field { it.contains("data") } ?: "", amount,
            field { it.contains("valuta") || it == "currency" }?.takeIf { it.isNotBlank() } ?: defaultCurrency, type)
    }

    private fun create(row: Int, description: String, date: String, amount: BigDecimal, currency: String, type: String, categoryId: String? = null, rate: BigDecimal? = null): ImportTransaction {
        if (description.isBlank() || description.length > 1000) throw TransferException("Descrizione assente o superiore a 1.000 caratteri.")
        val normalizedCurrency = currency.trim().uppercase()
        if (normalizedCurrency !in currencies) throw TransferException("Valuta non supportata: ${currency.take(12)}.")
        val normalizedType = type.trim().lowercase()
        if (normalizedType !in setOf("income", "expense")) throw TransferException("Tipo richiesto: income oppure expense.")
        if (amount.signum() <= 0) throw TransferException("L'importo deve essere positivo; usa type per indicare entrata o uscita.")
        if (amount.precision() > 18 || amount.scale() > 8) throw TransferException("Precisione dell'importo non supportata.")
        val day = try {
            if (date.trim().contains('/')) LocalDate.parse(date.trim(), DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT))
            else LocalDate.parse(date.trim().substringBefore('T'))
        } catch (_: Exception) { throw TransferException("Data non valida; usa YYYY-MM-DD oppure DD/MM/YYYY.") }
        if (rate != null && rate.signum() <= 0) throw TransferException("Il cambio EUR deve essere positivo.")
        return ImportTransaction(row, description, day, amount, normalizedCurrency, normalizedType, categoryId,
            if (normalizedCurrency == "EUR") BigDecimal.ONE else rate)
    }

    fun decimal(input: String): BigDecimal {
        val text = input.trim()
        if (text.length > 64) throw TransferException("Importo numerico troppo lungo.")
        val normalized = when {
            text.contains(',') && text.contains('.') && text.lastIndexOf('.') > text.lastIndexOf(',') -> text.replace(",", "")
            text.contains(',') -> text.replace(".", "").replace(',', '.')
            else -> text
        }
        if (!Regex("[+-]?[0-9]+(?:\\.[0-9]+)?").matches(normalized)) throw TransferException("Importo numerico non valido.")
        return try { normalized.toBigDecimal() } catch (_: Exception) { throw TransferException("Importo numerico non valido.") }
    }

    private fun detectDelimiter(input: String): Char {
        var quoted = false
        var comma = 0
        var semicolon = 0
        var index = 0
        while (index < input.length) {
            val char = input[index]
            if (char == '"') {
                if (quoted && input.getOrNull(index + 1) == '"') index++ else quoted = !quoted
            } else if (!quoted) when (char) {
                ',' -> comma++
                ';' -> semicolon++
                '\n', '\r' -> if (comma > 0 || semicolon > 0) break
            }
            index++
        }
        return if (semicolon > comma) ';' else ','
    }
}
