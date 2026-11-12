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

import android.content.Context
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import com.h3nc4.hypergesture.gesture.ScreenEdge

/**
 * Read from `currentWindowMetrics`, never `resources.displayMetrics`: the latter can
 * report only the app-usable area, while the `rawX/rawY` the recognizer compares against
 * are full-display coordinates, and mixing the two breaks bottom-edge detection.
 */
internal data class ScreenGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val navLeftPx: Int,
    val navRightPx: Int,
    val navBottomPx: Int,
) {

    /** From the window bounds, which swap on every rotation the display goes through. */
    val landscape: Boolean get() = widthPx > heightPx

    companion object {
        fun current(context: Context, windowManager: WindowManager): ScreenGeometry {
            val metrics = windowManager.currentWindowMetrics
            val nav = metrics.windowInsets.getInsets(WindowInsets.Type.navigationBars())
            return ScreenGeometry(
                widthPx = metrics.bounds.width(),
                heightPx = metrics.bounds.height(),
                density = context.resources.displayMetrics.density,
                navLeftPx = nav.left,
                navRightPx = nav.right,
                navBottomPx = nav.bottom,
            )
        }
    }
}

/**
 * Strips stop short of each edge's full length so the status bar, app bar controls and
 * the bottom corners stay natively touchable.
 */
internal data class OverlayLayout(
    val sideLengthPercent: Int = 80,
    val bottomLengthPercent: Int = 90,
)

internal data class StripDimensions(
    val widthPx: Int,
    val heightPx: Int,
    val gravity: Int,
)

internal fun calculateStripDimensions(
    edge: ScreenEdge,
    edgeWidthDp: Float,
    geometry: ScreenGeometry,
    layout: OverlayLayout = OverlayLayout(),
): StripDimensions {
    val thicknessPx = (edgeWidthDp * geometry.density).toInt().coerceAtLeast(1)
    return when (edge) {
        ScreenEdge.LEFT -> StripDimensions(
            widthPx = thicknessPx + geometry.navLeftPx,
            heightPx = (geometry.heightPx * layout.sideLengthPercent / 100).coerceAtLeast(1),
            gravity = Gravity.START or Gravity.CENTER_VERTICAL,
        )
        ScreenEdge.RIGHT -> StripDimensions(
            widthPx = thicknessPx + geometry.navRightPx,
            heightPx = (geometry.heightPx * layout.sideLengthPercent / 100).coerceAtLeast(1),
            gravity = Gravity.END or Gravity.CENTER_VERTICAL,
        )
        ScreenEdge.BOTTOM -> StripDimensions(
            widthPx = (geometry.widthPx * layout.bottomLengthPercent / 100).coerceAtLeast(1),
            heightPx = thicknessPx + geometry.navBottomPx,
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        )
    }
}
