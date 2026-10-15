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

import android.util.Log
import com.simplecoil.simplecoil.data.model.GpsData
import com.simplecoil.simplecoil.data.model.PlayerSettings
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.network.ClientCommand
import com.simplecoil.simplecoil.network.ClientMessage
import com.simplecoil.simplecoil.network.EventBus
import com.simplecoil.simplecoil.network.Frame
import com.simplecoil.simplecoil.network.NetTrace
import com.simplecoil.simplecoil.network.ServerCommand
import com.simplecoil.simplecoil.network.ServerEvent
import com.simplecoil.simplecoil.network.ServerMessage
import com.simplecoil.simplecoil.network.WireCodec

/**
 * The clients' frames, decoded and applied (network design §7.1 step 6): hellos, eliminations
 * and reliable messages, admin commands, team changes, settings, names and positions. Runs on
 * the server's dispatcher only.
 */
class ServerMessageHandler(
    private val server: GameServer,
    private val roster: GameRoster,
    private val settings: GameSettingsRepository,
    private val players: PlayerRepository,
    private val gameState: GameStateRepository,
    private val events: EventBus<ServerEvent>,
    /** Saves an admin's team mode as the operator's own choice. */
    private val saveTeamMode: (TeamMode) -> Unit,
) {
    fun handleFrame(client: Connection, raw: String) {
        try {
            handleFrameUnguarded(client, raw)
        } catch (e: RuntimeException) {
            // One bad frame must not stop the server handling everyone else's (N5)
            Log.e(TAG, "Error handling '$raw' from ${client.connectionId}", e)
        }
    }

    private fun handleFrameUnguarded(client: Connection, raw: String) {
        NetTrace.received("c${client.connectionId}", raw)
        val frame = Frame.parse(raw)
        if (frame.kind == Frame.Kind.PONG) return
        when (frame.kind) {
            Frame.Kind.JSON -> WireCodec.decodeClient(frame)?.let { handleMessage(client, it) }
            Frame.Kind.MESG -> handleCommand(client, WireCodec.decodeClientCommand(frame.body))
            Frame.Kind.OTHER_VERSION -> client.send(WireCodec.encode(ServerMessage.VersionError))
            Frame.Kind.PING -> {}
            else -> Log.d(TAG, "unknown tcp message received")
        }
    }

    /** Handles a client's command. */
    private fun handleCommand(client: Connection, command: ClientCommand) {
        when {
            command == ClientCommand.Leave || command == ClientCommand.EndGame -> {
                // Leaving; a plain ENDGAME from a client is treated as the client leaving too. A
                // player in the game's roster stays on the scoreboard, disconnected (Q12), but
                // without their grenade: it's paired again if they come back and pair it
                val playerId = client.player?.playerId
                removeClient(client)
                if (playerId != null && players.removeGrenadePairings(playerId)) {
                    server.sendGrenadePairings()
                    server.sendPlayerData(GameServer.SEND_ALL)
                }
                if (roster.isRunning() && roster.connectedCount() <= 1) server.endGame()
            }
            // Sent reliably since v07; a bare one is applied the same way, without de-dup
            command is ClientCommand.Eliminated -> handleEliminated(client, command)
            command == ClientCommand.ShotFired -> server.sendToAll(command(ServerCommand.ShotFired))
            command is ClientCommand.Hit ->
                command.shooterId?.let { server.sendToPlayer(command(ServerCommand.Hit(playerIdOf(client))), it) }
            command is ClientCommand.Out ->
                command.shooterId?.let { server.sendToPlayer(command(ServerCommand.Out(playerIdOf(client))), it) }
            command is ClientCommand.ShieldHit ->
                command.shooterId?.let { server.sendToPlayer(command(ServerCommand.ShieldHit(playerIdOf(client))), it) }
            command == ClientCommand.TeamEliminated ->
                server.sendToTeam(command(ServerCommand.TeamEliminated), playerIdOf(client), includePlayer = false)
            command == ClientCommand.PlayerDataRequest -> server.sendPlayerData(playerIdOf(client).toInt())
            command == ClientCommand.ScoreMatrixRequest -> server.sendScoreMatrix(client)
            WireCodec.isAdminCommand(command) && !isAdmin(client) ->
                // D14: only players the server operator made admins may run the game
                Log.w(TAG, "Ignoring '${WireCodec.text(command)}' from non-admin client ${client.connectionId}")
            command == ClientCommand.StartGame || command == ClientCommand.AdminStartGame -> {
                Log.d(TAG, "TcpServer received STARTGAME / ADMIN_STARTGAME command")
                if (!server.startGame() && roster.phase() == SessionPhase.POST_GAME)
                    client.send(command(ServerCommand.Error("Reset the game first")))
            }
            command == ClientCommand.AdminEndGame -> {
                Log.d(TAG, "TcpServer received ADMIN_ENDGAME command")
                server.endGame()
            }
            command == ClientCommand.AdminReset -> {
                Log.d(TAG, "TcpServer received ADMIN_RESET command")
                server.resetSession()
            }
            command == ClientCommand.AdminSortTeams -> {
                Log.d(TAG, "TcpServer received ADMIN_SORTTEAMS command")
                if (!roster.isRunning()) {
                    server.resetIfPostGame()
                    server.rebalanceAllPlayers(true)
                }
            }
            command is ClientCommand.AdminGameMode -> {
                Log.d(TAG, "TcpServer received ADMIN_GAMEMODE command: ${command.wireValue}")
                val wireValue = command.wireValue ?: return
                val mode = TeamMode.fromWire(wireValue)
                if (mode == null) {
                    Log.w(TAG, "ADMIN_GAMEMODE ignored: unknown team mode")
                } else if (!roster.isRunning()) {
                    server.resetIfPostGame()
                    settings.teamMode = mode
                    saveTeamMode(mode)
                    server.rebalanceAllPlayers(false)
                }
            }
            command is ClientCommand.AdminKick -> {
                Log.d(TAG, "TcpServer received ADMIN_KICK command: ${command.playerId}")
                command.playerId?.let { server.kickPlayer(it) }
            }
            command is ClientCommand.AdminTeamSizes -> {
                Log.d(TAG, "TcpServer received ADMIN_TEAMSIZES command: ${command.sizes}")
                val sizes = command.sizes
                if (!roster.isRunning() && sizes != null && sizes.sum() == GameSettingsRepository.MAX_PLAYER_ID.toInt()) {
                    server.resetIfPostGame()
                    settings.setTeamSizes(sizes.toIntArray())
                    server.rebalanceAllPlayers(false)
                }
            }
        }
    }

    /** A client's JSON message. */
    private fun handleMessage(client: Connection, message: ClientMessage) {
        when (message) {
            is ClientMessage.Reliable -> {
                val ack = handleReliable(client, message.rmsg, message.seq, message.gameId)
                if (ack >= 0) client.send(command(ServerCommand.Ack(ack)))
            }
            is ClientMessage.TeamChangeRequest -> handleTeamChangeRequest(client, message.team, message.swapWith)
            is ClientMessage.GrenadePairing -> {
                // Pairing and announcing run on the dispatcher, so two pairings can't go out in the wrong order.
                players.pairGrenade(message.grenadeId, message.playerId)
                server.sendGrenadePairings()
                server.sendPlayerData(GameServer.SEND_ALL) // the scoreboard shows who has a grenade
            }
            is ClientMessage.BlasterType -> {
                if (roster.onBlasterType(client, message.blasterType)) server.sendPlayerData(GameServer.SEND_ALL)
            }
            is ClientMessage.GpsLocation -> {
                val playerId = playerIdOf(client)
                val team = client.player?.team ?: 0
                synchronized(players) {
                    val gps = players.gpsData.getOrPut(playerId) { GpsData() }
                    gps.longitude = message.longitude
                    gps.latitude = message.latitude
                    gps.team = team
                    gps.hasUpdate = true // Client takes care of making sure that it is only sending us changes in coordinates
                }
            }
            is ClientMessage.Settings -> {
                if (!settings.isAllowPlayerSettings) {
                    Log.w(TAG, "Player ${playerIdOf(client)} not allowed to send player settings")
                    server.sendPlayerSettings(GameServer.SEND_ALL, applyAll = false, applyTeam = false, allowPlayerSettings = true)
                    return
                }
                message.settings?.let { wire ->
                    val playerId = playerIdOf(client)
                    synchronized(players) {
                        // Admin rights are ignored: only the server operator grants them (D14)
                        wire.applyTo(players.playerSettings.getOrPut(playerId) { PlayerSettings() }, acceptAdmin = false)
                    }
                }
                server.sendPlayerSettings(GameServer.SEND_ALL, applyAll = false, applyTeam = false, allowPlayerSettings = true)
                events.publish(ServerEvent.PlayerDataChanged(null))
            }
            is ClientMessage.NameChange -> {
                roster.onNameChange(client, message.name)
                server.sendAllGameInfo(GameServer.SEND_ALL)
                events.publish(ServerEvent.PlayerDataChanged(null))
            }
            is ClientMessage.Hello -> handleHello(client, message)
            is ClientMessage.Command -> handleCommand(client, message.command)
        }
    }

    private fun handleHello(client: Connection, hello: ClientMessage.Hello) {
        server.requestFullGpsUpdate() // Send all GPS info because of the new client
        val blasterMac = hello.mac ?: ""
        val oldTeam = client.player?.team // null for a new player
        val result = roster.onHello(client, hello.playerId.toByte(), hello.name, blasterMac, client.inetAddress)
        when (result.kind) {
            GameRoster.HelloResult.Kind.REFUSED_IN_PROGRESS -> {
                Log.d(TAG, "Rejecting client ${client.connectionId} (MAC: $blasterMac) - Game in progress and allowJoin is false.")
                client.send(command(ServerCommand.Error("Cannot join game in progress due to server settings.")))
                client.closeAfterPending()
                roster.removeConnection(client)
            }
            GameRoster.HelloResult.Kind.FULL -> {
                client.close()
                roster.removeConnection(client)
            }
            GameRoster.HelloResult.Kind.REJOINED -> {
                val existingPlayerId = result.record!!.playerId
                Log.d(TAG, "rejoining client ${client.connectionId} to player ID $existingPlayerId (MAC: $blasterMac)")
                result.displaced?.close()
                applyPending(client, hello)
                client.send(command(ServerCommand.ServerReply(existingPlayerId)))
                server.sendAllGameInfo(existingPlayerId.toInt())
                events.publish(ServerEvent.PlayerDataChanged(null))
                events.publish(ServerEvent.PlayerJoined)
            }
            GameRoster.HelloResult.Kind.JOINED -> {
                val record = result.record!!
                val id = record.playerId
                server.applyPreset(id, record.team, oldTeam)
                applyPending(client, hello)
                client.send(command(ServerCommand.ServerReply(id)))
                Log.d(TAG, "client ${client.connectionId} player '${record.name}' ($id, team ${record.team}) found at ${client.inetAddress}")
                server.sendAllGameInfo(id.toInt())
                events.publish(ServerEvent.PlayerJoined)
            }
        }
    }

    /** The entries a (re)joining client hadn't had acknowledged, applied before its snapshot is built. */
    private fun applyPending(client: Connection, hello: ClientMessage.Hello) {
        val gameId = hello.gameId ?: 0
        for (entry in hello.pending) handleReliable(client, entry.rmsg, entry.seq, gameId)
    }

    /**
     * A reliable message [rmsg] (§3.4): applied once, through the same handler as a live
     * one. Returns the seq to acknowledge, or -1 to leave it unacknowledged.
     */
    private fun handleReliable(client: Connection, rmsg: String, seq: Int, gameId: Long): Int {
        when (roster.checkReliable(client, seq, gameId)) {
            GameRoster.ReliableCheck.NO_PLAYER -> {
                Log.w(TAG, "reliable message before hello from client ${client.connectionId}: $rmsg")
                return -1
            }
            GameRoster.ReliableCheck.STALE -> {
                Log.d(TAG, "dropping reliable message from another game: $rmsg ($gameId)")
                return seq
            }
            GameRoster.ReliableCheck.DUPLICATE -> Log.d(TAG, "dropping duplicate reliable message $seq: $rmsg")
            GameRoster.ReliableCheck.APPLY -> {
                val command = WireCodec.decodeClientCommand(rmsg)
                if (command is ClientCommand.Eliminated) handleEliminated(client, command)
                else Log.w(TAG, "unknown reliable message: $rmsg")
            }
        }
        return client.player?.lastAppliedSeq ?: seq
    }

    /**
     * The player behind [client] was eliminated by the command's shooter. During the game:
     * the shooter and its team are told and the limits checked. After the game it only counts
     * in the final scores.
     */
    private fun handleEliminated(client: Connection, command: ClientCommand.Eliminated) {
        val id = command.shooterId ?: return
        val elimination = roster.onEliminated(client, id)
        if (elimination == null) {
            Log.d(TAG, "elimination ignored outside a game")
            return
        }
        if (!roster.isRunning()) {
            server.sendPlayerData(GameServer.SEND_ALL)
            events.publish(ServerEvent.PlayerDataChanged(null))
            return
        }
        if (!elimination.credited) {
            // Own or a teammate's grenade: a life lost, no score for anyone
            events.publish(ServerEvent.PlayerDataChanged(null))
            checkLivesLimitEndGame()
            return
        }
        server.sendToPlayer(command(ServerCommand.Eliminated(elimination.victimId)), id)
        val layout = settings.teamLayout
        if (!layout.mode.isFreeForAll) {
            // Send a message to all teammates about the score increase
            server.sendToTeam(command(ServerCommand.TeamEliminated), id, includePlayer = false)
        }
        events.publish(ServerEvent.PlayerDataChanged(null))
        val scoreLimit = if (settings.gameLimit and GameSettingsRepository.GAME_LIMIT_SCORE != 0) settings.scoreLimit else 0
        if (scoreLimit != 0) {
            val score = if (layout.mode.isFreeForAll) elimination.shooterPoints else roster.teamPoints(layout.teamOf(id))
            if (score >= scoreLimit) server.endGame()
        }
        checkLivesLimitEndGame()
    }

    /** Ends the game once at most one team has a player with lives left. */
    private fun checkLivesLimitEndGame() {
        if (gameState.gameState == GameStateRepository.GAME_STATE_NONE) return

        val hasGlobalLifeLimit = settings.gameLimit and GameSettingsRepository.GAME_LIMIT_LIVES != 0 && settings.livesLimit > 0
        val playerSettingsMap = players.playerSettings
        val hasOverrideLifeLimit = playerSettingsMap.values.any { it != null && it.overrideLives && it.lives > 0 }
        if (!hasGlobalLifeLimit && !hasOverrideLifeLimit) return

        val livingTeams = HashSet<Int>()
        for (record in roster.records()) {
            val playerId = record.playerId
            val playerSettings = playerSettingsMap[playerId]
            val totalLives = when {
                playerSettings != null && playerSettings.overrideLives && playerSettings.lives > 0 -> playerSettings.lives
                hasGlobalLifeLimit -> settings.livesLimit
                else -> 0
            }
            if (totalLives <= 0 || totalLives - record.eliminated > 0) livingTeams += players.calcNetworkTeam(playerId)
        }
        if (livingTeams.size <= 1) {
            Log.d(TAG, "checkLivesLimitEndGame: ending game, living teams remaining: ${livingTeams.size}")
            server.endGame()
        }
    }

    /** [swapWith]: the player on [targetTeam] to trade places with when it has no open slot. */
    private fun handleTeamChangeRequest(client: Connection, targetTeam: Int, swapWith: Int?) {
        val player = client.player
        if (player == null || player.playerId <= 0) return
        if (!settings.isAllowChooseTeams) {
            Log.w(TAG, "Team change rejected: Allow players to choose teams is turned off.")
            return
        }
        if (roster.phase() != SessionPhase.LOBBY) {
            Log.w(TAG, "Team change rejected: only allowed in the lobby.")
            return
        }
        val teamMode = settings.teamMode
        if (teamMode.isFreeForAll) {
            Log.w(TAG, "Team change rejected: Free For All mode.")
            return
        }
        if (targetTeam < 1 || targetTeam > teamMode.teamCount) {
            Log.w(TAG, "Team change rejected: Invalid target team $targetTeam")
            return
        }
        if (player.team == targetTeam) {
            Log.d(TAG, "Client ${player.playerId} is already on team $targetTeam")
            return
        }
        val oldTeam = player.team
        val moved = roster.changeTeam(client, targetTeam)
        if (moved == null) {
            val other = swapWith?.takeIf { it in 1..GameSettingsRepository.MAX_PLAYER_ID }?.toByte()
            if (other == null || settings.teamLayout.teamOf(other) != targetTeam) {
                Log.w(TAG, "Team change rejected: No open slot on team $targetTeam")
            } else if (!server.swapPlayers(player.playerId, other)) {
                Log.w(TAG, "Team change rejected: could not swap with player $other")
            }
            return
        }
        server.applyPreset(moved.playerId, targetTeam, oldTeam)
        client.send(command(ServerCommand.ServerReply(moved.playerId)))
        server.sendPlayerData(GameServer.SEND_ALL)
        server.sendAllGameInfo(GameServer.SEND_ALL)
        events.publish(ServerEvent.PlayerDataChanged(null))
    }

    /** [client] is gone: dropped, timed out, or it left. */
    fun removeClient(client: Connection) {
        if (client !in roster.connections()) {
            // Already removed, kicked, refused or replaced: its reader just noticed the close
            client.close()
            return
        }
        if (roster.onDisconnect(client) == GameRoster.Disconnect.REMOVED) {
            server.requestFullGpsUpdate() // Force a full GPS update when someone leaves
            server.sendAllGameInfo(GameServer.SEND_ALL)
            events.publish(ServerEvent.PlayerLeft)
        }
        client.close() // a player kept during a game stays on the scoreboard, disconnected
        events.publish(ServerEvent.PlayerDataChanged(null))
    }

    /** The server operator gave this player admin rights in its player settings. */
    private fun isAdmin(client: Connection): Boolean {
        val player = client.player ?: return false
        return players.playerSettings[player.playerId]?.isAdmin == true
    }

    private fun playerIdOf(client: Connection): Byte = client.player?.playerId ?: 0

    private companion object {
        const val TAG = "TCPServer"

        /** A MESG frame carrying [command]. */
        fun command(command: ServerCommand): String = WireCodec.encode(ServerMessage.Command(command))
    }
}
