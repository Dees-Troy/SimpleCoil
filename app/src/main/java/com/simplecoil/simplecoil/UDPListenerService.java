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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import javax.microedition.khronos.opengles.GL;

public class UDPListenerService extends Service {
    private static final String TAG = "UDPSvc";

    private static final Integer LISTEN_PORT = 17500;
    private static final Integer LISTEN_TIMEOUT_MS = 1000;
    private static final int RECEIVE_BUFFER_SIZE = 500; // May need to increase if player count is above 16

    DatagramSocket mSocket;

    WifiManager wm = null;
    WifiManager.MulticastLock multicastLock = null;

    private InetAddress mMyIP = null;
    private InetAddress mBroadcastAddress = null;

    private static volatile boolean mSendingMessage = false;
    private static volatile boolean keepListening = true;
    private static volatile boolean doneListening = true;
    private static volatile boolean mIsListService = false;
    private static volatile int mReadyToScan = 0;

    private volatile boolean mScanRunning = false;

    public static final String INTENT_PLAYERID = "playerid";
    public static final String INTENT_MESSAGE = "message";

    private void listenForMessage(InetAddress ip, Integer port, Integer timeout) throws Exception {
        byte[] recvBuf = new byte[RECEIVE_BUFFER_SIZE];
        if (mSocket == null || mSocket.isClosed()) {
            mSocket = new DatagramSocket(null);
            mSocket.setReuseAddress(true);
            mSocket.setBroadcast(true);
            mSocket.bind(new InetSocketAddress(port));
        }
        mSocket.setSoTimeout(timeout);
        DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
        Log.d(TAG, "Waiting for UDP messages on " + ip.toString() + ":" + port);
        mReadyToScan++;
        doneListening = false;
        while (keepListening) {
            try {
                mSocket.receive(packet);
                String senderIP = packet.getAddress().getHostAddress();
                String message = new String(packet.getData()).trim();
                message = message.substring(0, packet.getLength()); // If we don't do this, we get leftover garbage data
                //Log.e(TAG, "Got UDB message len " + packet.getLength() + " from " + senderIP + ", message: " + message);
                processMessage(packet.getAddress(), message);
            } catch (SocketTimeoutException e) {
                // do nothing
            }
        }
        mSocket.close();
    }

