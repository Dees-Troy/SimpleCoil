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

package com.simplecoil.simplecoil.network;

import com.simplecoil.simplecoil.NetMsg;
import com.simplecoil.simplecoil.TcpClient;
import com.simplecoil.simplecoil.TcpServer;

/**
 * One received TCP frame, classified by its header. Shared by the server and the client so
 * neither has to cut the header with substring, which threw on short frames (N5).
 */
public final class Frame {
    public enum Kind {
        /** {@code SimpleCoil:07MESG<command>}; the body is the command. */
        MESG,
        /** {@code SimpleCoil:07JSON<json>}; the body is the JSON text. */
        JSON,
        PING,
        PONG,
        VERSION_ERROR,
        /** {@code SimpleCoil:} with a protocol version other than ours. */
        OTHER_VERSION,
        UNKNOWN
    }

    public final Kind kind;
    /** The command or the JSON text; "" for every other kind. */
    public final String body;

    private Frame(Kind kind, String body) {
        this.kind = kind;
        this.body = body;
    }

    /** Never throws: null, short or garbage frames are {@link Kind#UNKNOWN}. */
    public static Frame parse(String raw) {
        if (raw == null)
            return new Frame(Kind.UNKNOWN, "");
        if (raw.startsWith(TcpServer.TCPMESSAGE_PREFIX)) {
            String rest = raw.substring(TcpServer.TCPMESSAGE_PREFIX.length());
            if (rest.startsWith(TcpServer.TCPPREFIX_JSON))
                return new Frame(Kind.JSON, rest.substring(TcpServer.TCPPREFIX_JSON.length()));
            if (rest.startsWith(TcpServer.TCPPREFIX_MESG))
                return new Frame(Kind.MESG, rest.substring(TcpServer.TCPPREFIX_MESG.length()));
            return new Frame(Kind.UNKNOWN, "");
        }
        if (raw.startsWith(NetMsg.MESSAGE_PREFIX))
            return new Frame(Kind.OTHER_VERSION, "");
        if (raw.equals(TcpServer.TCP_SERVER_PING))
            return new Frame(Kind.PING, "");
        if (raw.equals(TcpClient.TCP_CLIENT_PONG))
            return new Frame(Kind.PONG, "");
        if (raw.startsWith(NetMsg.NETMSG_VERSIONERROR))
            return new Frame(Kind.VERSION_ERROR, "");
        return new Frame(Kind.UNKNOWN, "");
    }
}
