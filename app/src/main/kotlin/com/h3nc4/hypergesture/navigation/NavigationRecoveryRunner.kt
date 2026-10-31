// Copyright (C) 2026  Henrique Almeida <me@h3nc4.com>
//
// This file is part of HyperGesture.
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.

package com.h3nc4.hypergesture.navigation

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import com.h3nc4.hypergesture.diagnostics.DiagnosticsCollector
import com.h3nc4.hypergesture.diagnostics.FailureLog
import com.h3nc4.hypergesture.setup.setupState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/** The settings recovery maintains; the repair script and the probe are generated from it. */
internal object NavigationSettingTargets {
    val all = listOf(
        SettingTarget("global", "force_fsg_nav_bar", "1"),
        SettingTarget("secure", "navigation_mode", "2"),
    )

    /** Only meaningful, and only written, on HyperOS builds. */
    private val hyperOs = listOf(
        SettingTarget("global", "use_gesture_version_three", "1"),
    )

    fun targets(includeHyperOs: Boolean): List<SettingTarget> =
        if (includeHyperOs) all + hyperOs else all

    fun verified(values: Map<String, String?>, includeHyperOs: Boolean = false): Boolean {
        if (!all.all { values[it.key] == it.value }) return false
        if (!includeHyperOs) return true
        // A build that does not expose the gesture flag reports null. Treat that as not
        // applicable so a ROM that ignores the write cannot trigger a shell every time.
        val flag = hyperOs.single()
        return values[flag.key]?.let { it == flag.value } ?: true
    }

    fun buttonsHidden(values: Map<String, String?>): Boolean =
        values["force_fsg_nav_bar"] == "1" || values["navigation_mode"] == "2"
}

internal data class SettingTarget(val namespace: String, val key: String, val value: String)

/** Reading these needs no permission and no process; that is what keeps probes free. */
internal interface SettingsReader {
    fun navigationValues(): Map<String, String?>
}

internal class ContentResolverSettingsReader(private val context: Context) : SettingsReader {
    override fun navigationValues(): Map<String, String?> = mapOf(
        "force_fsg_nav_bar" to Settings.Global.getString(context.contentResolver, "force_fsg_nav_bar"),
        "navigation_mode" to Settings.Secure.getString(context.contentResolver, "navigation_mode"),
        "use_gesture_version_three" to
            Settings.Global.getString(context.contentResolver, "use_gesture_version_three"),
    )
}

internal data class CommandResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
)

internal interface ShellRunner {
    suspend fun exec(command: String, timeoutSeconds: Long): CommandResult
}

internal sealed interface ShizukuAvailability {
    data object Available : ShizukuAvailability
    data object BinderDown : ShizukuAvailability
    data object PermissionDenied : ShizukuAvailability
}

internal interface ShizukuGateway {
    fun availability(): ShizukuAvailability
}

internal object SystemShizukuGateway : ShizukuGateway {
    override fun availability(): ShizukuAvailability = try {
        when {
            !Shizuku.pingBinder() -> ShizukuAvailability.BinderDown
            Shizuku.isPreV11() -> ShizukuAvailability.PermissionDenied
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                ShizukuAvailability.PermissionDenied
            else -> ShizukuAvailability.Available
        }
    } catch (_: Exception) {
        ShizukuAvailability.BinderDown
    }
}

/**
 * Shizuku runs the script as the shell user. Both pipes are drained on background threads
 * before waiting, so a chatty command cannot fill the pipe buffer and deadlock the wait.
 * [runInterruptible] makes cancellation interrupt the wait, and the child is reaped on the
 * way out either way.
 */
internal class ShizukuShellRunner : ShellRunner {

    override suspend fun exec(command: String, timeoutSeconds: Long): CommandResult =
        runInterruptible(Dispatchers.IO) {
            runCatching { run(command, timeoutSeconds) }.getOrElse { throwable ->
                CommandResult(exitCode = -1, output = throwable.message ?: throwable.javaClass.name)
            }
        }

