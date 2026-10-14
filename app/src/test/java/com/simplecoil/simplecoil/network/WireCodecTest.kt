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

    // ------------------------------------------------------------------------------------------
    // Client JSON
    // ------------------------------------------------------------------------------------------

    private val mac = "AA:BB:CC:DD:EE:FF"

    private fun settings(livesLimit: Int?) = WirePlayerSettings(
        playerId = 5, health = 20, reloadShots = 30, reloadTime = 1500, reloadOnEmpty = true, manualRespawn = false,
        spawnTime = 10, respawnShieldTime = 3, damage = -2, livesLimit = livesLimit, shotModeSingle = true,
        shotModeBurst3 = false, shotModeAuto = true, firingMode = 2, recoilSetting = null, isAdmin = null,
    )

    private val clientFixtures = mapOf(
        "client/hello_fresh.frame" to ClientMessage.Hello(0, "Rook", mac, false, null, emptyList()),
        "client/hello_fresh_id5.frame" to ClientMessage.Hello(5, "Rook", mac, false, null, emptyList()),
        "client/hello_rejoin_pending.frame" to ClientMessage.Hello(5, "Rook", mac, true, 418873301,
            listOf(WirePending("ELIMINATED17", 1), WirePending("ELIMINATED18", 2))),
        "client/hello_without_mac.frame" to ClientMessage.Hello(5, "Rook", null, false, null, emptyList()),
        "client/reliable.frame" to ClientMessage.Reliable("ELIMINATED17", 1, 418873301),
        "client/name_change.frame" to ClientMessage.NameChange(5, "Rook"),
        "client/player_settings.frame" to ClientMessage.Settings(5, settings(null)),
        "client/player_settings_lives.frame" to ClientMessage.Settings(5, settings(4)),
        "client/team_change.frame" to ClientMessage.TeamChangeRequest(5, 2),
        "client/grenade_pairing.frame" to ClientMessage.GrenadePairing(5, 3),
        "client/gps_location.frame" to ClientMessage.GpsLocation(40.5, -74.25),
    )

    @Test
    fun clientJson_encodesToTheFixtures_andDecodesBack() {
        for ((name, message) in clientFixtures) {
            WireFixtures.assertFrame(name, WireCodec.encode(message))
            assertEquals(name, message, decodeClient(WireFixtures.frame(name)))
        }
        // An empty MAC is left out, like none
        WireFixtures.assertFrame("client/hello_without_mac.frame", WireCodec.encode(ClientMessage.Hello(5, "Rook", "", false, null, emptyList())))
    }

    private fun clientJson(json: String) = decodeClient(WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON + json)

    @Test
    fun clientJson_isToldApartByItsKeys_inTheServersOrder() {
        assertEquals(ClientMessage.Reliable("ELIMINATED3", 1, 0), clientJson("""{"rmsg":"ELIMINATED3","seq":1,"gpslongitude":1}"""))
        assertEquals(ClientMessage.GpsLocation(2.0, 1.0), clientJson("""{"gpslongitude":1,"gpslatitude":2,"playersettings":true}"""))
        assertEquals(ClientMessage.NameChange(0, "X"), clientJson("""{"playernamechange":"X","playername":"Y"}"""))
        // Anything else is a hello
        assertEquals(ClientMessage.Hello(-1, "X", null, false, null, emptyList()), clientJson("""{"playerID":-1,"playername":"X"}"""))
    }

    @Test
    fun badClientJson_decodesAsNull_butBadSettingsStillAskForTheSettings() {
        for (json in listOf("{", "[]", """{"playerID":"x","playername":"X"}""", """{"rmsg":"ELIMINATED","gameid":1}""",
                """{"gpslongitude":1}""", """{"playerID":1}""")) {
            assertNull(json, clientJson(json))
        }
        assertEquals(ClientMessage.Settings(1, null), clientJson("""{"playerID":1,"playersettings":"x"}"""))
    }

    @Test
    fun aHellosPendingEntries_skipTheIncompleteOnes() {
        val hello = clientJson("""{"playerID":1,"playername":"X","gameid":7,"pending":[{"rmsg":"ELIMINATED2"},5,{"rmsg":"ELIMINATED3","seq":4}]}""")
        assertEquals(ClientMessage.Hello(1, "X", null, false, 7, listOf(WirePending("ELIMINATED3", 4))), hello)
    }
}
