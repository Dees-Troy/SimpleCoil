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

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A raw TCP client for TcpServer over 127.0.0.1: writes the wire strings by hand, answers the
 * server's pings and records everything else it receives.
 */
class LoopbackClient(port: Int = TcpServer.TCP_SERVER_PORT) {
    private val socket = Socket("127.0.0.1", port)
    private val out = DataOutputStream(socket.getOutputStream())

    /** Every message received except pings, in order. */
    val received = CopyOnWriteArrayList<String>()

    init {
        Thread {
            val input = DataInputStream(socket.getInputStream())
            try {
                while (true) {
                    val message = input.readUTF()
                    if (message == TcpServer.TCP_SERVER_PING) send(TcpClient.TCP_CLIENT_PONG) else received += message
                }
            } catch (e: Exception) {
                // closed
            }
        }.apply { isDaemon = true }.start()
    }

    @Synchronized
    fun send(raw: String) {
        out.writeUTF(raw)
        out.flush()
    }

    fun mesg(netMsg: String) = send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + netMsg)

    fun json(json: JSONObject) = send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + json)

    /** The join JSON TcpClient.sendPlayerInfo() sends. */
    fun hello(playerId: Int, name: String, mac: String?, rejoin: Boolean = false) {
        val json = JSONObject()
        json.put(TcpServer.JSON_PLAYERID, playerId)
        json.put(TcpServer.JSON_PLAYERNAME, name)
        if (mac != null) json.put(TcpServer.JSON_BLASTER_MAC, mac)
        if (rejoin) json.put(TcpServer.JSON_REJOIN, true)
        json(json)
    }

    /** The first MESG received that starts with [netMsg] (prefix stripped), waiting up to [timeoutMs]. */
    fun awaitMesg(netMsg: String, timeoutMs: Long = 3000): String {
        val prefix = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + netMsg
        val found = awaitValue(timeoutMs) { received.firstOrNull { it.startsWith(prefix) } }
        return found.substring(TcpServer.TCPMESSAGE_PREFIX.length + TcpServer.TCPPREFIX_MESG.length)
    }

    /** The last JSON received that has [key], waiting up to [timeoutMs]. */
    fun awaitJson(key: String, timeoutMs: Long = 3000): JSONObject = awaitValue(timeoutMs) { lastJson(key) }

    fun lastJson(key: String): JSONObject? {
        val prefix = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON
        return received.reversed().asSequence()
            .filter { it.startsWith(prefix) }
            .map { JSONObject(it.substring(prefix.length)) }
            .firstOrNull { it.has(key) }
    }

    fun mesgs(netMsg: String): List<String> {
        val prefix = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG
        return received.filter { it.startsWith(prefix + netMsg) }.map { it.substring(prefix.length) }
    }

    fun close() {
        try {
            socket.close()
        } catch (e: Exception) {
            // nothing
        }
    }

    companion object {
        /** Polls [condition] every 20 ms until it holds; fails after [timeoutMs]. */
        fun awaitTrue(timeoutMs: Long = 3000, what: String = "condition", condition: () -> Boolean) {
            awaitValue(timeoutMs, what) { if (condition()) true else null }
        }

        fun <T> awaitValue(timeoutMs: Long = 3000, what: String = "value", value: () -> T?): T {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                value()?.let { return it }
                if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what")
                Thread.sleep(20)
            }
        }

        /** Waits until nothing listens on [port] (a stopped TcpServer can take a second to close). */
        fun awaitPortFree(port: Int = TcpServer.TCP_SERVER_PORT, timeoutMs: Long = 5000) {
            awaitTrue(timeoutMs, "port $port to close") {
                try {
                    ServerSocket(port).close()
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
    }
}
