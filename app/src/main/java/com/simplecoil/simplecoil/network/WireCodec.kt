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

import android.util.Log
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ACK
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ADMIN_ENDGAME
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ADMIN_GAMEMODE
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ADMIN_KICK
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ADMIN_RESET
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ADMIN_SORTTEAMS
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ADMIN_STARTGAME
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ADMIN_TEAMSIZES
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ELIMINATED
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ENDGAME
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_ERROR
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_HIT
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_LEAVE
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_OUT
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_PLAYERDATAREQUEST
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_SERVERCANCEL
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_SERVERREPLY
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_SHOTFIRED
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_STARTGAME
import com.simplecoil.simplecoil.network.WireProtocol.NETMSG_TEAMELIMINATED

/**
 * The v07 wire format (network design §7): typed messages to frames and back. The only code
 * that knows the header strings and the JSON keys. JSON goes through org.json (Q2).
 *
 * Decoding never throws: a frame it can't make sense of decodes as null, which the receiver
 * ignores.
 */
object WireCodec {
    private const val TAG = "WireCodec"
    private const val ADMIN_PREFIX = "ADMIN_"
    private const val MESG_HEADER = WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_MESG

    // ---------------------------------------------------------------------------------------
    // Frames
    // ---------------------------------------------------------------------------------------

    @JvmStatic
    fun encode(message: ClientMessage): String = when (message) {
        is ClientMessage.Command -> MESG_HEADER + text(message.command)
    }

    @JvmStatic
    fun encode(message: ServerMessage): String = when (message) {
        is ServerMessage.Command -> MESG_HEADER + text(message.command)
        ServerMessage.VersionError -> WireProtocol.NETMSG_VERSIONERROR
    }

    /** What a client's MESG or JSON frame says; null for any other kind of frame, or one that doesn't decode. */
    @JvmStatic
    fun decodeClient(frame: Frame): ClientMessage? = when (frame.kind) {
        Frame.Kind.MESG -> ClientMessage.Command(decodeClientCommand(frame.body))
        else -> null
    }

    /** What the server's MESG or JSON frame says; null for any other kind of frame, or one that doesn't decode. */
    @JvmStatic
    fun decodeServer(frame: Frame): ServerMessage? = when (frame.kind) {
        Frame.Kind.MESG -> ServerMessage.Command(decodeServerCommand(frame.body))
        Frame.Kind.VERSION_ERROR -> ServerMessage.VersionError
        else -> null
    }

    // ---------------------------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------------------------

    /** The command's text: a MESG body, or a reliable message's rmsg. */
    @JvmStatic
    fun text(command: ClientCommand): String = when (command) {
        ClientCommand.ShotFired -> NETMSG_SHOTFIRED
        is ClientCommand.Hit -> NETMSG_HIT + command.shooterId.argument()
        is ClientCommand.Out -> NETMSG_OUT + command.shooterId.argument()
        is ClientCommand.Eliminated -> NETMSG_ELIMINATED + command.shooterId.argument()
        ClientCommand.TeamEliminated -> NETMSG_TEAMELIMINATED
        ClientCommand.PlayerDataRequest -> NETMSG_PLAYERDATAREQUEST
        ClientCommand.Leave -> NETMSG_LEAVE
        ClientCommand.EndGame -> NETMSG_ENDGAME
        ClientCommand.StartGame -> NETMSG_STARTGAME
        ClientCommand.AdminStartGame -> NETMSG_ADMIN_STARTGAME
        ClientCommand.AdminEndGame -> NETMSG_ADMIN_ENDGAME
        ClientCommand.AdminReset -> NETMSG_ADMIN_RESET
        ClientCommand.AdminSortTeams -> NETMSG_ADMIN_SORTTEAMS
        is ClientCommand.AdminGameMode -> NETMSG_ADMIN_GAMEMODE + command.wireValue.argument()
        is ClientCommand.AdminKick -> NETMSG_ADMIN_KICK + command.playerId.argument()
        is ClientCommand.AdminTeamSizes -> NETMSG_ADMIN_TEAMSIZES + (command.sizes?.joinToString(",") ?: "")
        is ClientCommand.Other -> command.text
    }

    @JvmStatic
    fun text(command: ServerCommand): String = when (command) {
        ServerCommand.StartGame -> NETMSG_STARTGAME
        ServerCommand.EndGame -> NETMSG_ENDGAME
        ServerCommand.ShotFired -> NETMSG_SHOTFIRED
        is ServerCommand.Hit -> NETMSG_HIT + command.targetId.argument()
        is ServerCommand.Out -> NETMSG_OUT + command.targetId.argument()
        is ServerCommand.Eliminated -> NETMSG_ELIMINATED + command.victimId.argument()
        ServerCommand.TeamEliminated -> NETMSG_TEAMELIMINATED
        is ServerCommand.ServerReply -> NETMSG_SERVERREPLY + command.playerId.argument()
        is ServerCommand.Ack -> NETMSG_ACK + command.seq.argument()
        is ServerCommand.Error -> NETMSG_ERROR + command.message
        ServerCommand.ServerCancel -> NETMSG_SERVERCANCEL
        is ServerCommand.Other -> command.text
    }

