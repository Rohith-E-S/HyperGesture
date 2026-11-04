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

package com.h3nc4.hypergesture.setup

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.h3nc4.hypergesture.R
import com.h3nc4.hypergesture.diagnostics.DiagnosticsCollector
import com.h3nc4.hypergesture.navigation.applyGesturesEnabled
import com.h3nc4.hypergesture.service.HyperGestureAccessibilityService
import com.h3nc4.hypergesture.settings.GestureSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

// The way back when HyperOS stops the service with the bar hidden, as Control Center still opens.
// Only the user may turn an accessibility service on. A tap on a stopped tile opens Settings.
// When running, a tap toggles gestures on or off, and a hold opens the app.
class GestureTileService : TileService() {

    private var listeningJob: Job? = null

    override fun onTileAdded() {
        recordAdded(true)
        refresh()
    }

    override fun onTileRemoved() = recordAdded(false)

    override fun onStartListening() {
        listening = WeakReference(this)
        // Only a tile in the panel listens, which also covers one added before this was recorded.
        recordAdded(true)
        val settings = GestureSettingsRepository(applicationContext)
        listeningJob?.cancel()
        listeningJob = writes.launch {
            settings.configuration.collect { config ->
                lastKnownGesturesEnabled = config.enabled
                withContext(Dispatchers.Main) {
                    refresh()
                }
            }
        }
        refresh()
    }

    private fun recordAdded(added: Boolean) {
        writes.launch { applicationContext.setupState.set(SetupFlag.TILE_ADDED, added) }
    }

    override fun onStopListening() {
        if (listening?.get() === this) listening = null
        listeningJob?.cancel()
        listeningJob = null
    }

    override fun onClick() {
        val face = currentFace()
        when (face.target) {
            TileTarget.TOGGLE -> toggleGestures()
            TileTarget.ACCESSIBILITY_SETTINGS -> {
                val target = accessibilityIntent(this)
                if (isLocked) unlockAndRun { open(target) } else open(target)
            }
        }
    }

    private fun toggleGestures() {
        writes.launch {
            toggleMutex.withLock {
                val next = !GestureSettingsRepository(applicationContext).read().enabled
                applyGesturesEnabled(applicationContext, next)
                lastKnownGesturesEnabled = next
                withContext(Dispatchers.Main) {
                    refresh()
                }
            }
        }
    }

    private fun currentFace() =
        TileFace.of(
            serviceEnabled = DiagnosticsCollector.isAccessibilityServiceEnabled(this),
            gesturesEnabled = lastKnownGesturesEnabled ?: true,
        )

    private fun refresh() {
        val tile = qsTile ?: return
        val face = currentFace()
        tile.state = if (face.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = getString(face.subtitle)
        tile.updateTile()
    }

    // The Intent overload throws on Android 14+, and the PendingIntent one is missing below it.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {

        fun component(context: Context) = ComponentName(context, GestureTileService::class.java)

        // Kept off the service, since the system may destroy it right after onTileRemoved.
        private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val toggleMutex = Mutex()

        @Volatile
        private var lastKnownGesturesEnabled: Boolean? = null

        // The system ignores requestListeningState for a tile that is already listening.
        @Volatile
        private var listening: WeakReference<GestureTileService>? = null

        /** Redraws the tile as the gesture service starts or stops, or configuration changes. */
        fun requestRefresh(context: Context, gesturesEnabled: Boolean? = null) {
            if (gesturesEnabled != null) {
                lastKnownGesturesEnabled = gesturesEnabled
            }
            val open = listening?.get()
            if (open != null) {
                open.refresh()
            } else {
                runCatching { requestListeningState(context, component(context)) }
            }
        }

        /** HyperGesture's own Accessibility page where the platform has one, else the full list. */
        fun accessibilityIntent(context: Context): Intent {
            val details = Intent(ACCESSIBILITY_DETAILS_SETTINGS).putExtra(
                Intent.EXTRA_COMPONENT_NAME,
                ComponentName(context, HyperGestureAccessibilityService::class.java)
                    .flattenToString(),
            )
            val target = context.packageManager.resolveActivity(details, 0)?.activityInfo
            val reachable = detailsReachable(target != null, target?.permission) {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
            return if (reachable) details else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }

        // Settings names it only from API 31, and minSdk 30 falls back to the full list.
        private const val ACCESSIBILITY_DETAILS_SETTINGS =
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    }
}
