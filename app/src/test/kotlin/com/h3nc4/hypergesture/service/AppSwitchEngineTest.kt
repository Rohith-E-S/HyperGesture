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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSwitchEngineTest {

    @Test
    fun `step returns failure when appSwitch is disabled`() {
        val engine = AppSwitchEngine(isAppSwitchEnabled = { false })
        engine.resetWalk(listOf("app.a", "app.b"), initialPosition = 0)
        val result = engine.computeNextStep(toPrevious = true)
        assertTrue(result is StepResult.Failure)
        assertEquals("app switch: disabled", (result as StepResult.Failure).message)
    }

    @Test
    fun `step returns failure when walk is empty`() {
        val engine = AppSwitchEngine()
        val result = engine.computeNextStep(toPrevious = true)
        assertTrue(result is StepResult.Failure)
        assertEquals(
            "app switch: no usage events, so usage access is probably not granted",
            (result as StepResult.Failure).message,
        )
        assertEquals(result.message, engine.lastFailure)
    }

    @Test
    fun `stepping toPrevious from index 0 advances to index 1`() {
        val engine = AppSwitchEngine()
        engine.resetWalk(listOf("app.a", "app.b", "app.c"), initialPosition = 0)
        val result = engine.computeNextStep(toPrevious = true)
        assertTrue(result is StepResult.Success)
        val success = result as StepResult.Success
        assertEquals("app.b", success.target)
        assertEquals(1, success.nextIndex)

        engine.commitStep(
            nextIndex = success.nextIndex,
            target = success.target,
            steppedAt = 1_000L,
            launchedAt = 2_000L,
        )
        assertEquals(1, engine.position)
        assertEquals("app.b", engine.lastTarget)
        assertEquals(1_000L, engine.lastStepAt)
        assertEquals(2_000L, engine.lastStepWallClock)
    }

    @Test
    fun `stepping toPrevious at oldest app returns OutOfBoundsOlder`() {
        val engine = AppSwitchEngine()
        engine.resetWalk(listOf("app.a", "app.b", "app.c"), initialPosition = 2)
        val result = engine.computeNextStep(toPrevious = true)
        assertTrue(result is StepResult.Failure)
        assertEquals(
            "app switch: nothing older than this",
            (result as StepResult.Failure).message,
        )
        assertEquals(2, engine.position)
    }

    @Test
    fun `stepping toNext at newest app returns OutOfBoundsNewer`() {
        val engine = AppSwitchEngine()
        engine.resetWalk(listOf("app.a", "app.b", "app.c"), initialPosition = 0)
        val result = engine.computeNextStep(toPrevious = false)
        assertTrue(result is StepResult.Failure)
        assertEquals(
            "app switch: nothing newer than this",
            (result as StepResult.Failure).message,
        )
        assertEquals(0, engine.position)
    }

    @Test
    fun `stepping forward from index 1 returns to index 0`() {
        val engine = AppSwitchEngine()
        engine.resetWalk(listOf("app.a", "app.b", "app.c"), initialPosition = 1)
        val result = engine.computeNextStep(toPrevious = false)
        assertTrue(result is StepResult.Success)
        val success = result as StepResult.Success
        assertEquals("app.a", success.target)
        assertEquals(0, success.nextIndex)

        engine.commitStep(
            nextIndex = success.nextIndex,
            target = success.target,
            steppedAt = 1_500L,
            launchedAt = 2_500L,
        )
        assertEquals(0, engine.position)
        assertEquals("app.a", engine.lastTarget)
    }

    @Test
    fun `chain is valid when now - lastStepAt is within 5000ms and not moved by hand`() {
        val engine = AppSwitchEngine()
        engine.resetWalk(listOf("app.a", "app.b"), initialPosition = 0)
        engine.commitStep(nextIndex = 1, target = "app.b", steppedAt = 1_000L, launchedAt = 2_000L)

        assertTrue(engine.isChained(now = 4_000L, movedByHand = false))
        assertTrue(engine.isChained(now = 6_000L, movedByHand = false)) // exactly 5000ms
    }

    @Test
    fun `chain is broken when now - lastStepAt exceeds 5000ms`() {
        val engine = AppSwitchEngine()
        engine.resetWalk(listOf("app.a", "app.b"), initialPosition = 0)
        engine.commitStep(nextIndex = 1, target = "app.b", steppedAt = 1_000L, launchedAt = 2_000L)

        assertFalse(engine.isChained(now = 6_001L, movedByHand = false))
    }

    @Test
    fun `chain is broken when movedByHand is true even within window`() {
        val engine = AppSwitchEngine()
        engine.resetWalk(listOf("app.a", "app.b"), initialPosition = 0)
        engine.commitStep(nextIndex = 1, target = "app.b", steppedAt = 1_000L, launchedAt = 2_000L)

        assertFalse(engine.isChained(now = 2_000L, movedByHand = true))
    }

    @Test
    fun `filtering recents removes self package, default launcher, and SYSTEM_SURFACES`() {
        val tasks = listOf(
            "com.h3nc4.hypergesture",
            "com.miui.securitycenter",
            "launcher.app",
            "app.target",
            "app.unlaunchable",
        )
        val filtered = AppSwitchEngine.filterRecents(
            tasks = tasks,
            selfPackage = "com.h3nc4.hypergesture",
            defaultHome = "launcher.app",
            isLaunchable = { it != "app.unlaunchable" },
        )
        assertEquals(listOf("app.target"), filtered)
    }

    @Test
    fun `filtering recents deduplicates preserving newest order`() {
        val tasks = listOf("app.b", "app.a", "app.b", "app.c", "app.a")
        val filtered = AppSwitchEngine.filterRecents(
            tasks = tasks,
            selfPackage = "com.h3nc4.hypergesture",
            defaultHome = null,
            isLaunchable = { true },
        )
        assertEquals(listOf("app.b", "app.a", "app.c"), filtered)
    }
}
