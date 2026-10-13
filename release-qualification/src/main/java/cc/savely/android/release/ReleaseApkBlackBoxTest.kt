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
        requireCode(code in setOf(11, 12, 13, 14), "EXPECTED_TARGET_CODE_INVALID")
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
        requireCode(code in setOf(11, 12, 13, 14), "EXPECTED_TARGET_CODE_INVALID")
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
            openSettings()
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

    private fun swipe(backwards: Boolean, bounds: Rect? = null) {
        val window = bounds ?: automation.rootInActiveWindow?.let { node ->
            try { Rect().also(node::getBoundsInScreen) } finally { node.recycle() }
        } ?: throw IllegalStateException("SCROLL_WINDOW_MISSING")
        requireCode(!window.isEmpty, "SCROLL_WINDOW_MISSING")
        val start = SystemClock.uptimeMillis()
        val x = window.exactCenterX()
        val from = window.top + window.height() * if (backwards) 0.15f else 0.85f
        val to = window.top + window.height() * if (backwards) 0.85f else 0.15f
        for (index in 0..12) {
            val action = when (index) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(start, start + index * 20L, action, x, from + (to - from) * index / 12, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { requireCode(automation.injectInputEvent(event, true), "SCROLL_REJECTED") } finally { event.recycle() }
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
