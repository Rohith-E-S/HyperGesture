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

package com.h3nc4.hypergesture.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.h3nc4.hypergesture.navigation.NavigationIntegrationFactory
import com.h3nc4.hypergesture.navigation.NavigationMode
import com.h3nc4.hypergesture.navigation.NavigationRecoveryRuntime
import com.h3nc4.hypergesture.navigation.RecoveryTrigger
import com.h3nc4.hypergesture.setup.AccessibilityAlert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Checks the post-boot navigation state and queues recovery only when the opt-in is enabled.
 * Android rebinds the accessibility service on boot by itself.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NavigationRecoveryRuntime.runner(appContext).run(RecoveryTrigger.BOOT, force = true)
                // Read after recovery, or the log describes the state it just repaired.
                val status = NavigationIntegrationFactory.create(appContext).status()
                if (status.available && status.currentMode != NavigationMode.GESTURAL) {
                    Log.i(
                        TAG,
                        "Navigation mode after boot is ${status.currentMode}; " +
                            "gestural navigation was not restored by the system.",
                    )
                }
                // HyperOS can revoke accessibility across a reboot. The alert is the only
                // route back once the navigation bar is hidden and the app leaves Recents.
                AccessibilityAlert.refresh(appContext)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "HyperGesture"
    }
}
