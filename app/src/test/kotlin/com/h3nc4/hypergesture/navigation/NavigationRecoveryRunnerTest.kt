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
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.
package com.h3nc4.hypergesture.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRecoveryRunnerTest {

    private class FakeStore(
        var enabled: Boolean = true,
        var granted: Boolean = true,
    ) : RecoveryStore {
        data class Record(val status: RecoveryStatus, val trigger: RecoveryTrigger, val at: Long)

        val records = mutableListOf<Record>()

        override suspend fun isEnabled() = enabled

        override suspend fun record(status: RecoveryStatus, trigger: RecoveryTrigger, at: Long) {
            records += Record(status, trigger, at)
        }

        override suspend fun exemptionsGranted() = granted

        override suspend fun markExemptionsGranted(granted: Boolean) {
            this.granted = granted
        }
    }

    private class FakeSettings(
        var fsg: String? = "0",
        var nav: String? = "0",
        var gesture: String? = "1",
    ) : SettingsReader {
        override fun navigationValues() = mapOf(
            "force_fsg_nav_bar" to fsg,
            "navigation_mode" to nav,
            "use_gesture_version_three" to gesture,
        )
    }

    private class FakeShell(
        var result: CommandResult = CommandResult(exitCode = 0, output = ""),
        var onExec: suspend (String) -> Unit = {},
    ) : ShellRunner {
        val commands = mutableListOf<String>()

        override suspend fun exec(command: String, timeoutSeconds: Long): CommandResult {
            commands += command
            onExec(command)
            return result
        }
    }

    private class FakeGateway(
        var availability: ShizukuAvailability = ShizukuAvailability.Available,
    ) : ShizukuGateway {
        override fun availability() = availability
    }

    private class Fixture(scope: CoroutineScope) {
        val store = FakeStore()
        val settings = FakeSettings()
        val shell = FakeShell()
        val gateway = FakeGateway()
        var hyperOs = false
        var setupComplete = true
        var now = 1_000L
        val runner = NavigationRecoveryRunner(
            store = store,
            settings = settings,
            shell = shell,
            gateway = gateway,
            isHyperOs = { hyperOs },
            setupComplete = { setupComplete },
            packageName = "com.h3nc4.hypergesture",
            now = { now },
            scope = scope,
        )

        fun values(correct: Boolean) {
            settings.fsg = if (correct) "1" else "0"
            settings.nav = if (correct) "2" else "0"
            settings.gesture = if (correct) "1" else "0"
        }
    }

    @Test
    fun `a disabled recovery never probes or spawns a shell`() = runBlocking {
        val f = Fixture(this)
        f.store.enabled = false

        assertNull(f.runner.run(RecoveryTrigger.RESUME))
        assertNull(f.runner.run(RecoveryTrigger.MANUAL, force = true))
        assertTrue(f.shell.commands.isEmpty())
        assertTrue(f.store.records.isEmpty())
    }

    @Test
    fun `incomplete setup never spawns the repair that hides the buttons`() = runBlocking {
        val f = Fixture(this)
        f.hyperOs = true
        f.setupComplete = false

        assertEquals(RecoveryStatus.SETUP_INCOMPLETE, f.runner.run(RecoveryTrigger.BOOT, force = true))

        assertTrue(f.shell.commands.isEmpty())
        assertEquals(
            listOf(FakeStore.Record(RecoveryStatus.SETUP_INCOMPLETE, RecoveryTrigger.BOOT, 1_000L)),
            f.store.records,
        )
    }

    @Test
    fun `incomplete setup leaves the buttons alone on a normal trigger too`() = runBlocking {
        val f = Fixture(this)
        f.setupComplete = false

        assertEquals(RecoveryStatus.SETUP_INCOMPLETE, f.runner.run(RecoveryTrigger.SETTINGS_OBSERVER))

        assertTrue(f.shell.commands.isEmpty())
    }

    @Test
    fun `incomplete setup restores buttons if they were hidden`() = runBlocking {
        val f = Fixture(this)
        f.hyperOs = true
        f.setupComplete = false
        f.values(correct = true)

        assertEquals(RecoveryStatus.SETUP_INCOMPLETE, f.runner.run(RecoveryTrigger.BOOT, force = true))

        assertFalse(f.shell.commands.isEmpty())
        assertTrue(f.shell.commands.single().contains("settings put global force_fsg_nav_bar 0"))
        assertTrue(f.shell.commands.single().contains("settings put secure navigation_mode 0"))
        assertTrue(f.shell.commands.single().contains("cmd overlay enable com.android.internal.systemui.navbar.threebutton"))
    }

    @Test
    fun `incomplete setup checks setup first even if values are currently gestural`() = runBlocking {
        val f = Fixture(this)
        f.setupComplete = false
        f.values(correct = true)

        assertEquals(RecoveryStatus.SETUP_INCOMPLETE, f.runner.run(RecoveryTrigger.PERIODIC))
    }

    @Test
    fun `correct values need no process at all`() = runBlocking {
        val f = Fixture(this)
        f.values(correct = true)

        assertEquals(RecoveryStatus.VERIFIED, f.runner.run(RecoveryTrigger.PERIODIC))

        assertTrue(f.shell.commands.isEmpty())
        assertEquals(
            listOf(FakeStore.Record(RecoveryStatus.VERIFIED, RecoveryTrigger.PERIODIC, 1_000L)),
            f.store.records,
        )
    }

    @Test
    fun `correct values still report a stopped Shizuku`() = runBlocking {
        val f = Fixture(this)
        f.values(correct = true)
        f.gateway.availability = ShizukuAvailability.BinderDown

        assertEquals(RecoveryStatus.SHIZUKU_UNAVAILABLE, f.runner.run(RecoveryTrigger.RESUME))
        assertTrue(f.shell.commands.isEmpty())
    }

    @Test
    fun `a mismatch cannot be repaired without Shizuku`() = runBlocking {
        val f = Fixture(this)
        f.gateway.availability = ShizukuAvailability.BinderDown

        assertEquals(RecoveryStatus.SHIZUKU_UNAVAILABLE, f.runner.run(RecoveryTrigger.PREFERRED_HOME))

        f.gateway.availability = ShizukuAvailability.PermissionDenied
        assertEquals(RecoveryStatus.PERMISSION_DENIED, f.runner.run(RecoveryTrigger.PERIODIC))

        assertTrue(f.shell.commands.isEmpty())
    }

    @Test
    fun `a mismatch repairs the two values without the exemptions`() = runBlocking {
        val f = Fixture(this)
        f.shell.onExec = { f.values(correct = true) }

        assertEquals(RecoveryStatus.VERIFIED, f.runner.run(RecoveryTrigger.SETTINGS_OBSERVER))

        val script = f.shell.commands.single()
        assertTrue(script.contains("settings put global force_fsg_nav_bar 1"))
        assertTrue(script.contains("settings put secure navigation_mode 2"))
        assertTrue("a normal repair does not grant exemptions", !script.contains("appops"))
        assertTrue(script.contains("if ["))
    }

    @Test
    fun `a forced run grants the exemptions even when the values are right`() = runBlocking {
        val f = Fixture(this)
        f.values(correct = true)
        f.hyperOs = true
        f.shell.result = CommandResult(exitCode = 0, output = "EXEMPTIONS=1")

        assertEquals(RecoveryStatus.VERIFIED, f.runner.run(RecoveryTrigger.MANUAL, force = true))

        val script = f.shell.commands.single()
        assertTrue(script.contains("appops set com.h3nc4.hypergesture 10008 allow"))
        assertTrue(script.contains("dumpsys deviceidle whitelist +com.h3nc4.hypergesture"))
        assertTrue(script.contains("am set-standby-bucket com.h3nc4.hypergesture active"))
        assertTrue(script.contains("use_gesture_version_three"))
        assertTrue(script.contains("cmd overlay"))
        assertFalse("a forced run writes without the guard", script.contains("if ["))
    }

    @Test
    fun `an ungranted exemption retries the forced path until it succeeds`() = runBlocking {
        val f = Fixture(this)
        f.store.granted = false
        f.values(correct = true)
        f.shell.result = CommandResult(exitCode = 0, output = "EXEMPTIONS=1")

        assertEquals(RecoveryStatus.VERIFIED, f.runner.run(RecoveryTrigger.PERIODIC))

        val script = f.shell.commands.single()
        assertTrue(script.contains("appops set com.h3nc4.hypergesture 10008 allow"))
        assertTrue("a forced retry writes without the guard", !script.contains("if ["))
        assertTrue(f.store.granted)

        // Once granted, a steady state is back to probing without a process.
        assertEquals(RecoveryStatus.VERIFIED, f.runner.run(RecoveryTrigger.PERIODIC))
        assertEquals(1, f.shell.commands.size)
    }

    @Test
    fun `a failed grant is not recorded and is retried`() = runBlocking {
        val f = Fixture(this)
        f.store.granted = false
        f.values(correct = true)
        f.shell.result = CommandResult(exitCode = 1, output = "appops: error\nEXEMPTIONS=0")

        // The values are right, so the readback still outranks the failed exit.
        assertEquals(RecoveryStatus.VERIFIED, f.runner.run(RecoveryTrigger.MANUAL))
        assertFalse(f.store.granted)

        assertEquals(RecoveryStatus.VERIFIED, f.runner.run(RecoveryTrigger.PERIODIC))
        assertEquals(2, f.shell.commands.size)
    }

    @Test
    fun `a HyperOS gesture flag mismatch still runs the repair`() = runBlocking {
        val f = Fixture(this)
        f.hyperOs = true
        f.values(correct = true)
        f.settings.gesture = "0"
        f.shell.onExec = { f.values(correct = true) }

        assertEquals(RecoveryStatus.VERIFIED, f.runner.run(RecoveryTrigger.SETTINGS_OBSERVER))

        val script = f.shell.commands.single()
        assertTrue(script.contains("settings put global use_gesture_version_three 1"))
        assertTrue(script.contains("cmd overlay"))
    }

    @Test
    fun `a non-HyperOS build ignores the gesture flag`() = runBlocking {
        val f = Fixture(this)
        f.values(correct = true)
        f.settings.gesture = "0"

        assertEquals(RecoveryStatus.VERIFIED, f.runner.run(RecoveryTrigger.PERIODIC))

        assertTrue(f.shell.commands.isEmpty())
    }

    @Test
    fun `a non-zero shell exit with the values still wrong is a command failure`() = runBlocking {
        val f = Fixture(this)
        f.shell.result = CommandResult(exitCode = 1, output = "permission denied")

        assertEquals(RecoveryStatus.COMMAND_FAILED, f.runner.run(RecoveryTrigger.SETTINGS_OBSERVER))
    }

    @Test
    fun `a successful shell that did not stick is not verified`() = runBlocking {
        val f = Fixture(this)

        assertEquals(RecoveryStatus.NOT_VERIFIED, f.runner.run(RecoveryTrigger.SETTINGS_OBSERVER))
    }

    @Test
    fun `a timeout counts as verified when the readback landed`() = runBlocking {
        val f = Fixture(this)
        f.shell.result = CommandResult(exitCode = -1, output = "Timed out", timedOut = true)
        f.shell.onExec = { f.values(correct = true) }

        assertEquals(RecoveryStatus.VERIFIED, f.runner.run(RecoveryTrigger.PREFERRED_HOME))
    }

    @Test
    fun `concurrent repairs run the shell once`() = runBlocking {
        val f = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.shell.onExec = {
            entered.complete(Unit)
            release.await()
            f.values(correct = true)
        }

        val first = async { f.runner.run(RecoveryTrigger.PREFERRED_HOME) }
        entered.await()
        val second = async { f.runner.run(RecoveryTrigger.PACKAGE_CHANGE) }
        release.complete(Unit)

        assertEquals(
            listOf(RecoveryStatus.VERIFIED, RecoveryStatus.VERIFIED),
            awaitAll(first, second),
        )
        assertEquals(1, f.shell.commands.size)
    }

    @Test
    fun `cancellation propagates out of the shell`() = runBlocking {
        val f = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        f.shell.onExec = {
            entered.complete(Unit)
            awaitCancellation()
        }

        val job = async { f.runner.run(RecoveryTrigger.PREFERRED_HOME) }
        entered.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    @Test
    fun `the delayed pass still probes after the watchdog may have reset the values`() = runBlocking {
        val f = Fixture(this)
        f.values(correct = true)
        f.shell.onExec = { f.values(correct = true) }

        val job = f.runner.launch(RecoveryTrigger.PREFERRED_HOME, delayMs = 50)
        // The broadcast lands while the values are still right; the reset comes after it.
        delay(10)
        f.values(correct = false)
        job.join()

        assertEquals(1, f.shell.commands.size)
        assertEquals(RecoveryStatus.VERIFIED, f.store.records.last().status)
    }

    @Test
    fun `legacy write failures read as command failures`() {
        assertEquals(RecoveryStatus.COMMAND_FAILED, decodeRecoveryStatus("WRITE_FAILED"))
        assertEquals(RecoveryStatus.DISABLED, decodeRecoveryStatus(null))
        assertEquals(RecoveryStatus.NOT_VERIFIED, decodeRecoveryStatus("NOT_VERIFIED"))
        assertEquals(RecoveryStatus.DISABLED, decodeRecoveryStatus("something else"))
        assertNull(decodeRecoveryTrigger("something else"))
        assertEquals(RecoveryTrigger.PERIODIC, decodeRecoveryTrigger("PERIODIC"))
    }

    @Test
    fun `only the self-update action reaches the manifest receiver`() {
        assertTrue(shouldHandle("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse(shouldHandle("android.intent.action.PACKAGE_ADDED"))
        assertFalse(shouldHandle("android.intent.action.PACKAGE_CHANGED"))
        assertFalse(shouldHandle("android.intent.action.PACKAGE_REMOVED"))
        assertFalse(shouldHandle("android.intent.action.PACKAGE_REPLACED"))
        assertFalse(shouldHandle("android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED"))
        assertFalse(shouldHandle(null))
    }
}
