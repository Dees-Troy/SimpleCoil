/*
 * Copyright (C) 2026 Ethan Yonker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.simplecoil.simplecoil.domain.team

/** The colour each team is shown in, as ARGB ints. */
object TeamColors {
    const val TEAM_1 = 0xFFD32F2F.toInt() // Red
    const val TEAM_2 = 0xFF2196F3.toInt() // Blue
    const val TEAM_3 = 0xFF4CAF50.toInt() // Green
    const val TEAM_4 = 0xFFFFEB3B.toInt() // Yellow

    /** No colour (fully transparent) for anything that isn't team 1-4. */
    const val NONE = 0

    @JvmStatic
    fun colorOf(team: Int): Int = when (team) {
        1 -> TEAM_1
        2 -> TEAM_2
        3 -> TEAM_3
        4 -> TEAM_4
        else -> NONE
    }
}
