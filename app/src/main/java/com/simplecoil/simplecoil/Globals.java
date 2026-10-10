/*
 * Copyright (C) 2018 Ethan Yonker
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

package com.simplecoil.simplecoil;

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.GameStateRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * Globals - Legacy singleton bridge forwarding access to
 * GameSettingsRepository, GameStateRepository, and PlayerRepository.
 */
public class Globals {
    private static Globals mInstance = null;

    public static final byte MAX_PLAYER_ID = GameSettingsRepository.MAX_PLAYER_ID;
    public static final byte RELOAD_COUNT = GameSettingsRepository.RELOAD_COUNT;
    public static final long RESPAWN_TIME_SECONDS = GameSettingsRepository.RESPAWN_TIME_SECONDS;
    public static final long RELOAD_TIME_MILLISECONDS = GameSettingsRepository.RELOAD_TIME_MILLISECONDS;
    public static final int MAX_HEALTH = GameSettingsRepository.MAX_HEALTH;
    public static final int DAMAGE_PER_HIT = GameSettingsRepository.DAMAGE_PER_HIT;

    public volatile byte mFullReload = RELOAD_COUNT;
    public volatile long mRespawnTime = RESPAWN_TIME_SECONDS;
    public volatile long mReloadTime = RELOAD_TIME_MILLISECONDS;
    public volatile int mFullHealth = MAX_HEALTH;
    public volatile int mDamage = DAMAGE_PER_HIT;
    public volatile boolean mOverrideLives = false;
    public volatile int mOverrideLivesVal = 0;
    public volatile boolean mAllowPlayerSettings = true;
    public volatile boolean mReloadOnEmpty = false;

    public static final int INVALID_PLAYER_ID = PlayerRepository.INVALID_PLAYER_ID;
    public static final int GRENADE_PLAYER_ID = PlayerRepository.GRENADE_PLAYER_ID;

    public static final int GAME_MODE_FFA = GameSettingsRepository.GAME_MODE_FFA;
    public static final int GAME_MODE_2TEAMS = GameSettingsRepository.GAME_MODE_2TEAMS;
    public static final int GAME_MODE_4TEAMS = GameSettingsRepository.GAME_MODE_4TEAMS;
    public volatile int mGameMode = GAME_MODE_2TEAMS;

    public static final int GAME_LIMIT_NONE = GameSettingsRepository.GAME_LIMIT_NONE;
    public static final int GAME_LIMIT_TIME = GameSettingsRepository.GAME_LIMIT_TIME;
    public static final int GAME_LIMIT_LIVES = GameSettingsRepository.GAME_LIMIT_LIVES;
    public static final int GAME_LIMIT_SCORE = GameSettingsRepository.GAME_LIMIT_SCORE;
    public volatile int mGameLimit = GAME_LIMIT_NONE;
    public volatile int mTimeLimit = 0;
    public volatile int mScoreLimit = 0;
    public volatile int mLivesLimit = 0;

    public static final int GPS_DISABLED = GameSettingsRepository.GPS_DISABLED;
    public static final int GPS_TEAMMATE = GameSettingsRepository.GPS_TEAMMATE;
    public static final int GPS_ALL = GameSettingsRepository.GPS_ALL;
    public volatile int mGPSMode = GPS_ALL;

    public static final int GAME_STATE_NONE = GameStateRepository.GAME_STATE_NONE;
    public static final int GAME_STATE_RUNNING = GameStateRepository.GAME_STATE_RUNNING;
    public static final int GAME_STATE_ELIMINATED = GameStateRepository.GAME_STATE_ELIMINATED;
    public volatile int mGameState = GAME_STATE_NONE;

    public static final int SHOT_MODE_FULL_AUTO = GameSettingsRepository.SHOT_MODE_FULL_AUTO;
    public static final int SHOT_MODE_SINGLE = GameSettingsRepository.SHOT_MODE_SINGLE;
    public static final int SHOT_MODE_BURST = GameSettingsRepository.SHOT_MODE_BURST;
    public volatile boolean mAllowSingleShotMode = true;
    public volatile boolean mAllowBurst3ShotMode = true;
    public volatile boolean mAllowAutoShotMode = true;

