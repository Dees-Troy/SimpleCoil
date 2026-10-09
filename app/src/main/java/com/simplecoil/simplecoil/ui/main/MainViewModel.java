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

package com.simplecoil.simplecoil.ui.main;

import android.app.Application;
import android.content.Context;
import android.location.Location;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.GameStateRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;
import com.simplecoil.simplecoil.domain.engine.GameEngine;
import com.simplecoil.simplecoil.hardware.audio.AudioHapticsManager;
import com.simplecoil.simplecoil.hardware.blaster.BlasterEventListener;
import com.simplecoil.simplecoil.hardware.blaster.BlasterManager;
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler;
import com.simplecoil.simplecoil.hardware.location.LocationUpdateListener;
import com.simplecoil.simplecoil.hardware.location.MapLocationManager;
import com.simplecoil.simplecoil.network.discovery.ServerDiscoveryManager;

import java.net.InetAddress;

/**
 * MainViewModel manages UI state and user interactions for the primary client activity.
 * It bridges repositories, game engine, BLE hardware, location, audio, and server discovery.
 */
public class MainViewModel extends AndroidViewModel implements
        GameStateRepository.GameStateChangeListener,
        GameEngine.GameEngineListener,
        BlasterEventListener,
        LocationUpdateListener,
        ServerDiscoveryManager.ServerDiscoveryListener {

    private final GameSettingsRepository settingsRepo;
    private final GameStateRepository gameStateRepo;
    private final PlayerRepository playerRepo;
    private final GameEngine gameEngine;
    private final BlasterManager blasterManager;
    private final AudioHapticsManager audioHapticsManager;
    private final MapLocationManager mapLocationManager;
    private final ServerDiscoveryManager serverDiscoveryManager;

    private final MutableLiveData<Integer> healthLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> maxHealthLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> ammoLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> maxAmmoLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> gameStateLiveData = new MutableLiveData<>();
    private final MutableLiveData<Long> timeRemainingLiveData = new MutableLiveData<>();
    private final MutableLiveData<InetAddress> serverIPLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> blasterConnectedLiveData = new MutableLiveData<>();
    private final MutableLiveData<Long> batteryLevelLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> recoilEnabledLiveData = new MutableLiveData<>();
    private final MutableLiveData<Byte> playerIdLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> playerNameLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> playerCountLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> teamLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> scoreLiveData = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> teamScoreLiveData = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> livesLiveData = new MutableLiveData<>(0);
    private final MutableLiveData<Location> locationLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> discoveredServerIpLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> discoveredServerNameLiveData = new MutableLiveData<>();

    public MainViewModel(@NonNull Application application) {
        this(
                application,
                GameSettingsRepository.getInstance(),
                GameStateRepository.getInstance(),
                PlayerRepository.getInstance(),
                GameEngine.getInstance(),
                BlasterManager.getInstance(),
                AudioHapticsManager.getInstance(),
                MapLocationManager.getInstance(),
                new ServerDiscoveryManager(application)
        );
    }

    public MainViewModel(
            @NonNull Application application,
            GameSettingsRepository settingsRepo,
            GameStateRepository gameStateRepo,
            PlayerRepository playerRepo,
            GameEngine gameEngine,
            BlasterManager blasterManager,
            AudioHapticsManager audioHapticsManager,
            MapLocationManager mapLocationManager,
            ServerDiscoveryManager serverDiscoveryManager) {

        super(application);
        this.settingsRepo = settingsRepo;
        this.gameStateRepo = gameStateRepo;
        this.playerRepo = playerRepo;
        this.gameEngine = gameEngine;
        this.blasterManager = blasterManager;
        this.audioHapticsManager = audioHapticsManager;
        this.mapLocationManager = mapLocationManager;
        this.serverDiscoveryManager = serverDiscoveryManager;

        initInitialState();
        registerListeners();
    }

    private void initInitialState() {
        healthLiveData.postValue(settingsRepo.getFullHealth());
        maxHealthLiveData.postValue(settingsRepo.getFullHealth());
        ammoLiveData.postValue((int) settingsRepo.getFullReload());
        maxAmmoLiveData.postValue((int) settingsRepo.getFullReload());
        gameStateLiveData.postValue(gameStateRepo.getGameState());
        timeRemainingLiveData.postValue(gameStateRepo.getServerGameTimeRemaining());
        serverIPLiveData.postValue(gameStateRepo.getServerIP());
        blasterConnectedLiveData.postValue(blasterManager.isConnected());
        recoilEnabledLiveData.postValue(blasterManager.isRecoilEnabled());
        playerIdLiveData.postValue(playerRepo.getPlayerID());
        playerNameLiveData.postValue(playerRepo.getPlayerName());
        playerCountLiveData.postValue(playerRepo.getPlayerCount(false));
        teamLiveData.postValue(playerRepo.calcNetworkTeam(playerRepo.getPlayerID()));
    }

    private void registerListeners() {
        gameStateRepo.addListener(this);
        gameEngine.addListener(this);
        blasterManager.setListener(this);
        audioHapticsManager.initialize(getApplication());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        gameStateRepo.removeListener(this);
        gameEngine.removeListener(this);
        mapLocationManager.stopLocationUpdates();
        serverDiscoveryManager.stopDiscovery();
    }

    // Actions
    public boolean pullTrigger() {
        boolean fired = gameEngine.processTriggerPull();
        if (fired) {
            audioHapticsManager.playShootSound();
        }
        return fired;
    }

    public void reload() {
        gameEngine.processReload();
        audioHapticsManager.playReloadSound();
    }

    public void processHit(int shooterPlayerId, int damageAmount) {
        gameEngine.processHitReceived(shooterPlayerId, damageAmount);
    }

    public void connectBlaster(String deviceAddress) {
        blasterManager.connect(deviceAddress);
    }

    public void disconnectBlaster() {
        blasterManager.disconnect();
    }

    public void startServerDiscovery() {
        serverDiscoveryManager.startDiscovery(this);
    }

    public void stopServerDiscovery() {
        serverDiscoveryManager.stopDiscovery();
    }

    public void startLocationUpdates(Context context) {
        mapLocationManager.startLocationUpdates(context, this);
    }

    public void stopLocationUpdates() {
        mapLocationManager.stopLocationUpdates();
    }

    public void setPlayerName(String name) {
        playerRepo.setPlayerName(name);
        playerNameLiveData.postValue(playerRepo.getPlayerName());
    }

    public void updatePlayerCount() {
        playerCountLiveData.postValue(playerRepo.getPlayerCount(false));
    }

    public void updatePlayerTeam() {
        teamLiveData.postValue(playerRepo.calcNetworkTeam(playerRepo.getPlayerID()));
    }

    // GameStateChangeListener
    @Override
    public void onGameStateChanged(int oldState, int newState) {
        gameStateLiveData.postValue(newState);
    }

    @Override
    public void onTimeRemainingUpdated(long secondsRemaining) {
        timeRemainingLiveData.postValue(secondsRemaining);
    }

    @Override
    public void onServerIPChanged(InetAddress serverIP) {
        serverIPLiveData.postValue(serverIP);
    }

    // GameEngineListener
    @Override
    public void onHealthChanged(int currentHealth, int maxHealth) {
        healthLiveData.postValue(currentHealth);
        maxHealthLiveData.postValue(maxHealth);
    }

    @Override
    public void onAmmoChanged(int remainingShots, int maxShots) {
        ammoLiveData.postValue(remainingShots);
        maxAmmoLiveData.postValue(maxShots);
    }

    @Override
    public void onShotFired(int remainingShots) {
        ammoLiveData.postValue(remainingShots);
    }

    @Override
    public void onDryFire() {
        audioHapticsManager.playEmptySound();
    }

    @Override
    public void onReloadStarted(long reloadTimeMs) {
        audioHapticsManager.playReloadSound();
    }

    @Override
    public void onReloadCompleted(int remainingShots) {
        ammoLiveData.postValue(remainingShots);
    }

    @Override
    public void onHitProcessed(int shooterPlayerId, int damage, int newHealth) {
        healthLiveData.postValue(newHealth);
        audioHapticsManager.playHitFeedback();
    }

    @Override
    public void onPlayerEliminated(int shooterPlayerId) {
        healthLiveData.postValue(0);
        gameStateLiveData.postValue(GameStateRepository.GAME_STATE_ELIMINATED);
        audioHapticsManager.playEliminatedFeedback();
    }

    @Override
    public void onRespawnStarted(long respawnTimeSeconds) {
        timeRemainingLiveData.postValue(respawnTimeSeconds);
    }

    @Override
    public void onPlayerRespawned() {
        healthLiveData.postValue(settingsRepo.getFullHealth());
        ammoLiveData.postValue((int) settingsRepo.getFullReload());
        gameStateLiveData.postValue(GameStateRepository.GAME_STATE_RUNNING);
        audioHapticsManager.playSpawnSound();
    }

    // BlasterEventListener
    @Override
    public void onBlasterConnected(String deviceAddress) {
        blasterConnectedLiveData.postValue(true);
    }

    @Override
    public void onBlasterDisconnected() {
        blasterConnectedLiveData.postValue(false);
    }

    @Override
    public void onServicesDiscovered() {}

    @Override
    public void onBlasterTypeDiscovered(byte blasterType) {}

    @Override
    public void onCharacteristicWriteFinished() {}

    @Override
    public void onTelemetryReceived(GattProtocolHandler.TelemetryData telemetry) {}

    @Override
    public void onPlayerIdChanged(byte playerId) {
        playerIdLiveData.postValue(playerId);
        updatePlayerTeam();
    }

    @Override
    public void onTriggerPulled(byte shotsRemaining) {
        pullTrigger();
    }

    @Override
    public void onReloadRequested() {
        reload();
    }

    @Override
    public void onShotModeChanged(int newMode) {}

    @Override
    public void onRecoilToggled(boolean enabled) {
        recoilEnabledLiveData.postValue(enabled);
    }

    @Override
    public void onBatteryLevelUpdated(long averageBatteryLevel, byte blasterType) {
        batteryLevelLiveData.postValue(averageBatteryLevel);
    }

    @Override
    public void onHitReceived(int shooterPlayerId, byte shotId, boolean isSecondaryHit) {
        processHit(shooterPlayerId, settingsRepo.getDamage());
    }

    // LocationUpdateListener
    @Override
    public void onLocationUpdated(Location location, double latitude, double longitude) {
        locationLiveData.postValue(location);
    }

    @Override
    public void onPermissionRequired() {}

    // ServerDiscoveryListener
    @Override
    public void onServerDiscovered(String serverIP, String serverName) {
        discoveredServerIpLiveData.postValue(serverIP);
        discoveredServerNameLiveData.postValue(serverName);
    }

    @Override
    public void onDiscoveryFailed(String errorReason) {}

    // LiveData Getters
    public LiveData<Integer> getHealthLiveData() { return healthLiveData; }
    public LiveData<Integer> getMaxHealthLiveData() { return maxHealthLiveData; }
    public LiveData<Integer> getAmmoLiveData() { return ammoLiveData; }
    public LiveData<Integer> getMaxAmmoLiveData() { return maxAmmoLiveData; }
    public LiveData<Integer> getGameStateLiveData() { return gameStateLiveData; }
    public LiveData<Long> getTimeRemainingLiveData() { return timeRemainingLiveData; }
    public LiveData<InetAddress> getServerIPLiveData() { return serverIPLiveData; }
    public LiveData<Boolean> getBlasterConnectedLiveData() { return blasterConnectedLiveData; }
    public LiveData<Long> getBatteryLevelLiveData() { return batteryLevelLiveData; }
    public LiveData<Boolean> getRecoilEnabledLiveData() { return recoilEnabledLiveData; }
    public LiveData<Byte> getPlayerIdLiveData() { return playerIdLiveData; }
    public LiveData<String> getPlayerNameLiveData() { return playerNameLiveData; }
    public LiveData<Integer> getPlayerCountLiveData() { return playerCountLiveData; }
    public LiveData<Integer> getTeamLiveData() { return teamLiveData; }
    public LiveData<Integer> getScoreLiveData() { return scoreLiveData; }
    public LiveData<Integer> getTeamScoreLiveData() { return teamScoreLiveData; }
    public LiveData<Integer> getLivesLiveData() { return livesLiveData; }

    public void setScore(int score) { scoreLiveData.postValue(score); }
    public void incrementScore() {
        Integer current = scoreLiveData.getValue();
        scoreLiveData.postValue((current != null ? current : 0) + 1);
    }
    public void setTeamScore(int teamScore) { teamScoreLiveData.postValue(teamScore); }
    public void incrementTeamScore() {
        Integer current = teamScoreLiveData.getValue();
        teamScoreLiveData.postValue((current != null ? current : 0) + 1);
    }
    public void setLives(int lives) { livesLiveData.postValue(lives); }
    public LiveData<Location> getLocationLiveData() { return locationLiveData; }
    public LiveData<String> getDiscoveredServerIpLiveData() { return discoveredServerIpLiveData; }
    public LiveData<String> getDiscoveredServerNameLiveData() { return discoveredServerNameLiveData; }
}
