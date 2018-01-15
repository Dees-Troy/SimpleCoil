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


/*
 * Linux command to send UDP:
 * #socat - UDP-DATAGRAM:192.168.1.255:11111,broadcast,sp=11111
 */
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
    public static final String UDPMSG_NOPLAYER = "UDPNOPLAYER";
    public static final String UDPMSG_STARTGAME = "UDPSTARTGAME";
    public static final String UDPMSG_ENDGAME = "UDPENDGAME";
    public static final String UDPMSG_ERROR = "UDPERROR";
    public static final String UDPMSG_VERSIONERROR = "UDPVERSIONERROR";
    public static final String UDPMSG_SAMETEAM = "UDPSAMETEAM";

    // UDPSCAN will include UDPSCAN + 2 char version + 1 char game mode + playerID of the player who initiated this scan, so UDPSCAN0126 for version 01, 2 teams, and playerID of 6
    public static final String UDPMSG_SCAN = "UDPSCAN";
    // UDPPLAYER is UDPPLAYER + playerID, so UDPPLAYER2 for playerID 2
    public static final String UDPMSG_PLAYER = "UDPPLAYER";
    /* UDPLISTPLAYERS contains a long list of all of the players. When a scan is started, all other
       players send their ID to the device that initiated the scan. The scanner collects all of the
       IDs and IP addresses and concatenates them into a string that is sent to each player individually
       in the format of playerID dash IPAddress underscore so something like:
       1-11.11.11.2_2-11.11.11.3_3-11.11.11.4
     */
    public static final String UDPMSG_LISTPLAYERS = "UDPLISTPLAYERS";

    private static final String MESSAGE_PREFIX = "SimpleCoil:";
    private static final String UDP_VERSION = "01";
    // 1 for FFA, 2 for 2 Teams, and 4 for 4 Teams
    private String mGameMode = "2";

    private byte mPlayerID = 0;

    DatagramSocket socketBroadcast;
    DatagramSocket socketGame;

    WifiManager wm = null;
    WifiManager.MulticastLock multicastLock = null;

    private Map<InetAddress, Byte> mIPTeamMap;
    private Map<Byte, InetAddress> mTeamIPMap;

    private InetAddress mMyIP = null;
    private InetAddress mBroadcastAddress = null;

    private static volatile boolean mSendingMessage = false;
    private static volatile boolean keepListening = true;
    private static volatile boolean mIsListService = false;
    private static volatile int mReadyToScan = 0;

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
        while (keepListening) {
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
            Log.d(TAG, "IP matched so ignored");
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
            } else if (message.startsWith(UDPMSG_OUT)) {
                // hitting a player that's already out
                intent = new Intent(UDPMSG_OUT);
            } else if (message.startsWith(UDPMSG_ELIMINATED)) {
                // you eliminated someone!
                intent = new Intent(UDPMSG_ELIMINATED);
            } else if (message.startsWith(UDPMSG_SCAN)) {
                mIsListService = false; // Someone else is now the player list service
                if (mListServiceTimer != null)
                    mListServiceTimer.cancel();
                if (mIPTeamMap == null)
                    mIPTeamMap = new HashMap<InetAddress, Byte>();
                else
                    mIPTeamMap.clear();
                if (mTeamIPMap == null)
                    mTeamIPMap = new HashMap<Byte, InetAddress>();
                else
                    mTeamIPMap.clear();
                message = message.substring(UDPMSG_SCAN.length());
                String version = message.substring(0, 2);
                if (!version.equals(UDP_VERSION)) {
                    intent = new Intent(UDPMSG_VERSIONERROR);
                    sendBroadcast(intent);
                    return;
                }
                mGameMode = message.substring(2, 3);
                message = message.substring(3);
                Byte team = (byte)(int)Integer.parseInt(message);
                mIPTeamMap.put(ip, team);
                mTeamIPMap.put(team, ip);
                Log.d(TAG, "scan started by player ID " + team + " at " + ip.toString());
                sendPlayerMessage(ip);
                intent = new Intent(UDPMSG_SCAN);
            } else if (message.startsWith(UDPMSG_PLAYER)) {
                if (mIPTeamMap == null)
                    mIPTeamMap = new HashMap<InetAddress, Byte>();
                if (mTeamIPMap == null)
                    mTeamIPMap = new HashMap<Byte, InetAddress>();
                // This is a player message
                message = message.substring(UDPMSG_PLAYER.length());
                Byte team = (byte) (int) Integer.parseInt(message);
                if (mTeamIPMap.get(team) != null || team == mPlayerID) {
                    Log.e(TAG, "2 Players using same ID!");
                    sendUDPBroadcast(UDPMSG_SAMETEAM);
                    intent = new Intent(UDPMSG_SAMETEAM);
                } else {
                    mIPTeamMap.put(ip, team);
                    mTeamIPMap.put(team, ip);
                    Log.d(TAG, "player " + team + " found at " + ip.toString());
                    if (mListServiceTimer == null)
                        sendPlayerList();
                    intent = new Intent(UDPMSG_PLAYER);
                }
            } else if (message.startsWith(UDPMSG_LISTPLAYERS)) {
                if (mIPTeamMap == null)
                    mIPTeamMap = new HashMap<InetAddress, Byte>();
                else
                    mIPTeamMap.clear();
                if (mTeamIPMap == null)
                    mTeamIPMap = new HashMap<Byte, InetAddress>();
                else
                    mTeamIPMap.clear();
                message = message.substring(UDPMSG_LISTPLAYERS.length());
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
                Log.d(TAG, "Added " + playersAdded + "players");
                intent = new Intent(UDPMSG_PLAYER);
            } else if (message.startsWith(UDPMSG_NOPLAYER)) {
                // This is a player left message
                Byte team = mIPTeamMap.get(ip);
                mTeamIPMap.remove(team);
                mIPTeamMap.remove(ip);
                Log.d(TAG, "player " + team + " left at " + ip.toString());
                intent = new Intent(UDPMSG_PLAYER);
            } else if (message.startsWith(UDPMSG_STARTGAME)) {
                // start the game!
                intent = new Intent(UDPMSG_STARTGAME);
            } else if (message.startsWith(UDPMSG_ENDGAME)) {
                // game ends!
                intent = new Intent(UDPMSG_ENDGAME);
            } else if (message.startsWith(UDPMSG_ERROR)) {
                // some kind of error
                intent = new Intent(UDPMSG_ERROR);
            } else if (message.startsWith(UDPMSG_SAMETEAM)) {
                // Two players using the same ID error!
                intent = new Intent(UDPMSG_SAMETEAM);
            }
        }
        if (intent != null)
            sendBroadcast(intent);
    }

    private void sendPlayerMessage(final InetAddress ip) {
        Thread playerMessageThread = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep((long) mPlayerID * 100); // The hope here is to prevent everyone from sending their data all at once
                } catch (Exception e) {
                    e.printStackTrace();
                }
                sendUDPMessage(MESSAGE_PREFIX + UDPMSG_PLAYER + mPlayerID, ip, GAME_LISTEN_PORT);
            }
        });
        playerMessageThread.start();
    }

    private Timer mListServiceTimer = null;

    private void sendPlayerList() {
        if (mListServiceTimer == null)
            mListServiceTimer = new Timer();
        else
            return;
        mListServiceTimer.schedule(new SendPlayerList(), 3000); // Finally send the list to all clients after 3 seconds
    }

    private class SendPlayerList extends TimerTask {
        public void run() {
            if (!mIsListService) {
                // Someone else is now the list service
                mListServiceTimer.cancel();
                return;
            }
            Log.d(TAG, "sending player list");
            if (mMyIP == null) {
                try {
                    mMyIP = InetAddress.getByName(getIPAddress());
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                }
            }
            String message = "" + mPlayerID + "-" + mMyIP.toString();
            for (Map.Entry<InetAddress, Byte> entry : mIPTeamMap.entrySet()) {
                message += "_" + entry.getValue() + "-" + entry.getKey();
            }
            sendUDPBroadcast(UDPMSG_LISTPLAYERS + message);
            mListServiceTimer.cancel();
            mListServiceTimer.purge();
            mListServiceTimer = null;
        }
    }

    Thread UDPBroadcastThread;

    public void startListenForUDPBroadcast() {
        keepListening = true;
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
                while (keepListening) {
                    try {
                        InetAddress broadcastIP = InetAddress.getByName("0.0.0.0");
                        listenForBroadcast(broadcastIP, BROADCAST_LISTEN_PORT, BROADCAST_LISTEN_TIMEOUT_MS);
                        //if (!shouldListenForUDPBroadcast) throw new ThreadDeath();
                    } catch (Exception e) {
                        Log.i(TAG, "no longer listening for UDP broadcasts cause of error " + e.getMessage());
                    }
                }
                if(multicastLock != null && multicastLock.isHeld()) multicastLock.release();
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
                        //if (!shouldListenForUDPBroadcast) throw new ThreadDeath();
                    } catch (Exception e) {
                        Log.i(TAG, "no longer listening for UDP game messages cause of error " + e.getMessage());
                    }
                }
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

    public void startPlayerScan() {
        if (mIPTeamMap == null)
            mIPTeamMap = new HashMap<InetAddress, Byte>();
        else
            mIPTeamMap.clear();
        if (mTeamIPMap == null)
            mTeamIPMap = new HashMap<Byte, InetAddress>();
        else
            mTeamIPMap.clear();
        if (mBroadcastAddress == null)
            mBroadcastAddress = getBroadcastAddress();
        if (mBroadcastAddress == null)
            return;
        keepListening = true;
        mIsListService = true;
        mReadyToScan = 0;
        startListenForUDPBroadcast();
        startListenForUDPGame();
        while(mReadyToScan < 2) {
            try {
                Thread.sleep(100);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        sendUDPMessage(MESSAGE_PREFIX + UDPMSG_SCAN + UDP_VERSION + mGameMode + mPlayerID, mBroadcastAddress, BROADCAST_LISTEN_PORT);
    }

    public void sendUDPBroadcast(String message) {
        if (mBroadcastAddress == null)
            mBroadcastAddress = getBroadcastAddress();
        if (mBroadcastAddress == null)
            return;
        message = MESSAGE_PREFIX + message;
        sendUDPMessage(message, mBroadcastAddress, BROADCAST_LISTEN_PORT);
    }

    public void sendUDPMessage(String message, Byte team) {
        message = MESSAGE_PREFIX + message;
        if (mTeamIPMap.get(team) == null) {
            Log.e(TAG, "cannot send message to unknown team " + team);
            return;
        }
        sendUDPMessage(message, mTeamIPMap.get(team), GAME_LISTEN_PORT);
    }

    public void sendUDPMessageAll(String message) {
        final String prefixedMessage = MESSAGE_PREFIX + message;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                while (mSendingMessage) {
                    try {
                        Thread.sleep(10);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
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
                    }
                }
                mSendingMessage = false;
                Log.d(TAG, "send all finished");
            }
        });
        sendThread.start();
    }

    private void sendUDPMessage(final String message, final InetAddress ip, final Integer port) {
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                try {
                    while (mSendingMessage) {
                        try {
                            Thread.sleep(10);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
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
                }
                mSendingMessage = false;
            }
        });
        sendThread.start();
    }

    void stopListen() {
        keepListening = false;
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
        //startListenForUDPBroadcast();
        Log.i(TAG, "Service started");
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

    public String getGameMode() { return mGameMode; }

}