    public static final int FIRING_MODE_OUTDOOR_NO_CONE = GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE;
    public static final int FIRING_MODE_OUTDOOR_WITH_CONE = GameSettingsRepository.FIRING_MODE_OUTDOOR_WITH_CONE;
    public static final int FIRING_MODE_INDOOR_NO_CONE = GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE;
    public volatile int mCurrentFiringMode = FIRING_MODE_OUTDOOR_NO_CONE;

    public volatile byte mPlayerID = 0;
    public volatile String mPlayerName = "";
    public volatile String mDeviceAddress = "";
    public volatile Map<InetAddress, Byte> mIPTeamMap;
    public volatile Map<Byte, InetAddress> mTeamIPMap;
    public volatile Map<Byte, String> mTeamPlayerNameMap;
    public volatile Map<Byte, GPSData> mGPSData;
    public volatile Map<Byte, PlayerSettings> mPlayerSettings;

    public static final int MAX_GRENADE_IDS = PlayerRepository.MAX_GRENADE_IDS;
    public volatile byte mPairedGrenadeID = 0;
    public volatile int[] mGrenadePairings;

    public Semaphore mIPTeamMapSemaphore;
    public Semaphore mTeamIPMapSemaphore;
    public Semaphore mTeamPlayerNameSemaphore;
    public Semaphore mGPSDataSemaphore;
    public Semaphore mPlayerSettingsSemaphore;
    public Semaphore mGrenadePairingsSemaphore;

    public volatile boolean mUseGPS = false;
    public volatile boolean mOnlyServerSettings = true;
    public volatile long mServerGameTimeRemaining = 0;
    public volatile InetAddress mServerIP = null;
    public volatile int[] mTeamSizes = new int[]{16, 16};

    public static final int RECOIL_SETTING_DEFAULT = GameSettingsRepository.RECOIL_SETTING_DEFAULT;
    public static final int RECOIL_SETTING_ENABLED = GameSettingsRepository.RECOIL_SETTING_ENABLED;
    public static final int RECOIL_SETTING_DISABLED = GameSettingsRepository.RECOIL_SETTING_DISABLED;
    public volatile int mServerRecoilSetting = RECOIL_SETTING_DEFAULT;
    public volatile boolean mIsAdmin = false;

    protected Globals() {
        PlayerRepository playerRepo = PlayerRepository.getInstance();
        mIPTeamMap = playerRepo.getIpTeamMap();
        mTeamIPMap = playerRepo.getTeamIPMap();
        mTeamPlayerNameMap = playerRepo.getTeamPlayerNameMap();
        mGPSData = playerRepo.getGpsData();
        mPlayerSettings = playerRepo.getPlayerSettings();
        mGrenadePairings = playerRepo.getGrenadePairingsInternal();

        mIPTeamMapSemaphore = new Semaphore(1);
        mTeamIPMapSemaphore = new Semaphore(1);
        mTeamPlayerNameSemaphore = new Semaphore(1);
        mGPSDataSemaphore = new Semaphore(1);
        mPlayerSettingsSemaphore = new Semaphore(1);
        mGrenadePairingsSemaphore = new Semaphore(1);
    }

    public static synchronized Globals getInstance() {
        if (mInstance == null) {
            mInstance = new Globals();
            mInstance.syncToRepositories();
        }
        return mInstance;
    }

