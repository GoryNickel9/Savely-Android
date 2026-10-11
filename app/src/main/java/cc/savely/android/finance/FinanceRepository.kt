package cc.savely.android.finance

import cc.savely.android.data.SupabaseApi
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

data class RecurringProcessingResult(val created: Int = 0, val existing: Int = 0, val advanced: Int = 0) {
    val message: String get() = "Elaborazione completata: $created transazioni create, $existing già presenti, $advanced scadenze aggiornate."
}

class RecurringProcessingException(val result: RecurringProcessingResult, stage: String, cause: Exception) : Exception(
    "Elaborazione interrotta durante $stage: ${cause.message ?: "operazione fallita"}. " +
        "${result.created} transazioni create, ${result.existing} già presenti, ${result.advanced} scadenze aggiornate. " +
        "Aggiorna i dati e verifica i movimenti prima di elaborare nuovamente: una richiesta fallita può avere già salvato dati.", cause,
)

class FinanceRepository(private val api: SupabaseApi, private val dismissals: RecurringDismissals = MemoryRecurringDismissals()) {
    private var loadedUserId: String? = null
    private suspend fun userId(): String = UUID.fromString(
        api.session()?.userId ?: error("Sessione scaduta. Accedi nuovamente."),
    ).toString()

    private suspend fun rows(table: String, query: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val identifiers = mutableSetOf<String>()
        var offset = 0
        while (true) {
            val page: JSONArray = api.select(table, "$query&limit=500&offset=$offset")
            if (page.length() == 0) return result
            check(page.length() <= 500 && result.size + page.length() <= 100_000) {
                "Archivio oltre il limite: caricamento interrotto senza mostrare totali parziali."
            }
            repeat(page.length()) {
                val row = page.getJSONObject(it)
                check(identifiers.add(row.getString("id"))) { "Archivio modificato durante la lettura. Aggiorna i dati." }
                result.add(row)
            }
            offset += page.length()
        }
    }

    suspend fun load(section: String): FinanceData {
        val user = userId()
        val ownerRevision = api.sessionOwnerRevision()
        val own = "user_id=eq.$user"
        val categories = rows("categories", "select=*&$own&deleted_at=is.null&order=name.asc,id.asc").map(::categoryFromJson)
        val profile = api.select("profiles", "select=default_currency&$own&limit=1")
        val currency = if (profile.length() == 0) "EUR" else profile.getJSONObject(0).nullableString("default_currency") ?: "EUR"
        val transactions = if (section in setOf("dashboard", "transactions", "budget", "charts", "net-worth", "insights", "recurring")) {
            rows("transactions", "select=*&$own&deleted_at=is.null&order=date.desc,id.asc").map(::transactionFromJson)
        } else emptyList()
        val budgets = if (section in setOf("dashboard", "budget", "insights")) {
            rows("budgets", "select=*&$own&order=id.asc").map(::budgetFromJson)
        } else emptyList()
        val recurring = if (section in setOf("recurring", "insights")) {
            rows("recurring_expenses", "select=*&$own&deleted_at=is.null&order=next_due_date.asc,id.asc").map(::recurringFromJson)
        } else emptyList()
        val assets = if (section in setOf("portfolio", "net-worth", "insights")) {
            rows("portfolio_assets", "select=*&$own&order=created_at.desc,id.asc").map(::assetFromJson)
        } else emptyList()
        val snapshots = if (section in setOf("net-worth", "insights")) {
            val order = if (section == "insights") "date.asc,id.asc" else "date.desc,id.asc"
            rows("net_worth_snapshots", "select=id,date,net_worth,components&$own&order=$order").map(::snapshotFromJson)
        } else emptyList()
        val lastUpdate = if (section == "portfolio") {
            val logs = api.select("manual_price_updates", "select=updated_at&$own&order=updated_at.desc&limit=1")
            if (logs.length() == 0) null else Instant.parse(logs.getJSONObject(0).getString("updated_at"))
        } else null
        val ignored = if (section in setOf("recurring", "insights")) withContext(Dispatchers.IO) { dismissals.keys(user) } else emptySet()
        // Portfolio web reads the owner's cards even when the TCG navigation permission is disabled.
        val tcgCards = if (section == "portfolio") {
            rows("tgc_cards", "select=id,category,quantity,purchase_price,current_price,currency&$own&order=created_at.desc,id.asc")
                .map(::tcgCardFromJson)
        } else emptyList()
        val history = if (section == "portfolio") {
            rows("asset_price_history", "select=id,asset_id,price,recorded_at&$own&order=recorded_at.asc,id.asc")
                .map(::priceHistoryFromJson)
        } else emptyList()
        check(userId() == user && api.sessionOwnerRevision() == ownerRevision) { "Account cambiato durante il caricamento. Aggiorna i dati." }
        loadedUserId = user
        return FinanceData(transactions, categories, budgets, recurring, currency, assets, snapshots, lastUpdate, ignored, tcgCards, history)
    }

