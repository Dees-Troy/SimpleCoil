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

package com.simplecoil.simplecoil.domain.rules

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TeamSizesTest {

    @Test
    fun sizesThatFillEverySlot_areValid() {
        assertArrayEquals(intArrayOf(20, 12), (TeamSizes.validate(listOf("20", " 12 ")) as TeamSizes.Result.Valid).sizes)
        assertArrayEquals(intArrayOf(8, 8, 8, 8), (TeamSizes.validate(listOf("8", "8", "8", "8")) as TeamSizes.Result.Valid).sizes)
    }

    @Test
    fun onlyTheFirstTeamCountFieldsCount() {
        assertArrayEquals(intArrayOf(16, 16), (TeamSizes.validate(listOf("16", "16", "", "x"), teamCount = 2) as TeamSizes.Result.Valid).sizes)
    }

    @Test
    fun anEmptyNonNumericOrNonPositiveField_isBelowOne() {
        for (bad in listOf("", "x", "0", "-3")) {
            assertEquals(bad, TeamSizes.Result.BelowOne, TeamSizes.validate(listOf("16", bad)))
        }
    }

    @Test
    fun sizesThatDontFillEverySlot_reportTheTotal() {
        assertEquals(TeamSizes.Result.WrongTotal(20), TeamSizes.validate(listOf("10", "10")))
        assertEquals(TeamSizes.Result.WrongTotal(40), TeamSizes.validate(listOf("20", "20")))
    }
}
