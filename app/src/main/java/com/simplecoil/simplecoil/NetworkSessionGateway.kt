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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.team.TeamLayout
import com.simplecoil.simplecoil.domain.session.SessionNetwork
import com.simplecoil.simplecoil.network.ClientCommand
import com.simplecoil.simplecoil.network.ClientEvent
import com.simplecoil.simplecoil.network.ClientMessage
import com.simplecoil.simplecoil.network.EventBus
import com.simplecoil.simplecoil.network.WireCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The play screen's network session (R13/R14 transport): binds UDPListenerService and TcpClient,
 * exposes the client bus their events go to as [events], builds the TCP message strings, holds
 * the session's network flags and scores, and serves BlasterSessionEngine as its [SessionNetwork].
 * The phone is always a client; the only server is DedicatedServerActivity.
 *
 * App-scoped; main thread unless noted.
 */
class NetworkSessionGateway(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: GameSettingsRepository,
    private val players: PlayerRepository,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val bus: EventBus<ClientEvent> = EventBus(name = "ClientEvents"),
) : SessionNetwork {

    /** The client bus: network events and service lifecycle, in order. Not replayed. */
    val events: SharedFlow<ClientEvent> = bus.events

    var udp: UDPListenerService? = null
        private set
    var tcpClient: TcpClient? = null
        private set

    private var udpConnection: ServiceConnection? = null
    private var tcpClientConnection: ServiceConnection? = null

    override var useNetwork = false
    /** Main thread only. */
    var score = 0
    /** Main thread only. */
    var teamScore = 0

    // ---------------------------------------------------------------------------------------
    // Service bindings
    // ---------------------------------------------------------------------------------------

    /**
     * Starts the two network services and binds each one that isn't bound yet. The activity
     * calls this on every resume; it used to bind again each time, leaving one more live
     * ServiceConnection per resume (B4).
     */
    fun bindServices() {
        if (udpConnection == null) bindUdp() else startServiceSafely(Intent(context, UDPListenerService::class.java))
        if (tcpClientConnection == null) bindTcpClient() else startServiceSafely(Intent(context, TcpClient::class.java))
    }

    private fun bindUdp() {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                udp = (service as UDPListenerService.LocalBinder).service
                emit(ClientEvent.UdpServiceConnected)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                emit(ClientEvent.UdpServiceDisconnected)
                udp = null
            }
        }
        udpConnection = connection
        val intent = Intent(context, UDPListenerService::class.java)
        startServiceSafely(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun bindTcpClient() {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                tcpClient = (service as TcpClient.LocalBinder).service
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                tcpClient = null
            }
        }
        tcpClientConnection = connection
        val intent = Intent(context, TcpClient::class.java)
        // Not a foreground start: TcpClient only goes foreground while its connection runs.
        startServiceSafely(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    /** Hands the network over to DedicatedServerActivity (what its launch button did). */
    fun releaseForDedicatedServer() {
        tcpClient?.stopTcpClient() // takes its network notification down with it
        udpConnection?.let { context.unbindService(it) }
        tcpClientConnection?.let { context.unbindService(it) }
        udp = null
        udpConnection = null
        tcpClientConnection = null
    }

    /** Unbinds the current connections, ignoring ones that are already gone (the activity's onDestroy). */
    fun unbindServices() {
        for (connection in listOfNotNull(udpConnection, tcpClientConnection)) {
            try {
                context.unbindService(connection)
            } catch (e: Exception) {
                // nothing
            }
        }
        udpConnection = null
        tcpClientConnection = null
    }

    private fun startServiceSafely(intent: Intent) {
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start service directly: " + e.message)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Scoreboard (R14)
    // ---------------------------------------------------------------------------------------

    /** Scoreboard rows (indexed by player id), the local player's points, and points per team. */
    class ParsedPlayerData(val players: Array<PlayerDisplayData?>, val myPoints: Int?, val teamPoints: IntArray)

    /**
     * Parses player data JSON off the main thread, then on the main thread raises the local
     * player's score to its points, sets the team score and emits [ClientEvent.PlayerData].
     *
     * B7: the scores used to be written from the parsing thread while the main thread also wrote
     * them.
     */
    fun updatePlayerData(message: String?) {
        if (message.isNullOrEmpty()) return
        scope.launch {
            val parsed = withContext(parseDispatcher) { parsePlayerData(message, players, settings.teamLayout) } ?: return@launch
            parsed.myPoints?.let { score = maxOf(score, it) }
            val layout = settings.teamLayout
            if (!layout.mode.isFreeForAll) {
                val myTeamIdx = layout.teamOf(players.playerID) - 1
                if (myTeamIdx in 0..3) teamScore = parsed.teamPoints[myTeamIdx]
            }
            emit(ClientEvent.PlayerData(parsed.players))
        }
    }

    /** Server replied to a join: connect the TCP client (package-private in TcpClient). */
    fun startTcpClient() {
        tcpClient!!.startTcpClient()
    }

    // ---------------------------------------------------------------------------------------
    // SessionNetwork (BlasterSessionEngine)
    // ---------------------------------------------------------------------------------------

    override fun sendHit(playerId: Byte) {
        tcpClient?.sendTCPMessage(command(ClientCommand.Hit(playerId)))
    }

    override fun sendOut(playerId: Byte) {
        tcpClient?.sendTCPMessage(command(ClientCommand.Out(playerId)))
    }

    override fun sendShieldHit(playerId: Byte) {
        tcpClient?.sendTCPMessage(command(ClientCommand.ShieldHit(playerId)))
    }

    override fun sendShotFired() {
        tcpClient?.sendTCPMessage(command(ClientCommand.ShotFired))
    }

    override fun sendEliminated(playerId: Byte) {
        tcpClient?.sendReliable(WireCodec.text(ClientCommand.Eliminated(playerId)))
    }

    /** Our position, from the map's location updates. */
    fun sendLocation(latitude: Double, longitude: Double) {
        tcpClient?.sendLocation(latitude, longitude)
    }

    override fun sendPlayerGrenade() {
        tcpClient!!.sendPlayerGrenade() // as before: only called while networking
    }

    @androidx.annotation.VisibleForTesting
    internal fun attachForTest(udp: UDPListenerService?, tcpClient: TcpClient?) {
        this.udp = udp
        this.tcpClient = tcpClient
    }

    @androidx.annotation.VisibleForTesting
    internal fun emit(event: ClientEvent) {
        bus.publish(event)
    }

    companion object {
        private const val TAG = "NetworkSession"

        /**
         * Parses the server's player data JSON. Reads player settings under PlayerRepository's lock,
         * so a settings update can't land halfway through a player. Null when the JSON is malformed.
         */
        @JvmStatic
        fun parsePlayerData(message: String, repo: PlayerRepository, layout: TeamLayout): ParsedPlayerData? {
            val data = WireCodec.decodePlayerData(message) ?: return null
            val finalData = arrayOfNulls<PlayerDisplayData>(GameSettingsRepository.MAX_PLAYER_ID + 2)
            val teamPoints = IntArray(4)
            var myPoints: Int? = null
            synchronized(repo) {
                for (player in data.players) {
                    val playerData = PlayerDisplayData()
                    playerData.playerID = player.playerId.toByte()
                    playerData.playerName = player.name
                    playerData.points = player.points
                    if (playerData.playerID == repo.playerID) {
                        myPoints = maxOf(myPoints ?: playerData.points, playerData.points)
                    }
                    if (!layout.mode.isFreeForAll) {
                        val teamIdx = layout.teamOf(playerData.playerID) - 1
                        if (teamIdx in 0..3) teamPoints[teamIdx] += playerData.points
                    }
                    playerData.eliminated = player.eliminated
                    playerData.isConnected = player.connected ?: true
                    playerData.blasterType = (player.blasterType ?: 0).toByte()
                    playerData.hasGrenade = player.hasGrenade
                    val settings = repo.playerSettings[playerData.playerID]
                    if (settings == null) {
                        playerData.overrideLives = false
                        playerData.lives = 0
                    } else {
                        playerData.overrideLives = settings.overrideLives
                        playerData.lives = settings.lives
                    }
                    finalData[playerData.playerID.toInt()] = playerData
                }
            }
            return ParsedPlayerData(finalData, myPoints, teamPoints)
        }

        /** The MESG frame carrying [command]. */
        @JvmStatic
        fun command(command: ClientCommand): String = WireCodec.encode(ClientMessage.Command(command))

        /** Team change request for [playerId] to [targetTeam], trading places with [swapWith] if the team is full. */
        @JvmStatic
        @JvmOverloads
        fun teamChangeRequest(playerId: Byte, targetTeam: Int, swapWith: Byte? = null): String =
            WireCodec.encode(ClientMessage.TeamChangeRequest(playerId.toInt(), targetTeam, swapWith?.toInt()))
    }
}
