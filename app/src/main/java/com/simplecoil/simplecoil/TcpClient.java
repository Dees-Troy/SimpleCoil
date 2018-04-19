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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;

public class TcpClient extends Service {
    private static final String TAG = "TCPClient";

    public static final String TCP_CLIENT_PONG = "pong";
    private static final int MAX_REJOIN_TRIES = 3;
    private static final int CONNECTION_TIMEOUT_MS = 1000;

    private static volatile boolean keepListening = false;
    private static volatile boolean isListening = false;
    private static boolean mIsDedicatedServer = false;

    private DataOutputStream out = null;
    private Queue<String> messageQueue;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    public class LocalBinder extends Binder {
        TcpClient getService() {
            return TcpClient.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(mGPSUpdateReceiver, new IntentFilter(NetMsg.NETMSG_GPSLOCUPDATE));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mGPSUpdateReceiver);
    }

    public void sendTCPMessage(final String message) {
        sendTCPMessage(message, false);
    }

    public void sendTCPMessage(final String message, final boolean queueMessage) {
        if (out == null) {
            Log.d(TAG, "Writer is null");
            if (queueMessage) {
                Log.e(TAG, "queuing: " + message);
                messageQueue.add(message);
            }
            return;
        }
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                try {
                    out.writeUTF(message);
                    out.flush();
                    if (!message.equals(TCP_CLIENT_PONG))
                        Log.i(TAG, "sent: " + message);
                } catch (IOException e) {
                    //Log.e(TAG, "IO Error:", e);
                    e.printStackTrace();
                    if (queueMessage) {
                        Log.e(TAG, "queuing: " + message);
                        messageQueue.add(message);
                    }
                }
            }
        });
        sendThread.start();
    }

    void startTcpClient() {
        if (keepListening) {
            Log.d(TAG, "Client is already listening");
            return;
        }
        if (isListening) {
            Log.e(TAG, "Please wait for client to stop listening");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                runTcpClient();
            }
        }).start();
    }

    private void runTcpClient() {
        if (Globals.getInstance().mGPSData == null)
            Globals.getInstance().mGPSData = new HashMap<>();
        else
            Globals.getInstance().mGPSData.clear();
        if (messageQueue == null) {
            messageQueue = new LinkedList<>();
        } else {
            messageQueue.clear();
        }
        keepListening = true;
        isListening = true;
        Log.d(TAG, "Starting client...");
        Socket s = null;
        InputStream is = null;
        DataInputStream in = null;
        OutputStream os = null;
        int retryCount = MAX_REJOIN_TRIES;
        boolean rejoin = false;
        boolean wasConnected = false;
        while (retryCount > 0 && keepListening) {
            try {
                if (wasConnected) {
                    wasConnected = false;
                    sendBroadcast(new Intent(NetMsg.NETMSG_NETWORKDISCONNECTED));
                }
                if (Globals.getInstance().mGameState == Globals.GAME_STATE_NONE)
                    retryCount--;
                s = new Socket();
                s.connect(new InetSocketAddress(Globals.getInstance().mServerIP, TcpServer.TCP_SERVER_PORT), CONNECTION_TIMEOUT_MS);
                is = s.getInputStream();
                in = new DataInputStream(is);
                os = s.getOutputStream();
                out = new DataOutputStream(new BufferedOutputStream(os));
                sendPlayerInfo(rejoin);
                wasConnected = true;
                if (rejoin)
                    sendBroadcast(new Intent(NetMsg.NETMSG_NETWORKCONNECTED));
                rejoin = true;
                int noReadCount = 0;
                retryCount = MAX_REJOIN_TRIES;
                while (keepListening) {
                    if (in.available() > 0) {
                        noReadCount = 0;
                        String message = in.readUTF();
                        if (message.equals(TcpServer.TCP_SERVER_PING))
                            sendTCPMessage(TCP_CLIENT_PONG);
                        else {
                            Log.i(TAG, "received: '" + message + "'");
                            if (message.substring(TcpServer.TCPMESSAGE_PREFIX.length(), TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_JSON.length()).equals(TcpServer.TCPPREFIX_JSON)) {
                                message = message.substring(TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_JSON.length());
                                parseGameInfo(message);
                            } else if (message.substring(TcpServer.TCPMESSAGE_PREFIX.length(), TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_MESG.length()).equals(TcpServer.TCPPREFIX_MESG)) {
                                message = message.substring(TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_MESG.length());
                                if (message.startsWith(NetMsg.NETMSG_ELIMINATED)) {
                                    message = message.substring(NetMsg.NETMSG_ELIMINATED.length());
                                    Byte id = (byte) (int) Integer.parseInt(message);
                                    Intent intent = new Intent(NetMsg.NETMSG_ELIMINATED);
                                    intent.putExtra(UDPListenerService.INTENT_PLAYERID, id);
                                    sendBroadcast(intent);
                                } else if (message.equals(NetMsg.NETMSG_TEAMELIMINATED)) {
                                    sendBroadcast(new Intent(NetMsg.NETMSG_TEAMELIMINATED));
                                } else if (message.equals(NetMsg.NETMSG_ENDGAME)) {
                                    sendBroadcast(new Intent(NetMsg.NETMSG_ENDGAME));
                                } else if (message.equals(NetMsg.NETMSG_STARTGAME)) {
                                    sendBroadcast(new Intent(NetMsg.NETMSG_STARTGAME));
                                } else if (message.equals(NetMsg.NETMSG_SERVERCANCEL)) {
                                    sendBroadcast(new Intent(NetMsg.NETMSG_SERVERCANCEL));
                                    break;
                                }
                            } else if (message.startsWith(NetMsg.NETMSG_VERSIONERROR)) {
                                sendBroadcast(new Intent(NetMsg.NETMSG_VERSIONERROR));
                                break;
                            } else {
                                Log.d(TAG, "unknown tcp message received");
                            }
                        }
                    } else {
                        if (mIsDedicatedServer)
                            sleep(TcpServer.TCP_DEDICATED_READ_WAIT_MS);
                        else
                            sleep(TcpServer.TCP_READ_WAIT_MS);
                        noReadCount++;
                        if (noReadCount >= 35) {
                            Log.d(TAG, "no ping from server, disconnecting");
                            break;
                        }
                    }
                }
                s.close();
                is.close();
                in.close();
                os.close();
                out.close();
            } catch (UnknownHostException e) {
                e.printStackTrace();
            } catch (ConnectException e) {
                Log.d(TAG, "Server not running: " + e.getLocalizedMessage());
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try { if (s != null) s.close(); } catch (Exception e) { /* ignored */ }
                try { if (is != null) is.close(); } catch (Exception e) { /* ignored */ }
                try { if (in != null) in.close(); } catch (Exception e) { /* ignored */ }
                try { if (os != null) os.close(); } catch (Exception e) { /* ignored */ }
                try { if (out != null) out.close(); } catch (Exception e) { /* ignored */ }
            }
        }
        if (retryCount == 0 && keepListening) {
            sendBroadcast(new Intent(NetMsg.NETMSG_SERVERCANCEL));
        }
        Log.d(TAG, "Client stopping");
        keepListening = false;
        isListening = false;
    }

    private void sendPlayerInfo(boolean rejoin) {
        try {
            JSONObject playerInfo = new JSONObject();
            playerInfo.put(TcpServer.JSON_PLAYERID, (int) Globals.getInstance().mPlayerID);
            playerInfo.put(TcpServer.JSON_PLAYERNAME, Globals.getInstance().mPlayerName);
            if (rejoin) {
                Log.d(TAG, "Attempting to rejoin server");
                playerInfo.put(TcpServer.JSON_REJOIN, true);
            }
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + playerInfo.toString();
            sendTCPMessage(message);
            while (messageQueue.size() > 0) {
                Log.e(TAG, "sending queued: " + messageQueue.peek());
                sendTCPMessage(messageQueue.peek());
                messageQueue.remove();
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void sendPlayerSettings() {
        try {
            JSONObject playerSettings = new JSONObject();
            playerSettings.put(TcpServer.JSON_PLAYERSETTINGS, true);
            playerSettings.put(TcpServer.JSON_PLAYERID, (int) Globals.getInstance().mPlayerID);
            playerSettings.put(TcpServer.JSON_HEALTH, Globals.getInstance().mFullHealth);
            playerSettings.put(TcpServer.JSON_RELOAD_SHOTS, Globals.getInstance().mFullReload);
            playerSettings.put(TcpServer.JSON_RELOAD_TIME, Globals.getInstance().mReloadTime);
            playerSettings.put(TcpServer.JSON_RELOAD_ON_EMPTY, Globals.getInstance().mReloadOnEmpty);
            playerSettings.put(TcpServer.JSON_SPAWN_TIME, Globals.getInstance().mRespawnTime);
            playerSettings.put(TcpServer.JSON_DAMAGE, Globals.getInstance().mDamage);
            if (Globals.getInstance().mOverrideLives)
                playerSettings.put(TcpServer.JSON_LIVESLIMIT, Globals.getInstance().mOverrideLivesVal);
            playerSettings.put(TcpServer.JSON_SHOT_MODE_SINGLE, Globals.getInstance().mAllowSingleShotMode);
            playerSettings.put(TcpServer.JSON_SHOT_MODE_BURST3, Globals.getInstance().mAllowBurst3ShotMode);
            playerSettings.put(TcpServer.JSON_SHOT_MODE_AUTO, Globals.getInstance().mAllowAutoShotMode);
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + playerSettings.toString();
            sendTCPMessage(message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void stopTcpClient() {
        if (mIsDedicatedServer)
            sleep(75); // Make sure that the end game message gets sent before we close down the socket
        keepListening = false;
    }

    public void leaveServer() {
        if (keepListening) {
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_LEAVE;
            sendTCPMessage(message);
            stopTcpClient();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void parseGameInfo(String message) {
        boolean gotGPSSemaphore = false;
        boolean gotPlayerSemaphores = false;
        boolean gotPlayerSettingsSemaphore = false;
        try {
            JSONObject game = new JSONObject(message);
            if (game.has(TcpServer.JSON_GPSUPDATE)) {
                JSONArray updates = game.getJSONArray(TcpServer.JSON_GPSUPDATE);
                Globals.getmGPSDataSemaphore();
                gotGPSSemaphore = true;
                boolean fullUpdate = false;
                if (game.has(TcpServer.JSON_GPSFULLUPDATE)) {
                    Globals.getInstance().mGPSData.clear();
                    fullUpdate = true;
                }
                for (int x = 0; x < updates.length(); x++) {
                    JSONObject update = updates.getJSONObject(x);
                    Byte playerID = (byte) update.getInt(TcpServer.JSON_PLAYERID);
                    if (playerID != Globals.getInstance().mPlayerID) {
                        double longitude = update.getDouble(TcpServer.JSON_GPSLONGITUDE);
                        double latitude = update.getDouble(TcpServer.JSON_GPSLATITUDE);
                        Globals.GPSData gps = Globals.getInstance().mGPSData.get(playerID);
                        if (gps == null) {
                            gps = new Globals.GPSData();
                            gps.longitude = longitude;
                            gps.latitude = latitude;
                            gps.team = update.getInt(TcpServer.JSON_TEAM);
                            Globals.getInstance().mGPSData.put(playerID, gps);
                        } else {
                            gps.longitude = longitude;
                            gps.latitude = latitude;
                        }
                        gps.hasUpdate = true; // Anything that the server sends us is considered an update
                    }
                }
                gotGPSSemaphore = false;
                Globals.getInstance().mGPSDataSemaphore.release();
                Intent intent = new Intent(NetMsg.NETMSG_GPSDATAUPDATE);
                intent.putExtra(NetMsg.INTENT_FULLUPDATE, fullUpdate);
                sendBroadcast(intent);
                return;
            }
            if (game.has(TcpServer.JSON_PLAYERDATA)) {
                Intent intent = new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE);
                intent.putExtra(NetMsg.INTENT_PLAYERDATA, message);
                sendBroadcast(intent);
                return;
            }
            if (game.has(TcpServer.JSON_PLAYERSETTINGS)) {
                JSONArray settings = game.getJSONArray(TcpServer.JSON_PLAYERSETTINGS);
                Globals.getmPlayerSettingsSemaphore();
                gotPlayerSettingsSemaphore = true;
                for (byte x = 0; x < settings.length(); x++) {
                    JSONObject setting = settings.getJSONObject(x);
                    Byte playerID = (byte) setting.getInt(TcpServer.JSON_PLAYERID);
                    Globals.PlayerSettings playerSettings = Globals.getInstance().mPlayerSettings.get(playerID);
                    if (playerSettings == null) {
                        playerSettings = new Globals.PlayerSettings();
                        Globals.getInstance().mPlayerSettings.put(playerID, playerSettings);
                    }
                    playerSettings.health = setting.getInt(TcpServer.JSON_HEALTH);
                    playerSettings.shots = (byte)setting.getInt(TcpServer.JSON_RELOAD_SHOTS);
                    playerSettings.reloadTime = setting.getLong(TcpServer.JSON_RELOAD_TIME);
                    playerSettings.reloadOnEmpty = setting.getBoolean(TcpServer.JSON_RELOAD_ON_EMPTY);
                    playerSettings.spawnTime = setting.getLong(TcpServer.JSON_SPAWN_TIME);
                    playerSettings.damage = setting.getInt(TcpServer.JSON_DAMAGE);
                    if (setting.has(TcpServer.JSON_LIVESLIMIT)) {
                        playerSettings.overrideLives = true;
                        playerSettings.lives = setting.getInt(TcpServer.JSON_LIVESLIMIT);
                    } else {
                        playerSettings.overrideLives = false;
                        playerSettings.lives = 0;
                    }
                    playerSettings.allowShotModeSingle = setting.getBoolean(TcpServer.JSON_SHOT_MODE_SINGLE);
                    playerSettings.allowShotModeBurst3 = setting.getBoolean(TcpServer.JSON_SHOT_MODE_BURST3);
                    playerSettings.allowShotModeAuto = setting.getBoolean(TcpServer.JSON_SHOT_MODE_AUTO);
                    if (playerID == Globals.getInstance().mPlayerID) {
                        Globals.getInstance().mFullHealth = playerSettings.health;
                        Globals.getInstance().mFullReload = playerSettings.shots;
                        Globals.getInstance().mReloadTime = playerSettings.reloadTime;
                        Globals.getInstance().mReloadOnEmpty = playerSettings.reloadOnEmpty;
                        Globals.getInstance().mRespawnTime = playerSettings.spawnTime;
                        Globals.getInstance().mDamage = playerSettings.damage;
                        Globals.getInstance().mOverrideLives = playerSettings.overrideLives;
                        Globals.getInstance().mOverrideLivesVal = playerSettings.lives;
                        Globals.getInstance().mAllowSingleShotMode = playerSettings.allowShotModeSingle;
                        Globals.getInstance().mAllowBurst3ShotMode = playerSettings.allowShotModeBurst3;
                        Globals.getInstance().mAllowAutoShotMode = playerSettings.allowShotModeAuto;
                    }
                }
                Globals.getInstance().mPlayerSettingsSemaphore.release();
                Globals.getInstance().mAllowPlayerSettings = game.getBoolean(TcpServer.JSON_ALLOWPLAYERSETTINGS);
                gotPlayerSettingsSemaphore = false;
                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
                return;
            }
            Globals.getmTeamPlayerNameSemaphore();
            Globals.getmTeamIPMapSemaphore();
            Globals.getmIPTeamMapSemaphore();
            gotPlayerSemaphores = true;
            Globals.getInstance().mTeamIPMap.clear();
            Globals.getInstance().mIPTeamMap.clear();
            Globals.getInstance().mTeamPlayerNameMap.clear();
            Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_NONE;
            JSONArray players = game.getJSONArray(TcpServer.JSON_PLAYERS);
            for (int x = 0; x < players.length(); x++) {
                JSONObject player = players.getJSONObject(x);
                Byte playerID = (byte) player.getInt(TcpServer.JSON_PLAYERID);
                if (playerID != Globals.getInstance().mPlayerID) {
                    InetAddress playerIP = null;
                    String ip = player.getString(TcpServer.JSON_PLAYERIP);
                    if (ip.startsWith("/")) ip = ip.substring(1);
                    try {
                        playerIP = InetAddress.getByName(ip);
                    } catch (UnknownHostException e) {
                        e.printStackTrace();
                    }
                    if (playerIP != null) {
                        String playerName = player.getString(TcpServer.JSON_PLAYERNAME);
                        Globals.getInstance().mTeamIPMap.put(playerID, playerIP);
                        Globals.getInstance().mIPTeamMap.put(playerIP, playerID);
                        Globals.getInstance().mTeamPlayerNameMap.put(playerID, playerName);
                        Log.d(TAG, "player '" + playerName + "' (" + playerID + ") found at " + playerIP.toString());
                    }
                }
            }
            Globals.getInstance().mTeamIPMapSemaphore.release();
            Globals.getInstance().mIPTeamMapSemaphore.release();
            Globals.getInstance().mTeamPlayerNameSemaphore.release();
            Log.d(TAG, "Found " + players.length() + " players");
            JSONObject limits = game.getJSONObject(TcpServer.JSON_LIMITS);
            if (limits.has(TcpServer.JSON_TIMELIMIT)) {
                Globals.getInstance().mGameLimit += Globals.GAME_LIMIT_TIME;
                Globals.getInstance().mTimeLimit = limits.getInt(TcpServer.JSON_TIMELIMIT);
            }
            if (limits.has(TcpServer.JSON_LIVESLIMIT)) {
                Globals.getInstance().mGameLimit += Globals.GAME_LIMIT_LIVES;
                Globals.getInstance().mLivesLimit = limits.getInt(TcpServer.JSON_LIVESLIMIT);
            }
            if (limits.has(TcpServer.JSON_SCORELIMIT)) {
                Globals.getInstance().mGameLimit += Globals.GAME_LIMIT_SCORE;
                Globals.getInstance().mScoreLimit = limits.getInt(TcpServer.JSON_SCORELIMIT);
            }
            Globals.getInstance().mGameMode = game.getInt(TcpServer.JSON_GAMEMODE);
            mIsDedicatedServer = game.has(TcpServer.JSON_DEDICATED);
            if (mIsDedicatedServer) {
                int gameState = game.getInt(TcpServer.JSON_GAMESTATE);
                if (gameState == Globals.GAME_STATE_NONE && Globals.getInstance().mGameState != gameState) {
                    sendBroadcast(new Intent(NetMsg.NETMSG_ENDGAME));
                    return;
                } else if (gameState == Globals.GAME_STATE_RUNNING && Globals.getInstance().mGameState == Globals.GAME_STATE_NONE) {
                    sendBroadcast(new Intent(NetMsg.NETMSG_STARTGAME));
                }
            }
            Globals.getInstance().mUseGPS = game.has(TcpServer.JSON_USEGPS);
            if (Globals.getInstance().mUseGPS) {
                Globals.getInstance().mGPSMode = game.getInt(TcpServer.JSON_USEGPS);
            }
            sendBroadcast(new Intent(NetMsg.NETMSG_LISTPLAYERS));
        } catch (JSONException e) {
            e.printStackTrace();
            if (gotPlayerSemaphores) {
                Globals.getInstance().mTeamIPMapSemaphore.release();
                Globals.getInstance().mIPTeamMapSemaphore.release();
                Globals.getInstance().mTeamPlayerNameSemaphore.release();
            }
            if (gotGPSSemaphore) Globals.getInstance().mGPSDataSemaphore.release();
            if (gotPlayerSettingsSemaphore) Globals.getInstance().mPlayerSettingsSemaphore.release();
        }
    }

    public boolean isDedicatedServer() { return mIsDedicatedServer; }

    private final BroadcastReceiver mGPSUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.e(TAG, action);
            if (NetMsg.NETMSG_GPSLOCUPDATE.equals(action)) {
                JSONObject gpsUpdate = new JSONObject();
                try {
                    gpsUpdate.put(TcpServer.JSON_GPSLONGITUDE, intent.getDoubleExtra(NetMsg.INTENT_LONGITUDE, 0));
                    gpsUpdate.put(TcpServer.JSON_GPSLATITUDE, intent.getDoubleExtra(NetMsg.INTENT_LATITUDE, 0));
                    sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + gpsUpdate.toString());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
    };
}