    suspend fun dismissRecurringSuggestion(key: String) {
        val user = loadedUserId ?: error("Carica le ricorrenze prima di ignorare un suggerimento")
        require(key.isNotBlank()) { "Suggerimento non valido" }
        withContext(Dispatchers.IO) { dismissals.ignore(user, key) }
    }

    private suspend fun validateCategory(id: String?, type: TransactionType) {
        if (id == null) return
        val uuid = UUID.fromString(id)
        val category = api.select("categories", "select=type&id=eq.$uuid&user_id=eq.${userId()}&deleted_at=is.null")
        require(category.length() == 1 && category.getJSONObject(0).getString("type") == type.wire) {
            "La categoria non esiste o non corrisponde al tipo di transazione"
        }
    }

    suspend fun saveTransaction(
        id: String?, type: TransactionType, amount: String, currency: String,
        rate: String, categoryId: String?, description: String, date: String,
    ) {
        require(currency in supportedCurrencies) { "Valuta non supportata" }
        validateCategory(categoryId, type)
        val body = transactionPayload(type, parseMoney(amount), currency,
            if (currency == "EUR") BigDecimal.ONE else parseExchangeRate(rate),
            categoryId, description.trim(), LocalDate.parse(date))
        save("transactions", id, body)
    }

    suspend fun saveCategory(id: String?, name: String, type: TransactionType, icon: String, color: String) {
        require(name.trim().isNotEmpty()) { "Inserisci il nome della categoria" }
        require(Regex("#[0-9a-fA-F]{6}").matches(color)) { "Colore non valido: usa #RRGGBB" }
        save("categories", id, JSONObject().put("name", name.trim()).put("type", type.wire)
            .put("icon", icon).put("color", color))
    }

    suspend fun saveBudget(id: String?, categoryId: String, amount: String) {
        if (id == null) validateCategory(categoryId, TransactionType.EXPENSE)
        val body = JSONObject().put("amount", parseMoney(amount, allowZero = true).toPlainString())
        if (id == null) body.put("category_id", categoryId).put("month", 1).put("year", 2000).put("currency", "EUR")
        save("budgets", id, body)
    }

    suspend fun saveRecurring(
        id: String?, name: String, amount: String, currency: String, categoryId: String?,
        frequency: RecurringFrequency, weekInterval: String, date: String, active: Boolean,
    ) {
        require(name.trim().isNotEmpty()) { "Inserisci il nome della ricorrenza" }
        require(currency in supportedCurrencies) { "Valuta non supportata" }
        val interval = if (frequency == RecurringFrequency.WEEKLY) weekInterval.toIntOrNull() else 1
        require(interval != null && interval in 1..520) { "Inserisci da 1 a 520 settimane" }
        validateCategory(categoryId, TransactionType.EXPENSE)
        save("recurring_expenses", id, JSONObject().put("name", name.trim())
            .put("amount", parseMoney(amount).toPlainString()).put("currency", currency)
            .put("category_id", categoryId ?: JSONObject.NULL).put("frequency", frequency.wire)
            .put("week_interval", interval).put("next_due_date", LocalDate.parse(date).toString()).put("is_active", active))
    }

    suspend fun setRecurringActive(id: String, active: Boolean) {
        api.update("recurring_expenses", UUID.fromString(id).toString(), JSONObject().put("is_active", active),
            "user_id=eq.${userId()}&deleted_at=is.null")
    }

