// Copyright (C) 2026  Henrique Almeida <me@h3nc4.com>
// Copyright (C) 2026  Victor Eduardo (@victoresidoro)
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

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.h3nc4.hypergesture.R
import com.h3nc4.hypergesture.gesture.GestureAction
import com.h3nc4.hypergesture.navigation.NavigationIntegrationFactory

/**
 * MIUI/HyperOS occasionally returns `false` from
 * [AccessibilityService.performGlobalAction] for no clear reason, so failures are
 * recorded for the diagnostics panel instead of looking like a recognizer bug.
 */
class GestureActionPerformer(private val service: AccessibilityService) {

    private val switcher = AppSwitcher(service)

    private val assistantTag: String? by lazy {
        NavigationIntegrationFactory.create(service).assistantAttributionTag
    }

    /** Called when a bottom-edge touch begins, so the recents list is ready by release. */
    fun prefetchAppSwitch() = switcher.prefetchTasks()

    /**
     * MIUI animates `GLOBAL_ACTION_RECENTS` itself, with no option to suppress it. The
     * recents activity is exported, so starting it directly with NO_ANIMATION turns the
     * switch into a cut, like the app switch. Falls back to the global action when the
     * activity is not known yet or refuses the start.
     */
    private fun openRecents(): Boolean {
        val component = switcher.recentsComponent() ?: return false
        val intent = Intent().setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return runCatching { service.startActivity(intent) }
            .onFailure { Log.w(TAG, "Could not open recents without animation", it) }
            .isSuccess
    }

    fun perform(action: GestureAction): Boolean {
        if (action == GestureAction.Recents && openRecents()) return true
        val globalAction = when (action) {
            GestureAction.Back -> AccessibilityService.GLOBAL_ACTION_BACK
            GestureAction.Home -> AccessibilityService.GLOBAL_ACTION_HOME
            GestureAction.Recents -> AccessibilityService.GLOBAL_ACTION_RECENTS
            // Android exposes no global action for these, so they launch instead.
            GestureAction.PreviousApp -> return switcher.step(toPrevious = true)
            GestureAction.NextApp -> return switcher.step(toPrevious = false)
            GestureAction.CircleToSearch -> return circleToSearch()
        }

        // performGlobalAction throws instead of returning false once the service has been
        // unbound; uncaught that kills the process and flags the service "malfunctioning".
        val performed = runCatching { service.performGlobalAction(globalAction) }
            .getOrElse { throwable ->
                lastFailure = "${action.id}: performGlobalAction threw ${throwable.javaClass.name}"
                Log.w(TAG, "performGlobalAction(${action.id}) threw", throwable)
                return false
            }
        if (!performed) {
            // No retry: a repeated global action can double-navigate.
            lastFailure = "${action.id}: performGlobalAction returned false"
            Log.w(TAG, "performGlobalAction(${action.id}) returned false")
        }
        return performed
    }

    // A missing default assistant is invisible from a gesture, so it is said on screen too.
    private fun circleToSearch(): Boolean {
        val problem = CircleToSearchLauncher.launch(service, assistantTag) ?: return true
        lastCircleToSearchFailure = problem
        Log.w(TAG, "CIRCLE_TO_SEARCH_FAILED $problem")
        runCatching {
            Toast.makeText(service, R.string.toast_circle_to_search_failed, Toast.LENGTH_LONG)
                .show()
        }
        return false
    }

    companion object {
        private const val TAG = "HyperGesture"

        @Volatile
        var lastCircleToSearchFailure: String? = null
            private set

        @Volatile
        var lastFailure: String? = null
            private set
    }
}
