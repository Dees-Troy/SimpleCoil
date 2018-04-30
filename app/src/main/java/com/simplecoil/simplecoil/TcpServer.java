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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Semaphore;

import javax.microedition.khronos.opengles.GL;

public class TcpServer extends Service {
    private static final String TAG = "TCPServer";

    public static final int SEND_ALL = -100;

    public static final int TCP_SERVER_PORT = 17510;
    // Amount of time to sleep before checking if data is available. Lower is more responsive but may gobble up more CPU time
    public static final int TCP_READ_WAIT_MS = 200;
    public static final int TCP_DEDICATED_READ_WAIT_MS = 100;
    public static final String TCP_SERVER_PING = "ping";

    public static final String TCPMESSAGE_PREFIX = NetMsg.MESSAGE_PREFIX + NetMsg.NETWORK_VERSION;
    public static final String TCPPREFIX_MESG = "MESG";
    public static final String TCPPREFIX_JSON = "JSON";

    public static final String JSON_PLAYERS = "players";
    public static final String JSON_PLAYERNAME = "playername";
    public static final String JSON_PLAYERID = "playerID";
    public static final String JSON_PLAYERIP = "playerIP";
    public static final String JSON_LIMITS = "limits";
    public static final String JSON_TIMELIMIT = "timelimit";
    public static final String JSON_LIVESLIMIT = "liveslimit";
    public static final String JSON_SCORELIMIT = "scorelimit";
    public static final String JSON_GAMEMODE = "gamemode";
    public static final String JSON_REJOIN = "rejoin";
    public static final String JSON_DEDICATED = "dedicatedserver";
    public static final String JSON_USEGPS = "usegps";
    public static final String JSON_TEAM = "team";
    public static final String JSON_GAMESTATE = "gamestate";

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
    public static final String JSON_DAMAGE = "damage";
    public static final String JSON_SHOT_MODE_SINGLE = "shotmodesingle";
    public static final String JSON_SHOT_MODE_BURST3 = "shotmodeburst3";
    public static final String JSON_SHOT_MODE_AUTO = "shotmodeauto";
    public static final String JSON_ALLOWPLAYERSETTINGS = "allowplayersettings";

    private static volatile boolean keepListening = false;
    private Semaphore mClientDataSemaphore = new Semaphore(1);
    private volatile Map<Integer, ClientData> mClientData = null;
    private static boolean mIsDedicated = false;
    private static volatile boolean mGPSRunning = false;

