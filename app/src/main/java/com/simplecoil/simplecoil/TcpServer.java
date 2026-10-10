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
import com.simplecoil.simplecoil.data.repository.PlayerRepository;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

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
    public static final String JSON_DEDICATED = "dedicatedserver";
    public static final String JSON_USEGPS = "usegps";
    public static final String JSON_TEAM = "team";
    public static final String JSON_GAMESTATE = "gamestate";
    public static final String JSON_ONLY_SERVER_SETTINGS = "onlyserversettings";
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
    public static final String JSON_DAMAGE = "damage";
    public static final String JSON_SHOT_MODE_SINGLE = "shotmodesingle";
    public static final String JSON_SHOT_MODE_BURST3 = "shotmodeburst3";
    public static final String JSON_SHOT_MODE_AUTO = "shotmodeauto";
    public static final String JSON_FIRING_MODE = "firingmode";
    public static final String JSON_RECOIL_SETTING = "recoilsetting";
    public static final String JSON_IS_ADMIN = "isadmin";
    public static final String JSON_ALLOWPLAYERSETTINGS = "allowplayersettings";

    private static volatile boolean keepListening = false;
    private Semaphore mClientDataSemaphore = new Semaphore(1);
    private volatile Map<Integer, ClientData> mClientData = null;
    private static volatile boolean mIsDedicated = false;
    private final AtomicInteger mHostPoints = new AtomicInteger(0);
    private final AtomicInteger mHostEliminated = new AtomicInteger(0);
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
        sendTCPMessageAll(message, false);
    }

    public void sendTCPMessageAll(final String message, final boolean queueMessage) {
        if (mClientData == null || mClientData.size() == 0)
            return;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                try {
                    mClientDataSemaphore.acquire();
                    try {
                        for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                            entry.getValue().sendTCPMessage(message, queueMessage);
                        }
                    } finally {
                        mClientDataSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
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
                boolean hasLock = false;
                try {
                    if (waitForAccess) {
                        mClientDataSemaphore.acquire();
                        hasLock = true;
                    }
                    for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                        if (entry.getValue().mPlayerID == playerID) {
                            entry.getValue().sendTCPMessage(message, queueMessage);
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    if (hasLock)
                        mClientDataSemaphore.release();
                }
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
                boolean hasLock = false;
                try {
                    if (waitForAccess) {
                        mClientDataSemaphore.acquire();
                        hasLock = true;
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
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    if (hasLock)
                        mClientDataSemaphore.release();
                }
            }
        });
        sendThread.start();
    }

    public void resetScores() {
        mHostPoints.set(0);
        mHostEliminated.set(0);
        if (mClientData == null)
            return;
        try {
            mClientDataSemaphore.acquire();
            try {
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    entry.getValue().points.set(0);
                    entry.getValue().eliminated.set(0);
                }
            } finally {
                mClientDataSemaphore.release();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void startGame() {
        Log.d(TAG, "TcpServer.startGame called. mClientData size: " + (mClientData != null ? mClientData.size() : "null"));
        if (mClientData == null || mClientData.size() == 0)
            return;
        Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
        resetScores();

        PlayerRepository.getInstance().clearActiveGameMacMap();
        try {
            mClientDataSemaphore.acquire();
            try {
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    ClientData c = entry.getValue();
                    if (c != null && c.mPlayerID != 0) {
                        String mac = PlayerRepository.getInstance().getPlayerMacMap().get(c.mPlayerID);
                        String pName = PlayerRepository.getInstance().getPlayerName(c.mPlayerID);
                        PlayerRepository.getInstance().addActiveGamePlayer(mac, c.mPlayerID, c.mNetworkTeam, pName);
                    }
                }
            } finally {
                mClientDataSemaphore.release();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (!mIsDedicated && Globals.getInstance().mPlayerID != 0) {
            byte hostId = Globals.getInstance().mPlayerID;
            String hostMac = PlayerRepository.getInstance().getPlayerMacMap().get(hostId);
            String hostName = Globals.getInstance().mPlayerName;
            int hostTeam = Globals.getInstance().calcNetworkTeam(hostId);
            PlayerRepository.getInstance().addActiveGamePlayer(hostMac, hostId, hostTeam, hostName);
        }

        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                // we want to make sure that we have sent the start game message to all clients before broadcasting that the game has started locally
                String message = TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_STARTGAME;
                try {
                    mClientDataSemaphore.acquire();
                    try {
                        for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                            entry.getValue().sendTCPMessage(message);
                        }
                    } finally {
                        mClientDataSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                sendAllGameInfo(SEND_ALL);
                sendBroadcast(new Intent(NetMsg.NETMSG_STARTGAME));
                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                if (!mIsDedicated)
                    keepListening = false;
            }
        });
        sendThread.start();
    }

    public void endGame() {
        Log.d(TAG, "TcpServer.endGame called. mClientData size: " + (mClientData != null ? mClientData.size() : "null"));
        if (mClientData == null)
            return;
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        PlayerRepository.getInstance().clearActiveGameMacMap();
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                sendPlayerData(SEND_ALL);
                // we want to make sure that we have sent the end game message to all clients before removing all clients
                String message = TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ENDGAME;
                try {
                    mClientDataSemaphore.acquire();
                    for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                        entry.getValue().sendTCPMessage(message);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    mClientDataSemaphore.release();
                }
                Globals.getmGPSDataSemaphore();
                Globals.getInstance().mGPSData.clear();
                Globals.getInstance().mGPSDataSemaphore.release();
                sendBroadcast(new Intent(NetMsg.NETMSG_ENDGAME));
            }
        });
        sendThread.start();
    }

    public void switchPlayerTeam(final byte oldPlayerID, final byte newPlayerID) {
        Log.d(TAG, "switchPlayerTeam: moving player " + oldPlayerID + " to new ID " + newPlayerID);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mClientDataSemaphore.acquire();
                    Globals.getmTeamIPMapSemaphore();
                    Globals.getmIPTeamMapSemaphore();
                    Globals.getmTeamPlayerNameSemaphore();
                    Globals.getmPlayerSettingsSemaphore();
                    Globals.getmGPSDataSemaphore();

                    ClientData client = null;
                    for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                        if (entry.getValue().mPlayerID == oldPlayerID) {
                            client = entry.getValue();
                            break;
                        }
                    }
                    if (client != null) {
                        if (client.clientID == (int) oldPlayerID) {
                            mClientData.remove(client.clientID);
                            client.clientID = (int) newPlayerID;
                            mClientData.put((int) newPlayerID, client);
                        }
                        client.mPlayerID = newPlayerID;
                        client.mNetworkTeam = Globals.getInstance().calcNetworkTeam(newPlayerID);
                        client.sendTCPMessage(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERREPLY + newPlayerID);
                    }

                    PlayerRepository.getInstance().reassignPlayer(oldPlayerID, newPlayerID);

                    Globals.GPSData gps = Globals.getInstance().mGPSData.get(newPlayerID);
                    if (gps != null) {
                        gps.team = Globals.getInstance().calcNetworkTeam(newPlayerID);
                        gps.hasUpdate = true;
                    }

                    if (oldPlayerID == Globals.getInstance().mPlayerID) {
                        Globals.getInstance().setPlayerID(newPlayerID);
                    }

                    Globals.getInstance().mGPSDataSemaphore.release();
                    Globals.getInstance().mPlayerSettingsSemaphore.release();
                    Globals.getInstance().mTeamPlayerNameSemaphore.release();
                    Globals.getInstance().mIPTeamMapSemaphore.release();
                    Globals.getInstance().mTeamIPMapSemaphore.release();
                    mClientDataSemaphore.release();

                    sendPlayerData(SEND_ALL);
                    sendAllGameInfo(SEND_ALL);
                    sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                } catch (Exception e) {
                    e.printStackTrace();
                    try { Globals.getInstance().mGPSDataSemaphore.release(); } catch (Exception ignored) {}
                    try { Globals.getInstance().mPlayerSettingsSemaphore.release(); } catch (Exception ignored) {}
                    try { Globals.getInstance().mTeamPlayerNameSemaphore.release(); } catch (Exception ignored) {}
                    try { Globals.getInstance().mIPTeamMapSemaphore.release(); } catch (Exception ignored) {}
                    try { Globals.getInstance().mTeamIPMapSemaphore.release(); } catch (Exception ignored) {}
                    try { mClientDataSemaphore.release(); } catch (Exception ignored) {}
                }
            }
        });
        thread.start();
    }

    public void kickPlayer(final byte playerID) {
        Log.d(TAG, "kickPlayer: kicking player " + playerID);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mClientDataSemaphore.acquire();
                    try {
                        ClientData client = mClientData.get((int) playerID);
                        if (client == null) {
                            for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                                if (entry.getValue().mPlayerID == playerID) {
                                    client = entry.getValue();
                                    break;
                                }
                            }
                        }
                        if (client != null) {
                            client.sendTCPMessage(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERCANCEL);
                            mClientData.remove(client.clientID);
                            client.close();
                        }
                    } finally {
                        mClientDataSemaphore.release();
                    }

                    Globals.getmTeamIPMapSemaphore();
                    InetAddress ip;
                    try {
                        ip = Globals.getInstance().mTeamIPMap.get(playerID);
                        if (ip != null) {
                            Globals.getInstance().mTeamIPMap.remove(playerID);
                        }
                    } finally {
                        Globals.getInstance().mTeamIPMapSemaphore.release();
                    }

                    Globals.getmIPTeamMapSemaphore();
                    try {
                        if (ip != null) {
                            Globals.getInstance().mIPTeamMap.remove(ip);
                        }
                    } finally {
                        Globals.getInstance().mIPTeamMapSemaphore.release();
                    }

                    Globals.getmTeamPlayerNameSemaphore();
                    try {
                        Globals.getInstance().mTeamPlayerNameMap.remove(playerID);
                    } finally {
                        Globals.getInstance().mTeamPlayerNameSemaphore.release();
                    }

                    Globals.getmPlayerSettingsSemaphore();
                    try {
                        Globals.getInstance().mPlayerSettings.remove(playerID);
                    } finally {
                        Globals.getInstance().mPlayerSettingsSemaphore.release();
                    }

                    PlayerRepository.getInstance().removePlayer(playerID);
                    mGPSIntervalCount.set(SEND_ALL_GPS_INTERVAL);
                    sendPlayerData(SEND_ALL);
                    sendAllGameInfo(SEND_ALL);
                    sendBroadcast(new Intent(NetMsg.NETMSG_LEAVE));
                    sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        thread.start();
    }

    private int findBestTeamForRebalance(int numTeams, int[] teamPointers, int[] teamLimits, int[] teamCounts) {
        double minRatio = Double.MAX_VALUE;
        int bestTeam = -1;
        int[] sizes = Globals.getInstance().getTeamSizes();
        if (sizes == null || sizes.length < numTeams) {
            Globals.getInstance().resetDefaultTeamSizes();
            sizes = Globals.getInstance().getTeamSizes();
        }

        for (int t = 1; t <= numTeams; t++) {
            if (teamPointers[t] <= teamLimits[t]) {
                int capacity = sizes[t - 1];
                double ratio = (capacity > 0) ? (double) teamCounts[t] / capacity : 1.0;
                if (ratio < minRatio) {
                    minRatio = ratio;
                    bestTeam = t;
                }
            }
        }
        return bestTeam;
    }

    public void rebalanceAllPlayers(final boolean randomize) {
        if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
            Log.w(TAG, "rebalanceAllPlayers: Game is running, skipping team rebalance!");
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mClientDataSemaphore.acquire();
                    Globals.getmTeamIPMapSemaphore();
                    Globals.getmIPTeamMapSemaphore();
                    Globals.getmTeamPlayerNameSemaphore();
                    Globals.getmPlayerSettingsSemaphore();
                    Globals.getmGPSDataSemaphore();
                    try {
                        int gameMode = Globals.getInstance().mGameMode;
                        List<ClientData> clients = new ArrayList<>(mClientData.values());
                        if (randomize) {
                            Collections.shuffle(clients);
                        }

                        Map<Integer, ClientData> newClientMap = new ConcurrentHashMap<>();
                        Map<Byte, InetAddress> newTeamIPMap = new ConcurrentHashMap<>();
                        Map<InetAddress, Byte> newIPTeamMap = new ConcurrentHashMap<>();
                        Map<Byte, String> newNameMap = new ConcurrentHashMap<>();
                        Map<Byte, Globals.PlayerSettings> newSettingsMap = new ConcurrentHashMap<>();
                        Map<Byte, Globals.GPSData> newGPSMap = new ConcurrentHashMap<>();

                        int maxPlayers = Globals.MAX_PLAYER_ID;

                        if (gameMode == Globals.GAME_MODE_FFA) {
                            byte nextID = 1;
                            Set<Byte> usedIDs = new HashSet<>();
                            if (!mIsDedicated && Globals.getInstance().mPlayerID != 0) {
                                byte oldHostID = Globals.getInstance().mPlayerID;
                                byte newHostID = nextID++;
                                usedIDs.add(newHostID);
                                Globals.getInstance().mPlayerID = newHostID;
                                String hostName = Globals.getInstance().mTeamPlayerNameMap.get(oldHostID);
                                if (hostName == null) hostName = Globals.getInstance().mPlayerName;
                                if (hostName != null) newNameMap.put(newHostID, hostName);
                                InetAddress hostIP = Globals.getInstance().mTeamIPMap.get(oldHostID);
                                if (hostIP == null) {
                                    try { hostIP = InetAddress.getByName(Globals.getIPAddressStr()); } catch (Exception e) {}
                                }
                                if (hostIP != null) {
                                    newTeamIPMap.put(newHostID, hostIP);
                                    newIPTeamMap.put(hostIP, newHostID);
                                }
                                Globals.PlayerSettings set = Globals.getInstance().mPlayerSettings.get(oldHostID);
                                if (set != null) newSettingsMap.put(newHostID, set);
                                Globals.GPSData gps = Globals.getInstance().mGPSData.get(oldHostID);
                                if (gps != null) {
                                    gps.team = newHostID;
                                    gps.hasUpdate = true;
                                    newGPSMap.put(newHostID, gps);
                                }

                                Intent hostReplyIntent = new Intent(NetMsg.NETMSG_SERVERREPLY);
                                hostReplyIntent.putExtra(UDPListenerService.INTENT_PLAYERID, newHostID);
                                sendBroadcast(hostReplyIntent);
                            }
                            for (ClientData client : clients) {
                                byte oldID = client.mPlayerID;
                                byte newID = 0;
                                while (nextID <= maxPlayers) {
                                    byte candidate = nextID++;
                                    if (!usedIDs.contains(candidate)) {
                                        newID = candidate;
                                        break;
                                    }
                                }
                                if (newID == 0) {
                                    Log.w(TAG, "Server full during FFA rebalance! Closing client " + client.clientID);
                                    client.close();
                                    continue;
                                }
                                usedIDs.add(newID);

                                client.clientID = (int) newID;
                                client.mPlayerID = newID;
                                client.mNetworkTeam = newID;
                                newClientMap.put((int) newID, client);

                                InetAddress ip = Globals.getInstance().mTeamIPMap.get(oldID);
                                if (ip != null) {
                                    newTeamIPMap.put(newID, ip);
                                    newIPTeamMap.put(ip, newID);
                                }
                                String name = Globals.getInstance().mTeamPlayerNameMap.get(oldID);
                                if (name != null) newNameMap.put(newID, name);
                                Globals.PlayerSettings set = Globals.getInstance().mPlayerSettings.get(oldID);
                                if (set != null) newSettingsMap.put(newID, set);
                                Globals.GPSData gps = Globals.getInstance().mGPSData.get(oldID);
                                if (gps != null) {
                                    gps.team = newID;
                                    gps.hasUpdate = true;
                                    newGPSMap.put(newID, gps);
                                }

                                client.sendTCPMessage(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERREPLY + newID);
                            }
                        } else {
                            Set<Byte> usedIDs = new HashSet<>();
                            int numTeams = (gameMode == Globals.GAME_MODE_4TEAMS) ? 4 : 2;
                            int[] teamPointers = new int[numTeams + 1];
                            int[] teamLimits = new int[numTeams + 1];
                            int[] teamCounts = new int[numTeams + 1];

                            for (int t = 1; t <= numTeams; t++) {
                                teamPointers[t] = Globals.getInstance().getTeamStartID(t);
                                teamLimits[t] = Globals.getInstance().getTeamEndID(t);
                                teamCounts[t] = 0;
                            }

                            if (!mIsDedicated && Globals.getInstance().mPlayerID != 0) {
                                byte oldHostID = Globals.getInstance().mPlayerID;
                                int hostTeam = findBestTeamForRebalance(numTeams, teamPointers, teamLimits, teamCounts);
                                byte newHostID = 0;
                                if (hostTeam != -1) {
                                    newHostID = (byte) teamPointers[hostTeam];
                                    teamPointers[hostTeam]++;
                                    teamCounts[hostTeam]++;
                                } else {
                                    for (byte i = 1; i <= maxPlayers; i++) {
                                        if (!usedIDs.contains(i)) {
                                            newHostID = i;
                                            hostTeam = Globals.getInstance().calcNetworkTeam(newHostID);
                                            break;
                                        }
                                    }
                                }
                                if (newHostID != 0) {
                                    usedIDs.add(newHostID);
                                    Globals.getInstance().mPlayerID = newHostID;
                                    String hostName = Globals.getInstance().mTeamPlayerNameMap.get(oldHostID);
                                    if (hostName == null) hostName = Globals.getInstance().mPlayerName;
                                    if (hostName != null) newNameMap.put(newHostID, hostName);
                                    InetAddress hostIP = Globals.getInstance().mTeamIPMap.get(oldHostID);
                                    if (hostIP == null) {
                                        try { hostIP = InetAddress.getByName(Globals.getIPAddressStr()); } catch (Exception e) {}
                                    }
                                    if (hostIP != null) {
                                        newTeamIPMap.put(newHostID, hostIP);
                                        newIPTeamMap.put(hostIP, newHostID);
                                    }
                                    Globals.PlayerSettings set = Globals.getInstance().mPlayerSettings.get(oldHostID);
                                    if (set != null) newSettingsMap.put(newHostID, set);
                                    Globals.GPSData gps = Globals.getInstance().mGPSData.get(oldHostID);
                                    if (gps != null) {
                                        gps.team = hostTeam;
                                        gps.hasUpdate = true;
                                        newGPSMap.put(newHostID, gps);
                                    }

                                    Intent hostReplyIntent = new Intent(NetMsg.NETMSG_SERVERREPLY);
                                    hostReplyIntent.putExtra(UDPListenerService.INTENT_PLAYERID, newHostID);
                                    sendBroadcast(hostReplyIntent);
                                }
                            }

                            for (ClientData client : clients) {
                                byte oldID = client.mPlayerID;
                                int targetTeam = findBestTeamForRebalance(numTeams, teamPointers, teamLimits, teamCounts);
                                byte newID = 0;
                                if (targetTeam != -1) {
                                    newID = (byte) teamPointers[targetTeam];
                                    teamPointers[targetTeam]++;
                                    teamCounts[targetTeam]++;
                                } else {
                                    for (byte i = 1; i <= maxPlayers; i++) {
                                        if (!usedIDs.contains(i)) {
                                            newID = i;
                                            targetTeam = Globals.getInstance().calcNetworkTeam(newID);
                                            break;
                                        }
                                    }
                                }

                                if (newID == 0) {
                                    Log.w(TAG, "Server full during rebalance! Closing client " + client.clientID);
                                    client.close();
                                    continue;
                                }

                                usedIDs.add(newID);

                                client.clientID = (int) newID;
                                client.mPlayerID = newID;
                                client.mNetworkTeam = targetTeam;
                                newClientMap.put((int) newID, client);

                                InetAddress ip = Globals.getInstance().mTeamIPMap.get(oldID);
                                if (ip != null) {
                                    newTeamIPMap.put(newID, ip);
                                    newIPTeamMap.put(ip, newID);
                                }
                                String name = Globals.getInstance().mTeamPlayerNameMap.get(oldID);
                                if (name != null) newNameMap.put(newID, name);
                                Globals.PlayerSettings set = Globals.getInstance().mPlayerSettings.get(oldID);
                                if (set != null) newSettingsMap.put(newID, set);
                                Globals.GPSData gps = Globals.getInstance().mGPSData.get(oldID);
                                if (gps != null) {
                                    gps.team = targetTeam;
                                    gps.hasUpdate = true;
                                    newGPSMap.put(newID, gps);
                                }

                                client.sendTCPMessage(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERREPLY + newID);
                            }
                        }

                        mClientData = newClientMap;
                        Globals.getInstance().mTeamIPMap = newTeamIPMap;
                        Globals.getInstance().mIPTeamMap = newIPTeamMap;
                        Globals.getInstance().mTeamPlayerNameMap = newNameMap;
                        Globals.getInstance().mPlayerSettings = newSettingsMap;
                        Globals.getInstance().mGPSData = newGPSMap;
                    } finally {
                        Globals.getInstance().mGPSDataSemaphore.release();
                        Globals.getInstance().mPlayerSettingsSemaphore.release();
                        Globals.getInstance().mTeamPlayerNameSemaphore.release();
                        Globals.getInstance().mIPTeamMapSemaphore.release();
                        Globals.getInstance().mTeamIPMapSemaphore.release();
                        mClientDataSemaphore.release();
                    }

                    sendPlayerData(SEND_ALL);
                    sendAllGameInfo(SEND_ALL);
                    sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        thread.start();
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
            if (mIsDedicated) {
                game.put(JSON_DEDICATED, true);
            }
            if (Globals.getInstance().mUseGPS)
                game.put(JSON_USEGPS, Globals.getInstance().mGPSMode);
            game.put(JSON_ALLOWPLAYERSETTINGS, Globals.getInstance().mAllowPlayerSettings);
            game.put(JSON_PLAYERSETTINGS, getPlayerSettings(id, false));
            game.put(JSON_ONLY_SERVER_SETTINGS, Globals.getInstance().mOnlyServerSettings);
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
                    sendTCPMessageAll(messageToSend, false);
                }

                if (keepListening && Globals.getInstance().mUseGPS) {
                    mGPSHandler.postDelayed(mGPSRunnable, GPS_UPDATE_INTERVAL);
                } else {
                    mGPSRunning = false;
                }
            }
        }, GPS_UPDATE_INTERVAL);
    }

    public String getPlayerDataJson() {
        try {
            JSONArray players = new JSONArray();
            if (Globals.getInstance().mTeamIPMap != null) {
                for (Map.Entry<Byte, InetAddress> entry : Globals.getInstance().mTeamIPMap.entrySet()) {
                    JSONObject player = new JSONObject();
                    player.put(JSON_PLAYERNAME, Globals.getInstance().getPlayerName(entry.getKey()));
                    player.put(JSON_PLAYERID, entry.getKey());
                    player.put(JSON_PLAYERIP, entry.getValue().toString());
                    ScoreData scoreData = getScore(entry.getKey());
                    if (scoreData != null) {
                        player.put(JSON_PLAYERPOINTS, scoreData.points);
                        player.put(JSON_PLAYERELIMINATED, scoreData.eliminated);
                    } else {
                        player.put(JSON_PLAYERPOINTS, 0);
                        player.put(JSON_PLAYERELIMINATED, 0);
                    }
                    players.put(player);
                }
            }
            if (!mIsDedicated) {
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERNAME, Globals.getInstance().mPlayerName);
                player.put(JSON_PLAYERID, Globals.getInstance().mPlayerID);
                player.put(JSON_PLAYERIP, Globals.getIPAddressStr());
                ScoreData hostScore = getScore(Globals.getInstance().mPlayerID);
                if (hostScore != null) {
                    player.put(JSON_PLAYERPOINTS, hostScore.points);
                    player.put(JSON_PLAYERELIMINATED, hostScore.eliminated);
                } else {
                    player.put(JSON_PLAYERPOINTS, 0);
                    player.put(JSON_PLAYERELIMINATED, 0);
                }
                players.put(player);
            }
            JSONObject game = new JSONObject();
            game.put(JSON_PLAYERDATA, players);
            return game.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
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
                if (scoreData != null) {
                    player.put(JSON_PLAYERPOINTS, scoreData.points);
                    player.put(JSON_PLAYERELIMINATED, scoreData.eliminated);
                } else {
                    player.put(JSON_PLAYERPOINTS, 0);
                    player.put(JSON_PLAYERELIMINATED, 0);
                }
                players.put(player);
            }
            if (!mIsDedicated) {
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERNAME, Globals.getInstance().mPlayerName);
                player.put(JSON_PLAYERID, Globals.getInstance().mPlayerID);
                player.put(JSON_PLAYERIP, Globals.getIPAddressStr());
                ScoreData hostScore = getScore(Globals.getInstance().mPlayerID);
                if (hostScore != null) {
                    player.put(JSON_PLAYERPOINTS, hostScore.points);
                    player.put(JSON_PLAYERELIMINATED, hostScore.eliminated);
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
                sendTCPMessageID(message, (byte)playerID, false);
            else {
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    entry.getValue().sendTCPMessage(message);
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
            Globals.PlayerSettings hostSettings = Globals.getInstance().mPlayerSettings.get(Globals.getInstance().mPlayerID);
            if (hostSettings != null) {
                Globals.getInstance().mServerRecoilSetting = hostSettings.recoilSetting;
            }
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
            mClientData = new ConcurrentHashMap<>();
        else
            mClientData.clear();
        if (Globals.getInstance().mGPSData == null)
            Globals.getInstance().mGPSData = new ConcurrentHashMap<>();
        else
            Globals.getInstance().mGPSData.clear();
        Globals.ClearGrenadePairings(true);
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
                        try {
                            mClientData.put(clientID, client);
                        } finally {
                            mClientDataSemaphore.release();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
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

    public void setHostScore(int points, int eliminated) {
        mHostPoints.set(points);
        mHostEliminated.set(eliminated);
    }

    public ScoreData getScore(byte playerID) {
        if (!mIsDedicated && playerID == Globals.getInstance().mPlayerID) {
            ScoreData scoreData = new ScoreData();
            scoreData.points = mHostPoints.get();
            scoreData.eliminated = mHostEliminated.get();
            scoreData.isConnected = true;
            return scoreData;
        }
        int clientID = clientIDFromPlayerID(playerID);
        if (clientID < 0)
            return null;
        ClientData client = mClientData.get(clientID);
        if (client == null)
            return null;
        ScoreData scoreData = new ScoreData();
        scoreData.points = client.points.get();
        scoreData.eliminated = client.eliminated.get();
        synchronized (client.mOutLock) {
            scoreData.isConnected = client.out != null;
        }
        return scoreData;
    }

    public class ScoreData {
        int points = 0;
        int eliminated = 0;
        boolean isConnected = false;
    }

    private class ClientData {
        private final Object mOutLock = new Object();
        private Socket clientSocket = null;
        private int clientID = -1;
        private byte mPlayerID = 0;
        private int mNetworkTeam = 0;
        private final AtomicInteger noReadCount = new AtomicInteger(0);
        private InputStream is = null;
        private OutputStream os = null;
        private DataInputStream in = null;
        private DataOutputStream out = null;
        private Queue<String> messageQueue;
        private final AtomicInteger points = new AtomicInteger(0);
        private final AtomicInteger eliminated = new AtomicInteger(0);

        public void initialize(Socket s, int cID) {
            synchronized (mOutLock) {
                clientSocket = s;
                clientID = cID;
                noReadCount.set(0);
                if (messageQueue == null)
                    messageQueue = new ConcurrentLinkedQueue<>();
                try {
                    is = clientSocket.getInputStream();
                    in = new DataInputStream(is);
                    os = clientSocket.getOutputStream();
                    out = new DataOutputStream(os);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        public void sendTCPMessage(final String message) { sendTCPMessage(message, false); }

        public void sendTCPMessage(final String message, boolean queueFailed) {
            synchronized (mOutLock) {
                if (out == null) {
                    if (queueFailed && messageQueue != null) {
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
                    e.printStackTrace();
                    if (queueFailed && messageQueue != null) {
                        Log.e(TAG, "queuing: " + message);
                        messageQueue.add(message);
                    }
                }
            }
        }

        public void close() {
            synchronized (mOutLock) {
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
        }

        public void rejoin(Socket s) {
            close();
            initialize(s, clientID);
            if (messageQueue != null) {
                String queuedMsg;
                while ((queuedMsg = messageQueue.poll()) != null) {
                    Log.e(TAG, "sending queued: " + queuedMsg);
                    sendTCPMessage(queuedMsg);
                }
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
                List<ClientData> clients = new ArrayList<>();
                try {
                    mClientDataSemaphore.acquire();
                    try {
                        if (mClientData != null) {
                            clients.addAll(mClientData.values());
                        }
                    } finally {
                        mClientDataSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                long startTime = System.currentTimeMillis();
                for (ClientData client : clients) {
                    try {
                        if (client.in != null && client.in.available() > 0) {
                            client.noReadCount.set(0);
                            String message = client.in.readUTF();
                            if (!message.equals(TcpClient.TCP_CLIENT_PONG)) {
                                Log.i(TAG, "received: '" + message + "' from " + client.clientID);
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
                                            removeClient(client, client.clientID, true);
                                            if (mClientData.size() <= 1 && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                                                endGame();
                                            break;
                                        } else if (message.startsWith(NetMsg.NETMSG_ELIMINATED)) {
                                            // A player was eliminated
                                            client.eliminated.incrementAndGet();
                                            message = message.substring(NetMsg.NETMSG_ELIMINATED.length());
                                            Byte id = (byte) (int) Integer.parseInt(message);
                                            int clientID = clientIDFromPlayerID(id);
                                            int shooterScore = 0;
                                            if (!mIsDedicated && id == Globals.getInstance().mPlayerID) {
                                                shooterScore = mHostPoints.incrementAndGet();
                                            } else if (clientID >= 0 && mClientData.get(clientID) != null) {
                                                shooterScore = mClientData.get(clientID).points.incrementAndGet();
                                            }
                                            sendTCPMessageID(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ELIMINATED + client.mPlayerID, id, false, true);
                                            if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                                                // Send a message to all teammates about the score increase
                                                sendTCPMessageTeam(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_TEAMELIMINATED, (byte) id, false, false, true);
                                            }
                                            sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                                            int scoreLimit = ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_SCORE) != 0 ? Globals.getInstance().mScoreLimit : 0);
                                            if (scoreLimit != 0) {
                                                if (Globals.getInstance().mGameMode == Globals.GAME_MODE_FFA) {
                                                    if (shooterScore >= scoreLimit)
                                                        endGame();
                                                } else {
                                                    int teamPoints = 0;
                                                    int team = Globals.getInstance().calcNetworkTeam(id);
                                                    for (byte x = 0; x <= Globals.MAX_PLAYER_ID; x++) {
                                                        if (Globals.getInstance().calcNetworkTeam(x) == team && getScore(x) != null)
                                                            teamPoints += getScore(x).points;
                                                    }
                                                    if (teamPoints >= scoreLimit)
                                                        endGame();
                                                }
                                            }
                                        } else if (message.equals(NetMsg.NETMSG_SHOTFIRED)) {
                                            sendTCPMessageAll(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SHOTFIRED);
                                        } else if (message.startsWith(NetMsg.NETMSG_HIT)) {
                                            message = message.substring(NetMsg.NETMSG_HIT.length());
                                            try {
                                                byte shooterId = (byte) Integer.parseInt(message);
                                                byte targetId = client.mPlayerID;
                                                sendTCPMessageID(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_HIT + targetId, shooterId, false, true);
                                            } catch (Exception e) {
                                                e.printStackTrace();
                                            }
                                        } else if (message.startsWith(NetMsg.NETMSG_OUT)) {
                                            message = message.substring(NetMsg.NETMSG_OUT.length());
                                            try {
                                                byte shooterId = (byte) Integer.parseInt(message);
                                                byte targetId = client.mPlayerID;
                                                sendTCPMessageID(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_OUT + targetId, shooterId, false, true);
                                            } catch (Exception e) {
                                                e.printStackTrace();
                                            }
                                        } else if (message.equals(NetMsg.NETMSG_TEAMELIMINATED)) {
                                            sendTCPMessageTeam(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_TEAMELIMINATED, client.mPlayerID, false, false, true);
                                        } else if (message.equals(NetMsg.NETMSG_PLAYERDATAREQUEST)) {
                                            sendPlayerData(client.mPlayerID);
                                        } else if (message.equals(NetMsg.NETMSG_STARTGAME) || message.startsWith(NetMsg.NETMSG_ADMIN_STARTGAME)) {
                                            Log.d(TAG, "TcpServer received STARTGAME / ADMIN_STARTGAME command: '" + message + "'");
                                            startGame();
                                        } else if (message.equals(NetMsg.NETMSG_ENDGAME) || message.startsWith(NetMsg.NETMSG_ADMIN_ENDGAME)) {
                                            Log.d(TAG, "TcpServer received ENDGAME / ADMIN_ENDGAME command: '" + message + "'");
                                            if (mIsDedicated || Globals.getInstance().mOnlyServerSettings) {
                                                if (message.startsWith(NetMsg.NETMSG_ADMIN_ENDGAME)) {
                                                    endGame();
                                                } else {
                                                    // Regular ENDGAME from a client on a dedicated/restricted server is treated as client leaving
                                                    removeClient(client, client.clientID, true);
                                                    if (mClientData.size() <= 1 && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                                                        endGame();
                                                    break;
                                                }
                                            } else {
                                                endGame();
                                            }
                                        } else if (message.startsWith(NetMsg.NETMSG_ADMIN_SORTTEAMS)) {
                                            Log.d(TAG, "TcpServer received ADMIN_SORTTEAMS command: '" + message + "'");
                                            rebalanceAllPlayers(true);
                                        } else if (message.startsWith(NetMsg.NETMSG_ADMIN_GAMEMODE)) {
                                            Log.d(TAG, "TcpServer received ADMIN_GAMEMODE command: '" + message + "'");
                                            try {
                                                int mode = Integer.parseInt(message.substring(NetMsg.NETMSG_ADMIN_GAMEMODE.length()).trim());
                                                Globals.getInstance().mGameMode = mode;
                                                rebalanceAllPlayers(false);
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
                                            if (Globals.getInstance().mGameState == Globals.GAME_STATE_NONE) {
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
                                    client.sendTCPMessage(NetMsg.NETMSG_VERSIONERROR);
                                    break;
                                }
                            }
                        } else {
                            int count = client.noReadCount.incrementAndGet();
                            if (client.out != null && count >= 35) {
                                Log.d(TAG, "no reply so dropping client " + client.clientID);
                                removeClient(client, client.clientID, false);
                                break;
                            } else if (count == 10 || count == 20) {
                                client.sendTCPMessage(TCP_SERVER_PING);
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        removeClient(client, client.clientID, false);
                        break;
                    }
                }
                long sleepTime = TCP_READ_WAIT_MS - (System.currentTimeMillis() - startTime);
                if (mIsDedicated)
                    sleepTime = TCP_DEDICATED_READ_WAIT_MS - (System.currentTimeMillis() - startTime);
                //Log.d(TAG, "Took " + (System.currentTimeMillis() - startTime) + "ms to service " + mClientData.size() + " clients");
                sleep(sleepTime);
            }
            try {
                mClientDataSemaphore.acquire();
                try {
                    for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                        entry.getValue().close();
                    }
                } finally {
                    mClientDataSemaphore.release();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
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

        private void parsePlayerInfo(String message, ClientData client) {
            JSONObject player;
            try {
                player = new JSONObject(message);
            } catch (JSONException e) {
                e.printStackTrace();
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
                    settings.allowShotModeAuto = player.getBoolean(JSON_SHOT_MODE_AUTO);
                    settings.allowShotModeBurst3 = player.getBoolean(JSON_SHOT_MODE_BURST3);
                    settings.allowShotModeSingle = player.getBoolean(JSON_SHOT_MODE_SINGLE);
                    settings.firingMode = player.getInt(JSON_FIRING_MODE);
                    if (player.has(JSON_RECOIL_SETTING)) {
                        settings.recoilSetting = player.getInt(JSON_RECOIL_SETTING);
                    }
                    if (player.has(JSON_IS_ADMIN)) {
                        settings.isAdmin = player.getBoolean(JSON_IS_ADMIN);
                    }
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
                Set<String> usedNames = new HashSet<>();
                for (Map.Entry<Byte, String> entry : Globals.getInstance().mTeamPlayerNameMap.entrySet()) {
                    if (entry.getKey() != client.mPlayerID) {
                        usedNames.add(entry.getValue());
                    }
                }
                if (playerName.isEmpty() || playerName.equalsIgnoreCase("Player") || ProfanityFilter.containsProfanity(playerName) || usedNames.contains(playerName)) {
                    playerName = NameGenerator.getRandomUniqueName(usedNames);
                }
                Globals.getInstance().mTeamPlayerNameMap.put(client.mPlayerID, playerName);
                Globals.getInstance().mTeamPlayerNameSemaphore.release();

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

            Set<Byte> occupiedIDs = new HashSet<>();
            if (!mIsDedicated && Globals.getInstance().mPlayerID != 0) {
                occupiedIDs.add(Globals.getInstance().mPlayerID);
            }
            if (Globals.getInstance().mTeamIPMap != null) {
                occupiedIDs.addAll(Globals.getInstance().mTeamIPMap.keySet());
            }
            if (mClientData != null) {
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    if (entry.getValue().clientID != client.clientID && entry.getValue().mPlayerID != 0) {
                        occupiedIDs.add(entry.getValue().mPlayerID);
                    }
                }
            }

            boolean isGameRunning = Globals.getInstance().mGameState != Globals.GAME_STATE_NONE;

            try {
                Globals.getmTeamIPMapSemaphore();
                Globals.getmIPTeamMapSemaphore();

                PlayerRepository.ActiveGamePlayerInfo activeInfo = null;
                if (isGameRunning && !blasterMac.isEmpty()) {
                    activeInfo = PlayerRepository.getInstance().getActiveGamePlayerByMac(blasterMac);
                }

                if (isGameRunning) {
                    if (activeInfo != null) {
                        byte existingPlayerId = activeInfo.playerId;
                        ClientData existingClient = null;
                        for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                            if (entry.getValue().mPlayerID == existingPlayerId) {
                                existingClient = entry.getValue();
                                break;
                            }
                        }
                        if (existingClient != null) {
                            Log.d(TAG, "rejoining client " + client.clientID + " to existing client " + existingClient.clientID + " for player ID " + existingPlayerId + " (MAC: " + blasterMac + ")");
                            existingClient.rejoin(client.clientSocket);
                            mClientData.remove(client.clientID);
                        } else {
                            Log.d(TAG, "restoring disconnected player ID " + existingPlayerId + " to new client socket " + client.clientID + " (MAC: " + blasterMac + ")");
                            client.mPlayerID = existingPlayerId;
                            client.mNetworkTeam = activeInfo.team;
                            existingClient = client;
                        }

                        InetAddress inetAddress = existingClient.clientSocket.getInetAddress();
                        Globals.getmTeamPlayerNameSemaphore();
                        playerName = activeInfo.originalName;
                        PlayerRepository.getInstance().addOrUpdatePlayer(existingPlayerId, inetAddress, playerName, blasterMac);
                        Globals.getInstance().mTeamPlayerNameSemaphore.release();

                        existingClient.sendTCPMessage(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERREPLY + existingPlayerId);
                        sendAllGameInfo(existingPlayerId);
                        sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
                        sendBroadcast(new Intent(NetMsg.NETMSG_JOIN));
                        return;
                    } else {
                        boolean allowJoin = GameSettingsRepository.getInstance().isAllowJoinInProgress();
                        if (!allowJoin) {
                            Log.d(TAG, "Rejecting client " + client.clientID + " (MAC: " + blasterMac + ") - Game in progress and allowJoin is false.");
                            client.sendTCPMessage(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ERROR + "Cannot join game in progress due to server settings.");
                            client.close();
                            mClientData.remove(client.clientID);
                            return;
                        }
                    }
                }

                if (id != 0 && occupiedIDs.contains(id)) {
                    Log.d(TAG, "Requested player ID " + id + " is already occupied in teamIPMap/host/clients; reassigning new auto-sorted ID.");
                    id = 0;
                }

                if (id == 0) {
                    id = Globals.findAutoSortPlayerID(occupiedIDs);
                    if (id == 0) {
                        client.close();
                        mClientData.remove(client.clientID);
                        return;
                    }
                }

                client.mPlayerID = id;
                client.mNetworkTeam = 1;
                if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                    client.mNetworkTeam = Globals.getInstance().calcNetworkTeam(id);
                }

                InetAddress inetAddress = client.clientSocket.getInetAddress();
                Globals.getmTeamPlayerNameSemaphore();
                Set<String> usedNames = new HashSet<>();
                for (Map.Entry<Byte, String> entry : Globals.getInstance().mTeamPlayerNameMap.entrySet()) {
                    if (entry.getKey() != client.mPlayerID) {
                        usedNames.add(entry.getValue());
                    }
                }
                if (playerName.isEmpty() || playerName.equalsIgnoreCase("Player") || ProfanityFilter.containsProfanity(playerName) || usedNames.contains(playerName)) {
                    playerName = NameGenerator.getRandomUniqueName(usedNames);
                }
                PlayerRepository.getInstance().addOrUpdatePlayer(client.mPlayerID, inetAddress, playerName, blasterMac);
                if (isGameRunning && !blasterMac.isEmpty()) {
                    PlayerRepository.getInstance().addActiveGamePlayer(blasterMac, client.mPlayerID, client.mNetworkTeam, playerName);
                }
                Globals.getInstance().mTeamPlayerNameSemaphore.release();
            } finally {
                Globals.getInstance().mIPTeamMapSemaphore.release();
                Globals.getInstance().mTeamIPMapSemaphore.release();
            }

            client.sendTCPMessage(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERREPLY + id);
            Log.e(TAG, "network team is " + client.mNetworkTeam);
            InetAddress inetAddress = client.clientSocket.getInetAddress();
            Log.d(TAG, "client " + client.clientID + " player '" + playerName + "' (" + client.mPlayerID + ") found at " + (inetAddress != null ? inetAddress.toString() : "null"));

            sendAllGameInfo(id);
            sendBroadcast(new Intent(NetMsg.NETMSG_JOIN));
        }

        private void removeClient(ClientData client, Integer clientID, boolean alwaysRemove) {
            if (client.mPlayerID != 0) {
                boolean isGameRunning = Globals.getInstance().mGameState != Globals.GAME_STATE_NONE;
                if (alwaysRemove || !isGameRunning) {
                    PlayerRepository.getInstance().removePlayer(client.mPlayerID, isGameRunning);
                    mGPSIntervalCount.set(SEND_ALL_GPS_INTERVAL); // Force a full GPS update when someone leaves
                    sendAllGameInfo(SEND_ALL);
                    sendBroadcast(new Intent(NetMsg.NETMSG_LEAVE));
                    client.close();
                    mClientData.remove(clientID);
                } else {
                    client.close(); // Keep client info in activeGameMacMap since game is underway
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
