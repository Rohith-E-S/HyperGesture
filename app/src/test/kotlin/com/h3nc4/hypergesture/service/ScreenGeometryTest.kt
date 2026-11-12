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

import android.view.Gravity
import com.h3nc4.hypergesture.gesture.ScreenEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenGeometryTest {

    @Test
    fun `portrait screen width less than height means landscape is false`() {
        val geometry = ScreenGeometry(
            widthPx = 1080,
            heightPx = 2400,
            density = 2.75f,
            navLeftPx = 0,
            navRightPx = 0,
            navBottomPx = 48,
        )
        assertFalse(geometry.landscape)
    }

    @Test
    fun `landscape screen width greater than height means landscape is true`() {
        val geometry = ScreenGeometry(
            widthPx = 2400,
            heightPx = 1080,
            density = 2.75f,
            navLeftPx = 0,
            navRightPx = 48,
            navBottomPx = 0,
        )
        assertTrue(geometry.landscape)
    }

    @Test
    fun `square screen width equals height means landscape is false`() {
        val geometry = ScreenGeometry(
            widthPx = 1080,
            heightPx = 1080,
            density = 2.0f,
            navLeftPx = 0,
            navRightPx = 0,
            navBottomPx = 0,
        )
        assertFalse(geometry.landscape)
    }

    @Test
    fun `default OverlayLayout values are 80 percent side and 90 percent bottom`() {
        val layout = OverlayLayout()
        assertEquals(80, layout.sideLengthPercent)
        assertEquals(90, layout.bottomLengthPercent)
    }

    @Test
    fun `left strip thickness includes density scaling and left nav inset`() {
        val geometry = ScreenGeometry(
            widthPx = 1080,
            heightPx = 2000,
            density = 3f,
            navLeftPx = 40,
            navRightPx = 0,
            navBottomPx = 0,
        )
        // edgeWidthDp = 20 -> thickness = 20 * 3 = 60; navLeft = 40 -> total width = 100
        val dims = calculateStripDimensions(
            edge = ScreenEdge.LEFT,
            edgeWidthDp = 20f,
            geometry = geometry,
            layout = OverlayLayout(sideLengthPercent = 80),
        )
        assertEquals(100, dims.widthPx)
        assertEquals(1600, dims.heightPx)
        assertEquals(Gravity.START or Gravity.CENTER_VERTICAL, dims.gravity)
    }

    @Test
    fun `right strip thickness includes density scaling and right nav inset`() {
        val geometry = ScreenGeometry(
            widthPx = 1080,
            heightPx = 2000,
            density = 3f,
            navLeftPx = 0,
            navRightPx = 50,
            navBottomPx = 0,
        )
        // edgeWidthDp = 20 -> thickness = 60; navRight = 50 -> total width = 110
        val dims = calculateStripDimensions(
            edge = ScreenEdge.RIGHT,
            edgeWidthDp = 20f,
            geometry = geometry,
            layout = OverlayLayout(sideLengthPercent = 80),
        )
        assertEquals(110, dims.widthPx)
        assertEquals(1600, dims.heightPx)
        assertEquals(Gravity.END or Gravity.CENTER_VERTICAL, dims.gravity)
    }

    @Test
    fun `side strip height scales with sideLengthPercent`() {
        val geometry = ScreenGeometry(
            widthPx = 1080,
            heightPx = 2000,
            density = 2f,
            navLeftPx = 0,
            navRightPx = 0,
            navBottomPx = 0,
        )
        val dims = calculateStripDimensions(
            edge = ScreenEdge.LEFT,
            edgeWidthDp = 10f,
            geometry = geometry,
            layout = OverlayLayout(sideLengthPercent = 50),
        )
        assertEquals(1000, dims.heightPx)
    }

    @Test
    fun `bottom strip dimensions scale with bottomLengthPercent and navBottom inset`() {
        val geometry = ScreenGeometry(
            widthPx = 1080,
            heightPx = 2400,
            density = 3f,
            navLeftPx = 0,
            navRightPx = 0,
            navBottomPx = 70,
        )
        // width = 1080 * 90 / 100 = 972; thickness = 20 * 3 = 60 + 70 = 130
        val dims = calculateStripDimensions(
            edge = ScreenEdge.BOTTOM,
            edgeWidthDp = 20f,
            geometry = geometry,
            layout = OverlayLayout(bottomLengthPercent = 90),
        )
        assertEquals(972, dims.widthPx)
        assertEquals(130, dims.heightPx)
        assertEquals(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, dims.gravity)
    }
}
