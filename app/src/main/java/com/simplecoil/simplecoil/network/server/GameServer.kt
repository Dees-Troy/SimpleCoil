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
import androidx.annotation.VisibleForTesting
import com.simplecoil.simplecoil.data.model.PlayerSettings
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.network.EventBus
import com.simplecoil.simplecoil.network.ServerCommand
import com.simplecoil.simplecoil.network.ServerEvent
import com.simplecoil.simplecoil.network.ServerMessage
import com.simplecoil.simplecoil.network.WireCodec
import com.simplecoil.simplecoil.network.WireProtocol
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The dedicated server (network design §7.1 step 6): accepts the clients, reads each one on its
 * own thread, and runs every handler and state change on one dispatcher, in one order: frames
 * from every connection, the operator's commands, the GPS tick and the game clock (§5.5). The
 * dispatcher never blocks on I/O: sends only queue frames on their connections.
 *
 * A plain class; TcpServer, the service, hosts it.
 */
class GameServer(
    private val settings: GameSettingsRepository,
    private val players: PlayerRepository,
    private val gameState: GameStateRepository,
    private val events: EventBus<ServerEvent>,
    /** Monotonic milliseconds that count deep sleep. */
    clock: () -> Long,
    /** Saves an admin's team mode as the operator's own choice. */
    saveTeamMode: (TeamMode) -> Unit = {},
) {
    @Volatile
    private var keepListening = false

    /** The server socket is open (tests wait for it). */
    @Volatile
    var isAccepting = false
        private set

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val lock = Any()

    @get:VisibleForTesting
    val roster = GameRoster(settings, players)
    private val snapshots = SnapshotBuilder(roster, settings, players, gameState)
    private val handler = ServerMessageHandler(this, roster, settings, players, gameState, events, saveTeamMode)

    @Volatile
    private var dispatcherThread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sc-server").apply {
            isDaemon = true
            dispatcherThread = this
        }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Server task failed", e) // must not take the server down (N5)
    })

    @Volatile
    private var gpsRunning = false
    private var gpsTick: Job? = null
    private val gpsIntervalCount = AtomicInteger(0)

    /** Enforces the time limit (dedicated server design §5.3); touched only on the dispatcher. */
    private val gameClock = ServerGameClock(dispatcher, clock, gameState,
        onStateChanged = { events.publish(ServerEvent.ClockChanged(it)) },
        onTimeUp = {
            events.publish(ServerEvent.TimeExpired)
            endGame()
        })

    // ---------------------------------------------------------------------------------------
    // The dispatcher
    // ---------------------------------------------------------------------------------------

    /** Runs [body] on the dispatcher: now if already on it, otherwise queued behind what's there. */
    private fun runOnDispatcher(body: () -> Unit) {
        if (Thread.currentThread() === dispatcherThread) {
            body()
            return
        }
        try {
            executor.execute {
                try {
                    body()
                } catch (e: RuntimeException) {
                    // One failed handler must not stop the server handling everything else (N5)
                    Log.e(TAG, "Server task failed", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Server is shut down; dropping a task")
        }
    }

    /** Runs [body] on the dispatcher and waits for its result. Safe: the dispatcher never blocks. */
    private fun <T> callOnDispatcher(onFailure: T, body: () -> T): T {
        try {
            if (Thread.currentThread() === dispatcherThread) return body()
            return executor.submit(Callable { body() }).get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "Server task failed", e)
        }
        return onFailure
    }

    // ---------------------------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------------------------

    /** Queues [message] for every connection. Never blocks: each connection has its own writer (N1, N2). */
    fun sendToAll(message: String) {
        for (connection in roster.connections()) connection.send(message)
    }

    /**
     * Sends to [playerId]'s connection. A disconnected player misses it: the snapshot it gets
     * when it comes back carries everything it needs (D11).
     */
    fun sendToPlayer(message: String, playerId: Byte) {
        roster.record(playerId)?.connection?.send(message)
    }

    /** Sends to [playerId]'s teammates, and to it too when [includePlayer]. */
    fun sendToTeam(message: String, playerId: Byte, includePlayer: Boolean) {
        val player = roster.record(playerId) ?: return
        if (includePlayer) player.connection?.send(message)
        for (record in roster.recordsOnTeam(player.team)) {
            if (record !== player) record.connection?.send(message)
        }
    }

    // ---------------------------------------------------------------------------------------
    // The game
    // ---------------------------------------------------------------------------------------

    fun resetScores() = roster.resetScores()

    /** Starts a game from the lobby. False if not in the lobby (after a game, reset first) or nobody is there. */
    fun startGame(): Boolean = callOnDispatcher(false) {
        Log.d(TAG, "TcpServer.startGame called. Clients: " + roster.size())
        if (!roster.startGame()) return@callOnDispatcher false
        syncGameState()
        // we want to make sure that we have sent the start game message to all clients before publishing that the game has started locally
        sendToAll(command(ServerCommand.StartGame))
        sendAllGameInfo(SEND_ALL)
        events.publish(ServerEvent.GameStarted)
        events.publish(ServerEvent.PlayerDataChanged(null))
        val timed = settings.gameLimit and GameSettingsRepository.GAME_LIMIT_TIME != 0
        gameClock.start(if (timed) settings.timeLimit else null, settings.startTime)
        true
    }

    /**
     * Ends a running game (POST_GAME). Every player stays, connected or not, so the game's roster
     * can still come back until the reset. Does nothing if no game is running.
     */
    fun endGame(): Unit = runOnDispatcher {
        Log.d(TAG, "TcpServer.endGame called. Clients: " + roster.size())
        if (!roster.endGame()) return@runOnDispatcher
        syncGameState()
        gameClock.stop()
        sendPlayerData(SEND_ALL)
        sendToAll(command(ServerCommand.EndGame))
        // after ENDGAME, so the clients have ended the game before they see game state NONE;
        // carries the POST_GAME phase, which swaps End Game for Reset in the admin menu
        sendAllGameInfo(SEND_ALL)
        players.gpsData.clear()
        events.publish(ServerEvent.GameEnded)
    }

    /**
     * Reset (reconnect design §3.1), POST_GAME → LOBBY: removes the disconnected players, zeroes
     * the scores, forgets the game's roster, then tells the clients and publishes
     * ServerEvent.SessionReset. Returns false (and does nothing) outside POST_GAME, so a
     * duplicate or late reset is harmless. Safe on the main thread: it waits for the dispatcher,
     * which never blocks.
     */
    fun resetSession(): Boolean = callOnDispatcher(false) { resetSessionNow() }

    private fun resetSessionNow(): Boolean {
        val removed = roster.reset() ?: return false
        Log.d(TAG, "Session reset: removed ${removed.size} disconnected players")
        syncGameState()
        gameClock.stop()
        requestFullGpsUpdate()
        sendPlayerData(SEND_ALL)
        sendAllGameInfo(SEND_ALL)
        events.publish(ServerEvent.SessionReset)
        events.publish(ServerEvent.PlayerDataChanged(null))
        return true
    }

    /**
     * Changing the team mode, team sizes, game limits or GPS mode, or sorting teams, after a game
     * resets first (§3.1.1). Call it before applying the change; true if it reset.
     */
    fun resetIfPostGame(): Boolean = resetSession()

    val sessionPhase: SessionPhase get() = roster.phase()

    /** The game clock, for the screen to render (any thread). */
    val clockState: ClockState get() = gameClock.state

    /** [playerId]'s score; null if there is no such player. */
    fun score(playerId: Byte): GameRoster.Score? = roster.score(playerId)

    /** GameStateRepository follows the session: running only in RUNNING (D8). */
    private fun syncGameState() {
        gameState.gameState = if (roster.isRunning()) GameStateRepository.GAME_STATE_RUNNING else GameStateRepository.GAME_STATE_NONE
    }

    fun switchPlayerTeam(oldPlayerId: Byte, newPlayerId: Byte): Unit = runOnDispatcher {
        Log.d(TAG, "switchPlayerTeam: moving player $oldPlayerId to new ID $newPlayerId")
        roster.movePlayer(oldPlayerId, newPlayerId)?.connection?.send(command(ServerCommand.ServerReply(newPlayerId)))
        sendPlayerData(SEND_ALL)
        sendAllGameInfo(SEND_ALL)
        events.publish(ServerEvent.PlayerDataChanged(null))
    }

    fun kickPlayer(playerId: Byte): Unit = runOnDispatcher {
        Log.d(TAG, "kickPlayer: kicking player $playerId")
        roster.kick(playerId)?.let { connection ->
            connection.send(command(ServerCommand.ServerCancel))
            connection.closeAfterPending()
        }
        requestFullGpsUpdate()
        sendPlayerData(SEND_ALL)
        sendAllGameInfo(SEND_ALL)
        events.publish(ServerEvent.PlayerLeft)
        events.publish(ServerEvent.PlayerDataChanged(null))
    }

    fun rebalanceAllPlayers(randomize: Boolean): Unit = runOnDispatcher {
        if (roster.isRunning()) {
            Log.w(TAG, "rebalanceAllPlayers: Game is running, skipping team rebalance!")
            return@runOnDispatcher
        }
        resetSessionNow() // after a game: the roster renumbers only in the lobby
        val result = roster.rebalance(randomize)
        for (connection in result.dropped) {
            Log.w(TAG, "Server full during rebalance! Closing client ${connection.connectionId}")
            connection.close()
        }
        for (record in result.assigned) record.connection?.send(command(ServerCommand.ServerReply(record.playerId)))
        sendPlayerData(SEND_ALL)
        sendAllGameInfo(SEND_ALL)
        events.publish(ServerEvent.PlayerDataChanged(null))
    }

    // ---------------------------------------------------------------------------------------
    // Snapshots
    // ---------------------------------------------------------------------------------------

    /** Sends the game info to everyone; [id] (or SEND_ALL) also gets its own game update. */
    fun sendAllGameInfo(id: Int): Unit = runOnDispatcher {
        val info = snapshots.gameInfo() ?: return@runOnDispatcher
        val allMessage = WireCodec.encode(info)
        if (id == SEND_ALL) {
            sendToAll(allMessage)
            return@runOnDispatcher
        }
        val idMessage = WireCodec.encode(info.withPlayerUpdate(snapshots.playerUpdate(id.toByte())))
        for (connection in roster.connections()) {
            val player = connection.player
            connection.send(if (player != null && player.playerId.toInt() == id) idMessage else allMessage)
        }
    }

    /** Sends the scoreboard to [playerId], or SEND_ALL, and to the screen. Runs on the dispatcher. */
    fun sendPlayerData(playerId: Int) {
        val data = snapshots.playerData() ?: return
        val message = WireCodec.encode(data)
        if (playerId != SEND_ALL) sendToPlayer(message, playerId.toByte()) else sendToAll(message)
        events.publish(ServerEvent.PlayerDataChanged(WireCodec.playerDataJson(data)))
    }

    /** Announces every grenade's owner, if any grenade is paired. Runs on the dispatcher. */
    fun sendGrenadePairings() {
        snapshots.grenadePairings()?.let { sendToAll(WireCodec.encode(it)) }
    }

    /**
     * Sends everyone's settings, after copying [playerId]'s to everyone ([applyAll]) or to its
     * team ([applyTeam]), and makes [allowPlayerSettings] the rule.
     */
    fun sendPlayerSettings(playerId: Int, applyAll: Boolean, applyTeam: Boolean, allowPlayerSettings: Boolean): Unit = runOnDispatcher {
        copyPlayerSettings(playerId, applyAll, applyTeam)
        val list = snapshots.playerSettings() ?: return@runOnDispatcher
        settings.isAllowPlayerSettings = allowPlayerSettings
        sendToAll(WireCodec.encode(ServerMessage.PlayerSettings(allowPlayerSettings, list)))
    }

    /**
     * The operator saved [playerId]'s settings dialog, as one operation on the dispatcher (S11):
     * stores the settings ([update] fills them in), moves the player to [targetTeam] (0: stay),
     * copies the settings to everyone ([applyAll]) or the player's new team ([applyTeam]), and
     * sends everyone's. Returns false if [targetTeam] had no open slot; the rest is still done,
     * under the player's old id.
     */
    fun applyPlayerSettings(
        playerId: Byte,
        update: (PlayerSettings) -> Unit,
        applyAll: Boolean,
        applyTeam: Boolean,
        targetTeam: Int,
    ): Boolean = callOnDispatcher(false) {
        synchronized(players) {
            update(players.playerSettings.getOrPut(playerId) { PlayerSettings() })
        }
        var targetId = playerId
        var moved = true
        if (targetTeam != 0) {
            val newId = players.findOpenPlayerIDOnTeam(targetTeam)
            if (newId.toInt() == 0) {
                moved = false
            } else {
                switchPlayerTeam(playerId, newId)
                targetId = newId
            }
        }
        sendPlayerSettings(targetId.toInt(), applyAll, applyTeam, settings.isAllowPlayerSettings)
        moved
    }

    /** Copies [playerId]'s settings to every other id, or to its teammates' ids. */
    private fun copyPlayerSettings(playerId: Int, applyAll: Boolean, applyTeam: Boolean) {
        if (playerId == SEND_ALL || !(applyAll || applyTeam)) return
        val layout = settings.teamLayout
        if (!applyAll && layout.mode.isFreeForAll) return
        synchronized(players) {
            val src = players.playerSettings[playerId.toByte()] ?: return
            val sourceTeam = layout.teamOf(playerId)
            for (x in 1..GameSettingsRepository.MAX_PLAYER_ID) {
                if (x == playerId || (!applyAll && layout.teamOf(x) != sourceTeam)) continue
                val target = players.playerSettings.getOrPut(x.toByte()) { PlayerSettings() }
                target.health = src.health
                target.shots = src.shots
                target.reloadTime = src.reloadTime
                target.reloadOnEmpty = src.reloadOnEmpty
                target.manualRespawn = src.manualRespawn
                target.spawnTime = src.spawnTime
                target.respawnShieldTime = src.respawnShieldTime
                target.damage = src.damage
                target.overrideLives = src.overrideLives
                target.lives = src.lives
                target.allowShotModeSingle = src.allowShotModeSingle
                target.allowShotModeBurst3 = src.allowShotModeBurst3
                target.allowShotModeAuto = src.allowShotModeAuto
                target.recoilSetting = src.recoilSetting
                target.firingMode = src.firingMode
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // GPS
    // ---------------------------------------------------------------------------------------

    /** The next GPS tick sends every position, not only the ones that changed. */
    fun requestFullGpsUpdate(): Unit = gpsIntervalCount.set(SEND_ALL_GPS_INTERVAL)

    /**
     * Starts relaying GPS: every second, the positions that changed since the last tick, and all
     * of them every 20th tick. Runs while the server listens and GPS is on.
     */
    @Synchronized
    fun startGpsRelay() {
        if (gpsRunning) return
        gpsRunning = true
        gpsIntervalCount.set(0)
        // Fixed delay, like the Handler it replaced: a frozen process must not catch up with a burst of ticks
        gpsTick = scope.launch {
            while (true) {
                delay(GPS_UPDATE_INTERVAL)
                tickGps()
            }
        }
    }

    private fun tickGps() {
        if (!gpsRunning) return // a tick already running when the relay stopped
        val currentInterval = gpsIntervalCount.get()
        val full = currentInterval >= SEND_ALL_GPS_INTERVAL
        val positions = snapshots.gpsPositions(full)
        if (positions != null) {
            if (positions.isNotEmpty()) sendToAll(WireCodec.encode(ServerMessage.GpsUpdate(positions, full)))
            if (gpsIntervalCount.incrementAndGet() >= SEND_ALL_GPS_INTERVAL) gpsIntervalCount.set(0)
        }
        if (!keepListening || !settings.isUseGPS) stopGpsRelay()
    }

    @Synchronized
    private fun stopGpsRelay() {
        gpsTick?.cancel()
        gpsTick = null
        gpsRunning = false
    }

    // ---------------------------------------------------------------------------------------
    // Listening
    // ---------------------------------------------------------------------------------------

    /** Opens the server socket and accepts clients, on its own thread. */
    fun start() {
        synchronized(lock) {
            if (keepListening) {
                Log.d(TAG, "Server already listening")
                return
            }
            keepListening = true
        }
        containedThread("sc-accept") { acceptLoop() }.start()
    }

    private fun acceptLoop() {
        roster.clear()
        syncGameState()
        players.gpsData.clear()
        players.clearGrenadePairings()
        var ss: ServerSocket? = null
        try {
            ss = ServerSocket(WireProtocol.TCP_SERVER_PORT)
            ss.soTimeout = 1000
            synchronized(lock) {
                if (!keepListening) return // stopped while binding
                serverSocket = ss
                isAccepting = true
            }
            Log.d(TAG, "TCP Server listening")
            while (keepListening) {
                try {
                    // Wait for new clients
                    val s = ss.accept()
                    s.soTimeout = READ_WAIT_MS
                    val connection = roster.accept(s)
                    startReader(connection)
                    if (connection.connectionId <= 1 && settings.isUseGPS) startGpsRelay()
                } catch (e: SocketTimeoutException) {
                    // do nothing
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Error accepting a client", e)
                }
            }
        } catch (e: IOException) {
            if (keepListening) Log.w(TAG, "Server socket failed", e)
        } finally {
            try {
                ss?.close()
            } catch (e: IOException) {
                // already closed
            }
            synchronized(lock) {
                // Unless a stop and a new start came first: that server is not ours to stop
                if (serverSocket === ss) {
                    serverSocket = null
                    keepListening = false
                    isAccepting = false
                }
            }
        }
        Log.d(TAG, "TCP Server done")
    }

    /**
     * The connection's reader thread: hands every complete frame to the dispatcher, pings a
     * silent peer after 1 s and 2 s and drops it after 3.5 s, measured from its last byte (N3).
     */
    private fun startReader(connection: Connection) {
        containedThread("sc-read-${connection.connectionId}") { readLoop(connection) }.start()
    }

    private fun readLoop(client: Connection) {
        var pinged1 = false
        var pinged2 = false
        try {
            while (keepListening) {
                val raw = client.poll()
                if (raw != null) {
                    pinged1 = false
                    pinged2 = false
                    runOnDispatcher { handler.handleFrame(client, raw) }
                    continue
                }
                val silence = client.silenceMillis()
                if (silence >= SILENCE_DROP_MS) {
                    Log.d(TAG, "no reply so dropping client ${client.connectionId}")
                    break
                } else if (silence >= SILENCE_PING2_MS) {
                    if (!pinged2) client.send(WireProtocol.PING)
                    pinged1 = true
                    pinged2 = true
                } else if (silence >= SILENCE_PING1_MS && !pinged1) {
                    client.send(WireProtocol.PING)
                    pinged1 = true
                }
            }
        } catch (e: IOException) {
            // Closed by the server, closed or reset by the peer, or a frame that isn't UTF-8
            Log.d(TAG, "connection ${client.connectionId} closed: ${e.message}")
        }
        if (keepListening) runOnDispatcher { handler.removeClient(client) }
    }

    /** Stops accepting and closes every connection once what's queued for it has been written. */
    fun stop() {
        val ss: ServerSocket?
        synchronized(lock) {
            keepListening = false
            isAccepting = false
            ss = serverSocket
            serverSocket = null
        }
        try {
            ss?.close() // accept() returns at once, so a restart can bind the port (N6)
        } catch (e: IOException) {
            // already closed
        }
        for (connection in roster.connections()) {
            connection.closeAfterPending() // after a SERVERCANCEL sent just before the stop
        }
        stopGpsRelay()
        runOnDispatcher { gameClock.stop() }
    }

    /** The service is going away. */
    fun shutdown() {
        stopGpsRelay()
        scope.cancel()
        executor.shutdown()
    }

    companion object {
        private const val TAG = "TCPServer"

        /** Every player, in place of one player's id. */
        const val SEND_ALL = -100

        /** Read timeout on every socket: the granularity of ping and drop timing (network design Q5). */
        private const val READ_WAIT_MS = 100

        /** Drop a connection after this long without a byte from it; ping it after 1 s and 2 s. */
        const val SILENCE_DROP_MS = 3500L
        private const val SILENCE_PING1_MS = 1000L
        private const val SILENCE_PING2_MS = 2000L

        private const val GPS_UPDATE_INTERVAL = 1000L

        /** We will send all GPS data regardless of whether there was a GPS change every xx intervals. */
        private const val SEND_ALL_GPS_INTERVAL = 20

        /** A MESG frame carrying [command]. */
        private fun command(command: ServerCommand): String = WireCodec.encode(ServerMessage.Command(command))

        /** A thread whose body can't crash the process: a RuntimeException is logged and ends it (N5). */
        private fun containedThread(name: String, body: () -> Unit): Thread = Thread({
            try {
                body()
            } catch (e: RuntimeException) {
                Log.e(TAG, "Server thread failed", e)
            }
        }, name).apply { isDaemon = true }
    }
}
