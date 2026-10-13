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

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.GameStateRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;
import com.simplecoil.simplecoil.network.discovery.ServerDiscoveryManager;
import com.simplecoil.simplecoil.network.server.SessionPhase;

import java.net.InetAddress;

/**
 * DedicatedServerViewModel manages state and user preferences for DedicatedServerActivity.
 */
public class DedicatedServerViewModel extends AndroidViewModel implements GameStateRepository.GameStateChangeListener {

    private final GameSettingsRepository settingsRepo;
    private final GameStateRepository gameStateRepo;
    private final PlayerRepository playerRepo;
    private final ServerDiscoveryManager discoveryManager;

    private final MutableLiveData<Integer> gameStateLiveData = new MutableLiveData<>();
    private final MutableLiveData<Long> timeRemainingLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> serverIpLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> playerCountLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> gameModeLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> gameLimitLiveData = new MutableLiveData<>();
    private final MutableLiveData<Long> startingTimerLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> gpsModeLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> allowJoinLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> onlyServerSettingsLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> allowChooseTeamsLiveData = new MutableLiveData<>();
    private final MutableLiveData<SessionPhase> phaseLiveData = new MutableLiveData<>(SessionPhase.LOBBY);

    public DedicatedServerViewModel(@NonNull Application application) {
        this(
                application,
                GameSettingsRepository.getInstance(),
                GameStateRepository.getInstance(),
                PlayerRepository.getInstance(),
                new ServerDiscoveryManager(application)
        );
    }

    public DedicatedServerViewModel(
            @NonNull Application application,
            GameSettingsRepository settingsRepo,
            GameStateRepository gameStateRepo,
            PlayerRepository playerRepo,
            ServerDiscoveryManager discoveryManager) {

        super(application);
        this.settingsRepo = settingsRepo;
        this.gameStateRepo = gameStateRepo;
        this.playerRepo = playerRepo;
        this.discoveryManager = discoveryManager;

        initInitialState();
        this.gameStateRepo.addListener(this);
    }

    private void initInitialState() {
        gameStateLiveData.postValue(gameStateRepo.getGameState());
        timeRemainingLiveData.postValue(gameStateRepo.getServerGameTimeRemaining());
        serverIpLiveData.postValue(PlayerRepository.getIPAddressStr());
        playerCountLiveData.postValue(playerRepo.getPlayerCount(true));
        gameModeLiveData.postValue(settingsRepo.getGameMode());
        gameLimitLiveData.postValue(settingsRepo.getGameLimit());
        startingTimerLiveData.postValue(settingsRepo.getStartTime());
        gpsModeLiveData.postValue(settingsRepo.getGpsMode());
        allowJoinLiveData.postValue(true);
        onlyServerSettingsLiveData.postValue(settingsRepo.isOnlyServerSettings());
        allowChooseTeamsLiveData.postValue(settingsRepo.isAllowChooseTeams());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        gameStateRepo.removeListener(this);
        discoveryManager.stopBroadcastingBeacon();
    }

    // Actions
    public void startServerBeacon(String serverName) {
        discoveryManager.startBroadcastingBeacon(serverName);
    }

    public void stopServerBeacon() {
        discoveryManager.stopBroadcastingBeacon();
    }

    public void setGameMode(int gameMode) {
        settingsRepo.setGameMode(gameMode);
        gameModeLiveData.postValue(settingsRepo.getGameMode());
    }

    public void setGpsMode(int gpsMode) {
        settingsRepo.setGpsMode(gpsMode);
        gpsModeLiveData.postValue(settingsRepo.getGpsMode());
    }

    public void setGameLimits(int timeLimit, int livesLimit, int scoreLimit) {
        int gameLimit = GameSettingsRepository.GAME_LIMIT_NONE;
        if (timeLimit > 0) gameLimit |= GameSettingsRepository.GAME_LIMIT_TIME;
        if (livesLimit > 0) gameLimit |= GameSettingsRepository.GAME_LIMIT_LIVES;
        if (scoreLimit > 0) gameLimit |= GameSettingsRepository.GAME_LIMIT_SCORE;

        settingsRepo.setGameLimit(gameLimit);
        settingsRepo.setTimeLimit(timeLimit);
        settingsRepo.setLivesLimit(livesLimit);
        settingsRepo.setScoreLimit(scoreLimit);

        gameLimitLiveData.postValue(settingsRepo.getGameLimit());
    }

    public void setStartTime(long startTime) {
        settingsRepo.setStartTime(startTime);
        startingTimerLiveData.postValue(settingsRepo.getStartTime());
    }

    public void setAllowJoin(boolean allowJoin) {
        allowJoinLiveData.postValue(allowJoin);
    }

    public void setOnlyServerSettings(boolean onlyServerSettings) {
        settingsRepo.setOnlyServerSettings(onlyServerSettings);
        onlyServerSettingsLiveData.postValue(settingsRepo.isOnlyServerSettings());
    }

    public void setAllowChooseTeams(boolean allowChooseTeams) {
        settingsRepo.setAllowChooseTeams(allowChooseTeams);
        allowChooseTeamsLiveData.postValue(settingsRepo.isAllowChooseTeams());
    }

    /** The server's session phase, from its STARTGAME / ENDGAME / SESSIONRESET broadcasts. Main thread. */
    public void setPhase(SessionPhase phase) {
        phaseLiveData.setValue(phase);
    }

    public void updatePlayerCount() {
        playerCountLiveData.postValue(playerRepo.getPlayerCount(true));
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
        if (serverIP != null) {
            serverIpLiveData.postValue(serverIP.getHostAddress());
        }
    }

    // Getters for LiveData
    public LiveData<Integer> getGameStateLiveData() { return gameStateLiveData; }
    public LiveData<Long> getTimeRemainingLiveData() { return timeRemainingLiveData; }
    public LiveData<String> getServerIpLiveData() { return serverIpLiveData; }
    public LiveData<Integer> getPlayerCountLiveData() { return playerCountLiveData; }
    public LiveData<Integer> getGameModeLiveData() { return gameModeLiveData; }
    public LiveData<Integer> getGameLimitLiveData() { return gameLimitLiveData; }
    public LiveData<Long> getStartingTimerLiveData() { return startingTimerLiveData; }
    public LiveData<Integer> getGpsModeLiveData() { return gpsModeLiveData; }
    public LiveData<Boolean> getAllowJoinLiveData() { return allowJoinLiveData; }
    public LiveData<Boolean> getOnlyServerSettingsLiveData() { return onlyServerSettingsLiveData; }
    public LiveData<Boolean> getAllowChooseTeamsLiveData() { return allowChooseTeamsLiveData; }
    public LiveData<SessionPhase> getPhaseLiveData() { return phaseLiveData; }
}