    public void syncToRepositories() {
        GameSettingsRepository settings = GameSettingsRepository.getInstance();
        GameStateRepository gameState = GameStateRepository.getInstance();
        PlayerRepository player = PlayerRepository.getInstance();

        settings.setGameMode(mGameMode);
        settings.setGameLimit(mGameLimit);
        settings.setTimeLimit(mTimeLimit);
        settings.setScoreLimit(mScoreLimit);
        settings.setLivesLimit(mLivesLimit);
        settings.setGpsMode(mGPSMode);
        settings.setUseGPS(mUseGPS);
        settings.setFullHealth(mFullHealth);
        settings.setFullReload(mFullReload);
        settings.setReloadTime(mReloadTime);
        settings.setRespawnTime(mRespawnTime);
        settings.setDamage(mDamage);
        settings.setOverrideLives(mOverrideLives);
        settings.setOverrideLivesVal(mOverrideLivesVal);
        settings.setAllowPlayerSettings(mAllowPlayerSettings);
        settings.setReloadOnEmpty(mReloadOnEmpty);
        settings.setOnlyServerSettings(mOnlyServerSettings);
        settings.setServerRecoilSetting(mServerRecoilSetting);
        settings.setCurrentFiringMode(mCurrentFiringMode);
        settings.setAllowSingleShotMode(mAllowSingleShotMode);
        settings.setAllowBurst3ShotMode(mAllowBurst3ShotMode);
        settings.setAllowAutoShotMode(mAllowAutoShotMode);

        gameState.setGameState(mGameState);
        gameState.setServerGameTimeRemaining(mServerGameTimeRemaining);
        gameState.setServerIP(mServerIP);

        player.setPlayerID(mPlayerID);
        player.setPlayerName(mPlayerName);
        player.setAdmin(mIsAdmin);
        player.setPairedGrenadeID(mPairedGrenadeID);
    }

    public static void updatePlayerIDFromRepo(byte playerID) {
        if (mInstance != null) {
            mInstance.mPlayerID = playerID;
        }
    }

    public static void updatePlayerNameFromRepo(String playerName) {
        if (mInstance != null) {
            mInstance.mPlayerName = playerName != null ? playerName : "";
        }
    }

    public void setPlayerID(byte playerID) {
        this.mPlayerID = playerID;
        PlayerRepository.getInstance().setPlayerIDDirect(playerID);
    }

    public void setPlayerName(String playerName) {
        this.mPlayerName = playerName != null ? playerName : "";
        PlayerRepository.getInstance().setPlayerNameDirect(this.mPlayerName);
    }

    public synchronized void resetDefaultTeamSizes() {
        GameSettingsRepository.getInstance().setGameMode(mGameMode);
        GameSettingsRepository.getInstance().resetDefaultTeamSizes();
        mTeamSizes = GameSettingsRepository.getInstance().getTeamSizes();
    }

    public synchronized void setTeamSizes(int[] sizes) {
        GameSettingsRepository.getInstance().setGameMode(mGameMode);
        GameSettingsRepository.getInstance().setTeamSizes(sizes);
        if (sizes != null) {
            mTeamSizes = sizes.clone();
        }
    }

    public synchronized int[] getTeamSizes() {
        GameSettingsRepository.getInstance().setGameMode(mGameMode);
        mTeamSizes = GameSettingsRepository.getInstance().getTeamSizes();
        return mTeamSizes;
    }

    public int getTeamStartID(int team) {
        return GameSettingsRepository.getInstance().getTeamStartID(team);
    }

    public int getTeamEndID(int team) {
        return GameSettingsRepository.getInstance().getTeamEndID(team);
    }

    public int calcNetworkTeam(byte player_id) {
        return PlayerRepository.getInstance().calcNetworkTeam(player_id);
    }

    public static byte findAutoSortPlayerID() {
        getInstance();
        return PlayerRepository.getInstance().findAutoSortPlayerID();
    }

    public static byte findAutoSortPlayerID(Set<Byte> additionalOccupiedIDs) {
        getInstance();
        return PlayerRepository.getInstance().findAutoSortPlayerID(additionalOccupiedIDs);
    }

    public static byte findOpenPlayerIDOnTeam(int targetTeam) {
        getInstance();
        return PlayerRepository.getInstance().findOpenPlayerIDOnTeam(targetTeam);
    }

