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

package com.simplecoil.simplecoil.data.repository;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class GameSettingsRepositoryTest {

    private GameSettingsRepository repository;

    @Before
    public void setUp() {
        repository = GameSettingsRepository.getInstance();
        repository.setGameMode(GameSettingsRepository.GAME_MODE_2TEAMS);
        repository.resetDefaultTeamSizes();
        repository.setFullHealth(GameSettingsRepository.MAX_HEALTH);
        repository.setFullReload(GameSettingsRepository.RELOAD_COUNT);
    }

    @Test
    public void testSingletonInstance() {
        assertNotNull(repository);
        assertEquals(repository, GameSettingsRepository.getInstance());
    }

    @Test
    public void testDefaultSettings() {
        assertEquals(GameSettingsRepository.MAX_HEALTH, repository.getFullHealth());
        assertEquals(GameSettingsRepository.RELOAD_COUNT, repository.getFullReload());
        assertEquals(GameSettingsRepository.GAME_MODE_2TEAMS, repository.getGameMode());
        assertEquals(GameSettingsRepository.GAME_LIMIT_NONE, repository.getGameLimit());
    }

    @Test
    public void testTeamSizeCalculation2Teams() {
        repository.setGameMode(GameSettingsRepository.GAME_MODE_2TEAMS);
        int[] teamSizes = repository.getTeamSizes();
        assertEquals(2, teamSizes.length);
        assertArrayEquals(new int[]{16, 16}, teamSizes);
        assertEquals(1, repository.getTeamStartID(1));
        assertEquals(16, repository.getTeamEndID(1));
        assertEquals(17, repository.getTeamStartID(2));
        assertEquals(32, repository.getTeamEndID(2));
    }

    @Test
    public void testTeamSizeCalculation4Teams() {
        repository.setGameMode(GameSettingsRepository.GAME_MODE_4TEAMS);
        int[] teamSizes = repository.getTeamSizes();
        assertEquals(4, teamSizes.length);
        assertArrayEquals(new int[]{8, 8, 8, 8}, teamSizes);
        assertEquals(1, repository.getTeamStartID(1));
        assertEquals(8, repository.getTeamEndID(1));
        assertEquals(9, repository.getTeamStartID(2));
        assertEquals(16, repository.getTeamEndID(2));
        assertEquals(17, repository.getTeamStartID(3));
        assertEquals(24, repository.getTeamEndID(3));
        assertEquals(25, repository.getTeamStartID(4));
        assertEquals(32, repository.getTeamEndID(4));
    }

    @Test
    public void testCustomTeamSizes() {
        repository.setGameMode(GameSettingsRepository.GAME_MODE_2TEAMS);
        repository.setTeamSizes(new int[]{20, 12});
        assertArrayEquals(new int[]{20, 12}, repository.getTeamSizes());

        // Calling setGameMode with the same game mode should preserve custom team sizes
        repository.setGameMode(GameSettingsRepository.GAME_MODE_2TEAMS);
        assertArrayEquals(new int[]{20, 12}, repository.getTeamSizes());

        assertEquals(1, repository.getTeamStartID(1));
        assertEquals(20, repository.getTeamEndID(1));
        assertEquals(21, repository.getTeamStartID(2));
        assertEquals(32, repository.getTeamEndID(2));
    }

    @Test
    public void testCustomSettingsMutation() {
        repository.setFullHealth(50);
        assertEquals(50, repository.getFullHealth());

        repository.setTimeLimit(600);
        assertEquals(600, repository.getTimeLimit());

        repository.setReloadOnEmpty(true);
        assertTrue(repository.isReloadOnEmpty());
    }
}
