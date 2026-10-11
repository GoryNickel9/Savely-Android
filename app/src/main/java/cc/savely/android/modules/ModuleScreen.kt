package cc.savely.android.modules

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.savely.android.data.SupabaseApi
import cc.savely.android.BuildConfig
import cc.savely.android.ui.localized
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.Currency
import java.util.concurrent.atomic.AtomicInteger

private fun money(value: Double) = NumberFormat.getCurrencyInstance(Locale.ITALY).format(value)
/** PokerHourlyEarnings.tsx uses toFixed(2): round the exact binary Double, ties away from zero.
 * BigDecimal(value), unlike valueOf(value), also preserves the web's 0.305 -> 0.30 behavior on Android ICU.
 */
internal fun pokerRateMoney(value: Double): String {
    require(value.isFinite())
    return NumberFormat.getCurrencyInstance(Locale.ITALY).apply { roundingMode = RoundingMode.HALF_UP }
        .format(BigDecimal(value))
}
private fun number(value: String): Double {
    val normalized = if (value.contains(',') && value.contains('.')) value.replace(".", "").replace(',', '.') else value.replace(',', '.')
    return normalized.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("Inserisci un numero valido")
}
private fun JSONArray.rows() = (0 until length()).map { getJSONObject(it) }
private suspend fun SupabaseApi.userRows(table: String, extra: String = ""): List<JSONObject> {
    val userId = session()?.userId ?: error("Accedi per leggere i tuoi dati")
    if (extra.contains("&limit=")) return select(table, "user_id=eq.$userId$extra").rows()
    val rows = mutableListOf<JSONObject>()
    val order = if (extra.contains("&order=")) "" else "&order=id.asc"
    while (true) {
        val page = select(table, "user_id=eq.$userId$extra$order&limit=500&offset=${rows.size}").rows()
        if (page.isEmpty()) return rows
        rows.addAll(page)
        require(rows.size <= 100000) { "Dataset troppo grande: restringi il periodo sul web" }
    }
}

@Composable
fun ModuleScreen(api: SupabaseApi, section: String, modifier: Modifier = Modifier) {
    when (section) {
        "fire" -> FireScreen(api, modifier)
        "poker" -> PokerScreen(api, modifier)
        "fumo" -> CollectionScreen(api, "Fumo", listOf("CBD", "THC", "Liquido"), fumoSchemas, modifier)
        "tcg" -> CollectionScreen(api, "TCG", listOf("Magic", "Pokémon", "Yu-Gi-Oh"), tcgSchemas, modifier)
        "libreria" -> CollectionScreen(api, "Libreria", listOf("Libri", "Fumetti", "Manga"), librarySchemas, modifier)
    }
}

@Composable
private fun Tabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label -> FilterChip(selected == index, { onSelect(index) }, label = { Text(localized(label)) }) }
    }
}

@Composable
private fun FireScreen(api: SupabaseApi, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    var loading by remember { mutableStateOf(true) }
    var account by remember { mutableStateOf("") }
    var preferences by remember { mutableStateOf<SharedPreferences?>(null) }
    var initial by remember { mutableStateOf(FireParameters()) }
    var hasSavedParameters by remember { mutableStateOf(false) }
    var restoreWarning by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(api, retry) {
        loading = true
        try {
            val userId = api.session()?.userId ?: error("Accedi per usare i parametri FIRE del tuo account")
            val local = withContext(Dispatchers.IO) {
                context.getSharedPreferences(FireParameters.preferencesName(userId), Context.MODE_PRIVATE)
            }
            val restored = withContext(Dispatchers.IO) {
                runCatching { local.getString(FireParameters.STORAGE_KEY, null)?.let { FireParameters.decode(it) } }
            }
            account = userId
            preferences = local
            initial = restored.getOrNull() ?: FireParameters()
            hasSavedParameters = restored.getOrNull() != null
            restoreWarning = if (restored.isFailure) "Parametri locali non leggibili o versione non supportata. Sono mostrati valori di esempio; usa Ripristina per sostituire il salvataggio." else null
            loadError = null
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { loadError = failure.message ?: "Impossibile aprire i parametri FIRE locali"; preferences = null }
        finally { loading = false }
    }
    if (loading) {
        Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized("FIRE"), style = MaterialTheme.typography.headlineMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(localized("Caricamento dei parametri locali del tuo account…"))
        }
    } else if (preferences == null) {
        Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized(loadError ?: "Parametri locali non disponibili"), color = MaterialTheme.colorScheme.error)
            Button({ retry++ }) { Text(localized("Riprova")) }
        }
    } else key(account, retry) { FireForm(api, account, preferences!!, initial, hasSavedParameters, restoreWarning, modifier) }
}

