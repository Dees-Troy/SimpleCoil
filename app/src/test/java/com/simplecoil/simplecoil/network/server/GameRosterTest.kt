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

package com.simplecoil.simplecoil.network.server

import com.simplecoil.simplecoil.Globals
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** GameRoster's rules on the JVM, with socket-less connections. */
class GameRosterTest {

    private lateinit var roster: GameRoster
    private val repo get() = PlayerRepository.getInstance()

    @Before
    fun setUp() {
        val g = Globals.getInstance()
        g.mGameMode = Globals.GAME_MODE_2TEAMS
        g.mPlayerID = 0
        g.resetDefaultTeamSizes()
        GameSettingsRepository.getInstance().setGameMode(GameSettingsRepository.GAME_MODE_2TEAMS)
        GameSettingsRepository.getInstance().setAllowJoinInProgress(true)
        repo.setPlayerIDDirect(0)
        repo.clearAllPlayers()
        roster = GameRoster()
    }

    private fun connect(): Connection = roster.accept(null)

    private fun hello(name: String, mac: String?, requestedId: Int = 0, connection: Connection = connect()): GameRoster.HelloResult =
        roster.onHello(connection, requestedId.toByte(), name, mac, null)

    private fun join(name: String, mac: String?, requestedId: Int = 0): Pair<Connection, PlayerRecord> {
        val connection = connect()
        val result = hello(name, mac, requestedId, connection)
        assertEquals(GameRoster.HelloResult.Kind.JOINED, result.kind)
        return connection to result.record
    }

    // ------------------------------------------------------------------------------------------
    // Joining
    // ------------------------------------------------------------------------------------------

    @Test
    fun hello_autoSortsAlternatingTeams_andHonorsAFreeRequestedId() {
        val (_, a) = join("Alpha", "AA")
        val (_, b) = join("Bravo", "BB")
        val (_, c) = join("Charlie", "CC", requestedId = 20)
        assertEquals(1.toByte(), a.playerId)
        assertEquals(17.toByte(), b.playerId)
        assertEquals(20.toByte(), c.playerId)
        assertEquals(2, c.team)
        assertEquals("Alpha", repo.getPlayerName(1))
        assertEquals(17.toByte(), repo.getPlayerIdByMac("BB"))
    }

    @Test
    fun hello_takenIdIsReassigned_andDuplicateNameRenamed() {
        join("Alpha", "AA", requestedId = 5)
        val (_, b) = join("ALPHA", "BB", requestedId = 5)
        assertTrue(b.playerId != 5.toByte())
        assertFalse(b.name.equals("alpha", ignoreCase = true))
    }

    @Test
    fun hello_again_onTheSameConnection_keepsThePlayer() {
        val (connection, a) = join("Alpha", "AA")
        val again = hello("Alpha", "AA", a.playerId.toInt(), connection)
        assertSame(a, again.record)
        assertEquals(1, roster.records().size)
    }

    // ------------------------------------------------------------------------------------------
    // Disconnects
    // ------------------------------------------------------------------------------------------

    @Test
    fun lobbyDisconnect_removesThePlayer() {
        val (connection, a) = join("Alpha", "AA")
        assertEquals(GameRoster.Disconnect.REMOVED, roster.onDisconnect(connection))
        assertNull(roster.record(a.playerId))
        assertNull(repo.teamIPMap[a.playerId])
        assertEquals(0, roster.connectionCount())
    }

    @Test
    fun runningDisconnect_keepsThePlayerAndScore_disconnected() {
        val (connection, a) = join("Alpha", "AA")
        val (shooter, _) = join("Bravo", "BB")
        roster.startGame()
        roster.onEliminated(shooter, a.playerId)
        assertEquals(GameRoster.Disconnect.KEPT, roster.onDisconnect(connection))
        val score = roster.score(a.playerId)!!
        assertFalse(score.connected)
        assertEquals(1, score.points)
    }

    @Test
    fun running_sameMacRejoins_withIdTeamNameAndScore_andDisplacesTheOldConnection() {
        val (old, a) = join("Alpha", "AA")
        val (shooter, b) = join("Bravo", "BB")
        roster.startGame()
        roster.onEliminated(old, b.playerId)
        roster.onEliminated(shooter, a.playerId)

        val fresh = connect()
        val result = hello("Somebody", "AA", 0, fresh)
        assertEquals(GameRoster.HelloResult.Kind.REJOINED, result.kind)
        assertSame(a, result.record)
        assertSame(old, result.displaced) // last connection wins
        assertSame(fresh, a.connection)
        assertEquals("Alpha", a.name)
        assertEquals(1, a.points)
        assertEquals(1, a.eliminated)
        assertEquals(2, roster.connectionCount())
        // The displaced connection timing out later doesn't touch the player.
        assertEquals(GameRoster.Disconnect.NO_PLAYER, roster.onDisconnect(old))
        assertTrue(roster.score(a.playerId)!!.connected)
    }