    /** A client's command, as the server has always read it: ids without spaces, admin arguments trimmed. */
    @JvmStatic
    fun decodeClientCommand(text: String): ClientCommand = when {
        text == NETMSG_LEAVE -> ClientCommand.Leave
        text.startsWith(NETMSG_ELIMINATED) -> ClientCommand.Eliminated(strictId(text.after(NETMSG_ELIMINATED)))
        text == NETMSG_SHOTFIRED -> ClientCommand.ShotFired
        text.startsWith(NETMSG_HIT) -> ClientCommand.Hit(strictId(text.after(NETMSG_HIT)))
        text.startsWith(NETMSG_OUT) -> ClientCommand.Out(strictId(text.after(NETMSG_OUT)))
        text == NETMSG_TEAMELIMINATED -> ClientCommand.TeamEliminated
        text == NETMSG_PLAYERDATAREQUEST -> ClientCommand.PlayerDataRequest
        text == NETMSG_STARTGAME -> ClientCommand.StartGame
        text.startsWith(NETMSG_ADMIN_STARTGAME) -> ClientCommand.AdminStartGame
        text == NETMSG_ENDGAME -> ClientCommand.EndGame
        text.startsWith(NETMSG_ADMIN_ENDGAME) -> ClientCommand.AdminEndGame
        text.startsWith(NETMSG_ADMIN_RESET) -> ClientCommand.AdminReset
        text.startsWith(NETMSG_ADMIN_SORTTEAMS) -> ClientCommand.AdminSortTeams
        text.startsWith(NETMSG_ADMIN_GAMEMODE) -> ClientCommand.AdminGameMode(strictInt(text.after(NETMSG_ADMIN_GAMEMODE).trim()))
        text.startsWith(NETMSG_ADMIN_KICK) -> ClientCommand.AdminKick(strictInt(text.after(NETMSG_ADMIN_KICK).trim())?.toByte())
        text.startsWith(NETMSG_ADMIN_TEAMSIZES) -> ClientCommand.AdminTeamSizes(teamSizes(text.after(NETMSG_ADMIN_TEAMSIZES).trim()))
        else -> ClientCommand.Other(text)
    }

    /** Every ADMIN_ command, and a plain STARTGAME: only admins may run the game (D14). */
    @JvmStatic
    fun isAdminCommand(command: ClientCommand): Boolean = when (command) {
        ClientCommand.StartGame, ClientCommand.AdminStartGame, ClientCommand.AdminEndGame, ClientCommand.AdminReset,
        ClientCommand.AdminSortTeams, is ClientCommand.AdminGameMode, is ClientCommand.AdminKick,
        is ClientCommand.AdminTeamSizes -> true
        is ClientCommand.Other -> command.text.startsWith(ADMIN_PREFIX)
        else -> false
    }

    /** The server's command, as the client has always read it: ids trimmed, a missing one null. */
    @JvmStatic
    fun decodeServerCommand(text: String): ServerCommand = when {
        text.startsWith(NETMSG_ELIMINATED) -> ServerCommand.Eliminated(lenientId(text.after(NETMSG_ELIMINATED)))
        text.startsWith(NETMSG_HIT) -> ServerCommand.Hit(lenientId(text.after(NETMSG_HIT)))
        text.startsWith(NETMSG_OUT) -> ServerCommand.Out(lenientId(text.after(NETMSG_OUT)))
        text == NETMSG_SHOTFIRED -> ServerCommand.ShotFired
        text.startsWith(NETMSG_SERVERREPLY) -> ServerCommand.ServerReply(lenientId(text.after(NETMSG_SERVERREPLY)))
        text == NETMSG_TEAMELIMINATED -> ServerCommand.TeamEliminated
        text == NETMSG_ENDGAME -> ServerCommand.EndGame
        text == NETMSG_STARTGAME -> ServerCommand.StartGame
        text == NETMSG_SERVERCANCEL -> ServerCommand.ServerCancel
        text.startsWith(NETMSG_ACK) -> ServerCommand.Ack(strictInt(text.after(NETMSG_ACK)))
        text.startsWith(NETMSG_ERROR) -> ServerCommand.Error(text.after(NETMSG_ERROR))
        else -> ServerCommand.Other(text)
    }

    private fun String.after(prefix: String) = substring(prefix.length)

    private fun Any?.argument(): String = this?.toString() ?: ""

    /** Integer.parseInt as the parsers used it (no trimming), or null. */
    private fun strictInt(text: String): Int? = try {
        Integer.parseInt(text)
    } catch (e: NumberFormatException) {
        Log.w(TAG, "Bad number '$text'")
        null
    }

    private fun strictId(text: String): Byte? = strictInt(text)?.toByte()

    /** A player id from a command's argument, or null when it is missing or not a number. */
    private fun lenientId(text: String): Byte? = if (text.isEmpty()) null else strictInt(text.trim())?.toByte()

    private fun teamSizes(text: String): List<Int>? = try {
        text.split(",").dropLastWhile { it.isEmpty() }.map { Integer.parseInt(it.trim()) } // as String.split did
    } catch (e: NumberFormatException) {
        Log.w(TAG, "Bad team sizes '$text'")
        null
    }
}
