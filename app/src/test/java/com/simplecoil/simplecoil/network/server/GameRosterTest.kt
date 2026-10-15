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

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.team.TeamMode
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

    private lateinit var settings: GameSettingsRepository
    private lateinit var repo: PlayerRepository
    private lateinit var roster: GameRoster

    @Before
    fun setUp() {
        settings = GameSettingsRepository()
        settings.teamMode = TeamMode.TWO_TEAMS
        settings.setAllowJoinInProgress(true)
        repo = PlayerRepository(settings, GameStateRepository())
        roster = GameRoster(settings, repo)
    }

    private fun connect(): Connection = roster.accept(null)

    private fun hello(name: String, mac: String?, requestedId: Int = 0, connection: Connection = connect()): GameRoster.HelloResult =
        roster.onHello(connection, requestedId.toByte(), name, mac, null)

    private fun join(name: String, mac: String?, requestedId: Int = 0): Pair<Connection, PlayerRecord> {
        val connection = connect()
        val result = hello(name, mac, requestedId, connection)
        assertEquals(GameRoster.HelloResult.Kind.JOINED, result.kind)
        return connection to result.record!!
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
    fun names_areSanitizedByTheServer() {
        val (connection, a) = join("\u202EZo\u00EB\n🔥", "AA")
        assertEquals("Zoë 🔥", a.name)
        assertEquals("Ace \"1\"", roster.onNameChange(connection, " Ace\t\"1\"\u0000 "))
        assertFalse(roster.onNameChange(connection, "\u200B\u3164").isNullOrBlank())
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
        roster.onEliminated(shooter, a.playerId)!!
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
        roster.onEliminated(old, b.playerId)!!
        roster.onEliminated(shooter, a.playerId)!!

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
        settings.setAllowJoinInProgress(false)
        assertEquals(GameRoster.HelloResult.Kind.REFUSED_IN_PROGRESS, hello("Late", "LL").kind)
        settings.setAllowJoinInProgress(true)
        val (_, late) = join("Late", "LL")
        assertTrue(roster.isCached("LL"))
        assertTrue(late.playerId != 1.toByte())
    }

    @Test
    fun startGame_cachesPlayersWithMacs_andZeroesScores() {
        val (ca, a) = join("Alpha", "AA")
        join("NoMac", null)
        roster.startGame()
        roster.onEliminated(ca, 17)!!
        roster.endGame()
        roster.reset()
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
        roster.onEliminated(ca, b.playerId)!!
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
        settings.setAllowJoinInProgress(false) // not consulted after the game
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
        roster.onEliminated(ca, b.playerId)!!
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
    fun start_isRefusedAfterAGameUntilReset() {
        join("Alpha", "AA")
        join("Bravo", "BB")
        roster.startGame()
        assertFalse(roster.startGame()) // already running
        roster.endGame()
        assertFalse(roster.startGame()) // Q8: reset first
        assertEquals(SessionPhase.POST_GAME, roster.phase())
        roster.reset()
        assertTrue(roster.startGame())
    }

    @Test
    fun rebalanceAfterAGame_runsOnTheResetRoster_likeTheSameRebalanceInTheLobby() {
        // §3.1.1: team mode, team sizes and sort reset first; the rebalance then sees only the
        // connected players and the cache is empty.
        val (_, _) = join("Alpha", "AA")
        val (cb, _) = join("Bravo", "BB")
        val (_, _) = join("Charlie", "CC")
        val (_, _) = join("Delta", "DD")
        roster.startGame()
        roster.onDisconnect(cb)
        roster.endGame()
        roster.reset()
        val afterGame = roster.rebalance(false).assigned.map { it.name to it.playerId }

        setUp()
        join("Alpha", "AA")
        join("Charlie", "CC", requestedId = 2)
        join("Delta", "DD", requestedId = 18)
        val inLobby = roster.rebalance(false).assigned.map { it.name to it.playerId }
        assertEquals(inLobby, afterGame)
    }

    @Test
    fun reliable_isAppliedOncePerSeq_andOnlyForTheCurrentGame() {
        val (ca, _) = join("Alpha", "AA")
        join("Bravo", "BB")
        val stranger = connect()
        assertEquals(GameRoster.ReliableCheck.NO_PLAYER, roster.checkReliable(stranger, 1, 0))
        assertEquals(GameRoster.ReliableCheck.STALE, roster.checkReliable(ca, 1, 0)) // lobby
        roster.startGame()
        val game = roster.gameId()
        assertTrue(game != 0L)
        assertEquals(GameRoster.ReliableCheck.APPLY, roster.checkReliable(ca, 1, game))
        assertEquals(GameRoster.ReliableCheck.DUPLICATE, roster.checkReliable(ca, 1, game))
        assertEquals(GameRoster.ReliableCheck.APPLY, roster.checkReliable(ca, 3, game))
        assertEquals(GameRoster.ReliableCheck.DUPLICATE, roster.checkReliable(ca, 2, game)) // lower than applied
        assertEquals(GameRoster.ReliableCheck.STALE, roster.checkReliable(ca, 4, game + 1))
        assertEquals(3, ca.player!!.lastAppliedSeq)
        roster.endGame()
        assertEquals(GameRoster.ReliableCheck.APPLY, roster.checkReliable(ca, 4, game)) // late replay after the game
        roster.reset()
        assertEquals(0L, roster.gameId())
        assertEquals(GameRoster.ReliableCheck.STALE, roster.checkReliable(ca, 5, game))
        assertEquals(0, ca.player!!.lastAppliedSeq)
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
        assertNull(roster.swapPlayers(1, 17))
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
        val elimination = roster.onEliminated(ca, b.playerId)!!
        assertEquals(a.playerId, elimination.victimId)
        assertEquals(1, elimination.shooterPoints)
        assertEquals(1, roster.score(b.playerId)!!.points)
        assertEquals(1, roster.teamPoints(2))
    }

    @Test
    fun ownOrTeammateKill_costsTheVictimALife_butScoresNothing() {
        val (ca, a) = join("Alpha", "AA") // 1, team 1
        val (_, b) = join("Bravo", "BB") // 17, team 2
        val (_, c) = join("Charlie", "CC") // 2, team 1
        roster.startGame()
        val teammate = roster.onEliminated(ca, c.playerId)!!
        assertFalse(teammate.credited)
        assertEquals(0, teammate.shooterPoints)
        val own = roster.onEliminated(ca, a.playerId)!!
        assertFalse(own.credited)
        assertEquals(2, a.eliminated)
        assertEquals(0, c.points)
        assertEquals(0, a.points)
        assertTrue(roster.onEliminated(ca, b.playerId)!!.credited)
        assertEquals(1, b.points)
    }

    @Test
    fun freeForAll_onlyAnOwnKillScoresNothing() {
        settings.teamMode = TeamMode.FREE_FOR_ALL
        val (ca, a) = join("Alpha", "AA")
        val (_, b) = join("Bravo", "BB")
        roster.startGame()
        assertFalse(roster.onEliminated(ca, a.playerId)!!.credited)
        assertTrue(roster.onEliminated(ca, b.playerId)!!.credited)
        assertEquals(2, a.eliminated)
        assertEquals(1, b.points)
    }

    @Test
    fun elimination_fromAConnectionWithoutHello_onlyCreditsTheShooter() {
        val (_, b) = join("Bravo", "BB")
        roster.startGame()
        val stranger = connect()
        val elimination = roster.onEliminated(stranger, b.playerId)!!
        assertEquals(0.toByte(), elimination.victimId)
        assertEquals(1, b.points)
    }

    // ------------------------------------------------------------------------------------------
    // Score matrix
    // ------------------------------------------------------------------------------------------

    /** The matrix's non-zero cells as (shooter, victim) → count. */
    private fun cells(): Map<Pair<Int, Int>, Int> =
        roster.scoreMatrix().cells.associate { (it.shooterId.toInt() to it.victimId.toInt()) to it.count }

    @Test
    fun matrix_recordsCreditedOwnAndTeammateKills() {
        val (ca, a) = join("Alpha", "AA") // 1, team 1
        val (cb, b) = join("Bravo", "BB") // 17, team 2
        val (_, c) = join("Charlie", "CC") // 2, team 1
        roster.startGame()
        roster.onEliminated(ca, b.playerId) // credited
        roster.onEliminated(ca, b.playerId)
        roster.onEliminated(cb, a.playerId)
        roster.onEliminated(ca, a.playerId) // own grenade
        roster.onEliminated(ca, c.playerId) // teammate's grenade
        assertEquals(mapOf((17 to 1) to 2, (1 to 17) to 1, (1 to 1) to 1, (2 to 1) to 1), cells())
        val matrix = roster.scoreMatrix()
        assertEquals(listOf<Byte>(1, 2, 17), matrix.players.map { it.playerId })
        assertEquals(listOf("Alpha", "Charlie", "Bravo"), matrix.players.map { it.name })
        assertEquals(listOf(1, 1, 2), matrix.players.map { it.team })
    }

    @Test
    fun matrix_skipsAnUnknownShooter_andAVictimWithoutHello() {
        val (ca, a) = join("Alpha", "AA")
        val (_, b) = join("Bravo", "BB")
        roster.startGame()
        roster.onEliminated(ca, 30) // nobody is 30
        roster.onEliminated(connect(), b.playerId) // no hello
        assertEquals(1, a.eliminated)
        assertEquals(1, b.points)
        assertTrue(cells().isEmpty())
    }

    @Test
    fun matrix_countsAReliableEliminationOnce() {
        val (ca, a) = join("Alpha", "AA")
        val (_, b) = join("Bravo", "BB")
        roster.startGame()
        val game = roster.gameId()
        repeat(3) {
            if (roster.checkReliable(ca, 1, game) == GameRoster.ReliableCheck.APPLY) roster.onEliminated(ca, b.playerId)
        }
        assertEquals(mapOf((b.playerId.toInt() to a.playerId.toInt()) to 1), cells())
    }

    @Test
    fun matrix_isEmptyInTheLobby_andClearedOnStartResetAndResetScores() {
        val (ca, _) = join("Alpha", "AA")
        val (_, b) = join("Bravo", "BB")
        roster.onEliminated(ca, b.playerId) // lobby: ignored
        assertTrue(cells().isEmpty())
        assertEquals(2, roster.scoreMatrix().players.size)

        roster.startGame()
        roster.onEliminated(ca, b.playerId)
        roster.resetScores()
        assertTrue(cells().isEmpty())

        roster.onEliminated(ca, b.playerId)
        roster.endGame()
        roster.onEliminated(ca, b.playerId) // post-game kills count
        assertEquals(2, cells().values.single())
        roster.reset()
        assertTrue(cells().isEmpty())

        roster.startGame()
        assertTrue(cells().isEmpty())
        roster.onEliminated(ca, b.playerId)
        roster.clear()
        assertTrue(roster.scoreMatrix().cells.isEmpty())
    }

    @Test
    fun matrix_purgesAKickedPlayersRowAndColumn() {
        val (ca, a) = join("Alpha", "AA")
        val (cb, b) = join("Bravo", "BB")
        val (cc, c) = join("Charlie", "CC")
        roster.startGame()
        roster.onEliminated(ca, b.playerId)
        roster.onEliminated(cb, a.playerId)
        roster.onEliminated(cc, a.playerId)
        roster.onEliminated(ca, c.playerId)
        roster.onEliminated(cc, b.playerId)
        roster.kick(a.playerId)
        assertEquals(mapOf((b.playerId.toInt() to c.playerId.toInt()) to 1), cells())
        assertFalse(roster.scoreMatrix().players.any { it.playerId == a.playerId })

        // A new player who gets id 1 starts at zero.
        val (_, d) = join("Delta", "DD", requestedId = 1)
        assertEquals(1.toByte(), d.playerId)
        assertEquals(mapOf((b.playerId.toInt() to c.playerId.toInt()) to 1), cells())
    }

    @Test
    fun matrix_keepsARosterPlayersCellsAcrossDisconnectAndRejoin() {
        val (ca, a) = join("Alpha", "AA")
        val (_, b) = join("Bravo", "BB")
        roster.startGame()
        roster.onEliminated(ca, b.playerId)
        assertEquals(GameRoster.Disconnect.KEPT, roster.onDisconnect(ca))
        assertFalse(roster.scoreMatrix().players.first { it.playerId == a.playerId }.connected)
        assertEquals(1, cells().size)
        val rejoin = hello("Alpha", "AA")
        assertEquals(GameRoster.HelloResult.Kind.REJOINED, rejoin.kind)
        roster.onEliminated(rejoin.record!!.connection!!, b.playerId)
        assertEquals(mapOf((b.playerId.toInt() to a.playerId.toInt()) to 2), cells())
    }

    @Test
    fun matrix_purgesAnUncachedPlayerWhoDisconnectsMidGame() {
        val (ca, a) = join("Alpha", "AA")
        roster.startGame()
        val (cn, n) = join("Nomac", null) // joined in progress, no MAC: not on the roster
        roster.onEliminated(ca, n.playerId)
        roster.onEliminated(cn, a.playerId)
        assertEquals(2, cells().size)
        assertEquals(GameRoster.Disconnect.REMOVED, roster.onDisconnect(cn))
        assertTrue(cells().isEmpty())
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
        settings.teamMode = TeamMode.FREE_FOR_ALL
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

    @Test
    fun swapPlayers_tradesIdsTeamsAndNames() {
        val (_, a) = join("Alpha", "AA")
        val (_, b) = join("Bravo", "BB")
        assertEquals(1.toByte(), a.playerId)
        assertEquals(17.toByte(), b.playerId)
        assertEquals(a to b, roster.swapPlayers(1, 17))
        assertEquals(17.toByte(), a.playerId)
        assertEquals(2, a.team)
        assertEquals(1.toByte(), b.playerId)
        assertEquals(1, b.team)
        assertSame(a, roster.record(17))
        assertSame(b, roster.record(1))
        assertEquals("Alpha", repo.getPlayerName(17))
        assertEquals("Bravo", repo.getPlayerName(1))
        assertEquals(17.toByte(), repo.getPlayerIdByMac("AA"))
        assertNull(roster.swapPlayers(1, 5))
    }
}
