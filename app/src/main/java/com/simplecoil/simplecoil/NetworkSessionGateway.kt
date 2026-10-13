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

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.simplecoil.simplecoil.domain.session.SessionNetwork
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** Score update from the server, carried by NETMSG_LISTPLAYERS. */
data class GameUpdate(val score: Int, val eliminations: Int, val teamScore: Int, val timeRemaining: Long)

/** The NETMSG_* broadcasts the play screen handles, with their extras, plus service lifecycle. */
sealed interface NetworkEvent {
    data object ShotFired : NetworkEvent
    data class Hit(val playerId: Byte) : NetworkEvent
    data class Out(val playerId: Byte) : NetworkEvent
    data class Eliminated(val playerId: Byte) : NetworkEvent
    data object TeamEliminated : NetworkEvent
    data object Join : NetworkEvent
    data object Leave : NetworkEvent

    /**
     * [gameUpdate] when INTENT_HASGAMEUPDATE; [gameState] when INTENT_GAMESTATE is present;
     * [sessionPhase] (SessionPhase.wireValue) and [gameId] when the server sent them.
     */
    data class ListPlayers(
        val gameUpdate: GameUpdate?,
        val gameState: Int?,
        val sessionPhase: Int? = null,
        val gameId: Long? = null,
    ) : NetworkEvent
    data class PlayerDataUpdate(val json: String?) : NetworkEvent
    data object StartGame : NetworkEvent
    data object EndGame : NetworkEvent
    data class Error(val message: String?) : NetworkEvent
    data object VersionError : NetworkEvent
    data object SameTeam : NetworkEvent
    data object FailedToJoin : NetworkEvent
    data object ServerCancel : NetworkEvent

    /** [assignedPlayerId] when the reply carries one. */
    data class ServerReply(val assignedPlayerId: Byte?) : NetworkEvent
    data object NetworkConnected : NetworkEvent
    data object NetworkDisconnected : NetworkEvent
    data object PlayerSettingsUpdate : NetworkEvent

    data object UdpServiceConnected : NetworkEvent
    data object UdpServiceDisconnected : NetworkEvent

    /** Scoreboard parsed from player data JSON; indexed by player id. Scores are already updated. */
    class PlayerData(val players: Array<PlayerDisplayData?>) : NetworkEvent
}

/**
 * The play screen's network session (R13/R14 transport): binds UDPListenerService and TcpClient,
 * turns their NETMSG_* broadcasts into [events], builds the TCP message strings, holds the
 * session's network flags and scores, and serves BlasterSessionEngine as its [SessionNetwork].
 * The phone is always a client; the only server is DedicatedServerActivity.
 *
 * App-scoped; main thread unless noted.
 */
