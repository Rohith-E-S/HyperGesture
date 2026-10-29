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
package com.h3nc4.hypergesture.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationSettingTargetsTest {
    @Test
    fun onlyTheTwoOptedInNavigationValuesAreVerified() {
        assertEquals(
            listOf(
                SettingTarget("global", "force_fsg_nav_bar", "1"),
                SettingTarget("secure", "navigation_mode", "2"),
            ),
            NavigationSettingTargets.all,
        )
        assertTrue(
            NavigationSettingTargets.verified(
                mapOf("force_fsg_nav_bar" to "1", "navigation_mode" to "2"),
            ),
        )
        assertFalse(
            NavigationSettingTargets.verified(
                mapOf("force_fsg_nav_bar" to "1", "navigation_mode" to "0"),
            ),
        )
        assertFalse(NavigationSettingTargets.verified(mapOf("force_fsg_nav_bar" to "1")))
        assertFalse(NavigationSettingTargets.verified(mapOf("force_fsg_nav_bar" to null, "navigation_mode" to "2")))
    }

    @Test
    fun theGestureFlagIsVerifiedOnlyOnHyperOs() {
        val correct = mapOf("force_fsg_nav_bar" to "1", "navigation_mode" to "2")
        assertTrue(NavigationSettingTargets.verified(correct))
        // A build that does not expose the flag (null) is not a mismatch.
        assertTrue(NavigationSettingTargets.verified(correct, includeHyperOs = true))
        assertTrue(
            NavigationSettingTargets.verified(
                correct + ("use_gesture_version_three" to "1"),
                includeHyperOs = true,
            ),
        )
        assertTrue(
            "a wrong flag is ignored when HyperOS is not requested",
            NavigationSettingTargets.verified(correct + ("use_gesture_version_three" to "0")),
        )
        assertFalse(
            NavigationSettingTargets.verified(
                correct + ("use_gesture_version_three" to "0"),
                includeHyperOs = true,
            ),
        )
    }

    @Test
    fun theRepairScriptIsGeneratedFromTheTargets() {
        val script = NavigationRecoveryScript.build(
            packageName = "com.h3nc4.hypergesture",
            includeExemptions = true,
            hyperOs = true,
            guard = true,
        )
        NavigationSettingTargets.all.forEach { target ->
            assertTrue(script.contains("settings put ${target.namespace} ${target.key} ${target.value}"))
            assertTrue(script.contains("settings get ${target.namespace} ${target.key}"))
        }
        assertTrue(script.contains("appops set com.h3nc4.hypergesture 10008 allow"))
        assertTrue(script.contains("appops set com.h3nc4.hypergesture 10021 allow"))
        assertTrue(script.contains("dumpsys deviceidle whitelist +com.h3nc4.hypergesture"))
        assertTrue(script.contains("am set-standby-bucket com.h3nc4.hypergesture active"))
        assertTrue(script.contains("use_gesture_version_three"))
        assertFalse("the duplicate user 0 writes are gone", script.contains("--user 0"))
    }

    @Test
    fun exemptionsAndHyperOsBitsAreOnlyAddedWhenAsked() {
        val minimal = NavigationRecoveryScript.build(
            packageName = "com.h3nc4.hypergesture",
            includeExemptions = false,
            hyperOs = false,
            guard = true,
        )
        assertFalse(minimal.contains("appops"))
        assertFalse(minimal.contains("deviceidle"))
        assertFalse(minimal.contains("use_gesture_version_three"))
        assertFalse(minimal.contains("cmd overlay"))
    }

    @Test
    fun aForcedScriptWritesWithoutTheGuard() {
        val forced = NavigationRecoveryScript.build(
            packageName = "com.h3nc4.hypergesture",
            includeExemptions = true,
            hyperOs = false,
            guard = false,
        )
        assertFalse(forced.contains("if ["))
        assertTrue(forced.contains("settings put global force_fsg_nav_bar 1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aPackageNameWithShellSyntaxIsRejected() {
        NavigationRecoveryScript.build(
            packageName = "com.h3nc4.hypergesture; reboot",
            includeExemptions = false,
            hyperOs = false,
            guard = true,
        )
    }
}
