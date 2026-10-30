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

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Walks the apps the user came from, which Android exposes to nobody: there is no global
 * action for it, so the app is launched outright. The order is the recents stack itself,
 * read through Shizuku - without it only usage events are available, which are a history
 * rather than a stack and still name an app after it was dismissed from recents.
 *
 * Starting an activity from here is a background start, allowed only because the service
 * holds a visible accessibility overlay. Measured on HyperOS as
 * `BAL_ALLOW_NON_APP_VISIBLE_WINDOW`, so the edge strips are what make this legal.
 *
 * Needs the usage access the user grants in Settings (and Shizuku for the real stack), and
 * reports [lastFailure] when it is missing so the diagnostics panel can say so rather than
 * the gesture looking broken.
 */
internal class AppSwitcher(
    private val context: Context,
    private val recents: RecentTasksSource = RecentTasksSource(),
) {

    /**
     * Starts reading the real recents stack in the background. Call when a bottom-edge
     * touch begins, so the list is ready by the time the swipe fires.
     */
    fun prefetchTasks() = recents.refresh()

    /** The system's recents activity, when its task list has been read. */
    fun recentsComponent(): ComponentName? = recents.recentsComponent()

    // The order this walk started from, newest first, and where in it the last step landed.
    // Kept rather than re-read because usage events lag by seconds: right after a launch the
    // event stream still names the previous app, so re-reading would keep resetting the walk
    // to the newest entry and the gesture would bounce between two apps.
    private var walk: List<String> = emptyList()
    private var position = 0
    private var lastStepAt = 0L
    private var lastStepWallClock = 0L
    private var lastTarget: String? = null

    // Whether each package runs as the system uid, which never changes for an install.
    private val privileged = mutableMapOf<String, Boolean>()

    /** A snapshot of the walk with the app that was in front when it was read. */
    private data class Walk(val apps: List<String>, val currentIndex: Int)

    fun step(toPrevious: Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        // A pause long enough, or a move by hand since the last step - home, recents, a
        // notification - means the user left the walk, so it restarts. Without the second
        // check a step within the window would continue the stale position and open the
        // wrong app: select something in recents, swipe, and the next swipe carries on
        // from where the previous chain stopped.
        val chained = walk.isNotEmpty() &&
            now - lastStepAt <= CHAIN_WINDOW_MS &&
            !movedByHandSince(lastStepWallClock, lastTarget)
        if (!chained) {
            val built = buildWalk()
            walk = built.apps
            // -1 when the app in front is not a switch target of its own, such as the
            // home screen or HyperGesture itself: the first step then lands on the newest
            // app rather than skipping past it.
            position = built.currentIndex
        }
        if (walk.isEmpty()) {
            val message = "app switch: no usage events, so usage access is probably not granted"
            lastFailure = message
            Log.w(TAG, message)
            return false
        }

        val next = position + if (toPrevious) 1 else -1
        if (next !in walk.indices) {
            lastFailure = "app switch: nothing ${if (toPrevious) "older" else "newer"} than this"
            return false
        }

        val target = walk[next]
        val intent = context.packageManager.getLaunchIntentForPackage(target)
        if (intent == null) {
            val message = "app switch: $target has no launcher entry"
            lastFailure = message
            Log.w(TAG, message)
            return false
        }
        // MIUI ignores a custom task animation and plays its own fixed slide from the
        // right whichever way the user swiped, so suppress it: the switch becomes a cut,
        // which at least follows no wrong direction.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        val launchedAt = System.currentTimeMillis()
        return runCatching { context.startActivity(intent) }
            .onFailure { throwable ->
                lastFailure = "app switch: starting $target threw ${throwable.javaClass.name}"
                Log.w(TAG, "startActivity($target) threw", throwable)
            }
            .onSuccess {
                position = next
                lastStepAt = now
                lastStepWallClock = launchedAt
                lastTarget = target
                lastFailure = null
            }
            .isSuccess
    }

    /**
     * True when an app other than the one this walk just launched has resumed since
     * [since].
     *
     * Usage events are read rather than the foreground window because the service keeps
     * no window access. An empty stream means the launch has not been recorded yet - the
     * normal lag right after a step - so the chain is kept rather than needlessly reset.
     */
    private fun movedByHandSince(since: Long, expected: String?): Boolean {
        if (since == 0L) return true
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return true
        val events = runCatching { usage.queryEvents(since, System.currentTimeMillis()) }
            .getOrNull() ?: return true
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val packageName = event.packageName ?: continue
            if (packageName != expected) return true
        }
        return false
    }

    /**
     * Apps by when each was last in front, newest first, every package once, and where in
     * that list the app currently in front sits (-1 when it is not one of them).
     *
     * Our own package is dropped, and so is every home screen. The home screen resumes
     * between every pair of apps, and many launchers - Niagara and Bitpit among them -
     * declare CATEGORY_LAUNCHER beside CATEGORY_HOME, so a launcher intent exists for them
     * and a plain launch-intent check leaves them in. A step would then land on the home
     * screen instead of the previous app. Anything with no launcher entry is dropped too,
     * and so is a visit too brief to be the user's own, such as a package installer or a
     * permission prompt that took the front for a moment and gave it back. A system app
     * is also dropped when the OS pushed it in front of another app, unless the user came
     * to it from home or recents, and the packages in [SYSTEM_SURFACES] are never targets.
     */
    private fun buildWalk(): Walk {
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return Walk(emptyList(), -1)
        val now = System.currentTimeMillis()
        val events = runCatching { usage.queryEvents(now - TRAIL_WINDOW_MS, now) }.getOrNull()
            ?: return Walk(emptyList(), -1)
        val homes = homeComponents()
        val defaultHome = defaultHomePackage()
        val resumed = mutableListOf<AppVisit>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            resumed.add(
                AppVisit(
                    packageName = event.packageName ?: continue,
                    className = event.className,
                    resumedAtMs = event.timeStamp,
                    dwellMs = 0L,
                ),
            )
        }
        // How long each activity held the front: until whatever resumed next, or until now.
        val visits = resumed.mapIndexed { index, visit ->
            val until = resumed.getOrNull(index + 1)?.resumedAtMs ?: now
            visit.copy(dwellMs = until - visit.resumedAtMs)
        }
        // The app in front, even when it is the home screen or us and so not a target.
        val currentPackage = visits.lastOrNull()?.packageName
        val launchable = mutableMapOf<String, Boolean>()
        fun switchable(visit: AppVisit): Boolean =
            visit.packageName != context.packageName &&
                visit.packageName != defaultHome &&
                (visit.className == null ||
                    ComponentName(visit.packageName, visit.className) !in homes) &&
                launchable.getOrPut(visit.packageName) {
                    context.packageManager.getLaunchIntentForPackage(visit.packageName) != null
                }
        // The system task list is the recents stack itself, so it matches what the user
        // sees and leaves out anything dismissed there. Usage events are the fallback for
        // when Shizuku is unavailable.
        val tasks = recents.cached(TASKS_MAX_AGE_MS)
        if (tasks != null) {
            val stacked = tasks.asSequence()
                .filter { it != context.packageName && it != defaultHome }
                .filter { it !in SYSTEM_SURFACES }
                .filter {
                    launchable.getOrPut(it) {
                        context.packageManager.getLaunchIntentForPackage(it) != null
                    }
                }
                .distinct()
                .toList()
            if (stacked.isNotEmpty()) {
                return Walk(stacked, currentPackage?.let { stacked.indexOf(it) } ?: -1)
            }
        }

        val apps = appWalk(visits, currentPackage, MIN_FOREGROUND_MS) { visit, previous ->
            visit.packageName !in SYSTEM_SURFACES &&
                switchable(visit) &&
                (visit.packageName == currentPackage ||
                    !isPrivileged(visit.packageName) ||
                    (previous != null && !switchable(previous)))
        }
        val currentIndex = currentPackage?.let { apps.indexOf(it) } ?: -1
        return Walk(apps, currentIndex)
    }

    /**
     * An app running under the system uid is part of the OS, so the system can put it in
     * front without the user asking. Such a visit is only trusted when the user arrived
     * at it from outside the walk - home, recents, the keyguard - rather than from
     * another app the system was running over. [SYSTEM_SURFACES] covers the cases even
     * that lets past.
     */
    private fun isPrivileged(packageName: String): Boolean = privileged.getOrPut(packageName) {
        runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).uid <
                Process.FIRST_APPLICATION_UID
        }.getOrDefault(false)
    }

    /** Every activity that can act as home, including launchers that also run as apps. */
    private fun homeComponents(): Set<ComponentName> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { resolveInfo ->
                    resolveInfo.activityInfo?.let { ComponentName(it.packageName, it.name) }
                }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /** The launcher in use, whose every screen belongs to home rather than to the walk. */
    private fun defaultHomePackage(): String? = runCatching {
        context.packageManager
            .resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
            ?.activityInfo?.packageName
    }.getOrNull()

    companion object {
        private const val TAG = "HyperGesture"
        private const val TRAIL_WINDOW_MS = 6L * 60L * 60L * 1000L

        /** How long a chain of swipes keeps walking one order before it restarts. */
        private const val CHAIN_WINDOW_MS = 5_000L

        /**
         * How long a task-list read is trusted. It is refreshed when a bottom-edge touch
         * begins; a touch that lasts longer than this falls back to the usage walk.
         */
        private const val TASKS_MAX_AGE_MS = 5_000L

        /**
         * Packages that only reach the front because the system put them there, which no
         * usage-event check can tell apart from a user launch.
         *
         * MIUI's Security app opens for package installs and for its own scans, even over
         * the home screen. It shares the system uid and exposes a launcher activity, so
         * every other condition here still lets it in - including a scan that resumed
         * right after home, indistinguishable from tapping its icon. A sideways swipe is
         * never meant to open it.
         */
        private val SYSTEM_SURFACES = setOf("com.miui.securitycenter")

        /**
         * A resumed activity in front for less than this is a transient overlay, such as
         * MIUI's package installer or a permission prompt. It resumes over an app and
         * returns to it, so counting it would offer its package as the previous app.
         */
        private const val MIN_FOREGROUND_MS = 1_000L

        @Volatile
        var lastFailure: String? = null
            private set
    }
}
