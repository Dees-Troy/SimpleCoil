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

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.GameStateRepository;
import com.simplecoil.simplecoil.network.server.Connection;
import com.simplecoil.simplecoil.network.server.GameRoster;
import com.simplecoil.simplecoil.network.server.PlayerRecord;
import com.simplecoil.simplecoil.network.server.SessionPhase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TcpServer extends Service {
    private static final String TAG = "TCPServer";

    public static final int SEND_ALL = -100;

    public static final int TCP_SERVER_PORT = 17510;
    // Amount of time to sleep before checking if data is available. Lower is more responsive but may gobble up more CPU time
    public static final int TCP_DEDICATED_READ_WAIT_MS = 100;
    public static final String TCP_SERVER_PING = "ping";
    private static final String ADMIN_PREFIX = "ADMIN_";

    public static final String TCPMESSAGE_PREFIX = NetMsg.MESSAGE_PREFIX + NetMsg.NETWORK_VERSION;
    public static final String TCPPREFIX_MESG = "MESG";
    public static final String TCPPREFIX_JSON = "JSON";

    public static final String JSON_PLAYERS = "players";
    public static final String JSON_PLAYERNAME = "playername";
    public static final String JSON_PLAYERNAMECHANGE = "playernamechange";
    public static final String JSON_PLAYERID = "playerID";
    public static final String JSON_PLAYERIP = "playerIP";
    public static final String JSON_LIMITS = "limits";
    public static final String JSON_TIMELIMIT = "timelimit";
    public static final String JSON_LIVESLIMIT = "liveslimit";
    public static final String JSON_SCORELIMIT = "scorelimit";
    public static final String JSON_GAMEMODE = "gamemode";
    public static final String JSON_TEAMSIZES = "teamsizes";
    public static final String JSON_REJOIN = "rejoin";
    public static final String JSON_USEGPS = "usegps";
    public static final String JSON_TEAM = "team";
    public static final String JSON_GAMESTATE = "gamestate";
    public static final String JSON_SESSIONPHASE = "sessionphase";
    public static final String JSON_GAMEID = "gameid";
    public static final String JSON_CONNECTED = "connected";
    // Reliable client messages (§3.4): {"rmsg": "ELIMINATED7", "seq": 12, "gameid": 418873301}
    public static final String JSON_RMSG = "rmsg";
    public static final String JSON_SEQ = "seq";
    public static final String JSON_PENDING = "pending";
    public static final String JSON_ACKSEQ = "ackseq";
    public static final String JSON_ONLY_SERVER_SETTINGS = "onlyserversettings";
    public static final String JSON_ALLOW_CHOOSE_TEAMS = "allowchooseteams";
    public static final String JSON_REQUEST_TEAM_CHANGE = "requestteamchange";
    public static final String JSON_PAIRED_GRENADE_ID = "pairedgrenadeID";
    public static final String JSON_GRENADE_PAIRINGS = "grenadepairings";
    public static final String JSON_BLASTER_MAC = "blaster_mac";

    public static final String JSON_GPSLONGITUDE = "gpslongitude";
    public static final String JSON_GPSLATITUDE = "gpslatitude";
    public static final String JSON_GPSUPDATE = "gpsupdate";
    public static final String JSON_GPSFULLUPDATE = "gpsfullupdate";

    public static final String JSON_PLAYERDATA = "playerdata";
    public static final String JSON_PLAYERPOINTS = "points";
    public static final String JSON_PLAYERELIMINATED = "eliminated";
    public static final String JSON_TEAMPOINTS = "teampoints";
    public static final String JSON_TIMEREMAINING = "timeremaining";
    public static final String JSON_PLAYERGAMEUPDATE = "playergameupdate";

    public static final String JSON_PLAYERSETTINGS = "playersettings";
    public static final String JSON_HEALTH = "health";
    public static final String JSON_RELOAD_SHOTS = "reloadshots";
    public static final String JSON_RELOAD_TIME = "reloadtime";
    public static final String JSON_RELOAD_ON_EMPTY = "reloadonempty";
    public static final String JSON_SPAWN_TIME = "spawntime";
    public static final String JSON_RESPAWN_SHIELD_TIME = "respawnshieldtime";
    public static final String JSON_DAMAGE = "damage";
    public static final String JSON_SHOT_MODE_SINGLE = "shotmodesingle";
    public static final String JSON_SHOT_MODE_BURST3 = "shotmodeburst3";
    public static final String JSON_SHOT_MODE_AUTO = "shotmodeauto";
    public static final String JSON_FIRING_MODE = "firingmode";
    public static final String JSON_RECOIL_SETTING = "recoilsetting";
    public static final String JSON_IS_ADMIN = "isadmin";
    public static final String JSON_ALLOWPLAYERSETTINGS = "allowplayersettings";

    private static volatile boolean keepListening = false;
    private volatile boolean mAccepting = false;
    private final GameRoster mRoster = new GameRoster();
    private static volatile boolean mGPSRunning = false;

    Handler mGPSHandler = new Handler();
    Runnable mGPSRunnable = null;
    private static final long GPS_UPDATE_INTERVAL = 1000;
    private static final int SEND_ALL_GPS_INTERVAL = 20; // We will send all GPS data regardless of whether there was a GPS change every xx intervals
    private static final AtomicInteger mGPSIntervalCount = new AtomicInteger(0);

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void sendBroadcast(Intent intent) {
        if (intent != null && intent.getPackage() == null) {
            intent.setPackage(getPackageName());
        }
        super.sendBroadcast(intent);
    }

    public class LocalBinder extends Binder {
        TcpServer getService() {
            return TcpServer.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    public void sendTCPMessageAll(final String message) {
        if (mRoster.isEmpty())
            return;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                for (Connection connection : mRoster.connections()) {
                    connection.send(message);
                }
            }
        });
        sendThread.start();
    }

    /**
     * Sends to [record]'s connection. A disconnected player misses it: the snapshot it gets when
     * it comes back carries everything it needs (D11).
     */
    private void sendToPlayer(PlayerRecord record, String message) {
        Connection connection = record.getConnection();
        if (connection != null)
            connection.send(message);
    }

    private void sendTCPMessageID(final String message, final byte playerID) {
        if (mRoster.isEmpty())
            return;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                PlayerRecord record = mRoster.record(playerID);
                if (record != null)
                    sendToPlayer(record, message);
            }
        });
        sendThread.start();
    }

    private void sendTCPMessageTeam(final String message, final byte playerID, final boolean includePlayer) {
        if (mRoster.isEmpty())
            return;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                PlayerRecord player = mRoster.record(playerID);
                if (player == null)
                    return;
                int team = player.getTeam();
                Log.e(TAG, "team is " + team);
                if (includePlayer)
                    sendToPlayer(player, message);
                for (PlayerRecord record : mRoster.recordsOnTeam(team)) {
                    if (record != player)
                        sendToPlayer(record, message);
                }
            }
        });
        sendThread.start();
    }

    public void resetScores() {
        mRoster.resetScores();
    }

    /** Starts a game from the lobby. False if not in the lobby (after a game, reset first) or nobody is there. */
    public boolean startGame() {
        Log.d(TAG, "TcpServer.startGame called. Clients: " + mRoster.size());
        if (!mRoster.startGame())
            return false;
        syncGameState();

        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                // we want to make sure that we have sent the start game message to all clients before broadcasting that the game has started locally
                String message = TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_STARTGAME;
                for (Connection connection : mRoster.connections()) {
                    connection.send(message);
                }
                sendAllGameInfo(SEND_ALL);
                sendBroadcast(new Intent(NetMsg.NETMSG_STARTGAME));
                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
            }
        });
        sendThread.start();
        return true;
    }

    /**
     * Ends a running game (POST_GAME). Every player stays, connected or not, so the game's roster
     * can still come back until the reset. Does nothing if no game is running.
     */
    public void endGame() {
        Log.d(TAG, "TcpServer.endGame called. Clients: " + mRoster.size());
        if (!mRoster.endGame())
            return;
        syncGameState();
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                sendPlayerData(SEND_ALL);
                String message = TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ENDGAME;
                for (Connection connection : mRoster.connections()) {
                    connection.send(message);
                }
                Globals.getmGPSDataSemaphore();
                Globals.getInstance().mGPSData.clear();
                Globals.getInstance().mGPSDataSemaphore.release();
                sendBroadcast(new Intent(NetMsg.NETMSG_ENDGAME));
            }
        });
        sendThread.start();
    }

    /**
     * Reset (reconnect design §3.1), POST_GAME → LOBBY: removes the disconnected players, zeroes
     * the scores, forgets the game's roster, then tells the clients and broadcasts
     * NETMSG_SESSIONRESET locally. Returns false (and does nothing) outside POST_GAME, so a
     * duplicate or late reset is harmless. Safe on the main thread: the sending happens on a
     * worker thread.
     */
    public boolean resetSession() {
        List<PlayerRecord> removed;
        acquirePlayerMapSemaphores();
        try {
            removed = mRoster.reset();
        } finally {
            releasePlayerMapSemaphores();
        }
        if (removed == null)
            return false;
        Log.d(TAG, "Session reset: removed " + removed.size() + " disconnected players");
        syncGameState();
        mGPSIntervalCount.set(SEND_ALL_GPS_INTERVAL);
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                sendPlayerData(SEND_ALL);
                sendAllGameInfo(SEND_ALL);
                sendBroadcast(new Intent(NetMsg.NETMSG_SESSIONRESET));
                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
            }
        });
        sendThread.start();
        return true;
    }

    /**
     * Changing the game mode, team sizes, game limits or GPS mode, or sorting teams, after a game
     * resets first (§3.1.1). Call it before applying the change; true if it reset.
     */
    public boolean resetIfPostGame() {
        return resetSession();
    }

    public SessionPhase getSessionPhase() {
        return mRoster.phase();
    }

    /** Globals and GameStateRepository follow the session: running only in RUNNING (D8). */
    private void syncGameState() {
        int state = mRoster.isRunning() ? Globals.GAME_STATE_RUNNING : Globals.GAME_STATE_NONE;
        Globals.getInstance().mGameState = state;
        GameStateRepository.getInstance().setGameState(state);
    }

    /** The Globals semaphores over the player maps, in the order every caller takes them (before the roster's lock). */
    private static void acquirePlayerMapSemaphores() {
        Globals.getmTeamIPMapSemaphore();
        Globals.getmIPTeamMapSemaphore();
        Globals.getmTeamPlayerNameSemaphore();
        Globals.getmPlayerSettingsSemaphore();
        Globals.getmGPSDataSemaphore();
    }

    private static void releasePlayerMapSemaphores() {
        Globals.getInstance().mGPSDataSemaphore.release();
        Globals.getInstance().mPlayerSettingsSemaphore.release();
        Globals.getInstance().mTeamPlayerNameSemaphore.release();
        Globals.getInstance().mIPTeamMapSemaphore.release();
        Globals.getInstance().mTeamIPMapSemaphore.release();
    }

    public void switchPlayerTeam(final byte oldPlayerID, final byte newPlayerID) {
        Log.d(TAG, "switchPlayerTeam: moving player " + oldPlayerID + " to new ID " + newPlayerID);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                PlayerRecord record;
                acquirePlayerMapSemaphores();
                try {
                    record = mRoster.movePlayer(oldPlayerID, newPlayerID);
                } finally {
                    releasePlayerMapSemaphores();
                }
                if (record != null && record.getConnection() != null)
                    record.getConnection().send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERREPLY + newPlayerID);
                sendPlayerData(SEND_ALL);
                sendAllGameInfo(SEND_ALL);
                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
            }
        });
        thread.start();
    }

    public void kickPlayer(final byte playerID) {
        Log.d(TAG, "kickPlayer: kicking player " + playerID);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Connection connection;
                acquirePlayerMapSemaphores();
                try {
                    connection = mRoster.kick(playerID);
                } finally {
                    releasePlayerMapSemaphores();
                }
                if (connection != null) {
                    connection.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERCANCEL);
                    connection.close();
                }
                mGPSIntervalCount.set(SEND_ALL_GPS_INTERVAL);
                sendPlayerData(SEND_ALL);
                sendAllGameInfo(SEND_ALL);
                sendBroadcast(new Intent(NetMsg.NETMSG_LEAVE));
                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
            }
        });
        thread.start();
    }

    public void rebalanceAllPlayers(final boolean randomize) {
        if (mRoster.isRunning()) {
            Log.w(TAG, "rebalanceAllPlayers: Game is running, skipping team rebalance!");
            return;
        }
        resetIfPostGame(); // the roster renumbers only in the lobby
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                GameRoster.Rebalance result;
                acquirePlayerMapSemaphores();
                try {
                    result = mRoster.rebalance(randomize);
                } finally {
                    releasePlayerMapSemaphores();
                }
                for (Connection connection : result.dropped) {
                    Log.w(TAG, "Server full during rebalance! Closing client " + connection.getConnectionId());
                    connection.close();
                }
                for (PlayerRecord record : result.assigned) {
                    Connection connection = record.getConnection();
                    if (connection != null)
                        connection.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERREPLY + record.getPlayerId());
                }
                sendPlayerData(SEND_ALL);
                sendAllGameInfo(SEND_ALL);
                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
            }
        });
        thread.start();
    }

    public void sendAllGameInfo(final int id) {
        if (mRoster.isEmpty() || Globals.getInstance().mTeamIPMap == null || Globals.getInstance().mTeamIPMap.size() == 0)
            return;
        try {
            JSONArray players = new JSONArray();
            for (Map.Entry<Byte, InetAddress> entry : Globals.getInstance().mTeamIPMap.entrySet()) {
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERNAME, Globals.getInstance().getPlayerName(entry.getKey()));
                player.put(JSON_PLAYERID, entry.getKey());
                player.put(JSON_PLAYERIP, entry.getValue());
                players.put(player);
            }
            JSONObject game = new JSONObject();
            game.put(JSON_PLAYERS, players);
            JSONObject limits = new JSONObject();
            if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0) {
                limits.put(JSON_TIMELIMIT, Globals.getInstance().mTimeLimit);
            }
            if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_LIVES) != 0) {
                limits.put(JSON_LIVESLIMIT, Globals.getInstance().mLivesLimit);
            }
            if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_SCORE) != 0) {
                limits.put(JSON_SCORELIMIT, Globals.getInstance().mScoreLimit);
            }
            game.put(JSON_LIMITS, limits);
            game.put(JSON_GAMEMODE, Globals.getInstance().mGameMode);
            if (Globals.getInstance().getTeamSizes() != null) {
                JSONArray teamSizesArr = new JSONArray();
                for (int size : Globals.getInstance().getTeamSizes()) {
                    teamSizesArr.put(size);
                }
                game.put(JSON_TEAMSIZES, teamSizesArr);
            }
            game.put(JSON_GAMESTATE, Globals.getInstance().mGameState);
            if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE && (Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0)
                game.put(JSON_TIMEREMAINING, Globals.getInstance().mServerGameTimeRemaining);
            SessionPhase phase = mRoster.phase();
            game.put(JSON_SESSIONPHASE, phase.wireValue);
            if (phase != SessionPhase.LOBBY)
                game.put(JSON_GAMEID, mRoster.gameId());
            if (Globals.getInstance().mUseGPS)
                game.put(JSON_USEGPS, Globals.getInstance().mGPSMode);
            game.put(JSON_ALLOWPLAYERSETTINGS, Globals.getInstance().mAllowPlayerSettings);
            game.put(JSON_PLAYERSETTINGS, getPlayerSettings(id, false));
            game.put(JSON_ONLY_SERVER_SETTINGS, Globals.getInstance().mOnlyServerSettings);
            game.put(JSON_ALLOW_CHOOSE_TEAMS, GameSettingsRepository.getInstance().isAllowChooseTeams());
            final String allMessage = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
            if (id == SEND_ALL)
                sendTCPMessageAll(allMessage);
            else {
                // Get update data for this specific player
                ScoreData scoreData = getScore((byte)id);
                JSONObject playerGameUpdate = new JSONObject();
                if (scoreData != null) {
                    playerGameUpdate.put(JSON_PLAYERPOINTS, scoreData.points);
                    playerGameUpdate.put(JSON_PLAYERELIMINATED, scoreData.eliminated);
                } else {
                    playerGameUpdate.put(JSON_PLAYERPOINTS, 0);
                    playerGameUpdate.put(JSON_PLAYERELIMINATED, 0);
                }
                if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                    int team = Globals.getInstance().calcNetworkTeam((byte)id);
                    playerGameUpdate.put(JSON_TEAMPOINTS, mRoster.teamPoints(team));
                }
                if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0 && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
                    playerGameUpdate.put(JSON_TIMEREMAINING, Globals.getInstance().mServerGameTimeRemaining);
                }
                PlayerRecord record = mRoster.record((byte) id);
                playerGameUpdate.put(JSON_ACKSEQ, record != null ? record.getLastAppliedSeq() : 0);
                game.put(JSON_PLAYERGAMEUPDATE, playerGameUpdate);
                final String idMessage = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();

                Thread sendThread = new Thread(new Runnable() {
                    public void run() {
                        for (Connection connection : mRoster.connections()) {
                            PlayerRecord player = connection.getPlayer();
                            if (player != null && player.getPlayerId() == id)
                                connection.send(idMessage);
                            else
                                connection.send(allMessage);
                        }
                    }
                });
                sendThread.start();
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void sendGPSData() {
        if (mGPSRunning) return;
        mGPSRunning = true;
        mGPSIntervalCount.set(0);
        mGPSHandler.postDelayed(new Runnable() {
            public void run() {
                mGPSRunnable = this;
                String messageToSend = null;
                boolean hasSemaphore = false;
                try {
                    Globals.getmGPSDataSemaphore();
                    hasSemaphore = true;
                    if (Globals.getInstance().mGPSData != null && Globals.getInstance().mGPSData.size() != 0) {
                        JSONArray players = new JSONArray();
                        boolean hasUpdate = false;
                        int currentInterval = mGPSIntervalCount.get();
                        for (Map.Entry<Byte, Globals.GPSData> entry : Globals.getInstance().mGPSData.entrySet()) {
                            if (entry.getValue().hasUpdate || currentInterval >= SEND_ALL_GPS_INTERVAL) {
                                JSONObject player = new JSONObject();
                                player.put(JSON_PLAYERID, entry.getKey());
                                player.put(JSON_TEAM, entry.getValue().team);
                                player.put(JSON_GPSLONGITUDE, entry.getValue().longitude);
                                player.put(JSON_GPSLATITUDE, entry.getValue().latitude);
                                players.put(player);
                                hasUpdate = true;
                            }
                            entry.getValue().hasUpdate = false;
                        }
                        Globals.getInstance().mGPSDataSemaphore.release();
                        hasSemaphore = false;
                        if (hasUpdate) {
                            JSONObject game = new JSONObject();
                            game.put(JSON_GPSUPDATE, players);
                            if (currentInterval >= SEND_ALL_GPS_INTERVAL) {
                                game.put(JSON_GPSFULLUPDATE, true);
                            }
                            messageToSend = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
                        }
                        if (mGPSIntervalCount.incrementAndGet() >= SEND_ALL_GPS_INTERVAL)
                            mGPSIntervalCount.set(0);
                    } else {
                        Globals.getInstance().mGPSDataSemaphore.release();
                        hasSemaphore = false;
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                    if (hasSemaphore)
                        Globals.getInstance().mGPSDataSemaphore.release();
                }

                if (messageToSend != null) {
                    sendTCPMessageAll(messageToSend);
                }

                if (keepListening && Globals.getInstance().mUseGPS) {
                    mGPSHandler.postDelayed(mGPSRunnable, GPS_UPDATE_INTERVAL);
                } else {
                    mGPSRunning = false;
                }
            }
        }, GPS_UPDATE_INTERVAL);
    }

    public void sendPlayerData(int playerID) {
        if (mRoster.isEmpty() || Globals.getInstance().mTeamIPMap == null || Globals.getInstance().mTeamIPMap.size() == 0)
            return;
        try {
            JSONArray players = new JSONArray();
            for (Map.Entry<Byte, InetAddress> entry : Globals.getInstance().mTeamIPMap.entrySet()) {
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERNAME, Globals.getInstance().getPlayerName(entry.getKey()));
                player.put(JSON_PLAYERID, entry.getKey());
                player.put(JSON_PLAYERIP, entry.getValue());
                ScoreData scoreData = getScore(entry.getKey());
                if (scoreData != null) {
                    player.put(JSON_PLAYERPOINTS, scoreData.points);
                    player.put(JSON_PLAYERELIMINATED, scoreData.eliminated);
                    player.put(JSON_CONNECTED, scoreData.isConnected);
                } else {
                    player.put(JSON_PLAYERPOINTS, 0);
                    player.put(JSON_PLAYERELIMINATED, 0);
                }
                players.put(player);
            }
            JSONObject game = new JSONObject();
            game.put(JSON_PLAYERDATA, players);
            String message = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
            if (playerID != SEND_ALL)
                sendTCPMessageID(message, (byte)playerID);
            else {
                for (Connection connection : mRoster.connections()) {
                    connection.send(message);
                }
            }
            Intent broadcastIntent = new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE);
            broadcastIntent.putExtra(NetMsg.INTENT_PLAYERDATA, game.toString());
            sendBroadcast(broadcastIntent);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public JSONArray getPlayerSettings(int playerID, boolean applyAll) {
        return getPlayerSettings(playerID, applyAll, false);
    }

    public JSONArray getPlayerSettings(int playerID, boolean applyAll, boolean applyTeam) {
        boolean hasSemaphore = false;
        try {
            JSONArray players = new JSONArray();
            Globals.getmPlayerSettingsSemaphore();
            hasSemaphore = true;
            if (applyAll && playerID != SEND_ALL) {
                Globals.PlayerSettings src = Globals.getInstance().mPlayerSettings.get((byte) playerID);
                if (src != null) {
                    for (Byte x = 1; x <= Globals.MAX_PLAYER_ID; x++) {
                        if (x != playerID) {
                            Globals.PlayerSettings playerSettings = Globals.getInstance().mPlayerSettings.get(x);
                            if (playerSettings == null) {
                                playerSettings = new Globals.PlayerSettings();
                                Globals.getInstance().mPlayerSettings.put(x, playerSettings);
                            }
                            playerSettings.health = src.health;
                            playerSettings.shots = src.shots;
                            playerSettings.reloadTime = src.reloadTime;
                            playerSettings.reloadOnEmpty = src.reloadOnEmpty;
                            playerSettings.spawnTime = src.spawnTime;
                            playerSettings.respawnShieldTime = src.respawnShieldTime;
                            playerSettings.damage = src.damage;
                            playerSettings.overrideLives = src.overrideLives;
                            playerSettings.lives = src.lives;
                            playerSettings.allowShotModeSingle = src.allowShotModeSingle;
                            playerSettings.allowShotModeBurst3 = src.allowShotModeBurst3;
                            playerSettings.allowShotModeAuto = src.allowShotModeAuto;
                            playerSettings.recoilSetting = src.recoilSetting;
                            playerSettings.firingMode = src.firingMode;
                        }
                    }
                }
            } else if (applyTeam && playerID != SEND_ALL && Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                Globals.PlayerSettings src = Globals.getInstance().mPlayerSettings.get((byte) playerID);
                if (src != null) {
                    int sourceTeam = Globals.getInstance().calcNetworkTeam((byte) playerID);
                    for (Byte x = 1; x <= Globals.MAX_PLAYER_ID; x++) {
                        if (x != playerID && Globals.getInstance().calcNetworkTeam(x) == sourceTeam) {
                            Globals.PlayerSettings playerSettings = Globals.getInstance().mPlayerSettings.get(x);
                            if (playerSettings == null) {
                                playerSettings = new Globals.PlayerSettings();
                                Globals.getInstance().mPlayerSettings.put(x, playerSettings);
                            }
                            playerSettings.health = src.health;
                            playerSettings.shots = src.shots;
                            playerSettings.reloadTime = src.reloadTime;
                            playerSettings.reloadOnEmpty = src.reloadOnEmpty;
                            playerSettings.spawnTime = src.spawnTime;
                            playerSettings.respawnShieldTime = src.respawnShieldTime;
                            playerSettings.damage = src.damage;
                            playerSettings.overrideLives = src.overrideLives;
                            playerSettings.lives = src.lives;
                            playerSettings.allowShotModeSingle = src.allowShotModeSingle;
                            playerSettings.allowShotModeBurst3 = src.allowShotModeBurst3;
                            playerSettings.allowShotModeAuto = src.allowShotModeAuto;
                            playerSettings.recoilSetting = src.recoilSetting;
                            playerSettings.firingMode = src.firingMode;
                        }
                    }
                }
            }
            if (mRoster.isEmpty() || Globals.getInstance().mTeamIPMap == null || Globals.getInstance().mTeamIPMap.size() == 0) {
                Globals.getInstance().mPlayerSettingsSemaphore.release();
                hasSemaphore = false;
                return null;
            }
            for (Map.Entry<Byte, Globals.PlayerSettings> entry : Globals.getInstance().mPlayerSettings.entrySet()) {
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERID, entry.getKey());
                player.put(JSON_HEALTH, entry.getValue().health);
                player.put(JSON_RELOAD_SHOTS, entry.getValue().shots);
                player.put(JSON_RELOAD_TIME, entry.getValue().reloadTime);
                player.put(JSON_RELOAD_ON_EMPTY, entry.getValue().reloadOnEmpty);
                player.put(JSON_SPAWN_TIME, entry.getValue().spawnTime);
                player.put(JSON_RESPAWN_SHIELD_TIME, entry.getValue().respawnShieldTime);
                player.put(JSON_DAMAGE, entry.getValue().damage);
                if (entry.getValue().overrideLives)
                    player.put(JSON_LIVESLIMIT, entry.getValue().lives);
                player.put(JSON_SHOT_MODE_SINGLE, entry.getValue().allowShotModeSingle);
                player.put(JSON_SHOT_MODE_BURST3, entry.getValue().allowShotModeBurst3);
                player.put(JSON_SHOT_MODE_AUTO, entry.getValue().allowShotModeAuto);
                player.put(JSON_FIRING_MODE, entry.getValue().firingMode);
                player.put(JSON_RECOIL_SETTING, entry.getValue().recoilSetting);
                player.put(JSON_IS_ADMIN, entry.getValue().isAdmin);
                players.put(player);
            }
            Globals.getInstance().mPlayerSettingsSemaphore.release();
            hasSemaphore = false;
            return players;
        } catch (JSONException e) {
            e.printStackTrace();
            if (hasSemaphore)
                Globals.getInstance().mPlayerSettingsSemaphore.release();
        }
        return null;
    }

    public void sendPlayerSettings(int playerID, boolean applyAll, boolean allowPlayerSettings) {
        sendPlayerSettings(playerID, applyAll, false, allowPlayerSettings);
    }

    public void sendPlayerSettings(int playerID, boolean applyAll, boolean applyTeam, boolean allowPlayerSettings) {
        JSONArray players = getPlayerSettings(playerID, applyAll, applyTeam);
        if (players == null)
            return;
        try {
            JSONObject game = new JSONObject();
            game.put(JSON_ALLOWPLAYERSETTINGS, allowPlayerSettings);
            Globals.getInstance().mAllowPlayerSettings = allowPlayerSettings;
            game.put(JSON_PLAYERSETTINGS, players);
            String message = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
            sendTCPMessageAll(message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    void startTcpServer() {
        if (keepListening) {
            Log.d(TAG, "Server already listening");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                runTcpServer();
            }
        }).start();
    }

    private void runTcpServer() {
        mRoster.clear();
        syncGameState();
        if (Globals.getInstance().mGPSData == null)
            Globals.getInstance().mGPSData = new ConcurrentHashMap<>();
        else
            Globals.getInstance().mGPSData.clear();
        Globals.ClearGrenadePairings(true);
        keepListening = true;
        ServerSocket ss = null;
        try {
            ss = new ServerSocket(TCP_SERVER_PORT);
            ss.setSoTimeout(1000);
            mAccepting = true;
            Socket s = null;
            Log.d(TAG, "TCP Server listening");
            while (keepListening) {
                try {
                    // Wait for new clients
                    s = ss.accept();
                    Connection connection = mRoster.accept(s);
                    if (connection.getConnectionId() <= 1) {
                        new Thread(new ClientThread()).start();
                    }
                } catch (SocketTimeoutException e) {
                    // do nothing
                }
            }
            if (s != null)
                s.close();
        } catch (InterruptedIOException e) {
            //if timeout occurs
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            mAccepting = false;
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        Log.d(TAG, "TCP Server done");
        keepListening = false;
    }

    public void checkLivesLimitEndGame() {
        if (Globals.getInstance().mGameState == Globals.GAME_STATE_NONE) {
            return;
        }

        boolean hasGlobalLifeLimit = (Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_LIVES) != 0
                && Globals.getInstance().mLivesLimit > 0;

        boolean hasOverrideLifeLimit = false;
        Map<Byte, Globals.PlayerSettings> playerSettingsMap = Globals.getInstance().mPlayerSettings;
        if (playerSettingsMap != null) {
            for (Globals.PlayerSettings settings : playerSettingsMap.values()) {
                if (settings != null && settings.overrideLives && settings.lives > 0) {
                    hasOverrideLifeLimit = true;
                    break;
                }
            }
        }

        if (!hasGlobalLifeLimit && !hasOverrideLifeLimit) {
            return;
        }

        Set<Integer> livingTeams = new HashSet<>();

        for (PlayerRecord record : mRoster.records()) {
            byte playerId = record.getPlayerId();
            int totalLives = 0;
            Globals.PlayerSettings settings = (playerSettingsMap != null) ? playerSettingsMap.get(playerId) : null;
            if (settings != null && settings.overrideLives && settings.lives > 0) {
                totalLives = settings.lives;
            } else if (hasGlobalLifeLimit) {
                totalLives = Globals.getInstance().mLivesLimit;
            }

            if (totalLives > 0) {
                int remainingLives = totalLives - record.getEliminated();
                if (remainingLives > 0) {
                    int team = Globals.getInstance().calcNetworkTeam(playerId);
                    livingTeams.add(team);
                }
            } else {
                int team = Globals.getInstance().calcNetworkTeam(playerId);
                livingTeams.add(team);
            }
        }

        if (livingTeams.size() <= 1) {
            Log.d(TAG, "checkLivesLimitEndGame: ending game, living teams remaining: " + livingTeams.size());
            endGame();
        }
    }

    public ScoreData getScore(byte playerID) {
        GameRoster.Score score = mRoster.score(playerID);
        if (score == null)
            return null;
        ScoreData scoreData = new ScoreData();
        scoreData.points = score.points;
        scoreData.eliminated = score.eliminated;
        scoreData.isConnected = score.connected;
        return scoreData;
    }

    public class ScoreData {
        int points = 0;
        int eliminated = 0;
        boolean isConnected = false;
    }

    private class ClientThread implements Runnable {
        public void run() {
            Log.i(TAG, "Running TCP listening server for clients");
            if (Globals.getInstance().mUseGPS) {
                sendGPSData();
            }
            //receive a message
            while (keepListening) {
                List<Connection> clients = mRoster.connections();
                long startTime = System.currentTimeMillis();
                for (Connection client : clients) {
                    try {
                        if (client.available() > 0) {
                            client.resetNoReadCount();
                            String message = client.readUTF();
                            if (!message.equals(TcpClient.TCP_CLIENT_PONG)) {
                                Log.i(TAG, "received: '" + message + "' from " + client.getConnectionId());
                                if (message.startsWith(TCPMESSAGE_PREFIX)) {
                                    if (message.substring(TCPMESSAGE_PREFIX.length(), TCPMESSAGE_PREFIX.length() + TCPPREFIX_JSON.length()).equals(TCPPREFIX_JSON)) {
                                        // Handle JSON Data
                                        message = message.substring(TCPMESSAGE_PREFIX.length() + TCPPREFIX_JSON.length());
                                        parsePlayerInfo(message, client);
                                        break;
                                    } else if (message.substring(TCPMESSAGE_PREFIX.length(), TCPMESSAGE_PREFIX.length() + TCPPREFIX_MESG.length()).equals(TCPPREFIX_MESG)) {
                                        // Handle messages
                                        message = message.substring(TCPMESSAGE_PREFIX.length() + TCPPREFIX_MESG.length());
                                        if (message.equals(NetMsg.NETMSG_LEAVE)) {
                                            // A player in the game's roster stays on the scoreboard, disconnected (Q12)
                                            removeClient(client);
                                            if (mRoster.isRunning() && mRoster.connectedCount() <= 1)
                                                endGame();
                                            break;
                                        } else if (message.startsWith(NetMsg.NETMSG_ELIMINATED)) {
                                            // Sent reliably since v07; a bare one is applied the same way, without de-dup
                                            handleEliminated(client, message);
                                        } else if (message.equals(NetMsg.NETMSG_SHOTFIRED)) {
                                            sendTCPMessageAll(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SHOTFIRED);
                                        } else if (message.startsWith(NetMsg.NETMSG_HIT)) {
                                            message = message.substring(NetMsg.NETMSG_HIT.length());
                                            try {
                                                byte shooterId = (byte) Integer.parseInt(message);
                                                byte targetId = playerIdOf(client);
                                                sendTCPMessageID(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_HIT + targetId, shooterId);
                                            } catch (Exception e) {
                                                e.printStackTrace();
                                            }
                                        } else if (message.startsWith(NetMsg.NETMSG_OUT)) {
                                            message = message.substring(NetMsg.NETMSG_OUT.length());
                                            try {
                                                byte shooterId = (byte) Integer.parseInt(message);
                                                byte targetId = playerIdOf(client);
                                                sendTCPMessageID(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_OUT + targetId, shooterId);
                                            } catch (Exception e) {
                                                e.printStackTrace();
                                            }
                                        } else if (message.equals(NetMsg.NETMSG_TEAMELIMINATED)) {
                                            sendTCPMessageTeam(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_TEAMELIMINATED, playerIdOf(client), false);
                                        } else if (message.equals(NetMsg.NETMSG_PLAYERDATAREQUEST)) {
                                            sendPlayerData(playerIdOf(client));
                                        } else if (isAdminCommand(message) && !isAdmin(client)) {
                                            // D14: only players the server operator made admins may run the game
                                            Log.w(TAG, "Ignoring '" + message + "' from non-admin client " + client.getConnectionId());
                                        } else if (message.equals(NetMsg.NETMSG_STARTGAME) || message.startsWith(NetMsg.NETMSG_ADMIN_STARTGAME)) {
                                            Log.d(TAG, "TcpServer received STARTGAME / ADMIN_STARTGAME command: '" + message + "'");
                                            if (!startGame() && mRoster.phase() == SessionPhase.POST_GAME)
                                                client.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ERROR + "Reset the game first");
                                        } else if (message.equals(NetMsg.NETMSG_ENDGAME) || message.startsWith(NetMsg.NETMSG_ADMIN_ENDGAME)) {
                                            Log.d(TAG, "TcpServer received ENDGAME / ADMIN_ENDGAME command: '" + message + "'");
                                            if (message.startsWith(NetMsg.NETMSG_ADMIN_ENDGAME)) {
                                                endGame();
                                            } else {
                                                // Regular ENDGAME from a client is treated as the client leaving
                                                removeClient(client);
                                                if (mRoster.isRunning() && mRoster.connectedCount() <= 1)
                                                    endGame();
                                                break;
                                            }
                                        } else if (message.startsWith(NetMsg.NETMSG_ADMIN_RESET)) {
                                            Log.d(TAG, "TcpServer received ADMIN_RESET command: '" + message + "'");
                                            resetSession();
                                        } else if (message.startsWith(NetMsg.NETMSG_ADMIN_SORTTEAMS)) {
                                            Log.d(TAG, "TcpServer received ADMIN_SORTTEAMS command: '" + message + "'");
                                            if (!mRoster.isRunning()) {
                                                resetIfPostGame();
                                                rebalanceAllPlayers(true);
                                            }
                                        } else if (message.startsWith(NetMsg.NETMSG_ADMIN_GAMEMODE)) {
                                            Log.d(TAG, "TcpServer received ADMIN_GAMEMODE command: '" + message + "'");
                                            try {
                                                int mode = Integer.parseInt(message.substring(NetMsg.NETMSG_ADMIN_GAMEMODE.length()).trim());
                                                if (!mRoster.isRunning()) {
                                                    resetIfPostGame();
                                                    Globals.getInstance().mGameMode = mode;
                                                    rebalanceAllPlayers(false);
                                                }
                                            } catch (Exception e) {
                                                e.printStackTrace();
                                            }
                                        } else if (message.startsWith(NetMsg.NETMSG_ADMIN_KICK)) {
                                            Log.d(TAG, "TcpServer received ADMIN_KICK command: '" + message + "'");
                                            try {
                                                byte targetID = (byte) Integer.parseInt(message.substring(NetMsg.NETMSG_ADMIN_KICK.length()).trim());
                                                kickPlayer(targetID);
                                            } catch (Exception e) {
                                                e.printStackTrace();
                                            }
                                        } else if (message.startsWith(NetMsg.NETMSG_ADMIN_TEAMSIZES)) {
                                            Log.d(TAG, "TcpServer received ADMIN_TEAMSIZES command: '" + message + "'");
                                            if (!mRoster.isRunning()) {
                                                try {
                                                    String sizesStr = message.substring(NetMsg.NETMSG_ADMIN_TEAMSIZES.length()).trim();
                                                    String[] parts = sizesStr.split(",");
                                                    int[] newSizes = new int[parts.length];
                                                    int sum = 0;
                                                    for (int i = 0; i < parts.length; i++) {
                                                        newSizes[i] = Integer.parseInt(parts[i].trim());
                                                        sum += newSizes[i];
                                                    }
                                                    if (sum == Globals.MAX_PLAYER_ID) {
                                                        resetIfPostGame();
                                                        Globals.getInstance().setTeamSizes(newSizes);
                                                        rebalanceAllPlayers(false);
                                                    }
                                                } catch (Exception e) {
                                                    e.printStackTrace();
                                                }
                                            }
                                        }
                                    } else {
                                        Log.d(TAG, "unknown tcp message received");
                                    }
                                } else if (message.startsWith(NetMsg.MESSAGE_PREFIX)) {
                                    client.send(NetMsg.NETMSG_VERSIONERROR);
                                    break;
                                }
                            }
                        } else {
                            int count = client.incrementNoReadCount();
                            if (client.isOpen() && count >= 35) {
                                Log.d(TAG, "no reply so dropping client " + client.getConnectionId());
                                removeClient(client);
                                break;
                            } else if (count == 10 || count == 20) {
                                client.send(TCP_SERVER_PING);
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        removeClient(client);
                        break;
                    }
                }
                long sleepTime = TCP_DEDICATED_READ_WAIT_MS - (System.currentTimeMillis() - startTime);
                sleep(sleepTime);
            }
            for (Connection connection : mRoster.connections()) {
                connection.close();
            }
        }

        private void sendGrenadePairings(boolean getSemaphore) {
            boolean has_data = false;
            if (getSemaphore)
                Globals.getmGrenadePairingsSemaphore();
            try {
                JSONArray grenadePairings = new JSONArray();
                for (int index = 1; index < Globals.MAX_GRENADE_IDS; index++) {
                    if (Globals.getInstance().mGrenadePairings[index] != Globals.INVALID_PLAYER_ID) {
                        has_data = true;
                        JSONObject grenadePairing = new JSONObject();
                        grenadePairing.put(JSON_PLAYERID, Globals.getInstance().mGrenadePairings[index]);
                        grenadePairing.put(JSON_PAIRED_GRENADE_ID, index);
                        grenadePairings.put(grenadePairing);
                    }
                }
                if (getSemaphore)
                    Globals.getInstance().mGrenadePairingsSemaphore.release();
                getSemaphore = false; // don't release the semaphore in the exception clause if we fail after this point
                if (has_data) {
                    JSONObject data = new JSONObject();
                    data.put(JSON_GRENADE_PAIRINGS, grenadePairings);
                    String message = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + data.toString();
                    sendTCPMessageAll(message);
                }
            } catch (JSONException e) {
                if (getSemaphore)
                    Globals.getInstance().mGrenadePairingsSemaphore.release();
                e.printStackTrace();
            }
        }

        /** Messages that run the game: every ADMIN_* message, and a plain STARTGAME. */
        private boolean isAdminCommand(String message) {
            return message.equals(NetMsg.NETMSG_STARTGAME) || message.startsWith(ADMIN_PREFIX);
        }

        /** The server operator gave this player admin rights in its player settings. */
        private boolean isAdmin(Connection client) {
            PlayerRecord player = client.getPlayer();
            if (player == null)
                return false;
            Globals.PlayerSettings settings = Globals.getInstance().mPlayerSettings.get(player.getPlayerId());
            return settings != null && settings.isAdmin;
        }

        private byte playerIdOf(Connection client) {
            PlayerRecord player = client.getPlayer();
            return player != null ? player.getPlayerId() : 0;
        }

        /**
         * [message] ("ELIMINATED" + shooter id): the player behind [client] was eliminated. During
         * the game: the shooter and its team are told and the limits checked. After the game it
         * only counts in the final scores.
         */
        private void handleEliminated(Connection client, String message) {
            byte id;
            try {
                id = (byte) Integer.parseInt(message.substring(NetMsg.NETMSG_ELIMINATED.length()));
            } catch (NumberFormatException e) {
                e.printStackTrace();
                return;
            }
            GameRoster.Elimination elimination = mRoster.onEliminated(client, id);
            if (elimination == null) {
                Log.d(TAG, "elimination ignored outside a game");
                return;
            }
            if (!mRoster.isRunning()) {
                sendPlayerData(SEND_ALL);
                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                return;
            }
            int shooterScore = elimination.shooterPoints;
            sendTCPMessageID(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ELIMINATED + elimination.victimId, id);
            if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                // Send a message to all teammates about the score increase
                sendTCPMessageTeam(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_TEAMELIMINATED, id, false);
            }
            sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
            int scoreLimit = ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_SCORE) != 0 ? Globals.getInstance().mScoreLimit : 0);
            if (scoreLimit != 0) {
                if (Globals.getInstance().mGameMode == Globals.GAME_MODE_FFA) {
                    if (shooterScore >= scoreLimit)
                        endGame();
                } else {
                    int team = Globals.getInstance().calcNetworkTeam(id);
                    if (mRoster.teamPoints(team) >= scoreLimit)
                        endGame();
                }
            }
            checkLivesLimitEndGame();
        }

        /**
         * A reliable message [rmsg] (§3.4): applied once, through the same handler as a live
         * one. Returns the seq to acknowledge, or -1 to leave it unacknowledged.
         */
        private int handleReliable(Connection client, String rmsg, int seq, long gameId) {
            GameRoster.ReliableCheck check = mRoster.checkReliable(client, seq, gameId);
            switch (check) {
                case NO_PLAYER:
                    Log.w(TAG, "reliable message before hello from client " + client.getConnectionId() + ": " + rmsg);
                    return -1;
                case STALE:
                    Log.d(TAG, "dropping reliable message from another game: " + rmsg + " (" + gameId + ")");
                    return seq;
                case DUPLICATE:
                    Log.d(TAG, "dropping duplicate reliable message " + seq + ": " + rmsg);
                    break;
                case APPLY:
                    if (rmsg.startsWith(NetMsg.NETMSG_ELIMINATED))
                        handleEliminated(client, rmsg);
                    else
                        Log.w(TAG, "unknown reliable message: " + rmsg);
                    break;
            }
            PlayerRecord player = client.getPlayer();
            return player != null ? player.getLastAppliedSeq() : seq;
        }

        /** The entries a (re)joining client hadn't had acknowledged, applied before its snapshot is built. */
        private void applyPending(Connection client, JSONObject hello) {
            JSONArray pending = hello.optJSONArray(JSON_PENDING);
            if (pending == null)
                return;
            long gameId = hello.optLong(JSON_GAMEID, 0);
            for (int i = 0; i < pending.length(); i++) {
                JSONObject entry = pending.optJSONObject(i);
                if (entry == null || !entry.has(JSON_RMSG) || !entry.has(JSON_SEQ))
                    continue;
                handleReliable(client, entry.optString(JSON_RMSG), entry.optInt(JSON_SEQ), gameId);
            }
        }

        private void parsePlayerInfo(String message, Connection client) {
            JSONObject player;
            try {
                player = new JSONObject(message);
            } catch (JSONException e) {
                e.printStackTrace();
                return;
            }
            if (player.has(JSON_RMSG)) {
                try {
                    int ack = handleReliable(client, player.getString(JSON_RMSG), player.getInt(JSON_SEQ), player.optLong(JSON_GAMEID, 0));
                    if (ack >= 0)
                        client.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ACK + ack);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return;
            }
            if (player.has(JSON_REQUEST_TEAM_CHANGE)) {
                try {
                    int targetTeam = player.getInt(JSON_REQUEST_TEAM_CHANGE);
                    handleClientTeamChangeRequest(client, targetTeam);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return;
            }
            if (player.has(JSON_PAIRED_GRENADE_ID)) {
                Globals.getmGrenadePairingsSemaphore();
                try {
                    Globals.getInstance().mGrenadePairings[player.getInt(JSON_PAIRED_GRENADE_ID)] = player.getInt(JSON_PLAYERID);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                sendGrenadePairings(false);
                Globals.getInstance().mGrenadePairingsSemaphore.release();
                return;
            }
            if (player.has(JSON_GPSLONGITUDE)) {
                Globals.getmGPSDataSemaphore();
                byte playerId = playerIdOf(client);
                PlayerRecord record = client.getPlayer();
                Globals.GPSData gps = Globals.getInstance().mGPSData.get(playerId);
                double longitude, latitude;
                try {
                    longitude = player.getDouble(JSON_GPSLONGITUDE);
                    latitude = player.getDouble(JSON_GPSLATITUDE);
                } catch (JSONException e) {
                    e.printStackTrace();
                    Globals.getInstance().mGPSDataSemaphore.release();
                    return;
                }
                if (gps == null) {
                    gps = new Globals.GPSData();
                    Globals.getInstance().mGPSData.put(playerId, gps);
                }
                gps.longitude = longitude;
                gps.latitude = latitude;
                gps.team = record != null ? record.getTeam() : 0;
                gps.hasUpdate = true; // Client takes care of making sure that it is only sending us changes in coordinates
                Globals.getInstance().mGPSDataSemaphore.release();
                return;
            }
            if (player.has(JSON_PLAYERSETTINGS)) {
                if (!Globals.getInstance().mAllowPlayerSettings) {
                    Log.e(TAG, "Player " + playerIdOf(client) + "not allowed to send player settings");
                    sendPlayerSettings((byte)SEND_ALL, false, true);
                    return;
                }
                Globals.getmPlayerSettingsSemaphore();
                byte playerId = playerIdOf(client);
                Globals.PlayerSettings settings = Globals.getInstance().mPlayerSettings.get(playerId);
                if (settings == null) {
                    settings = new Globals.PlayerSettings();
                    Globals.getInstance().mPlayerSettings.put(playerId, settings);
                }
                try {
                    settings.health = player.getInt(JSON_HEALTH);
                    settings.shots = (byte)player.getInt(JSON_RELOAD_SHOTS);
                    settings.reloadTime = player.getLong(JSON_RELOAD_TIME);
                    settings.reloadOnEmpty = player.getBoolean(JSON_RELOAD_ON_EMPTY);
                    settings.spawnTime = player.getLong(JSON_SPAWN_TIME);
                    if (player.has(JSON_RESPAWN_SHIELD_TIME)) {
                        settings.respawnShieldTime = player.getLong(JSON_RESPAWN_SHIELD_TIME);
                    }
                    settings.damage = player.getInt(JSON_DAMAGE);
                    if (player.has(JSON_LIVESLIMIT)) {
                        settings.overrideLives = true;
                        settings.lives = player.getInt(JSON_LIVESLIMIT);
                    } else {
                        settings.overrideLives = false;
                        settings.lives = 0;
                    }
                    settings.allowShotModeAuto = player.getBoolean(JSON_SHOT_MODE_AUTO);
                    settings.allowShotModeBurst3 = player.getBoolean(JSON_SHOT_MODE_BURST3);
                    settings.allowShotModeSingle = player.getBoolean(JSON_SHOT_MODE_SINGLE);
                    settings.firingMode = player.getInt(JSON_FIRING_MODE);
                    if (player.has(JSON_RECOIL_SETTING)) {
                        settings.recoilSetting = player.getInt(JSON_RECOIL_SETTING);
                    }
                    // JSON_IS_ADMIN is ignored: only the server operator grants admin rights (D14)
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                Globals.getInstance().mPlayerSettingsSemaphore.release();
                sendPlayerSettings((byte)SEND_ALL, false, true);
                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                return;
            }
            if (player.has(JSON_PLAYERNAMECHANGE)) {
                String playerName;
                try {
                    playerName = player.getString(JSON_PLAYERNAMECHANGE);
                } catch (JSONException e) {
                    e.printStackTrace();
                    return;
                }
                Globals.getmTeamPlayerNameSemaphore();
                try {
                    mRoster.onNameChange(client, playerName);
                } finally {
                    Globals.getInstance().mTeamPlayerNameSemaphore.release();
                }

                sendAllGameInfo(SEND_ALL);
                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                return;
            }
            mGPSIntervalCount.set(SEND_ALL_GPS_INTERVAL); // Send all GPS info because of the new client
            byte id;
            String playerName;
            String blasterMac;
            try {
                id = (byte) player.getInt(JSON_PLAYERID);
                playerName = player.getString(JSON_PLAYERNAME);
                blasterMac = player.has(JSON_BLASTER_MAC) ? player.getString(JSON_BLASTER_MAC) : "";
            } catch (JSONException e) {
                e.printStackTrace();
                return;
            }

            GameRoster.HelloResult result;
            Globals.getmTeamIPMapSemaphore();
            Globals.getmIPTeamMapSemaphore();
            Globals.getmTeamPlayerNameSemaphore();
            try {
                result = mRoster.onHello(client, id, playerName, blasterMac, client.getInetAddress());
            } finally {
                Globals.getInstance().mTeamPlayerNameSemaphore.release();
                Globals.getInstance().mIPTeamMapSemaphore.release();
                Globals.getInstance().mTeamIPMapSemaphore.release();
            }

            switch (result.kind) {
                case REFUSED_IN_PROGRESS:
                    Log.d(TAG, "Rejecting client " + client.getConnectionId() + " (MAC: " + blasterMac + ") - Game in progress and allowJoin is false.");
                    client.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ERROR + "Cannot join game in progress due to server settings.");
                    client.close();
                    mRoster.removeConnection(client);
                    return;
                case FULL:
                    client.close();
                    mRoster.removeConnection(client);
                    return;
                case REJOINED: {
                    PlayerRecord record = result.record;
                    byte existingPlayerId = record.getPlayerId();
                    Log.d(TAG, "rejoining client " + client.getConnectionId() + " to player ID " + existingPlayerId + " (MAC: " + blasterMac + ")");
                    if (result.displaced != null)
                        result.displaced.close();
                    applyPending(client, player);
                    client.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERREPLY + existingPlayerId);
                    sendAllGameInfo(existingPlayerId);
                    sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                    sendBroadcast(new Intent(NetMsg.NETMSG_JOIN));
                    return;
                }
                default:
                    break;
            }

            PlayerRecord record = result.record;
            id = record.getPlayerId();
            applyPending(client, player);
            client.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERREPLY + id);
            Log.e(TAG, "network team is " + record.getTeam());
            InetAddress inetAddress = client.getInetAddress();
            Log.d(TAG, "client " + client.getConnectionId() + " player '" + record.getName() + "' (" + id + ") found at " + (inetAddress != null ? inetAddress.toString() : "null"));

            sendAllGameInfo(id);
            sendBroadcast(new Intent(NetMsg.NETMSG_JOIN));
        }

        private void handleClientTeamChangeRequest(final Connection client, final int targetTeam) {
            final PlayerRecord player = client != null ? client.getPlayer() : null;
            if (player == null || player.getPlayerId() <= 0) {
                return;
            }
            if (!GameSettingsRepository.getInstance().isAllowChooseTeams()) {
                Log.w(TAG, "Team change rejected: Allow players to choose teams is turned off.");
                return;
            }
            if (mRoster.phase() != SessionPhase.LOBBY) {
                Log.w(TAG, "Team change rejected: only allowed in the lobby.");
                return;
            }
            int gameMode = GameSettingsRepository.getInstance().getGameMode();
            if (gameMode == GameSettingsRepository.GAME_MODE_FFA) {
                Log.w(TAG, "Team change rejected: Free For All mode.");
                return;
            }
            int numTeams = (gameMode == GameSettingsRepository.GAME_MODE_4TEAMS) ? 4 : 2;
            if (targetTeam < 1 || targetTeam > numTeams) {
                Log.w(TAG, "Team change rejected: Invalid target team " + targetTeam);
                return;
            }
            if (player.getTeam() == targetTeam) {
                Log.d(TAG, "Client " + player.getPlayerId() + " is already on team " + targetTeam);
                return;
            }

            Thread switchThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    PlayerRecord moved;
                    acquirePlayerMapSemaphores();
                    try {
                        moved = mRoster.changeTeam(client, targetTeam);
                    } finally {
                        releasePlayerMapSemaphores();
                    }
                    if (moved == null) {
                        Log.w(TAG, "Team change rejected: No open slot on team " + targetTeam);
                        return;
                    }
                    client.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERREPLY + moved.getPlayerId());
                    sendPlayerData(SEND_ALL);
                    sendAllGameInfo(SEND_ALL);
                    sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                }
            });
            switchThread.start();
        }

        private void removeClient(Connection client) {
            GameRoster.Disconnect result = mRoster.onDisconnect(client);
            if (result == GameRoster.Disconnect.REMOVED) {
                mGPSIntervalCount.set(SEND_ALL_GPS_INTERVAL); // Force a full GPS update when someone leaves
                sendAllGameInfo(SEND_ALL);
                sendBroadcast(new Intent(NetMsg.NETMSG_LEAVE));
            }
            client.close(); // a player kept during a game stays on the scoreboard, disconnected
            sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
        }
    }

    public void stopTcpServer() {
        keepListening = false;
    }

    /** The server socket is open (tests wait for it). */
    boolean isAccepting() {
        return mAccepting;
    }

    GameRoster getRoster() {
        return mRoster;
    }

    private void sleep(long millis) {
        if (millis <= 0)
            return;
        try {
            Thread.sleep(millis);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
