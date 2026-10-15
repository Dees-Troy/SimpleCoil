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

package com.simplecoil.simplecoil.ui.server

import com.simplecoil.simplecoil.MemoryPrefs
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.ServerController
import com.simplecoil.simplecoil.data.model.PlayerSettings
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.domain.rules.LimitKind
import com.simplecoil.simplecoil.domain.rules.Limits
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.network.ServerEvent
import com.simplecoil.simplecoil.network.server.ClockState
import com.simplecoil.simplecoil.network.server.ServerPlayerRow
import com.simplecoil.simplecoil.network.server.ServerSettings
import com.simplecoil.simplecoil.network.server.ServerSnapshot
import com.simplecoil.simplecoil.network.server.SessionPhase
import com.simplecoil.simplecoil.ui.playersettings.PlayerSettingsDialog
import com.simplecoil.simplecoil.ui.playersettings.PlayerSettingsValues
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The server screen's rules over a fake [ServerController] (dedicated server design §6.1). */
@OptIn(ExperimentalCoroutinesApi::class)
class DedicatedServerViewModelTest {

    /** Records the commands; the settings changes report [resets] as having reset a finished game. */
    private class FakeController : ServerController {
        override val snapshot = MutableStateFlow<ServerSnapshot?>(null)
        override val isRunning = MutableStateFlow(false)
        val calls = mutableListOf<String>()
        var resets = false
        var targetTeamFull = false
        var screenVisible: Boolean? = null

        override fun start() {
            calls += "start"
            isRunning.value = true
        }

        override fun stop() {
            calls += "stop"
            isRunning.value = false
            snapshot.value = null
        }

        override fun setScreenVisible(visible: Boolean) {
            screenVisible = visible
        }

        override fun startGame(): Boolean = true.also { calls += "startGame" }
        override fun endGame() { calls += "endGame" }
        override fun resetSession(): Boolean = true.also { calls += "reset" }
        override fun changeTeamMode(mode: TeamMode): Boolean = resets.also { calls += "teamMode $mode" }
        override fun sortTeams(): Boolean = resets.also { calls += "sort" }
        override fun changeTeamSizes(sizes: IntArray): Boolean = resets.also { calls += "sizes ${sizes.toList()}" }
        override fun changeLimits(limits: Limits): Boolean = resets.also { calls += "limits $limits" }
        override fun changeStartTime(seconds: Long): Boolean = resets.also { calls += "startTime $seconds" }
        override fun changeGpsMode(mode: Int): Boolean = resets.also { calls += "gps $mode" }
        override fun setAllowJoinInProgress(allow: Boolean) { calls += "allowJoin $allow" }
        override fun setOnlyServerSettings(only: Boolean) { calls += "onlyServer $only" }
        override fun setAllowChooseTeams(allow: Boolean) { calls += "chooseTeams $allow" }
        override fun playerSettings(playerId: Byte): PlayerSettingsValues = PlayerSettingsValues.of(PlayerSettings())
        override fun applyPlayerSettings(playerId: Byte, values: PlayerSettingsValues, applyAll: Boolean, applyTeam: Boolean, targetTeam: Int): Boolean =
            (!targetTeamFull).also { calls += "apply $playerId team $targetTeam" }
        override fun kickPlayer(playerId: Byte) { calls += "kick $playerId" }
    }

