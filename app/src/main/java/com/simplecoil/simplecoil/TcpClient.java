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
import com.simplecoil.simplecoil.network.ClientCommand;
import com.simplecoil.simplecoil.network.ClientEvent;
import com.simplecoil.simplecoil.network.ClientMessage;
import com.simplecoil.simplecoil.network.EventBus;
import com.simplecoil.simplecoil.network.Frame;
import com.simplecoil.simplecoil.network.FrameReader;
import com.simplecoil.simplecoil.network.GameUpdate;
import com.simplecoil.simplecoil.network.ServerCommand;
import com.simplecoil.simplecoil.network.ServerMessage;
import com.simplecoil.simplecoil.network.WireCodec;
import com.simplecoil.simplecoil.network.WireGpsPosition;
import com.simplecoil.simplecoil.network.WireGrenadePairing;
import com.simplecoil.simplecoil.network.WireLimits;
import com.simplecoil.simplecoil.network.WirePending;
import com.simplecoil.simplecoil.network.WirePlayer;
import com.simplecoil.simplecoil.network.WirePlayerGameUpdate;
import com.simplecoil.simplecoil.network.WirePlayerSettings;
import com.simplecoil.simplecoil.network.WireProtocol;
import com.simplecoil.simplecoil.network.client.ClientOutbox;
import com.simplecoil.simplecoil.network.client.ReconnectPolicy;
import com.simplecoil.simplecoil.network.server.SessionPhase;

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
import java.util.ArrayList;
import java.util.List;
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
        sendTCPMessage(WireCodec.encode(new ClientMessage.Reliable(entry.getRmsg(), entry.getSeq(), mOutbox.getGameId())));
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
            ServerMessage message = WireCodec.decodeServer(frame);
            if (message != null)
                handleMessage(message, frame.body);
        } else if (frame.kind == Frame.Kind.MESG) {
            return handleCommand(WireCodec.decodeServerCommand(frame.body));
        } else if (frame.kind == Frame.Kind.VERSION_ERROR) {
            mEvents.publish(ClientEvent.VersionError.INSTANCE);
            return true;
        } else {
            Log.d(TAG, "unknown tcp message received");
        }
        return false;
    }

    /** Handles a command from the server; true when it ended the session (SERVERCANCEL). */
    private boolean handleCommand(ServerCommand command) {
        if (command instanceof ServerCommand.Eliminated) {
            Byte id = ((ServerCommand.Eliminated) command).getVictimId();
            if (id != null)
                mEvents.publish(new ClientEvent.Eliminated(id));
        } else if (command instanceof ServerCommand.Hit) {
            // Without an id the screen names player 0, as the broadcast's default did
            Byte id = ((ServerCommand.Hit) command).getTargetId();
            mEvents.publish(new ClientEvent.Hit(id != null ? id : 0));
        } else if (command instanceof ServerCommand.Out) {
            Byte id = ((ServerCommand.Out) command).getTargetId();
            mEvents.publish(new ClientEvent.Out(id != null ? id : 0));
        } else if (command instanceof ServerCommand.ShotFired) {
            mEvents.publish(ClientEvent.ShotFired.INSTANCE);
        } else if (command instanceof ServerCommand.ServerReply) {
            Byte assignedID = ((ServerCommand.ServerReply) command).getPlayerId();
            if (assignedID != null)
                PlayerRepository.getInstance().setPlayerID(assignedID);
            mEvents.publish(new ClientEvent.ServerReply(assignedID));
        } else if (command instanceof ServerCommand.TeamEliminated) {
            mEvents.publish(ClientEvent.TeamEliminated.INSTANCE);
        } else if (command instanceof ServerCommand.EndGame) {
            mEvents.publish(ClientEvent.EndGame.INSTANCE);
        } else if (command instanceof ServerCommand.StartGame) {
            mEvents.publish(ClientEvent.StartGame.INSTANCE);
        } else if (command instanceof ServerCommand.ServerCancel) {
            mEvents.publish(ClientEvent.ServerCancel.INSTANCE);
            return true;
        } else if (command instanceof ServerCommand.Ack) {
            Integer seq = ((ServerCommand.Ack) command).getSeq();
            if (seq != null)
                mOutbox.ack(seq);
        } else if (command instanceof ServerCommand.Error) {
            mEvents.publish(new ClientEvent.Error(((ServerCommand.Error) command).getMessage()));
        }
        return false;
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
        PlayerRepository players = PlayerRepository.getInstance();
        sendTCPMessage(WireCodec.encode(new ClientMessage.NameChange(players.getPlayerID(), players.getPlayerName())));
    }

    public void sendPlayerInfo(boolean rejoin) {
        if (rejoin)
            Log.d(TAG, "Attempting to rejoin server");
        PlayerRepository players = PlayerRepository.getInstance();
        sendTCPMessage(WireCodec.encode(hello(players.getPlayerID(), players.getPlayerName(), players.getDeviceAddress(), rejoin, mOutbox)));
        if (players.getPairedGrenadeID() != 0)
            sendPlayerGrenade();
    }

    /**
     * The hello: who we are, and the reliable messages not acknowledged yet. They ride in the
     * hello so the server applies them once it knows who we are and before it builds our
     * snapshot (D2, D3).
     */
    static ClientMessage.Hello hello(byte playerId, String name, String mac, boolean rejoin, ClientOutbox outbox) {
        List<WirePending> pending = new ArrayList<>();
        for (ClientOutbox.Entry entry : outbox.pending()) {
            pending.add(new WirePending(entry.getRmsg(), entry.getSeq()));
        }
        if (!pending.isEmpty())
            Log.d(TAG, "Replaying " + pending.size() + " unacknowledged messages");
        Long gameId = outbox.getGameId() != 0 ? outbox.getGameId() : null;
        return new ClientMessage.Hello(playerId, name, mac, rejoin, gameId, pending);
    }

    /** Our own settings, for the server to apply when it allows player settings. */
    public void sendPlayerSettings() {
        GameSettingsRepository settings = GameSettingsRepository.getInstance();
        int playerId = PlayerRepository.getInstance().getPlayerID();
        WirePlayerSettings wire = new WirePlayerSettings(playerId, settings.getFullHealth(), settings.getFullReload(),
                settings.getReloadTime(), settings.isReloadOnEmpty(), settings.isManualRespawn(), settings.getRespawnTime(),
                settings.getRespawnShieldTime(), settings.getDamage(),
                settings.isOverrideLives() ? Integer.valueOf(settings.getOverrideLivesVal()) : null,
                settings.isAllowSingleShotMode(), settings.isAllowBurst3ShotMode(), settings.isAllowAutoShotMode(),
                settings.getCurrentFiringMode(), null, null);
        sendTCPMessage(WireCodec.encode(new ClientMessage.Settings(playerId, wire)));
    }

    public void sendPlayerGrenade() {
        PlayerRepository players = PlayerRepository.getInstance();
        sendTCPMessage(WireCodec.encode(new ClientMessage.GrenadePairing(players.getPlayerID(), players.getPairedGrenadeID())));
    }

    /** Reports our position (the map's location updates) to the server. */
    public void sendLocation(double latitude, double longitude) {
        sendTCPMessage(WireCodec.encode(new ClientMessage.GpsLocation(latitude, longitude)));
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
            sendTCPMessage(WireCodec.encode(new ClientMessage.Command(ClientCommand.Leave.INSTANCE)));
            stopTcpClient();
        }
    }

    /** A JSON message from the server; [json] is the frame's JSON. */
    private void handleMessage(ServerMessage message, String json) {
        if (message instanceof ServerMessage.GrenadePairings) {
            PlayerRepository repo = PlayerRepository.getInstance();
            synchronized (repo) {
                repo.clearGrenadePairings();
                for (WireGrenadePairing pairing : ((ServerMessage.GrenadePairings) message).getPairings()) {
                    repo.pairGrenade(pairing.getGrenadeId(), pairing.getPlayerId());
                }
            }
        } else if (message instanceof ServerMessage.GpsUpdate) {
            ServerMessage.GpsUpdate update = (ServerMessage.GpsUpdate) message;
            PlayerRepository repo = PlayerRepository.getInstance();
            synchronized (repo) {
                if (update.getFull())
                    repo.getGpsData().clear();
                for (WireGpsPosition position : update.getPositions()) {
                    byte playerID = (byte) position.getPlayerId();
                    if (playerID == repo.getPlayerID())
                        continue;
                    GpsData gps = repo.getGpsData().get(playerID);
                    if (gps == null) {
                        gps = new GpsData();
                        gps.team = position.getTeam() != null ? position.getTeam() : 0;
                        repo.getGpsData().put(playerID, gps);
                    }
                    gps.longitude = position.getLongitude();
                    gps.latitude = position.getLatitude();
                    gps.hasUpdate = true; // Anything that the server sends us is considered an update
                }
            }
            mEvents.publish(new ClientEvent.GpsData(update.getFull()));
        } else if (message instanceof ServerMessage.PlayerData) {
            mEvents.publish(new ClientEvent.PlayerDataUpdate(json));
        } else if (message instanceof ServerMessage.PlayerSettings) {
            ServerMessage.PlayerSettings settings = (ServerMessage.PlayerSettings) message;
            applyPlayerSettings(settings.getPlayers(), settings.getAllowPlayerSettings());
        } else if (message instanceof ServerMessage.GameInfo) {
            ServerMessage.GameInfo info = (ServerMessage.GameInfo) message;
            if (info.getPlayerSettings() != null)
                applyPlayerSettings(info.getPlayerSettings(), info.getAllowPlayerSettings());
            applyGameInfo(info);
        }
    }

    /** Everyone's settings from the server; our own become the game settings. */
    private void applyPlayerSettings(List<WirePlayerSettings> players, boolean allowPlayerSettings) {
        PlayerRepository repo = PlayerRepository.getInstance();
        GameSettingsRepository settings = GameSettingsRepository.getInstance();
        synchronized (repo) {
            for (WirePlayerSettings wire : players) {
                byte playerID = (byte) wire.getPlayerId();
                PlayerSettings playerSettings = repo.getPlayerSettings().get(playerID);
                if (playerSettings == null) {
                    playerSettings = new PlayerSettings();
                    repo.getPlayerSettings().put(playerID, playerSettings);
                }
                wire.applyTo(playerSettings, true);
                if (playerID == repo.getPlayerID()) {
                    settings.setFullHealth(playerSettings.health);
                    settings.setFullReload(playerSettings.shots);
                    settings.setReloadTime(playerSettings.reloadTime);
                    settings.setReloadOnEmpty(playerSettings.reloadOnEmpty);
                    settings.setManualRespawn(playerSettings.manualRespawn);
                    settings.setRespawnTime(playerSettings.spawnTime);
                    settings.setRespawnShieldTime(playerSettings.respawnShieldTime);
                    settings.setDamage(playerSettings.damage);
                    settings.setOverrideLives(playerSettings.overrideLives);
                    settings.setOverrideLivesVal(playerSettings.lives);
                    settings.setAllowSingleShotMode(playerSettings.allowShotModeSingle);
                    settings.setAllowBurst3ShotMode(playerSettings.allowShotModeBurst3);
                    settings.setAllowAutoShotMode(playerSettings.allowShotModeAuto);
                    settings.setCurrentFiringMode(playerSettings.firingMode);
                    settings.setServerRecoilSetting(playerSettings.recoilSetting);
                    repo.setAdmin(playerSettings.isAdmin);
                }
            }
        }
        settings.setAllowPlayerSettings(allowPlayerSettings);
        mEvents.publish(ClientEvent.PlayerSettingsUpdate.INSTANCE);
    }

    /** The players, limits, teams and phase from the game info. */
    private void applyGameInfo(ServerMessage.GameInfo info) {
        PlayerRepository repo = PlayerRepository.getInstance();
        GameSettingsRepository settings = GameSettingsRepository.getInstance();
        synchronized (repo) {
            repo.clearAllPlayers();
            settings.setGameLimit(GameSettingsRepository.GAME_LIMIT_NONE);
            for (WirePlayer player : info.getPlayers()) {
                byte playerID = (byte) player.getPlayerId();
                String playerName = player.getName();
                if (playerID == repo.getPlayerID()) {
                    if (!playerName.isEmpty() && !playerName.equals(repo.getPlayerName())) {
                        repo.setPlayerName(playerName);
                    }
                } else {
                    String ip = player.getIp();
                    if (ip == null) {
                        Log.w(TAG, "No address for player " + playerID);
                        continue;
                    }
                    if (ip.startsWith("/")) ip = ip.substring(1);
                    InetAddress playerIP = null;
                    try {
                        playerIP = InetAddress.getByName(ip);
                    } catch (UnknownHostException e) {
                        e.printStackTrace();
                    }
                    if (playerIP != null) {
                        repo.addOrUpdatePlayer(playerID, playerIP, playerName);
                        Log.d(TAG, "player '" + playerName + "' (" + playerID + ") found at " + playerIP.toString());
                    }
                }
            }
        }
        Log.d(TAG, "Found " + info.getPlayers().size() + " players");
        WireLimits limits = info.getLimits();
        int gameLimit = settings.getGameLimit();
        if (limits.getTimeLimit() != null) {
            gameLimit += GameSettingsRepository.GAME_LIMIT_TIME;
            settings.setTimeLimit(limits.getTimeLimit());
        }
        if (limits.getLivesLimit() != null) {
            gameLimit += GameSettingsRepository.GAME_LIMIT_LIVES;
            settings.setLivesLimit(limits.getLivesLimit());
            Log.e(TAG, "tcpClient set lives limit to " + settings.getLivesLimit());
        }
        if (limits.getScoreLimit() != null) {
            gameLimit += GameSettingsRepository.GAME_LIMIT_SCORE;
            settings.setScoreLimit(limits.getScoreLimit());
        }
        settings.setGameLimit(gameLimit);
        TeamMode teamMode = TeamMode.fromWire(info.getGameMode());
        int[] sizes = null;
        if (info.getTeamSizes() != null) {
            sizes = new int[info.getTeamSizes().size()];
            for (int i = 0; i < sizes.length; i++) {
                sizes[i] = info.getTeamSizes().get(i);
            }
        }
        if (teamMode != null) {
            settings.setTeamModeAndSizes(teamMode, sizes);
        } else {
            Log.w(TAG, "Unknown team mode from the server: " + info.getGameMode());
        }
        settings.setUseGPS(info.getGpsMode() != null);
        if (info.getGpsMode() != null) {
            settings.setGpsMode(info.getGpsMode());
        }
        if (info.getStartTime() != null) {
            settings.setStartTime(info.getStartTime());
        }
        settings.setOnlyServerSettings(info.getOnlyServerSettings());
        if (info.getAllowChooseTeams() != null) {
            settings.setAllowChooseTeams(info.getAllowChooseTeams());
        }
        Integer sessionPhase = null;
        Long gameId = null;
        if (info.getSessionPhase() != null) {
            mSessionPhase = SessionPhase.fromWire(info.getSessionPhase());
            mGameId = info.getGameId() != null ? info.getGameId() : 0;
            mOutbox.onGameId(mGameId); // a new game, or a reset: the old entries are void
            sessionPhase = mSessionPhase.wireValue;
            gameId = mGameId;
        }
        GameUpdate gameUpdate = null;
        WirePlayerGameUpdate update = info.getPlayerUpdate();
        if (update != null) {
            // D2: the server's count plus the eliminations it hasn't applied yet
            int eliminations = mOutbox.reconcile(update.getEliminated(), update.getAckSeq() != null ? update.getAckSeq() : 0);
            // A missing team score is 0 and a missing time is -1 (no time limit)
            gameUpdate = new GameUpdate(update.getPoints(), eliminations,
                    update.getTeamPoints() != null ? update.getTeamPoints() : 0,
                    update.getTimeRemaining() != null ? update.getTimeRemaining() : -1);
        }
        mEvents.publish(new ClientEvent.ListPlayers(gameUpdate, info.getGameState(), sessionPhase, gameId));
    }

    public SessionPhase getSessionPhase() {
        return mSessionPhase;
    }

    public long getGameId() {
        return mGameId;
    }
}
