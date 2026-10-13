package cc.savely.android.finance

import cc.savely.android.data.SupabaseApi
import cc.savely.android.data.ApiError
import cc.savely.android.data.ApiException
import cc.savely.android.data.CoupleRepository
import cc.savely.android.data.couplePartnerAmount
import cc.savely.android.data.coupleSplit
import cc.savely.android.data.parseBackendInstant
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

/** An attempted multi-step write can leave the displayed dataset stale even when its request failed. */
class FinancePendingRefreshException(message: String, cause: Exception) : IllegalStateException(message, cause)

class FinanceRepository(private val api: SupabaseApi, private val dismissals: RecurringDismissals = MemoryRecurringDismissals(),
    private val exchangeRates: FinanceExchangeRates = FinanceExchangeRates()) {
    private var loadedUserId: String? = null
    private var loadedOwner: FinanceOwner? = null
    private suspend fun userId(): String = UUID.fromString(
        api.session()?.userId ?: error("Sessione scaduta. Accedi nuovamente."),
    ).toString()

    private data class FinanceOwner(val id: String, val revision: Long)
    private suspend fun ownerSnapshot(): FinanceOwner {
        val session = api.session() ?: error("Sessione scaduta. Accedi nuovamente.")
        val revision = api.sessionOwnerRevision(session) ?: error("Account cambiato. Aggiorna i dati prima di continuare.")
        val owner = FinanceOwner(UUID.fromString(session.userId).toString(), revision)
        // A form belongs to the accepted session that supplied its data, including a new login by the same user.
        check(loadedOwner == null || loadedOwner == owner) { "Account cambiato. Aggiorna i dati prima di continuare." }
        return owner
    }
    private suspend fun checkOwner(owner: FinanceOwner) {
        check(userId() == owner.id && api.isSessionOwnerCurrent(owner.revision)) { "Account cambiato. Aggiorna i dati prima di continuare." }
    }

    private suspend fun rows(table: String, query: String, owner: FinanceOwner): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val identifiers = mutableSetOf<String>()
        var offset = 0
        while (true) {
            val page: JSONArray = api.select(table, "$query&limit=500&offset=$offset",
                expectedOwner = owner.id, expectedRevision = owner.revision)
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
        val owner = ownerSnapshot()
        val user = owner.id
        val ownerRevision = owner.revision
        val own = "user_id=eq.$user"
        val categories = rows("categories", "select=*&$own&deleted_at=is.null&order=name.asc,id.asc", owner).map(::categoryFromJson)
        val profileFields = if (section == "transactions") "default_currency,permissions" else "default_currency"
        val profile = api.select("profiles", "select=$profileFields&$own&limit=1", expectedOwner = user, expectedRevision = ownerRevision)
        val currency = if (profile.length() == 0) "EUR" else profile.getJSONObject(0).nullableString("default_currency") ?: "EUR"
        val transactions = if (section in setOf("dashboard", "transactions", "budget", "charts", "net-worth", "insights", "recurring")) {
            rows("transactions", "select=*&$own&deleted_at=is.null&order=date.desc,id.asc", owner).map(::transactionFromJson)
        } else emptyList()
        val budgets = if (section in setOf("dashboard", "budget", "insights")) {
            rows("budgets", "select=*&$own&order=id.asc", owner).map(::budgetFromJson)
        } else emptyList()
        val recurring = if (section in setOf("recurring", "insights")) {
            rows("recurring_expenses", "select=*&$own&deleted_at=is.null&order=next_due_date.asc,id.asc", owner).map(::recurringFromJson)
        } else emptyList()
        val assets = if (section in setOf("portfolio", "net-worth", "insights")) {
            rows("portfolio_assets", "select=*&$own&order=created_at.desc,id.asc", owner).map(::assetFromJson)
        } else emptyList()
        val snapshots = if (section in setOf("net-worth", "insights")) {
            val order = if (section == "insights") "date.asc,id.asc" else "date.desc,id.asc"
            rows("net_worth_snapshots", "select=id,date,net_worth,components&$own&order=$order", owner).map(::snapshotFromJson)
        } else emptyList()
        val lastUpdate = if (section == "portfolio") {
            val logs = api.select("manual_price_updates", "select=updated_at&$own&order=updated_at.desc&limit=1",
                expectedOwner = user, expectedRevision = ownerRevision)
            if (logs.length() == 0) null else parseBackendInstant(logs.getJSONObject(0).getString("updated_at"))
        } else null
        val ignored = if (section in setOf("recurring", "insights")) withContext(Dispatchers.IO) { dismissals.keys(user) } else emptySet()
        // Portfolio web reads the owner's cards even when the TCG navigation permission is disabled.
        val tcgCards = if (section == "portfolio") {
            rows("tgc_cards", "select=id,category,quantity,purchase_price,current_price,currency&$own&order=created_at.desc,id.asc", owner)
                .map(::tcgCardFromJson)
        } else emptyList()
        val history = if (section == "portfolio") {
            rows("asset_price_history", "select=id,asset_id,price,recorded_at&$own&order=recorded_at.asc,id.asc", owner)
                .map(::priceHistoryFromJson)
        } else emptyList()
        val connection = if (section == "transactions" && profile.length() == 1 &&
            SupabaseApi.parsePermissions(profile.getJSONObject(0).optJSONObject("permissions"))["couple_expenses"] == true) {
            val connections = api.select("couple_connections", "select=*&or=(user_a.eq.$user,user_b.eq.$user)&revoked_at=is.null&limit=2",
                expectedOwner = user, expectedRevision = ownerRevision)
            require(connections.length() <= 1) { "Più connessioni attive: verifica il tuo account." }
            connections.optJSONObject(0)?.let { financeConnectionFromJson(it, user) }
        } else null
        val shares = if (connection == null) emptyList() else {
            rows("shared_expenses_view", "select=*&connection_id=eq.${connection.id}&order=date.desc,id.asc", owner)
                .filter { it.isNull("tx_deleted_at") }.map { financeSharedExpenseFromJson(it, connection) }
        }
        check(userId() == user && api.sessionOwnerRevision() == ownerRevision) { "Account cambiato durante il caricamento. Aggiorna i dati." }
        loadedUserId = user
        loadedOwner = owner
        return FinanceData(transactions, categories, budgets, recurring, currency, assets, snapshots, lastUpdate, ignored, tcgCards, history,
            user, connection, shares)
    }

    suspend fun dismissRecurringSuggestion(key: String) {
        val user = loadedUserId ?: error("Carica le ricorrenze prima di ignorare un suggerimento")
        require(key.isNotBlank()) { "Suggerimento non valido" }
        withContext(Dispatchers.IO) { dismissals.ignore(user, key) }
    }

    private suspend fun validateCategory(id: String?, type: TransactionType, owner: FinanceOwner) {
        if (id == null) return
        val uuid = UUID.fromString(id)
        val category = api.select("categories", "select=type&id=eq.$uuid&user_id=eq.${owner.id}&deleted_at=is.null",
            expectedOwner = owner.id, expectedRevision = owner.revision)
        require(category.length() == 1 && category.getJSONObject(0).getString("type") == type.wire) {
            "La categoria non esiste o non corrisponde al tipo di transazione"
        }
    }

    suspend fun saveTransaction(
        id: String?, type: TransactionType, amount: String, currency: String,
        rate: String, categoryId: String?, description: String, date: String,
        share: TransactionShareRequest? = null,
    ) {
        require(currency in supportedCurrencies) { "Valuta non supportata" }
        val parsedAmount = parseMoney(amount)
        val parsedDate = LocalDate.parse(date)
        val manualRate = if (currency != "EUR" && rate.isNotBlank()) parseExchangeRate(rate) else null
        val identity = ownerSnapshot()
        val owner = identity.id
        val revision = identity.revision
        if (share != null) {
            require(id == null && type == TransactionType.EXPENSE) { "Solo una nuova uscita può essere condivisa dal form" }
            coupleSplit(parsedAmount, share.customPartner?.let(::couplePartnerAmount))
            val connection = UUID.fromString(share.connectionId).toString()
            val active = api.select("couple_connections", "select=*&id=eq.$connection&or=(user_a.eq.$owner,user_b.eq.$owner)&revoked_at=is.null&limit=1",
                expectedOwner = owner, expectedRevision = revision)
            require(active.length() == 1) { "La connessione non è più attiva. Aggiorna la schermata." }
            financeConnectionFromJson(active.getJSONObject(0), owner)
        }
        validateCategory(categoryId, type, identity)
        val body = transactionPayload(type, parsedAmount, currency,
            if (currency == "EUR") BigDecimal.ONE else manualRate ?: exchangeRates.toEuro(currency),
            categoryId, description.trim(), parsedDate)
        check(userId() == owner && api.sessionOwnerRevision() == revision) { "Account cambiato prima del salvataggio. Aggiorna i dati." }
        if (share == null) save("transactions", id, body, identity)
        else {
            // Preallocate in the domain too: an uncertain INSERT has an ID the user can reconcile.
            val transactionId = UUID.randomUUID().toString()
            var confirmed = false
            try {
                val inserted = api.insert("transactions", body.put("id", transactionId).put("user_id", owner),
                    expectedOwner = owner, expectedRevision = revision)
                check(inserted.getString("id") == transactionId && inserted.getString("user_id") == owner) { "Risposta del salvataggio non valida" }
                confirmed = true
                check(userId() == owner && api.sessionOwnerRevision() == revision) { "Account cambiato durante il salvataggio" }
                CoupleRepository(api).shareExpense(share.connectionId, transactionId, share.categoryName.orEmpty(), share.customPartner,
                    expectedOwner = owner, expectedRevision = revision)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { throw TransactionSharingException(transactionId, confirmed, error) }
        }
    }

    suspend fun removeTransactionShare(expense: FinanceSharedExpense) {
        val identity = ownerSnapshot()
        val owner = identity.id
        val revision = identity.revision
        val connectionId = UUID.fromString(expense.connectionId).toString()
        val active = api.select("couple_connections", "select=*&id=eq.$connectionId&or=(user_a.eq.$owner,user_b.eq.$owner)&revoked_at=is.null&limit=1",
            expectedOwner = owner, expectedRevision = revision)
        require(active.length() == 1) { "La connessione non è più attiva. Aggiorna la schermata." }
        val connection = financeConnectionFromJson(active.getJSONObject(0), owner)
        val id = UUID.fromString(expense.id).toString()
        val rows = api.select("shared_expenses_view", "select=*&id=eq.$id&connection_id=eq.$connectionId&limit=1",
            expectedOwner = owner, expectedRevision = revision)
        require(rows.length() == 1 && rows.getJSONObject(0).isNull("tx_deleted_at")) { "La condivisione non è più disponibile" }
        val fresh = financeSharedExpenseFromJson(rows.getJSONObject(0), connection)
        require(fresh.createdBy == expense.createdBy && fresh.originalTransactionId == expense.originalTransactionId) { "La condivisione è cambiata. Aggiorna i dati." }
        check(userId() == owner && api.sessionOwnerRevision() == revision) { "Account cambiato prima della rimozione" }
        try {
            if (fresh.createdBy == owner) api.delete("shared_expenses", id, "created_by=eq.$owner&connection_id=eq.$connectionId",
                expectedOwner = owner, expectedRevision = revision)
            else api.rpc("delete_shared_expense_by_partner", JSONObject().put("p_id", id),
                expectedOwner = owner, expectedRevision = revision)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            throw FinancePendingRefreshException("Rimozione della condivisione non confermata. Aggiorna i dati prima di continuare.", error)
        }
    }

    suspend fun saveCategory(id: String?, name: String, type: TransactionType, icon: String, color: String) {
        val owner = ownerSnapshot()
        require(name.trim().isNotEmpty()) { "Inserisci il nome della categoria" }
        require(Regex("#[0-9a-fA-F]{6}").matches(color)) { "Colore non valido: usa #RRGGBB" }
        save("categories", id, JSONObject().put("name", name.trim()).put("type", type.wire)
            .put("icon", icon).put("color", color), owner)
    }

    suspend fun saveBudget(id: String?, categoryId: String, amount: String) {
        val owner = ownerSnapshot()
        if (id == null) validateCategory(categoryId, TransactionType.EXPENSE, owner)
        val body = JSONObject().put("amount", parseMoney(amount, allowZero = true).toPlainString())
        if (id == null) body.put("category_id", categoryId).put("month", 1).put("year", 2000).put("currency", "EUR")
        save("budgets", id, body, owner)
    }

    suspend fun saveRecurring(
        id: String?, name: String, amount: String, currency: String, categoryId: String?,
        frequency: RecurringFrequency, weekInterval: String, date: String, active: Boolean,
    ) {
        val owner = ownerSnapshot()
        require(name.trim().isNotEmpty()) { "Inserisci il nome della ricorrenza" }
        require(currency in supportedCurrencies) { "Valuta non supportata" }
        val interval = if (frequency == RecurringFrequency.WEEKLY) weekInterval.toIntOrNull() else 1
        require(interval != null && interval in 1..520) { "Inserisci da 1 a 520 settimane" }
        validateCategory(categoryId, TransactionType.EXPENSE, owner)
        save("recurring_expenses", id, JSONObject().put("name", name.trim())
            .put("amount", parseMoney(amount).toPlainString()).put("currency", currency)
            .put("category_id", categoryId ?: JSONObject.NULL).put("frequency", frequency.wire)
            .put("week_interval", interval).put("next_due_date", LocalDate.parse(date).toString()).put("is_active", active), owner)
    }

    suspend fun setRecurringActive(id: String, active: Boolean) {
        val owner = ownerSnapshot()
        checkOwner(owner)
        api.update("recurring_expenses", UUID.fromString(id).toString(), JSONObject().put("is_active", active),
            "user_id=eq.${owner.id}&deleted_at=is.null", expectedOwner = owner.id, expectedRevision = owner.revision)
    }

    /** Matches web processDueExpenses: one occurrence per due record and deduplication by name/month.
     * This is not a backend transaction or an exactly-once guarantee against another client or cron.
     * All responses are checked; a failed insert never advances the schedule. No automatic retry.
     */
    suspend fun processDueRecurring(today: LocalDate = LocalDate.now()): RecurringProcessingResult {
        val owner = ownerSnapshot()
        val user = owner.id
        val own = "user_id=eq.$user"
        var result = RecurringProcessingResult()
        var stage = "il caricamento delle ricorrenze"
        try {
            val due = rows("recurring_expenses", "select=*&$own&deleted_at=is.null&is_active=eq.true&next_due_date=lte.$today&order=next_due_date.asc,id.asc", owner)
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
                val existing = api.select("transactions", "select=id&$own&deleted_at=is.null&description=$description&date=gte.$start&date=lt.$end&limit=1",
                    expectedOwner = user, expectedRevision = owner.revision)
                if (existing.length() == 0) {
                    stage = "il salvataggio della transazione"
                    checkOwner(owner)
                    // Same payload as web/cron: the backend supplies its exchange-rate default.
                    api.insert("transactions", JSONObject().put("user_id", user).put("type", "expense")
                        .put("amount", amount.toPlainString()).put("currency", expense.currency)
                        .put("category_id", expense.categoryId ?: JSONObject.NULL).put("description", expense.name)
                        .put("date", expense.nextDueDate.toString()), expectedOwner = user, expectedRevision = owner.revision)
                    result = result.copy(created = result.created + 1)
                } else result = result.copy(existing = result.existing + 1)
                stage = "l’aggiornamento della scadenza"
                checkOwner(owner)
                // Compare date/activity: protect an intervening date edit or pause, not every record field.
                api.update("recurring_expenses", UUID.fromString(expense.id).toString(),
                    JSONObject().put("next_due_date", next.toString()),
                    "$own&deleted_at=is.null&is_active=eq.true&next_due_date=eq.${expense.nextDueDate}",
                    expectedOwner = user, expectedRevision = owner.revision)
                result = result.copy(advanced = result.advanced + 1)
            }
            return result
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw RecurringProcessingException(result, stage, exception)
        }
    }

    private suspend fun save(table: String, id: String?, body: JSONObject, owner: FinanceOwner) {
        checkOwner(owner)
        val user = owner.id
        if (id == null) {
            try {
                api.insert(table, body.put("user_id", user), expectedOwner = user, expectedRevision = owner.revision)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                // An explicit rejection is safe to correct. Missing/invalid responses and postflight
                // owner failures can follow a committed INSERT; never leave its create form reusable.
                val rejected = error is ApiException && ((error.status in 400..499 && error.status != 408) ||
                    error.status == null && error.kind in setOf(ApiError.CONFIGURATION, ApiError.VALIDATION))
                if (rejected) throw error
                throw FinancePendingRefreshException(
                    "Salvataggio non confermato. Aggiorna i dati e verifica la riga prima di creare un nuovo elemento.", error)
            }
        } else api.update(table, UUID.fromString(id).toString(), body, "user_id=eq.$user",
            expectedOwner = user, expectedRevision = owner.revision)
    }

    suspend fun saveAsset(id: String?, name: String, symbol: String, type: FinanceAssetType, quantity: String,
        purchasePrice: String, currentPrice: String, currency: String, date: String, notes: String) {
        val owner = ownerSnapshot()
        require(name.trim().isNotEmpty()) { "Inserisci il nome dell’asset" }
        require(currency in supportedCurrencies) { "Valuta non supportata" }
        val parsedQuantity = parseQuantity(quantity)
        val body = JSONObject().put("name", name.trim()).put("symbol", symbol.trim().uppercase().ifBlank { null } ?: JSONObject.NULL)
            .put("type", type.wire).put("quantity", parsedQuantity.toPlainString())
            .put("purchase_price", parseMoney(purchasePrice, allowZero = true).toPlainString())
            .put("current_price", if (currentPrice.isBlank()) JSONObject.NULL else parseMoney(currentPrice, allowZero = true).toPlainString())
            .put("currency", currency).put("purchase_date", LocalDate.parse(date).toString()).put("notes", notes.ifBlank { null } ?: JSONObject.NULL)
        save("portfolio_assets", id, body, owner)
    }

    suspend fun closeAsset(asset: FinanceAsset, soldAt: String, soldPrice: String) {
        closeAssetGroup(listOf(asset), soldAt, soldPrice)
    }

    /** Sequential optimistic writes, not a database transaction. Stop on the first uncertain result. */
    suspend fun closeAssetGroup(assets: List<FinanceAsset>, soldAt: String, soldPrice: String) {
        val identity = ownerSnapshot()
        require(assets.isNotEmpty() && assets.size <= 100_000) { "Seleziona una posizione aperta" }
        require(assets.map { it.id }.distinct().size == assets.size) { "Acquisti duplicati nella selezione" }
        require(portfolioPositionGroups(assets).size == 1 && assets.none { it.closed }) { "La selezione contiene strumenti diversi o acquisti già chiusi" }
        require(assets.all { it.currency in supportedCurrencies }) { "Valuta non supportata" }
        val date = LocalDate.parse(soldAt)
        val price = parseMoney(soldPrice)
        assets.forEach { validatePositionClosure(date, price, it.purchaseDate) }
        val owner = identity.id
        val revision = identity.revision
        val nullCurrencies = mutableSetOf<String>()
        suspend fun checkOwner() {
            check(userId() == owner && api.sessionOwnerRevision() == revision) { "Account cambiato. Aggiorna i dati prima di continuare." }
        }
        // Validate every selected row before the first write; the PATCH predicates guard later races.
        assets.forEach { expected ->
            try {
                checkOwner()
                val id = UUID.fromString(expected.id).toString()
                val rows = api.select("portfolio_assets", "select=*&id=eq.$id&user_id=eq.$owner&limit=1",
                    expectedOwner = owner, expectedRevision = revision)
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
                        "&purchase_date=eq.${asset.purchaseDate}&sold_at=$soldDate&sold_price=$soldValue",
                    expectedOwner = owner, expectedRevision = revision)
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
        val identity = ownerSnapshot()
        val user = identity.id
        val revision = identity.revision
        val logs = api.select("manual_price_updates", "select=updated_at&user_id=eq.$user&order=updated_at.desc&limit=1",
            expectedOwner = user, expectedRevision = revision)
        if (logs.length() > 0) require(parseBackendInstant(logs.getJSONObject(0).getString("updated_at")).plusSeconds(86_400) <= Instant.now()) {
            "L’aggiornamento prezzi è disponibile una volta ogni 24 ore"
        }
        check(userId() == user && api.sessionOwnerRevision() == revision) { "Account cambiato prima dell’aggiornamento prezzi" }
        try {
            val result = api.invokeFunction("update-prices", JSONObject().put("user_id", user),
                expectedOwner = user, expectedRevision = revision) as? JSONObject
                ?: error("Il servizio prezzi ha restituito una risposta non valida")
            check(userId() == user && api.sessionOwnerRevision() == revision) { "Account cambiato durante l’aggiornamento prezzi" }
            api.insert("manual_price_updates", JSONObject().put("user_id", user)
                .put("assets_updated", result.optInt("updated", 0)).put("assets_checked", result.optInt("checked", 0)),
                expectedOwner = user, expectedRevision = revision)
            check(userId() == user && api.sessionOwnerRevision() == revision) { "Account cambiato durante l’aggiornamento prezzi" }
            val errors = result.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val symbols = (0 until minOf(errors.length(), 5)).mapNotNull { index ->
                    errors.optJSONObject(index)?.nullableString("symbol")?.take(40)
                }.joinToString(", ")
                error("Aggiornamento parziale: ${result.optInt("updated", 0)} prezzi aggiornati, ${errors.length()} errori" +
                    (if (symbols.isBlank()) "." else " ($symbols).") + " Aggiorna la schermata per leggere i prezzi salvati.")
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            throw FinancePendingRefreshException(error.message ?: "Aggiornamento prezzi non confermato. Aggiorna i dati prima di continuare.", error)
        }
    }

    suspend fun delete(table: String, id: String) {
        val identity = ownerSnapshot()
        require(table in setOf("transactions", "categories", "budgets", "recurring_expenses", "portfolio_assets"))
        val user = identity.id
        val uuid = UUID.fromString(id).toString()
        if (table == "categories") {
            // The web detaches transaction categories before soft deletion. Keep the category on a partial failure.
            val affected = rows("transactions", "select=id&user_id=eq.$user&category_id=eq.$uuid&order=id.asc", identity)
            val revision = identity.revision
            var detached = 0
            try {
                for (tx in affected) {
                    check(userId() == user && api.sessionOwnerRevision() == revision) { "Account cambiato prima della rimozione" }
                    // Keep an intervening reassignment: never detach a row now belonging to another category.
                    api.update("transactions", tx.getString("id"), JSONObject().put("category_id", JSONObject.NULL),
                        "user_id=eq.$user&category_id=eq.$uuid", expectedOwner = user, expectedRevision = revision)
                    detached++
                }
                check(userId() == user && api.sessionOwnerRevision() == revision) { "Account cambiato prima della rimozione" }
                api.updateMinimal(table, uuid, JSONObject().put("deleted_at", Instant.now().toString()), "user_id=eq.$user",
                    expectedOwner = user, expectedRevision = revision)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                throw FinancePendingRefreshException("Rimozione interrotta: $detached transazioni scollegate con conferma. Aggiorna i dati e verifica la categoria prima di continuare.", error)
            }
        } else if (table == "recurring_expenses") {
            checkOwner(identity)
            api.updateMinimal(table, uuid, JSONObject().put("deleted_at", Instant.now().toString()), "user_id=eq.$user",
                expectedOwner = user, expectedRevision = identity.revision)
        } else {
            checkOwner(identity)
            api.delete(table, uuid, "user_id=eq.$user", expectedOwner = user, expectedRevision = identity.revision)
        }
    }
}
