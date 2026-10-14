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

import com.simplecoil.simplecoil.data.repository.PlayerRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TeamLayoutTest {

    @Test
    fun defaults_perMode() {
        assertArrayEquals(intArrayOf(16, 16), TeamLayout.defaultFor(TeamMode.TWO_TEAMS).teamSizes())
        assertArrayEquals(intArrayOf(8, 8, 8, 8), TeamLayout.defaultFor(TeamMode.FOUR_TEAMS).teamSizes())
        // Free for all carries the two-team sizes the protocol has always sent.
        assertArrayEquals(intArrayOf(16, 16), TeamLayout.defaultFor(TeamMode.FREE_FOR_ALL).teamSizes())
        assertEquals(TeamLayout.defaultFor(TeamMode.TWO_TEAMS), TeamLayout.DEFAULT)
    }

    @Test
    fun teamOf_twoTeams() {
        val layout = TeamLayout.defaultFor(TeamMode.TWO_TEAMS)
        assertEquals(1, layout.teamOf(1))
        assertEquals(1, layout.teamOf(16))
        assertEquals(2, layout.teamOf(17))
        assertEquals(2, layout.teamOf(32))
    }

    @Test
    fun teamOf_fourTeams() {
        val layout = TeamLayout.defaultFor(TeamMode.FOUR_TEAMS)
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 4), listOf(1, 8, 9, 16, 17, 24, 25, 32).map { layout.teamOf(it) })
    }

    @Test
    fun teamOf_customSizes_andStartEndIds() {
        val layout = TeamLayout.of(TeamMode.FOUR_TEAMS, intArrayOf(5, 9, 9, 9))
        assertEquals(1, layout.teamOf(5))
        assertEquals(2, layout.teamOf(6))
        assertEquals(6, layout.teamStartId(2))
        assertEquals(14, layout.teamEndId(2))
        assertEquals(24, layout.teamStartId(4))
        assertEquals(32, layout.teamEndId(4))
    }

    @Test
    fun teamOf_freeForAllIsTheIdItself_andInvalidIdsAreRejected() {
        val ffa = TeamLayout.defaultFor(TeamMode.FREE_FOR_ALL)
        assertEquals(7, ffa.teamOf(7))
        assertEquals(PlayerRepository.INVALID_PLAYER_ID, ffa.teamOf(0))
        assertEquals(PlayerRepository.INVALID_PLAYER_ID, TeamLayout.DEFAULT.teamOf(33))
        assertEquals(PlayerRepository.INVALID_PLAYER_ID, TeamLayout.DEFAULT.teamOf(167.toByte())) // grenade id
    }

    @Test
    fun teamEndId_ofAMissingTeamIsTheLastId() {
        assertEquals(32, TeamLayout.DEFAULT.teamEndId(3))
        assertEquals(32, TeamLayout.DEFAULT.teamEndId(0))
    }

    @Test
    fun withSizes_rejectsSizesThatDontFit() {
        val four = TeamLayout.defaultFor(TeamMode.FOUR_TEAMS)
        assertEquals(four, four.withSizes(intArrayOf(16, 16)))          // wrong team count
        assertEquals(four, four.withSizes(intArrayOf(10, 10, 10, 10)))  // doesn't add up to 32
        assertSame(four, four.withSizes(null))                          // null keeps the sizes
    }

    @Test
    fun withMode_keepsSizesOnlyForTheSameMode() {
        val custom = TeamLayout.of(TeamMode.TWO_TEAMS, intArrayOf(20, 12))
        assertSame(custom, custom.withMode(TeamMode.TWO_TEAMS))
        assertEquals(TeamLayout.defaultFor(TeamMode.FOUR_TEAMS), custom.withMode(TeamMode.FOUR_TEAMS))
    }

    @Test
    fun teamSizes_isACopy() {
        val layout = TeamLayout.DEFAULT
        layout.teamSizes()[0] = 1
        assertArrayEquals(intArrayOf(16, 16), layout.teamSizes())
    }

    @Test
    fun wireValues_roundTrip_andUnknownValuesAreRejected() {
        for (mode in TeamMode.entries) assertEquals(mode, TeamMode.fromWire(mode.wireValue))
        assertEquals(listOf(1, 2, 4), TeamMode.entries.map { it.wireValue }) // fixed by the protocol
        assertNull(TeamMode.fromWire(3))
        assertEquals(TeamMode.TWO_TEAMS, TeamMode.fromWireOrDefault(99))
    }
}