    /** Matches web processDueExpenses: one occurrence per due record and deduplication by name/month.
     * This is not a backend transaction or an exactly-once guarantee against another client or cron.
     * All responses are checked; a failed insert never advances the schedule. No automatic retry.
     */
    suspend fun processDueRecurring(today: LocalDate = LocalDate.now()): RecurringProcessingResult {
        val user = userId()
        val own = "user_id=eq.$user"
        var result = RecurringProcessingResult()
        var stage = "il caricamento delle ricorrenze"
        try {
            val due = rows("recurring_expenses", "select=*&$own&deleted_at=is.null&is_active=eq.true&next_due_date=lte.$today&order=next_due_date.asc,id.asc")
                .map(::recurringFromJson)
            for (expense in due) {
                // Defensive checks also protect against malformed imported data or stale fixture responses.
                if (!expense.active || expense.nextDueDate > today) continue
                stage = "la validazione della ricorrenza"
                require(expense.name.isNotBlank()) { "Ricorrenza senza nome" }
                val amount = parseMoney(expense.amount.toPlainString())
                require(expense.currency in supportedCurrencies) { "Valuta della ricorrenza non supportata" }
                val next = nextDueDate(expense.nextDueDate, expense.frequency, expense.weekInterval)
                stage = "la verifica delle transazioni"
                val start = expense.nextDueDate.withDayOfMonth(1)
                val end = start.plusMonths(1)
                val description = URLEncoder.encode("eq.${expense.name}", StandardCharsets.UTF_8.name())
                // limit=1 treats multiple matching transactions as already present instead of a maybeSingle error.
                val existing = api.select("transactions", "select=id&$own&deleted_at=is.null&description=$description&date=gte.$start&date=lt.$end&limit=1")
                if (existing.length() == 0) {
                    stage = "il salvataggio della transazione"
                    // Same payload as web/cron: the backend supplies its exchange-rate default.
                    api.insert("transactions", JSONObject().put("user_id", user).put("type", "expense")
                        .put("amount", amount.toPlainString()).put("currency", expense.currency)
                        .put("category_id", expense.categoryId ?: JSONObject.NULL).put("description", expense.name)
                        .put("date", expense.nextDueDate.toString()))
                    result = result.copy(created = result.created + 1)
                } else result = result.copy(existing = result.existing + 1)
                stage = "l’aggiornamento della scadenza"
                // Compare date/activity: protect an intervening date edit or pause, not every record field.
                api.update("recurring_expenses", UUID.fromString(expense.id).toString(),
                    JSONObject().put("next_due_date", next.toString()),
                    "$own&deleted_at=is.null&is_active=eq.true&next_due_date=eq.${expense.nextDueDate}")
                result = result.copy(advanced = result.advanced + 1)
            }
            return result
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw RecurringProcessingException(result, stage, exception)
        }
    }

    private suspend fun save(table: String, id: String?, body: JSONObject) {
        val user = userId()
        if (id == null) api.insert(table, body.put("user_id", user))
        else api.update(table, UUID.fromString(id).toString(), body, "user_id=eq.$user")
    }

    suspend fun saveAsset(id: String?, name: String, symbol: String, type: FinanceAssetType, quantity: String,
        purchasePrice: String, currentPrice: String, currency: String, date: String, notes: String) {
        require(name.trim().isNotEmpty()) { "Inserisci il nome dell’asset" }
        require(currency in supportedCurrencies) { "Valuta non supportata" }
        val parsedQuantity = parseQuantity(quantity)
        val body = JSONObject().put("name", name.trim()).put("symbol", symbol.trim().uppercase().ifBlank { null } ?: JSONObject.NULL)
            .put("type", type.wire).put("quantity", parsedQuantity.toPlainString())
            .put("purchase_price", parseMoney(purchasePrice, allowZero = true).toPlainString())
            .put("current_price", if (currentPrice.isBlank()) JSONObject.NULL else parseMoney(currentPrice, allowZero = true).toPlainString())
            .put("currency", currency).put("purchase_date", LocalDate.parse(date).toString()).put("notes", notes.ifBlank { null } ?: JSONObject.NULL)
        save("portfolio_assets", id, body)
    }

    suspend fun closeAsset(asset: FinanceAsset, soldAt: String, soldPrice: String) {
        closeAssetGroup(listOf(asset), soldAt, soldPrice)
    }