    private void processMessage(InetAddress ip, String message) {
        Intent intent = null;
        if (mMyIP == null) {
            mMyIP = Globals.getIPAddress();
        }
        if (ip.equals(mMyIP)) {
            //Log.d(TAG, "IP matched so ignored");
            return;
        }
        if (message.startsWith(NetMsg.MESSAGE_PREFIX)) {
            message = message.substring(NetMsg.MESSAGE_PREFIX.length());
            if (message.startsWith(NetMsg.NETMSG_SHOTFIRED)) {
                // someone else fired a shot
                intent = new Intent(NetMsg.NETMSG_SHOTFIRED);
            } else if (message.startsWith(NetMsg.NETMSG_HIT)) {
                // you hit someone!
                intent = new Intent(NetMsg.NETMSG_HIT);
                Globals.getmIPTeamMapSemaphore();
                Byte id = Globals.getInstance().mIPTeamMap.get(ip);
                Globals.getInstance().mIPTeamMapSemaphore.release();
                if (id == null) {
                    Log.e(TAG, "Unknown IP " + ip.toString());
                    return;
                }
                intent.putExtra(INTENT_PLAYERID, id);
            } else if (message.startsWith(NetMsg.NETMSG_OUT)) {
                // hitting a player that's already out
                intent = new Intent(NetMsg.NETMSG_OUT);
                Globals.getmIPTeamMapSemaphore();
                Byte id = Globals.getInstance().mIPTeamMap.get(ip);
                Globals.getInstance().mIPTeamMapSemaphore.release();
                if (id == null) {
                    Log.e(TAG, "Unknown IP " + ip.toString());
                    return;
                }
                intent.putExtra(INTENT_PLAYERID, id);
            } else if (message.startsWith(NetMsg.NETMSG_ELIMINATED)) {
                // you eliminated someone!
                intent = new Intent(NetMsg.NETMSG_ELIMINATED);
                Globals.getmIPTeamMapSemaphore();
                Byte id = Globals.getInstance().mIPTeamMap.get(ip);
                Globals.getInstance().mIPTeamMapSemaphore.release();
                if (id == null) {
                    Log.e(TAG, "Unknown IP " + ip.toString());
                    return;
                }
                intent.putExtra(INTENT_PLAYERID, id);
            } else if (message.startsWith(NetMsg.NETMSG_JOIN)) {
                if (!mIsListService) return;
                // This is a player join message
                message = message.substring(NetMsg.NETMSG_JOIN.length());
                String version = message.substring(0, 2);
                if (!version.equals(NetMsg.NETWORK_VERSION)) {
                    sendUDPMessage(NetMsg.NETMSG_VERSIONERROR, ip, LISTEN_PORT);
                    return;
                }
                message = message.substring(2);
                Byte team = (byte) (int) Integer.parseInt(message);
                Globals.getmTeamIPMapSemaphore();
                if (team == Globals.getInstance().mPlayerID || (Globals.getInstance().mTeamIPMap.get(team) != null && !Globals.getInstance().mTeamIPMap.get(team).equals(ip))) {
                    Globals.getInstance().mTeamIPMapSemaphore.release();
                    Log.e(TAG, "2 Players using same ID!");
                    sendUDPMessage(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_SAMETEAM, ip, LISTEN_PORT);
                } else {
                    Globals.getInstance().mTeamIPMap.put(team, ip);
                    Globals.getInstance().mTeamIPMapSemaphore.release();
                    Globals.getmIPTeamMapSemaphore();
                    Globals.getInstance().mIPTeamMap.put(ip, team);
                    Globals.getInstance().mIPTeamMapSemaphore.release();
                    Log.d(TAG, "player " + team + " found at " + ip.toString());
                    sendUDPMessage(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_SERVERREPLY, ip, LISTEN_PORT);
                }
            } else if (message.startsWith(NetMsg.NETMSG_SERVERREPLY)) {
                Globals.getInstance().mServerIP = ip;
                intent = new Intent(NetMsg.NETMSG_SERVERREPLY);
            } else if (message.startsWith(NetMsg.NETMSG_LEAVE)) {
                // This is a player left message
                Globals.getmIPTeamMapSemaphore();
                Byte team = Globals.getInstance().mIPTeamMap.get(ip);
                Globals.getInstance().mIPTeamMap.remove(ip);
                Globals.getInstance().mIPTeamMapSemaphore.release();
                Globals.getmTeamIPMapSemaphore();
                Globals.getInstance().mTeamIPMap.remove(team);
                Globals.getInstance().mTeamIPMapSemaphore.release();
                Log.d(TAG, "player " + team + " left at " + ip.toString());
                intent = new Intent(NetMsg.NETMSG_LEAVE);
            } else if (message.startsWith(NetMsg.NETMSG_ENDGAME)) {
                if (Globals.getInstance().mGameState == Globals.GAME_STATE_NONE)
                    return;
                // game ends!
                intent = new Intent(NetMsg.NETMSG_ENDGAME);
            } else if (message.startsWith(NetMsg.NETMSG_ERROR)) {
                // some kind of error
                intent = new Intent(NetMsg.NETMSG_ERROR);
            } else if (message.startsWith(NetMsg.NETMSG_SAMETEAM)) {
                // Two players using the same ID error!
                intent = new Intent(NetMsg.NETMSG_SAMETEAM);
            } else if (message.startsWith(NetMsg.NETMSG_TEAMELIMINATED)) {
                // Someone else on your team scored a point
                intent = new Intent(NetMsg.NETMSG_TEAMELIMINATED);
            }
        }
        if (intent != null)
            sendBroadcast(intent);
    }

