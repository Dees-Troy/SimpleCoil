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

package com.simplecoil.simplecoil.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.simplecoil.simplecoil.domain.session.SessionPreferences
import com.simplecoil.simplecoil.domain.team.TeamMode

/**
 * The player's saved settings: SharedPreferences "SimpleCoil" with exactly the keys and types the
 * app has always used (persisted user data is a compatibility surface, §2.1.4). Each save is one
 * apply(), grouped as the activity grouped them.
 */
class UserPreferencesRepository(private val prefs: SharedPreferences) : SessionPreferences {

    constructor(context: Context) : this(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE))

    fun playerName(default: String): String = prefs.getString(PLAYER_NAME, default) ?: default
    fun savePlayerName(name: String) = prefs.edit().putString(PLAYER_NAME, name).apply()

    fun playerId(): Int = prefs.getInt(PLAYER_ID, 0)
    fun selectedPlayerId(): Int = prefs.getInt(SELECTED_PLAYER_ID, 0)

    /** The team buttons / server-assigned id: player id, selected id and team mode together. */
    fun savePlayerIdAndTeamMode(playerId: Int, teamMode: TeamMode) =
        prefs.edit()
            .putInt(PLAYER_ID, playerId)
            .putInt(SELECTED_PLAYER_ID, playerId)
            .putInt(TEAM_MODE, teamMode.wireValue)
            .apply()

    fun firingMode(default: Int): Int = prefs.getInt(FIRING_MODE, default)
    fun saveFiringMode(firingMode: Int) = prefs.edit().putInt(FIRING_MODE, firingMode).apply()

    fun shotMode(default: Int): Int = prefs.getInt(SHOT_MODE, default)
    override fun saveShotMode(shotMode: Int) = prefs.edit().putInt(SHOT_MODE, shotMode).apply()

    fun recoilEnabled(): Boolean = prefs.getBoolean(RECOIL_ENABLED, true)
    override fun saveRecoilEnabled(enabled: Boolean) = prefs.edit().putBoolean(RECOIL_ENABLED, enabled).apply()

    /** Shared by the player screen and the dedicated server. */
    fun teamMode(): TeamMode = TeamMode.fromWireOrDefault(prefs.getInt(TEAM_MODE, TeamMode.DEFAULT.wireValue))
    fun saveTeamMode(teamMode: TeamMode) = prefs.edit().putInt(TEAM_MODE, teamMode.wireValue).apply()

    fun timeLimit(): Int = prefs.getInt(LIMIT_TIME, 0)
    fun livesLimit(): Int = prefs.getInt(LIMIT_LIVES, 0)
    fun scoreLimit(): Int = prefs.getInt(LIMIT_SCORE, 0)
    fun saveLimits(timeLimit: Int, scoreLimit: Int, livesLimit: Int) =
        prefs.edit()
            .putInt(LIMIT_TIME, timeLimit)
            .putInt(LIMIT_SCORE, scoreLimit)
            .putInt(LIMIT_LIVES, livesLimit)
            .apply()

    /** The starting timer in seconds; the dedicated server's. */
    fun startTime(default: Int): Int = prefs.getInt(START_TIME, default)
    fun saveStartTime(seconds: Int) = prefs.edit().putInt(START_TIME, seconds).apply()

    /** The dedicated server's GPS relay mode (GameSettingsRepository.GPS_*). */
    fun gpsMode(default: Int): Int = prefs.getInt(GPS_MODE, default)
    fun saveGpsMode(mode: Int) = prefs.edit().putInt(GPS_MODE, mode).apply()

    /** The dedicated server lets players pick their own team. */
    fun allowChooseTeams(): Boolean = prefs.getBoolean(ALLOW_CHOOSE_TEAMS, true)
    fun saveAllowChooseTeams(allow: Boolean) = prefs.edit().putBoolean(ALLOW_CHOOSE_TEAMS, allow).apply()

    fun deviceAddress(): String = prefs.getString(DEVICE_ADDRESS, "") ?: ""
    fun saveDeviceAddress(address: String) = prefs.edit().putString(DEVICE_ADDRESS, address).apply()

    companion object {
        const val PREF_NAME = "SimpleCoil"
        const val PLAYER_NAME = "PlayerName"
        const val PLAYER_ID = "PlayerID"
        const val SELECTED_PLAYER_ID = "SelectedPlayerID"
        const val FIRING_MODE = "FiringMode"
        const val SHOT_MODE = "ShotMode"
        const val RECOIL_ENABLED = "RecoilEnabled"
        /** Stored under its old name so existing installs keep their setting. */
        const val TEAM_MODE = "GameMode"
        const val START_TIME = "StartTime"
        const val LIMIT_TIME = "TimeLimit"
        const val LIMIT_LIVES = "LivesLimit"
        const val LIMIT_SCORE = "ScoreLimit"
        const val DEVICE_ADDRESS = "DeviceAddress"
        const val GPS_MODE = "GPSMode"
        const val ALLOW_CHOOSE_TEAMS = "AllowChooseTeams"
    }
}
