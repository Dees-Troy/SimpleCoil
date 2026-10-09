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
        mGrenadePairings = playerRepo.getGrenadePairings();

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
        }

        // Sync legacy fields with repositories
        GameSettingsRepository settings = GameSettingsRepository.getInstance();
        GameStateRepository gameState = GameStateRepository.getInstance();
        PlayerRepository player = PlayerRepository.getInstance();

        settings.setGameMode(mInstance.mGameMode);
        settings.setGameLimit(mInstance.mGameLimit);
        settings.setTimeLimit(mInstance.mTimeLimit);
        settings.setScoreLimit(mInstance.mScoreLimit);
        settings.setLivesLimit(mInstance.mLivesLimit);
        settings.setGpsMode(mInstance.mGPSMode);
        settings.setUseGPS(mInstance.mUseGPS);
        settings.setFullHealth(mInstance.mFullHealth);
        settings.setFullReload(mInstance.mFullReload);
        settings.setReloadTime(mInstance.mReloadTime);
        settings.setRespawnTime(mInstance.mRespawnTime);
        settings.setDamage(mInstance.mDamage);
        settings.setOverrideLives(mInstance.mOverrideLives);
        settings.setOverrideLivesVal(mInstance.mOverrideLivesVal);
        settings.setAllowPlayerSettings(mInstance.mAllowPlayerSettings);
        settings.setReloadOnEmpty(mInstance.mReloadOnEmpty);
        settings.setOnlyServerSettings(mInstance.mOnlyServerSettings);
        settings.setServerRecoilSetting(mInstance.mServerRecoilSetting);
        settings.setCurrentFiringMode(mInstance.mCurrentFiringMode);
        settings.setAllowSingleShotMode(mInstance.mAllowSingleShotMode);
        settings.setAllowBurst3ShotMode(mInstance.mAllowBurst3ShotMode);
        settings.setAllowAutoShotMode(mInstance.mAllowAutoShotMode);

        gameState.setGameState(mInstance.mGameState);
        gameState.setServerGameTimeRemaining(mInstance.mServerGameTimeRemaining);
        gameState.setServerIP(mInstance.mServerIP);

        player.setPlayerID(mInstance.mPlayerID);
        player.setPlayerName(mInstance.mPlayerName);
        player.setAdmin(mInstance.mIsAdmin);
        player.setPairedGrenadeID(mInstance.mPairedGrenadeID);

        return mInstance;
    }

    public void resetDefaultTeamSizes() {
        GameSettingsRepository.getInstance().setGameMode(mGameMode);
        GameSettingsRepository.getInstance().resetDefaultTeamSizes();
        mTeamSizes = GameSettingsRepository.getInstance().getTeamSizes();
    }

    public void setTeamSizes(int[] sizes) {
        GameSettingsRepository.getInstance().setGameMode(mGameMode);
        GameSettingsRepository.getInstance().setTeamSizes(sizes);
        if (sizes != null) {
            mTeamSizes = sizes.clone();
        }
    }

    public int[] getTeamSizes() {
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
        public double latitude = 0;
        public double longitude = 0;
        public int team = 0;
        public boolean hasUpdate = false;
    }

    public static class PlayerSettings {
        public int health = Globals.MAX_HEALTH;
        public byte shots = Globals.RELOAD_COUNT;
        public long reloadTime = Globals.RELOAD_TIME_MILLISECONDS;
        public boolean reloadOnEmpty = false;
        public long spawnTime = Globals.RESPAWN_TIME_SECONDS;
        public int damage = Globals.DAMAGE_PER_HIT;
        public boolean overrideLives = false;
        public int lives = 0;
        public boolean allowShotModeSingle = true;
        public boolean allowShotModeBurst3 = true;
        public boolean allowShotModeAuto = true;
        public int firingMode = FIRING_MODE_OUTDOOR_NO_CONE;
        public int recoilSetting = RECOIL_SETTING_DEFAULT;
        public boolean isAdmin = false;
    }

    public static void getmIPTeamMapSemaphore() {
        getInstance().mIPTeamMapSemaphore.acquireUninterruptibly();
    }

    public static void getmTeamIPMapSemaphore() {
        getInstance().mTeamIPMapSemaphore.acquireUninterruptibly();
    }

    public static void getmTeamPlayerNameSemaphore() {
        getInstance().mTeamPlayerNameSemaphore.acquireUninterruptibly();
    }

    public static void getmGPSDataSemaphore() {
        getInstance().mGPSDataSemaphore.acquireUninterruptibly();
    }

    public static void getmPlayerSettingsSemaphore() {
        getInstance().mPlayerSettingsSemaphore.acquireUninterruptibly();
    }

    public static void getmGrenadePairingsSemaphore() {
        getInstance().mGrenadePairingsSemaphore.acquireUninterruptibly();
    }

    public static void ClearGrenadePairings(boolean getSemaphore) {
        PlayerRepository.getInstance().clearGrenadePairings();
    }
}
