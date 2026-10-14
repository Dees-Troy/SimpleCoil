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

// The typed v07 wire model (network design §7.3). WireCodec is the only code that turns these
// into frames and back; nothing else knows the header strings or the JSON keys.

/**
 * A MESG command from a client. An id that is missing or not a number decodes as null, which
 * the server ignores, as its parser did.
 */
sealed interface ClientCommand {
    data object ShotFired : ClientCommand

    /** The sender was hit by [shooterId]. */
    data class Hit(val shooterId: Byte?) : ClientCommand

    /** The sender is out of health, from [shooterId]'s shot. */
    data class Out(val shooterId: Byte?) : ClientCommand

    /** The sender was eliminated by [shooterId]. Sent reliably since v07 (as a reliable message's rmsg). */
    data class Eliminated(val shooterId: Byte?) : ClientCommand
    data object TeamEliminated : ClientCommand
    data object PlayerDataRequest : ClientCommand
    data object Leave : ClientCommand

    /** A plain ENDGAME: the client is leaving (older clients). */
    data object EndGame : ClientCommand

    /** A plain STARTGAME; admins only (D14). */
    data object StartGame : ClientCommand
    data object AdminStartGame : ClientCommand
    data object AdminEndGame : ClientCommand
    data object AdminReset : ClientCommand
    data object AdminSortTeams : ClientCommand
    data class AdminGameMode(val wireValue: Int?) : ClientCommand
    data class AdminKick(val playerId: Byte?) : ClientCommand

    /** Null when a size is not a number. */
    data class AdminTeamSizes(val sizes: List<Int>?) : ClientCommand

    /** Anything else; one that starts with ADMIN_ still counts as an admin command. */
    data class Other(val text: String) : ClientCommand
}

/** A MESG command from the server. An id that is missing or not a number decodes as null. */
sealed interface ServerCommand {
    data object StartGame : ServerCommand
    data object EndGame : ServerCommand
    data object ShotFired : ServerCommand

    /** The receiver's shot hit [targetId]. */
    data class Hit(val targetId: Byte?) : ServerCommand

    /** The receiver's shot took [targetId] out of health. */
    data class Out(val targetId: Byte?) : ServerCommand

    /** The receiver eliminated [victimId]. */
    data class Eliminated(val victimId: Byte?) : ServerCommand

    /** A teammate eliminated someone. */
    data object TeamEliminated : ServerCommand

    /** The receiver's (new) player id. */
    data class ServerReply(val playerId: Byte?) : ServerCommand

    /** Every reliable message up to [seq] is applied. */
    data class Ack(val seq: Int?) : ServerCommand
    data class Error(val message: String) : ServerCommand

    /** The server dropped this player (kicked, or the server stopped). */
    data object ServerCancel : ServerCommand
    data class Other(val text: String) : ServerCommand
}

/** What a client sends the server. */
sealed interface ClientMessage {
    data class Command(val command: ClientCommand) : ClientMessage
}

/** What the server sends a client. */
sealed interface ServerMessage {
    data class Command(val command: ServerCommand) : ServerMessage

    /** Sent bare, without the header: the client may not speak our version. */
    data object VersionError : ServerMessage
}
