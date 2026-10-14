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

import com.simplecoil.simplecoil.data.model.PlayerSettings

// The typed v07 wire model (network design §7.3). WireCodec is the only code that turns these
// into frames and back; nothing else knows the header strings or the JSON keys.

/**
 * A MESG command from a client. An id that is missing or not a number decodes as null, which
 * the server ignores, as its parser did.
 */
sealed interface ClientCommand {
    data object ShotFired : ClientCommand

    /** The sender was hit by [shooterId]. */
    data class Hit(val shooterId: Byte?) : ClientCommand

    /** The sender is out of health, from [shooterId]'s shot. */
    data class Out(val shooterId: Byte?) : ClientCommand

    /** The sender was eliminated by [shooterId]. Sent reliably since v07 (as a reliable message's rmsg). */
    data class Eliminated(val shooterId: Byte?) : ClientCommand
    data object TeamEliminated : ClientCommand
    data object PlayerDataRequest : ClientCommand
    data object Leave : ClientCommand

    /** A plain ENDGAME: the client is leaving (older clients). */
    data object EndGame : ClientCommand

    /** A plain STARTGAME; admins only (D14). */
    data object StartGame : ClientCommand
    data object AdminStartGame : ClientCommand
    data object AdminEndGame : ClientCommand
    data object AdminReset : ClientCommand
    data object AdminSortTeams : ClientCommand
    data class AdminGameMode(val wireValue: Int?) : ClientCommand
    data class AdminKick(val playerId: Byte?) : ClientCommand

    /** Null when a size is not a number. */
    data class AdminTeamSizes(val sizes: List<Int>?) : ClientCommand

    /** Anything else; one that starts with ADMIN_ still counts as an admin command. */
    data class Other(val text: String) : ClientCommand
}

/** A MESG command from the server. An id that is missing or not a number decodes as null. */
sealed interface ServerCommand {
    data object StartGame : ServerCommand
    data object EndGame : ServerCommand
    data object ShotFired : ServerCommand

    /** The receiver's shot hit [targetId]. */
    data class Hit(val targetId: Byte?) : ServerCommand

    /** The receiver's shot took [targetId] out of health. */
    data class Out(val targetId: Byte?) : ServerCommand

    /** The receiver eliminated [victimId]. */
    data class Eliminated(val victimId: Byte?) : ServerCommand

    /** A teammate eliminated someone. */
    data object TeamEliminated : ServerCommand

    /** The receiver's (new) player id. */
    data class ServerReply(val playerId: Byte?) : ServerCommand

    /** Every reliable message up to [seq] is applied. */
    data class Ack(val seq: Int?) : ServerCommand
    data class Error(val message: String) : ServerCommand

    /** The server dropped this player (kicked, or the server stopped). */
    data object ServerCancel : ServerCommand
    data class Other(val text: String) : ServerCommand
}

/**
 * One player's settings, in both directions: the one wire mapping (N10). The client sends its
 * own without [recoilSetting] and [isAdmin]; the server sends everyone's with them. A null
 * optional field is left out of the frame.
 */
data class WirePlayerSettings(
    val playerId: Int,
    val health: Int,
    val reloadShots: Int,
    val reloadTime: Long,
    val reloadOnEmpty: Boolean,
    val manualRespawn: Boolean,
    val spawnTime: Long,
    val respawnShieldTime: Long?,
    val damage: Int,
    /** The player's own lives limit; null for the game's. */
    val livesLimit: Int?,
    val shotModeSingle: Boolean,
    val shotModeBurst3: Boolean,
    val shotModeAuto: Boolean,
    val firingMode: Int,
    val recoilSetting: Int?,
    val isAdmin: Boolean?,
) {
    /**
     * Writes these settings into [target]. A field the frame left out keeps its value, except
     * the lives limit, which is then off. [isAdmin] only counts when [acceptAdmin]: only the
     * server operator grants admin rights (D14).
     */
    fun applyTo(target: PlayerSettings, acceptAdmin: Boolean) {
        target.health = health
        target.shots = reloadShots.toByte()
        target.reloadTime = reloadTime
        target.reloadOnEmpty = reloadOnEmpty
        target.manualRespawn = manualRespawn
        target.spawnTime = spawnTime
        if (respawnShieldTime != null) target.respawnShieldTime = respawnShieldTime
        target.damage = damage
        target.overrideLives = livesLimit != null
        target.lives = livesLimit ?: 0
        target.allowShotModeAuto = shotModeAuto
        target.allowShotModeBurst3 = shotModeBurst3
        target.allowShotModeSingle = shotModeSingle
        target.firingMode = firingMode
        if (recoilSetting != null) target.recoilSetting = recoilSetting
        if (acceptAdmin && isAdmin != null) target.isAdmin = isAdmin
    }

    companion object {
        /** Everything [settings] holds, as the server sends it for [playerId]. */
        @JvmStatic
        fun of(playerId: Int, settings: PlayerSettings) = WirePlayerSettings(
            playerId = playerId,
            health = settings.health,
            reloadShots = settings.shots.toInt(),
            reloadTime = settings.reloadTime,
            reloadOnEmpty = settings.reloadOnEmpty,
            manualRespawn = settings.manualRespawn,
            spawnTime = settings.spawnTime,
            respawnShieldTime = settings.respawnShieldTime,
            damage = settings.damage,
            livesLimit = if (settings.overrideLives) settings.lives else null,
            shotModeSingle = settings.allowShotModeSingle,
            shotModeBurst3 = settings.allowShotModeBurst3,
            shotModeAuto = settings.allowShotModeAuto,
            firingMode = settings.firingMode,
            recoilSetting = settings.recoilSetting,
            isAdmin = settings.isAdmin,
        )
    }
}

