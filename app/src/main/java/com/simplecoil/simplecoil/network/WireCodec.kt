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

import android.util.Log
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ACK
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ADMIN_ENDGAME
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ADMIN_GAMEMODE
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ADMIN_KICK
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ADMIN_RESET
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ADMIN_SORTTEAMS
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ADMIN_STARTGAME
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ADMIN_TEAMSIZES
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ELIMINATED
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ENDGAME
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ERROR
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_HIT
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_LEAVE
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_OUT
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_PLAYERDATAREQUEST
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_SCOREMATRIXREQUEST
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_SERVERCANCEL
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_SERVERREPLY
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_SHOTFIRED
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_STARTGAME
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_TEAMELIMINATED
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The v07 wire format (network design §7): typed messages to frames and back. The only code
 * that knows the header strings and the JSON keys. JSON goes through org.json (Q2).
 *
 * Decoding never throws: a frame it can't make sense of decodes as null, which the receiver
 * ignores.
 */
object WireCodec {
    private const val TAG = "WireCodec"
    private const val ADMIN_PREFIX = "ADMIN_"
    private const val MESG_HEADER = WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_MESG
    private const val JSON_HEADER = WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON

    // ---------------------------------------------------------------------------------------
    // Frames
    // ---------------------------------------------------------------------------------------

    @JvmStatic
    fun encode(message: ClientMessage): String = when (message) {
        is ClientMessage.Command -> MESG_HEADER + text(message.command)
        is ClientMessage.Hello -> json {
            put(WireKeys.PLAYERID, message.playerId)
            put(WireKeys.PLAYERNAME, message.name)
            if (!message.mac.isNullOrEmpty()) put(WireKeys.BLASTER_MAC, message.mac)
            if (message.rejoin) put(WireKeys.REJOIN, true)
            if (message.gameId != null) put(WireKeys.GAMEID, message.gameId)
            if (message.pending.isNotEmpty()) {
                put(WireKeys.PENDING, JSONArray(message.pending.map { JSONObject().put(WireKeys.RMSG, it.rmsg).put(WireKeys.SEQ, it.seq) }))
            }
        }
        is ClientMessage.Reliable -> json {
            put(WireKeys.RMSG, message.rmsg)
            put(WireKeys.SEQ, message.seq)
            put(WireKeys.GAMEID, message.gameId)
        }
        is ClientMessage.NameChange -> json {
            put(WireKeys.PLAYERID, message.playerId)
            put(WireKeys.PLAYERNAMECHANGE, message.name)
        }
        is ClientMessage.Settings -> json {
            put(WireKeys.PLAYERSETTINGS, true)
            message.settings?.let { putSettings(it) }
            put(WireKeys.PLAYERID, message.playerId)
        }
        is ClientMessage.TeamChangeRequest -> json {
            put(WireKeys.PLAYERID, message.playerId)
            put(WireKeys.REQUEST_TEAM_CHANGE, message.team)
        }
        is ClientMessage.GrenadePairing -> json {
            put(WireKeys.PLAYERID, message.playerId)
            put(WireKeys.PAIRED_GRENADE_ID, message.grenadeId)
        }
        is ClientMessage.GpsLocation -> json {
            put(WireKeys.GPSLONGITUDE, message.longitude)
            put(WireKeys.GPSLATITUDE, message.latitude)
        }
    }

