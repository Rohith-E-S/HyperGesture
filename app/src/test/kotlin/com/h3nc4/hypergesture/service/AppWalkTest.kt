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

import org.junit.Assert.assertEquals
import org.junit.Test

class AppWalkTest {

    private fun visit(packageName: String, resumedAtMs: Long, dwellMs: Long) =
        AppVisit(packageName, className = null, resumedAtMs = resumedAtMs, dwellMs = dwellMs)

    private fun walk(
        visits: List<AppVisit>,
        currentPackage: String?,
        isTarget: (AppVisit, AppVisit?) -> Boolean = { _, _ -> true },
    ) = appWalk(visits, currentPackage, minimumDwellMs = 1_000L, isTarget = isTarget)

    @Test
    fun `a transient installer visit never becomes a switch target`() {
        // MIUI's installer resumes for a moment over the app and gives the front back.
        val visits = listOf(
            visit("app.a", 0L, 5_000L),
            visit("com.miui.securitycenter", 5_000L, 200L),
            visit("app.a", 5_200L, 5_000L),
        )
        assertEquals(listOf("app.a"), walk(visits, currentPackage = "app.a"))
    }

    @Test
    fun `a brief return to an app keeps it at the front of the walk`() {
        val visits = listOf(
            visit("app.c", 0L, 2_000L),
            visit("app.b", 2_000L, 3_000L),
            visit("app.c", 5_000L, 300L),
        )
        assertEquals(listOf("app.c", "app.b"), walk(visits, currentPackage = "app.c"))
    }

    @Test
    fun `each app appears once, where it was last really used`() {
        val visits = listOf(
            visit("app.a", 0L, 1_500L),
            visit("app.b", 1_500L, 1_500L),
            visit("app.a", 3_000L, 1_500L),
        )
        assertEquals(listOf("app.a", "app.b"), walk(visits, currentPackage = "app.a"))
    }

    @Test
    fun `an app in front that is not a target is left out of the walk`() {
        val visits = listOf(
            visit("app.a", 0L, 2_000L),
            visit("launcher", 2_000L, 3_000L),
        )
        assertEquals(
            listOf("app.a"),
            walk(visits, currentPackage = "launcher") { visit, _ -> visit.packageName != "launcher" },
        )
    }

    @Test
    fun `a package seen only briefly is dropped when it is not in front`() {
        val visits = listOf(
            visit("app.a", 0L, 2_000L),
            visit("prompt", 2_000L, 400L),
            visit("app.a", 2_400L, 2_000L),
        )
        assertEquals(listOf("app.a"), walk(visits, currentPackage = "app.a"))
    }

    @Test
    fun `the app in front is kept even when it has only just resumed`() {
        val visits = listOf(
            visit("app.a", 0L, 2_000L),
            visit("app.b", 2_000L, 300L),
        )
        assertEquals(listOf("app.b", "app.a"), walk(visits, currentPackage = "app.b"))
    }

    @Test
    fun `a target is judged with the visit that came before it`() {
        val visits = listOf(
            visit("app.a", 0L, 2_000L),
            visit("app.b", 2_000L, 2_000L),
            visit("app.c", 4_000L, 2_000L),
        )
        val seen = mutableListOf<Pair<String, String?>>()
        val apps = appWalk(visits, currentPackage = "app.c", minimumDwellMs = 1_000L) { visit, previous ->
            seen += visit.packageName to previous?.packageName
            true
        }
        assertEquals(listOf("app.c", "app.b", "app.a"), apps)
        assertEquals(
            listOf("app.c" to "app.b", "app.b" to "app.a", "app.a" to null),
            seen,
        )
    }
}
