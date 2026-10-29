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
// along with this program.  If not, see <https://www.gnu.org/licenses/>.

package com.h3nc4.hypergesture.navigation

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.Settings

/**
 * Watches the two navigation settings and asks the runner to repair them when they drift.
 * The runner probes on its own dispatcher and does nothing when the values are still right.
 */
internal class NavigationSettingsObserver(
    private val context: Context,
    private val runner: NavigationRecoveryRunner,
    handler: Handler? = null,
) : ContentObserver(handler) {

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        runner.launch(RecoveryTrigger.SETTINGS_OBSERVER)
    }

    fun register() {
        runCatching {
            val resolver = context.contentResolver
            resolver.registerContentObserver(Settings.Global.getUriFor(FSG), false, this)
            resolver.registerContentObserver(Settings.Secure.getUriFor(NAVIGATION_MODE), false, this)
        }
    }

    fun unregister() {
        runCatching { context.contentResolver.unregisterContentObserver(this) }
    }

    private companion object {
        const val FSG = "force_fsg_nav_bar"
        const val NAVIGATION_MODE = "navigation_mode"
    }
}
