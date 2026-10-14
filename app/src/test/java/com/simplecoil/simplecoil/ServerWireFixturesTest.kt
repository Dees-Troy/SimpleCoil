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

package com.simplecoil.simplecoil

import com.simplecoil.simplecoil.LoopbackClient.Companion.awaitTrue
import com.simplecoil.simplecoil.LoopbackClient.Companion.awaitValue
import com.simplecoil.simplecoil.data.model.PlayerSettings
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.network.WireFixtures
import com.simplecoil.simplecoil.network.WireKeys
import com.simplecoil.simplecoil.network.WireProtocol
import com.simplecoil.simplecoil.network.client.ClientOutbox
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Every server → client frame against its golden fixture (network design §7.2), as a real
 * server sends it over 127.0.0.1 to players with fixed names. The game id is random, so a
 * frame's id is replaced by the fixture's placeholder before comparing.
 */
class ServerWireFixturesTest {

    private val settings = GameSettingsRepository.getInstance()
    private val players = PlayerRepository.getInstance()
    private val gameState = GameStateRepository.getInstance()
    private lateinit var server: TcpServer
    private val clients = mutableListOf<LoopbackClient>()

    private val jsonHeader = WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON
    private val mesgHeader = WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_MESG

    @Before
    fun setUp() {
        gameState.gameState = GameStateRepository.GAME_STATE_NONE
        gameState.serverGameTimeRemaining = 0
        settings.teamMode = TeamMode.TWO_TEAMS
        settings.resetDefaultTeamSizes()
        settings.gameLimit = GameSettingsRepository.GAME_LIMIT_NONE
        settings.setUseGPS(false)
        settings.setGpsMode(GameSettingsRepository.GPS_DISABLED)
        settings.setAllowJoinInProgress(true)
        settings.setAllowPlayerSettings(false)
        settings.setOnlyServerSettings(false)
        settings.setAllowChooseTeams(true)
        settings.setStartTime(5)
        players.playerID = 0
        players.clearAllPlayers()
        LoopbackClient.awaitPortFree()
        server = TcpServer()
        server.startTcpServer()
        awaitTrue(5000, "server socket") { server.isAccepting }
    }

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        server.stopTcpServer()
        LoopbackClient.awaitPortFree()
        gameState.gameState = GameStateRepository.GAME_STATE_NONE
        settings.gameLimit = GameSettingsRepository.GAME_LIMIT_NONE
        settings.setUseGPS(false)
        players.clearAllPlayers()
    }

    private fun connect(): LoopbackClient = LoopbackClient().also { clients += it }

    private fun join(name: String, mac: String): LoopbackClient {
        val client = connect()
        client.hello(0, name, mac)
        client.awaitMesg(WireProtocol.NETMSG_SERVERREPLY)
        return client
    }

    /** The JSON frames [client] received that have [key] (and aren't game info, unless that is the key), in order. */
    private fun jsonFrames(client: LoopbackClient, key: String): List<String> = client.received.filter {
        if (!it.startsWith(jsonHeader)) return@filter false
        val json = JSONObject(it.removePrefix(jsonHeader))
        json.has(key) && (key == WireKeys.PLAYERS || !json.has(WireKeys.PLAYERS))
    }

    /** The [index]th JSON frame with [key] that [client] receives, waiting for it. */
    private fun awaitJsonFrame(client: LoopbackClient, key: String, index: Int = 0): String =
        awaitValue(5000, "frame $index with $key") { jsonFrames(client, key).getOrNull(index) }

    /** [frame] with its game id replaced by [gameId] (the fixture's own is a placeholder). */
    private fun withGameId(frame: String, gameId: Long): String {
        val json = JSONObject(frame.removePrefix(jsonHeader))
        if (json.has(WireKeys.GAMEID)) json.put(WireKeys.GAMEID, gameId)
        return jsonHeader + json
    }

    private fun assertGameInfo(name: String, frame: String) {
        WireFixtures.assertFrame(name, withGameId(frame, FIXTURE_GAME_ID))
    }

    @Test
    fun gameInfo_lobby_withAndWithoutThePlayersOwnUpdate() {
        val alpha = join("Alpha", "AA:00")
        val bravo = join("Bravo", "BB:00")
        // Bravo's hello: Bravo gets its own update, everyone else the plain game info
        assertGameInfo("server/game_info_lobby_joiner.frame", awaitJsonFrame(bravo, WireKeys.PLAYERS))
        assertGameInfo("server/game_info_lobby.frame", awaitJsonFrame(alpha, WireKeys.PLAYERS, 1))
    }

    @Test
    fun gameInfo_runningAndPostGame_withLimitsAndGps_andGpsAndPlayerData() {
        settings.gameLimit = GameSettingsRepository.GAME_LIMIT_TIME or GameSettingsRepository.GAME_LIMIT_LIVES or
            GameSettingsRepository.GAME_LIMIT_SCORE
        settings.setTimeLimit(10)
        settings.setLivesLimit(3)
        settings.setScoreLimit(25)
        settings.setUseGPS(true)
        settings.setGpsMode(GameSettingsRepository.GPS_ALL)
        val alpha = join("Alpha", "AA:00")
        val bravo = join("Bravo", "BB:00")
        players.playerSettings[1] = PlayerSettings().apply { overrideLives = true; lives = 2 }
        val infosBefore = jsonFrames(bravo, WireKeys.PLAYERS).size
        server.startGame()
        bravo.awaitMesg(WireProtocol.NETMSG_STARTGAME)
        assertGameInfo("server/game_info_running.frame", awaitJsonFrame(bravo, WireKeys.PLAYERS, infosBefore))

        // The first relayed position after a hello is a full update; the next one only what changed.
        alpha.json(JSONObject().put(WireKeys.GPSLONGITUDE, -74.25).put(WireKeys.GPSLATITUDE, 40.5))
        WireFixtures.assertFrame("server/gps_full.frame", awaitJsonFrame(bravo, WireKeys.GPSUPDATE))
        alpha.json(JSONObject().put(WireKeys.GPSLONGITUDE, -74.5).put(WireKeys.GPSLATITUDE, 40.75))
        WireFixtures.assertFrame("server/gps_delta.frame", awaitJsonFrame(bravo, WireKeys.GPSUPDATE, 1))

        alpha.mesg(WireProtocol.NETMSG_ELIMINATED + 17) // Alpha out, Bravo scores: the final scores aren't all 0
        bravo.awaitMesg(WireProtocol.NETMSG_ELIMINATED)
        val infosBeforeEnd = jsonFrames(bravo, WireKeys.PLAYERS).size
        server.endGame()
        bravo.awaitMesg(WireProtocol.NETMSG_ENDGAME)
        WireFixtures.assertFrame("server/player_data.frame", awaitJsonFrame(bravo, WireKeys.PLAYERDATA))
        assertGameInfo("server/game_info_post_game.frame", awaitJsonFrame(bravo, WireKeys.PLAYERS, infosBeforeEnd))
    }

    @Test
    fun playerSettings_andGrenadePairings() {
        val alpha = join("Alpha", "AA:00")
        val bravo = join("Bravo", "BB:00")
        players.playerSettings[1] = PlayerSettings().apply { overrideLives = true; lives = 2; isAdmin = true }
        players.playerSettings[17] = PlayerSettings().apply {
            health = 30; shots = 12; reloadTime = 2500; reloadOnEmpty = true; manualRespawn = true; spawnTime = 15
            respawnShieldTime = 4; damage = -3; allowShotModeAuto = false; firingMode = 1; recoilSetting = 2
        }
        server.sendPlayerSettings(TcpServer.SEND_ALL, false, true)
        WireFixtures.assertFrame("server/player_settings.frame", awaitJsonFrame(bravo, WireKeys.ALLOWPLAYERSETTINGS))

        alpha.json(JSONObject().put(WireKeys.PLAYERID, 1).put(WireKeys.PAIRED_GRENADE_ID, 3))
        WireFixtures.assertFrame("server/grenade_pairings.frame", awaitJsonFrame(bravo, WireKeys.GRENADE_PAIRINGS))
    }

    @Test
    fun commands() {
        val alpha = join("Alpha", "AA:00") // 1, team 1
        val bravo = join("Bravo", "BB:00") // 17, team 2
        val charlie = join("Charlie", "CC:00") // 2, team 1
        server.startGame()
        charlie.awaitMesg(WireProtocol.NETMSG_STARTGAME)
        alpha.mesg(WireProtocol.NETMSG_HIT + 17)
        bravo.awaitMesg(WireProtocol.NETMSG_HIT)
        alpha.mesg(WireProtocol.NETMSG_OUT + 17)
        bravo.awaitMesg(WireProtocol.NETMSG_OUT)
        bravo.mesg(WireProtocol.NETMSG_SHOTFIRED)
        charlie.awaitMesg(WireProtocol.NETMSG_SHOTFIRED)
        val outbox = ClientOutbox()
        outbox.onGameId(server.roster.gameId())
        alpha.json(TcpClient.reliableJson(outbox.add(WireProtocol.NETMSG_ELIMINATED + 17, outbox.gameId), outbox.gameId))
        alpha.awaitMesg(WireProtocol.NETMSG_ACK)
        bravo.awaitMesg(WireProtocol.NETMSG_ELIMINATED)
        alpha.mesg(WireProtocol.NETMSG_TEAMELIMINATED)
        charlie.awaitMesg(WireProtocol.NETMSG_TEAMELIMINATED)
        val other = connect()
        other.send(WireProtocol.MESSAGE_PREFIX + "06" + WireProtocol.TCPPREFIX_MESG + WireProtocol.NETMSG_LEAVE)
        awaitTrue { WireProtocol.NETMSG_VERSIONERROR in other.received }
        server.kickPlayer(2)
        charlie.awaitMesg(WireProtocol.NETMSG_SERVERCANCEL)
        server.endGame()
        alpha.awaitMesg(WireProtocol.NETMSG_ENDGAME)
        players.playerSettings[1] = PlayerSettings().apply { isAdmin = true }
        alpha.mesg(WireProtocol.NETMSG_STARTGAME)
        alpha.awaitMesg(WireProtocol.NETMSG_ERROR)

        val received = listOf(alpha, bravo, charlie, other).flatMap { it.received }
            .filter { it.startsWith(mesgHeader) || it == WireProtocol.NETMSG_VERSIONERROR }
        WireFixtures.assertCommands("server/commands.txt", received.distinct().sorted())
    }

    private companion object {
        /** The game id the fixtures carry; each test's real one stands in for it. */
        const val FIXTURE_GAME_ID = 418873301L
    }
}