    private fun run(command: String, timeoutSeconds: Long): CommandResult {
        val process = try {
            newProcessMethod().invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        } catch (t: Throwable) {
            return CommandResult(exitCode = -1, output = t.message ?: t.javaClass.simpleName)
        }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val drainOut = drain(process.inputStream, stdout)
        val drainErr = drain(process.errorStream, stderr)
        // The pipes reach EOF when the shell exits. Asking Shizuku for the process state
        // via waitFor() or isAlive() throws IllegalArgumentException("process hasn't exited")
        // on some versions, so inspect exitValue() safely and fall back to 0.
        try {
            drainOut.join(TimeUnit.SECONDS.toMillis(timeoutSeconds) + DRAIN_JOIN_MS)
            drainErr.join(DRAIN_JOIN_MS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (drainOut.isAlive || drainErr.isAlive) {
            runCatching { process.destroyForcibly() }
            return CommandResult(
                exitCode = -1,
                output = "Timed out after ${timeoutSeconds}s",
                timedOut = true,
            )
        }
        val exitCode = runCatching { process.exitValue() }.getOrDefault(0)
        return CommandResult(
            exitCode = exitCode,
            output = (stdout.toString() + stderr.toString()).trim(),
        )
    }

    private fun drain(stream: InputStream, sink: StringBuilder) =
        Thread({
            runCatching { stream.bufferedReader().use { sink.append(it.readText()) } }
        }, "shizuku-shell-drain").apply {
            isDaemon = true
            start()
        }

    private companion object {
        const val DRAIN_JOIN_MS = 1_000L

        @Volatile
        private var method: Method? = null

        fun newProcessMethod(): Method = method ?: synchronized(this) {
            method ?: Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }.also { method = it }
        }
    }
}

/** Builds the repair script from [NavigationSettingTargets], so the two cannot drift. */
internal object NavigationRecoveryScript {

    private val PACKAGE_NAME = Regex("[A-Za-z0-9_.]+")

    /**
     * @param guard reads the settings first and repairs only on a mismatch; forced runs
     * write unconditionally.
     * @param includeExemptions grants the app-ops and battery exemptions; only forced runs
     * need them, and granting them on every probe would defeat the point of the probe.
     *
     * Shizuku's shell runs as user 0, which is the user these settings target, so the
     * commands need no explicit `--user` flag.
     */
    fun build(packageName: String, includeExemptions: Boolean, hyperOs: Boolean, guard: Boolean): String {
        require(PACKAGE_NAME.matches(packageName)) { "Unexpected package name: $packageName" }

        val targets = NavigationSettingTargets.targets(includeHyperOs = hyperOs)
        val repairs = buildList {
            addAll(targets.map { "settings put ${it.namespace} ${it.key} ${it.value}" })
            if (hyperOs) {
                // The overlay rides along with any target mismatch; it cannot be probed
                // without a shell, but it is re-enabled together with the gesture flag.
                add(
                    """
                    if cmd overlay list 2>/dev/null | grep -q '\[x\] com.android.internal.systemui.navbar.threebutton'; then
                      cmd overlay disable com.android.internal.systemui.navbar.threebutton 2>/dev/null || true
                    fi
                    """.trimIndent(),
                )
            }
        }
        val body = if (guard) {
            val mismatch = targets.joinToString(" || ") {
                "\"\$(settings get ${it.namespace} ${it.key})\" != \"${it.value}\""
            }
            buildString {
                appendLine("if [ $mismatch ]; then")
                repairs.forEach { appendLine(it.prependIndent("  ")) }
                appendLine("fi")
            }
        } else {
            repairs.joinToString("\n")
        }
        val exemptions = if (includeExemptions) {
            buildString {
                appendLine("exemption_failed=0")
                listOf(
                    "appops set $packageName 10008 allow 2>/dev/null",
                    "appops set $packageName 10021 allow 2>/dev/null",
                    "appops set $packageName android:get_usage_stats allow 2>/dev/null",
                    "dumpsys deviceidle whitelist +$packageName 2>/dev/null",
                    "am set-standby-bucket $packageName active 2>/dev/null",
                ).forEach { appendLine("$it || exemption_failed=1") }
                // The grants can fail silently, so success needs a positive marker rather
                // than relying on the exit code alone.
                appendLine("echo \"$EXEMPTIONS_MARKER$((1 - exemption_failed))\"")
                appendLine("exit \$exemption_failed")
            }
        } else {
            ""
        }
        return listOf(body, exemptions).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** True only when the script ran to the end and every grant reported success. */
    fun grantsSucceeded(output: String): Boolean =
        output.lineSequence().any { it.trim() == "${EXEMPTIONS_MARKER}1" }

    fun buildRestoreButtons(hyperOs: Boolean): String = buildList {
        add("settings put global force_fsg_nav_bar 0")
        add("settings put secure navigation_mode 0")
        if (hyperOs) {
            add(
                """
                if cmd overlay list 2>/dev/null | grep -q '\[ \] com.android.internal.systemui.navbar.threebutton'; then
                  cmd overlay enable com.android.internal.systemui.navbar.threebutton 2>/dev/null || true
                fi
                if cmd overlay list 2>/dev/null | grep -q '\[x\] com.android.internal.systemui.navbar.gestural'; then
                  cmd overlay disable com.android.internal.systemui.navbar.gestural 2>/dev/null || true
                fi
                """.trimIndent(),
            )
        }
    }.joinToString("\n")

    fun buildRestoreAccessibilityService(packageName: String): String {
        require(PACKAGE_NAME.matches(packageName)) { "Unexpected package name: $packageName" }
        val target = "$packageName/$packageName.service.HyperGestureAccessibilityService"
        return """
            curr="$(settings get secure enabled_accessibility_services 2>/dev/null)"
            case ":${'$'}curr:" in
              *":$target:"*|*":$packageName/.service.HyperGestureAccessibilityService:"*)
                ;;
              *)
                if [ -z "${'$'}curr" ] || [ "${'$'}curr" = "null" ]; then
                  settings put secure enabled_accessibility_services "$target"
                else
                  settings put secure enabled_accessibility_services "${'$'}curr:$target"
                fi
                ;;
            esac
            settings put secure accessibility_enabled 1
        """.trimIndent()
    }

    private const val EXEMPTIONS_MARKER = "EXEMPTIONS="
}

internal class NavigationRecoveryRunner(
    private val store: RecoveryStore,
    private val settings: SettingsReader,
    private val shell: ShellRunner,
    private val gateway: ShizukuGateway,
    private val isHyperOs: () -> Boolean,
    /** Accessibility running, Autostart confirmed and Battery done, read when a repair is needed. */
    private val setupComplete: suspend () -> Boolean,
    private val serviceEverEnabled: suspend () -> Boolean = { false },
    private val packageName: String,
    private val now: () -> Long,
    private val scope: CoroutineScope,
) {
    private val repairMutex = Mutex()

    fun launch(trigger: RecoveryTrigger, force: Boolean = false, delayMs: Long = 0): Job =
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            run(trigger, force)
        }

