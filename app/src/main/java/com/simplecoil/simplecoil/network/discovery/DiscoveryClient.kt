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
 * Finds a server on the LAN: listens for beacons on UDP 17500 and broadcasts a query every
 * second until one answers, on its own thread.
 */
class DiscoveryClient(
    context: Context,
    /** This device's address, so its own beacon (if any) is ignored. */
    private val localAddress: () -> InetAddress?,
) {
    interface Listener {
        fun onServerDiscovered(serverIp: String, serverName: String)
        fun onDiscoveryFailed(errorReason: String?)
    }

    private val network = DiscoveryNetwork(context)

    @Volatile
    private var running = false

    @Volatile
    private var socket: DatagramSocket? = null

    /** Starts looking for a server, unless already looking. [listener] hears on the discovery thread. */
    @Synchronized
    fun start(listener: Listener?) {
        if (running) {
            Log.d(TAG, "Client discovery already running")
            return
        }
        network.acquireMulticastLock()
        running = true
        Thread { run(listener) }.start()
    }

    private fun run(listener: Listener?) {
        try {
            val s = DiscoveryNetwork.bindDiscoverySocket()
            socket = s
            Log.i(TAG, "Client discovery listening on port ${WireProtocol.DISCOVERY_PORT}")
            sendQuery()
            val packet = DatagramPacket(ByteArray(DiscoveryNetwork.RECEIVE_BUFFER_SIZE), DiscoveryNetwork.RECEIVE_BUFFER_SIZE)
            while (running) {
                try {
                    s.receive(packet)
                    if (packet.address == localAddress()) continue // Ignore self
                    val msg = String(packet.data, 0, packet.length).trim()
                    if (msg.startsWith(WireProtocol.BEACON_PREFIX) || msg.startsWith(WireProtocol.MESSAGE_PREFIX + WireProtocol.NETMSG_SERVERREPLY)) {
                        val serverIp = packet.address.hostAddress ?: continue
                        val serverName = if (msg.startsWith(WireProtocol.BEACON_PREFIX)) msg.substring(WireProtocol.BEACON_PREFIX.length) else "SimpleCoil Server"
                        Log.i(TAG, "Discovered server: $serverName at $serverIp")
                        listener?.onServerDiscovered(serverIp, serverName)
                    }
                } catch (ignored: SocketTimeoutException) {
                    // Send query again periodically
                    if (running) sendQuery()
                }
            }
        } catch (e: IOException) {
            if (running) {
                Log.e(TAG, "Client discovery thread error", e)
                listener?.onDiscoveryFailed(e.localizedMessage)
            } else {
                Log.d(TAG, "Client discovery socket closed normally")
            }
        } finally {
            socket?.let { if (!it.isClosed) it.close() }
            network.releaseMulticastLock()
            Log.i(TAG, "Client discovery stopped")
        }
    }

    private fun sendQuery() {
        for (address in network.broadcastAddresses()) {
            try {
                network.send(WireProtocol.DISCOVER_MSG, address)
            } catch (e: IOException) {
                Log.e(TAG, "Error sending discovery query to " + address.hostAddress, e)
            }
        }
    }

    /** Stops looking. */
    @Synchronized
    fun stop() {
        running = false
        socket?.close()
    }

    private companion object {
        const val TAG = "ServerDiscoveryMgr"
    }
}
