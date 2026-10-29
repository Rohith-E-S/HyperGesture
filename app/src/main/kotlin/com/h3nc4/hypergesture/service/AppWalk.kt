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

/** One resumed activity and how long it stayed in front before another resumed. */
internal data class AppVisit(
    val packageName: String,
    val className: String?,
    val resumedAtMs: Long,
    val dwellMs: Long,
)

/**
 * The apps worth switching between, newest first, from every resumed activity.
 *
 * [visits] is chronological. A visit shorter than [minimumDwellMs] is a transient
 * activity - a package installer, a permission prompt - that resumed over an app and
 * returned to it. Such a visit must not enter the walk, or a sideways swipe would open
 * the installer's app where the user expected the app they left.
 *
 * Every package keeps its newest visit that did last, so an app that briefly showed a
 * transient activity is still ordered where the user left it. A valid app in front
 * always leads the list, even when its current visit is newer than the minimum, so a
 * quick Previous followed by Next returns to it; the caller reads its index and steps
 * from there.
 *
 * [isTarget] also receives the visit before the one being judged, so a caller can tell
 * how the app was entered - from home or recents rather than from another app.
 */
internal fun appWalk(
    visits: List<AppVisit>,
    currentPackage: String?,
    minimumDwellMs: Long,
    isTarget: (AppVisit, AppVisit?) -> Boolean,
): List<String> {
    val seen = mutableSetOf<String>()
    val newestFirst = ArrayDeque<String>()
    for (index in visits.indices.reversed()) {
        val visit = visits[index]
        if (visit.dwellMs < minimumDwellMs) continue
        if (!isTarget(visit, visits.getOrNull(index - 1))) continue
        if (!seen.add(visit.packageName)) continue
        newestFirst.addLast(visit.packageName)
    }
    val apps = newestFirst.toMutableList()
    if (currentPackage != null) {
        val index = apps.indexOf(currentPackage)
        if (index > 0) {
            apps.removeAt(index)
            apps.add(0, currentPackage)
        } else if (index < 0) {
            // The current visit may be too new to have lasted the minimum, but the app in
            // front is still where a Next step must return to.
            val currentIndex = visits.indexOfLast { it.packageName == currentPackage }
            val currentVisit = visits.getOrNull(currentIndex)
            if (currentVisit != null &&
                isTarget(currentVisit, visits.getOrNull(currentIndex - 1))
            ) {
                apps.add(0, currentPackage)
            }
        }
    }
    return apps
}
