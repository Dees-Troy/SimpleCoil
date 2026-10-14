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

package com.simplecoil.simplecoil.network.client

import com.simplecoil.simplecoil.LoopbackClient
import com.simplecoil.simplecoil.NetworkSessionGateway
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.network.ClientCommand
import com.simplecoil.simplecoil.network.EventBus
import com.simplecoil.simplecoil.network.WireKeys
import com.simplecoil.simplecoil.network.WireProtocol
import org.json.JSONObject
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

/** The client writes on one thread, in the order sent (D3), over an in-memory stream. */
class GameClientWriterTest {

    private val settings = GameSettingsRepository()
    private val gameState = GameStateRepository()
    private val players = PlayerRepository(settings, gameState)

    private fun newClient() = GameClient(settings, players, gameState, EventBus(), object : GameClient.Foreground {
        override fun start(statusText: String) {} // notifications are stubs here
        override fun stop() {}
    })

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
        val client = newClient()
        client.attachOutputForTest(DataOutputStream(bytes))
        val sent = (0 until 500).map { "message $it" }
        sent.forEach { client.sendTCPMessage(it) }
        client.awaitWritesForTest()
        assertEquals(sent, written())
    }

    @Test
    fun awaitPendingWrites_returnsOnceEverythingSentIsWritten() {
        // Quit waits on this before ending the process: the writer thread is a daemon.
        val client = newClient()
        client.attachOutputForTest(DataOutputStream(bytes))
        val sent = (0 until 500).map { "message $it" }
        sent.forEach { client.sendTCPMessage(it) }
        assertTrue(client.awaitPendingWrites(5_000))
        assertEquals(sent, written())
    }

    @Test
    fun awaitPendingWrites_afterShutdown_doesNotWait() {
        val client = newClient()
        client.shutdown()
        assertTrue(client.awaitPendingWrites(5_000))
    }

    private fun json(message: String) = JSONObject(message.removePrefix(WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON))

    @Test
    fun eliminationsWhileDisconnected_rideInsideTheHello() {
        // D3: queued eliminations used to race the hello; now they are part of it.
        players.playerID = 7
        players.playerName = "Rook"
        players.deviceAddress = "AA:BB"
        players.pairedGrenadeID = 0
        val client = newClient()
        client.sendReliable(WireProtocol.NETMSG_ELIMINATED + 3) // no connection: only in the outbox
        client.sendTCPMessage(NetworkSessionGateway.command(ClientCommand.Hit(3))) // no connection: dropped
        client.awaitWritesForTest()

        client.attachOutputForTest(DataOutputStream(bytes))
        client.sendPlayerInfo(true)
        client.awaitWritesForTest()
        val messages = written()
        assertEquals(1, messages.size)
        val hello = json(messages[0])
        assertEquals(7, hello.getInt(WireKeys.PLAYERID))
        assertEquals("AA:BB", hello.getString(WireKeys.BLASTER_MAC))
        assertTrue(hello.getBoolean(WireKeys.REJOIN))
        val pending = hello.getJSONArray(WireKeys.PENDING)
        assertEquals(1, pending.length())
        assertEquals("ELIMINATED3", pending.getJSONObject(0).getString(WireKeys.RMSG))
        assertEquals(1, pending.getJSONObject(0).getInt(WireKeys.SEQ))
    }

    @Test
    fun aReliableMessageStaysInTheOutbox_evenWhenItsWriteSucceeds() {
        // D1: a write into the socket buffer during the 3.5 s detection window is no delivery.
        val client = newClient()
        client.attachOutputForTest(DataOutputStream(bytes))
        client.sendReliable(WireProtocol.NETMSG_ELIMINATED + 3)
        client.awaitWritesForTest()
        val envelope = json(written().single())
        assertEquals("ELIMINATED3", envelope.getString(WireKeys.RMSG))
        assertEquals(1, envelope.getInt(WireKeys.SEQ))
        assertEquals(1, client.outbox.pending().size)
    }

    @Test
    fun leaveServer_writesLeaveLast_thenCloses_withoutBlockingTheCaller() {
        // N11: stopTcpClient slept 75 ms on the main thread and hoped LEAVE had gone out by then.
        LoopbackClient.awaitPortFree()
        gameState.serverIP = InetAddress.getByName("127.0.0.1")
        players.pairedGrenadeID = 0
        ServerSocket(WireProtocol.TCP_SERVER_PORT).use { server ->
            server.soTimeout = 5_000
            val client = newClient()
            client.start()
            val socket = server.accept()
            socket.soTimeout = 5_000
            val input = DataInputStream(socket.getInputStream())
            input.readUTF() // the hello
            val chatter = (0 until 50).map { NetworkSessionGateway.command(ClientCommand.ShotFired) }
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
            assertEquals(chatter + NetworkSessionGateway.command(ClientCommand.Leave), frames)
            socket.close()
        }
    }
}
