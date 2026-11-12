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

package com.h3nc4.hypergesture.service

import com.h3nc4.hypergesture.navigation.CommandResult
import com.h3nc4.hypergesture.navigation.ShellRunner
import com.h3nc4.hypergesture.navigation.ShizukuAvailability
import com.h3nc4.hypergesture.navigation.ShizukuGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentTasksSourceTest {

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

    private val validDump = """
        ACTIVITY MANAGER RECENT TASKS (dumpsys activity recents)
          Recent tasks:
          * Recent #0: Task{123 #101 type=standard A=com.app.one}
            mActivityComponent=com.app.one/.MainActivity
            autoRemoveRecents=false isPersistable=true activityType=1
            isTrimmable=true isForceHidden=false isForceExcludedFromRecents=false
          * Recent #1: Task{124 #102 type=standard A=com.app.two}
            mActivityComponent=com.app.two/.MainActivity
            autoRemoveRecents=false isPersistable=true activityType=1
            isTrimmable=true isForceHidden=false isForceExcludedFromRecents=false
    """.trimIndent()

    @Test
    fun `cached returns null initially before any refresh`() {
        val source = RecentTasksSource(
            shell = FakeShell(),
            gateway = FakeGateway(),
            coroutineContext = Dispatchers.Unconfined,
        )
        assertNull(source.cached(5_000L))
    }

    @Test
    fun `refresh is skipped when isAppSwitchEnabled returns false`() {
        val shell = FakeShell(result = CommandResult(exitCode = 0, output = validDump))
        val source = RecentTasksSource(
            shell = shell,
            gateway = FakeGateway(),
            isAppSwitchEnabled = { false },
            coroutineContext = Dispatchers.Unconfined,
        )
        source.refresh()
        assertEquals(0, shell.commands.size)
        assertNull(source.cached(5_000L))
    }

    @Test
    fun `refresh is skipped when Shizuku availability is BinderDown`() {
        val shell = FakeShell(result = CommandResult(exitCode = 0, output = validDump))
        val source = RecentTasksSource(
            shell = shell,
            gateway = FakeGateway(availability = ShizukuAvailability.BinderDown),
            coroutineContext = Dispatchers.Unconfined,
        )
        source.refresh()
        assertEquals(0, shell.commands.size)
        assertNull(source.cached(5_000L))
    }

    @Test
    fun `refresh is skipped when Shizuku availability is PermissionDenied`() {
        val shell = FakeShell(result = CommandResult(exitCode = 0, output = validDump))
        val source = RecentTasksSource(
            shell = shell,
            gateway = FakeGateway(availability = ShizukuAvailability.PermissionDenied),
            coroutineContext = Dispatchers.Unconfined,
        )
        source.refresh()
        assertEquals(0, shell.commands.size)
        assertNull(source.cached(5_000L))
    }

    @Test
    fun `refresh ignores failed shell execution with non-zero exit code`() {
        val shell = FakeShell(result = CommandResult(exitCode = 1, output = "error"))
        val source = RecentTasksSource(
            shell = shell,
            gateway = FakeGateway(),
            coroutineContext = Dispatchers.Unconfined,
        )
        source.refresh()
        assertEquals(1, shell.commands.size)
        assertNull(source.cached(5_000L))
    }

    @Test
    fun `refresh ignores timed out shell execution`() {
        val shell = FakeShell(result = CommandResult(exitCode = 0, output = validDump, timedOut = true))
        val source = RecentTasksSource(
            shell = shell,
            gateway = FakeGateway(),
            coroutineContext = Dispatchers.Unconfined,
        )
        source.refresh()
        assertEquals(1, shell.commands.size)
        assertNull(source.cached(5_000L))
    }

    @Test
    fun `refresh successfully parses dumpsys and updates cached tasks`() {
        var now = 1_000L
        val shell = FakeShell(result = CommandResult(exitCode = 0, output = validDump))
        val source = RecentTasksSource(
            shell = shell,
            gateway = FakeGateway(),
            clock = { now },
            coroutineContext = Dispatchers.Unconfined,
        )
        source.refresh()
        assertEquals(1, shell.commands.size)
        assertEquals(listOf("com.app.one", "com.app.two"), source.cached(5_000L))
    }

    @Test
    fun `cached returns tasks when age is within maxAgeMs`() {
        var now = 1_000L
        val shell = FakeShell(result = CommandResult(exitCode = 0, output = validDump))
        val source = RecentTasksSource(
            shell = shell,
            gateway = FakeGateway(),
            clock = { now },
            coroutineContext = Dispatchers.Unconfined,
        )
        source.refresh()
        now = 4_000L // age = 3000ms <= 5000ms
        assertEquals(listOf("com.app.one", "com.app.two"), source.cached(5_000L))
    }

    @Test
    fun `cached returns null when age exceeds maxAgeMs`() {
        var now = 1_000L
        val shell = FakeShell(result = CommandResult(exitCode = 0, output = validDump))
        val source = RecentTasksSource(
            shell = shell,
            gateway = FakeGateway(),
            clock = { now },
            coroutineContext = Dispatchers.Unconfined,
        )
        source.refresh()
        now = 7_000L // age = 6000ms > 5000ms
        assertNull(source.cached(5_000L))
    }

    @Test
    fun `cached returns null when clock goes backwards`() {
        var now = 2_000L
        val shell = FakeShell(result = CommandResult(exitCode = 0, output = validDump))
        val source = RecentTasksSource(
            shell = shell,
            gateway = FakeGateway(),
            clock = { now },
            coroutineContext = Dispatchers.Unconfined,
        )
        source.refresh()
        now = 1_000L // age = -1000ms < 0
        assertNull(source.cached(5_000L))
    }

    @Test
    fun `concurrent refresh calls are deduplicated`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val shell = FakeShell(
            result = CommandResult(exitCode = 0, output = validDump),
            onExec = { gate.await() },
        )
        val source = RecentTasksSource(
            shell = shell,
            gateway = FakeGateway(),
            coroutineContext = Dispatchers.Default,
        )

        try {
            withTimeout(2_000L) {
                source.refresh()
                // Wait until shell command has been invoked
                while (shell.commands.isEmpty()) {
                    kotlinx.coroutines.delay(5L)
                }
                // Second call while first is in-flight
                source.refresh()
                gate.complete(Unit)

                // Wait for completion
                while (source.cached(5_000L) == null) {
                    kotlinx.coroutines.delay(5L)
                }

                assertEquals(1, shell.commands.size)
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun `subsequent refresh is allowed after previous refresh completes`() {
        val shell = FakeShell(result = CommandResult(exitCode = 0, output = validDump))
        val source = RecentTasksSource(
            shell = shell,
            gateway = FakeGateway(),
            coroutineContext = Dispatchers.Unconfined,
        )
        source.refresh()
        assertEquals(1, shell.commands.size)
        source.refresh()
        assertEquals(2, shell.commands.size)
    }

    @Test
    fun `close cancels coroutine scope and suppresses subsequent refreshes`() {
        val shell = FakeShell(result = CommandResult(exitCode = 0, output = validDump))
        val source = RecentTasksSource(
            shell = shell,
            gateway = FakeGateway(),
            coroutineContext = Dispatchers.Unconfined,
        )
        source.close()
        source.refresh()
        assertEquals(0, shell.commands.size)
    }

    @Test
    fun `shell exception is caught and does not crash caller`() {
        val shell = FakeShell(
            onExec = { throw IllegalStateException("shell binder broke") },
        )
        val source = RecentTasksSource(
            shell = shell,
            gateway = FakeGateway(),
            coroutineContext = Dispatchers.Unconfined,
        )
        source.refresh()
        assertEquals(1, shell.commands.size)
        assertNull(source.cached(5_000L))
    }
}
