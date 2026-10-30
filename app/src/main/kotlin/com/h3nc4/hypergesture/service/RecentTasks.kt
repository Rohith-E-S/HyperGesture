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

import android.content.ComponentName
import android.os.SystemClock
import com.h3nc4.hypergesture.navigation.ShizukuAvailability
import com.h3nc4.hypergesture.navigation.ShizukuGateway
import com.h3nc4.hypergesture.navigation.ShizukuShellRunner
import com.h3nc4.hypergesture.navigation.SystemShizukuGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The recents stack the user actually sees, newest first, read through Shizuku.
 *
 * Without a privilege only usage events are available, and those are a history rather
 * than a stack: an app dismissed from recents keeps its events, so a Previous step would
 * reopen something the user closed. Shizuku runs this as the shell user, whose identity
 * may read the system task list - the same list the recents UI draws.
 *
 * Reads happen off the gesture thread. The service asks for a refresh when a bottom-edge
 * touch begins, which is usually finished by the time the swipe fires.
 */
internal class RecentTasksSource(
    private val shell: ShizukuShellRunner = ShizukuShellRunner(),
    private val gateway: ShizukuGateway = SystemShizukuGateway,
) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            android.util.Log.w("HyperGesture", "Could not read the recents stack", throwable)
        },
    )

    @Volatile
    private var packages: List<String>? = null

    @Volatile
    private var fetchedAt = 0L

    @Volatile
    private var component: ComponentName? = null

    private val refreshing = AtomicBoolean()

    /**
     * The activity the system uses for recents, when it has been read. It never changes
     * for an install, so it is kept without an age limit.
     */
    fun recentsComponent(): ComponentName? = component

    /** The last list, when it was fetched less than [maxAgeMs] ago; null when too old. */
    fun cached(maxAgeMs: Long): List<String>? {
        val age = SystemClock.elapsedRealtime() - fetchedAt
        return packages?.takeIf { age in 0..maxAgeMs }
    }

    /** Refreshes in the background; a call while one is still running is dropped. */
    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (gateway.availability() != ShizukuAvailability.Available) return@launch
                val result = shell.exec(COMMAND, TIMEOUT_SECONDS)
                if (result.timedOut || result.exitCode != 0) return@launch
                component = parseRecentsComponent(result.output)
                val parsed = parseRecentTasks(result.output)
                if (parsed.isNotEmpty()) {
                    packages = parsed
                    fetchedAt = SystemClock.elapsedRealtime()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                android.util.Log.w("HyperGesture", "Could not read the recents stack", throwable)
            } finally {
                refreshing.set(false)
            }
        }
    }

    private companion object {
        const val COMMAND = "dumpsys activity recents"
        const val TIMEOUT_SECONDS = 2L
    }
}

/**
 * Packages of the tasks the recents UI shows, newest first, from `dumpsys activity recents`.
 *
 * Only `type=standard` tasks are kept: home and the recents activity are not apps to
 * switch to. Tasks the launcher hides ([mHiddenTasks]) are skipped, and so are tasks the
 * system excludes or removes on its own. Each package appears once, at its newest task.
 */
internal fun parseRecentTasks(dump: String): List<String> {
    val hidden = dump.lineSequence()
        .firstOrNull { it.contains("mHiddenTasks=") }
        ?.let { line -> HIDDEN_ID.findAll(line).map { it.groupValues[1].toInt() }.toSet() }
        .orEmpty()

    val tasks = mutableListOf<ParsedTask>()
    var current: ParsedTask? = null
    for (line in dump.lineSequence()) {
        val header = HEADER.find(line)
        if (header != null) {
            current?.let(tasks::add)
            current = ParsedTask(
                id = header.groupValues[1].toInt(),
                type = header.groupValues[2],
            )
        }
        val task = current ?: continue
        COMPONENT.find(line)?.let { task.packageName = it.groupValues[1] }
        if (line.contains("isForceExcludedFromRecents=true") ||
            line.contains("autoRemoveRecents=true")
        ) {
            task.ignored = true
        }
    }
    current?.let(tasks::add)

    val seen = mutableSetOf<String>()
    return tasks.asSequence()
        .filter { it.type == "standard" && !it.ignored && it.id !in hidden }
        .mapNotNull { it.packageName }
        .filter { seen.add(it) }
        .toList()
}

/**
 * The recents activity component string named in [dump], or null when the line is absent.
 */
internal fun parseRecentsComponentString(dump: String): String? =
    RECENTS_COMPONENT.find(dump)?.groupValues?.get(1)

/**
 * The recents activity named in [dump], or null when the line is absent. It has no intent
 * filter, so it can only be learned from here rather than resolved with PackageManager.
 */
internal fun parseRecentsComponent(dump: String): ComponentName? =
    parseRecentsComponentString(dump)?.let(ComponentName::unflattenFromString)

private class ParsedTask(val id: Int, val type: String) {
    var packageName: String? = null
    var ignored = false
}

private val HEADER = Regex("""^\s*\* Recent #\d+: Task\{[0-9a-f]+ #(\d+) type=(\w+)""")

private val HIDDEN_ID = Regex("""#(\d+) type=""")

private val COMPONENT = Regex("""^\s*(?:mActivityComponent|realActivity)=([^/\s]+)/""")

private val RECENTS_COMPONENT = Regex("""mRecentsComponent=ComponentInfo\{([^}]+)\}""")
