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
import static org.junit.Assert.assertTrue;

import com.simplecoil.simplecoil.domain.team.TeamLayout;
import com.simplecoil.simplecoil.domain.team.TeamMode;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.ArrayList;
import java.util.List;

public class GameSettingsRepositoryTest {

    private GameSettingsRepository repository;

    @Before
    public void setUp() {
        repository = new GameSettingsRepository();
    }

    @Test
    public void testDefaultSettings() {
        assertEquals(GameSettingsRepository.MAX_HEALTH, repository.getFullHealth());
        assertEquals(GameSettingsRepository.RELOAD_COUNT, repository.getFullReload());
        assertEquals(GameSettingsRepository.RESPAWN_SHIELD_TIME_SECONDS, repository.getRespawnShieldTime());
        assertEquals(GameSettingsRepository.START_TIME_SECONDS, repository.getStartTime());
        assertEquals(TeamMode.TWO_TEAMS, repository.getTeamMode());
        assertEquals(GameSettingsRepository.GAME_LIMIT_NONE, repository.getGameLimit());
    }

    @Test
    public void testTeamSizeCalculation2Teams() {
        repository.setTeamMode(TeamMode.TWO_TEAMS);
        TeamLayout layout = repository.getTeamLayout();
        assertArrayEquals(new int[]{16, 16}, layout.teamSizes());
        assertEquals(1, layout.teamStartId(1));
        assertEquals(16, layout.teamEndId(1));
        assertEquals(17, layout.teamStartId(2));
        assertEquals(32, layout.teamEndId(2));
    }

    @Test
    public void testTeamSizeCalculation4Teams() {
        repository.setTeamMode(TeamMode.FOUR_TEAMS);
        TeamLayout layout = repository.getTeamLayout();
        assertArrayEquals(new int[]{8, 8, 8, 8}, layout.teamSizes());
        assertEquals(1, layout.teamStartId(1));
        assertEquals(8, layout.teamEndId(1));
        assertEquals(9, layout.teamStartId(2));
        assertEquals(16, layout.teamEndId(2));
        assertEquals(17, layout.teamStartId(3));
        assertEquals(24, layout.teamEndId(3));
        assertEquals(25, layout.teamStartId(4));
        assertEquals(32, layout.teamEndId(4));
    }

    @Test
    public void testCustomTeamSizes() {
        repository.setTeamMode(TeamMode.TWO_TEAMS);
        repository.setTeamSizes(new int[]{20, 12});
        assertArrayEquals(new int[]{20, 12}, repository.getTeamLayout().teamSizes());

        // Setting the same team mode again keeps custom team sizes
        repository.setTeamMode(TeamMode.TWO_TEAMS);
        TeamLayout layout = repository.getTeamLayout();
        assertArrayEquals(new int[]{20, 12}, layout.teamSizes());

        assertEquals(1, layout.teamStartId(1));
        assertEquals(20, layout.teamEndId(1));
        assertEquals(21, layout.teamStartId(2));
        assertEquals(32, layout.teamEndId(2));

        // A different mode starts from its defaults
        repository.setTeamMode(TeamMode.FOUR_TEAMS);
        assertArrayEquals(new int[]{8, 8, 8, 8}, repository.getTeamLayout().teamSizes());
    }

    @Test
    public void testInvalidTeamSizesFallBackToDefaults() {
        repository.setTeamMode(TeamMode.FOUR_TEAMS);
        repository.setTeamSizes(new int[]{16, 16}); // two sizes for four teams
        assertArrayEquals(new int[]{8, 8, 8, 8}, repository.getTeamLayout().teamSizes());
        repository.setTeamSizes(new int[]{10, 10, 10, 10}); // doesn't add up to MAX_PLAYER_ID
        assertArrayEquals(new int[]{8, 8, 8, 8}, repository.getTeamLayout().teamSizes());
    }

    @Test
    public void testServerGameInfoAppliesModeAndSizesTogether() {
        repository.setTeamModeAndSizes(TeamMode.FOUR_TEAMS, new int[]{5, 9, 9, 9});
        TeamLayout layout = repository.getTeamLayout();
        assertEquals(TeamMode.FOUR_TEAMS, layout.getMode());
        assertArrayEquals(new int[]{5, 9, 9, 9}, layout.teamSizes());
        assertEquals(2, layout.teamOf((byte) 6));
    }

    @Test
    public void testListenersHearOnlyRealChanges() {
        List<TeamLayout> heard = new ArrayList<>();
        GameSettingsRepository.TeamLayoutListener listener = heard::add;
        repository.addTeamLayoutListener(listener);
        try {
            repository.setTeamMode(TeamMode.TWO_TEAMS); // unchanged
            repository.setTeamMode(TeamMode.FREE_FOR_ALL);
            repository.setTeamSizes(new int[]{20, 12});
        } finally {
            repository.removeTeamLayoutListener(listener);
        }
        assertEquals(2, heard.size());
        assertEquals(TeamMode.FREE_FOR_ALL, heard.get(0).getMode());
        assertArrayEquals(new int[]{20, 12}, heard.get(1).teamSizes());
    }

    @Test
    public void testCustomSettingsMutation() {
        repository.setFullHealth(50);
        assertEquals(50, repository.getFullHealth());

        repository.setTimeLimit(600);
        assertEquals(600, repository.getTimeLimit());

        repository.setStartTime(8);
        assertEquals(8, repository.getStartTime());

        repository.setReloadOnEmpty(true);
        assertTrue(repository.isReloadOnEmpty());
    }

    /** Writers flip the mode and sizes while readers check that every snapshot is self-consistent. */
    @Test
    public void testConcurrentTeamLayoutSnapshotsAreConsistent() throws InterruptedException {
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
                            repository.setTeamMode((j % 2 == 0) ? TeamMode.FOUR_TEAMS : TeamMode.TWO_TEAMS);
                            if (j % 3 == 0) repository.setTeamSizes(new int[]{20, 12});
                        } else {
                            TeamLayout layout = repository.getTeamLayout();
                            int[] sizes = layout.teamSizes();
                            int expectedTeams = layout.getMode() == TeamMode.FOUR_TEAMS ? 4 : 2;
                            if (sizes.length != expectedTeams) throw new AssertionError("mixed layout " + layout);
                            for (int team = 1; team <= expectedTeams; team++) {
                                int startId = layout.teamStartId(team);
                                int endId = layout.teamEndId(team);
                                if (layout.teamOf((byte) startId) != team || layout.teamOf((byte) endId) != team)
                                    throw new AssertionError("team " + team + " ids disagree in " + layout);
                            }
                        }
                    }
                } catch (Throwable t) {
                    hasError.set(true);
                }
            });
        }

        executor.shutdown();
        assertTrue("Executor did not terminate in time", executor.awaitTermination(10, TimeUnit.SECONDS));
        Assert.assertFalse("A reader saw an inconsistent team layout", hasError.get());
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
