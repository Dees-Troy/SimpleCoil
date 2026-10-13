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

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;
import com.simplecoil.simplecoil.network.server.SessionPhase;

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
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TcpClient extends Service {
    private static final String TAG = "TCPClient";

    public static final String TCP_CLIENT_PONG = "pong";
    private static final int MAX_REJOIN_TRIES = 3;
    private static final int CONNECTION_TIMEOUT_MS = 1000;

    private static volatile boolean keepListening = false;
    private static volatile boolean isListening = false;

    /** The server's session phase and game id, from the latest game info. */
    private volatile SessionPhase mSessionPhase = SessionPhase.LOBBY;
    private volatile long mGameId = 0;

    private final Object mOutLock = new Object();
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

    private static final int NOTIFICATION_ID = 1338;
    private static final String CHANNEL_ID = "TcpClientServiceChannel";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        ContextCompat.registerReceiver(this, mGPSUpdateReceiver, new IntentFilter(NetMsg.NETMSG_GPSLOCUPDATE), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "SimpleCoil Network Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private Notification createPersistentNotification(String contentText) {
        Intent notificationIntent = new Intent(this, FullscreenActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SimpleCoil Game Network")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    /** Shows the network notification while the client is connected or rejoining. */
    public void startAsForeground(String statusText) {
        Notification notification = createPersistentNotification(statusText);
        try {
            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            // Android 12+ refuses this while the app is in the background; play on without it.
            Log.w(TAG, "Could not start foreground: " + e.getMessage());
        }
    }

    private void stopAsForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mGPSUpdateReceiver);
    }

    @Override
    public void sendBroadcast(Intent intent) {
        if (intent != null && intent.getPackage() == null) {
            intent.setPackage(getPackageName());
        }
        super.sendBroadcast(intent);
    }

    public void sendTCPMessage(final String message) {
        sendTCPMessage(message, false);
    }

    public void sendTCPMessage(final String message, final boolean queueMessage) {
        synchronized (mOutLock) {
            if (out == null) {
                Log.d(TAG, "Writer is null");
                if (queueMessage && messageQueue != null) {
                    Log.e(TAG, "queuing: " + message);
                    messageQueue.add(message);
                }
                return;
            }
        }
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                synchronized (mOutLock) {
                    if (out == null) {
                        if (queueMessage && messageQueue != null) {
                            Log.e(TAG, "queuing: " + message);
                            messageQueue.add(message);
                        }
                        return;
                    }
                    try {
                        out.writeUTF(message);
                        out.flush();
                        if (!message.equals(TCP_CLIENT_PONG))
                            Log.i(TAG, "sent: " + message);
                    } catch (IOException e) {
                        e.printStackTrace();
                        if (queueMessage && messageQueue != null) {
                            Log.e(TAG, "queuing: " + message);
                            messageQueue.add(message);
                        }
                    }
                }
            }
        });
        sendThread.start();
    }

    synchronized void startTcpClient() {
        if (keepListening) {
            Log.d(TAG, "Client is already listening");
            return;
        }
        if (isListening) {
            Log.e(TAG, "Please wait for client to stop listening");
            return;
        }
        keepListening = true;
        isListening = true;
        startAsForeground("SimpleCoil Network Connected");
        new Thread(new Runnable() {
            public void run() {
                runTcpClient();
            }
        }).start();
    }

    private void runTcpClient() {
        Globals.getmGPSDataSemaphore();
        try {
            if (Globals.getInstance().mGPSData == null)
                Globals.getInstance().mGPSData = new ConcurrentHashMap<>();
            else
                Globals.getInstance().mGPSData.clear();
        } finally {
            Globals.getInstance().mGPSDataSemaphore.release();
        }
        if (messageQueue == null) {
            messageQueue = new ConcurrentLinkedQueue<>();
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
                synchronized (mOutLock) {
                    out = new DataOutputStream(new BufferedOutputStream(os));
                }
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
                                } else if (message.startsWith(NetMsg.NETMSG_HIT)) {
                                    message = message.substring(NetMsg.NETMSG_HIT.length());
                                    Intent intent = new Intent(NetMsg.NETMSG_HIT);
                                    if (!message.isEmpty()) {
                                        try {
                                            byte id = (byte) Integer.parseInt(message);
                                            intent.putExtra(UDPListenerService.INTENT_PLAYERID, id);
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                    }
                                    sendBroadcast(intent);
                                } else if (message.startsWith(NetMsg.NETMSG_OUT)) {
                                    message = message.substring(NetMsg.NETMSG_OUT.length());
                                    Intent intent = new Intent(NetMsg.NETMSG_OUT);
                                    if (!message.isEmpty()) {
                                        try {
                                            byte id = (byte) Integer.parseInt(message);
                                            intent.putExtra(UDPListenerService.INTENT_PLAYERID, id);
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                    }
                                    sendBroadcast(intent);
                                } else if (message.equals(NetMsg.NETMSG_SHOTFIRED)) {
                                    sendBroadcast(new Intent(NetMsg.NETMSG_SHOTFIRED));
                                } else if (message.startsWith(NetMsg.NETMSG_SERVERREPLY)) {
                                    Intent intent = new Intent(NetMsg.NETMSG_SERVERREPLY);
                                    String assignedIDStr = message.substring(NetMsg.NETMSG_SERVERREPLY.length());
                                    if (!assignedIDStr.isEmpty()) {
                                        try {
                                            byte assignedID = (byte) Integer.parseInt(assignedIDStr);
                                            Globals.getInstance().mPlayerID = assignedID;
                                            intent.putExtra(UDPListenerService.INTENT_PLAYERID, assignedID);
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                    }
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
                                } else if (message.startsWith(NetMsg.NETMSG_ERROR)) {
                                    Intent intent = new Intent(NetMsg.NETMSG_ERROR);
                                    intent.putExtra(UDPListenerService.INTENT_MESSAGE, message.substring(NetMsg.NETMSG_ERROR.length()));
                                    sendBroadcast(intent);
                                }
                            } else if (message.startsWith(NetMsg.NETMSG_VERSIONERROR)) {
                                sendBroadcast(new Intent(NetMsg.NETMSG_VERSIONERROR));
                                break;
                            } else {
                                Log.d(TAG, "unknown tcp message received");
                            }
                        }
                    } else {
                        sleep(TcpServer.TCP_DEDICATED_READ_WAIT_MS);
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
                synchronized (mOutLock) {
                    try { if (out != null) out.close(); } catch (Exception e) { /* ignored */ }
                    out = null;
                }
                try { if (s != null) s.close(); } catch (Exception e) { /* ignored */ }
                try { if (is != null) is.close(); } catch (Exception e) { /* ignored */ }
                try { if (in != null) in.close(); } catch (Exception e) { /* ignored */ }
                try { if (os != null) os.close(); } catch (Exception e) { /* ignored */ }
            }
        }
        if (retryCount == 0 && keepListening) {
            sendBroadcast(new Intent(NetMsg.NETMSG_SERVERCANCEL));
        }
        Log.d(TAG, "Client stopping");
        // Under the startTcpClient lock so a client started right after this keeps its notification.
        synchronized (this) {
            stopAsForeground();
            keepListening = false;
            isListening = false;
        }
    }

    public void sendPlayerNameChange() {
        try {
            JSONObject playerInfo = new JSONObject();
            playerInfo.put(TcpServer.JSON_PLAYERID, (int) Globals.getInstance().mPlayerID);
            playerInfo.put(TcpServer.JSON_PLAYERNAMECHANGE, Globals.getInstance().mPlayerName);
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + playerInfo.toString();
            sendTCPMessage(message);
            if (messageQueue != null) {
                String queuedMsg;
                while ((queuedMsg = messageQueue.poll()) != null) {
                    Log.e(TAG, "sending queued: " + queuedMsg);
                    sendTCPMessage(queuedMsg);
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void sendPlayerInfo(boolean rejoin) {
        try {
            JSONObject playerInfo = new JSONObject();
            playerInfo.put(TcpServer.JSON_PLAYERID, (int) Globals.getInstance().mPlayerID);
            playerInfo.put(TcpServer.JSON_PLAYERNAME, Globals.getInstance().mPlayerName);
            if (Globals.getInstance().mDeviceAddress != null && !Globals.getInstance().mDeviceAddress.isEmpty()) {
                playerInfo.put(TcpServer.JSON_BLASTER_MAC, Globals.getInstance().mDeviceAddress);
            }
            if (rejoin) {
                Log.d(TAG, "Attempting to rejoin server");
                playerInfo.put(TcpServer.JSON_REJOIN, true);
            }
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + playerInfo.toString();
            sendTCPMessage(message);
            if (messageQueue != null) {
                String queuedMsg;
                while ((queuedMsg = messageQueue.poll()) != null) {
                    Log.e(TAG, "sending queued: " + queuedMsg);
                    sendTCPMessage(queuedMsg);
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        if (Globals.getInstance().mPairedGrenadeID != 0)
            sendPlayerGrenade();
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
            playerSettings.put(TcpServer.JSON_RESPAWN_SHIELD_TIME, Globals.getInstance().mRespawnShieldTime);
            playerSettings.put(TcpServer.JSON_DAMAGE, Globals.getInstance().mDamage);
            if (Globals.getInstance().mOverrideLives)
                playerSettings.put(TcpServer.JSON_LIVESLIMIT, Globals.getInstance().mOverrideLivesVal);
            playerSettings.put(TcpServer.JSON_SHOT_MODE_SINGLE, Globals.getInstance().mAllowSingleShotMode);
            playerSettings.put(TcpServer.JSON_SHOT_MODE_BURST3, Globals.getInstance().mAllowBurst3ShotMode);
            playerSettings.put(TcpServer.JSON_SHOT_MODE_AUTO, Globals.getInstance().mAllowAutoShotMode);
            playerSettings.put(TcpServer.JSON_FIRING_MODE, Globals.getInstance().mCurrentFiringMode);
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + playerSettings.toString();
            sendTCPMessage(message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void sendPlayerGrenade() {
        try {
            JSONObject playerGrenade = new JSONObject();
            playerGrenade.put(TcpServer.JSON_PLAYERID, (int) Globals.getInstance().mPlayerID);
            playerGrenade.put(TcpServer.JSON_PAIRED_GRENADE_ID, (int) Globals.getInstance().mPairedGrenadeID);
            String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + playerGrenade.toString();
            sendTCPMessage(message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void stopTcpClient() {
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

    // sendBroadcast() is overridden above to make every broadcast explicit (setPackage), which lint can't see.
    @SuppressLint("UnsafeImplicitIntentLaunch")
    private void parseGameInfo(String message) {
        try {
            JSONObject game = new JSONObject(message);
            if (game.has(TcpServer.JSON_GRENADE_PAIRINGS)) {
                Globals.getmGrenadePairingsSemaphore();
                try {
                    Globals.ClearGrenadePairings(false);
                    JSONArray grenadePairings = game.getJSONArray(TcpServer.JSON_GRENADE_PAIRINGS);
                    for (int x = 0; x < grenadePairings.length(); x++) {
                        JSONObject grenadePairing = grenadePairings.getJSONObject(x);
                        PlayerRepository.getInstance().setGrenadePairing(
                                grenadePairing.getInt(TcpServer.JSON_PAIRED_GRENADE_ID),
                                grenadePairing.getInt(TcpServer.JSON_PLAYERID)
                        );
                    }
                } finally {
                    Globals.getInstance().mGrenadePairingsSemaphore.release();
                }
                return;
            }
            if (game.has(TcpServer.JSON_GPSUPDATE)) {
                JSONArray updates = game.getJSONArray(TcpServer.JSON_GPSUPDATE);
                Globals.getmGPSDataSemaphore();
                boolean fullUpdate = false;
                try {
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
                } finally {
                    Globals.getInstance().mGPSDataSemaphore.release();
                }
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
                try {
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
                        if (setting.has(TcpServer.JSON_RESPAWN_SHIELD_TIME)) {
                            playerSettings.respawnShieldTime = setting.getLong(TcpServer.JSON_RESPAWN_SHIELD_TIME);
                        }
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
                        playerSettings.firingMode = setting.getInt(TcpServer.JSON_FIRING_MODE);
                        if (setting.has(TcpServer.JSON_RECOIL_SETTING)) {
                            playerSettings.recoilSetting = setting.getInt(TcpServer.JSON_RECOIL_SETTING);
                        }
                        if (setting.has(TcpServer.JSON_IS_ADMIN)) {
                            playerSettings.isAdmin = setting.getBoolean(TcpServer.JSON_IS_ADMIN);
                        }
                        if (playerID == Globals.getInstance().mPlayerID) {
                            Globals.getInstance().mFullHealth = playerSettings.health;
                            Globals.getInstance().mFullReload = playerSettings.shots;
                            Globals.getInstance().mReloadTime = playerSettings.reloadTime;
                            Globals.getInstance().mReloadOnEmpty = playerSettings.reloadOnEmpty;
                            Globals.getInstance().mRespawnTime = playerSettings.spawnTime;
                            Globals.getInstance().mRespawnShieldTime = playerSettings.respawnShieldTime;
                            Globals.getInstance().mDamage = playerSettings.damage;
                            Globals.getInstance().mOverrideLives = playerSettings.overrideLives;
                            Globals.getInstance().mOverrideLivesVal = playerSettings.lives;
                            Globals.getInstance().mAllowSingleShotMode = playerSettings.allowShotModeSingle;
                            Globals.getInstance().mAllowBurst3ShotMode = playerSettings.allowShotModeBurst3;
                            Globals.getInstance().mAllowAutoShotMode = playerSettings.allowShotModeAuto;
                            Globals.getInstance().mCurrentFiringMode = playerSettings.firingMode;
                            Globals.getInstance().mServerRecoilSetting = playerSettings.recoilSetting;
                            Globals.getInstance().mIsAdmin = playerSettings.isAdmin;
                        }
                    }
                } finally {
                    Globals.getInstance().mPlayerSettingsSemaphore.release();
                }
                Globals.getInstance().mAllowPlayerSettings = game.getBoolean(TcpServer.JSON_ALLOWPLAYERSETTINGS);
                sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
            }
            if (game.has(TcpServer.JSON_PLAYERS)) {
                Globals.getmTeamIPMapSemaphore();
                Globals.getmIPTeamMapSemaphore();
                Globals.getmTeamPlayerNameSemaphore();
                try {
                    PlayerRepository.getInstance().clearAllPlayers();
                    Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_NONE;
                    JSONArray players = game.getJSONArray(TcpServer.JSON_PLAYERS);
                    for (int x = 0; x < players.length(); x++) {
                        JSONObject player = players.getJSONObject(x);
                        Byte playerID = (byte) player.getInt(TcpServer.JSON_PLAYERID);
                        String playerName = player.getString(TcpServer.JSON_PLAYERNAME);
                        if (playerID == Globals.getInstance().mPlayerID) {
                            if (!playerName.isEmpty() && !playerName.equals(Globals.getInstance().mPlayerName)) {
                                Globals.getInstance().mPlayerName = playerName;
                                PlayerRepository.getInstance().setPlayerName(playerName);
                            }
                        } else {
                            InetAddress playerIP = null;
                            String ip = player.getString(TcpServer.JSON_PLAYERIP);
                            if (ip.startsWith("/")) ip = ip.substring(1);
                            try {
                                playerIP = InetAddress.getByName(ip);
                            } catch (UnknownHostException e) {
                                e.printStackTrace();
                            }
                            if (playerIP != null) {
                                PlayerRepository.getInstance().addOrUpdatePlayer(playerID, playerIP, playerName);
                                Log.d(TAG, "player '" + playerName + "' (" + playerID + ") found at " + playerIP.toString());
                            }
                        }
                    }
                } finally {
                    Globals.getInstance().mTeamPlayerNameSemaphore.release();
                    Globals.getInstance().mIPTeamMapSemaphore.release();
                    Globals.getInstance().mTeamIPMapSemaphore.release();
                }
                JSONArray players = game.getJSONArray(TcpServer.JSON_PLAYERS);
                Log.d(TAG, "Found " + players.length() + " players");
                JSONObject limits = game.getJSONObject(TcpServer.JSON_LIMITS);
                int gameLimit = Globals.getInstance().mGameLimit;
                if (limits.has(TcpServer.JSON_TIMELIMIT)) {
                    gameLimit += Globals.GAME_LIMIT_TIME;
                    Globals.getInstance().mTimeLimit = limits.getInt(TcpServer.JSON_TIMELIMIT);
                }
                if (limits.has(TcpServer.JSON_LIVESLIMIT)) {
                    gameLimit += Globals.GAME_LIMIT_LIVES;
                    Globals.getInstance().mLivesLimit = limits.getInt(TcpServer.JSON_LIVESLIMIT);
                    Log.e(TAG, "tcpClient set lives limit to " + Globals.getInstance().mLivesLimit);
                }
                if (limits.has(TcpServer.JSON_SCORELIMIT)) {
                    gameLimit += Globals.GAME_LIMIT_SCORE;
                    Globals.getInstance().mScoreLimit = limits.getInt(TcpServer.JSON_SCORELIMIT);
                }
                Globals.getInstance().mGameLimit = gameLimit;
                Globals.getInstance().mGameMode = game.getInt(TcpServer.JSON_GAMEMODE);
                if (game.has(TcpServer.JSON_TEAMSIZES)) {
                    JSONArray teamSizesArr = game.getJSONArray(TcpServer.JSON_TEAMSIZES);
                    int[] sizes = new int[teamSizesArr.length()];
                    for (int i = 0; i < teamSizesArr.length(); i++) {
                        sizes[i] = teamSizesArr.getInt(i);
                    }
                    Globals.getInstance().setTeamSizes(sizes);
                }
                Globals.getInstance().mUseGPS = game.has(TcpServer.JSON_USEGPS);
                if (Globals.getInstance().mUseGPS) {
                    Globals.getInstance().mGPSMode = game.getInt(TcpServer.JSON_USEGPS);
                }
                Globals.getInstance().mOnlyServerSettings = game.getBoolean(TcpServer.JSON_ONLY_SERVER_SETTINGS);
                if (game.has(TcpServer.JSON_ALLOW_CHOOSE_TEAMS)) {
                    boolean allowChooseTeams = game.getBoolean(TcpServer.JSON_ALLOW_CHOOSE_TEAMS);
                    Globals.getInstance().mAllowChooseTeams = allowChooseTeams;
                    GameSettingsRepository.getInstance().setAllowChooseTeams(allowChooseTeams);
                }
                Intent intent = new Intent(NetMsg.NETMSG_LISTPLAYERS);
                if (game.has(TcpServer.JSON_PLAYERGAMEUPDATE)) {
                    intent.putExtra(NetMsg.INTENT_HASGAMEUPDATE, true);
                    JSONObject playerGameUpdate = game.getJSONObject(TcpServer.JSON_PLAYERGAMEUPDATE);
                    intent.putExtra(NetMsg.INTENT_SCORE, playerGameUpdate.getInt(TcpServer.JSON_PLAYERPOINTS));
                    intent.putExtra(NetMsg.INTENT_ELIMINATIONS, playerGameUpdate.getInt(TcpServer.JSON_PLAYERELIMINATED));
                    if (playerGameUpdate.has(TcpServer.JSON_TEAMPOINTS))
                        intent.putExtra(NetMsg.INTENT_TEAMSCORE, playerGameUpdate.getInt(TcpServer.JSON_TEAMPOINTS));
                    if (playerGameUpdate.has(TcpServer.JSON_TIMEREMAINING))
                        intent.putExtra(NetMsg.INTENT_TIMEREMAINING, playerGameUpdate.getLong(TcpServer.JSON_TIMEREMAINING));
                }
                if (game.has(TcpServer.JSON_GAMESTATE)) {
                    int gameState = game.getInt(TcpServer.JSON_GAMESTATE);
                    intent.putExtra(NetMsg.INTENT_GAMESTATE, gameState);
                }
                if (game.has(TcpServer.JSON_SESSIONPHASE)) {
                    mSessionPhase = SessionPhase.fromWire(game.getInt(TcpServer.JSON_SESSIONPHASE));
                    mGameId = game.optLong(TcpServer.JSON_GAMEID, 0);
                    intent.putExtra(NetMsg.INTENT_SESSIONPHASE, mSessionPhase.wireValue);
                    intent.putExtra(NetMsg.INTENT_GAMEID, mGameId);
                }
                sendBroadcast(intent);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public SessionPhase getSessionPhase() {
        return mSessionPhase;
    }

    public long getGameId() {
        return mGameId;
    }

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
