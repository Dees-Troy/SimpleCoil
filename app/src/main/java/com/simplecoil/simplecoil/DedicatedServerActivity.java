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

import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.simplecoil.simplecoil.data.model.PlayerSettings;
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository;
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.GameStateRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;
import com.simplecoil.simplecoil.domain.rules.GameLimitRules;
import com.simplecoil.simplecoil.domain.rules.LimitKind;
import com.simplecoil.simplecoil.domain.rules.Limits;
import com.simplecoil.simplecoil.domain.session.ClockFormat;
import com.simplecoil.simplecoil.domain.team.TeamLayout;
import com.simplecoil.simplecoil.domain.team.TeamMode;
import com.simplecoil.simplecoil.network.ServerEvent;
import com.simplecoil.simplecoil.network.WireProtocol;
import com.simplecoil.simplecoil.network.server.ClockState;
import com.simplecoil.simplecoil.network.server.SessionPhase;
import com.simplecoil.simplecoil.ui.dialogs.GameLimitDialog;
import com.simplecoil.simplecoil.ui.dialogs.StartingTimerDialog;
import com.simplecoil.simplecoil.ui.dialogs.TeamRatioDialog;
import com.simplecoil.simplecoil.ui.playersettings.PlayerSettingsDialog;
import com.simplecoil.simplecoil.ui.playersettings.PlayerSettingsValues;
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardAdapter;
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardContext;
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardRows;
import com.simplecoil.simplecoil.ui.server.DedicatedServerViewModel;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.TimeUnit;

public class DedicatedServerActivity extends AppCompatActivity implements PopupMenu.OnMenuItemClickListener {
    private static final String TAG = "DEDSRV";


    private TextView mServerIPTV = null;
    private Chronometer mGameTimer = null;
    private TextView mGameCountDownTV = null;
    private TextView mNetworkPlayerCountTV = null;
    private Button mTeamModeButton = null;
    private Button mGameLimitButton = null;
    private Button mStartingTimerButton = null;
    private Button mGPSModeButton = null;
    private Button mEndGameButton = null;
    private Button mStartGameButton = null;
    private Button mResetGameButton = null;
    private TextView mGameLimitTV = null;
    private TextView mGameStatusTV = null;
    private SwitchCompat mAllowJoinSwitch = null;
    private SwitchCompat mOnlyServerSettingsSwitch = null;
    private SwitchCompat mAllowChooseTeamsSwitch = null;
    private RecyclerView mPlayerDisplayList = null;

    /** The server's game clock, as last rendered. */
    private ClockState mClockState = ClockState.Idle.INSTANCE;
    /** Redraws a timed game's countdown once a second while it counts. */
    private final Runnable mCountdownTicker = new Runnable() {
        public void run() {
            showCountdown();
        }
    };

    private UserPreferencesRepository mPreferences = null;

    private DedicatedServerViewModel mViewModel = null;

    private PlayerDisplayData[] mPlayerDisplayData = new PlayerDisplayData[GameSettingsRepository.MAX_PLAYER_ID + 2];
    private ScoreboardAdapter mPlayerDisplayListAdapter = null;

    // Code to manage Service lifecycle.
    private ServiceConnection mUDPServiceConnection = null;
    private UDPListenerService mUDPListenerService = null;

    private void startServiceSafely(Intent intent) {
        try {
            startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not start service directly: " + e.getMessage());
        }
    }

