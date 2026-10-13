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
import android.os.IBinder;
import android.util.Log;

import com.simplecoil.simplecoil.data.repository.GameStateRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;
import com.simplecoil.simplecoil.network.discovery.ServerDiscoveryManager;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Service managing LAN server discovery via ServerDiscoveryManager.
 * Note: P2P gameplay messaging over UDP has been completely removed in favor of TCP Client-Server architecture.
 */
public class UDPListenerService extends Service {
    private static final String TAG = "UDPSvc";

    public static final String INTENT_PLAYERID = "playerid";
    public static final String INTENT_MESSAGE = "message";

    private ServerDiscoveryManager mDiscoveryManager;
    private final IBinder mBinder = new LocalBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        mDiscoveryManager = new ServerDiscoveryManager(this);
        Log.i(TAG, "UDP Service initialized with ServerDiscoveryManager");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public void createServer() {
        if (mDiscoveryManager != null) {
            mDiscoveryManager.startBroadcastingBeacon("SimpleCoil Dedicated Server");
        }
        InetAddress myIp = PlayerRepository.getIPAddress();
        if (myIp != null) {
            GameStateRepository.getInstance().setServerIP(myIp);
        }
        Intent intent = new Intent(NetMsg.NETMSG_SERVERCREATED);
        sendBroadcast(intent);
    }

    public void cancelServer() {
        if (mDiscoveryManager != null) {
            mDiscoveryManager.stopBroadcastingBeacon();
        }
    }

    public void joinServer() {
        startDiscovery();
    }

    public void joinServer(String serverIP) {
        if (serverIP == null || serverIP.trim().isEmpty()) {
            sendFailedJoin();
            return;
        }
        if (serverIP.startsWith("/")) {
            serverIP = serverIP.substring(1);
        }
        final String ipStr = serverIP;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InetAddress ipAddr = InetAddress.getByName(ipStr);
                    GameStateRepository.getInstance().setServerIP(ipAddr);
                    Intent intent = new Intent(NetMsg.NETMSG_SERVERREPLY);
                    sendBroadcast(intent);
                } catch (UnknownHostException e) {
                    Log.e(TAG, "Failed to resolve server IP: " + ipStr, e);
                    sendFailedJoin();
                }
            }
        }).start();
    }

    public void joinServer(InetAddress serverIP) {
        if (serverIP != null) {
            joinServer(serverIP.getHostAddress());
        } else {
            sendFailedJoin();
        }
    }

    public void startDiscovery() {
        if (mDiscoveryManager == null) return;
        mDiscoveryManager.startDiscovery(new ServerDiscoveryManager.ServerDiscoveryListener() {
            @Override
            public void onServerDiscovered(String serverIP, String serverName) {
                try {
                    GameStateRepository.getInstance().setServerIP(InetAddress.getByName(serverIP));
                    mDiscoveryManager.stopDiscovery();
                    Intent intent = new Intent(NetMsg.NETMSG_SERVERREPLY);
                    sendBroadcast(intent);
                } catch (UnknownHostException e) {
                    Log.e(TAG, "Invalid discovered server IP: " + serverIP, e);
                    sendFailedJoin();
                }
            }

            @Override
            public void onDiscoveryFailed(String errorReason) {
                Log.e(TAG, "Discovery failed: " + errorReason);
                sendFailedJoin();
            }
        });
    }

    public void stopListen() {
        if (mDiscoveryManager != null) {
            mDiscoveryManager.stopDiscovery();
            mDiscoveryManager.stopBroadcastingBeacon();
        }
    }

    public void endScanning() {
        stopListen();
    }

    public void allowJoin(boolean allowed) {
        if (allowed) {
            if (mDiscoveryManager != null) {
                mDiscoveryManager.startBroadcastingBeacon("SimpleCoil Dedicated Server");
            }
        } else {
            if (mDiscoveryManager != null) {
                mDiscoveryManager.stopBroadcastingBeacon();
            }
        }
    }

    public void startGame() {
        // Disallow discovery joins during active game
        allowJoin(false);
    }

    public void endGame() {
        // Allow discovery after game end
        allowJoin(true);
    }

    private void sendFailedJoin() {
        Intent intent = new Intent(NetMsg.NETMSG_FAILEDTOJOIN);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        stopListen();
        super.onDestroy();
    }

    public class LocalBinder extends Binder {
        public UDPListenerService getService() {
            return UDPListenerService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void sendBroadcast(Intent intent) {
        if (intent != null && intent.getPackage() == null) {
            intent.setPackage(getPackageName());
        }
        super.sendBroadcast(intent);
    }
}
