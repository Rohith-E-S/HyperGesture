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

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Logcat cannot be trusted on the target ROM: its global `log.tag` threshold drops debug
 * and info logs, its `adb shell` lacks `WRITE_SECURE_SETTINGS`, and DropBox stayed empty —
 * a crash can be invisible from a host machine. So the stack trace goes to app storage:
 * `adb shell run-as com.h3nc4.hypergesture cat files/last_failure.txt` (debug builds).
 */
object FailureLog {

    private const val TAG = "HyperGesture"
    private const val FILE_NAME = "last_failure.txt"
    private const val MAX_BYTES = 32 * 1024

    fun record(context: Context, label: String, throwable: Throwable) =
        record(context, label, throwable = throwable, message = null)

    fun record(context: Context, label: String, message: String) =
        record(context, label, throwable = null, message = message)

    fun record(
        context: Context,
        label: String,
        throwable: Throwable? = null,
        message: String? = null,
        timestamp: String = currentTimestamp(),
    ) {
        if (throwable != null) {
            Log.e(TAG, "FAILURE [$label] ${message.orEmpty()}", throwable)
        } else {
            Log.w(TAG, "FAILURE [$label] ${message.orEmpty()}")
        }
        val entry = formatEntry(
            label = label,
            throwable = throwable,
            message = message,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            release = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            timestamp = timestamp,
        )
        runCatching {
            val target = file(context)
            val existing = if (target.exists()) target.readText().trim() else null
            val combined = combineEntries(entry, existing, MAX_BYTES)
            target.writeText(combined)
        }.onFailure { Log.e(TAG, "Could not persist the failure record", it) }
    }

    fun read(context: Context): String? = runCatching {
        val file = file(context)
        if (file.exists()) file.readText().ifBlank { null } else null
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    internal fun formatEntry(
        label: String,
        throwable: Throwable? = null,
        message: String? = null,
        manufacturer: String? = null,
        model: String? = null,
        release: String? = null,
        sdkInt: Int = 0,
        timestamp: String = currentTimestamp(),
    ): String = buildString {
        appendLine("[$timestamp] $label")
        val devInfo = listOfNotNull(manufacturer, model).joinToString(" ").ifBlank { null }
        val osInfo = if (release != null || sdkInt > 0) "Android $release (API $sdkInt)" else null
        val meta = listOfNotNull(devInfo?.let { "device: $it" }, osInfo).joinToString(" | ")
        if (meta.isNotBlank()) {
            appendLine(meta)
        }
        if (!message.isNullOrBlank()) {
            appendLine("message: $message")
        }
        if (throwable != null) {
            appendLine("---")
            append(throwable.stackTraceToString().trimEnd())
        }
    }.trimEnd()

    internal fun combineEntries(newEntry: String, existing: String?, maxBytes: Int = MAX_BYTES): String {
        val base = if (existing.isNullOrBlank()) {
            newEntry
        } else {
            "$newEntry\n\n========================================\n\n$existing"
        }
        return base.take(maxBytes)
    }

    internal fun currentTimestamp(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