@Composable
private fun FireForm(api: SupabaseApi, ownerId: String, preferences: SharedPreferences, initial: FireParameters, hasSavedParameters: Boolean, restoreWarning: String?, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val shareChooserTitle = localized("Condividi configurazione FIRE")
    val writes = remember(preferences) { Mutex() }
    val writeGeneration = remember(preferences) { AtomicInteger(0) }
    val renderedGeneration = writeGeneration.get()
    var importing by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var age by rememberSaveable { mutableStateOf(initial.inputs.currentAge.toString()) }
    var retirement by rememberSaveable { mutableStateOf(initial.inputs.retirementAge.toString()) }
    var savings by rememberSaveable { mutableStateOf(initial.inputs.currentSavings.toString()) }
    var contribution by rememberSaveable { mutableStateOf(initial.inputs.annualContribution.toString()) }
    var income by rememberSaveable { mutableStateOf(initial.inputs.annualIncome.toString()) }
    var expenses by rememberSaveable { mutableStateOf(initial.inputs.annualExpenses.toString()) }
    var expectedReturn by rememberSaveable { mutableStateOf((initial.inputs.expectedReturn * 100).toString()) }
    var inflation by rememberSaveable { mutableStateOf((initial.inputs.inflationRate * 100).toString()) }
    var withdrawal by rememberSaveable { mutableStateOf((initial.inputs.withdrawalRate * 100).toString()) }
    var partTime by rememberSaveable { mutableStateOf(initial.partTimeIncome.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var storageWarning by remember { mutableStateOf(restoreWarning) }
    var storageError by remember { mutableStateOf<String?>(null) }
    var lastSaved by remember { mutableStateOf(if (hasSavedParameters) initial.encode() else "") }
    var saveRetry by remember { mutableIntStateOf(0) }
    var resetting by remember { mutableStateOf(false) }
    var sharingOpen by remember { mutableStateOf(false) }
    var sharingBaseline by remember { mutableStateOf(initial) }
    var sharingError by remember { mutableStateOf<String?>(null) }
    fun currentParameters(): FireParameters {
        require(number(age) % 1 == 0.0 && number(retirement) % 1 == 0.0) { "Usa età intere" }
        return FireParameters(FireCalculations.Inputs(number(age).toInt(), number(retirement).toInt(), number(savings),
            number(contribution), number(income), number(expectedReturn) / 100, number(inflation) / 100,
            number(withdrawal) / 100, number(expenses)), number(partTime)).also { it.encode() }
    }
    val parameters = runCatching { currentParameters() }
    val encoded = parameters.getOrNull()?.encode()
    // Metrics and projections belong to this exact draft; invalid drafts have no current result.
    val calculation = remember(encoded, tab) {
        runCatching {
            val current = parameters.getOrThrow()
            if (tab == 0) {
                val calculated = FireCalculations.standard(current.inputs)
                listOf("Obiettivo FIRE" to money(calculated.fireNumber),
                    "Anni al FIRE" to years(calculated.yearsToFire), "Età al FIRE" to years(calculated.fireAge),
                    "Tasso di risparmio" to "${format(calculated.savingsRate * 100)}%",
                    "Contributo mensile" to money(calculated.monthlyContribution), "Coast FIRE" to money(calculated.coastFireNumber)) to calculated.projections
            } else {
                val calculated = FireCalculations.barista(current.inputs, current.partTimeIncome)
                listOf("Obiettivo Barista" to money(calculated.baristaNumber), "Obiettivo FIRE completo" to money(calculated.fullFireNumber),
                    "Anni al Barista FIRE" to years(calculated.yearsToBaristaFire),
                    "Reddito part-time richiesto" to money(calculated.partTimeIncomeNeeded),
                    "Capitale risparmiato con part-time" to money(calculated.savingsFromPartTime)) to calculated.projections
            }
        }
    }
    val result = calculation.getOrNull()?.first.orEmpty()
    val projections = calculation.getOrNull()?.second.orEmpty()
    val calculationError = parameters.exceptionOrNull()?.message ?: calculation.exceptionOrNull()?.message
        ?: if (calculation.isFailure) "Parametri FIRE non validi" else null
    val latestEncoded = rememberUpdatedState(encoded)
    LaunchedEffect(encoded, saveRetry, storageWarning) {
        val snapshot = encoded ?: return@LaunchedEffect
        if (storageWarning != null || snapshot == lastSaved) return@LaunchedEffect
        try {
            // Finish each small disk commit during navigation; a newer edit must never be overwritten.
            val saved = withContext(NonCancellable + Dispatchers.IO) {
                writes.withLock {
                    if (writeGeneration.get() != renderedGeneration || latestEncoded.value != snapshot) false else {
                        check(preferences.edit().putString(FireParameters.STORAGE_KEY, snapshot).commit()) { "Salvataggio locale FIRE non riuscito" }
                        true
                    }
                }
            }
            if (saved) { lastSaved = snapshot; storageError = null }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { storageError = "Parametri non salvati sul dispositivo. Riprova o libera spazio." }
    }
    LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(localized("FIRE"), style = MaterialTheme.typography.headlineMedium) }
        item { Tabs(listOf("Standard", "Barista"), tab) { tab = it } }
        item { Text(localized("Parametri di esempio modificabili. Proiezione deterministica: rendimenti e inflazione restano costanti.")) }
        item {
            Text(localized(when {
                storageWarning != null -> storageWarning!!
                storageError != null -> storageError!!
                encoded == null -> "Valori incompleti o non validi: resta salvata l'ultima configurazione valida."
                encoded == lastSaved -> "Parametri salvati sul dispositivo per questo account."
                else -> "Salvataggio dei parametri locali…"
            }), style = MaterialTheme.typography.bodySmall,
                color = if (storageWarning != null || storageError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (storageError != null) OutlinedButton({ saveRetry++ }) { Text(localized("Riprova salvataggio")) }
                OutlinedButton(onClick = {
                    scope.launch {
                        // Reset invalidates queued writes from the previous draft before taking the disk lock.
                        writeGeneration.incrementAndGet()
                        resetting = true
                        try {
                            val defaults = FireParameters()
                            val reset = defaults.encode()
                            withContext(NonCancellable + Dispatchers.IO) { writes.withLock {
                                check(preferences.edit().putString(FireParameters.STORAGE_KEY, reset).commit()) { "Ripristino locale non riuscito" }
                            } }
                            age = defaults.inputs.currentAge.toString(); retirement = defaults.inputs.retirementAge.toString()
                            savings = defaults.inputs.currentSavings.toString(); contribution = defaults.inputs.annualContribution.toString()
                            income = defaults.inputs.annualIncome.toString(); expenses = defaults.inputs.annualExpenses.toString()
                            expectedReturn = (defaults.inputs.expectedReturn * 100).toString(); inflation = (defaults.inputs.inflationRate * 100).toString()
                            withdrawal = (defaults.inputs.withdrawalRate * 100).toString(); partTime = defaults.partTimeIncome.toString()
                            lastSaved = reset; storageWarning = null; storageError = null; error = null
                        } catch (failure: CancellationException) { throw failure }
                        catch (failure: Exception) { storageError = "Ripristino locale non riuscito. Il salvataggio precedente resta disponibile." }
                        finally { resetting = false }
                    }
                }, enabled = !resetting && !importing) { Text(localized(if (resetting) "Ripristino…" else "Ripristina valori di esempio")) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    importing = true
                    try {
                        val holdings = api.userRows("portfolio_assets")
                        val transactions = api.userRows("transactions").filter { LocalDate.parse(it.getString("date").take(10)) >= LocalDate.now().minusMonths(24) }
                        require(transactions.isNotEmpty()) { "Nessuna transazione negli ultimi 24 mesi" }
                        val totalIncome = transactions.filter { it.getString("type") == "income" }.sumOf { it.getDouble("amount") }
                        val totalExpenses = transactions.filter { it.getString("type") == "expense" }.sumOf { it.getDouble("amount") }
                        savings = holdings.filter { it.isNull("sold_at") || it.optString("sold_at").isBlank() || it.isNull("sold_price") }.sumOf {
                            (if (it.isNull("current_price")) it.getDouble("purchase_price") else it.getDouble("current_price")) * it.getDouble("quantity")
                        }.toString()
                        income = (totalIncome / 2).toString()
                        expenses = (totalExpenses / 2).toString()
                        contribution = ((totalIncome - totalExpenses) / 2).coerceAtLeast(0.0).toString()
                        error = null
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { error = failure.message ?: "Impossibile caricare i parametri" }
                    finally { importing = false }
                }
            }, enabled = !importing && !resetting) { Text(localized(if (importing) "Caricamento…" else "Usa i miei dati (24 mesi)")) }
            Text(localized("Il link condiviso contiene i tuoi parametri finanziari. Condividilo soltanto con i destinatari scelti."),
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                scope.launch {
                    if (importing || resetting) return@launch
                    importing = true
                    try {
                        check(api.session()?.userId == ownerId) { "Account cambiato. Riapri FIRE per continuare." }
                        val current = currentParameters()
                        val link = FireSharing.link(BuildConfig.WEB_URL, if (tab == 0) FireCalculator.STANDARD else FireCalculator.BARISTA, current)
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                        context.startActivity(Intent.createChooser(send, shareChooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        error = null
                    } catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) { error = "Impossibile condividere il link FIRE. Controlla i parametri e riprova." }
                    finally { importing = false }
                }
            }, enabled = encoded != null && !importing && !resetting) { Text(localized("Condividi link FIRE")) }
            OutlinedButton(onClick = {
                sharingBaseline = parameters.getOrNull() ?: runCatching { FireParameters.decode(lastSaved) }.getOrNull() ?: initial
                sharingError = null
                sharingOpen = true
            }, enabled = !importing && !resetting) { Text(localized("Importa link FIRE")) }
            }
        }
        item { NumberInput("Età attuale", age) { age = it } }
        item { NumberInput("Età pensionamento", retirement) { retirement = it } }
        item { NumberInput("Risparmi attuali (€)", savings) { savings = it } }
        item { NumberInput("Contributo annuo (€)", contribution) { contribution = it } }
        item { NumberInput("Reddito netto annuo (€)", income) { income = it } }
        item { NumberInput("Spese annue (€)", expenses) { expenses = it } }
        item { NumberInput("Rendimento nominale (%)", expectedReturn) { expectedReturn = it } }
        item { NumberInput("Inflazione (%)", inflation) { inflation = it } }
        item { NumberInput("Prelievo annuo (%)", withdrawal) { withdrawal = it } }
        if (tab == 1) item { NumberInput("Reddito part-time annuo (€)", partTime) { partTime = it } }
        (calculationError ?: error)?.let { item { Text(localized(it), color = MaterialTheme.colorScheme.error) } }
        items(result) { (label, value) -> Metric(label, value) }
        if (projections.isNotEmpty()) item { Text(localized("Proiezione annuale"), style = MaterialTheme.typography.titleLarge) }
        items(projections) { point ->
            Metric(localized("{{year}} · {{age}} anni", "year" to point.year, "age" to point.age),
                localized("{{amount}} · valore reale {{real}}", "amount" to money(point.portfolio), "real" to money(point.inflationAdjusted)))
        }
    }
    if (sharingOpen) FireSharingImportDialog(BuildConfig.WEB_URL, sharingBaseline, importing, sharingError,
        onEdited = { sharingError = null }, close = { sharingOpen = false }, apply = { shared ->
            scope.launch {
                if (importing || resetting) return@launch
                importing = true
                var applied = false
                try {
                    check(api.session()?.userId == ownerId) { "Account cambiato. Riapri FIRE per continuare." }
                    val snapshot = shared.parameters.encode()
                    // Applying is explicit, including replacing a corrupt account-specific snapshot.
                    writeGeneration.incrementAndGet()
                    withContext(NonCancellable + Dispatchers.IO) { writes.withLock {
                        check(api.session()?.userId == ownerId) { "Account cambiato. Riapri FIRE per continuare." }
                        check(preferences.edit().putString(FireParameters.STORAGE_KEY, snapshot).commit()) {
                            "Configurazione non applicata: salvataggio locale FIRE non riuscito."
                        }
                    } }
                    check(api.session()?.userId == ownerId) { "Account cambiato. Riapri FIRE per continuare." }
                    val imported = shared.parameters.inputs
                    age = imported.currentAge.toString(); retirement = imported.retirementAge.toString()
                    savings = imported.currentSavings.toString(); contribution = imported.annualContribution.toString()
                    income = imported.annualIncome.toString(); expenses = imported.annualExpenses.toString()
                    expectedReturn = (imported.expectedReturn * 100).toString(); inflation = (imported.inflationRate * 100).toString()
                    withdrawal = (imported.withdrawalRate * 100).toString(); partTime = shared.parameters.partTimeIncome.toString()
                    tab = if (shared.calculator == FireCalculator.STANDARD) 0 else 1
                    lastSaved = snapshot; storageWarning = null; storageError = null; error = null
                    sharingError = null; sharingOpen = false; applied = true
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { sharingError = failure.message ?: "Impossibile applicare la configurazione FIRE" }
                finally {
                    importing = false
                    // A failed apply must not leave a previously queued valid local draft unsaved.
                    if (!applied) saveRetry++
                }
            }
        })
}

private fun years(value: Double) = if (value.isFinite()) format(value) else "Obiettivo non raggiungibile nel modello"
private fun format(value: Double) = String.format(Locale.ITALY, "%.1f", value)
@Composable
private fun NumberInput(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(localized(label)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}
@Composable
private fun Metric(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(localized(label), style = MaterialTheme.typography.labelLarge)
        Text(localized(value), style = MaterialTheme.typography.titleMedium)
    } }
}

@Composable
private fun PokerScreen(api: SupabaseApi, modifier: Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(localized("Poker"), style = MaterialTheme.typography.headlineMedium)
        Text(localized("Contabilità personale e risultati fuori dal tavolo."))
        Tabs(listOf("Next Cut", "Guadagno orario", "Rakeback", "Spese manuali"), tab) { tab = it }
        when (tab) {
            0 -> NextCutScreen(api, Modifier.weight(1f))
            1 -> RecordsScreen(api, hourlySchema, Modifier.weight(1f))
            2 -> RecordsScreen(api, rakebackSchema, Modifier.weight(1f))
            3 -> RecordsScreen(api, manualExpenseSchema, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NextCutScreen(api: SupabaseApi, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var record by remember { mutableStateOf<JSONObject?>(null) }
    var spending by remember { mutableDoubleStateOf(0.0) }
    var deal by rememberSaveable { mutableStateOf("0,55") }
    var profit by rememberSaveable { mutableStateOf("0") }
    var loading by remember { mutableStateOf(true) }
    var dataReady by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(api, revision) {
        loading = true
        dataReady = false
        try {
            record = api.userRows("poker_next_cut", "&order=created_at.asc&limit=1").firstOrNull()
            val transactions = api.userRows("transactions").map { PokerCalculations.Expense(
                LocalDate.parse(it.getString("date").take(10)), it.getDouble("amount"), it.getString("type")) }
            val manual = api.userRows("poker_manual_expenses").sumOf { it.getDouble("amount") }
            spending = PokerCalculations.medianMonthlySpending(transactions, LocalDate.now().minusDays(730)) + manual
            deal = record?.getDouble("deal")?.toString() ?: "0,55"
            profit = record?.getDouble("profit_loss")?.toString() ?: "0"
            error = null
            dataReady = true
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { error = failure.message ?: "Impossibile leggere Next Cut" }
        finally { loading = false }
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedButton(onClick = { revision++ }, enabled = !loading) { Text(localized("Aggiorna")) } }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { item { Text(localized(it), color = MaterialTheme.colorScheme.error) } }
        item { Metric("Spesa mensile (mediana + spese manuali)", money(spending)) }
        item { NumberInput("Deal (0–1)", deal) { deal = it } }
        item { NumberInput("Profit / Loss attuale (€)", profit) { profit = it } }
        item {
            val need = runCatching { PokerCalculations.nextCut(spending, number(deal), number(profit)) }.getOrNull()
            Metric("Quanto manca al prossimo cut", need?.let(::money) ?: "Controlla il deal e il P/L")
        }
        item {
            Button(onClick = {
                scope.launch {
                    loading = true
                    try {
                        val ratio = number(deal)
                        val pnl = number(profit)
                        PokerCalculations.nextCut(spending, ratio, pnl)
                        val payload = JSONObject().put("deal", ratio).put("profit_loss", pnl).put("amount", spending)
                        if (record == null) api.insert("poker_next_cut", payload.put("name", "Next Cut").put("user_id", api.session()?.userId ?: error("Sessione scaduta")))
                        else api.update("poker_next_cut", record!!.getString("id"), payload)
                        revision++
                        error = null
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { error = failure.message ?: "Salvataggio non riuscito" }
                    finally { loading = false }
                }
            }, enabled = !loading && dataReady, modifier = Modifier.fillMaxWidth()) { Text(localized("Salva Next Cut")) }
        }
    }
}

@Composable
private fun CollectionScreen(api: SupabaseApi, title: String, labels: List<String>, schemas: List<RecordSchema>, modifier: Modifier) {
    var selected by rememberSaveable(title) { mutableIntStateOf(0) }
    val overviewKind = when (title) { "TCG" -> CollectionKind.TCG; "Libreria" -> CollectionKind.LIBRARY; else -> null }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(localized(title), style = MaterialTheme.typography.headlineMedium)
        Tabs(if (overviewKind == null) labels else labels + "Panoramica", selected) { selected = it }
        if (overviewKind != null && selected == schemas.size) CollectionOverviewScreen(api, overviewKind,
            Modifier.weight(1f)) { category -> selected = schemas.indexOfFirst { it.category == category }.coerceAtLeast(0) }
        else RecordsScreen(api, schemas[selected], Modifier.weight(1f))
    }
}

private fun overviewMoney(value: BigDecimal, code: String): String {
    val currency = runCatching { Currency.getInstance(code) }.getOrNull()
    val formatter = if (currency == null) NumberFormat.getNumberInstance(Locale.ITALY)
        else NumberFormat.getCurrencyInstance(Locale.ITALY).apply { this.currency = currency }
    val amount = formatter.apply { minimumFractionDigits = 2; maximumFractionDigits = 2; roundingMode = RoundingMode.HALF_UP }.format(value)
    return if (currency == null) "$amount $code" else amount
}

@Composable
private fun CollectionOverviewScreen(api: SupabaseApi, kind: CollectionKind, modifier: Modifier, openCategory: (String) -> Unit) {
    key(api, kind) {
        val repository = remember(api) { CollectionOverviewRepository(api) }
        var snapshot by remember { mutableStateOf<CollectionOverview?>(null) }
        var loading by remember { mutableStateOf(true) }
        var failed by remember { mutableStateOf(false) }
        var revision by remember { mutableIntStateOf(0) }
        LaunchedEffect(repository, revision) {
            snapshot = null; loading = true; failed = false
            try { snapshot = repository.load(kind) }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { failed = true }
            finally { loading = false }
        }
        LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OutlinedButton(onClick = { snapshot = null; loading = true; revision++ }, enabled = !loading) {
                Text(localized(if (failed) "Riprova" else "Aggiorna"))
            } }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(localized("Caricamento della panoramica…")) }
            if (failed) item { Text(localized("Panoramica non disponibile. Nessun totale parziale viene mostrato."), color = MaterialTheme.colorScheme.error) }
            snapshot?.let { data ->
                item { Metric("Totale Pezzi", data.pieces.toString()) }
                if (data.records == 0) item { Text(localized("La collezione è vuota.")) }
                if (kind == CollectionKind.TCG && data.currencies.size > 1) item {
                    Text(localized("Importi separati per valuta; nessuna conversione o somma tra valute."))
                }
                data.currencies.forEach { total -> item(key = "total-${total.currency}") {
                    OverviewTotalCard(localized("Complessivo · {{currency}}", "currency" to total.currency), total,
                        "overview-total-${total.currency}")
                } }
                data.categories.forEach { category ->
                    item(key = "category-${category.category}") {
                        val label = collectionCategoryLabel(category.category)
                        Text(label, style = MaterialTheme.typography.titleLarge)
                        Text(localized("{{records}} elementi · {{pieces}} pezzi", "records" to category.records, "pieces" to category.pieces))
                        if (category.records == 0) Text(localized("Nessun elemento."))
                        if (category.category in kind.categories) TextButton({ openCategory(category.category) }) {
                            Text(localized("Apri {{category}}", "category" to label))
                        }
                    }
                    category.currencies.forEach { total -> item(key = "${category.category}-${total.currency}") {
                        OverviewTotalCard(total.currency, total, "overview-category-${category.category}-${total.currency}")
                    } }
                }
            }
        }
    }
}

@Composable
private fun collectionCategoryLabel(category: String): String = when (category) {
    "magic" -> localized("Magic")
    "pokemon" -> localized("Pokémon")
    "yugioh" -> localized("Yu-Gi-Oh")
    "libri" -> localized("Libri")
    "fumetti" -> localized("Fumetti")
    "manga" -> localized("Manga")
    else -> category
}

@Composable
private fun OverviewTotalCard(title: String, total: CollectionCurrencyTotal, tag: String) {
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(localized("{{count}} pezzi", "count" to total.pieces))
            Text(localized("Investimento: {{amount}}", "amount" to overviewMoney(total.investment, total.currency)))
            Text(localized("Valore: {{amount}}", "amount" to overviewMoney(total.value, total.currency)))
            Text(localized("Profitto / Perdita: {{amount}}", "amount" to overviewMoney(total.profit, total.currency)))
            val percent = total.gainPercent?.let { NumberFormat.getNumberInstance(Locale.ITALY).apply {
                minimumFractionDigits = 2; maximumFractionDigits = 2; roundingMode = RoundingMode.HALF_UP
            }.format(it) + "%" } ?: "—"
            Text(localized("Rendimento: {{percent}}", "percent" to percent))
        }
    }
}

