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

import com.simplecoil.simplecoil.domain.team.TeamLayout;
import com.simplecoil.simplecoil.domain.team.TeamMode;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The single source of truth for game settings (STATE_CONSOLIDATION_DESIGN.md). Independent values
 * are volatile; values that must change together (the team mode and team sizes) live in one
 * immutable object that is swapped atomically.
 */
public class GameSettingsRepository {
    /** Told after the team layout changes, on the thread that changed it. */
    public interface TeamLayoutListener {
        void onTeamLayoutChanged(TeamLayout layout);
    }

    private static volatile GameSettingsRepository instance;

    // Constants
    public static final byte MAX_PLAYER_ID = (byte) 0x20;
    public static final byte RELOAD_COUNT = (byte) 30;
    public static final long RESPAWN_TIME_SECONDS = 10;
    public static final long RESPAWN_SHIELD_TIME_SECONDS = 3;
    public static final long START_TIME_SECONDS = 5;
    public static final long RELOAD_TIME_MILLISECONDS = 1500;
    public static final int MAX_HEALTH = 20;
    public static final int DAMAGE_PER_HIT = -1;

    public static final int GAME_LIMIT_NONE = 0;
    public static final int GAME_LIMIT_TIME = 1;
    public static final int GAME_LIMIT_LIVES = 2;
    public static final int GAME_LIMIT_SCORE = 4;

    public static final int GPS_DISABLED = 0;
    public static final int GPS_TEAMMATE = 1;
    public static final int GPS_ALL = 2;

    public static final int SHOT_MODE_FULL_AUTO = 1;
    public static final int SHOT_MODE_SINGLE = 2;
    public static final int SHOT_MODE_BURST = 4;

    public static final int FIRING_MODE_OUTDOOR_NO_CONE = 0;
    public static final int FIRING_MODE_OUTDOOR_WITH_CONE = 1;
    public static final int FIRING_MODE_INDOOR_NO_CONE = 2;

    public static final int RECOIL_SETTING_DEFAULT = 0;
    public static final int RECOIL_SETTING_ENABLED = 1;
    public static final int RECOIL_SETTING_DISABLED = 2;

    // Mutable Settings
    private volatile byte fullReload = RELOAD_COUNT;
    private volatile long respawnTime = RESPAWN_TIME_SECONDS;
    private volatile long respawnShieldTime = RESPAWN_SHIELD_TIME_SECONDS;
    private volatile long startTime = START_TIME_SECONDS;
    private volatile long reloadTime = RELOAD_TIME_MILLISECONDS;
    private volatile int fullHealth = MAX_HEALTH;
    private volatile int damage = DAMAGE_PER_HIT;
    private volatile boolean overrideLives = false;
    private volatile int overrideLivesVal = 0;
    private volatile boolean allowPlayerSettings = true;
    private volatile boolean reloadOnEmpty = false;
    private volatile boolean allowJoinInProgress = true;
    private volatile boolean allowChooseTeams = true;

    private volatile int gameLimit = GAME_LIMIT_NONE;
    private volatile int timeLimit = 0;
    private volatile int scoreLimit = 0;
    private volatile int livesLimit = 0;

    private volatile int gpsMode = GPS_ALL;
    private volatile boolean useGPS = false;

    private volatile boolean allowSingleShotMode = true;
    private volatile boolean allowBurst3ShotMode = true;
    private volatile boolean allowAutoShotMode = true;
    private volatile int currentFiringMode = FIRING_MODE_OUTDOOR_NO_CONE;

    private volatile boolean onlyServerSettings = true;
    private volatile int serverRecoilSetting = RECOIL_SETTING_DEFAULT;

    private final AtomicReference<TeamLayout> teamLayout = new AtomicReference<>(TeamLayout.DEFAULT);
    private final CopyOnWriteArrayList<TeamLayoutListener> teamLayoutListeners = new CopyOnWriteArrayList<>();

    private GameSettingsRepository() {}

    public static GameSettingsRepository getInstance() {
        if (instance == null) {
            synchronized (GameSettingsRepository.class) {
                if (instance == null) {
                    instance = new GameSettingsRepository();
                }
            }
        }
        return instance;
    }

    // Team layout

    /** One consistent snapshot of the team mode and sizes. Read it once per decision. */
    public TeamLayout getTeamLayout() { return teamLayout.get(); }

    public TeamMode getTeamMode() { return teamLayout.get().getMode(); }

    /** Changing the mode resets the team sizes to that mode's defaults. */
    public void setTeamMode(TeamMode mode) {
        updateTeamLayout(current -> current.withMode(mode));
    }

    /** Sizes that don't fit the current mode fall back to its defaults (see TeamLayout.withSizes). */
    public void setTeamSizes(int[] sizes) {
        updateTeamLayout(current -> current.withSizes(sizes));
    }

    /**
     * The mode and sizes from a server's game info, applied in one step. Null sizes keep the
     * current sizes if the mode is unchanged, otherwise the new mode's defaults.
     */
    public void setTeamModeAndSizes(TeamMode mode, int[] sizes) {
        updateTeamLayout(current -> current.withMode(mode).withSizes(sizes));
    }

