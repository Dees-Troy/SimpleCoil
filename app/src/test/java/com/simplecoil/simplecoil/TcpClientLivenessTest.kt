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

import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.network.WireKeys
import com.simplecoil.simplecoil.network.WireProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket

/** TcpClient notices a dead server even while it is half-way through a frame (N4, network design §5.3). */
class TcpClientLivenessTest {

    @Test
    fun aHalfReceivedFrame_dropsTheConnectionAfterTheSilenceLimit_andTheClientRetries() {
        LoopbackClient.awaitPortFree()
        GameStateRepository.getInstance().serverIP = InetAddress.getByName("127.0.0.1")
        PlayerRepository.getInstance().pairedGrenadeID = 0
        ServerSocket(WireProtocol.TCP_SERVER_PORT).use { server ->
            server.soTimeout = 10_000
            val client = object : TcpClient() {
                override fun startAsForeground(statusText: String?) {} // notifications are stubs here
            }
            client.startTcpClient()
            try {
                val first = server.accept()
                first.soTimeout = 10_000
                DataInputStream(first.getInputStream()).readUTF() // the hello

                // Half of a large game info, then nothing: the client used to block in readUTF forever.
                val players = (1..32).joinToString(",") { "{\"playername\":\"Player $it\",\"playerID\":$it}" }
                val bytes = ByteArrayOutputStream().also {
                    DataOutputStream(it).writeUTF(WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON + "{\"players\":[$players]}")
                }.toByteArray()
                first.getOutputStream().apply { write(bytes, 0, bytes.size / 2); flush() }
                val stalledAt = System.nanoTime()

                assertEquals(-1, first.getInputStream().read()) // the client closes its socket
                val droppedAfter = (System.nanoTime() - stalledAt) / 1_000_000
                assertTrue("dropped after $droppedAfter ms", droppedAfter in 3_300..3_800)

                // ...and enters its retry loop.
                val second = server.accept()
                val hello = JSONObject(DataInputStream(second.getInputStream()).readUTF().removePrefix(WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON))
                assertTrue(hello.getBoolean(WireKeys.REJOIN))
                second.close()
                first.close()
            } finally {
                client.stopTcpClient()
            }
        }
    }
}
