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
import com.simplecoil.simplecoil.network.ServerEvent
import com.simplecoil.simplecoil.network.discovery.DiscoveryBeacon
import com.simplecoil.simplecoil.network.discovery.DiscoveryClient
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * LAN server discovery, as a service: the [DiscoveryClient] that finds a server for the play
 * screen, and the [DiscoveryBeacon] that announces the dedicated server. The game itself runs
 * over TCP.
 */
class UDPListenerService : Service() {

    private lateinit var gameState: GameStateRepository
    private lateinit var client: DiscoveryClient
    private lateinit var beacon: DiscoveryBeacon

    /** Discovery's results for the play screen (the client role). */
    private lateinit var clientEvents: EventBus<ClientEvent>

    /** The beacon's start, for the dedicated server's screen (the server role). */
    private lateinit var serverEvents: EventBus<ServerEvent>

    inner class LocalBinder : Binder() {
        val service: UDPListenerService get() = this@UDPListenerService
    }

    private val binder: IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        val container = (application as SimpleCoilApp).container
        gameState = container.gameState
        clientEvents = container.clientEvents
        serverEvents = container.serverEvents
        client = DiscoveryClient(this, localAddress = { PlayerRepository.getIPAddress() })
        beacon = DiscoveryBeacon(this)
        Log.d(TAG, "UDP Service initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        stopListen()
        super.onDestroy()
    }

    /** Announces the dedicated server, which listens on this device's address. */
    fun createServer() {
        beacon.start(SERVER_NAME)
        PlayerRepository.getIPAddress()?.let { gameState.serverIP = it }
        gameState.serverIP?.let { serverEvents.publish(ServerEvent.ServerCreated(it.hostAddress ?: return@let)) }
    }

    fun cancelServer() = beacon.stop()

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

    /** Stops looking for a server and stops announcing one. */
    fun stopListen() {
        client.stop()
        beacon.stop()
    }

    fun endScanning() = stopListen()

    /** Announces the server while new players may join through discovery. */
    fun allowJoin(allowed: Boolean) {
        if (allowed) beacon.start(SERVER_NAME) else beacon.stop()
    }

    /** Disallow discovery joins during active game. */
    fun startGame() = allowJoin(false)

    /** Allow discovery after game end. */
    fun endGame() = allowJoin(true)

    private fun sendFailedJoin() {
        clientEvents.publish(ClientEvent.FailedToJoin)
    }

    private companion object {
        const val TAG = "UDPSvc"
        const val SERVER_NAME = "SimpleCoil Dedicated Server"
    }
}