    /** Sequential optimistic writes, not a database transaction. Stop on the first uncertain result. */
    suspend fun closeAssetGroup(assets: List<FinanceAsset>, soldAt: String, soldPrice: String) {
        require(assets.isNotEmpty() && assets.size <= 100_000) { "Seleziona una posizione aperta" }
        require(assets.map { it.id }.distinct().size == assets.size) { "Acquisti duplicati nella selezione" }
        require(portfolioPositionGroups(assets).size == 1 && assets.none { it.closed }) { "La selezione contiene strumenti diversi o acquisti già chiusi" }
        require(assets.all { it.currency in supportedCurrencies }) { "Valuta non supportata" }
        val date = LocalDate.parse(soldAt)
        val price = parseMoney(soldPrice)
        assets.forEach { validatePositionClosure(date, price, it.purchaseDate) }
        val owner = userId()
        val revision = api.sessionOwnerRevision()
        val nullCurrencies = mutableSetOf<String>()
        suspend fun checkOwner() {
            check(userId() == owner && api.sessionOwnerRevision() == revision) { "Account cambiato. Aggiorna i dati prima di continuare." }
        }
        // Validate every selected row before the first write; the PATCH predicates guard later races.
        assets.forEach { expected ->
            try {
                checkOwner()
                val id = UUID.fromString(expected.id).toString()
                val rows = api.select("portfolio_assets", "select=*&id=eq.$id&user_id=eq.$owner&limit=1")
                require(rows.length() == 1 && rows.getJSONObject(0).getString("user_id") == owner &&
                    samePositionPurchase(expected, assetFromJson(rows.getJSONObject(0)))) {
                    "Un acquisto è cambiato o non è più disponibile. Aggiorna i dati prima di chiudere la posizione."
                }
                if (rows.getJSONObject(0).isNull("currency")) nullCurrencies.add(expected.id)
                checkOwner()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                throw PositionCloseException(0, assets.size, expected.id, exception, attemptedWrite = false)
            }
        }
        var confirmed = 0
        for (asset in assets) {
            try {
                checkOwner()
                val symbol = asset.symbol?.let { "eq." + URLEncoder.encode(it, StandardCharsets.UTF_8.name()) } ?: "is.null"
                val soldDate = asset.soldAt?.let { "eq.$it" } ?: "is.null"
                val soldValue = asset.soldPrice?.let { "eq.${it.toPlainString()}" } ?: "is.null"
                val currency = if (asset.id in nullCurrencies) "is.null" else "eq.${asset.currency}"
                val returned = api.update("portfolio_assets", UUID.fromString(asset.id).toString(),
                    JSONObject().put("sold_at", date.toString()).put("sold_price", price.toPlainString()),
                    "user_id=eq.$owner&type=eq.${asset.type.wire}&currency=$currency&symbol=$symbol" +
                        "&quantity=eq.${asset.quantity.toPlainString()}&purchase_price=eq.${asset.purchasePrice.toPlainString()}" +
                        "&purchase_date=eq.${asset.purchaseDate}&sold_at=$soldDate&sold_price=$soldValue")
                check(returned.getString("id") == asset.id && returned.getString("user_id") == owner) { "Risposta della chiusura non valida" }
                val saved = assetFromJson(returned)
                check(saved.closed && saved.soldAt == date && saved.soldPrice!!.compareTo(price) == 0) { "Risposta della chiusura non valida" }
                confirmed++
                checkOwner()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                throw PositionCloseException(confirmed, assets.size, asset.id, exception)
            }
        }
    }

    suspend fun updatePrices() {
        val user = userId()
        val logs = api.select("manual_price_updates", "select=updated_at&user_id=eq.$user&order=updated_at.desc&limit=1")
        if (logs.length() > 0) require(Instant.parse(logs.getJSONObject(0).getString("updated_at")).plusSeconds(86_400) <= Instant.now()) {
            "L’aggiornamento prezzi è disponibile una volta ogni 24 ore"
        }
        val result = api.invokeFunction("update-prices", JSONObject().put("user_id", user)) as? JSONObject
            ?: error("Il servizio prezzi ha restituito una risposta non valida")
        api.insert("manual_price_updates", JSONObject().put("user_id", user)
            .put("assets_updated", result.optInt("updated", 0)).put("assets_checked", result.optInt("checked", 0)))
        val errors = result.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val symbols = (0 until minOf(errors.length(), 5)).mapNotNull { index ->
                errors.optJSONObject(index)?.nullableString("symbol")?.take(40)
            }.joinToString(", ")
            error("Aggiornamento parziale: ${result.optInt("updated", 0)} prezzi aggiornati, ${errors.length()} errori" +
                (if (symbols.isBlank()) "." else " ($symbols).") + " Aggiorna la schermata per leggere i prezzi salvati.")
        }
    }

    suspend fun delete(table: String, id: String) {
        require(table in setOf("transactions", "categories", "budgets", "recurring_expenses", "portfolio_assets"))
        val user = userId()
        val uuid = UUID.fromString(id).toString()
        if (table == "categories") {
            // The web detaches transaction categories before soft deletion. Keep the category on a partial failure.
            val affected = rows("transactions", "select=id&user_id=eq.$user&category_id=eq.$uuid&order=id.asc")
            for (tx in affected) api.update("transactions", tx.getString("id"),
                JSONObject().put("category_id", JSONObject.NULL), "user_id=eq.$user")
            api.updateMinimal(table, uuid, JSONObject().put("deleted_at", Instant.now().toString()), "user_id=eq.$user")
        } else if (table == "recurring_expenses") {
            api.updateMinimal(table, uuid, JSONObject().put("deleted_at", Instant.now().toString()), "user_id=eq.$user")
        } else api.delete(table, uuid, "user_id=eq.$user")
    }
}
