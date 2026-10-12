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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
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
import org.mockito.Mockito.`when`

/**
 * The network session's wire strings are a compatibility surface (other phones and dedicated
 * servers run older builds), so they are pinned as literals here, like the BLE golden packets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkSessionGatewayTest {

    @Test
    fun messageFormat_isPrefixVersionMesgPayload() {
        assertEquals("SimpleCoil:06MESGHIT2", NetworkSessionGateway.message(NetMsg.NETMSG_HIT + 2.toByte()))
        assertEquals("SimpleCoil:06MESGOUT32", NetworkSessionGateway.message(NetMsg.NETMSG_OUT + 32.toByte()))
        assertEquals("SimpleCoil:06MESGSHOTFIRED", NetworkSessionGateway.message(NetMsg.NETMSG_SHOTFIRED))
        assertEquals("SimpleCoil:06MESGELIMINATED7", NetworkSessionGateway.message(NetMsg.NETMSG_ELIMINATED + 7.toByte()))
        assertEquals("SimpleCoil:06MESGTEAMELIMINATED", NetworkSessionGateway.message(NetMsg.NETMSG_TEAMELIMINATED))
        assertEquals("SimpleCoil:06MESGPLAYERDATAREQUEST", NetworkSessionGateway.message(NetMsg.NETMSG_PLAYERDATAREQUEST))
        assertEquals("SimpleCoil:06MESGSTARTGAME", NetworkSessionGateway.message(NetMsg.NETMSG_STARTGAME))
        assertEquals("SimpleCoil:06MESGADMIN_GAMEMODE4", NetworkSessionGateway.message(NetMsg.NETMSG_ADMIN_GAMEMODE + 4))
        assertEquals("SimpleCoil:06MESGADMIN_TEAMSIZES16,16", NetworkSessionGateway.message(NetMsg.NETMSG_ADMIN_TEAMSIZES + "16,16"))
    }

    private fun gateway(client: TcpClient? = mock(TcpClient::class.java), server: TcpServer? = mock(TcpServer::class.java)) =
        NetworkSessionGateway(mock(Context::class.java), TestScope()).also { it.attachForTest(null, client, server) }

    @Test
    fun sessionSends_useTheSameStringsAndQueueOnlyEliminated() {
        val client = mock(TcpClient::class.java)
        val g = gateway(client)
        g.sendHit(2)
        g.sendOut(3)
        g.sendShotFired()
        g.sendEliminated(4)
        verify(client).sendTCPMessage("SimpleCoil:06MESGHIT2")
        verify(client).sendTCPMessage("SimpleCoil:06MESGOUT3")
        verify(client).sendTCPMessage("SimpleCoil:06MESGSHOTFIRED")
        verify(client).sendTCPMessage("SimpleCoil:06MESGELIMINATED4", true)
    }

    @Test
    fun sessionSends_withoutClient_areDropped() {
        val g = gateway(client = null)
        g.sendHit(2)
        g.sendShotFired()
        g.sendEliminated(4)
    }

    @Test
    fun grenade_isReportedOnlyToDedicatedServers() {
        val client = mock(TcpClient::class.java)
        val g = gateway(client)
        g.sendPlayerGrenade()
        verify(client, never()).sendPlayerGrenade()
        `when`(client.isDedicatedServer).thenReturn(true)
        g.sendPlayerGrenade()
        verify(client).sendPlayerGrenade()
    }

    @Test
    fun hostOnly_scoreAndEndGame() {
        val server = mock(TcpServer::class.java)
        val g = gateway(server = server)
        g.score = 6
        g.setHostEliminations(2)
        g.serverEndGame()
        verify(server, never()).setHostScore(6, 2)
        verify(server, never()).endGame()
        g.isServer = true
        g.setHostEliminations(2)
        g.serverEndGame()
        verify(server).setHostScore(6, 2)
        verify(server).endGame()
    }

    private fun intent(action: String): Intent = mock(Intent::class.java).also { `when`(it.action).thenReturn(action) }

    @Test
    fun broadcasts_mapToEventsWithTheActivitysDefaults() {
        val hit = intent(NetMsg.NETMSG_HIT)
        `when`(hit.getByteExtra(UDPListenerService.INTENT_PLAYERID, 0)).thenReturn(9)
        assertEquals(NetworkEvent.Hit(9), NetworkSessionGateway.toEvent(hit))

        val reply = intent(NetMsg.NETMSG_SERVERREPLY)
        assertEquals(NetworkEvent.ServerReply(null), NetworkSessionGateway.toEvent(reply))
        `when`(reply.hasExtra(UDPListenerService.INTENT_PLAYERID)).thenReturn(true)
        `when`(reply.getByteExtra(UDPListenerService.INTENT_PLAYERID, 0)).thenReturn(3)
        assertEquals(NetworkEvent.ServerReply(3), NetworkSessionGateway.toEvent(reply))

        val list = intent(NetMsg.NETMSG_LISTPLAYERS)
        assertEquals(NetworkEvent.ListPlayers(null, null), NetworkSessionGateway.toEvent(list))
        `when`(list.getBooleanExtra(NetMsg.INTENT_HASGAMEUPDATE, false)).thenReturn(true)
        `when`(list.getIntExtra(NetMsg.INTENT_SCORE, 0)).thenReturn(4)
        `when`(list.getIntExtra(NetMsg.INTENT_ELIMINATIONS, 0)).thenReturn(2)
        `when`(list.getIntExtra(NetMsg.INTENT_TEAMSCORE, 0)).thenReturn(9)
        `when`(list.getLongExtra(NetMsg.INTENT_TIMEREMAINING, -1)).thenReturn(120)
        `when`(list.hasExtra(NetMsg.INTENT_GAMESTATE)).thenReturn(true)
        `when`(list.getIntExtra(NetMsg.INTENT_GAMESTATE, Globals.GAME_STATE_ELIMINATED)).thenReturn(Globals.GAME_STATE_RUNNING)
        assertEquals(NetworkEvent.ListPlayers(GameUpdate(4, 2, 9, 120), Globals.GAME_STATE_RUNNING), NetworkSessionGateway.toEvent(list))

        val error = intent(NetMsg.NETMSG_ERROR)
        `when`(error.getStringExtra(UDPListenerService.INTENT_MESSAGE)).thenReturn("boom")
        assertEquals(NetworkEvent.Error("boom"), NetworkSessionGateway.toEvent(error))

        assertNull(NetworkSessionGateway.toEvent(intent(NetMsg.NETMSG_SERVERCREATED)))
        assertEquals(NetworkEvent.EndGame, NetworkSessionGateway.toEvent(intent(NetMsg.NETMSG_ENDGAME)))
    }

    @Test
    fun everyFilteredActionIsKnown() {
        val actions = listOf(NetMsg.NETMSG_SHOTFIRED, NetMsg.NETMSG_HIT, NetMsg.NETMSG_OUT, NetMsg.NETMSG_ELIMINATED,
            NetMsg.NETMSG_JOIN, NetMsg.NETMSG_FAILEDTOJOIN, NetMsg.NETMSG_LEAVE, NetMsg.NETMSG_LISTPLAYERS,
            NetMsg.NETMSG_PLAYERDATAUPDATE, NetMsg.NETMSG_STARTGAME, NetMsg.NETMSG_ENDGAME, NetMsg.NETMSG_ERROR,
            NetMsg.NETMSG_VERSIONERROR, NetMsg.NETMSG_SAMETEAM, NetMsg.NETMSG_SERVERCANCEL, NetMsg.NETMSG_SERVERREPLY,
            NetMsg.NETMSG_TEAMELIMINATED, NetMsg.NETMSG_NETWORKCONNECTED, NetMsg.NETMSG_NETWORKDISCONNECTED,
            NetMsg.NETMSG_PLAYERSETTINGSUPDATE)
        for (action in actions) {
            assert(NetworkSessionGateway.toEvent(intent(action)) != null) { action }
        }
    }

    @Test
    fun bindServices_bindsEachServiceOnceAcrossResumes() {
        // B4: every resume used to bind all three services again.
        val context = mock(Context::class.java)
        val g = NetworkSessionGateway(context, TestScope())
        repeat(3) { g.bindServices() }
        verify(context, times(3)).bindService(any(Intent::class.java), any(ServiceConnection::class.java), anyInt())
        verify(context, times(9)).startService(any(Intent::class.java))

        g.unbindServices()
        verify(context, times(3)).unbindService(any(ServiceConnection::class.java))
        g.bindServices() // a new screen binds again
        verify(context, times(6)).bindService(any(Intent::class.java), any(ServiceConnection::class.java), anyInt())
    }

    @Test
    fun dedicatedServerHandOff_releasesAllThreeSoTheyRebindOnReturn() {
        val context = mock(Context::class.java)
        val g = NetworkSessionGateway(context, TestScope())
        g.bindServices()
        g.releaseForDedicatedServer()
        verify(context, times(3)).unbindService(any(ServiceConnection::class.java))
        g.bindServices()
        verify(context, times(6)).bindService(any(Intent::class.java), any(ServiceConnection::class.java), anyInt())
    }

    // ------------------------------------------------------------------------------------------
    // Scoreboard (B7)
    // ------------------------------------------------------------------------------------------

    private val playerJson = """{"playerdata":[
        {"playerID":1,"playername":"Me","points":4,"eliminated":1},
        {"playerID":2,"playername":"Mate","points":3,"eliminated":0},
        {"playerID":17,"playername":"Them","points":6,"eliminated":2}]}"""

    @Test
    fun parsePlayerData_buildsRowsMyPointsAndTeamPoints() {
        Globals.getInstance().mPlayerID = 1
        Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS
        val parsed = NetworkSessionGateway.parsePlayerData(playerJson)
        assertNotNull(parsed)
        parsed!!
        assertEquals("Me", parsed.players[1]!!.playerName)
        assertEquals(6, parsed.players[17]!!.points)
        assertEquals(4, parsed.myPoints)
        val t1 = Globals.getInstance().calcNetworkTeam(1) - 1
        val t17 = Globals.getInstance().calcNetworkTeam(17) - 1
        assertEquals(if (t1 == t17) 13 else 7, parsed.teamPoints[t1])
        assertNull(NetworkSessionGateway.parsePlayerData("not json"))
        // The semaphore is released on both paths.
        assertEquals(1, Globals.getInstance().mPlayerSettingsSemaphore.availablePermits())
    }

    @Test
    fun updatePlayerData_parsesOffMainThenWritesScoresOnMain() {
        Globals.getInstance().mPlayerID = 1
        Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS
        val scope = TestScope()
        val parse = StandardTestDispatcher(scope.testScheduler)
        val server = mock(TcpServer::class.java)
        val g = NetworkSessionGateway(mock(Context::class.java), scope, parse)
        g.attachForTest(null, null, server)
        g.isServer = true
        g.score = 2
        val events = mutableListOf<NetworkEvent>()
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { g.events.collect { events += it } }

        g.updatePlayerData(playerJson) { 5 }
        assertEquals(2, g.score) // nothing written before parsing completes
        scope.testScheduler.runCurrent()
        assertEquals(4, g.score)
        verify(server).setHostScore(4, 5)
        assertEquals(1, events.filterIsInstance<NetworkEvent.PlayerData>().size)

        g.updatePlayerData("""{"playerdata":[{"playerID":1,"playername":"Me","points":3,"eliminated":0}]}""") { 5 }
        scope.testScheduler.runCurrent()
        assertEquals(4, g.score) // never lowered
        g.updatePlayerData("") { 5 }
        g.updatePlayerData("garbage") { 5 }
        scope.testScheduler.runCurrent()
        assertEquals(2, events.filterIsInstance<NetworkEvent.PlayerData>().size)
    }

    @Test
    fun broadcastsAreNotPublishedWhileTheDedicatedServerOwnsTheNetwork() {
        val scope = TestScope()
        val context = mock(Context::class.java)
        val g = NetworkSessionGateway(context, scope)
        val events = mutableListOf<NetworkEvent>()
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { g.events.collect { events += it } }
        val receiver = g.receiver

        receiver.onReceive(context, intent(NetMsg.NETMSG_STARTGAME))
        g.releaseForDedicatedServer()
        receiver.onReceive(context, intent(NetMsg.NETMSG_STARTGAME))
        g.bindServices()
        receiver.onReceive(context, intent(NetMsg.NETMSG_ENDGAME))
        assertEquals(listOf(NetworkEvent.StartGame, NetworkEvent.EndGame), events)
    }
}
