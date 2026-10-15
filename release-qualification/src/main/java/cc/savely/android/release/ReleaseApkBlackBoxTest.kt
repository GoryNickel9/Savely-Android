package cc.savely.android.release

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.UUID
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Opt-in accessibility-only qualification of the installed, signed, non-debuggable target APK. */
@Suppress("DEPRECATION") // recycle still releases accessibility nodes on supported API 26-32.
@RunWith(AndroidJUnit4::class)
class ReleaseApkBlackBoxTest {
    // Additive F04 hunk: no production class, store, API or private target data access.
    @Test fun qualifyReservedMangaProcessDeath() {
        val run = preflight(18)
        requireCode(dedicatedQaAccount().first == QA_USER, "MANGA_ACCOUNT_INVALID")
        val op = InstrumentationRegistry.getArguments().getString("mangaOperation").orEmpty()
        requireCode(op in setOf("prepare", "submit", "restored", "resume"), "MANGA_OPERATION_INVALID")
        val phase = "manga-$op"
        requireNewNavigationReport(run, phase)
        requireNewNavigationReport(run, "$phase-reservation")
        val name = "release-qualification-$run-$phase-input.json"
        val input = readJson(name, 4_096)
        requireCode(input.keys().asSequence().toSet() == setOf("run_id", "user_id", "operation", "login_run_id",
            "session_id", "previous_pid", "task_id", "confirmed") && input.optString("run_id") == run &&
            input.optString("user_id") == QA_USER && input.optString("operation") == op, "MANGA_INPUT_INVALID")
        val login = input.optString("login_run_id")
        val sid = input.optString("session_id")
        requireCode(runCatching { UUID.fromString(login).toString() == login && UUID.fromString(sid).toString() == sid }
            .getOrDefault(false) && login != run, "MANGA_REFERENCE_INVALID")
        fireRequireLogin(login, QA_USER)
        writeReport(run, "$phase-reservation", JSONObject(input.toString()).put("stage", "reserved"))
        requireCode(privateFile(name).delete(), "MANGA_INPUT_CONSUMPTION_FAILED")
        val title = "android-f04-manga-$run"
        val report = baseReport(run, 18, phase).put("user_id", QA_USER).put("login_run_id", login)
            .put("session_id", sid).put("operation", op).put("mutation_attempted", false)
            .put("cleanup_pending", true).put("session_id_source", "host-readonly-binding-not-target-store")
        try {
            if (op == "prepare") {
                requireCode(input.optInt("previous_pid", -1) == 0 && input.optInt("task_id", -1) == 0 &&
                    input.optInt("confirmed", -1) == 0, "MANGA_PREPARE_INPUT_INVALID")
                launch()
                financeOpenProfile(); requireEmail(QA_EMAIL); awaitProfileRead()
                financeEnter("Library")
                if (!selected("Manga")) {
                    val manga = seekLabel(setOf("Manga"), "MANGA_TAB_MISSING", true, clickable = true)
                    clickControl(manga, "MANGA_TAB_CLICK_FAILED")
                }
                await("MANGA_TAB_NOT_SELECTED") { selected("Manga") }
                clickLabel(setOf("Add"), "MANGA_ADD_MISSING")
                financeSetField("Title", title)
                financeSetField("Volume number or range (optional)", "1-3")
                financeSetField("Purchase price per unit (€) (optional)", "0.01")
                financeSetField("Resale value per unit (€) (optional)", "0.02")
                financeSetField("Notes (optional)", "android-f04-$run")
                hideKeyboardIfShown()
                val add = seekLabel(setOf("Add 3 volumes"), "MANGA_SUBMIT_MISSING", true, clickable = true)
                try { requireCode(add.isEnabled, "MANGA_SUBMIT_DISABLED") } finally { add.recycle() }
            } else {
                val prepared = mangaPrior(run, "prepare", login, sid)
                requireCode(prepared.getInt("pid") == input.optInt("previous_pid") &&
                    prepared.getInt("task_id") == input.optInt("task_id"), "MANGA_TASK_REFERENCE_INVALID")
                if (op == "restored") {
                    requireCode(input.optInt("confirmed") in 1..2, "MANGA_PARTIAL_REQUIRED")
                    mangaOpenExistingTaskFromRecents()
                    val current = mangaPidAndTask()
                    requireCode(current.first != prepared.getInt("pid") && current.second == prepared.getInt("task_id"),
                        "MANGA_PROCESS_RESTORATION_INVALID")
                    report.put("confirmed_ui", mangaPendingPlan(title, input.getInt("confirmed")))
                    report.put("new_process", true).put("same_task", true).put("resume_tapped", false)
                } else if (op == "submit") {
                    requireCode(input.optInt("confirmed", -1) == 0, "MANGA_SUBMIT_INPUT_INVALID")
                    requireCode(mangaPidAndTask() == (prepared.getInt("pid") to prepared.getInt("task_id")),
                        "MANGA_PREPARED_PROCESS_CHANGED")
                    requireCode(!hasText("Resume series"), "MANGA_ALREADY_SUBMITTED")
                    val add = seekLabel(setOf("Add 3 volumes"), "MANGA_SUBMIT_MISSING", true, clickable = true)
                    writeReport(run, "$phase-before-submit", JSONObject(input.toString()).put("stage", "before-submit"))
                    report.put("mutation_attempted", true)
                    clickControl(add, "MANGA_SUBMIT_CLICK_FAILED")
                    // Do not wait for all writes: the host observer must be able to stop the Activity.
                    report.put("sole_submit_tapped", true)
                } else {
                    val restored = mangaPrior(run, "restored", login, sid)
                    requireCode(input.optInt("confirmed") in 1..2 && restored.optBoolean("new_process") &&
                        restored.optBoolean("same_task") && mangaPidAndTask() ==
                        (restored.getInt("pid") to restored.getInt("task_id")), "MANGA_RESTORED_REFERENCE_INVALID")
                    mangaPendingPlan(title, input.getInt("confirmed"))
                    val resume = seekLabel(setOf("Resume series"), "MANGA_RESUME_MISSING", true, clickable = true)
                    writeReport(run, "$phase-before-submit", JSONObject(input.toString()).put("stage", "before-submit"))
                    report.put("mutation_attempted", true)
                    clickControl(resume, "MANGA_RESUME_CLICK_FAILED")
                    seekLabel(setOf("Title: $title Vol. 3"), "MANGA_RESUME_NOT_COMPLETED", true).recycle()
                    report.put("explicit_resume_tapped", true)
                }
            }
            val current = if (op == "submit") input.getInt("previous_pid") to input.getInt("task_id") else mangaPidAndTask()
            report.put("pid", current.first).put("task_id", current.second).put("stage", "passed")
            writeReport(run, phase, report)
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure))
            writeReport(run, phase, report)
            throw IllegalStateException(report.getString("failure_code"))
        }
    }

    private fun mangaPrior(run: String, op: String, login: String, sid: String): JSONObject {
        val phase = "manga-$op"
        val prior = readJson("release-qualification-$run-$phase.json", 16_384)
        requireCode(prior.optString("run_id") == run && prior.optString("phase") == phase &&
            prior.optString("stage") == "passed" && prior.optString("user_id") == QA_USER &&
            prior.optString("login_run_id") == login && prior.optString("session_id") == sid &&
            prior.optInt("target_code") == 18 && prior.optString("target_package") == TARGET,
            "MANGA_PRIOR_INVALID")
        return prior
    }

    private fun mangaPendingPlan(title: String, confirmed: Int): Int {
        // A committed response may not have reached the saved plan before STOPPED.
        val labels = (0..confirmed).associateBy { "$it/3 volumes confirmed · $title" }
        repeat(8) {
            if (labels.keys.any(::hasText)) return@repeat
            val activeId = automation.rootInActiveWindow?.let { node ->
                try { node.windowId } finally { node.recycle() }
            }
            val scroll = find { it.windowId == activeId && it.isScrollable && visible(it) &&
                it.className?.toString() == "android.widget.ScrollView" }
            scroll?.let { try { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } finally { it.recycle() } }
            SystemClock.sleep(200)
        }
        await("MANGA_SAVED_PLAN_NOT_RESTORED") { labels.keys.any(::hasText) && hasText("Resume series") }
        val seen = labels.entries.firstOrNull { hasText(it.key) }?.value
            ?: throw IllegalStateException("MANGA_PROGRESS_MISSING")
        val resume = seekLabel(setOf("Resume series"), "MANGA_RESUME_MISSING", true, clickable = true)
        try { requireCode(resume.isEnabled, "MANGA_RESUME_DISABLED") } finally { resume.recycle() }
        return seen
    }

    private fun mangaPidAndTask(): Pair<Int, Int> {
        val pid = readShell("pidof $TARGET", 128).trim().toIntOrNull()
        requireCode(pid != null && pid > 0, "MANGA_TARGET_PID_MISSING")
        val state = readShell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'", 8_192)
        val ids = Regex("${Regex.escape(TARGET)}/(?:\\.MainActivity|${Regex.escape(TARGET)}\\.MainActivity) t(\\d+)")
            .findAll(state).map { it.groupValues[1].toInt() }.toSet()
        requireCode(ids.size == 1, "MANGA_RESUMED_TASK_MISSING")
        return pid!! to ids.single()
    }

    private fun mangaOpenExistingTaskFromRecents() {
        // No MainActivity launch/new Intent: only a visible existing Savely task card.
        readShell("input keyevent KEYCODE_APP_SWITCH", 256)
        var card: AccessibilityNodeInfo? = null
        await("MANGA_RECENTS_TASK_MISSING") {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            automation.windows.forEach { it.root?.let(queue::addLast) }
            var visited = 0
            try {
                while (queue.isNotEmpty() && visited++ < 2_048 && card == null) {
                    val node = queue.removeFirst()
                    val launcher = node.packageName?.toString() in setOf("com.android.launcher3", "com.google.android.apps.nexuslauncher", "com.android.systemui")
                    if (launcher && visible(node) && node.isEnabled && node.isClickable &&
                        (node.text?.toString() == "Savely" || node.contentDescription?.toString() in setOf("Savely", "Task snapshot of Savely"))) {
                        card = AccessibilityNodeInfo.obtain(node)
                    }
                    for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
                    node.recycle()
                }
            } finally { while (queue.isNotEmpty()) queue.removeFirst().recycle() }
            card != null
        }
        try { requireCode(card!!.performAction(AccessibilityNodeInfo.ACTION_CLICK), "MANGA_RECENTS_CLICK_FAILED") }
        finally { card?.recycle() }
        await("MANGA_RECENTS_NOT_TARGET") { automation.rootInActiveWindow?.let {
            try { it.packageName?.toString() == TARGET } finally { it.recycle() }
        } == true }
    }

    /** F02/F03: one host-reserved operation; only native UI controls may write account data. */
    @Test fun qualifyReservedTransferForm() {
        val run = preflight(18)
        requireCode(dedicatedQaAccount().first == QA_USER, "TRANSFER_ACCOUNT_INVALID")
        val operation = InstrumentationRegistry.getArguments().getString("transferOperation").orEmpty()
        requireCode(operation in setOf("revolut", "revolut-duplicate", "bbva", "tr", "tr-duplicate", "export", "roundtrip",
            "delete-revolut", "delete-bank", "delete-asset", "delete-isin", "delete-category"), "TRANSFER_OPERATION_INVALID")
        val phase = "transfer-$operation"
        requireNewNavigationReport(run, phase)
        requireNewNavigationReport(run, "$phase-reservation")
        val inputName = "release-qualification-$run-$phase-input.json"
        val input = readJson(inputName, 4_096)
        requireCode(input.keys().asSequence().toSet() == setOf("run_id", "user_id", "operation", "login_run_id", "day", "expected_rows", "file_name", "file_sha256") &&
            input.optString("run_id") == run && input.optString("user_id") == QA_USER && input.optString("operation") == operation,
            "TRANSFER_INPUT_INVALID")
        val loginRun = input.getString("login_run_id")
        requireCode(runCatching { UUID.fromString(loginRun).toString() == loginRun && loginRun != run }.getOrDefault(false), "TRANSFER_LOGIN_REFERENCE_INVALID")
        val login = readJson("release-qualification-$loginRun-couple-login.json", 16_384)
        requireCode(login.optString("run_id") == loginRun && login.optString("user_id") == QA_USER && login.optInt("target_code") == 18 &&
            login.optString("target_package") == TARGET && login.optString("stage") == "passed" &&
            login.optBoolean("settings_identity") && login.optBoolean("profile_read") && login.optBoolean("english_selected"), "TRANSFER_LOGIN_INVALID")
        val day = runCatching { java.time.LocalDate.parse(input.getString("day")) }.getOrNull()
        requireCode(day != null && day.toString() == input.getString("day") && java.time.LocalDate.now() == day &&
            java.time.LocalDate.now(java.time.ZoneId.of("Europe/Rome")) == day, "TRANSFER_DEVICE_DAY_MISMATCH")
        val expectedRows = input.getInt("expected_rows")
        requireCode(expectedRows in 0..500, "TRANSFER_EXPECTED_ROWS_INVALID")
        val fileName = input.getString("file_name")
        val fileHash = input.getString("file_sha256")
        val selectedFile = when (operation) {
            "revolut", "revolut-duplicate" -> "revolut-usd-$run.csv"
            "bbva" -> "bbva-keyword-note-$run.csv"
            "tr", "tr-duplicate" -> "tr-isin-$run.csv"
            else -> null
        }
        if (selectedFile != null || operation == "roundtrip") {
            requireCode(fileName == selectedFile || operation == "roundtrip" &&
                fileName.matches(Regex("savely_export_\\d{4}-\\d{2}-\\d{2}_[0-9a-f-]{36}\\.csv")), "TRANSFER_FILE_NAME_INVALID")
            requireCode(fileHash.matches(Regex("[0-9a-f]{64}")), "TRANSFER_FILE_HASH_INVALID")
            val hash = readShell("sha256sum /sdcard/Download/savely-transfer-$run/$fileName", 512).substringBefore(' ')
            requireCode(hash == fileHash, "TRANSFER_DEVICE_FILE_CHANGED")
        } else requireCode(fileName.isEmpty() && fileHash.isEmpty(), "TRANSFER_UNEXPECTED_FILE")
        writeReport(run, "$phase-reservation", JSONObject(input.toString()).put("stage", "reserved"))
        requireCode(privateFile(inputName).delete(), "TRANSFER_INPUT_CONSUMPTION_FAILED")
        val marker = "android-transfer-form-$run"
        val report = baseReport(run, 18, phase).put("login_run_id", loginRun).put("operation", operation)
            .put("day", day.toString()).put("mutation_attempted", false).put("cleanup_pending", true)
        try {
            report.put("pid", launch())
            financeOpenProfile(); requireEmail(QA_EMAIL); awaitProfileRead()
            report.put("settings_identity", true).put("profile_read", true)
            if (operation.startsWith("delete-") && operation != "delete-isin") {
                val name = when (operation) {
                    "delete-revolut" -> "$marker-revolut"
                    "delete-bank" -> "$marker-keyword - receipt \"A\"; local"
                    "delete-asset" -> "$marker-asset"
                    else -> "$marker-expense"
                }
                financeEnter(when (operation) { "delete-asset" -> "Portfolio"; "delete-category" -> "Categories"; else -> "Transactions" })
                if (operation == "delete-asset") { portfolioClosed(false) } else {
                    financeTop()
                    await("TRANSFER_FINANCE_READ_NOT_READY") { exists { label(it, setOf("Refresh data")) && visible(it) && hasClickableAncestor(it) } }
                }
                clickLabel(setOf("Delete $name"), "TRANSFER_DELETE_CONTROL_MISSING", true)
                await("TRANSFER_DELETE_DIALOG_MISSING") { hasText("Delete $name?") }
                transferBeforeSubmit(run, phase, report)
                clickLabel(setOf("Delete"), "TRANSFER_DELETE_CONFIRM_MISSING")
                await("TRANSFER_DELETE_DIALOG_REMAINS") { !hasText("Delete $name?") && !hasText("Deleting…") }
                report.put("native_delete_confirmed", true)
            } else {
                clickLabel(setOf("Data"), "TRANSFER_DATA_PANE_MISSING")
                await("TRANSFER_SCREEN_MISSING") { hasText("Import and export") }
                transferTop()
                when (operation) {
                    "delete-isin" -> {
                        clickLabel(setOf("Manage ISIN mappings"), "TRANSFER_MAPPING_CONTROL_MISSING", true)
                        val symbol = "QA" + run.replace("-", "").take(12).uppercase(java.util.Locale.ROOT)
                        seekLabel(setOf("ZZ0000000001 · $symbol · $marker-asset"), "TRANSFER_ISIN_MAPPING_MISSING", true).recycle()
                        clickLabel(setOf("Delete"), "TRANSFER_ISIN_DELETE_MISSING", true)
                        await("TRANSFER_ISIN_DELETE_DIALOG_MISSING") { hasText("Delete this mapping?") }
                        transferBeforeSubmit(run, phase, report)
                        clickLabel(setOf("Delete"), "TRANSFER_ISIN_DELETE_CONFIRM_MISSING")
                        await("TRANSFER_ISIN_DELETE_NOT_CONFIRMED") { hasText("No saved ISIN mappings.") && !hasText("Delete this mapping?") }
                        clickLabel(setOf("Close"), "TRANSFER_MAPPING_CLOSE_MISSING")
                    }
                    "export" -> {
                        clickLabel(setOf("Prepare CSV export"), "TRANSFER_EXPORT_PREPARE_MISSING", true)
                        seekLabel(setOf("$expectedRows rows in 25 tables. Choose where to save the CSV."), "TRANSFER_EXPORT_COUNT_MISMATCH", true).recycle()
                        clickLabel(setOf("Save CSV ($expectedRows rows)"), "TRANSFER_EXPORT_SAVE_MISSING", true)
                        transferPickerDirectory(run)
                        val filename = transferPickerExportName()
                        report.put("export_name", filename)
                        transferBeforeSubmit(run, phase, report, filename)
                        transferPickerClick(setOf("Save", "Salva"), "TRANSFER_PICKER_SAVE_MISSING")
                        await("TRANSFER_EXPORT_NOT_SAVED") { hasText("CSV saved: $expectedRows rows from 25 tables.") }
                        report.put("export_saved", true).put("export_rows", expectedRows).put("export_tables", 25)
                    }
                    else -> {
                        clickLabel(setOf(when (operation) { "revolut", "revolut-duplicate" -> "Revolut"; "roundtrip" -> "Transaction CSV"; else -> "BBVA / Trade Republic CSV" }),
                            "TRANSFER_FORMAT_MISSING", true)
                        clickLabel(setOf("Choose CSV and prepare preview"), "TRANSFER_OPEN_PICKER_MISSING", true)
                        transferPickerDirectory(run)
                        transferPickerClick(setOf(fileName), "TRANSFER_INPUT_DOCUMENT_MISSING")
                        await("TRANSFER_PREVIEW_NOT_READY") { hasText("Preview ready. No data has been written.") }
                        report.put("real_picker_returned", true).put("file_name", fileName).put("file_sha256", fileHash)
                        transferTop()
                        val duplicateRead = operation.endsWith("-duplicate") || operation == "roundtrip"
                        val skipped = if (operation == "roundtrip") expectedRows - 2 else 1
                        seekLabel(setOf("${if (duplicateRead) 2 else 1} duplicates; $skipped rows excluded by the format; 0 invalid rows."),
                            "TRANSFER_PREVIEW_COUNTS_MISMATCH", true).recycle()
                        report.put("preview_rows", 2).put("duplicates", if (duplicateRead) 2 else 1).put("skipped", skipped).put("problems", 0)
                        if (!duplicateRead) {
                            if (operation == "revolut") {
                                clickLabel(setOf("New category"), "TRANSFER_NEW_CATEGORY_MISSING", true)
                                await("TRANSFER_CATEGORY_DIALOG_MISSING") { hasText("Category name") }
                                financeSetField("Category name", "$marker-expense")
                                hideKeyboardIfShown()
                                clickLabel(setOf("Stage category"), "TRANSFER_STAGE_CATEGORY_MISSING")
                                await("TRANSFER_CATEGORY_DIALOG_REMAINS") { !hasText("Category name") }
                                financeSetField("1 USD in EUR", "0.91")
                            } else if (operation == "bbva") {
                                clickLabel(setOf("Choose category"), "TRANSFER_CATEGORY_SELECTION_MISSING", true)
                                clickLabel(setOf("$marker-expense"), "TRANSFER_CREATED_CATEGORY_MISSING", true)
                                financeSetField("Description / keyword", "$marker-keyword")
                                financeSetField("Import note", "receipt \"A\"; local")
                                transferRememberCategory()
                            } else {
                                financeSetField("Symbol", "QA" + run.replace("-", "").take(12).uppercase(java.util.Locale.ROOT))
                                seekLabel(setOf("$marker-asset"), "TRANSFER_ASSET_NAME_MISMATCH", true).recycle()
                            }
                            hideKeyboardIfShown()
                            transferTop()
                            clickLabel(setOf(if (operation == "tr") "Import 1 selected rows" else "Import 1 selected transactions"),
                                "TRANSFER_IMPORT_CONTROL_MISSING", true)
                            await("TRANSFER_CONFIRMATION_MISSING") { hasText("Confirm import") }
                            // A durable native checkpoint complements the host reservation; never retry this operation.
                            transferBeforeSubmit(run, phase, report)
                            clickLabel(setOf("Confirm and import"), "TRANSFER_IMPORT_CONFIRM_MISSING")
                            transferTop()
                            seekLabel(setOf("1 imported, 1 duplicates, 0 failed, 0 require verification."), "TRANSFER_IMPORT_NOT_CONFIRMED", true).recycle()
                            report.put("import_confirmed", true)
                        } else {
                            requireCode(!exists { visible(it) && it.isEnabled && hasClickableAncestor(it) &&
                                it.text?.toString()?.matches(Regex("Import [1-9][0-9]* selected (?:rows|transactions)")) == true }, "TRANSFER_DUPLICATE_IMPORT_ENABLED")
                            report.put("zero_write_duplicate_preview", true)
                        }
                    }
                }
            }
            report.put("stage", "passed")
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure))
            throw AssertionError(safeFailure(failure))
        } finally { recordUiFlags(report); writeReport(run, phase, report) }
    }

    private fun transferBeforeSubmit(run: String, phase: String, report: JSONObject, exportName: String? = null) {
        val checkpoint = JSONObject().put("run_id", run).put("user_id", QA_USER).put("phase", phase).put("stage", "before-submit")
        exportName?.let { checkpoint.put("export_name", it) }
        writeReport(run, "$phase-before-submit", checkpoint)
        report.put("mutation_attempted", true)
    }

    private fun transferTop() {
        hideKeyboardIfShown()
        repeat(32) {
            if (hasText("Import and export")) return
            val container = find { it.isScrollable && visible(it) && it.className?.toString() != "android.widget.EditText" }
            val moved = container?.let { try { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } finally { it.recycle() } } ?: false
            requireCode(moved, "TRANSFER_TOP_UNREACHABLE")
            SystemClock.sleep(200)
        }
        throw IllegalStateException("TRANSFER_TOP_MISSING")
    }

    private fun transferRememberCategory() {
        val label = seekLabel(setOf("Remember this category for future imports"), "TRANSFER_REMEMBER_LABEL_MISSING", true)
        var ancestor: AccessibilityNodeInfo? = label
        try {
            repeat(4) {
                val parent = ancestor ?: throw IllegalStateException("TRANSFER_REMEMBER_CONTROL_MISSING")
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(AccessibilityNodeInfo.obtain(parent))
                var checkbox: AccessibilityNodeInfo? = null
                var visited = 0
                try {
                    while (queue.isNotEmpty() && visited++ < 64) {
                        val node = queue.removeFirst()
                        if (node.packageName?.toString() == TARGET && node.isCheckable && node.isEnabled && visible(node)) {
                            requireCode(checkbox == null, "TRANSFER_REMEMBER_AMBIGUOUS")
                            checkbox = AccessibilityNodeInfo.obtain(node)
                        }
                        for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
                        node.recycle()
                    }
                    checkbox?.let {
                        try { if (!it.isChecked) requireCode(it.performAction(AccessibilityNodeInfo.ACTION_CLICK), "TRANSFER_REMEMBER_REJECTED") }
                        finally { it.recycle() }
                        return
                    }
                } finally { while (queue.isNotEmpty()) queue.removeFirst().recycle() }
                ancestor = parent.parent; parent.recycle()
            }
        } finally { ancestor?.recycle() }
        throw IllegalStateException("TRANSFER_REMEMBER_CONTROL_MISSING")
    }

    private fun transferPickerFind(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        if (root.packageName?.toString() !in setOf("com.google.android.documentsui", "com.android.documentsui")) { root.recycle(); return null }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        try {
            while (queue.isNotEmpty() && count++ < 2048) {
                val node = queue.removeFirst()
                if (node.refresh() && node.packageName?.toString() in setOf("com.google.android.documentsui", "com.android.documentsui") &&
                    visible(node) && predicate(node)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
                node.recycle()
            }
            return null
        } finally { while (queue.isNotEmpty()) queue.removeFirst().recycle() }
    }

    private fun transferPickerClick(labels: Set<String>, code: String) {
        var candidate: AccessibilityNodeInfo? = null
        await(code) { candidate = transferPickerFind { label(it, labels) && it.isEnabled && hasClickableAncestor(it) }; candidate != null }
        var node = requireNotNull(candidate)
        try {
            repeat(8) {
                requireCode(node.refresh() && node.packageName?.toString() in setOf("com.google.android.documentsui", "com.android.documentsui"), code)
                if (node.isClickable && node.isEnabled) { requireCode(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), code); return }
                val parent = node.parent ?: throw IllegalStateException(code)
                node.recycle(); node = parent
            }
            throw IllegalStateException(code)
        } finally { node.recycle() }
    }

    private fun transferPickerDirectory(run: String) {
        transferPickerClick(setOf("Show roots", "Mostra radici"), "TRANSFER_PICKER_ROOTS_MISSING")
        transferPickerClick(setOf("Downloads", "Download"), "TRANSFER_PICKER_DOWNLOADS_MISSING")
        transferPickerClick(setOf("savely-transfer-$run"), "TRANSFER_PICKER_RUN_DIRECTORY_MISSING")
    }

    private fun transferPickerExportName(): String {
        var value: String? = null
        await("TRANSFER_EXPORT_NAME_MISSING") {
            val field = transferPickerFind { it.isEditable && it.isEnabled && it.text?.toString()?.matches(
                Regex("savely_export_\\d{4}-\\d{2}-\\d{2}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.csv")) == true }
            try { value = field?.text?.toString(); value != null } finally { field?.recycle() }
        }
        return requireNotNull(value)
    }

    /** V12/S02: one pre-reserved EUR QA row; the host verifies UUID, SID and every column. */
    @Test fun qualifyReservedTcgPrice() {
        val run = preflight(18)
        requireCode(dedicatedQaAccount().first == QA_USER, "TCG_PRICE_ACCOUNT_INVALID")
        val op = InstrumentationRegistry.getArguments().getString("tcgPriceOperation").orEmpty()
        requireCode(op in setOf("update", "observe", "delete"), "TCG_PRICE_OPERATION_INVALID")
        val phase = "tcg-price-$op"
        requireNewNavigationReport(run, phase)
        requireNewNavigationReport(run, "$phase-reservation")
        val name = "release-qualification-$run-$phase-input.json"
        val input = readJson(name, 4096)
        requireCode(input.keys().asSequence().toSet() == setOf("run_id", "user_id", "operation", "login_run_id", "session_id", "row_id", "day", "price") &&
            input.optString("run_id") == run && input.optString("user_id") == QA_USER && input.optString("operation") == op,
            "TCG_PRICE_INPUT_INVALID")
        val loginRun = input.optString("login_run_id")
        requireCode(listOf(loginRun, input.optString("session_id"), input.optString("row_id")).all {
            runCatching { UUID.fromString(it).toString() == it && it != run }.getOrDefault(false)
        }, "TCG_PRICE_REFERENCE_INVALID")
        fireRequireLogin(loginRun, QA_USER)
        requireCode(input.optString("day") == java.time.LocalDate.now().toString() &&
            input.optString("day") == java.time.LocalDate.now(java.time.ZoneId.of("Europe/Rome")).toString(), "TCG_PRICE_DAY_CHANGED")
        val rawPrice = input.optString("price")
        requireCode(rawPrice.matches(Regex("[0-9]{1,8}(?:\\.[0-9]{1,2})?")), "TCG_PRICE_EXPECTED_INVALID")
        val price = java.math.BigDecimal(rawPrice)
        requireCode(op != "update" || price.compareTo(java.math.BigDecimal("0.02")) == 0, "TCG_PRICE_SEED_INVALID")
        writeReport(run, "$phase-reservation", JSONObject(input.toString()).put("stage", "reserved").put("automatic_retry", false))
        requireCode(privateFile(name).delete(), "TCG_PRICE_INPUT_CONSUMPTION_FAILED")
        val report = baseReport(run, 18, phase).put("operation", op).put("login_run_id", loginRun)
            .put("session_id", input.getString("session_id")).put("row_id", input.getString("row_id"))
            .put("price", rawPrice).put("mutation_attempted", false).put("save_tapped", false)
            .put("blueprint_id", "37789").put("currency", "EUR").put("condition", "near_mint")
        try {
            report.put("pid", launch())
            financeOpenProfile(); requireEmail(QA_EMAIL); awaitProfileRead()
            report.put("settings_identity", true).put("profile_read", true)
            financeEnter("TCG")
            fumoTop() // Existing Add/Update header helper also serves these generic record lists.
            val tab = seekLabel(setOf("Magic"), "TCG_PRICE_TAB_MISSING", true, true)
            if (selectedControl(tab)) tab.recycle() else clickControl(tab, "TCG_PRICE_TAB_REJECTED")
            await("TCG_PRICE_TAB_NOT_SELECTED") { selected("Magic") }
            fumoTop()
            await("TCG_PRICE_DATA_NOT_READY") { exists { label(it, setOf("Add")) && visible(it) && it.isEnabled && hasClickableAncestor(it) } }
            if (op == "observe") clickLabel(setOf("Update"), "TCG_PRICE_READ_REFRESH_MISSING")
            seekLabel(setOf("Card name: android-tcg-price-$run"), "TCG_PRICE_MARKER_MISSING", true).recycle()
            val variants = mutableSetOf(price.stripTrailingZeros().toPlainString(), price.setScale(2).toPlainString())
            runCatching { price.setScale(1).toPlainString() }.getOrNull()?.let(variants::add)
            seekLabel(variants.map { "Current price per unit (€): $it" }.toSet(), "TCG_PRICE_AMOUNT_MISSING", true).recycle()
            report.put("price_visible", true)
            when (op) {
                "update" -> {
                    val control = seekLabel(setOf("Refresh CardTrader price"), "TCG_PRICE_UPDATE_MISSING", true, true)
                    writeReport(run, "$phase-before-submit", JSONObject(input.toString()).put("stage", "before-submit").put("automatic_retry", false))
                    report.put("mutation_attempted", true)
                    clickControl(control, "TCG_PRICE_UPDATE_REJECTED")
                    // A successful click is not a successful quote. A separate host read and fresh observe phase qualify persistence.
                    report.put("update_tapped_once", true)
                }
                "observe" -> report.put("persisted_price_visible", true)
                "delete" -> {
                    clickLabel(setOf("Delete"), "TCG_PRICE_DELETE_MISSING", true)
                    await("TCG_PRICE_DELETE_DIALOG_MISSING") { hasText("Delete this record?") }
                    writeReport(run, "$phase-before-submit", JSONObject(input.toString()).put("stage", "before-submit").put("automatic_retry", false))
                    report.put("mutation_attempted", true)
                    clickLabel(setOf("Delete"), "TCG_PRICE_DELETE_CONFIRM_MISSING")
                    await("TCG_PRICE_DELETE_NOT_CONFIRMED") { !hasText("Delete this record?") }
                    seekLabel(setOf("No items. Add the first record."), "TCG_PRICE_EMPTY_MISSING", true).recycle()
                    report.put("row_removed", true)
                }
            }
            report.put("stage", "passed")
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure))
            throw AssertionError(safeFailure(failure))
        } finally { recordUiFlags(report); writeReport(run, phase, report) }
    }

    /** F04: exactly one six-row Confirm; restored process only prepares a new duplicate preview. */
    @Test fun qualifyReservedTransferPartial() {
        val run = preflight(18)
        requireCode(dedicatedQaAccount().first == QA_USER, "F04_TRANSFER_ACCOUNT_INVALID")
        val op = InstrumentationRegistry.getArguments().getString("transferPartialOperation").orEmpty()
        requireCode(op in setOf("prepare", "submit", "restored", "preview"), "F04_TRANSFER_OPERATION_INVALID")
        val phase = "transfer-partial-$op"
        requireNewNavigationReport(run, phase); requireNewNavigationReport(run, "$phase-reservation")
        val name = "release-qualification-$run-$phase-input.json"
        val input = readJson(name, 4096)
        requireCode(input.keys().asSequence().toSet() == setOf("run_id", "user_id", "operation", "login_run_id", "session_id", "day",
            "category_id", "file_name", "file_sha256", "previous_pid", "task_id", "committed") && input.optString("run_id") == run &&
            input.optString("user_id") == QA_USER && input.optString("operation") == op, "F04_TRANSFER_INPUT_INVALID")
        val login = input.optString("login_run_id")
        val sid = input.optString("session_id")
        requireCode(listOf(login, sid, input.optString("category_id")).all { value ->
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        } && login != run, "F04_TRANSFER_REFERENCE_INVALID")
        fireRequireLogin(login, QA_USER)
        val day = input.optString("day")
        requireCode(day == java.time.LocalDate.now().toString() && day == java.time.LocalDate.now(java.time.ZoneId.of("Europe/Rome")).toString(),
            "F04_TRANSFER_DAY_CHANGED")
        val filename = "normalized-six-$run.csv"
        requireCode(input.optString("file_name") == filename && input.optString("file_sha256").matches(Regex("[a-f0-9]{64}")) &&
            readShell("sha256sum /sdcard/Download/savely-transfer-$run/$filename", 512).substringBefore(' ') == input.optString("file_sha256"),
            "F04_TRANSFER_CSV_CHANGED")
        writeReport(run, "$phase-reservation", JSONObject(input.toString()).put("stage", "reserved").put("automatic_retry", false))
        requireCode(privateFile(name).delete(), "F04_TRANSFER_INPUT_CONSUMPTION_FAILED")
        val report = baseReport(run, 18, phase).put("operation", op).put("login_run_id", login).put("session_id", sid)
            .put("mutation_attempted", false).put("confirm_remaining_tapped", false).put("cleanup_pending", true)
            .put("file_sha256", input.getString("file_sha256")).put("committed", input.getInt("committed"))
            .put("session_id_source", "host-readonly-binding-not-target-store")
        try {
            if (op == "prepare") {
                requireCode(input.optInt("previous_pid", -1) == 0 && input.optInt("task_id", -1) == 0 && input.optInt("committed", -1) == 0,
                    "F04_TRANSFER_PREPARE_INPUT_INVALID")
                launch(); financeOpenProfile(); requireEmail(QA_EMAIL); awaitProfileRead()
                report.put("settings_identity", true).put("profile_read", true)
                f04TransferPreview(run, day, filename, 0)
                report.put("six_ready", true).put("real_picker_returned", true)
            } else {
                val prepared = f04TransferPrior(run, "prepare", login, sid)
                requireCode(prepared.getInt("pid") == input.optInt("previous_pid") && prepared.getInt("task_id") == input.optInt("task_id"),
                    "F04_TRANSFER_ORIGIN_MISMATCH")
                if (op == "submit") {
                    requireCode(input.optInt("committed", -1) == 0 && mangaPidAndTask() ==
                        (prepared.getInt("pid") to prepared.getInt("task_id")), "F04_TRANSFER_PREPARED_PROCESS_CHANGED")
                    transferTop()
                    clickLabel(setOf("Import 6 selected transactions"), "F04_TRANSFER_IMPORT_MISSING", true)
                    await("F04_TRANSFER_CONFIRM_MISSING") { hasText("Confirm import") }
                    val confirm = seekLabel(setOf("Confirm and import"), "F04_TRANSFER_CONFIRM_MISSING", false, true)
                    writeReport(run, "$phase-before-submit", JSONObject(input.toString()).put("stage", "before-submit").put("automatic_retry", false))
                    report.put("mutation_attempted", true)
                    clickControl(confirm, "F04_TRANSFER_CONFIRM_REJECTED")
                    report.put("sole_confirm_tapped", true)
                    // No wait for batch completion: host observes commits and stops the background target.
                } else if (op == "restored") {
                    requireCode(input.optInt("committed") in 1..5, "F04_TRANSFER_PARTIAL_REQUIRED")
                    mangaOpenExistingTaskFromRecents()
                    val current = mangaPidAndTask()
                    requireCode(current.first != prepared.getInt("pid") && current.second == prepared.getInt("task_id"),
                        "F04_TRANSFER_PROCESS_RESTORATION_INVALID")
                    report.put("new_process", true).put("same_task", true).put("no_action_after_restore", true)
                } else {
                    val restored = f04TransferPrior(run, "restored", login, sid)
                    requireCode(input.optInt("committed") in 1..5 && mangaPidAndTask() ==
                        (restored.getInt("pid") to restored.getInt("task_id")), "F04_TRANSFER_RESTORED_PROCESS_CHANGED")
                    financeOpenProfile(); requireEmail(QA_EMAIL); awaitProfileRead()
                    report.put("settings_identity", true).put("profile_read", true)
                    f04TransferPreview(run, day, filename, input.getInt("committed"))
                    report.put("real_picker_returned", true).put("fresh_duplicate_preview", true)
                }
            }
            val current = if (op == "submit") input.getInt("previous_pid") to input.getInt("task_id") else mangaPidAndTask()
            report.put("pid", current.first).put("task_id", current.second).put("stage", "passed")
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure))
            throw IllegalStateException(report.getString("failure_code"))
        } finally { writeReport(run, phase, report) }
    }

    private fun f04TransferPrior(run: String, op: String, login: String, sid: String): JSONObject {
        val phase = "transfer-partial-$op"
        val report = readJson("release-qualification-$run-$phase.json", 16384)
        requireCode(report.optString("run_id") == run && report.optString("user_id") == QA_USER && report.optString("phase") == phase &&
            report.optString("login_run_id") == login && report.optString("session_id") == sid && report.optString("stage") == "passed" &&
            report.optInt("target_code") == 18 && report.optString("target_package") == TARGET, "F04_TRANSFER_PRIOR_INVALID")
        return report
    }

    private fun f04TransferPreview(run: String, day: String, filename: String, committed: Int) {
        if (!hasText("Import and export")) clickLabel(setOf("Data"), "F04_TRANSFER_DATA_MISSING")
        await("F04_TRANSFER_SCREEN_MISSING") { hasText("Import and export") }
        transferTop()
        clickLabel(setOf("Transaction CSV"), "F04_TRANSFER_FORMAT_MISSING", true)
        clickLabel(setOf("Choose CSV and prepare preview"), "F04_TRANSFER_PICKER_MISSING", true)
        transferPickerDirectory(run)
        transferPickerClick(setOf(filename), "F04_TRANSFER_CSV_MISSING")
        await("F04_TRANSFER_PREVIEW_MISSING") { hasText("Preview ready. No data has been written.") }
        seekLabel(setOf("6 transactions in preview"), "F04_TRANSFER_PREVIEW_COUNT_MISMATCH", true).recycle()
        seekLabel(setOf("$committed duplicates; 0 rows excluded by the format; 0 invalid rows."), "F04_TRANSFER_DUPLICATES_MISMATCH", true).recycle()
        for (index in 1..6) {
            seekLabel(setOf("android-f04-import-$run-$index"), "F04_TRANSFER_DESCRIPTION_MISSING", true).recycle()
            seekLabel(setOf("$day · 0.0$index EUR · Expense"), "F04_TRANSFER_AMOUNT_MISMATCH", true).recycle()
            seekLabel(setOf("Row ${index + 1}: ${if (index <= committed) "Duplicate, excluded" else "Ready to import"}"),
                "F04_TRANSFER_ROW_STATUS_MISMATCH", true).recycle()
        }
        transferTop()
        seekLabel(setOf("Import ${6 - committed} selected transactions"), "F04_TRANSFER_READY_COUNT_MISMATCH", true, true).recycle()
        // Intentionally leaves the fresh preview open; never opens/confirms its import dialog.
    }

    /** S01/S02: exactly one fixed catalog query, selection and cancel; never taps Save. */
    @Test fun qualifyReservedCatalogRead() {
        val run = preflight(18)
        requireCode(dedicatedQaAccount().first == QA_USER, "CATALOG_ACCOUNT_INVALID")
        val provider = InstrumentationRegistry.getArguments().getString("catalogProvider").orEmpty()
        requireCode(provider in setOf("books", "cards"), "CATALOG_PROVIDER_INVALID")
        val phase = "catalog-$provider"
        requireNewNavigationReport(run, phase)
        requireNewNavigationReport(run, "$phase-reservation")
        val inputName = "release-qualification-$run-$phase-input.json"
        val input = readJson(inputName, 4096)
        requireCode(input.keys().asSequence().toSet() == setOf("run_id", "user_id", "provider", "query", "login_run_id", "session_id") &&
            input.optString("run_id") == run && input.optString("user_id") == QA_USER && input.optString("provider") == provider &&
            input.optString("query") == (if (provider == "books") "Dune" else "Black Lotus"), "CATALOG_INPUT_INVALID")
        val loginRun = input.getString("login_run_id")
        val sessionId = input.getString("session_id")
        requireCode(listOf(loginRun, sessionId).all { value -> runCatching { UUID.fromString(value).toString() == value && value != run }.getOrDefault(false) }, "CATALOG_REFERENCE_INVALID")
        val login = readJson("release-qualification-$loginRun-couple-login.json", 16384)
        requireCode(login.optString("stage") == "passed" && login.optString("run_id") == loginRun && login.optString("user_id") == QA_USER &&
            login.optString("target_package") == TARGET && login.optInt("target_code") == 18 && login.optBoolean("english_selected") &&
            login.optBoolean("settings_identity") && login.optBoolean("profile_read"), "CATALOG_LOGIN_INVALID")
        writeReport(run, "$phase-reservation", JSONObject(input.toString()).put("stage", "reserved"))
        requireCode(privateFile(inputName).delete(), "CATALOG_INPUT_CONSUMPTION_FAILED")
        val report = baseReport(run, 18, phase).put("provider", provider).put("login_run_id", loginRun).put("session_id", sessionId)
            .put("save_tapped", false).put("query_reserved", false).put("raw_provider_id_observed", false).put("positive_qualified", false)
        try {
            report.put("pid", launch())
            financeOpenProfile(); requireEmail(QA_EMAIL); awaitProfileRead()
            report.put("settings_identity", true).put("profile_read", true)
            financeEnter(if (provider == "books") "Library" else "TCG")
            clickLabel(setOf(if (provider == "books") "Books" else "Magic"), "CATALOG_CATEGORY_MISSING", true)
            clickLabel(setOf(if (provider == "books") "Search Google Books" else "Search CardTrader"), "CATALOG_SEARCH_CONTROL_MISSING", true)
            val searchTitle = if (provider == "books") "Google Books search" else "CardTrader search"
            await("CATALOG_DIALOG_MISSING") { hasText(searchTitle) }
            financeSetField(if (provider == "books") "Title" else "Card name", if (provider == "books") "Dune" else "Black Lotus")
            hideKeyboardIfShown()
            writeReport(run, "$phase-before-query", JSONObject().put("run_id", run).put("user_id", QA_USER).put("session_id", sessionId)
                .put("provider", provider).put("stage", "before-query").put("automatic_retry", false))
            report.put("query_reserved", true)
            clickLabel(setOf("Search"), "CATALOG_QUERY_CONTROL_MISSING")
            var result: AccessibilityNodeInfo? = null
            var outcome = "pending"
            var httpCode: Int? = null
            await("CATALOG_QUERY_UNRESOLVED") {
                result = catalogResult(provider)
                if (result != null) { outcome = "result"; true }
                else if (hasText("No results")) { outcome = "empty"; true }
                else {
                    val error = find { node -> visible(node) && node.text?.toString()?.let { value ->
                        value.length <= 500 && (value.startsWith("Ricerca libri non disponibile") || value.startsWith("Book search unavailable") ||
                            value in setOf("Search unavailable", "Ricerca non disponibile", "Service temporarily unavailable. Try again.",
                                "Servizio temporaneamente non disponibile. Riprova.", "Non hai il permesso per questa operazione.", "You do not have permission for this operation."))
                    } == true }
                    if (error == null) false else {
                        try { httpCode = Regex("\\(HTTP ([1-5][0-9]{2})\\)").find(error.text.toString())?.groupValues?.get(1)?.toInt() }
                        finally { error.recycle() }
                        outcome = "provider-unavailable"; true
                    }
                }
            }
            report.put("provider_outcome", outcome).put("http_status_from_ui", httpCode ?: JSONObject.NULL)
            if (outcome != "result") {
                clickLabel(setOf("Close"), "CATALOG_CLOSE_MISSING")
                await("CATALOG_DIALOG_REMAINS") { !hasText(searchTitle) }
                report.put("search_closed", true).put("stage", "observed-unavailable")
            } else {
                val resultNode = requireNotNull(result)
                val label = resultNode.text?.toString().orEmpty()
                val separator = label.lastIndexOf(" · ")
                requireCode(separator > 0 && label.length <= 600, "CATALOG_RESULT_LABEL_INVALID")
                resultNode.recycle()
                val title = label.substring(0, separator)
                val secondary = label.substring(separator + 3)
                requireCode(title.length in 1..300 && secondary.length <= 300 && label.none { it.code < 32 }, "CATALOG_RESULT_LABEL_INVALID")
                val cover = find { it.packageName?.toString() == TARGET && it.contentDescription?.toString() == "Cover of $title" }
                report.put("cover_node_exposed", cover != null)
                if (cover != null) {
                    cover.recycle()
                    val visibleCover = seekLabel(setOf("Cover of $title"), "CATALOG_COVER_UNREACHABLE", true)
                    try { catalogCover(run, provider, visibleCover, report) } finally { visibleCover.recycle() }
                }
                // Metadata stays only in RAM; reports never contain title, author, set or image URL.
                clickControl(seekLabel(setOf(label), "CATALOG_RESULT_UNREACHABLE", true, clickable = true), "CATALOG_RESULT_SELECTION_REJECTED")
                await("CATALOG_SEEDED_FORM_MISSING") { !hasText(searchTitle) && hasText("Cancel") && hasText("Save") }
                requireCode(catalogField(if (provider == "books") "Title" else "Card name") == title, "CATALOG_TITLE_SEED_MISMATCH")
                requireCode(catalogField(if (provider == "books") "Author (optional)" else "Set (optional)") == secondary, "CATALOG_SECONDARY_SEED_MISMATCH")
                report.put("visible_metadata_matches", true).put("selected_actual_result", true)
                if (provider == "books") {
                    report.put("publisher_present", catalogField("Publisher (optional)").isNotBlank())
                    val year = catalogField("Publication year (optional)")
                    requireCode(year.isEmpty() || year.matches(Regex("[0-9]{1,4}")), "CATALOG_YEAR_SEED_INVALID")
                    report.put("year_present", year.isNotEmpty())
                }
                clickLabel(setOf("Cancel"), "CATALOG_CANCEL_MISSING")
                await("CATALOG_FORM_REMAINS") { !hasText("Save") && !hasText("Cancel") }
                report.put("form_cancelled", true).put("positive_qualified", true).put("stage", "passed")
            }
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure))
            throw AssertionError(safeFailure(failure))
        } finally { writeReport(run, phase, report) }
    }

    private fun catalogResult(provider: String): AccessibilityNodeInfo? = find { node ->
        val text = node.text?.toString().orEmpty()
        node.packageName?.toString() == TARGET && node.isEnabled && hasClickableAncestor(node) && !node.isEditable && text.length in 4..600 &&
            text.contains(" · ") && text.substringBefore(" · ").contains(if (provider == "books") "Dune" else "Black Lotus", ignoreCase = true)
    }

    private fun catalogField(label: String): String {
        var field: AccessibilityNodeInfo? = null
        // Only scroll to a known field; never substitute a click on an unrecognised control.
        val labelNode = seekLabel(setOf(label), "CATALOG_FIELD_MISSING", true)
        var current: AccessibilityNodeInfo? = labelNode
        try {
            for (depth in 0 until 5) {
                val node = current ?: break
                if (node.isEditable && node.isEnabled && visible(node)) {
                    field = AccessibilityNodeInfo.obtain(node)
                    break
                }
                current = node.parent; node.recycle()
            }
            val node = requireNotNull(field) { "CATALOG_FIELD_MISSING" }
            return node.text?.toString().orEmpty().let { if (it == label) "" else it }
        } finally { current?.recycle(); field?.recycle() }
    }

    private fun catalogCover(run: String, provider: String, node: AccessibilityNodeInfo, report: JSONObject) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val screenshot = automation.takeScreenshot() ?: return
        try {
            requireCode(!bounds.isEmpty && bounds.left >= 0 && bounds.top >= 0 && bounds.right <= screenshot.width && bounds.bottom <= screenshot.height,
                "CATALOG_COVER_BOUNDS_INVALID")
            val crop = android.graphics.Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
            try {
                val file = privateFile("release-qualification-$run-catalog-$provider-cover.png")
                requireCode(!file.exists(), "CATALOG_COVER_ALREADY_EXISTS")
                file.outputStream().use { requireCode(crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it), "CATALOG_COVER_WRITE_FAILED") }
                report.put("cover_crop_saved", true).put("cover_pixels_reviewed", false)
            } finally { crop.recycle() }
        } finally { screenshot.recycle() }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation: UiAutomation get() = instrumentation.uiAutomation

    @Test fun navigationFixedTargetDiagnostic() {
        val run = preflight(14)
        val (loginRun, failedRun) = navigationRecoveryReferences(run)
        val (user, _) = dedicatedQaAccount()
        requireNewNavigationReport(run, "navigation-diagnostic")
        val report = baseReport(run, 14, "navigation-diagnostic").put("user_id", user)
            .put("login_run_id", loginRun).put("failed_run_id", failedRun)
            .put("failed_phase", InstrumentationRegistry.getArguments().getString("releaseQualificationFailurePhase") ?: "navigation-routes")
        val activeRoot = automation.rootInActiveWindow
        try {
            report.put("active_root_is_target", activeRoot?.packageName?.toString() == TARGET)
                .put("active_root_bounds", activeRoot?.let { Rect().also(it::getBoundsInScreen).toShortString() } ?: JSONObject.NULL)
        } finally { activeRoot?.recycle() }
        val labels = JSONObject()
        for (text in listOf("Modules", "Sign out of your account", "More", "Settings", "Dashboard", "Your finances", "Profile")) {
            val matches = org.json.JSONArray()
            val seen = mutableSetOf<String>()
            for (match in 0 until 8) {
                val node = find {
                    label(it, setOf(text)) && "${it.windowId}:${it.className}:${it.isClickable}:${Rect().also(it::getBoundsInScreen).toShortString()}" !in seen
                } ?: break
                seen.add("${node.windowId}:${node.className}:${node.isClickable}:${Rect().also(node::getBoundsInScreen).toShortString()}")
                val values = org.json.JSONArray()
                var current: AccessibilityNodeInfo? = node
                try {
                    repeat(8) {
                        val parent = current ?: return@repeat
                        values.put(JSONObject().put("class", parent.className?.toString().orEmpty())
                            .put("bounds", Rect().also(parent::getBoundsInScreen).toShortString())
                            .put("scrollable", parent.isScrollable).put("clickable", parent.isClickable)
                            .put("visible", visible(parent)).put("enabled", parent.isEnabled)
                            .put("child_count", parent.childCount)
                            .put("pane_title_known", Build.VERSION.SDK_INT >= 28 && parent.paneTitle?.toString() == "Navigation menu"))
                        current = parent.parent
                        parent.recycle()
                    }
                } finally { current?.recycle() }
                matches.put(values)
            }
            labels.put(text, matches)
        }
        report.put("labels", labels).put("stage", "observed").put("back_injected", false)
        if (InstrumentationRegistry.getArguments().getString("navigationDiagnosticCloseDrawer") == "true") {
            requireTargetActiveWindow()
            navigationBack()
            report.put("back_injected", true)
        }
        writeReport(run, "navigation-diagnostic", report)
    }

    @Test fun recoverDedicatedQaNavigationLogout() {
        val run = preflight(14)
        val (loginRun, failedRun) = navigationRecoveryReferences(run)
        val (user, email) = dedicatedQaAccount()
        requireNewNavigationReport(run, "navigation-recovery")
        val report = baseReport(run, 14, "navigation-recovery").put("user_id", user)
            .put("login_run_id", loginRun).put("failed_run_id", failedRun).put("cleanup_pending", true)
            .put("failed_phase", InstrumentationRegistry.getArguments().getString("releaseQualificationFailurePhase") ?: "navigation-routes")
        // Reserve before any UI action; an interrupted recovery must not replay this run's logout.
        writeReport(run, "navigation-recovery-reservation", JSONObject().put("run_id", run).put("user_id", user)
            .put("login_run_id", loginRun).put("failed_run_id", failedRun).put("stage", "reserved")
            .put("failed_phase", InstrumentationRegistry.getArguments().getString("releaseQualificationFailurePhase") ?: "navigation-routes"))
        try {
            report.put("pid", launch())
            await("NAVIGATION_RECOVERY_UI_NOT_READY") { navigationDrawerOpen() || navigationBottomReady() }
            if (navigationDrawerOpen()) navigationBack()
            await("NAVIGATION_RECOVERY_DRAWER_NOT_CLOSED") { !navigationDrawerOpen() && navigationBottomReady() }
            if (!hasProfileContent()) openSettings(englishOnly = true)
            scrollAccountToTop()
            requireEmail(email)
            awaitProfileRead()
            report.put("settings_identity", true).put("profile_read", true)
            requireCode(logoutQa(report, email), "NAVIGATION_RECOVERY_LOGOUT_FAILED")
            report.put("native_logout", true).put("stage", "passed").put("cleanup_pending", false)
        } catch (failure: Throwable) {
            recordUiFlags(report)
            report.put("admin_identity_seen", runCatching { hasText(QA_ADMIN_EMAIL) }.getOrDefault(false))
                .put("dedicated_identity_seen", runCatching { hasText(email) }.getOrDefault(false))
            report.put("stage", "failed").put("failure_code", safeFailure(failure))
            throw AssertionError(safeFailure(failure))
        } finally { writeReport(run, "navigation-recovery", report) }
    }

    private fun navigationRecoveryReferences(run: String): Pair<String, String> {
        val user = dedicatedQaAccount().first
        val loginRun = navigationLogin(user)
        val failedRun = InstrumentationRegistry.getArguments().getString("releaseQualificationFailureRunId").orEmpty()
        val failedPhase = InstrumentationRegistry.getArguments().getString("releaseQualificationFailurePhase") ?: "navigation-routes"
        requireCode(failedPhase in setOf("navigation-routes", "navigation-role", "couple-logout"), "NAVIGATION_RECOVERY_PHASE_INVALID")
        requireCode(runCatching { UUID.fromString(failedRun).toString() == failedRun }.getOrDefault(false) &&
            run != loginRun && run != failedRun &&
            (if (failedPhase == "couple-logout") failedRun == loginRun else failedRun != loginRun),
            "NAVIGATION_RECOVERY_REFERENCES_INVALID")
        val failed = readJson("release-qualification-$failedRun-$failedPhase.json", 16_384)
        requireCode(failed.optString("run_id") == failedRun && failed.optString("phase") == failedPhase &&
            failed.optString("stage") == "failed" && failed.optString("user_id") == user &&
            failed.optInt("target_code") == 14 && failed.optString("target_package") == TARGET &&
            (failedPhase == "couple-logout" || failed.optString("login_run_id") == loginRun) &&
            failed.optBoolean("cleanup_pending") && failed.optString("failure_code").matches(Regex("[A-Z_]{3,80}")),
            "NAVIGATION_RECOVERY_FAILURE_INVALID")
        return loginRun to failedRun
    }

    private fun requireNewNavigationReport(run: String, phase: String) {
        val file = privateFile("release-qualification-$run-$phase.json")
        requireCode(!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists(),
            "NAVIGATION_RECOVERY_REPORT_ALREADY_EXISTS")
    }

    private fun requireTargetActiveWindow() {
        val root = automation.rootInActiveWindow ?: throw IllegalStateException("NAVIGATION_TARGET_WINDOW_MISSING")
        try { requireCode(root.packageName?.toString() == TARGET, "NAVIGATION_TARGET_WINDOW_INVALID") }
        finally { root.recycle() }
    }

    @Test fun qualifyDedicatedQaNavigationRoutes() {
        val run = preflight(14)
        val (user, email) = dedicatedQaAccount()
        val loginRun = navigationLogin(user)
        val fire = InstrumentationRegistry.getArguments().getString("navigationFireAllowed")
        requireCode(fire in setOf("true", "false") && (fire == "true" || user == QA_USER_B), "NAVIGATION_POLICY_INVALID")
        val routes = listOf("dashboard" to "Dashboard", "transactions" to "Transactions", "budget" to "Budget",
            "categories" to "Categories", "recurring" to "Recurring expenses", "charts" to "Charts",
            "portfolio" to "Portfolio", "net-worth" to "Net worth", "insights" to "Insights",
            "poker" to "Poker", "fire" to "FIRE", "fumo" to "Smoking", "tcg" to "TCG", "libreria" to "Library",
            "couple" to "Couple", "admin" to "Admin", "settings" to "Settings", "app-release" to "Version and updates",
            "modules" to "Modules").filter { (route, _) -> (route != "admin" || user == QA_ADMIN_USER) && (route != "fire" || fire == "true") }
        val report = baseReport(run, 14, "navigation-routes").put("user_id", user).put("login_run_id", loginRun)
        val covered = org.json.JSONArray()
        try {
            report.put("pid", launch())
            openSettings(englishOnly = true); requireEmail(email); awaitProfileRead()
            navigationDrawer()
            requireCode(navigationScanDrawer() == routes.map { it.second }.toSet() + "Sign out of your account",
                "NAVIGATION_DENIED_ROUTE_VISIBLE")
            navigationBack()
            await("NAVIGATION_DRAWER_BACK_FAILED") { !navigationDrawerOpen() && navigationTitle("Settings") }
            report.put("drawer_back", true)
            for ((route, title) in routes) {
                navigationEnter(title)
                requireCode(!editable(password = true), "NAVIGATION_LOGIN_FALLBACK")
                covered.put(route)
                if (route == "modules") {
                    navigationOpenSelectedMore()
                    await("NAVIGATION_SELECTED_MORE_DRAWER_MISSING") { navigationDrawerOpen() }
                    requireCode(navigationScanDrawer() == routes.map { it.second }.toSet() + "Sign out of your account",
                        "NAVIGATION_SELECTED_MORE_POLICY_INVALID")
                    navigationBack()
                    await("NAVIGATION_SELECTED_MORE_BACK_FAILED") { !navigationDrawerOpen() && navigationTitle("Modules") }
                    report.put("selected_more_accessible", true)
                }
                if (route != "dashboard") {
                    navigationBack()
                    await("NAVIGATION_DESTINATION_BACK_FAILED") { navigationTitle("Dashboard") }
                }
            }
            report.put("covered_routes", covered).put("destination_back", true)
            openSettings(englishOnly = true); requireEmail(email); awaitProfileRead()
            report.put("identity_preserved", true).put("stage", "passed").put("cleanup_pending", true)
        } catch (failure: Throwable) {
            recordUiFlags(report)
            runCatching {
                val root = automation.rootInActiveWindow
                try {
                    report.put("active_root_is_target", root?.packageName?.toString() == TARGET)
                        .put("active_root_bounds", root?.let { Rect().also(it::getBoundsInScreen).toShortString() } ?: JSONObject.NULL)
                } finally { root?.recycle() }
            }
            report.put("covered_routes", covered).put("stage", "failed").put("failure_code", safeFailure(failure)).put("cleanup_pending", true)
            throw AssertionError(safeFailure(failure))
        } finally { writeReport(run, "navigation-routes", report) }
    }

    @Test fun qualifyDedicatedQaNavigationRoleChanges() {
        val run = preflight(14)
        requireCode(dedicatedQaAccount().first == QA_USER_B, "NAVIGATION_ROLE_OWNER_INVALID")
        val loginRun = navigationLogin(QA_USER_B)
        val report = baseReport(run, 14, "navigation-role").put("user_id", QA_USER_B).put("login_run_id", loginRun)
        var checkpoint = "started"
        fun checked(stage: String) {
            checkpoint = stage
            val suffix = stage.replace('_', '-')
            writeReport(run, "navigation-$suffix", JSONObject().put("run_id", run).put("user_id", QA_USER_B).put("stage", stage).put("status", "ready"))
            val name = "release-qualification-$run-navigation-ack-$suffix.json"
            val until = SystemClock.uptimeMillis() + 90_000
            while (!privateFile(name).exists() && SystemClock.uptimeMillis() < until) SystemClock.sleep(250)
            val ack = readJson(name, 1_024)
            requireCode(ack.keys().asSequence().toSet() == setOf("run_id", "user_id", "stage", "status") &&
                ack.optString("run_id") == run && ack.optString("user_id") == QA_USER_B && ack.optString("stage") == stage &&
                ack.optString("status") == "observed", "NAVIGATION_ACK_INVALID")
            requireCode(privateFile(name).delete(), "NAVIGATION_ACK_CONSUMPTION_FAILED")
        }
        try {
            report.put("pid", launch())
            openSettings(englishOnly = true); requireEmail(QA_EMAIL_B); awaitProfileRead()
            checked("b_granted")
            navigationResume()
            navigationEnter("FIRE")
            checked("b_revoked")
            navigationResume()
            await("NAVIGATION_REVOKED_ROUTE_REMAINED") { navigationTitle("Dashboard") }
            navigationDrawer()
            requireCode(navigationScanDrawer() == navigationDrawerLabels - setOf("FIRE", "Admin"), "NAVIGATION_REVOKED_DRAWER_REMAINED")
            navigationBack()
            await("NAVIGATION_REVOKED_BACK_FAILED") { navigationTitle("Dashboard") }
            report.put("revoked_route_and_drawer_cleared", true)
            checked("b_revocation_denied")
            checked("b_restored")
            navigationResume()
            navigationEnter("FIRE")
            report.put("regranted_route_visible", true)
            checked("b_restoration_visible")
            launch()
            await("NAVIGATION_FINAL_TARGET_NOT_READY") { navigationBottomReady() }
            openSettings(englishOnly = true); requireEmail(QA_EMAIL_B); awaitProfileRead()
            report.put("identity_preserved", true).put("stage", "passed").put("checkpoint_count", 5).put("cleanup_pending", true)
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure)).put("last_checkpoint", checkpoint).put("cleanup_pending", true)
            throw AssertionError(safeFailure(failure))
        } finally { writeReport(run, "navigation-role", report) }
    }

    private fun navigationLogin(user: String): String {
        val run = InstrumentationRegistry.getArguments().getString("releaseQualificationLoginRunId").orEmpty()
        requireCode(runCatching { UUID.fromString(run).toString() == run }.getOrDefault(false), "NAVIGATION_LOGIN_RUN_INVALID")
        val prior = readJson("release-qualification-$run-couple-login.json", 16_384)
        requireCode(prior.optString("run_id") == run && prior.optString("phase") == "couple-login" && prior.optString("target_package") == TARGET &&
            prior.optString("stage") == "passed" && prior.optString("user_id") == user && prior.optInt("target_code") == 14 &&
            prior.optBoolean("settings_identity") && prior.optBoolean("english_selected"), "NAVIGATION_LOGIN_INVALID")
        return run
    }

    private fun navigationTitle(title: String): Boolean {
        val root = automation.rootInActiveWindow ?: return false
        val window = try { Rect().also(root::getBoundsInScreen) } finally { root.recycle() }
        return exists {
            val bounds = Rect().also(it::getBoundsInScreen)
            label(it, setOf(title)) && visible(it) && !hasClickableAncestor(it) && bounds.top < window.top + window.height() / 3
        }
    }

    private val navigationDrawerLabels = setOf("Modules", "Dashboard", "Transactions", "Budget", "Categories",
        "Recurring expenses", "Charts", "Portfolio", "Net worth", "Insights", "Poker", "FIRE", "Smoking",
        "TCG", "Library", "Couple", "Admin", "Settings", "Version and updates", "Sign out of your account")

    private fun navigationDrawerOpen(): Boolean = exists { visible(it) && navigationDrawerAncestor(it) }

    private fun navigationBottomReady(): Boolean = exists {
        navigationLabel(it, setOf("Dashboard")) && it.isEnabled
    }

    private fun navigationOpenSelectedMore() {
        var found: AccessibilityNodeInfo? = null
        await("NAVIGATION_SELECTED_MORE_MISSING") {
            found = find { navigationLabel(it, setOf("More")) && it.isEnabled && selectedControl(it) }
            found != null
        }
        var node = requireNotNull(found)
        try {
            repeat(8) {
                if (node.isClickable) {
                    // Compose exposes a control with children as View; selection and click remain on it.
                    requireCode(node.packageName?.toString() == TARGET && visible(node) && node.isEnabled &&
                        node.className?.toString() in setOf("android.view.View", "android.widget.Button") &&
                        node.isCheckable && node.isChecked &&
                        node.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK },
                        "NAVIGATION_SELECTED_MORE_BUTTON_INVALID")
                    requireCode(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "NAVIGATION_SELECTED_MORE_CLICK_REJECTED")
                    return
                }
                val parent = node.parent ?: throw IllegalStateException("NAVIGATION_SELECTED_MORE_BUTTON_MISSING")
                node.recycle()
                node = parent
            }
            throw IllegalStateException("NAVIGATION_SELECTED_MORE_BUTTON_MISSING")
        } finally { node.recycle() }
    }

    private fun navigationDrawerAncestor(node: AccessibilityNodeInfo): Boolean {
        fun knownPane(candidate: AccessibilityNodeInfo): Boolean {
            if (Build.VERSION.SDK_INT >= 28) return candidate.paneTitle?.toString() == "Navigation menu"
            if (!candidate.isScrollable) return false
            val matches = candidate.findAccessibilityNodeInfosByText("Sign out of your account")
            return try { matches.any { label(it, setOf("Sign out of your account")) } }
            finally { matches.forEach { it.recycle() } }
        }
        if (knownPane(node)) return true
        var current = node.parent
        try {
            repeat(10) {
                val parent = current ?: return false
                if (!parent.refresh()) return false
                if (knownPane(parent)) return true
                current = parent.parent
                parent.recycle()
            }
            return false
        } finally { current?.recycle() }
    }

    private fun navigationDrawer() {
        hideKeyboardIfShown()
        clickNavigation(setOf("More"), "NAVIGATION_MORE_MISSING")
        await("NAVIGATION_DRAWER_MISSING") { navigationDrawerOpen() }
        navigationDrawerToTop()
    }

    private fun navigationDrawerToTop() {
        repeat(64) {
            if (exists { label(it, setOf("Modules")) && visible(it) && navigationDrawerAncestor(it) }) return
            navigationDrawerScroll(backwards = true)
        }
        throw IllegalStateException("NAVIGATION_DRAWER_TOP_MISSING")
    }

    private fun navigationDrawerScroll(backwards: Boolean) {
        val container = find { it.isScrollable && visible(it) && navigationDrawerAncestor(it) }
            ?: throw IllegalStateException("NAVIGATION_DRAWER_SCROLL_MISSING")
        val bounds = Rect().also(container::getBoundsInScreen)
        val moved = try { container.performAction(if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } finally { container.recycle() }
        if (!moved) swipe(backwards, bounds)
        SystemClock.sleep(250)
    }

    private fun navigationScanDrawer(): Set<String> {
        val seen = mutableSetOf<String>()
        repeat(64) {
            // Only public, fixed route labels are collected; absence is judged after reaching the footer.
            exists { node ->
                if (visible(node) && navigationDrawerAncestor(node)) {
                    navigationDrawerLabels.firstOrNull { label(node, setOf(it)) }?.let(seen::add)
                }
                false
            }
            if ("Sign out of your account" in seen) {
                navigationDrawerToTop()
                return seen
            }
            navigationDrawerScroll(backwards = false)
        }
        throw IllegalStateException("NAVIGATION_DRAWER_FOOTER_MISSING")
    }

    private fun navigationSeekFooter() {
        repeat(64) {
            if (exists { label(it, setOf("Sign out of your account")) && visible(it) && navigationDrawerAncestor(it) }) return
            navigationDrawerScroll(backwards = false)
        }
        throw IllegalStateException("NAVIGATION_DRAWER_FOOTER_MISSING")
    }

    private fun navigationEnter(title: String) {
        navigationDrawer()
        navigationChooseDrawerRoute(title)
    }

    private fun navigationChooseDrawerRoute(title: String) {
        repeat(64) {
            val candidate = find { label(it, setOf(title)) && visible(it) && hasClickableAncestor(it) && navigationDrawerAncestor(it) }
            if (candidate != null) {
                clickControl(candidate, "NAVIGATION_DRAWER_CLICK_FAILED")
                await("NAVIGATION_TITLE_MISSING") { !navigationDrawerOpen() && navigationTitle(title) }
                return
            }
            val heading = find { label(it, setOf(title)) && navigationDrawerAncestor(it) }
            if (heading != null) {
                val moved = try { heading.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) } finally { heading.recycle() }
                if (!moved) navigationDrawerScroll(backwards = false)
            } else navigationDrawerScroll(backwards = false)
            SystemClock.sleep(250)
        }
        throw IllegalStateException("NAVIGATION_DRAWER_ROUTE_NOT_VISIBLE")
    }

    private fun navigationBack() {
        requireTargetActiveWindow()
        readShell("input keyevent KEYCODE_BACK", 256)
    }

    private fun navigationResume() {
        readShell("input keyevent KEYCODE_HOME", 256)
        SystemClock.sleep(250)
        launch()
        await("NAVIGATION_RESUME_MISSING") { hasText("Dashboard") && !editable(password = true) }
        navigationDrawer()
        navigationSeekFooter()
        await("NAVIGATION_RESTORE_NOT_SETTLED") {
            exists { label(it, setOf("Sign out of your account")) && visible(it) && it.isEnabled && hasClickableAncestor(it) && navigationDrawerAncestor(it) }
        }
        navigationBack()
        await("NAVIGATION_RESUME_DRAWER_NOT_CLOSED") { !navigationDrawerOpen() && navigationBottomReady() }
    }

    @Test fun adminFixedTargetDiagnostic() {
        val run = preflight(13)
        val failedRun = InstrumentationRegistry.getArguments().getString("releaseQualificationLoginRunId").orEmpty()
        requireCode(runCatching { UUID.fromString(failedRun).toString() == failedRun }.getOrDefault(false), "ADMIN_DIAGNOSTIC_RUN_INVALID")
        val failed = readJson("release-qualification-$failedRun-admin-forms.json", 16_384)
        requireCode(failed.optString("stage") == "failed" && failed.optString("user_id") == QA_ADMIN_USER &&
            failed.optInt("target_code") == 13 && hasText(QA_EMAIL_B) && hasText("Save permissions"), "ADMIN_DIAGNOSTIC_OWNER_INVALID")
        val result = baseReport(run, 13, "admin-diagnostic").put("user_id", QA_ADMIN_USER)
        val labels = JSONObject()
        for (text in listOf("Admin", "Poker", "Fire", "Fumo", "Tcg", "Libreria", "Couple", "Save permissions", "Close")) {
            val heading = find { label(it, setOf(text)) }
            if (heading == null) { labels.put(text, JSONObject().put("found", false)); continue }
            val parents = org.json.JSONArray()
            var parent: AccessibilityNodeInfo? = heading
            try {
                repeat(5) {
                    val current = parent ?: return@repeat
                    val values = org.json.JSONArray()
                    val queue = ArrayDeque<AccessibilityNodeInfo>()
                    queue.add(AccessibilityNodeInfo.obtain(current))
                    var visited = 0
                    try {
                        while (queue.isNotEmpty() && visited++ < 128) {
                            val node = queue.removeFirst()
                            if (node.isCheckable) values.put(JSONObject().put("checked", node.isChecked)
                                .put("visible", visible(node)).put("enabled", node.isEnabled)
                                .put("bounds", Rect().also(node::getBoundsInScreen).toShortString()))
                            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
                            node.recycle()
                        }
                    } finally { while (queue.isNotEmpty()) queue.removeFirst().recycle() }
                    parents.put(values)
                    parent = current.parent
                    current.recycle()
                }
            } finally { parent?.recycle() }
            labels.put(text, JSONObject().put("found", true).put("ancestors", parents))
        }
        result.put("labels", labels).put("stage", "observed")
        if (InstrumentationRegistry.getArguments().getString("adminDiagnosticClose") == "true") {
            clickLabel(setOf("Close"), "ADMIN_DIAGNOSTIC_CLOSE_MISSING")
            requireCode(!hasText("Save permissions"), "ADMIN_DIAGNOSTIC_NOT_CLOSED")
            result.put("dialog_closed", true)
        }
        writeReport(run, "admin-diagnostic", result)
    }

    @Test fun qualifyDedicatedQaAdminPermissions() {
        val run = preflight(13)
        requireCode(InstrumentationRegistry.getArguments().getString("releaseQualificationAccount") == "admin", "ADMIN_QA_OWNER_INVALID")
        val loginRun = InstrumentationRegistry.getArguments().getString("releaseQualificationLoginRunId").orEmpty()
        requireCode(runCatching { UUID.fromString(loginRun).toString() == loginRun }.getOrDefault(false), "LOGIN_RUN_INVALID")
        val previous = readJson("release-qualification-$loginRun-couple-login.json", 16_384)
        requireCode(previous.optString("stage") == "passed" && previous.optString("user_id") == QA_ADMIN_USER &&
            previous.optBoolean("settings_identity") && previous.optBoolean("english_selected"), "QA_ADMIN_LOGIN_INVALID")
        requireCode(!privateFile("release-qualification-$run-admin-forms.json").exists() &&
            ADMIN_STAGES.none { privateFile("release-qualification-$run-admin-${it.replace('_', '-')}.json").exists() },
            "ADMIN_REPORT_ALREADY_EXISTS")
        val report = baseReport(run, 13, "admin-forms").put("user_id", QA_ADMIN_USER)
        var checkpoint = "started"
        fun checked(stage: String) {
            checkpoint = stage
            val suffix = stage.replace('_', '-')
            writeReport(run, "admin-$suffix", JSONObject().put("run_id", run).put("user_id", QA_ADMIN_USER)
                .put("stage", stage).put("status", "ready"))
            val name = "release-qualification-$run-admin-ack-$suffix.json"
            val until = SystemClock.uptimeMillis() + 90_000
            while (!privateFile(name).exists() && SystemClock.uptimeMillis() < until) SystemClock.sleep(250)
            val ack = readJson(name, 1_024)
            requireCode(ack.keys().asSequence().toSet() == setOf("run_id", "user_id", "stage", "status") &&
                ack.optString("run_id") == run && ack.optString("user_id") == QA_ADMIN_USER &&
                ack.optString("stage") == stage && ack.optString("status") == "observed", "ADMIN_ACK_INVALID")
            requireCode(privateFile(name).delete(), "ADMIN_ACK_CONSUMPTION_FAILED")
        }
        fun openTarget() {
            val email = QA_EMAIL_B
            scrollAdminToTop()
            val heading = seekLabel(setOf(email), "ADMIN_QA_TARGET_MISSING", scroll = true)
            adminSiblingControl(heading, "Manage", "ADMIN_QA_MANAGE_MISSING")
            await("ADMIN_DIALOG_MISSING") { hasText("Save permissions") && hasText(email) }
        }
        fun saveTarget() {
            clickLabel(setOf("Save permissions"), "ADMIN_SAVE_MISSING")
            adminAwaitMessage("Permissions updated.")
            requireCode(!hasText("Save permissions"), "ADMIN_DIALOG_NOT_CLOSED")
        }
        try {
            report.put("pid", launch())
            openSettings(englishOnly = true)
            requireEmail(QA_ADMIN_EMAIL)
            awaitProfileRead()
            clickNavigation(setOf("More"), "MORE_NAVIGATION_MISSING")
            clickLabel(setOf("Admin"), "ADMIN_NAVIGATION_MISSING", scroll = true)
            await("ADMIN_CONTENT_MISSING") { hasText("Administration") }
            seekLabel(setOf("Refresh users"), "ADMIN_LOAD_NOT_SETTLED", scroll = true, clickable = true).recycle()
            checked("admin_ready")
            openTarget()
            adminPermission("Fire", true, false)
            clickLabel(setOf("Close"), "ADMIN_DIALOG_CLOSE_MISSING")
            requireCode(!hasText("Save permissions"), "ADMIN_DIALOG_NOT_CLOSED")
            checked("permission_cancelled")
            openTarget()
            adminPermission("Fire", true, false)
            saveTarget()
            checked("permission_changed")
            openTarget()
            adminPermission("Fire", false, true)
            saveTarget()
            checked("permission_restored")
            openTarget()
            adminPermission("Admin", false, true)
            saveTarget()
            checked("secondary_admin_ready")
            // Keep a loaded dialog while the host's exact QA B session revokes only this QA Admin role.
            openTarget()
            checked("primary_role_revoked")
            clickLabel(setOf("Save permissions"), "ADMIN_SAVE_MISSING")
            adminAwaitMessage("Administrator permissions are required.")
            requireCode(!hasText(QA_EMAIL_B) && !hasText("Registration enabled") && !hasText("Save permissions") &&
                !exists { label(it, setOf("Manage")) }, "ADMIN_DENIED_CACHE_REMAINED")
            report.put("denial_cleared_cache_and_dialog", true)
            checked("primary_role_denied")
            checked("primary_role_restored")
            clickLabel(setOf("Refresh users"), "ADMIN_REFRESH_MISSING", scroll = true)
            seekLabel(setOf("Refresh users"), "ADMIN_RELOAD_NOT_SETTLED", scroll = true, clickable = true).recycle()
            scrollAdminToTop()
            await("ADMIN_RESTORED_CONTROLS_MISSING") { hasText("Registration enabled") }
            checked("primary_reloaded")
            openTarget()
            adminPermission("Admin", true, false)
            saveTarget()
            checked("secondary_admin_removed")
            openSettings(englishOnly = true)
            requireEmail(QA_ADMIN_EMAIL)
            awaitProfileRead()
            requireCode(logoutQa(report, QA_ADMIN_EMAIL), "QA_ADMIN_LOGOUT_FAILED")
            report.put("native_logout", true)
            checked("completed")
            report.put("stage", "passed").put("cleanup_pending", false).put("checkpoint_count", ADMIN_STAGES.size)
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure)).put("last_checkpoint", checkpoint).put("cleanup_pending", true)
            throw AssertionError(safeFailure(failure))
        } finally { writeReport(run, "admin-forms", report) }
    }

    private fun scrollAdminToTop() {
        repeat(64) {
            if (hasText("Registration enabled") || hasText("Administrator permissions are required.")) return
            val container = find { it.isScrollable && visible(it) } ?: return
            val bounds = Rect().also(container::getBoundsInScreen)
            val moved = try { container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } finally { container.recycle() }
            if (!moved) swipe(backwards = true, bounds = bounds)
            SystemClock.sleep(250)
        }
    }

    private fun adminAwaitMessage(message: String) {
        requireCode(message in setOf("Permissions updated.", "Administrator permissions are required."), "ADMIN_MESSAGE_INVALID")
        hideKeyboardIfShown()
        val until = SystemClock.uptimeMillis() + 30_000
        var scrolls = 0
        while (SystemClock.uptimeMillis() < until) {
            if (hasText(message)) return
            // Admin feedback follows the whole user list; never rewind that list while seeking it.
            val offscreen = find { label(it, setOf(message)) }
            if (offscreen != null) {
                try { offscreen.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) } finally { offscreen.recycle() }
                SystemClock.sleep(250)
                if (hasText(message)) return
            }
            if (scrolls < 64) {
                val container = find {
                    val bounds = Rect().also(it::getBoundsInScreen)
                    it.isScrollable && visible(it) && (it.className?.toString() == "android.widget.ScrollView" || bounds.height() > bounds.width())
                }
                if (container != null) {
                    val bounds = Rect().also(container::getBoundsInScreen)
                    val moved = try { container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } finally { container.recycle() }
                    if (!moved) swipe(backwards = false, bounds = bounds)
                    scrolls++
                }
            }
            SystemClock.sleep(250)
        }
        throw IllegalStateException("ADMIN_MESSAGE_MISSING")
    }

    private fun adminSiblingControl(heading: AccessibilityNodeInfo, text: String, code: String) {
        var container: AccessibilityNodeInfo? = heading
        try {
            repeat(8) {
                val parent = container ?: throw IllegalStateException(code)
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(AccessibilityNodeInfo.obtain(parent))
                var match: AccessibilityNodeInfo? = null
                var count = 0
                var visited = 0
                try {
                    while (queue.isNotEmpty() && visited++ < 256) {
                        val node = queue.removeFirst()
                        if (label(node, setOf(text))) {
                            count++
                            if (visible(node) && hasClickableAncestor(node) && match == null) match = AccessibilityNodeInfo.obtain(node)
                        }
                        for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
                        node.recycle()
                    }
                    if (match != null) {
                        requireCode(count == 1, "ADMIN_AMBIGUOUS_TARGET_CONTROL")
                        val chosen = requireNotNull(match); match = null
                        clickControl(chosen, code)
                        return
                    }
                } finally { match?.recycle(); while (queue.isNotEmpty()) queue.removeFirst().recycle() }
                container = parent.parent
                parent.recycle()
            }
            throw IllegalStateException(code)
        } finally { container?.recycle() }
    }

    private fun adminPermission(label: String, before: Boolean, after: Boolean) {
        requireCode(hasText("Save permissions") && hasText(QA_EMAIL_B), "ADMIN_TARGET_DIALOG_INVALID")
        val heading = seekLabel(setOf(label), "ADMIN_PERMISSION_LABEL_MISSING", scroll = true)
        val headingBounds = Rect().also(heading::getBoundsInScreen)
        requireCode(!headingBounds.isEmpty, "ADMIN_PERMISSION_LABEL_BOUNDS_INVALID")
        var container: AccessibilityNodeInfo? = heading
        try {
            repeat(8) {
                val parent = container ?: throw IllegalStateException("ADMIN_CHECKBOX_MISSING")
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(AccessibilityNodeInfo.obtain(parent))
                var checkbox: AccessibilityNodeInfo? = null
                var count = 0
                var visited = 0
                try {
                    while (queue.isNotEmpty() && visited++ < 128) {
                        val node = queue.removeFirst()
                        val bounds = Rect().also(node::getBoundsInScreen)
                        // Compose exposes these rows as siblings in one column; bind by the label's own vertical row.
                        if (node.isCheckable && bounds.top <= headingBounds.centerY() && bounds.bottom > headingBounds.centerY()) {
                            count++; if (checkbox == null) checkbox = AccessibilityNodeInfo.obtain(node)
                        }
                        for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
                        node.recycle()
                    }
                    if (checkbox != null) {
                        requireCode(count == 1 && checkbox!!.isChecked == before && visible(checkbox!!), "ADMIN_CHECKBOX_STATE_INVALID")
                        val chosen = requireNotNull(checkbox); checkbox = null
                        clickControl(chosen, "ADMIN_CHECKBOX_CLICK_REJECTED")
                        // The server ledger after Save verifies persistence; this action never submits on its own.
                        requireCode(before != after, "ADMIN_CHECKBOX_NO_CHANGE")
                        return
                    }
                } finally { checkbox?.recycle(); while (queue.isNotEmpty()) queue.removeFirst().recycle() }
                container = parent.parent
                parent.recycle()
            }
            throw IllegalStateException("ADMIN_CHECKBOX_MISSING")
        } finally { container?.recycle() }
    }

    @Test fun qualifyDedicatedQaSessionForms() {
        val run = preflight(12)
        val args = InstrumentationRegistry.getArguments()
        requireCode(args.getString("releaseQualificationAccount") == "a", "SESSION_QA_OWNER_INVALID")
        val loginRun = args.getString("releaseQualificationLoginRunId").orEmpty()
        requireCode(runCatching { UUID.fromString(loginRun).toString() == loginRun }.getOrDefault(false), "LOGIN_RUN_INVALID")
        val previous = readJson("release-qualification-$loginRun-couple-login.json", 16_384)
        requireCode(previous.optString("stage") == "passed" && previous.optString("user_id") == QA_USER &&
            previous.optBoolean("settings_identity") && previous.optBoolean("english_selected"), "QA_LOGIN_CHECKPOINT_INVALID")
        requireCode(!privateFile("release-qualification-$run-session-forms.json").exists() &&
            SESSION_STAGES.none { privateFile("release-qualification-$run-session-${it.replace('_', '-')}.json").exists() },
            "SESSION_REPORT_ALREADY_EXISTS")
        val report = baseReport(run, 12, "session-forms")
        var checkpoint = "started"
        fun checked(stage: String): JSONObject {
            checkpoint = stage
            val suffix = stage.replace('_', '-')
            writeReport(run, "session-$suffix", JSONObject().put("run_id", run).put("user_id", QA_USER)
                .put("stage", stage).put("status", "ready"))
            val name = "release-qualification-$run-session-ack-$suffix.json"
            val until = SystemClock.uptimeMillis() + 90_000
            while (!privateFile(name).exists() && SystemClock.uptimeMillis() < until) SystemClock.sleep(250)
            val ack = readJson(name, 2_048)
            val keys = ack.keys().asSequence().toSet()
            requireCode(keys == setOf("run_id", "user_id", "stage", "status", "session_id") &&
                ack.optString("run_id") == run && ack.optString("user_id") == QA_USER &&
                ack.optString("stage") == stage && ack.optString("status") == "observed", "SESSION_ACK_INVALID")
            val id = ack.optString("session_id")
            requireCode(id.isEmpty() || runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false), "SESSION_ACK_ID_INVALID")
            requireCode(privateFile(name).delete(), "SESSION_ACK_CONSUMPTION_FAILED")
            return ack
        }
        fun refreshSecurity() {
            mfaTopClick("Refresh security")
            // This enabled control is restored only after the asynchronous refresh finishes.
            await("SESSION_REFRESH_BUSY") {
                exists { label(it, setOf("Refresh security")) && visible(it) && hasClickableAncestor(it) }
            }
        }
        try {
            report.put("pid", launch())
            openSettings(englishOnly = true)
            requireQaEmail()
            awaitProfileRead()
            mfaOpenSecurity()
            val primary = checked("primary_ready").getString("session_id")
            val secondary = checked("secondary_ready").getString("session_id")
            requireCode(primary != secondary && primary.isNotEmpty() && secondary.isNotEmpty(), "SESSION_IDS_NOT_DISTINCT")
            refreshSecurity()
            sessionRevoke(primary = false, id = secondary)
            await("SESSION_CONFIRMATION_MISSING") { hasText("Revoke this session?") }
            clickLabel(setOf("Cancel"), "SESSION_CANCEL_MISSING")
            requireCode(!hasText("Revoke this session?"), "SESSION_CANCEL_NOT_CLOSED")
            checked("revocation_cancelled")
            sessionRevoke(primary = false, id = secondary)
            clickLabel(setOf("Confirm"), "SESSION_CONFIRM_MISSING")
            mfaAwaitMessage("Access revoked.")
            report.put("secondary_revoked_from_form", true)
            checked("secondary_revoked")
            checked("secondary_refresh_rejected")
            // Returning to Account observes the current authenticated identity after revocation.
            scrollAccountToTop()
            clickLabel(setOf("Account"), "ACCOUNT_PANE_MISSING")
            requireQaEmail()
            awaitProfileRead()
            report.put("primary_identity_preserved", true)
            val survivor = checked("survivor_ready").getString("session_id")
            requireCode(survivor !in setOf(primary, secondary) && survivor.isNotEmpty(), "SESSION_SURVIVOR_NOT_DISTINCT")
            mfaOpenSecurity()
            refreshSecurity()
            sessionRevoke(primary = true, id = primary)
            await("SESSION_CONFIRMATION_MISSING") { hasText("Revoke this session?") }
            clickLabel(setOf("Confirm"), "SESSION_CONFIRM_MISSING")
            await("SESSION_CURRENT_NOT_SIGNED_OUT") { editable(password = false) && editable(password = true) && !hasDashboardContent() }
            report.put("current_revoked_from_form", true).put("login_form_after_current_revocation", true)
            checked("primary_revoked")
            checked("survivor_refreshed")
            checked("completed")
            report.put("stage", "passed").put("cleanup_pending", false).put("checkpoint_count", SESSION_STAGES.size)
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure)).put("last_checkpoint", checkpoint).put("cleanup_pending", true)
            throw AssertionError(safeFailure(failure))
        } finally { writeReport(run, "session-forms", report) }
    }

    private fun sessionRevoke(primary: Boolean, id: String) {
        val agent = (if (primary) "Savely Android" else "Savely QA A05") + " · session $id"
        scrollAccountToTop()
        val labelNode = seekLabel(setOf(agent), "SESSION_DEVICE_MISSING", scroll = true)
        var container: AccessibilityNodeInfo? = labelNode
        try {
            repeat(8) {
                val current = container ?: throw IllegalStateException("SESSION_CARD_MISSING")
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(AccessibilityNodeInfo.obtain(current))
                var button: AccessibilityNodeInfo? = null
                var deviceCount = 0
                var thisDevice = false
                var visited = 0
                try {
                    while (queue.isNotEmpty() && visited++ < 256) {
                        val node = queue.removeFirst()
                        val text = node.text?.toString().orEmpty()
                        if (text.contains(" · session ")) deviceCount++
                        if (label(node, setOf("This device"))) thisDevice = true
                        if (label(node, setOf("Revoke")) && visible(node) && hasClickableAncestor(node) && button == null)
                            button = AccessibilityNodeInfo.obtain(node)
                        for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
                        node.recycle()
                    }
                    if (button != null) {
                        requireCode(deviceCount == 1 && thisDevice == primary, "SESSION_CARD_OWNER_INVALID")
                        val selected = requireNotNull(button)
                        button = null
                        clickControl(selected, "SESSION_REVOKE_REJECTED")
                        return
                    }
                } finally {
                    button?.recycle()
                    while (queue.isNotEmpty()) queue.removeFirst().recycle()
                }
                container = current.parent
                current.recycle()
            }
            throw IllegalStateException("SESSION_REVOKE_MISSING")
        } finally { container?.recycle() }
    }

    @Test fun mfaTotpReferenceVector() {
        val run = preflight(12)
        requireCode(qaTotp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59) == "287082", "RFC6238_VECTOR_FAILED")
        writeReport(run, "mfa-vector", baseReport(run, 12, "mfa-vector").put("stage", "passed"))
    }

    @Test fun mfaFixedLabelDiagnostic() {
        val run = preflight(12)
        val failedRun = InstrumentationRegistry.getArguments().getString("releaseQualificationLoginRunId").orEmpty()
        requireCode(runCatching { UUID.fromString(failedRun).toString() == failedRun }.getOrDefault(false), "MFA_DIAGNOSTIC_RUN_INVALID")
        val failed = readJson("release-qualification-$failedRun-mfa-forms.json", 16_384)
        requireCode(failed.optString("stage") == "failed" && failed.optString("user_id") == QA_USER && failed.optInt("target_code") == 12,
            "MFA_DIAGNOSTIC_OWNER_INVALID")
        val labels = listOf("Two-step verification", "Set up authenticator", "Add this key manually to your authenticator app:",
            "QR code to set up your authenticator", "6-digit code", "Enable verification", "Cancel setup", "Incomplete authenticator setup",
            "Incorrect or expired verification code.", "The service returned invalid data.", "Refresh security")
        val flags = JSONObject()
        for (text in labels) {
            val node = find { label(it, setOf(text)) }
            flags.put(text, if (node == null) JSONObject().put("found", false) else try {
                JSONObject().put("found", true).put("visible", visible(node)).put("enabled", node.isEnabled)
                    .put("clickable", hasClickableAncestor(node)).put("bounds", Rect().also(node::getBoundsInScreen).toShortString())
            } finally { node.recycle() })
        }
        writeReport(run, "mfa-diagnostic", baseReport(run, 12, "mfa-diagnostic").put("stage", "observed").put("labels", flags))
    }

    @Test fun qualifyDedicatedQaMfaForms() {
        val run = preflight(12)
        requireCode(InstrumentationRegistry.getArguments().getString("releaseQualificationAccount") == "a", "MFA_QA_OWNER_INVALID")
        val loginRun = InstrumentationRegistry.getArguments().getString("releaseQualificationLoginRunId").orEmpty()
        requireCode(runCatching { UUID.fromString(loginRun).toString() == loginRun }.getOrDefault(false), "LOGIN_RUN_INVALID")
        val previous = readJson("release-qualification-$loginRun-couple-login.json", 16_384)
        requireCode(previous.optString("stage") == "passed" && previous.optString("user_id") == QA_USER &&
            previous.optBoolean("settings_identity") && previous.optBoolean("english_selected"), "QA_LOGIN_CHECKPOINT_INVALID")
        requireCode(!privateFile("release-qualification-$run-mfa-forms.json").exists(), "MFA_REPORT_ALREADY_EXISTS")
        requireCode(MFA_STAGES.none { privateFile("release-qualification-$run-mfa-${it.replace('_', '-')}.json").exists() }, "MFA_CHECKPOINT_ALREADY_EXISTS")
        val report = baseReport(run, 12, "mfa-forms")
        var checkpoint = "started"
        var lastSuccessfulCounter = -1L
        fun checked(stage: String) {
            checkpoint = stage
            mfaCheckpoint(run, stage)
        }
        fun freshCode(secret: String): String {
            val until = SystemClock.uptimeMillis() + 40_000
            while (SystemClock.uptimeMillis() < until) {
                val seconds = System.currentTimeMillis() / 1000
                if (seconds / 30 > lastSuccessfulCounter && seconds % 30 < 22) {
                    lastSuccessfulCounter = seconds / 30
                    return qaTotp(secret, seconds)
                }
                SystemClock.sleep(250)
            }
            throw IllegalStateException("MFA_FRESH_COUNTER_TIMEOUT")
        }
        try {
            // A second monouse input is consumed before any MFA action, solely for the later form login.
            val credentials = consumeCredentials(run)
            report.put("input_consumed", true).put("pid", launch())
            openSettings(englishOnly = true)
            requireQaEmail()
            awaitProfileRead()
            report.put("upgrade_preserved_authenticated_ui", true)
            mfaOpenSecurity()

            var secret = mfaStartEnrollment()
            report.put("first_qr_accessible", hasText("QR code to set up your authenticator"))
            checked("pending_cancel_ready")
            mfaTopClick("Cancel setup")
            await("MFA_CANCEL_DIALOG_MISSING") { hasText("Cancel setup?") }
            clickLabel(setOf("Cancel"), "MFA_CANCEL_BUTTON_MISSING")
            await("MFA_CANCEL_NOT_DISMISSED") { !hasText("Cancel setup?") }
            scrollAccountToTop()
            seekLabel(setOf("Add this key manually to your authenticator app:"), "MFA_MANUAL_KEY_MISSING", scroll = true).recycle()
            requireCode(exists { it.text?.toString() == secret }, "MFA_SETUP_LOST_AFTER_CANCEL")
            checked("pending_cancel_kept")
            clickLabel(setOf("Account"), "ACCOUNT_PANE_MISSING")
            await("ACCOUNT_CONTENT_MISSING") { hasProfileContent() }
            requireCode(!exists { it.text?.toString() == secret }, "MFA_SECRET_RETAINED_OUTSIDE_SETUP")
            mfaOpenSecurity()
            seekLabel(setOf("Incomplete authenticator setup"), "MFA_PENDING_MISSING", scroll = true).recycle()
            requireCode(!exists { it.text?.toString() == secret }, "MFA_SECRET_RESTORED")
            secret = ""
            checked("pending_persisted")
            mfaTopClick("Cancel incomplete setup")
            await("MFA_CANCEL_DIALOG_MISSING") { hasText("Cancel setup?") }
            clickLabel(setOf("Confirm"), "MFA_CONFIRM_MISSING")
            mfaAwaitMessage("Incomplete setup removed.")
            checked("pending_removed")

            secret = mfaStartEnrollment()
            report.put("second_qr_accessible", hasText("QR code to set up your authenticator"))
            checked("enrollment_ready")
            mfaFillCode("6-digit code", "12345")
            requireCode(!exists { label(it, setOf("Enable verification")) && it.isEnabled && hasClickableAncestor(it) }, "MFA_SHORT_CODE_ENABLED")
            mfaFillCode("6-digit code", mfaWrongCode(secret))
            mfaTopClick("Enable verification")
            mfaAwaitMessage("Incorrect or expired verification code.")
            scrollAccountToTop()
            seekLabel(setOf("Add this key manually to your authenticator app:"), "MFA_MANUAL_KEY_MISSING", scroll = true).recycle()
            requireCode(exists { it.text?.toString() == secret }, "MFA_SETUP_LOST_AFTER_WRONG_CODE")
            checked("enrollment_wrong")
            mfaFillCode("6-digit code", freshCode(secret))
            mfaTopClick("Enable verification")
            mfaAwaitMessage("Authenticator enabled.")
            requireCode(!exists { it.text?.toString() == secret }, "MFA_SECRET_RETAINED_AFTER_VERIFY")
            checked("enrollment_verified")

            openSettings(englishOnly = true)
            requireQaEmail()
            requireCode(logoutQa(report), "MFA_FIRST_LOGOUT_FAILED")
            checked("logged_out")
            setField(password = false, value = credentials.first)
            setField(password = true, value = credentials.second)
            hideKeyboardIfShown()
            clickLabel(setOf("Sign in"), "LOGIN_SUBMIT_MISSING")
            await("MFA_LOGIN_GATE_MISSING") { hasText("Two-step verification") && hasText("6-digit code") && !hasText("Dashboard") }
            mfaFillCode("6-digit code", mfaWrongCode(secret))
            mfaTopClick("Verify")
            mfaAwaitMessage("Incorrect or expired verification code.")
            requireCode(!hasText("Dashboard") && !hasText("Profile"), "MFA_GATE_EXPOSED_ACCOUNT_DATA")
            checked("login_gate_wrong")
            mfaFillCode("6-digit code", freshCode(secret))
            mfaTopClick("Verify")
            awaitDashboard()
            checked("login_gate_verified")

            openSettings(englishOnly = true)
            requireQaEmail()
            awaitProfileRead()
            mfaOpenSecurity()
            mfaTopClick("Remove authenticator")
            await("MFA_REMOVE_DIALOG_MISSING") { hasText("Remove the authenticator?") }
            clickLabel(setOf("Cancel"), "MFA_CANCEL_BUTTON_MISSING")
            await("MFA_REMOVE_DIALOG_STILL_PRESENT") { !hasText("Remove the authenticator?") }
            checked("removal_cancelled")
            mfaTopClick("Remove authenticator")
            await("MFA_REMOVE_DIALOG_MISSING") { hasText("Remove the authenticator?") }
            mfaFillCode("Code to remove the authenticator", mfaWrongCode(secret))
            clickLabel(setOf("Confirm"), "MFA_CONFIRM_MISSING")
            mfaAwaitMessage("Incorrect or expired verification code.")
            requireCode(exists { it.isEditable && it.text?.toString().orEmpty().isEmpty() }, "MFA_REMOVAL_CODE_NOT_CLEARED")
            checked("removal_wrong")
            mfaFillCode("Code to remove the authenticator", freshCode(secret))
            clickLabel(setOf("Confirm"), "MFA_CONFIRM_MISSING")
            mfaAwaitMessage("Authenticator removed.")
            requireCode(!hasText("Remove the authenticator?"), "MFA_REMOVAL_DIALOG_RETAINED")
            secret = ""
            checked("removal_completed")
            openSettings(englishOnly = true)
            requireQaEmail()
            requireCode(logoutQa(report), "MFA_FINAL_LOGOUT_FAILED")
            checked("completed")
            report.put("stage", "passed").put("cleanup_pending", false).put("checkpoint_count", MFA_STAGES.size)
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure)).put("last_checkpoint", checkpoint).put("cleanup_pending", true)
            throw AssertionError(safeFailure(failure))
        } finally { writeReport(run, "mfa-forms", report) }
    }

    private fun mfaCheckpoint(run: String, stage: String) {
        requireCode(stage in MFA_STAGES, "MFA_STAGE_INVALID")
        val suffix = stage.replace('_', '-')
        writeReport(run, "mfa-$suffix", JSONObject().put("run_id", run).put("user_id", QA_USER).put("stage", stage).put("status", "ready"))
        val name = "release-qualification-$run-mfa-ack-$suffix.json"
        val until = SystemClock.uptimeMillis() + 60_000
        while (!privateFile(name).exists() && SystemClock.uptimeMillis() < until) SystemClock.sleep(250)
        val ack = readJson(name, 1_024)
        requireCode(ack.keys().asSequence().toSet() == setOf("run_id", "user_id", "stage", "status") &&
            ack.optString("run_id") == run && ack.optString("user_id") == QA_USER &&
            ack.optString("stage") == stage && ack.optString("status") == "observed", "MFA_ACK_INVALID")
        requireCode(privateFile(name).delete(), "MFA_ACK_CONSUMPTION_FAILED")
    }

    private fun mfaOpenSecurity() {
        scrollAccountToTop()
        clickLabel(setOf("Security"), "SECURITY_PANE_MISSING")
        await("MFA_SECURITY_MISSING") { hasText("Two-step verification") }
        // The heading precedes the async factor/session refresh; keep its status area in view until it settles.
        await("MFA_SECURITY_STATUS_MISSING") {
            hasText("Incomplete authenticator setup") || hasText("Active authenticator") ||
                exists { label(it, setOf("Set up authenticator")) && visible(it) && hasClickableAncestor(it) }
        }
    }

    private fun mfaTopClick(label: String) {
        hideKeyboardIfShown()
        val until = SystemClock.uptimeMillis() + 45_000
        while (SystemClock.uptimeMillis() < until) {
            scrollAccountToTop()
            val node = runCatching { seekLabel(setOf(label), "MFA_CONTROL_MISSING", scroll = true, clickable = true) }.getOrNull()
            if (node != null) {
                clickControl(node, "MFA_ACTION_REJECTED")
                return
            }
            SystemClock.sleep(250)
        }
        throw IllegalStateException("MFA_CONTROL_MISSING")
    }

    private fun mfaStartEnrollment(): String {
        mfaTopClick("Set up authenticator")
        // Wait for the enrollment body at the top before a search can scroll past the async response.
        await("MFA_ENROLLMENT_BODY_MISSING") {
            hasText("Add this key manually to your authenticator app:") || hasText("QR code to set up your authenticator")
        }
        seekLabel(setOf("Add this key manually to your authenticator app:"), "MFA_MANUAL_KEY_MISSING", scroll = true).recycle()
        val node = find { !it.isEditable && Regex("[A-Z2-7]{16,256}").matches(it.text?.toString().orEmpty()) }
            ?: throw IllegalStateException("MFA_MANUAL_KEY_INVALID")
        val secret = try { node.text.toString() } finally { node.recycle() }
        scrollAccountToTop()
        // Rejected provider SVGs use the same manual-key fallback covered by the local QR fixtures.
        return secret
    }

    private fun mfaFillCode(label: String, code: String) {
        requireCode(Regex("[0-9]{5,6}").matches(code), "MFA_INPUT_INVALID")
        hideKeyboardIfShown()
        scrollAccountToTop()
        var node = seekLabel(setOf(label), "MFA_CODE_FIELD_MISSING", scroll = true)
        try {
            for (depth in 0 until 8) {
                if (node.isEditable && !node.isPassword) break
                val parent = node.parent ?: throw IllegalStateException("MFA_EDITABLE_MISSING")
                node.recycle(); node = parent
            }
            requireCode(node.isEditable && node.isEnabled && !node.isPassword, "MFA_EDITABLE_MISSING")
            requireCode(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code) }), "MFA_SET_TEXT_REJECTED")
            await("MFA_INPUT_NOT_CONFIRMED") { exists { it.isEditable && !it.isPassword && it.text?.toString() == code } }
        } finally { node.recycle() }
        hideKeyboardIfShown()
    }

    private fun mfaAwaitMessage(message: String) {
        hideKeyboardIfShown()
        val until = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < until) {
            scrollAccountToTop()
            val node = runCatching { seekLabel(setOf(message), "MFA_MESSAGE_MISSING", scroll = true) }.getOrNull()
            if (node != null) { node.recycle(); return }
            SystemClock.sleep(250)
        }
        throw IllegalStateException("MFA_MESSAGE_MISSING")
    }

    private fun mfaWrongCode(secret: String): String {
        val seconds = System.currentTimeMillis() / 1000
        val nearby = (-2L..2L).map { qaTotp(secret, seconds + it * 30) }.toSet()
        return (0..9).map { it.toString().padStart(6, '0') }.first { it !in nearby }
    }

    // Same SHA1/30s/6-digit reference as RealAuthQualificationTest; real inputs stay in RAM only.
    private fun qaTotp(secret: String, seconds: Long): String {
        requireCode(Regex("[A-Z2-7]{16,256}").matches(secret), "MFA_SECRET_ENCODING_INVALID")
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val key = ByteArray(secret.length * 5 / 8)
        var buffer = 0; var bits = 0; var index = 0
        for (char in secret) {
            buffer = (buffer shl 5) or alphabet.indexOf(char); bits += 5
            if (bits >= 8) { bits -= 8; key[index++] = (buffer shr bits).toByte() }
        }
        val hash = try {
            Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
                .doFinal(ByteBuffer.allocate(8).putLong(seconds / 30).array())
        } finally { key.fill(0) }
        val offset = hash.last().toInt() and 15
        val value = ((hash[offset].toInt() and 127) shl 24) or ((hash[offset + 1].toInt() and 255) shl 16) or
            ((hash[offset + 2].toInt() and 255) shl 8) or (hash[offset + 3].toInt() and 255)
        hash.fill(0)
        return (value % 1_000_000).toString().padStart(6, '0')
    }

    @Test fun fillDedicatedQaCouplePublicField() {
        val run = preflight(11)
        val (user, _) = dedicatedQaAccount()
        val args = InstrumentationRegistry.getArguments()
        val loginRun = args.getString("releaseQualificationLoginRunId").orEmpty()
        requireCode(runCatching { UUID.fromString(loginRun).toString() == loginRun }.getOrDefault(false), "LOGIN_RUN_INVALID")
        val previous = readJson("release-qualification-$loginRun-couple-login.json", 16_384)
        requireCode(previous.optString("stage") == "passed" && previous.optString("user_id") == user, "QA_LOGIN_CHECKPOINT_INVALID")
        val field = args.getString("releaseQualificationPublicField").orEmpty()
        val value = args.getString("releaseQualificationPublicValue").orEmpty()
        requireCode(field in setOf("Shared category (optional)", "Partner's amount in the transaction currency", "Monthly limit EUR"), "PUBLIC_FIELD_NOT_ALLOWED")
        val validValue = if (field == "Shared category (optional)")
            Regex("android-couple-form-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(value)
        else value.length in 1..32 && Regex("[0-9]+(?:\\.[0-9]{1,3})?").matches(value)
        requireCode(validValue, "PUBLIC_VALUE_INVALID")
        val report = baseReport(run, 11, "couple-field").put("user_id", user).put("field", field)
        try {
            repeat(3) { hideKeyboardIfShown(); SystemClock.sleep(250) }
            var node = seekLabel(setOf(field), "PUBLIC_FIELD_MISSING", scroll = true)
            try {
                for (depth in 0 until 8) {
                    if (node.isEditable && !node.isPassword) break
                    val parent = node.parent ?: throw IllegalStateException("PUBLIC_EDITABLE_MISSING")
                    node.recycle(); node = parent
                }
                requireCode(node.isEditable && !node.isPassword && node.isEnabled, "PUBLIC_EDITABLE_MISSING")
                requireCode(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }), "PUBLIC_SET_TEXT_REJECTED")
                await("PUBLIC_VALUE_NOT_CONFIRMED") { exists { it.isEditable && !it.isPassword && it.text?.toString() == value && visible(it) } }
            } finally { node.recycle() }
            repeat(3) { hideKeyboardIfShown(); SystemClock.sleep(250) }
            report.put("value_verified", true).put("stage", "passed")
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure))
            throw AssertionError(safeFailure(failure))
        } finally { writeReport(run, "couple-field", report) }
    }

    @Test fun loginDedicatedQaForCouple() {
        val code = InstrumentationRegistry.getArguments().getString("expectedTargetCode")?.toIntOrNull()
        requireCode(code in setOf(11, 12, 13, 14, 15, 16, 18), "EXPECTED_TARGET_CODE_INVALID")
        val run = preflight(requireNotNull(code))
        val (user, email) = dedicatedQaAccount()
        val report = baseReport(run, code, "couple-login").put("user_id", user)
        try {
            report.put("pid", launch())
            await("LOGIN_FORM_MISSING") { editable(password = false) && editable(password = true) }
            requireCode(!hasText("Dashboard"), "PREEXISTING_AUTHENTICATED_UI")
            val credentials = consumeCredentials(run, user, email)
            report.put("input_consumed", true)
            setField(password = false, value = credentials.first)
            setField(password = true, value = credentials.second)
            hideKeyboardIfShown()
            clickLabel(setOf("Accedi", "Sign in"), "LOGIN_SUBMIT_MISSING")
            report.put("login_form_submitted", true)
            awaitDashboard()
            report.put("dashboard", true)
            clickLabel(setOf("Settings", "Impostazioni"), "SETTINGS_NAVIGATION_MISSING")
            scrollAccountToTop()
            await("ACCOUNT_CONTENT_MISSING") { hasProfileContent() }
            requireEmail(email)
            awaitProfileRead()
            report.put("settings_identity", true).put("profile_read", true)
            clickLabel(setOf("English"), "LANGUAGE_CONTROL_MISSING", scroll = true)
            await("ENGLISH_NOT_SELECTED") { selected("English") && hasText("Language") }
            report.put("english_selected", true).put("stage", "passed").put("session_retained_for_couple", true)
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure))
                .put("cleanup_pending", report.optBoolean("login_form_submitted"))
            throw AssertionError(safeFailure(failure))
        } finally { writeReport(run, "couple-login", report) }
    }

    @Test fun logoutDedicatedQaForCouple() {
        val code = InstrumentationRegistry.getArguments().getString("expectedTargetCode")?.toIntOrNull()
        requireCode(code in setOf(11, 12, 13, 14, 15, 16, 18), "EXPECTED_TARGET_CODE_INVALID")
        val run = preflight(requireNotNull(code))
        val (user, email) = dedicatedQaAccount()
        val previous = readJson("release-qualification-$run-couple-login.json", 16_384)
        requireCode(previous.optString("run_id") == run && previous.optString("user_id") == user &&
            previous.optString("stage") == "passed" && previous.optBoolean("settings_identity"), "COUPLE_LOGIN_CHECKPOINT_INVALID")
        requireNewNavigationReport(run, "couple-logout")
        requireNewNavigationReport(run, "couple-logout-reservation")
        writeReport(run, "couple-logout-reservation", JSONObject().put("run_id", run).put("user_id", user).put("stage", "reserved"))
        val report = baseReport(run, code, "couple-logout").put("user_id", user)
        try {
            report.put("pid", launch())
            if (code in setOf(15, 16, 18)) financeOpenProfile() else openSettings()
            scrollAccountToTop()
            await("ACCOUNT_CONTENT_MISSING") { hasProfileContent() }
            requireEmail(email)
            awaitProfileRead()
            report.put("settings_identity", true)
            val loggedOut = logoutQa(report, email)
            report.put("logout_confirmed", loggedOut).put("cleanup_pending", !loggedOut)
            requireCode(loggedOut, "QA_LOGOUT_NOT_CONFIRMED")
            report.put("stage", "passed")
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure)).put("cleanup_pending", true)
            throw AssertionError(safeFailure(failure))
        } finally { writeReport(run, "couple-logout", report) }
    }

    /** V09/F04: known QA local drafts only. The host owns process death and server-session evidence. */
    @Test fun qualifyReservedFireForm() {
        val run = preflight(18)
        val (user, email) = dedicatedQaAccount()
        requireCode(user in setOf(QA_USER, QA_USER_B), "FIRE_ACCOUNT_INVALID")
        val actor = if (user == QA_USER) "a" else "b"
        val op = InstrumentationRegistry.getArguments().getString("fireOperation").orEmpty()
        requireCode(op in setOf("baseline", "prepare-draft", "verify-after-process-death", "share-cancel", "cancel-import",
            "read-retained", "reset-own-draft", "verify-clean"), "FIRE_OPERATION_INVALID")
        val phase = "fire-$actor-$op"
        requireNewNavigationReport(run, phase)
        requireNewNavigationReport(run, "$phase-reservation")
        val inputName = "release-qualification-$run-$phase-input.json"
        val input = readJson(inputName, 4_096)
        requireCode(input.keys().asSequence().toSet() == setOf("run_id", "user_id", "operation", "login_run_id") &&
            input.optString("run_id") == run && input.optString("user_id") == user && input.optString("operation") == op,
            "FIRE_INPUT_INVALID")
        val loginRun = input.optString("login_run_id")
        requireCode(runCatching { UUID.fromString(loginRun).toString() == loginRun }.getOrDefault(false) && loginRun != run,
            "FIRE_LOGIN_REFERENCE_INVALID")
        fireRequireLogin(loginRun, user)
        writeReport(run, "$phase-reservation", input.put("stage", "reserved"))
        requireCode(privateFile(inputName).delete(), "FIRE_INPUT_CONSUMPTION_FAILED")
        val report = baseReport(run, 18, phase).put("user_id", user).put("login_run_id", loginRun)
            .put("operation", op).put("local_only", true).put("mutation_scope", "local-fire-preferences")
            .put("mutation_attempted", false).put("local_cleanup_pending", true).put("cleanup_pending", true)
        val defaults = fireDefaultValues()
        val draft = fireDraftValues(user)
        try {
            val pid = launch()
            report.put("pid", pid)
            financeOpenProfile()
            requireEmail(email)
            awaitProfileRead()
            report.put("settings_identity", true).put("profile_read", true).put("session_retained", true)
            financeEnter("FIRE")
            fireTop()
            when (op) {
                "baseline" -> {
                    fireRequireValues(defaults)
                    fireAwaitSaved()
                    report.put("baseline_confirmed", true).put("parameters_seen", 10)
                }
                "prepare-draft" -> {
                    val baseline = firePrior(run, actor, "baseline", user)
                    requireCode(baseline.optBoolean("baseline_confirmed") && baseline.optString("login_run_id") == loginRun,
                        "FIRE_BASELINE_INVALID")
                    fireRequireValues(defaults)
                    var before = defaults.toMap()
                    for ((index, entry) in draft.entries.withIndex()) {
                        val after = before + (entry.key to entry.value)
                        writeReport(run, "$phase-field-$index", JSONObject().put("run_id", run).put("user_id", user)
                            .put("login_run_id", loginRun).put("stage", "reserved").put("field", entry.key)
                            .put("before", JSONObject(before)).put("after", JSONObject(after)))
                        report.put("mutation_attempted", true)
                        fireTop()
                        fireLocateField(fireFieldLabels().getValue(entry.key)).recycle()
                        financeSetField(fireFieldLabels().getValue(entry.key), entry.value)
                        before = after
                    }
                    fireRequireValues(draft)
                    fireAwaitSaved()
                    report.put("valid_draft_saved", true).put("parameters_seen", 10)
                    fireTab("Standard")
                    fireMetric("FIRE target", if (user == QA_USER) "750000" else "900000")
                    fireTab("Barista")
                    fireMetric("Barista target", "600000")
                    fireMetric("Full FIRE target", if (user == QA_USER) "750000" else "900000")
                    report.put("standard_barista_seen", true)
                    writeReport(run, "$phase-before-invalid", JSONObject().put("run_id", run).put("user_id", user)
                        .put("login_run_id", loginRun).put("stage", "reserved").put("valid_draft", JSONObject(draft)))
                    fireTop()
                    fireLocateField("Annual expenses (€)").recycle()
                    financeSetField("Annual expenses (€)", "abc")
                    fireTop()
                    seekLabel(setOf("Incomplete or invalid values: the last valid configuration remains saved."),
                        "FIRE_INVALID_DRAFT_STATUS_MISSING", true).recycle()
                    seekLabel(setOf("Enter a valid number"), "FIRE_INVALID_DRAFT_ERROR_MISSING", true).recycle()
                    report.put("invalid_draft_seen", true)
                }
                "verify-after-process-death", "read-retained" -> {
                    val prepared = firePrior(run, actor, "prepare-draft", user)
                    requireCode(prepared.optBoolean("valid_draft_saved") && prepared.optBoolean("invalid_draft_seen"),
                        "FIRE_PREPARE_INVALID")
                    if (op == "verify-after-process-death") {
                        requireCode(prepared.optString("login_run_id") == loginRun && prepared.optInt("pid") > 0 &&
                            prepared.optInt("pid") != pid, "FIRE_NEW_PROCESS_NOT_CONFIRMED")
                        report.put("previous_app_pid", prepared.getInt("pid")).put("new_process", true)
                    } else requireCode(prepared.optString("login_run_id") != loginRun, "FIRE_NEW_LOGIN_REQUIRED")
                    fireRequireValues(draft)
                    fireAwaitSaved()
                    fireTab("Standard")
                    fireMetric("FIRE target", if (user == QA_USER) "750000" else "900000")
                    fireTab("Barista")
                    fireMetric("Barista target", "600000")
                    report.put("parameters_seen", 10).put("valid_draft_restored", true)
                }
                "share-cancel" -> {
                    val prepared = firePrior(run, actor, "prepare-draft", user)
                    requireCode(prepared.optBoolean("valid_draft_saved"), "FIRE_PREPARE_INVALID")
                    fireRequireValues(draft)
                    fireTab("Standard")
                    fireTop()
                    writeReport(run, "$phase-before-chooser", JSONObject().put("run_id", run).put("user_id", user)
                        .put("login_run_id", loginRun).put("stage", "reserved").put("valid_draft", JSONObject(draft)))
                    clickLabel(setOf("Share FIRE link"), "FIRE_SHARE_CONTROL_MISSING", true)
                    await("FIRE_SYSTEM_CHOOSER_MISSING") { fireChooserVisible(requirePreview = true) }
                    report.put("system_chooser_seen", true).put("synthetic_link_preview_seen", true)
                    // ACTION_SEND/text/plain is a source contract; accessibility cannot inspect the inner intent.
                    report.put("inner_send_intent_inspected", false)
                    readShell("input keyevent KEYCODE_BACK", 256)
                    await("FIRE_CHOOSER_CANCEL_NOT_CONFIRMED") {
                        !fireChooserVisible(requirePreview = false) && exists {
                            visible(it) && label(it, setOf("FIRE", "Standard", "Barista"))
                        }
                    }
                    fireRequireValues(draft)
                    fireAwaitSaved()
                    report.put("cancelled_without_send", true).put("parameters_seen", 10)
                }
                "cancel-import" -> {
                    val prepared = firePrior(run, actor, "prepare-draft", user)
                    requireCode(prepared.optBoolean("valid_draft_saved"), "FIRE_PREPARE_INVALID")
                    fireRequireValues(draft)
                    fireTop()
                    clickLabel(setOf("Import FIRE link"), "FIRE_IMPORT_CONTROL_MISSING", true)
                    await("FIRE_IMPORT_DIALOG_MISSING") { hasText("Import FIRE configuration") }
                    val preview = fireDraftValues(if (user == QA_USER) QA_USER_B else QA_USER)
                    val query = preview.entries.joinToString("&") { (key, value) ->
                        val wire = when (key) { "retirement" -> "retire"; "contribution" -> "contrib";
                            "withdrawal" -> "swr"; else -> key }
                        val number = if (key in setOf("return", "inflation", "withdrawal"))
                            java.math.BigDecimal(value).movePointLeft(2).toPlainString() else value
                        "$wire=$number"
                    }
                    financeSetField("Shared FIRE link", "https://savely.cc/fire/barista?$query")
                    hideKeyboardIfShown()
                    clickLabel(setOf("Preview"), "FIRE_PREVIEW_MISSING", true)
                    seekLabel(setOf("Configuration preview"), "FIRE_PREVIEW_NOT_CONFIRMED", true).recycle()
                    seekLabel(setOf("Annual part-time income (€): ${preview.getValue("parttime")}"),
                        "FIRE_PREVIEW_VALUES_MISSING", true).recycle()
                    clickLabel(setOf("Cancel"), "FIRE_CANCEL_MISSING", true)
                    await("FIRE_CANCEL_NOT_CONFIRMED") { !hasText("Import FIRE configuration") }
                    fireRequireValues(draft)
                    fireAwaitSaved()
                    report.put("preview_seen", true).put("cancelled_without_apply", true).put("parameters_seen", 10)
                }
                "reset-own-draft" -> {
                    val baseline = firePrior(run, actor, "baseline", user)
                    requireCode(baseline.optBoolean("baseline_confirmed"), "FIRE_BASELINE_INVALID")
                    val current = fireReadValues()
                    val basis = fireOwnedResetBasis(run, actor, user, current)
                    writeReport(run, "$phase-before-reset", JSONObject().put("run_id", run).put("user_id", user)
                        .put("login_run_id", loginRun).put("stage", "reserved").put("basis", basis))
                    fireTop()
                    report.put("mutation_attempted", true)
                    clickLabel(setOf("Restore example values"), "FIRE_RESET_MISSING", true)
                    fireAwaitSaved()
                    fireRequireValues(defaults)
                    fireAwaitSaved()
                    report.put("baseline_restored", true).put("parameters_seen", 10).put("cleanup_basis", basis)
                        .put("local_cleanup", true)
                }
                "verify-clean" -> {
                    val reset = firePrior(run, actor, "reset-own-draft", user)
                    requireCode(reset.optBoolean("baseline_restored") && reset.optBoolean("local_cleanup"), "FIRE_RESET_INVALID")
                    fireRequireValues(defaults)
                    fireAwaitSaved()
                    report.put("baseline_restored", true).put("parameters_seen", 10).put("local_cleanup", true)
                }
            }
            requireCode(!exists { visible(it) && it.isPassword }, "FIRE_LOGIN_FALLBACK")
            report.put("stage", "passed")
            // This describes local preferences only. The host still owns session logout and backend checks.
            if (op in setOf("baseline", "reset-own-draft", "verify-clean")) report.put("local_cleanup_pending", false)
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure))
            throw AssertionError(safeFailure(failure))
        } finally { recordUiFlags(report); writeReport(run, phase, report) }
    }

    private fun fireChooserVisible(requirePreview: Boolean): Boolean {
        // Query only fixed chooser title/domain matches, never enumerate recipients or contacts.
        for (window in automation.windows) {
            val root = window.root ?: continue
            try {
                if (root.packageName?.toString() !in setOf("android", "com.android.intentresolver")) continue
                fun fixedMatch(query: String, matches: (String) -> Boolean): Boolean {
                    val found = root.findAccessibilityNodeInfosByText(query).orEmpty()
                    return try { found.any { visible(it) && matches(it.text?.toString().orEmpty()) } }
                    finally { found.forEach { it.recycle() } }
                }
                val title = fixedMatch("Share FIRE configuration") { it == "Share FIRE configuration" }
                val preview = !requirePreview || fixedMatch("savely.cc") {
                    it == "savely.cc" || it == "www.savely.cc" ||
                        it.startsWith("https://savely.cc/fire/standard?") || it.startsWith("https://www.savely.cc/fire/standard?")
                }
                if (title && preview) return true
            } finally { root.recycle() }
        }
        return false
    }

    private fun fireRequireLogin(loginRun: String, user: String) {
        val login = readJson("release-qualification-$loginRun-couple-login.json", 16_384)
        requireCode(login.optString("run_id") == loginRun && login.optString("user_id") == user &&
            login.optString("phase") == "couple-login" && login.optString("stage") == "passed" &&
            login.optInt("target_code") == 18 && login.optString("target_package") == TARGET &&
            login.optBoolean("settings_identity") && login.optBoolean("profile_read") && login.optBoolean("english_selected"),
            "FIRE_LOGIN_INVALID")
    }

    private fun firePrior(run: String, actor: String, op: String, user: String): JSONObject {
        val phase = "fire-$actor-$op"
        val prior = readJson("release-qualification-$run-$phase.json", 16_384)
        requireCode(prior.optString("run_id") == run && prior.optString("user_id") == user &&
            prior.optString("phase") == phase && prior.optString("operation") == op && prior.optString("stage") == "passed" &&
            prior.optInt("target_code") == 18 && prior.optString("target_package") == TARGET &&
            prior.optBoolean("settings_identity") && prior.optBoolean("profile_read"), "FIRE_PRIOR_INVALID")
        fireRequireLogin(prior.getString("login_run_id"), user)
        return prior
    }

    private fun fireOwnedResetBasis(run: String, actor: String, user: String, actual: Map<String, String>): String {
        val phase = "fire-$actor-prepare-draft"
        val reserved = readJson("release-qualification-$run-$phase-reservation.json", 4_096)
        requireCode(reserved.optString("run_id") == run && reserved.optString("user_id") == user &&
            reserved.optString("operation") == "prepare-draft" && reserved.optString("stage") == "reserved", "FIRE_DRAFT_RESERVATION_INVALID")
        val login = reserved.getString("login_run_id")
        fireRequireLogin(login, user)
        val draft = fireDraftValues(user)
        var before = fireDefaultValues().toMap()
        var matched: String? = null
        var count = 0
        var gap = false
        for ((index, entry) in draft.entries.withIndex()) {
            val name = "release-qualification-$run-$phase-field-$index.json"
            if (!privateFile(name).exists()) { gap = true; continue }
            requireCode(!gap, "FIRE_DRAFT_CHECKPOINT_GAP")
            val step = readJson(name, 8_192)
            val after = before + (entry.key to entry.value)
            requireCode(step.optString("run_id") == run && step.optString("user_id") == user &&
                step.optString("login_run_id") == login && step.optString("stage") == "reserved" &&
                step.optString("field") == entry.key && fireKnownJson(step.optJSONObject("before"), before) &&
                fireKnownJson(step.optJSONObject("after"), after), "FIRE_DRAFT_CHECKPOINT_INVALID")
            // Only the last reserved edit's exact before/after can explain an interrupted form.
            matched = if (actual == before || actual == after) "reserved-prefix" else null
            before = after
            count++
        }
        if (count == 0 && actual == fireDefaultValues() &&
            !privateFile("release-qualification-$run-$phase-before-invalid.json").exists()) return "baseline-unchanged"
        requireCode(count > 0, "FIRE_DRAFT_CHECKPOINT_MISSING")
        val invalidName = "release-qualification-$run-$phase-before-invalid.json"
        if (privateFile(invalidName).exists()) {
            val invalid = readJson(invalidName, 8_192)
            requireCode(count == 10 && invalid.optString("run_id") == run && invalid.optString("user_id") == user &&
                invalid.optString("login_run_id") == login && invalid.optString("stage") == "reserved" &&
                fireKnownJson(invalid.optJSONObject("valid_draft"), draft), "FIRE_INVALID_RESERVATION_INVALID")
            if (actual == draft + ("expenses" to "abc")) matched = "reserved-invalid-draft"
        }
        return matched ?: throw IllegalStateException("FIRE_DRAFT_NOT_OWNED")
    }

    private fun fireKnownJson(json: JSONObject?, expected: Map<String, String>): Boolean = json != null &&
        json.keys().asSequence().toSet() == expected.keys && expected.all { (key, value) -> json.opt(key) == value }

    private fun fireDefaultValues() = linkedMapOf("age" to "30", "retirement" to "55", "savings" to "100000",
        "contribution" to "24000", "income" to "72000", "expenses" to "48000", "return" to "7",
        "inflation" to "3", "withdrawal" to "4", "parttime" to "20000")

    private fun fireDraftValues(user: String): Map<String, String> = if (user == QA_USER)
        linkedMapOf("age" to "31", "retirement" to "62", "savings" to "125000", "contribution" to "24000",
            "income" to "60000", "expenses" to "30000", "return" to "7", "inflation" to "3", "withdrawal" to "4", "parttime" to "6000")
        else linkedMapOf("age" to "35", "retirement" to "60", "savings" to "50000", "contribution" to "12000",
            "income" to "60000", "expenses" to "36000", "return" to "6", "inflation" to "2", "withdrawal" to "4", "parttime" to "12000")

    private fun fireFieldLabels() = linkedMapOf("age" to "Current age", "retirement" to "Retirement age",
        "savings" to "Current savings (€)", "contribution" to "Annual contribution (€)", "income" to "Annual net income (€)",
        "expenses" to "Annual expenses (€)", "return" to "Nominal return (%)", "inflation" to "Inflation (%)",
        "withdrawal" to "Annual withdrawal (%)", "parttime" to "Annual part-time income (€)")

    private fun fireTop() {
        hideKeyboardIfShown()
        repeat(32) {
            if (exists { label(it, setOf("Standard", "Barista")) && visible(it) && it.isEnabled && hasClickableAncestor(it) }) return
            val container = find { it.isScrollable && visible(it) && it.className?.toString() != "android.widget.EditText" }
            val bounds = container?.let { Rect().also(it::getBoundsInScreen) }
            val moved = container?.let { try { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } finally { it.recycle() } } ?: false
            if (!moved) swipe(true, bounds)
            SystemClock.sleep(250)
        }
        throw IllegalStateException("FIRE_TABS_MISSING")
    }

    private fun fireTab(tab: String) {
        fireTop()
        val node = seekLabel(setOf(tab), "FIRE_TAB_MISSING", true, true)
        if (selectedControl(node)) node.recycle() else clickControl(node, "FIRE_TAB_REJECTED")
        await("FIRE_TAB_NOT_SELECTED") { selected(tab) }
    }

    private fun fireReadValues(): Map<String, String> {
        fireTab("Barista")
        return fireFieldLabels().mapValues { (_, field) ->
            val labelNode = fireLocateField(field)
            var current: AccessibilityNodeInfo? = labelNode
            var value: String? = null
            try {
                repeat(5) {
                    val node = current ?: return@repeat
                    if (value == null && node.isEditable && node.isEnabled && visible(node)) value = node.text?.toString()
                    current = if (value == null) node.parent else null
                    node.recycle()
                }
            } finally { current?.recycle() }
            val raw = value ?: throw IllegalStateException("FIRE_FIELD_MISSING")
            requireCode(raw.length <= 128, "FIRE_FIELD_VALUE_INVALID")
            if (raw == "abc") raw else runCatching { java.math.BigDecimal(raw).stripTrailingZeros().toPlainString() }
                .getOrElse { throw IllegalStateException("FIRE_FIELD_VALUE_INVALID") }
        }
    }

    private fun fireRequireValues(expected: Map<String, String>) {
        requireCode(fireReadValues() == expected, "FIRE_PARAMETERS_MISMATCH")
    }

    private fun fireLocateField(field: String): AccessibilityNodeInfo {
        repeat(96) {
            find { it.isEditable && it.isEnabled && visible(it) && it.hintText?.toString() == field }?.let { return it }
            find { visible(it) && it.isEnabled && label(it, setOf(field)) }?.let { return it }
            val container = find { it.isScrollable && visible(it) && it.className?.toString() != "android.widget.EditText" }
                ?: throw IllegalStateException("FIRE_FORM_SCROLL_MISSING")
            val bounds = try { Rect().also(container::getBoundsInScreen) } finally { container.recycle() }
            // Small gestures cannot skip an uncomposed LazyColumn field between whole-page scrolls.
            val height = minOf(bounds.height() / 2, (48 * instrumentation.context.resources.displayMetrics.density).toInt())
            requireCode(height > 0, "FIRE_FORM_SCROLL_MISSING")
            swipe(false, Rect(bounds.left, bounds.centerY() - height / 2, bounds.right, bounds.centerY() + height / 2), avoidFling = true)
            SystemClock.sleep(100)
        }
        throw IllegalStateException("FIRE_FIELD_LABEL_MISSING")
    }

    private fun fireAwaitSaved() {
        fireTop()
        seekLabel(setOf("Parameters saved on this device for this account."), "FIRE_SAVED_STATUS_MISSING", true).recycle()
    }

    private fun fireMetric(titleText: String, amount: String) {
        fireTop()
        val labelNode = seekLabel(setOf(titleText), "FIRE_METRIC_LABEL_MISSING", true)
        val expected = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.ITALY).format(java.math.BigDecimal(amount))
        var parent: AccessibilityNodeInfo? = labelNode.parent
        labelNode.recycle()
        var paired = false
        try {
            repeat(4) {
                val node = parent ?: return@repeat
                if (node.childCount in 2..3) {
                    var title = false
                    var value = false
                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index) ?: continue
                        try {
                            title = title || label(child, setOf(titleText))
                            value = value || label(child, setOf(expected))
                        } finally { child.recycle() }
                    }
                    paired = paired || title && value
                }
                parent = if (paired) null else node.parent
                node.recycle()
            }
        } finally { parent?.recycle() }
        requireCode(paired, "FIRE_METRIC_CARD_MISMATCH")
        seekLabel(setOf(expected), "FIRE_METRIC_VALUE_MISSING", true).recycle()
    }


    /** V11: fixed QA A, one host-adopted row per table; no production storage or API access. */
    @Test fun qualifyReservedFumoForm() {
        val run = preflight(18)
        requireCode(dedicatedQaAccount().first == QA_USER, "FUMO_ACCOUNT_INVALID")
        val op = InstrumentationRegistry.getArguments().getString("fumoOperation").orEmpty()
        val allowed = setOf("cbd-read-cancel", "cbd-preserve-end", "cbd-clear-end", "cbd-complete", "cbd-delete-cancel", "cbd-delete",
            "thc-read-cancel", "thc-clear-end", "thc-complete", "thc-delete",
            "liquid-read-cancel", "liquid-clear-end", "liquid-complete", "liquid-delete")
        requireCode(op in allowed, "FUMO_OPERATION_INVALID")
        val phase = "fumo-$op"
        requireNewNavigationReport(run, phase)
        requireNewNavigationReport(run, "$phase-reservation")
        val name = "release-qualification-$run-$phase-input.json"
        val input = readJson(name, 4_096)
        requireCode(input.keys().asSequence().toSet() == setOf("run_id", "user_id", "operation", "login_run_id", "day", "row_id") &&
            input.optString("run_id") == run && input.optString("user_id") == QA_USER && input.optString("operation") == op,
            "FUMO_INPUT_INVALID")
        val loginRun = input.optString("login_run_id")
        requireCode(runCatching { UUID.fromString(loginRun).toString() == loginRun && loginRun != run &&
            UUID.fromString(input.optString("row_id")).toString() == input.optString("row_id") }.getOrDefault(false),
            "FUMO_REFERENCE_INVALID")
        fireRequireLogin(loginRun, QA_USER)
        val day = runCatching { java.time.LocalDate.parse(input.optString("day")) }.getOrNull()
        requireCode(day != null && day.toString() == input.optString("day") && java.time.LocalDate.now() == day &&
            java.time.LocalDate.now(java.time.ZoneId.of("Europe/Rome")) == day && day.minusDays(20).year == day.year,
            "FUMO_DEVICE_DAY_MISMATCH")
        val arrival = requireNotNull(day).minusDays(20)
        val finish = arrival.plusDays(10)
        val prefix = op.substringBefore('-')
        val operation = op.substringAfter('-')
        val tab = when (prefix) { "cbd" -> "CBD"; "thc" -> "THC"; else -> "Liquid" }
        writeReport(run, "$phase-reservation", JSONObject(input.toString()).put("stage", "reserved"))
        requireCode(privateFile(name).delete(), "FUMO_INPUT_CONSUMPTION_FAILED")
        val report = baseReport(run, 18, phase).put("operation", op).put("login_run_id", loginRun)
            .put("row_id", input.optString("row_id")).put("day", day.toString()).put("mutation_attempted", false)
            .put("cleanup_pending", true)
        try {
            report.put("pid", launch())
            financeOpenProfile()
            requireEmail(QA_EMAIL)
            awaitProfileRead()
            report.put("settings_identity", true).put("profile_read", true)
            financeEnter("Smoking")
            fumoTop()
            val control = seekLabel(setOf(tab), "FUMO_TAB_MISSING", true, true)
            if (selectedControl(control)) control.recycle() else clickControl(control, "FUMO_TAB_REJECTED")
            await("FUMO_TAB_NOT_SELECTED") { selected(tab) }
            fumoTop()
            await("FUMO_DATA_NOT_READY") { exists { label(it, setOf("Add")) && visible(it) && it.isEnabled && hasClickableAncestor(it) } }
            seekLabel(setOf("Arrival/purchase date (YYYY-MM-DD): $arrival"), "FUMO_RECORD_DATE_MISSING", true).recycle()
            if (operation == "read-cancel") {
                val detail = when (prefix) {
                    "cbd" -> "20 days · 2,00/day · 1,00\u00a0€/day · 30,00\u00a0€/month"
                    "thc" -> "11 days · -/day · 1,00\u00a0€/day · 30,00\u00a0€/month"
                    else -> "- days · 0,00/day · -/day · -/month"
                }
                seekLabel(setOf(detail), "FUMO_LEGACY_DETAIL_MISMATCH", true).recycle()
                report.put("legacy_detail_seen", true)
            }
            if (operation in setOf("delete", "delete-cancel")) {
                clickLabel(setOf("Delete"), "FUMO_DELETE_MISSING", true)
                await("FUMO_DELETE_DIALOG_MISSING") { hasText("Delete this record?") }
                if (operation == "delete-cancel") {
                    clickLabel(setOf("Cancel"), "FUMO_CANCEL_MISSING")
                    await("FUMO_CANCEL_NOT_CONFIRMED") { !hasText("Delete this record?") }
                    report.put("cancelled_without_write", true)
                } else {
                    fumoBeforeSubmit(run, phase, report)
                    clickLabel(setOf("Delete"), "FUMO_DELETE_CONFIRM_MISSING")
                    await("FUMO_DELETE_NOT_CONFIRMED") { !hasText("Delete this record?") }
                    fumoTop()
                    seekLabel(setOf("No items. Add the first record."), "FUMO_EMPTY_LIST_MISSING", true).recycle()
                    report.put("row_removed", true)
                }
            } else {
                clickLabel(setOf("Edit"), "FUMO_EDIT_MISSING", true)
                await("FUMO_EDITOR_MISSING") { hasText("Save") && hasText("Cost (€)") }
                val endField = "End date (YYYY-MM-DD) (optional)"
                if (operation == "read-cancel") {
                    financeSetField("Cost (€)", "99")
                    hideKeyboardIfShown()
                    clickLabel(setOf("Cancel"), "FUMO_CANCEL_MISSING")
                    await("FUMO_CANCEL_NOT_CONFIRMED") { !hasText("Save") }
                    report.put("cancelled_without_write", true)
                } else {
                    when (operation) {
                        "preserve-end" -> financeSetField("Cost (€)", "22")
                        "clear-end" -> financeSetField(endField, "")
                        "complete" -> financeSetField(endField, finish.toString())
                        else -> throw IllegalStateException("FUMO_OPERATION_INVALID")
                    }
                    hideKeyboardIfShown()
                    fumoBeforeSubmit(run, phase, report)
                    clickLabel(setOf("Save"), "FUMO_SAVE_MISSING")
                    await("FUMO_SAVE_NOT_CONFIRMED") { !hasText("Save") && !hasText("Saving…") }
                    fumoTop()
                    await("FUMO_RELOAD_NOT_CONFIRMED") { exists { label(it, setOf("Add")) && visible(it) && it.isEnabled && hasClickableAncestor(it) } }
                    if (operation == "preserve-end")
                        seekLabel(setOf("11 days · 2,00/day · 2,00\u00a0€/day · 60,00\u00a0€/month"), "FUMO_RECALCULATION_MISMATCH", true).recycle()
                    if (operation == "complete") {
                        val daily = if (prefix == "cbd") "2,20" else "1,10"
                        val monthly = if (prefix == "cbd") "66,00" else "33,00"
                        seekLabel(setOf("10 days · 2,20/day · $daily\u00a0€/day · $monthly\u00a0€/month"), "FUMO_RECALCULATION_MISMATCH", true).recycle()
                    }
                    report.put("form_closed", true)
                }
            }
            requireCode(!exists { it.isPassword && visible(it) }, "FUMO_LOGIN_FALLBACK")
            report.put("stage", "passed")
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure))
            throw AssertionError(safeFailure(failure))
        } finally { recordUiFlags(report); writeReport(run, phase, report) }
    }

    private fun fumoBeforeSubmit(run: String, phase: String, report: JSONObject) {
        writeReport(run, "$phase-before-submit", JSONObject().put("run_id", run).put("user_id", QA_USER).put("stage", "before-submit"))
        report.put("mutation_attempted", true)
    }

    private fun fumoTop() {
        hideKeyboardIfShown()
        repeat(32) {
            if (exists { label(it, setOf("Add", "Update")) && visible(it) }) return
            val container = find { it.isScrollable && visible(it) && it.className?.toString() != "android.widget.EditText" }
            val bounds = container?.let { Rect().also(it::getBoundsInScreen) }
            val moved = container?.let { try { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } finally { it.recycle() } } ?: false
            if (!moved) swipe(true, bounds)
            SystemClock.sleep(250)
        }
        throw IllegalStateException("FUMO_HEADER_MISSING")
    }

    /** V06: only native form controls mutate data; the host owns exact-row adoption and reconciliation. */
    @Test fun qualifyReservedPortfolioForm() {
        val run = preflight(18)
        requireCode(dedicatedQaAccount().first == QA_USER, "PORTFOLIO_ACCOUNT_INVALID")
        val op = InstrumentationRegistry.getArguments().getString("portfolioOperation").orEmpty()
        requireCode(op in setOf("create-cancel", "create-single", "edit-cancel", "edit-single", "single-close-cancel",
            "close-single", "create-first", "create-second", "group-close-cancel", "close-group", "delete-cancel",
            "delete-single", "delete-first", "delete-second", "closed-read", "prices"), "PORTFOLIO_OPERATION_INVALID")
        val phase = "portfolio-$op"
        requireNewNavigationReport(run, phase)
        requireNewNavigationReport(run, "$phase-reservation")
        val inputName = "release-qualification-$run-$phase-input.json"
        val input = readJson(inputName, 4_096)
        requireCode(input.keys().asSequence().toSet() == setOf("run_id", "user_id", "operation", "login_run_id", "day") &&
            input.optString("run_id") == run && input.optString("user_id") == QA_USER && input.optString("operation") == op,
            "PORTFOLIO_INPUT_INVALID")
        val loginRun = input.optString("login_run_id")
        requireCode(runCatching { UUID.fromString(loginRun).toString() == loginRun }.getOrDefault(false) && loginRun != run,
            "PORTFOLIO_LOGIN_REFERENCE_INVALID")
        val login = readJson("release-qualification-$loginRun-couple-login.json", 16_384)
        requireCode(login.optString("run_id") == loginRun && login.optString("user_id") == QA_USER &&
            login.optInt("target_code") == 18 && login.optString("target_package") == TARGET &&
            login.optString("phase") == "couple-login" && login.optString("stage") == "passed" &&
            login.optBoolean("settings_identity") && login.optBoolean("profile_read") && login.optBoolean("english_selected"),
            "PORTFOLIO_LOGIN_INVALID")
        val day = runCatching { java.time.LocalDate.parse(input.optString("day")) }.getOrNull()
        requireCode(day != null && day.toString() == input.optString("day") && java.time.LocalDate.now() == day &&
            java.time.LocalDate.now(java.time.ZoneId.of("Europe/Rome")) == day, "PORTFOLIO_DEVICE_DAY_MISMATCH")
        val today = requireNotNull(day)
        val marker = "android-portfolio-form-$run"
        val single = "$marker-single"
        val first = "$marker-first"
        val second = "$marker-second"
        writeReport(run, "$phase-reservation", input.put("stage", "reserved"))
        requireCode(privateFile(inputName).delete(), "PORTFOLIO_INPUT_CONSUMPTION_FAILED")
        val report = baseReport(run, 18, phase).put("login_run_id", loginRun).put("operation", op)
            .put("day", today.toString()).put("mutation_attempted", false).put("cleanup_pending", true)
        try {
            report.put("pid", launch())
            financeOpenProfile()
            requireEmail(QA_EMAIL)
            awaitProfileRead()
            report.put("settings_identity", true).put("profile_read", true)
            financeEnter("Portfolio")
            portfolioTop()
            await("PORTFOLIO_READ_NOT_READY") {
                exists { label(it, setOf("Refresh data")) && visible(it) && hasClickableAncestor(it) }
            }
            when (op) {
                "create-cancel", "create-single", "create-first", "create-second", "edit-cancel", "edit-single" -> {
                    val editing = op.startsWith("edit-")
                    portfolioClosed(false)
                    portfolioTop()
                    if (editing) clickLabel(setOf("Edit $single"), "PORTFOLIO_EDIT_MISSING", true)
                    else clickLabel(setOf("New purchase"), "PORTFOLIO_CREATE_MISSING")
                    await("PORTFOLIO_EDITOR_MISSING") { hasText(if (editing) "Edit purchase" else "New purchase") }
                    if (!editing) {
                        val name = when (op) { "create-first" -> first; "create-second" -> second; else -> single }
                        financeSetField("Name", name)
                        seekLabel(setOf("Type: Stocks"), "PORTFOLIO_STOCK_TYPE_MISSING", true).recycle()
                        financeCurrencyEur()
                        financeSetField("Optional symbol", "AAPL")
                        financeSetField("Quantity", if (op == "create-second") "3" else "2")
                        financeSetField("Purchase price per unit", "0.01")
                        financeSetField("Optional current price", "0.02")
                        seekLabel(setOf("Purchase: $today"), "PORTFOLIO_PURCHASE_DAY_MISMATCH", true).recycle()
                        financeSetField("Optional notes", marker)
                    } else financeSetField("Optional current price", if (op == "edit-cancel") "0.99" else "0.03")
                    if (op.endsWith("cancel")) {
                        hideKeyboardIfShown()
                        clickLabel(setOf("Cancel"), "PORTFOLIO_CANCEL_MISSING")
                        await("PORTFOLIO_CANCEL_NOT_CONFIRMED") { portfolioEditorClosed() }
                        report.put("dialog_closed", true)
                    } else {
                        portfolioSubmit(run, phase, report, "Save") { portfolioEditorClosed() }
                        report.put("form_closed", true)
                    }
                }
                "single-close-cancel", "close-single" -> {
                    portfolioClosed(false)
                    seekLabel(setOf(single), "PORTFOLIO_SINGLE_MISSING", true).recycle()
                    clickLabel(setOf("Close position"), "PORTFOLIO_SINGLE_CLOSE_MISSING", true)
                    await("PORTFOLIO_CLOSE_DIALOG_MISSING") { hasText("Close $single") }
                    seekLabel(setOf("Closing applies to this purchase of 2 units."), "PORTFOLIO_CLOSE_QUANTITY_MISMATCH", true).recycle()
                    portfolioSaleDay(today, report)
                    financeSetField("Sale price per unit in EUR", if (op == "single-close-cancel") "0.99" else "0.03")
                    if (op == "single-close-cancel") {
                        hideKeyboardIfShown()
                        clickLabel(setOf("Cancel"), "PORTFOLIO_CANCEL_MISSING")
                        await("PORTFOLIO_CANCEL_NOT_CONFIRMED") { !hasText("Close $single") }
                        report.put("dialog_closed", true)
                    } else {
                        portfolioSubmit(run, phase, report, "Confirm closing") { !hasText("Close $single") && !hasText("Saving…") }
                        report.put("form_closed", true)
                    }
                }
                "group-close-cancel", "close-group" -> {
                    portfolioClosed(false)
                    portfolioTop()
                    clickLabel(setOf("Close all purchases"), "PORTFOLIO_GROUP_CLOSE_MISSING", true)
                    await("PORTFOLIO_GROUP_DIALOG_MISSING") { hasText("Close $first") || hasText("Close $second") }
                    seekLabel(setOf("2 purchases, totalling 5 units, will be closed. Each purchase will receive the same sale date and unit price."),
                        "PORTFOLIO_GROUP_QUANTITY_MISMATCH", true).recycle()
                    portfolioSaleDay(today, report)
                    financeSetField("Sale price per unit in EUR", if (op == "group-close-cancel") "0.99" else "0.03")
                    if (op == "group-close-cancel") {
                        hideKeyboardIfShown()
                        clickLabel(setOf("Cancel"), "PORTFOLIO_CANCEL_MISSING")
                        await("PORTFOLIO_CANCEL_NOT_CONFIRMED") { !hasText("Close $first") && !hasText("Close $second") }
                        report.put("dialog_closed", true)
                    } else {
                        portfolioSubmit(run, phase, report, "Confirm closing all purchases") {
                            !hasText("Close $first") && !hasText("Close $second") && !hasText("Saving…")
                        }
                        report.put("form_closed", true)
                    }
                }
                "delete-cancel", "delete-single", "delete-first", "delete-second" -> {
                    val name = when (op) { "delete-first" -> first; "delete-second" -> second; else -> single }
                    portfolioClosed(true)
                    clickLabel(setOf("Delete $name"), "PORTFOLIO_DELETE_MISSING", true)
                    await("PORTFOLIO_DELETE_DIALOG_MISSING") { hasText("Delete $name?") }
                    if (op == "delete-cancel") {
                        clickLabel(setOf("Cancel"), "PORTFOLIO_CANCEL_MISSING")
                        await("PORTFOLIO_CANCEL_NOT_CONFIRMED") { !hasText("Delete $name?") }
                        report.put("dialog_closed", true)
                    } else {
                        financeDelete(run, phase, report)
                        report.put("form_closed", true)
                    }
                }
                "closed-read" -> {
                    portfolioClosed(true)
                    for (name in listOf(single, first, second)) {
                        portfolioTop()
                        seekLabel(setOf(name), "PORTFOLIO_CLOSED_PURCHASE_MISSING", true).recycle()
                    }
                    report.put("closed_markers_seen", 3)
                }
                "prices" -> {
                    portfolioTop()
                    portfolioBeforeSubmit(run, phase, report)
                    clickLabel(setOf("Refresh prices"), "PORTFOLIO_PRICES_CONTROL_MISSING", true)
                    portfolioTop()
                    await("PORTFOLIO_PRICES_NOT_CONFIRMED") {
                        if (hasText("Retry") || hasText("Changes are blocked until the data refresh succeeds."))
                            throw IllegalStateException("PORTFOLIO_PRICES_FAILED")
                        if (exists { visible(it) && it.text?.toString()?.startsWith("Update available from ") == true }) true
                        else {
                            val container = find {
                                val bounds = Rect().also(it::getBoundsInScreen)
                                it.isScrollable && visible(it) && bounds.height() > bounds.width() &&
                                    it.className?.toString() != "android.widget.EditText"
                            }
                            val moved = container?.let {
                                try { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                                finally { it.recycle() }
                            } ?: false
                            // A newly loaded cooldown may be above a retained scroll position.
                            if (!moved) portfolioTop()
                            false
                        }
                    }
                    portfolioTop()
                    requireCode(!hasText("Retry") && !hasText("Changes are blocked until the data refresh succeeds."), "PORTFOLIO_PRICES_FAILED")
                    report.put("cooldown_visible", true)
                }
            }
            requireCode(!exists { it.isPassword && visible(it) }, "PORTFOLIO_LOGIN_FALLBACK")
            report.put("stage", "passed")
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure))
            throw AssertionError(safeFailure(failure))
        } finally { recordUiFlags(report); writeReport(run, phase, report) }
    }

    private fun portfolioTop() {
        hideKeyboardIfShown()
        repeat(24) {
            if (hasText("Refresh data")) return
            val container = find { it.isScrollable && visible(it) && it.className?.toString() != "android.widget.EditText" }
            val bounds = container?.let { Rect().also(it::getBoundsInScreen) }
            val moved = container?.let { try { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } finally { it.recycle() } } ?: false
            if (!moved) swipe(true, bounds)
            SystemClock.sleep(250)
        }
        throw IllegalStateException("PORTFOLIO_HEADER_MISSING")
    }

    private fun portfolioClosed(closed: Boolean) {
        portfolioTop()
        val label = if (closed) "Closed" else "Open"
        val control = seekLabel(setOf(label), "PORTFOLIO_STATUS_FILTER_MISSING", true, true)
        if (selectedControl(control)) control.recycle() else clickControl(control, "PORTFOLIO_STATUS_FILTER_REJECTED")
        await("PORTFOLIO_STATUS_FILTER_NOT_SELECTED") { selected(label) }
    }

    private fun portfolioSaleDay(day: java.time.LocalDate, report: JSONObject) {
        val node = seekLabel(setOf("Sale: $day", "Vendita: $day"), "PORTFOLIO_SALE_DAY_MISMATCH", true)
        try { report.put("sale_date_english", label(node, setOf("Sale: $day"))) }
        finally { node.recycle() }
    }

    private fun portfolioEditorClosed() = !hasText("Edit purchase") && !hasText("Save") && !hasText("Saving…") &&
        !exists { visible(it) && it.className?.toString() == "android.widget.EditText" }

    private fun portfolioBeforeSubmit(run: String, phase: String, report: JSONObject) {
        writeReport(run, "$phase-before-submit", JSONObject().put("run_id", run).put("user_id", QA_USER).put("stage", "before-submit"))
        report.put("mutation_attempted", true)
    }

    private fun portfolioSubmit(run: String, phase: String, report: JSONObject, button: String, finished: () -> Boolean) {
        hideKeyboardIfShown()
        portfolioBeforeSubmit(run, phase, report)
        clickLabel(setOf(button), "PORTFOLIO_SUBMIT_MISSING")
        await("PORTFOLIO_SUBMIT_NOT_CONFIRMED", finished)
    }

    /** V04: each invocation consumes one reservation; server evidence is collected by the host. */
    @Test fun qualifyReservedRecurringForm() {
        val run = preflight(16)
        requireCode(dedicatedQaAccount().first == QA_USER, "RECURRING_ACCOUNT_INVALID")
        val op = InstrumentationRegistry.getArguments().getString("recurringOperation").orEmpty()
        requireCode(op in setOf("create-paused", "edit-amount", "resume", "process-create", "reset-due",
            "process-dedup", "pause", "paused-process", "calendar", "calendar-after-process",
            "calendar-after-dedup", "calendar-paused", "empty-process", "delete-cancel"), "RECURRING_OPERATION_INVALID")
        val phase = "recurring-$op"
        requireNewNavigationReport(run, phase)
        requireNewNavigationReport(run, "$phase-reservation")
        val inputName = "release-qualification-$run-$phase-input.json"
        val input = readJson(inputName, 4_096)
        requireCode(input.keys().asSequence().toSet() == setOf("run_id", "user_id", "operation", "login_run_id", "day") &&
            input.optString("run_id") == run && input.optString("user_id") == QA_USER && input.optString("operation") == op,
            "RECURRING_INPUT_INVALID")
        val loginRun = input.optString("login_run_id")
        requireCode(runCatching { UUID.fromString(loginRun).toString() == loginRun }.getOrDefault(false) && loginRun != run,
            "RECURRING_LOGIN_REFERENCE_INVALID")
        val login = readJson("release-qualification-$loginRun-couple-login.json", 16_384)
        requireCode(login.optString("run_id") == loginRun && login.optString("user_id") == QA_USER &&
            login.optInt("target_code") == 16 && login.optString("stage") == "passed" &&
            login.optString("target_package") == TARGET && login.optBoolean("settings_identity"), "RECURRING_LOGIN_INVALID")
        val day = runCatching { java.time.LocalDate.parse(input.optString("day")) }.getOrNull()
        requireCode(day != null && day.toString() == input.optString("day") &&
            java.time.LocalDate.now(java.time.ZoneId.of("Europe/Rome")) == day && java.time.LocalDate.now() == day,
            "RECURRING_DEVICE_DAY_MISMATCH")
        val today = requireNotNull(day)
        val marker = "android-recurring-form-$run"
        writeReport(run, "$phase-reservation", input.put("stage", "reserved"))
        requireCode(privateFile(inputName).delete(), "RECURRING_INPUT_CONSUMPTION_FAILED")
        val report = baseReport(run, 16, phase).put("login_run_id", loginRun).put("operation", op)
            .put("day", today.toString()).put("mutation_attempted", false).put("cleanup_pending", true)
        try {
            report.put("pid", launch())
            financeOpenProfile()
            requireEmail(QA_EMAIL)
            awaitProfileRead()
            report.put("settings_identity", true)
            financeEnter("Recurring expenses")
            recurringTop()
            await("RECURRING_READ_NOT_READY") {
                exists { label(it, setOf("Refresh data")) && visible(it) && hasClickableAncestor(it) }
            }
            when (op) {
                "create-paused", "edit-amount", "reset-due" -> {
                    if (op == "create-paused") clickLabel(setOf("New recurring expense"), "RECURRING_CREATE_MISSING")
                    else clickLabel(setOf("Edit $marker"), "RECURRING_EDIT_MISSING", true)
                    await("RECURRING_EDITOR_MISSING") { hasText(if (op == "create-paused") "New item" else "Edit item") }
                    if (op == "create-paused") {
                        financeSetField("Name", marker)
                        financeSetField("Amount", "0.01")
                        financeCurrencyEur()
                        financeSelect("Frequency: Monthly", "Weekly")
                        financeSetField("Every how many weeks", "1")
                        seekLabel(setOf("Active recurring expense"), "RECURRING_ACTIVE_FIELD_MISSING", true).recycle()
                        val toggle = find { it.isCheckable && it.isEnabled && visible(it) }
                            ?: throw IllegalStateException("RECURRING_ACTIVE_SWITCH_MISSING")
                        requireCode(toggle.isChecked, "RECURRING_DEFAULT_ACTIVE_INVALID")
                        clickControl(toggle, "RECURRING_ACTIVE_SWITCH_REJECTED")
                    } else if (op == "edit-amount") financeSetField("Amount", "0.02")
                    else recurringResetDate(today)
                    financeSubmit(run, phase, report)
                    report.put("form_closed", true)
                }
                "resume", "pause" -> {
                    val desired = op == "resume"
                    val toggle = seekLabel(setOf("Enable or pause $marker"), "RECURRING_TOGGLE_MISSING", true, true)
                    requireCode(toggle.isCheckable && toggle.isChecked != desired, "RECURRING_TOGGLE_STATE_INVALID")
                    writeReport(run, "$phase-before-submit", JSONObject().put("run_id", run).put("user_id", QA_USER).put("stage", "before-submit"))
                    report.put("mutation_attempted", true)
                    clickControl(toggle, "RECURRING_TOGGLE_REJECTED")
                    await("RECURRING_TOGGLE_NOT_CONFIRMED") {
                        exists { label(it, setOf("Enable or pause $marker")) && it.isCheckable && it.isChecked == desired }
                    }
                    report.put("active", desired)
                }
                "process-create", "process-dedup", "paused-process", "empty-process" -> {
                    val created = if (op == "process-create") 1 else 0
                    val existing = if (op == "process-dedup") 1 else 0
                    val advanced = if (op in setOf("process-create", "process-dedup")) 1 else 0
                    val expected = "Processing complete: $created transactions created, $existing already present, $advanced due dates updated."
                    writeReport(run, "$phase-before-submit", JSONObject().put("run_id", run).put("user_id", QA_USER).put("stage", "before-submit"))
                    report.put("mutation_attempted", true)
                    clickLabel(setOf("Process now"), "RECURRING_PROCESS_MISSING", true)
                    recurringTop()
                    await("RECURRING_PROCESS_RESULT_MISMATCH") { hasText(expected) }
                    report.put("created", created).put("existing", existing).put("advanced", advanced)
                }
                "delete-cancel" -> {
                    clickLabel(setOf("Delete $marker"), "RECURRING_DELETE_MISSING", true)
                    await("RECURRING_DELETE_DIALOG_MISSING") { hasText("Delete") && hasText("Cancel") }
                    clickLabel(setOf("Cancel"), "RECURRING_CANCEL_MISSING")
                    await("RECURRING_CANCEL_NOT_CONFIRMED") { !hasText("Delete") }
                    report.put("dialog_closed", true)
                }
                else -> {
                    val projected = if (op == "calendar") today else today.plusDays(7)
                    val count = if (op == "calendar-paused") 0 else 1
                    clickLabel(setOf("Today"), "RECURRING_CALENDAR_TODAY_MISSING", true)
                    if (java.time.YearMonth.from(projected) != java.time.YearMonth.from(today))
                        clickLabel(setOf("Next month"), "RECURRING_CALENDAR_NEXT_MISSING", true)
                    clickLabel(setOf("$projected: $count due items"), "RECURRING_CALENDAR_COUNT_MISMATCH", true)
                    seekLabel(setOf("Due on $projected"), "RECURRING_CALENDAR_SELECTED_MISSING", true).recycle()
                    if (count == 0) seekLabel(setOf("Nothing is due on the selected day"), "RECURRING_PAUSED_PROJECTION_PRESENT", true).recycle()
                    else {
                        val occurrence = "$marker · 0,02\u00a0€"
                        seekLabel(setOf(occurrence), "RECURRING_CALENDAR_AMOUNT_MISMATCH", true).recycle()
                    }
                    report.put("calendar_date", projected.toString()).put("calendar_count", count)
                }
            }
            report.put("stage", "passed")
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure))
            throw AssertionError(safeFailure(failure))
        } finally { writeReport(run, phase, report) }
    }

    private fun recurringTop() {
        hideKeyboardIfShown()
        repeat(24) {
            if (hasText("Refresh data")) return
            val container = find { it.isScrollable && visible(it) && it.className?.toString() != "android.widget.EditText" }
            val bounds = container?.let { Rect().also(it::getBoundsInScreen) }
            val moved = container?.let { try { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } finally { it.recycle() } } ?: false
            if (!moved) swipe(true, bounds)
            SystemClock.sleep(250)
        }
        throw IllegalStateException("RECURRING_HEADER_MISSING")
    }

    private fun recurringResetDate(day: java.time.LocalDate) {
        clickLabel(setOf("Next due date: ${day.plusDays(7)}"), "RECURRING_DATE_CONTROL_MISSING", true)
        if (java.time.YearMonth.from(day) != java.time.YearMonth.from(day.plusDays(7)))
            clickLabel(setOf("Previous month"), "RECURRING_DATE_PREVIOUS_MISSING")
        val dateNode = find { visible(it) && it.isEnabled && it.text?.toString() == day.dayOfMonth.toString() &&
            it.contentDescription?.toString()?.contains(day.year.toString()) == true }
            ?: throw IllegalStateException("RECURRING_DATE_DAY_MISSING")
        clickControl(dateNode, "RECURRING_DATE_DAY_REJECTED")
        clickLabel(setOf("OK"), "RECURRING_DATE_OK_MISSING")
        await("RECURRING_DATE_VALUE_MISMATCH") { hasText("Next due date: $day") }
    }

    /** One reserved operation per invocation; the host reads the owner ledger before and after every write. */
    @Test fun qualifyReservedFinanceForm() {
        val run = preflight(15)
        requireCode(dedicatedQaAccount().first == QA_USER, "FINANCE_ACCOUNT_INVALID")
        val args = InstrumentationRegistry.getArguments()
        val op = args.getString("financeOperation").orEmpty()
        val allowed = setOf("expense-category-create", "income-category-create", "expense-create", "income-create",
            "expense-update", "income-update", "budget-create", "budget-update", "expense-category-rename",
            "expense-category-soft-delete", "expense-delete", "income-delete", "budget-delete",
            "income-category-soft-delete", "cancel-category", "cancel-expense-delete", "dashboard-check", "filter-check")
        requireCode(op in allowed, "FINANCE_OPERATION_INVALID")
        val phase = "finance-$op"
        requireNewNavigationReport(run, phase)
        requireNewNavigationReport(run, "$phase-reservation")
        val inputName = "release-qualification-$run-$phase-input.json"
        val input = readJson(inputName, 4_096)
        requireCode(input.keys().asSequence().toSet() == setOf("run_id", "user_id", "operation", "login_run_id", "day", "renamed") &&
            input.optString("run_id") == run && input.optString("user_id") == QA_USER && input.optString("operation") == op,
            "FINANCE_INPUT_INVALID")
        val loginRun = input.optString("login_run_id")
        requireCode(runCatching { UUID.fromString(loginRun).toString() == loginRun }.getOrDefault(false) && loginRun != run,
            "FINANCE_LOGIN_REFERENCE_INVALID")
        val login = readJson("release-qualification-$loginRun-couple-login.json", 16_384)
        requireCode(login.optString("run_id") == loginRun && login.optString("user_id") == QA_USER &&
            login.optInt("target_code") == 15 && login.optString("stage") == "passed" &&
            login.optString("target_package") == TARGET && login.optBoolean("settings_identity"), "FINANCE_LOGIN_INVALID")
        val day = input.optString("day")
        requireCode(runCatching { java.time.LocalDate.parse(day).toString() == day }.getOrDefault(false), "FINANCE_DAY_INVALID")
        requireCode(java.time.LocalDate.now(java.time.ZoneId.of("Europe/Rome")).toString() == day &&
            java.time.LocalDate.now().toString() == day, "FINANCE_DEVICE_DAY_MISMATCH")
        requireCode(input.get("renamed") is Boolean, "FINANCE_RENAMED_INVALID")
        val marker = "android-finance-form-$run"
        val expense = "$marker-expense"
        val income = "$marker-income"
        val expenseCategory = expense + if (input.getBoolean("renamed")) "-renamed" else ""
        writeReport(run, "$phase-reservation", input.put("stage", "reserved"))
        requireCode(privateFile(inputName).delete(), "FINANCE_INPUT_CONSUMPTION_FAILED")
        val report = baseReport(run, 15, phase).put("login_run_id", loginRun).put("operation", op)
            .put("day", day).put("mutation_attempted", false).put("cleanup_pending", true)
        try {
            report.put("pid", launch())
            report.put("keyboard_before_settings", automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD })
            financeOpenProfile()
            requireEmail(QA_EMAIL)
            report.put("settings_identity", true)
            awaitProfileRead()
            report.put("profile_read", true)
            financeEnter("Dashboard")
            financeTop()
            await("FINANCE_DASHBOARD_MISSING") { hasDashboardContent() }
            report.put("dashboard_returned", true)
            when {
                op == "dashboard-check" -> {
                    await("FINANCE_DASHBOARD_MISSING") { hasDashboardContent() }
                    report.put("dashboard_content", true)
                    seekLabel(setOf("Statistics EUR"), "FINANCE_DASHBOARD_STATISTICS_MISSING", true).recycle()
                    await("FINANCE_DASHBOARD_TOTALS_MISMATCH") { financeDashboardTotals(report) }
                    report.put("eur_income", "0.06").put("eur_expense", "0.02").put("eur_balance", "0.04")
                    // Both isolated descriptions must be visible among the refreshed recent transactions.
                    seekLabel(setOf(income), "FINANCE_DASHBOARD_INCOME_MISSING", true).recycle()
                    seekLabel(setOf(expense), "FINANCE_DASHBOARD_EXPENSE_MISSING", true).recycle()
                    report.put("recent_expense", true).put("recent_income", true)
                }
                op.contains("category") -> {
                    financeEnter("Categories")
                    financeTop()
                    await("FINANCE_CATEGORIES_MISSING") { hasText("Expense categories") }
                    if (op.endsWith("create") || op == "cancel-category") {
                        clickLabel(setOf("New category"), "FINANCE_CREATE_CONTROL_MISSING", true)
                        await("FINANCE_EDITOR_MISSING") { hasText("New item") }
                        if (op == "cancel-category") {
                            financeSetField("Name", "$marker-cancelled")
                            hideKeyboardIfShown()
                            clickLabel(setOf("Cancel"), "FINANCE_CANCEL_MISSING")
                        } else {
                            val name = if (op.startsWith("income")) income else expense
                            financeSetField("Name", name)
                            hideKeyboardIfShown()
                            if (op.startsWith("income")) financeSelect("Type: Expense", "Income")
                            financeSubmit(run, phase, report)
                            seekLabel(setOf("Edit $name"), "FINANCE_CREATED_CATEGORY_MISSING", true).recycle()
                            seekLabel(setOf("Delete $name"), "FINANCE_CREATED_CATEGORY_MISSING", true).recycle()
                        }
                    } else if (op == "expense-category-rename") {
                        clickLabel(setOf("Edit $expense"), "FINANCE_EDIT_CONTROL_MISSING", true)
                        await("FINANCE_EDITOR_MISSING") { hasText("Edit item") }
                        financeSetField("Name", "$expense-renamed")
                        financeSubmit(run, phase, report)
                        seekLabel(setOf("Edit $expense-renamed"), "FINANCE_UPDATED_CATEGORY_MISSING", true).recycle()
                        seekLabel(setOf("Delete $expense-renamed"), "FINANCE_UPDATED_CATEGORY_MISSING", true).recycle()
                    } else {
                        val name = if (op.startsWith("income")) income else expenseCategory
                        clickLabel(setOf("Delete $name"), "FINANCE_DELETE_CONTROL_MISSING", true)
                        await("FINANCE_DELETE_CONFIRMATION_MISSING") { hasText("Delete $name?") }
                        financeDelete(run, phase, report)
                    }
                }
                op.startsWith("budget") -> {
                    financeEnter("Budget")
                    financeTop()
                    seekLabel(setOf("New budget"), "FINANCE_BUDGET_MISSING", true).recycle()
                    if (op == "budget-create") {
                        clickLabel(setOf("New budget"), "FINANCE_CREATE_CONTROL_MISSING")
                        await("FINANCE_EDITOR_MISSING") { hasText("New item") }
                        financeSelect("Category: Select", expenseCategory)
                        financeSetField("Amount", "3.00")
                        financeSubmit(run, phase, report)
                    } else if (op == "budget-update") {
                        clickLabel(setOf("Edit $expenseCategory"), "FINANCE_EDIT_CONTROL_MISSING", true)
                        await("FINANCE_EDITOR_MISSING") { hasText("Edit item") }
                        financeSetField("Amount", "4.00")
                        financeSubmit(run, phase, report)
                    } else {
                        // This category was soft deleted while its budget still existed.
                        seekLabel(setOf("Removed category"), "FINANCE_REMOVED_CATEGORY_MISSING", true).recycle()
                        clickLabel(setOf("Delete Removed category"), "FINANCE_DELETE_CONTROL_MISSING")
                        await("FINANCE_DELETE_CONFIRMATION_MISSING") { hasText("Delete budget?") }
                        financeDelete(run, phase, report)
                        report.put("removed_category_display", true)
                    }
                }
                else -> {
                    financeEnter("Transactions")
                    financeTop()
                    await("FINANCE_TRANSACTIONS_MISSING") { hasText("New transaction") }
                    if (op == "filter-check") {
                        financeSetField("Search description or category", marker)
                        hideKeyboardIfShown()
                        financeSelect("Category: All", expenseCategory)
                        clickLabel(setOf("Expenses"), "FINANCE_TYPE_FILTER_MISSING", true)
                        seekLabel(setOf("1 transactions"), "FINANCE_COMBINED_FILTER_MISSING", true).recycle()
                        seekLabel(setOf(expense), "FINANCE_FILTERED_EXPENSE_MISSING", true).recycle()
                        requireCode(!hasText(income), "FINANCE_FILTERED_INCOME_VISIBLE")
                        report.put("search_type_category_combined", true)
                        // Restore filters before later phases; no write is performed.
                        financeTop()
                        financeSetField("Search description or category", "")
                        hideKeyboardIfShown()
                        financeSelect("Category: $expenseCategory", "All")
                        clickLabel(setOf("All types"), "FINANCE_TYPE_FILTER_MISSING", true)
                    } else if (op.endsWith("create")) {
                        clickLabel(setOf("New transaction"), "FINANCE_CREATE_CONTROL_MISSING")
                        await("FINANCE_EDITOR_MISSING") { hasText("New item") }
                        val isIncome = op.startsWith("income")
                        financeSetField("Amount", if (isIncome) "0.05" else "0.01")
                        hideKeyboardIfShown()
                        financeCurrencyEur()
                        if (isIncome) financeSelect("Type: Expense", "Income")
                        financeSelect("Category: Uncategorized", if (isIncome) income else expenseCategory)
                        financeSetField("Optional description", if (isIncome) income else expense)
                        hideKeyboardIfShown()
                        seekLabel(setOf("Date: $day"), "FINANCE_DATE_MISSING", true).recycle()
                        financeSubmit(run, phase, report)
                    } else if (op.endsWith("update")) {
                        val isIncome = op.startsWith("income")
                        financeTransactionsReady(report)
                        clickLabel(setOf("Edit ${if (isIncome) income else expense}"), "FINANCE_EDIT_CONTROL_MISSING", true)
                        await("FINANCE_EDITOR_MISSING") { hasText("Edit item") }
                        financeSetField("Amount", if (isIncome) "0.06" else "0.02")
                        financeSubmit(run, phase, report)
                    } else {
                        val name = if (op.startsWith("income")) income else expense
                        clickLabel(setOf("Delete $name"), "FINANCE_DELETE_CONTROL_MISSING", true)
                        await("FINANCE_DELETE_CONFIRMATION_MISSING") { hasText("Delete $name?") }
                        if (op == "cancel-expense-delete") clickLabel(setOf("Cancel"), "FINANCE_CANCEL_MISSING")
                        else financeDelete(run, phase, report)
                    }
                }
            }
            await("FINANCE_DIALOG_NOT_CLOSED") { !hasText("New item") && !hasText("Edit item") &&
                !hasText("Delete $expense?") && !hasText("Delete $income?") &&
                !hasText("Delete $expenseCategory?") && !hasText("Delete budget?") &&
                !exists { it.isPassword && visible(it) } }
            report.put("stage", "passed").put("identity_preserved", true)
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure))
            throw AssertionError(safeFailure(failure))
        } finally {
            recordUiFlags(report)
            val controls = JSONObject()
            for (text in listOf("Dashboard", "Settings", "Open menu", "Modules")) {
                val node = find { label(it, setOf(text)) && visible(it) }
                var parent = node
                val chain = org.json.JSONArray()
                try {
                    repeat(8) {
                        val current = parent ?: return@repeat
                        val clickableBefore = current.isClickable
                        val refreshed = current.refresh()
                        chain.put(JSONObject().put("refresh", refreshed).put("clickable_before_refresh", clickableBefore)
                            .put("clickable", current.isClickable).put("enabled", current.isEnabled)
                            .put("selected", current.isSelected || current.isCheckable && current.isChecked)
                            .put("click_action", current.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                            .put("visible", visible(current)).put("drawer_pane", Build.VERSION.SDK_INT >= 28 && current.paneTitle?.toString() == "Navigation menu")
                            .put("bounds", Rect().also(current::getBoundsInScreen).toShortString()))
                        parent = current.parent
                        current.recycle()
                    }
                } finally { parent?.recycle() }
                controls.put(text, chain)
            }
            report.put("fixed_control_flags", controls)
            val root = automation.rootInActiveWindow
            val dashboard = find { label(it, setOf("Dashboard")) }
            try {
                report.put("active_root_is_target", root?.packageName?.toString() == TARGET)
                    .put("active_root_bounds", root?.let { Rect().also(it::getBoundsInScreen).toShortString() } ?: JSONObject.NULL)
                    .put("dashboard_label_bounds", dashboard?.let { Rect().also(it::getBoundsInScreen).toShortString() } ?: JSONObject.NULL)
                    .put("keyboard_after_operation", automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD })
            } finally { root?.recycle(); dashboard?.recycle() }
            writeReport(run, phase, report)
        }
    }

    private fun financeSubmit(run: String, phase: String, report: JSONObject) {
        hideKeyboardIfShown()
        writeReport(run, "$phase-before-submit", JSONObject().put("run_id", run).put("user_id", QA_USER).put("stage", "before-submit"))
        report.put("mutation_attempted", true)
        clickLabel(setOf("Save"), "FINANCE_SAVE_MISSING")
        await("FINANCE_SAVE_NOT_CONFIRMED") { !hasText("New item") && !hasText("Edit item") && !hasText("Saving…") }
    }

    @Test fun recoverFailedFinanceFormLogout() {
        val run = preflight(15)
        requireCode(dedicatedQaAccount().first == QA_USER, "FINANCE_ACCOUNT_INVALID")
        val args = InstrumentationRegistry.getArguments()
        val failedRun = args.getString("financeFailedRun").orEmpty()
        val op = args.getString("financeOperation").orEmpty()
        requireCode(runCatching { UUID.fromString(failedRun).toString() == failedRun }.getOrDefault(false) && run != failedRun &&
            op.matches(Regex("[a-z-]{4,40}")), "FINANCE_RECOVERY_REFERENCE_INVALID")
        val failedPhase = when (op) { "login" -> "couple-login"; "logout" -> "couple-logout"; else -> "finance-$op" }
        val failure = readJson("release-qualification-$failedRun-$failedPhase.json", 16_384)
        requireCode(failure.optString("stage") == "failed" && failure.optString("run_id") == failedRun &&
            failure.optString("user_id") == QA_USER && failure.optInt("target_code") == 15 &&
            failure.optString("target_package") == TARGET && failure.optString("phase") == failedPhase &&
            (op in setOf("login", "logout") || failure.optString("operation") == op) &&
            failure.optBoolean("cleanup_pending"), "FINANCE_RECOVERY_FAILURE_INVALID")
        if (op == "login") {
            requireCode(failure.optBoolean("input_consumed") && failure.optBoolean("login_form_submitted"), "FINANCE_LOGIN_INVALID")
        } else {
            val loginRun = if (op == "logout") failedRun else failure.optString("login_run_id")
            val login = readJson("release-qualification-$loginRun-couple-login.json", 16_384)
            requireCode(login.optString("stage") == "passed" && login.optInt("target_code") == 15 &&
                login.optString("user_id") == QA_USER && login.optBoolean("settings_identity"), "FINANCE_LOGIN_INVALID")
        }
        requireNewNavigationReport(run, "finance-recovery")
        requireNewNavigationReport(run, "finance-recovery-reservation")
        writeReport(run, "finance-recovery-reservation", JSONObject().put("run_id", run).put("failed_run_id", failedRun)
            .put("operation", op).put("user_id", QA_USER).put("stage", "reserved"))
        val report = baseReport(run, 15, "finance-recovery").put("failed_run_id", failedRun)
        try {
            report.put("pid", launch())
            hideKeyboardIfShown()
            if (hasText("New item") || hasText("Edit item") || hasText("Delete budget?") ||
                exists { it.text?.toString()?.startsWith("Delete android-finance-form-$failedRun-") == true && visible(it) }) {
                clickLabel(setOf("Cancel"), "FINANCE_RECOVERY_CANCEL_MISSING")
            }
            if (navigationDrawerOpen()) navigationBack()
            financeOpenProfile()
            requireEmail(QA_EMAIL)
            awaitProfileRead()
            requireCode(logoutQa(report, QA_EMAIL), "FINANCE_RECOVERY_LOGOUT_FAILED")
            report.put("stage", "passed").put("cleanup_pending", false)
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("failure_code", safeFailure(failure)).put("cleanup_pending", true)
            throw AssertionError(safeFailure(failure))
        } finally { writeReport(run, "finance-recovery", report) }
    }

    private fun financeDelete(run: String, phase: String, report: JSONObject) {
        writeReport(run, "$phase-before-submit", JSONObject().put("run_id", run).put("user_id", QA_USER).put("stage", "before-submit"))
        report.put("mutation_attempted", true)
        clickLabel(setOf("Delete"), "FINANCE_DELETE_CONFIRM_MISSING")
        await("FINANCE_DELETE_NOT_CONFIRMED") { !hasText("Delete") && !hasText("Deleting…") }
    }

    private fun financeOpenProfile() {
        financeEnter("Settings")
        scrollAccountToTop()
        if (!hasProfileContent()) clickLabel(setOf("Account"), "ACCOUNT_PANE_MISSING")
        await("ACCOUNT_CONTENT_MISSING") { hasProfileContent() }
    }

    private fun financeTop() {
        hideKeyboardIfShown()
        repeat(24) {
            if (hasDashboardContent() || hasText("Search description or category") || hasText("New category") || hasText("New budget")) return
            val container = find { it.isScrollable && visible(it) && it.className?.toString() != "android.widget.EditText" }
            val bounds = container?.let { Rect().also(it::getBoundsInScreen) }
            val moved = container?.let { try { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } finally { it.recycle() } } ?: false
            if (!moved) swipe(true, bounds)
            SystemClock.sleep(250)
        }
        throw IllegalStateException("FINANCE_TOP_MISSING")
    }

    private fun financeTransactionsReady(report: JSONObject) {
        fun header() {
            repeat(24) {
                if (hasText("Refresh data")) return
                val container = find {
                    val bounds = Rect().also(it::getBoundsInScreen)
                    it.isScrollable && visible(it) && bounds.height() > bounds.width()
                }
                val bounds = container?.let { Rect().also(it::getBoundsInScreen) }
                val moved = container?.let {
                    try { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } finally { it.recycle() }
                } ?: false
                if (!moved) swipe(true, bounds)
                SystemClock.sleep(250)
            }
            throw IllegalStateException("FINANCE_REFRESH_CONTROL_MISSING")
        }
        header()
        // New transaction is available during reload; Refresh is enabled only after the read completes.
        await("FINANCE_TRANSACTIONS_NOT_READY") {
            exists { label(it, setOf("Refresh data")) && visible(it) && hasClickableAncestor(it) }
        }
        report.put("transaction_read_ready", true)
        seekLabel(setOf("2 transactions"), "FINANCE_EXPECTED_TRANSACTION_COUNT_MISSING", true).recycle()
        report.put("expected_transaction_count", 2)
        header()
    }

    private fun financeSelect(control: String, option: String) {
        hideKeyboardIfShown()
        clickLabel(setOf(control), "FINANCE_SELECTOR_MISSING", true)
        clickLabel(setOf(option), "FINANCE_OPTION_MISSING", true)
    }

    private fun financeEnter(title: String) {
        hideKeyboardIfShown()
        // An already selected tab can omit ACTION_CLICK; the verified toolbar title permits staying on that route.
        if (!navigationDrawerOpen() && navigationTitle(title)) return
        clickLabel(setOf("Open menu"), "FINANCE_MENU_MISSING")
        await("NAVIGATION_DRAWER_MISSING") { navigationDrawerOpen() }
        navigationDrawerToTop()
        navigationChooseDrawerRoute(title)
    }

    private fun financeCurrencyEur() {
        repeat(24) {
            val control = find { it.text?.toString()?.matches(Regex("Currency: [A-Z]{3}")) == true && visible(it) && hasClickableAncestor(it) }
            if (control != null) {
                if (control.text?.toString() == "Currency: EUR") { control.recycle(); return }
                clickControl(control, "FINANCE_CURRENCY_MISSING")
                clickLabel(setOf("EUR"), "FINANCE_CURRENCY_EUR_MISSING", true)
                return
            }
            val container = find { it.isScrollable && visible(it) && it.className?.toString() != "android.widget.EditText" }
            val bounds = container?.let { Rect().also(it::getBoundsInScreen) }
            val moved = container?.let { try { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } finally { it.recycle() } } ?: false
            if (!moved) swipe(false, bounds)
            SystemClock.sleep(250)
        }
        throw IllegalStateException("FINANCE_CURRENCY_MISSING")
    }

    private fun financeDashboardTotals(report: JSONObject): Boolean {
        val labelNode = find { label(it, setOf("Statistics EUR")) && visible(it) } ?: return false
        val labelBounds = Rect().also(labelNode::getBoundsInScreen)
        val parent = labelNode.parent
        // Compose can expose the content description as a leaf inside the actual card.
        val useParent = labelNode.childCount == 0 && labelNode.text.isNullOrEmpty() && parent != null &&
            parent.packageName?.toString() == TARGET && parent.windowId == labelNode.windowId &&
            Rect().also(parent::getBoundsInScreen) == labelBounds
        report.put("dashboard_description_leaf", useParent)
        val card = if (useParent) {
            labelNode.recycle()
            requireNotNull(parent)
        } else {
            parent?.recycle()
            labelNode
        }
        report.put("dashboard_container_child_count", card.childCount)
        if (card.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id }) {
            card.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            card.refresh()
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(card)
        val text = StringBuilder()
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited++ < 200) {
                val node = queue.removeLast()
                text.append(node.text?.toString().orEmpty()).append('\n')
                for (index in node.childCount - 1 downTo 0) node.getChild(index)?.let(queue::addLast)
                node.recycle()
            }
        } finally { while (queue.isNotEmpty()) queue.removeFirst().recycle() }
        val normalized = text.toString().replace('\u00a0', ' ').replace('\u202f', ' ').replace(Regex("\\s+"), " ")
        val positions = JSONObject()
        for (token in listOf("EUR", "Total balance", "Income", "Expenses", "Savings", "0,04 €", "0,06 €", "0,02 €", "0,00 €")) {
            positions.put(token, normalized.indexOf(token))
        }
        // Fixed tokens and offsets only; never persist raw accessibility text.
        report.put("dashboard_token_offsets", positions).put("dashboard_nodes_read", visited)
        val matched = financeDashboardAmountsMatch(text.toString())
        val balanceVisible = positions.getInt("Total balance") >= 0 && positions.getInt("0,04 €") >= 0
        val expensesVisible = positions.getInt("Expenses") >= 0 && positions.getInt("0,02 €") >= 0
        if (!matched && balanceVisible != expensesVisible && report.optInt("dashboard_scroll_attempts") < 12) {
            val container = find {
                val bounds = Rect().also(it::getBoundsInScreen)
                it.isScrollable && visible(it) && bounds.height() > bounds.width()
            }
            val bounds = container?.let { Rect().also(it::getBoundsInScreen) }
            container?.recycle()
            if (bounds != null) {
                val height = minOf(360, bounds.height() / 2)
                val shortArea = Rect(bounds.left, bounds.centerY() - height / 2, bounds.right, bounds.centerY() + height / 2)
                swipe(!balanceVisible, shortArea, avoidFling = true)
                report.put("dashboard_scroll_attempts", report.optInt("dashboard_scroll_attempts") + 1)
            }
        }
        return matched
    }

    private fun financeDashboardAmountsMatch(text: String): Boolean {
        val normalized = text.replace('\u00a0', ' ').replace('\u202f', ' ')
            .replace(Regex("\\s+"), " ")
        return listOf("Total balance" to "0.04", "Income" to "0.06", "Expenses" to "0.02").all { (label, amount) ->
            val money =
            java.text.NumberFormat.getCurrencyInstance(java.util.Locale.ITALY).apply {
                currency = java.util.Currency.getInstance("EUR")
                minimumFractionDigits = 2; maximumFractionDigits = 2
                roundingMode = java.math.RoundingMode.HALF_UP
            }.format(java.math.BigDecimal(amount)).replace('\u00a0', ' ').replace('\u202f', ' ')
            Regex("(?<![\\p{L}\\p{N}])${Regex.escape(label)}[\\s,]+${Regex.escape(money)}(?![\\p{L}\\p{N}])")
                .containsMatchIn(normalized)
        }
    }

    @Test fun financeDashboardAmountOracleRejectsSwappedNegativeAndSubstringTotals() {
        org.junit.Assert.assertTrue(financeDashboardAmountsMatch("EUR\nTotal balance\n0,04\u00a0€\nIncome\n0,06\u00a0€\nExpenses\n0,02\u00a0€"))
        org.junit.Assert.assertFalse(financeDashboardAmountsMatch("Total balance -0,04 € Income 0,06 € Expenses 0,02 €"))
        org.junit.Assert.assertFalse(financeDashboardAmountsMatch("Total balance 0,04 € Income 0,02 € Expenses 0,06 €"))
        org.junit.Assert.assertFalse(financeDashboardAmountsMatch("Total balance 10,04 € Income 0,06 € Expenses 0,02 €"))
    }

    private fun financeSetField(field: String, value: String) {
        val direct = find { it.isEditable && it.isEnabled && visible(it) && it.hintText?.toString() == field }
        val labelNode = direct ?: seekLabel(setOf(field), "FINANCE_FIELD_LABEL_MISSING", true)
        var candidate: AccessibilityNodeInfo? = null
        try {
            // Compose exposes the floating label inside its editable semantics node.
            var ancestor: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(labelNode)
            repeat(4) {
                val current = ancestor ?: return@repeat
                if (current.isEditable && current.isEnabled && visible(current)) {
                    candidate = AccessibilityNodeInfo.obtain(current)
                    current.recycle(); ancestor = null
                } else {
                    ancestor = current.parent
                    current.recycle()
                }
            }
            ancestor?.recycle()
        } finally { labelNode.recycle() }
        val node = candidate ?: find { it.isEditable && it.isEnabled && visible(it) &&
            (it.hintText?.toString() == field || it.text?.toString() == field || it.contentDescription?.toString() == field) }
            ?: throw IllegalStateException("FINANCE_FIELD_MISSING")
        try {
            requireCode(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }), "FINANCE_FIELD_INPUT_REJECTED")
            await("FINANCE_FIELD_VALUE_MISMATCH") {
                node.refresh() && node.isEditable && (node.text?.toString().orEmpty() == value ||
                    value.isEmpty() && node.text?.toString() == field)
            }
        } finally { node.recycle() }
    }

    private fun dedicatedQaAccount(): Pair<String, String> = when (InstrumentationRegistry.getArguments().getString("releaseQualificationAccount")) {
        "a" -> QA_USER to QA_EMAIL
        "b" -> QA_USER_B to QA_EMAIL_B
        "admin" -> QA_ADMIN_USER to QA_ADMIN_EMAIL
        else -> throw IllegalStateException("QA_ACCOUNT_INVALID")
    }

    @Test fun loginFromFormAndPrepareUpgrade() {
        val run = preflight(10)
        val report = baseReport(run, 10, "phase1")
        var identityConfirmed = false
        var prepared = false
        try {
            report.put("pid", launch())
            await("LOGIN_FORM_MISSING") { editable(password = false) && editable(password = true) }
            requireCode(!hasText("Dashboard"), "PREEXISTING_AUTHENTICATED_UI")
            val credentials = consumeCredentials(run)
            report.put("input_consumed", true)
            setField(password = false, value = credentials.first)
            setField(password = true, value = credentials.second)
            hideKeyboardIfShown()
            clickLabel(setOf("Accedi", "Sign in"), "LOGIN_SUBMIT_MISSING")
            report.put("login_form_submitted", true)
            awaitDashboard()
            report.put("dashboard", true)
            openSettings()
            requireQaEmail()
            awaitProfileRead()
            identityConfirmed = true
            report.put("settings_identity", true).put("profile_read", true)
            clickLabel(setOf("English"), "LANGUAGE_CONTROL_MISSING", scroll = true)
            await("ENGLISH_NOT_SELECTED") { selected("English") && hasText("Language") }
            clickLabel(setOf("Light"), "THEME_CONTROL_MISSING", scroll = true)
            await("LIGHT_NOT_SELECTED") { selected("Light") }
            requireQaEmail(scroll = true)
            report.put("english_selected", true).put("light_selected", true)
                .put("session_retained_for_upgrade", true).put("cleanup_pending", false).put("stage", "prepared")
            prepared = true
        } catch (failure: Throwable) {
            if (report.optBoolean("input_consumed")) recordUiFlags(report)
            report.put("stage", "prepare_failed").put("failure_code", safeFailure(failure))
            throw AssertionError(safeFailure(failure))
        } finally {
            if (!prepared) {
                val loggedOut = identityConfirmed && logoutQa(report)
                report.put("logout_confirmed", loggedOut)
                    .put("cleanup_pending", report.optBoolean("login_form_submitted") && !loggedOut)
            }
            writeReport(run, "phase1", report)
        }
    }

    @Test fun afterUpgradePreservesAuthenticatedSession() {
        val run = preflight(11)
        val report = baseReport(run, 11, "phase2")
        var identityConfirmed = false
        var verified = false
        try {
            val previous = readJson("release-qualification-$run-phase1.json", 16_384)
            requireCode(previous.optString("run_id") == run && previous.optString("user_id") == QA_USER &&
                previous.optString("target_package") == TARGET && previous.optInt("target_code") == 10 &&
                previous.optString("stage") == "prepared" && previous.optBoolean("input_consumed") &&
                previous.optBoolean("settings_identity") && previous.optBoolean("english_selected") &&
                previous.optBoolean("light_selected") && previous.optBoolean("session_retained_for_upgrade"), "UPGRADE_CHECKPOINT_INVALID")
            val appPid = launch()
            report.put("pid", appPid)
            requireCode(previous.optInt("pid") > 0 && previous.getInt("pid") != appPid, "PROCESS_NOT_REPLACED")
            report.put("previous_pid", previous.getInt("pid")).put("new_process", true)
            awaitDashboard()
            requireCode(!editable(password = true), "LOGIN_FORM_AFTER_UPGRADE")
            report.put("dashboard", true).put("no_login_form", true)
            openSettings(englishOnly = true)
            requireQaEmail()
            awaitProfileRead()
            identityConfirmed = true
            report.put("settings_identity", true).put("profile_read", true)
            seekLabel(setOf("English"), "ENGLISH_PREFERENCE_LOST", scroll = true).recycle()
            await("ENGLISH_PREFERENCE_LOST") { selected("English") && hasText("Language") }
            seekLabel(setOf("Light"), "LIGHT_PREFERENCE_LOST", scroll = true).recycle()
            await("LIGHT_PREFERENCE_LOST") { selected("Light") }
            report.put("english_preserved", true).put("light_preserved", true)
            verified = true
        } catch (failure: Throwable) {
            recordUiFlags(report)
            report.put("failure_code", safeFailure(failure))
            throw AssertionError(safeFailure(failure))
        } finally {
            val loggedOut = identityConfirmed && logoutQa(report)
            report.put("logout_confirmed", loggedOut).put("cleanup_pending", !loggedOut)
                .put("stage", if (verified && loggedOut) "passed" else "upgrade_failed")
            writeReport(run, "phase2", report)
            if (verified && !loggedOut) throw AssertionError("QA_LOGOUT_NOT_CONFIRMED")
        }
    }

    private fun preflight(code: Int): String {
        val args = InstrumentationRegistry.getArguments()
        requireCode(args.getString("releaseQualification") == "true", "OPT_IN_REQUIRED")
        val run = args.getString("releaseQualificationRunId") ?: throw IllegalStateException("RUN_REQUIRED")
        requireCode(runCatching { UUID.fromString(run).toString() == run }.getOrDefault(false), "RUN_INVALID")
        requireCode(args.getString("expectedTargetCode") == code.toString(), "EXPECTED_CODE_INVALID")
        requireCode(instrumentation.targetContext.packageName == TEST_PACKAGE && instrumentation.context.packageName == TEST_PACKAGE,
            "PACKAGE_INVALID")
        val manager = instrumentation.targetContext.packageManager
        val target = manager.getPackageInfo(TARGET, 0)
        val actualCode = if (Build.VERSION.SDK_INT >= 28) target.longVersionCode else @Suppress("DEPRECATION") target.versionCode.toLong()
        requireCode(actualCode == code.toLong(), "TARGET_CODE_INVALID")
        requireCode(target.applicationInfo?.flags?.and(ApplicationInfo.FLAG_DEBUGGABLE) == 0, "TARGET_DEBUGGABLE")
        requireCode(manager.getApplicationInfo(TEST_PACKAGE, 0).flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            "TEST_INPUT_STORAGE_UNAVAILABLE")
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        val report = privateFile("release-qualification-$run-phase${if (code == 10) 1 else 2}.json")
        requireCode(!report.exists() && !File(report.path + ".bak").exists() && !File(report.path + ".new").exists(),
            "REPORT_ALREADY_EXISTS")
        return run
    }

    private fun consumeCredentials(run: String, user: String = QA_USER, email: String = QA_EMAIL): Pair<String, String> {
        val input = readJson(INPUT, 8_192)
        requireCode(input.keys().asSequence().toSet() == setOf("run_id", "user_id", "email", "password") &&
            input.optString("run_id") == run && input.optString("user_id") == user && input.optString("email") == email,
            "INPUT_OWNER_INVALID")
        requireCode(input.get("password") is String, "INPUT_PASSWORD_INVALID")
        val password = input.getString("password")
        requireCode(password.length in 40..256 && password.none { it.isISOControl() }, "INPUT_PASSWORD_INVALID")
        val inputFile = privateFile(INPUT)
        requireCode(inputFile.delete() && !inputFile.exists(), "INPUT_CONSUMPTION_FAILED")
        return email to password
    }

    private fun launch(): Int {
        val started = readShell("am start -W -n $TARGET/.MainActivity", 4_096)
        requireCode(started.lineSequence().any { it.trim() == "Status: ok" }, "TARGET_LAUNCH_FAILED")
        val pid = readShell("pidof $TARGET", 128).trim().toIntOrNull()
        requireCode(pid != null && pid > 0 && pid != Process.myPid(), "TARGET_PID_INVALID")
        return requireNotNull(pid)
    }

    private fun openSettings(englishOnly: Boolean = false) {
        // Saved navigation/pane state may reopen Data. Always navigate from a confirmed Dashboard.
        awaitDashboard()
        clickNavigation(if (englishOnly) setOf("Settings") else setOf("Impostazioni", "Settings"),
            if (englishOnly) "ENGLISH_NAVIGATION_MISSING" else "SETTINGS_NAVIGATION_MISSING")
        await("SETTINGS_PANES_MISSING") {
            hasText("Account") && (hasText("Security") || hasText("Sicurezza") || hasText("Data") || hasText("Dati"))
        }
        scrollAccountToTop()
        // An already selected chip may not expose OnClick; only the actual Profile content permits skipping it.
        if (!hasProfileContent()) clickLabel(setOf("Account"), "ACCOUNT_PANE_MISSING")
        scrollAccountToTop()
        await("ACCOUNT_CONTENT_MISSING") { hasProfileContent() }
    }

    private fun clickNavigation(labels: Set<String>, code: String) {
        var found: AccessibilityNodeInfo? = null
        await(code) {
            found = find { navigationLabel(it, labels) && it.isEnabled && hasClickableAncestor(it) }
            found != null
        }
        clickControl(requireNotNull(found), code)
    }

    private fun navigationLabel(node: AccessibilityNodeInfo, labels: Set<String>): Boolean {
        if (!label(node, labels) || !visible(node)) return false
        val bounds = Rect().also(node::getBoundsInScreen)
        // The separate harness can have compatibility display metrics different from the target window.
        val root = automation.rootInActiveWindow ?: return false
        val window = try {
            if (root.packageName?.toString() != TARGET) return false
            Rect().also(root::getBoundsInScreen)
        } finally { root.recycle() }
        return !window.isEmpty && bounds.top >= window.top + window.height() * 0.70f
    }

    private fun hasDashboardContent(): Boolean = hasText("Your finances") || hasText("La tua finanza")
    private fun hasProfileContent(): Boolean = hasText("Profile") || hasText("Profilo")

    private fun requireQaEmail(scroll: Boolean = false) {
        if (scroll) scrollAccountToTop()
        requireEmail(QA_EMAIL)
    }

    private fun requireEmail(email: String) = await("QA_IDENTITY_MISSING") { hasText(email) }

    private fun scrollAccountToTop() {
        repeat(64) {
            if (hasProfileContent() || hasText("Two-step verification") || hasText("Verifica in due passaggi") || hasDashboardContent() ||
                hasText("Registration enabled") || hasText("Administrator permissions are required.")) return
            val container = find {
                val bounds = Rect().also(it::getBoundsInScreen)
                it.isScrollable && visible(it) && (it.className?.toString() == "android.widget.ScrollView" || bounds.height() > bounds.width())
            } ?: return
            val bounds = Rect().also(container::getBoundsInScreen)
            val moved = try { container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
                finally { container.recycle() }
            if (!moved) swipe(backwards = true, bounds = bounds)
            SystemClock.sleep(250)
        }
    }

    private fun awaitDashboard() {
        await("DASHBOARD_NAVIGATION_MISSING") { exists { navigationLabel(it, setOf("Dashboard")) } }
        if (!hasDashboardContent() && !navigationTitle("Dashboard")) {
            scrollAccountToTop()
            if (!hasDashboardContent() && !navigationTitle("Dashboard")) clickNavigation(setOf("Dashboard"), "DASHBOARD_NAVIGATION_MISSING")
        }
        scrollAccountToTop()
        await("DASHBOARD_MISSING") { hasDashboardContent() && !editable(password = true) }
    }

    private fun awaitProfileRead() {
        val until = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < until) {
            scrollAccountToTop()
            val node = runCatching { seekLabel(setOf("Salva profilo", "Save profile"), "PROFILE_READ_NOT_CONFIRMED", scroll = true) }.getOrNull()
            if (node != null) { node.recycle(); return }
            SystemClock.sleep(250)
        }
        throw IllegalStateException("PROFILE_READ_NOT_CONFIRMED")
    }

    private fun logoutQa(report: JSONObject, email: String = QA_EMAIL): Boolean = runCatching {
        // Both phases stay on Account after identity confirmation; preferences may scroll Profile away.
        scrollAccountToTop()
        requireCode(hasProfileContent(), "QA_ACCOUNT_CONTENT_MISSING")
        requireEmail(email)
        clickLabel(setOf("Sign out of your account", "Esci dall’account"), "QA_LOGOUT_CONTROL_MISSING", scroll = true)
        await("QA_LOGOUT_NOT_CONFIRMED") { editable(password = false) && editable(password = true) && !hasText("Dashboard") }
        true
    }.getOrElse { failure ->
        report.put("logout_failure_code", safeFailure(failure))
        recordUiFlags(report)
        false
    }

    private fun setField(password: Boolean, value: String) {
        val node = find { it.isEditable && it.isPassword == password && visible(it) }
            ?: throw IllegalStateException("LOGIN_FIELD_MISSING")
        try {
            requireCode(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }), "FIELD_INPUT_REJECTED")
        } finally { node.recycle() }
    }

    private fun editable(password: Boolean): Boolean = exists { it.isEditable && it.isPassword == password && visible(it) }
    private fun hasText(text: String): Boolean = exists { label(it, setOf(text)) && visible(it) }
    private fun selected(text: String): Boolean = exists { label(it, setOf(text)) && visible(it) && selectedControl(it) }

    private fun selectedControl(node: AccessibilityNodeInfo): Boolean {
        // FilterChip exposes its selected state as checked on the clickable control, not its Text.
        fun chosen(control: AccessibilityNodeInfo): Boolean = control.packageName?.toString() == TARGET &&
            control.isEnabled && visible(control) && (control.isSelected || control.isCheckable && control.isChecked)
        if (node.isClickable) return chosen(node)
        var parent = node.parent
        repeat(8) {
            val current = parent ?: return false
            if (!current.refresh()) { current.recycle(); return false }
            val clickable = current.isClickable
            val selected = clickable && chosen(current)
            parent = if (clickable) null else current.parent
            current.recycle()
            // Stop at the first control; an unrelated selected container cannot satisfy this check.
            if (clickable) return selected
        }
        parent?.recycle()
        return false
    }

    private fun clickLabel(labels: Set<String>, code: String, scroll: Boolean = false) {
        clickControl(seekLabel(labels, code, scroll, clickable = true), code)
    }

    private fun clickControl(candidate: AccessibilityNodeInfo, code: String) {
        var node = candidate
        try {
            for (depth in 0 until 8) {
                requireCode(node.refresh(), code)
                if (node.isClickable && node.isEnabled) {
                    requireCode(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), code)
                    return
                }
                val parent = node.parent ?: break
                node.recycle()
                node = parent
            }
            throw IllegalStateException(code)
        } finally { node.recycle() }
    }

    private fun seekLabel(labels: Set<String>, code: String, scroll: Boolean, clickable: Boolean = false): AccessibilityNodeInfo {
        for (attempt in 0..(if (scroll) 64 else 12)) {
            find { label(it, labels) && it.isEnabled && visible(it) && (!clickable || hasClickableAncestor(it)) }?.let { return it }
            // A busy control already in view must become enabled before searching farther down the page.
            if (clickable && exists { label(it, labels) && visible(it) }) {
                SystemClock.sleep(250)
                continue
            }
            if (!scroll) {
                SystemClock.sleep(250)
            } else {
                val offscreen = find { label(it, labels) }
                // Compose can retain an offscreen label with empty bounds; it gives no scroll direction.
                val targetBounds = offscreen?.let { Rect().also(it::getBoundsInScreen) }?.takeUnless { it.isEmpty }
                offscreen?.recycle()
                val container = find {
                    val bounds = Rect().also(it::getBoundsInScreen)
                    it.isScrollable && visible(it) && (it.className?.toString() == "android.widget.ScrollView" || bounds.height() > bounds.width())
                }
                val containerBounds = container?.let { Rect().also(it::getBoundsInScreen) }
                val backwards = targetBounds != null && containerBounds != null &&
                    targetBounds.top < containerBounds.top
                val moved = if (container != null) {
                    try { container.performAction(if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } finally { container.recycle() }
                } else false
                if (!moved) swipe(backwards, containerBounds)
                SystemClock.sleep(250)
            }
        }
        throw IllegalStateException(code)
    }

    private fun label(node: AccessibilityNodeInfo, labels: Set<String>): Boolean =
        node.text?.toString() in labels || node.contentDescription?.toString() in labels

    // A heading can share a button's label; choose only nodes with a real enabled click action.
    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.isEnabled) return true
        var parent = node.parent
        repeat(8) {
            val current = parent ?: return false
            if (!current.refresh()) { current.recycle(); return false }
            val clickable = current.isClickable && current.isEnabled
            parent = if (clickable) null else current.parent
            current.recycle()
            if (clickable) return true
        }
        parent?.recycle()
        return false
    }

    private fun visible(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        return node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0
    }

    private fun exists(predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val node = find(predicate) ?: return false
        node.recycle()
        return true
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        // Re-read the active root and virtual Compose nodes after login replaces its content.
        val active = automation.rootInActiveWindow
        val roots = mutableListOf<AccessibilityNodeInfo>()
        active?.let(roots::add)
        automation.windows.forEach { window ->
            val root = window.root ?: return@forEach
            if (root.windowId == active?.windowId) root.recycle() else roots.add(root)
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addAll(roots)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited++ < 4_096) {
                val node = queue.removeFirst()
                if (!node.refresh()) { node.recycle(); continue }
                if (node.packageName?.toString() == TARGET && predicate(node)) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
                node.recycle()
            }
            return null
        } finally { while (queue.isNotEmpty()) queue.removeFirst().recycle() }
    }

    private fun recordUiFlags(report: JSONObject) {
        // Fixed-label booleans only: never serialize accessibility text or password nodes.
        runCatching {
            report.put("ui_diagnostic", JSONObject()
                .put("dashboard_label_seen", hasText("Dashboard"))
                .put("dashboard_content_seen", hasDashboardContent())
                .put("account_profile_seen", hasProfileContent())
                .put("password_field_seen", editable(password = true))
                .put("settings_navigation_seen", hasText("Settings") || hasText("Impostazioni"))
                .put("qa_identity_seen", hasText(QA_EMAIL)))
        }
    }

    private fun hideKeyboardIfShown() {
        if (automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            val time = SystemClock.uptimeMillis()
            automation.injectInputEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true)
            automation.injectInputEvent(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true)
        }
    }

    private fun swipe(backwards: Boolean, bounds: Rect? = null, avoidFling: Boolean = false) {
        val window = bounds ?: automation.rootInActiveWindow?.let { node ->
            try { Rect().also(node::getBoundsInScreen) } finally { node.recycle() }
        } ?: throw IllegalStateException("SCROLL_WINDOW_MISSING")
        requireCode(!window.isEmpty, "SCROLL_WINDOW_MISSING")
        val start = SystemClock.uptimeMillis()
        val x = window.exactCenterX()
        val from = window.top + window.height() * if (backwards) 0.15f else 0.85f
        val to = window.top + window.height() * if (backwards) 0.85f else 0.15f
        val steps = if (avoidFling) 20 else 12
        for (index in 0..steps) {
            val action = when (index) { 0 -> MotionEvent.ACTION_DOWN; steps -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val time = if (avoidFling) SystemClock.uptimeMillis() else start + index * 20L
            val event = MotionEvent.obtain(start, time, action, x, from + (to - from) * minOf(index, 12) / 12, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { requireCode(automation.injectInputEvent(event, true), "SCROLL_REJECTED") } finally { event.recycle() }
            if (avoidFling && index < steps) SystemClock.sleep(20)
        }
    }

    private fun await(code: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < end) {
            if (predicate()) return
            SystemClock.sleep(250)
        }
        throw IllegalStateException(code)
    }

    private fun baseReport(run: String, code: Int, phase: String): JSONObject = JSONObject()
        .put("run_id", run).put("user_id", QA_USER).put("target_package", TARGET).put("target_code", code)
        .put("phase", phase).put("pid", 0).put("instrumentation_pid", Process.myPid())
        .put("scope", "signed-release-accessibility-black-box-self-instrumented")
        .put("stage", "started")

    private fun writeReport(run: String, phase: String, report: JSONObject) {
        val file = privateFile("release-qualification-$run-$phase.json")
        requireCode(!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists(), "REPORT_ALREADY_EXISTS")
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(report.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (_: Exception) {
            atomic.failWrite(output)
            throw IllegalStateException("REPORT_WRITE_FAILED")
        }
    }

    private fun readJson(name: String, limit: Int): JSONObject {
        val file = privateFile(name)
        requireCode(file.isFile, "PRIVATE_INPUT_MISSING")
        val bytes = file.inputStream().use { readBounded(it, limit) }
        return try { JSONObject(bytes.toString(Charsets.UTF_8)) }
            catch (_: Exception) { throw IllegalStateException("PRIVATE_JSON_INVALID") }
    }

    private fun privateFile(name: String): File {
        requireCode(name.matches(Regex("[a-z0-9.-]+")) && !name.contains(".."), "PRIVATE_NAME_INVALID")
        return File(instrumentation.context.filesDir, name)
    }

    private fun readShell(command: String, limit: Int): String {
        val descriptor = automation.executeShellCommand(command)
        val bytes = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            readBounded(input, limit)
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1_024)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            requireCode(output.size() + count <= limit, "PRIVATE_INPUT_TOO_LARGE")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun requireCode(condition: Boolean, code: String) { if (!condition) throw IllegalStateException(code) }
    private fun safeFailure(failure: Throwable): String = failure.message?.takeIf { it.matches(Regex("[A-Z_]{3,80}")) } ?: "BLACK_BOX_FAILURE"

    companion object {
        private val ADMIN_STAGES = listOf("admin_ready", "permission_cancelled", "permission_changed", "permission_restored",
            "secondary_admin_ready", "primary_role_revoked", "primary_role_denied", "primary_role_restored",
            "primary_reloaded", "secondary_admin_removed", "completed")
        private const val QA_ADMIN_USER = "3afa4bc1-0910-4f18-81b3-9e9f69c65a6e"
        private const val QA_ADMIN_EMAIL = "android-qa-5ef2838c-admin@example.invalid"
        private val SESSION_STAGES = listOf("primary_ready", "secondary_ready", "revocation_cancelled", "secondary_revoked",
            "secondary_refresh_rejected", "survivor_ready", "primary_revoked", "survivor_refreshed", "completed")
        private val MFA_STAGES = listOf("pending_cancel_ready", "pending_cancel_kept", "pending_persisted", "pending_removed",
            "enrollment_ready", "enrollment_wrong", "enrollment_verified", "logged_out", "login_gate_wrong",
            "login_gate_verified", "removal_cancelled", "removal_wrong", "removal_completed", "completed")
        private const val TARGET = "cc.savely.android"
        private const val TEST_PACKAGE = "cc.savely.android.releaseqa"
        private const val INPUT = "release-qualification-input.json"
        private const val QA_USER = "d46cee52-3d3b-45ae-9bc3-c95931a82339"
        private const val QA_EMAIL = "android-qa-5ef2838c-a@example.invalid"
        private const val QA_USER_B = "ed79c6ec-5265-4b8b-9f50-4fc1f296bc5b"
        private const val QA_EMAIL_B = "android-qa-5ef2838c-b@example.invalid"
    }
}
