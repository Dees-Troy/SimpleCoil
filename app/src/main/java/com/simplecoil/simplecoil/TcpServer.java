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
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.simplecoil.simplecoil.data.model.GpsData;
import com.simplecoil.simplecoil.data.model.PlayerSettings;
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository;
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.GameStateRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;
import com.simplecoil.simplecoil.domain.team.TeamLayout;
import com.simplecoil.simplecoil.domain.team.TeamMode;
import com.simplecoil.simplecoil.network.EventBus;
import com.simplecoil.simplecoil.network.Frame;
import com.simplecoil.simplecoil.network.ServerEvent;
import com.simplecoil.simplecoil.network.server.Connection;
import com.simplecoil.simplecoil.network.server.GameRoster;
import com.simplecoil.simplecoil.network.server.PlayerRecord;
import com.simplecoil.simplecoil.network.server.SessionPhase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TcpServer extends Service {
    private static final String TAG = "TCPServer";

    public static final int SEND_ALL = -100;

    public static final int TCP_SERVER_PORT = 17510;
    // Read timeout on every socket: the granularity of ping and drop timing (network design Q5)
    public static final int TCP_DEDICATED_READ_WAIT_MS = 100;
    public static final String TCP_SERVER_PING = "ping";
    private static final String ADMIN_PREFIX = "ADMIN_";

    public static final String TCPMESSAGE_PREFIX = NetMsg.MESSAGE_PREFIX + NetMsg.NETWORK_VERSION;
    public static final String TCPPREFIX_MESG = "MESG";
    public static final String TCPPREFIX_JSON = "JSON";

    public static final String JSON_PLAYERS = "players";
    public static final String JSON_PLAYERNAME = "playername";
    public static final String JSON_PLAYERNAMECHANGE = "playernamechange";
    public static final String JSON_PLAYERID = "playerID";
    public static final String JSON_PLAYERIP = "playerIP";
    public static final String JSON_LIMITS = "limits";
    public static final String JSON_TIMELIMIT = "timelimit";
    public static final String JSON_LIVESLIMIT = "liveslimit";
    public static final String JSON_SCORELIMIT = "scorelimit";
    public static final String JSON_GAMEMODE = "gamemode";
    public static final String JSON_TEAMSIZES = "teamsizes";
    public static final String JSON_REJOIN = "rejoin";
    public static final String JSON_USEGPS = "usegps";
    public static final String JSON_TEAM = "team";
    public static final String JSON_GAMESTATE = "gamestate";
    public static final String JSON_SESSIONPHASE = "sessionphase";
    public static final String JSON_GAMEID = "gameid";
    public static final String JSON_CONNECTED = "connected";
    // Reliable client messages (§3.4): {"rmsg": "ELIMINATED7", "seq": 12, "gameid": 418873301}
    public static final String JSON_RMSG = "rmsg";
    public static final String JSON_SEQ = "seq";
    public static final String JSON_PENDING = "pending";
    public static final String JSON_ACKSEQ = "ackseq";
    public static final String JSON_ONLY_SERVER_SETTINGS = "onlyserversettings";
    public static final String JSON_ALLOW_CHOOSE_TEAMS = "allowchooseteams";
    public static final String JSON_REQUEST_TEAM_CHANGE = "requestteamchange";
    public static final String JSON_PAIRED_GRENADE_ID = "pairedgrenadeID";
    public static final String JSON_GRENADE_PAIRINGS = "grenadepairings";
    public static final String JSON_BLASTER_MAC = "blaster_mac";

    public static final String JSON_GPSLONGITUDE = "gpslongitude";
    public static final String JSON_GPSLATITUDE = "gpslatitude";
    public static final String JSON_GPSUPDATE = "gpsupdate";
    public static final String JSON_GPSFULLUPDATE = "gpsfullupdate";

    public static final String JSON_PLAYERDATA = "playerdata";
    public static final String JSON_PLAYERPOINTS = "points";
    public static final String JSON_PLAYERELIMINATED = "eliminated";
    public static final String JSON_TEAMPOINTS = "teampoints";
    public static final String JSON_TIMEREMAINING = "timeremaining";
    public static final String JSON_PLAYERGAMEUPDATE = "playergameupdate";

    public static final String JSON_PLAYERSETTINGS = "playersettings";
    public static final String JSON_HEALTH = "health";
    public static final String JSON_RELOAD_SHOTS = "reloadshots";
    public static final String JSON_RELOAD_TIME = "reloadtime";
    public static final String JSON_RELOAD_ON_EMPTY = "reloadonempty";
    public static final String JSON_MANUAL_RESPAWN = "manualrespawn";
    public static final String JSON_SPAWN_TIME = "spawntime";
    public static final String JSON_RESPAWN_SHIELD_TIME = "respawnshieldtime";
    public static final String JSON_START_TIME = "starttime";
    public static final String JSON_DAMAGE = "damage";
    public static final String JSON_SHOT_MODE_SINGLE = "shotmodesingle";
    public static final String JSON_SHOT_MODE_BURST3 = "shotmodeburst3";
    public static final String JSON_SHOT_MODE_AUTO = "shotmodeauto";
    public static final String JSON_FIRING_MODE = "firingmode";
    public static final String JSON_RECOIL_SETTING = "recoilsetting";
    public static final String JSON_IS_ADMIN = "isadmin";
    public static final String JSON_ALLOWPLAYERSETTINGS = "allowplayersettings";

    private volatile boolean keepListening = false;
    private volatile boolean mAccepting = false;
    private volatile ServerSocket mServerSocket = null;
    private final GameRoster mRoster = new GameRoster();
    private final MessageHandler mHandler = new MessageHandler();
    /** Drop a connection after this long without a byte from it; ping it after 1 s and 2 s. */
    static final long SILENCE_DROP_MS = 3500;
    private static final long SILENCE_PING1_MS = 1000;
    private static final long SILENCE_PING2_MS = 2000;

    /**
     * Runs every handler and state change in one order: frames from every connection, operator
     * commands and the GPS tick (network design §5.5). It never blocks on I/O: sends only queue
     * frames on their connections.
     */
    private final ExecutorService mDispatcher;
    private volatile Thread mDispatcherThread;

    private volatile boolean mGPSRunning = false;
    private final ScheduledExecutorService mGPSTimer;
    private ScheduledFuture<?> mGPSTick = null;
    private static final long GPS_UPDATE_INTERVAL = 1000;
    private static final int SEND_ALL_GPS_INTERVAL = 20; // We will send all GPS data regardless of whether there was a GPS change every xx intervals
    private final AtomicInteger mGPSIntervalCount = new AtomicInteger(0);

    /** Where the server's events go: the app's server bus once created (a private one in tests). */
    private EventBus<ServerEvent> mEvents = new EventBus<>();

    public TcpServer() {
        mDispatcher = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "sc-server");
                thread.setDaemon(true);
                mDispatcherThread = thread;
                return thread;
            }
        });
        mGPSTimer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "sc-gps");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mEvents = ((SimpleCoilApp) getApplication()).getContainer().getServerEvents();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mGPSTimer.shutdownNow();
        mDispatcher.shutdown();
    }

    /** Runs [body] on the dispatcher: now if already on it, otherwise queued behind what's there. */
    private void runOnDispatcher(final Runnable body) {
        if (Thread.currentThread() == mDispatcherThread) {
            body.run();
            return;
        }
        try {
            mDispatcher.execute(new Runnable() {
                public void run() {
                    try {
                        body.run();
                    } catch (RuntimeException e) {
                        // One failed handler must not stop the server handling everything else (N5)
                        Log.e(TAG, "Server task failed", e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Server is shut down; dropping a task");
        }
    }

    /** Runs [body] on the dispatcher and waits for its result. Safe: the dispatcher never blocks. */
    private <T> T callOnDispatcher(Callable<T> body, T onFailure) {
        try {
            if (Thread.currentThread() == mDispatcherThread)
                return body.call();
            return mDispatcher.submit(body).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Server task failed", e);
        }
        return onFailure;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    private static final int NOTIFICATION_ID = 1339;
    private static final String CHANNEL_ID = "TcpServerServiceChannel";

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.server_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    /**
     * Keeps the process in the foreground while the server runs, so a minimized or backgrounded
     * server isn't frozen or killed (S3). Called by the screen once the server has started.
     */
    @SuppressLint("InlinedApi") // ServiceCompat ignores the type below Android 10
    public void startAsForeground() {
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, DedicatedServerActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.server_notification_title))
                .setContentText(getString(R.string.server_notification_text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (Exception e) {
            // Android 12+ refuses this while the app is in the background; serve on without it.
            Log.w(TAG, "Could not start foreground: " + e.getMessage());
        }
    }

    private void stopAsForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }

    public class LocalBinder extends Binder {
        TcpServer getService() {
            return TcpServer.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    /** Queues [message] for every connection. Never blocks: each connection has its own writer (N1, N2). */
    public void sendTCPMessageAll(final String message) {
        for (Connection connection : mRoster.connections()) {
            connection.send(message);
        }
    }

    /**
     * Sends to [record]'s connection. A disconnected player misses it: the snapshot it gets when
     * it comes back carries everything it needs (D11).
     */
    private void sendToPlayer(PlayerRecord record, String message) {
        Connection connection = record.getConnection();
        if (connection != null)
            connection.send(message);
    }

    private void sendTCPMessageID(final String message, final byte playerID) {
        PlayerRecord record = mRoster.record(playerID);
        if (record != null)
            sendToPlayer(record, message);
    }

    private void sendTCPMessageTeam(final String message, final byte playerID, final boolean includePlayer) {
        PlayerRecord player = mRoster.record(playerID);
        if (player == null)
            return;
        int team = player.getTeam();
        if (includePlayer)
            sendToPlayer(player, message);
        for (PlayerRecord record : mRoster.recordsOnTeam(team)) {
            if (record != player)
                sendToPlayer(record, message);
        }
    }

    public void resetScores() {
        mRoster.resetScores();
    }

    /** Starts a game from the lobby. False if not in the lobby (after a game, reset first) or nobody is there. */
    public boolean startGame() {
        return callOnDispatcher(new Callable<Boolean>() {
            public Boolean call() {
                return startGameNow();
            }
        }, false);
    }

    private boolean startGameNow() {
        Log.d(TAG, "TcpServer.startGame called. Clients: " + mRoster.size());
        if (!mRoster.startGame())
            return false;
        syncGameState();
        // we want to make sure that we have sent the start game message to all clients before publishing that the game has started locally
        sendTCPMessageAll(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_STARTGAME);
        sendAllGameInfo(SEND_ALL);
        mEvents.publish(ServerEvent.GameStarted.INSTANCE);
        mEvents.publish(new ServerEvent.PlayerDataChanged(null));
        return true;
    }

    /**
     * Ends a running game (POST_GAME). Every player stays, connected or not, so the game's roster
     * can still come back until the reset. Does nothing if no game is running.
     */
    public void endGame() {
        runOnDispatcher(new Runnable() {
            public void run() {
                Log.d(TAG, "TcpServer.endGame called. Clients: " + mRoster.size());
                if (!mRoster.endGame())
                    return;
                syncGameState();
                sendPlayerData(SEND_ALL);
                sendTCPMessageAll(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ENDGAME);
                // after ENDGAME, so the clients have ended the game before they see game state NONE;
                // carries the POST_GAME phase, which swaps End Game for Reset in the admin menu
                sendAllGameInfo(SEND_ALL);
                PlayerRepository.getInstance().getGpsData().clear();
                mEvents.publish(ServerEvent.GameEnded.INSTANCE);
            }
        });
    }

    /**
     * Reset (reconnect design §3.1), POST_GAME → LOBBY: removes the disconnected players, zeroes
     * the scores, forgets the game's roster, then tells the clients and publishes
     * ServerEvent.SessionReset. Returns false (and does nothing) outside POST_GAME, so a
     * duplicate or late reset is harmless. Safe on the main thread: it waits for the dispatcher,
     * which never blocks.
     */
    public boolean resetSession() {
        return callOnDispatcher(new Callable<Boolean>() {
            public Boolean call() {
                return resetSessionNow();
            }
        }, false);
    }

    private boolean resetSessionNow() {
        List<PlayerRecord> removed = mRoster.reset();
        if (removed == null)
            return false;
        Log.d(TAG, "Session reset: removed " + removed.size() + " disconnected players");
        syncGameState();
        mGPSIntervalCount.set(SEND_ALL_GPS_INTERVAL);
        sendPlayerData(SEND_ALL);
        sendAllGameInfo(SEND_ALL);
        mEvents.publish(ServerEvent.SessionReset.INSTANCE);
        mEvents.publish(new ServerEvent.PlayerDataChanged(null));
        return true;
    }

    /**
     * Changing the team mode, team sizes, game limits or GPS mode, or sorting teams, after a game
     * resets first (§3.1.1). Call it before applying the change; true if it reset.
     */
    public boolean resetIfPostGame() {
        return resetSession();
    }

    public SessionPhase getSessionPhase() {
        return mRoster.phase();
    }

    /** GameStateRepository follows the session: running only in RUNNING (D8). */
    private void syncGameState() {
        int state = mRoster.isRunning() ? GameStateRepository.GAME_STATE_RUNNING : GameStateRepository.GAME_STATE_NONE;
        GameStateRepository.getInstance().setGameState(state);
    }

    public void switchPlayerTeam(final byte oldPlayerID, final byte newPlayerID) {
        runOnDispatcher(new Runnable() {
            public void run() {
                Log.d(TAG, "switchPlayerTeam: moving player " + oldPlayerID + " to new ID " + newPlayerID);
                PlayerRecord record = mRoster.movePlayer(oldPlayerID, newPlayerID);
                if (record != null && record.getConnection() != null)
                    record.getConnection().send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERREPLY + newPlayerID);
                sendPlayerData(SEND_ALL);
                sendAllGameInfo(SEND_ALL);
                mEvents.publish(new ServerEvent.PlayerDataChanged(null));
            }
        });
    }

    public void kickPlayer(final byte playerID) {
        runOnDispatcher(new Runnable() {
            public void run() {
                Log.d(TAG, "kickPlayer: kicking player " + playerID);
                Connection connection = mRoster.kick(playerID);
                if (connection != null) {
                    connection.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERCANCEL);
                    connection.closeAfterPending();
                }
                mGPSIntervalCount.set(SEND_ALL_GPS_INTERVAL);
                sendPlayerData(SEND_ALL);
                sendAllGameInfo(SEND_ALL);
                mEvents.publish(ServerEvent.PlayerLeft.INSTANCE);
                mEvents.publish(new ServerEvent.PlayerDataChanged(null));
            }
        });
    }

    public void rebalanceAllPlayers(final boolean randomize) {
        runOnDispatcher(new Runnable() {
            public void run() {
                if (mRoster.isRunning()) {
                    Log.w(TAG, "rebalanceAllPlayers: Game is running, skipping team rebalance!");
                    return;
                }
                resetSessionNow(); // after a game: the roster renumbers only in the lobby
                GameRoster.Rebalance result = mRoster.rebalance(randomize);
                for (Connection connection : result.dropped) {
                    Log.w(TAG, "Server full during rebalance! Closing client " + connection.getConnectionId());
                    connection.close();
                }
                for (PlayerRecord record : result.assigned) {
                    Connection connection = record.getConnection();
                    if (connection != null)
                        connection.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERREPLY + record.getPlayerId());
                }
                sendPlayerData(SEND_ALL);
                sendAllGameInfo(SEND_ALL);
                mEvents.publish(new ServerEvent.PlayerDataChanged(null));
            }
        });
    }

    /** Sends the game info to everyone; [id] (or SEND_ALL) also gets its own game update. */
    public void sendAllGameInfo(final int id) {
        runOnDispatcher(new Runnable() {
            public void run() {
                sendAllGameInfoNow(id);
            }
        });
    }

    private void sendAllGameInfoNow(final int id) {
        if (mRoster.isEmpty() || PlayerRepository.getInstance().getTeamIPMap() == null || PlayerRepository.getInstance().getTeamIPMap().size() == 0)
            return;
        try {
            JSONArray players = new JSONArray();
            for (Map.Entry<Byte, InetAddress> entry : PlayerRepository.getInstance().getTeamIPMap().entrySet()) {
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERNAME, PlayerRepository.getInstance().getPlayerName(entry.getKey()));
                player.put(JSON_PLAYERID, entry.getKey());
                player.put(JSON_PLAYERIP, entry.getValue());
                players.put(player);
            }
            JSONObject game = new JSONObject();
            game.put(JSON_PLAYERS, players);
            JSONObject limits = new JSONObject();
            if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_TIME) != 0) {
                limits.put(JSON_TIMELIMIT, GameSettingsRepository.getInstance().getTimeLimit());
            }
            if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_LIVES) != 0) {
                limits.put(JSON_LIVESLIMIT, GameSettingsRepository.getInstance().getLivesLimit());
            }
            if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_SCORE) != 0) {
                limits.put(JSON_SCORELIMIT, GameSettingsRepository.getInstance().getScoreLimit());
            }
            game.put(JSON_LIMITS, limits);
            TeamLayout layout = GameSettingsRepository.getInstance().getTeamLayout();
            game.put(JSON_GAMEMODE, layout.getMode().wireValue);
            JSONArray teamSizesArr = new JSONArray();
            for (int size : layout.teamSizes()) {
                teamSizesArr.put(size);
            }
            game.put(JSON_TEAMSIZES, teamSizesArr);
            game.put(JSON_GAMESTATE, GameStateRepository.getInstance().getGameState());
            if (GameStateRepository.getInstance().getGameState() != GameStateRepository.GAME_STATE_NONE && (GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_TIME) != 0)
                game.put(JSON_TIMEREMAINING, GameStateRepository.getInstance().getServerGameTimeRemaining());
            SessionPhase phase = mRoster.phase();
            game.put(JSON_SESSIONPHASE, phase.wireValue);
            if (phase != SessionPhase.LOBBY)
                game.put(JSON_GAMEID, mRoster.gameId());
            if (GameSettingsRepository.getInstance().isUseGPS())
                game.put(JSON_USEGPS, GameSettingsRepository.getInstance().getGpsMode());
            game.put(JSON_ALLOWPLAYERSETTINGS, GameSettingsRepository.getInstance().isAllowPlayerSettings());
            game.put(JSON_PLAYERSETTINGS, getPlayerSettings(id, false));
            game.put(JSON_ONLY_SERVER_SETTINGS, GameSettingsRepository.getInstance().isOnlyServerSettings());
            game.put(JSON_ALLOW_CHOOSE_TEAMS, GameSettingsRepository.getInstance().isAllowChooseTeams());
            game.put(JSON_START_TIME, GameSettingsRepository.getInstance().getStartTime());
            final String allMessage = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
            if (id == SEND_ALL)
                sendTCPMessageAll(allMessage);
            else {
                // Get update data for this specific player
                ScoreData scoreData = getScore((byte)id);
                JSONObject playerGameUpdate = new JSONObject();
                if (scoreData != null) {
                    playerGameUpdate.put(JSON_PLAYERPOINTS, scoreData.points);
                    playerGameUpdate.put(JSON_PLAYERELIMINATED, scoreData.eliminated);
                } else {
                    playerGameUpdate.put(JSON_PLAYERPOINTS, 0);
                    playerGameUpdate.put(JSON_PLAYERELIMINATED, 0);
                }
                if (!layout.getMode().isFreeForAll()) {
                    playerGameUpdate.put(JSON_TEAMPOINTS, mRoster.teamPoints(layout.teamOf((byte) id)));
                }
                if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_TIME) != 0 && GameStateRepository.getInstance().getGameState() != GameStateRepository.GAME_STATE_NONE) {
                    playerGameUpdate.put(JSON_TIMEREMAINING, GameStateRepository.getInstance().getServerGameTimeRemaining());
                }
                PlayerRecord record = mRoster.record((byte) id);
                playerGameUpdate.put(JSON_ACKSEQ, record != null ? record.getLastAppliedSeq() : 0);
                game.put(JSON_PLAYERGAMEUPDATE, playerGameUpdate);
                final String idMessage = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();

                for (Connection connection : mRoster.connections()) {
                    PlayerRecord player = connection.getPlayer();
                    if (player != null && player.getPlayerId() == id)
                        connection.send(idMessage);
                    else
                        connection.send(allMessage);
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * Starts relaying GPS: every second, the positions that changed since the last tick, and all
     * of them every 20th tick. Runs while the server listens and GPS is on.
     */
    public synchronized void sendGPSData() {
        if (mGPSRunning) return;
        mGPSRunning = true;
        mGPSIntervalCount.set(0);
        // Fixed delay, like the Handler it replaces: a frozen process must not catch up with a burst of ticks
        mGPSTick = mGPSTimer.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                runOnDispatcher(new Runnable() {
                    public void run() {
                        gpsTick();
                    }
                });
            }
        }, GPS_UPDATE_INTERVAL, GPS_UPDATE_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void gpsTick() {
        if (!mGPSRunning) return; // a tick already queued when the relay stopped
        String messageToSend = null;
        PlayerRepository repo = PlayerRepository.getInstance();
        try {
            JSONArray players = new JSONArray();
            boolean hasUpdate = false;
            boolean hasData;
            int currentInterval = mGPSIntervalCount.get();
            // The repository's lock keeps a GPS report from landing between reading and clearing hasUpdate.
            synchronized (repo) {
                hasData = !repo.getGpsData().isEmpty();
                for (Map.Entry<Byte, GpsData> entry : repo.getGpsData().entrySet()) {
                    if (entry.getValue().hasUpdate || currentInterval >= SEND_ALL_GPS_INTERVAL) {
                        JSONObject player = new JSONObject();
                        player.put(JSON_PLAYERID, entry.getKey());
                        player.put(JSON_TEAM, entry.getValue().team);
                        player.put(JSON_GPSLONGITUDE, entry.getValue().longitude);
                        player.put(JSON_GPSLATITUDE, entry.getValue().latitude);
                        players.put(player);
                        hasUpdate = true;
                    }
                    entry.getValue().hasUpdate = false;
                }
            }
            if (hasData) {
                if (hasUpdate) {
                    JSONObject game = new JSONObject();
                    game.put(JSON_GPSUPDATE, players);
                    if (currentInterval >= SEND_ALL_GPS_INTERVAL) {
                        game.put(JSON_GPSFULLUPDATE, true);
                    }
                    messageToSend = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
                }
                if (mGPSIntervalCount.incrementAndGet() >= SEND_ALL_GPS_INTERVAL)
                    mGPSIntervalCount.set(0);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        if (messageToSend != null) {
            sendTCPMessageAll(messageToSend);
        }

        if (!keepListening || !GameSettingsRepository.getInstance().isUseGPS()) {
            stopGPSRelay();
        }
    }

    private synchronized void stopGPSRelay() {
        if (mGPSTick != null) {
            mGPSTick.cancel(false);
            mGPSTick = null;
        }
        mGPSRunning = false;
    }

    private void sendPlayerData(int playerID) {
        if (mRoster.isEmpty() || PlayerRepository.getInstance().getTeamIPMap() == null || PlayerRepository.getInstance().getTeamIPMap().size() == 0)
            return;
        try {
            JSONArray players = new JSONArray();
            for (Map.Entry<Byte, InetAddress> entry : PlayerRepository.getInstance().getTeamIPMap().entrySet()) {
                JSONObject player = new JSONObject();
                player.put(JSON_PLAYERNAME, PlayerRepository.getInstance().getPlayerName(entry.getKey()));
                player.put(JSON_PLAYERID, entry.getKey());
                player.put(JSON_PLAYERIP, entry.getValue());
                ScoreData scoreData = getScore(entry.getKey());
                if (scoreData != null) {
                    player.put(JSON_PLAYERPOINTS, scoreData.points);
                    player.put(JSON_PLAYERELIMINATED, scoreData.eliminated);
                    player.put(JSON_CONNECTED, scoreData.isConnected);
                } else {
                    player.put(JSON_PLAYERPOINTS, 0);
                    player.put(JSON_PLAYERELIMINATED, 0);
                }
                players.put(player);
            }
            JSONObject game = new JSONObject();
            game.put(JSON_PLAYERDATA, players);
            String message = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
            if (playerID != SEND_ALL)
                sendTCPMessageID(message, (byte)playerID);
            else {
                for (Connection connection : mRoster.connections()) {
                    connection.send(message);
                }
            }
            mEvents.publish(new ServerEvent.PlayerDataChanged(game.toString()));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private JSONArray getPlayerSettings(int playerID, boolean applyAll) {
        return getPlayerSettings(playerID, applyAll, false);
    }

    private JSONArray getPlayerSettings(int playerID, boolean applyAll, boolean applyTeam) {
        // Read before taking the repository's lock: the roster's lock always comes first.
        boolean noPlayers = mRoster.isEmpty();
        PlayerRepository repo = PlayerRepository.getInstance();
        TeamLayout layout = GameSettingsRepository.getInstance().getTeamLayout();
        try {
            JSONArray players = new JSONArray();
            synchronized (repo) {
                if (applyAll && playerID != SEND_ALL) {
                    PlayerSettings src = PlayerRepository.getInstance().getPlayerSettings().get((byte) playerID);
                    if (src != null) {
                        for (Byte x = 1; x <= GameSettingsRepository.MAX_PLAYER_ID; x++) {
                            if (x != playerID) {
                                PlayerSettings playerSettings = PlayerRepository.getInstance().getPlayerSettings().get(x);
                                if (playerSettings == null) {
                                    playerSettings = new PlayerSettings();
                                    PlayerRepository.getInstance().getPlayerSettings().put(x, playerSettings);
                                }
                                playerSettings.health = src.health;
                                playerSettings.shots = src.shots;
                                playerSettings.reloadTime = src.reloadTime;
                                playerSettings.reloadOnEmpty = src.reloadOnEmpty;
                                playerSettings.manualRespawn = src.manualRespawn;
                                playerSettings.spawnTime = src.spawnTime;
                                playerSettings.respawnShieldTime = src.respawnShieldTime;
                                playerSettings.damage = src.damage;
                                playerSettings.overrideLives = src.overrideLives;
                                playerSettings.lives = src.lives;
                                playerSettings.allowShotModeSingle = src.allowShotModeSingle;
                                playerSettings.allowShotModeBurst3 = src.allowShotModeBurst3;
                                playerSettings.allowShotModeAuto = src.allowShotModeAuto;
                                playerSettings.recoilSetting = src.recoilSetting;
                                playerSettings.firingMode = src.firingMode;
                            }
                        }
                    }
                } else if (applyTeam && playerID != SEND_ALL && !layout.getMode().isFreeForAll()) {
                    PlayerSettings src = PlayerRepository.getInstance().getPlayerSettings().get((byte) playerID);
                    if (src != null) {
                        int sourceTeam = layout.teamOf((byte) playerID);
                        for (Byte x = 1; x <= GameSettingsRepository.MAX_PLAYER_ID; x++) {
                            if (x != playerID && layout.teamOf(x) == sourceTeam) {
                                PlayerSettings playerSettings = PlayerRepository.getInstance().getPlayerSettings().get(x);
                                if (playerSettings == null) {
                                    playerSettings = new PlayerSettings();
                                    PlayerRepository.getInstance().getPlayerSettings().put(x, playerSettings);
                                }
                                playerSettings.health = src.health;
                                playerSettings.shots = src.shots;
                                playerSettings.reloadTime = src.reloadTime;
                                playerSettings.reloadOnEmpty = src.reloadOnEmpty;
                                playerSettings.manualRespawn = src.manualRespawn;
                                playerSettings.spawnTime = src.spawnTime;
                                playerSettings.respawnShieldTime = src.respawnShieldTime;
                                playerSettings.damage = src.damage;
                                playerSettings.overrideLives = src.overrideLives;
                                playerSettings.lives = src.lives;
                                playerSettings.allowShotModeSingle = src.allowShotModeSingle;
                                playerSettings.allowShotModeBurst3 = src.allowShotModeBurst3;
                                playerSettings.allowShotModeAuto = src.allowShotModeAuto;
                                playerSettings.recoilSetting = src.recoilSetting;
                                playerSettings.firingMode = src.firingMode;
                            }
                        }
                    }
                }
                if (noPlayers || PlayerRepository.getInstance().getTeamIPMap().isEmpty()) {
                    return null;
                }
                for (Map.Entry<Byte, PlayerSettings> entry : PlayerRepository.getInstance().getPlayerSettings().entrySet()) {
                    JSONObject player = new JSONObject();
                    player.put(JSON_PLAYERID, entry.getKey());
                    player.put(JSON_HEALTH, entry.getValue().health);
                    player.put(JSON_RELOAD_SHOTS, entry.getValue().shots);
                    player.put(JSON_RELOAD_TIME, entry.getValue().reloadTime);
                    player.put(JSON_RELOAD_ON_EMPTY, entry.getValue().reloadOnEmpty);
                    player.put(JSON_MANUAL_RESPAWN, entry.getValue().manualRespawn);
                    player.put(JSON_SPAWN_TIME, entry.getValue().spawnTime);
                    player.put(JSON_RESPAWN_SHIELD_TIME, entry.getValue().respawnShieldTime);
                    player.put(JSON_DAMAGE, entry.getValue().damage);
                    if (entry.getValue().overrideLives)
                        player.put(JSON_LIVESLIMIT, entry.getValue().lives);
                    player.put(JSON_SHOT_MODE_SINGLE, entry.getValue().allowShotModeSingle);
                    player.put(JSON_SHOT_MODE_BURST3, entry.getValue().allowShotModeBurst3);
                    player.put(JSON_SHOT_MODE_AUTO, entry.getValue().allowShotModeAuto);
                    player.put(JSON_FIRING_MODE, entry.getValue().firingMode);
                    player.put(JSON_RECOIL_SETTING, entry.getValue().recoilSetting);
                    player.put(JSON_IS_ADMIN, entry.getValue().isAdmin);
                    players.put(player);
                }
            }
            return players;
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void sendPlayerSettings(int playerID, boolean applyAll, boolean allowPlayerSettings) {
        sendPlayerSettings(playerID, applyAll, false, allowPlayerSettings);
    }

    public void sendPlayerSettings(final int playerID, final boolean applyAll, final boolean applyTeam, final boolean allowPlayerSettings) {
        runOnDispatcher(new Runnable() {
            public void run() {
                sendPlayerSettingsNow(playerID, applyAll, applyTeam, allowPlayerSettings);
            }
        });
    }

    private void sendPlayerSettingsNow(int playerID, boolean applyAll, boolean applyTeam, boolean allowPlayerSettings) {
        JSONArray players = getPlayerSettings(playerID, applyAll, applyTeam);
        if (players == null)
            return;
        try {
            JSONObject game = new JSONObject();
            game.put(JSON_ALLOWPLAYERSETTINGS, allowPlayerSettings);
            GameSettingsRepository.getInstance().setAllowPlayerSettings(allowPlayerSettings);
            game.put(JSON_PLAYERSETTINGS, players);
            String message = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + game.toString();
            sendTCPMessageAll(message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    synchronized void startTcpServer() {
        if (keepListening) {
            Log.d(TAG, "Server already listening");
            return;
        }
        keepListening = true;
        containedThread(new Runnable() {
            public void run() {
                runTcpServer();
            }
        }, "sc-accept").start();
    }

    private void runTcpServer() {
        mRoster.clear();
        syncGameState();
        PlayerRepository.getInstance().getGpsData().clear();
        PlayerRepository.getInstance().clearGrenadePairings();
        ServerSocket ss = null;
        try {
            ss = new ServerSocket(TCP_SERVER_PORT);
            ss.setSoTimeout(1000);
            synchronized (this) {
                if (!keepListening)
                    return; // stopped while binding
                mServerSocket = ss;
                mAccepting = true;
            }
            Log.d(TAG, "TCP Server listening");
            while (keepListening) {
                try {
                    // Wait for new clients
                    Socket s = ss.accept();
                    s.setSoTimeout(TCP_DEDICATED_READ_WAIT_MS);
                    Connection connection = mRoster.accept(s);
                    startReader(connection);
                    if (connection.getConnectionId() <= 1 && GameSettingsRepository.getInstance().isUseGPS()) {
                        sendGPSData();
                    }
                } catch (SocketTimeoutException e) {
                    // do nothing
                } catch (RuntimeException e) {
                    Log.e(TAG, "Error accepting a client", e);
                }
            }
        } catch (IOException e) {
            if (keepListening)
                Log.w(TAG, "Server socket failed", e);
        } finally {
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException e) {
                    // already closed
                }
            }
            synchronized (this) {
                // Unless a stop and a new start came first: that server is not ours to stop
                if (mServerSocket == ss) {
                    mServerSocket = null;
                    keepListening = false;
                    mAccepting = false;
                }
            }
        }
        Log.d(TAG, "TCP Server done");
    }

    /**
     * The connection's reader thread: hands every complete frame to the dispatcher, pings a
     * silent peer after 1 s and 2 s and drops it after 3.5 s, measured from its last byte (N3).
     */
    private void startReader(final Connection connection) {
        containedThread(new Runnable() {
            public void run() {
                readLoop(connection);
            }
        }, "sc-read-" + connection.getConnectionId()).start();
    }

    private void readLoop(final Connection client) {
        boolean pinged1 = false;
        boolean pinged2 = false;
        try {
            while (keepListening) {
                final String raw = client.poll();
                if (raw != null) {
                    pinged1 = false;
                    pinged2 = false;
                    runOnDispatcher(new Runnable() {
                        public void run() {
                            mHandler.handleFrame(client, raw);
                        }
                    });
                    continue;
                }
                long silence = client.silenceMillis();
                if (silence >= SILENCE_DROP_MS) {
                    Log.d(TAG, "no reply so dropping client " + client.getConnectionId());
                    break;
                } else if (silence >= SILENCE_PING2_MS) {
                    if (!pinged2)
                        client.send(TCP_SERVER_PING);
                    pinged1 = true;
                    pinged2 = true;
                } else if (silence >= SILENCE_PING1_MS && !pinged1) {
                    client.send(TCP_SERVER_PING);
                    pinged1 = true;
                }
            }
        } catch (IOException e) {
            // Closed by the server, closed or reset by the peer, or a frame that isn't UTF-8
            Log.d(TAG, "connection " + client.getConnectionId() + " closed: " + e.getMessage());
        }
        if (keepListening) {
            runOnDispatcher(new Runnable() {
                public void run() {
                    mHandler.removeClient(client);
                }
            });
        }
    }

    private void checkLivesLimitEndGame() {
        if (GameStateRepository.getInstance().getGameState() == GameStateRepository.GAME_STATE_NONE) {
            return;
        }

        boolean hasGlobalLifeLimit = (GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_LIVES) != 0
                && GameSettingsRepository.getInstance().getLivesLimit() > 0;

        boolean hasOverrideLifeLimit = false;
        Map<Byte, PlayerSettings> playerSettingsMap = PlayerRepository.getInstance().getPlayerSettings();
        if (playerSettingsMap != null) {
            for (PlayerSettings settings : playerSettingsMap.values()) {
                if (settings != null && settings.overrideLives && settings.lives > 0) {
                    hasOverrideLifeLimit = true;
                    break;
                }
            }
        }

        if (!hasGlobalLifeLimit && !hasOverrideLifeLimit) {
            return;
        }

        Set<Integer> livingTeams = new HashSet<>();

        for (PlayerRecord record : mRoster.records()) {
            byte playerId = record.getPlayerId();
            int totalLives = 0;
            PlayerSettings settings = (playerSettingsMap != null) ? playerSettingsMap.get(playerId) : null;
            if (settings != null && settings.overrideLives && settings.lives > 0) {
                totalLives = settings.lives;
            } else if (hasGlobalLifeLimit) {
                totalLives = GameSettingsRepository.getInstance().getLivesLimit();
            }

            if (totalLives > 0) {
                int remainingLives = totalLives - record.getEliminated();
                if (remainingLives > 0) {
                    int team = PlayerRepository.getInstance().calcNetworkTeam(playerId);
                    livingTeams.add(team);
                }
            } else {
                int team = PlayerRepository.getInstance().calcNetworkTeam(playerId);
                livingTeams.add(team);
            }
        }

        if (livingTeams.size() <= 1) {
            Log.d(TAG, "checkLivesLimitEndGame: ending game, living teams remaining: " + livingTeams.size());
            endGame();
        }
    }

    public ScoreData getScore(byte playerID) {
        GameRoster.Score score = mRoster.score(playerID);
        if (score == null)
            return null;
        ScoreData scoreData = new ScoreData();
        scoreData.points = score.points;
        scoreData.eliminated = score.eliminated;
        scoreData.isConnected = score.connected;
        return scoreData;
    }

    public class ScoreData {
        int points = 0;
        int eliminated = 0;
        boolean isConnected = false;
    }

    /** Handles the clients' frames. Runs on the dispatcher only. */
    private class MessageHandler {
        void handleFrame(Connection client, String raw) {
            try {
                handleFrameUnguarded(client, raw);
            } catch (RuntimeException e) {
                // One bad frame must not stop the server handling everyone else's (N5)
                Log.e(TAG, "Error handling '" + raw + "' from " + client.getConnectionId(), e);
            }
        }

        private void handleFrameUnguarded(Connection client, String raw) {
            String message = raw;
            Frame frame = Frame.parse(message);
            if (frame.kind != Frame.Kind.PONG) {
                Log.i(TAG, "received: '" + message + "' from " + client.getConnectionId());
                if (frame.kind == Frame.Kind.JSON) {
                    // Handle JSON Data
                    parsePlayerInfo(frame.body, client);
                    return;
                } else if (frame.kind == Frame.Kind.MESG) {
                    // Handle messages
                    message = frame.body;
                    if (message.equals(NetMsg.NETMSG_LEAVE)) {
                        // A player in the game's roster stays on the scoreboard, disconnected (Q12)
                        removeClient(client);
                        if (mRoster.isRunning() && mRoster.connectedCount() <= 1)
                            endGame();
                        return;
                    } else if (message.startsWith(NetMsg.NETMSG_ELIMINATED)) {
                        // Sent reliably since v07; a bare one is applied the same way, without de-dup
                        handleEliminated(client, message);
                    } else if (message.equals(NetMsg.NETMSG_SHOTFIRED)) {
                        sendTCPMessageAll(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SHOTFIRED);
                    } else if (message.startsWith(NetMsg.NETMSG_HIT)) {
                        message = message.substring(NetMsg.NETMSG_HIT.length());
                        try {
                            byte shooterId = (byte) Integer.parseInt(message);
                            byte targetId = playerIdOf(client);
                            sendTCPMessageID(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_HIT + targetId, shooterId);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else if (message.startsWith(NetMsg.NETMSG_OUT)) {
                        message = message.substring(NetMsg.NETMSG_OUT.length());
                        try {
                            byte shooterId = (byte) Integer.parseInt(message);
                            byte targetId = playerIdOf(client);
                            sendTCPMessageID(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_OUT + targetId, shooterId);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else if (message.equals(NetMsg.NETMSG_TEAMELIMINATED)) {
                        sendTCPMessageTeam(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_TEAMELIMINATED, playerIdOf(client), false);
                    } else if (message.equals(NetMsg.NETMSG_PLAYERDATAREQUEST)) {
                        sendPlayerData(playerIdOf(client));
                    } else if (isAdminCommand(message) && !isAdmin(client)) {
                        // D14: only players the server operator made admins may run the game
                        Log.w(TAG, "Ignoring '" + message + "' from non-admin client " + client.getConnectionId());
                    } else if (message.equals(NetMsg.NETMSG_STARTGAME) || message.startsWith(NetMsg.NETMSG_ADMIN_STARTGAME)) {
                        Log.d(TAG, "TcpServer received STARTGAME / ADMIN_STARTGAME command: '" + message + "'");
                        if (!startGame() && mRoster.phase() == SessionPhase.POST_GAME)
                            client.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ERROR + "Reset the game first");
                    } else if (message.equals(NetMsg.NETMSG_ENDGAME) || message.startsWith(NetMsg.NETMSG_ADMIN_ENDGAME)) {
                        Log.d(TAG, "TcpServer received ENDGAME / ADMIN_ENDGAME command: '" + message + "'");
                        if (message.startsWith(NetMsg.NETMSG_ADMIN_ENDGAME)) {
                            endGame();
                        } else {
                            // Regular ENDGAME from a client is treated as the client leaving
                            removeClient(client);
                            if (mRoster.isRunning() && mRoster.connectedCount() <= 1)
                                endGame();
                            return;
                        }
                    } else if (message.startsWith(NetMsg.NETMSG_ADMIN_RESET)) {
                        Log.d(TAG, "TcpServer received ADMIN_RESET command: '" + message + "'");
                        resetSession();
                    } else if (message.startsWith(NetMsg.NETMSG_ADMIN_SORTTEAMS)) {
                        Log.d(TAG, "TcpServer received ADMIN_SORTTEAMS command: '" + message + "'");
                        if (!mRoster.isRunning()) {
                            resetIfPostGame();
                            rebalanceAllPlayers(true);
                        }
                    } else if (message.startsWith(NetMsg.NETMSG_ADMIN_GAMEMODE)) {
                        Log.d(TAG, "TcpServer received ADMIN_GAMEMODE command: '" + message + "'");
                        try {
                            TeamMode mode = TeamMode.fromWire(Integer.parseInt(message.substring(NetMsg.NETMSG_ADMIN_GAMEMODE.length()).trim()));
                            if (mode == null) {
                                Log.w(TAG, "ADMIN_GAMEMODE ignored: unknown team mode");
                            } else if (!mRoster.isRunning()) {
                                resetIfPostGame();
                                GameSettingsRepository.getInstance().setTeamMode(mode);
                                new UserPreferencesRepository(TcpServer.this).saveTeamMode(mode);
                                rebalanceAllPlayers(false);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else if (message.startsWith(NetMsg.NETMSG_ADMIN_KICK)) {
                        Log.d(TAG, "TcpServer received ADMIN_KICK command: '" + message + "'");
                        try {
                            byte targetID = (byte) Integer.parseInt(message.substring(NetMsg.NETMSG_ADMIN_KICK.length()).trim());
                            kickPlayer(targetID);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else if (message.startsWith(NetMsg.NETMSG_ADMIN_TEAMSIZES)) {
                        Log.d(TAG, "TcpServer received ADMIN_TEAMSIZES command: '" + message + "'");
                        if (!mRoster.isRunning()) {
                            try {
                                String sizesStr = message.substring(NetMsg.NETMSG_ADMIN_TEAMSIZES.length()).trim();
                                String[] parts = sizesStr.split(",");
                                int[] newSizes = new int[parts.length];
                                int sum = 0;
                                for (int i = 0; i < parts.length; i++) {
                                    newSizes[i] = Integer.parseInt(parts[i].trim());
                                    sum += newSizes[i];
                                }
                                if (sum == GameSettingsRepository.MAX_PLAYER_ID) {
                                    resetIfPostGame();
                                    GameSettingsRepository.getInstance().setTeamSizes(newSizes);
                                    rebalanceAllPlayers(false);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                } else if (frame.kind == Frame.Kind.OTHER_VERSION) {
                    client.send(NetMsg.NETMSG_VERSIONERROR);
                    return;
                } else if (frame.kind != Frame.Kind.PING) {
                    Log.d(TAG, "unknown tcp message received");
                }
            }
        }

        private void sendGrenadePairings() {
            boolean has_data = false;
            int[] pairings = PlayerRepository.getInstance().getGrenadePairings();
            try {
                JSONArray grenadePairings = new JSONArray();
                for (int index = 1; index < PlayerRepository.MAX_GRENADE_IDS; index++) {
                    if (pairings[index] != PlayerRepository.INVALID_PLAYER_ID) {
                        has_data = true;
                        JSONObject grenadePairing = new JSONObject();
                        grenadePairing.put(JSON_PLAYERID, pairings[index]);
                        grenadePairing.put(JSON_PAIRED_GRENADE_ID, index);
                        grenadePairings.put(grenadePairing);
                    }
                }
                if (has_data) {
                    JSONObject data = new JSONObject();
                    data.put(JSON_GRENADE_PAIRINGS, grenadePairings);
                    String message = TCPMESSAGE_PREFIX + TCPPREFIX_JSON + data.toString();
                    sendTCPMessageAll(message);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        /** Messages that run the game: every ADMIN_* message, and a plain STARTGAME. */
        private boolean isAdminCommand(String message) {
            return message.equals(NetMsg.NETMSG_STARTGAME) || message.startsWith(ADMIN_PREFIX);
        }

        /** The server operator gave this player admin rights in its player settings. */
        private boolean isAdmin(Connection client) {
            PlayerRecord player = client.getPlayer();
            if (player == null)
                return false;
            PlayerSettings settings = PlayerRepository.getInstance().getPlayerSettings().get(player.getPlayerId());
            return settings != null && settings.isAdmin;
        }

        private byte playerIdOf(Connection client) {
            PlayerRecord player = client.getPlayer();
            return player != null ? player.getPlayerId() : 0;
        }

        /**
         * [message] ("ELIMINATED" + shooter id): the player behind [client] was eliminated. During
         * the game: the shooter and its team are told and the limits checked. After the game it
         * only counts in the final scores.
         */
        private void handleEliminated(Connection client, String message) {
            byte id;
            try {
                id = (byte) Integer.parseInt(message.substring(NetMsg.NETMSG_ELIMINATED.length()));
            } catch (NumberFormatException e) {
                e.printStackTrace();
                return;
            }
            GameRoster.Elimination elimination = mRoster.onEliminated(client, id);
            if (elimination == null) {
                Log.d(TAG, "elimination ignored outside a game");
                return;
            }
            if (!mRoster.isRunning()) {
                sendPlayerData(SEND_ALL);
                mEvents.publish(new ServerEvent.PlayerDataChanged(null));
                return;
            }
            if (!elimination.credited) {
                // Own or a teammate's grenade: a life lost, no score for anyone
                mEvents.publish(new ServerEvent.PlayerDataChanged(null));
                checkLivesLimitEndGame();
                return;
            }
            int shooterScore = elimination.shooterPoints;
            sendTCPMessageID(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ELIMINATED + elimination.victimId, id);
            TeamLayout layout = GameSettingsRepository.getInstance().getTeamLayout();
            if (!layout.getMode().isFreeForAll()) {
                // Send a message to all teammates about the score increase
                sendTCPMessageTeam(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_TEAMELIMINATED, id, false);
            }
            mEvents.publish(new ServerEvent.PlayerDataChanged(null));
            int scoreLimit = ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_SCORE) != 0 ? GameSettingsRepository.getInstance().getScoreLimit() : 0);
            if (scoreLimit != 0) {
                if (layout.getMode().isFreeForAll()) {
                    if (shooterScore >= scoreLimit)
                        endGame();
                } else {
                    int team = layout.teamOf(id);
                    if (mRoster.teamPoints(team) >= scoreLimit)
                        endGame();
                }
            }
            checkLivesLimitEndGame();
        }

        /**
         * A reliable message [rmsg] (§3.4): applied once, through the same handler as a live
         * one. Returns the seq to acknowledge, or -1 to leave it unacknowledged.
         */
        private int handleReliable(Connection client, String rmsg, int seq, long gameId) {
            GameRoster.ReliableCheck check = mRoster.checkReliable(client, seq, gameId);
            switch (check) {
                case NO_PLAYER:
                    Log.w(TAG, "reliable message before hello from client " + client.getConnectionId() + ": " + rmsg);
                    return -1;
                case STALE:
                    Log.d(TAG, "dropping reliable message from another game: " + rmsg + " (" + gameId + ")");
                    return seq;
                case DUPLICATE:
                    Log.d(TAG, "dropping duplicate reliable message " + seq + ": " + rmsg);
                    break;
                case APPLY:
                    if (rmsg.startsWith(NetMsg.NETMSG_ELIMINATED))
                        handleEliminated(client, rmsg);
                    else
                        Log.w(TAG, "unknown reliable message: " + rmsg);
                    break;
            }
            PlayerRecord player = client.getPlayer();
            return player != null ? player.getLastAppliedSeq() : seq;
        }

        /** The entries a (re)joining client hadn't had acknowledged, applied before its snapshot is built. */
        private void applyPending(Connection client, JSONObject hello) {
            JSONArray pending = hello.optJSONArray(JSON_PENDING);
            if (pending == null)
                return;
            long gameId = hello.optLong(JSON_GAMEID, 0);
            for (int i = 0; i < pending.length(); i++) {
                JSONObject entry = pending.optJSONObject(i);
                if (entry == null || !entry.has(JSON_RMSG) || !entry.has(JSON_SEQ))
                    continue;
                handleReliable(client, entry.optString(JSON_RMSG), entry.optInt(JSON_SEQ), gameId);
            }
        }

        private void parsePlayerInfo(String message, Connection client) {
            JSONObject player;
            try {
                player = new JSONObject(message);
            } catch (JSONException e) {
                e.printStackTrace();
                return;
            }
            if (player.has(JSON_RMSG)) {
                try {
                    int ack = handleReliable(client, player.getString(JSON_RMSG), player.getInt(JSON_SEQ), player.optLong(JSON_GAMEID, 0));
                    if (ack >= 0)
                        client.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ACK + ack);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return;
            }
            if (player.has(JSON_REQUEST_TEAM_CHANGE)) {
                try {
                    int targetTeam = player.getInt(JSON_REQUEST_TEAM_CHANGE);
                    handleClientTeamChangeRequest(client, targetTeam);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return;
            }
            if (player.has(JSON_PAIRED_GRENADE_ID)) {
                // Pairing and announcing run on the dispatcher, so two pairings can't go out in the wrong order.
                try {
                    PlayerRepository.getInstance().pairGrenade(player.getInt(JSON_PAIRED_GRENADE_ID), player.getInt(JSON_PLAYERID));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                sendGrenadePairings();
                return;
            }
            if (player.has(JSON_GPSLONGITUDE)) {
                byte playerId = playerIdOf(client);
                PlayerRecord record = client.getPlayer();
                double longitude, latitude;
                try {
                    longitude = player.getDouble(JSON_GPSLONGITUDE);
                    latitude = player.getDouble(JSON_GPSLATITUDE);
                } catch (JSONException e) {
                    e.printStackTrace();
                    return;
                }
                int team = record != null ? record.getTeam() : 0;
                PlayerRepository repo = PlayerRepository.getInstance();
                synchronized (repo) {
                    GpsData gps = repo.getGpsData().get(playerId);
                    if (gps == null) {
                        gps = new GpsData();
                        repo.getGpsData().put(playerId, gps);
                    }
                    gps.longitude = longitude;
                    gps.latitude = latitude;
                    gps.team = team;
                    gps.hasUpdate = true; // Client takes care of making sure that it is only sending us changes in coordinates
                }
                return;
            }
            if (player.has(JSON_PLAYERSETTINGS)) {
                if (!GameSettingsRepository.getInstance().isAllowPlayerSettings()) {
                    Log.e(TAG, "Player " + playerIdOf(client) + "not allowed to send player settings");
                    sendPlayerSettings((byte)SEND_ALL, false, true);
                    return;
                }
                byte playerId = playerIdOf(client);
                PlayerRepository repo = PlayerRepository.getInstance();
                synchronized (repo) {
                    PlayerSettings settings = repo.getPlayerSettings().get(playerId);
                    if (settings == null) {
                        settings = new PlayerSettings();
                        repo.getPlayerSettings().put(playerId, settings);
                    }
                    try {
                        settings.health = player.getInt(JSON_HEALTH);
                        settings.shots = (byte)player.getInt(JSON_RELOAD_SHOTS);
                        settings.reloadTime = player.getLong(JSON_RELOAD_TIME);
                        settings.reloadOnEmpty = player.getBoolean(JSON_RELOAD_ON_EMPTY);
                        settings.manualRespawn = player.optBoolean(JSON_MANUAL_RESPAWN, false);
                        settings.spawnTime = player.getLong(JSON_SPAWN_TIME);
                        if (player.has(JSON_RESPAWN_SHIELD_TIME)) {
                            settings.respawnShieldTime = player.getLong(JSON_RESPAWN_SHIELD_TIME);
                        }
                        settings.damage = player.getInt(JSON_DAMAGE);
                        if (player.has(JSON_LIVESLIMIT)) {
                            settings.overrideLives = true;
                            settings.lives = player.getInt(JSON_LIVESLIMIT);
                        } else {
                            settings.overrideLives = false;
                            settings.lives = 0;
                        }
                        settings.allowShotModeAuto = player.getBoolean(JSON_SHOT_MODE_AUTO);
                        settings.allowShotModeBurst3 = player.getBoolean(JSON_SHOT_MODE_BURST3);
                        settings.allowShotModeSingle = player.getBoolean(JSON_SHOT_MODE_SINGLE);
                        settings.firingMode = player.getInt(JSON_FIRING_MODE);
                        if (player.has(JSON_RECOIL_SETTING)) {
                            settings.recoilSetting = player.getInt(JSON_RECOIL_SETTING);
                        }
                        // JSON_IS_ADMIN is ignored: only the server operator grants admin rights (D14)
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
                sendPlayerSettings((byte)SEND_ALL, false, true);
                mEvents.publish(new ServerEvent.PlayerDataChanged(null));
                return;
            }
            if (player.has(JSON_PLAYERNAMECHANGE)) {
                String playerName;
                try {
                    playerName = player.getString(JSON_PLAYERNAMECHANGE);
                } catch (JSONException e) {
                    e.printStackTrace();
                    return;
                }
                mRoster.onNameChange(client, playerName);

                sendAllGameInfo(SEND_ALL);
                mEvents.publish(new ServerEvent.PlayerDataChanged(null));
                return;
            }
            mGPSIntervalCount.set(SEND_ALL_GPS_INTERVAL); // Send all GPS info because of the new client
            byte id;
            String playerName;
            String blasterMac;
            try {
                id = (byte) player.getInt(JSON_PLAYERID);
                playerName = player.getString(JSON_PLAYERNAME);
                blasterMac = player.has(JSON_BLASTER_MAC) ? player.getString(JSON_BLASTER_MAC) : "";
            } catch (JSONException e) {
                e.printStackTrace();
                return;
            }

            GameRoster.HelloResult result = mRoster.onHello(client, id, playerName, blasterMac, client.getInetAddress());

            switch (result.kind) {
                case REFUSED_IN_PROGRESS:
                    Log.d(TAG, "Rejecting client " + client.getConnectionId() + " (MAC: " + blasterMac + ") - Game in progress and allowJoin is false.");
                    client.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_ERROR + "Cannot join game in progress due to server settings.");
                    client.closeAfterPending();
                    mRoster.removeConnection(client);
                    return;
                case FULL:
                    client.close();
                    mRoster.removeConnection(client);
                    return;
                case REJOINED: {
                    PlayerRecord record = result.record;
                    byte existingPlayerId = record.getPlayerId();
                    Log.d(TAG, "rejoining client " + client.getConnectionId() + " to player ID " + existingPlayerId + " (MAC: " + blasterMac + ")");
                    if (result.displaced != null)
                        result.displaced.close();
                    applyPending(client, player);
                    client.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERREPLY + existingPlayerId);
                    sendAllGameInfo(existingPlayerId);
                    mEvents.publish(new ServerEvent.PlayerDataChanged(null));
                    mEvents.publish(ServerEvent.PlayerJoined.INSTANCE);
                    return;
                }
                default:
                    break;
            }

            PlayerRecord record = result.record;
            id = record.getPlayerId();
            applyPending(client, player);
            client.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERREPLY + id);
            Log.e(TAG, "network team is " + record.getTeam());
            InetAddress inetAddress = client.getInetAddress();
            Log.d(TAG, "client " + client.getConnectionId() + " player '" + record.getName() + "' (" + id + ") found at " + (inetAddress != null ? inetAddress.toString() : "null"));

            sendAllGameInfo(id);
            mEvents.publish(ServerEvent.PlayerJoined.INSTANCE);
        }

        private void handleClientTeamChangeRequest(final Connection client, final int targetTeam) {
            final PlayerRecord player = client != null ? client.getPlayer() : null;
            if (player == null || player.getPlayerId() <= 0) {
                return;
            }
            if (!GameSettingsRepository.getInstance().isAllowChooseTeams()) {
                Log.w(TAG, "Team change rejected: Allow players to choose teams is turned off.");
                return;
            }
            if (mRoster.phase() != SessionPhase.LOBBY) {
                Log.w(TAG, "Team change rejected: only allowed in the lobby.");
                return;
            }
            TeamMode teamMode = GameSettingsRepository.getInstance().getTeamMode();
            if (teamMode.isFreeForAll()) {
                Log.w(TAG, "Team change rejected: Free For All mode.");
                return;
            }
            int numTeams = teamMode.teamCount;
            if (targetTeam < 1 || targetTeam > numTeams) {
                Log.w(TAG, "Team change rejected: Invalid target team " + targetTeam);
                return;
            }
            if (player.getTeam() == targetTeam) {
                Log.d(TAG, "Client " + player.getPlayerId() + " is already on team " + targetTeam);
                return;
            }

            PlayerRecord moved = mRoster.changeTeam(client, targetTeam);
            if (moved == null) {
                Log.w(TAG, "Team change rejected: No open slot on team " + targetTeam);
                return;
            }
            client.send(TCPMESSAGE_PREFIX + TCPPREFIX_MESG + NetMsg.NETMSG_SERVERREPLY + moved.getPlayerId());
            sendPlayerData(SEND_ALL);
            sendAllGameInfo(SEND_ALL);
            mEvents.publish(new ServerEvent.PlayerDataChanged(null));
        }

        void removeClient(Connection client) {
            if (!mRoster.connections().contains(client)) {
                // Already removed, kicked, refused or replaced: its reader just noticed the close
                client.close();
                return;
            }
            GameRoster.Disconnect result = mRoster.onDisconnect(client);
            if (result == GameRoster.Disconnect.REMOVED) {
                mGPSIntervalCount.set(SEND_ALL_GPS_INTERVAL); // Force a full GPS update when someone leaves
                sendAllGameInfo(SEND_ALL);
                mEvents.publish(ServerEvent.PlayerLeft.INSTANCE);
            }
            client.close(); // a player kept during a game stays on the scoreboard, disconnected
            mEvents.publish(new ServerEvent.PlayerDataChanged(null));
        }
    }

    public void stopTcpServer() {
        ServerSocket ss;
        synchronized (this) {
            keepListening = false;
            mAccepting = false;
            ss = mServerSocket;
            mServerSocket = null;
        }
        if (ss != null) {
            try {
                ss.close(); // accept() returns at once, so a restart can bind the port (N6)
            } catch (IOException e) {
                // already closed
            }
        }
        for (Connection connection : mRoster.connections()) {
            connection.closeAfterPending(); // after a SERVERCANCEL sent just before the stop
        }
        stopGPSRelay();
        stopAsForeground();
    }

    /** The server socket is open (tests wait for it). */
    @VisibleForTesting
    void setEventsForTest(EventBus<ServerEvent> events) {
        mEvents = events;
    }

    boolean isAccepting() {
        return mAccepting;
    }

    GameRoster getRoster() {
        return mRoster;
    }

    /** A thread whose body can't crash the process: a RuntimeException is logged and ends it (N5). */
    private static Thread containedThread(final Runnable body, String name) {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try {
                    body.run();
                } catch (RuntimeException e) {
                    Log.e(TAG, "Server thread failed", e);
                }
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }
}