    public static byte findOpenPlayerIDOnTeam(int targetTeam, Set<Byte> additionalOccupiedIDs) {
        getInstance();
        return PlayerRepository.getInstance().findOpenPlayerIDOnTeam(targetTeam, additionalOccupiedIDs);
    }

    public String getPlayerName(Byte playerID) {
        return PlayerRepository.getInstance().getPlayerName(playerID);
    }

    public static String getIPAddressStr() {
        return PlayerRepository.getIPAddressStr();
    }

    public static InetAddress getIPAddress() {
        return PlayerRepository.getIPAddress();
    }

    public static int getPlayerCount() {
        return getPlayerCount(false);
    }

    public static int getPlayerCount(boolean isDedicated) {
        return PlayerRepository.getInstance().getPlayerCount(isDedicated);
    }

    public static class GPSData {
        public volatile double latitude = 0;
        public volatile double longitude = 0;
        public volatile int team = 0;
        public volatile boolean hasUpdate = false;
    }

    public static class PlayerSettings {
        public volatile int health = Globals.MAX_HEALTH;
        public volatile byte shots = Globals.RELOAD_COUNT;
        public volatile long reloadTime = Globals.RELOAD_TIME_MILLISECONDS;
        public volatile boolean reloadOnEmpty = false;
        public volatile long spawnTime = Globals.RESPAWN_TIME_SECONDS;
        public volatile int damage = Globals.DAMAGE_PER_HIT;
        public volatile boolean overrideLives = false;
        public volatile int lives = 0;
        public volatile boolean allowShotModeSingle = true;
        public volatile boolean allowShotModeBurst3 = true;
        public volatile boolean allowShotModeAuto = true;
        public volatile int firingMode = FIRING_MODE_OUTDOOR_NO_CONE;
        public volatile int recoilSetting = RECOIL_SETTING_DEFAULT;
        public volatile boolean isAdmin = false;
    }

    public static void getmIPTeamMapSemaphore() {
        getInstance().mIPTeamMapSemaphore.acquireUninterruptibly();
    }

    public static void releaseIPTeamMapSemaphore() {
        getInstance().mIPTeamMapSemaphore.release();
    }

    public static void getmTeamIPMapSemaphore() {
        getInstance().mTeamIPMapSemaphore.acquireUninterruptibly();
    }

    public static void releaseTeamIPMapSemaphore() {
        getInstance().mTeamIPMapSemaphore.release();
    }

    public static void getmTeamPlayerNameSemaphore() {
        getInstance().mTeamPlayerNameSemaphore.acquireUninterruptibly();
    }

    public static void releaseTeamPlayerNameSemaphore() {
        getInstance().mTeamPlayerNameSemaphore.release();
    }

    public static void getmGPSDataSemaphore() {
        getInstance().mGPSDataSemaphore.acquireUninterruptibly();
    }

    public static void releaseGPSDataSemaphore() {
        getInstance().mGPSDataSemaphore.release();
    }

    public static void getmPlayerSettingsSemaphore() {
        getInstance().mPlayerSettingsSemaphore.acquireUninterruptibly();
    }

    public static void releasePlayerSettingsSemaphore() {
        getInstance().mPlayerSettingsSemaphore.release();
    }

    public static void getmGrenadePairingsSemaphore() {
        getInstance().mGrenadePairingsSemaphore.acquireUninterruptibly();
    }

    public static void releaseGrenadePairingsSemaphore() {
        getInstance().mGrenadePairingsSemaphore.release();
    }

    public static void ClearGrenadePairings(boolean getSemaphore) {
        if (getSemaphore) {
            getmGrenadePairingsSemaphore();
        }
        try {
            PlayerRepository.getInstance().clearGrenadePairings();
        } finally {
            if (getSemaphore) {
                releaseGrenadePairingsSemaphore();
            }
        }
    }
}
