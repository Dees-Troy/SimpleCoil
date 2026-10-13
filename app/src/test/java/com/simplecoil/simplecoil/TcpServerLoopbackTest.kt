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
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.network.server.SessionPhase
import org.junit.After
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
        val g = Globals.getInstance()
        g.mGameState = Globals.GAME_STATE_NONE
        g.mGameMode = Globals.GAME_MODE_2TEAMS
        g.mGameLimit = Globals.GAME_LIMIT_NONE
        g.mUseGPS = false
        g.mPlayerID = 0
        g.resetDefaultTeamSizes()
        GameSettingsRepository.getInstance().setGameMode(GameSettingsRepository.GAME_MODE_2TEAMS)
        GameSettingsRepository.getInstance().setAllowJoinInProgress(true)
        GameStateRepository.getInstance().setGameState(GameStateRepository.GAME_STATE_NONE)
        PlayerRepository.getInstance().setPlayerIDDirect(0)
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
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE
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
        assertEquals(Globals.GAME_STATE_NONE, info.getInt(TcpServer.JSON_GAMESTATE))
        assertTrue(info.getBoolean(TcpServer.JSON_DEDICATED))
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
        assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState)
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
        val (clientA, _, _) = endedGameWithBravoAway()
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
        awaitTrue { clientA.lastJson(TcpServer.JSON_PLAYERS)?.getInt(TcpServer.JSON_GAMESTATE) == Globals.GAME_STATE_NONE }
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
    fun adminGameModeAfterAGame_resetsFirst() {
        val (clientA, _, idB) = endedGameWithBravoAway()
        clientA.mesg(NetMsg.NETMSG_ADMIN_GAMEMODE + Globals.GAME_MODE_4TEAMS)
        awaitTrue { server.sessionPhase == SessionPhase.LOBBY }
        awaitTrue { Globals.getInstance().mGameMode == Globals.GAME_MODE_4TEAMS }
        assertNull(score(idB))
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
}