/** A reliable message the server hasn't acknowledged, replayed in the hello (reconnect design §3.4). */
data class WirePending(val rmsg: String, val seq: Int)

/** What a client sends the server. */
sealed interface ClientMessage {
    data class Command(val command: ClientCommand) : ClientMessage

    /**
     * Who the client is (the hello). [playerId] 0 asks the server to choose; [gameId] and
     * [pending] carry the reliable messages the server hasn't acknowledged (D2, D3).
     */
    data class Hello(
        val playerId: Int,
        val name: String,
        /** Null or "" when the client has no blaster. */
        val mac: String?,
        val rejoin: Boolean,
        val gameId: Long?,
        val pending: List<WirePending>,
    ) : ClientMessage

    /** A reliable message (§3.4): [rmsg] is a command's text, such as ELIMINATED7. */
    data class Reliable(val rmsg: String, val seq: Int, val gameId: Long) : ClientMessage
    data class NameChange(val playerId: Int, val name: String) : ClientMessage

    /** The client's own settings; null when the frame's settings don't decode. */
    data class Settings(val playerId: Int, val settings: WirePlayerSettings?) : ClientMessage
    data class TeamChangeRequest(val playerId: Int, val team: Int) : ClientMessage
    data class GrenadePairing(val playerId: Int, val grenadeId: Int) : ClientMessage
    data class GpsLocation(val latitude: Double, val longitude: Double) : ClientMessage
}

/** A player in the game info. [ip] is InetAddress.toString(), "/" and all; the client skips its own entry's. */
data class WirePlayer(val playerId: Int, val name: String, val ip: String?)

/** The game's limits; null where there is none. */
data class WireLimits(val timeLimit: Int?, val livesLimit: Int?, val scoreLimit: Int?)

/** The receiving player's own score, in the game info it gets when it (re)joins. */
data class WirePlayerGameUpdate(
    val points: Int,
    val eliminated: Int,
    /** In team games. */
    val teamPoints: Int?,
    /** Seconds left, in a running timed game. */
    val timeRemaining: Long?,
    /** The last reliable message applied (§3.4). */
    val ackSeq: Int?,
)

/** A scoreboard row. [connected] is left out for a player the server has no score for. */
data class WireScore(val playerId: Int, val name: String, val ip: String?, val points: Int, val eliminated: Int, val connected: Boolean?)

/** A player's position; [team] is only read for a player the client hasn't placed yet. */
data class WireGpsPosition(val playerId: Int, val team: Int?, val longitude: Double, val latitude: Double)

data class WireGrenadePairing(val grenadeId: Int, val playerId: Int)

/** What the server sends a client. */
sealed interface ServerMessage {
    data class Command(val command: ServerCommand) : ServerMessage

    /** Sent bare, without the header: the client may not speak our version. */
    data object VersionError : ServerMessage

    /**
     * The game: players, limits, teams, settings and phase. A null field is left out of the
     * frame, as the server has always done; [playerUpdate] goes only to a player that just
     * (re)joined.
     */
    data class GameInfo(
        val players: List<WirePlayer>,
        val limits: WireLimits,
        /** TeamMode.wireValue. */
        val gameMode: Int,
        val teamSizes: List<Int>?,
        val gameState: Int?,
        val timeRemaining: Long?,
        /** SessionPhase.wireValue. */
        val sessionPhase: Int?,
        /** After the lobby only. */
        val gameId: Long?,
        /** The GPS mode, when GPS is on. */
        val gpsMode: Int?,
        val allowPlayerSettings: Boolean?,
        val playerSettings: List<WirePlayerSettings>?,
        val onlyServerSettings: Boolean,
        val allowChooseTeams: Boolean?,
        val startTime: Long?,
        val playerUpdate: WirePlayerGameUpdate?,
    ) : ServerMessage {
        fun withPlayerUpdate(update: WirePlayerGameUpdate?) = copy(playerUpdate = update)
    }

    /** The scoreboard. */
    data class PlayerData(val players: List<WireScore>) : ServerMessage

    /** Everyone's settings. */
    data class PlayerSettings(val allowPlayerSettings: Boolean, val players: List<WirePlayerSettings>) : ServerMessage
    data class GrenadePairings(val pairings: List<WireGrenadePairing>) : ServerMessage

    /** Positions that changed, or all of them when [full] (the client then forgets the rest). */
    data class GpsUpdate(val positions: List<WireGpsPosition>, val full: Boolean) : ServerMessage
}
