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

/**
 * One received TCP frame, classified by its header. Shared by the server and the client so
 * neither has to cut the header with substring, which threw on short frames (N5).
 */
class Frame private constructor(
    val kind: Kind,
    /** The command or the JSON text; "" for every other kind. */
    val body: String,
) {
    enum class Kind {
        /** `SimpleCoil:07MESG<command>`; the body is the command. */
        MESG,

        /** `SimpleCoil:07JSON<json>`; the body is the JSON text. */
        JSON,
        PING,
        PONG,
        VERSION_ERROR,

        /** `SimpleCoil:` with a protocol version other than ours. */
        OTHER_VERSION,
        UNKNOWN,
    }

    companion object {
        /** Never throws: null, short or garbage frames are [Kind.UNKNOWN]. */
        @JvmStatic
        fun parse(raw: String?): Frame = when {
            raw == null -> Frame(Kind.UNKNOWN, "")
            raw.startsWith(WireProtocol.TCPMESSAGE_PREFIX) -> {
                val rest = raw.substring(WireProtocol.TCPMESSAGE_PREFIX.length)
                when {
                    rest.startsWith(WireProtocol.TCPPREFIX_JSON) -> Frame(Kind.JSON, rest.substring(WireProtocol.TCPPREFIX_JSON.length))
                    rest.startsWith(WireProtocol.TCPPREFIX_MESG) -> Frame(Kind.MESG, rest.substring(WireProtocol.TCPPREFIX_MESG.length))
                    else -> Frame(Kind.UNKNOWN, "")
                }
            }
            raw.startsWith(WireProtocol.MESSAGE_PREFIX) -> Frame(Kind.OTHER_VERSION, "")
            raw == WireProtocol.PING -> Frame(Kind.PING, "")
            raw == WireProtocol.PONG -> Frame(Kind.PONG, "")
            raw.startsWith(WireProtocol.NETMSG_VERSIONERROR) -> Frame(Kind.VERSION_ERROR, "")
            else -> Frame(Kind.UNKNOWN, "")
        }
    }
}
