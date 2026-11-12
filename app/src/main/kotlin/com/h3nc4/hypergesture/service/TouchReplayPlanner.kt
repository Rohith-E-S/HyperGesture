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
import kotlin.math.hypot

internal data class ReplayPlan(
    val isTap: Boolean,
    val durationMs: Long,
    val timeoutMs: Long,
    val pathPoints: List<Pair<Float, Float>>,
)

internal object TouchReplayPlanner {
    const val TAP_SLOP_PX = 12f
    const val MIN_TAP_MS = 50L
    const val TAP_STROKE_CAP_MS = 60L
    const val MAX_REPLAY_MS = 3_000L
    const val WINDOW_SETTLE_MS = 65L
    const val FINISH_GRACE_MS = 1_000L

    fun plan(
        samples: List<TouchSample>,
        longPressTimeoutMs: Long = 400L,
    ): ReplayPlan? {
        if (samples.isEmpty()) return null

        val first = samples.first()
        val last = samples.last()
        val movedPx = hypot(last.xPx - first.xPx, last.yPx - first.yPx)
        val isTap = movedPx < TAP_SLOP_PX
        val rawDurationMs = last.timestampMs - first.timestampMs

        val pathPoints = if (isTap) {
            listOf(
                Pair(first.xPx, first.yPx),
                Pair(first.xPx + 1f, first.yPx + 1f),
            )
        } else {
            samples.map { Pair(it.xPx, it.yPx) }
        }

        val durationMs = when {
            !isTap -> rawDurationMs.coerceIn(1L, MAX_REPLAY_MS)
            rawDurationMs >= longPressTimeoutMs -> rawDurationMs.coerceAtMost(MAX_REPLAY_MS)
            else -> rawDurationMs.coerceIn(MIN_TAP_MS, TAP_STROKE_CAP_MS)
        }

        val timeoutMs = durationMs + WINDOW_SETTLE_MS + FINISH_GRACE_MS

        return ReplayPlan(
            isTap = isTap,
            durationMs = durationMs,
            timeoutMs = timeoutMs,
            pathPoints = pathPoints,
        )
    }
}
