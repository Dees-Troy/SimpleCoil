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
import android.net.wifi.WifiManager
import android.util.Log
import com.simplecoil.simplecoil.network.WireProtocol
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * What the discovery beacon and client share: the Wi-Fi manager (from the application context,
 * so it can't leak an activity), the multicast lock, the broadcast addresses and the sockets.
 * UDP is used only for finding the server; the game itself runs over TCP.
 */
internal class DiscoveryNetwork(private val context: Context) {
    private var wifiManager: WifiManager? = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
    private var multicastLock: WifiManager.MulticastLock? = null

    fun acquireMulticastLock() {
        val manager = wifiManager ?: return
        val lock = multicastLock ?: manager.createMulticastLock("SimpleCoilDiscovery").also {
            it.setReferenceCounted(true)
            multicastLock = it
        }
        if (!lock.isHeld) lock.acquire()
    }

    fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
    }

    /**
     * The subnet broadcast from the Wi-Fi DHCP info, plus the limited broadcast 255.255.255.255.
     * On a Chromebook the app sits behind NAT and the DHCP info describes the container's internal
     * subnet, so the subnet broadcast may never reach the LAN.
     */
    fun broadcastAddresses(): List<InetAddress> {
        val addresses = ArrayList<InetAddress>()
        subnetBroadcastAddress()?.let { addresses += it }
        try {
            val limited = InetAddress.getByAddress(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()))
            if (limited !in addresses) addresses += limited
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build limited broadcast address", e)
        }
        return addresses
    }

    @Suppress("DEPRECATION") // DhcpInfo: the only source of the netmask without location permission
    private fun subnetBroadcastAddress(): InetAddress? {
        if (wifiManager == null) wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        val manager = wifiManager
        if (manager == null) {
            Log.e(TAG, "WifiManager unavailable")
            return null
        }
        val dhcp = manager.dhcpInfo
        if (dhcp == null) {
            Log.e(TAG, "DhcpInfo unavailable")
            return null
        }
        val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
        val quads = ByteArray(4) { k -> (broadcast shr (k * 8) and 0xFF).toByte() }
        return try {
            InetAddress.getByAddress(quads)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute broadcast address", e)
            null
        }
    }

    /** Sends [payload] to [destination]'s discovery port from a throwaway socket. */
    fun send(payload: String, destination: InetAddress) {
        val buf = payload.toByteArray()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(DatagramPacket(buf, buf.size, destination, WireProtocol.DISCOVERY_PORT))
        }
    }

    companion object {
        private const val TAG = "ServerDiscoveryMgr"
        const val TIMEOUT_MS = 1000
        const val RECEIVE_BUFFER_SIZE = 1024

        /** A socket on the discovery port, shared with any other listener on it. */
        @Throws(IOException::class)
        fun bindDiscoverySocket(): DatagramSocket {
            val socket = DatagramSocket(null)
            try {
                socket.reuseAddress = true
                socket.broadcast = true
                socket.bind(InetSocketAddress(WireProtocol.DISCOVERY_PORT))
                socket.soTimeout = TIMEOUT_MS
                return socket
            } catch (e: IOException) {
                socket.close()
                throw e
            }
        }
    }
}
