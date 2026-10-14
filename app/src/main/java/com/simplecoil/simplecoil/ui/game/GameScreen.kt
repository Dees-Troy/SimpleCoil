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

package com.simplecoil.simplecoil.ui.game

import com.simplecoil.simplecoil.PlayerDisplayData
import com.simplecoil.simplecoil.domain.session.SessionToast
import com.simplecoil.simplecoil.domain.team.TeamMode

/** Status line on the connect screen. */
enum class ConnectStatus { SCANNING, CONNECTING, COMMUNICATING }

/** Text of the network button. */
enum class NetworkButton { USE_NETWORK, MENU, SEARCHING, CONNECTED }

/** One-line messages the game shows. */
enum class GameMessage {
    NOT_ENOUGH_PLAYERS, PLAYER_SETTINGS_NOT_ALLOWED, NO_WIFI, LIMIT_TOO_HIGH,
    UDP_VERSION_ERROR, KICKED_BY_SERVER, INAPPROPRIATE_NAME, LOCATION_PERMISSION_REQUIRED,
    RATIOS_DURING_GAME, RATIOS_ADMIN_ONLY, RATIOS_NOT_IN_FFA, RATIOS_MIN_ONE,
    /** Team sizes don't add up; arg = the entered total. */
    RATIOS_TOTAL,
    KICK_SENT,
    /** Generate QR without a blaster address to put in the code. */
    QR_NO_BLASTER,
}

/** Entries of the network popup menu, in display order. */
sealed interface NetworkMenuEntry {
    data object Join : NetworkMenuEntry
    data object JoinIp : NetworkMenuEntry
    data class PlayerName(val name: String) : NetworkMenuEntry
    data object ChooseTeamMode : NetworkMenuEntry
    data class SwitchTeam(val team: Int, val color: Int) : NetworkMenuEntry
    data object DisableNetwork : NetworkMenuEntry
}

/** Entries of the admin popup menu. */
enum class AdminAction { START_GAME, END_GAME, TWO_TEAMS, FOUR_TEAMS, FREE_FOR_ALL, SHUFFLE_TEAMS, RESET_GAME }

/** The team line. */
sealed interface TeamDisplay {
    /** No player id yet: the server will sort the player into a team. */
    data object AutoSort : TeamDisplay

    /** [playerName] when joined to a dedicated server (shown as "name (team-player)"). */
    data class Team(val team: Int, val player: Int, val color: Int, val teamScoreVisible: Boolean, val playerName: String?) : TeamDisplay

    data class FreeForAll(val playerId: Byte) : TeamDisplay
}

/** Lobby buttons shown while no game is running. */
data class PreGameButtons(
    val ready: Boolean,
    val startVisible: Boolean,
    val startEnabled: Boolean,
    val serverSettingsOnly: Boolean,
    /** Not on a server, so this player chooses 2 teams / 4 teams / FFA. */
    val teamModeSelectable: Boolean,
)

/**
 * What the play screen can be told to do. GameViewModel calls these synchronously, in the same
 * order the activity used to make its view changes, so the screen ends up exactly as before.
 * FullscreenActivity assembles it from [GameView] (GameScreenRenderer), [GameDialogs]
 * (DialogCoordinator) and [GameNavigator] (the activity itself).
 */
interface GameScreen : GameView, GameDialogs, GameNavigator

/** A [GameScreen] made of its three parts. */
class CompositeGameScreen(view: GameView, dialogs: GameDialogs, navigator: GameNavigator) :
    GameScreen, GameView by view, GameDialogs by dialogs, GameNavigator by navigator

/** View and window changes. */
interface GameView {
    // Connection
    fun showConnectScreen(reconnectVisible: Boolean)
    fun showPlayScreen()
    fun setReconnectVisible(visible: Boolean)
    fun setConnectButtonsEnabled(enabled: Boolean)

    // Game
    /** [isAdmin]: a network admin also gets the in-game End Game button. */
    fun showGameStarted(useNetwork: Boolean, isAdmin: Boolean, freeForAll: Boolean)
    fun showGameEnded(useNetwork: Boolean, freeForAll: Boolean)
    fun showScore(score: Int)
    fun showTeamScore(teamScore: Int)
    fun showGameLimit(timeLimitMinutes: Int?, livesLabel: Boolean, scoreLimit: Int?)
    fun showPlayerSettings(fullHealth: Int, fullReload: Byte, damage: Int)
    fun showFiringMode(firingMode: Int)
    fun showTeamMode(mode: TeamMode)
    fun showTeam(team: TeamDisplay)
    fun showPlayerName(name: String)
    fun showPreGameButtons(buttons: PreGameButtons)
    fun showAdminButton(visible: Boolean)

    // Network
    fun showNetworkOptions(enabled: Boolean, freeForAll: Boolean)
    fun showNetworkButton(button: NetworkButton)
    fun showServerSearching()
    fun showNotReady()
    fun setKeepScreenOn(on: Boolean)
    fun setScoreboardAvailable(available: Boolean)
    fun showPlayerCount(count: Int)
    fun showOnlyOnePlayer()
    fun showConnectedToServer(ip: String)
    fun hideStartButton()
    fun showNetworkConnected(connected: Boolean)
    fun showShotsFired()
    fun showHitOtherPlayer(out: Boolean, name: String)
    fun showScoreIncrease(name: String)
}

/** Dialogs, toasts and popup menus. */
interface GameDialogs {
    /** The connecting popup with [status]; null closes it. */
    fun showConnectStatus(status: ConnectStatus?)
    fun showDisconnectDialog()
    fun dismissDisconnectDialog()
    fun showBlasterType(rifle: Boolean, address: String)
    fun confirmEndGame()
    /** Ask before an admin resets the server; [disconnectedPlayers] would be removed. */
    fun confirmReset(disconnectedPlayers: Int)
    /** Rebuild the admin popup from [GameViewModel.adminActions] if it is open. */
    fun rebuildAdminMenu()
    fun showSessionToast(toast: SessionToast)
    fun showMessage(message: GameMessage, arg: Int = 0)
    fun showText(text: String)
    /** Rebuild the network popup (if it exists) from [GameViewModel.networkMenuEntries]. */
    fun rebuildNetworkMenu()
    fun prepareNetworkMenu()
    fun showNetworkMenu()
    fun showTeamModeMenu()
    fun showServerIpDialog(suggestion: String)
    fun showPlayerNameDialog(current: String)
    /** Ask for the name to put in a pairing QR code, after explaining what it is for. */
    fun showQrNameDialog(current: String)
    /** A pairing QR code for [payload], labeled with [playerName] (may be blank). */
    fun showQrCode(payload: String, playerName: String)
    fun showGameLimitDialog(allowScoreLimit: Boolean)
    fun showTeamRatioDialog(numTeams: Int, sizes: IntArray)
    fun showLocalPlayerSettings()
    fun showScoreboard(players: Array<PlayerDisplayData?>)
    fun closeScoreboard()
}

/** Leaving the play screen, or running the platform flows in front of a connect. */
interface GameNavigator {
    /** Run the permission / Bluetooth checks, then call [GameViewModel.startScan]. */
    fun requestConnect()
    fun finishScreen()
    fun openDedicatedServer()
}
