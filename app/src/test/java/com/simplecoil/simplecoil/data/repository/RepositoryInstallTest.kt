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

package com.simplecoil.simplecoil.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The install shim keeps one instance per process (APP_CONTAINER_DESIGN.md §3, G4). */
@Suppress("DEPRECATION")
class RepositoryInstallTest {

    @Test
    fun installingTheExistingInstance_isAllowed() {
        val settings = GameSettingsRepository.getInstance()
        GameSettingsRepository.install(settings)
        assertSame(settings, GameSettingsRepository.getInstance())
    }

    @Test(expected = IllegalStateException::class)
    fun installingASecondInstance_fails() {
        GameStateRepository.getInstance()
        GameStateRepository.install(GameStateRepository())
    }

    @Test
    fun playerRepository_usesTheRepositoriesItWasGiven() {
        val settings = GameSettingsRepository()
        val state = GameStateRepository()
        val players = PlayerRepository(settings, state)
        settings.teamMode = com.simplecoil.simplecoil.domain.team.TeamMode.FOUR_TEAMS
        // Four teams of 8: player 17 is on team 3 (team 2 with the default two teams of 16)
        assertEquals(3, players.calcNetworkTeam(17))
    }
}