    @JvmStatic
    fun encode(message: ServerMessage): String = when (message) {
        is ServerMessage.Command -> MESG_HEADER + text(message.command)
        ServerMessage.VersionError -> WireProtocol.NETMSG_VERSIONERROR
        is ServerMessage.GameInfo -> json { putGameInfo(message) }
        is ServerMessage.PlayerData -> json { putPlayerData(message) }
        is ServerMessage.PlayerSettings -> json {
            put(WireKeys.ALLOWPLAYERSETTINGS, message.allowPlayerSettings)
            put(WireKeys.PLAYERSETTINGS, settingsArray(message.players))
        }
        is ServerMessage.GrenadePairings -> json {
            put(WireKeys.GRENADE_PAIRINGS, JSONArray(message.pairings.map {
                JSONObject().put(WireKeys.PLAYERID, it.playerId).put(WireKeys.PAIRED_GRENADE_ID, it.grenadeId)
            }))
        }
        is ServerMessage.GpsUpdate -> json {
            put(WireKeys.GPSUPDATE, JSONArray(message.positions.map {
                JSONObject().apply {
                    put(WireKeys.PLAYERID, it.playerId)
                    it.team?.let { team -> put(WireKeys.TEAM, team) }
                    put(WireKeys.GPSLONGITUDE, it.longitude)
                    put(WireKeys.GPSLATITUDE, it.latitude)
                }
            }))
            if (message.full) put(WireKeys.GPSFULLUPDATE, true)
        }
        is ServerMessage.ScoreMatrix -> json { putScoreMatrix(message) }
    }

    /** The JSON of a player data frame (the server screen reads it too). */
    @JvmStatic
    fun playerDataJson(message: ServerMessage.PlayerData): String = JSONObject().apply { putPlayerData(message) }.toString()

    /** The scoreboard in a player data frame's JSON; null when it doesn't decode. */
    @JvmStatic
    fun decodePlayerData(json: String): ServerMessage.PlayerData? = parse(json) { readPlayerData(it) }

    /** What a client's MESG or JSON frame says; null for any other kind of frame, or one that doesn't decode. */
    @JvmStatic
    fun decodeClient(frame: Frame): ClientMessage? = when (frame.kind) {
        Frame.Kind.MESG -> ClientMessage.Command(decodeClientCommand(frame.body))
        Frame.Kind.JSON -> parse(frame.body) { decodeClientJson(it) }
        else -> null
    }

    /** What the server's MESG or JSON frame says; null for any other kind of frame, or one that doesn't decode. */
    @JvmStatic
    fun decodeServer(frame: Frame): ServerMessage? = when (frame.kind) {
        Frame.Kind.MESG -> ServerMessage.Command(decodeServerCommand(frame.body))
        Frame.Kind.JSON -> parse(frame.body) { decodeServerJson(it) }
        Frame.Kind.VERSION_ERROR -> ServerMessage.VersionError
        else -> null
    }

    // ---------------------------------------------------------------------------------------
    // JSON
    // ---------------------------------------------------------------------------------------

    private inline fun json(build: JSONObject.() -> Unit): String = JSON_HEADER + JSONObject().apply(build)

    /** [decode]'s result for [body]'s JSON, or null (logged) when the JSON or a required key is bad. */
    private inline fun <T> parse(body: String, decode: (JSONObject) -> T): T? = orNull { decode(JSONObject(body)) }

