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
import com.simplecoil.simplecoil.data.model.PlayerSettings
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.network.ClientMessage
import com.simplecoil.simplecoil.network.EventBus
import com.simplecoil.simplecoil.network.ServerEvent
import com.simplecoil.simplecoil.network.WireCodec
import com.simplecoil.simplecoil.network.WireKeys
import com.simplecoil.simplecoil.network.WireProtocol
import com.simplecoil.simplecoil.network.client.ClientOutbox
import com.simplecoil.simplecoil.network.client.GameClient
import com.simplecoil.simplecoil.network.server.ClockState
import com.simplecoil.simplecoil.network.server.Connection
import com.simplecoil.simplecoil.network.server.GameServer
import com.simplecoil.simplecoil.network.server.SavedServerSettings
import com.simplecoil.simplecoil.network.server.SessionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TcpServer's join, drop, rejoin and end-of-game paths over real sockets on 127.0.0.1 (reconnect
 * design step 2). These pin what the server does today, defects included, so the roster
 * extraction that follows can be checked against them.
 *
 * A dropped socket is only noticed by the 3.5 s read timeout, so the drop tests are slow.
 */
class TcpServerLoopbackTest {

    private val settings = GameSettingsRepository()
    private val gameState = GameStateRepository()
    private val players = PlayerRepository(settings, gameState)
    private lateinit var server: GameServer
    private val clients = mutableListOf<LoopbackClient>()

    /** The server's events, as published (collected on the publishing thread). */
    private val events = mutableListOf<ServerEvent>()
    private val eventScope = CoroutineScope(Job() + Dispatchers.Unconfined)

    /** Added to the server's clock: time jumps forward without waiting for it. */
    @Volatile
    private var clockOffset = 0L

