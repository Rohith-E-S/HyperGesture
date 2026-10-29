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
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.h3nc4.hypergesture.navigation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Only `MY_PACKAGE_REPLACED` reaches this static receiver: the other package actions and
 * `ACTION_PREFERRED_ACTIVITY_CHANGED` are implicit broadcasts that a manifest receiver
 * cannot receive on Android 8+. They are registered at runtime in the accessibility
 * service instead.
 */
class NavigationRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!shouldHandle(intent.action)) return

        val pending = goAsync()
        val runner = NavigationRecoveryRuntime.runner(context.applicationContext)
        // Force: a self-update is exactly when the vendor watchdog rewrites the settings.
        runner.launch(RecoveryTrigger.PACKAGE_CHANGE, force = true)
            .invokeOnCompletion { pending.finish() }
        // Best effort: the delayed pass is not awaited, so goAsync stays well under 10 s.
        runner.launch(RecoveryTrigger.PACKAGE_CHANGE, delayMs = WATCHDOG_GRACE_MS)
    }
}

internal fun shouldHandle(action: String?): Boolean = action == Intent.ACTION_MY_PACKAGE_REPLACED