    /** Probes first, so a steady state spawns no process at all. */
    suspend fun run(trigger: RecoveryTrigger, force: Boolean = false): RecoveryStatus? {
        if (!store.isEnabled()) return null

        // The exemption grants cannot be read back, so until a forced run reports success
        // every trigger retries that path; otherwise enabling while Shizuku was down would
        // leave the app without its app-ops/doze/standby exemptions forever.
        val forceRun = force || !store.exemptionsGranted()
        val hyperOs = isHyperOs()

        val availability = gateway.availability()
        // The repair writes gestural navigation and disables the three-button overlay, which
        // takes away the only navigation a user has when accessibility is not actually live.
        // HyperOS can revoke it across a reboot or battery saver, so the stored setup is not enough either.
        if (!setupComplete()) {
            if (availability == ShizukuAvailability.Available) {
                repairMutex.withLock {
                    if (serviceEverEnabled()) {
                        restoreAccessibilityService()
                    }
                    if (!setupComplete() && buttonsHidden()) {
                        restoreButtons(hyperOs)
                    }
                }
            }
            if (!setupComplete()) {
                return finish(RecoveryStatus.SETUP_INCOMPLETE, trigger)
            }
        }
        if (!forceRun && valuesAreCorrect(hyperOs)) {
            return finish(availability.status(), trigger)
        }
        if (availability != ShizukuAvailability.Available) {
            return finish(availability.status(), trigger)
        }

        return repairMutex.withLock {
            // A run that queued behind another may already have repaired the values.
            if (!store.isEnabled()) return@withLock null
            if (!setupComplete()) {
                if (availability == ShizukuAvailability.Available) {
                    if (serviceEverEnabled()) {
                        restoreAccessibilityService()
                    }
                    if (!setupComplete() && buttonsHidden()) {
                        restoreButtons(hyperOs)
                    }
                }
                if (!setupComplete()) {
                    return@withLock finish(RecoveryStatus.SETUP_INCOMPLETE, trigger)
                }
            }
            if (!forceRun && valuesAreCorrect(hyperOs)) {
                return@withLock finish(RecoveryStatus.VERIFIED, trigger)
            }
            finish(repair(forceRun, hyperOs), trigger)
        }
    }

