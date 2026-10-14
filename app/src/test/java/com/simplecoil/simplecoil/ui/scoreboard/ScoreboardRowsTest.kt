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

import com.simplecoil.simplecoil.PlayerDisplayData
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.domain.team.TeamLayout
import com.simplecoil.simplecoil.domain.team.TeamMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scoreboard's grouping and sorting; ported from PlayerDisplayDataListAdapterTest with the same orderings. */
class ScoreboardRowsTest {

    private fun context(
        mode: TeamMode = TeamMode.FREE_FOR_ALL,
        gameLimit: Int = GameSettingsRepository.GAME_LIMIT_NONE,
        livesLimit: Int = 0,
        isClient: Boolean = false,
        selfId: Byte = 0,
        isAdmin: Boolean = false,
    ) = ScoreboardContext(TeamLayout.defaultFor(mode), gameLimit, livesLimit, isClient, selfId, isAdmin)

    private fun players(vararg list: PlayerDisplayData): Array<PlayerDisplayData?> {
        val data = arrayOfNulls<PlayerDisplayData>(GameSettingsRepository.MAX_PLAYER_ID + 2)
        list.forEach { data[it.playerID.toInt()] = it }
        return data
    }

    private fun player(id: Int, name: String, points: Int, eliminated: Int = 0) = PlayerDisplayData().apply {
        playerID = id.toByte()
        playerName = name
        this.points = points
        this.eliminated = eliminated
    }

    private fun List<ScoreboardRow>.playerIds() = filterIsInstance<ScoreboardRow.Player>().map { it.playerId.toInt() }

    @Test
    fun ffa_sortsByScore() {
        val rows = ScoreboardRows.build(players(player(1, "Player 1", 10), player(2, "Player 2", 25), player(3, "Player 3", 15)), context())
        // Column header, the PLAYERS header, then 2 (25), 3 (15), 1 (10)
        assertEquals(5, rows.size)
        assertTrue(rows[0] is ScoreboardRow.ColumnHeader)
        assertEquals(0, (rows[1] as ScoreboardRow.TeamHeader).team)
        assertEquals(listOf(2, 3, 1), rows.playerIds())
    }

    @Test
    fun ffa_sortsByFewestEliminations_withoutALivesLimit() {
        val rows = ScoreboardRows.build(players(player(1, "Player 1", 10, 3), player(2, "Player 2", 10, 0), player(3, "Player 3", 10, 1)), context())
        assertEquals(listOf(2, 3, 1), rows.playerIds())
    }

    @Test
    fun ffa_sortsByMostLivesRemaining_withALivesLimit() {
        val rows = ScoreboardRows.build(
            players(player(1, "Player 1", 10, 4), player(2, "Player 2", 10, 1), player(3, "Player 3", 10, 2)),
            context(gameLimit = GameSettingsRepository.GAME_LIMIT_LIVES, livesLimit = 10),
        )
        assertEquals(listOf(2, 3, 1), rows.playerIds())
        assertEquals(listOf(9, 8, 6), rows.filterIsInstance<ScoreboardRow.Player>().map { it.livesOrEliminations })
        assertTrue((rows[0] as ScoreboardRow.ColumnHeader).livesColumn)
    }

    @Test
    fun ffa_sortsByNameIgnoringCase() {
        val rows = ScoreboardRows.build(players(player(1, "Charlie", 10), player(2, "alice", 10), player(3, "Bob", 10)), context())
        assertEquals(listOf(2, 3, 1), rows.playerIds())
    }

    @Test
    fun teams_sortByTeamScore_withTheirPlayersUnderThem() {
        val rows = ScoreboardRows.build(
            players(player(1, "Team 1 Low", 5), player(2, "Team 1 High", 15), player(17, "Team 2 High", 50)),
            context(mode = TeamMode.TWO_TEAMS),
        )
        // Column header, team 2 (50), 17, team 1 (20), 2 (15), 1 (5)
        assertEquals(6, rows.size)
        assertEquals(ScoreboardRow.TeamHeader(2, 1, 16, 50, true), rows[1])
        assertEquals(17, (rows[2] as ScoreboardRow.Player).playerId.toInt())
        assertEquals(ScoreboardRow.TeamHeader(1, 2, 16, 20, true), rows[3])
        assertEquals(listOf(17, 2, 1), rows.playerIds())
        assertEquals(listOf("2-1", "1-2", "1-1"), rows.filterIsInstance<ScoreboardRow.Player>().map { it.label })
    }

    @Test
    fun overriddenLives_showLivesRemaining() {
        val p = player(1, "A", 0, 2).apply {
            overrideLives = true
            lives = 5
        }
        val row = ScoreboardRows.build(players(p), context()).filterIsInstance<ScoreboardRow.Player>().single()
        assertEquals(3, row.livesOrEliminations)
    }

    @Test
    fun client_editsAndKicksOnlyAsAnAdmin_andNeverKicksItself() {
        val data = players(player(1, "Me", 0), player(17, "Them", 0))
        val plain = ScoreboardRows.build(data, context(mode = TeamMode.TWO_TEAMS, isClient = true, selfId = 1))
        assertFalse(plain.filterIsInstance<ScoreboardRow.TeamHeader>().any { it.editable })
        assertFalse(plain.filterIsInstance<ScoreboardRow.Player>().any { it.kickable })

        val admin = ScoreboardRows.build(data, context(mode = TeamMode.TWO_TEAMS, isClient = true, selfId = 1, isAdmin = true))
        assertTrue(admin.filterIsInstance<ScoreboardRow.TeamHeader>().all { it.editable })
        val byId = admin.filterIsInstance<ScoreboardRow.Player>().associateBy { it.playerId.toInt() }
        assertFalse(byId.getValue(1).kickable)
        assertTrue(byId.getValue(1).isSelf)
        assertTrue(byId.getValue(17).kickable)
    }
}
