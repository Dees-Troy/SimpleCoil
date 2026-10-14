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
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.simplecoil.simplecoil.data.model.GpsData;
import com.simplecoil.simplecoil.data.model.PlayerSettings;
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.GameStateRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;
import com.simplecoil.simplecoil.domain.team.TeamMode;
import com.simplecoil.simplecoil.network.ClientEvent;
import com.simplecoil.simplecoil.network.EventBus;
import com.simplecoil.simplecoil.network.Frame;
import com.simplecoil.simplecoil.network.GameUpdate;
import com.simplecoil.simplecoil.network.FrameReader;
import com.simplecoil.simplecoil.network.WireKeys;
import com.simplecoil.simplecoil.network.WireProtocol;
import com.simplecoil.simplecoil.network.client.ClientOutbox;
import com.simplecoil.simplecoil.network.client.ReconnectPolicy;
import com.simplecoil.simplecoil.network.server.SessionPhase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;

public class TcpClient extends Service {
    private static final String TAG = "TCPClient";

    private static final int CONNECTION_TIMEOUT_MS = 1000;
    /** No byte from the server for this long: the connection is dead. */
    static final long SILENCE_DROP_MS = 3500;

    private volatile boolean keepListening = false;
    private volatile boolean isListening = false;
    /** The connected socket; closed by a queued close (N11). */
    private volatile Socket mSocket = null;

    /** The server's session phase and game id, from the latest game info. */
    private volatile SessionPhase mSessionPhase = SessionPhase.LOBBY;
    private volatile long mGameId = 0;

    private final Object mOutLock = new Object();
    private DataOutputStream out = null;
    private final ReconnectPolicy mReconnectPolicy = new ReconnectPolicy();
    /** Wakes the retry wait early when Wi-Fi comes back. */
    private final Object mRetryLock = new Object();
    private boolean mRetryWake = false;
    /** The Wi-Fi network while it is up; sockets are bound to it (§3.6). */
    private volatile Network mWifiNetwork = null;
    private ConnectivityManager.NetworkCallback mWifiCallback = null;

    /** Reliable messages (eliminations) the server hasn't acknowledged yet (§3.4). */
    private final ClientOutbox mOutbox = new ClientOutbox();
    /**
     * Every write goes through this one thread, in the order sent (D3). Thread-per-message used
     * to let queued eliminations overtake the hello they followed.
     */
    private final ExecutorService mWriter = Executors.newSingleThreadExecutor();

