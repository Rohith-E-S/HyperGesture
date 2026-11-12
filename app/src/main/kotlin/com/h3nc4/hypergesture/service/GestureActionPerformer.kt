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
import android.util.Log
import android.widget.Toast
import com.h3nc4.hypergesture.R
import com.h3nc4.hypergesture.diagnostics.FailureLog
import com.h3nc4.hypergesture.gesture.GestureAction
import com.h3nc4.hypergesture.navigation.NavigationIntegration

/**
 * MIUI/HyperOS occasionally returns `false` from
 * [AccessibilityService.performGlobalAction] for no clear reason, so failures are
 * recorded for the diagnostics panel instead of looking like a recognizer bug.
 */
class GestureActionPerformer internal constructor(
    private val service: AccessibilityService,
    isAppSwitchEnabled: () -> Boolean = { true },
    private val globalActionPerformer: (Int) -> Boolean = service::performGlobalAction,
    private val switcher: AppSwitcher? = null,
    private val appStepPerformer: ((Boolean) -> Boolean)? = null,
    private val circleToSearchLauncher: () -> String? = {
        val assistantTag = NavigationIntegration.create(service).assistantAttributionTag
        CircleToSearchLauncher.launch(service, assistantTag)
    },
    private val failureRecorder: (String, Throwable?, String?) -> Unit = { label, throwable, message ->
        FailureLog.record(service, label, throwable = throwable, message = message)
    },
    private val toastDisplayer: () -> Unit = {
        runCatching {
            Toast.makeText(service, R.string.toast_circle_to_search_failed, Toast.LENGTH_LONG)
                .show()
        }
    },
) {

    private val activeSwitcher by lazy {
        switcher ?: AppSwitcher(service, isAppSwitchEnabled = isAppSwitchEnabled)
    }

    /** Called when a bottom-edge touch begins, so the recents list is ready by release. */
    fun prefetchAppSwitch() = activeSwitcher.prefetchTasks()

    /** Cancels the switcher scopes. Call when the owner is torn down. */
    fun dispose() = activeSwitcher.close()

    fun perform(action: GestureAction): Boolean {
        val globalAction = when (action) {
            GestureAction.Back -> AccessibilityService.GLOBAL_ACTION_BACK
            GestureAction.Home -> AccessibilityService.GLOBAL_ACTION_HOME
            GestureAction.Recents -> AccessibilityService.GLOBAL_ACTION_RECENTS
            // Android exposes no global action for these, so they launch instead.
            GestureAction.PreviousApp -> return appStepPerformer?.invoke(true) ?: activeSwitcher.step(toPrevious = true)
            GestureAction.NextApp -> return appStepPerformer?.invoke(false) ?: activeSwitcher.step(toPrevious = false)
            GestureAction.CircleToSearch -> return circleToSearch()
        }

        // performGlobalAction throws instead of returning false once the service has been
        // unbound; uncaught that kills the process and flags the service "malfunctioning".
        val performed = runCatching { globalActionPerformer(globalAction) }
            .getOrElse { throwable ->
                val message = "${action.id}: performGlobalAction threw ${throwable.javaClass.name}"
                lastFailure = message
                failureRecorder("global action", throwable, message)
                return false
            }
        if (!performed) {
            // No retry: a repeated global action can double-navigate.
            val message = "${action.id}: performGlobalAction returned false"
            lastFailure = message
            failureRecorder("global action", null, message)
        }
        return performed
    }

    // A missing default assistant is invisible from a gesture, so it is said on screen too.
    private fun circleToSearch(): Boolean {
        val problem = circleToSearchLauncher() ?: return true
        lastCircleToSearchFailure = problem
        failureRecorder("circle to search", null, problem)
        Log.w(TAG, "CIRCLE_TO_SEARCH_FAILED $problem")
        toastDisplayer()
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

        fun clearFailures() {
            lastCircleToSearchFailure = null
            lastFailure = null
        }
    }
}
