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

package com.h3nc4.hypergesture.settings

import com.h3nc4.hypergesture.MemoryDataStore
import com.h3nc4.hypergesture.gesture.GestureConfiguration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureSettingsRepositoryTest {

    @Test
    fun `empty store yields default GestureConfiguration`() = runBlocking {
        val repo = GestureSettingsRepository(MemoryDataStore())
        val config = repo.read()
        assertEquals(GestureConfiguration(), config)
    }

    @Test
    fun `update modifies values and flow emits updated configuration`() = runBlocking {
        val store = MemoryDataStore()
        val repo = GestureSettingsRepository(store)

        repo.update { current ->
            current.copy(
                enabled = false,
                edgeWidthDp = 30f,
                sideMinimumSwipeDistanceDp = 40f,
                bottomMinimumSwipeDistanceDp = 50f,
                maximumArmDurationMs = 800L,
                recentsHoldMs = 250L,
                holdStillnessDp = 15f,
                offAxisToleranceRatio = 0.5f,
                appSwitchEnabled = true,
                leftEdgeBackEnabled = false,
                rightEdgeBackEnabled = false,
                hapticFeedbackEnabled = false,
                landscapeConfirmEnabled = false,
                circleToSearchEnabled = true,
                circleToSearchFollowsSystemTiming = false,
                circleToSearchHoldMs = 600L,
            )
        }

        val updated = repo.configuration.first()
        assertFalse(updated.enabled)
        assertEquals(30f, updated.edgeWidthDp, 0.001f)
        assertEquals(40f, updated.sideMinimumSwipeDistanceDp, 0.001f)
        assertEquals(50f, updated.bottomMinimumSwipeDistanceDp, 0.001f)
        assertEquals(800L, updated.maximumArmDurationMs)
        assertEquals(250L, updated.recentsHoldMs)
        assertEquals(15f, updated.holdStillnessDp, 0.001f)
        assertEquals(0.5f, updated.offAxisToleranceRatio, 0.001f)
        assertTrue(updated.appSwitchEnabled)
        assertFalse(updated.leftEdgeBackEnabled)
        assertFalse(updated.rightEdgeBackEnabled)
        assertFalse(updated.hapticFeedbackEnabled)
        assertFalse(updated.landscapeConfirmEnabled)
        assertTrue(updated.circleToSearchEnabled)
        assertFalse(updated.circleToSearchFollowsSystemTiming)
        assertEquals(600L, updated.circleToSearchHoldMs)
    }

    @Test
    fun `debug app switch override overrides stored and default value`() = runBlocking {
        val store = MemoryDataStore()
        val repoOverriddenTrue = GestureSettingsRepository(
            dataStore = store,
            debugAppSwitch = { true },
        )
        assertTrue(repoOverriddenTrue.read().appSwitchEnabled)

        val repoOverriddenFalse = GestureSettingsRepository(
            dataStore = store,
            debugAppSwitch = { false },
        )
        assertFalse(repoOverriddenFalse.read().appSwitchEnabled)
    }

    @Test
    fun `debug recents hold override overrides stored and default value`() = runBlocking {
        val store = MemoryDataStore()
        val repo = GestureSettingsRepository(
            dataStore = store,
            debugRecentsHold = { 999L },
        )
        assertEquals(999L, repo.read().recentsHoldMs)

        // Stored value should still be overridden if debug override is present
        repo.update { it.copy(recentsHoldMs = 123L) }
        assertEquals(999L, repo.read().recentsHoldMs)
    }
}