private enum class FieldType { TEXT, NUMBER, INTEGER, DATE, MONTH, URL }
private data class Field(val key: String, val label: String, val type: FieldType = FieldType.TEXT,
    val optional: Boolean = false, val default: String = "", val choices: List<String> = emptyList(), val signed: Boolean = false)
private data class RecordSchema(val table: String, val fields: List<Field>, val category: String? = null,
    val uniqueMonth: Boolean = false)

private val hourlySchema = RecordSchema("poker_hourly_earnings", listOf(
    Field("date", "Mese (AAAA-MM)", FieldType.MONTH, default = YearMonth.now().toString()),
    Field("hours_played", "Tempo giocato (minuti)", FieldType.NUMBER),
    Field("profit_loss", "Net Won (€)", FieldType.NUMBER, signed = true),
    Field("net_won_ev", "Net Won EV (€)", FieldType.NUMBER, default = "0", signed = true)), uniqueMonth = true)
private val rakebackSchema = RecordSchema("poker_rakeback", listOf(
    Field("date", "Mese (AAAA-MM)", FieldType.MONTH, default = YearMonth.now().toString()),
    Field("rake_generated", "Rake generato (€)", FieldType.NUMBER), Field("rakeback_received", "Rakeback ricevuto (€)", FieldType.NUMBER)), uniqueMonth = true)