    @Test
    fun running_newPlayer_refusedWithoutJoinInProgress_elseCached() {
        join("Alpha", "AA")
        roster.startGame()
        GameSettingsRepository.getInstance().setAllowJoinInProgress(false)
        assertEquals(GameRoster.HelloResult.Kind.REFUSED_IN_PROGRESS, hello("Late", "LL").kind)
        GameSettingsRepository.getInstance().setAllowJoinInProgress(true)
        val (_, late) = join("Late", "LL")
        assertTrue(roster.isCached("LL"))
        assertTrue(late.playerId != 1.toByte())
    }

    @Test
    fun startGame_cachesPlayersWithMacs_andZeroesScores() {
        val (ca, a) = join("Alpha", "AA")
        join("NoMac", null)
        roster.startGame()
        roster.onEliminated(ca, 17)
        roster.endGame()
        roster.startGame()
        assertTrue(roster.isCached("AA"))
        assertFalse(roster.isCached(""))
        assertEquals(0, a.eliminated)
    }

    @Test
    fun endGame_keepsTheRosterAndDisconnectedPlayers() {
        // D5: the roster used to be discarded at the end of the game.
        val (ca, a) = join("Alpha", "AA")
        join("Bravo", "BB")
        roster.startGame()
        roster.onDisconnect(ca)
        assertTrue(roster.endGame())
        assertEquals(SessionPhase.POST_GAME, roster.phase())
        assertTrue(roster.isCached("AA"))
        assertSame(a, roster.record(a.playerId))
        assertFalse(roster.endGame()) // only once
    }

    @Test
    fun postGame_cachedMacRejoins_withItsFinalScore() {
        val (ca, a) = join("Alpha", "AA")
        val (cb, b) = join("Bravo", "BB")
        roster.startGame()
        roster.onEliminated(ca, b.playerId)
        roster.onDisconnect(cb)
        roster.endGame()
        val result = hello("Bravo", "BB")
        assertEquals(GameRoster.HelloResult.Kind.REJOINED, result.kind)
        assertSame(b, result.record)
        assertEquals(1, b.points)
        assertEquals(17.toByte(), b.playerId)
        assertEquals(1, a.eliminated)
    }

    @Test
    fun postGame_newPlayer_isNotCached_andNeverGetsAReservedId() {
        val (_, a) = join("Alpha", "AA")
        val (cb, b) = join("Bravo", "BB")
        roster.startGame()
        roster.onDisconnect(cb)
        roster.endGame()
        GameSettingsRepository.getInstance().setAllowJoinInProgress(false) // not consulted after the game
        val (_, late) = join("Late", "LL", requestedId = b.playerId.toInt())
        assertTrue(late.playerId != a.playerId && late.playerId != b.playerId)
        assertFalse(roster.isCached("LL"))
    }

    @Test
    fun runningOrPostGame_disconnect_keepsCachedPlayers_andRemovesUncachedOnes() {
        val (ca, a) = join("Alpha", "AA")
        val (cn, n) = join("NoMac", null)
        roster.startGame()
        assertEquals(GameRoster.Disconnect.KEPT, roster.onDisconnect(ca))
        assertEquals(GameRoster.Disconnect.REMOVED, roster.onDisconnect(cn))
        assertNull(roster.record(n.playerId))
        roster.endGame()
        val (cl, late) = join("Late", "LL")
        assertEquals(GameRoster.Disconnect.REMOVED, roster.onDisconnect(cl))
        assertNull(roster.record(late.playerId))
        assertNotNull(roster.record(a.playerId))
    }

    @Test
    fun reset_removesDisconnectedPlayers_zeroesTheRest_andReturnsToTheLobby() {
        val (ca, a) = join("Alpha", "AA")
        val (cb, b) = join("Bravo", "BB")
        assertNull(roster.reset()) // only after a game
        roster.startGame()
        roster.onEliminated(ca, b.playerId)
        roster.onDisconnect(ca)
        roster.endGame()
        repo.setGrenadePairing(3, a.playerId.toInt())
        assertEquals(listOf(a), roster.reset())
        assertEquals(SessionPhase.LOBBY, roster.phase())
        assertNull(roster.record(a.playerId))
        assertNull(repo.getPlayerIdByMac("AA"))
        assertEquals(PlayerRepository.INVALID_PLAYER_ID, repo.getGrenadePairing(3))
        assertEquals(0, b.points)
        assertFalse(roster.isCached("BB"))
        assertNull(roster.reset())
        // Nobody is cached again until the next start: a disconnect now removes the player.
        assertEquals(GameRoster.Disconnect.REMOVED, roster.onDisconnect(cb))
    }

    @Test
    fun startAfterAGame_resetsFirst() {
        val (ca, a) = join("Alpha", "AA")
        join("Bravo", "BB")
        roster.startGame()
        roster.onDisconnect(ca)
        roster.endGame()
        assertTrue(roster.startGame())
        assertNull(roster.record(a.playerId))
        assertFalse(roster.startGame()) // already running
    }