    private void setupUDPServiceConnection() {
        mUDPServiceConnection = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                mUDPListenerService = ((UDPListenerService.LocalBinder) service).getService();
                mUDPListenerService.createServer();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                mUDPListenerService = null;
            }
        };
        Intent udpServiceIntent = new Intent(getBaseContext(), UDPListenerService.class);
        startServiceSafely(udpServiceIntent);
        bindService(udpServiceIntent, mUDPServiceConnection, BIND_AUTO_CREATE);
    }

    private TcpServer mTcpServer = null;
    private ServiceConnection mTcpServerServiceConnection = null;

    private void setupTcpServerServiceConnection() {
        mTcpServerServiceConnection = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                mTcpServer = ((TcpServer.LocalBinder) service).getService();
                mTcpServer.startTcpServer();
                mTcpServer.startAsForeground();
                mViewModel.setPhase(mTcpServer.getSessionPhase());
                renderClock(mTcpServer.getClockState());
                getPlayerDisplayData();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                mTcpServer = null;
            }
        };
        Intent serviceIntent = new Intent(getBaseContext(), TcpServer.class);
        startServiceSafely(serviceIntent);
        bindService(serviceIntent, mTcpServerServiceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FragmentManager fm = getSupportFragmentManager();
        fm.setFragmentResultListener(GameLimitDialog.REQUEST_KEY, this,
                (key, result) -> onGameLimitEntered(GameLimitDialog.text(result), GameLimitDialog.kind(result)));
        fm.setFragmentResultListener(StartingTimerDialog.REQUEST_KEY, this,
                (key, result) -> onStartingTimerEntered(StartingTimerDialog.text(result)));
        fm.setFragmentResultListener(PlayerSettingsDialog.REQUEST_KEY, this,
                (key, result) -> onPlayerSettingsSaved(PlayerSettingsDialog.result(result)));
        fm.setFragmentResultListener(TeamRatioDialog.REQUEST_KEY, this,
                (key, result) -> onTeamSizesEntered(TeamRatioDialog.sizes(result)));
        setContentView(R.layout.activity_dedicated_server);
        PlayerRepository.getInstance().setPlayerID((byte) 0);
        GameStateRepository.getInstance().setGameState(GameStateRepository.GAME_STATE_NONE);

        mViewModel = new ViewModelProvider(this).get(DedicatedServerViewModel.class);
        mViewModel.getServerIpLiveData().observe(this, ip -> {
            if (mServerIPTV != null && ip != null) {
                mServerIPTV.setText(ip);
            }
        });
        mViewModel.getPlayerCountLiveData().observe(this, count -> {
            if (mNetworkPlayerCountTV != null && count != null) {
                mNetworkPlayerCountTV.setText(quantity(R.plurals.network_player_count, count));
            }
        });
        mViewModel.startServerBeacon("SimpleCoil Server");
        mViewModel.getTeamModeLiveData().observe(this, this::showTeamMode);

        mServerIPTV = findViewById(R.id.server_ip_tv);
        mGameTimer = findViewById(R.id.game_timer_chronometer);
        mGameCountDownTV = findViewById(R.id.game_countdown_tv);
        mNetworkPlayerCountTV = findViewById(R.id.player_count_tv);
        mNetworkPlayerCountTV.setText(quantity(R.plurals.network_player_count, 0));
        mTeamModeButton = findViewById(R.id.team_mode_toggle_button);
        if (mTeamModeButton != null) {
            mTeamModeButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    PopupMenu popup = new PopupMenu(new ContextThemeWrapper(DedicatedServerActivity.this, R.style.AppTheme_PopupOverlay), v);
                    MenuInflater inflater = popup.getMenuInflater();
                    inflater.inflate(R.menu.team_mode_menu, popup.getMenu());
                    popup.setOnMenuItemClickListener(DedicatedServerActivity.this);
                    popup.show();
                }
            }));
        }
        mGameLimitButton = findViewById(R.id.game_limit_button);
        if (mGameLimitButton != null) {
            mGameLimitButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    requestGameLimit();
                }
            }));
        }
        mStartingTimerButton = findViewById(R.id.starting_timer_button);
        if (mStartingTimerButton != null) {
            mStartingTimerButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    requestStartingTimer();
                }
            }));
        }
        mGameLimitTV = findViewById(R.id.game_limit_tv);
        mGameStatusTV = findViewById(R.id.game_status_tv);
        mGPSModeButton = findViewById(R.id.gps_mode_button);
        if (mGPSModeButton != null) {
            mGPSModeButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    PopupMenu popup = new PopupMenu(new ContextThemeWrapper(DedicatedServerActivity.this, R.style.AppTheme_PopupOverlay), v);
                    MenuInflater inflater = popup.getMenuInflater();
                    inflater.inflate(R.menu.gps_mode_menu, popup.getMenu());
                    popup.setOnMenuItemClickListener(DedicatedServerActivity.this);
                    popup.show();
                }
            }));
        }
        mEndGameButton = findViewById(R.id.end_game_button);
        if (mEndGameButton != null) {
            mEndGameButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    Log.d(TAG, "DedicatedServer UI: End Game clicked");
                    if (mTcpServer != null) {
                        mTcpServer.endGame();
                    }
                }
            }));
        }
        mStartGameButton = findViewById(R.id.start_game_button);
        if (mStartGameButton != null) {
            mStartGameButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    Log.d(TAG, "DedicatedServer UI: Start Game clicked. getPlayerCount=" + PlayerRepository.getInstance().getPlayerCount(true));
                    if (PlayerRepository.getInstance().getPlayerCount(true) <= 1) {
                        Toast.makeText(getApplicationContext(), getString(R.string.not_enough_players_toast), Toast.LENGTH_SHORT).show();
                        mNetworkPlayerCountTV.setText(R.string.network_player_1count);
                        return;
                    }
                    if (mTcpServer != null) {
                        mTcpServer.startGame();
                    }
                }
            }));
        }
        mResetGameButton = findViewById(R.id.reset_game_button);
        mResetGameButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmReset();
            }
        });
        mPreferences = new UserPreferencesRepository(this);
        mViewModel.setTeamMode(mPreferences.teamMode());
        int gameLimit = GameSettingsRepository.GAME_LIMIT_NONE;
        GameSettingsRepository.getInstance().setTimeLimit(mPreferences.timeLimit());
        if (GameSettingsRepository.getInstance().getTimeLimit() != 0)
            gameLimit += GameSettingsRepository.GAME_LIMIT_TIME;
        GameSettingsRepository.getInstance().setLivesLimit(mPreferences.livesLimit());
        if (GameSettingsRepository.getInstance().getLivesLimit() != 0)
            gameLimit += GameSettingsRepository.GAME_LIMIT_LIVES;
        GameSettingsRepository.getInstance().setScoreLimit(mPreferences.scoreLimit());
        if (GameSettingsRepository.getInstance().getScoreLimit() != 0)
            gameLimit += GameSettingsRepository.GAME_LIMIT_SCORE;
        GameSettingsRepository.getInstance().setGameLimit(gameLimit);
        setGameLimit();
        GameSettingsRepository.getInstance().setStartTime(mPreferences.startTime((int) GameSettingsRepository.START_TIME_SECONDS));
        GameSettingsRepository.getInstance().setStartTime(GameSettingsRepository.getInstance().getStartTime());
        GameSettingsRepository.getInstance().setGpsMode(mPreferences.gpsMode(GameSettingsRepository.GPS_ALL));
        setGPSMode(GameSettingsRepository.getInstance().getGpsMode());
        mAllowJoinSwitch = findViewById(R.id.allow_join_switch);
        GameSettingsRepository.getInstance().setAllowJoinInProgress(mAllowJoinSwitch.isChecked());
        // Whether a new player may join a running game is enforced at the TCP hello. The discovery
        // beacon stays on so a player whose app restarted mid-game can find the server again.
        mAllowJoinSwitch.setOnClickListener((new View.OnClickListener() {
            public void onClick(View v) {
                GameSettingsRepository.getInstance().setAllowJoinInProgress(mAllowJoinSwitch.isChecked());
            }
        }));
        mOnlyServerSettingsSwitch = findViewById(R.id.only_server_settings_switch);
        mOnlyServerSettingsSwitch.setChecked(GameSettingsRepository.getInstance().isOnlyServerSettings());
        mOnlyServerSettingsSwitch.setOnClickListener((new View.OnClickListener() {
            public void onClick(View v) {
                GameSettingsRepository.getInstance().setOnlyServerSettings(mOnlyServerSettingsSwitch.isChecked());
                if (mTcpServer != null)
                    mTcpServer.sendAllGameInfo(TcpServer.SEND_ALL);
            }
        }));
        mAllowChooseTeamsSwitch = findViewById(R.id.allow_choose_teams_switch);
        if (mAllowChooseTeamsSwitch != null) {
            boolean allowChooseTeams = mPreferences.allowChooseTeams();
            GameSettingsRepository.getInstance().setAllowChooseTeams(allowChooseTeams);
            GameSettingsRepository.getInstance().setAllowChooseTeams(allowChooseTeams);
            mAllowChooseTeamsSwitch.setChecked(allowChooseTeams);
            mAllowChooseTeamsSwitch.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean isChecked = mAllowChooseTeamsSwitch.isChecked();
                    GameSettingsRepository.getInstance().setAllowChooseTeams(isChecked);
                    GameSettingsRepository.getInstance().setAllowChooseTeams(isChecked);
                    mPreferences.saveAllowChooseTeams(isChecked);
                    if (mTcpServer != null) {
                        mTcpServer.sendAllGameInfo(TcpServer.SEND_ALL);
                    }
                }
            });
        }
        try {
            // Display app version
            PackageInfo pInfo = this.getPackageManager().getPackageInfo(getPackageName(), 0);
            TextView versionTextView = findViewById(R.id.version_tv);
            versionTextView.setText(pInfo.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mPlayerDisplayListAdapter = new ScoreboardAdapter(false, new ScoreboardAdapter.Listener() {
            @Override
            public void onTeamHeaderClicked() {
                showTeamRatioDialog();
            }

            @Override
            public void onPlayerClicked(byte playerID) {
                showPlayerSettings(playerID);
            }

            @Override
            public void onKickClicked(byte playerID) {
                kickPlayerFromAdmin(playerID);
            }
        });
        mPlayerDisplayList = findViewById(R.id.player_list);
        mPlayerDisplayList.setLayoutManager(new LinearLayoutManager(this));
        mPlayerDisplayList.setAdapter(mPlayerDisplayListAdapter);
        getPlayerDisplayData();
        mViewModel.getPhaseLiveData().observe(this, this::renderPhase);
        // While resumed, as the broadcast receiver it replaces was registered
        ((SimpleCoilApp) getApplication()).getContainer().getServerEvents()
                .observe(this, Lifecycle.State.RESUMED, this::onServerEvent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupUDPServiceConnection();
        setupTcpServerServiceConnection();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mGameCountDownTV.removeCallbacks(mCountdownTicker);
        // Only an operator leaving the screen ends the session; a recreation the manifest's
        // configChanges don't cover must not kick every player (S1).
        if (isFinishing()) {
            // Kick everyone; the server's client thread closes their sockets once it stops.
            if (mTcpServer != null)
                mTcpServer.sendTCPMessageAll(WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_MESG + WireProtocol.NETMSG_SERVERCANCEL);
            if (mUDPListenerService != null)
                mUDPListenerService.stopListen();
            if (mTcpServer != null)
                mTcpServer.stopTcpServer();
        }
        if (mUDPServiceConnection != null)
            unbindService(mUDPServiceConnection);
        if (mTcpServerServiceConnection != null)
            unbindService(mTcpServerServiceConnection);
    }

    /** The operator picked a team mode: reset a finished game, change it, save it and renumber the players. */
    private void applyTeamMode(TeamMode mode) {
        resetIfPostGame();
        mViewModel.setTeamMode(mode);
        GameSettingsRepository.getInstance().resetDefaultTeamSizes(); // even when re-picking the same mode, as always
        mPreferences.saveTeamMode(mode);
        setGPSMode(GameSettingsRepository.getInstance().getGpsMode());
        if (mTcpServer != null) {
            mTcpServer.rebalanceAllPlayers(false);
        }
        getPlayerDisplayData();
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.team_mode_2teams_item) {
            applyTeamMode(TeamMode.TWO_TEAMS);
            return true;
        } else if (id == R.id.team_mode_4teams_item) {
            applyTeamMode(TeamMode.FOUR_TEAMS);
            return true;
        } else if (id == R.id.team_mode_ffa_item) {
            applyTeamMode(TeamMode.FREE_FOR_ALL);
            return true;
        } else if (id == R.id.sort_teams_item) {
            if (mTcpServer != null) {
                resetIfPostGame();
                mTcpServer.rebalanceAllPlayers(true);
                Toast.makeText(DedicatedServerActivity.this, "Teams Sorted & Randomized!", Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (id == R.id.gps_mode_disabled) {
            resetIfPostGame();
            setGPSMode(GameSettingsRepository.GPS_DISABLED);
            return true;
        } else if (id == R.id.gps_mode_teammate) {
            resetIfPostGame();
            setGPSMode(GameSettingsRepository.GPS_TEAMMATE);
            return true;
        } else if (id == R.id.gps_mode_all) {
            resetIfPostGame();
            setGPSMode(GameSettingsRepository.GPS_ALL);
            return true;
        }
        return false;
    }

    private void setGPSMode(int mode) {
        GameSettingsRepository.getInstance().setGpsMode(mode);
        if (mode == GameSettingsRepository.GPS_DISABLED || (GameSettingsRepository.getInstance().getTeamMode() == TeamMode.FREE_FOR_ALL && mode == GameSettingsRepository.GPS_TEAMMATE)) {
            GameSettingsRepository.getInstance().setUseGPS(false);
            mGPSModeButton.setText(R.string.gps_mode_disabled);
        } else {
            GameSettingsRepository.getInstance().setUseGPS(true);
            if (mTcpServer != null)
                mTcpServer.sendGPSData();
            if (mode == GameSettingsRepository.GPS_TEAMMATE)
                mGPSModeButton.setText(R.string.gps_mode_teammate);
            else
                mGPSModeButton.setText(R.string.gps_mode_all);
        }
        mPreferences.saveGpsMode(mode);
        if (mTcpServer != null)
            mTcpServer.sendAllGameInfo(TcpServer.SEND_ALL);
    }

    private void requestGameLimit() {
        showDialog(GameLimitDialog.newInstance(true), GameLimitDialog.REQUEST_KEY);
    }

    /** The game limit dialog's result. */
    private void onGameLimitEntered(String text, LimitKind kind) {
        GameSettingsRepository settings = GameSettingsRepository.getInstance();
        Limits current = new Limits(settings.getGameLimit(), settings.getTimeLimit(), settings.getLivesLimit(), settings.getScoreLimit());
        GameLimitRules.Result result = GameLimitRules.INSTANCE.apply(current, kind, text);
        if (result instanceof GameLimitRules.Result.TooHigh) {
            Toast.makeText(getApplicationContext(), getString(R.string.error_limit_too_high), Toast.LENGTH_SHORT).show();
        } else if (result instanceof GameLimitRules.Result.Applied) {
            resetIfPostGame();
            Limits limits = ((GameLimitRules.Result.Applied) result).getLimits();
            settings.setTimeLimit(limits.getTimeLimit());
            settings.setLivesLimit(limits.getLivesLimit());
            settings.setScoreLimit(limits.getScoreLimit());
            settings.setGameLimit(limits.getGameLimit());
            setGameLimit();
        }
    }

    /** The player settings dialog for network player [playerID]. */
    private void showPlayerSettings(byte playerID) {
        if (playerID <= 0)
            return;
        PlayerSettings settings = PlayerRepository.getInstance().getPlayerSettings().get(playerID);
        PlayerSettingsValues values = PlayerSettingsValues.of(settings != null ? settings : new PlayerSettings());
        TeamLayout layout = GameSettingsRepository.getInstance().getTeamLayout();
        int teamCount = layout.getMode().isFreeForAll() ? 0 : layout.getMode().teamCount;
        int team = layout.teamOf(playerID);
        int indexInTeam = teamCount == 0 ? playerID : playerID - layout.teamStartId(team) + 1;
        showDialog(PlayerSettingsDialog.server(playerID, values, teamCount, team, indexInTeam), PlayerSettingsDialog.REQUEST_KEY);
    }

    /** The player settings dialog's result: kick, or store the settings, move the player and send them. */
    private void onPlayerSettingsSaved(PlayerSettingsDialog.Result result) {
        byte playerID = result.getPlayerId();
        if (result.getKick()) {
            kickPlayerFromAdmin(playerID);
            return;
        }
        PlayerSettingsValues values = result.getValues();
        if (values == null)
            return;
        PlayerRepository repo = PlayerRepository.getInstance();
        synchronized (repo) {
            PlayerSettings playerSettings = repo.getPlayerSettings().get(playerID);
            if (playerSettings == null) {
                playerSettings = new PlayerSettings();
                repo.getPlayerSettings().put(playerID, playerSettings);
            }
            values.applyTo(playerSettings);
        }
        byte targetPlayerID = playerID;
        if (result.getTargetTeam() != 0) {
            byte newPlayerID = repo.findOpenPlayerIDOnTeam(result.getTargetTeam());
            if (newPlayerID == 0) {
                Toast.makeText(this, getString(R.string.error_team_full, result.getTargetTeam()), Toast.LENGTH_SHORT).show();
            } else if (mTcpServer != null) {
                mTcpServer.switchPlayerTeam(playerID, newPlayerID);
                targetPlayerID = newPlayerID;
            }
        }
        if (mTcpServer != null) {
            mTcpServer.sendPlayerSettings(targetPlayerID, result.getApplyToAll(), result.getApplyToTeam(),
                    GameSettingsRepository.getInstance().isAllowPlayerSettings());
        }
        mViewModel.updatePlayerCount();
        getPlayerDisplayData();
    }

    /** The plural [id] for [count], with the count filled in. */
    private String quantity(int id, int count) {
        return getResources().getQuantityString(id, count, count);
    }

    /** Shows [dialog] unless one is already showing under [tag] or the state is saved. */
    private void showDialog(DialogFragment dialog, String tag) {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.isStateSaved() || fm.findFragmentByTag(tag) != null)
            return;
        dialog.show(fm, tag);
    }

    private void setGameLimit() {
        String gameLimits = "";
        if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_TIME) != 0) {
            gameLimits += quantity(R.plurals.dedicated_time_limit, GameSettingsRepository.getInstance().getTimeLimit());
            mGameCountDownTV.setVisibility(View.VISIBLE);
            String display = ClockFormat.minutes(GameSettingsRepository.getInstance().getTimeLimit());
            mGameCountDownTV.setText(display);
            mGameTimer.setVisibility(View.GONE);
        } else {
            mGameCountDownTV.setVisibility(View.GONE);
            mGameTimer.setVisibility(View.VISIBLE);
        }
        if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_LIVES) != 0) {
            if (!gameLimits.isEmpty())
                gameLimits += getString(R.string.dedicated_limit_separator);
            gameLimits += quantity(R.plurals.dedicated_lives_limit, GameSettingsRepository.getInstance().getLivesLimit());
        }
        if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_SCORE) != 0) {
            if (!gameLimits.isEmpty())
                gameLimits += getString(R.string.dedicated_limit_separator);
            gameLimits += quantity(R.plurals.dedicated_score_limit, GameSettingsRepository.getInstance().getScoreLimit());
        }
        if (gameLimits.isEmpty())
            gameLimits = getString(R.string.dedicated_unlimited);
        mGameLimitTV.setText(gameLimits);
        mPreferences.saveLimits(GameSettingsRepository.getInstance().getTimeLimit(), GameSettingsRepository.getInstance().getScoreLimit(),
                GameSettingsRepository.getInstance().getLivesLimit());
        if (mTcpServer != null)
            mTcpServer.sendAllGameInfo(TcpServer.SEND_ALL);
    }

    private void requestStartingTimer() {
        showDialog(StartingTimerDialog.newInstance(GameSettingsRepository.getInstance().getStartTime()), StartingTimerDialog.REQUEST_KEY);
    }

    /** The starting timer dialog's result: empty or not a number changes nothing. */
    private void onStartingTimerEntered(String text) {
        if (text.isEmpty())
            return;
        long time;
        try {
            time = Long.parseLong(text);
        } catch (Exception ignored) {
            return;
        }
        if (time > 1000) {
            Toast.makeText(getApplicationContext(), getString(R.string.error_limit_too_high), Toast.LENGTH_SHORT).show();
            return;
        }
        resetIfPostGame();
        GameSettingsRepository.getInstance().setStartTime(time);
        mPreferences.saveStartTime((int) time);
        if (mTcpServer != null)
            mTcpServer.sendAllGameInfo(TcpServer.SEND_ALL);
    }

    public void kickPlayerFromAdmin(byte playerID) {
        if (mTcpServer != null) {
            mTcpServer.kickPlayer(playerID);
            Toast.makeText(this, "Player kicked", Toast.LENGTH_SHORT).show();
        }
    }

    public void showTeamRatioDialog() {
        if (mTcpServer != null && mTcpServer.getSessionPhase() == SessionPhase.RUNNING) {
            Toast.makeText(this, "Cannot edit team ratios while a game is in progress", Toast.LENGTH_SHORT).show();
            return;
        }

        TeamMode teamMode = GameSettingsRepository.getInstance().getTeamMode();
        if (teamMode.isFreeForAll()) {
            Toast.makeText(this, "Team ratios are not applicable in Free For All mode", Toast.LENGTH_SHORT).show();
            return;
        }

        showDialog(TeamRatioDialog.newInstance(teamMode.teamCount, GameSettingsRepository.getInstance().getTeamLayout().teamSizes()),
                TeamRatioDialog.REQUEST_KEY);
    }

    /** The team ratio dialog's result, already validated. */
    private void onTeamSizesEntered(int[] newSizes) {
        resetIfPostGame();
        GameSettingsRepository.getInstance().setTeamSizes(newSizes);
        if (mTcpServer != null) {
            mTcpServer.rebalanceAllPlayers(false);
        }
        getPlayerDisplayData();
    }

    /**
     * Changing a game setting after a game resets first (reconnect design §3.1.1). The change is
     * the intent, so there is no confirmation, only a toast.
     */
    private void resetIfPostGame() {
        if (mTcpServer != null && mTcpServer.resetIfPostGame())
            Toast.makeText(this, R.string.dedicated_game_reset_toast, Toast.LENGTH_SHORT).show();
    }

    /** Reset button: confirm, since it drops players who may be about to walk back in. */
    private void confirmReset() {
        int disconnected = 0;
        for (PlayerDisplayData player : mPlayerDisplayData) {
            if (player != null && !player.isConnected) disconnected++;
        }
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setMessage(quantity(R.plurals.dedicated_reset_confirm, disconnected))
                .setPositiveButton(R.string.reset_game_button, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        if (mTcpServer != null)
                            mTcpServer.resetSession();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Buttons and status for the server's session phase (reconnect design §3.1). */
    private void renderPhase(SessionPhase phase) {
        boolean running = phase == SessionPhase.RUNNING;
        boolean postGame = phase == SessionPhase.POST_GAME;
        // Reset takes Start's place after a game. Invisible, not gone: other views are laid out against Start.
        mStartGameButton.setVisibility(phase == SessionPhase.LOBBY ? View.VISIBLE : View.INVISIBLE);
        mStartGameButton.setEnabled(phase == SessionPhase.LOBBY);
        mResetGameButton.setVisibility(postGame ? View.VISIBLE : View.INVISIBLE);
        mEndGameButton.setEnabled(running);
        mTeamModeButton.setEnabled(!running);
        mGameLimitButton.setEnabled(!running);
        if (mStartingTimerButton != null) mStartingTimerButton.setEnabled(!running);
        mGPSModeButton.setEnabled(!running);
        if (running)
            mGameStatusTV.setText(R.string.dedicated_game_running);
        else if (postGame)
            mGameStatusTV.setText(R.string.dedicated_game_over);
        else
            mGameStatusTV.setText(R.string.dedicated_game_waiting);
    }

    private void onGameStarted() {
        Log.d(TAG, "DedicatedServerActivity.onGameStarted called. mGameState=" + GameStateRepository.getInstance().getGameState());
        mViewModel.setPhase(SessionPhase.RUNNING);
        GameSettingsRepository.getInstance().setAllowJoinInProgress(mAllowJoinSwitch.isChecked());
        getPlayerDisplayData();
    }

    private void onGameEnded() {
        Log.d(TAG, "DedicatedServerActivity.onGameEnded called. mGameState=" + GameStateRepository.getInstance().getGameState());
        mViewModel.setPhase(SessionPhase.POST_GAME);
        mNetworkPlayerCountTV.setText(quantity(R.plurals.network_player_count, PlayerRepository.getInstance().getPlayerCount(true)));
        getPlayerDisplayData();
    }

    /**
     * Shows the server's game clock (S2: the server keeps the time; this only draws it). A timed
     * game counts its time limit plus the starting timer down; an untimed one runs the
     * chronometer from the end of the starting timer.
     */
    private void renderClock(ClockState state) {
        mClockState = state;
        mGameCountDownTV.removeCallbacks(mCountdownTicker);
        if (state instanceof ClockState.Counting) {
            showCountdown();
        } else if (state instanceof ClockState.Elapsed) {
            mGameTimer.setBase(((ClockState.Elapsed) state).getStartedAtElapsed());
            mGameTimer.start();
        } else if (state == ClockState.Idle.INSTANCE) {
            mGameTimer.stop();
            if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_TIME) != 0)
                mGameCountDownTV.setText(ClockFormat.minutes(GameSettingsRepository.getInstance().getTimeLimit()));
        }
    }

    private void showCountdown() {
        if (!(mClockState instanceof ClockState.Counting))
            return;
        long millisLeft = Math.max(0, ((ClockState.Counting) mClockState).getEndsAtElapsed() - SystemClock.elapsedRealtime());
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millisLeft);
        mGameCountDownTV.setText(ClockFormat.minutesSeconds(minutes, TimeUnit.MILLISECONDS.toSeconds(millisLeft) - TimeUnit.MINUTES.toSeconds(minutes)));
        if (millisLeft > 0)
            mGameCountDownTV.postDelayed(mCountdownTicker, 1000 - millisLeft % 1000 + 1);
    }

    private void onServerEvent(ServerEvent event) {
        Log.d(TAG, "onServerEvent " + event);
        if (event == ServerEvent.PlayerJoined.INSTANCE || event == ServerEvent.PlayerLeft.INSTANCE) {
            // The server ends the game itself when the last but one player leaves.
            mViewModel.updatePlayerCount();
            mNetworkPlayerCountTV.setText(quantity(R.plurals.network_player_count, PlayerRepository.getInstance().getPlayerCount(true)));
            getPlayerDisplayData();
        } else if (event == ServerEvent.GameStarted.INSTANCE) {
            onGameStarted();
        } else if (event == ServerEvent.GameEnded.INSTANCE) {
            onGameEnded();
        } else if (event == ServerEvent.SessionReset.INSTANCE) {
            mViewModel.setPhase(SessionPhase.LOBBY);
            mViewModel.updatePlayerCount();
            mNetworkPlayerCountTV.setText(quantity(R.plurals.network_player_count, PlayerRepository.getInstance().getPlayerCount(true)));
            getPlayerDisplayData();
        } else if (event instanceof ServerEvent.ServerCreated) {
            mServerIPTV.setText(getString(R.string.server_status_serving_on, ((ServerEvent.ServerCreated) event).getIp()));
        } else if (event instanceof ServerEvent.PlayerDataChanged) {
            mViewModel.updatePlayerCount();
            getPlayerDisplayData();
        } else if (event instanceof ServerEvent.ClockChanged) {
            renderClock(((ServerEvent.ClockChanged) event).getState());
        } else if (event == ServerEvent.TimeExpired.INSTANCE) {
            Toast.makeText(getApplicationContext(), getString(R.string.dialog_game_time_expired), Toast.LENGTH_SHORT).show();
        }
    }

    /** The team mode button shows the current mode, whoever changed it (this screen or an admin). */
    private void showTeamMode(TeamMode mode) {
        if (mTeamModeButton == null || mode == null) return;
        switch (mode) {
            case TWO_TEAMS:
                mTeamModeButton.setText(R.string.team_mode_2teams);
                break;
            case FOUR_TEAMS:
                mTeamModeButton.setText(R.string.team_mode_4teams);
                break;
            case FREE_FOR_ALL:
                mTeamModeButton.setText(R.string.team_mode_ffa);
                break;
        }
    }

    private void getPlayerDisplayData() {
        if (mTcpServer == null) return;
        TcpServer.ScoreData[] scores = new TcpServer.ScoreData[GameSettingsRepository.MAX_PLAYER_ID + 1];
        for (byte x = 1; x <= GameSettingsRepository.MAX_PLAYER_ID; x++) {
            scores[x] = mTcpServer.getScore(x);
        }
        PlayerRepository repo = PlayerRepository.getInstance();
        synchronized (repo) {
            for (byte x = 1; x <= GameSettingsRepository.MAX_PLAYER_ID; x++) {
                TcpServer.ScoreData scoreData = scores[x];
                if (scoreData == null) {
                    mPlayerDisplayData[x] = null;
                } else {
                    mPlayerDisplayData[x] = new PlayerDisplayData();
                    mPlayerDisplayData[x].playerID = x;
                    mPlayerDisplayData[x].playerName = repo.getTeamPlayerNameMap().get(x);
                    mPlayerDisplayData[x].points = scoreData.points;
                    mPlayerDisplayData[x].eliminated = scoreData.eliminated;
                    mPlayerDisplayData[x].isConnected = scoreData.isConnected;
                    PlayerSettings settings = repo.getPlayerSettings().get(x);
                    if (settings == null) {
                        mPlayerDisplayData[x].overrideLives = false;
                        mPlayerDisplayData[x].lives = 0;
                    } else {
                        mPlayerDisplayData[x].overrideLives = settings.overrideLives;
                        mPlayerDisplayData[x].lives = settings.lives;
                    }
                }
            }
        }
        if (mPlayerDisplayListAdapter != null) {
            GameSettingsRepository settings = GameSettingsRepository.getInstance();
            ScoreboardContext context = new ScoreboardContext(settings.getTeamLayout(), settings.getGameLimit(),
                    settings.getLivesLimit(), false, repo.getPlayerID(), repo.isAdmin());
            // A diffed update keeps the scroll position (S10)
            mPlayerDisplayListAdapter.submitList(ScoreboardRows.INSTANCE.build(mPlayerDisplayData, context));
        }
    }
}
