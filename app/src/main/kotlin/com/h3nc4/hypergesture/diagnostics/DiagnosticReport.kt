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

package com.h3nc4.hypergesture.diagnostics

import android.text.format.DateUtils
import com.h3nc4.hypergesture.navigation.RecoveryState
import com.h3nc4.hypergesture.ui.Assistant

internal object DiagnosticReport {

    fun format(
        diagnostics: Diagnostics,
        recoveryState: RecoveryState,
        usageAccess: Boolean,
        assistant: Assistant,
        batteryUnrestricted: Boolean,
        timeFormatter: (Long) -> CharSequence = { at ->
            DateUtils.getRelativeTimeSpanString(
                at,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
        },
    ): String = buildString {
        appendLine("### HyperGesture Diagnostics")
        appendLine("- **App Version:** ${diagnostics.appVersionName ?: "Unknown"}")
        val device = listOfNotNull(diagnostics.manufacturer, diagnostics.model).joinToString(" ").ifBlank { "Unknown" }
        appendLine("- **Device:** $device")
        appendLine("- **Android:** ${diagnostics.androidRelease ?: "Unknown"} (API ${diagnostics.sdkInt})")
        appendLine("- **HyperOS / MIUI:** ${diagnostics.hyperOsVersion ?: diagnostics.miuiVersion ?: "None"}")
        appendLine("- **Navigation Mode:** ${diagnostics.navigationMode.name}")
        appendLine("- **Navigation Integration:** ${diagnostics.navigationIntegrationId} (Available: ${diagnostics.navigationIntegrationAvailable})")
        appendLine()
        appendLine("#### Service & Overlays")
        appendLine("- **Service Switched On:** ${diagnostics.accessibilityServiceSwitchedOn}")
        appendLine("- **Service Bound:** ${diagnostics.accessibilityServiceEnabled}")
        appendLine("- **Service Running:** ${diagnostics.accessibilityServiceRunning}")
        appendLine("- **Overlays Installed:** ${diagnostics.overlaysInstalled}")
        appendLine("- **Overlays Interactive:** ${diagnostics.overlaysInteractive}")
        appendLine()
        appendLine("#### Permissions & Settings")
        appendLine("- **Battery Optimization Ignored:** $batteryUnrestricted")
        appendLine("- **Usage Access:** $usageAccess")
        appendLine("- **Assistant:** $assistant")
        appendLine()
        appendLine("#### Navigation Setting Recovery")
        appendLine("- **Recovery Enabled:** ${recoveryState.enabled}")
        appendLine("- **Recovery Status:** ${recoveryState.status.name}")
        appendLine("- **Last Trigger:** ${recoveryState.lastTrigger?.name ?: "None"}")
        recoveryState.lastAttemptAt?.let { at ->
            val relative = timeFormatter(at)
            appendLine("- **Last Attempt:** $relative")
        }
        appendLine()
        appendLine("#### Recent Problems")
        appendLine("- **Last Global Action Failure:** ${diagnostics.lastGlobalActionFailure ?: "None"}")
        appendLine("- **Last App Switch Failure:** ${diagnostics.lastAppSwitchFailure ?: "None"}")
        appendLine("- **Last Circle to Search Failure:** ${diagnostics.lastCircleToSearchFailure ?: "None"}")
        appendLine()
        appendLine("#### Recorded Failure Log")
        val recorded = diagnostics.lastRecordedFailure
        if (recorded.isNullOrBlank()) {
            appendLine("None")
        } else {
            appendLine("````")
            appendLine(recorded)
            appendLine("````")
        }
    }
}
