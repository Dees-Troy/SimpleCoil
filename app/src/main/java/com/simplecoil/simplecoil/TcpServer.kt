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

package com.simplecoil.simplecoil

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.simplecoil.simplecoil.data.model.PlayerSettings
import com.simplecoil.simplecoil.network.server.ClockState
import com.simplecoil.simplecoil.network.server.GameRoster
import com.simplecoil.simplecoil.network.server.GameServer
import com.simplecoil.simplecoil.network.server.SessionPhase

/**
 * The dedicated server, as a foreground service (S3): the notification and the binder around a
 * [GameServer] built from the app container. The server screen drives it through this API.
 */
class TcpServer : Service() {

    private lateinit var server: GameServer

    inner class LocalBinder : Binder() {
        val service: TcpServer get() = this@TcpServer
    }

    private val binder: IBinder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val container = (application as SimpleCoilApp).container
        server = GameServer(container.settings, container.players, container.gameState, container.serverEvents, container.clock,
            saveTeamMode = { container.userPreferences.saveTeamMode(it) })
    }

    override fun onDestroy() {
        super.onDestroy()
        server.shutdown()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, getString(R.string.server_notification_channel), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
        }
    }

    /**
     * Keeps the process in the foreground while the server runs, so a minimized or backgrounded
     * server isn't frozen or killed (S3). Called by the screen once the server has started.
     */
    @SuppressLint("InlinedApi") // ServiceCompat ignores the type below Android 10
    fun startAsForeground() {
        createNotificationChannel()
        val notificationIntent = Intent(this, DedicatedServerActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.server_notification_title))
            .setContentText(getString(R.string.server_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Android 12+ refuses this while the app is in the background; serve on without it.
            Log.w(TAG, "Could not start foreground: " + e.message)
        }
    }

    private fun stopAsForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // The server's API, for the server screen

    fun startTcpServer() = server.start()

    /** Stops serving: closes every connection once its queued frames are written, and leaves the foreground. */
    fun stopTcpServer() {
        server.stop()
        stopAsForeground()
    }

    /** Queues [message] for every connection. */
    fun sendTCPMessageAll(message: String) = server.sendToAll(message)

    /** Starts a game from the lobby. False if not in the lobby (after a game, reset first) or nobody is there. */
    fun startGame(): Boolean = server.startGame()

    fun endGame() = server.endGame()

    /** Reset, POST_GAME → LOBBY; false outside POST_GAME. */
    fun resetSession(): Boolean = server.resetSession()

    /** Resets first after a game; call before changing the teams, limits or GPS mode. True if it reset. */
    fun resetIfPostGame(): Boolean = server.resetIfPostGame()

    val sessionPhase: SessionPhase get() = server.sessionPhase

    /** The game clock, for the screen to render (any thread). */
    val clockState: ClockState get() = server.clockState

    fun getScore(playerId: Byte): GameRoster.Score? = server.score(playerId)

    fun resetScores() = server.resetScores()

    fun kickPlayer(playerId: Byte) = server.kickPlayer(playerId)

    fun rebalanceAllPlayers(randomize: Boolean) = server.rebalanceAllPlayers(randomize)

    /** Sends the game info to everyone; [id] (or SEND_ALL) also gets its own game update. */
    fun sendAllGameInfo(id: Int) = server.sendAllGameInfo(id)

    /** Starts relaying GPS while GPS is on. */
    fun sendGPSData() = server.startGpsRelay()

    /** Stores, moves, copies and sends a player's settings in one step; false if [targetTeam] is full. */
    fun applyPlayerSettings(playerId: Byte, update: (PlayerSettings) -> Unit, applyAll: Boolean, applyTeam: Boolean, targetTeam: Int): Boolean =
        server.applyPlayerSettings(playerId, update, applyAll, applyTeam, targetTeam)

    companion object {
        private const val TAG = "TCPServer"
        private const val NOTIFICATION_ID = 1339
        private const val CHANNEL_ID = "TcpServerServiceChannel"

        /** Every player, in place of one player's id. */
        const val SEND_ALL = GameServer.SEND_ALL
    }
}
