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

package com.simplecoil.simplecoil.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WireCodec against the golden fixtures (network design §7.2): encode gives the fixture, decoding it gives the message. */
class WireCodecTest {

    private fun decodeClient(frame: String) = WireCodec.decodeClient(Frame.parse(frame))

    private fun decodeServer(frame: String) = WireCodec.decodeServer(Frame.parse(frame))

    // ------------------------------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------------------------------

    private val clientCommands = listOf(
        ClientCommand.ShotFired, ClientCommand.Hit(17), ClientCommand.Out(17), ClientCommand.TeamEliminated,
        ClientCommand.PlayerDataRequest, ClientCommand.StartGame, ClientCommand.AdminStartGame, ClientCommand.AdminEndGame,
        ClientCommand.AdminSortTeams, ClientCommand.AdminReset, ClientCommand.AdminGameMode(4), ClientCommand.AdminKick(17),
        ClientCommand.AdminTeamSizes(listOf(10, 6, 8, 8)), ClientCommand.Leave,
    )

    @Test
    fun clientCommands_encodeToTheFixture_andDecodeBack() {
        val messages = clientCommands.map { ClientMessage.Command(it) }
        assertEquals(WireFixtures.commands("client/commands.txt"), messages.map { WireCodec.encode(it) })
        assertEquals(messages, WireFixtures.commands("client/commands.txt").map { decodeClient(it) })
    }

    private val serverCommands = listOf(
        ServerCommand.Ack(1), ServerCommand.Eliminated(1), ServerCommand.EndGame, ServerCommand.Error("Reset the game first"),
        ServerCommand.Hit(1), ServerCommand.Out(1), ServerCommand.ServerCancel, ServerCommand.ServerReply(1),
        ServerCommand.ServerReply(17), ServerCommand.ServerReply(2), ServerCommand.ShotFired, ServerCommand.StartGame,
        ServerCommand.TeamEliminated,
    )

    @Test
    fun serverCommands_encodeToTheFixture_andDecodeBack() {
        val messages: List<ServerMessage> = serverCommands.map { ServerMessage.Command(it) } + ServerMessage.VersionError
        assertEquals(WireFixtures.commands("server/commands.txt"), messages.map { WireCodec.encode(it) })
        assertEquals(messages, WireFixtures.commands("server/commands.txt").map { decodeServer(it) })
    }

    @Test
    fun theReliableEliminationsText_isTheBareCommand() {
        assertEquals("ELIMINATED17", WireCodec.text(ClientCommand.Eliminated(17)))
        assertEquals(ClientCommand.Eliminated(17), WireCodec.decodeClientCommand("ELIMINATED17"))
    }

    @Test
    fun clientCommandIds_areReadAsTheServerAlwaysDid() {
        // Ids are not trimmed; a bad one is null, which the server ignores
        assertEquals(ClientCommand.Hit(null), WireCodec.decodeClientCommand("HIT 3"))
        assertEquals(ClientCommand.Eliminated(null), WireCodec.decodeClientCommand("ELIMINATED"))
        assertEquals(ClientCommand.Out(null), WireCodec.decodeClientCommand("OUTx"))
        assertEquals(ClientCommand.Hit(44), WireCodec.decodeClientCommand("HIT300")) // (byte) 300
        // Admin arguments are trimmed
        assertEquals(ClientCommand.AdminKick(5), WireCodec.decodeClientCommand("ADMIN_KICK 5 "))
        assertEquals(ClientCommand.AdminGameMode(null), WireCodec.decodeClientCommand("ADMIN_GAMEMODEx"))
        assertEquals(ClientCommand.AdminTeamSizes(listOf(16, 16)), WireCodec.decodeClientCommand("ADMIN_TEAMSIZES 16, 16,"))
        assertEquals(ClientCommand.AdminTeamSizes(null), WireCodec.decodeClientCommand("ADMIN_TEAMSIZES16,x"))
        // Prefixes, as the server matched them
        assertEquals(ClientCommand.AdminStartGame, WireCodec.decodeClientCommand("ADMIN_STARTGAMEnow"))
        assertEquals(ClientCommand.EndGame, WireCodec.decodeClientCommand("ENDGAME"))
        assertEquals(ClientCommand.Other("ENDGAMEx"), WireCodec.decodeClientCommand("ENDGAMEx"))
    }

    @Test
    fun adminCommands_includeAPlainStartGameAndUnknownAdminOnes() {
        assertTrue(WireCodec.isAdminCommand(ClientCommand.StartGame))
        assertTrue(WireCodec.isAdminCommand(ClientCommand.AdminKick(null)))
        assertTrue(WireCodec.isAdminCommand(ClientCommand.Other("ADMIN_FUTURE")))
        assertFalse(WireCodec.isAdminCommand(ClientCommand.EndGame))
        assertFalse(WireCodec.isAdminCommand(ClientCommand.Other("FUTURE")))
    }

    @Test
    fun serverCommandIds_areReadAsTheClientAlwaysDid() {
        // Ids are trimmed; a missing or bad one is null
        assertEquals(ServerCommand.Hit(9), WireCodec.decodeServerCommand("HIT 9 "))
        assertEquals(ServerCommand.Hit(null), WireCodec.decodeServerCommand("HIT"))
        assertEquals(ServerCommand.ServerReply(null), WireCodec.decodeServerCommand("SERVERREPLYx"))
        assertEquals(ServerCommand.Ack(null), WireCodec.decodeServerCommand("ACK 3"))
        assertEquals(ServerCommand.Error(""), WireCodec.decodeServerCommand("ERROR"))
        assertEquals(ServerCommand.Other("PLAYERDATAREQUEST"), WireCodec.decodeServerCommand("PLAYERDATAREQUEST"))
    }

    @Test
    fun framesThatAreNotMessages_decodeAsNull() {
        for (raw in listOf(WireProtocol.PING, WireProtocol.PONG, "SimpleCoil:06MESGLEAVE", "garbage", "")) {
            assertNull(raw, decodeClient(raw))
        }
        for (raw in listOf(WireProtocol.PING, "SimpleCoil:06MESGLEAVE", "garbage")) {
            assertNull(raw, decodeServer(raw))
        }
    }
}
