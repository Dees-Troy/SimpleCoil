/*
 * Copyright (C) 2018
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
import android.os.IBinder;
import android.util.Log;

public class UDPListenerService extends Service {
    private static final String TAG = "UDPSvc";

    private static final Integer BROADCAST_LISTEN_PORT = 17500;
    private static final Integer BROADCAST_LISTEN_TIMEOUT_MS = 1000;
    private static final Integer GAME_LISTEN_PORT = 17505;
    private static final int RECEIVE_BUFFER_SIZE = 500; // May need to increase if player count is above 16

    // All of these messages are straightforward and contain no extra data
    public static final String UDPMSG_SHOTFIRED = "UDPSHOTFIRED";
    public static final String UDPMSG_HIT = "UDPHIT";
    public static final String UDPMSG_OUT = "UDPOUT";
    public static final String UDPMSG_ELIMINATED = "UDPELIMINATED";
    public static final String UDPMSG_LEAVE = "UDPLEAVE";
    public static final String UDPMSG_STARTGAME = "UDPSTARTGAME";
    public static final String UDPMSG_ENDGAME = "UDPENDGAME";
    public static final String UDPMSG_ERROR = "UDPERROR";
    public static final String UDPMSG_FAILEDTOJOIN = "UDPFAILEDTOJOIN";
    public static final String UDPMSG_VERSIONERROR = "UDPVERSIONERROR";
    public static final String UDPMSG_SAMETEAM = "UDPSAMETEAM";
    public static final String UDPMSG_SERVERCREATED = "UDPSERVERCREATED";
    public static final String UDPMSG_SERVERCANCEL = "UDPSERVERCANCEL";
    public static final String UDPMSG_LIMITTIME = "UDPLIMITTIME";
    public static final String UDPMSG_LIMITLIVES = "UDPLIMITLIVES";
    public static final String UDPMSG_LIMITSCORE = "UDPLIMITSCORE";
    public static final String UDPMSG_UNLIMITED = "UDPUNLIMITED";
    public static final String UDPMSG_PLAYERNAME = "UDPPLAYERNAME";
    public static final String UDPMSG_TEAMELIMINATED = "UDPTEAMELIMINATED";

    // UDPJOIN is UDPJOIN + playerID, so UDPJOIN2 for playerID 2
    public static final String UDPMSG_JOIN = "UDPJOIN";
    /* UDPLISTPLAYERS contains a long list of all of the players. The server collects all of the
       IDs and IP addresses and concatenates them into a string that is sent to each player individually
       prefixed with the game mode so something like this (the leading 4 means 4 teams):
       41-11.11.11.2_2-11.11.11.3_3-11.11.11.4 */
    public static final String UDPMSG_LISTPLAYERS = "UDPLISTPLAYERS";

    private static final String MESSAGE_PREFIX = "SimpleCoil:";
    private static final String UDP_VERSION = "03";
    // 1 for FFA, 2 for 2 Teams, and 4 for 4 Teams
    private String mGameMode = "2";

    private int mGameLimit = FullscreenActivity.GAME_LIMIT_NONE;
    private int mTimeLimit = 0;
    private int mScoreLimit = 0;
    private int mLivesLimit = 0;

    private volatile byte mPlayerID = 0;
    private volatile String mPlayerName = "";

    DatagramSocket socketBroadcast;
    DatagramSocket socketGame;

    WifiManager wm = null;
    WifiManager.MulticastLock multicastLock = null;

    private Map<InetAddress, Byte> mIPTeamMap;
    private Map<Byte, InetAddress> mTeamIPMap;
    private Map<Byte, String> mTeamPlayerNameMap;

    private InetAddress mMyIP = null;
    private InetAddress mBroadcastAddress = null;
    private InetAddress mServerIP = null;

    private static volatile boolean mSendingMessage = false;
    private static volatile boolean keepListeningBroadcast = true;
    private static volatile boolean keepListening = true;
    private static volatile boolean doneListeningBroadcast = true;
    private static volatile boolean doneListening = true;
    private static volatile boolean mIsListService = false;
    private static volatile boolean mSentPlayerName = false;
    private static volatile int mReadyToScan = 0;

    private volatile boolean mScanRunning = false;
    private volatile boolean mGameRunning = false;

    public static final String INTENT_PLAYERID = "playerid";
    public static final String INTENT_LIMIT = "limit";
    public static final String INTENT_MESSAGE = "message";

    /**
     * Get IP address from first non-localhost interface
     * @return  address or empty string
     */
    public static String getIPAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;

                        if (isIPv4) {
                            return sAddr;
                        } /*else {
                            // IPv6 stuff
                            int delim = sAddr.indexOf('%'); // drop ip6 zone suffix
                            return delim<0 ? sAddr.toUpperCase() : sAddr.substring(0, delim).toUpperCase();
                        }*/
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return "";
    }

    private void listenForBroadcast(InetAddress ip, Integer port, Integer timeout) throws Exception {
        byte[] recvBuf = new byte[RECEIVE_BUFFER_SIZE];
        if (socketBroadcast == null || socketBroadcast.isClosed()) {
            socketBroadcast = new DatagramSocket(null);
            socketBroadcast.setReuseAddress(true);
            socketBroadcast.setBroadcast(true);
            socketBroadcast.bind(new InetSocketAddress(port));
        }
        socketBroadcast.setSoTimeout(timeout);
        DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
        Log.d(TAG, "Waiting for UDP broadcasts on " + ip.toString() + ":" + port);
        mReadyToScan++;
        doneListeningBroadcast = false;
        while (keepListeningBroadcast) {
            try {
                socketBroadcast.receive(packet);
                String senderIP = packet.getAddress().getHostAddress();
                String message = new String(packet.getData()).trim();
                message = message.substring(0, packet.getLength()); // If we don't do this, we get leftover garbage data
                //Log.e(TAG, "Got UDB broadcast len " + packet.getLength() + " from " + senderIP + ", message: " + message);
                processMessage(packet.getAddress(), message);
            } catch (SocketTimeoutException e) {
                // do nothing
            }
        }
        socketBroadcast.close();
    }

    private void listenForGame(InetAddress ip, Integer port, Integer timeout) throws Exception {
        byte[] recvBuf = new byte[RECEIVE_BUFFER_SIZE];
        if (socketGame == null || socketGame.isClosed()) {
            socketGame = new DatagramSocket(null);
            socketGame.setReuseAddress(true);
            socketGame.setBroadcast(true);
            socketGame.bind(new InetSocketAddress(port));
        }
        socketGame.setSoTimeout(timeout);
        DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
        Log.d(TAG, "Waiting for UDP message on " + ip.toString() + ":" + port);
        mReadyToScan++;
        doneListening = false;
        while (keepListening) {
            try {
                socketGame.receive(packet);
                String senderIP = packet.getAddress().getHostAddress();
                String message = new String(packet.getData()).trim();
                message = message.substring(0, packet.getLength()); // If we don't do this, we get leftover garbage data
                //Log.e(TAG, "Got UDB message len " + packet.getLength() + " from " + senderIP + ", message: " + message);
                processMessage(packet.getAddress(), message);
            } catch (SocketTimeoutException e) {
                // do nothing
            }
        }
        socketGame.close();
    }

    private void processMessage(InetAddress ip, String message) {
        Intent intent = null;
        if (mMyIP == null) {
            try {
                mMyIP = InetAddress.getByName(getIPAddress());
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
        }
        if (ip.equals(mMyIP)) {
            //Log.d(TAG, "IP matched so ignored");
            return;
        }
        if (message.startsWith(MESSAGE_PREFIX)) {
            message = message.substring(MESSAGE_PREFIX.length());
            if (message.startsWith(UDPMSG_SHOTFIRED)) {
                // someone else fired a shot
                intent = new Intent(UDPMSG_SHOTFIRED);
            } else if (message.startsWith(UDPMSG_HIT)) {
                // you hit someone!
                intent = new Intent(UDPMSG_HIT);
                intent.putExtra(INTENT_PLAYERID, mIPTeamMap.get(ip));
            } else if (message.startsWith(UDPMSG_OUT)) {
                // hitting a player that's already out
                intent = new Intent(UDPMSG_OUT);
                intent.putExtra(INTENT_PLAYERID, mIPTeamMap.get(ip));
            } else if (message.startsWith(UDPMSG_ELIMINATED)) {
                // you eliminated someone!
                intent = new Intent(UDPMSG_ELIMINATED);
                intent.putExtra(INTENT_PLAYERID, mIPTeamMap.get(ip));
            } else if (message.startsWith(UDPMSG_JOIN)) {
                if (mIPTeamMap == null)
                    mIPTeamMap = new HashMap<InetAddress, Byte>();
                if (mTeamIPMap == null)
                    mTeamIPMap = new HashMap<Byte, InetAddress>();
                // This is a player join message
                message = message.substring(UDPMSG_JOIN.length());
                String version = message.substring(0, 2);
                if (!version.equals(UDP_VERSION)) {
                    sendUDPMessage(UDPMSG_VERSIONERROR, ip, GAME_LISTEN_PORT);
                    return;
                }
                message = message.substring(2);
                Byte team = (byte) (int) Integer.parseInt(message);
                if (team == mPlayerID || (mTeamIPMap.get(team) != null && !mTeamIPMap.get(team).equals(ip))) {
                    Log.e(TAG, "2 Players using same ID!");
                    sendUDPMessage(UDPMSG_SAMETEAM, ip, GAME_LISTEN_PORT);
                } else {
                    mIPTeamMap.put(ip, team);
                    mTeamIPMap.put(team, ip);
                    Log.d(TAG, "player " + team + " found at " + ip.toString());
                    sendPlayerList();
                    intent = new Intent(UDPMSG_JOIN);
                }
            } else if (message.startsWith(UDPMSG_LISTPLAYERS)) {
                mScanRunning = false;
                if (mIPTeamMap == null)
                    mIPTeamMap = new HashMap<InetAddress, Byte>();
                else
                    mIPTeamMap.clear();
                if (mTeamIPMap == null)
                    mTeamIPMap = new HashMap<Byte, InetAddress>();
                else
                    mTeamIPMap.clear();
                message = message.substring(UDPMSG_LISTPLAYERS.length());
                mGameMode = message.substring(0, 1);
                message = message.substring(1);
                String[] entries = message.split("_", 0);
                int playersAdded = 0;
                for (String entry : entries) {
                    String[] splitEntry = entry.split("-", 2);
                    Byte team = (byte) (int) Integer.parseInt(splitEntry[0]);
                    String ipStr = splitEntry[1];
                    if (ipStr.startsWith("/"))
                        ipStr = ipStr.substring(1);
                    try {
                        InetAddress teamIP = InetAddress.getByName(ipStr);
                        if (!teamIP.equals(mMyIP)) {
                            mIPTeamMap.put(teamIP, team);
                            mTeamIPMap.put(team, teamIP);
                            Log.d(TAG, "list player " + team + " found at " + teamIP.toString());
                            playersAdded++;
                        }
                    } catch (UnknownHostException e) {
                        Log.e(TAG, "unrecognized IP " + ipStr);
                    }
                }
                Log.d(TAG, "Added " + playersAdded + " players");
                mServerIP = ip;
                if (!mSentPlayerName) {
                    mSentPlayerName = true;
                    sendUDPMessage(MESSAGE_PREFIX + UDPMSG_PLAYERNAME + String.format("%02d", mPlayerID) + mPlayerName, mServerIP, GAME_LISTEN_PORT);
                }
                intent = new Intent(UDPMSG_LISTPLAYERS);
            } else if (message.startsWith(UDPMSG_LEAVE)) {
                // This is a player left message
                if (mIPTeamMap == null)
                    mIPTeamMap = new HashMap<InetAddress, Byte>();
                if (mTeamIPMap == null)
                    mTeamIPMap = new HashMap<Byte, InetAddress>();
                Byte team = mIPTeamMap.get(ip);
                mTeamIPMap.remove(team);
                mIPTeamMap.remove(ip);
                Log.d(TAG, "player " + team + " left at " + ip.toString());
                intent = new Intent(UDPMSG_LEAVE);
            } else if (message.startsWith(UDPMSG_STARTGAME)) {
                if (mGameRunning)
                    return;
                mGameRunning = true;
                // start the game!
                intent = new Intent(UDPMSG_STARTGAME);
            } else if (message.startsWith(UDPMSG_ENDGAME)) {
                if (!mGameRunning)
                    return;
                mGameRunning = false;
                // game ends!
                intent = new Intent(UDPMSG_ENDGAME);
            } else if (message.startsWith(UDPMSG_ERROR)) {
                // some kind of error
                intent = new Intent(UDPMSG_ERROR);
            } else if (message.startsWith(UDPMSG_SAMETEAM)) {
                // Two players using the same ID error!
                intent = new Intent(UDPMSG_SAMETEAM);
            } else if (message.startsWith(UDPMSG_SERVERCANCEL)) {
                // Server is gone
                intent = new Intent(UDPMSG_SERVERCANCEL);
            } else if (message.startsWith(UDPMSG_LIMITTIME)) {
                // Game limited by time
                message = message.substring(UDPMSG_LIMITTIME.length());
                int limit = (int) Integer.parseInt(message);
                intent = new Intent(UDPMSG_LIMITTIME);
                intent.putExtra(INTENT_LIMIT, limit);
            } else if (message.startsWith(UDPMSG_LIMITLIVES)) {
                // Game limited by lives
                message = message.substring(UDPMSG_LIMITLIVES.length());
                int limit = (int) Integer.parseInt(message);
                intent = new Intent(UDPMSG_LIMITLIVES);
                intent.putExtra(INTENT_LIMIT, limit);
            } else if (message.startsWith(UDPMSG_LIMITSCORE)) {
                // Game limited by score
                message = message.substring(UDPMSG_LIMITSCORE.length());
                int limit = (int) Integer.parseInt(message);
                intent = new Intent(UDPMSG_LIMITSCORE);
                intent.putExtra(INTENT_LIMIT, limit);
            } else if (message.startsWith(UDPMSG_UNLIMITED)) {
                // No game limit
                intent = new Intent(UDPMSG_UNLIMITED);
            } else if (message.startsWith(UDPMSG_PLAYERNAME)) {
                // Player name
                if (mTeamPlayerNameMap == null)
                    mTeamPlayerNameMap = new HashMap<Byte, String>();
                message = message.substring(UDPMSG_PLAYERNAME.length());
                String teamStr = message.substring(0, 2);
                Byte team = (byte) (int) Integer.parseInt(teamStr);
                message = message.substring(2);
                mTeamPlayerNameMap.remove(team);
                mTeamPlayerNameMap.put(team, message);
                if (mIsListService) {
                    sendPlayerNames();
                }
            } else if (message.startsWith(UDPMSG_TEAMELIMINATED)) {
                // Someone else on your team scored a point
                intent = new Intent(UDPMSG_TEAMELIMINATED);
            }
        }
        if (intent != null)
            sendBroadcast(intent);
    }

    private void sendPlayerList() {
        Log.d(TAG, "sending player list");
        if (mMyIP == null) {
            try {
                mMyIP = InetAddress.getByName(getIPAddress());
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
        }
        Thread sendListThread = new Thread(new Runnable() {
            public void run() {
                String message = mGameMode + mPlayerID + "-" + mMyIP.toString();
                for (Map.Entry<InetAddress, Byte> entry : mIPTeamMap.entrySet()) {
                    message += "_" + entry.getValue() + "-" + entry.getKey();
                }
                sendUDPMessageAll(UDPMSG_LISTPLAYERS + message);
                sleep(100);
                if ((mGameLimit & FullscreenActivity.GAME_LIMIT_TIME) != 0) {
                    message = UDPMSG_LIMITTIME + mTimeLimit;
                    sendUDPMessageAll(message);
                    sleep(100);
                }
                if ((mGameLimit & FullscreenActivity.GAME_LIMIT_LIVES) != 0) {
                    message = UDPMSG_LIMITLIVES + mLivesLimit;
                    sendUDPMessageAll(message);
                    sleep(100);
                }
                if ((mGameLimit & FullscreenActivity.GAME_LIMIT_SCORE) != 0) {
                    message = UDPMSG_LIMITSCORE + mScoreLimit;
                    sendUDPMessageAll(message);
                }
            }
        });
        sendListThread.start();
        Intent intent = new Intent(UDPMSG_LISTPLAYERS);
        sendBroadcast(intent);
    }

    private void sendPlayerNames() {
        Log.d(TAG, "sending player names");
        if (mMyIP == null) {
            try {
                mMyIP = InetAddress.getByName(getIPAddress());
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
        }
        Thread sendListThread = new Thread(new Runnable() {
            public void run() {
                String message = UDPMSG_PLAYERNAME + String.format("%02d", mPlayerID) + mPlayerName;
                sendUDPMessageAll(message);
                for (Map.Entry<Byte, String> entry : mTeamPlayerNameMap.entrySet()) {
                    sleep(100);
                    message = UDPMSG_PLAYERNAME + String.format("%02d", entry.getKey()) + entry.getValue();
                    sendUDPMessageAll(message);
                }
            }
        });
        sendListThread.start();
    }

    Thread UDPBroadcastThread;

    public void startListenForUDPBroadcast() {
        keepListeningBroadcast = true;
        if(wm == null)wm = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            Log.e(TAG, "Failed to get wifi manager");
        } else {
            if (multicastLock == null) {
                multicastLock = wm.createMulticastLock("SimpleCoil");

                multicastLock.setReferenceCounted(true);
            }
        }
        if(multicastLock != null && !multicastLock.isHeld()) multicastLock.acquire();

        UDPBroadcastThread = new Thread(new Runnable() {
            public void run() {
                while (keepListeningBroadcast) {
                    try {
                        InetAddress broadcastIP = InetAddress.getByName("0.0.0.0");
                        listenForBroadcast(broadcastIP, BROADCAST_LISTEN_PORT, BROADCAST_LISTEN_TIMEOUT_MS);
                    } catch (Exception e) {
                        Log.i(TAG, "no longer listening for UDP broadcasts: " + e.getMessage());
                    }
                }
                Log.i(TAG, "Stopped listening for UDP broadcasts");
                if(multicastLock != null && multicastLock.isHeld()) multicastLock.release();
                doneListeningBroadcast = true;
            }
        });
        UDPBroadcastThread.start();
    }

    Thread UDPGameThread;

    public void startListenForUDPGame() {
        keepListening = true;
        UDPGameThread = new Thread(new Runnable() {
            public void run() {
                while (keepListening) {
                    try {
                        mMyIP = InetAddress.getByName(getIPAddress());
                        listenForGame(mMyIP, GAME_LISTEN_PORT, BROADCAST_LISTEN_TIMEOUT_MS);
                    } catch (Exception e) {
                        Log.i(TAG, "no longer listening for UDP game messages: " + e.getMessage());
                    }
                }
                Log.i(TAG, "Stopped listening for UDP messages");
                doneListening = true;
            }
        });
        UDPGameThread.start();
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

    public String getIP() {
        String ip = "";
        if (mServerIP != null) {
            ip = mServerIP.toString();
            if (ip.startsWith("/"))
                ip = ip.substring(1);
            return ip;
        }
        if (mMyIP == null) {
            try {
                mMyIP = InetAddress.getByName(getIPAddress());
            } catch (UnknownHostException e) {
                return "";
            }
        }
        ip = mMyIP.toString();
        if (ip.startsWith("/"))
            ip = ip.substring(1);
        return ip;
    }

    public void createServer() {
        if (!doneListening || !doneListeningBroadcast) {
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
        if (mIPTeamMap == null)
            mIPTeamMap = new HashMap<InetAddress, Byte>();
        else
            mIPTeamMap.clear();
        if (mTeamIPMap == null)
            mTeamIPMap = new HashMap<Byte, InetAddress>();
        else
            mTeamIPMap.clear();
        keepListeningBroadcast = true;
        keepListening = true;
        mIsListService = true;
        mScanRunning = false;
        mGameRunning = false;
        mReadyToScan = 0;
        startListenForUDPBroadcast();
        startListenForUDPGame();
        while(mReadyToScan < 2) {
            sleep(100);
        }
        if (mMyIP == null) {
            try {
                mMyIP = InetAddress.getByName(getIPAddress());
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
        }
        mServerIP = mMyIP;
        Intent intent = new Intent(UDPMSG_SERVERCREATED);
        sendBroadcast(intent);
    }

    public void cancelServer() {
        if (!mIsListService)
            return;
        sendUDPMessageAll(UDPMSG_SERVERCANCEL);
        keepListeningBroadcast = false;
        keepListening = false;
    }

    public void joinServer() {
        if (mBroadcastAddress == null)
            mBroadcastAddress = getBroadcastAddress();
        if (mBroadcastAddress == null) {
            sendFailedJoin();
            return;
        }
        joinServer(mBroadcastAddress, BROADCAST_LISTEN_PORT);
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
        joinServer(serverIP, GAME_LISTEN_PORT);
    }

    public void joinServer(InetAddress serverIP, Integer port) {
        if (!doneListening) {
            Log.e(TAG, "Listening is still in progress");
            sendFailedJoin();
            return;
        }
        if (mIPTeamMap == null)
            mIPTeamMap = new HashMap<InetAddress, Byte>();
        else
            mIPTeamMap.clear();
        if (mTeamIPMap == null)
            mTeamIPMap = new HashMap<Byte, InetAddress>();
        else
            mTeamIPMap.clear();
        keepListeningBroadcast = true;
        keepListening = true;
        mIsListService = false;
        mScanRunning = true;
        mGameRunning = false;
        mReadyToScan = 0;
        mSentPlayerName = false;
        startListenForUDPGame();
        startJoinFailTimer();
        while(mReadyToScan < 1) {
            sleep(100);
        }
        sendUDPMessage(MESSAGE_PREFIX + UDPMSG_JOIN + UDP_VERSION + mPlayerID, serverIP, port);
    }

    private Timer mJoinFailTimer = null;

    private void startJoinFailTimer() {
        if (mJoinFailTimer == null)
            mJoinFailTimer = new Timer();
        else
            return;
        mJoinFailTimer.schedule(new joinFailTimer(), 1500); // Notify GUI of failed join after 1.5 seconds
    }

    private class joinFailTimer extends TimerTask {
        public void run() {
            if (mScanRunning) {
                Log.d(TAG, "join failed, could not find a server");
                mScanRunning = false;
                keepListening = false;
                sendFailedJoin();
            }
            mJoinFailTimer.cancel();
            mJoinFailTimer.purge();
            mJoinFailTimer = null;
        }
    }

    private void sendFailedJoin() {
        Intent intent = new Intent(UDPMSG_FAILEDTOJOIN);
        sendBroadcast(intent);
    }

    public void sendUDPBroadcast(String message) {
        if (mBroadcastAddress == null)
            mBroadcastAddress = getBroadcastAddress();
        if (mBroadcastAddress == null)
            return;
        message = MESSAGE_PREFIX + message;
        sendUDPMessage(message, mBroadcastAddress, BROADCAST_LISTEN_PORT);
    }

    public void sendUDPMessage(String message, Byte playerID) {
        message = MESSAGE_PREFIX + message;
        if (mTeamIPMap.get(playerID) == null) {
            Log.e(TAG, "cannot send message to unknown ID " + playerID);
            return;
        }
        sendUDPMessage(message, mTeamIPMap.get(playerID), GAME_LISTEN_PORT);
    }

    public void sendUDPMessageAll(String message) {
        final String prefixedMessage = MESSAGE_PREFIX + message;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                while (mSendingMessage) {
                    sleep(10);
                }
                mSendingMessage = true;
                for (InetAddress ip : mIPTeamMap.keySet()) {
                    try {
                        Log.d(TAG, "sending '" + prefixedMessage + "' to " + ip.toString() + ":" + GAME_LISTEN_PORT);
                        DatagramSocket udpSocket = new DatagramSocket(0); // system will assign any unused port for sending
                        byte[] buf = prefixedMessage.getBytes();
                        DatagramPacket packet = new DatagramPacket(buf, buf.length, ip, GAME_LISTEN_PORT);
                        udpSocket.send(packet);
                        udpSocket.close();
                    } catch (SocketException e) {
                        Log.e(TAG, "Socket Error:", e);
                    } catch (IOException e) {
                        //Log.e(TAG, "IO Error:", e);
                        e.printStackTrace();
                        Intent intent = new Intent(UDPMSG_ERROR);
                        intent.putExtra(INTENT_MESSAGE, e.getLocalizedMessage());
                        sendBroadcast(intent);
                    }
                }
                mSendingMessage = false;
                Log.d(TAG, "send all finished");
            }
        });
        sendThread.start();
    }

    public void sendUDPMessageAllRepeat(String message, final int repeatCount) {
        final String prefixedMessage = MESSAGE_PREFIX + message;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                while (mSendingMessage) {
                    sleep(10);
                }
                mSendingMessage = true;
                int repeat = repeatCount;
                while (repeat-- > 0) {
                    for (InetAddress ip : mIPTeamMap.keySet()) {
                        try {
                            Log.d(TAG, "sending '" + prefixedMessage + "' to " + ip.toString() + ":" + GAME_LISTEN_PORT);
                            DatagramSocket udpSocket = new DatagramSocket(0); // system will assign any unused port for sending
                            byte[] buf = prefixedMessage.getBytes();
                            DatagramPacket packet = new DatagramPacket(buf, buf.length, ip, GAME_LISTEN_PORT);
                            udpSocket.send(packet);
                            udpSocket.close();
                        } catch (SocketException e) {
                            Log.e(TAG, "Socket Error:", e);
                        } catch (IOException e) {
                            //Log.e(TAG, "IO Error:", e);
                            e.printStackTrace();
                            Intent intent = new Intent(UDPMSG_ERROR);
                            intent.putExtra(INTENT_MESSAGE, e.getLocalizedMessage());
                            sendBroadcast(intent);
                        }
                    }
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
                    Intent intent = new Intent(UDPMSG_ERROR);
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
        mGameRunning = false;
        keepListening = false;
        keepListeningBroadcast = false;
    }

    @Override
    public void onCreate() {

    }

    @Override
    public void onDestroy() {
        stopListen();
        if(multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (socketBroadcast != null)
            socketBroadcast.close();
        if (socketGame != null)
            socketGame.close();
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        keepListening = true;
        keepListeningBroadcast = true;
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

    public void setPlayerID(byte playerID) {
        mPlayerID = playerID;
    }

    public int getPlayerCount() {
        if (mTeamIPMap == null)
            return 1; // Guess it's just you
        return mTeamIPMap.size() + 1; // All other players plus ourself
    }

    public void setGameMode(int gameMode) {
        if (gameMode == FullscreenActivity.GAME_MODE_2TEAMS)
            mGameMode = "2";
        else if (gameMode == FullscreenActivity.GAME_MODE_4TEAMS)
            mGameMode = "4";
        else
            mGameMode = "1";
    }

    public void setGameLimit(int gameLimit, int timeLimit, int livesLimit, int scoreLimit) {
        mGameLimit = gameLimit;
        mTimeLimit = timeLimit;
        mLivesLimit = livesLimit;
        mScoreLimit = scoreLimit;
    }

    public String getGameMode() { return mGameMode; }

    public void startGame() {
        mGameRunning = true;
        keepListeningBroadcast = false;
        sendUDPMessageAllRepeat(UDPMSG_STARTGAME, 3);
    }

    public void endGame() {
        mGameRunning = false;
        sendUDPMessageAllRepeat(UDPMSG_ENDGAME, 3);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getPlayerName(Byte playerID) {
        if (mTeamPlayerNameMap == null)
            return "";
        String ret = mTeamPlayerNameMap.get(playerID);
        if (ret == null)
            ret = "";
        return ret;
    }

    public void setPlayerName(String name) { mPlayerName = name; }

}