private val manualExpenseSchema = RecordSchema("poker_manual_expenses", listOf(Field("name", "Nome"), Field("amount", "Importo mensile (€)", FieldType.NUMBER)))
private val fumoSchemas = listOf("cbd", "thc", "liquido_sigaretta").map { table ->
    RecordSchema(table, listOf(Field("costo", "Costo (€)", FieldType.NUMBER),
        Field(if (table == "liquido_sigaretta") "millilitri" else "grammi", if (table == "liquido_sigaretta") "Millilitri" else "Grammi", FieldType.NUMBER),
        Field(if (table == "liquido_sigaretta") "data_arrivo" else "data_acquisto", "Data arrivo/acquisto (AAAA-MM-GG)", FieldType.DATE, default = LocalDate.now().toString()),
        Field("data_finito", "Data fine (AAAA-MM-GG)", FieldType.DATE, optional = true)))
}
private val tcgSchemas = listOf("magic", "pokemon", "yugioh").map { category -> RecordSchema("tgc_cards", listOf(
    Field("name", "Nome carta"), Field("set_code", "Set", optional = true), Field("collector_number", "Numero carta", optional = true),
    Field("condition", "Condizione", default = "near_mint", choices = listOf("near_mint", "lightly_played", "moderately_played", "heavily_played", "damaged")),
    Field("language", "Lingua", default = "EN", choices = listOf("EN", "IT", "JP", "DE", "FR", "ES", "PT", "KOR", "ZHS")), Field("quantity", "Quantità", FieldType.INTEGER, default = "1"),
    Field("purchase_price", "Prezzo acquisto unitario (€)", FieldType.NUMBER, default = "0"),
    Field("purchase_date", "Data acquisto (AAAA-MM-GG)", FieldType.DATE, default = LocalDate.now().toString()),
    Field("current_price", "Prezzo attuale unitario (€)", FieldType.NUMBER, optional = true), Field("notes", "Note", optional = true)), category) }