    /** A client's JSON frame, told apart by its keys in the order the server has always checked them. */
    private fun decodeClientJson(json: JSONObject): ClientMessage = when {
        json.has(WireKeys.RMSG) ->
            ClientMessage.Reliable(json.getString(WireKeys.RMSG), json.getInt(WireKeys.SEQ), json.optLong(WireKeys.GAMEID, 0))
        json.has(WireKeys.REQUEST_TEAM_CHANGE) ->
            ClientMessage.TeamChangeRequest(json.optInt(WireKeys.PLAYERID, 0), json.getInt(WireKeys.REQUEST_TEAM_CHANGE))
        json.has(WireKeys.PAIRED_GRENADE_ID) ->
            ClientMessage.GrenadePairing(json.getInt(WireKeys.PLAYERID), json.getInt(WireKeys.PAIRED_GRENADE_ID))
        json.has(WireKeys.GPSLONGITUDE) ->
            ClientMessage.GpsLocation(json.getDouble(WireKeys.GPSLATITUDE), json.getDouble(WireKeys.GPSLONGITUDE))
        json.has(WireKeys.PLAYERSETTINGS) -> {
            val playerId = json.optInt(WireKeys.PLAYERID, 0)
            ClientMessage.Settings(playerId, orNull { readSettings(json, playerId) })
        }
        json.has(WireKeys.PLAYERNAMECHANGE) ->
            ClientMessage.NameChange(json.optInt(WireKeys.PLAYERID, 0), json.getString(WireKeys.PLAYERNAMECHANGE))
        else -> {
            val pending = ArrayList<WirePending>()
            val entries = json.optJSONArray(WireKeys.PENDING)
            if (entries != null) {
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    if (!entry.has(WireKeys.RMSG) || !entry.has(WireKeys.SEQ)) continue
                    pending += WirePending(entry.optString(WireKeys.RMSG), entry.optInt(WireKeys.SEQ))
                }
            }
            ClientMessage.Hello(
                playerId = json.getInt(WireKeys.PLAYERID),
                name = json.getString(WireKeys.PLAYERNAME),
                mac = if (json.has(WireKeys.BLASTER_MAC)) json.getString(WireKeys.BLASTER_MAC) else null,
                rejoin = json.optBoolean(WireKeys.REJOIN, false),
                gameId = if (json.has(WireKeys.GAMEID)) json.optLong(WireKeys.GAMEID, 0) else null,
                pending = pending,
            )
        }
    }

    /** [decode]'s result, or null (logged) when a required key is missing or of the wrong type. */
    private inline fun <T> orNull(decode: () -> T): T? = try {
        decode()
    } catch (e: JSONException) {
        Log.w(TAG, "Bad JSON frame: ${e.message}")
        null
    }

    /**
     * The server's JSON frame, told apart by its keys in the order the client has always checked
     * them; null for one with none of them.
     */
    private fun decodeServerJson(json: JSONObject): ServerMessage? = when {
        json.has(WireKeys.GRENADE_PAIRINGS) -> {
            val array = json.getJSONArray(WireKeys.GRENADE_PAIRINGS)
            ServerMessage.GrenadePairings((0 until array.length()).map {
                val pairing = array.getJSONObject(it)
                WireGrenadePairing(pairing.getInt(WireKeys.PAIRED_GRENADE_ID), pairing.getInt(WireKeys.PLAYERID))
            })
        }
        json.has(WireKeys.GPSUPDATE) -> {
            val array = json.getJSONArray(WireKeys.GPSUPDATE)
            ServerMessage.GpsUpdate((0 until array.length()).map {
                val position = array.getJSONObject(it)
                WireGpsPosition(position.getInt(WireKeys.PLAYERID),
                    if (position.has(WireKeys.TEAM)) position.getInt(WireKeys.TEAM) else null,
                    position.getDouble(WireKeys.GPSLONGITUDE), position.getDouble(WireKeys.GPSLATITUDE))
            }, json.has(WireKeys.GPSFULLUPDATE))
        }
        json.has(WireKeys.PLAYERDATA) -> readPlayerData(json)
        json.has(WireKeys.SCOREMATRIX) -> readScoreMatrix(json.getJSONObject(WireKeys.SCOREMATRIX))
        json.has(WireKeys.PLAYERS) -> readGameInfo(json)
        json.has(WireKeys.PLAYERSETTINGS) ->
            ServerMessage.PlayerSettings(json.getBoolean(WireKeys.ALLOWPLAYERSETTINGS), readSettingsArray(json))
        else -> null
    }

    private fun JSONObject.putGameInfo(info: ServerMessage.GameInfo) {
        put(WireKeys.PLAYERS, JSONArray(info.players.map {
            JSONObject().put(WireKeys.PLAYERNAME, it.name).put(WireKeys.PLAYERID, it.playerId).putOpt(WireKeys.PLAYERIP, it.ip)
        }))
        put(WireKeys.LIMITS, JSONObject().apply {
            info.limits.timeLimit?.let { put(WireKeys.TIMELIMIT, it) }
            info.limits.livesLimit?.let { put(WireKeys.LIVESLIMIT, it) }
            info.limits.scoreLimit?.let { put(WireKeys.SCORELIMIT, it) }
        })
        put(WireKeys.GAMEMODE, info.gameMode)
        info.teamSizes?.let { put(WireKeys.TEAMSIZES, JSONArray(it)) }
        info.gameState?.let { put(WireKeys.GAMESTATE, it) }
        info.timeRemaining?.let { put(WireKeys.TIMEREMAINING, it) }
        info.sessionPhase?.let { put(WireKeys.SESSIONPHASE, it) }
        info.gameId?.let { put(WireKeys.GAMEID, it) }
        info.gpsMode?.let { put(WireKeys.USEGPS, it) }
        info.allowPlayerSettings?.let { put(WireKeys.ALLOWPLAYERSETTINGS, it) }
        info.playerSettings?.let { put(WireKeys.PLAYERSETTINGS, settingsArray(it)) }
        put(WireKeys.ONLY_SERVER_SETTINGS, info.onlyServerSettings)
        info.allowChooseTeams?.let { put(WireKeys.ALLOW_CHOOSE_TEAMS, it) }
        info.startTime?.let { put(WireKeys.START_TIME, it) }
        info.playerUpdate?.let { update ->
            put(WireKeys.PLAYERGAMEUPDATE, JSONObject().apply {
                put(WireKeys.PLAYERPOINTS, update.points)
                put(WireKeys.PLAYERELIMINATED, update.eliminated)
                update.teamPoints?.let { put(WireKeys.TEAMPOINTS, it) }
                update.timeRemaining?.let { put(WireKeys.TIMEREMAINING, it) }
                update.ackSeq?.let { put(WireKeys.ACKSEQ, it) }
            })
        }
    }

    /** The game info, with the keys the client requires required and the rest read as it read them. */
    private fun readGameInfo(json: JSONObject): ServerMessage.GameInfo {
        val playersArray = json.getJSONArray(WireKeys.PLAYERS)
        val players = (0 until playersArray.length()).map {
            val player = playersArray.getJSONObject(it)
            WirePlayer(player.getInt(WireKeys.PLAYERID), player.getString(WireKeys.PLAYERNAME),
                if (player.has(WireKeys.PLAYERIP)) player.getString(WireKeys.PLAYERIP) else null)
        }
        val limits = json.getJSONObject(WireKeys.LIMITS)
        val hasSettings = json.has(WireKeys.PLAYERSETTINGS)
        val update = if (!json.has(WireKeys.PLAYERGAMEUPDATE)) null else json.getJSONObject(WireKeys.PLAYERGAMEUPDATE).let {
            WirePlayerGameUpdate(
                points = it.getInt(WireKeys.PLAYERPOINTS),
                eliminated = it.getInt(WireKeys.PLAYERELIMINATED),
                teamPoints = it.optIntOrNull(WireKeys.TEAMPOINTS, 0),
                timeRemaining = if (it.has(WireKeys.TIMEREMAINING)) it.optLong(WireKeys.TIMEREMAINING, -1) else null,
                ackSeq = it.optIntOrNull(WireKeys.ACKSEQ, 0),
            )
        }
        return ServerMessage.GameInfo(
            players = players,
            limits = WireLimits(limits.getIntOrNull(WireKeys.TIMELIMIT), limits.getIntOrNull(WireKeys.LIVESLIMIT),
                limits.getIntOrNull(WireKeys.SCORELIMIT)),
            gameMode = json.getInt(WireKeys.GAMEMODE),
            teamSizes = if (!json.has(WireKeys.TEAMSIZES)) null else
                json.getJSONArray(WireKeys.TEAMSIZES).let { sizes -> (0 until sizes.length()).map { sizes.getInt(it) } },
            gameState = json.getIntOrNull(WireKeys.GAMESTATE),
            timeRemaining = if (json.has(WireKeys.TIMEREMAINING)) json.optLong(WireKeys.TIMEREMAINING) else null,
            sessionPhase = json.getIntOrNull(WireKeys.SESSIONPHASE),
            gameId = if (json.has(WireKeys.GAMEID)) json.optLong(WireKeys.GAMEID, 0) else null,
            gpsMode = json.getIntOrNull(WireKeys.USEGPS),
            allowPlayerSettings = when {
                hasSettings -> json.getBoolean(WireKeys.ALLOWPLAYERSETTINGS)
                json.has(WireKeys.ALLOWPLAYERSETTINGS) -> json.optBoolean(WireKeys.ALLOWPLAYERSETTINGS)
                else -> null
            },
            playerSettings = if (hasSettings) readSettingsArray(json) else null,
            onlyServerSettings = json.getBoolean(WireKeys.ONLY_SERVER_SETTINGS),
            allowChooseTeams = if (json.has(WireKeys.ALLOW_CHOOSE_TEAMS)) json.getBoolean(WireKeys.ALLOW_CHOOSE_TEAMS) else null,
            startTime = if (json.has(WireKeys.START_TIME)) json.getLong(WireKeys.START_TIME) else null,
            playerUpdate = update,
        )
    }

    private fun JSONObject.putPlayerData(data: ServerMessage.PlayerData) {
        put(WireKeys.PLAYERDATA, JSONArray(data.players.map {
            JSONObject().apply {
                put(WireKeys.PLAYERNAME, it.name)
                put(WireKeys.PLAYERID, it.playerId)
                putOpt(WireKeys.PLAYERIP, it.ip)
                put(WireKeys.PLAYERPOINTS, it.points)
                put(WireKeys.PLAYERELIMINATED, it.eliminated)
                it.connected?.let { connected -> put(WireKeys.CONNECTED, connected) }
            }
        }))
    }

    private fun readPlayerData(json: JSONObject): ServerMessage.PlayerData {
        val array = json.getJSONArray(WireKeys.PLAYERDATA)
        return ServerMessage.PlayerData((0 until array.length()).map {
            val player = array.getJSONObject(it)
            WireScore(player.getInt(WireKeys.PLAYERID), player.getString(WireKeys.PLAYERNAME),
                if (player.has(WireKeys.PLAYERIP)) player.optString(WireKeys.PLAYERIP) else null,
                player.getInt(WireKeys.PLAYERPOINTS), player.getInt(WireKeys.PLAYERELIMINATED),
                if (player.has(WireKeys.CONNECTED)) player.optBoolean(WireKeys.CONNECTED, true) else null)
        })
    }

    private fun JSONObject.putScoreMatrix(matrix: ServerMessage.ScoreMatrix) {
        put(WireKeys.SCOREMATRIX, JSONObject().apply {
            put(WireKeys.PLAYERS, JSONArray(matrix.players.map {
                JSONObject().apply {
                    put(WireKeys.PLAYERID, it.playerId)
                    put(WireKeys.PLAYERNAME, it.name)
                    put(WireKeys.TEAM, it.team)
                    put(WireKeys.CONNECTED, it.connected)
                }
            }))
            put(WireKeys.CELLS, JSONArray(matrix.cells.map { JSONArray().put(it.shooterId).put(it.victimId).put(it.count) }))
            matrix.gameId?.let { put(WireKeys.GAMEID, it) }
        })
    }

    private fun readScoreMatrix(json: JSONObject): ServerMessage.ScoreMatrix {
        val players = json.getJSONArray(WireKeys.PLAYERS)
        val cells = json.getJSONArray(WireKeys.CELLS)
        return ServerMessage.ScoreMatrix(
            players = (0 until players.length()).map {
                val player = players.getJSONObject(it)
                WireMatrixPlayer(player.getInt(WireKeys.PLAYERID), player.getString(WireKeys.PLAYERNAME),
                    player.optInt(WireKeys.TEAM, 1), player.optBoolean(WireKeys.CONNECTED, true))
            },
            cells = (0 until cells.length()).map {
                val cell = cells.getJSONArray(it)
                WireMatrixCell(cell.getInt(0), cell.getInt(1), cell.getInt(2))
            },
            gameId = if (json.has(WireKeys.GAMEID)) json.optLong(WireKeys.GAMEID, 0) else null,
        )
    }

    private fun settingsArray(players: List<WirePlayerSettings>) = JSONArray(players.map {
        JSONObject().apply {
            put(WireKeys.PLAYERID, it.playerId)
            putSettings(it)
        }
    })

    private fun readSettingsArray(json: JSONObject): List<WirePlayerSettings> {
        val array = json.getJSONArray(WireKeys.PLAYERSETTINGS)
        return (0 until array.length()).map {
            val entry = array.getJSONObject(it)
            readSettings(entry, entry.getInt(WireKeys.PLAYERID))
        }
    }

    private fun JSONObject.getIntOrNull(key: String): Int? = if (has(key)) getInt(key) else null

    /** Null when [key] is absent; [default] when it is there but not a number, as optInt read it. */
    private fun JSONObject.optIntOrNull(key: String, default: Int): Int? = if (has(key)) optInt(key, default) else null

    private fun JSONObject.putSettings(settings: WirePlayerSettings) {
        put(WireKeys.HEALTH, settings.health)
        put(WireKeys.RELOAD_SHOTS, settings.reloadShots)
        put(WireKeys.RELOAD_TIME, settings.reloadTime)
        put(WireKeys.RELOAD_ON_EMPTY, settings.reloadOnEmpty)
        put(WireKeys.MANUAL_RESPAWN, settings.manualRespawn)
        put(WireKeys.SPAWN_TIME, settings.spawnTime)
        settings.respawnShieldTime?.let { put(WireKeys.RESPAWN_SHIELD_TIME, it) }
        put(WireKeys.DAMAGE, settings.damage)
        settings.livesLimit?.let { put(WireKeys.LIVESLIMIT, it) }
        put(WireKeys.SHOT_MODE_SINGLE, settings.shotModeSingle)
        put(WireKeys.SHOT_MODE_BURST3, settings.shotModeBurst3)
        put(WireKeys.SHOT_MODE_AUTO, settings.shotModeAuto)
        put(WireKeys.FIRING_MODE, settings.firingMode)
        settings.recoilSetting?.let { put(WireKeys.RECOIL_SETTING, it) }
        settings.isAdmin?.let { put(WireKeys.IS_ADMIN, it) }
    }

    /** One player's settings; the optional keys as both parsers have always treated them. */
    private fun readSettings(json: JSONObject, playerId: Int) = WirePlayerSettings(
        playerId = playerId,
        health = json.getInt(WireKeys.HEALTH),
        reloadShots = json.getInt(WireKeys.RELOAD_SHOTS),
        reloadTime = json.getLong(WireKeys.RELOAD_TIME),
        reloadOnEmpty = json.getBoolean(WireKeys.RELOAD_ON_EMPTY),
        manualRespawn = json.optBoolean(WireKeys.MANUAL_RESPAWN, false),
        spawnTime = json.getLong(WireKeys.SPAWN_TIME),
        respawnShieldTime = if (json.has(WireKeys.RESPAWN_SHIELD_TIME)) json.getLong(WireKeys.RESPAWN_SHIELD_TIME) else null,
        damage = json.getInt(WireKeys.DAMAGE),
        livesLimit = if (json.has(WireKeys.LIVESLIMIT)) json.getInt(WireKeys.LIVESLIMIT) else null,
        shotModeSingle = json.getBoolean(WireKeys.SHOT_MODE_SINGLE),
        shotModeBurst3 = json.getBoolean(WireKeys.SHOT_MODE_BURST3),
        shotModeAuto = json.getBoolean(WireKeys.SHOT_MODE_AUTO),
        firingMode = json.getInt(WireKeys.FIRING_MODE),
        recoilSetting = if (json.has(WireKeys.RECOIL_SETTING)) json.getInt(WireKeys.RECOIL_SETTING) else null,
        isAdmin = if (json.has(WireKeys.IS_ADMIN)) json.getBoolean(WireKeys.IS_ADMIN) else null,
    )

    // ---------------------------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------------------------

    /** The command's text: a MESG body, or a reliable message's rmsg. */
    @JvmStatic
    fun text(command: ClientCommand): String = when (command) {
        ClientCommand.ShotFired -> NETMSG_SHOTFIRED
        is ClientCommand.Hit -> NETMSG_HIT + command.shooterId.argument()
        is ClientCommand.Out -> NETMSG_OUT + command.shooterId.argument()
        is ClientCommand.Eliminated -> NETMSG_ELIMINATED + command.shooterId.argument()
        ClientCommand.TeamEliminated -> NETMSG_TEAMELIMINATED
        ClientCommand.PlayerDataRequest -> NETMSG_PLAYERDATAREQUEST
        ClientCommand.ScoreMatrixRequest -> NETMSG_SCOREMATRIXREQUEST
        ClientCommand.Leave -> NETMSG_LEAVE
        ClientCommand.EndGame -> NETMSG_ENDGAME
        ClientCommand.StartGame -> NETMSG_STARTGAME
        ClientCommand.AdminStartGame -> NETMSG_ADMIN_STARTGAME
        ClientCommand.AdminEndGame -> NETMSG_ADMIN_ENDGAME
        ClientCommand.AdminReset -> NETMSG_ADMIN_RESET
        ClientCommand.AdminSortTeams -> NETMSG_ADMIN_SORTTEAMS
        is ClientCommand.AdminGameMode -> NETMSG_ADMIN_GAMEMODE + command.wireValue.argument()
        is ClientCommand.AdminKick -> NETMSG_ADMIN_KICK + command.playerId.argument()
        is ClientCommand.AdminTeamSizes -> NETMSG_ADMIN_TEAMSIZES + (command.sizes?.joinToString(",") ?: "")
        is ClientCommand.Other -> command.text
    }

    @JvmStatic
    fun text(command: ServerCommand): String = when (command) {
        ServerCommand.StartGame -> NETMSG_STARTGAME
        ServerCommand.EndGame -> NETMSG_ENDGAME
        ServerCommand.ShotFired -> NETMSG_SHOTFIRED
        is ServerCommand.Hit -> NETMSG_HIT + command.targetId.argument()
        is ServerCommand.Out -> NETMSG_OUT + command.targetId.argument()
        is ServerCommand.Eliminated -> NETMSG_ELIMINATED + command.victimId.argument()
        ServerCommand.TeamEliminated -> NETMSG_TEAMELIMINATED
        is ServerCommand.ServerReply -> NETMSG_SERVERREPLY + command.playerId.argument()
        is ServerCommand.Ack -> NETMSG_ACK + command.seq.argument()
        is ServerCommand.Error -> NETMSG_ERROR + command.message
        ServerCommand.ServerCancel -> NETMSG_SERVERCANCEL
        is ServerCommand.Other -> command.text
    }

    /** A client's command, as the server has always read it: ids without spaces, admin arguments trimmed. */
    @JvmStatic
    fun decodeClientCommand(text: String): ClientCommand = when {
        text == NETMSG_LEAVE -> ClientCommand.Leave
        text.startsWith(NETMSG_ELIMINATED) -> ClientCommand.Eliminated(strictId(text.after(NETMSG_ELIMINATED)))
        text == NETMSG_SHOTFIRED -> ClientCommand.ShotFired
        text.startsWith(NETMSG_HIT) -> ClientCommand.Hit(strictId(text.after(NETMSG_HIT)))
        text.startsWith(NETMSG_OUT) -> ClientCommand.Out(strictId(text.after(NETMSG_OUT)))
        text == NETMSG_TEAMELIMINATED -> ClientCommand.TeamEliminated
        text == NETMSG_PLAYERDATAREQUEST -> ClientCommand.PlayerDataRequest
        text == NETMSG_SCOREMATRIXREQUEST -> ClientCommand.ScoreMatrixRequest
        text == NETMSG_STARTGAME -> ClientCommand.StartGame
        text.startsWith(NETMSG_ADMIN_STARTGAME) -> ClientCommand.AdminStartGame
        text == NETMSG_ENDGAME -> ClientCommand.EndGame
        text.startsWith(NETMSG_ADMIN_ENDGAME) -> ClientCommand.AdminEndGame
        text.startsWith(NETMSG_ADMIN_RESET) -> ClientCommand.AdminReset
        text.startsWith(NETMSG_ADMIN_SORTTEAMS) -> ClientCommand.AdminSortTeams
        text.startsWith(NETMSG_ADMIN_GAMEMODE) -> ClientCommand.AdminGameMode(strictInt(text.after(NETMSG_ADMIN_GAMEMODE).trim()))
        text.startsWith(NETMSG_ADMIN_KICK) -> ClientCommand.AdminKick(strictInt(text.after(NETMSG_ADMIN_KICK).trim())?.toByte())
        text.startsWith(NETMSG_ADMIN_TEAMSIZES) -> ClientCommand.AdminTeamSizes(teamSizes(text.after(NETMSG_ADMIN_TEAMSIZES).trim()))
        else -> ClientCommand.Other(text)
    }

    /** Every ADMIN_ command, and a plain STARTGAME: only admins may run the game (D14). */
    @JvmStatic
    fun isAdminCommand(command: ClientCommand): Boolean = when (command) {
        ClientCommand.StartGame, ClientCommand.AdminStartGame, ClientCommand.AdminEndGame, ClientCommand.AdminReset,
        ClientCommand.AdminSortTeams, is ClientCommand.AdminGameMode, is ClientCommand.AdminKick,
        is ClientCommand.AdminTeamSizes -> true
        is ClientCommand.Other -> command.text.startsWith(ADMIN_PREFIX)
        else -> false
    }

    /** The server's command, as the client has always read it: ids trimmed, a missing one null. */
    @JvmStatic
    fun decodeServerCommand(text: String): ServerCommand = when {
        text.startsWith(NETMSG_ELIMINATED) -> ServerCommand.Eliminated(lenientId(text.after(NETMSG_ELIMINATED)))
        text.startsWith(NETMSG_HIT) -> ServerCommand.Hit(lenientId(text.after(NETMSG_HIT)))
        text.startsWith(NETMSG_OUT) -> ServerCommand.Out(lenientId(text.after(NETMSG_OUT)))
        text == NETMSG_SHOTFIRED -> ServerCommand.ShotFired
        text.startsWith(NETMSG_SERVERREPLY) -> ServerCommand.ServerReply(lenientId(text.after(NETMSG_SERVERREPLY)))
        text == NETMSG_TEAMELIMINATED -> ServerCommand.TeamEliminated
        text == NETMSG_ENDGAME -> ServerCommand.EndGame
        text == NETMSG_STARTGAME -> ServerCommand.StartGame
        text == NETMSG_SERVERCANCEL -> ServerCommand.ServerCancel
        text.startsWith(NETMSG_ACK) -> ServerCommand.Ack(strictInt(text.after(NETMSG_ACK)))
        text.startsWith(NETMSG_ERROR) -> ServerCommand.Error(text.after(NETMSG_ERROR))
        else -> ServerCommand.Other(text)
    }

    private fun String.after(prefix: String) = substring(prefix.length)

    private fun Any?.argument(): String = this?.toString() ?: ""

    /** Integer.parseInt as the parsers used it (no trimming), or null. */
    private fun strictInt(text: String): Int? = try {
        Integer.parseInt(text)
    } catch (e: NumberFormatException) {
        Log.w(TAG, "Bad number '$text'")
        null
    }

    private fun strictId(text: String): Byte? = strictInt(text)?.toByte()

    /** A player id from a command's argument, or null when it is missing or not a number. */
    private fun lenientId(text: String): Byte? = if (text.isEmpty()) null else strictInt(text.trim())?.toByte()

    private fun teamSizes(text: String): List<Int>? = try {
        text.split(",").dropLastWhile { it.isEmpty() }.map { Integer.parseInt(it.trim()) } // as String.split did
    } catch (e: NumberFormatException) {
        Log.w(TAG, "Bad team sizes '$text'")
        null
    }
}
