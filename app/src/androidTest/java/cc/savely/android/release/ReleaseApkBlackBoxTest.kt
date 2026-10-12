package cc.savely.android.release

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.Base64
import java.util.UUID

/** Opt-in accessibility-only qualification of the installed, signed, non-debuggable target APK. */
@Suppress("DEPRECATION") // recycle still releases accessibility nodes on supported API 26-32.
@RunWith(AndroidJUnit4::class)
class ReleaseApkBlackBoxTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation: UiAutomation get() = instrumentation.uiAutomation

    @Test fun loginFromFormAndPrepareUpgrade() {
        val run = preflight(10)
        val report = baseReport(run, 10, "phase1")
        var identityConfirmed = false
        var prepared = false
        try {
            launch()
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
            report.put("stage", "prepare_failed").put("failure_code", safeFailure(failure))
            throw AssertionError(safeFailure(failure))
        } finally {
            if (!prepared) {
                val loggedOut = identityConfirmed && logoutQa()
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
            requireCode(previous.optInt("pid") > 0 && previous.getInt("pid") != Process.myPid(), "PROCESS_NOT_REPLACED")
            report.put("previous_pid", previous.getInt("pid")).put("new_process", true)
            launch()
            awaitDashboard()
            requireCode(!editable(password = true), "LOGIN_FORM_AFTER_UPGRADE")
            report.put("dashboard", true).put("no_login_form", true)
            clickLabel(setOf("Settings"), "ENGLISH_NAVIGATION_MISSING")
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
            report.put("failure_code", safeFailure(failure))
            throw AssertionError(safeFailure(failure))
        } finally {
            val loggedOut = identityConfirmed && logoutQa()
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
        requireCode(instrumentation.targetContext.packageName == TARGET && instrumentation.context.packageName == TEST_PACKAGE,
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
        requireCode(shell("test ! -e files/release-qualification-$run-phase${if (code == 10) 1 else 2}.json", 256).second == 0,
            "REPORT_ALREADY_EXISTS")
        return run
    }

    private fun consumeCredentials(run: String): Pair<String, String> {
        val input = readJson(INPUT, 8_192)
        requireCode(input.keys().asSequence().toSet() == setOf("run_id", "user_id", "email", "password") &&
            input.optString("run_id") == run && input.optString("user_id") == QA_USER && input.optString("email") == QA_EMAIL,
            "INPUT_OWNER_INVALID")
        requireCode(input.get("password") is String, "INPUT_PASSWORD_INVALID")
        val password = input.getString("password")
        requireCode(password.length in 40..256 && password.none { it.isISOControl() }, "INPUT_PASSWORD_INVALID")
        requireCode(shell("rm -- files/$INPUT", 256).second == 0 &&
            shell("test ! -e files/$INPUT", 256).second == 0, "INPUT_CONSUMPTION_FAILED")
        return QA_EMAIL to password
    }

    private fun launch() {
        instrumentation.targetContext.startActivity(Intent().setClassName(TARGET, "$TARGET.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    private fun openSettings() = clickLabel(setOf("Impostazioni", "Settings"), "SETTINGS_NAVIGATION_MISSING")

    private fun requireQaEmail(scroll: Boolean = false) {
        if (scroll) scrollAccountToTop()
        await("QA_IDENTITY_MISSING") { hasText(QA_EMAIL) }
    }

    private fun scrollAccountToTop() {
        repeat(12) {
            val container = find {
                val bounds = Rect().also(it::getBoundsInScreen)
                it.isScrollable && visible(it) && bounds.height() > bounds.width()
            } ?: return
            val moved = try { container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
                finally { container.recycle() }
            if (!moved) return
            SystemClock.sleep(250)
        }
    }

    private fun awaitDashboard() = await("DASHBOARD_MISSING") { hasText("Dashboard") && !editable(password = true) }

    private fun awaitProfileRead() = await("PROFILE_READ_NOT_CONFIRMED") {
        exists { label(it, setOf("Salva profilo", "Save profile")) && it.isEnabled && visible(it) }
    }

    private fun logoutQa(): Boolean = runCatching {
        openSettings()
        requireQaEmail(scroll = true)
        clickLabel(setOf("Sign out of your account", "Esci dall’account"), "QA_LOGOUT_CONTROL_MISSING", scroll = true)
        await("QA_LOGOUT_NOT_CONFIRMED") { editable(password = false) && editable(password = true) && !hasText("Dashboard") }
        true
    }.getOrDefault(false)

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
    private fun selected(text: String): Boolean = exists { label(it, setOf(text)) && it.isSelected && visible(it) }

    private fun clickLabel(labels: Set<String>, code: String, scroll: Boolean = false) {
        var node = seekLabel(labels, code, scroll)
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

    private fun seekLabel(labels: Set<String>, code: String, scroll: Boolean): AccessibilityNodeInfo {
        for (attempt in 0..12) {
            find { label(it, labels) && it.isEnabled && visible(it) }?.let { return it }
            if (!scroll) {
                SystemClock.sleep(250)
            } else {
                val offscreen = find { label(it, labels) }
                val targetBounds = offscreen?.let { Rect().also(it::getBoundsInScreen) }
                offscreen?.recycle()
                val container = find {
                    val bounds = Rect().also(it::getBoundsInScreen)
                    it.isScrollable && visible(it) && bounds.height() > bounds.width()
                }
                val containerBounds = container?.let { Rect().also(it::getBoundsInScreen) }
                val backwards = targetBounds != null && containerBounds != null &&
                    targetBounds.top < containerBounds.top
                if (container != null) {
                    try { container.performAction(if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } finally { container.recycle() }
                } else swipe(backwards)
                SystemClock.sleep(250)
            }
        }
        throw IllegalStateException(code)
    }

    private fun label(node: AccessibilityNodeInfo, labels: Set<String>): Boolean =
        node.text?.toString() in labels || node.contentDescription?.toString() in labels

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
        val roots = automation.windows.mapNotNull { it.root }.toMutableList()
        if (roots.isEmpty()) automation.rootInActiveWindow?.let(roots::add)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addAll(roots)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited++ < 4_096) {
                val node = queue.removeFirst()
                if (node.packageName?.toString() == TARGET && predicate(node)) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
                node.recycle()
            }
            return null
        } finally { while (queue.isNotEmpty()) queue.removeFirst().recycle() }
    }

    private fun hideKeyboardIfShown() {
        if (automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            val time = SystemClock.uptimeMillis()
            automation.injectInputEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true)
            automation.injectInputEvent(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true)
        }
    }

    private fun swipe(backwards: Boolean) {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val start = SystemClock.uptimeMillis()
        val x = metrics.widthPixels * 0.5f
        val from = metrics.heightPixels * if (backwards) 0.30f else 0.75f
        val to = metrics.heightPixels * if (backwards) 0.75f else 0.30f
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
        .put("phase", phase).put("pid", Process.myPid()).put("scope", "signed-release-accessibility-black-box")
        .put("stage", "started")

    private fun writeReport(run: String, phase: String, report: JSONObject) {
        val encoded = Base64.getEncoder().encodeToString(report.toString().toByteArray(Charsets.UTF_8))
        val name = "release-qualification-$run-$phase.json"
        requireCode(shell("test ! -e files/$name && test ! -e files/$name.tmp && printf %s $encoded | base64 -d > files/$name.tmp && mv -n files/$name.tmp files/$name && test ! -e files/$name.tmp", 256).second == 0,
            "REPORT_WRITE_FAILED")
    }

    private fun readJson(name: String, limit: Int): JSONObject {
        requireCode(name.matches(Regex("[a-z0-9.-]+")), "PRIVATE_NAME_INVALID")
        val result = shell("cat files/$name", limit)
        requireCode(result.second == 0, "PRIVATE_INPUT_MISSING")
        return try { JSONObject(result.first) } catch (_: Exception) { throw IllegalStateException("PRIVATE_JSON_INVALID") }
    }

    private fun shell(command: String, limit: Int): Pair<String, Int> {
        val descriptor = automation.executeShellCommand("run-as $TEST_PACKAGE sh -c '$command; printf \"\\n__STATUS__%s\" \$?' ")
        val bytes = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1_024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                requireCode(output.size() + count <= limit + 64, "PRIVATE_INPUT_TOO_LARGE")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val value = bytes.toString(Charsets.UTF_8)
        val marker = value.lastIndexOf("\n__STATUS__")
        requireCode(marker >= 0, "PRIVATE_COMMAND_FAILED")
        return value.substring(0, marker) to (value.substring(marker + 11).trim().toIntOrNull()
            ?: throw IllegalStateException("PRIVATE_COMMAND_FAILED"))
    }

    private fun requireCode(condition: Boolean, code: String) { if (!condition) throw IllegalStateException(code) }
    private fun safeFailure(failure: Throwable): String = failure.message?.takeIf { it.matches(Regex("[A-Z_]{3,80}")) } ?: "BLACK_BOX_FAILURE"

    companion object {
        private const val TARGET = "cc.savely.android"
        private const val TEST_PACKAGE = "cc.savely.android.test"
        private const val INPUT = "release-qualification-input.json"
        private const val QA_USER = "d46cee52-3d3b-45ae-9bc3-c95931a82339"
        private const val QA_EMAIL = "android-qa-5ef2838c-a@example.invalid"
    }
}
