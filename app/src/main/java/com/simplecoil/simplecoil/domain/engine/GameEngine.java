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

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.GameStateRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class GameEngine {
    private static volatile GameEngine instance;

    private static final long DEFAULT_HIT_COOLDOWN_MS = 500L;

    private final GameSettingsRepository settingsRepo;
    private final GameStateRepository gameStateRepo;
    private final PlayerRepository playerRepo;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> reloadFuture;
    private ScheduledFuture<?> respawnFuture;
    private ScheduledFuture<?> respawnShieldFuture;

    private final List<GameEngineListener> listeners = new CopyOnWriteArrayList<>();

    private volatile int currentHealth;
    private volatile int remainingShots;
    private volatile boolean isReloading = false;
    private volatile boolean isRespawning = false;
    private volatile boolean isShielded = false;
    private volatile long lastHitTimestamp = 0L;
    private volatile long hitCooldownMs = DEFAULT_HIT_COOLDOWN_MS;

    public interface GameEngineListener {
        void onHealthChanged(int currentHealth, int maxHealth);
        void onAmmoChanged(int remainingShots, int maxShots);
        void onShotFired(int remainingShots);
        void onDryFire();
        void onReloadStarted(long reloadTimeMs);
        void onReloadCompleted(int remainingShots);
        void onHitProcessed(int shooterPlayerId, int damage, int newHealth);
        void onPlayerEliminated(int shooterPlayerId);
        void onRespawnStarted(long respawnTimeSeconds);
        void onPlayerRespawned();
        void onRespawnShieldStarted(long respawnShieldTimeSeconds);
        void onRespawnShieldEnded();
    }

    private GameEngine(GameSettingsRepository settingsRepo, GameStateRepository gameStateRepo, PlayerRepository playerRepo) {
        this.settingsRepo = settingsRepo;
        this.gameStateRepo = gameStateRepo;
        this.playerRepo = playerRepo;

        this.currentHealth = settingsRepo.getFullHealth();
        this.remainingShots = settingsRepo.getFullReload();
    }

    public static GameEngine getInstance() {
        if (instance == null) {
            synchronized (GameEngine.class) {
                if (instance == null) {
                    instance = new GameEngine(
                            GameSettingsRepository.getInstance(),
                            GameStateRepository.getInstance(),
                            PlayerRepository.getInstance()
                    );
                }
            }
        }
        return instance;
    }

    public static GameEngine createInstanceForTesting(GameSettingsRepository settingsRepo,
                                                      GameStateRepository gameStateRepo,
                                                      PlayerRepository playerRepo) {
        return new GameEngine(settingsRepo, gameStateRepo, playerRepo);
    }

    public void addListener(GameEngineListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(GameEngineListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public synchronized boolean processTriggerPull() {
        if (!gameStateRepo.isGameRunning()) {
            return false;
        }
        if (isReloading || isRespawning) {
            return false;
        }
        if (remainingShots <= 0) {
            for (GameEngineListener listener : listeners) {
                listener.onDryFire();
            }
            if (settingsRepo.isReloadOnEmpty()) {
                processReload();
            }
            return false;
        }

        remainingShots--;

        for (GameEngineListener listener : listeners) {
            listener.onShotFired(remainingShots);
            listener.onAmmoChanged(remainingShots, settingsRepo.getFullReload());
        }

        if (remainingShots == 0 && settingsRepo.isReloadOnEmpty()) {
            processReload();
        }
        return true;
    }

    public synchronized void processReload() {
        if (!gameStateRepo.isGameRunning() || isRespawning || isReloading) {
            return;
        }
        if (remainingShots >= settingsRepo.getFullReload()) {
            return;
        }

        isReloading = true;
        long reloadTimeMs = settingsRepo.getReloadTime();

        for (GameEngineListener listener : listeners) {
            listener.onReloadStarted(reloadTimeMs);
        }

        if (reloadFuture != null && !reloadFuture.isDone()) {
            reloadFuture.cancel(true);
        }

        reloadFuture = scheduler.schedule(() -> {
            synchronized (GameEngine.this) {
                remainingShots = settingsRepo.getFullReload();
                isReloading = false;

                for (GameEngineListener listener : listeners) {
                    listener.onReloadCompleted(remainingShots);
                    listener.onAmmoChanged(remainingShots, settingsRepo.getFullReload());
                }
            }
        }, reloadTimeMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void processHitReceived(int shooterPlayerId, int damageAmount) {
        if (!gameStateRepo.isGameRunning() || isRespawning || isShielded) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastHitTimestamp < hitCooldownMs) {
            return; // Ignore hit due to cooldown
        }

        byte localPlayerId = playerRepo.getPlayerID();
        if (localPlayerId != 0 && shooterPlayerId == localPlayerId) {
            return; // Ignore self-hit
        }

        if (shooterPlayerId > 0 && shooterPlayerId <= GameSettingsRepository.MAX_PLAYER_ID) {
            int shooterTeam = playerRepo.calcNetworkTeam((byte) shooterPlayerId);
            int localTeam = playerRepo.calcNetworkTeam(localPlayerId);

            int gameMode = settingsRepo.getGameMode();
            if (gameMode != GameSettingsRepository.GAME_MODE_FFA && shooterTeam == localTeam) {
                return; // Ignore friendly fire
            }
        }

        lastHitTimestamp = now;
        int absDamage = Math.abs(damageAmount);
        currentHealth -= absDamage;

        if (currentHealth < 0) {
            currentHealth = 0;
        }

        for (GameEngineListener listener : listeners) {
            listener.onHitProcessed(shooterPlayerId, absDamage, currentHealth);
            listener.onHealthChanged(currentHealth, settingsRepo.getFullHealth());
        }

        if (currentHealth <= 0) {
            gameStateRepo.setGameState(GameStateRepository.GAME_STATE_ELIMINATED);
            for (GameEngineListener listener : listeners) {
                listener.onPlayerEliminated(shooterPlayerId);
            }
            startRespawnTimer();
        }
    }

    private synchronized void startRespawnTimer() {
        isRespawning = true;
        long respawnTimeSec = settingsRepo.getRespawnTime();

        for (GameEngineListener listener : listeners) {
            listener.onRespawnStarted(respawnTimeSec);
        }

        if (respawnFuture != null && !respawnFuture.isDone()) {
            respawnFuture.cancel(true);
        }

        respawnFuture = scheduler.schedule(() -> {
            synchronized (GameEngine.this) {
                currentHealth = settingsRepo.getFullHealth();
                remainingShots = settingsRepo.getFullReload();
                isRespawning = false;
                isReloading = false;

                gameStateRepo.setGameState(GameStateRepository.GAME_STATE_RUNNING);

                for (GameEngineListener listener : listeners) {
                    listener.onPlayerRespawned();
                    listener.onHealthChanged(currentHealth, settingsRepo.getFullHealth());
                    listener.onAmmoChanged(remainingShots, settingsRepo.getFullReload());
                }

                long shieldTimeSec = settingsRepo.getRespawnShieldTime();
                if (shieldTimeSec > 0) {
                    startRespawnShieldTimer(shieldTimeSec);
                } else {
                    isShielded = false;
                }
            }
        }, respawnTimeSec, TimeUnit.SECONDS);
    }

    private synchronized void startRespawnShieldTimer(long shieldTimeSec) {
        isShielded = true;
        for (GameEngineListener listener : listeners) {
            listener.onRespawnShieldStarted(shieldTimeSec);
        }

        if (respawnShieldFuture != null && !respawnShieldFuture.isDone()) {
            respawnShieldFuture.cancel(true);
        }

        respawnShieldFuture = scheduler.schedule(() -> {
            synchronized (GameEngine.this) {
                isShielded = false;
                for (GameEngineListener listener : listeners) {
                    listener.onRespawnShieldEnded();
                }
            }
        }, shieldTimeSec, TimeUnit.SECONDS);
    }

    public synchronized void resetGameDefaults() {
        if (reloadFuture != null) reloadFuture.cancel(true);
        if (respawnFuture != null) respawnFuture.cancel(true);
        if (respawnShieldFuture != null) respawnShieldFuture.cancel(true);

        isReloading = false;
        isRespawning = false;
        isShielded = false;
        currentHealth = settingsRepo.getFullHealth();
        remainingShots = settingsRepo.getFullReload();

        for (GameEngineListener listener : listeners) {
            listener.onHealthChanged(currentHealth, settingsRepo.getFullHealth());
            listener.onAmmoChanged(remainingShots, settingsRepo.getFullReload());
        }
    }

    // Getters / Setters
    public int getCurrentHealth() { return currentHealth; }
    public void setCurrentHealth(int health) { this.currentHealth = health; }

    public int getRemainingShots() { return remainingShots; }
    public void setRemainingShots(int shots) { this.remainingShots = shots; }

    public boolean isReloading() { return isReloading; }
    public boolean isRespawning() { return isRespawning; }
    public boolean isShielded() { return isShielded; }

    public long getHitCooldownMs() { return hitCooldownMs; }
    public void setHitCooldownMs(long hitCooldownMs) { this.hitCooldownMs = hitCooldownMs; }
}
