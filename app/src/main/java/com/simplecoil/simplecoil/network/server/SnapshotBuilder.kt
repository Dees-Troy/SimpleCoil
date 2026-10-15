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

package com.simplecoil.simplecoil.network.server

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.network.ServerMessage
import com.simplecoil.simplecoil.network.WireGpsPosition
import com.simplecoil.simplecoil.network.WireGrenadePairing
import com.simplecoil.simplecoil.network.WireLimits
import com.simplecoil.simplecoil.network.WireMatrixCell
import com.simplecoil.simplecoil.network.WireMatrixPlayer
import com.simplecoil.simplecoil.network.WirePlayer
import com.simplecoil.simplecoil.network.WirePlayerGameUpdate
import com.simplecoil.simplecoil.network.WirePlayerSettings
import com.simplecoil.simplecoil.network.WireScore
import java.net.InetAddress

/**
 * The server's snapshots (network design §7.1 step 6): game info, player data, player settings,
 * GPS and grenade pairings, read from the roster and the repositories. Builds messages only;
 * the server sends them. Called on the server's dispatcher.
 */
class SnapshotBuilder(
    private val roster: GameRoster,
    private val settings: GameSettingsRepository,
    private val players: PlayerRepository,
    private val gameState: GameStateRepository,
) {
    /** Nobody to tell: no connection, or no player has said hello. */
    private fun nobodyThere(): Boolean = roster.isEmpty() || players.teamIPMap.isEmpty()

    /** The game info everyone gets; null when there is nobody to send it to. */
    fun gameInfo(): ServerMessage.GameInfo? {
        if (nobodyThere()) return null
        val wirePlayers = players.teamIPMap.entries.map { (id, address) -> WirePlayer(id.toInt(), players.getPlayerName(id), ipString(address)) }
        val gameLimit = settings.gameLimit
        val timed = gameLimit and GameSettingsRepository.GAME_LIMIT_TIME != 0
        val limits = WireLimits(
            if (timed) settings.timeLimit else null,
            if (gameLimit and GameSettingsRepository.GAME_LIMIT_LIVES != 0) settings.livesLimit else null,
            if (gameLimit and GameSettingsRepository.GAME_LIMIT_SCORE != 0) settings.scoreLimit else null,
        )
        val layout = settings.teamLayout
        val state = gameState.gameState
        val phase = roster.phase()
        return ServerMessage.GameInfo(
            players = wirePlayers,
            limits = limits,
            gameMode = layout.mode.wireValue,
            teamSizes = layout.teamSizes().toList(),
            gameState = state,
            timeRemaining = timeRemaining(),
            sessionPhase = phase.wireValue,
            gameId = if (phase != SessionPhase.LOBBY) roster.gameId() else null,
            gpsMode = if (settings.isUseGPS) settings.gpsMode else null,
            allowPlayerSettings = settings.isAllowPlayerSettings,
            playerSettings = playerSettings(),
            onlyServerSettings = settings.isOnlyServerSettings,
            allowChooseTeams = settings.isAllowChooseTeams,
            startTime = settings.startTime,
            playerUpdate = null,
        )
    }

    /** Seconds left, in a running timed game. */
    private fun timeRemaining(): Long? {
        val timed = settings.gameLimit and GameSettingsRepository.GAME_LIMIT_TIME != 0
        return if (timed && gameState.gameState != GameStateRepository.GAME_STATE_NONE) gameState.serverGameTimeRemaining else null
    }

    /** [playerId]'s own score, for the game info it gets when it (re)joins. */
    fun playerUpdate(playerId: Byte): WirePlayerGameUpdate {
        val score = roster.score(playerId)
        val layout = settings.teamLayout
        return WirePlayerGameUpdate(
            points = score?.points ?: 0,
            eliminated = score?.eliminated ?: 0,
            teamPoints = if (!layout.mode.isFreeForAll) roster.teamPoints(layout.teamOf(playerId)) else null,
            timeRemaining = timeRemaining(),
            ackSeq = roster.record(playerId)?.lastAppliedSeq ?: 0,
        )
    }

    /** The scoreboard; null when there is nobody to send it to. */
    fun playerData(): ServerMessage.PlayerData? {
        if (nobodyThere()) return null
        val withGrenade = grenadeHolders()
        return ServerMessage.PlayerData(players.teamIPMap.entries.map { (id, address) ->
            val score = roster.score(id)
            WireScore(id.toInt(), players.getPlayerName(id), ipString(address), score?.points ?: 0, score?.eliminated ?: 0, score?.connected,
                roster.record(id)?.blasterType, id.toInt() in withGrenade)
        })
    }

    /** Who eliminated whom in the current game (score matrix design §3.4); sent in every phase. */
    fun scoreMatrix(): ServerMessage.ScoreMatrix {
        val matrix = roster.scoreMatrix()
        return ServerMessage.ScoreMatrix(
            players = matrix.players.map { WireMatrixPlayer(it.playerId.toInt(), it.name ?: "", it.team, it.connected) },
            cells = matrix.cells.map { WireMatrixCell(it.shooterId.toInt(), it.victimId.toInt(), it.count) },
            gameId = matrix.gameId.takeIf { it != 0L },
        )
    }

    /** Everyone's settings; null when there is nobody to send them to. */
    fun playerSettings(): List<WirePlayerSettings>? {
        // Read before taking the repository's lock: the roster's lock always comes first.
        val noPlayers = roster.isEmpty()
        synchronized(players) {
            if (noPlayers || players.teamIPMap.isEmpty()) return null
            return players.playerSettings.entries.map { (id, settings) -> WirePlayerSettings.of(id.toInt(), settings) }
        }
    }

    /**
     * The positions to relay: the ones that changed since the last call, or all of them when
     * [full]; marks them all as sent. Null when no player has a position yet.
     */
    fun gpsPositions(full: Boolean): List<WireGpsPosition>? {
        // The repository's lock keeps a GPS report from landing between reading and clearing hasUpdate.
        synchronized(players) {
            if (players.gpsData.isEmpty()) return null
            val positions = ArrayList<WireGpsPosition>()
            for ((id, gps) in players.gpsData) {
                if (gps.hasUpdate || full) positions += WireGpsPosition(id.toInt(), gps.team, gps.longitude, gps.latitude)
                gps.hasUpdate = false
            }
            return positions
        }
    }

    /** The ids of the players with a paired grenade. */
    fun grenadeHolders(): Set<Int> = players.grenadePairings.filter { it != PlayerRepository.INVALID_PLAYER_ID }.toSet()

    /** Every grenade's owner; empty when none is paired, so clients drop the last one too. */
    fun grenadePairings(): ServerMessage.GrenadePairings {
        val pairings = players.grenadePairings
        val paired = (1 until PlayerRepository.MAX_GRENADE_IDS)
            .filter { pairings[it] != PlayerRepository.INVALID_PLAYER_ID }
            .map { WireGrenadePairing(it, pairings[it]) }
        return ServerMessage.GrenadePairings(paired)
    }

    private companion object {
        /** As the address has always gone into the JSON: InetAddress.toString(), "/" and all. */
        fun ipString(address: InetAddress?): String? = address?.toString()
    }
}
