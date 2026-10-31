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

package com.h3nc4.hypergesture.setup

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.h3nc4.hypergesture.R
import com.h3nc4.hypergesture.diagnostics.DiagnosticsCollector
import com.h3nc4.hypergesture.diagnostics.FailureLog
import com.h3nc4.hypergesture.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * True only for a user who has already run the service and whose access is gone now.
 * A fresh install that never turned accessibility on must not be nagged at boot.
 */
internal fun shouldShowGesturesOffAlert(serviceBound: Boolean, serviceEverEnabled: Boolean): Boolean =
    !serviceBound && serviceEverEnabled

/**
 * Keeps a notification in the shade while gestures cannot run.
 *
 * HyperOS turns the accessibility switch off across a reboot, and the hidden navigation bar
 * then leaves no way back into settings. Swiping the app from Recents does not remove a
 * notification, so this one stays as the route to the switch.
 */
object AccessibilityAlert {

    const val CHANNEL_ID = "gestures_off"
    const val NOTIFICATION_ID = 1001

    private const val REQUEST_OPEN_APP = 1
    private const val REQUEST_TURN_ON = 2

    /** UiAutomation unbinds the service for a moment, so a stop has to outlast that. */
    private const val UNBIND_GRACE_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Posts when the service is not bound for a user who had it, and clears it otherwise. */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        try {
            val serviceEverEnabled = app.setupState.record.first().serviceEverEnabled
            val bound = DiagnosticsCollector.isAccessibilityServiceEnabled(app)
            if (shouldShowGesturesOffAlert(bound, serviceEverEnabled)) post(app) else cancel(app)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            // An unreadable DataStore must not take down a boot receiver or the worker.
            FailureLog.record(app, "accessibility alert", throwable)
        }
    }

    /** For an unbind, where the framework still reports the service as bound for a moment. */
    fun refreshSoon(context: Context) {
        val app = context.applicationContext
        scope.launch {
            delay(UNBIND_GRACE_MS)
            refresh(app)
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        // Android 13+ sends nothing until the prompt is granted, and a blocked channel is
        // silent too; skipping here keeps the check out of every caller.
        if (!manager.areNotificationsEnabled()) return
        createChannel(context)
        if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            return
        }
        manager.notify(NOTIFICATION_ID, notification(context))
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.alert_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun notification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(context.getString(R.string.alert_gestures_off_title))
            .setContentText(context.getString(R.string.alert_gestures_off_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.alert_gestures_off_body)))
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.action_turn_on), turnOn(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Every boot and periodic check refreshes the same notification without a new
            // sound or a new timestamp that would make it look like a fresh incident.
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setAutoCancel(false)
            .build()

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun turnOn(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_TURN_ON,
        GestureTileService.accessibilityIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
