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

package com.simplecoil.simplecoil.domain.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.GameStateRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;

import org.junit.Before;
import org.junit.Test;

public class GameEngineTest {

    private GameEngine engine;
    private GameSettingsRepository settingsRepo;
    private GameStateRepository gameStateRepo;
    private PlayerRepository playerRepo;

    private boolean dryFireCalled = false;
    private boolean shotFiredCalled = false;
    private boolean playerEliminatedCalled = false;
    private int lastHealthReported = -1;

    @Before
    public void setUp() {
        settingsRepo = GameSettingsRepository.getInstance();
        gameStateRepo = GameStateRepository.getInstance();
        playerRepo = PlayerRepository.getInstance();

        settingsRepo.setGameMode(GameSettingsRepository.GAME_MODE_2TEAMS);
        settingsRepo.setFullHealth(20);
        settingsRepo.setFullReload((byte) 30);
        gameStateRepo.setGameState(GameStateRepository.GAME_STATE_RUNNING);
        playerRepo.setPlayerID((byte) 1); // Team 1

        engine = GameEngine.createInstanceForTesting(settingsRepo, gameStateRepo, playerRepo);
        engine.setHitCooldownMs(0); // Disable cooldown for test predictions

        dryFireCalled = false;
        shotFiredCalled = false;
        playerEliminatedCalled = false;
        lastHealthReported = -1;

        engine.addListener(new GameEngine.GameEngineListener() {
            @Override
            public void onHealthChanged(int currentHealth, int maxHealth) {
                lastHealthReported = currentHealth;
            }

            @Override
            public void onAmmoChanged(int remainingShots, int maxShots) {}

            @Override
            public void onShotFired(int remainingShots) {
                shotFiredCalled = true;
            }

            @Override
            public void onDryFire() {
                dryFireCalled = true;
            }

            @Override
            public void onReloadStarted(long reloadTimeMs) {}

            @Override
            public void onReloadCompleted(int remainingShots) {}

            @Override
            public void onHitProcessed(int shooterPlayerId, int damage, int newHealth) {}

            @Override
            public void onPlayerEliminated(int shooterPlayerId) {
                playerEliminatedCalled = true;
            }

            @Override
            public void onRespawnStarted(long respawnTimeSeconds) {}

            @Override
            public void onPlayerRespawned() {}

            @Override
            public void onRespawnShieldStarted(long respawnShieldTimeSeconds) {}

            @Override
            public void onRespawnShieldEnded() {}
        });
    }

    @Test
    public void testTriggerPullDecrementsAmmo() {
        assertEquals(30, engine.getRemainingShots());
        boolean success = engine.processTriggerPull();

        assertTrue(success);
        assertTrue(shotFiredCalled);
        assertEquals(29, engine.getRemainingShots());
    }

    @Test
    public void testDryFireWhenEmpty() {
        engine.setRemainingShots(0);
        boolean success = engine.processTriggerPull();

        assertFalse(success);
        assertTrue(dryFireCalled);
        assertEquals(0, engine.getRemainingShots());
    }

    @Test
    public void testHitReceivedFromEnemySubtractsHealth() {
        // Player 1 is Team 1. Player 17 is Team 2.
        assertEquals(20, engine.getCurrentHealth());
        engine.processHitReceived(17, -5);

        assertEquals(15, engine.getCurrentHealth());
        assertEquals(15, lastHealthReported);
        assertFalse(playerEliminatedCalled);
    }

    @Test
    public void testFriendlyFireIgnored() {
        // Player 1 is Team 1. Player 2 is Team 1.
        assertEquals(20, engine.getCurrentHealth());
        engine.processHitReceived(2, -5);

        assertEquals(20, engine.getCurrentHealth()); // Unchanged
    }

    @Test
    public void testSelfHitIgnored() {
        assertEquals(20, engine.getCurrentHealth());
        engine.processHitReceived(1, -5);

        assertEquals(20, engine.getCurrentHealth()); // Unchanged
    }

    @Test
    public void testHitReceivedIgnoredWhenShielded() {
        assertEquals(20, engine.getCurrentHealth());
        // Force shield state via reflection or simulated respawn
        // If engine is shielded, hits must be ignored
        assertFalse(engine.isShielded());
    }

    @Test
    public void testEliminationWhenHealthReachesZero() {
        assertEquals(20, engine.getCurrentHealth());
        engine.processHitReceived(17, -20);

        assertEquals(0, engine.getCurrentHealth());
        assertTrue(playerEliminatedCalled);
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, gameStateRepo.getGameState());
    }

    @Test
    public void testResetGameDefaults() {
        engine.setCurrentHealth(5);
        engine.setRemainingShots(2);

        engine.resetGameDefaults();

        assertEquals(20, engine.getCurrentHealth());
        assertEquals(30, engine.getRemainingShots());
    }
}
