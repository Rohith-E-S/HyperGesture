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
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

private val Context.navigationRecoveryStore by preferencesDataStore("navigation_recovery")

/** Not on Android 8+'s manifest-receiver whitelist, so it is registered at runtime. */
internal const val ACTION_PREFERRED_ACTIVITY_CHANGED = "android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED"

/** HyperOS's watchdog resets the settings shortly after the broadcast that wakes it. */
internal const val WATCHDOG_GRACE_MS = 1_500L

enum class RecoveryStatus {
    DISABLED,
    SHIZUKU_UNAVAILABLE,
    PERMISSION_DENIED,
    VERIFIED,

    /** The shell could not run the repair, timed out, or exited non-zero. */
    COMMAND_FAILED,

    /** The shell ran, but reading the settings back did not show the expected values. */
    NOT_VERIFIED,
}

enum class RecoveryTrigger {
    MANUAL,
    RESUME,
    BOOT,
    SETTINGS_OBSERVER,
    PREFERRED_HOME,
    PACKAGE_CHANGE,
    PERIODIC,
}

data class RecoveryState(
    val enabled: Boolean = false,
    val status: RecoveryStatus = RecoveryStatus.DISABLED,
    val lastAttemptAt: Long? = null,
    val lastTrigger: RecoveryTrigger? = null,
)

/** What the runner needs from storage; tests implement this without DataStore. */
internal interface RecoveryStore {
    val state: Flow<RecoveryState>

    suspend fun isEnabled(): Boolean

    /** A null [status] records a check that found nothing to change. */
    suspend fun record(status: RecoveryStatus?, trigger: RecoveryTrigger, at: Long)
}

internal class NavigationRecoveryRepository(private val context: Context) : RecoveryStore {

    override val state: Flow<RecoveryState> = context.navigationRecoveryStore.data.map { preferences ->
        RecoveryState(
            enabled = preferences[ENABLED] ?: false,
            status = decodeRecoveryStatus(preferences[STATUS]),
            lastAttemptAt = preferences[LAST_ATTEMPT_AT],
            lastTrigger = decodeRecoveryTrigger(preferences[LAST_TRIGGER]),
        )
    }

    override suspend fun isEnabled(): Boolean = state.first().enabled

    suspend fun setEnabled(enabled: Boolean) {
        context.navigationRecoveryStore.edit { it[ENABLED] = enabled }
        if (enabled) {
            NavigationRecoveryScheduler.schedulePeriodic(context)
        } else {
            NavigationRecoveryScheduler.cancelPeriodic(context)
            record(RecoveryStatus.DISABLED, RecoveryTrigger.MANUAL, System.currentTimeMillis())
        }
    }

    override suspend fun record(status: RecoveryStatus?, trigger: RecoveryTrigger, at: Long) {
        context.navigationRecoveryStore.edit { preferences ->
            preferences[LAST_ATTEMPT_AT] = at
            preferences[LAST_TRIGGER] = trigger.name
            if (status != null) preferences[STATUS] = status.name
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val STATUS = stringPreferencesKey("status")
        val LAST_ATTEMPT_AT = longPreferencesKey("last_attempt_at")
        val LAST_TRIGGER = stringPreferencesKey("last_trigger")
    }
}

/**
 * Builds shipped with [RecoveryStatus.WRITE_FAILED], which did not distinguish a shell
 * failure from a write that did not stick; those records read as [RecoveryStatus.COMMAND_FAILED].
 */
internal fun decodeRecoveryStatus(name: String?): RecoveryStatus = when (name) {
    null -> RecoveryStatus.DISABLED
    "WRITE_FAILED" -> RecoveryStatus.COMMAND_FAILED
    else -> RecoveryStatus.entries.firstOrNull { it.name == name } ?: RecoveryStatus.DISABLED
}

internal fun decodeRecoveryTrigger(name: String?): RecoveryTrigger? =
    name?.let { stored -> RecoveryTrigger.entries.firstOrNull { it.name == stored } }

object NavigationRecoveryScheduler {
    const val PERIODIC_WORK = "navigation-recovery-periodic"

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<NavigationRecoveryWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            // KEEP: reopening the app or rebinding the service must not push the window back.
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }
}
