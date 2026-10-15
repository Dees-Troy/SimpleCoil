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

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.simplecoil.simplecoil.PlayerDisplayData
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.ServerController
import com.simplecoil.simplecoil.SimpleCoilApp
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.domain.rules.GameLimitRules
import com.simplecoil.simplecoil.domain.rules.LimitKind
import com.simplecoil.simplecoil.domain.rules.Limits
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.network.ServerEvent
import com.simplecoil.simplecoil.network.server.ClockState
import com.simplecoil.simplecoil.network.server.ServerSnapshot
import com.simplecoil.simplecoil.network.server.SessionPhase
import com.simplecoil.simplecoil.ui.playersettings.PlayerSettingsDialog
import com.simplecoil.simplecoil.ui.playersettings.PlayerSettingsValues
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardContext
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardRow
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardRows
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the server screen shows; null fields aside, everything comes from the server's snapshot. */
data class ServerUiState(
    val phase: SessionPhase,
    /** Null until the server knows its address. */
    val serverIp: String?,
    val playerCount: Int,
    val clock: ClockState,
    val limits: Limits,
    val teamMode: TeamMode,
    /** GameSettingsRepository.GPS_*; shown as disabled when [useGps] is off. */
    val gpsMode: Int,
    val useGps: Boolean,
    val startTimeSeconds: Long,
    val allowJoin: Boolean,
    val onlyServerSettings: Boolean,
    val allowChooseTeams: Boolean,
    val scoreboard: List<ScoreboardRow>,
)

/** One-shot things for the screen to do. */
sealed interface ServerUiEffect {
    data class Toast(@StringRes val text: Int, val args: List<Any> = emptyList()) : ServerUiEffect
    data class ConfirmReset(val disconnectedPlayers: Int) : ServerUiEffect
    data class ConfirmStop(val players: Int) : ServerUiEffect
    data object ConfirmEnd : ServerUiEffect
    class ShowTeamRatio(val teamCount: Int, val sizes: IntArray) : ServerUiEffect
    data class ShowPlayerSettings(
        val playerId: Byte,
        /** Blank if the player has no name. */
        val playerName: String,
        val values: PlayerSettingsValues,
        /** 0 in free for all. */
        val teamCount: Int,
        val team: Int,
        val indexInTeam: Int,
        /** The other teams with no open slot, and who is on each (id to name), to switch with. */
        val fullTeams: Map<Int, List<Pair<Byte, String>>> = emptyMap(),
    ) : ServerUiEffect

    /** The server stopped: the screen closes. */
    data object Finish : ServerUiEffect
}

/**
 * The dedicated server screen's state and rules (dedicated server design §6.1). The server owns
 * the session; this turns its snapshot into what the screen shows and the operator's input
 * into commands, saving the settings the operator changes (R5).
 */
