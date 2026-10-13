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

import android.content.Intent
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.network.ClientEvent
import com.simplecoil.simplecoil.network.EventBus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mockConstruction

/** TcpClient's handling of single frames from the server (network design §5.1, N5). */
class TcpClientFrameTest {

    /** The actions of the intents [block] broadcasts. Intents are stubs here, so their constructors are recorded. */
    private fun broadcastsDuring(block: (TcpClient) -> Unit): List<String?> {
        val created = mutableListOf<Pair<Intent, String?>>()
        val sent = mutableListOf<Intent>()
        val client = object : TcpClient() {
            override fun sendBroadcast(intent: Intent?) {
                if (intent != null) sent += intent
            }
        }
        mockConstruction(Intent::class.java) { mock, context ->
            created += mock to context.arguments().firstOrNull() as String?
        }.use { block(client) }
        return sent.map { intent -> created.first { it.first === intent }.second }
    }

    @Test
    fun versionError_broadcastsTheVersionError_andEndsTheSession() {
        // It used to throw in substring before reaching its branch
        var ended = false
        val actions = broadcastsDuring { ended = it.handleFrame(NetMsg.NETMSG_VERSIONERROR) }
        assertTrue(ended)
        assertEquals(listOf(NetMsg.NETMSG_VERSIONERROR), actions)
    }

    @Test
    fun shortAndGarbageFrames_areIgnored() {
        val actions = broadcastsDuring {
            for (raw in listOf("", "Simple", TcpServer.TCPMESSAGE_PREFIX, TcpServer.TCPMESSAGE_PREFIX + "JS", "SimpleCoil:06MESGSTARTGAME"))
                assertFalse(raw, it.handleFrame(raw))
        }
        assertEquals(emptyList<String?>(), actions)
    }

    @Test
    fun eliminatedWithoutAnId_isIgnored() {
        val prefix = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG
        val actions = broadcastsDuring {
            assertFalse(it.handleFrame(prefix + NetMsg.NETMSG_ELIMINATED))
            assertFalse(it.handleFrame(prefix + NetMsg.NETMSG_ELIMINATED + "x"))
            assertFalse(it.handleFrame(prefix + NetMsg.NETMSG_ELIMINATED + "7"))
        }
        assertEquals(listOf(NetMsg.NETMSG_ELIMINATED), actions)
    }

    @Test
    fun serverCancel_endsTheSession() {
        var ended = false
        val actions = broadcastsDuring { ended = it.handleFrame(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_SERVERCANCEL) }
        assertTrue(ended)
        assertEquals(listOf(NetMsg.NETMSG_SERVERCANCEL), actions)
    }

    /** The client events [block] publishes. */
    private fun eventsDuring(block: (TcpClient) -> Unit): List<ClientEvent> = runBlocking {
        val bus = EventBus<ClientEvent>()
        val received = mutableListOf<ClientEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { bus.events.toList(received) }
        val client = TcpClient()
        client.setEventsForTest(bus)
        block(client)
        yield() // the collector drains what was published
        collector.cancel()
        received
    }

    @Test
    fun gpsUpdate_storesThePositions_andPublishesGpsData() {
        val json = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON
        val repo = PlayerRepository.getInstance()
        repo.playerID = 1
        try {
            val events = eventsDuring {
                it.handleFrame(json + """{"gpsupdate":[{"playerID":3,"team":1,"gpslongitude":4.5,"gpslatitude":6.5}]}""")
                it.handleFrame(json + """{"gpsupdate":[{"playerID":1,"team":1,"gpslongitude":0,"gpslatitude":0}],"gpsfullupdate":true}""")
            }
            assertEquals(listOf(ClientEvent.GpsData(false), ClientEvent.GpsData(true)), events)
            assertTrue(repo.gpsData.isEmpty()) // the full update replaced everything, and we are player 1
        } finally {
            repo.gpsData.clear()
            repo.playerID = 0
        }
    }
}
