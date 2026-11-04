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

import com.h3nc4.hypergesture.navigation.NavigationMode
import com.h3nc4.hypergesture.navigation.RecoveryState
import com.h3nc4.hypergesture.navigation.RecoveryStatus
import com.h3nc4.hypergesture.navigation.RecoveryTrigger
import com.h3nc4.hypergesture.ui.Assistant
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {

    @Test
    fun `formats comprehensive diagnostic report`() {
        val diagnostics = Diagnostics(
            androidRelease = "16",
            sdkInt = 36,
            manufacturer = "Xiaomi",
            model = "Redmi 14",
            hyperOsVersion = "OS3.0.1.0",
            miuiVersion = null,
            navigationMode = NavigationMode.GESTURAL,
            accessibilityServiceEnabled = true,
            accessibilityServiceSwitchedOn = true,
            accessibilityServiceRunning = true,
            overlaysInstalled = true,
            overlaysInteractive = true,
            navigationIntegrationId = "HyperOS",
            navigationIntegrationAvailable = true,
            lastGlobalActionFailure = null,
            lastAppSwitchFailure = null,
            lastCircleToSearchFailure = null,
            lastRecordedFailure = "[2026-11-04 12:00:00] test-failure\nsomething went wrong",
            appVersionName = "2.0.11",
        )
        val recoveryState = RecoveryState(
            enabled = true,
            status = RecoveryStatus.VERIFIED,
            lastTrigger = RecoveryTrigger.RESUME,
            lastAttemptAt = 123456789L,
        )

        val report = DiagnosticReport.format(
            diagnostics = diagnostics,
            recoveryState = recoveryState,
            usageAccess = true,
            assistant = Assistant.GOOGLE,
            batteryUnrestricted = true,
            timeFormatter = { "5 minutes ago" },
        )

        assertTrue(report.contains("### HyperGesture Diagnostics"))
        assertTrue(report.contains("- **App Version:** 2.0.11"))
        assertTrue(report.contains("- **Device:** Xiaomi Redmi 14"))
        assertTrue(report.contains("- **Android:** 16 (API 36)"))
        assertTrue(report.contains("- **HyperOS / MIUI:** OS3.0.1.0"))
        assertTrue(report.contains("- **Navigation Mode:** GESTURAL"))
        assertTrue(report.contains("- **Service Switched On:** true"))
        assertTrue(report.contains("- **Overlays Interactive:** true"))
        assertTrue(report.contains("- **Battery Optimization Ignored:** true"))
        assertTrue(report.contains("- **Usage Access:** true"))
        assertTrue(report.contains("- **Assistant:** GOOGLE"))
        assertTrue(report.contains("- **Recovery Enabled:** true"))
        assertTrue(report.contains("- **Recovery Status:** VERIFIED"))
        assertTrue(report.contains("- **Last Trigger:** RESUME"))
        assertTrue(report.contains("- **Last Attempt:** 5 minutes ago"))
        assertTrue(report.contains("[2026-11-04 12:00:00] test-failure\nsomething went wrong"))
        assertTrue(report.contains("````\n[2026-11-04 12:00:00] test-failure\nsomething went wrong\n````"))
    }

    @Test
    fun `format with null or blank recorded failure prints None`() {
        val diagnostics = Diagnostics(
            androidRelease = "16",
            appVersionName = "2.0.11",
            hyperOsVersion = "OS3.0.1.0",
            miuiVersion = null,
            manufacturer = "Xiaomi",
            model = "Redmi 14",
            sdkInt = 36,
            navigationMode = NavigationMode.GESTURAL,
            accessibilityServiceEnabled = true,
            accessibilityServiceSwitchedOn = true,
            accessibilityServiceRunning = true,
            overlaysInstalled = true,
            overlaysInteractive = true,
            navigationIntegrationId = "HyperOS",
            navigationIntegrationAvailable = true,
            lastGlobalActionFailure = null,
            lastAppSwitchFailure = null,
            lastCircleToSearchFailure = null,
            lastRecordedFailure = null,
        )
        val recoveryState = RecoveryState()
        val report = DiagnosticReport.format(
            diagnostics = diagnostics,
            recoveryState = recoveryState,
            usageAccess = false,
            assistant = Assistant.UNKNOWN,
            batteryUnrestricted = false,
        )
        assertTrue(report.contains("#### Recorded Failure Log\nNone"))
    }
}
