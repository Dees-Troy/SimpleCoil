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
import com.simplecoil.simplecoil.TcpClient
import com.simplecoil.simplecoil.TcpServer
import com.simplecoil.simplecoil.domain.session.SessionToast

/** Status line on the connect screen. */
enum class ConnectStatus { SCANNING, CONNECTING, COMMUNICATING, NOT_CONNECTED }

/** Text of the network button. */
enum class NetworkButton { USE_NETWORK, MENU, SEARCHING, CONNECTED }

/** One-line messages the game shows. */
enum class GameMessage {
    NOT_ENOUGH_PLAYERS, PLAYER_SETTINGS_NOT_ALLOWED, TEAMS_SHUFFLED, NO_WIFI, LIMIT_TOO_HIGH,
    UDP_VERSION_ERROR, SAME_ID_ERROR, KICKED_BY_SERVER, INAPPROPRIATE_NAME, LOCATION_PERMISSION_REQUIRED,
    RATIOS_DURING_GAME, RATIOS_ADMIN_ONLY, RATIOS_NOT_IN_FFA, RATIOS_MIN_ONE,
    /** Team sizes don't add up; arg = the entered total. */
    RATIOS_TOTAL,
    PLAYER_KICKED, KICK_SENT,
}

/** Entries of the network popup menu, in display order. */
sealed interface NetworkMenuEntry {
    data object Join : NetworkMenuEntry
    data object JoinIp : NetworkMenuEntry
    data class PlayerName(val name: String) : NetworkMenuEntry
    data object GameMode : NetworkMenuEntry
    data class SwitchTeam(val team: Int, val color: Int) : NetworkMenuEntry
    data object DisableNetwork : NetworkMenuEntry
}

/** Entries of the admin popup menu. */
enum class AdminAction { START_GAME, END_GAME, TWO_TEAMS, FOUR_TEAMS, FREE_FOR_ALL, SHUFFLE_TEAMS }

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
    val shuffleVisible: Boolean,
)

/**
 * What the play screen can be told to do. GameViewModel calls these synchronously, in the same
 * order the activity used to make its view changes, so the screen ends up exactly as before;
 * FullscreenActivity implements it with views, dialogs and menus only.
 */
interface GameScreen {
    // Connection
    fun showConnectScreen(reconnectVisible: Boolean)
    fun showPlayScreen()
    fun setReconnectVisible(visible: Boolean)
    fun setConnectButtonsEnabled(enabled: Boolean)
    fun showConnectStatus(status: ConnectStatus)
    /** Run the permission / Bluetooth checks, then call [GameViewModel.startScan]. */
    fun requestConnect()
    fun showDisconnectDialog()
    fun dismissDisconnectDialog()
    fun showBlasterType(rifle: Boolean, address: String)
    fun finishScreen()

    // Game
    fun showGameStarted(useNetwork: Boolean, isServer: Boolean, freeForAll: Boolean)
    fun showGameEnded(useNetwork: Boolean, freeForAll: Boolean)
    fun confirmEndGame()
    fun showScore(score: Int)
    fun showTeamScore(teamScore: Int)
    fun showGameLimit(timeLimitMinutes: Int?, livesLabel: Boolean, scoreLimit: Int?)
    fun showPlayerSettings(fullHealth: Int, fullReload: Byte, damage: Int)
    fun showFiringMode(firingMode: Int)
    fun showGameMode(gameMode: Int)
    fun showTeam(team: TeamDisplay)
    fun showPlayerName(name: String)
    fun showPreGameButtons(buttons: PreGameButtons)
    fun showAdminButton(visible: Boolean)
    fun showSessionToast(toast: SessionToast)
    fun showMessage(message: GameMessage, arg: Int = 0)
    fun showText(text: String)

    // Network
    fun showNetworkOptions(enabled: Boolean, freeForAll: Boolean)
    fun showNetworkButton(button: NetworkButton)
    /** Rebuild the network popup (if it exists) from [GameViewModel.networkMenuEntries]. */
    fun rebuildNetworkMenu()
    fun prepareNetworkMenu()
    fun showNetworkMenu()
    fun showGameModeMenu()
    fun showServerSearching()
    fun showNotReady()
    fun setKeepScreenOn(on: Boolean)
    fun setPlayerDataButtonVisible(visible: Boolean)
    fun showPlayerCount(count: Int)
    fun showOnlyOnePlayer()
    fun showConnectedToServer(ip: String, dedicated: Boolean)
    fun hideStartButton()
    fun showNetworkConnected(connected: Boolean)
    fun showShotsFired()
    fun showHitOtherPlayer(out: Boolean, name: String)
    fun showScoreIncrease(name: String)
    fun showServerIpDialog(suggestion: String)
    fun showPlayerNameDialog(current: String)
    fun showGameLimitDialog(allowScoreLimit: Boolean)
    fun showTeamRatioDialog(numTeams: Int, sizes: IntArray)
    fun showLocalPlayerSettings(client: TcpClient?)
    fun showServerPlayerSettings(playerId: Byte, server: TcpServer?)
    fun showScoreboard(players: Array<PlayerDisplayData?>, isServer: Boolean)
    fun closeScoreboard()
    fun openDedicatedServer()
}
