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

package com.h3nc4.hypergesture.service

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentTasksTest {

    private val dump = """
        ACTIVITY MANAGER RECENT TASKS (dumpsys activity recents)
        mRecentsUid=10173
        mRecentsComponent=ComponentInfo{com.mi.android.globallauncher/com.miui.home.recents.RecentsActivity}
        mFreezeTaskListReordering=false
        mHiddenTasks=[Task{de4cc4a #3795 type=standard A=10588:com.pinterest}, Task{c953af4 #3731 type=standard A=10578:com.reddit.frontpage}]
          Recent tasks:
          * Recent #0: Task{9be82c6 #3864 type=home I=bitpit.launcher/.ui.HomeActivity}
            mActivityComponent=bitpit.launcher/.ui.HomeActivity
            autoRemoveRecents=false isPersistable=true activityType=2
            isTrimmable=true isForceHidden=false isForceExcludedFromRecents=false
          * Recent #1: Task{3907469 #3876 type=standard A=10585:com.confirmtkt.lite}
            mActivityComponent=com.confirmtkt.lite/.LaunchActivity
            autoRemoveRecents=false isPersistable=true activityType=1
            isTrimmable=true isForceHidden=false isForceExcludedFromRecents=false
          * Recent #2: Task{b365f54 #3718 type=recents I=com.mi.android.globallauncher/com.miui.home.recents.RecentsActivity}
            mActivityComponent=com.mi.android.globallauncher/com.miui.home.recents.RecentsActivity
            autoRemoveRecents=false isPersistable=false activityType=3
            isTrimmable=true isForceHidden=false isForceExcludedFromRecents=false
          * Recent #3: Task{64683eb #3874 type=standard A=10588:com.pinterest}
            mActivityComponent=com.pinterest/.activity.PinterestActivity
            autoRemoveRecents=false isPersistable=true activityType=1
            isTrimmable=true isForceHidden=false isForceExcludedFromRecents=false
          * Recent #4: Task{de4cc4a #3795 type=standard A=10588:com.pinterest}
            mActivityComponent=com.pinterest/.activity.PinterestActivity
            autoRemoveRecents=false isPersistable=true activityType=1
            isTrimmable=true isForceHidden=false isForceExcludedFromRecents=false
          * Recent #5: Task{c953af4 #3731 type=standard A=10578:com.reddit.frontpage}
            mActivityComponent=com.reddit.frontpage/.MainActivity
            autoRemoveRecents=false isPersistable=true activityType=1
            isTrimmable=true isForceHidden=false isForceExcludedFromRecents=false
          * Recent #6: Task{a29ffeb #3865 type=standard A=10329:com.whatsapp}
            mActivityComponent=com.whatsapp/.home.ui.HomeActivity
            autoRemoveRecents=false isPersistable=true activityType=1
            isTrimmable=true isForceHidden=false isForceExcludedFromRecents=false
          * Recent #7: Task{1111111 #3900 type=standard A=1000:com.foo.excluded}
            mActivityComponent=com.foo.excluded/.MainActivity
            autoRemoveRecents=false isPersistable=true activityType=1
            isTrimmable=true isForceHidden=false isForceExcludedFromRecents=true
          * Recent #8: Task{2222222 #3901 type=standard A=1000:com.foo.ephemeral}
            mActivityComponent=com.foo.ephemeral/.MainActivity
            autoRemoveRecents=true isPersistable=true activityType=1
            isTrimmable=true isForceHidden=false isForceExcludedFromRecents=false
    """.trimIndent()

    @Test
    fun `keeps only the visible standard tasks in recents order`() {
        assertEquals(
            listOf(
                "com.confirmtkt.lite",
                "com.pinterest",
                "com.whatsapp",
            ),
            parseRecentTasks(dump),
        )
    }

    @Test
    fun `an empty dump yields no tasks`() {
        assertEquals(emptyList<String>(), parseRecentTasks("ACTIVITY MANAGER RECENT TASKS\n"))
    }

    @Test
    fun `parses realActivity when mActivityComponent is absent`() {
        val coldDump = """
            ACTIVITY MANAGER RECENT TASKS (dumpsys activity recents)
              * Recent #0: Task{1234567 #1001 type=standard A=1000:com.example.app}
                realActivity=com.example.app/.MainActivity
                autoRemoveRecents=false isPersistable=true activityType=1
                isTrimmable=true isForceHidden=false isForceExcludedFromRecents=false
        """.trimIndent()
        assertEquals(listOf("com.example.app"), parseRecentTasks(coldDump))
    }
}
