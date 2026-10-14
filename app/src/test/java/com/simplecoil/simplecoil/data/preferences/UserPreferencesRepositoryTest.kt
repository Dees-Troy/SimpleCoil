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

import android.content.SharedPreferences
import com.simplecoil.simplecoil.domain.team.TeamMode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/** Saved settings are user data from older builds: the file name, keys and types are pinned as literals. */
class UserPreferencesRepositoryTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var repo: UserPreferencesRepository

    @Before
    fun setUp() {
        prefs = mock(SharedPreferences::class.java)
        editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(prefs.edit()).thenReturn(editor)
        repo = UserPreferencesRepository(prefs)
    }

    @Test
    fun fileName() {
        assertEquals("SimpleCoil", UserPreferencesRepository.PREF_NAME)
    }

    @Test
    fun reads_useTheHistoricKeysAndDefaults() {
        `when`(prefs.getString(anyString(), anyString())).thenAnswer { it.getArgument<String>(1) }
        `when`(prefs.getInt(anyString(), anyInt())).thenAnswer { it.getArgument<Int>(1) }
        `when`(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer { it.getArgument<Boolean>(1) }
        repo.playerName("Player")
        repo.playerId()
        repo.selectedPlayerId()
        repo.firingMode(0)
        repo.shotMode(2)
        repo.recoilEnabled()
        repo.teamMode()
        repo.timeLimit()
        repo.livesLimit()
        repo.scoreLimit()
        repo.deviceAddress()
        verify(prefs).getString("PlayerName", "Player")
        verify(prefs).getInt("PlayerID", 0)
        verify(prefs).getInt("SelectedPlayerID", 0)
        verify(prefs).getInt("FiringMode", 0)
        verify(prefs).getInt("ShotMode", 2)
        verify(prefs).getBoolean("RecoilEnabled", true)
        verify(prefs).getInt("GameMode", 2)
        verify(prefs).getInt("TimeLimit", 0)
        verify(prefs).getInt("LivesLimit", 0)
        verify(prefs).getInt("ScoreLimit", 0)
        verify(prefs).getString("DeviceAddress", "")
    }

    @Test
    fun writes_useTheHistoricKeysAndTypes() {
        repo.savePlayerName("Bob")
        repo.savePlayerIdAndTeamMode(5, TeamMode.FOUR_TEAMS)
        repo.saveFiringMode(1)
        repo.saveShotMode(4)
        repo.saveRecoilEnabled(false)
        repo.saveLimits(10, 20, 3)
        repo.saveDeviceAddress("AA:BB:CC:DD:EE:FF")
        val order = inOrder(editor)
        order.verify(editor).putString("PlayerName", "Bob")
        order.verify(editor).putInt("PlayerID", 5)
        order.verify(editor).putInt("SelectedPlayerID", 5)
        order.verify(editor).putInt("GameMode", 4)
        order.verify(editor).putInt("FiringMode", 1)
        order.verify(editor).putInt("ShotMode", 4)
        order.verify(editor).putBoolean("RecoilEnabled", false)
        order.verify(editor).putInt("TimeLimit", 10)
        order.verify(editor).putInt("ScoreLimit", 20)
        order.verify(editor).putInt("LivesLimit", 3)
        order.verify(editor).putString("DeviceAddress", "AA:BB:CC:DD:EE:FF")
        verify(editor, org.mockito.Mockito.times(7)).apply()
    }

    @Test
    fun dedicatedServerSettings_useTheKeysTheServerScreenAlwaysUsed() {
        // S13: these were raw SharedPreferences writes in DedicatedServerActivity
        `when`(prefs.getInt(anyString(), anyInt())).thenAnswer { it.getArgument<Int>(1) }
        `when`(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer { it.getArgument<Boolean>(1) }
        repo.startTime(10)
        repo.gpsMode(2)
        repo.allowChooseTeams()
        verify(prefs).getInt("StartTime", 10)
        verify(prefs).getInt("GPSMode", 2)
        verify(prefs).getBoolean("AllowChooseTeams", true)

        repo.saveStartTime(15)
        repo.saveGpsMode(1)
        repo.saveAllowChooseTeams(false)
        val order = inOrder(editor)
        order.verify(editor).putInt("StartTime", 15)
        order.verify(editor).putInt("GPSMode", 1)
        order.verify(editor).putBoolean("AllowChooseTeams", false)
        verify(editor, org.mockito.Mockito.times(3)).apply()
    }
}
