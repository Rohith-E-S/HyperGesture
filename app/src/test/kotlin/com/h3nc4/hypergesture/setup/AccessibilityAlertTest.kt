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

package com.h3nc4.hypergesture.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityAlertTest {

    @Test
    fun `a fresh install that never ran gestures is never alerted`() {
        assertFalse(shouldShowGesturesOffAlert(serviceBound = false, serviceEverEnabled = false))
    }

    @Test
    fun `a user whose running service is gone gets the way back`() {
        assertTrue(shouldShowGesturesOffAlert(serviceBound = false, serviceEverEnabled = true))
    }

    @Test
    fun `a bound service clears any alert`() {
        assertFalse(shouldShowGesturesOffAlert(serviceBound = true, serviceEverEnabled = true))
        assertFalse(shouldShowGesturesOffAlert(serviceBound = true, serviceEverEnabled = false))
    }
}
