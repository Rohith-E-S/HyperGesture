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

package com.h3nc4.hypergesture.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.h3nc4.hypergesture.gesture.GestureConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.gestureDataStore: DataStore<Preferences> by
    preferencesDataStore(
        name = "gesture_configuration",
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    )

/** Defaults are never duplicated here: a missing key falls back to [GestureConfiguration]. */
class GestureSettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val debugAppSwitch: () -> Boolean? = { null },
    private val debugRecentsHold: () -> Long? = { null },
) {
    constructor(context: Context) : this(
        dataStore = context.gestureDataStore,
        debugAppSwitch = { debugAppSwitchEnabled(context) },
        debugRecentsHold = { debugRecentsHoldMs(context) },
    )

    val configuration: Flow<GestureConfiguration> =
        dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map(::toConfiguration)

    suspend fun read(): GestureConfiguration = configuration.first()

    suspend fun update(transform: (GestureConfiguration) -> GestureConfiguration) {
        // Atomic read-modify-write on stored values: debug overrides apply to reads
        // only, so they never reach storage through a transform that preserves them.
        dataStore.updateData { preferences ->
            val updated = transform(toStoredConfiguration(preferences))
            preferences.toMutablePreferences().apply {
                this[ENABLED] = updated.enabled
                this[EDGE_WIDTH_DP] = updated.edgeWidthDp
                this[SIDE_MIN_DISTANCE_DP] = updated.sideMinimumSwipeDistanceDp
                this[BOTTOM_MIN_DISTANCE_DP] = updated.bottomMinimumSwipeDistanceDp
                this[MAX_ARM_DURATION_MS] = updated.maximumArmDurationMs
                this[RECENTS_HOLD_MS] = updated.recentsHoldMs
                this[HOLD_STILLNESS_DP] = updated.holdStillnessDp
                this[OFF_AXIS_TOLERANCE] = updated.offAxisToleranceRatio
                this[APP_SWITCH] = updated.appSwitchEnabled
                this[LEFT_EDGE_BACK] = updated.leftEdgeBackEnabled
                this[RIGHT_EDGE_BACK] = updated.rightEdgeBackEnabled
                this[HAPTIC_FEEDBACK] = updated.hapticFeedbackEnabled
                this[LANDSCAPE_CONFIRM] = updated.landscapeConfirmEnabled
                this[CIRCLE_TO_SEARCH] = updated.circleToSearchEnabled
                this[CIRCLE_TO_SEARCH_SYSTEM_TIMING] = updated.circleToSearchFollowsSystemTiming
                this[CIRCLE_TO_SEARCH_HOLD_MS] = updated.circleToSearchHoldMs
            }
        }
    }

    private fun toConfiguration(preferences: Preferences): GestureConfiguration =
        storedConfiguration(preferences, debugRecentsHold(), debugAppSwitch())

    private fun toStoredConfiguration(preferences: Preferences): GestureConfiguration =
        storedConfiguration(preferences, recentsHoldOverride = null, appSwitchOverride = null)

    private fun storedConfiguration(
        preferences: Preferences,
        recentsHoldOverride: Long?,
        appSwitchOverride: Boolean?,
    ): GestureConfiguration {
        val defaults = GestureConfiguration()
        val storedHoldMs = preferences[RECENTS_HOLD_MS] ?: defaults.recentsHoldMs
        return GestureConfiguration(
            enabled = preferences[ENABLED] ?: defaults.enabled,
            edgeWidthDp = preferences[EDGE_WIDTH_DP] ?: defaults.edgeWidthDp,
            sideMinimumSwipeDistanceDp =
                preferences[SIDE_MIN_DISTANCE_DP] ?: defaults.sideMinimumSwipeDistanceDp,
            bottomMinimumSwipeDistanceDp =
                preferences[BOTTOM_MIN_DISTANCE_DP] ?: defaults.bottomMinimumSwipeDistanceDp,
            maximumArmDurationMs =
                preferences[MAX_ARM_DURATION_MS] ?: defaults.maximumArmDurationMs,
            recentsHoldMs = recentsHoldOverride ?: storedHoldMs,
            holdStillnessDp = preferences[HOLD_STILLNESS_DP] ?: defaults.holdStillnessDp,
            offAxisToleranceRatio =
                preferences[OFF_AXIS_TOLERANCE] ?: defaults.offAxisToleranceRatio,
            appSwitchEnabled = appSwitchOverride ?: (preferences[APP_SWITCH] ?: defaults.appSwitchEnabled),
            leftEdgeBackEnabled = preferences[LEFT_EDGE_BACK] ?: defaults.leftEdgeBackEnabled,
            rightEdgeBackEnabled = preferences[RIGHT_EDGE_BACK] ?: defaults.rightEdgeBackEnabled,
            hapticFeedbackEnabled = preferences[HAPTIC_FEEDBACK] ?: defaults.hapticFeedbackEnabled,
            landscapeConfirmEnabled =
                preferences[LANDSCAPE_CONFIRM] ?: defaults.landscapeConfirmEnabled,
            circleToSearchEnabled =
                preferences[CIRCLE_TO_SEARCH] ?: defaults.circleToSearchEnabled,
            circleToSearchFollowsSystemTiming = preferences[CIRCLE_TO_SEARCH_SYSTEM_TIMING]
                ?: defaults.circleToSearchFollowsSystemTiming,
            circleToSearchHoldMs =
                preferences[CIRCLE_TO_SEARCH_HOLD_MS] ?: defaults.circleToSearchHoldMs,
        )
    }

    private companion object {
        const val RECENTS_HOLD_OVERRIDE = "hypergesture_recents_hold_ms"
        const val APP_SWITCH_OVERRIDE = "hypergesture_app_switch"

        /**
         * Test seam that spares the debug emulator run a tap on the toggle. Debug only.
         * The FLAG_DEBUGGABLE gate ensures release builds never query Settings.Global.
         * Exercised in emulator/device E2E suites; on JVM tests, simulated via the functional seam.
         */
        private fun debugAppSwitchEnabled(context: Context): Boolean? {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            return runCatching {
                Settings.Global.getString(context.contentResolver, APP_SWITCH_OVERRIDE)?.let {
                    it == "1" || it.equals("true", ignoreCase = true)
                }
            }.getOrNull()
        }

        /**
         * Test seam for the emulator suite, which cannot deliver a gap-free touch stream: on a
         * loaded machine input stalls past the hold window and a continuous swipe is read as a
         * hold. Only consulted in a debuggable build, so a release APK never reaches it.
         * Exercised in emulator/device E2E suites; on JVM tests, simulated via the functional seam.
         */
        private fun debugRecentsHoldMs(context: Context): Long? {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            return runCatching {
                Settings.Global.getString(context.contentResolver, RECENTS_HOLD_OVERRIDE)?.toLong()
            }.getOrNull()
        }

        val ENABLED = booleanPreferencesKey("gestures_enabled")
        val EDGE_WIDTH_DP = floatPreferencesKey("edge_width_dp")
        val SIDE_MIN_DISTANCE_DP = floatPreferencesKey("side_minimum_swipe_distance_dp")
        val BOTTOM_MIN_DISTANCE_DP = floatPreferencesKey("bottom_minimum_swipe_distance_dp")
        val MAX_ARM_DURATION_MS = longPreferencesKey("maximum_arm_duration_ms")
        val RECENTS_HOLD_MS = longPreferencesKey("recents_hold_ms")
        val HOLD_STILLNESS_DP = floatPreferencesKey("hold_stillness_dp")
        val OFF_AXIS_TOLERANCE = floatPreferencesKey("off_axis_tolerance_ratio")
        val APP_SWITCH = booleanPreferencesKey("app_switch_enabled")
        val LEFT_EDGE_BACK = booleanPreferencesKey("left_edge_back_enabled")
        val RIGHT_EDGE_BACK = booleanPreferencesKey("right_edge_back_enabled")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback_enabled")
        val LANDSCAPE_CONFIRM = booleanPreferencesKey("landscape_confirm_enabled")
        val CIRCLE_TO_SEARCH = booleanPreferencesKey("circle_to_search_enabled")
        val CIRCLE_TO_SEARCH_SYSTEM_TIMING =
            booleanPreferencesKey("circle_to_search_follows_system_timing")
        val CIRCLE_TO_SEARCH_HOLD_MS = longPreferencesKey("circle_to_search_hold_ms")
    }
}
