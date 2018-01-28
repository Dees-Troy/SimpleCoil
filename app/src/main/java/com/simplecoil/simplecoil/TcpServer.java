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
import java.util.Map;

public class TcpServer extends Service {
    private static final String TAG = "TCPServer";

    public static final int TCP_SERVER_PORT = 17510;
    // Amount of time to sleep before checking if data is available. Lower is more responsive but may gobble up more CPU time
    public static final int TCP_READ_WAIT_MS = 200;
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

    private static volatile boolean keepListening = false;
    private volatile boolean mAccessClients = false;
    private volatile Map<Integer, ClientData> mClientData = null;

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

    private void waitForClientAccess() {
        while (mAccessClients)
            sleep(50);
        mAccessClients = true;
    }

    public void sendTCPMessage(final String message) {
        if (mClientData == null || mClientData.size() == 0)
            return;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                waitForClientAccess();
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    entry.getValue().sendTCPMessage(message);
                }
                mAccessClients = false;
            }
        });
        sendThread.start();
    }

    public void startGame() {
        if (mClientData == null || mClientData.size() == 0)
            return;
        Thread sendThread = new Thread(new Runnable() {
            public void run() {
                String message = TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_STARTGAME;
                waitForClientAccess();
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    entry.getValue().sendTCPMessage(message);
                }
                mAccessClients = false;
                Intent intent = new Intent(NetMsg.NETMSG_STARTGAME);
                sendBroadcast(intent);
                keepListening = false;
            }
        });
        sendThread.start();
    }

    public void sendAllGameInfo() {
        try {
            JSONArray players = new JSONArray();
            for (Map.Entry<Byte, InetAddress> entry : Globals.getInstance().mTeamIPMap.entrySet()) {
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERNAME, Globals.getInstance().getPlayerName(entry.getKey()));
                player.put(JSON_PLAYERID, entry.getKey());
                player.put(JSON_PLAYERIP, entry.getValue());
                players.put(player);
            }
            {
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERNAME, Globals.getInstance().mPlayerName);
                player.put(JSON_PLAYERID, Globals.getInstance().mPlayerID);
                player.put(JSON_PLAYERIP, Globals.getIPAddressStr());
                players.put(player);
            }
            JSONObject game = new JSONObject();
            game.put(JSON_PLAYERS, players);
            JSONObject limits = new JSONObject();
            if ((Globals.getInstance().mGameLimit & FullscreenActivity.GAME_LIMIT_TIME) != 0) {
                limits.put(JSON_TIMELIMIT, Globals.getInstance().mTimeLimit);
            }
            if ((Globals.getInstance().mGameLimit & FullscreenActivity.GAME_LIMIT_LIVES) != 0) {
                limits.put(JSON_LIVESLIMIT, Globals.getInstance().mLivesLimit);
            }
            if ((Globals.getInstance().mGameLimit & FullscreenActivity.GAME_LIMIT_SCORE) != 0) {
                limits.put(JSON_SCORELIMIT, Globals.getInstance().mScoreLimit);
            }
            game.put(JSON_LIMITS, limits);
            game.put(JSON_GAMEMODE, Globals.getInstance().mGameMode);
            String message = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
            sendTCPMessage(message);
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
                    waitForClientAccess();
                    mClientData.put(clientID, client);
                    mAccessClients = false;
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

    private class ClientData {
        private Socket clientSocket = null;
        private int clientID = -1;
        private byte mPlayerID = 0;
        private int noReadCount = 0;
        private InputStream is = null;
        private OutputStream os = null;
        private DataInputStream in = null;
        private DataOutputStream out = null;

        public void initialize(Socket s, int cID) {
            clientSocket = s;
            clientID = cID;
            noReadCount = 0;
            try {
                is = clientSocket.getInputStream();
                in = new DataInputStream(is);
                os = clientSocket.getOutputStream();
                out = new DataOutputStream(os);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void sendTCPMessage(final String message) {
            try {
                out.writeUTF(message);
                out.flush();
                if (!message.equals(TcpServer.TCP_SERVER_PING))
                    Log.i(TAG, "sent(" + clientID + "): " + message);
            } catch (IOException e) {
                //Log.e(TAG, "IO Error:", e);
                e.printStackTrace();
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
        }
    }

    private class ClientThread implements Runnable {
        public void run() {
            Log.i(TAG, "Running TCP listening server for clients");
            //receive a message
            while (keepListening) {
                waitForClientAccess();
                long startTime = System.currentTimeMillis();
                for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                    try {
                        if (entry.getValue().in.available() > 0) {
                            entry.getValue().noReadCount = 0;
                            String message = entry.getValue().in.readUTF();
                            if (!message.equals(TcpClient.TCP_CLIENT_PONG)) {
                                Log.i(TAG, "received: '" + message + "' from " + entry.getValue().clientID);
                                if (message.startsWith(TCPMESSAGE_PREFIX)) {
                                    if (message.substring(TCPMESSAGE_PREFIX.length(), TCPMESSAGE_PREFIX.length() + TCPPREFIX_JSON.length()).equals(TCPPREFIX_JSON)) {
                                        message = message.substring(TCPMESSAGE_PREFIX.length() + TCPPREFIX_JSON.length());
                                        parsePlayerInfo(message, entry.getValue());
                                        break;
                                    } else if (message.substring(TCPMESSAGE_PREFIX.length(), TCPMESSAGE_PREFIX.length() + TCPPREFIX_MESG.length()).equals(TCPPREFIX_MESG)) {
                                        message = message.substring(TCPMESSAGE_PREFIX.length() + TCPPREFIX_JSON.length());
                                        if (message.equals(NetMsg.NETMSG_LEAVE)) {
                                            removeClient(entry.getValue(), entry.getKey());
                                            break;
                                        } else if (message.equals(NetMsg.NETMSG_STARTGAME)) {
                                            startGame();
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
                            if (entry.getValue().noReadCount >= 35) {
                                Log.d(TAG, "no reply so dropping client " + entry.getValue().clientID);
                                removeClient(entry.getValue(), entry.getKey());
                                break;
                            } else if (entry.getValue().noReadCount == 10 || entry.getValue().noReadCount == 20) {
                                entry.getValue().sendTCPMessage(TCP_SERVER_PING);
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        removeClient(entry.getValue(), entry.getKey());
                        break;
                    }
                }
                mAccessClients = false;
                long sleepTime = TCP_READ_WAIT_MS - (System.currentTimeMillis() - startTime);
                //Log.d(TAG, "Took " + (System.currentTimeMillis() - startTime) + "ms to service " + mClientData.size() + " clients");
                sleep(sleepTime);
            }
            waitForClientAccess();
            for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                entry.getValue().close();
            }
            mAccessClients = false;
        }

        private void parsePlayerInfo(String message, ClientData client) {
            try {
                JSONObject player = new JSONObject(message);
                boolean rejoin = player.has(JSON_REJOIN);
                byte id = (byte) player.getInt(JSON_PLAYERID);
                if (rejoin) {
                    for (Map.Entry<Integer, ClientData> entry : mClientData.entrySet()) {
                        if (entry.getValue().mPlayerID == id) {
                            Log.d(TAG, "rejoining " + client.clientID + " to " + entry.getValue().clientID);
                            entry.getValue().rejoin(client.clientSocket);
                            mClientData.remove(client.clientID);
                            return;
                        }
                    }
                    Log.d(TAG, "rejoined client " + client.clientID + " not present so adding as a new player");
                }
                client.mPlayerID = id;
                String playerName = player.getString(JSON_PLAYERNAME);
                InetAddress inetAddress = client.clientSocket.getInetAddress();
                Log.d(TAG, "client " + client.clientID + " player '" + playerName + "' (" + client.mPlayerID + ") found at " + inetAddress.toString());
                Globals.getInstance().mTeamIPMap.put(client.mPlayerID, inetAddress);
                Globals.getInstance().mIPTeamMap.put(inetAddress, client.mPlayerID);
                Globals.getInstance().mTeamPlayerNameMap.put(client.mPlayerID, playerName);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            sendAllGameInfo();
            Intent intent = new Intent(NetMsg.NETMSG_JOIN);
            sendBroadcast(intent);
        }

        private void removeClient(ClientData client, Integer clientID) {
            if (client.mPlayerID != 0) {
                Globals.getInstance().mTeamPlayerNameMap.remove(client.mPlayerID);
                Globals.getInstance().mIPTeamMap.remove(client.clientSocket.getInetAddress());
                Globals.getInstance().mTeamIPMap.remove(client.mPlayerID);
                sendAllGameInfo();
                Intent intent = new Intent(NetMsg.NETMSG_LEAVE);
                sendBroadcast(intent);
            }
            client.close();
            mClientData.remove(clientID);
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
}
