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
package com.simplecoil.simplecoil.domain.session

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository.SHOT_MODE_BURST as B
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository.SHOT_MODE_FULL_AUTO as A
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository.SHOT_MODE_SINGLE as S
import org.junit.Assert.assertEquals
import org.junit.Test

/** Full truth tables for current mode × allowed (single, burst, auto). */
class ShotModePolicyTest {

    private val allowances = listOf(
        "---", "--a", "-b-", "-ba", "s--", "s-a", "sb-", "sba",
    )

    private fun check(expected: Map<Int, List<Int?>>, policy: (Int, Boolean, Boolean, Boolean) -> Int?) {
        for ((current, row) in expected) {
            for ((i, allowed) in allowances.withIndex()) {
                assertEquals("current=$current allowed=$allowed", row[i],
                    policy(current, allowed[0] == 's', allowed[1] == 'b', allowed[2] == 'a'))
            }
        }
    }

    @Test
    fun thumbButton_cyclesSingleBurstAuto() {
        check(mapOf(
            //         ---  --a  -b-  -ba  s--  s-a  sb-  sba
            S to listOf(S,   A,   B,   B,   S,   A,   B,   B),
            B to listOf(B,   A,   B,   A,   S,   A,   S,   A),
            A to listOf(A,   A,   B,   B,   S,   S,   S,   S),
            0 to listOf(null, null, null, null, null, null, null, null),
        ), ShotModePolicy::nextOnThumb)
    }

    @Test
    fun settingsChange_leavesAllowedModeAlone() {
        check(mapOf(
            //         ---   --a   -b-   -ba   s--   s-a   sb-   sba
            S to listOf(A,    A,    B,    B,    null, null, null, null),
            B to listOf(S,    A,    null, null, S,    A,    null, null),
            A to listOf(B,    null, B,    null, S,    null, S,    null),
            0 to listOf(A,    A,    B,    B,    S,    S,    S,    S),
        ), ShotModePolicy::coerceToAllowed)
    }
}
