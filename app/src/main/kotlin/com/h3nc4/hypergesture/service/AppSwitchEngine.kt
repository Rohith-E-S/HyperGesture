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

internal sealed class StepResult {
    data class Success(val target: String, val nextIndex: Int) : StepResult()
    data class Failure(val message: String) : StepResult()
}

internal class AppSwitchEngine(
    private val isAppSwitchEnabled: () -> Boolean = { true },
) {
    var walk: List<String> = emptyList()
        private set
    var position: Int = 0
        private set
    var lastStepAt: Long = 0L
        private set
    var lastStepWallClock: Long = 0L
        private set
    var lastTarget: String? = null
        private set
    var lastFailure: String? = null
        private set

    fun resetWalk(newWalk: List<String>, initialPosition: Int) {
        walk = newWalk
        position = initialPosition
    }

    fun isChained(now: Long, movedByHand: Boolean): Boolean {
        if (walk.isEmpty()) return false
        val elapsed = now - lastStepAt
        return elapsed in 0..CHAIN_WINDOW_MS && !movedByHand
    }

    fun computeNextStep(toPrevious: Boolean): StepResult {
        if (!isAppSwitchEnabled()) {
            return StepResult.Failure("app switch: disabled")
        }
        if (walk.isEmpty()) {
            val message = "app switch: no usage events, so usage access is probably not granted"
            lastFailure = message
            return StepResult.Failure(message)
        }
        val next = position + if (toPrevious) 1 else -1
        if (next !in walk.indices) {
            val message = "app switch: nothing ${if (toPrevious) "older" else "newer"} than this"
            lastFailure = message
            return StepResult.Failure(message)
        }
        val target = walk[next]
        return StepResult.Success(target = target, nextIndex = next)
    }

    fun commitStep(nextIndex: Int, target: String, steppedAt: Long, launchedAt: Long) {
        position = nextIndex
        lastStepAt = steppedAt
        lastStepWallClock = launchedAt
        lastTarget = target
        lastFailure = null
    }

    fun setFailure(message: String) {
        lastFailure = message
    }

    companion object {
        const val CHAIN_WINDOW_MS = 5_000L
        val SYSTEM_SURFACES = setOf("com.miui.securitycenter")

        fun filterRecents(
            tasks: List<String>,
            selfPackage: String,
            defaultHome: String?,
            isLaunchable: (String) -> Boolean,
        ): List<String> {
            val seen = mutableSetOf<String>()
            return tasks.asSequence()
                .filter { it != selfPackage && it != defaultHome }
                .filter { it !in SYSTEM_SURFACES }
                .filter { isLaunchable(it) }
                .filter { seen.add(it) }
                .toList()
        }
    }
}
