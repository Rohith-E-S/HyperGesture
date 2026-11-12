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

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.h3nc4.hypergesture.gesture.GestureAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GestureActionPerformerTest {

    private class TestAccessibilityService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
        override fun onInterrupt() {}
    }

    private val service = TestAccessibilityService()

    @Before
    @After
    fun resetFailures() {
        GestureActionPerformer.clearFailures()
    }

    @Test
    fun `Back gesture triggers GLOBAL_ACTION_BACK (1)`() {
        val actions = mutableListOf<Int>()
        val performer = GestureActionPerformer(
            service = service,
            globalActionPerformer = { action ->
                actions += action
                true
            },
        )
        val result = performer.perform(GestureAction.Back)
        assertTrue(result)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), actions)
    }

    @Test
    fun `Home gesture triggers GLOBAL_ACTION_HOME (2)`() {
        val actions = mutableListOf<Int>()
        val performer = GestureActionPerformer(
            service = service,
            globalActionPerformer = { action ->
                actions += action
                true
            },
        )
        val result = performer.perform(GestureAction.Home)
        assertTrue(result)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_HOME), actions)
    }

    @Test
    fun `Recents gesture triggers GLOBAL_ACTION_RECENTS (3)`() {
        val actions = mutableListOf<Int>()
        val performer = GestureActionPerformer(
            service = service,
            globalActionPerformer = { action ->
                actions += action
                true
            },
        )
        val result = performer.perform(GestureAction.Recents)
        assertTrue(result)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_RECENTS), actions)
    }

    @Test
    fun `PreviousApp delegates to switcher step with toPrevious = true`() {
        val steps = mutableListOf<Boolean>()
        val performer = GestureActionPerformer(
            service = service,
            appStepPerformer = { toPrevious ->
                steps += toPrevious
                true
            },
        )
        val result = performer.perform(GestureAction.PreviousApp)
        assertTrue(result)
        assertEquals(listOf(true), steps)
    }

    @Test
    fun `NextApp delegates to switcher step with toPrevious = false`() {
        val steps = mutableListOf<Boolean>()
        val performer = GestureActionPerformer(
            service = service,
            appStepPerformer = { toPrevious ->
                steps += toPrevious
                true
            },
        )
        val result = performer.perform(GestureAction.NextApp)
        assertTrue(result)
        assertEquals(listOf(false), steps)
    }

    @Test
    fun `global action returning false records failure message in lastFailure`() {
        val recordedFailures = mutableListOf<String?>()
        val performer = GestureActionPerformer(
            service = service,
            globalActionPerformer = { false },
            failureRecorder = { _, _, msg -> recordedFailures += msg },
        )
        val result = performer.perform(GestureAction.Back)
        assertFalse(result)
        assertEquals("Back: performGlobalAction returned false", GestureActionPerformer.lastFailure)
        assertEquals(listOf("Back: performGlobalAction returned false"), recordedFailures)
    }

    @Test
    fun `global action throwing exception catches, records throwable class in lastFailure, returns false`() {
        val recordedThrowables = mutableListOf<Throwable?>()
        val performer = GestureActionPerformer(
            service = service,
            globalActionPerformer = { throw IllegalStateException("Service disconnected") },
            failureRecorder = { _, throwable, _ -> recordedThrowables += throwable },
        )
        val result = performer.perform(GestureAction.Home)
        assertFalse(result)
        assertEquals(
            "Home: performGlobalAction threw java.lang.IllegalStateException",
            GestureActionPerformer.lastFailure,
        )
        assertTrue(recordedThrowables.firstOrNull() is IllegalStateException)
    }

    @Test
    fun `clearFailures resets lastFailure and lastCircleToSearchFailure to null`() {
        val performer = GestureActionPerformer(
            service = service,
            globalActionPerformer = { false },
            circleToSearchLauncher = { "Not available" },
            failureRecorder = { _, _, _ -> },
            toastDisplayer = {},
        )
        performer.perform(GestureAction.Back)
        performer.perform(GestureAction.CircleToSearch)
        assertEquals("Back: performGlobalAction returned false", GestureActionPerformer.lastFailure)
        assertEquals("Not available", GestureActionPerformer.lastCircleToSearchFailure)

        GestureActionPerformer.clearFailures()
        assertNull(GestureActionPerformer.lastFailure)
        assertNull(GestureActionPerformer.lastCircleToSearchFailure)
    }

    @Test
    fun `CircleToSearch failure sets lastCircleToSearchFailure and returns false`() {
        var toastShown = false
        val performer = GestureActionPerformer(
            service = service,
            circleToSearchLauncher = { "No CTS assistant configured" },
            failureRecorder = { _, _, _ -> },
            toastDisplayer = { toastShown = true },
        )
        val result = performer.perform(GestureAction.CircleToSearch)
        assertFalse(result)
        assertEquals("No CTS assistant configured", GestureActionPerformer.lastCircleToSearchFailure)
        assertTrue(toastShown)
    }

    @Test
    fun `CircleToSearch success returns true and leaves failures null`() {
        var toastShown = false
        val performer = GestureActionPerformer(
            service = service,
            circleToSearchLauncher = { null },
            failureRecorder = { _, _, _ -> },
            toastDisplayer = { toastShown = true },
        )
        val result = performer.perform(GestureAction.CircleToSearch)
        assertTrue(result)
        assertNull(GestureActionPerformer.lastCircleToSearchFailure)
        assertFalse(toastShown)
    }
}
