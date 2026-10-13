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

package com.simplecoil.simplecoil.ui.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.GameStateRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;

import org.junit.Before;
import org.junit.Test;

public class DedicatedServerViewModelTest {

    private GameSettingsRepository settingsRepo;
    private GameStateRepository gameStateRepo;
    private PlayerRepository playerRepo;

    @Before
    public void setUp() {
        settingsRepo = GameSettingsRepository.getInstance();
        gameStateRepo = GameStateRepository.getInstance();
        playerRepo = PlayerRepository.getInstance();

        settingsRepo.setStartTime(GameSettingsRepository.START_TIME_SECONDS);
        settingsRepo.setGameMode(GameSettingsRepository.GAME_MODE_2TEAMS);
        settingsRepo.setGameLimit(GameSettingsRepository.GAME_LIMIT_NONE);
        settingsRepo.setGpsMode(GameSettingsRepository.GPS_ALL);
        gameStateRepo.setGameState(GameStateRepository.GAME_STATE_NONE);
    }

    @Test
    public void testGameModeSettingsUpdate() {
        assertEquals(GameSettingsRepository.GAME_MODE_2TEAMS, settingsRepo.getGameMode());

        settingsRepo.setGameMode(GameSettingsRepository.GAME_MODE_4TEAMS);
        assertEquals(GameSettingsRepository.GAME_MODE_4TEAMS, settingsRepo.getGameMode());
    }

    @Test
    public void testStartTimeUpdate() {
        assertEquals(GameSettingsRepository.START_TIME_SECONDS, settingsRepo.getStartTime());

        settingsRepo.setStartTime(8);
        assertEquals(8, settingsRepo.getStartTime());
    }

    @Test
    public void testGameLimitsUpdate() {
        int timeLimit = 300;
        int livesLimit = 5;
        int scoreLimit = 100;

        int gameLimit = GameSettingsRepository.GAME_LIMIT_TIME
                | GameSettingsRepository.GAME_LIMIT_LIVES
                | GameSettingsRepository.GAME_LIMIT_SCORE;

        settingsRepo.setGameLimit(gameLimit);
        settingsRepo.setTimeLimit(timeLimit);
        settingsRepo.setLivesLimit(livesLimit);
        settingsRepo.setScoreLimit(scoreLimit);

        assertEquals(gameLimit, settingsRepo.getGameLimit());
        assertEquals(300, settingsRepo.getTimeLimit());
        assertEquals(5, settingsRepo.getLivesLimit());
        assertEquals(100, settingsRepo.getScoreLimit());
    }

    @Test
    public void testGpsModeUpdate() {
        assertEquals(GameSettingsRepository.GPS_ALL, settingsRepo.getGpsMode());

        settingsRepo.setGpsMode(GameSettingsRepository.GPS_DISABLED);
        assertEquals(GameSettingsRepository.GPS_DISABLED, settingsRepo.getGpsMode());
    }

    @Test
    public void testOnlyServerSettingsToggle() {
        assertTrue(settingsRepo.isOnlyServerSettings());

        settingsRepo.setOnlyServerSettings(false);
        assertFalse(settingsRepo.isOnlyServerSettings());
    }
}
