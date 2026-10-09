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

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Test
    public void testConcurrentGameModeAndTeamIdAccess() throws InterruptedException {
        int numThreads = 10;
        int iterations = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        AtomicBoolean hasError = new AtomicBoolean(false);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.execute(() -> {
                try {
                    for (int j = 0; j < iterations; j++) {
                        if (threadId % 2 == 0) {
                            int mode = (j % 2 == 0) ? GameSettingsRepository.GAME_MODE_4TEAMS
                                                    : GameSettingsRepository.GAME_MODE_2TEAMS;
                            repository.setGameMode(mode);
                        } else {
                            int team = (j % 4) + 1;
                            int startId = repository.getTeamStartID(team);
                            int endId = repository.getTeamEndID(team);
                            assertTrue(startId >= 1);
                            assertTrue(endId <= GameSettingsRepository.MAX_PLAYER_ID);
                        }
                    }
                } catch (Throwable t) {
                    hasError.set(true);
                }
            });
        }

        executor.shutdown();
        assertTrue("Executor did not terminate in time", executor.awaitTermination(10, TimeUnit.SECONDS));
        Assert.assertFalse("Concurrent access threw an exception", hasError.get());
    }

    @Test
    public void testConcurrentGameLimitFlagOperations() throws InterruptedException {
        int numThreads = 8;
        int iterations = 500;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        AtomicBoolean hasError = new AtomicBoolean(false);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.execute(() -> {
                try {
                    for (int j = 0; j < iterations; j++) {
                        if (threadId % 3 == 0) {
                            repository.addGameLimitFlag(GameSettingsRepository.GAME_LIMIT_TIME);
                        } else if (threadId % 3 == 1) {
                            repository.removeGameLimitFlag(GameSettingsRepository.GAME_LIMIT_TIME);
                        } else {
                            repository.setGameLimit(GameSettingsRepository.GAME_LIMIT_LIVES);
                        }
                    }
                } catch (Throwable t) {
                    hasError.set(true);
                }
            });
        }

        executor.shutdown();
        assertTrue("Executor did not terminate in time", executor.awaitTermination(10, TimeUnit.SECONDS));
        Assert.assertFalse("Concurrent flag operations threw an exception", hasError.get());
    }
}
