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

package com.simplecoil.simplecoil.network.client

import android.net.Network
import android.util.Log
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.network.ClientCommand
import com.simplecoil.simplecoil.network.ClientEvent
import com.simplecoil.simplecoil.network.ClientMessage
import com.simplecoil.simplecoil.network.EventBus
import com.simplecoil.simplecoil.network.WireCodec
import com.simplecoil.simplecoil.network.WirePending
import com.simplecoil.simplecoil.network.WirePlayerSettings
import com.simplecoil.simplecoil.network.server.SessionPhase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The play screen's network client: what it sends the server, built from the repositories, and
 * the [ClientConnection] and [ClientMessageHandler] that carry it. A plain class; TcpClient,
 * the service, hosts it.
 */
class GameClient(
    private val settings: GameSettingsRepository,
    private val players: PlayerRepository,
    gameState: GameStateRepository,
    events: EventBus<ClientEvent>,
    private val foreground: Foreground,
    network: () -> Network? = { null },
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The network notification, while the client is connected or rejoining. */
    interface Foreground {
        fun start(statusText: String)
        fun stop()
    }

    /** Reliable messages (eliminations) the server hasn't acknowledged yet (§3.4). */
    val outbox = ClientOutbox()
    private val handler = ClientMessageHandler(settings, players, outbox, events)

    private val connection = ClientConnection({ gameState.serverIP }, handler, events, object : ClientConnection.Listener {
        override fun onStarting() = foreground.start("SimpleCoil Network Connected")

        override fun onSessionStart() {
            players.gpsData.clear()
            outbox.clear()
            handler.startSession()
        }

        override fun onConnected(rejoin: Boolean) = sendPlayerInfo(rejoin)

        override fun onStopped() = foreground.stop()
    }, network, io)

    /** The server's session phase, from the latest game info. */
    val sessionPhase: SessionPhase get() = handler.sessionPhase

    /** The server's game id, from the latest game info. */
    val gameId: Long get() = handler.gameId

    /** Connects (and keeps reconnecting) to the server in GameStateRepository. */
    fun start() = connection.start()

    /** Queues [message] for the writer thread. Without a connection it is dropped. */
    fun sendTCPMessage(message: String) = connection.send(message)

    private fun send(message: ClientMessage) = connection.send(WireCodec.encode(message))

    /**
     * Sends [rmsg] (an ELIMINATED message) reliably: it stays in the outbox, whatever happens to
     * this write, until the server acknowledges it, and is replayed in the next hello (D1).
     * Writes are not retried on a live connection; TCP does that.
     */
    fun sendReliable(rmsg: String) {
        val entry = outbox.add(rmsg, handler.gameId)
        send(ClientMessage.Reliable(entry.rmsg, entry.seq, outbox.gameId))
    }

    fun sendPlayerNameChange() {
        send(ClientMessage.NameChange(players.playerID.toInt(), players.playerName))
    }

    /** The hello, then our grenade's pairing if there is one. */
    fun sendPlayerInfo(rejoin: Boolean) {
        if (rejoin) Log.d(TAG, "Attempting to rejoin server")
        send(hello(players.playerID, players.playerName, players.deviceAddress, rejoin, outbox))
        if (players.pairedGrenadeID.toInt() != 0) sendPlayerGrenade()
    }

    /** Our own settings, for the server to apply when it allows player settings. */
    fun sendPlayerSettings() {
        val playerId = players.playerID.toInt()
        send(ClientMessage.Settings(playerId, WirePlayerSettings(
            playerId = playerId,
            health = settings.fullHealth,
            reloadShots = settings.fullReload.toInt() and 0xFF,
            reloadTime = settings.reloadTime,
            reloadOnEmpty = settings.isReloadOnEmpty,
            manualRespawn = settings.isManualRespawn,
            spawnTime = settings.respawnTime,
            respawnShieldTime = settings.respawnShieldTime,
            damage = settings.damage,
            livesLimit = if (settings.isOverrideLives) settings.overrideLivesVal else null,
            shotModeSingle = settings.isAllowSingleShotMode,
            shotModeBurst3 = settings.isAllowBurst3ShotMode,
            shotModeAuto = settings.isAllowAutoShotMode,
            firingMode = settings.currentFiringMode,
            recoilSetting = null,
            isAdmin = null,
        )))
    }

    fun sendPlayerGrenade() {
        send(ClientMessage.GrenadePairing(players.playerID.toInt(), players.pairedGrenadeID.toInt()))
    }

    /** Reports our position (the map's location updates) to the server. */
    fun sendLocation(latitude: Double, longitude: Double) {
        send(ClientMessage.GpsLocation(latitude, longitude))
    }

    /** Stops once everything sent so far has been written (N11). */
    fun stop() = connection.stopAfterPending()

    /** Tells the server we are leaving, then stops: LEAVE is the last frame written. */
    fun leaveServer() {
        outbox.clear()
        if (connection.isRunning) {
            send(ClientMessage.Command(ClientCommand.Leave))
            stop()
        }
    }

    /** Waits up to [timeoutMillis] for everything sent so far to be written; false on timeout. Blocks. */
    fun awaitPendingWrites(timeoutMillis: Long) = connection.awaitPendingWrites(timeoutMillis)

    /** Wi-Fi is back: retry now. */
    fun onNetworkAvailable() = connection.onNetworkAvailable()

    /** The service is going away. */
    fun shutdown() = connection.shutdown()

    /** For tests: [handler]'s handling of one frame. */
    internal fun handleFrameForTest(raw: String): Boolean = handler.handle(raw)

    /** For tests: writes go to [output] as if connected. */
    internal fun attachOutputForTest(output: java.io.DataOutputStream?) = connection.attachOutputForTest(output)

    /** For tests: waits until everything sent so far has been written (or dropped). */
    internal fun awaitWritesForTest() = connection.awaitWritesForTest()

    companion object {
        private const val TAG = "TCPClient"

        /**
         * The hello: who we are, and the reliable messages not acknowledged yet. They ride in the
         * hello so the server applies them once it knows who we are and before it builds our
         * snapshot (D2, D3).
         */
        @JvmStatic
        fun hello(playerId: Byte, name: String, mac: String?, rejoin: Boolean, outbox: ClientOutbox): ClientMessage.Hello {
            val pending = outbox.pending().map { WirePending(it.rmsg, it.seq) }
            if (pending.isNotEmpty()) Log.d(TAG, "Replaying ${pending.size} unacknowledged messages")
            return ClientMessage.Hello(playerId.toInt(), name, mac, rejoin, outbox.gameId.takeIf { it != 0L }, pending)
        }
    }
}
