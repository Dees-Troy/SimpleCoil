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

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.network.ClientEvent
import com.simplecoil.simplecoil.network.EventBus
import com.simplecoil.simplecoil.network.discovery.DiscoveryClient
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * LAN server discovery for the play screen, as a service: the [DiscoveryClient] that finds a
 * server. The dedicated server's beacon lives in TcpServer. The game itself runs over TCP.
 */
class UDPListenerService : Service() {

    private lateinit var gameState: GameStateRepository
    private lateinit var client: DiscoveryClient

    /** Discovery's results for the play screen. */
    private lateinit var clientEvents: EventBus<ClientEvent>

    inner class LocalBinder : Binder() {
        val service: UDPListenerService get() = this@UDPListenerService
    }

    private val binder: IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        val container = (application as SimpleCoilApp).container
        gameState = container.gameState
        clientEvents = container.clientEvents
        client = DiscoveryClient(this, localAddress = { PlayerRepository.getIPAddress() })
        Log.d(TAG, "UDP Service initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        stopListen()
        super.onDestroy()
    }

    /** Looks for a server on the LAN. */
    fun joinServer() = startDiscovery()

    /** Joins the server at [serverIP] (a typed address, with or without a leading "/"). */
    fun joinServer(serverIP: String?) {
        if (serverIP == null || serverIP.trim().isEmpty()) {
            sendFailedJoin()
            return
        }
        val ip = serverIP.removePrefix("/")
        Thread {
            try {
                gameState.serverIP = InetAddress.getByName(ip)
                clientEvents.publish(ClientEvent.ServerReply(null))
            } catch (e: UnknownHostException) {
                Log.e(TAG, "Failed to resolve server IP: $ip", e)
                sendFailedJoin()
            }
        }.start()
    }

    fun joinServer(serverIP: InetAddress?) {
        if (serverIP != null) joinServer(serverIP.hostAddress) else sendFailedJoin()
    }

    fun startDiscovery() {
        client.start(object : DiscoveryClient.Listener {
            override fun onServerDiscovered(serverIp: String, serverName: String) {
                try {
                    gameState.serverIP = InetAddress.getByName(serverIp)
                    client.stop()
                    clientEvents.publish(ClientEvent.ServerReply(null))
                } catch (e: UnknownHostException) {
                    Log.e(TAG, "Invalid discovered server IP: $serverIp", e)
                    sendFailedJoin()
                }
            }

            override fun onDiscoveryFailed(errorReason: String?) {
                Log.e(TAG, "Discovery failed: $errorReason")
                sendFailedJoin()
            }
        })
    }

    /** Stops looking for a server. */
    fun stopListen() = client.stop()

    fun endScanning() = stopListen()

    private fun sendFailedJoin() {
        clientEvents.publish(ClientEvent.FailedToJoin)
    }

    private companion object {
        const val TAG = "UDPSvc"
    }
}