private val librarySchemas = listOf("libri", "fumetti", "manga").map { category -> RecordSchema("library_items", listOf(
    Field("title", "Titolo"), Field("author", "Autore", optional = true), Field("publisher", "Editore", optional = true),
    Field("year", "Anno pubblicazione", FieldType.INTEGER, optional = true),
    Field("quantity", "Quantità", FieldType.INTEGER, default = "1"),
    Field("purchase_price", "Prezzo acquisto unitario (€)", FieldType.NUMBER, optional = true, default = "0"),
    Field("reselling_value", "Valore rivendita unitario (€)", FieldType.NUMBER, optional = true, default = "0"), Field("notes", "Note", optional = true)) +
    (if (category == "manga") listOf(Field("cover_image", "URL copertina", FieldType.URL, optional = true)) else emptyList()), category) }

@Composable
private fun RecordsScreen(api: SupabaseApi, schema: RecordSchema, modifier: Modifier) {
    key(schema) {
        val scope = rememberCoroutineScope()
        var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        var owner by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(true) }
        var dataReady by remember { mutableStateOf(false) }
        var deal by remember { mutableDoubleStateOf(0.55) }
        var error by remember { mutableStateOf<String?>(null) }
        var revision by remember { mutableIntStateOf(0) }
        var editRow by remember { mutableStateOf<JSONObject?>(null) }
        var seed by remember { mutableStateOf<JSONObject?>(null) }
        var formOpen by remember { mutableStateOf(false) }
        var mangaCreationOpen by rememberSaveable { mutableStateOf(false) }
        var searchOpen by remember { mutableStateOf(false) }
        var filterText by rememberSaveable { mutableStateOf("") }
        var filterSet by rememberSaveable { mutableStateOf<String?>(null) }
        var filterAuthor by rememberSaveable { mutableStateOf<String?>(null) }
        var filterPublisher by rememberSaveable { mutableStateOf<String?>(null) }
        var filterYear by rememberSaveable { mutableStateOf<String?>(null) }
        val catalog = remember(api) { CatalogRepository(api) }
        var deleteRow by remember { mutableStateOf<JSONObject?>(null) }
        var deleteError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(schema, revision) {
            loading = true
            dataReady = false
            try {
                owner = api.session()?.userId ?: error("Accedi per leggere i tuoi dati")
                rows = api.userRows(schema.table, "&order=created_at.desc" + (schema.category?.let { "&category=eq.$it" } ?: ""))
                if (schema.table == "poker_hourly_earnings") {
                    deal = api.userRows("poker_next_cut", "&order=created_at.asc&limit=1").firstOrNull()?.optDouble("deal", 0.55) ?: 0.55
                    if (deal == 0.0) deal = 0.55
                }
                error = null
                dataReady = true
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { error = failure.message ?: "Impossibile leggere i dati" }
            finally { loading = false }
        }
        val summaries = yearlyMetrics(schema, rows, deal)
        val tcgTotals = remember(schema, owner, rows, dataReady) {
            if (schema.table == "tgc_cards" && dataReady) runCatching { collectionOverview(CollectionKind.TCG, owner, rows) }
            else null
        }
        LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ editRow = null; seed = null
                    if (schema.table == "library_items" && schema.category == "manga") mangaCreationOpen = true
                    else formOpen = true
                }, enabled = !loading && dataReady) { Text(localized("Aggiungi")) }
                OutlinedButton({ revision++ }, enabled = !loading) { Text(localized("Aggiorna")) }
            } }
            if (schema.table == "tgc_cards" || (schema.table == "library_items" && schema.category != "manga")) item {
                OutlinedButton({ searchOpen = true }, enabled = !loading && dataReady, modifier = Modifier.fillMaxWidth()) {
                    Text(localized(if (schema.table == "tgc_cards") "Cerca su CardTrader" else "Cerca su Google Books"))
                }
            }
            if (schema.table == "tgc_cards" || schema.table == "library_items") item {
                OutlinedTextField(filterText, { filterText = it }, Modifier.fillMaxWidth(), label = { Text(localized(
                    when {
                        schema.table == "library_items" -> "Cerca per titolo"
                        schema.category == "yugioh" -> "Cerca per nome"
                        else -> "Cerca per nome o set"
                    })) }, singleLine = true)
            }
            if (schema.table == "tgc_cards") item {
                CollectionFilterPicker("Set", filterSet, CollectionFilters.cardSets(rows, schema.category.orEmpty())) { filterSet = it }
            }
            if (schema.table == "library_items") {
                item { CollectionFilterPicker("Autore", filterAuthor, CollectionFilters.libraryValues(rows, "author")) { filterAuthor = it } }
                item { CollectionFilterPicker("Editore", filterPublisher, CollectionFilters.libraryValues(rows, "publisher")) { filterPublisher = it } }
                item { CollectionFilterPicker("Anno", filterYear, CollectionFilters.libraryYears(rows).map(Int::toString)) { filterYear = it } }
            }
            if (schema.table in listOf("tgc_cards", "library_items") && (filterText.isNotEmpty() || listOf(filterSet, filterAuthor, filterPublisher, filterYear).any { it != null })) item {
                TextButton(onClick = { filterText = ""; filterSet = null; filterAuthor = null; filterPublisher = null; filterYear = null }) { Text(localized("Azzera filtri")) }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item { Text(localized(it), color = MaterialTheme.colorScheme.error) } }
            if (schema.table == "library_items") {
                val cost = rows.sumOf { it.optDouble("purchase_price", 0.0) * it.optInt("quantity", 1) }
                val value = rows.sumOf { it.optDouble("reselling_value", 0.0) * it.optInt("quantity", 1) }
                item { Metric(localized("{{count}} pezzi · costo {{amount}}", "count" to rows.sumOf { it.optInt("quantity", 1) }, "amount" to money(cost)),
                    localized("Valore {{amount}} · variazione {{change}}", "amount" to money(value), "change" to money(value - cost))) }
                item { Text(localized("Prezzi unitari in euro. I prezzi CardTrader possono non essere disponibili; non vengono stimati."), style = MaterialTheme.typography.bodySmall) }
            }
            if (schema.table == "tgc_cards") {
                if (tcgTotals?.isFailure == true) item { Text(localized("Riepilogo non disponibile: controlla i dati della collezione."), color = MaterialTheme.colorScheme.error) }
                tcgTotals?.getOrNull()?.currencies?.forEach { total -> item {
                    Metric(localized("{{count}} pezzi · costo {{amount}}", "count" to total.pieces, "amount" to overviewMoney(total.investment, total.currency)),
                        localized("Valore {{amount}} · variazione {{change}}", "amount" to overviewMoney(total.value, total.currency), "change" to overviewMoney(total.profit, total.currency)))
                } }
                if (dataReady && rows.isEmpty()) item { Metric(localized("{{count}} pezzi · costo {{amount}}", "count" to 0, "amount" to money(0.0)),
                    localized("Valore {{amount}} · variazione {{change}}", "amount" to money(0.0), "change" to money(0.0))) }
                item { Text(localized("Prezzi unitari nella valuta del record. I prezzi CardTrader possono non essere disponibili; non vengono stimati."), style = MaterialTheme.typography.bodySmall) }
            }
            summaries.forEach { metric -> item { Metric(metric.first, metric.second) } }
            if (rows.isEmpty() && !loading && error == null) item { Text(localized("Nessun elemento. Aggiungi il primo record.")) }
            val yearRows = currentYearDetails(schema.table, rows, LocalDate.now().year)
            val visibleRows = when (schema.table) {
                "tgc_cards" -> CollectionFilters.cards(yearRows, schema.category.orEmpty(), filterText, filterSet)
                "library_items" -> CollectionFilters.library(yearRows, filterText, filterAuthor, filterPublisher, filterYear?.toIntOrNull())
                else -> yearRows
            }
            if (schema.table in listOf("tgc_cards", "library_items")) {
                item { Text(localized("{{visible}} di {{total}} elementi", "visible" to visibleRows.size, "total" to rows.size), style = MaterialTheme.typography.bodySmall) }
                if (!loading && error == null && rows.isNotEmpty() && visibleRows.isEmpty()) item { Text(localized("Nessun elemento corrisponde ai filtri. Usa Azzera filtri per mostrare la collezione.")) }
            }
            if (yearRows.size != rows.size) item { Text(localized("Dettaglio anno corrente; riepiloghi su tutti gli anni.")) }
            items(visibleRows, key = { it.getString("id") }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val imageUrl = row.optString(if (schema.table == "tgc_cards") "image_url" else "cover_image")
                            .replaceFirst(Regex("^https?://", RegexOption.IGNORE_CASE), "https://").takeIf { it.startsWith("https://") }
                        if (imageUrl != null) AsyncImage(model = imageUrl,
                            contentDescription = localized("Copertina di {{title}}", "title" to row.optString(if (schema.table == "tgc_cards") "name" else "title")),
                            modifier = Modifier.size(width = 96.dp, height = 128.dp), contentScale = ContentScale.Fit)
                        schema.fields.forEach { field ->
                            val text = readField(row, field)
                            if (text.isNotBlank()) Text("${localized(field.label)}: $text")
                        }
                        if (schema.table == "poker_hourly_earnings") {
                            val hourly = PokerCalculations.hourly(row.getDouble("hours_played") * 60, row.getDouble("profit_loss"), row.optDouble("net_won_ev", 0.0))
                            Text(localized("Orario dopo deal {{rate}} · EV {{ev}}", "rate" to pokerRateMoney(hourly.hourlyRate * deal), "ev" to pokerRateMoney(hourly.hourlyRateEv * deal)))
                        }
                        if (schema.table in listOf("cbd", "thc", "liquido_sigaretta")) {
                            val liquid = schema.table == "liquido_sigaretta"
                            val derived = FumoCalculations.derived(LocalDate.parse(row.getString(if (liquid) "data_arrivo" else "data_acquisto")),
                                if (row.isNull("data_finito")) null else LocalDate.parse(row.getString("data_finito")),
                                if (row.isNull(if (liquid) "millilitri" else "grammi")) null else row.getDouble(if (liquid) "millilitri" else "grammi"), row.getDouble("costo"))
                            fumoDetailValues(derived)?.let { Text(localized("{{days}} giorni · {{quantity}}/giorno · {{daily}}/giorno · {{monthly}}/mese",
                                "days" to it.days, "quantity" to it.quantity, "daily" to it.dailyCost, "monthly" to it.monthlyCost)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ editRow = row; seed = null; formOpen = true }, enabled = !loading && dataReady) { Text(localized("Modifica")) }
                            TextButton({ deleteError = null; deleteRow = row }, enabled = !loading && dataReady) { Text(localized("Elimina")) }
                        }
                        if (schema.table == "tgc_cards" && !row.isNull("card_id")) {
                            TextButton(onClick = {
                                scope.launch {
                                    loading = true
                                    try {
                                        val price = catalog.cardPrice(row.getString("card_id"), row.getString("condition"))
                                        require(price != null) { "Nessun prezzo disponibile per questa carta" }
                                        api.update(schema.table, row.getString("id"), JSONObject().put("current_price", price))
                                        revision++
                                        error = null
                                    } catch (failure: CancellationException) { throw failure }
                                    catch (failure: Exception) { error = failure.message ?: "Aggiornamento prezzo non riuscito" }
                                    finally { loading = false }
                                }
                            }, enabled = !loading && dataReady) { Text(localized("Aggiorna prezzo CardTrader")) }
                        }
                    }
                }
            }
        }
        if (searchOpen) CatalogSearchDialog(catalog, schema, { searchOpen = false }) {
            seed = it; editRow = null; searchOpen = false; formOpen = true
        }
        if (formOpen || mangaCreationOpen) RecordForm(api, catalog, schema, editRow, seed, rows,
            { formOpen = false; mangaCreationOpen = false }) { revision++ }
        deleteRow?.let { row ->
            AlertDialog(onDismissRequest = { if (!loading) { deleteRow = null; deleteError = null } }, title = { Text(localized("Eliminare questo record?")) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(localized("La cancellazione viene sincronizzata anche con Savely web."))
                    deleteError?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                } },
                confirmButton = { TextButton(onClick = {
                    scope.launch {
                        loading = true
                        deleteError = null
                        try { api.delete(schema.table, row.getString("id")); deleteRow = null; revision++; error = null }
                        catch (failure: CancellationException) { throw failure }
                        catch (failure: Exception) { deleteError = failure.message ?: "Cancellazione non riuscita. Riprova." }
                        finally { loading = false }
                    }
                }, enabled = !loading) { Text(localized("Elimina")) } },
                dismissButton = { TextButton({ deleteRow = null; deleteError = null }, enabled = !loading) { Text(localized("Annulla")) } })
        }
    }
}