    Handler mGPSHandler = new Handler();
    Runnable mGPSRunnable = null;
    private static final long GPS_UPDATE_INTERVAL = 1000;
    private static final int SEND_ALL_GPS_INTERVAL = 20; // We will send all GPS data regardless of whether there was a GPS change every xx intervals
    private static volatile int mGPSIntervalCount = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    public class LocalBinder extends Binder {
        TcpServer getService() {
            return TcpServer.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    public void sendTCPMessageAll(final String message) {
        sendTCPMessageAll(message, false);
    }

    public void sendTCPMessageAll(final String message, final boolean queueMessage) {
        if (mClientData == null || mClientData.size() == 0)
            return;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                try {
                    mClientDataSemaphore.acquire();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    entry.getValue().sendTCPMessage(message, queueMessage);
                }
                mClientDataSemaphore.release();
            }
        });
        sendThread.start();
    }

    private void sendTCPMessageID(final String message, final byte playerID, final boolean waitForAccess) {
        sendTCPMessageID(message, playerID, waitForAccess, false);
    }

    private void sendTCPMessageID(final String message, final byte playerID, final boolean waitForAccess, final boolean queueMessage) {
        if (mClientData == null || mClientData.size() == 0)
            return;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                try {
                    if (waitForAccess)
                        mClientDataSemaphore.acquire();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    if (entry.getValue().mPlayerID == playerID) {
                        entry.getValue().sendTCPMessage(message, queueMessage);
                        break;
                    }
                }
                if (waitForAccess)
                    mClientDataSemaphore.release();
            }
        });
        sendThread.start();
    }

    private void sendTCPMessageTeam(final String message, final byte playerID, final boolean includePlayer, final boolean waitForAccess) {
        sendTCPMessageTeam(message, playerID, includePlayer, waitForAccess, false);
    }

    private void sendTCPMessageTeam(final String message, final byte playerID, final boolean includePlayer, final boolean waitForAccess, final boolean queueMessage) {
        if (mClientData == null || mClientData.size() == 0)
            return;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                try {
                    if (waitForAccess)
                        mClientDataSemaphore.acquire();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                int team = -1;
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    if (entry.getValue().mPlayerID == playerID) {
                        team = entry.getValue().mNetworkTeam;
                        if (includePlayer)
                            entry.getValue().sendTCPMessage(message, queueMessage);
                        break;
                    }
                }
                if (team != -1) {
                    Log.e(TAG, "team is " + team);
                    for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                        if (entry.getValue().mNetworkTeam == team && entry.getValue().mPlayerID != playerID)
                            entry.getValue().sendTCPMessage(message, queueMessage);
                    }
                }
                if (waitForAccess)
                    mClientDataSemaphore.release();
            }
        });
        sendThread.start();
    }

    public void startGame() {
        if (mClientData == null || mClientData.size() == 0)
            return;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                // we want to make sure that we have sent the start game message to all clients before broadcasting that the game has started locally
                String message = TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_STARTGAME;
                try {
                    mClientDataSemaphore.acquire();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    entry.getValue().sendTCPMessage(message);
                }
                mClientDataSemaphore.release();
                sendBroadcast(new Intent(NetMsg.NETMSG_STARTGAME));
                if (!mIsDedicated)
                    keepListening = false;
            }
        });
        sendThread.start();
    }

    public void endGame() {
        if (mClientData == null || mClientData.size() == 0)
            return;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                sendPlayerData(SEND_ALL);
                // we want to make sure that we have sent the end game message to all clients before removing all clients
                String message = TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ENDGAME;
                try {
                    mClientDataSemaphore.acquire();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    entry.getValue().sendTCPMessage(message);
                    entry.getValue().close();
                }
                mClientData.clear();
                mClientDataSemaphore.release();
                Globals.getmGPSDataSemaphore();
                Globals.getInstance().mGPSData.clear();
                Globals.getInstance().mGPSDataSemaphore.release();
                Globals.getmTeamPlayerNameSemaphore();
                Globals.getInstance().mTeamPlayerNameMap.clear();
                Globals.getInstance().mTeamPlayerNameSemaphore.release();
                Globals.getmTeamIPMapSemaphore();
                Globals.getInstance().mTeamIPMap.clear();
                Globals.getInstance().mTeamIPMapSemaphore.release();
                Globals.getmIPTeamMapSemaphore();
                Globals.getInstance().mIPTeamMap.clear();
                Globals.getInstance().mIPTeamMapSemaphore.release();
                sendBroadcast(new Intent(NetMsg.NETMSG_ENDGAME));
            }
        });
        sendThread.start();
    }

    public void sendAllGameInfo(final int id) {
        if (mClientData == null || mClientData.size() == 0 || Globals.getInstance().mTeamIPMap == null || Globals.getInstance().mTeamIPMap.size() == 0)
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
            if (!mIsDedicated) {
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERNAME, Globals.getInstance().mPlayerName);
                player.put(JSON_PLAYERID, Globals.getInstance().mPlayerID);
                player.put(JSON_PLAYERIP, Globals.getIPAddressStr());
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
            if (mIsDedicated) {
                game.put(JSON_DEDICATED, true);
                game.put(JSON_GAMESTATE, Globals.getInstance().mGameState);
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE && (Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0)
                    game.put(JSON_TIMEREMAINING, Globals.getInstance().mServerGameTimeRemaining);
            }
            if (Globals.getInstance().mUseGPS)
                game.put(JSON_USEGPS, Globals.getInstance().mGPSMode);
            game.put(JSON_ALLOWPLAYERSETTINGS, Globals.getInstance().mAllowPlayerSettings);
            game.put(JSON_PLAYERSETTINGS, getPlayerSettings(id, false));
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
                    int teamPoints = 0;
                    int team = Globals.getInstance().calcNetworkTeam((byte)id);
                    for (byte x = 0; x <= Globals.MAX_PLAYER_ID; x++) {
                        if (Globals.getInstance().calcNetworkTeam(x) == team && getScore(x) != null)
                            teamPoints += getScore(x).points;
                    }
                    playerGameUpdate.put(JSON_TEAMPOINTS, teamPoints);
                }
                if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0 && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
                    playerGameUpdate.put(JSON_TIMEREMAINING, Globals.getInstance().mServerGameTimeRemaining);
                }
                game.put(JSON_PLAYERGAMEUPDATE, playerGameUpdate);
                final String idMessage = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();

                Thread sendThread = new Thread(new Runnable() {
                    public void run() {
                        try {
                            mClientDataSemaphore.acquire();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                            if (entry.getValue().mPlayerID != id)
                                entry.getValue().sendTCPMessage(allMessage, false);
                            else
                                entry.getValue().sendTCPMessage(idMessage, false);
                        }
                        mClientDataSemaphore.release();
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
        mGPSIntervalCount = 0;
        mGPSHandler.postDelayed(new Runnable() {
            public void run() {
                mGPSRunnable = this;
                boolean hasSemaphore = false;
                try {
                    Globals.getmGPSDataSemaphore();
                    hasSemaphore = true;
                    if (Globals.getInstance().mGPSData != null && Globals.getInstance().mGPSData.size() != 0) {
                        JSONArray players = new JSONArray();
                        boolean hasUpdate = false;
                        for (Map.Entry<Byte, Globals.GPSData> entry : Globals.getInstance().mGPSData.entrySet()) {
                            if (entry.getValue().hasUpdate || mGPSIntervalCount >= SEND_ALL_GPS_INTERVAL) {
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
                            if (mGPSIntervalCount >= SEND_ALL_GPS_INTERVAL) {
                                game.put(JSON_GPSFULLUPDATE, true);
                            }
                            String message = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
                            sendTCPMessageAll(message, false);
                        }
                        if (mGPSIntervalCount++ >= SEND_ALL_GPS_INTERVAL)
                            mGPSIntervalCount = 0;
                    } else {
                        Globals.getInstance().mGPSDataSemaphore.release();
                        hasSemaphore = false;
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                    if (hasSemaphore)
                        Globals.getInstance().mGPSDataSemaphore.release();
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
        if (mClientData == null || mClientData.size() == 0 || Globals.getInstance().mTeamIPMap == null || Globals.getInstance().mTeamIPMap.size() == 0)
            return;
        try {
            JSONArray players = new JSONArray();
            for (Map.Entry<Byte, InetAddress> entry : Globals.getInstance().mTeamIPMap.entrySet()) {
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERNAME, Globals.getInstance().getPlayerName(entry.getKey()));
                player.put(JSON_PLAYERID, entry.getKey());
                player.put(JSON_PLAYERIP, entry.getValue());
                ScoreData scoreData = getScore(entry.getKey());
                player.put(JSON_PLAYERPOINTS, scoreData.points);
                player.put(JSON_PLAYERELIMINATED, scoreData.eliminated);
                players.put(player);
            }
            JSONObject game = new JSONObject();
            game.put(JSON_PLAYERDATA, players);
            String message = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
            if (playerID != SEND_ALL)
                sendTCPMessageID(message, (byte)playerID, false);
            else {
                // Using sendTCPMessageAll here does not work because the server ends before the messages get sent, mostly due to semaphore locking
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    entry.getValue().sendTCPMessage(message);
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public JSONArray getPlayerSettings(int playerID, boolean applyAll) {
        boolean hasSemaphore = false;
        try {
            JSONArray players = new JSONArray();
            Globals.getmPlayerSettingsSemaphore();
            hasSemaphore = true;
            if (applyAll && playerID != SEND_ALL) {
                for (Byte x = 1; x <= Globals.MAX_PLAYER_ID; x++) {
                    if (x != playerID) {
                        Globals.PlayerSettings playerSettings = Globals.getInstance().mPlayerSettings.get(x);
                        if (playerSettings == null) {
                            playerSettings = new Globals.PlayerSettings();
                            Globals.getInstance().mPlayerSettings.put(x, playerSettings);
                        }
                        playerSettings.health = Globals.getInstance().mPlayerSettings.get((byte)playerID).health;
                        playerSettings.shots = Globals.getInstance().mPlayerSettings.get((byte)playerID).shots;
                        playerSettings.reloadTime = Globals.getInstance().mPlayerSettings.get((byte)playerID).reloadTime;
                        playerSettings.spawnTime = Globals.getInstance().mPlayerSettings.get((byte)playerID).spawnTime;
                        playerSettings.damage = Globals.getInstance().mPlayerSettings.get((byte)playerID).damage;
                        playerSettings.overrideLives = Globals.getInstance().mPlayerSettings.get((byte)playerID).overrideLives;
                        playerSettings.lives = Globals.getInstance().mPlayerSettings.get((byte)playerID).lives;
                        playerSettings.allowShotModeSingle = Globals.getInstance().mPlayerSettings.get((byte)playerID).allowShotModeSingle;
                        playerSettings.allowShotModeBurst3 = Globals.getInstance().mPlayerSettings.get((byte)playerID).allowShotModeBurst3;
                        playerSettings.allowShotModeAuto = Globals.getInstance().mPlayerSettings.get((byte)playerID).allowShotModeAuto;
                    }
                }
            }
            if (mClientData == null || mClientData.size() == 0 || Globals.getInstance().mTeamIPMap == null || Globals.getInstance().mTeamIPMap.size() == 0) {
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
                player.put(JSON_DAMAGE, entry.getValue().damage);
                if (entry.getValue().overrideLives)
                    player.put(JSON_LIVESLIMIT, entry.getValue().lives);
                player.put(JSON_SHOT_MODE_SINGLE, entry.getValue().allowShotModeSingle);
                player.put(JSON_SHOT_MODE_BURST3, entry.getValue().allowShotModeBurst3);
                player.put(JSON_SHOT_MODE_AUTO, entry.getValue().allowShotModeAuto);
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
        JSONArray players = getPlayerSettings(playerID, applyAll);
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
        if (mClientData == null)
            mClientData = new HashMap<>();
        else
            mClientData.clear();
        if (Globals.getInstance().mGPSData == null)
            Globals.getInstance().mGPSData = new HashMap<>();
        else
            Globals.getInstance().mGPSData.clear();
        keepListening = true;
        ServerSocket ss = null;
        int clientID = 0;
        try {
            ss = new ServerSocket(TCP_SERVER_PORT);
            ss.setSoTimeout(1000);
            Socket s = null;
            Log.d(TAG, "TCP Server listening");
            while (keepListening) {
                try {
                    // Wait for new clients
                    s = ss.accept();
                    clientID++;
                    ClientData client = new ClientData();
                    client.initialize(s, clientID);
                    try {
                        mClientDataSemaphore.acquire();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    mClientData.put(clientID, client);
                    mClientDataSemaphore.release();
                    if (clientID <= 1) {
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

    private int clientIDFromPlayerID(byte playerID) {
        for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
            if (entry.getValue().mPlayerID == playerID) {
                return entry.getKey();
            }
        }
        return -1;
    }

    public void lockAccess() {
        try {
            mClientDataSemaphore.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void unlockAccess() {
        mClientDataSemaphore.release();
    }

    public ScoreData getScore(byte playerID) {
        int clientID = clientIDFromPlayerID(playerID);
        if (clientID < 0)
            return null;
        ScoreData scoreData = new ScoreData();
        scoreData.points = mClientData.get(clientID).points;
        scoreData.eliminated = mClientData.get(clientID).eliminated;
        scoreData.isConnected = mClientData.get(clientID).out != null;
        return scoreData;
    }

    public class ScoreData {
        int points = 0;
        int eliminated = 0;
        boolean isConnected = false;
    }

    private class ClientData {
        private Socket clientSocket = null;
        private int clientID = -1;
        private byte mPlayerID = 0;
        private int mNetworkTeam = 0;
        private int noReadCount = 0;
        private InputStream is = null;
        private OutputStream os = null;
        private DataInputStream in = null;
        private DataOutputStream out = null;
        private Queue<String> messageQueue;
        private int points = 0;
        private int eliminated = 0;

        public void initialize(Socket s, int cID) {
            clientSocket = s;
            clientID = cID;
            noReadCount = 0;
            if (messageQueue == null)
                messageQueue = new LinkedList<>();
            try {
                is = clientSocket.getInputStream();
                in = new DataInputStream(is);
                os = clientSocket.getOutputStream();
                out = new DataOutputStream(os);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void sendTCPMessage(final String message) { sendTCPMessage(message, false); }

        public void sendTCPMessage(final String message, boolean queueFailed) {
            if (out == null) {
                if (queueFailed) {
                    Log.e(TAG, "queuing: " + message);
                    messageQueue.add(message);
                }
                return;
            }
            try {
                out.writeUTF(message);
                out.flush();
                if (!message.equals(TcpServer.TCP_SERVER_PING))
                    Log.i(TAG, "sent(" + clientID + "): " + message);
            } catch (IOException e) {
                //Log.e(TAG, "IO Error:", e);
                e.printStackTrace();
                if (queueFailed) {
                    Log.e(TAG, "queuing: " + message);
                    messageQueue.add(message);
                }
            }
        }

        public void close() {
            if (clientSocket == null) return;
            try { clientSocket.close(); } catch (IOException e) {/*do nothing*/}
            try { in.close(); } catch (IOException e) {/*do nothing*/}
            try { is.close(); } catch (IOException e) {/*do nothing*/}
            try { out.close(); } catch (IOException e) {/*do nothing*/}
            try { os.close(); } catch (IOException e) {/*do nothing*/}
            clientSocket = null;
            in = null;
            is = null;
            out = null;
            os = null;
        }

        public void rejoin(Socket s) {
            close();
            initialize(s, clientID);
            while (messageQueue.size() > 0) {
                Log.e(TAG, "sending queued: " + messageQueue.peek());
                sendTCPMessage(messageQueue.peek());
                messageQueue.remove();
            }
        }
    }

    private class ClientThread implements Runnable {
        public void run() {
            Log.i(TAG, "Running TCP listening server for clients");
            if (Globals.getInstance().mUseGPS) {
                sendGPSData();
            }
            //receive a message
            while (keepListening) {
                try {
                    mClientDataSemaphore.acquire();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                long startTime = System.currentTimeMillis();
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    try {
                        if (entry.getValue().in != null && entry.getValue().in.available() > 0) {
                            entry.getValue().noReadCount = 0;
                            String message = entry.getValue().in.readUTF();
                            if (!message.equals(TcpClient.TCP_CLIENT_PONG)) {
                                Log.i(TAG, "received: '" + message + "' from " + entry.getValue().clientID);
                                if (message.startsWith(TCPMESSAGE_PREFIX)) {
                                    if (message.substring(TCPMESSAGE_PREFIX.length(), TCPMESSAGE_PREFIX.length() + TCPPREFIX_JSON.length()).equals(TCPPREFIX_JSON)) {
                                        // Handle JSON Data
                                        message = message.substring(TCPMESSAGE_PREFIX.length() + TCPPREFIX_JSON.length());
                                        parsePlayerInfo(message, entry.getValue());
                                        break;
                                    } else if (message.substring(TCPMESSAGE_PREFIX.length(), TCPMESSAGE_PREFIX.length() + TCPPREFIX_MESG.length()).equals(TCPPREFIX_MESG)) {
                                        // Handle messages
                                        message = message.substring(TCPMESSAGE_PREFIX.length() + TCPPREFIX_MESG.length());
                                        if (message.equals(NetMsg.NETMSG_LEAVE)) {
                                            removeClient(entry.getValue(), entry.getKey(), true);
                                            if (mClientData.size() <= 1 && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                                                endGame();
                                            break;
                                        } else if (message.startsWith(NetMsg.NETMSG_ELIMINATED)) {
                                            // A player was eliminated
                                            entry.getValue().eliminated++;
                                            message = message.substring(NetMsg.NETMSG_ELIMINATED.length());
                                            Byte id = (byte) (int) Integer.parseInt(message);
                                            int clientID = clientIDFromPlayerID(id);
                                            if (clientID >= 0)
                                                mClientData.get(clientID).points++;
                                            sendTCPMessageID(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ELIMINATED + entry.getValue().mPlayerID, id, false, true);
                                            if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                                                // Send a message to all teammates about the score increase
                                                sendTCPMessageTeam(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_TEAMELIMINATED, (byte) id, false, false, true);
                                            }
                                            sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                                        } else if (message.equals(NetMsg.NETMSG_PLAYERDATAREQUEST)) {
                                            sendPlayerData(entry.getValue().mPlayerID);
                                        } else if (message.equals(NetMsg.NETMSG_STARTGAME)) {
                                            startGame();
                                        } else if (message.equals(NetMsg.NETMSG_ENDGAME)) {
                                            endGame();
                                        }
                                    } else {
                                        Log.d(TAG, "unknown tcp message received");
                                    }
                                } else if (message.startsWith(NetMsg.MESSAGE_PREFIX)) {
                                    entry.getValue().sendTCPMessage(NetMsg.NETMSG_VERSIONERROR);
                                    break;
                                }
                            }
                        } else {
                            entry.getValue().noReadCount++;
                            if (entry.getValue().out != null && entry.getValue().noReadCount >= 35) {
                                Log.d(TAG, "no reply so dropping client " + entry.getValue().clientID);
                                removeClient(entry.getValue(), entry.getKey(), false);
                                break;
                            } else if (entry.getValue().noReadCount == 10 || entry.getValue().noReadCount == 20) {
                                entry.getValue().sendTCPMessage(TCP_SERVER_PING);
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        removeClient(entry.getValue(), entry.getKey(), false);
                        break;
                    }
                }
                mClientDataSemaphore.release();
                long sleepTime = TCP_READ_WAIT_MS - (System.currentTimeMillis() - startTime);
                if (mIsDedicated)
                    sleepTime = TCP_DEDICATED_READ_WAIT_MS - (System.currentTimeMillis() - startTime);
                //Log.d(TAG, "Took " + (System.currentTimeMillis() - startTime) + "ms to service " + mClientData.size() + " clients");
                sleep(sleepTime);
            }
            try {
                mClientDataSemaphore.acquire();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                entry.getValue().close();
            }
            mClientDataSemaphore.release();
        }

        private void parsePlayerInfo(String message, ClientData client) {
            JSONObject player;
            try {
                player = new JSONObject(message);
            } catch (JSONException e) {
                e.printStackTrace();
                return;
            }
            if (player.has(JSON_GPSLONGITUDE)) {
                Globals.getmGPSDataSemaphore();
                Globals.GPSData gps = Globals.getInstance().mGPSData.get(client.mPlayerID);
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
                    Globals.getInstance().mGPSData.put(client.mPlayerID, gps);
                }
                gps.longitude = longitude;
                gps.latitude = latitude;
                gps.team = client.mNetworkTeam;
                gps.hasUpdate = true; // Client takes care of making sure that it is only sending us changes in coordinates
                Globals.getInstance().mGPSDataSemaphore.release();
                return;
            }
            if (player.has(JSON_PLAYERSETTINGS)) {
                if (!Globals.getInstance().mAllowPlayerSettings) {
                    Log.e(TAG, "Player " + client.mPlayerID + "not allowed to send player settings");
                    sendPlayerSettings((byte)SEND_ALL, false, true);
                    return;
                }
                Globals.getmPlayerSettingsSemaphore();
                Globals.PlayerSettings settings = Globals.getInstance().mPlayerSettings.get(client.mPlayerID);
                if (settings == null) {
                    settings = new Globals.PlayerSettings();
                    Globals.getInstance().mPlayerSettings.put(client.mPlayerID, settings);
                }
                try {
                    settings.health = player.getInt(JSON_HEALTH);
                    settings.shots = (byte)player.getInt(JSON_RELOAD_SHOTS);
                    settings.reloadTime = player.getLong(JSON_RELOAD_TIME);
                    settings.reloadOnEmpty = player.getBoolean(JSON_RELOAD_ON_EMPTY);
                    settings.spawnTime = player.getLong(JSON_SPAWN_TIME);
                    settings.damage = player.getInt(JSON_DAMAGE);
                    if (player.has(JSON_LIVESLIMIT)) {
                        settings.overrideLives = true;
                        settings.lives = player.getInt(JSON_LIVESLIMIT);
                    } else {
                        settings.overrideLives = false;
                        settings.lives = 0;
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                Globals.getInstance().mPlayerSettingsSemaphore.release();
                sendPlayerSettings((byte)SEND_ALL, false, true);
                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                return;
            }
            mGPSIntervalCount = SEND_ALL_GPS_INTERVAL; // Send all GPS info because of the new client
            boolean rejoin;
            byte id;
            String playerName;
            try {
                rejoin = player.has(JSON_REJOIN);
                id = (byte) player.getInt(JSON_PLAYERID);
                playerName = player.getString(JSON_PLAYERNAME);
            } catch (JSONException e) {
                e.printStackTrace();
                return;
            }
            for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                if (entry.getValue().mPlayerID == id) {
                    if (rejoin) {
                        Log.d(TAG, "rejoining " + client.clientID + " to " + entry.getValue().clientID);
                        entry.getValue().rejoin(client.clientSocket);
                        mClientData.remove(client.clientID);
                        sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                        return;
                    } else {
                        Log.d(TAG, "new client " + client.clientID + " replacing old client " + entry.getValue().clientID);
                        entry.getValue().rejoin(client.clientSocket);
                        mClientData.remove(client.clientID);
                        break;
                    }
                }
            }
            if (rejoin)
                Log.d(TAG, "rejoined client " + client.clientID + " not present so adding as a new player");
            client.mPlayerID = id;
            client.mNetworkTeam = 1;
            if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                client.mNetworkTeam = Globals.getInstance().calcNetworkTeam(id);
            }
            Log.e(TAG, "network team is " + client.mNetworkTeam);
            InetAddress inetAddress = client.clientSocket.getInetAddress();
            Log.d(TAG, "client " + client.clientID + " player '" + playerName + "' (" + client.mPlayerID + ") found at " + inetAddress.toString());
            Globals.getmTeamIPMapSemaphore();
            Globals.getInstance().mTeamIPMap.put(client.mPlayerID, inetAddress);
            Globals.getInstance().mTeamIPMapSemaphore.release();
            Globals.getmIPTeamMapSemaphore();
            Globals.getInstance().mIPTeamMap.put(inetAddress, client.mPlayerID);
            Globals.getInstance().mIPTeamMapSemaphore.release();
            Globals.getmTeamPlayerNameSemaphore();
            Globals.getInstance().mTeamPlayerNameMap.put(client.mPlayerID, playerName);
            Globals.getInstance().mTeamPlayerNameSemaphore.release();

            sendAllGameInfo(id);
            sendBroadcast(new Intent(NetMsg.NETMSG_JOIN));
        }

        private void removeClient(ClientData client, Integer clientID, boolean alwaysRemove) {
            if (client.mPlayerID != 0) {
                if (alwaysRemove || Globals.getInstance().mGameState == Globals.GAME_STATE_NONE) {
                    Globals.getmTeamPlayerNameSemaphore();
                    Globals.getInstance().mTeamPlayerNameMap.remove(client.mPlayerID);
                    Globals.getInstance().mTeamPlayerNameSemaphore.release();
                    Globals.getmIPTeamMapSemaphore();
                    Globals.getInstance().mIPTeamMap.remove(client.clientSocket.getInetAddress());
                    Globals.getInstance().mIPTeamMapSemaphore.release();
                    Globals.getmTeamIPMapSemaphore();
                    Globals.getInstance().mTeamIPMap.remove(client.mPlayerID);
                    Globals.getInstance().mTeamIPMapSemaphore.release();
                    Globals.getmGPSDataSemaphore();
                    Globals.getInstance().mGPSData.remove(client.mPlayerID);
                    mGPSIntervalCount = SEND_ALL_GPS_INTERVAL; // Force a full GPS update when someone leaves
                    Globals.getInstance().mGPSDataSemaphore.release();
                    sendAllGameInfo(SEND_ALL);
                    sendBroadcast(new Intent(NetMsg.NETMSG_LEAVE));
                    client.close();
                    mClientData.remove(clientID);
                } else {
                    client.close(); // We'll keep this client around in case they rejoin later since the game was underway
                }
            } else {
                client.close();
                mClientData.remove(clientID);
            }
            sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
        }
    }

    public void stopTcpServer() {
        keepListening = false;
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

    public void setDedicated() { mIsDedicated = true; }
}
