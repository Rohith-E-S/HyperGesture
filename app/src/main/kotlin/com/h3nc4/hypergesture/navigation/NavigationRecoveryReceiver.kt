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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NavigationRecoveryScheduler.runDirectIfEnabled(context)
                // The vendor watchdog may reset the settings after the broadcast lands.
                delay(WATCHDOG_GRACE_MS)
                NavigationRecoveryScheduler.runDirectIfEnabled(context)
            } finally {
                pending.finish()
            }
        }
    }
}

internal fun shouldHandle(action: String?): Boolean = action == Intent.ACTION_MY_PACKAGE_REPLACED