class NetworkSessionGateway(
    private val context: Context,
    private val scope: CoroutineScope,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SessionNetwork {

    private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 64)

    /** Network broadcasts and service lifecycle, in order. Not replayed. */
    val events: SharedFlow<NetworkEvent> = _events.asSharedFlow()

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

    private var receiverRegistered = false

    /** DedicatedServerActivity owns the network; the play screen's broadcasts are not published. */
    private var handedOff = false

    // ---------------------------------------------------------------------------------------
    // Service bindings
    // ---------------------------------------------------------------------------------------

    /**
     * Starts the two network services and binds each one that isn't bound yet. The activity
     * calls this on every resume; it used to bind again each time, leaving one more live
     * ServiceConnection per resume (B4).
     */
    fun bindServices() {
        handedOff = false
        if (udpConnection == null) bindUdp() else startServiceSafely(Intent(context, UDPListenerService::class.java))
        if (tcpClientConnection == null) bindTcpClient() else startServiceSafely(Intent(context, TcpClient::class.java))
    }

    private fun bindUdp() {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                udp = (service as UDPListenerService.LocalBinder).service
                emit(NetworkEvent.UdpServiceConnected)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                emit(NetworkEvent.UdpServiceDisconnected)
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
        handedOff = true
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
    // Broadcasts → events
    // ---------------------------------------------------------------------------------------

    /** Starts listening for the NETMSG_* broadcasts. Idempotent. */
    fun registerReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(context, receiver, intentFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    @androidx.annotation.VisibleForTesting
    internal val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!handedOff) toEvent(intent)?.let { emit(it) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Scoreboard (R14)
    // ---------------------------------------------------------------------------------------

    /** Scoreboard rows (indexed by player id), the local player's points, and points per team. */
    class ParsedPlayerData(val players: Array<PlayerDisplayData?>, val myPoints: Int?, val teamPoints: IntArray)

    /**
     * Parses player data JSON off the main thread, then on the main thread raises the local
     * player's score to its points, sets the team score and emits [NetworkEvent.PlayerData].
     *
     * B7: the scores used to be written from the parsing thread while the main thread also wrote
     * them.
     */
    fun updatePlayerData(message: String?) {
        if (message.isNullOrEmpty()) return
        scope.launch {
            val parsed = withContext(parseDispatcher) { parsePlayerData(message) } ?: return@launch
            parsed.myPoints?.let { score = maxOf(score, it) }
            if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                val myTeamIdx = Globals.getInstance().calcNetworkTeam(Globals.getInstance().mPlayerID) - 1
                if (myTeamIdx in 0..3) teamScore = parsed.teamPoints[myTeamIdx]
            }
            emit(NetworkEvent.PlayerData(parsed.players))
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
        tcpClient?.sendTCPMessage(message(NetMsg.NETMSG_HIT + playerId))
    }

    override fun sendOut(playerId: Byte) {
        tcpClient?.sendTCPMessage(message(NetMsg.NETMSG_OUT + playerId))
    }

    override fun sendShotFired() {
        tcpClient?.sendTCPMessage(message(NetMsg.NETMSG_SHOTFIRED))
    }

    override fun sendEliminated(playerId: Byte) {
        tcpClient?.sendReliable(NetMsg.NETMSG_ELIMINATED + playerId)
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
    internal fun emit(event: NetworkEvent) {
        if (!_events.tryEmit(event)) Log.w(TAG, "Dropped $event")
    }

    companion object {
        private const val TAG = "NetworkSession"

        /**
         * Parses the server's player data JSON. Reads player settings under Globals' player settings
         * semaphore. Null when the JSON is malformed.
         */
        @JvmStatic
        fun parsePlayerData(message: String): ParsedPlayerData? {
            val finalData = arrayOfNulls<PlayerDisplayData>(Globals.MAX_PLAYER_ID + 2)
            var hasSemaphore = false
            val teamPoints = IntArray(4)
            var myPoints: Int? = null
            try {
                val json = JSONObject(message)
                val players = json.getJSONArray(TcpServer.JSON_PLAYERDATA)
                Globals.getInstance()
                Globals.getmPlayerSettingsSemaphore()
                hasSemaphore = true
                for (x in 0 until players.length()) {
                    val player = players.getJSONObject(x)
                    val playerData = PlayerDisplayData()
                    playerData.playerID = player.getInt(TcpServer.JSON_PLAYERID).toByte()
                    playerData.playerName = player.getString(TcpServer.JSON_PLAYERNAME)
                    playerData.points = player.getInt(TcpServer.JSON_PLAYERPOINTS)
                    if (playerData.playerID == Globals.getInstance().mPlayerID) {
                        myPoints = maxOf(myPoints ?: playerData.points, playerData.points)
                    }
                    if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                        val teamIdx = Globals.getInstance().calcNetworkTeam(playerData.playerID) - 1
                        if (teamIdx in 0..3) teamPoints[teamIdx] += playerData.points
                    }
                    playerData.eliminated = player.getInt(TcpServer.JSON_PLAYERELIMINATED)
                    playerData.isConnected = player.optBoolean(TcpServer.JSON_CONNECTED, true)
                    val settings = Globals.getInstance().mPlayerSettings[playerData.playerID]
                    if (settings == null) {
                        playerData.overrideLives = false
                        playerData.lives = 0
                    } else {
                        playerData.overrideLives = settings.overrideLives
                        playerData.lives = settings.lives
                    }
                    finalData[playerData.playerID.toInt()] = playerData
                }
                Globals.getInstance().mPlayerSettingsSemaphore.release()
                hasSemaphore = false
            } catch (e: JSONException) {
                Log.e(TAG, "Error parsing player data", e)
                if (hasSemaphore) Globals.getInstance().mPlayerSettingsSemaphore.release()
                return null
            }
            return ParsedPlayerData(finalData, myPoints, teamPoints)
        }

        /** TCP message carrying a NETMSG: prefix + version + "MESG" + [netMsg]. */
        @JvmStatic
        fun message(netMsg: String): String = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + netMsg

        /** TCP message carrying JSON: prefix + version + "JSON" + [json]. */
        @JvmStatic
        fun jsonMessage(json: JSONObject): String = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + json

        /** Team change request for [playerId] to [targetTeam]. */
        @JvmStatic
        @Throws(JSONException::class)
        fun teamChangeRequest(playerId: Byte, targetTeam: Int): String {
            val json = JSONObject()
            json.put(TcpServer.JSON_PLAYERID, playerId.toInt())
            json.put(TcpServer.JSON_REQUEST_TEAM_CHANGE, targetTeam)
            return jsonMessage(json)
        }

        /** Maps a NETMSG_* broadcast to its event, reading the extras with the activity's defaults. */
        @JvmStatic
        fun toEvent(intent: Intent): NetworkEvent? = when (intent.action) {
            NetMsg.NETMSG_SHOTFIRED -> NetworkEvent.ShotFired
            NetMsg.NETMSG_HIT -> NetworkEvent.Hit(intent.getByteExtra(UDPListenerService.INTENT_PLAYERID, 0))
            NetMsg.NETMSG_OUT -> NetworkEvent.Out(intent.getByteExtra(UDPListenerService.INTENT_PLAYERID, 0))
            NetMsg.NETMSG_ELIMINATED -> NetworkEvent.Eliminated(intent.getByteExtra(UDPListenerService.INTENT_PLAYERID, 0))
            NetMsg.NETMSG_TEAMELIMINATED -> NetworkEvent.TeamEliminated
            NetMsg.NETMSG_JOIN -> NetworkEvent.Join
            NetMsg.NETMSG_LEAVE -> NetworkEvent.Leave
            NetMsg.NETMSG_LISTPLAYERS -> NetworkEvent.ListPlayers(
                gameUpdate = if (intent.getBooleanExtra(NetMsg.INTENT_HASGAMEUPDATE, false)) {
                    GameUpdate(
                        score = intent.getIntExtra(NetMsg.INTENT_SCORE, 0),
                        eliminations = intent.getIntExtra(NetMsg.INTENT_ELIMINATIONS, 0),
                        teamScore = intent.getIntExtra(NetMsg.INTENT_TEAMSCORE, 0),
                        timeRemaining = intent.getLongExtra(NetMsg.INTENT_TIMEREMAINING, -1),
                    )
                } else null,
                gameState = if (intent.hasExtra(NetMsg.INTENT_GAMESTATE)) {
                    intent.getIntExtra(NetMsg.INTENT_GAMESTATE, Globals.GAME_STATE_ELIMINATED)
                } else null,
                sessionPhase = if (intent.hasExtra(NetMsg.INTENT_SESSIONPHASE)) intent.getIntExtra(NetMsg.INTENT_SESSIONPHASE, 0) else null,
                gameId = if (intent.hasExtra(NetMsg.INTENT_GAMEID)) intent.getLongExtra(NetMsg.INTENT_GAMEID, 0) else null,
            )
            NetMsg.NETMSG_PLAYERDATAUPDATE -> NetworkEvent.PlayerDataUpdate(intent.getStringExtra(NetMsg.INTENT_PLAYERDATA))
            NetMsg.NETMSG_STARTGAME -> NetworkEvent.StartGame
            NetMsg.NETMSG_ENDGAME -> NetworkEvent.EndGame
            NetMsg.NETMSG_ERROR -> NetworkEvent.Error(intent.getStringExtra(UDPListenerService.INTENT_MESSAGE))
            NetMsg.NETMSG_VERSIONERROR -> NetworkEvent.VersionError
            NetMsg.NETMSG_SAMETEAM -> NetworkEvent.SameTeam
            NetMsg.NETMSG_FAILEDTOJOIN -> NetworkEvent.FailedToJoin
            NetMsg.NETMSG_SERVERCANCEL -> NetworkEvent.ServerCancel
            NetMsg.NETMSG_SERVERREPLY -> NetworkEvent.ServerReply(
                if (intent.hasExtra(UDPListenerService.INTENT_PLAYERID)) intent.getByteExtra(UDPListenerService.INTENT_PLAYERID, 0) else null)
            NetMsg.NETMSG_NETWORKCONNECTED -> NetworkEvent.NetworkConnected
            NetMsg.NETMSG_NETWORKDISCONNECTED -> NetworkEvent.NetworkDisconnected
            NetMsg.NETMSG_PLAYERSETTINGSUPDATE -> NetworkEvent.PlayerSettingsUpdate
            else -> null // NETMSG_SERVERCREATED was registered but never handled
        }

        @JvmStatic
        fun intentFilter(): IntentFilter {
            val intentFilter = IntentFilter()
            intentFilter.addAction(NetMsg.NETMSG_SHOTFIRED)
            intentFilter.addAction(NetMsg.NETMSG_HIT)
            intentFilter.addAction(NetMsg.NETMSG_OUT)
            intentFilter.addAction(NetMsg.NETMSG_ELIMINATED)
            intentFilter.addAction(NetMsg.NETMSG_JOIN)
            intentFilter.addAction(NetMsg.NETMSG_FAILEDTOJOIN)
            intentFilter.addAction(NetMsg.NETMSG_LEAVE)
            intentFilter.addAction(NetMsg.NETMSG_LISTPLAYERS)
            intentFilter.addAction(NetMsg.NETMSG_PLAYERDATAUPDATE)
            intentFilter.addAction(NetMsg.NETMSG_STARTGAME)
            intentFilter.addAction(NetMsg.NETMSG_ENDGAME)
            intentFilter.addAction(NetMsg.NETMSG_ERROR)
            intentFilter.addAction(NetMsg.NETMSG_VERSIONERROR)
            intentFilter.addAction(NetMsg.NETMSG_SAMETEAM)
            intentFilter.addAction(NetMsg.NETMSG_SERVERCREATED)
            intentFilter.addAction(NetMsg.NETMSG_SERVERCANCEL)
            intentFilter.addAction(NetMsg.NETMSG_SERVERREPLY)
            intentFilter.addAction(NetMsg.NETMSG_TEAMELIMINATED)
            intentFilter.addAction(NetMsg.NETMSG_NETWORKCONNECTED)
            intentFilter.addAction(NetMsg.NETMSG_NETWORKDISCONNECTED)
            intentFilter.addAction(NetMsg.NETMSG_PLAYERSETTINGSUPDATE)
            return intentFilter
        }
    }
}
