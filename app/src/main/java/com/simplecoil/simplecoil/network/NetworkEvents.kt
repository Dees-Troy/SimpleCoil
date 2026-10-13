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

package com.simplecoil.simplecoil.network

import com.simplecoil.simplecoil.PlayerDisplayData
import com.simplecoil.simplecoil.network.server.ClockState

/** Score update from the server, carried by [ClientEvent.ListPlayers]. */
data class GameUpdate(val score: Int, val eliminations: Int, val teamScore: Int, val timeRemaining: Long)

/**
 * What the client role reports to the play screen and the map: the network client, discovery
 * and the network session's service lifecycle. Separate from [ServerEvent], so the dedicated
 * server's events can't reach the play screen.
 */
sealed interface ClientEvent {
    data object ShotFired : ClientEvent
    data class Hit(val playerId: Byte) : ClientEvent
    data class Out(val playerId: Byte) : ClientEvent
    data class Eliminated(val playerId: Byte) : ClientEvent
    data object TeamEliminated : ClientEvent
    data object Join : ClientEvent
    data object Leave : ClientEvent

    /**
     * [gameUpdate] when the game info carried a player game update; [gameState] when it carried
     * a game state; [sessionPhase] (SessionPhase.wireValue) and [gameId] when the server sent them.
     */
    data class ListPlayers(
        val gameUpdate: GameUpdate?,
        val gameState: Int?,
        val sessionPhase: Int? = null,
        val gameId: Long? = null,
    ) : ClientEvent
    data class PlayerDataUpdate(val json: String?) : ClientEvent
    data object StartGame : ClientEvent
    data object EndGame : ClientEvent
    data class Error(val message: String?) : ClientEvent
    data object VersionError : ClientEvent
    data object SameTeam : ClientEvent
    data object FailedToJoin : ClientEvent
    data object ServerCancel : ClientEvent

    /** [assignedPlayerId] when the reply carries one. */
    data class ServerReply(val assignedPlayerId: Byte?) : ClientEvent
    data object NetworkConnected : ClientEvent
    data object NetworkDisconnected : ClientEvent
    data object PlayerSettingsUpdate : ClientEvent

    /** Other players' positions changed in PlayerRepository; [fullUpdate] replaced all of them. */
    data class GpsData(val fullUpdate: Boolean) : ClientEvent

    /** GameSettingsRepository's GPS setting changed on this phone. */
    data object GpsSettingChanged : ClientEvent

    data object UdpServiceConnected : ClientEvent
    data object UdpServiceDisconnected : ClientEvent

    /** Scoreboard parsed from player data JSON; indexed by player id. Scores are already updated. */
    class PlayerData(val players: Array<PlayerDisplayData?>) : ClientEvent
}

/** What the dedicated server reports to its screen. */
sealed interface ServerEvent {
    data object PlayerJoined : ServerEvent
    data object PlayerLeft : ServerEvent
    data object GameStarted : ServerEvent
    data object GameEnded : ServerEvent
    data object SessionReset : ServerEvent

    /** Scores, players or settings changed; [json] is the player data sent to the clients, when there is one. */
    data class PlayerDataChanged(val json: String?) : ServerEvent

    /** The discovery beacon is up; the server listens on [ip]. */
    data class ServerCreated(val ip: String) : ServerEvent

    /** The game clock moved to [state]. */
    data class ClockChanged(val state: ClockState) : ServerEvent

    /** The time limit ran out; the game is ending. */
    data object TimeExpired : ServerEvent
}
