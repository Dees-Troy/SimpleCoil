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
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.network.ClientCommand
import com.simplecoil.simplecoil.network.EventBus
import com.simplecoil.simplecoil.network.WireFixtures
import com.simplecoil.simplecoil.network.WireProtocol
import com.simplecoil.simplecoil.network.client.GameClient
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Every client → server frame against its golden fixture (network design §7.2), built by the
 * client's own code (GameClient, NetworkSessionGateway) from fixed inputs.
 */
class ClientWireFixturesTest {

    private val settings = GameSettingsRepository()
    private val gameState = GameStateRepository()
    private val players = PlayerRepository(settings, gameState)
    private val bytes = ByteArrayOutputStream()
    private val client = GameClient(settings, players, gameState, EventBus(), object : GameClient.Foreground {
        override fun start(statusText: String) {}
        override fun stop() {}
    })

    @Before
    fun setUp() {
        players.playerID = 5
        players.playerName = "Rook"
        players.deviceAddress = "AA:BB:CC:DD:EE:FF"
        players.pairedGrenadeID = 0
        settings.setFullHealth(20)
        settings.setFullReload(30)
        settings.setReloadTime(1500)
        settings.setReloadOnEmpty(true)
        settings.setManualRespawn(false)
        settings.setRespawnTime(10)
        settings.setRespawnShieldTime(3)
        settings.setDamage(-2)
        settings.setOverrideLives(false)
        settings.setOverrideLivesVal(0)
        settings.setAllowSingleShotMode(true)
        settings.setAllowBurst3ShotMode(false)
        settings.setAllowAutoShotMode(true)
        settings.setCurrentFiringMode(GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE)
    }

    /** The frames [block] writes. */
    private fun written(block: () -> Unit): List<String> {
        client.attachOutputForTest(DataOutputStream(bytes))
        bytes.reset()
        block()
        client.awaitWritesForTest()
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        val frames = mutableListOf<String>()
        try {
            while (true) frames += input.readUTF()
        } catch (e: EOFException) {
            // end
        }
        return frames
    }

    private fun single(block: () -> Unit): String = written(block).single()

    @Test
    fun hello_fresh() {
        players.playerID = 0
        WireFixtures.assertFrame("client/hello_fresh.frame", single { client.sendPlayerInfo(false) })
    }

    @Test
    fun hello_withoutMac() {
        players.deviceAddress = ""
        WireFixtures.assertFrame("client/hello_without_mac.frame", single { client.sendPlayerInfo(false) })
    }

    @Test
    fun hello_rejoinWithPending() {
        // The server's game info gives the outbox its game; two eliminations wait for their ack.
        client.handleFrameForTest(WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON +
            """{"players":[],"limits":{},"gamemode":2,"onlyserversettings":false,"sessionphase":1,"gameid":418873301}""")
        client.sendReliable(WireProtocol.NETMSG_ELIMINATED + 17)
        client.sendReliable(WireProtocol.NETMSG_ELIMINATED + 18)
        client.awaitWritesForTest() // written nowhere: no connection yet
        WireFixtures.assertFrame("client/hello_rejoin_pending.frame", single { client.sendPlayerInfo(true) })
    }

    @Test
    fun hello_withAPairedGrenade_isFollowedByThePairing() {
        players.pairedGrenadeID = 3
        val frames = written { client.sendPlayerInfo(false) }
        WireFixtures.assertFrame("client/hello_fresh_id5.frame", frames[0])
        WireFixtures.assertFrame("client/grenade_pairing.frame", frames[1])
    }

    @Test
    fun reliableEnvelope() {
        client.handleFrameForTest(WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON +
            """{"players":[],"limits":{},"gamemode":2,"onlyserversettings":false,"sessionphase":1,"gameid":418873301}""")
        WireFixtures.assertFrame("client/reliable.frame", single { client.sendReliable(WireProtocol.NETMSG_ELIMINATED + 17) })
    }

    @Test
    fun nameChange() {
        WireFixtures.assertFrame("client/name_change.frame", single { client.sendPlayerNameChange() })
    }

    @Test
    fun playerSettings_withoutAndWithALivesLimit() {
        WireFixtures.assertFrame("client/player_settings.frame", single { client.sendPlayerSettings() })
        settings.setOverrideLives(true)
        settings.setOverrideLivesVal(4)
        WireFixtures.assertFrame("client/player_settings_lives.frame", single { client.sendPlayerSettings() })
    }

    @Test
    fun teamChangeRequest() {
        WireFixtures.assertFrame("client/team_change.frame", NetworkSessionGateway.teamChangeRequest(5, 2))
        WireFixtures.assertFrame("client/team_change_swap.frame", NetworkSessionGateway.teamChangeRequest(5, 2, 18))
    }

    @Test
    fun grenadePairing() {
        players.pairedGrenadeID = 3
        WireFixtures.assertFrame("client/grenade_pairing.frame", single { client.sendPlayerGrenade() })
    }

    @Test
    fun gpsLocation() {
        WireFixtures.assertFrame("client/gps_location.frame", single { client.sendLocation(40.5, -74.25) })
    }

    @Test
    fun commands() {
        val service = mock(TcpClient::class.java)
        doAnswer { client.sendTCPMessage(it.getArgument(0)) }.`when`(service).sendTCPMessage(anyString())
        val gateway = NetworkSessionGateway(mock(Context::class.java), TestScope(), settings, players)
        gateway.attachForTest(null, service)
        val frames = written {
            gateway.sendShotFired()
            gateway.sendHit(17)
            gateway.sendOut(17)
            gateway.sendShieldHit(17)
            // The play screen's own commands (GameViewModel), built the same way
            for (command in listOf(ClientCommand.TeamEliminated, ClientCommand.PlayerDataRequest,
                    ClientCommand.ScoreMatrixRequest, ClientCommand.StartGame, ClientCommand.AdminStartGame, ClientCommand.AdminEndGame, ClientCommand.AdminSortTeams, ClientCommand.AdminReset,
                    ClientCommand.AdminGameMode(4), ClientCommand.AdminKick(17), ClientCommand.AdminTeamSizes(listOf(10, 6, 8, 8)),
                    ClientCommand.Leave)) { // leaveServer()'s LEAVE: it only writes while connected
                client.sendTCPMessage(NetworkSessionGateway.command(command))
            }
        }
        WireFixtures.assertCommands("client/commands.txt", frames)
    }
}
