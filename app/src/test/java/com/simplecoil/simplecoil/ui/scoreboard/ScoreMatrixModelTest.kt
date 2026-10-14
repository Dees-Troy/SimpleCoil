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

package com.simplecoil.simplecoil.ui.scoreboard

import com.simplecoil.simplecoil.domain.team.TeamLayout
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.network.ServerMessage
import com.simplecoil.simplecoil.network.WireMatrixCell
import com.simplecoil.simplecoil.network.WireMatrixPlayer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The score matrix's ordering, cells and totals. */
class ScoreMatrixModelTest {

    private fun player(id: Int, team: Int = 1, connected: Boolean = true) = WireMatrixPlayer(id, "P$id", team, connected)

    private fun cell(shooter: Int, victim: Int, count: Int) = WireMatrixCell(shooter, victim, count)

    private fun model(mode: TeamMode, players: List<WireMatrixPlayer>, cells: List<WireMatrixCell> = emptyList(), self: Int = 0) =
        ScoreMatrixModel.from(ServerMessage.ScoreMatrix(players, cells, 7), TeamLayout.defaultFor(mode), self.toByte())

    private fun ScoreMatrixModel.ids() = players.map { it.playerId.toInt() }

    @Test
    fun freeForAll_isOrderedById_withoutTeams() {
        val m = model(TeamMode.FREE_FOR_ALL, listOf(player(5, team = 5), player(2, team = 2), player(9, team = 9)), self = 5)
        assertEquals(listOf(2, 5, 9), m.ids())
        assertFalse(m.teams)
        assertEquals(listOf(1, 1, 1), m.players.map { it.team })
        assertEquals(1, m.selfIndex)
        assertEquals(listOf(false, true, false), m.players.map { it.isSelf })
        assertEquals(7L, m.gameId)
    }

    @Test
    fun twoTeams_areOrderedByTeamThenId() {
        val m = model(TeamMode.TWO_TEAMS, listOf(player(17, 2), player(2, 1), player(18, 2), player(1, 1)))
        assertEquals(listOf(1, 2, 17, 18), m.ids())
        assertTrue(m.teams)
        assertEquals(-1, m.selfIndex)
    }

    @Test
    fun fourTeams_areOrderedByTeamThenId() {
        val m = model(TeamMode.FOUR_TEAMS, listOf(player(30, 4), player(3, 1), player(12, 2), player(20, 3), player(1, 1)))
        assertEquals(listOf(1, 3, 12, 20, 30), m.ids())
        assertEquals(listOf(1, 1, 2, 3, 4), m.players.map { it.team })
    }

    @Test
    fun cellsAndTotals() {
        val m = model(TeamMode.TWO_TEAMS, listOf(player(1, 1), player(2, 1), player(17, 2)),
            listOf(cell(1, 17, 4), cell(17, 1, 2), cell(17, 17, 1), cell(2, 1, 1)))
        // Rows and columns: 1, 2, 17
        assertArrayEquals(intArrayOf(0, 0, 4), m.counts[0])
        assertArrayEquals(intArrayOf(1, 0, 0), m.counts[1])
        assertArrayEquals(intArrayOf(2, 0, 1), m.counts[2])
        assertArrayEquals(intArrayOf(4, 1, 3), m.rowTotals)
        assertArrayEquals(intArrayOf(3, 0, 5), m.columnTotals)
        assertEquals(8, m.total)
        assertFalse(m.isEmpty)
    }

    @Test
    fun cellsForUnknownPlayers_andNegativeCounts_areIgnored() {
        val m = model(TeamMode.FREE_FOR_ALL, listOf(player(1), player(2)),
            listOf(cell(1, 3, 5), cell(3, 1, 5), cell(2, 1, -4), cell(1, 2, 2)))
        assertArrayEquals(intArrayOf(0, 2), m.counts[0])
        assertArrayEquals(intArrayOf(0, 0), m.counts[1])
        assertArrayEquals(intArrayOf(2, 0), m.rowTotals)
    }

    @Test
    fun noCells_isEmpty() {
        val m = model(TeamMode.TWO_TEAMS, listOf(player(1, 1), player(17, 2)))
        assertTrue(m.isEmpty)
        assertEquals(2, m.size)
        assertTrue(model(TeamMode.TWO_TEAMS, emptyList()).isEmpty)
    }

    @Test
    fun disconnectedPlayers_keepTheirRow() {
        val m = model(TeamMode.FREE_FOR_ALL, listOf(player(1, connected = false), player(2)), listOf(cell(1, 2, 1)))
        assertFalse(m.players[0].connected)
        assertEquals(1, m.counts[0][1])
    }
}
