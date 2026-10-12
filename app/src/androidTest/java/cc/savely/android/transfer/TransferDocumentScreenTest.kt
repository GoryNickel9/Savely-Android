package cc.savely.android.transfer

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.savely.android.ui.SavelyTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * SAF contracts use a fixture result registry, with actual app-owned MediaStore metadata and streams.
 * No system picker UI, process-death, external backend or API26 storage coverage is claimed.
 * Scoped MediaStore Downloads needs API29+: these tests intentionally skip only earlier devices.
 */
@SdkSuppress(minSdkVersion = 29)
class TransferDocumentScreenTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(90)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val documents = CopyOnWriteArrayList<Uri>()
    private val visible = mutableStateOf(true)
    private lateinit var fixture: TransferTlsFixture
    private lateinit var registry: DocumentRegistry
    private lateinit var registryOwner: ActivityResultRegistryOwner
    private lateinit var focus: FocusManager
    private var keyboard: SoftwareKeyboardController? = null
    private var lastTouch: Rect? = null
    private var mounted = false
    private val lifecycle = ActivityLifecycleCallback { activity, stage ->
        if (mounted && activity is ComponentActivity && stage == Stage.CREATED) {
            // Reinstall the same production screen in the replacement Activity; its ViewModelStore survives recreation.
            activity.setContent { FixtureContent() }
        }
    }

    @Before fun setup() {
        fixture = TransferTlsFixture()
        registry = DocumentRegistry(context)
        registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle)
        }
    }

    @After fun cleanup() {
        if (mounted) { compose.runOnIdle { visible.value = false }; compose.waitForIdle() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
        }
        fixture.close()
        documents.forEach { context.contentResolver.delete(it, null, null) }
        assertEquals("No foreign record mutation", fixture.foreignSnapshot, fixture.foreign.toString())
        assertEquals("Only whitelisted owned TLS requests", 0, fixture.denied.get())
        assertEquals("No PATCH/DELETE or unrelated POST", 0, fixture.otherMutations.get())
    }

    @Test fun cancelledDocumentAndExcelOrMalformedUtf8NeverReadBackendOrWrite() {
        mount()
        registry.enqueueCancelled()
        touch(button("Scegli CSV e prepara anteprima"))
        await("OpenDocument cancellation delivered") { registry.delivered == 1 }
        assertOpenContract(registry.intents.single())
        assertEquals(0, fixture.reads.get())
        assertEquals(0, fixture.posts.get())
        compose.onNodeWithText("Anteprima pronta. Nessun dato è stato scritto.").assertDoesNotExist()

        registry.enqueue(document("unsupported.xlsx", "not-an-excel-fixture".toByteArray()))
        touch(button("Scegli CSV e prepara anteprima"))
        message("File Excel non supportato. Esporta prima un CSV dalla banca.")
        registry.enqueue(document("invalid-utf8.csv", byteArrayOf(0xC3.toByte(), 0x28)))
        touch(button("Scegli CSV e prepara anteprima"))
        message("Il file non è un CSV UTF-8. Esportalo con questa codifica.")
        assertEquals(3, registry.intents.size)
        registry.intents.forEach(::assertOpenContract)
        assertEquals(0, fixture.reads.get())
        assertEquals(0, fixture.posts.get())
        assertEquals(1, fixture.stored().size)
    }

    @Test fun previewExcludesDuplicatesAndInvalidRowsThenOnlyExplicitConfirmationInsertsOnce() {
        val csv = normalized(
            "2026-10-10,Già presente,5.25,EUR,expense,${fixture.category},1",
            "2026-10-11,Spesa nuova,12.50,EUR,expense,${fixture.category},1",
            "2026-10-12,Importo invalido,-3,EUR,expense,${fixture.category},1")
        prepare(csv)
        compose.onNodeWithText("1 duplicate; 0 righe escluse dal formato; 1 righe non valide.").performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        compose.onNodeWithText("Riga 2: Duplicata, esclusa").performScrollTo().assertIsDisplayed()
        assertEquals(0, fixture.posts.get())
        openImportConfirmation()
        compose.onNodeWithText("Conferma importazione").assertIsDisplayed()
        touch(button("Annulla"))
        assertEquals(0, fixture.posts.get())
        openImportConfirmation()
        touch(button("Conferma e importa"))
        await("Owned insert starts once") { fixture.posts.get() == 1 }
        assertEquals(1, fixture.stored().size)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
        compose.onNode(button("Importa 1 transazioni selezionate")).assertIsNotEnabled()
        fixture.releasePost.countDown()
        message("1 importate, 1 duplicate, 0 fallite, 0 con esito da verificare.")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(6)
        compose.onNodeWithText("Riga 3: Importata").performScrollTo().assertIsDisplayed()
        val stored = readPersisted("Spesa nuova")
        assertEquals(fixture.owner, stored.getString("user_id"))
        assertEquals(fixture.category, stored.getString("category_id"))
        assertEquals("2026-10-11", stored.getString("date"))
        assertEquals("12.5", stored.get("amount").toString())
        assertEquals("EUR", stored.getString("currency"))
        assertEquals("1", stored.get("exchange_rate_eur").toString())
        assertTrue(UUID.fromString(stored.getString("id")).toString().isNotBlank())
        assertNotEquals(fixture.stored().first().getString("id"), stored.getString("id"))
        assertEquals(1, fixture.posts.get())
    }

    @Test fun keyboardRateValidationAndActivityRecreationRetainDraftButNeverAutoConfirmDialog() {
        prepare(normalized("2026-10-11,Spesa USD,12.50,USD,expense,${fixture.category},1.08"))
        replaceRate("0")
        openImportConfirmation()
        touch(button("Conferma e importa"))
        message("Riga 2: cambio EUR non valido.")
        assertEquals(0, fixture.posts.get())
        replaceRate("1.08")
        openImportConfirmation()
        compose.onNodeWithText("Conferma importazione").assertIsDisplayed()
        val readsBeforeRecreation = fixture.reads.get()
        compose.activityRule.scenario.recreate()
        scrollRecreatedList(5)
        await("Preview survives real Activity recreation") { compose.onAllNodesWithText("Spesa USD").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        assertSelectedFormat(ImportFormat.NORMALIZED)
        compose.onNodeWithText("Conferma importazione").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        compose.onNode(rateField()).performScrollTo().assertTextContains("1.08")
        assertEquals(readsBeforeRecreation, fixture.reads.get())
        assertEquals(0, fixture.posts.get())
        openImportConfirmation()
        touch(button("Conferma e importa"))
        await("One post after renewed explicit confirmation") { fixture.posts.get() == 1 }
        fixture.releasePost.countDown()
        message("1 importate, 0 duplicate, 0 fallite, 0 con esito da verificare.")
        val row = readPersisted("Spesa USD")
        assertEquals("USD", row.getString("currency"))
        assertEquals("12.5", row.get("amount").toString())
        assertEquals("1.08", row.get("exchange_rate_eur").toString())
        assertEquals(fixture.owner, row.getString("user_id"))
        assertEquals(1, fixture.posts.get())
    }

    @Test fun bankCurrencyAndFormatSurviveRecreationWhileDocumentResultIsPending() {
        mount()
        touch(button(ImportFormat.BANK.label))
        touch(button("EUR"))
        touch(button("USD"))
        val csv = "Data,Descrizione,Importo\n2026-10-11,Spesa banca,-12.50"
        registry.enqueueDeferred(document("bank.csv", csv.toByteArray(Charsets.UTF_8)))
        touch(button("Scegli CSV e prepara anteprima"))
        assertOpenContract(registry.intents.single())
        assertEquals(0, registry.delivered)
        assertEquals(0, fixture.reads.get())
        compose.activityRule.scenario.recreate()
        scrollRecreatedList(1)
        assertSelectedFormat(ImportFormat.BANK)
        compose.onNode(button("USD")).performScrollTo().assertExists()
        compose.runOnIdle { registry.deliverDeferred() }
        message("Anteprima pronta. Nessun dato è stato scritto.")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        compose.onNodeWithText("2026-10-11 · 12.50 USD · Uscita").performScrollTo().assertIsDisplayed()
        compose.onNode(rateField()).performScrollTo().assertExists()
        assertEquals(1, registry.delivered)
        assertEquals(0, fixture.posts.get())
        assertEquals(1, fixture.stored().size)

        compose.activityRule.scenario.recreate()
        scrollRecreatedList(1)
        assertSelectedFormat(ImportFormat.BANK)
        compose.onNode(button("USD")).performScrollTo().assertExists()
        replaceRate("1.08")
        openImportConfirmation()
        touch(button("Annulla"))
        assertEquals(0, fixture.posts.get())
        assertEquals(1, fixture.stored().size)
    }

    @Test fun normalizedFormatSurvivesPendingPickerRecreationWithoutAutomaticImport() {
        mount()
        touch(button(ImportFormat.NORMALIZED.label))
        registry.enqueueDeferred(document("normalized.csv", normalized(
            "2026-10-11,Spesa picker,12.50,EUR,expense,${fixture.category},1").toByteArray(Charsets.UTF_8)))
        touch(button("Scegli CSV e prepara anteprima"))
        compose.activityRule.scenario.recreate()
        scrollRecreatedList(1)
        assertSelectedFormat(ImportFormat.NORMALIZED)
        assertEquals(0, fixture.reads.get())
        compose.runOnIdle { registry.deliverDeferred() }
        message("Anteprima pronta. Nessun dato è stato scritto.")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        compose.onNodeWithText("Spesa picker").performScrollTo().assertIsDisplayed()
        assertEquals(0, fixture.posts.get())
        assertEquals(1, registry.delivered)
    }

    @Test fun cancellingPendingPickerAfterRecreationPreservesOptionsWithoutBackendReads() {
        mount()
        touch(button(ImportFormat.BANK.label))
        touch(button("EUR"))
        touch(button("USD"))
        registry.enqueueDeferredCancellation()
        touch(button("Scegli CSV e prepara anteprima"))
        compose.activityRule.scenario.recreate()
        scrollRecreatedList(1)
        compose.runOnIdle { registry.deliverDeferred() }
        await("Deferred cancellation delivered") { registry.delivered == 1 }
        assertSelectedFormat(ImportFormat.BANK)
        compose.onNode(button("USD")).performScrollTo().assertExists()
        compose.onNodeWithText("Anteprima pronta. Nessun dato è stato scritto.").assertDoesNotExist()
        assertEquals(0, fixture.reads.get())
        assertEquals(0, fixture.posts.get())
        assertEquals(1, fixture.stored().size)
    }

    @Test fun commitWith503SurvivesBackgroundAndRecreationThenExplicitReadResolvesWithoutRetry() {
        fixture.ambiguousCommit = true
        prepare(normalized("2026-10-11,Esito ambiguo,7.25,EUR,expense,${fixture.category},1"))
        openImportConfirmation()
        touch(button("Conferma e importa"))
        await("TLS post gated") { fixture.posts.get() == 1 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        fixture.releasePost.countDown()
        assertTrue("Synthetic commit completes while screen is stopped", fixture.postFinished.await(15, TimeUnit.SECONDS))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        message("0 importate, 0 duplicate, 0 fallite, 1 con esito da verificare.")
        val insertedId = fixture.stored().single { it.getString("description") == "Esito ambiguo" }.getString("id")
        compose.activityRule.scenario.recreate()
        scrollRecreatedList(4)
        await("Uncertain row survives recreation") { compose.onAllNodesWithText("Verifica gli esiti incerti").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, fixture.posts.get())
        compose.onNode(button("Importa 0 transazioni selezionate")).performScrollTo().assertIsNotEnabled()
        val readsBeforeRecheck = fixture.reads.get()
        touch(button("Verifica gli esiti incerti"))
        message("Dati riletti. Nessun retry automatico. Payload diversi o esiti ancora incerti restano bloccati.")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        compose.onNodeWithText("Riga 2: Importata").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Presente nel backend dopo verifica.").assertIsDisplayed()
        assertTrue(fixture.reads.get() > readsBeforeRecheck)
        assertEquals(insertedId, fixture.stored().single { it.getString("description") == "Esito ambiguo" }.getString("id"))
        assertEquals(1, fixture.posts.get())
        assertEquals(2, fixture.stored().size)
    }

    @Test fun allTablesExportUsesCreateDocumentCancellationThenRealProviderWriteWithoutBackendMutation() {
        mount()
        touch(button("Prepara export CSV"))
        message("2 righe in 25 tabelle. Scegli dove salvare il CSV.")
        assertEquals(TransferRepository.USER_TABLES.toSet() - setOf("couple_budgets", "shared_expenses"), fixture.readTables.toSet())
        registry.enqueueCancelled()
        touch(button("Salva CSV (2 righe)"))
        await("CreateDocument cancellation delivered") { registry.delivered == 1 }
        assertCreateContract(registry.intents.single())
        compose.onNodeWithText("CSV salvato: 2 righe da 25 tabelle.").assertDoesNotExist()
        val output = document("export-fixture.csv", byteArrayOf())
        registry.enqueue(output)
        touch(button("Salva CSV (2 righe)"))
        message("CSV salvato: 2 righe da 25 tabelle.")
        registry.intents.forEach(::assertCreateContract)
        val text = context.contentResolver.openInputStream(output)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val rows = TransferCsv.parseRows(text)
        assertEquals(3, rows.size)
        val records = rows.drop(1).map { rows.first().zip(it).toMap() }
        val transaction = records.single { it["entity"] == "transactions" }
        assertEquals(fixture.owner, transaction["user_id"])
        assertEquals("Già presente", transaction["description"])
        assertEquals("5.25", transaction["amount"])
        assertEquals("EUR", transaction["currency"])
        assertEquals("", transaction["notes"])
        assertEquals("€\n\"quote\"", JSONObject(transaction.getValue("metadata")).getString("symbols"))
        assertEquals("Spese fixture", records.single { it["entity"] == "categories" }["name"])
        assertFalse(text.contains("Foreign private transaction"))
        assertEquals(0, fixture.posts.get())
        assertEquals(2, registry.intents.size)
    }

    @Test fun failingExportTableOffersNoPartialDocumentAndOnlyExplicitRetryCanPrepareCompleteData() {
        fixture.failTable = "budgets"
        mount()
        touch(button("Prepara export CSV"))
        message("Servizio temporaneamente non disponibile. Riprova.")
        compose.onNodeWithText("Salva CSV (2 righe)").assertDoesNotExist()
        assertEquals(0, registry.intents.size)
        assertEquals(0, fixture.posts.get())
        val readsAtFailure = fixture.reads.get()
        compose.activityRule.scenario.recreate()
        await("Export failure retained without automatic retry") {
            compose.onAllNodesWithText("Servizio temporaneamente non disponibile. Riprova.").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(readsAtFailure, fixture.reads.get())
        assertEquals(0, registry.intents.size)
        fixture.failTable = null
        touch(button("Prepara export CSV"))
        message("2 righe in 25 tabelle. Scegli dove salvare il CSV.")
        compose.onNode(button("Salva CSV (2 righe)")).performScrollTo().assertIsEnabled()
        assertEquals(0, registry.intents.size)
        assertEquals(0, fixture.posts.get())
    }

    private fun mount() {
        mounted = true
        compose.setContent { FixtureContent() }
        await("Owner screen loaded") { compose.onAllNodesWithText("Scegli CSV e prepara anteprima").fetchSemanticsNodes().isNotEmpty() }
    }

    @Composable private fun FixtureContent() {
        if (visible.value) {
            val manager = LocalFocusManager.current
            val controller = LocalSoftwareKeyboardController.current
            SideEffect { focus = manager; keyboard = controller }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                SavelyTheme("light") { TransferScreen(fixture.api, Modifier.fillMaxSize()) }
            }
        }
    }

    private fun prepare(csv: String) {
        mount()
        touch(button("CSV transazioni"))
        registry.enqueue(document("transactions.csv", csv.toByteArray(Charsets.UTF_8)))
        touch(button("Scegli CSV e prepara anteprima"))
        message("Anteprima pronta. Nessun dato è stato scritto.")
        assertOpenContract(registry.intents.single())
        assertEquals(0, fixture.posts.get())
    }

    private fun normalized(vararg rows: String) = "date,description,amount,currency,type,category_id,exchange_rate_eur\n" + rows.joinToString("\n")
    private fun assertSelectedFormat(format: ImportFormat) {
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertCountEquals(ImportFormat.entries.size)
            .get(ImportFormat.entries.indexOf(format)).assertIsSelected()
    }
    private fun button(text: String) = hasText(text, substring = false) and hasClickAction()
    private fun rateField() = hasText("1 USD in EUR", substring = false) and hasSetTextAction()

    private fun replaceRate(value: String) {
        clearInput()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        touch(rateField())
        val field = compose.onNode(rateField())
        field.performTextReplacement(value)
        field.assertTextContains(value)
    }

    private fun message(text: String) {
        clearInput()
        // Busy progress and final message occupy index 3; make the lazy item observable before waiting.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        await("Message: $text") { compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text, substring = false).performScrollTo().assertIsDisplayed()
    }

    private fun readPersisted(description: String): JSONObject = runBlocking {
        val rows = fixture.api.select("transactions", "select=*&order=id.asc&limit=500&user_id=eq.${fixture.owner}")
        (0 until rows.length()).map(rows::getJSONObject).single { it.getString("description") == description }
    }

    private fun clearInput() { compose.runOnIdle { focus.clearFocus(force = true); keyboard?.hide() } }

    private fun openImportConfirmation() {
        clearInput()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
        touch(button("Importa 1 transazioni selezionate"))
    }

    private fun scrollRecreatedList(index: Int) {
        await("Recreated owner list available") { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        clearInput()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
    }

    private fun touch(matcher: SemanticsMatcher) {
        clearInput()
        val interaction = compose.onNode(matcher)
        val node = interaction.fetchSemanticsNode()
        if (generateSequence(node.parent) { it.parent }.any { it.config.contains(SemanticsActions.ScrollBy) }) {
            interaction.performScrollTo()
        }
        interaction.assertIsEnabled().assertIsDisplayed()
        var prior: Rect? = null
        var stableFrames = 0
        await("Touch bounds stable") {
            compose.mainClock.advanceTimeByFrame()
            val node = runCatching { interaction.fetchSemanticsNode() }.getOrNull() ?: return@await false
            val bounds = node.boundsInRoot
            lastTouch = bounds
            var root = node
            while (root.parent != null) root = root.parent!!
            stableFrames = if (prior == bounds) stableFrames + 1 else 0
            prior = bounds
            stableFrames >= 2 && bounds.width > 0 && bounds.height > 0 && root.boundsInRoot.contains(bounds.center)
        }
        interaction.assertIsDisplayed().performClick()
    }

    private fun await(label: String, condition: () -> Boolean) {
        try { compose.waitUntil(15_000, condition) }
        catch (failure: ComposeTimeoutException) {
            throw AssertionError("$label; clock=${compose.mainClock.currentTime}, reads=${fixture.reads.get()}, " +
                "posts=${fixture.posts.get()}, denied=${fixture.denied.get()}, documents=${registry.intents.size}, touch=$lastTouch", failure)
        }
    }

    private fun assertOpenContract(intent: Intent) {
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertEquals(listOf("text/*", "application/csv", "application/vnd.ms-excel"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
    }
    private fun assertCreateContract(intent: Intent) {
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("text/csv", intent.type)
        assertEquals("savely_export_${LocalDate.now()}.csv", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    private fun document(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${UUID.randomUUID()}-$name")
            put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".xlsx")) "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SavelyFixtures/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        documents += uri
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private class DocumentRegistry(private val context: Context) : ActivityResultRegistry() {
        data class Result(val code: Int, val uri: Uri?, val deferred: Boolean = false)
        val intents = CopyOnWriteArrayList<Intent>()
        private val pending = ConcurrentLinkedQueue<Result>()
        private var deferredResult: Pair<Int, Result>? = null
        @Volatile var delivered = 0
        fun enqueue(uri: Uri) { pending.add(Result(Activity.RESULT_OK, uri)) }
        fun enqueueCancelled() { pending.add(Result(Activity.RESULT_CANCELED, null)) }
        fun enqueueDeferred(uri: Uri) { pending.add(Result(Activity.RESULT_OK, uri, deferred = true)) }
        fun enqueueDeferredCancellation() { pending.add(Result(Activity.RESULT_CANCELED, null, deferred = true)) }
        fun deliverDeferred() {
            val (requestCode, result) = checkNotNull(deferredResult) { "No pending document launch" }
            deferredResult = null
            deliver(requestCode, result)
        }
        private fun deliver(requestCode: Int, result: Result) {
            dispatchResult(requestCode, result.code, result.uri?.let { Intent().setData(it) })
            delivered++
        }
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            val intent = contract.createIntent(context, input)
            check(intent.action == Intent.ACTION_OPEN_DOCUMENT || intent.action == Intent.ACTION_CREATE_DOCUMENT)
            intents += intent
            val result = checkNotNull(pending.poll()) { "Document result must be explicitly queued by the fixture" }
            if (result.deferred) {
                check(deferredResult == null) { "A document launch is already pending" }
                deferredResult = requestCode to result
                return
            }
            Handler(Looper.getMainLooper()).post {
                deliver(requestCode, result)
            }
        }
    }
}