    @Test
    fun eliminations_areIgnoredInTheLobby() {
        val (ca, _) = join("Alpha", "AA")
        val (_, b) = join("Bravo", "BB")
        assertNull(roster.onEliminated(ca, b.playerId))
        assertEquals(0, b.points)
    }

    @Test
    fun teamChangesAndOperatorMoves_onlyInTheLobby() {
        val (ca, a) = join("Alpha", "AA")
        join("Bravo", "BB")
        roster.startGame()
        assertNull(roster.changeTeam(ca, 2))
        assertNull(roster.movePlayer(a.playerId, 5))
        assertTrue(roster.rebalance(false).assigned.isEmpty())
        roster.endGame()
        assertNull(roster.changeTeam(ca, 2))
        assertEquals(1.toByte(), a.playerId)
    }

    // ------------------------------------------------------------------------------------------
    // Scoring
    // ------------------------------------------------------------------------------------------

    @Test
    fun elimination_creditsTheShooterEvenWhenDisconnected() {
        val (ca, a) = join("Alpha", "AA")
        val (cb, b) = join("Bravo", "BB")
        roster.startGame()
        roster.onDisconnect(cb)
        val elimination = roster.onEliminated(ca, b.playerId)
        assertEquals(a.playerId, elimination.victimId)
        assertEquals(1, elimination.shooterPoints)
        assertEquals(1, roster.score(b.playerId)!!.points)
        assertEquals(1, roster.teamPoints(2))
    }

    @Test
    fun elimination_fromAConnectionWithoutHello_onlyCreditsTheShooter() {
        val (_, b) = join("Bravo", "BB")
        roster.startGame()
        val stranger = connect()
        val elimination = roster.onEliminated(stranger, b.playerId)
        assertEquals(0.toByte(), elimination.victimId)
        assertEquals(1, b.points)
    }

    // ------------------------------------------------------------------------------------------
    // Kick and renumbering
    // ------------------------------------------------------------------------------------------

    @Test
    fun kick_removesThePlayerAndItsCacheEntry_andReturnsItsConnection() {
        val (ca, a) = join("Alpha", "AA")
        join("Bravo", "BB")
        roster.startGame()
        assertSame(ca, roster.kick(a.playerId))
        assertNull(roster.record(a.playerId))
        assertFalse(roster.isCached("AA"))
        assertEquals(1, roster.connectionCount())
    }

    @Test
    fun kick_findsThePlayerById_notByConnectionKey() {
        // D9: kick looked the player up by connection key first, so kicking player 1 hit
        // connection 1 even when that was someone else.
        val (_, a) = join("Alpha", "AA", requestedId = 2) // connection 1, player 2
        val (cb, b) = join("Bravo", "BB", requestedId = 1) // connection 2, player 1
        assertSame(cb, roster.kick(b.playerId))
        assertSame(a, roster.record(2))
    }

    @Test
    fun rebalance_assignsIdsByTeamSize_andMovesTheRepositoryMaps() {
        join("Alpha", "AA", requestedId = 3)
        join("Bravo", "BB", requestedId = 4)
        join("Charlie", "CC", requestedId = 5)
        val result = roster.rebalance(false)
        assertEquals(listOf<Byte>(1, 17, 2), result.assigned.map { it.playerId })
        assertEquals(listOf(1, 2, 1), result.assigned.map { it.team })
        assertEquals("Bravo", repo.getPlayerName(17))
        assertEquals(17.toByte(), repo.getPlayerIdByMac("BB"))
        assertNull(repo.teamIPMap[3])
    }

    @Test
    fun rebalance_freeForAll_numbersPlayersInOrder() {
        Globals.getInstance().mGameMode = Globals.GAME_MODE_FFA
        join("Alpha", "AA", requestedId = 9)
        join("Bravo", "BB", requestedId = 12)
        val result = roster.rebalance(false)
        assertEquals(listOf<Byte>(1, 2), result.assigned.map { it.playerId })
        assertEquals(listOf(1, 2), result.assigned.map { it.team })
    }

    @Test
    fun d4_connectionKeysSurviveRebalance_followedByNewConnections() {
        val (_, a) = join("Alpha", "AA")
        val (cb, b) = join("Bravo", "BB")
        roster.rebalance(false)
        repeat(20) { connect() }
        assertEquals(22, roster.connectionCount())
        assertSame(b, roster.record(17))
        assertSame(cb, b.connection)
        assertSame(a, roster.record(1))
    }

    @Test
    fun changeTeam_movesToAnOpenSlotOnTheTeam() {
        val (ca, a) = join("Alpha", "AA")
        val moved = roster.changeTeam(ca, 2)
        assertSame(a, moved)
        assertEquals(17.toByte(), a.playerId)
        assertEquals(2, a.team)
        assertNull(roster.record(1))
        assertEquals("Alpha", repo.getPlayerName(17))
    }
}