    private suspend fun repair(force: Boolean, hyperOs: Boolean): RecoveryStatus {
        val script = NavigationRecoveryScript.build(
            packageName = packageName,
            includeExemptions = force,
            hyperOs = hyperOs,
            guard = !force,
        )
        val result = try {
            shell.exec(script, SHELL_TIMEOUT_SECONDS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return if (valuesAreCorrect(hyperOs)) {
                RecoveryStatus.VERIFIED
            } else {
                gateway.availability().failureStatus()
            }
        }
        if (force && !result.timedOut && NavigationRecoveryScript.grantsSucceeded(result.output)) {
            store.markExemptionsGranted(true)
        }
        // The settings provider is the truth, even when the shell timed out or exited non-zero.
        return when {
            valuesAreCorrect(hyperOs) -> RecoveryStatus.VERIFIED
            result.timedOut || result.exitCode != 0 -> RecoveryStatus.COMMAND_FAILED
            else -> RecoveryStatus.NOT_VERIFIED
        }
    }

    private fun valuesAreCorrect(hyperOs: Boolean): Boolean =
        NavigationSettingTargets.verified(
            runCatching { settings.navigationValues() }.getOrDefault(emptyMap()),
            includeHyperOs = hyperOs,
        )

    private suspend fun restoreButtons(hyperOs: Boolean) {
        val script = NavigationRecoveryScript.buildRestoreButtons(hyperOs)
        try {
            shell.exec(script, SHELL_TIMEOUT_SECONDS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best effort restoration
        }
    }

    private suspend fun restoreAccessibilityService() {
        val script = NavigationRecoveryScript.buildRestoreAccessibilityService(packageName)
        try {
            shell.exec(script, SHELL_TIMEOUT_SECONDS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best effort restoration
        }
    }

    private fun buttonsHidden(): Boolean =
        NavigationSettingTargets.buttonsHidden(
            runCatching { settings.navigationValues() }.getOrDefault(emptyMap()),
        )

    private suspend fun finish(status: RecoveryStatus, trigger: RecoveryTrigger): RecoveryStatus {
        store.record(status, trigger, now())
        return status
    }

    private fun ShizukuAvailability.status(): RecoveryStatus = when (this) {
        ShizukuAvailability.Available -> RecoveryStatus.VERIFIED
        ShizukuAvailability.BinderDown -> RecoveryStatus.SHIZUKU_UNAVAILABLE
        ShizukuAvailability.PermissionDenied -> RecoveryStatus.PERMISSION_DENIED
    }

    private fun ShizukuAvailability.failureStatus(): RecoveryStatus =
        if (this == ShizukuAvailability.Available) RecoveryStatus.COMMAND_FAILED else status()

    private companion object {
        const val SHELL_TIMEOUT_SECONDS = 8L
    }
}

/** One runner per process: the worker and the service share it, and it outlives unbind. */
internal object NavigationRecoveryRuntime {

    @Volatile
    private var runner: NavigationRecoveryRunner? = null

    fun runner(context: Context): NavigationRecoveryRunner =
        runner ?: synchronized(this) {
            runner ?: create(context.applicationContext).also { runner = it }
        }

    private fun create(context: Context): NavigationRecoveryRunner {
        val failures = CoroutineExceptionHandler { _, throwable ->
            FailureLog.record(context, "navigation recovery", throwable)
        }
        return NavigationRecoveryRunner(
            store = NavigationRecoveryRepository(context),
            settings = ContentResolverSettingsReader(context),
            shell = ShizukuShellRunner(),
            gateway = SystemShizukuGateway,
            isHyperOs = { NavigationModeReader.isHyperOsDevice() },
            setupComplete = { safetySetupDone(context) },
            serviceEverEnabled = {
                runCatching { context.setupState.record.first().serviceEverEnabled }.getOrDefault(false)
            },
            packageName = context.packageName,
            now = System::currentTimeMillis,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + failures),
        )
    }

    /**
     * Read at repair time rather than when recovery is enabled: HyperOS can revoke
     * accessibility across a reboot, and the navigation buttons must stay whenever
     * gestures cannot actually run. The battery and autostart steps are the user's
     * confirmations from the setup checklist.
     */
    private suspend fun safetySetupDone(context: Context): Boolean {
        // An unreadable store reads as "not set up", which keeps the buttons rather than
        // letting a corrupt file make the repair hide them.
        val record = runCatching { context.setupState.record.first() }.getOrNull() ?: return false
        // The checklist's "done" is the live service, not merely the Accessibility toggle.
        val accessibilityDone = DiagnosticsCollector.isAccessibilityServiceRunning()
        // A yes is trusted; HyperOS keeps its own Power setting, so a no proves nothing there.
        val batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
        val batteryDone = batteryUnrestricted || record.batteryConfirmed
        val autostartDone = !NavigationModeReader.isHyperOsDevice() || record.autostartConfirmed
        return accessibilityDone && batteryDone && autostartDone
    }
}
