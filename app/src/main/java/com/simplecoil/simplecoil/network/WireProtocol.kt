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

package com.simplecoil.simplecoil.network

/** The v07 wire strings: frame header pieces, the bare frames and MESG commands. Frozen (network design §1). */
object WireProtocol {
    const val MESSAGE_PREFIX = "SimpleCoil:"
    const val NETWORK_VERSION = "07"

    /** Every TCP frame but the bare ones starts with this, then [TCPPREFIX_MESG] or [TCPPREFIX_JSON]. */
    const val TCPMESSAGE_PREFIX = MESSAGE_PREFIX + NETWORK_VERSION
    const val TCPPREFIX_MESG = "MESG"
    const val TCPPREFIX_JSON = "JSON"

    /** Bare frames: the server's liveness ping and the client's answer. */
    const val PING = "ping"
    const val PONG = "pong"

    const val TCP_SERVER_PORT = 17510

    // All of these messages are straightforward and contain no extra data
    const val NETMSG_SHOTFIRED = "SHOTFIRED"
    const val NETMSG_HIT = "HIT"
    const val NETMSG_OUT = "OUT"
    const val NETMSG_ELIMINATED = "ELIMINATED"
    const val NETMSG_LEAVE = "LEAVE"
    const val NETMSG_STARTGAME = "STARTGAME"
    const val NETMSG_ENDGAME = "ENDGAME"
    const val NETMSG_ERROR = "ERROR"
    /** Sent bare, without the header: the client may not speak our version. */
    const val NETMSG_VERSIONERROR = "VERSIONERROR"
    const val NETMSG_SERVERCANCEL = "SERVERCANCEL"
    const val NETMSG_TEAMELIMINATED = "TEAMELIMINATED"
    const val NETMSG_SERVERREPLY = "SERVERREPLY"
    const val NETMSG_PLAYERDATAREQUEST = "PLAYERDATAREQUEST"
    const val NETMSG_ADMIN_STARTGAME = "ADMIN_STARTGAME"
    const val NETMSG_ADMIN_ENDGAME = "ADMIN_ENDGAME"
    const val NETMSG_ADMIN_GAMEMODE = "ADMIN_GAMEMODE"
    const val NETMSG_ADMIN_SORTTEAMS = "ADMIN_SORTTEAMS"
    const val NETMSG_ADMIN_KICK = "ADMIN_KICK"
    const val NETMSG_ADMIN_TEAMSIZES = "ADMIN_TEAMSIZES"
    const val NETMSG_ADMIN_RESET = "ADMIN_RESET"
    // Server -> client: ACK + seq acknowledges every reliable message up to seq
    const val NETMSG_ACK = "ACK"

    // UDP discovery: a beacon answers a query that starts with MESSAGE_PREFIX + NETMSG_JOIN
    const val NETMSG_JOIN = "JOIN"

    /** UDP discovery: beacons and queries go to this port. */
    const val DISCOVERY_PORT = 17500

    /** A server's beacon: this, then the server's name. */
    const val BEACON_PREFIX = MESSAGE_PREFIX + "BEACON:"

    /** A client's query; servers answer it with a beacon. */
    const val DISCOVER_MSG = MESSAGE_PREFIX + "DISCOVER"
}

/** The JSON keys of the v07 frames. Frozen (network design §1): a key's name, type and presence never change. */
object WireKeys {
    const val PLAYERS = "players"
    const val PLAYERNAME = "playername"
    const val PLAYERNAMECHANGE = "playernamechange"
    const val PLAYERID = "playerID"
    const val PLAYERIP = "playerIP"
    const val LIMITS = "limits"
    const val TIMELIMIT = "timelimit"
    const val LIVESLIMIT = "liveslimit"
    const val SCORELIMIT = "scorelimit"
    const val GAMEMODE = "gamemode"
    const val TEAMSIZES = "teamsizes"
    const val REJOIN = "rejoin"
    const val USEGPS = "usegps"
    const val TEAM = "team"
    const val GAMESTATE = "gamestate"
    const val SESSIONPHASE = "sessionphase"
    const val GAMEID = "gameid"
    const val CONNECTED = "connected"
    // Reliable client messages (§3.4): {"rmsg": "ELIMINATED7", "seq": 12, "gameid": 418873301}
    const val RMSG = "rmsg"
    const val SEQ = "seq"
    const val PENDING = "pending"
    const val ACKSEQ = "ackseq"
    const val ONLY_SERVER_SETTINGS = "onlyserversettings"
    const val ALLOW_CHOOSE_TEAMS = "allowchooseteams"
    const val REQUEST_TEAM_CHANGE = "requestteamchange"
    const val PAIRED_GRENADE_ID = "pairedgrenadeID"
    const val GRENADE_PAIRINGS = "grenadepairings"
    const val BLASTER_MAC = "blaster_mac"

    const val GPSLONGITUDE = "gpslongitude"
    const val GPSLATITUDE = "gpslatitude"
    const val GPSUPDATE = "gpsupdate"
    const val GPSFULLUPDATE = "gpsfullupdate"

    const val PLAYERDATA = "playerdata"
    const val PLAYERPOINTS = "points"
    const val PLAYERELIMINATED = "eliminated"
    const val TEAMPOINTS = "teampoints"
    const val TIMEREMAINING = "timeremaining"
    const val PLAYERGAMEUPDATE = "playergameupdate"

    const val PLAYERSETTINGS = "playersettings"
    const val HEALTH = "health"
    const val RELOAD_SHOTS = "reloadshots"
    const val RELOAD_TIME = "reloadtime"
    const val RELOAD_ON_EMPTY = "reloadonempty"
    const val MANUAL_RESPAWN = "manualrespawn"
    const val SPAWN_TIME = "spawntime"
    const val RESPAWN_SHIELD_TIME = "respawnshieldtime"
    const val START_TIME = "starttime"
    const val DAMAGE = "damage"
    const val SHOT_MODE_SINGLE = "shotmodesingle"
    const val SHOT_MODE_BURST3 = "shotmodeburst3"
    const val SHOT_MODE_AUTO = "shotmodeauto"
    const val FIRING_MODE = "firingmode"
    const val RECOIL_SETTING = "recoilsetting"
    const val IS_ADMIN = "isadmin"
    const val ALLOWPLAYERSETTINGS = "allowplayersettings"
}
