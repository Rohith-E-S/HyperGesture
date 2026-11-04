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

package com.h3nc4.hypergesture.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureLogTest {

    @Test
    fun `formatEntry includes timestamp, label, device, and message`() {
        val entry = FailureLog.formatEntry(
            label = "test-label",
            message = "test failure detail",
            manufacturer = "Xiaomi",
            model = "Redmi 14",
            release = "16",
            sdkInt = 36,
            timestamp = "2026-11-04 12:00:00",
        )
        assertTrue(entry.contains("[2026-11-04 12:00:00] test-label"))
        assertTrue(entry.contains("device: Xiaomi Redmi 14"))
        assertTrue(entry.contains("Android 16 (API 36)"))
        assertTrue(entry.contains("message: test failure detail"))
    }

    @Test
    fun `formatEntry includes throwable stack trace`() {
        val exception = IllegalStateException("test explosion")
        val entry = FailureLog.formatEntry(
            label = "crash",
            throwable = exception,
            timestamp = "2026-11-04 12:00:00",
        )
        assertTrue(entry.contains("java.lang.IllegalStateException: test explosion"))
        assertTrue(entry.contains("---"))
    }

    @Test
    fun `combineEntries prepends new entry above existing entries`() {
        val newEntry = "[2026-11-04 12:01:00] new entry"
        val existing = "[2026-11-04 12:00:00] old entry"
        val combined = FailureLog.combineEntries(newEntry, existing)
        assertTrue(combined.startsWith(newEntry))
        assertTrue(combined.contains("========================================"))
        assertTrue(combined.endsWith(existing))
    }

    @Test
    fun `combineEntries with null or blank existing returns new entry`() {
        val newEntry = "[2026-11-04 12:01:00] new entry"
        assertEquals(newEntry, FailureLog.combineEntries(newEntry, null))
        assertEquals(newEntry, FailureLog.combineEntries(newEntry, "   "))
    }

    @Test
    fun `combineEntries respects maxBytes limit`() {
        val newEntry = "A".repeat(50)
        val existing = "B".repeat(100)
        val combined = FailureLog.combineEntries(newEntry, existing, maxBytes = 60)
        assertEquals(60, combined.length)
        assertTrue(combined.startsWith("A".repeat(50)))
    }
}
