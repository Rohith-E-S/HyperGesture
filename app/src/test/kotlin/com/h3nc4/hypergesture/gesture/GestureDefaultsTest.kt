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

package com.h3nc4.hypergesture.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDefaultsTest {

    @Test
    fun `reset restores every setting the gesture screen shows`() {
        val current = GestureConfiguration(
            edgeWidthDp = 64f,
            sideMinimumSwipeDistanceDp = 80f,
            bottomMinimumSwipeDistanceDp = 60f,
            maximumArmDurationMs = 3000L,
            recentsHoldMs = 500L,
            leftEdgeBackEnabled = false,
            rightEdgeBackEnabled = false,
            landscapeConfirmEnabled = false,
            hapticFeedbackEnabled = false,
        )

        assertEquals(GestureConfiguration(), current.withGestureDefaults())
    }

    @Test
    fun `reset leaves the toggles of other screens alone`() {
        val current = GestureConfiguration(
            appSwitchEnabled = true,
            circleToSearchEnabled = true,
            circleToSearchFollowsSystemTiming = false,
            circleToSearchHoldMs = 900L,
            holdStillnessDp = 30f,
            offAxisToleranceRatio = 2f,
        )

        val reset = current.withGestureDefaults()

        assertTrue(reset.appSwitchEnabled)
        assertTrue(reset.circleToSearchEnabled)
        assertFalse(reset.circleToSearchFollowsSystemTiming)
        assertEquals(900L, reset.circleToSearchHoldMs)
        assertEquals(30f, reset.holdStillnessDp, 0f)
        assertEquals(2f, reset.offAxisToleranceRatio, 0f)
    }
}
