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

package com.simplecoil.simplecoil

import android.app.Activity
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.domain.team.TeamMode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class PlayerDisplayDataListAdapterTest {

    private lateinit var mockActivity: Activity

    @Before
    fun setUp() {
        mockActivity = Mockito.mock(Activity::class.java)
        GameSettingsRepository.getInstance().teamMode = TeamMode.FREE_FOR_ALL
        GameSettingsRepository.getInstance().gameLimit = 0
        GameSettingsRepository.getInstance().livesLimit = 0
    }

    @Test
    fun testFfaSortingByScore() {
        GameSettingsRepository.getInstance().teamMode = TeamMode.FREE_FOR_ALL
        val data = arrayOfNulls<PlayerDisplayData>(GameSettingsRepository.MAX_PLAYER_ID + 2)

        data[1] = PlayerDisplayData().apply {
            playerID = 1
            playerName = "Player 1"
            points = 10
            eliminated = 0
        }
        data[2] = PlayerDisplayData().apply {
            playerID = 2
            playerName = "Player 2"
            points = 25
            eliminated = 0
        }
        data[3] = PlayerDisplayData().apply {
            playerID = 3
            playerName = "Player 3"
            points = 15
            eliminated = 0
        }

        val adapter = PlayerDisplayDataListAdapter(mockActivity, data, false)

        // Row 0: Column Header
        // Row 1: Team Header (PLAYERS)
        // Row 2: Player 2 (points 25)
        // Row 3: Player 3 (points 15)
        // Row 4: Player 1 (points 10)
        assertEquals(5, adapter.count)
        assertEquals(2.toByte(), adapter.getDisplayRow(2)?.playerID)
        assertEquals(3.toByte(), adapter.getDisplayRow(3)?.playerID)
        assertEquals(1.toByte(), adapter.getDisplayRow(4)?.playerID)
    }

    @Test
    fun testFfaSortingByLeastEliminationsWhenNoLivesLimit() {
        GameSettingsRepository.getInstance().teamMode = TeamMode.FREE_FOR_ALL
        GameSettingsRepository.getInstance().gameLimit = 0
        val data = arrayOfNulls<PlayerDisplayData>(GameSettingsRepository.MAX_PLAYER_ID + 2)

        data[1] = PlayerDisplayData().apply {
            playerID = 1
            playerName = "Player 1"
            points = 10
            eliminated = 3
        }
        data[2] = PlayerDisplayData().apply {
            playerID = 2
            playerName = "Player 2"
            points = 10
            eliminated = 0
        }
        data[3] = PlayerDisplayData().apply {
            playerID = 3
            playerName = "Player 3"
            points = 10
            eliminated = 1
        }

        val adapter = PlayerDisplayDataListAdapter(mockActivity, data, false)

        // All points equal to 10. Least eliminations first -> Player 2 (0), Player 3 (1), Player 1 (3).
        assertEquals(2.toByte(), adapter.getDisplayRow(2)?.playerID)
        assertEquals(3.toByte(), adapter.getDisplayRow(3)?.playerID)
        assertEquals(1.toByte(), adapter.getDisplayRow(4)?.playerID)
    }

    @Test
    fun testFfaSortingByMostLivesRemainingWhenLivesLimitSet() {
        GameSettingsRepository.getInstance().teamMode = TeamMode.FREE_FOR_ALL
        GameSettingsRepository.getInstance().gameLimit = GameSettingsRepository.GAME_LIMIT_LIVES
        GameSettingsRepository.getInstance().livesLimit = 10
        val data = arrayOfNulls<PlayerDisplayData>(GameSettingsRepository.MAX_PLAYER_ID + 2)

        data[1] = PlayerDisplayData().apply {
            playerID = 1
            playerName = "Player 1"
            points = 10
            eliminated = 4 // 6 lives remaining
        }
        data[2] = PlayerDisplayData().apply {
            playerID = 2
            playerName = "Player 2"
            points = 10
            eliminated = 1 // 9 lives remaining
        }
        data[3] = PlayerDisplayData().apply {
            playerID = 3
            playerName = "Player 3"
            points = 10
            eliminated = 2 // 8 lives remaining
        }

        val adapter = PlayerDisplayDataListAdapter(mockActivity, data, false)

        // All points equal to 10. Most lives remaining first -> Player 2 (9), Player 3 (8), Player 1 (6).
        assertEquals(2.toByte(), adapter.getDisplayRow(2)?.playerID)
        assertEquals(3.toByte(), adapter.getDisplayRow(3)?.playerID)
        assertEquals(1.toByte(), adapter.getDisplayRow(4)?.playerID)
    }

    @Test
    fun testFfaSortingByNameCaseInsensitive() {
        GameSettingsRepository.getInstance().teamMode = TeamMode.FREE_FOR_ALL
        val data = arrayOfNulls<PlayerDisplayData>(GameSettingsRepository.MAX_PLAYER_ID + 2)

        data[1] = PlayerDisplayData().apply {
            playerID = 1
            playerName = "Charlie"
            points = 10
            eliminated = 0
        }
        data[2] = PlayerDisplayData().apply {
            playerID = 2
            playerName = "alice"
            points = 10
            eliminated = 0
        }
        data[3] = PlayerDisplayData().apply {
            playerID = 3
            playerName = "Bob"
            points = 10
            eliminated = 0
        }

        val adapter = PlayerDisplayDataListAdapter(mockActivity, data, false)

        // Alphabetical order: alice (2), Bob (3), Charlie (1)
        assertEquals(2.toByte(), adapter.getDisplayRow(2)?.playerID)
        assertEquals(3.toByte(), adapter.getDisplayRow(3)?.playerID)
        assertEquals(1.toByte(), adapter.getDisplayRow(4)?.playerID)
    }

    @Test
    fun testTeamModeTeamSortingByScoreAndPlayersUnderTeam() {
        GameSettingsRepository.getInstance().teamMode = TeamMode.TWO_TEAMS
        val data = arrayOfNulls<PlayerDisplayData>(GameSettingsRepository.MAX_PLAYER_ID + 2)

        // Team 1 (IDs 1-16)
        data[1] = PlayerDisplayData().apply {
            playerID = 1
            playerName = "Team 1 Low"
            points = 5
            eliminated = 0
        }
        data[2] = PlayerDisplayData().apply {
            playerID = 2
            playerName = "Team 1 High"
            points = 15
            eliminated = 0
        }
        // Team 1 total = 20

        // Team 2 (IDs 17-32)
        data[17] = PlayerDisplayData().apply {
            playerID = 17
            playerName = "Team 2 High"
            points = 50
            eliminated = 0
        }
        // Team 2 total = 50

        val adapter = PlayerDisplayDataListAdapter(mockActivity, data, false)

        // Team 2 total (50) > Team 1 total (20)
        // Order:
        // Row 0: Column Header
        // Row 1: Team 2 Header
        // Row 2: Player 17
        // Row 3: Team 1 Header
        // Row 4: Player 2 (points 15)
        // Row 5: Player 1 (points 5)
        assertEquals(6, adapter.count)

        assertEquals(PlayerDisplayDataListAdapter.DisplayRow.TYPE_TEAM_HEADER, adapter.getDisplayRow(1)?.type)
        assertEquals(2, adapter.getDisplayRow(1)?.teamNumber)

        assertEquals(17.toByte(), adapter.getDisplayRow(2)?.playerID)

        assertEquals(PlayerDisplayDataListAdapter.DisplayRow.TYPE_TEAM_HEADER, adapter.getDisplayRow(3)?.type)
        assertEquals(1, adapter.getDisplayRow(3)?.teamNumber)

        assertEquals(2.toByte(), adapter.getDisplayRow(4)?.playerID)
        assertEquals(1.toByte(), adapter.getDisplayRow(5)?.playerID)
    }
}
