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
import com.simplecoil.simplecoil.network.client.ClientOutbox
import com.simplecoil.simplecoil.network.server.SessionPhase
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

    private lateinit var server: TcpServer
    private val clients = mutableListOf<LoopbackClient>()

    @Before
    fun setUp() {
        GameStateRepository.getInstance().gameState = GameStateRepository.GAME_STATE_NONE
        GameSettingsRepository.getInstance().teamMode = TeamMode.TWO_TEAMS
        GameSettingsRepository.getInstance().gameLimit = GameSettingsRepository.GAME_LIMIT_NONE
        GameSettingsRepository.getInstance().isUseGPS = false
        PlayerRepository.getInstance().playerID = 0
        GameSettingsRepository.getInstance().resetDefaultTeamSizes()
        GameSettingsRepository.getInstance().setAllowJoinInProgress(true)
        GameStateRepository.getInstance().setGameState(GameStateRepository.GAME_STATE_NONE)
        PlayerRepository.getInstance().setPlayerID(0)
        PlayerRepository.getInstance().clearAllPlayers()
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
        Thread.sleep(200) // the client thread sees keepListening = false within one poll
        GameStateRepository.getInstance().gameState = GameStateRepository.GAME_STATE_NONE
        PlayerRepository.getInstance().clearAllPlayers()
    }

    private fun connect(): LoopbackClient = LoopbackClient().also { clients += it }

    /** Connects and says hello; returns the client and the id the server assigned. */
    private fun join(name: String, mac: String?, requestedId: Int = 0): Pair<LoopbackClient, Int> {
        val client = connect()
        client.hello(requestedId, name, mac)
        val id = client.awaitMesg(NetMsg.NETMSG_SERVERREPLY).substring(NetMsg.NETMSG_SERVERREPLY.length).toInt()
        return client to id
    }

    private fun score(id: Int) = server.getScore(id.toByte())

    /** What the operator's player settings dialog does for the admin switch. */
    private fun makeAdmin(id: Int) {
        val settings = PlayerSettings()
        settings.isAdmin = true
        PlayerRepository.getInstance().playerSettings[id.toByte()] = settings
    }

    private fun awaitDisconnected(id: Int) =
        awaitTrue(6000, "player $id to show as disconnected") { score(id)?.isConnected == false }

    // ------------------------------------------------------------------------------------------
    // Lobby
    // ------------------------------------------------------------------------------------------

    @Test
    fun lobbyJoin_autoSortsIntoTeams_andRepliesWithIdAndGameInfo() {
        val (a, idA) = join("Alpha", "AA:00")
        val (b, idB) = join("Bravo", "BB:00")
        assertEquals(1, idA)
        assertEquals(17, idB) // the second player goes to team 2
        val info = b.awaitJson(TcpServer.JSON_PLAYERS)
        assertEquals(GameStateRepository.GAME_STATE_NONE, info.getInt(TcpServer.JSON_GAMESTATE))
        assertFalse(info.has("dedicatedserver")) // every server is dedicated (v07)
        assertEquals(SessionPhase.LOBBY.wireValue, info.getInt(TcpServer.JSON_SESSIONPHASE))
        assertFalse(info.has(TcpServer.JSON_GAMEID))
        assertTrue(info.has(TcpServer.JSON_PLAYERGAMEUPDATE)) // the joiner gets its own update
        awaitTrue { score(idA)?.isConnected == true && score(idB)?.isConnected == true }
        assertEquals("Alpha", PlayerRepository.getInstance().getPlayerName(idA.toByte()))
        assertEquals(idB.toByte(), PlayerRepository.getInstance().getPlayerIdByMac("BB:00"))
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
        val name = PlayerRepository.getInstance().getPlayerName(idB.toByte())
        assertFalse(name.equals("alpha", ignoreCase = true))
        assertTrue(name.isNotEmpty())
    }

    @Test
    fun lobbyLeave_removesThePlayer() {
        val (a, idA) = join("Alpha", "AA:00")
        awaitTrue { score(idA) != null }
        a.mesg(NetMsg.NETMSG_LEAVE)
        awaitTrue { score(idA) == null }
        assertNull(PlayerRepository.getInstance().teamIPMap[idA.toByte()])
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
        joined.forEach { (client, _) -> client.awaitMesg(NetMsg.NETMSG_STARTGAME) }
        return joined
    }

    @Test
    fun runningGame_eliminationCreditsShooterAndVictim() {
        val (a, b) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (clientA, idA) = a
        val (clientB, idB) = b
        clientA.mesg(NetMsg.NETMSG_ELIMINATED + idB)
        awaitTrue { score(idA)?.eliminated == 1 && score(idB)?.points == 1 }
        assertEquals(NetMsg.NETMSG_ELIMINATED + idA, clientB.awaitMesg(NetMsg.NETMSG_ELIMINATED))
    }

    @Test
    fun runningGame_teammateGrenadeKill_costsALife_withoutScoreOrNotifications() {
        val (a, _, c) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00", "Charlie" to "CC:00")
        val (clientA, idA) = a
        val (clientC, idC) = c
        assertEquals(PlayerRepository.getInstance().calcNetworkTeam(idA.toByte()), PlayerRepository.getInstance().calcNetworkTeam(idC.toByte()))
        clientA.mesg(NetMsg.NETMSG_ELIMINATED + idC)
        clientA.mesg(NetMsg.NETMSG_PLAYERDATAREQUEST) // answered after the elimination was handled
        clientA.awaitJson(TcpServer.JSON_PLAYERDATA)
        assertEquals(1, score(idA)!!.eliminated)
        assertEquals(0, score(idC)!!.points)
        Thread.sleep(200)
        assertTrue(clientC.mesgs(NetMsg.NETMSG_ELIMINATED).isEmpty())
        assertTrue(clientC.mesgs(NetMsg.NETMSG_TEAMELIMINATED).isEmpty())
    }

    @Test
    fun runningGame_dropKeepsTheScore_andTheSameMacRejoinsWithItsIdAndScore() {
        val (a, b) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (clientA, idA) = a
        val (clientB, idB) = b
        clientA.mesg(NetMsg.NETMSG_ELIMINATED + idB)
        awaitTrue { score(idB)?.points == 1 }

        clientB.close()
        awaitDisconnected(idB)
        assertEquals(1, score(idB)!!.points)

        val (back, idBack) = join("Bravo", "BB:00")
        assertEquals(idB, idBack)
        val update = back.awaitJson(TcpServer.JSON_PLAYERGAMEUPDATE).getJSONObject(TcpServer.JSON_PLAYERGAMEUPDATE)
        assertEquals(1, update.getInt(TcpServer.JSON_PLAYERPOINTS))
        awaitTrue { score(idB)?.isConnected == true }
        assertEquals(1, score(idB)!!.points)
        assertEquals(1, score(idA)!!.eliminated)
    }

    @Test
    fun runningGame_newPlayerIsRefusedWhenJoinInProgressIsOff() {
        startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        GameSettingsRepository.getInstance().setAllowJoinInProgress(false)
        val late = connect()
        late.hello(0, "Charlie", "CC:00")
        assertTrue(late.awaitMesg(NetMsg.NETMSG_ERROR).startsWith(NetMsg.NETMSG_ERROR))
        assertTrue(late.mesgs(NetMsg.NETMSG_SERVERREPLY).isEmpty())
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
        clientA.mesg(NetMsg.NETMSG_LEAVE)
        awaitDisconnected(idA)
        assertEquals(SessionPhase.RUNNING, server.sessionPhase)
    }

    @Test
    fun runningGame_lastButOnePlayerLeaving_endsTheGame() {
        val (a, _) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        a.first.mesg(NetMsg.NETMSG_LEAVE)
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
        clientA.mesg(NetMsg.NETMSG_ELIMINATED + idB)
        awaitTrue { score(idB)?.points == 1 }
        clientB.close()
        awaitDisconnected(idB)

        server.endGame()
        clientA.awaitMesg(NetMsg.NETMSG_ENDGAME)
        assertEquals(SessionPhase.POST_GAME, server.sessionPhase)
        assertEquals(GameStateRepository.GAME_STATE_NONE, GameStateRepository.getInstance().gameState)
        assertEquals(GameStateRepository.GAME_STATE_NONE, GameStateRepository.getInstance().gameState)
        assertTrue(server.roster.isCached("BB:00"))
        assertFalse(score(idB)!!.isConnected)

        val (back, idBack) = join("Bravo", "BB:00")
        assertEquals(idB, idBack)
        val update = back.awaitJson(TcpServer.JSON_PLAYERGAMEUPDATE).getJSONObject(TcpServer.JSON_PLAYERGAMEUPDATE)
        assertEquals(1, update.getInt(TcpServer.JSON_PLAYERPOINTS))
        assertNotNull(score(idA))
    }

    // ------------------------------------------------------------------------------------------
    // Reset
    // ------------------------------------------------------------------------------------------

    private fun endedGameWithBravoAway(): Triple<LoopbackClient, Int, Int> {
        val (a, b) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (clientA, idA) = a
        val (clientB, idB) = b
        clientA.mesg(NetMsg.NETMSG_ELIMINATED + idB)
        awaitTrue { score(idB)?.points == 1 }
        clientB.close()
        awaitDisconnected(idB)
        server.endGame()
        clientA.awaitMesg(NetMsg.NETMSG_ENDGAME)
        return Triple(clientA, idA, idB)
    }

    @Test
    fun startAfterAGame_isRefusedWithAnError() {
        val (clientA, idA, _) = endedGameWithBravoAway()
        makeAdmin(idA)
        assertFalse(server.startGame())
        clientA.mesg(NetMsg.NETMSG_STARTGAME)
        assertEquals(NetMsg.NETMSG_ERROR + "Reset the game first", clientA.awaitMesg(NetMsg.NETMSG_ERROR))
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
        awaitTrue { clientA.lastJson(TcpServer.JSON_PLAYERS)?.getInt(TcpServer.JSON_GAMESTATE) == GameStateRepository.GAME_STATE_NONE }
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
        clientA.mesg(NetMsg.NETMSG_ADMIN_GAMEMODE + TeamMode.FOUR_TEAMS.wireValue)
        awaitTrue { server.sessionPhase == SessionPhase.LOBBY }
        awaitTrue { GameSettingsRepository.getInstance().teamMode == TeamMode.FOUR_TEAMS }
        assertNull(score(idB))
    }

    @Test
    fun adminTeamMode_unknownValue_isIgnored() {
        val (a, idA) = join("Alpha", "AA:00")
        makeAdmin(idA)
        a.mesg(NetMsg.NETMSG_ADMIN_GAMEMODE + 3)
        a.mesg(NetMsg.NETMSG_PLAYERDATAREQUEST) // processed after the mode change
        a.awaitJson(TcpServer.JSON_PLAYERDATA)
        assertEquals(TeamMode.TWO_TEAMS, GameSettingsRepository.getInstance().teamMode)
        assertArrayEquals(intArrayOf(16, 16), GameSettingsRepository.getInstance().teamLayout.teamSizes())
    }

    // ------------------------------------------------------------------------------------------
    // D14: admin commands
    // ------------------------------------------------------------------------------------------

    @Test
    fun adminCommandsFromANonAdmin_areIgnored() {
        val (a, idA) = join("Alpha", "AA:00")
        val (_, idB) = join("Bravo", "BB:00")
        a.mesg(NetMsg.NETMSG_STARTGAME)
        a.mesg(NetMsg.NETMSG_ADMIN_STARTGAME)
        a.mesg(NetMsg.NETMSG_ADMIN_KICK + idB)
        a.mesg(NetMsg.NETMSG_ADMIN_GAMEMODE + TeamMode.FREE_FOR_ALL.wireValue)
        a.mesg(NetMsg.NETMSG_PLAYERDATAREQUEST) // processed after the others
        a.awaitJson(TcpServer.JSON_PLAYERDATA)
        assertEquals(SessionPhase.LOBBY, server.sessionPhase)
        assertNotNull(score(idB))
        assertEquals(TeamMode.TWO_TEAMS, GameSettingsRepository.getInstance().teamMode)

        // A client can't make itself an admin through its player settings either.
        val settings = org.json.JSONObject()
        settings.put(TcpServer.JSON_PLAYERSETTINGS, true)
        settings.put(TcpServer.JSON_PLAYERID, idA)
        settings.put(TcpServer.JSON_HEALTH, 20)
        settings.put(TcpServer.JSON_RELOAD_SHOTS, 30)
        settings.put(TcpServer.JSON_RELOAD_TIME, 1500)
        settings.put(TcpServer.JSON_RELOAD_ON_EMPTY, false)
        settings.put(TcpServer.JSON_SPAWN_TIME, 10)
        settings.put(TcpServer.JSON_DAMAGE, -1)
        settings.put(TcpServer.JSON_SHOT_MODE_SINGLE, true)
        settings.put(TcpServer.JSON_SHOT_MODE_BURST3, true)
        settings.put(TcpServer.JSON_SHOT_MODE_AUTO, true)
        settings.put(TcpServer.JSON_FIRING_MODE, 0)
        settings.put(TcpServer.JSON_IS_ADMIN, true)
        a.json(settings)
        awaitTrue { PlayerRepository.getInstance().playerSettings[idA.toByte()] != null }
        assertFalse(PlayerRepository.getInstance().playerSettings[idA.toByte()]!!.isAdmin)
    }

    @Test
    fun gameInfo_carriesThePhaseAndGameId_andPlayerDataTheConnection() {
        val (a, b) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (clientA, _) = a
        awaitTrue { clientA.lastJson(TcpServer.JSON_PLAYERS)?.getInt(TcpServer.JSON_SESSIONPHASE) == SessionPhase.RUNNING.wireValue }
        val gameId = clientA.lastJson(TcpServer.JSON_PLAYERS)!!.getLong(TcpServer.JSON_GAMEID)
        assertEquals(server.roster.gameId(), gameId)
        assertTrue(gameId != 0L)
        b.first.close()
        awaitDisconnected(b.second)
        server.endGame()
        clientA.awaitMesg(NetMsg.NETMSG_ENDGAME)
        val players = clientA.awaitJson(TcpServer.JSON_PLAYERDATA).getJSONArray(TcpServer.JSON_PLAYERDATA)
        val bravo = (0 until players.length()).map { players.getJSONObject(it) }.first { it.getInt(TcpServer.JSON_PLAYERID) == b.second }
        assertFalse(bravo.getBoolean(TcpServer.JSON_CONNECTED))
    }

    @Test
    fun adminReset_resetsOnlyForAnAdmin() {
        val (clientA, idA, idB) = endedGameWithBravoAway()
        val before = clientA.received.size
        clientA.mesg(NetMsg.NETMSG_ADMIN_RESET)
        clientA.mesg(NetMsg.NETMSG_PLAYERDATAREQUEST) // answered after the reset was handled
        awaitTrue { clientA.received.drop(before).any { it.contains(TcpServer.JSON_PLAYERDATA) } }
        assertEquals(SessionPhase.POST_GAME, server.sessionPhase)
        makeAdmin(idA)
        clientA.mesg(NetMsg.NETMSG_ADMIN_RESET)
        awaitTrue { server.sessionPhase == SessionPhase.LOBBY }
        assertNull(score(idB))
        awaitTrue { clientA.lastJson(TcpServer.JSON_PLAYERS)?.getInt(TcpServer.JSON_SESSIONPHASE) == SessionPhase.LOBBY.wireValue }
        assertFalse(clientA.lastJson(TcpServer.JSON_PLAYERS)!!.has(TcpServer.JSON_GAMEID))
    }

    @Test
    fun adminCommandsFromAnAdmin_run() {
        val (a, idA) = join("Alpha", "AA:00")
        join("Bravo", "BB:00")
        makeAdmin(idA)
        a.mesg(NetMsg.NETMSG_ADMIN_STARTGAME)
        a.awaitMesg(NetMsg.NETMSG_STARTGAME)
        assertEquals(SessionPhase.RUNNING, server.sessionPhase)
        a.mesg(NetMsg.NETMSG_ADMIN_ENDGAME)
        a.awaitMesg(NetMsg.NETMSG_ENDGAME)
        assertEquals(SessionPhase.POST_GAME, server.sessionPhase)
    }

    // ------------------------------------------------------------------------------------------
    // Reliable eliminations (§3.4, §3.5), with TcpClient's own hello and envelope
    // ------------------------------------------------------------------------------------------

    private fun LoopbackClient.reliable(outbox: ClientOutbox, rmsg: String): ClientOutbox.Entry {
        val entry = outbox.add(rmsg, outbox.gameId)
        json(TcpClient.reliableJson(entry, outbox.gameId))
        return entry
    }

    private fun LoopbackClient.rejoin(name: String, mac: String, outbox: ClientOutbox) =
        json(TcpClient.helloJson(0, name, mac, true, outbox))

    @Test
    fun reliableEliminations_lostAckAndOfflineKills_countOnceAfterTheRejoin() {
        val (a, b) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (clientA, idA) = a
        val (clientB, idB) = b
        val outbox = ClientOutbox()
        outbox.onGameId(server.roster.gameId())
        val killedByBravo = NetMsg.NETMSG_ELIMINATED + idB

        // 1: delivered and acknowledged.
        clientA.reliable(outbox, killedByBravo)
        assertEquals(NetMsg.NETMSG_ACK + 1, clientA.awaitMesg(NetMsg.NETMSG_ACK))
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
        assertEquals(NetMsg.NETMSG_SERVERREPLY + idA, back.awaitMesg(NetMsg.NETMSG_SERVERREPLY))
        val update = back.awaitJson(TcpServer.JSON_PLAYERGAMEUPDATE).getJSONObject(TcpServer.JSON_PLAYERGAMEUPDATE)
        assertEquals(4, update.getInt(TcpServer.JSON_ACKSEQ))
        assertEquals(4, update.getInt(TcpServer.JSON_PLAYERELIMINATED))
        // D2: the lives the client shows equal its own count.
        assertEquals(4, outbox.reconcile(update.getInt(TcpServer.JSON_PLAYERELIMINATED), update.getInt(TcpServer.JSON_ACKSEQ)))
        assertTrue(outbox.pending().isEmpty())
        awaitTrue { score(idA)?.isConnected == true }
        assertEquals(4, score(idA)!!.eliminated)
        assertEquals(4, score(idB)!!.points)
        // The shooter heard about each kill once.
        awaitTrue { clientB.mesgs(NetMsg.NETMSG_ELIMINATED).size == 4 }
        Thread.sleep(200)
        assertEquals(4, clientB.mesgs(NetMsg.NETMSG_ELIMINATED).size)
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
        outbox.add(NetMsg.NETMSG_ELIMINATED + idB, outbox.gameId)
        server.endGame()
        clientB.awaitMesg(NetMsg.NETMSG_ENDGAME)

        val back = connect()
        back.rejoin("Alpha", "AA:00", outbox)
        back.awaitMesg(NetMsg.NETMSG_SERVERREPLY)
        awaitTrue { score(idA)?.isConnected == true }
        assertEquals(1, score(idA)!!.eliminated)
        assertEquals(1, score(idB)!!.points)
        assertEquals(SessionPhase.POST_GAME, server.sessionPhase)

        // An envelope from an older game is acknowledged and dropped.
        val stale = ClientOutbox()
        stale.onGameId(server.roster.gameId() + 1)
        back.reliable(stale, NetMsg.NETMSG_ELIMINATED + idB)
        assertEquals(NetMsg.NETMSG_ACK + 1, back.awaitMesg(NetMsg.NETMSG_ACK))
        assertEquals(1, score(idB)!!.points)
    }

    @Test
    fun startGame_syncsGameStateRepository() {
        // D8: GameStateRepository always said "not running".
        startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, GameStateRepository.getInstance().gameState)
    }

    @Test
    fun endGame_sendsFinalPlayerDataBeforeEndGame() {
        val (a, _) = startGameWith("Alpha" to "AA:00", "Bravo" to "BB:00")
        val (clientA, _) = a
        server.endGame()
        clientA.awaitMesg(NetMsg.NETMSG_ENDGAME)
        val data = clientA.lastJson(TcpServer.JSON_PLAYERDATA)
        assertNotNull(data)
        val prefix = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON
        val endGame = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ENDGAME
        val dataIndex = clientA.received.indexOfLast { it.startsWith(prefix) && it.contains(TcpServer.JSON_PLAYERDATA) }
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
        awaitTrue { b.mesgs(NetMsg.NETMSG_SERVERREPLY).size >= 2 }

        // Connections 3..17.
        repeat(15) { connect() }
        awaitTrue(3000, "17 connections") { server.roster.connectionCount() == 17 }
        assertNotNull(score(idB))
        assertTrue(score(idB)!!.isConnected)
        assertNotNull(score(idA))
    }

    @Test
    fun malformedFrame_isIgnored_andTheServerKeepsServingEveryone() {
        // N5: a short frame after the version prefix used to throw out of the read thread
        val (a, _) = join("Alpha", "AA:00")
        val (b, _) = join("Bravo", "BB:00")
        a.send(TcpServer.TCPMESSAGE_PREFIX + "X")
        a.send(TcpServer.TCPMESSAGE_PREFIX)
        b.mesg(NetMsg.NETMSG_SHOTFIRED)
        a.awaitMesg(NetMsg.NETMSG_SHOTFIRED)
        b.awaitMesg(NetMsg.NETMSG_SHOTFIRED)
    }

    @Test
    fun otherProtocolVersion_getsVersionError() {
        val client = connect()
        client.send(NetMsg.MESSAGE_PREFIX + "06" + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_LEAVE)
        awaitTrue { client.received.contains(NetMsg.NETMSG_VERSIONERROR) }
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
        hostile.forEach { c.send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + it) }
        hostile.forEach { a.send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + it) }
        listOf(NetMsg.NETMSG_HIT + "1", NetMsg.NETMSG_OUT + "1", NetMsg.NETMSG_TEAMELIMINATED, NetMsg.NETMSG_PLAYERDATAREQUEST,
            NetMsg.NETMSG_ELIMINATED + "1", NetMsg.NETMSG_ELIMINATED, NetMsg.NETMSG_ELIMINATED + "x", NetMsg.NETMSG_LEAVE).forEach { c.mesg(it) }
        b.mesg(NetMsg.NETMSG_SHOTFIRED)
        b.awaitMesg(NetMsg.NETMSG_SHOTFIRED)
    }
}