    Thread UDPMessageThread;

    public void startListenForUDPMessage() {
        keepListening = true;
        if (mIsListService) {
            if (wm == null)
                wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                Log.e(TAG, "Failed to get wifi manager");
            } else {
                if (multicastLock == null) {
                    multicastLock = wm.createMulticastLock("SimpleCoil");

                    multicastLock.setReferenceCounted(true);
                }
            }
            if (multicastLock != null && !multicastLock.isHeld()) multicastLock.acquire();
        }

        UDPMessageThread = new Thread(new Runnable() {
            public void run() {
                while (keepListening) {
                    try {
                        InetAddress address = Globals.getIPAddress();
                        if (mIsListService || address == null)
                            address = InetAddress.getByName("0.0.0.0");
                        listenForMessage(address, LISTEN_PORT, LISTEN_TIMEOUT_MS);
                    } catch (Exception e) {
                        Log.i(TAG, "no longer listening for UDP messages: " + e.getMessage());
                    }
                }
                Log.i(TAG, "Stopped listening for UDP messages");
                if(multicastLock != null && multicastLock.isHeld()) multicastLock.release();
                doneListening = true;
            }
        });
        UDPMessageThread.start();
    }

    private InetAddress getBroadcastAddress() {
        if(wm == null)wm = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            Log.e(TAG, "Failed to get wifi manager");
            return null;
        }
        DhcpInfo dhcp = wm.getDhcpInfo();
        if (dhcp == null) {
            Log.e(TAG, "Failed to get dhcp info");
            return null;
        }

        int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
        byte[] quads = new byte[4];
        for (int k = 0; k < 4; k++)
            quads[k] = (byte) ((broadcast >> k * 8) & 0xFF);
        InetAddress ret = null;
        try {
            ret = InetAddress.getByAddress(quads);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get broadcast IP address");
        }
        return ret;
    }

    public void createServer() {
        if (!doneListening) {
            Log.e(TAG, "Listening is still in progress");
            sendFailedJoin();
            return;
        }
        if (mBroadcastAddress == null)
            mBroadcastAddress = getBroadcastAddress();
        if (mBroadcastAddress == null) {
            Log.e(TAG, "Failed to get broadcast IP address");
            sendFailedJoin();
            return;
        }
        Globals.getmIPTeamMapSemaphore();
        Globals.getInstance().mIPTeamMap.clear();
        Globals.getInstance().mIPTeamMapSemaphore.release();
        Globals.getmTeamIPMapSemaphore();
        Globals.getInstance().mTeamIPMap.clear();
        Globals.getInstance().mTeamIPMapSemaphore.release();
        keepListening = true;
        mIsListService = true;
        mScanRunning = false;
        mReadyToScan = 0;
        startListenForUDPMessage();
        while (mReadyToScan == 0) {
            sleep(100);
        }
        if (mMyIP == null) {
            mMyIP = Globals.getIPAddress();
        }
        Globals.getInstance().mServerIP = mMyIP;
        Intent intent = new Intent(NetMsg.NETMSG_SERVERCREATED);
        sendBroadcast(intent);
    }

    public void cancelServer() {
        if (!mIsListService)
            return;
        keepListening = false;
    }

    public void joinServer() {
        if (mBroadcastAddress == null)
            mBroadcastAddress = getBroadcastAddress();
        if (mBroadcastAddress == null) {
            sendFailedJoin();
            return;
        }
        joinServer(mBroadcastAddress, LISTEN_PORT);
    }

    public void joinServer(String serverIP) {
        if (serverIP.startsWith("/"))
            serverIP = serverIP.substring(1);
        final String ip = serverIP;
        Log.e(TAG, "attempt to join " + ip);
        Thread joinThread = new Thread(new Runnable() {
            public void run() {
                InetAddress ipAddr = null;
                try {
                    ipAddr = InetAddress.getByName(ip);
                } catch (UnknownHostException e) {
                    Log.e(TAG, "unknown host!");
                    sendFailedJoin();
                    return;
                }
                if (ipAddr == null) {
                    Log.e(TAG, "ip is still null!");
                    sendFailedJoin();
                    return;
                }
                joinServer(ipAddr);
            }
        });
        joinThread.start();
    }

    public void joinServer(InetAddress serverIP) {
        joinServer(serverIP, LISTEN_PORT);
    }

    public void joinServer(InetAddress serverIP, Integer port) {
        if (!doneListening) {
            Log.e(TAG, "Listening is still in progress");
            sendFailedJoin();
            return;
        }
        Globals.getmIPTeamMapSemaphore();
        Globals.getInstance().mIPTeamMap.clear();
        Globals.getInstance().mIPTeamMapSemaphore.release();
        Globals.getmTeamIPMapSemaphore();
        Globals.getInstance().mTeamIPMap.clear();
        Globals.getInstance().mTeamIPMapSemaphore.release();
        keepListening = true;
        mIsListService = false;
        mScanRunning = true;
        mReadyToScan = 0;
        startListenForUDPMessage();
        joinFailCheck(serverIP, port);
    }

    private void joinFailCheck(final InetAddress serverIP, final Integer port) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                new CountDownTimer(2000, 500) { // We send 3 join requests over 2 seconds and quit if we don't get connected

                    public void onTick(long millisUntilFinished) {
                        if (mScanRunning) {
                            Log.d(TAG, "Sending join request");
                            sendUDPMessage(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_JOIN + NetMsg.NETWORK_VERSION + Globals.getInstance().mPlayerID, serverIP, port);
                        } else {
                            this.cancel();
                        }
                    }

                    public void onFinish() {
                        if (mScanRunning) {
                            Log.d(TAG, "join failed, could not find a server");
                            mScanRunning = false;
                            keepListening = false;
                            sendFailedJoin();
                        }
                    }
                }.start();
            }
        });
    }

    private void sendFailedJoin() {
        Intent intent = new Intent(NetMsg.NETMSG_FAILEDTOJOIN);
        sendBroadcast(intent);
    }

    public void sendUDPMessage(String message, Byte playerID) {
        message = NetMsg.MESSAGE_PREFIX + message;
        Globals.getmTeamIPMapSemaphore();
        InetAddress ip = Globals.getInstance().mTeamIPMap.get(playerID);
        Globals.getInstance().mTeamIPMapSemaphore.release();
        if (ip == null) {
            Log.e(TAG, "cannot send message to unknown ID " + playerID);
            return;
        }
        sendUDPMessage(message, ip, LISTEN_PORT);
    }

    public void sendUDPMessageAll(String message) {
        final String prefixedMessage = NetMsg.MESSAGE_PREFIX + message;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                while (mSendingMessage) {
                    sleep(10);
                }
                mSendingMessage = true;
                Globals.getmIPTeamMapSemaphore();
                for (InetAddress ip : Globals.getInstance().mIPTeamMap.keySet()) {
                    try {
                        Log.d(TAG, "sending '" + prefixedMessage + "' to " + ip.toString() + ":" + LISTEN_PORT);
                        DatagramSocket udpSocket = new DatagramSocket(0); // system will assign any unused port for sending
                        byte[] buf = prefixedMessage.getBytes();
                        DatagramPacket packet = new DatagramPacket(buf, buf.length, ip, LISTEN_PORT);
                        udpSocket.send(packet);
                        udpSocket.close();
                    } catch (SocketException e) {
                        Log.e(TAG, "Socket Error:", e);
                    } catch (IOException e) {
                        //Log.e(TAG, "IO Error:", e);
                        e.printStackTrace();
                        Intent intent = new Intent(NetMsg.NETMSG_ERROR);
                        intent.putExtra(INTENT_MESSAGE, e.getLocalizedMessage());
                        sendBroadcast(intent);
                    }
                }
                Globals.getInstance().mIPTeamMapSemaphore.release();
                mSendingMessage = false;
                Log.d(TAG, "send all finished");
            }
        });
        sendThread.start();
    }

    public void sendUDPMessageAllRepeat(String message, final int repeatCount) {
        final String prefixedMessage = NetMsg.MESSAGE_PREFIX + message;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                while (mSendingMessage) {
                    sleep(10);
                }
                mSendingMessage = true;
                int repeat = repeatCount;
                while (repeat-- > 0) {
                    Globals.getmIPTeamMapSemaphore();
                    for (InetAddress ip : Globals.getInstance().mIPTeamMap.keySet()) {
                        try {
                            Log.d(TAG, "sending '" + prefixedMessage + "' to " + ip.toString() + ":" + LISTEN_PORT);
                            DatagramSocket udpSocket = new DatagramSocket(0); // system will assign any unused port for sending
                            byte[] buf = prefixedMessage.getBytes();
                            DatagramPacket packet = new DatagramPacket(buf, buf.length, ip, LISTEN_PORT);
                            udpSocket.send(packet);
                            udpSocket.close();
                        } catch (SocketException e) {
                            Log.e(TAG, "Socket Error:", e);
                        } catch (IOException e) {
                            //Log.e(TAG, "IO Error:", e);
                            e.printStackTrace();
                            Intent intent = new Intent(NetMsg.NETMSG_ERROR);
                            intent.putExtra(INTENT_MESSAGE, e.getLocalizedMessage());
                            sendBroadcast(intent);
                        }
                    }
                    Globals.getInstance().mIPTeamMapSemaphore.release();
                    sleep(50);
                }
                mSendingMessage = false;
                Log.d(TAG, "send all repeat finished");
            }
        });
        sendThread.start();
    }

    private void sendUDPMessage(final String message, final InetAddress ip, final Integer port) {
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                try {
                    while (mSendingMessage) {
                        sleep(10);
                    }
                    mSendingMessage = true;
                    Log.d(TAG, "sending '" + message + "' to " + ip.toString() + ":" + port);
                    DatagramSocket udpSocket = new DatagramSocket(0); // system will assign any unused port for sending
                    byte[] buf = message.getBytes();
                    DatagramPacket packet = new DatagramPacket(buf, buf.length, ip, port);
                    udpSocket.send(packet);
                    udpSocket.close();
                } catch (SocketException e) {
                    Log.e(TAG, "Socket Error:", e);
                } catch (IOException e) {
                    //Log.e(TAG, "IO Error:", e);
                    e.printStackTrace();
                    Intent intent = new Intent(NetMsg.NETMSG_ERROR);
                    intent.putExtra(INTENT_MESSAGE, e.getLocalizedMessage());
                    sendBroadcast(intent);
                }
                mSendingMessage = false;
            }
        });
        sendThread.start();
    }

    void stopListen() {
        mScanRunning = false;
        keepListening = false;
    }

    @Override
    public void onCreate() {

    }

    @Override
    public void onDestroy() {
        stopListen();
        if(multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (mSocket != null)
            mSocket.close();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        keepListening = true;
        Log.i(TAG, "UDP Service started");
        return START_STICKY;
    }

    public class LocalBinder extends Binder {
        UDPListenerService getService() {
            return UDPListenerService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    public void startGame() {
        mIsListService = false; // There is no list service while the game is running
    }

    public void endGame() {
        sendUDPMessageAllRepeat(NetMsg.NETMSG_ENDGAME, 3);
    }

    public void allowJoin(boolean allowed) { mIsListService = allowed;}

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void endScanning() { mScanRunning = false; }
}