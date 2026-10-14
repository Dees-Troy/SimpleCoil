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

package com.simplecoil.simplecoil.network.discovery

import android.content.Context
import android.util.Log
import com.simplecoil.simplecoil.network.WireProtocol
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * The server's discovery beacon: broadcasts the server's name on UDP 17500 every second, and
 * answers every query at once, on its own thread.
 */
class DiscoveryBeacon(context: Context) {
    private val network = DiscoveryNetwork(context)

    @Volatile
    private var running = false

    @Volatile
    private var socket: DatagramSocket? = null

    /** Starts broadcasting [serverName], unless the beacon is already on. */
    @Synchronized
    fun start(serverName: String) {
        if (running) {
            Log.d(TAG, "Server beacon already running")
            return
        }
        network.acquireMulticastLock()
        running = true
        Thread { run(serverName) }.start()
    }

    private fun run(serverName: String) {
        try {
            val s = DiscoveryNetwork.bindDiscoverySocket()
            socket = s
            Log.d(TAG, "Server discovery beacon started on port ${WireProtocol.DISCOVERY_PORT}")
            val packet = DatagramPacket(ByteArray(DiscoveryNetwork.RECEIVE_BUFFER_SIZE), DiscoveryNetwork.RECEIVE_BUFFER_SIZE)
            var lastBeaconTime = 0L
            while (running) {
                val now = System.currentTimeMillis()
                if (now - lastBeaconTime >= BEACON_INTERVAL_MS) {
                    for (address in network.broadcastAddresses()) sendBeacon(address, serverName)
                    lastBeaconTime = now
                }
                try {
                    s.receive(packet)
                    val msg = String(packet.data, 0, packet.length).trim()
                    if (msg.startsWith(WireProtocol.DISCOVER_MSG) || msg.startsWith(WireProtocol.MESSAGE_PREFIX + WireProtocol.NETMSG_JOIN)) {
                        Log.d(TAG, "Received discovery query from " + packet.address.hostAddress)
                        sendBeacon(packet.address, serverName)
                    }
                } catch (ignored: SocketTimeoutException) {
                    // Timeout expected to allow periodic beacon loop
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Server beacon thread error", e)
        } finally {
            socket?.let { if (!it.isClosed) it.close() }
            network.releaseMulticastLock()
            Log.d(TAG, "Server discovery beacon stopped")
        }
    }

    private fun sendBeacon(destination: InetAddress, serverName: String) {
        try {
            network.send(WireProtocol.BEACON_PREFIX + serverName, destination)
        } catch (e: IOException) {
            Log.e(TAG, "Error sending discovery beacon packet", e)
        }
    }

    /** Stops the beacon. */
    @Synchronized
    fun stop() {
        running = false
        socket?.close()
    }

    private companion object {
        const val TAG = "ServerDiscoveryMgr"
        const val BEACON_INTERVAL_MS = 1000
    }
}