    /** Back to the default sizes for the current mode. */
    public void resetDefaultTeamSizes() {
        updateTeamLayout(current -> TeamLayout.defaultFor(current.getMode()));
    }

    public void addTeamLayoutListener(TeamLayoutListener listener) {
        if (listener != null) teamLayoutListeners.addIfAbsent(listener);
    }

    public void removeTeamLayoutListener(TeamLayoutListener listener) {
        teamLayoutListeners.remove(listener);
    }

    /** java.util.function isn't available on API 23. */
    private interface LayoutUpdate {
        TeamLayout apply(TeamLayout current);
    }

    private void updateTeamLayout(LayoutUpdate update) {
        TeamLayout before;
        TeamLayout after;
        do {
            before = teamLayout.get();
            after = update.apply(before);
        } while (!teamLayout.compareAndSet(before, after));
        if (!after.equals(before)) {
            for (TeamLayoutListener listener : teamLayoutListeners) {
                listener.onTeamLayoutChanged(after);
            }
        }
    }

    // Getters and Setters
    public byte getFullReload() { return fullReload; }
    public void setFullReload(byte fullReload) { this.fullReload = fullReload; }

    public long getRespawnTime() { return respawnTime; }
    public void setRespawnTime(long respawnTime) { this.respawnTime = respawnTime; }

    public long getRespawnShieldTime() { return respawnShieldTime; }
    public void setRespawnShieldTime(long respawnShieldTime) { this.respawnShieldTime = respawnShieldTime; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getReloadTime() { return reloadTime; }
    public void setReloadTime(long reloadTime) { this.reloadTime = reloadTime; }

    public int getFullHealth() { return fullHealth; }
    public void setFullHealth(int fullHealth) { this.fullHealth = fullHealth; }

    public int getDamage() { return damage; }
    public void setDamage(int damage) { this.damage = damage; }

    public boolean isOverrideLives() { return overrideLives; }
    public void setOverrideLives(boolean overrideLives) { this.overrideLives = overrideLives; }

    public int getOverrideLivesVal() { return overrideLivesVal; }
    public void setOverrideLivesVal(int overrideLivesVal) { this.overrideLivesVal = overrideLivesVal; }

    public boolean isAllowPlayerSettings() { return allowPlayerSettings; }
    public void setAllowPlayerSettings(boolean allowPlayerSettings) { this.allowPlayerSettings = allowPlayerSettings; }

    public boolean isReloadOnEmpty() { return reloadOnEmpty; }
    public void setReloadOnEmpty(boolean reloadOnEmpty) { this.reloadOnEmpty = reloadOnEmpty; }

    public boolean isAllowJoinInProgress() { return allowJoinInProgress; }
    public void setAllowJoinInProgress(boolean allowJoinInProgress) { this.allowJoinInProgress = allowJoinInProgress; }

    public synchronized int getGameLimit() { return gameLimit; }
    public synchronized void setGameLimit(int gameLimit) { this.gameLimit = gameLimit; }

    public synchronized void addGameLimitFlag(int flag) {
        this.gameLimit |= flag;
    }

    public synchronized void removeGameLimitFlag(int flag) {
        this.gameLimit &= ~flag;
    }

    public int getTimeLimit() { return timeLimit; }
    public void setTimeLimit(int timeLimit) { this.timeLimit = timeLimit; }

    public int getScoreLimit() { return scoreLimit; }
    public void setScoreLimit(int scoreLimit) { this.scoreLimit = scoreLimit; }

    public int getLivesLimit() { return livesLimit; }
    public void setLivesLimit(int livesLimit) { this.livesLimit = livesLimit; }

    public int getGpsMode() { return gpsMode; }
    public void setGpsMode(int gpsMode) { this.gpsMode = gpsMode; }

    public boolean isUseGPS() { return useGPS; }
    public void setUseGPS(boolean useGPS) { this.useGPS = useGPS; }

    public boolean isAllowSingleShotMode() { return allowSingleShotMode; }
    public void setAllowSingleShotMode(boolean allowSingleShotMode) { this.allowSingleShotMode = allowSingleShotMode; }

    public boolean isAllowBurst3ShotMode() { return allowBurst3ShotMode; }
    public void setAllowBurst3ShotMode(boolean allowBurst3ShotMode) { this.allowBurst3ShotMode = allowBurst3ShotMode; }

    public boolean isAllowAutoShotMode() { return allowAutoShotMode; }
    public void setAllowAutoShotMode(boolean allowAutoShotMode) { this.allowAutoShotMode = allowAutoShotMode; }

    public int getCurrentFiringMode() { return currentFiringMode; }
    public void setCurrentFiringMode(int currentFiringMode) { this.currentFiringMode = currentFiringMode; }

    public boolean isOnlyServerSettings() { return onlyServerSettings; }
    public void setOnlyServerSettings(boolean onlyServerSettings) { this.onlyServerSettings = onlyServerSettings; }

    public boolean isAllowChooseTeams() { return allowChooseTeams; }
    public void setAllowChooseTeams(boolean allowChooseTeams) { this.allowChooseTeams = allowChooseTeams; }

    public int getServerRecoilSetting() { return serverRecoilSetting; }
    public void setServerRecoilSetting(int serverRecoilSetting) { this.serverRecoilSetting = serverRecoilSetting; }
}
