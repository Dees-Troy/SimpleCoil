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

package com.simplecoil.simplecoil.network.client

import android.util.Log
import com.simplecoil.simplecoil.data.model.GpsData
import com.simplecoil.simplecoil.data.model.PlayerSettings
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.network.ClientEvent
import com.simplecoil.simplecoil.network.EventBus
import com.simplecoil.simplecoil.network.Frame
import com.simplecoil.simplecoil.network.GameUpdate
import com.simplecoil.simplecoil.network.ServerCommand
import com.simplecoil.simplecoil.network.ServerMessage
import com.simplecoil.simplecoil.network.WireCodec
import com.simplecoil.simplecoil.network.WirePlayerSettings
import com.simplecoil.simplecoil.network.server.SessionPhase
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The server's frames, decoded, turned into repository writes and [ClientEvent]s (network
 * design §7.1 step 5). Runs on the connection's reader thread.
 */
class ClientMessageHandler(
    private val settings: GameSettingsRepository,
    private val players: PlayerRepository,
    private val outbox: ClientOutbox,
    private val events: EventBus<ClientEvent>,
) {
    /** The server's session phase, from the latest game info. */
    @Volatile
    var sessionPhase: SessionPhase = SessionPhase.LOBBY
        private set

    /** The server's game id, from the latest game info; 0 in the lobby. */
    @Volatile
    var gameId: Long = 0
        private set

    /** A new session: nothing to come back to until the server says so. */
    fun startSession() {
        sessionPhase = SessionPhase.LOBBY
        gameId = 0
    }

    /**
     * Handles one frame from the server (pings are the connection's). Returns true when the
     * server ended the session (SERVERCANCEL or VERSIONERROR) and the client must not reconnect.
     */
    fun handle(raw: String): Boolean {
        val frame = Frame.parse(raw)
        Log.i(TAG, "received: '$raw'")
        when (frame.kind) {
            Frame.Kind.JSON -> WireCodec.decodeServer(frame)?.let { handleMessage(it, frame.body) }
            Frame.Kind.MESG -> return handleCommand(WireCodec.decodeServerCommand(frame.body))
            Frame.Kind.VERSION_ERROR -> {
                events.publish(ClientEvent.VersionError)
                return true
            }
            else -> Log.d(TAG, "unknown tcp message received")
        }
        return false
    }

    /** Handles a command from the server; true when it ended the session (SERVERCANCEL). */
    private fun handleCommand(command: ServerCommand): Boolean {
        when (command) {
            is ServerCommand.Eliminated -> command.victimId?.let { events.publish(ClientEvent.Eliminated(it)) }
            // Without an id the screen names player 0, as the broadcast's default did
            is ServerCommand.Hit -> events.publish(ClientEvent.Hit(command.targetId ?: 0))
            is ServerCommand.Out -> events.publish(ClientEvent.Out(command.targetId ?: 0))
            ServerCommand.ShotFired -> events.publish(ClientEvent.ShotFired)
            is ServerCommand.ServerReply -> {
                command.playerId?.let { players.playerID = it }
                events.publish(ClientEvent.ServerReply(command.playerId))
            }
            ServerCommand.TeamEliminated -> events.publish(ClientEvent.TeamEliminated)
            ServerCommand.EndGame -> events.publish(ClientEvent.EndGame)
            ServerCommand.StartGame -> events.publish(ClientEvent.StartGame)
            ServerCommand.ServerCancel -> {
                events.publish(ClientEvent.ServerCancel)
                return true
            }
            is ServerCommand.Ack -> command.seq?.let { outbox.ack(it) }
            is ServerCommand.Error -> events.publish(ClientEvent.Error(command.message))
            is ServerCommand.Other -> {}
        }
        return false
    }

    /** A JSON message from the server; [json] is the frame's JSON. */
    private fun handleMessage(message: ServerMessage, json: String) {
        when (message) {
            is ServerMessage.GrenadePairings -> synchronized(players) {
                players.clearGrenadePairings()
                for (pairing in message.pairings) players.pairGrenade(pairing.grenadeId, pairing.playerId)
            }
            is ServerMessage.GpsUpdate -> {
                synchronized(players) {
                    if (message.full) players.gpsData.clear()
                    for (position in message.positions) {
                        val playerId = position.playerId.toByte()
                        if (playerId == players.playerID) continue
                        val gps = players.gpsData.getOrPut(playerId) { GpsData().also { it.team = position.team ?: 0 } }
                        gps.longitude = position.longitude
                        gps.latitude = position.latitude
                        gps.hasUpdate = true // Anything that the server sends us is considered an update
                    }
                }
                events.publish(ClientEvent.GpsData(message.full))
            }
            is ServerMessage.PlayerData -> events.publish(ClientEvent.PlayerDataUpdate(json))
            is ServerMessage.PlayerSettings -> applyPlayerSettings(message.players, message.allowPlayerSettings)
            is ServerMessage.GameInfo -> {
                if (message.playerSettings != null) applyPlayerSettings(message.playerSettings, message.allowPlayerSettings == true)
                applyGameInfo(message)
            }
            is ServerMessage.Command, ServerMessage.VersionError -> {} // not JSON
        }
    }

    /** Everyone's settings from the server; our own become the game settings. */
    private fun applyPlayerSettings(list: List<WirePlayerSettings>, allowPlayerSettings: Boolean) {
        synchronized(players) {
            for (wire in list) {
                val playerId = wire.playerId.toByte()
                val playerSettings = players.playerSettings.getOrPut(playerId) { PlayerSettings() }
                wire.applyTo(playerSettings, acceptAdmin = true)
                if (playerId == players.playerID) {
                    settings.fullHealth = playerSettings.health
                    settings.fullReload = playerSettings.shots
                    settings.reloadTime = playerSettings.reloadTime
                    settings.isReloadOnEmpty = playerSettings.reloadOnEmpty
                    settings.isManualRespawn = playerSettings.manualRespawn
                    settings.respawnTime = playerSettings.spawnTime
                    settings.respawnShieldTime = playerSettings.respawnShieldTime
                    settings.damage = playerSettings.damage
                    settings.isOverrideLives = playerSettings.overrideLives
                    settings.overrideLivesVal = playerSettings.lives
                    settings.isAllowSingleShotMode = playerSettings.allowShotModeSingle
                    settings.isAllowBurst3ShotMode = playerSettings.allowShotModeBurst3
                    settings.isAllowAutoShotMode = playerSettings.allowShotModeAuto
                    settings.currentFiringMode = playerSettings.firingMode
                    settings.serverRecoilSetting = playerSettings.recoilSetting
                    players.isAdmin = playerSettings.isAdmin
                }
            }
        }
        settings.isAllowPlayerSettings = allowPlayerSettings
        events.publish(ClientEvent.PlayerSettingsUpdate)
    }

    /** The players, limits, teams and phase from the game info. */
    private fun applyGameInfo(info: ServerMessage.GameInfo) {
        synchronized(players) {
            players.clearAllPlayers()
            settings.gameLimit = GameSettingsRepository.GAME_LIMIT_NONE
            for (player in info.players) {
                val playerId = player.playerId.toByte()
                if (playerId == players.playerID) {
                    if (player.name.isNotEmpty() && player.name != players.playerName) players.playerName = player.name
                    continue
                }
                val ip = player.ip?.removePrefix("/")
                if (ip == null) {
                    Log.w(TAG, "No address for player $playerId")
                    continue
                }
                val address = try {
                    InetAddress.getByName(ip)
                } catch (e: UnknownHostException) {
                    e.printStackTrace()
                    null
                }
                if (address != null) {
                    players.addOrUpdatePlayer(playerId, address, player.name)
                    Log.d(TAG, "player '${player.name}' ($playerId) found at $address")
                }
            }
        }
        Log.d(TAG, "Found ${info.players.size} players")
        var gameLimit = settings.gameLimit
        info.limits.timeLimit?.let {
            gameLimit += GameSettingsRepository.GAME_LIMIT_TIME
            settings.timeLimit = it
        }
        info.limits.livesLimit?.let {
            gameLimit += GameSettingsRepository.GAME_LIMIT_LIVES
            settings.livesLimit = it
            Log.e(TAG, "tcpClient set lives limit to ${settings.livesLimit}")
        }
        info.limits.scoreLimit?.let {
            gameLimit += GameSettingsRepository.GAME_LIMIT_SCORE
            settings.scoreLimit = it
        }
        settings.gameLimit = gameLimit
        val teamMode = TeamMode.fromWire(info.gameMode)
        if (teamMode != null) {
            settings.setTeamModeAndSizes(teamMode, info.teamSizes?.toIntArray())
        } else {
            Log.w(TAG, "Unknown team mode from the server: ${info.gameMode}")
        }
        settings.isUseGPS = info.gpsMode != null
        info.gpsMode?.let { settings.gpsMode = it }
        info.startTime?.let { settings.startTime = it }
        settings.isOnlyServerSettings = info.onlyServerSettings
        info.allowChooseTeams?.let { settings.isAllowChooseTeams = it }
        var sessionPhase: Int? = null
        var gameId: Long? = null
        if (info.sessionPhase != null) {
            this.sessionPhase = SessionPhase.fromWire(info.sessionPhase)
            this.gameId = info.gameId ?: 0
            outbox.onGameId(this.gameId) // a new game, or a reset: the old entries are void
            sessionPhase = this.sessionPhase.wireValue
            gameId = this.gameId
        }
        val gameUpdate = info.playerUpdate?.let { update ->
            // D2: the server's count plus the eliminations it hasn't applied yet
            val eliminations = outbox.reconcile(update.eliminated, update.ackSeq ?: 0)
            // A missing team score is 0 and a missing time is -1 (no time limit)
            GameUpdate(update.points, eliminations, update.teamPoints ?: 0, update.timeRemaining ?: -1)
        }
        events.publish(ClientEvent.ListPlayers(gameUpdate, info.gameState, sessionPhase, gameId))
    }

    private companion object {
        const val TAG = "TCPClient"
    }
}