class DedicatedServerViewModel(
    private val controller: ServerController,
    private val prefs: UserPreferencesRepository,
    serverEvents: Flow<ServerEvent>,
) : ViewModel() {

    private val _effects = Channel<ServerUiEffect>(Channel.BUFFERED)
    val effects: Flow<ServerUiEffect> = _effects.receiveAsFlow()

    /** Null until the server has started. */
    val state: StateFlow<ServerUiState?> = controller.snapshot
        .map { it?.let(::uiState) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, controller.snapshot.value?.let(::uiState))

    private val snapshot: ServerSnapshot? get() = controller.snapshot.value

    init {
        controller.start()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            serverEvents.collect { if (it == ServerEvent.TimeExpired) toast(R.string.dialog_game_time_expired) }
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var wasRunning = false
            controller.isRunning.collect { running ->
                if (wasRunning && !running) _effects.trySend(ServerUiEffect.Finish) // e.g. the notification's Stop
                wasRunning = running
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The session
    // ---------------------------------------------------------------------------------------

    fun onStartPressed() {
        if ((snapshot?.playerCount ?: 0) <= 1) {
            toast(R.string.not_enough_players_toast)
            return
        }
        controller.startGame()
    }

    /** Ending a game can't be undone, so it asks first. */
    fun onEndPressed() {
        _effects.trySend(ServerUiEffect.ConfirmEnd)
    }

    fun onEndConfirmed() = controller.endGame()

    /** Reset drops the players who are away, so it asks first, with their count. */
    fun onResetPressed() {
        val away = snapshot?.players?.count { !it.connected } ?: 0
        _effects.trySend(ServerUiEffect.ConfirmReset(away))
    }

    fun onResetConfirmed() {
        controller.resetSession()
    }

    /** Back stops the server, after a confirmation (Q1). */
    fun onBackPressed() {
        _effects.trySend(ServerUiEffect.ConfirmStop(snapshot?.playerCount ?: 0))
    }

    fun onStopConfirmed() = controller.stop()

    /** The screen is in the foreground (started) or not: Wi-Fi stays awake only while it is (§5.6). */
    fun onScreenVisible(visible: Boolean) = controller.setScreenVisible(visible)

    // ---------------------------------------------------------------------------------------
    // The settings. A change to the game after a game resets first, with a toast (§1.1).
    // ---------------------------------------------------------------------------------------

    fun onTeamModeSelected(mode: TeamMode) {
        toastIfReset(controller.changeTeamMode(mode))
        prefs.saveTeamMode(mode)
    }

    fun onSortTeams() {
        toastIfReset(controller.sortTeams())
        toast(R.string.dedicated_teams_sorted)
    }

    fun onGpsModeSelected(mode: Int) {
        toastIfReset(controller.changeGpsMode(mode))
        prefs.saveGpsMode(mode)
    }

    /** The game limit dialog's result: the text entered for each limit. */
    fun onGameLimitsEntered(texts: Map<LimitKind, String>) {
        val current = snapshot?.settings ?: return
        when (val result = GameLimitRules.apply(Limits(current.gameLimit, current.timeLimit, current.livesLimit, current.scoreLimit), texts)) {
            GameLimitRules.Result.Ignored -> {}
            is GameLimitRules.Result.Applied -> {
                toastIfReset(controller.changeLimits(result.limits))
                with(result.limits) { prefs.saveLimits(timeLimit, scoreLimit, livesLimit) }
            }
        }
    }

    /** The starting timer dialog's result: empty, not a number or above the maximum changes nothing. */
    fun onStartTimeEntered(text: String) {
        val seconds = text.toLongOrNull() ?: return
        if (seconds > MAX_START_TIME_SECONDS) return
        toastIfReset(controller.changeStartTime(seconds))
        prefs.saveStartTime(seconds.toInt())
    }

    /** The team header: edit the team sizes, except during a game or in free for all. */
    fun onTeamHeaderClicked() {
        val current = snapshot ?: return
        val mode = current.settings.teamLayout.mode
        when {
            current.phase == SessionPhase.RUNNING -> toast(R.string.dedicated_team_ratio_running)
            mode.isFreeForAll -> toast(R.string.dedicated_team_ratio_ffa)
            else -> _effects.trySend(ServerUiEffect.ShowTeamRatio(mode.teamCount, current.settings.teamLayout.teamSizes()))
        }
    }

    /** The team ratio dialog's result, already validated. */
    fun onTeamSizesEntered(sizes: IntArray) {
        toastIfReset(controller.changeTeamSizes(sizes))
    }

    fun onAllowJoinChanged(allow: Boolean) = controller.setAllowJoinInProgress(allow)

    fun onOnlyServerSettingsChanged(only: Boolean) = controller.setOnlyServerSettings(only)

    fun onAllowChooseTeamsChanged(allow: Boolean) {
        controller.setAllowChooseTeams(allow)
        prefs.saveAllowChooseTeams(allow)
    }

    // ---------------------------------------------------------------------------------------
    // Players
    // ---------------------------------------------------------------------------------------

    /** A player row: that network player's settings. */
    fun onPlayerClicked(playerId: Byte) {
        if (playerId <= 0) return
        val current = snapshot ?: return
        val layout = current.settings.teamLayout
        val teamCount = if (layout.mode.isFreeForAll) 0 else layout.mode.teamCount
        val team = layout.teamOf(playerId)
        val indexInTeam = if (teamCount == 0) playerId.toInt() else playerId - layout.teamStartId(team) + 1
        val name = current.players.firstOrNull { it.playerId == playerId }?.name ?: ""
        _effects.trySend(ServerUiEffect.ShowPlayerSettings(playerId, name, controller.playerSettings(playerId), teamCount, team,
            indexInTeam, fullTeams(current, team)))
    }

    /** The teams other than [team] with no open slot and who is on them; players only switch in the lobby. */
    private fun fullTeams(snapshot: ServerSnapshot, team: Int): Map<Int, List<Pair<Byte, String>>> {
        val layout = snapshot.settings.teamLayout
        if (snapshot.phase != SessionPhase.LOBBY || layout.mode.isFreeForAll) return emptyMap()
        return (1..layout.mode.teamCount).filter { it != team }.associateWith { t ->
            snapshot.players.filter { layout.teamOf(it.playerId) == t }.map { it.playerId to (it.name ?: "") }
        }.filter { (t, players) -> players.size >= layout.teamEndId(t) - layout.teamStartId(t) + 1 }
    }

    /** The player settings dialog's result: kick, or save the settings (and move the player). */
    fun onPlayerSettingsSaved(result: PlayerSettingsDialog.Result) {
        if (result.kick) {
            onKick(result.playerId)
            return
        }
        val values = result.values ?: return
        if (!controller.applyPlayerSettings(result.playerId, values, result.applyToAll, result.applyToTeam, result.targetTeam, result.swapWith)) {
            toast(R.string.error_team_full, result.targetTeam)
        }
    }

    fun onKick(playerId: Byte) {
        if (snapshot == null) return
        controller.kickPlayer(playerId)
        toast(R.string.dedicated_player_kicked)
    }

    // ---------------------------------------------------------------------------------------

    private fun toastIfReset(reset: Boolean) {
        if (reset) toast(R.string.dedicated_game_reset_toast)
    }

    private fun toast(@StringRes text: Int, vararg args: Any) {
        _effects.trySend(ServerUiEffect.Toast(text, args.toList()))
    }

    companion object {
        /** The longest starting timer the operator may enter, in seconds. */
        const val MAX_START_TIME_SECONDS = 1000L

        /** The screen's state for [snapshot]. */
        fun uiState(snapshot: ServerSnapshot): ServerUiState {
            val settings = snapshot.settings
            val players = arrayOfNulls<PlayerDisplayData>(GameSettingsRepository.MAX_PLAYER_ID + 2)
            for (row in snapshot.players) {
                val id = row.playerId.toInt()
                if (id !in 1..GameSettingsRepository.MAX_PLAYER_ID) continue
                players[id] = PlayerDisplayData().apply {
                    playerID = row.playerId
                    playerName = row.name
                    points = row.points
                    eliminated = row.eliminated
                    isConnected = row.connected
                    overrideLives = row.overrideLives
                    lives = row.lives
                    blasterType = (row.blasterType ?: 0).toByte()
                    hasGrenade = row.hasGrenade
                }
            }
            val context = ScoreboardContext(settings.teamLayout, settings.gameLimit, settings.livesLimit,
                isClient = false, selfId = 0, isAdmin = false)
            return ServerUiState(
                phase = snapshot.phase,
                serverIp = snapshot.serverIp,
                playerCount = snapshot.playerCount,
                clock = snapshot.clock,
                limits = Limits(settings.gameLimit, settings.timeLimit, settings.livesLimit, settings.scoreLimit),
                teamMode = settings.teamLayout.mode,
                gpsMode = settings.gpsMode,
                useGps = settings.useGps,
                startTimeSeconds = settings.startTimeSeconds,
                allowJoin = settings.allowJoinInProgress,
                onlyServerSettings = settings.onlyServerSettings,
                allowChooseTeams = settings.allowChooseTeams,
                scoreboard = ScoreboardRows.build(players, context),
            )
        }
    }

    /** Builds the view model from the application container. */
    class Factory(private val app: SimpleCoilApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = with(app.container) {
            DedicatedServerViewModel(serverController, userPreferences, serverEvents.events) as T
        }
    }
}
