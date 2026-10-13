/*
 * Copyright (C) 2018 Ethan Yonker
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

package com.simplecoil.simplecoil;

// The v07 wire strings: frame header pieces and MESG commands. Frozen (network design §1).

public class NetMsg {
    public static final String MESSAGE_PREFIX = "SimpleCoil:";
    public static final String NETWORK_VERSION = "07";

    // All of these messages are straightforward and contain no extra data
    public static final String NETMSG_SHOTFIRED = "SHOTFIRED";
    public static final String NETMSG_HIT = "HIT";
    public static final String NETMSG_OUT = "OUT";
    public static final String NETMSG_ELIMINATED = "ELIMINATED";
    public static final String NETMSG_LEAVE = "LEAVE";
    public static final String NETMSG_STARTGAME = "STARTGAME";
    public static final String NETMSG_ENDGAME = "ENDGAME";
    public static final String NETMSG_ERROR = "ERROR";
    public static final String NETMSG_VERSIONERROR = "VERSIONERROR";
    public static final String NETMSG_SERVERCANCEL = "SERVERCANCEL";
    public static final String NETMSG_TEAMELIMINATED = "TEAMELIMINATED";
    public static final String NETMSG_SERVERREPLY = "SERVERREPLY";
    public static final String NETMSG_PLAYERDATAREQUEST = "PLAYERDATAREQUEST";
    public static final String NETMSG_ADMIN_STARTGAME = "ADMIN_STARTGAME";
    public static final String NETMSG_ADMIN_ENDGAME = "ADMIN_ENDGAME";
    public static final String NETMSG_ADMIN_GAMEMODE = "ADMIN_GAMEMODE";
    public static final String NETMSG_ADMIN_SORTTEAMS = "ADMIN_SORTTEAMS";
    public static final String NETMSG_ADMIN_KICK = "ADMIN_KICK";
    public static final String NETMSG_ADMIN_TEAMSIZES = "ADMIN_TEAMSIZES";
    public static final String NETMSG_ADMIN_RESET = "ADMIN_RESET";
    // Server -> client: ACK + seq acknowledges every reliable message up to seq
    public static final String NETMSG_ACK = "ACK";

    // UDP discovery: a beacon answers a query that starts with MESSAGE_PREFIX + NETMSG_JOIN
    public static final String NETMSG_JOIN = "JOIN";
}
