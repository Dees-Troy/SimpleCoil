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

// Network message globals

public class NetMsg {
    public static final String MESSAGE_PREFIX = "SimpleCoil:";
    public static final String NETWORK_VERSION = "06";

    // All of these messages are straightforward and contain no extra data
    public static final String NETMSG_SHOTFIRED = "SHOTFIRED";
    public static final String NETMSG_HIT = "HIT";
    public static final String NETMSG_OUT = "OUT";
    public static final String NETMSG_ELIMINATED = "ELIMINATED";
    public static final String NETMSG_LEAVE = "LEAVE";
    public static final String NETMSG_STARTGAME = "STARTGAME";
    public static final String NETMSG_ENDGAME = "ENDGAME";
    public static final String NETMSG_ERROR = "ERROR";
    public static final String NETMSG_FAILEDTOJOIN = "FAILEDTOJOIN";
    public static final String NETMSG_VERSIONERROR = "VERSIONERROR";
    public static final String NETMSG_SAMETEAM = "SAMETEAM";
    public static final String NETMSG_SERVERCREATED = "SERVERCREATED";
    public static final String NETMSG_SERVERCANCEL = "SERVERCANCEL";
    public static final String NETMSG_TEAMELIMINATED = "TEAMELIMINATED";
    public static final String NETMSG_SERVERREPLY = "SERVERREPLY";
    public static final String NETMSG_GPSLOCUPDATE = "GPSLOCUPDATE";
    public static final String NETMSG_GPSDATAUPDATE = "GPSDATAUPDATE";
    public static final String NETMSG_GPSSETTING = "GPSSETTING";
    public static final String NETMSG_PLAYERDATAUPDATE = "PLAYERDATAUPDATE";
    public static final String NETMSG_PLAYERDATAREQUEST = "PLAYERDATAREQUEST";
    public static final String NETMSG_NETWORKCONNECTED = "NETWORKCONNECTED";
    public static final String NETMSG_NETWORKDISCONNECTED = "NETWORKDISCONNECTED";
    public static final String NETMSG_PLAYERSETTINGSUPDATE = "PLAYERSETTINGSUPDATE";

    // When players join a game in progress, the server can send the player updates on appropriate values.
    // These items are intent extras.
    public static final String INTENT_HASGAMEUPDATE = "HASGAMEUPDATE";
    public static final String INTENT_SCORE = "SCORE";
    public static final String INTENT_TEAMSCORE = "TEAMSCORE";
    public static final String INTENT_ELIMINATIONS = "ELIMINATIONS";
    public static final String INTENT_TIMEREMAINING = "TIMEREMAINING";
    public static final String INTENT_GAMESTATE = "GAMESTATE";

    public static final String INTENT_LONGITUDE = "longitude";
    public static final String INTENT_LATITUDE = "latitude";
    public static final String INTENT_FULLUPDATE = "fullupdate";
    public static final String INTENT_PLAYERDATA = "playerdata";

    // UDPJOIN is UDPJOIN + playerID, so UDPJOIN2 for playerID 2
    public static final String NETMSG_JOIN = "JOIN";
    public static final String NETMSG_LISTPLAYERS = "LISTPLAYERS";
}
