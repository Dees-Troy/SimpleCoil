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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/** TcpClient writes on one thread, in the order sent (D3), over an in-memory stream. */
class TcpClientWriterTest {

    private val bytes = ByteArrayOutputStream()

    private fun written(): List<String> {
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        val messages = mutableListOf<String>()
        try {
            while (true) messages += input.readUTF()
        } catch (e: EOFException) {
            // end
        }
        return messages
    }

    @Test
    fun messages_areWrittenInTheOrderSent() {
        val client = TcpClient()
        client.attachOutputForTest(DataOutputStream(bytes))
        val sent = (0 until 500).map { "message $it" }
        sent.forEach { client.sendTCPMessage(it) }
        client.awaitWritesForTest()
        assertEquals(sent, written())
    }

    @Test
    fun messagesKeptWhileDisconnected_followTheHelloOnReconnect() {
        // D3: the queued eliminations used to race the hello and could reach the server first.
        Globals.getInstance().mPlayerID = 7
        Globals.getInstance().mPlayerName = "Rook"
        Globals.getInstance().mDeviceAddress = "AA:BB"
        Globals.getInstance().mPairedGrenadeID = 0
        val client = TcpClient()
        val eliminated = NetworkSessionGateway.message(NetMsg.NETMSG_ELIMINATED + 3)
        client.sendTCPMessage(eliminated, true) // no connection: kept
        client.sendTCPMessage(NetworkSessionGateway.message(NetMsg.NETMSG_HIT + 3)) // no connection: dropped
        client.awaitWritesForTest()

        client.attachOutputForTest(DataOutputStream(bytes))
        client.sendPlayerInfo(true)
        client.awaitWritesForTest()
        val messages = written()
        assertEquals(2, messages.size)
        val hello = JSONObject(messages[0].removePrefix(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON))
        assertEquals(7, hello.getInt(TcpServer.JSON_PLAYERID))
        assertTrue(hello.getBoolean(TcpServer.JSON_REJOIN))
        assertEquals(eliminated, messages[1])
    }
}