private fun readField(row: JSONObject, field: Field): String {
    if (row.isNull(field.key)) return ""
    if (field.key == "hours_played") return (row.getDouble(field.key) * 60).toString()
    if (field.type == FieldType.MONTH) return row.getString(field.key).take(7)
    return row.get(field.key).toString()
}

@Composable
private fun RecordForm(api: SupabaseApi, catalog: CatalogRepository, schema: RecordSchema, row: JSONObject?, seed: JSONObject?, existing: List<JSONObject>, close: () -> Unit, saved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val mangaCreation = row == null && schema.table == "library_items" && schema.category == "manga"
    var volumeRange by rememberSaveable { mutableStateOf("") }
    var batchSnapshot by rememberSaveable { mutableStateOf<String?>(null) }
    val restoredBatch = remember(batchSnapshot) { runCatching { batchSnapshot?.let(MangaVolumeBatch::decode) } }
    val batch = restoredBatch.getOrNull()
    val rangeResult = remember(volumeRange) { runCatching { MangaVolumes.parse(volumeRange) } }
    val volumeNumbers = rangeResult.getOrNull()
    val mangaRepository = remember(api) { MangaVolumeRepository(api) }
    val values = remember { mutableStateMapOf<String, String>().apply { schema.fields.forEach { field ->
        val initial = row ?: seed ?: batch?.volumes?.firstOrNull()?.body()
        put(field.key, if (field.key == "title" && batch != null) batch.baseTitle
            else if (initial != null && initial.has(field.key)) readField(initial, field) else field.default)
    } } }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var invalidField by remember { mutableStateOf<String?>(null) }
    suspend fun saveSeries(plan: MangaVolumeBatch) {
        var latest = plan
        try {
            mangaRepository.save(plan) { progress -> latest = progress; batchSnapshot = progress.encode() }
        } finally {
            if (latest.completed > plan.completed) saved()
        }
        close()
    }
    AlertDialog(onDismissRequest = { if (!loading) close() }, title = { Text(localized(if (row == null) "Aggiungi" else "Modifica")) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (mangaCreation) {
                OutlinedTextField(volumeRange, { volumeRange = it }, Modifier.fillMaxWidth(),
                    label = { Text(localized("Numero volume o intervallo (facoltativo)")) }, placeholder = { Text(localized("3 oppure 1-5")) },
                    singleLine = true, enabled = !loading && batchSnapshot == null, isError = rangeResult.isFailure)
                if (batchSnapshot == null && rangeResult.isFailure) Text(localized(rangeResult.exceptionOrNull()?.message ?: "Intervallo non valido"), color = MaterialTheme.colorScheme.error)
                if (batchSnapshot == null && volumeNumbers != null) Text(
                    localized("{{count}} volumi separati, quantità 1 ciascuno. Prezzo di acquisto e rivendita sono per volume.", "count" to volumeNumbers.size),
                    style = MaterialTheme.typography.bodySmall)
                batch?.let { Text(localized("{{done}}/{{total}} volumi confermati · {{title}}", "done" to it.completed, "total" to it.total, "title" to it.baseTitle), style = MaterialTheme.typography.titleSmall) }
                if (batchSnapshot != null) Text(
                    localized("La serie è fissata. Riprendi verifica l'ultimo volume e continua. Chiudendo, i volumi già salvati restano nella collezione; controllala prima di aggiungere nuovamente la serie."),
                    style = MaterialTheme.typography.bodySmall)
                if (restoredBatch.isFailure) Text(localized("Serie locale non leggibile. Chiudi e controlla la collezione prima di riprovare."), color = MaterialTheme.colorScheme.error)
            }
            schema.fields.forEach { field ->
                if (mangaCreation && field.key == "quantity" && (volumeNumbers != null || batchSnapshot != null)) {
                    // Each expanded manga volume is a separate quantity-1 record, as in the web.
                } else if (field.choices.isNotEmpty()) {
                    Text(localized(field.label))
                    field.choices.forEach { choice -> FilterChip(values[field.key] == choice, { values[field.key] = choice }, label = {
                        Text(if (field.key == "condition") localized(choice.replace('_', ' ')) else choice)
                    }) }
                } else OutlinedTextField(values[field.key] ?: "", { values[field.key] = it; invalidField = null }, Modifier.fillMaxWidth(),
                    label = { Text(localized(field.label) + if (field.optional) " " + localized("(facoltativo)") else "") }, enabled = !loading && batchSnapshot == null,
                    singleLine = field.key != "notes", isError = invalidField == field.key,
                    keyboardOptions = KeyboardOptions(keyboardType = when (field.type) {
                        FieldType.NUMBER, FieldType.INTEGER -> KeyboardType.Decimal
                        FieldType.URL -> KeyboardType.Uri
                        else -> KeyboardType.Text
                    }))
            }
            error?.let { Text(recordErrorText(it, schema), color = MaterialTheme.colorScheme.error) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = {
        scope.launch {
            if (loading) return@launch
            loading = true
            try {
                if (mangaCreation && batchSnapshot != null) {
                    saveSeries(batch ?: error("Serie locale non leggibile"))
                    return@launch
                }
                if (mangaCreation) rangeResult.getOrThrow()
                val payload = if (row == null && seed != null) JSONObject(seed.toString()) else JSONObject()
                for (field in schema.fields) {
                    if (mangaCreation && volumeNumbers != null && field.key == "quantity") { payload.put("quantity", 1); continue }
                    invalidField = field.key
                    val text = values[field.key].orEmpty().trim()
                    require(field.optional || text.isNotEmpty()) { "${field.label}: campo obbligatorio" }
                    if (text.isEmpty()) payload.put(field.key, JSONObject.NULL) else {
                        val value: Any = when (field.type) {
                            FieldType.TEXT -> { require(field.choices.isEmpty() || text in field.choices); text }
                            FieldType.NUMBER, FieldType.INTEGER -> number(text).also {
                                require(field.signed || it >= 0) { "${field.label}: valore negativo" }
                                if (field.type == FieldType.INTEGER) require(it % 1 == 0.0 && it <= Int.MAX_VALUE) { "${field.label}: usa un intero valido" }
                                if (field.key == "quantity") require(it >= 1) { "La quantità minima è 1" }
                            }.let { if (field.type == FieldType.INTEGER) it.toInt() else it }
                            FieldType.DATE -> LocalDate.parse(text).toString()
                            FieldType.MONTH -> YearMonth.parse(text).atDay(1).toString()
                            FieldType.URL -> checkNotNull(mangaCoverUrl(text))
                        }
                        payload.put(field.key, value)
                    }
                }
                invalidField = null
                if (schema.uniqueMonth) require(existing.none { it.getString("id") != row?.optString("id") && it.getString("date") == payload.getString("date") }) { "Esiste già un record per questo mese" }
                if (schema.table == "poker_hourly_earnings") {
                    val hourly = PokerCalculations.hourly(payload.getDouble("hours_played"), payload.getDouble("profit_loss"), payload.getDouble("net_won_ev"))
                    payload.put("hours_played", hourly.hours).put("hourly_rate", hourly.hourlyRate).put("hourly_rate_ev", hourly.hourlyRateEv)
                }
                if (schema.table in listOf("cbd", "thc", "liquido_sigaretta")) {
                    val liquid = schema.table == "liquido_sigaretta"
                    val arrival = LocalDate.parse(payload.getString(if (liquid) "data_arrivo" else "data_acquisto"))
                    val finished = if (payload.isNull("data_finito")) null else LocalDate.parse(payload.getString("data_finito"))
                    require(finished == null || finished >= arrival) { "La data di fine precede l'arrivo" }
                    val derived = FumoCalculations.derived(arrival, finished, payload.getDouble(if (liquid) "millilitri" else "grammi"), payload.getDouble("costo"))
                    payload.put("giorni_durata", derived.days ?: JSONObject.NULL)
                        .put(if (liquid) "millilitri_al_giorno" else "grammi_al_giorno", derived.quantityPerDay ?: JSONObject.NULL)
                        .put("euro_al_giorno", derived.euroPerDay ?: JSONObject.NULL).put("costo_mensile", derived.monthlyCost ?: JSONObject.NULL)
                }
                schema.category?.let { payload.put("category", it) }
                if (mangaCreation && volumeNumbers != null) {
                    val owner = api.session()?.userId ?: error("Sessione scaduta")
                    val plan = MangaVolumes.prepare(owner, payload, volumeNumbers)
                    batchSnapshot = plan.encode()
                    error = null
                    saveSeries(plan)
                    return@launch
                }
                if (row == null && schema.table == "tgc_cards" && seed != null && payload.isNull("current_price")) {
                    val price = try { catalog.cardPrice(seed.getString("card_id"), payload.getString("condition")) }
                        catch (failure: CancellationException) { throw failure }
                        catch (_: Exception) { null } // Web also allows adding a card while its price service is unavailable.
                    payload.put("current_price", price ?: JSONObject.NULL)
                }
                if (row == null) api.insert(schema.table, payload.put("user_id", api.session()?.userId ?: error("Sessione scaduta")))
                else api.update(schema.table, row.getString("id"), payload)
                saved(); close()
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { error = failure.message ?: "Salvataggio non riuscito" }
            finally { loading = false }
        }
    }, enabled = !loading && (!mangaCreation || (restoredBatch.isSuccess && (batchSnapshot != null || rangeResult.isSuccess)))) {
        Text(when { batchSnapshot != null -> localized("Riprendi serie"); mangaCreation && volumeNumbers != null -> localized("Aggiungi {{count}} volumi", "count" to volumeNumbers.size); else -> localized("Salva") })
    } }, dismissButton = { TextButton(close, enabled = !loading) { Text(localized(if (batchSnapshot != null) "Chiudi serie" else "Annulla")) } })
}

@Composable
private fun recordErrorText(message: String, schema: RecordSchema): String {
    val field = schema.fields.firstOrNull { message.startsWith("${it.label}: ") } ?: return localized(message)
    return localized("{{field}}: {{error}}", "field" to localized(field.label), "error" to localized(message.removePrefix("${field.label}: ")))
}

@Composable
private fun yearlyMetrics(schema: RecordSchema, rows: List<JSONObject>, deal: Double): List<Pair<String, String>> {
    val dateField = when (schema.table) {
        "poker_hourly_earnings", "poker_rakeback" -> "date"
        "cbd", "thc" -> "data_acquisto"
        "liquido_sigaretta" -> "data_arrivo"
        else -> return emptyList()
    }
    return rows.groupBy { it.getString(dateField).take(4) }.toSortedMap(compareByDescending { it }).map { (year, group) ->
        val result = when (schema.table) {
            "poker_hourly_earnings" -> {
                val totals = PokerCalculations.aggregate(group.map { PokerCalculations.Hourly(it.getDouble("hours_played"), it.getDouble("profit_loss"), it.optDouble("net_won_ev", 0.0)) })
                localized("{{hours}} h · Net Won {{won}} · EV {{ev}}\nOrario dopo deal {{rate}} · EV/h {{evRate}}\nMediana ore {{median}}",
                    "hours" to format(totals.hours), "won" to money(totals.netWon), "ev" to money(totals.netWonEv),
                    "rate" to pokerRateMoney(totals.hourlyRate * deal), "evRate" to pokerRateMoney(totals.hourlyRateEv * deal),
                    "median" to format(PokerCalculations.median(group.map { it.getDouble("hours_played") })))
            }
            "poker_rakeback" -> {
                val rake = group.sumOf { it.getDouble("rake_generated") }
                val received = group.sumOf { it.getDouble("rakeback_received") }
                localized("Rake {{rake}} · ricevuto {{received}} · {{percent}}%", "rake" to money(rake), "received" to money(received), "percent" to format(PokerCalculations.rakebackPercentage(rake, received)))
            }
            else -> {
                val cost = group.sumOf { it.getDouble("costo") }
                val liquid = schema.table == "liquido_sigaretta"
                val quantity = group.sumOf { it.optDouble(if (liquid) "millilitri" else "grammi", 0.0) }
                val detail = if (liquid) {
                    val days = group.sumOf { row ->
                        if (row.isNull("data_finito")) 0L else FumoCalculations.derived(LocalDate.parse(row.getString("data_arrivo")),
                            LocalDate.parse(row.getString("data_finito")), row.getDouble("millilitri"), row.getDouble("costo")).days ?: 0L
                    }
                    localized(" · {{quantity}} ml/giorno", "quantity" to format(if (days > 0) quantity / days else 0.0))
                } else " · ${money(if (quantity > 0) cost / quantity else 0.0)}/g"
                localized("Costo {{cost}} · mensile {{monthly}} · {{quantity}} {{unit}}{{detail}}", "cost" to money(cost),
                    "monthly" to money(cost / 12), "quantity" to format(quantity), "unit" to if (liquid) "ml" else "g", "detail" to detail)
            }
        }
        year to result
    }
}

@Composable
private fun CatalogSearchDialog(catalog: CatalogRepository, schema: RecordSchema, close: () -> Unit, selected: (JSONObject) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    var searchSet by rememberSaveable { mutableStateOf<String?>(null) }
    val cards = schema.table == "tgc_cards"
    AlertDialog(onDismissRequest = close, title = { Text(localized(if (cards) "Ricerca CardTrader" else "Ricerca Google Books")) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(localized(if (cards) "Nome carta" else "Titolo")) }, enabled = !loading)
            Button(onClick = {
                scope.launch {
                    loading = true; searched = true; results = emptyList(); error = null
                    try { results = if (cards) catalog.searchCards(query) else catalog.searchBooks(query, schema.category == "fumetti") }
                    catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { error = failure.message ?: "Ricerca non disponibile" }
                    finally { loading = false }
                }
            }, enabled = !loading && query.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(localized("Cerca")) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
            if (searched && !loading && results.isEmpty() && error == null) Text(localized("Nessun risultato"))
            if (cards && results.isNotEmpty()) CollectionFilterPicker("Set risultati", searchSet, CollectionFilters.searchSets(results)) { searchSet = it }
            val visibleResults = if (cards) CollectionFilters.searchCards(results, searchSet) else results
            if (!loading && results.isNotEmpty() && visibleResults.isEmpty()) Text(localized("Nessun risultato per il set selezionato"))
            visibleResults.forEach { result ->
                val info = result.optJSONObject("volumeInfo")
                val image = (if (cards) result.optString("image") else info?.optJSONObject("imageLinks")?.optString("thumbnail"))
                    ?.replace("http://", "https://")?.takeIf { it.startsWith("https://") }
                if (image != null) AsyncImage(model = image,
                    contentDescription = localized("Copertina di {{title}}", "title" to if (cards) result.optString("name") else info?.optString("title").orEmpty()),
                    modifier = Modifier.size(width = 72.dp, height = 96.dp), contentScale = ContentScale.Fit)
                OutlinedButton(onClick = { selected(if (cards) CatalogRepository.cardSeed(result) else CatalogRepository.bookSeed(result)) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (cards) "${result.getString("name")} · ${result.optJSONObject("set")?.optString("name").orEmpty()}"
                    else "${result.getJSONObject("volumeInfo").getString("title")} · ${result.getJSONObject("volumeInfo").optJSONArray("authors")?.optString(0).orEmpty()}")
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text(localized("Chiudi")) } })
}

@Composable
private fun CollectionFilterPicker(label: String, selection: String?, options: List<String>, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("${localized(label)}: ${selection ?: localized("Tutti")}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            DropdownMenuItem(text = { Text(localized("Tutti")) }, onClick = { onSelect(null); expanded = false })
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false }) }
        }
    }
}
