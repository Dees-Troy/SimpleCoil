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

import com.simplecoil.simplecoil.data.repository.PlayerRepository
import org.json.JSONObject
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket

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

    private fun json(message: String) = JSONObject(message.removePrefix(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON))

    @Test
    fun eliminationsWhileDisconnected_rideInsideTheHello() {
        // D3: queued eliminations used to race the hello; now they are part of it.
        PlayerRepository.getInstance().playerID = 7
        PlayerRepository.getInstance().playerName = "Rook"
        PlayerRepository.getInstance().deviceAddress = "AA:BB"
        PlayerRepository.getInstance().pairedGrenadeID = 0
        val client = TcpClient()
        client.sendReliable(NetMsg.NETMSG_ELIMINATED + 3) // no connection: only in the outbox
        client.sendTCPMessage(NetworkSessionGateway.message(NetMsg.NETMSG_HIT + 3)) // no connection: dropped
        client.awaitWritesForTest()

        client.attachOutputForTest(DataOutputStream(bytes))
        client.sendPlayerInfo(true)
        client.awaitWritesForTest()
        val messages = written()
        assertEquals(1, messages.size)
        val hello = json(messages[0])
        assertEquals(7, hello.getInt(TcpServer.JSON_PLAYERID))
        assertEquals("AA:BB", hello.getString(TcpServer.JSON_BLASTER_MAC))
        assertTrue(hello.getBoolean(TcpServer.JSON_REJOIN))
        val pending = hello.getJSONArray(TcpServer.JSON_PENDING)
        assertEquals(1, pending.length())
        assertEquals("ELIMINATED3", pending.getJSONObject(0).getString(TcpServer.JSON_RMSG))
        assertEquals(1, pending.getJSONObject(0).getInt(TcpServer.JSON_SEQ))
    }

    @Test
    fun aReliableMessageStaysInTheOutbox_evenWhenItsWriteSucceeds() {
        // D1: a write into the socket buffer during the 3.5 s detection window is no delivery.
        val client = TcpClient()
        client.attachOutputForTest(DataOutputStream(bytes))
        client.sendReliable(NetMsg.NETMSG_ELIMINATED + 3)
        client.awaitWritesForTest()
        val envelope = json(written().single())
        assertEquals("ELIMINATED3", envelope.getString(TcpServer.JSON_RMSG))
        assertEquals(1, envelope.getInt(TcpServer.JSON_SEQ))
        assertEquals(1, client.outbox.pending().size)
    }

    @Test
    fun leaveServer_writesLeaveLast_thenCloses_withoutBlockingTheCaller() {
        // N11: stopTcpClient slept 75 ms on the main thread and hoped LEAVE had gone out by then.
        LoopbackClient.awaitPortFree()
        GameStateRepository.getInstance().serverIP = InetAddress.getByName("127.0.0.1")
        PlayerRepository.getInstance().pairedGrenadeID = 0
        ServerSocket(TcpServer.TCP_SERVER_PORT).use { server ->
            server.soTimeout = 5_000
            val client = object : TcpClient() {
                override fun startAsForeground(statusText: String?) {} // notifications are stubs here
            }
            client.startTcpClient()
            val socket = server.accept()
            socket.soTimeout = 5_000
            val input = DataInputStream(socket.getInputStream())
            input.readUTF() // the hello
            val chatter = (0 until 50).map { NetworkSessionGateway.message(NetMsg.NETMSG_SHOTFIRED) }
            chatter.forEach { client.sendTCPMessage(it) }
            val startedAt = System.nanoTime()
            client.leaveServer()
            assertTrue((System.nanoTime() - startedAt) / 1_000_000 < 50)

            val frames = mutableListOf<String>()
            try {
                while (true) frames += input.readUTF()
            } catch (e: EOFException) {
                // the client closed its socket
            }
            assertEquals(chatter + NetworkSessionGateway.message(NetMsg.NETMSG_LEAVE), frames)
            socket.close()
        }
    }
}
