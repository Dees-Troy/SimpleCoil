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

import android.content.Context
import android.content.Intent
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.network.ClientEvent
import com.simplecoil.simplecoil.network.WireProtocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import android.content.ServiceConnection
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/**
 * The network session's wire strings are a compatibility surface (other phones and dedicated
 * servers run older builds), so they are pinned as literals here, like the BLE golden packets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkSessionGatewayTest {

    private val settings = GameSettingsRepository()
    private val players = PlayerRepository(settings, GameStateRepository())


    @Test
    fun messageFormat_isPrefixVersionMesgPayload() {
        assertEquals("SimpleCoil:07MESGHIT2", NetworkSessionGateway.message(WireProtocol.NETMSG_HIT + 2.toByte()))
        assertEquals("SimpleCoil:07MESGOUT32", NetworkSessionGateway.message(WireProtocol.NETMSG_OUT + 32.toByte()))
        assertEquals("SimpleCoil:07MESGSHOTFIRED", NetworkSessionGateway.message(WireProtocol.NETMSG_SHOTFIRED))
        assertEquals("SimpleCoil:07MESGELIMINATED7", NetworkSessionGateway.message(WireProtocol.NETMSG_ELIMINATED + 7.toByte()))
        assertEquals("SimpleCoil:07MESGTEAMELIMINATED", NetworkSessionGateway.message(WireProtocol.NETMSG_TEAMELIMINATED))
        assertEquals("SimpleCoil:07MESGPLAYERDATAREQUEST", NetworkSessionGateway.message(WireProtocol.NETMSG_PLAYERDATAREQUEST))
        assertEquals("SimpleCoil:07MESGSTARTGAME", NetworkSessionGateway.message(WireProtocol.NETMSG_STARTGAME))
        assertEquals("SimpleCoil:07MESGADMIN_GAMEMODE4", NetworkSessionGateway.message(WireProtocol.NETMSG_ADMIN_GAMEMODE + 4))
        assertEquals("SimpleCoil:07MESGADMIN_TEAMSIZES16,16", NetworkSessionGateway.message(WireProtocol.NETMSG_ADMIN_TEAMSIZES + "16,16"))
        assertEquals("SimpleCoil:07MESGADMIN_RESET", NetworkSessionGateway.message(WireProtocol.NETMSG_ADMIN_RESET))
    }

    private fun gateway(client: TcpClient? = mock(TcpClient::class.java)) =
        NetworkSessionGateway(mock(Context::class.java), TestScope(), settings, players).also { it.attachForTest(null, client) }

    @Test
    fun sessionSends_useTheSameStrings_andEliminatedGoesReliably() {
        val client = mock(TcpClient::class.java)
        val g = gateway(client)
        g.sendHit(2)
        g.sendOut(3)
        g.sendShotFired()
        g.sendEliminated(4)
        verify(client).sendTCPMessage("SimpleCoil:07MESGHIT2")
        verify(client).sendTCPMessage("SimpleCoil:07MESGOUT3")
        verify(client).sendTCPMessage("SimpleCoil:07MESGSHOTFIRED")
        verify(client).sendReliable("ELIMINATED4")
    }

    @Test
    fun sessionSends_withoutClient_areDropped() {
        val g = gateway(client = null)
        g.sendHit(2)
        g.sendShotFired()
        g.sendEliminated(4)
    }

    @Test
    fun grenade_isReportedToTheServer() {
        val client = mock(TcpClient::class.java)
        val g = gateway(client)
        g.sendPlayerGrenade()
        verify(client).sendPlayerGrenade()
    }

    @Test
    fun bindServices_bindsEachServiceOnceAcrossResumes() {
        // B4: every resume used to bind all the services again.
        val context = mock(Context::class.java)
        val g = NetworkSessionGateway(context, TestScope(), settings, players)
        repeat(3) { g.bindServices() }
        verify(context, times(2)).bindService(any(Intent::class.java), any(ServiceConnection::class.java), anyInt())
        verify(context, times(6)).startService(any(Intent::class.java))

        g.unbindServices()
        verify(context, times(2)).unbindService(any(ServiceConnection::class.java))
        g.bindServices() // a new screen binds again
        verify(context, times(4)).bindService(any(Intent::class.java), any(ServiceConnection::class.java), anyInt())
    }

    @Test
    fun dedicatedServerHandOff_releasesBothSoTheyRebindOnReturn() {
        val context = mock(Context::class.java)
        val g = NetworkSessionGateway(context, TestScope(), settings, players)
        g.bindServices()
        g.releaseForDedicatedServer()
        verify(context, times(2)).unbindService(any(ServiceConnection::class.java))
        g.bindServices()
        verify(context, times(4)).bindService(any(Intent::class.java), any(ServiceConnection::class.java), anyInt())
    }

    // ------------------------------------------------------------------------------------------
    // Scoreboard (B7)
    // ------------------------------------------------------------------------------------------

    private val playerJson = """{"playerdata":[
        {"playerID":1,"playername":"Me","points":4,"eliminated":1,"connected":false},
        {"playerID":2,"playername":"Mate","points":3,"eliminated":0},
        {"playerID":17,"playername":"Them","points":6,"eliminated":2}]}"""

    @Test
    fun parsePlayerData_buildsRowsMyPointsAndTeamPoints() {
        players.playerID = 1
        val parsed = NetworkSessionGateway.parsePlayerData(playerJson, players, settings.teamLayout)
        assertNotNull(parsed)
        parsed!!
        assertEquals("Me", parsed.players[1]!!.playerName)
        assertFalse(parsed.players[1]!!.isConnected)
        assertTrue(parsed.players[2]!!.isConnected) // absent means connected
        assertEquals(6, parsed.players[17]!!.points)
        assertEquals(4, parsed.myPoints)
        val t1 = players.calcNetworkTeam(1) - 1
        val t17 = players.calcNetworkTeam(17) - 1
        assertEquals(if (t1 == t17) 13 else 7, parsed.teamPoints[t1])
        assertNull(NetworkSessionGateway.parsePlayerData("not json", players, settings.teamLayout))
    }

    @Test
    fun updatePlayerData_parsesOffMainThenWritesScoresOnMain() {
        players.playerID = 1
        val scope = TestScope()
        val parse = StandardTestDispatcher(scope.testScheduler)
        val g = NetworkSessionGateway(mock(Context::class.java), scope, settings, players, parse)
        g.score = 2
        val events = mutableListOf<ClientEvent>()
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { g.events.collect { events += it } }

        g.updatePlayerData(playerJson)
        assertEquals(2, g.score) // nothing written before parsing completes
        scope.testScheduler.runCurrent()
        assertEquals(4, g.score)
        assertEquals(1, events.filterIsInstance<ClientEvent.PlayerData>().size)

        g.updatePlayerData("""{"playerdata":[{"playerID":1,"playername":"Me","points":3,"eliminated":0}]}""")
        scope.testScheduler.runCurrent()
        assertEquals(4, g.score) // never lowered
        g.updatePlayerData("")
        g.updatePlayerData("garbage")
        scope.testScheduler.runCurrent()
        assertEquals(2, events.filterIsInstance<ClientEvent.PlayerData>().size)
    }
}