    /** Where the client's events go: the app's client bus once created (a private one in tests). */
    private EventBus<ClientEvent> mEvents = new EventBus<>();

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
        mEvents = ((SimpleCoilApp) getApplication()).getContainer().getClientEvents();
        createNotificationChannel();
        registerWifiCallback();
    }

    /**
     * Follows the Wi-Fi network: when it comes back the retry loop wakes at once (Q4), and
     * sockets are bound to it so they don't go out over mobile data when the field's Wi-Fi has
     * no internet.
     */
    private void registerWifiCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null)
            return;
        mWifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "Wi-Fi available");
                mWifiNetwork = network;
                mReconnectPolicy.onNetworkAvailable();
                wakeRetry();
            }

            @Override
            public void onLost(Network network) {
                if (network.equals(mWifiNetwork))
                    mWifiNetwork = null;
            }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), mWifiCallback);
        } catch (Exception e) {
            Log.w(TAG, "Could not watch Wi-Fi: " + e.getMessage());
            mWifiCallback = null;
        }
    }

    private void wakeRetry() {
        synchronized (mRetryLock) {
            mRetryWake = true;
            mRetryLock.notifyAll();
        }
    }

    /** Waits [millis] before the next attempt, or less if Wi-Fi comes back or the client stops. */
    private void waitForRetry(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        synchronized (mRetryLock) {
            while (!mRetryWake && keepListening) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0)
                    break;
                try {
                    mRetryLock.wait(left);
                } catch (InterruptedException e) {
                    break;
                }
            }
            mRetryWake = false;
        }
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
    @SuppressLint("InlinedApi") // ServiceCompat ignores the type below Android 10
    public void startAsForeground(String statusText) {
        Notification notification = createPersistentNotification(statusText);
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
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
        if (mWifiCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            try {
                if (cm != null)
                    cm.unregisterNetworkCallback(mWifiCallback);
            } catch (Exception e) {
                // already gone
            }
            mWifiCallback = null;
        }
        mWriter.shutdown();
    }

    /** Queues [message] for the writer thread. Without a connection it is dropped. */
    public void sendTCPMessage(final String message) {
        mWriter.execute(new Runnable() {
            public void run() {
                write(message);
            }
        });
    }

    private void write(String message) {
        synchronized (mOutLock) {
            if (out == null) {
                Log.d(TAG, "Writer is null, dropping: " + message);
                return;
            }
            try {
                out.writeUTF(message);
                out.flush();
                if (!message.equals(WireProtocol.PONG))
                    Log.i(TAG, "sent: " + message);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Sends [rmsg] (an ELIMINATED message) reliably: it stays in the outbox, whatever happens to
     * this write, until the server acknowledges it, and is replayed in the next hello (D1).
     * Writes are not retried on a live connection; TCP does that.
     */
    public void sendReliable(String rmsg) {
        ClientOutbox.Entry entry = mOutbox.add(rmsg, mGameId);
        try {
            sendTCPMessage(WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON + reliableJson(entry, mOutbox.getGameId()));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /** The reliable envelope: {"rmsg": "ELIMINATED7", "seq": 12, "gameid": 418873301}. */
    static JSONObject reliableJson(ClientOutbox.Entry entry, long gameId) throws JSONException {
        JSONObject envelope = new JSONObject();
        envelope.put(WireKeys.RMSG, entry.getRmsg());
        envelope.put(WireKeys.SEQ, entry.getSeq());
        envelope.put(WireKeys.GAMEID, gameId);
        return envelope;
    }

    @VisibleForTesting
    void setEventsForTest(EventBus<ClientEvent> events) {
        mEvents = events;
    }

    @VisibleForTesting
    ClientOutbox getOutbox() {
        return mOutbox;
    }

    @VisibleForTesting
    void attachOutputForTest(DataOutputStream output) {
        synchronized (mOutLock) {
            out = output;
        }
    }

    /** Waits until everything sent so far has been written (or queued). */
    @VisibleForTesting
    void awaitWritesForTest() throws ExecutionException, InterruptedException {
        mWriter.submit(new Runnable() {
            public void run() {
            }
        }).get();
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
                try {
                    runTcpClient();
                } catch (RuntimeException e) {
                    Log.e(TAG, "Client thread failed", e);
                }
            }
        }).start();
    }

    private void runTcpClient() {
        PlayerRepository.getInstance().getGpsData().clear();
        mOutbox.clear();
        mSessionPhase = SessionPhase.LOBBY; // a new session: nothing to come back to until the server says so
        mGameId = 0;
        keepListening = true;
        isListening = true;
        Log.d(TAG, "Starting client...");
        Socket s = null;
        InputStream is = null;
        OutputStream os = null;
        boolean rejoin = false;
        boolean wasConnected = false;
        boolean firstAttempt = true;
        boolean gaveUp = false;
        boolean cancelled = false;
        mReconnectPolicy.onConnected();
        synchronized (mRetryLock) {
            mRetryWake = false;
        }
        while (keepListening && !cancelled) {
            if (!firstAttempt) {
                // D6, D10: retry for as long as the server could still recognize us, spaced out
                Long delay = mReconnectPolicy.nextDelay(mSessionPhase);
                if (delay == null) {
                    gaveUp = true;
                    break;
                }
                waitForRetry(delay);
                if (!keepListening)
                    break;
            }
            firstAttempt = false;
            try {
                if (wasConnected) {
                    wasConnected = false;
                    mEvents.publish(ClientEvent.NetworkDisconnected.INSTANCE);
                }
                s = new Socket();
                mSocket = s;
                bindToWifi(s);
                s.connect(new InetSocketAddress(GameStateRepository.getInstance().getServerIP(), WireProtocol.TCP_SERVER_PORT), CONNECTION_TIMEOUT_MS);
                s.setSoTimeout(TcpServer.TCP_DEDICATED_READ_WAIT_MS);
                is = s.getInputStream();
                FrameReader reader = new FrameReader(is);
                os = s.getOutputStream();
                synchronized (mOutLock) {
                    out = new DataOutputStream(new BufferedOutputStream(os));
                }
                sendPlayerInfo(rejoin);
                wasConnected = true;
                if (rejoin)
                    mEvents.publish(ClientEvent.NetworkConnected.INSTANCE);
                rejoin = true;
                mReconnectPolicy.onConnected();
                while (keepListening) {
                    // Times out after 100 ms; a half-received frame waits for the next poll (N4)
                    String message = reader.poll();
                    if (message != null) {
                        boolean ended = false;
                        try {
                            ended = handleFrame(message);
                        } catch (RuntimeException e) {
                            // Skip the frame and keep reading (N5)
                            Log.e(TAG, "Error handling '" + message + "'", e);
                        }
                        if (ended) {
                            cancelled = true;
                            break;
                        }
                    } else if (reader.silenceMillis() >= SILENCE_DROP_MS) {
                        Log.d(TAG, "no ping from server, disconnecting");
                        break;
                    }
                }
            } catch (UnknownHostException e) {
                e.printStackTrace();
            } catch (ConnectException e) {
                Log.d(TAG, "Server not running: " + e.getLocalizedMessage());
            } catch (IOException e) {
                e.printStackTrace();
            } catch (RuntimeException e) {
                // Drop the connection; the reconnect policy decides what happens next (N5)
                Log.e(TAG, "Reader failed, dropping the connection", e);
            } finally {
                synchronized (mOutLock) {
                    try { if (out != null) out.close(); } catch (Exception e) { /* ignored */ }
                    out = null;
                }
                mSocket = null;
                try { if (s != null) s.close(); } catch (Exception e) { /* ignored */ }
                try { if (is != null) is.close(); } catch (Exception e) { /* ignored */ }
                try { if (os != null) os.close(); } catch (Exception e) { /* ignored */ }
            }
        }
        if (gaveUp && keepListening) {
            mEvents.publish(ClientEvent.ServerCancel.INSTANCE);
        }
        Log.d(TAG, "Client stopping");
        // Under the startTcpClient lock so a client started right after this keeps its notification.
        synchronized (this) {
            stopAsForeground();
            keepListening = false;
            isListening = false;
        }
    }

    /**
     * Handles one frame from the server on the reader thread. Returns true when the server ended
     * the session (SERVERCANCEL or VERSIONERROR) and the client must not reconnect.
     */
    @VisibleForTesting
    boolean handleFrame(String raw) {
        Frame frame = Frame.parse(raw);
        if (frame.kind == Frame.Kind.PING) {
            sendTCPMessage(WireProtocol.PONG);
            return false;
        }
        Log.i(TAG, "received: '" + raw + "'");
        if (frame.kind == Frame.Kind.JSON) {
            parseGameInfo(frame.body);
        } else if (frame.kind == Frame.Kind.MESG) {
            String message = frame.body;
            if (message.startsWith(WireProtocol.NETMSG_ELIMINATED)) {
                Byte id = parseId(message.substring(WireProtocol.NETMSG_ELIMINATED.length()));
                if (id != null)
                    mEvents.publish(new ClientEvent.Eliminated(id));
            } else if (message.startsWith(WireProtocol.NETMSG_HIT)) {
                // Without an id the screen names player 0, as the broadcast's default did
                Byte id = parseId(message.substring(WireProtocol.NETMSG_HIT.length()));
                mEvents.publish(new ClientEvent.Hit(id != null ? id : 0));
            } else if (message.startsWith(WireProtocol.NETMSG_OUT)) {
                Byte id = parseId(message.substring(WireProtocol.NETMSG_OUT.length()));
                mEvents.publish(new ClientEvent.Out(id != null ? id : 0));
            } else if (message.equals(WireProtocol.NETMSG_SHOTFIRED)) {
                mEvents.publish(ClientEvent.ShotFired.INSTANCE);
            } else if (message.startsWith(WireProtocol.NETMSG_SERVERREPLY)) {
                Byte assignedID = parseId(message.substring(WireProtocol.NETMSG_SERVERREPLY.length()));
                if (assignedID != null)
                    PlayerRepository.getInstance().setPlayerID(assignedID);
                mEvents.publish(new ClientEvent.ServerReply(assignedID));
            } else if (message.equals(WireProtocol.NETMSG_TEAMELIMINATED)) {
                mEvents.publish(ClientEvent.TeamEliminated.INSTANCE);
            } else if (message.equals(WireProtocol.NETMSG_ENDGAME)) {
                mEvents.publish(ClientEvent.EndGame.INSTANCE);
            } else if (message.equals(WireProtocol.NETMSG_STARTGAME)) {
                mEvents.publish(ClientEvent.StartGame.INSTANCE);
            } else if (message.equals(WireProtocol.NETMSG_SERVERCANCEL)) {
                mEvents.publish(ClientEvent.ServerCancel.INSTANCE);
                return true;
            } else if (message.startsWith(WireProtocol.NETMSG_ACK)) {
                try {
                    mOutbox.ack(Integer.parseInt(message.substring(WireProtocol.NETMSG_ACK.length())));
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                }
            } else if (message.startsWith(WireProtocol.NETMSG_ERROR)) {
                mEvents.publish(new ClientEvent.Error(message.substring(WireProtocol.NETMSG_ERROR.length())));
            }
        } else if (frame.kind == Frame.Kind.VERSION_ERROR) {
            mEvents.publish(ClientEvent.VersionError.INSTANCE);
            return true;
        } else {
            Log.d(TAG, "unknown tcp message received");
        }
        return false;
    }

    /** A player id from a command's argument, or null (logged) when it is missing or not a number. */
    private static Byte parseId(String text) {
        if (text.isEmpty())
            return null;
        try {
            return (byte) Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            Log.w(TAG, "Bad player id '" + text + "'");
            return null;
        }
    }

    /** Routes [socket] over Wi-Fi even when Android made mobile data the default network. */
    private void bindToWifi(Socket socket) {
        Network wifi = mWifiNetwork;
        if (wifi == null)
            return;
        try {
            wifi.bindSocket(socket);
        } catch (IOException e) {
            Log.w(TAG, "Could not bind to Wi-Fi: " + e.getMessage());
        }
    }

    public void sendPlayerNameChange() {
        try {
            JSONObject playerInfo = new JSONObject();
            playerInfo.put(WireKeys.PLAYERID, (int) PlayerRepository.getInstance().getPlayerID());
            playerInfo.put(WireKeys.PLAYERNAMECHANGE, PlayerRepository.getInstance().getPlayerName());
            String message = WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON + playerInfo.toString();
            sendTCPMessage(message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void sendPlayerInfo(boolean rejoin) {
        if (rejoin)
            Log.d(TAG, "Attempting to rejoin server");
        try {
            JSONObject playerInfo = helloJson(PlayerRepository.getInstance().getPlayerID(), PlayerRepository.getInstance().getPlayerName(),
                    PlayerRepository.getInstance().getDeviceAddress(), rejoin, mOutbox);
            String message = WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON + playerInfo.toString();
            sendTCPMessage(message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        if (PlayerRepository.getInstance().getPairedGrenadeID() != 0)
            sendPlayerGrenade();
    }

    /**
     * The hello: who we are, and the reliable messages not acknowledged yet. They ride in the
     * hello so the server applies them once it knows who we are and before it builds our
     * snapshot (D2, D3).
     */
    static JSONObject helloJson(byte playerId, String name, String mac, boolean rejoin, ClientOutbox outbox) throws JSONException {
        JSONObject playerInfo = new JSONObject();
        playerInfo.put(WireKeys.PLAYERID, (int) playerId);
        playerInfo.put(WireKeys.PLAYERNAME, name);
        if (mac != null && !mac.isEmpty()) {
            playerInfo.put(WireKeys.BLASTER_MAC, mac);
        }
        if (rejoin) {
            playerInfo.put(WireKeys.REJOIN, true);
        }
        if (outbox.getGameId() != 0)
            playerInfo.put(WireKeys.GAMEID, outbox.getGameId());
        JSONArray pending = new JSONArray();
        for (ClientOutbox.Entry entry : outbox.pending()) {
            JSONObject item = new JSONObject();
            item.put(WireKeys.RMSG, entry.getRmsg());
            item.put(WireKeys.SEQ, entry.getSeq());
            pending.put(item);
        }
        if (pending.length() > 0) {
            Log.d(TAG, "Replaying " + pending.length() + " unacknowledged messages");
            playerInfo.put(WireKeys.PENDING, pending);
        }
        return playerInfo;
    }

    public void sendPlayerSettings() {
        try {
            JSONObject playerSettings = new JSONObject();
            playerSettings.put(WireKeys.PLAYERSETTINGS, true);
            playerSettings.put(WireKeys.PLAYERID, (int) PlayerRepository.getInstance().getPlayerID());
            playerSettings.put(WireKeys.HEALTH, GameSettingsRepository.getInstance().getFullHealth());
            playerSettings.put(WireKeys.RELOAD_SHOTS, GameSettingsRepository.getInstance().getFullReload());
            playerSettings.put(WireKeys.RELOAD_TIME, GameSettingsRepository.getInstance().getReloadTime());
            playerSettings.put(WireKeys.RELOAD_ON_EMPTY, GameSettingsRepository.getInstance().isReloadOnEmpty());
            playerSettings.put(WireKeys.MANUAL_RESPAWN, GameSettingsRepository.getInstance().isManualRespawn());
            playerSettings.put(WireKeys.SPAWN_TIME, GameSettingsRepository.getInstance().getRespawnTime());
            playerSettings.put(WireKeys.RESPAWN_SHIELD_TIME, GameSettingsRepository.getInstance().getRespawnShieldTime());
            playerSettings.put(WireKeys.DAMAGE, GameSettingsRepository.getInstance().getDamage());
            if (GameSettingsRepository.getInstance().isOverrideLives())
                playerSettings.put(WireKeys.LIVESLIMIT, GameSettingsRepository.getInstance().getOverrideLivesVal());
            playerSettings.put(WireKeys.SHOT_MODE_SINGLE, GameSettingsRepository.getInstance().isAllowSingleShotMode());
            playerSettings.put(WireKeys.SHOT_MODE_BURST3, GameSettingsRepository.getInstance().isAllowBurst3ShotMode());
            playerSettings.put(WireKeys.SHOT_MODE_AUTO, GameSettingsRepository.getInstance().isAllowAutoShotMode());
            playerSettings.put(WireKeys.FIRING_MODE, GameSettingsRepository.getInstance().getCurrentFiringMode());
            String message = WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON + playerSettings.toString();
            sendTCPMessage(message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void sendPlayerGrenade() {
        try {
            JSONObject playerGrenade = new JSONObject();
            playerGrenade.put(WireKeys.PLAYERID, (int) PlayerRepository.getInstance().getPlayerID());
            playerGrenade.put(WireKeys.PAIRED_GRENADE_ID, (int) PlayerRepository.getInstance().getPairedGrenadeID());
            String message = WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON + playerGrenade.toString();
            sendTCPMessage(message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /** Reports our position (the map's location updates) to the server. */
    public void sendLocation(double latitude, double longitude) {
        try {
            JSONObject gpsUpdate = new JSONObject();
            gpsUpdate.put(WireKeys.GPSLONGITUDE, longitude);
            gpsUpdate.put(WireKeys.GPSLATITUDE, latitude);
            sendTCPMessage(WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON + gpsUpdate.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * Stops the client once everything sent so far has been written: the stop is queued on the
     * writer, behind the frames already there (N11).
     */
    public void stopTcpClient() {
        try {
            mWriter.execute(new Runnable() {
                public void run() {
                    stopNow();
                }
            });
        } catch (RejectedExecutionException e) {
            stopNow(); // the service is gone, and its writer with it
        }
    }

    private void stopNow() {
        keepListening = false;
        wakeRetry();
        Socket socket = mSocket;
        if (socket != null) {
            try {
                socket.close(); // the reader stops at once
            } catch (IOException e) {
                // already closed
            }
        }
    }

    /** Tells the server we are leaving, then stops: LEAVE is the last frame written. */
    public void leaveServer() {
        mOutbox.clear();
        if (keepListening) {
            String message = WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_MESG + WireProtocol.NETMSG_LEAVE;
            sendTCPMessage(message);
            stopTcpClient();
        }
    }

    private void parseGameInfo(String message) {
        try {
            JSONObject game = new JSONObject(message);
            if (game.has(WireKeys.GRENADE_PAIRINGS)) {
                PlayerRepository repo = PlayerRepository.getInstance();
                synchronized (repo) {
                    repo.clearGrenadePairings();
                    JSONArray grenadePairings = game.getJSONArray(WireKeys.GRENADE_PAIRINGS);
                    for (int x = 0; x < grenadePairings.length(); x++) {
                        JSONObject grenadePairing = grenadePairings.getJSONObject(x);
                        repo.pairGrenade(
                                grenadePairing.getInt(WireKeys.PAIRED_GRENADE_ID),
                                grenadePairing.getInt(WireKeys.PLAYERID)
                        );
                    }
                }
                return;
            }
            if (game.has(WireKeys.GPSUPDATE)) {
                JSONArray updates = game.getJSONArray(WireKeys.GPSUPDATE);
                boolean fullUpdate = false;
                synchronized (PlayerRepository.getInstance()) {
                    if (game.has(WireKeys.GPSFULLUPDATE)) {
                        PlayerRepository.getInstance().getGpsData().clear();
                        fullUpdate = true;
                    }
                    for (int x = 0; x < updates.length(); x++) {
                        JSONObject update = updates.getJSONObject(x);
                        Byte playerID = (byte) update.getInt(WireKeys.PLAYERID);
                        if (playerID != PlayerRepository.getInstance().getPlayerID()) {
                            double longitude = update.getDouble(WireKeys.GPSLONGITUDE);
                            double latitude = update.getDouble(WireKeys.GPSLATITUDE);
                            GpsData gps = PlayerRepository.getInstance().getGpsData().get(playerID);
                            if (gps == null) {
                                gps = new GpsData();
                                gps.longitude = longitude;
                                gps.latitude = latitude;
                                gps.team = update.getInt(WireKeys.TEAM);
                                PlayerRepository.getInstance().getGpsData().put(playerID, gps);
                            } else {
                                gps.longitude = longitude;
                                gps.latitude = latitude;
                            }
                            gps.hasUpdate = true; // Anything that the server sends us is considered an update
                        }
                    }
                }
                mEvents.publish(new ClientEvent.GpsData(fullUpdate));
                return;
            }
            if (game.has(WireKeys.PLAYERDATA)) {
                mEvents.publish(new ClientEvent.PlayerDataUpdate(message));
                return;
            }
            if (game.has(WireKeys.PLAYERSETTINGS)) {
                JSONArray settings = game.getJSONArray(WireKeys.PLAYERSETTINGS);
                synchronized (PlayerRepository.getInstance()) {
                    for (byte x = 0; x < settings.length(); x++) {
                        JSONObject setting = settings.getJSONObject(x);
                        Byte playerID = (byte) setting.getInt(WireKeys.PLAYERID);
                        PlayerSettings playerSettings = PlayerRepository.getInstance().getPlayerSettings().get(playerID);
                        if (playerSettings == null) {
                            playerSettings = new PlayerSettings();
                            PlayerRepository.getInstance().getPlayerSettings().put(playerID, playerSettings);
                        }
                        playerSettings.health = setting.getInt(WireKeys.HEALTH);
                        playerSettings.shots = (byte)setting.getInt(WireKeys.RELOAD_SHOTS);
                        playerSettings.reloadTime = setting.getLong(WireKeys.RELOAD_TIME);
                        playerSettings.reloadOnEmpty = setting.getBoolean(WireKeys.RELOAD_ON_EMPTY);
                        playerSettings.manualRespawn = setting.optBoolean(WireKeys.MANUAL_RESPAWN, false);
                        playerSettings.spawnTime = setting.getLong(WireKeys.SPAWN_TIME);
                        if (setting.has(WireKeys.RESPAWN_SHIELD_TIME)) {
                            playerSettings.respawnShieldTime = setting.getLong(WireKeys.RESPAWN_SHIELD_TIME);
                        }
                        playerSettings.damage = setting.getInt(WireKeys.DAMAGE);
                        if (setting.has(WireKeys.LIVESLIMIT)) {
                            playerSettings.overrideLives = true;
                            playerSettings.lives = setting.getInt(WireKeys.LIVESLIMIT);
                        } else {
                            playerSettings.overrideLives = false;
                            playerSettings.lives = 0;
                        }
                        playerSettings.allowShotModeSingle = setting.getBoolean(WireKeys.SHOT_MODE_SINGLE);
                        playerSettings.allowShotModeBurst3 = setting.getBoolean(WireKeys.SHOT_MODE_BURST3);
                        playerSettings.allowShotModeAuto = setting.getBoolean(WireKeys.SHOT_MODE_AUTO);
                        playerSettings.firingMode = setting.getInt(WireKeys.FIRING_MODE);
                        if (setting.has(WireKeys.RECOIL_SETTING)) {
                            playerSettings.recoilSetting = setting.getInt(WireKeys.RECOIL_SETTING);
                        }
                        if (setting.has(WireKeys.IS_ADMIN)) {
                            playerSettings.isAdmin = setting.getBoolean(WireKeys.IS_ADMIN);
                        }
                        if (playerID == PlayerRepository.getInstance().getPlayerID()) {
                            GameSettingsRepository.getInstance().setFullHealth(playerSettings.health);
                            GameSettingsRepository.getInstance().setFullReload(playerSettings.shots);
                            GameSettingsRepository.getInstance().setReloadTime(playerSettings.reloadTime);
                            GameSettingsRepository.getInstance().setReloadOnEmpty(playerSettings.reloadOnEmpty);
                            GameSettingsRepository.getInstance().setManualRespawn(playerSettings.manualRespawn);
                            GameSettingsRepository.getInstance().setRespawnTime(playerSettings.spawnTime);
                            GameSettingsRepository.getInstance().setRespawnShieldTime(playerSettings.respawnShieldTime);
                            GameSettingsRepository.getInstance().setDamage(playerSettings.damage);
                            GameSettingsRepository.getInstance().setOverrideLives(playerSettings.overrideLives);
                            GameSettingsRepository.getInstance().setOverrideLivesVal(playerSettings.lives);
                            GameSettingsRepository.getInstance().setAllowSingleShotMode(playerSettings.allowShotModeSingle);
                            GameSettingsRepository.getInstance().setAllowBurst3ShotMode(playerSettings.allowShotModeBurst3);
                            GameSettingsRepository.getInstance().setAllowAutoShotMode(playerSettings.allowShotModeAuto);
                            GameSettingsRepository.getInstance().setCurrentFiringMode(playerSettings.firingMode);
                            GameSettingsRepository.getInstance().setServerRecoilSetting(playerSettings.recoilSetting);
                            PlayerRepository.getInstance().setAdmin(playerSettings.isAdmin);
                        }
                    }
                }
                GameSettingsRepository.getInstance().setAllowPlayerSettings(game.getBoolean(WireKeys.ALLOWPLAYERSETTINGS));
                mEvents.publish(ClientEvent.PlayerSettingsUpdate.INSTANCE);
            }
            if (game.has(WireKeys.PLAYERS)) {
                synchronized (PlayerRepository.getInstance()) {
                    PlayerRepository.getInstance().clearAllPlayers();
                    GameSettingsRepository.getInstance().setGameLimit(GameSettingsRepository.GAME_LIMIT_NONE);
                    JSONArray players = game.getJSONArray(WireKeys.PLAYERS);
                    for (int x = 0; x < players.length(); x++) {
                        JSONObject player = players.getJSONObject(x);
                        Byte playerID = (byte) player.getInt(WireKeys.PLAYERID);
                        String playerName = player.getString(WireKeys.PLAYERNAME);
                        if (playerID == PlayerRepository.getInstance().getPlayerID()) {
                            if (!playerName.isEmpty() && !playerName.equals(PlayerRepository.getInstance().getPlayerName())) {
                                PlayerRepository.getInstance().setPlayerName(playerName);
                            }
                        } else {
                            InetAddress playerIP = null;
                            String ip = player.getString(WireKeys.PLAYERIP);
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
                }
                JSONArray players = game.getJSONArray(WireKeys.PLAYERS);
                Log.d(TAG, "Found " + players.length() + " players");
                JSONObject limits = game.getJSONObject(WireKeys.LIMITS);
                int gameLimit = GameSettingsRepository.getInstance().getGameLimit();
                if (limits.has(WireKeys.TIMELIMIT)) {
                    gameLimit += GameSettingsRepository.GAME_LIMIT_TIME;
                    GameSettingsRepository.getInstance().setTimeLimit(limits.getInt(WireKeys.TIMELIMIT));
                }
                if (limits.has(WireKeys.LIVESLIMIT)) {
                    gameLimit += GameSettingsRepository.GAME_LIMIT_LIVES;
                    GameSettingsRepository.getInstance().setLivesLimit(limits.getInt(WireKeys.LIVESLIMIT));
                    Log.e(TAG, "tcpClient set lives limit to " + GameSettingsRepository.getInstance().getLivesLimit());
                }
                if (limits.has(WireKeys.SCORELIMIT)) {
                    gameLimit += GameSettingsRepository.GAME_LIMIT_SCORE;
                    GameSettingsRepository.getInstance().setScoreLimit(limits.getInt(WireKeys.SCORELIMIT));
                }
                GameSettingsRepository.getInstance().setGameLimit(gameLimit);
                TeamMode teamMode = TeamMode.fromWire(game.getInt(WireKeys.GAMEMODE));
                int[] sizes = null;
                if (game.has(WireKeys.TEAMSIZES)) {
                    JSONArray teamSizesArr = game.getJSONArray(WireKeys.TEAMSIZES);
                    sizes = new int[teamSizesArr.length()];
                    for (int i = 0; i < teamSizesArr.length(); i++) {
                        sizes[i] = teamSizesArr.getInt(i);
                    }
                }
                if (teamMode != null) {
                    GameSettingsRepository.getInstance().setTeamModeAndSizes(teamMode, sizes);
                } else {
                    Log.w(TAG, "Unknown team mode from the server: " + game.getInt(WireKeys.GAMEMODE));
                }
                GameSettingsRepository.getInstance().setUseGPS(game.has(WireKeys.USEGPS));
                if (GameSettingsRepository.getInstance().isUseGPS()) {
                    GameSettingsRepository.getInstance().setGpsMode(game.getInt(WireKeys.USEGPS));
                }
                if (game.has(WireKeys.START_TIME)) {
                    GameSettingsRepository.getInstance().setStartTime(game.getLong(WireKeys.START_TIME));
                    GameSettingsRepository.getInstance().setStartTime(GameSettingsRepository.getInstance().getStartTime());
                }
                GameSettingsRepository.getInstance().setOnlyServerSettings(game.getBoolean(WireKeys.ONLY_SERVER_SETTINGS));
                if (game.has(WireKeys.ALLOW_CHOOSE_TEAMS)) {
                    boolean allowChooseTeams = game.getBoolean(WireKeys.ALLOW_CHOOSE_TEAMS);
                    GameSettingsRepository.getInstance().setAllowChooseTeams(allowChooseTeams);
                    GameSettingsRepository.getInstance().setAllowChooseTeams(allowChooseTeams);
                }
                Integer sessionPhase = null;
                Long gameId = null;
                if (game.has(WireKeys.SESSIONPHASE)) {
                    mSessionPhase = SessionPhase.fromWire(game.getInt(WireKeys.SESSIONPHASE));
                    mGameId = game.optLong(WireKeys.GAMEID, 0);
                    mOutbox.onGameId(mGameId); // a new game, or a reset: the old entries are void
                    sessionPhase = mSessionPhase.wireValue;
                    gameId = mGameId;
                }
                GameUpdate gameUpdate = null;
                if (game.has(WireKeys.PLAYERGAMEUPDATE)) {
                    JSONObject playerGameUpdate = game.getJSONObject(WireKeys.PLAYERGAMEUPDATE);
                    // D2: the server's count plus the eliminations it hasn't applied yet
                    int eliminations = mOutbox.reconcile(playerGameUpdate.getInt(WireKeys.PLAYERELIMINATED),
                            playerGameUpdate.optInt(WireKeys.ACKSEQ, 0));
                    // A missing team score is 0 and a missing time is -1 (no time limit)
                    gameUpdate = new GameUpdate(playerGameUpdate.getInt(WireKeys.PLAYERPOINTS), eliminations,
                            playerGameUpdate.optInt(WireKeys.TEAMPOINTS, 0),
                            playerGameUpdate.optLong(WireKeys.TIMEREMAINING, -1));
                }
                Integer gameState = game.has(WireKeys.GAMESTATE) ? game.getInt(WireKeys.GAMESTATE) : null;
                mEvents.publish(new ClientEvent.ListPlayers(gameUpdate, gameState, sessionPhase, gameId));
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
}
