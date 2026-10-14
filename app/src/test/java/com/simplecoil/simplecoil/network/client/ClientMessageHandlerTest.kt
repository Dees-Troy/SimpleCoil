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

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.network.ClientEvent
import com.simplecoil.simplecoil.network.EventBus
import com.simplecoil.simplecoil.network.GameUpdate
import com.simplecoil.simplecoil.network.WireProtocol
import com.simplecoil.simplecoil.network.server.SessionPhase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client's handling of single frames from the server (network design §5.1, N5), and the client
 * events each one publishes (§6.5). The defaults the broadcasts' extras used to supply are now in
 * the parsing code, so they are pinned here.
 */
class ClientMessageHandlerTest {

    private val mesg = WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_MESG
    private val json = WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON
    private val settings = GameSettingsRepository()
    private val repo = PlayerRepository(settings, GameStateRepository())

    /** The client events [block] publishes. */
    private fun eventsDuring(block: (ClientMessageHandler) -> Unit): List<ClientEvent> = runBlocking {
        val bus = EventBus<ClientEvent>()
        val received = mutableListOf<ClientEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { bus.events.toList(received) }
        block(ClientMessageHandler(settings, repo, ClientOutbox(), bus))
        yield() // the collector drains what was published
        collector.cancel()
        received
    }

    /** The events one frame publishes. */
    private fun eventsFor(raw: String): List<ClientEvent> = eventsDuring { it.handle(raw) }

    @Test
    fun versionError_publishesTheVersionError_andEndsTheSession() {
        // It used to throw in substring before reaching its branch
        var ended = false
        val events = eventsDuring { ended = it.handle(WireProtocol.NETMSG_VERSIONERROR) }
        assertTrue(ended)
        assertEquals(listOf(ClientEvent.VersionError), events)
    }

    @Test
    fun shortAndGarbageFrames_areIgnored() {
        val events = eventsDuring {
            for (raw in listOf("", "Simple", WireProtocol.TCPMESSAGE_PREFIX, WireProtocol.TCPMESSAGE_PREFIX + "JS", "SimpleCoil:06MESGSTARTGAME"))
                assertFalse(raw, it.handle(raw))
        }
        assertEquals(emptyList<ClientEvent>(), events)
    }

    @Test
    fun eliminatedWithoutAnId_isIgnored() {
        val events = eventsDuring {
            assertFalse(it.handle(mesg + WireProtocol.NETMSG_ELIMINATED))
            assertFalse(it.handle(mesg + WireProtocol.NETMSG_ELIMINATED + "x"))
            assertFalse(it.handle(mesg + WireProtocol.NETMSG_ELIMINATED + "7"))
        }
        assertEquals(listOf(ClientEvent.Eliminated(7)), events)
    }

    @Test
    fun serverCancel_endsTheSession() {
        var ended = false
        val events = eventsDuring { ended = it.handle(mesg + WireProtocol.NETMSG_SERVERCANCEL) }
        assertTrue(ended)
        assertEquals(listOf(ClientEvent.ServerCancel), events)
    }

    @Test
    fun commands_publishTheirEvents() {
        assertEquals(listOf(ClientEvent.ShotFired), eventsFor(mesg + WireProtocol.NETMSG_SHOTFIRED))
        assertEquals(listOf(ClientEvent.TeamEliminated), eventsFor(mesg + WireProtocol.NETMSG_TEAMELIMINATED))
        assertEquals(listOf(ClientEvent.StartGame), eventsFor(mesg + WireProtocol.NETMSG_STARTGAME))
        assertEquals(listOf(ClientEvent.EndGame), eventsFor(mesg + WireProtocol.NETMSG_ENDGAME))
        assertEquals(listOf(ClientEvent.Error("Reset the game first")), eventsFor(mesg + WireProtocol.NETMSG_ERROR + "Reset the game first"))
        assertEquals(emptyList<ClientEvent>(), eventsFor(mesg + WireProtocol.NETMSG_ACK + "3"))
    }

