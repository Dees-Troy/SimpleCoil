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
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.simplecoil.simplecoil.network.client.GameClient

/**
 * The play screen's network client, as a service: the notification, the binder and the Wi-Fi
 * callback around a [GameClient], built from the app container.
 */
class TcpClient : Service() {

    private lateinit var client: GameClient

    /** The Wi-Fi network while it is up; sockets are bound to it (§3.6). */
    @Volatile
    private var wifiNetwork: Network? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    inner class LocalBinder : Binder() {
        val service: TcpClient get() = this@TcpClient
    }

    private val binder: IBinder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val container = (application as SimpleCoilApp).container
        client = GameClient(container.settings, container.players, container.gameState, container.clientEvents,
            foreground = object : GameClient.Foreground {
                override fun start(statusText: String) = startAsForeground(statusText)
                override fun stop() = stopAsForeground()
            },
            network = { wifiNetwork })
        createNotificationChannel()
        registerWifiCallback()
    }

    /**
     * Follows the Wi-Fi network: when it comes back the retry loop wakes at once (Q4), and
     * sockets are bound to it so they don't go out over mobile data when the field's Wi-Fi has
     * no internet.
     */
    private fun registerWifiCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager? ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Wi-Fi available")
                wifiNetwork = network
                client.onNetworkAvailable()
            }

            override fun onLost(network: Network) {
                if (network == wifiNetwork) wifiNetwork = null
            }
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            wifiCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch Wi-Fi: " + e.message)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "SimpleCoil Network Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createPersistentNotification(contentText: String): Notification {
        val notificationIntent = FullscreenActivity.resumeIntent(this)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SimpleCoil Game Network")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /** Shows the network notification while the client is connected or rejoining. */
    @SuppressLint("InlinedApi") // ServiceCompat ignores the type below Android 10
    private fun startAsForeground(statusText: String) {
        val notification = createPersistentNotification(statusText)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Android 12+ refuses this while the app is in the background; play on without it.
            Log.w(TAG, "Could not start foreground: " + e.message)
        }
    }

    private fun stopAsForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiCallback?.let { callback ->
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?)?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // already gone
            }
        }
        wifiCallback = null
        client.shutdown()
    }

    // The client's API, for NetworkSessionGateway and GameViewModel

    fun startTcpClient() = client.start()

    /** Stops once everything sent so far has been written (N11). */
    fun stopTcpClient() = client.stop()

    /** Tells the server we are leaving, then stops: LEAVE is the last frame written. */
    fun leaveServer() = client.leaveServer()

    /** Queues [message] for the writer thread. Without a connection it is dropped. */
    fun sendTCPMessage(message: String) = client.sendTCPMessage(message)

    /** Sends [rmsg] (an ELIMINATED message) reliably, until the server acknowledges it (D1). */
    fun sendReliable(rmsg: String) = client.sendReliable(rmsg)

    fun sendPlayerInfo(rejoin: Boolean) = client.sendPlayerInfo(rejoin)

    fun sendPlayerNameChange() = client.sendPlayerNameChange()

    fun sendPlayerSettings() = client.sendPlayerSettings()

    fun sendPlayerGrenade() = client.sendPlayerGrenade()

    /** Reports our position (the map's location updates) to the server. */
    fun sendLocation(latitude: Double, longitude: Double) = client.sendLocation(latitude, longitude)

    private companion object {
        const val TAG = "TCPClient"
        const val NOTIFICATION_ID = 1338
        const val CHANNEL_ID = "TcpClientServiceChannel"
    }
}