    private val controller = FakeController()
    private val prefsStore = MemoryPrefs()
    private val prefs = UserPreferencesRepository(prefsStore)
    private val serverEvents = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 8)
    private val main = UnconfinedTestDispatcher()
    private lateinit var vm: DedicatedServerViewModel
    private val effects = mutableListOf<ServerUiEffect>()
    private val collector = CoroutineScope(main)

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        vm = DedicatedServerViewModel(controller, prefs, serverEvents)
        collector.launch { vm.effects.collect { effects += it } }
    }

    @After
    fun tearDown() {
        collector.cancel()
        Dispatchers.resetMain()
    }

    private val defaultLayout = GameSettingsRepository().teamLayout

    private fun player(id: Int, connected: Boolean = true, points: Int = 0) =
        ServerPlayerRow(id.toByte(), "P$id", points, eliminated = 0, connected = connected, overrideLives = false, lives = 0)

    private fun publish(
        phase: SessionPhase = SessionPhase.LOBBY,
        players: List<ServerPlayerRow> = emptyList(),
        playerCount: Int = players.count { it.connected },
        gameLimit: Int = GameSettingsRepository.GAME_LIMIT_NONE,
        timeLimit: Int = 0,
        mode: TeamMode = TeamMode.TWO_TEAMS,
    ) {
        controller.snapshot.value = ServerSnapshot(phase, "10.0.0.2", players, playerCount, ClockState.Idle,
            ServerSettings(defaultLayout.withMode(mode), gameLimit, timeLimit, livesLimit = 0, scoreLimit = 0, startTimeSeconds = 5,
                gpsMode = GameSettingsRepository.GPS_ALL, useGps = true, allowJoinInProgress = false, onlyServerSettings = true,
                allowChooseTeams = true))
    }

    private fun toasts() = effects.filterIsInstance<ServerUiEffect.Toast>().map { it.text }

    @Test
    fun opening_startsTheServer_andTheStateFollowsItsSnapshot() {
        assertEquals(listOf("start"), controller.calls)
        assertNull(vm.state.value)
        publish(players = listOf(player(1, points = 3), player(17)))
        val state = vm.state.value!!
        assertEquals("10.0.0.2", state.serverIp)
        assertEquals(2, state.playerCount)
        assertEquals(TeamMode.TWO_TEAMS, state.teamMode)
        val rows = state.scoreboard.filterIsInstance<ScoreboardRow.Player>()
        assertEquals(listOf<Byte>(1, 17), rows.map { it.playerId })
        assertTrue(rows.all { it.kickable && !it.isSelf })
    }

    @Test
    fun startWithOnePlayer_toastsNotEnoughPlayers() {
        publish(players = listOf(player(1)))
        vm.onStartPressed()
        assertEquals(listOf(R.string.not_enough_players_toast), toasts())
        assertEquals(listOf("start"), controller.calls)
        publish(players = listOf(player(1), player(17)))
        vm.onStartPressed()
        assertEquals("startGame", controller.calls.last())
    }

    @Test
    fun reset_asksWithTheDisconnectedCount_thenResets() {
        publish(SessionPhase.POST_GAME, listOf(player(1), player(17, connected = false), player(2, connected = false)))
        vm.onResetPressed()
        assertEquals(ServerUiEffect.ConfirmReset(2), effects.single())
        vm.onResetConfirmed()
        assertEquals("reset", controller.calls.last())
    }

    @Test
    fun back_asksBeforeStopping_andTheScreenFinishesWhenTheServerStops() {
        publish(players = listOf(player(1), player(17)))
        vm.onBackPressed()
        assertEquals(ServerUiEffect.ConfirmStop(2), effects.single())
        vm.onStopConfirmed()
        assertEquals("stop", controller.calls.last())
        assertEquals(ServerUiEffect.Finish, effects.last())
    }

    @Test
    fun aSettingsChangeThatResetAGame_toasts_andTheSettingIsSaved() {
        publish(SessionPhase.POST_GAME)
        controller.resets = true
        vm.onTeamModeSelected(TeamMode.FOUR_TEAMS)
        assertEquals(listOf(R.string.dedicated_game_reset_toast), toasts())
        assertEquals(TeamMode.FOUR_TEAMS, prefs.teamMode())
        controller.resets = false
        vm.onGpsModeSelected(GameSettingsRepository.GPS_TEAMMATE)
        assertEquals(1, toasts().size)
        assertEquals(GameSettingsRepository.GPS_TEAMMATE, prefs.gpsMode(-1))
    }

    @Test
    fun gameLimit_appliesThroughTheRules_savesIt_andRejectsTooHigh() {
        publish()
        vm.onGameLimitsEntered(mapOf(LimitKind.TIME to "15", LimitKind.LIVES to "3", LimitKind.SCORE to "0"))
        val both = GameSettingsRepository.GAME_LIMIT_TIME or GameSettingsRepository.GAME_LIMIT_LIVES
        assertEquals("limits ${Limits(both, 15, 3, 0)}", controller.calls.last())
        assertEquals(15, prefs.timeLimit())
        assertEquals(3, prefs.livesLimit())
        val calls = controller.calls.size
        vm.onGameLimitsEntered(mapOf(LimitKind.TIME to "20", LimitKind.LIVES to "1001"))
        assertEquals(listOf(R.string.error_limit_too_high), toasts())
        assertEquals(calls, controller.calls.size)
        vm.onGameLimitsEntered(mapOf(LimitKind.SCORE to "0")) // S9: 0 leaves an unset limit off
        assertEquals("limits ${Limits(GameSettingsRepository.GAME_LIMIT_NONE, 0, 0, 0)}", controller.calls.last())
    }

    @Test
    fun startTime_ignoresNonNumbers_andRejectsOver1000() {
        publish()
        vm.onStartTimeEntered("")
        vm.onStartTimeEntered("abc")
        vm.onStartTimeEntered("1001")
        assertEquals(listOf(R.string.error_limit_too_high), toasts())
        vm.onStartTimeEntered("12")
        assertEquals("startTime 12", controller.calls.last())
        assertEquals(12, prefs.startTime(0))
    }

    @Test
    fun teamHeader_opensTheSizes_exceptDuringAGameOrInFreeForAll() {
        publish(SessionPhase.RUNNING)
        vm.onTeamHeaderClicked()
        publish(mode = TeamMode.FREE_FOR_ALL)
        vm.onTeamHeaderClicked()
        assertEquals(listOf(R.string.dedicated_team_ratio_running, R.string.dedicated_team_ratio_ffa), toasts())
        publish(mode = TeamMode.FOUR_TEAMS)
        vm.onTeamHeaderClicked()
        val ratio = effects.last() as ServerUiEffect.ShowTeamRatio
        assertEquals(4, ratio.teamCount)
        assertArrayEquals(intArrayOf(8, 8, 8, 8), ratio.sizes)
    }

    @Test
    fun playerRow_opensItsSettings_withItsPlaceOnTheTeam() {
        publish(players = listOf(player(18)))
        vm.onPlayerClicked(18)
        val dialog = effects.single() as ServerUiEffect.ShowPlayerSettings
        assertEquals(2, dialog.teamCount)
        assertEquals(2, dialog.team)
        assertEquals(2, dialog.indexInTeam)
    }

    @Test
    fun playerSettings_saveToAFullTeam_toasts_andKickKicks() {
        publish(players = listOf(player(1)))
        controller.targetTeamFull = true
        val values = PlayerSettingsValues.of(PlayerSettings())
        vm.onPlayerSettingsSaved(PlayerSettingsDialog.Result(1, kick = false, values, applyToAll = false, applyToTeam = true, targetTeam = 2))
        assertEquals("apply 1 team 2", controller.calls.last())
        assertEquals(ServerUiEffect.Toast(R.string.error_team_full, listOf(2)), effects.single())
        vm.onPlayerSettingsSaved(PlayerSettingsDialog.Result(1, kick = true, null, applyToAll = false, applyToTeam = false, targetTeam = 0))
        assertEquals("kick 1", controller.calls.last())
        assertEquals(R.string.dedicated_player_kicked, toasts().last())
    }

    @Test
    fun switches_goToTheServer_andChooseTeamsIsSaved() {
        vm.onAllowJoinChanged(true)
        vm.onOnlyServerSettingsChanged(false)
        vm.onAllowChooseTeamsChanged(false)
        assertEquals(listOf("start", "allowJoin true", "onlyServer false", "chooseTeams false"), controller.calls)
        assertEquals(false, prefs.allowChooseTeams())
    }

    @Test
    fun timeExpired_toasts_andTheScreensVisibilityReachesTheServer() {
        serverEvents.tryEmit(ServerEvent.TimeExpired)
        serverEvents.tryEmit(ServerEvent.PlayerJoined)
        assertEquals(listOf(R.string.dialog_game_time_expired), toasts())
        vm.onScreenVisible(true)
        assertEquals(true, controller.screenVisible)
    }
}
