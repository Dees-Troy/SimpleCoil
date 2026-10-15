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

import com.simplecoil.simplecoil.NameGenerator
import com.simplecoil.simplecoil.ProfanityFilter
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.player.PlayerNames
import java.net.InetAddress
import java.net.Socket
import java.util.Random
import java.util.TreeMap

/**
 * The dedicated server's players, connections and game roster (reconnect design §3.2). Player
 * records are keyed by player id, connections only by their connection id, so nothing that
 * renumbers players can collide with a connection (D4, D9).
 *
 * One lock, the roster itself, guards all of it. No method does socket I/O: each returns what
 * changed, and the server sends the messages after the lock is released. PlayerRepository's maps
 * (ip, name, MAC, settings, GPS) are kept in step for the screens that read them. Lock order:
 * the roster, then [repo], then [settings].
 */
class GameRoster @JvmOverloads constructor(
    private val settings: GameSettingsRepository,
    private val repo: PlayerRepository,
    private val random: Random = Random(),
) {

    /** What a hello did. */
    class HelloResult internal constructor(
        @JvmField val kind: Kind,
        /** The player the connection now speaks for (JOINED, REJOINED). */
        @JvmField val record: PlayerRecord?,
        /** The player's previous connection, replaced by this one; close it. */
        @JvmField val displaced: Connection?,
    ) {
        enum class Kind { JOINED, REJOINED, REFUSED_IN_PROGRESS, FULL }
    }

    /** A reported elimination: [victimId] (0 if the sender had not said hello) was eliminated by [shooterId]. */
    class Elimination internal constructor(
        @JvmField val victimId: Byte,
        @JvmField val shooterId: Byte,
        /** The shooter scored: it is known, and is neither the victim nor on the victim's team. */
        @JvmField val credited: Boolean,
        /** The shooter's points after the kill; 0 if it didn't score. */
        @JvmField val shooterPoints: Int,
    )

    /** Result of renumbering everyone. */
    class Rebalance internal constructor(
        /** Every player that was given an id, in order; each is told its (possibly unchanged) id. */
        @JvmField val assigned: List<PlayerRecord>,
        /** Connections of players that did not fit; close them. */
        @JvmField val dropped: List<Connection>,
    )

    /** A player's score. */
    class Score internal constructor(
        @JvmField val points: Int,
        @JvmField val eliminated: Int,
        @JvmField val connected: Boolean,
    )

    /** One player as the score matrix shows it. */
    class MatrixPlayer internal constructor(
        @JvmField val playerId: Byte,
        @JvmField val name: String?,
        /** 1 in free for all. */
        @JvmField val team: Int,
        @JvmField val connected: Boolean,
    )

    /** A non-zero score matrix cell: [shooterId] eliminated [victimId] [count] times. */
    class MatrixCell internal constructor(
        @JvmField val shooterId: Byte,
        @JvmField val victimId: Byte,
        @JvmField val count: Int,
    )

    /** The current game's score matrix: every player, and the non-zero cells. */
    class Matrix internal constructor(
        @JvmField val players: List<MatrixPlayer>,
        @JvmField val cells: List<MatrixCell>,
        /** The game the matrix belongs to; 0 in the lobby. */
        @JvmField val gameId: Long,
    )

    private var nextConnectionId = 0
    private var phase = SessionPhase.LOBBY
    /** The current game, from its start until the reset; 0 in the lobby. */
    private var gameId = 0L
    private val byId = TreeMap<Byte, PlayerRecord>()
    private val cacheByMac = HashMap<String, PlayerRecord>()
    private val connections = LinkedHashMap<Int, Connection>()
    /** Eliminations in the current game: [pairKey] (shooter id, victim id) → count. Cleared with the scores. */
    private val eliminationCounts = HashMap<Int, Int>()

    // ---------------------------------------------------------------------------------------
    // Connections
    // ---------------------------------------------------------------------------------------

    /** Forgets every player and connection (the server is starting). Does not close sockets. */
    @Synchronized
    fun clear() {
        nextConnectionId = 0
        phase = SessionPhase.LOBBY
        gameId = 0
        byId.clear()
        cacheByMac.clear()
        connections.clear()
        eliminationCounts.clear()
    }

    /** Wraps an accepted socket in a new connection with the next connection id. */
    @Synchronized
    fun accept(socket: Socket?): Connection {
        val connection = Connection(++nextConnectionId, socket)
        connections[connection.connectionId] = connection
        return connection
    }

    /** Forgets a connection that was refused or closed, without touching its player. */
    @Synchronized
    fun removeConnection(connection: Connection) {
        connections.remove(connection.connectionId)
        val record = connection.player
        if (record != null && record.connection === connection) record.connection = null
        connection.player = null
    }

    @Synchronized
    fun connections(): List<Connection> = ArrayList(connections.values)

    @Synchronized
    fun connectionCount(): Int = connections.size

    /** Players with a live connection. */
    @Synchronized
    fun connectedCount(): Int = byId.values.count { it.connection != null }

    /** Players plus connections that have not said hello: what the server used to count as its clients. */
    @Synchronized
    fun size(): Int = byId.size + connections.values.count { it.player == null }

    @Synchronized
    fun isEmpty(): Boolean = byId.isEmpty() && connections.isEmpty()

    // ---------------------------------------------------------------------------------------
    // Players
    // ---------------------------------------------------------------------------------------

    @Synchronized
    fun record(playerId: Byte): PlayerRecord? = byId[playerId]

    @Synchronized
    fun records(): List<PlayerRecord> = ArrayList(byId.values)

    /** Players whose team is [team]. */
    @Synchronized
    fun recordsOnTeam(team: Int): List<PlayerRecord> = byId.values.filter { it.team == team }

    @Synchronized
    fun score(playerId: Byte): Score? {
        val record = byId[playerId] ?: return null
        return Score(record.points, record.eliminated, record.connection != null)
    }

    /** Points of every player whose id falls in [team]. */
    @Synchronized
    fun teamPoints(team: Int): Int {
        val layout = settings.teamLayout
        return byId.values.filter { layout.teamOf(it.playerId) == team }.sumOf { it.points }
    }

    @Synchronized
    fun phase(): SessionPhase = phase

    /** The current game's id; 0 in the lobby. */
    @Synchronized
    fun gameId(): Long = gameId

    @Synchronized
    fun isRunning(): Boolean = phase == SessionPhase.RUNNING

    @Synchronized
    fun isCached(mac: String?): Boolean = mac != null && cacheByMac.containsKey(mac)

    // ---------------------------------------------------------------------------------------
    // Game transitions
    // ---------------------------------------------------------------------------------------

    /**
     * Starts a game from the lobby: scores to 0 and every player with a MAC into the roster
     * cache. False (and nothing changes) outside the lobby (after a game, reset first) or if
     * nobody is there.
     */
    @Synchronized
    fun startGame(): Boolean {
        if (phase != SessionPhase.LOBBY) return false
        if (connections.isEmpty() && byId.isEmpty()) return false
        phase = SessionPhase.RUNNING
        gameId = 1L + random.nextInt(Int.MAX_VALUE - 1)
        resetScoresLocked()
        cacheByMac.clear()
        for (record in byId.values) {
            if (record.blasterMac.isNotEmpty()) cacheByMac[record.blasterMac] = record
        }
        return true
    }

    /**
     * Ends a running game. The roster cache and every player, connected or not, are kept so that
     * cached players can still come back. False if no game was running.
     */
    @Synchronized
    fun endGame(): Boolean {
        if (phase != SessionPhase.RUNNING) return false
        phase = SessionPhase.POST_GAME
        return true
    }

    /**
     * After a game: removes every disconnected player, zeroes the others, forgets the roster
     * cache and returns to the lobby. Returns the removed players, or null outside POST_GAME.
     */
    @Synchronized
    fun reset(): List<PlayerRecord>? {
        if (phase != SessionPhase.POST_GAME) return null
        val removed = ArrayList<PlayerRecord>()
        val remainingIds = HashSet<Byte>()
        for (record in ArrayList(byId.values)) {
            if (record.connection == null) {
                removeRecordLocked(record)
                repo.removePlayer(record.playerId, false)
                repo.removeGrenadePairings(record.playerId)
                removed += record
            } else {
                remainingIds += record.playerId
            }
        }
        repo.purgeDisconnectedPlayers(remainingIds)
        resetScoresLocked()
        cacheByMac.clear()
        gameId = 0
        phase = SessionPhase.LOBBY
        return removed
    }

    @Synchronized
    fun resetScores() {
        resetScoresLocked()
    }

    private fun resetScoresLocked() {
        for (record in byId.values) {
            record.points = 0
            record.eliminated = 0
            record.lastAppliedSeq = 0
        }
        eliminationCounts.clear()
    }

    // ---------------------------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------------------------

    /**
     * A client's hello: [requestedId] (0 = auto-sort), [requestedName], [mac] ("" or null if none)
     * and the address it connected from.
     */
    @Synchronized
    fun onHello(connection: Connection, requestedId: Byte, requestedName: String?, mac: String?, address: InetAddress?): HelloResult {
        val blasterMac = mac ?: ""
        if (phase != SessionPhase.LOBBY && blasterMac.isNotEmpty()) {
            val cached = cacheByMac[blasterMac]
            if (cached != null) {
                val displaced = bindLocked(cached, connection)
                repo.addOrUpdatePlayer(cached.playerId, address, cached.name, blasterMac)
                return HelloResult(HelloResult.Kind.REJOINED, cached, displaced)
            }
        }
        val current = connection.player
        if (phase == SessionPhase.RUNNING && current == null && !settings.isAllowJoinInProgress) {
            return HelloResult(HelloResult.Kind.REFUSED_IN_PROGRESS, null, null)
        }

        // Every known player's id is taken, connected or not: cached players keep theirs.
        val occupied = HashSet<Byte>()
        for (record in byId.values) {
            if (record !== current) occupied += record.playerId
        }
        var id = requestedId
        if (id.toInt() != 0 && id in occupied) id = 0
        if (id.toInt() == 0) {
            id = repo.findAutoSortPlayerID(occupied)
            if (id.toInt() == 0) return HelloResult(HelloResult.Kind.FULL, null, null)
        }
        var team = 1
        val layout = settings.teamLayout
        if (!layout.mode.isFreeForAll) {
            team = layout.teamOf(id)
        }

        var record = current
        if (record == null) {
            record = PlayerRecord(id, team, null, blasterMac)
        } else if (record.playerId != id) {
            byId.remove(record.playerId)
            purgeMatrixLocked(record.playerId)
            repo.reassignPlayer(record.playerId, id)
        }
        record.playerId = id
        record.team = team
        record.name = uniqueName(id, requestedName)
        byId[id] = record
        bindLocked(record, connection)
        repo.addOrUpdatePlayer(id, address, record.name, blasterMac)
        if (phase == SessionPhase.RUNNING && blasterMac.isNotEmpty()) cacheByMac[blasterMac] = record
        return HelloResult(HelloResult.Kind.JOINED, record, null)
    }

    /** Points [connection] at [record]; returns the record's previous connection, if any. */
    private fun bindLocked(record: PlayerRecord, connection: Connection): Connection? {
        val other = connection.player
        if (other != null && other !== record && other.connection === connection) other.connection = null
        var previous = record.connection
        if (previous === connection) previous = null
        if (previous != null) {
            previous.player = null
            connections.remove(previous.connectionId)
        }
        record.connection = connection
        connection.player = record
        return previous
    }

    /**
     * [rawRequested], sanitized ([PlayerNames]), unless that is empty, "Player", profane or
     * (ignoring case) another player's name; then a random unused name.
     */
    private fun uniqueName(playerId: Byte, rawRequested: String?): String {
        val requested = PlayerNames.sanitize(rawRequested)
        val usedNames = HashSet<String>()
        for ((id, name) in repo.teamPlayerNameMap) {
            if (id != playerId) usedNames += name
        }
        val isDuplicate = usedNames.any { it != null && it.equals(requested, ignoreCase = true) }
        if (requested.isEmpty() || requested.equals("Player", ignoreCase = true)
            || ProfanityFilter.containsProfanity(requested) || isDuplicate) {
            return NameGenerator.getRandomUniqueName(usedNames)
        }
        return requested
    }

    /** The player behind [connection] asked for a new name; returns the name it got, or null. */
    @Synchronized
    fun onNameChange(connection: Connection, requested: String?): String? {
        val record = connection.player ?: return null
        record.name = uniqueName(record.playerId, requested)
        repo.teamPlayerNameMap[record.playerId] = record.name
        return record.name
    }

    /** The connection was dropped or closed. */
    enum class Disconnect { NO_PLAYER, KEPT, REMOVED }

    /**
     * [connection] is gone: dropped, timed out, or the player left on purpose. A player in the
     * game's roster stays, disconnected, with its score until the reset (Q12). Anyone else is
     * removed.
     */
    @Synchronized
    fun onDisconnect(connection: Connection): Disconnect {
        connections.remove(connection.connectionId)
        val record = connection.player
        connection.player = null
        if (record == null) return Disconnect.NO_PLAYER
        if (record.connection !== connection) return Disconnect.NO_PLAYER // already replaced by a newer connection
        record.connection = null
        val cached = record.blasterMac.isNotEmpty() && cacheByMac[record.blasterMac] === record
        if (phase == SessionPhase.LOBBY || !cached) {
            removeRecordLocked(record)
            repo.removePlayer(record.playerId, phase == SessionPhase.RUNNING)
            return Disconnect.REMOVED
        }
        return Disconnect.KEPT
    }

    /** What to do with a reliable message (reconnect design §3.4). */
    enum class ReliableCheck {
        /** New: apply it. */
        APPLY,
        /** Already applied (its ack was lost): drop it. */
        DUPLICATE,
        /** For another game, or the lobby: drop it. */
        STALE,
        /** The connection has not said hello: keep it unacknowledged; it comes back in the hello. */
        NO_PLAYER,
    }

    /**
     * De-duplicates reliable message [seq] of game [gameId] from [connection]'s player. APPLY
     * records it as applied, so the caller must apply it.
     */
    @Synchronized
    fun checkReliable(connection: Connection, seq: Int, gameId: Long): ReliableCheck {
        val record = connection.player ?: return ReliableCheck.NO_PLAYER
        if (phase == SessionPhase.LOBBY || gameId != this.gameId) return ReliableCheck.STALE
        if (seq <= record.lastAppliedSeq) return ReliableCheck.DUPLICATE
        record.lastAppliedSeq = seq
        return ReliableCheck.APPLY
    }

    /**
     * The player behind [sender] was eliminated by [shooterId]. The victim always loses the life
     * (or gains an elimination); the shooter scores unless it is the victim or a teammate (its
     * own or a teammate's grenade). Counted during and after a game; null (ignored) in the lobby.
     */
    @Synchronized
    fun onEliminated(sender: Connection, shooterId: Byte): Elimination? {
        if (phase == SessionPhase.LOBBY) return null
        val victim = sender.player
        if (victim != null) victim.eliminated++
        val shooter = byId[shooterId]
        val credited = shooter != null && (victim == null || !sameSide(shooter.playerId, victim.playerId))
        val shooterPoints = if (credited) ++shooter!!.points else 0
        // Every applied elimination goes in the matrix, credited or not (score matrix design §3.2).
        if (victim != null && shooter != null) {
            val key = pairKey(shooter.playerId, victim.playerId)
            eliminationCounts[key] = (eliminationCounts[key] ?: 0) + 1
        }
        return Elimination(victim?.playerId ?: 0, shooterId, credited, shooterPoints)
    }

    /** The same player, or teammates in a team game. */
    private fun sameSide(a: Byte, b: Byte): Boolean {
        if (a == b) return true
        val layout = settings.teamLayout
        return !layout.mode.isFreeForAll && layout.teamOf(a) == layout.teamOf(b)
    }

    /** Removes the player and closes nothing; returns its connection (send it SERVERCANCEL and close it). */
    @Synchronized
    fun kick(playerId: Byte): Connection? {
        val record = byId[playerId]
        var connection: Connection? = null
        if (record != null) {
            connection = record.connection
            removeRecordLocked(record)
            if (connection != null) {
                connections.remove(connection.connectionId)
                connection.player = null
            }
        }
        repo.removePlayer(playerId, false)
        return connection
    }

    private fun removeRecordLocked(record: PlayerRecord) {
        if (byId[record.playerId] === record) {
            byId.remove(record.playerId)
            purgeMatrixLocked(record.playerId)
        }
        if (record.blasterMac.isNotEmpty() && cacheByMac[record.blasterMac] === record)
            cacheByMac.remove(record.blasterMac)
        record.connection = null
    }

    // ---------------------------------------------------------------------------------------
    // Score matrix
    // ---------------------------------------------------------------------------------------

    /** Who eliminated whom in the current game: every player in id order, and the non-zero cells. */
    @Synchronized
    fun scoreMatrix(): Matrix {
        val layout = settings.teamLayout
        val players = byId.values.map {
            val team = if (layout.mode.isFreeForAll) 1 else layout.teamOf(it.playerId)
            MatrixPlayer(it.playerId, it.name, team, it.connection != null)
        }
        val cells = eliminationCounts.entries
            .sortedBy { it.key }
            .map { (key, count) -> MatrixCell((key shr 8).toByte(), key.toByte(), count) }
        return Matrix(players, cells, gameId)
    }

    /** Forgets [playerId]'s row and column, so a later player given the id starts at zero. */
    private fun purgeMatrixLocked(playerId: Byte) {
        val id = playerId.toInt() and 0xFF
        eliminationCounts.keys.removeAll { (it shr 8) == id || (it and 0xFF) == id }
    }

    // ---------------------------------------------------------------------------------------
    // Renumbering
    // ---------------------------------------------------------------------------------------

    /**
     * The server operator moved [oldId] to [newId]. Returns the player, or null if there is none
     * or it is not the lobby (ids are reserved by the game's roster).
     */
    @Synchronized
    fun movePlayer(oldId: Byte, newId: Byte): PlayerRecord? {
        if (phase != SessionPhase.LOBBY) return null
        val team = repo.calcNetworkTeam(newId)
        val record = byId[oldId]
        if (record != null) {
            val other = byId[newId]
            if (other != null && other !== record) return null
            byId.remove(oldId)
            record.playerId = newId
            record.team = team
            byId[newId] = record
        }
        repo.reassignPlayer(oldId, newId)
        updateGpsTeam(newId, team)
        return record
    }

    /**
     * The player behind [connection] asked to move to [targetTeam]. Returns the player with its
     * new id, or null outside the lobby or if there is no open slot on that team.
     */
    @Synchronized
    fun changeTeam(connection: Connection, targetTeam: Int): PlayerRecord? {
        val record = connection.player
        if (record == null || phase != SessionPhase.LOBBY) return null
        val occupied = HashSet(repo.teamIPMap.keys)
        occupied.addAll(byId.keys)
        occupied.remove(record.playerId)
        val newId = repo.findOpenPlayerIDOnTeam(targetTeam, occupied)
        if (newId.toInt() == 0) return null
        val oldId = record.playerId
        byId.remove(oldId)
        record.playerId = newId
        record.team = targetTeam
        byId[newId] = record
        repo.reassignPlayer(oldId, newId)
        updateGpsTeam(newId, targetTeam)
        return record
    }

    /**
     * [firstId] and [secondId] trade ids, and with them teams. Returns both players, or null
     * outside the lobby or if either id has no player.
     */
    @Synchronized
    fun swapPlayers(firstId: Byte, secondId: Byte): Pair<PlayerRecord, PlayerRecord>? {
        if (phase != SessionPhase.LOBBY || firstId == secondId) return null
        val first = byId[firstId] ?: return null
        val second = byId[secondId] ?: return null
        first.playerId = secondId
        first.team = repo.calcNetworkTeam(secondId)
        second.playerId = firstId
        second.team = repo.calcNetworkTeam(firstId)
        byId[secondId] = first
        byId[firstId] = second
        repo.swapPlayers(firstId, secondId)
        updateGpsTeam(firstId, second.team)
        updateGpsTeam(secondId, first.team)
        return first to second
    }

    /**
     * Renumbers every player for the current team mode and team sizes, in id order or shuffled.
     * Players that do not fit are removed. Lobby only; elsewhere nothing changes.
     */
    @Synchronized
    fun rebalance(randomize: Boolean): Rebalance {
        if (phase != SessionPhase.LOBBY) return Rebalance(ArrayList(), ArrayList())
        val layout = settings.teamLayout
        val maxPlayers = GameSettingsRepository.MAX_PLAYER_ID.toInt()
        val players = ArrayList(byId.values)
        if (randomize) players.shuffle()

        val assigned = ArrayList<PlayerRecord>()
        val dropped = ArrayList<Connection?>()
        val oldToNew = LinkedHashMap<Byte, Byte>()
        val newTeams = HashMap<Byte, Int>()

        if (layout.mode.isFreeForAll) {
            var nextId = 1
            for (record in players) {
                if (nextId > maxPlayers) {
                    dropped += record.connection
                    continue
                }
                val newId = (nextId++).toByte()
                oldToNew[record.playerId] = newId
                newTeams[newId] = newId.toInt()
                assigned += record
            }
        } else {
            val usedIds = HashSet<Byte>()
            val numTeams = layout.mode.teamCount
            val sizes = layout.teamSizes()
            val teamPointers = IntArray(numTeams + 1)
            val teamLimits = IntArray(numTeams + 1)
            val teamCounts = IntArray(numTeams + 1)
            for (t in 1..numTeams) {
                teamPointers[t] = layout.teamStartId(t)
                teamLimits[t] = layout.teamEndId(t)
            }
            for (record in players) {
                var targetTeam = findBestTeamForRebalance(sizes, numTeams, teamPointers, teamLimits, teamCounts)
                var newId: Byte = 0
                if (targetTeam != -1) {
                    newId = teamPointers[targetTeam].toByte()
                    teamPointers[targetTeam]++
                    teamCounts[targetTeam]++
                } else {
                    for (i in 1..maxPlayers) {
                        if (i.toByte() !in usedIds) {
                            newId = i.toByte()
                            targetTeam = layout.teamOf(newId)
                            break
                        }
                    }
                }
                if (newId.toInt() == 0) {
                    dropped += record.connection
                    continue
                }
                usedIds += newId
                oldToNew[record.playerId] = newId
                newTeams[newId] = targetTeam
                assigned += record
            }
        }

        for (record in players) {
            if (record !in assigned) removeRecordLocked(record)
        }
        val droppedConnections = dropped.filterNotNull()
        for (connection in droppedConnections) {
            connections.remove(connection.connectionId)
            connection.player = null
        }
        byId.clear()
        for (record in assigned) {
            val newId = oldToNew.getValue(record.playerId)
            record.playerId = newId
            record.team = newTeams.getValue(newId)
            byId[newId] = record
        }
        repo.remapPlayers(oldToNew)
        for (record in assigned) updateGpsTeam(record.playerId, record.team)
        return Rebalance(assigned, droppedConnections)
    }

    private fun updateGpsTeam(playerId: Byte, team: Int) {
        val gps = repo.gpsData[playerId] ?: return
        gps.team = team
        gps.hasUpdate = true
    }

    private companion object {
        fun pairKey(shooter: Byte, victim: Byte): Int = ((shooter.toInt() and 0xFF) shl 8) or (victim.toInt() and 0xFF)

        fun findBestTeamForRebalance(sizes: IntArray, numTeams: Int, teamPointers: IntArray, teamLimits: IntArray, teamCounts: IntArray): Int {
            var minRatio = Double.MAX_VALUE
            var bestTeam = -1
            for (t in 1..numTeams) {
                if (teamPointers[t] <= teamLimits[t]) {
                    val capacity = sizes[t - 1]
                    val ratio = if (capacity > 0) teamCounts[t].toDouble() / capacity else 1.0
                    if (ratio < minRatio) {
                        minRatio = ratio
                        bestTeam = t
                    }
                }
            }
            return bestTeam
        }
    }
}