    @Before
    fun setUp() {
        settings.teamMode = TeamMode.TWO_TEAMS
        settings.setAllowJoinInProgress(true)
        LoopbackClient.awaitPortFree()
        val bus = EventBus<ServerEvent>()
        eventScope.launch(start = CoroutineStart.UNDISPATCHED) { bus.events.collect { synchronized(events) { events += it } } }
        server = GameServer(settings, players, gameState, bus, clock = { System.nanoTime() / 1_000_000 + clockOffset })
        server.start()
        awaitTrue(5000, "server socket") { server.isAccepting }
    }

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        server.stop()
        server.shutdown()
        eventScope.cancel()
        LoopbackClient.awaitPortFree()
    }

    private fun connect(): LoopbackClient = LoopbackClient().also { clients += it }

    /** Connects and says hello; returns the client and the id the server assigned. */
    private fun join(name: String, mac: String?, requestedId: Int = 0): Pair<LoopbackClient, Int> {
        val client = connect()
        client.hello(requestedId, name, mac)
        val id = client.awaitMesg(WireProtocol.NETMSG_SERVERREPLY).substring(WireProtocol.NETMSG_SERVERREPLY.length).toInt()
        return client to id
    }

    private fun score(id: Int) = server.score(id.toByte())

    /** What the operator's player settings dialog does for the admin switch. */
    private fun makeAdmin(id: Int) {
        val settings = PlayerSettings()
        settings.isAdmin = true
        players.playerSettings[id.toByte()] = settings
    }

    private fun awaitDisconnected(id: Int) =
        awaitTrue(6000, "player $id to show as disconnected") { score(id)?.connected == false }

    // ------------------------------------------------------------------------------------------
    // Lobby
    // ------------------------------------------------------------------------------------------

    @Test
    fun blasterTypeAndGrenade_reachEveryonesScoreboard_andTheServerScreen() {
        val (a, idA) = join("Alpha", "AA:00")
        val (b, idB) = join("Bravo", "BB:00")
        a.json(org.json.JSONObject().put(WireKeys.PLAYERID, idA).put(WireKeys.BLASTER_TYPE, 1))
        b.json(org.json.JSONObject().put(WireKeys.PLAYERID, idB).put(WireKeys.PAIRED_GRENADE_ID, 3))
        awaitTrue(3000, "rifle and grenade in B's player data") {
            val rows = b.lastJson(WireKeys.PLAYERDATA)?.getJSONArray(WireKeys.PLAYERDATA) ?: return@awaitTrue false
            val byId = (0 until rows.length()).associate { rows.getJSONObject(it).let { r -> r.getInt(WireKeys.PLAYERID) to r } }
            byId[idA]?.optInt(WireKeys.BLASTER_TYPE) == 1 && byId[idB]?.optBoolean(WireKeys.HAS_GRENADE) == true
        }
        awaitTrue(3000, "the server screen's rows") {
            val rows = server.snapshot.value.players.associateBy { it.playerId.toInt() }
            rows[idA]?.blasterType == 1 && rows[idB]?.hasGrenade == true
        }
    }

    @Test
    fun lobbyJoin_autoSortsIntoTeams_andRepliesWithIdAndGameInfo() {
        val (a, idA) = join("Alpha", "AA:00")
        val (b, idB) = join("Bravo", "BB:00")
        assertEquals(1, idA)
        assertEquals(17, idB) // the second player goes to team 2
        val info = b.awaitJson(WireKeys.PLAYERS)
        assertEquals(GameStateRepository.GAME_STATE_NONE, info.getInt(WireKeys.GAMESTATE))
        assertFalse(info.has("dedicatedserver")) // every server is dedicated (v07)
        assertEquals(SessionPhase.LOBBY.wireValue, info.getInt(WireKeys.SESSIONPHASE))
        assertFalse(info.has(WireKeys.GAMEID))
        assertTrue(info.has(WireKeys.PLAYERGAMEUPDATE)) // the joiner gets its own update
        awaitTrue { score(idA)?.connected == true && score(idB)?.connected == true }
        assertEquals("Alpha", players.getPlayerName(idA.toByte()))
        assertEquals(idB.toByte(), players.getPlayerIdByMac("BB:00"))
        a.close()
    }

    @Test
    fun lobbyJoin_honorsAFreeRequestedId_andReassignsATakenOne() {
        val (_, idA) = join("Alpha", "AA:00", requestedId = 5)
        assertEquals(5, idA)
        val (_, idB) = join("Bravo", "BB:00", requestedId = 5)
        assertEquals(17, idB)
    }

    @Test
    fun lobbyJoin_renamesADuplicateName() {
        join("Alpha", "AA:00")
        val (_, idB) = join("alpha", "BB:00")
        val name = players.getPlayerName(idB.toByte())
        assertFalse(name.equals("alpha", ignoreCase = true))
        assertTrue(name.isNotEmpty())
    }

    @Test
    fun lobbyLeave_removesThePlayer() {
        val (a, idA) = join("Alpha", "AA:00")
        awaitTrue { score(idA) != null }
        a.mesg(WireProtocol.NETMSG_LEAVE)
        awaitTrue { score(idA) == null }
        assertNull(players.teamIPMap[idA.toByte()])
    }

    private fun eventsSoFar(): List<ServerEvent> = synchronized(events) { events.toList() }

    /** The events other than PlayerDataChanged, which follows nearly every change, and the clock's. */
    private fun sessionEvents() = eventsSoFar().filter { it !is ServerEvent.PlayerDataChanged && it !is ServerEvent.ClockChanged }

    @Test
    fun serverEvents_followTheSession() {
        val (a, idA) = join("Alpha", "AA:00")
        join("Bravo", "BB:00")
        awaitTrue { sessionEvents().size == 2 }
        assertTrue(server.startGame())
        server.endGame()
        awaitTrue(what = "game end") { ServerEvent.GameEnded in sessionEvents() }
        // the final scores, as sent to the clients
        val data = eventsSoFar().filterIsInstance<ServerEvent.PlayerDataChanged>().mapNotNull { it.json }
        assertTrue(data.isNotEmpty() && data.last().contains(WireKeys.PLAYERDATA))
        assertTrue(server.resetSession())
        a.mesg(WireProtocol.NETMSG_LEAVE)
        awaitTrue { score(idA) == null && ServerEvent.PlayerLeft in sessionEvents() }
        assertEquals(listOf(ServerEvent.PlayerJoined, ServerEvent.PlayerJoined, ServerEvent.GameStarted,
            ServerEvent.GameEnded, ServerEvent.SessionReset, ServerEvent.PlayerLeft), sessionEvents())
    }

    @Test
    fun timeLimit_theServerEndsTheGame_andTellsTheClients() {
        settings.gameLimit = GameSettingsRepository.GAME_LIMIT_TIME
        settings.timeLimit = 1
        settings.startTime = 5
        try {
            val (a, _) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00").first()
            awaitTrue { server.clockState is ClockState.Counting }
            awaitTrue { gameState.serverGameTimeRemaining in 60..65 }
            clockOffset += 65_000
            a.awaitMesg(WireProtocol.NETMSG_ENDGAME)
            awaitTrue { server.clockState == ClockState.Idle }
            assertEquals(SessionPhase.POST_GAME, server.sessionPhase)
            assertEquals(0, gameState.serverGameTimeRemaining)
            awaitTrue { ServerEvent.GameEnded in sessionEvents() }
            val events = sessionEvents()
            assertTrue(events.toString(), events.indexOf(ServerEvent.TimeExpired) in 0 until events.indexOf(ServerEvent.GameEnded))
        } finally {
            settings.gameLimit = GameSettingsRepository.GAME_LIMIT_NONE
            settings.startTime = GameSettingsRepository.START_TIME_SECONDS
        }
    }

    @Test
    fun lobbyDrop_removesThePlayerAfterTheReadTimeout() {
        val (a, idA) = join("Alpha", "AA:00")
        awaitTrue { score(idA) != null }
        a.close()
        awaitTrue(6000, "dropped lobby player to be removed") { score(idA) == null }
    }

    // ------------------------------------------------------------------------------------------
    // Running game
    // ------------------------------------------------------------------------------------------

    private fun startGameWith(vararg names: Pair<String, String?>): List<Pair<LoopbackClient, Int>> {
        val joined = names.map { (name, mac) -> join(name, mac) }
        server.startGame()
        joined.forEach { (client, _) -> client.awaitMesg(WireProtocol.NETMSG_STARTGAME) }
        return joined
    }

    @Test
    fun runningGame_eliminationCreditsShooterAndVictim() {
        val (a, b) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (clientA, idA) = a
        val (clientB, idB) = b
        clientA.mesg(WireProtocol.NETMSG_ELIMINATED + idB)
        awaitTrue { score(idA)?.eliminated == 1 && score(idB)?.points == 1 }
        assertEquals(WireProtocol.NETMSG_ELIMINATED + idA, clientB.awaitMesg(WireProtocol.NETMSG_ELIMINATED))
    }

    @Test
    fun scoreMatrix_goesOnlyToTheRequester_withWhoEliminatedWhom() {
        val (a, b) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (clientA, idA) = a
        val (clientB, idB) = b
        clientA.mesg(WireProtocol.NETMSG_ELIMINATED + idB)
        clientB.awaitMesg(WireProtocol.NETMSG_ELIMINATED)
        clientB.mesg(WireProtocol.NETMSG_SCOREMATRIXREQUEST)
        val matrix = clientB.awaitJson(WireKeys.SCOREMATRIX).getJSONObject(WireKeys.SCOREMATRIX)
        assertEquals(server.roster.gameId(), matrix.getLong(WireKeys.GAMEID))
        assertEquals(2, matrix.getJSONArray(WireKeys.PLAYERS).length())
        val cells = matrix.getJSONArray(WireKeys.CELLS)
        assertEquals(1, cells.length())
        assertEquals(listOf(idB, idA, 1), (0 until 3).map { cells.getJSONArray(0).getInt(it) })
        // A's own request is answered after B's; by then B's matrix would have reached A too.
        clientA.mesg(WireProtocol.NETMSG_PLAYERDATAREQUEST)
        clientA.awaitJson(WireKeys.PLAYERDATA)
        assertNull(clientA.lastJson(WireKeys.SCOREMATRIX))
    }

    @Test
    fun runningGame_teammateGrenadeKill_costsALife_withoutScoreOrNotifications() {
        val (a, _, c) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00", "Charlie" to "CC:00")
        val (clientA, idA) = a
        val (clientC, idC) = c
        assertEquals(players.calcNetworkTeam(idA.toByte()), players.calcNetworkTeam(idC.toByte()))
        clientA.mesg(WireProtocol.NETMSG_ELIMINATED + idC)
        clientA.mesg(WireProtocol.NETMSG_PLAYERDATAREQUEST) // answered after the elimination was handled
        clientA.awaitJson(WireKeys.PLAYERDATA)
        assertEquals(1, score(idA)!!.eliminated)
        assertEquals(0, score(idC)!!.points)
        Thread.sleep(200)
        assertTrue(clientC.mesgs(WireProtocol.NETMSG_ELIMINATED).isEmpty())
        assertTrue(clientC.mesgs(WireProtocol.NETMSG_TEAMELIMINATED).isEmpty())
    }

    @Test
    fun runningGame_dropKeepsTheScore_andTheSameMacRejoinsWithItsIdAndScore() {
        val (a, b) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (clientA, idA) = a
        val (clientB, idB) = b
        clientA.mesg(WireProtocol.NETMSG_ELIMINATED + idB)
        awaitTrue { score(idB)?.points == 1 }

        clientB.close()
        awaitDisconnected(idB)
        assertEquals(1, score(idB)!!.points)

        val (back, idBack) = join("Bravo", "BB:00")
        assertEquals(idB, idBack)
        val update = back.awaitJson(WireKeys.PLAYERGAMEUPDATE).getJSONObject(WireKeys.PLAYERGAMEUPDATE)
        assertEquals(1, update.getInt(WireKeys.PLAYERPOINTS))
        awaitTrue { score(idB)?.connected == true }
        assertEquals(1, score(idB)!!.points)
        assertEquals(1, score(idA)!!.eliminated)
    }

    @Test
    fun runningGame_newPlayerIsRefusedWhenJoinInProgressIsOff() {
        startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        settings.setAllowJoinInProgress(false)
        val late = connect()
        late.hello(0, "Charlie", "CC:00")
        assertTrue(late.awaitMesg(WireProtocol.NETMSG_ERROR).startsWith(WireProtocol.NETMSG_ERROR))
        assertTrue(late.mesgs(WireProtocol.NETMSG_SERVERREPLY).isEmpty())
    }

    @Test
    fun runningGame_newPlayerJoinsWhenJoinInProgressIsOn() {
        startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (_, idC) = join("Charlie", "CC:00")
        assertTrue(idC != 1 && idC != 17)
        assertTrue(server.roster.isCached("CC:00"))
    }

    @Test
    fun runningGame_leaveKeepsThePlayerAsDisconnected() {
        // Q12: a player who leaves on purpose stays on the scoreboard until the reset.
        val (clientA, idA) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00", "Charlie" to "CC:00")[0]
        clientA.mesg(WireProtocol.NETMSG_LEAVE)
        awaitDisconnected(idA)
        assertEquals(SessionPhase.RUNNING, server.sessionPhase)
    }

    @Test
    fun runningGame_lastButOnePlayerLeaving_endsTheGame() {
        val (a, _) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        a.first.mesg(WireProtocol.NETMSG_LEAVE)
        awaitTrue { server.sessionPhase == SessionPhase.POST_GAME }
    }

    // ------------------------------------------------------------------------------------------
    // End of game
    // ------------------------------------------------------------------------------------------

    @Test
    fun endGame_keepsDisconnectedPlayersAndTheRoster_andTheyRejoinAfterTheGame() {
        // D5: the roster used to be discarded at the end of the game.
        val (a, b) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (clientA, idA) = a
        val (clientB, idB) = b
        clientA.mesg(WireProtocol.NETMSG_ELIMINATED + idB)
        awaitTrue { score(idB)?.points == 1 }
        clientB.close()
        awaitDisconnected(idB)

        server.endGame()
        clientA.awaitMesg(WireProtocol.NETMSG_ENDGAME)
        assertEquals(SessionPhase.POST_GAME, server.sessionPhase)
        assertEquals(GameStateRepository.GAME_STATE_NONE, gameState.gameState)
        assertEquals(GameStateRepository.GAME_STATE_NONE, gameState.gameState)
        assertTrue(server.roster.isCached("BB:00"))
        assertFalse(score(idB)!!.connected)

        val (back, idBack) = join("Bravo", "BB:00")
        assertEquals(idB, idBack)
        val update = back.awaitJson(WireKeys.PLAYERGAMEUPDATE).getJSONObject(WireKeys.PLAYERGAMEUPDATE)
        assertEquals(1, update.getInt(WireKeys.PLAYERPOINTS))
        assertNotNull(score(idA))
    }

    // ------------------------------------------------------------------------------------------
    // Reset
    // ------------------------------------------------------------------------------------------

    private fun endedGameWithBravoAway(): Triple<LoopbackClient, Int, Int> {
        val (a, b) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (clientA, idA) = a
        val (clientB, idB) = b
        clientA.mesg(WireProtocol.NETMSG_ELIMINATED + idB)
        awaitTrue { score(idB)?.points == 1 }
        clientB.close()
        awaitDisconnected(idB)
        server.endGame()
        clientA.awaitMesg(WireProtocol.NETMSG_ENDGAME)
        return Triple(clientA, idA, idB)
    }

    @Test
    fun startAfterAGame_isRefusedWithAnError() {
        val (clientA, idA, _) = endedGameWithBravoAway()
        makeAdmin(idA)
        assertFalse(server.startGame())
        clientA.mesg(WireProtocol.NETMSG_STARTGAME)
        assertEquals(WireProtocol.NETMSG_ERROR + "Reset the game first", clientA.awaitMesg(WireProtocol.NETMSG_ERROR))
        assertEquals(SessionPhase.POST_GAME, server.sessionPhase)
    }

    @Test
    fun resetSession_removesTheAwayPlayer_zeroesScores_andOnlyWorksOnce() {
        val (clientA, idA, idB) = endedGameWithBravoAway()
        assertTrue(server.resetSession())
        assertFalse(server.resetIfPostGame())
        assertEquals(SessionPhase.LOBBY, server.sessionPhase)
        assertNull(score(idB))
        assertEquals(0, score(idA)!!.eliminated)
        assertFalse(server.roster.isCached("AA:00"))
        // The clients hear about it.
        awaitTrue { clientA.lastJson(WireKeys.PLAYERS)?.getInt(WireKeys.GAMESTATE) == GameStateRepository.GAME_STATE_NONE }
        assertTrue(server.startGame())
    }

    @Test
    fun resetIfPostGame_doesNothingInTheLobbyOrDuringAGame() {
        join("Alpha", "AA:00")
        join("Bravo", "BB:00")
        assertFalse(server.resetIfPostGame())
        server.startGame()
        assertFalse(server.resetIfPostGame())
        assertEquals(SessionPhase.RUNNING, server.sessionPhase)
    }

    @Test
    fun adminTeamModeAfterAGame_resetsFirst() {
        val (clientA, idA, idB) = endedGameWithBravoAway()
        makeAdmin(idA)
        clientA.mesg(WireProtocol.NETMSG_ADMIN_GAMEMODE + TeamMode.FOUR_TEAMS.wireValue)
        awaitTrue { server.sessionPhase == SessionPhase.LOBBY }
        awaitTrue { settings.teamMode == TeamMode.FOUR_TEAMS }
        assertNull(score(idB))
    }

    @Test
    fun adminTeamMode_unknownValue_isIgnored() {
        val (a, idA) = join("Alpha", "AA:00")
        makeAdmin(idA)
        a.mesg(WireProtocol.NETMSG_ADMIN_GAMEMODE + 3)
        a.mesg(WireProtocol.NETMSG_PLAYERDATAREQUEST) // processed after the mode change
        a.awaitJson(WireKeys.PLAYERDATA)
        assertEquals(TeamMode.TWO_TEAMS, settings.teamMode)
        assertArrayEquals(intArrayOf(16, 16), settings.teamLayout.teamSizes())
    }

    // ------------------------------------------------------------------------------------------
    // D14: admin commands
    // ------------------------------------------------------------------------------------------

    @Test
    fun adminCommandsFromANonAdmin_areIgnored() {
        val (a, idA) = join("Alpha", "AA:00")
        val (_, idB) = join("Bravo", "BB:00")
        a.mesg(WireProtocol.NETMSG_STARTGAME)
        a.mesg(WireProtocol.NETMSG_ADMIN_STARTGAME)
        a.mesg(WireProtocol.NETMSG_ADMIN_KICK + idB)
        a.mesg(WireProtocol.NETMSG_ADMIN_GAMEMODE + TeamMode.FREE_FOR_ALL.wireValue)
        a.mesg(WireProtocol.NETMSG_PLAYERDATAREQUEST) // processed after the others
        a.awaitJson(WireKeys.PLAYERDATA)
        assertEquals(SessionPhase.LOBBY, server.sessionPhase)
        assertNotNull(score(idB))
        assertEquals(TeamMode.TWO_TEAMS, settings.teamMode)

        // A client can't make itself an admin through its player settings either.
        val settings = org.json.JSONObject()
        settings.put(WireKeys.PLAYERSETTINGS, true)
        settings.put(WireKeys.PLAYERID, idA)
        settings.put(WireKeys.HEALTH, 20)
        settings.put(WireKeys.RELOAD_SHOTS, 30)
        settings.put(WireKeys.RELOAD_TIME, 1500)
        settings.put(WireKeys.RELOAD_ON_EMPTY, false)
        settings.put(WireKeys.SPAWN_TIME, 10)
        settings.put(WireKeys.DAMAGE, -1)
        settings.put(WireKeys.SHOT_MODE_SINGLE, true)
        settings.put(WireKeys.SHOT_MODE_BURST3, true)
        settings.put(WireKeys.SHOT_MODE_AUTO, true)
        settings.put(WireKeys.FIRING_MODE, 0)
        settings.put(WireKeys.IS_ADMIN, true)
        a.json(settings)
        awaitTrue { players.playerSettings[idA.toByte()] != null }
        assertFalse(players.playerSettings[idA.toByte()]!!.isAdmin)
    }

    @Test
    fun gameInfo_carriesThePhaseAndGameId_andPlayerDataTheConnection() {
        val (a, b) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (clientA, _) = a
        awaitTrue { clientA.lastJson(WireKeys.PLAYERS)?.getInt(WireKeys.SESSIONPHASE) == SessionPhase.RUNNING.wireValue }
        val gameId = clientA.lastJson(WireKeys.PLAYERS)!!.getLong(WireKeys.GAMEID)
        assertEquals(server.roster.gameId(), gameId)
        assertTrue(gameId != 0L)
        b.first.close()
        awaitDisconnected(b.second)
        server.endGame()
        clientA.awaitMesg(WireProtocol.NETMSG_ENDGAME)
        val players = clientA.awaitJson(WireKeys.PLAYERDATA).getJSONArray(WireKeys.PLAYERDATA)
        val bravo = (0 until players.length()).map { players.getJSONObject(it) }.first { it.getInt(WireKeys.PLAYERID) == b.second }
        assertFalse(bravo.getBoolean(WireKeys.CONNECTED))
    }

    @Test
    fun adminReset_resetsOnlyForAnAdmin() {
        val (clientA, idA, idB) = endedGameWithBravoAway()
        val before = clientA.received.size
        clientA.mesg(WireProtocol.NETMSG_ADMIN_RESET)
        clientA.mesg(WireProtocol.NETMSG_PLAYERDATAREQUEST) // answered after the reset was handled
        awaitTrue { clientA.received.drop(before).any { it.contains(WireKeys.PLAYERDATA) } }
        assertEquals(SessionPhase.POST_GAME, server.sessionPhase)
        makeAdmin(idA)
        clientA.mesg(WireProtocol.NETMSG_ADMIN_RESET)
        awaitTrue { server.sessionPhase == SessionPhase.LOBBY }
        assertNull(score(idB))
        awaitTrue { clientA.lastJson(WireKeys.PLAYERS)?.getInt(WireKeys.SESSIONPHASE) == SessionPhase.LOBBY.wireValue }
        assertFalse(clientA.lastJson(WireKeys.PLAYERS)!!.has(WireKeys.GAMEID))
    }

    @Test
    fun adminCommandsFromAnAdmin_run() {
        val (a, idA) = join("Alpha", "AA:00")
        join("Bravo", "BB:00")
        makeAdmin(idA)
        a.mesg(WireProtocol.NETMSG_ADMIN_STARTGAME)
        a.awaitMesg(WireProtocol.NETMSG_STARTGAME)
        assertEquals(SessionPhase.RUNNING, server.sessionPhase)
        a.mesg(WireProtocol.NETMSG_ADMIN_ENDGAME)
        a.awaitMesg(WireProtocol.NETMSG_ENDGAME)
        assertEquals(SessionPhase.POST_GAME, server.sessionPhase)
    }

    // ------------------------------------------------------------------------------------------
    // Reliable eliminations (§3.4, §3.5), with TcpClient's own hello and envelope
    // ------------------------------------------------------------------------------------------

    private fun LoopbackClient.reliable(outbox: ClientOutbox, rmsg: String): ClientOutbox.Entry {
        val entry = outbox.add(rmsg, outbox.gameId)
        send(WireCodec.encode(ClientMessage.Reliable(entry.rmsg, entry.seq, outbox.gameId)))
        return entry
    }

    private fun LoopbackClient.rejoin(name: String, mac: String, outbox: ClientOutbox) =
        send(WireCodec.encode(GameClient.hello(0, name, mac, true, outbox)))

    @Test
    fun reliableEliminations_lostAckAndOfflineKills_countOnceAfterTheRejoin() {
        val (a, b) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (clientA, idA) = a
        val (clientB, idB) = b
        val outbox = ClientOutbox()
        outbox.onGameId(server.roster.gameId())
        val killedByBravo = WireProtocol.NETMSG_ELIMINATED + idB

        // 1: delivered and acknowledged.
        clientA.reliable(outbox, killedByBravo)
        assertEquals(WireProtocol.NETMSG_ACK + 1, clientA.awaitMesg(WireProtocol.NETMSG_ACK))
        outbox.ack(1)
        // 2: applied, but its ack is lost with the connection (D1).
        clientA.reliable(outbox, killedByBravo)
        awaitTrue { score(idA)?.eliminated == 2 }
        clientA.close()
        awaitDisconnected(idA)
        // 3 and 4: eliminated twice while out of range.
        outbox.add(killedByBravo, outbox.gameId)
        outbox.add(killedByBravo, outbox.gameId)
        assertEquals(listOf(2, 3, 4), outbox.pending().map { it.seq })

        val back = connect()
        back.rejoin("Alpha", "AA:00", outbox)
        assertEquals(WireProtocol.NETMSG_SERVERREPLY + idA, back.awaitMesg(WireProtocol.NETMSG_SERVERREPLY))
        val update = back.awaitJson(WireKeys.PLAYERGAMEUPDATE).getJSONObject(WireKeys.PLAYERGAMEUPDATE)
        assertEquals(4, update.getInt(WireKeys.ACKSEQ))
        assertEquals(4, update.getInt(WireKeys.PLAYERELIMINATED))
        // D2: the lives the client shows equal its own count.
        assertEquals(4, outbox.reconcile(update.getInt(WireKeys.PLAYERELIMINATED), update.getInt(WireKeys.ACKSEQ)))
        assertTrue(outbox.pending().isEmpty())
        awaitTrue { score(idA)?.connected == true }
        assertEquals(4, score(idA)!!.eliminated)
        assertEquals(4, score(idB)!!.points)
        // The shooter heard about each kill once.
        awaitTrue { clientB.mesgs(WireProtocol.NETMSG_ELIMINATED).size == 4 }
        Thread.sleep(200)
        assertEquals(4, clientB.mesgs(WireProtocol.NETMSG_ELIMINATED).size)
    }

    @Test
    fun reliableReplay_afterTheGame_countsInTheFinalScore_andAnOldGamesIsDropped() {
        val (a, b) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (clientA, idA) = a
        val (clientB, idB) = b
        val outbox = ClientOutbox()
        outbox.onGameId(server.roster.gameId())
        clientA.close()
        awaitDisconnected(idA)
        outbox.add(WireProtocol.NETMSG_ELIMINATED + idB, outbox.gameId)
        server.endGame()
        clientB.awaitMesg(WireProtocol.NETMSG_ENDGAME)

        val back = connect()
        back.rejoin("Alpha", "AA:00", outbox)
        back.awaitMesg(WireProtocol.NETMSG_SERVERREPLY)
        awaitTrue { score(idA)?.connected == true }
        assertEquals(1, score(idA)!!.eliminated)
        assertEquals(1, score(idB)!!.points)
        assertEquals(SessionPhase.POST_GAME, server.sessionPhase)

        // An envelope from an older game is acknowledged and dropped.
        val stale = ClientOutbox()
        stale.onGameId(server.roster.gameId() + 1)
        back.reliable(stale, WireProtocol.NETMSG_ELIMINATED + idB)
        assertEquals(WireProtocol.NETMSG_ACK + 1, back.awaitMesg(WireProtocol.NETMSG_ACK))
        assertEquals(1, score(idB)!!.points)
    }

    @Test
    fun startGame_syncsGameStateRepository() {
        // D8: GameStateRepository always said "not running".
        startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, gameState.gameState)
    }

    @Test
    fun endGame_sendsFinalPlayerDataBeforeEndGame() {
        val (a, _) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (clientA, _) = a
        server.endGame()
        clientA.awaitMesg(WireProtocol.NETMSG_ENDGAME)
        val data = clientA.lastJson(WireKeys.PLAYERDATA)
        assertNotNull(data)
        val prefix = WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON
        val endGame = WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_MESG + WireProtocol.NETMSG_ENDGAME
        val dataIndex = clientA.received.indexOfLast { it.startsWith(prefix) && it.contains(WireKeys.PLAYERDATA) }
        assertTrue(dataIndex < clientA.received.indexOf(endGame))
    }

    // ------------------------------------------------------------------------------------------
    // D4: connection keys
    // ------------------------------------------------------------------------------------------

    @Test
    fun d4_aNewConnectionWhoseCounterMatchesARenumberedPlayer_leavesThatPlayerAlone() {
        // Players used to be re-keyed by player id when teams were rebalanced while new
        // connections were keyed by an accept counter, so the 17th connection replaced player 17
        // (D4). Connections are now keyed by connection id only.
        val (_, idA) = join("Alpha", "AA:00")
        val (b, idB) = join("Bravo", "BB:00")
        assertEquals(17, idB)
        server.rebalanceAllPlayers(false)
        awaitTrue { b.mesgs(WireProtocol.NETMSG_SERVERREPLY).size >= 2 }

        // Connections 3..17.
        repeat(15) { connect() }
        awaitTrue(3000, "17 connections") { server.roster.connectionCount() == 17 }
        assertNotNull(score(idB))
        assertTrue(score(idB)!!.connected)
        assertNotNull(score(idA))
    }

    @Test
    fun malformedFrame_isIgnored_andTheServerKeepsServingEveryone() {
        // N5: a short frame after the version prefix used to throw out of the read thread
        val (a, _) = join("Alpha", "AA:00")
        val (b, _) = join("Bravo", "BB:00")
        a.send(WireProtocol.TCPMESSAGE_PREFIX + "X")
        a.send(WireProtocol.TCPMESSAGE_PREFIX)
        b.mesg(WireProtocol.NETMSG_SHOTFIRED)
        a.awaitMesg(WireProtocol.NETMSG_SHOTFIRED)
        b.awaitMesg(WireProtocol.NETMSG_SHOTFIRED)
    }

    @Test
    fun otherProtocolVersion_getsVersionError() {
        val client = connect()
        client.send(WireProtocol.MESSAGE_PREFIX + "06" + WireProtocol.TCPPREFIX_MESG + WireProtocol.NETMSG_LEAVE)
        awaitTrue { client.received.contains(WireProtocol.NETMSG_VERSIONERROR) }
    }

    @Test
    fun hostileFrames_neverStopTheServerServingEveryone() {
        // N5: bad JSON and commands from a connection that never joined are ignored; an exception
        // while handling any frame would be logged, never thrown out of the read thread
        val (a, _) = join("Alpha", "AA:00")
        val (b, _) = join("Bravo", "BB:00")
        val hostile = listOf(
            "{",
            "[]",
            "{\"playerID\":-1,\"playername\":\"X\"}",
            "{\"playerID\":999999,\"playername\":\"X\"}",
            "{\"playerID\":\"x\",\"playername\":\"X\"}",
            "{\"playerID\":1,\"playersettings\":\"x\"}",
            "{\"playerID\":1,\"pairedgrenadeID\":999}",
            "{\"playerID\":1,\"requestteamchange\":999}",
            "{\"rmsg\":\"ELIMINATED\",\"seq\":1,\"gameid\":1}",
        )
        val c = connect()
        hostile.forEach { c.send(WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON + it) }
        hostile.forEach { a.send(WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON + it) }
        listOf(WireProtocol.NETMSG_HIT + "1", WireProtocol.NETMSG_OUT + "1", WireProtocol.NETMSG_TEAMELIMINATED, WireProtocol.NETMSG_PLAYERDATAREQUEST,
            WireProtocol.NETMSG_ELIMINATED + "1", WireProtocol.NETMSG_ELIMINATED, WireProtocol.NETMSG_ELIMINATED + "x", WireProtocol.NETMSG_LEAVE).forEach { c.mesg(it) }
        b.mesg(WireProtocol.NETMSG_SHOTFIRED)
        b.awaitMesg(WireProtocol.NETMSG_SHOTFIRED)
    }

    // ------------------------------------------------------------------------------------------
    // Writer queues (network design §5.4)
    // ------------------------------------------------------------------------------------------

    @Test
    fun messagesToOneClient_arriveInEachSendersOrder() {
        // N1: every send used to start its own thread, so two sends could arrive swapped.
        val (a, _) = join("Alpha", "AA:00")
        // Both bursts fit in the writer's queue even if its thread doesn't run until they're done:
        // a full queue closes the connection (Q1), which is what a stalled phone should get.
        val perSender = (Connection.MAX_QUEUED_FRAMES - 16) / 2
        val senders = listOf("x", "y").map { name ->
            Thread { repeat(perSender) { server.sendToAll("$name$it") } }
        }
        senders.forEach { it.start() }
        senders.forEach { it.join() }
        awaitTrue { a.received.count { it.startsWith("x") || it.startsWith("y") } == 2 * perSender }
        for (name in listOf("x", "y")) {
            assertEquals((0 until perSender).map { "$name$it" }, a.received.filter { it.startsWith(name) })
        }
    }

    @Test
    fun aClientThatNeverReads_isClosedWhenItsQueueFills_andNobodyElseWaits() {
        // N2: a blocking write to a client in a dead zone used to stall the whole server.
        val (a, idA) = join("Alpha", "AA:00")
        val stuck = java.net.Socket("127.0.0.1", WireProtocol.TCP_SERVER_PORT) // never reads
        try {
            awaitTrue { server.roster.connectionCount() == 2 }
            // Batches the reading client keeps up with; the stuck one's socket buffers, then its queue, fill up.
            val big = "z".repeat(60_000)
            var sent = 0
            while (server.roster.connectionCount() == 2 && sent < 2_000) {
                val startedAt = System.nanoTime()
                repeat(100) { server.sendToAll(big) }
                assertTrue("sending blocked", (System.nanoTime() - startedAt) / 1_000_000 < 1_000)
                sent += 100
                awaitTrue(5_000, "batch to reach the reading client") { a.received.count { it == big } == sent }
            }
            awaitTrue(5_000, "the stuck connection to be dropped") { server.roster.connectionCount() == 1 }
            assertNotNull(score(idA))
            a.mesg(WireProtocol.NETMSG_SHOTFIRED)
            a.awaitMesg(WireProtocol.NETMSG_SHOTFIRED)
        } finally {
            stuck.close()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Readers and the dispatcher (network design §5.3, §5.5)
    // ------------------------------------------------------------------------------------------

    /** A raw client that says hello, then sends half of a frame and goes silent. */
    private fun joinAndStallMidFrame(name: String, mac: String): java.net.Socket {
        val socket = java.net.Socket("127.0.0.1", WireProtocol.TCP_SERVER_PORT)
        val out = java.io.DataOutputStream(socket.getOutputStream())
        val hello = org.json.JSONObject().put(WireKeys.PLAYERID, 0).put(WireKeys.PLAYERNAME, name).put(WireKeys.BLASTER_MAC, mac)
        out.writeUTF(WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON + hello)
        out.write(byteArrayOf(0, 100))
        out.write("SimpleCoil".toByteArray())
        out.flush()
        return socket
    }

    @Test
    fun aPeerStalledMidFrame_isDroppedAfterTheSilenceLimit_andNobodyElseWaits() {
        // N3: the one read loop used to block in readUTF until TCP gave up.
        val (a, _) = join("Alpha", "AA:00")
        val stalled = joinAndStallMidFrame("Stalled", "SS:00")
        try {
            val stalledAt = System.nanoTime()
            awaitTrue { server.roster.connectionCount() == 2 && server.roster.records().size == 2 }
            a.mesg(WireProtocol.NETMSG_SHOTFIRED)
            a.awaitMesg(WireProtocol.NETMSG_SHOTFIRED, 500)
            awaitTrue(6_000, "the stalled peer to be dropped") { server.roster.connectionCount() == 1 }
            val droppedAfter = (System.nanoTime() - stalledAt) / 1_000_000
            assertTrue("dropped after $droppedAfter ms", droppedAfter in 3_300..3_900)
        } finally {
            stalled.close()
        }
    }

    @Test
    fun thirtyTwoPlayers_oneStalledMidFrame_everyEliminationIsAppliedAndAcknowledgedQuickly() {
        val joined = (1..31).map { join("P$it", "00:$it") }
        val stalled = joinAndStallMidFrame("Stalled", "SS:00")
        try {
            awaitTrue { server.roster.records().size == 32 }
            assertTrue(server.startGame())
            joined.forEach { (client, _) -> client.awaitMesg(WireProtocol.NETMSG_STARTGAME) }
            val gameId = server.roster.gameId()
            joined.forEachIndexed { index, (client, _) ->
                val shooter = joined[(index + 1) % joined.size].second
                val outbox = ClientOutbox()
                outbox.onGameId(gameId)
                val sentAt = System.nanoTime()
                client.reliable(outbox, WireProtocol.NETMSG_ELIMINATED + shooter)
                client.awaitMesg(WireProtocol.NETMSG_ACK, 500)
                assertTrue((System.nanoTime() - sentAt) / 1_000_000 < 500)
            }
            joined.forEach { (_, id) -> assertEquals(1, score(id)!!.eliminated) }
        } finally {
            stalled.close()
        }
    }

    @Test
    fun aStopAndAnImmediateStart_servesAgain() {
        // N6: the old accept loop held the port for up to a second, so the new server failed to bind.
        server.stop()
        server.start()
        awaitTrue(1_000, "server socket") { server.isAccepting }
        val (a, _) = join("Alpha", "AA:00")
        a.mesg(WireProtocol.NETMSG_SHOTFIRED)
        a.awaitMesg(WireProtocol.NETMSG_SHOTFIRED)
    }

    @Test
    fun applyPlayerSettings_switchTeamAndApplyToTeam_copiesToTheNewTeammates() {
        // S11: the move used to run on its own thread after the copy had already read the old id
        val (_, idA) = join("Alpha", "AA:00")
        val (_, idB) = join("Bravo", "BB:00")
        val (_, idC) = join("Charlie", "CC:00")
        val layout = settings.teamLayout
        assertEquals(layout.teamOf(idA), layout.teamOf(idC))
        assertTrue(server.applyPlayerSettings(idA.toByte(), { it.health = 77 }, applyAll = false, applyTeam = true,
            targetTeam = layout.teamOf(idB)))
        val newIdA = players.getPlayerIdByMac("AA:00")
        assertEquals(layout.teamOf(idB), layout.teamOf(newIdA))
        assertEquals(77, players.playerSettings[newIdA]!!.health)
        assertEquals(77, players.playerSettings[idB.toByte()]!!.health)
        assertTrue((players.playerSettings[idC.toByte()]?.health ?: 0) != 77)
    }

    @Test
    fun applyPlayerSettings_toAFullTeam_storesTheSettingsWhereThePlayerIs() {
        settings.setTeamSizes(intArrayOf(31, 1))
        val joined = listOf(join("Alpha", "AA:00").second, join("Bravo", "BB:00").second)
        val layout = settings.teamLayout
        val onSmallTeam = joined.single { layout.teamOf(it) == 2 } // the one slot on team 2 is taken
        val mover = joined.single { it != onSmallTeam }
        assertFalse(server.applyPlayerSettings(mover.toByte(), { it.health = 55 }, applyAll = false, applyTeam = false, targetTeam = 2))
        assertEquals(1, layout.teamOf(mover))
        assertEquals(55, players.playerSettings[mover.toByte()]!!.health)
    }

    @Test
    fun applyPlayerSettings_toAll_isGivenToPlayersWhoJoinLater() {
        val (_, idA) = join("Alpha", "AA:00")
        server.applyPlayerSettings(idA.toByte(), { it.health = 66 }, applyAll = true, applyTeam = false, targetTeam = 0)
        players.playerSettings.clear() // nothing left in the empty slots to inherit
        val (b, idB) = join("Bravo", "BB:00")
        assertEquals(66, players.playerSettings[idB.toByte()]!!.health)
        val sent = b.awaitJson(WireKeys.PLAYERSETTINGS).getJSONArray(WireKeys.PLAYERSETTINGS)
        assertTrue((0 until sent.length()).any { sent.getJSONObject(it).getInt(WireKeys.PLAYERID) == idB })
    }

    @Test
    fun applyPlayerSettings_toTeam_isGivenToPlayersWhoJoinThatTeamLater() {
        val (_, idA) = join("Alpha", "AA:00")
        server.applyPlayerSettings(idA.toByte(), { it.health = 44 }, applyAll = false, applyTeam = true, targetTeam = 0)
        players.playerSettings.clear()
        val layout = settings.teamLayout
        val (_, idB) = join("Bravo", "BB:00")
        val (_, idC) = join("Charlie", "CC:00")
        assertEquals(layout.teamOf(idA), layout.teamOf(idC))
        assertEquals(44, players.playerSettings[idC.toByte()]!!.health)
        assertTrue((players.playerSettings[idB.toByte()]?.health ?: 0) != 44)
    }

    @Test
    fun applyPlayerSettings_toTeam_isGivenToAPlayerWhoSwitchesToThatTeam() {
        settings.setAllowChooseTeams(true)
        val (_, idA) = join("Alpha", "AA:00")
        val (b, idB) = join("Bravo", "BB:00")
        val layout = settings.teamLayout
        val teamA = layout.teamOf(idA)
        server.applyPlayerSettings(idA.toByte(), { it.health = 33 }, applyAll = false, applyTeam = true, targetTeam = 0)
        server.applyPlayerSettings(idB.toByte(), { it.health = 88 }, applyAll = false, applyTeam = false, targetTeam = 0)
        b.send(NetworkSessionGateway.teamChangeRequest(idB.toByte(), teamA))
        awaitTrue { layout.teamOf(players.getPlayerIdByMac("BB:00")) == teamA }
        awaitTrue { players.playerSettings[players.getPlayerIdByMac("BB:00")]?.health == 33 }
    }

    @Test
    fun applyPlayerSettings_toAll_replacesTheTeams() {
        val (_, idA) = join("Alpha", "AA:00")
        server.applyPlayerSettings(idA.toByte(), { it.health = 44 }, applyAll = false, applyTeam = true, targetTeam = 0)
        server.applyPlayerSettings(idA.toByte(), { it.health = 22 }, applyAll = true, applyTeam = false, targetTeam = 0)
        players.playerSettings.clear()
        val (_, idB) = join("Bravo", "BB:00")
        val (_, idC) = join("Charlie", "CC:00") // on Alpha's team
        assertEquals(22, players.playerSettings[idB.toByte()]!!.health)
        assertEquals(22, players.playerSettings[idC.toByte()]!!.health)
    }

    // ------------------------------------------------------------------------------------------
    // The snapshot and the operator's settings (dedicated server design §5.2, §6.1)
    // ------------------------------------------------------------------------------------------

    @Test
    fun snapshot_followsJoinsTheGameAndTheClock() {
        val (a, idA) = join("Alpha", "AA:00")
        join("Bravo", "BB:00")
        awaitTrue { server.snapshot.value.players.size == 2 }
        val lobby = server.snapshot.value
        assertEquals(SessionPhase.LOBBY, lobby.phase)
        assertEquals(2, lobby.playerCount)
        assertEquals("Alpha", lobby.players.single { it.playerId.toInt() == idA }.name)
        assertEquals(ClockState.Idle, lobby.clock)
        assertTrue(server.startGame())
        assertEquals(SessionPhase.RUNNING, server.snapshot.value.phase)
        assertTrue(server.snapshot.value.clock is ClockState.Starting)
        a.close()
        awaitTrue(6000, "Alpha to show as disconnected") {
            server.snapshot.value.players.single { it.playerId.toInt() == idA }.connected.not()
        }
    }

    @Test
    fun loadSettings_appliesTheSavedSettings_andTeammateGpsIsOffInFreeForAll() {
        server.loadSettings(SavedServerSettings(TeamMode.FREE_FOR_ALL, timeLimit = 10, livesLimit = 0, scoreLimit = 5,
            startTimeSeconds = 7, gpsMode = GameSettingsRepository.GPS_TEAMMATE, allowChooseTeams = false))
        awaitTrue { server.snapshot.value.settings.startTimeSeconds == 7L }
        val saved = server.snapshot.value.settings
        assertEquals(TeamMode.FREE_FOR_ALL, saved.teamLayout.mode)
        assertEquals(GameSettingsRepository.GAME_LIMIT_TIME or GameSettingsRepository.GAME_LIMIT_SCORE, saved.gameLimit)
        assertEquals(10, saved.timeLimit)
        assertEquals(5, saved.scoreLimit)
        assertEquals(GameSettingsRepository.GPS_TEAMMATE, saved.gpsMode)
        assertFalse(saved.useGps)
        assertFalse(saved.allowChooseTeams)
        assertFalse(saved.allowJoinInProgress)
    }

    @Test
    fun changeTeamMode_afterAGame_resetsFirst_andUsesTheDefaultSizes() {
        val (_, _, idB) = endedGameWithBravoAway()
        settings.setTeamSizes(intArrayOf(10, 22))
        assertTrue(server.changeTeamMode(TeamMode.TWO_TEAMS))
        assertEquals(SessionPhase.LOBBY, server.snapshot.value.phase)
        assertArrayEquals(intArrayOf(16, 16), server.snapshot.value.settings.teamLayout.teamSizes())
        assertNull(score(idB))
        assertFalse(server.changeTeamMode(TeamMode.FOUR_TEAMS)) // nothing left to reset
    }

    @Test
    fun changeLimits_inTheLobby_tellsTheClients() {
        val (a, _) = join("Alpha", "AA:00")
        a.awaitJson(WireKeys.PLAYERS)
        assertFalse(server.changeLimits(GameSettingsRepository.GAME_LIMIT_TIME, 12, 0, 0))
        assertEquals(12, server.snapshot.value.settings.timeLimit)
        awaitTrue { a.lastJson(WireKeys.PLAYERS)?.optJSONObject(WireKeys.LIMITS)?.optInt(WireKeys.TIMELIMIT) == 12 }
    }
}