    @Test
    fun hitAndOut_withoutAnId_namePlayerZero() {
        assertEquals(listOf(ClientEvent.Hit(9)), eventsFor(mesg + WireProtocol.NETMSG_HIT + "9"))
        assertEquals(listOf(ClientEvent.Hit(0)), eventsFor(mesg + WireProtocol.NETMSG_HIT))
        assertEquals(listOf(ClientEvent.Out(17)), eventsFor(mesg + WireProtocol.NETMSG_OUT + "17"))
        assertEquals(listOf(ClientEvent.Out(0)), eventsFor(mesg + WireProtocol.NETMSG_OUT + "x"))
    }

    @Test
    fun serverReply_carriesTheAssignedId_whenThereIsOne() {
        assertEquals(listOf(ClientEvent.ServerReply(3)), eventsFor(mesg + WireProtocol.NETMSG_SERVERREPLY + "3"))
        assertEquals(3.toByte(), repo.playerID)
        assertEquals(listOf(ClientEvent.ServerReply(null)), eventsFor(mesg + WireProtocol.NETMSG_SERVERREPLY))
        assertEquals(3.toByte(), repo.playerID)
    }

    @Test
    fun playerData_publishesTheWholeFrameBody() {
        val body = """{"playerdata":[{"playerID":1,"playername":"Me","points":4,"eliminated":1}]}"""
        assertEquals(listOf(ClientEvent.PlayerDataUpdate(body)), eventsFor(json + body))
    }

    /** A lobby game info as the server builds it, with [extra] JSON members appended. */
    private fun gameInfo(extra: String = "") = json +
        """{"players":[],"limits":{},"gamemode":2,"onlyserversettings":false,"playersettings":[],""" +
        """"allowplayersettings":true$extra}"""

    @Test
    fun gameInfo_withoutUpdateOrState_publishesTheSettingsThenAnEmptyListPlayers() {
        assertEquals(listOf(ClientEvent.PlayerSettingsUpdate, ClientEvent.ListPlayers(null, null)), eventsFor(gameInfo()))
    }

    @Test
    fun gameInfo_withAGameUpdate_defaultsTheMissingTeamScoreAndTime() {
        val events = eventsFor(gameInfo(""","gamestate":${GameStateRepository.GAME_STATE_RUNNING},""" +
            """"playergameupdate":{"points":4,"eliminated":2,"ackseq":0}"""))
        assertEquals(listOf(ClientEvent.PlayerSettingsUpdate,
            ClientEvent.ListPlayers(GameUpdate(4, 2, 0, -1), GameStateRepository.GAME_STATE_RUNNING)), events)
    }

    @Test
    fun gameInfo_carriesTheGameUpdate_phaseAndGameId() {
        val events = eventsFor(gameInfo(""","gamestate":${GameStateRepository.GAME_STATE_RUNNING},""" +
            """"sessionphase":${SessionPhase.RUNNING.wireValue},"gameid":418873301,""" +
            """"playergameupdate":{"points":4,"eliminated":2,"teampoints":9,"timeremaining":120,"ackseq":0}"""))
        assertEquals(ClientEvent.ListPlayers(GameUpdate(4, 2, 9, 120), GameStateRepository.GAME_STATE_RUNNING,
            SessionPhase.RUNNING.wireValue, 418873301), events.last())
    }

    @Test
    fun gpsUpdate_storesThePositions_andPublishesGpsData() {
        repo.playerID = 1
        val events = eventsDuring {
            it.handle(json + """{"gpsupdate":[{"playerID":3,"team":1,"gpslongitude":4.5,"gpslatitude":6.5}]}""")
            it.handle(json + """{"gpsupdate":[{"playerID":1,"team":1,"gpslongitude":0,"gpslatitude":0}],"gpsfullupdate":true}""")
        }
        assertEquals(listOf(ClientEvent.GpsData(false), ClientEvent.GpsData(true)), events)
        assertTrue(repo.gpsData.isEmpty()) // the full update replaced everything, and we are player 1
    }
}
