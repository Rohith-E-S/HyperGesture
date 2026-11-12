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

import com.h3nc4.hypergesture.gesture.TouchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchReplayPlannerTest {

    @Test
    fun `plan returns null for empty touch samples`() {
        assertNull(TouchReplayPlanner.plan(emptyList()))
    }

    @Test
    fun `plan for single sample is tap with nudged path point (+1px)`() {
        val sample = TouchSample(xPx = 100f, yPx = 200f, timestampMs = 1_000L)
        val plan = TouchReplayPlanner.plan(listOf(sample))
        assertNotNull(plan)
        requireNotNull(plan)
        assertTrue(plan.isTap)
        assertEquals(TouchReplayPlanner.MIN_TAP_MS, plan.durationMs)
        assertEquals(
            listOf(Pair(100f, 200f), Pair(101f, 201f)),
            plan.pathPoints,
        )
    }

    @Test
    fun `plan for short tap under 50ms clamps duration to 50ms (MIN_TAP_MS)`() {
        val samples = listOf(
            TouchSample(xPx = 100f, yPx = 100f, timestampMs = 1_000L),
            TouchSample(xPx = 101f, yPx = 101f, timestampMs = 1_010L), // raw 10ms
        )
        val plan = TouchReplayPlanner.plan(samples)
        assertNotNull(plan)
        requireNotNull(plan)
        assertTrue(plan.isTap)
        assertEquals(50L, plan.durationMs)
    }

    @Test
    fun `plan for tap between 50ms and 60ms preserves exact duration`() {
        val samples = listOf(
            TouchSample(xPx = 100f, yPx = 100f, timestampMs = 1_000L),
            TouchSample(xPx = 102f, yPx = 102f, timestampMs = 1_055L), // raw 55ms
        )
        val plan = TouchReplayPlanner.plan(samples)
        assertNotNull(plan)
        requireNotNull(plan)
        assertTrue(plan.isTap)
        assertEquals(55L, plan.durationMs)
    }

    @Test
    fun `plan for tap over 60ms but under long press timeout clamps duration to 60ms (TAP_STROKE_CAP_MS)`() {
        val samples = listOf(
            TouchSample(xPx = 100f, yPx = 100f, timestampMs = 1_000L),
            TouchSample(xPx = 103f, yPx = 103f, timestampMs = 1_180L), // raw 180ms
        )
        val plan = TouchReplayPlanner.plan(samples, longPressTimeoutMs = 400L)
        assertNotNull(plan)
        requireNotNull(plan)
        assertTrue(plan.isTap)
        assertEquals(60L, plan.durationMs)
    }

    @Test
    fun `plan for long press tap exceeding longPressTimeout mirrors raw duration up to 3000ms`() {
        val samples = listOf(
            TouchSample(xPx = 100f, yPx = 100f, timestampMs = 1_000L),
            TouchSample(xPx = 102f, yPx = 102f, timestampMs = 1_600L), // raw 600ms >= 400ms
        )
        val plan = TouchReplayPlanner.plan(samples, longPressTimeoutMs = 400L)
        assertNotNull(plan)
        requireNotNull(plan)
        assertTrue(plan.isTap)
        assertEquals(600L, plan.durationMs)
    }

    @Test
    fun `plan for long press tap exceeding 3000ms is capped at 3000ms (MAX_REPLAY_MS)`() {
        val samples = listOf(
            TouchSample(xPx = 100f, yPx = 100f, timestampMs = 1_000L),
            TouchSample(xPx = 102f, yPx = 102f, timestampMs = 5_500L), // raw 4500ms
        )
        val plan = TouchReplayPlanner.plan(samples, longPressTimeoutMs = 400L)
        assertNotNull(plan)
        requireNotNull(plan)
        assertTrue(plan.isTap)
        assertEquals(3_000L, plan.durationMs)
    }

    @Test
    fun `plan for drag with movement greater than or equal to 12px (TAP_SLOP_PX) is not tap and preserves all points`() {
        val samples = listOf(
            TouchSample(xPx = 0f, yPx = 0f, timestampMs = 1_000L),
            TouchSample(xPx = 10f, yPx = 10f, timestampMs = 1_100L),
            TouchSample(xPx = 20f, yPx = 20f, timestampMs = 1_200L), // hypot ~28.28px >= 12px
        )
        val plan = TouchReplayPlanner.plan(samples)
        assertNotNull(plan)
        requireNotNull(plan)
        assertFalse(plan.isTap)
        assertEquals(200L, plan.durationMs)
        assertEquals(
            listOf(Pair(0f, 0f), Pair(10f, 10f), Pair(20f, 20f)),
            plan.pathPoints,
        )
    }

    @Test
    fun `plan for fast drag (0ms duration) clamps duration to 1ms`() {
        val samples = listOf(
            TouchSample(xPx = 0f, yPx = 0f, timestampMs = 1_000L),
            TouchSample(xPx = 50f, yPx = 50f, timestampMs = 1_000L), // raw 0ms
        )
        val plan = TouchReplayPlanner.plan(samples)
        assertNotNull(plan)
        requireNotNull(plan)
        assertFalse(plan.isTap)
        assertEquals(1L, plan.durationMs)
    }

    @Test
    fun `timeoutMs equals duration + 65ms settle + 1000ms grace`() {
        val samples = listOf(
            TouchSample(xPx = 100f, yPx = 100f, timestampMs = 1_000L),
            TouchSample(xPx = 101f, yPx = 101f, timestampMs = 1_010L), // duration clamped to 50ms
        )
        val plan = TouchReplayPlanner.plan(samples)
        assertNotNull(plan)
        requireNotNull(plan)
        val expected = 50L + TouchReplayPlanner.WINDOW_SETTLE_MS + TouchReplayPlanner.FINISH_GRACE_MS
        assertEquals(expected, plan.timeoutMs)
        assertEquals(1_115L, plan.timeoutMs)
    }
}
