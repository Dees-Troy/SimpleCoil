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

public class TcpClient extends Service {
    private static final String TAG = "TCPClient";

    public static final String TCP_CLIENT_PONG = "pong";
    private static final int MAX_REJOIN_TRIES = 3;
    private static final int CONNECTION_TIMEOUT_MS = 1000;

    private static volatile boolean keepListening = false;
    private static volatile boolean isListening = false;

    private DataOutputStream out = null;

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

    public void sendTCPMessage(final String message) {
        if (out == null) {
            Log.d(TAG, "Writer is null");
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
        keepListening = true;
        isListening = true;
        Log.d(TAG, "Starting client...");
        Socket s = null;
        InputStream is = null;
        DataInputStream in = null;
        OutputStream os = null;
        int retryCount = MAX_REJOIN_TRIES;
        boolean rejoin = false;
        while (retryCount > 0 && keepListening) {
            try {
                retryCount--;
                s = new Socket();
                s.connect(new InetSocketAddress(Globals.getInstance().mServerIP, TcpServer.TCP_SERVER_PORT), CONNECTION_TIMEOUT_MS);
                is = s.getInputStream();
                in = new DataInputStream(is);
                os = s.getOutputStream();
                out = new DataOutputStream(new BufferedOutputStream(os));
                sendPlayerInfo(rejoin);
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
                                if (message.equals(NetMsg.NETMSG_STARTGAME)) {
                                    Intent intent = new Intent(NetMsg.NETMSG_STARTGAME);
                                    sendBroadcast(intent);
                                    break;
                                } else if (message.equals(NetMsg.NETMSG_SERVERCANCEL)) {
                                    Intent intent = new Intent(NetMsg.NETMSG_SERVERCANCEL);
                                    sendBroadcast(intent);
                                    break;
                                }
                            } else if (message.startsWith(NetMsg.NETMSG_VERSIONERROR)) {
                                Intent intent = new Intent(NetMsg.NETMSG_VERSIONERROR);
                                sendBroadcast(intent);
                                break;
                            } else {
                                Log.d(TAG, "unknown tcp message received");
                            }
                        }
                    } else {
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
            Intent intent = new Intent(NetMsg.NETMSG_SERVERCANCEL);
            sendBroadcast(intent);
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
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void stopTcpClient() { keepListening = false; }

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
        Globals.getInstance().mTeamIPMap.clear();
        Globals.getInstance().mIPTeamMap.clear();
        Globals.getInstance().mTeamPlayerNameMap.clear();
        Globals.getInstance().mGameLimit = FullscreenActivity.GAME_LIMIT_NONE;
        try {
            JSONObject game = new JSONObject(message);
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
            Log.d(TAG, "Found " + players.length() + " players");
            JSONObject limits = game.getJSONObject(TcpServer.JSON_LIMITS);
            if (limits.has(TcpServer.JSON_TIMELIMIT)) {
                Globals.getInstance().mGameLimit += FullscreenActivity.GAME_LIMIT_TIME;
                Globals.getInstance().mTimeLimit = limits.getInt(TcpServer.JSON_TIMELIMIT);
            }
            if (limits.has(TcpServer.JSON_LIVESLIMIT)) {
                Globals.getInstance().mGameLimit += FullscreenActivity.GAME_LIMIT_LIVES;
                Globals.getInstance().mLivesLimit = limits.getInt(TcpServer.JSON_LIVESLIMIT);
            }
            if (limits.has(TcpServer.JSON_SCORELIMIT)) {
                Globals.getInstance().mGameLimit += FullscreenActivity.GAME_LIMIT_SCORE;
                Globals.getInstance().mScoreLimit = limits.getInt(TcpServer.JSON_SCORELIMIT);
            }
            Globals.getInstance().mGameMode = game.getString(TcpServer.JSON_GAMEMODE);
            Intent intent = new Intent(NetMsg.NETMSG_LISTPLAYERS);
            sendBroadcast(intent);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
}
