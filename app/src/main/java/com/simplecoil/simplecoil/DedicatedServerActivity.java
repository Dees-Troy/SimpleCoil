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

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.os.SystemClock;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
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
import com.simplecoil.simplecoil.domain.rules.TeamSizes;
import com.simplecoil.simplecoil.domain.team.TeamMode;
import com.simplecoil.simplecoil.network.server.SessionPhase;
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardAdapter;
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardContext;
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardRows;
import com.simplecoil.simplecoil.ui.server.DedicatedServerViewModel;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class DedicatedServerActivity extends AppCompatActivity implements PopupMenu.OnMenuItemClickListener {
    private static final String TAG = "DEDSRV";

    private static final String PREF_GPS_MODE = "GPSMode";
    private static final String PREF_ALLOW_CHOOSE_TEAMS = "AllowChooseTeams";

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
    private Switch mAllowJoinSwitch = null;
    private Switch mOnlyServerSettingsSwitch = null;
    private Switch mAllowChooseTeamsSwitch = null;
    private RecyclerView mPlayerDisplayList = null;

    private CountDownTimer mGameCountdownTimer = null;
    private CountDownTimer mSpawnTimer = null;

    private SharedPreferences sharedPreferences = null;
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
        setContentView(R.layout.activity_dedicated_server);
        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
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
                mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, count));
            }
        });
        mViewModel.startServerBeacon("SimpleCoil Server");
        mViewModel.getTeamModeLiveData().observe(this, this::showTeamMode);

        mServerIPTV = findViewById(R.id.server_ip_tv);
        mGameTimer = findViewById(R.id.game_timer_chronometer);
        mGameCountDownTV = findViewById(R.id.game_countdown_tv);
        mNetworkPlayerCountTV = findViewById(R.id.player_count_tv);
        mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, 0));
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
        sharedPreferences = getSharedPreferences(FullscreenActivity.PREF_NAME, Context.MODE_PRIVATE);
        mPreferences = new UserPreferencesRepository(sharedPreferences);
        mViewModel.setTeamMode(mPreferences.teamMode());
        int gameLimit = GameSettingsRepository.GAME_LIMIT_NONE;
        GameSettingsRepository.getInstance().setTimeLimit(sharedPreferences.getInt(FullscreenActivity.PREF_LIMIT_TIME, 0));
        if (GameSettingsRepository.getInstance().getTimeLimit() != 0)
            gameLimit += GameSettingsRepository.GAME_LIMIT_TIME;
        GameSettingsRepository.getInstance().setLivesLimit(sharedPreferences.getInt(FullscreenActivity.PREF_LIMIT_LIVES, 0));
        if (GameSettingsRepository.getInstance().getLivesLimit() != 0)
            gameLimit += GameSettingsRepository.GAME_LIMIT_LIVES;
        GameSettingsRepository.getInstance().setScoreLimit(sharedPreferences.getInt(FullscreenActivity.PREF_LIMIT_SCORE, 0));
        if (GameSettingsRepository.getInstance().getScoreLimit() != 0)
            gameLimit += GameSettingsRepository.GAME_LIMIT_SCORE;
        GameSettingsRepository.getInstance().setGameLimit(gameLimit);
        setGameLimit();
        GameSettingsRepository.getInstance().setStartTime(sharedPreferences.getInt(FullscreenActivity.PREF_START_TIME, (int) GameSettingsRepository.START_TIME_SECONDS));
        GameSettingsRepository.getInstance().setStartTime(GameSettingsRepository.getInstance().getStartTime());
        GameSettingsRepository.getInstance().setGpsMode(sharedPreferences.getInt(PREF_GPS_MODE, GameSettingsRepository.GPS_ALL));
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
            boolean allowChooseTeams = sharedPreferences.getBoolean(PREF_ALLOW_CHOOSE_TEAMS, true);
            GameSettingsRepository.getInstance().setAllowChooseTeams(allowChooseTeams);
            GameSettingsRepository.getInstance().setAllowChooseTeams(allowChooseTeams);
            mAllowChooseTeamsSwitch.setChecked(allowChooseTeams);
            mAllowChooseTeamsSwitch.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean isChecked = mAllowChooseTeamsSwitch.isChecked();
                    GameSettingsRepository.getInstance().setAllowChooseTeams(isChecked);
                    GameSettingsRepository.getInstance().setAllowChooseTeams(isChecked);
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putBoolean(PREF_ALLOW_CHOOSE_TEAMS, isChecked);
                    editor.apply();
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
                if (playerID <= 0)
                    return;
                PlayerSettingsAlertDialog dialog = new PlayerSettingsAlertDialog(DedicatedServerActivity.this);
                dialog.setServer(playerID, mTcpServer);
                dialog.show();
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextCompat.registerReceiver(this, mServerUpdateReceiver, makeServerUpdateIntentFilter(), ContextCompat.RECEIVER_NOT_EXPORTED);
        setupUDPServiceConnection();
        setupTcpServerServiceConnection();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mServerUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Only an operator leaving the screen ends the session; a recreation the manifest's
        // configChanges don't cover must not kick every player (S1).
        if (isFinishing()) {
            // Kick everyone; the server's client thread closes their sockets once it stops.
            if (mTcpServer != null)
                mTcpServer.sendTCPMessageAll(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_SERVERCANCEL);
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

    @Override
    public void sendBroadcast(Intent intent) {
        if (intent != null && intent.getPackage() == null) {
            intent.setPackage(getPackageName());
        }
        super.sendBroadcast(intent);
    }

    private void savePreference(String prefName, int prefValue) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(prefName, prefValue);
        editor.apply();
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
        savePreference(PREF_GPS_MODE, mode);
        if (mTcpServer != null)
            mTcpServer.sendAllGameInfo(TcpServer.SEND_ALL);
    }

    private void requestGameLimit() {
        LayoutInflater li = LayoutInflater.from(getApplicationContext());
        View view = li.inflate(R.layout.game_limit_dialog, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        alertDialogBuilder.setView(view);

        final EditText gameLimitET = view.findViewById(R.id.game_limit_et);
        final RadioButton gameLimitTime = view.findViewById(R.id.game_limit_time_radio);
        gameLimitTime.setChecked(true);
        final RadioButton gameLimitLives = view.findViewById(R.id.game_limit_lives_radio);
        gameLimitLives.setChecked(false);
        final RadioButton gameLimitScore = view.findViewById(R.id.game_limit_score_radio);
        gameLimitScore.setChecked(false);
        gameLimitScore.setVisibility(View.VISIBLE);;
        gameLimitTime.setOnClickListener((new View.OnClickListener() {
            public void onClick(View v) {
                gameLimitTime.setChecked(true);
                gameLimitLives.setChecked(false);
                gameLimitScore.setChecked(false);
            }
        }));
        gameLimitLives.setOnClickListener((new View.OnClickListener() {
            public void onClick(View v) {
                gameLimitTime.setChecked(false);
                gameLimitLives.setChecked(true);
                gameLimitScore.setChecked(false);
            }
        }));
        gameLimitScore.setOnClickListener((new View.OnClickListener() {
            public void onClick(View v) {
                gameLimitTime.setChecked(false);
                gameLimitLives.setChecked(false);
                gameLimitScore.setChecked(true);
            }
        }));

        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton(R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,int id) {
                                LimitKind kind = gameLimitTime.isChecked() ? LimitKind.TIME
                                        : gameLimitLives.isChecked() ? LimitKind.LIVES : LimitKind.SCORE;
                                GameSettingsRepository settings = GameSettingsRepository.getInstance();
                                Limits current = new Limits(settings.getGameLimit(), settings.getTimeLimit(), settings.getLivesLimit(), settings.getScoreLimit());
                                GameLimitRules.Result result = GameLimitRules.INSTANCE.apply(current, kind, gameLimitET.getText().toString());
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
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton(R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,int id) {
                                dialog.cancel();
                            }
                        });
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    private void setGameLimit() {
        String gameLimits = "";
        if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_TIME) != 0) {
            gameLimits += getString(R.string.dedicated_time_limit, GameSettingsRepository.getInstance().getTimeLimit());
            mGameCountDownTV.setVisibility(View.VISIBLE);
            String display = String.format("%02d:00", GameSettingsRepository.getInstance().getTimeLimit());
            mGameCountDownTV.setText(display);
            mGameTimer.setVisibility(View.GONE);
        } else {
            mGameCountDownTV.setVisibility(View.GONE);
            mGameTimer.setVisibility(View.VISIBLE);
        }
        if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_LIVES) != 0) {
            if (!gameLimits.isEmpty())
                gameLimits += getString(R.string.dedicated_limit_separator);
            gameLimits += getString(R.string.dedicated_lives_limit, GameSettingsRepository.getInstance().getLivesLimit());
        }
        if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_SCORE) != 0) {
            if (!gameLimits.isEmpty())
                gameLimits += getString(R.string.dedicated_limit_separator);
            gameLimits += getString(R.string.dedicated_score_limit, GameSettingsRepository.getInstance().getScoreLimit());
        }
        if (gameLimits.isEmpty())
            gameLimits = getString(R.string.dedicated_unlimited);
        mGameLimitTV.setText(gameLimits);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(FullscreenActivity.PREF_LIMIT_TIME, GameSettingsRepository.getInstance().getTimeLimit());
        editor.putInt(FullscreenActivity.PREF_LIMIT_SCORE, GameSettingsRepository.getInstance().getScoreLimit());
        editor.putInt(FullscreenActivity.PREF_LIMIT_LIVES, GameSettingsRepository.getInstance().getLivesLimit());
        editor.apply();
        if (mTcpServer != null)
            mTcpServer.sendAllGameInfo(TcpServer.SEND_ALL);
    }

    private void requestStartingTimer() {
        LayoutInflater li = LayoutInflater.from(getApplicationContext());
        View view = li.inflate(R.layout.starting_timer_dialog, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        alertDialogBuilder.setView(view);

        final EditText startingTimerET = view.findViewById(R.id.starting_timer_et);
        startingTimerET.setText(String.valueOf(GameSettingsRepository.getInstance().getStartTime()));

        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton(R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                if (startingTimerET.getText().toString().isEmpty()) {
                                    dialog.dismiss();
                                    return;
                                }
                                long time;
                                try {
                                    time = Long.parseLong(startingTimerET.getText().toString());
                                } catch (Exception ignored) {
                                    dialog.dismiss();
                                    return;
                                }
                                if (time > 1000) {
                                    Toast.makeText(getApplicationContext(), getString(R.string.error_limit_too_high), Toast.LENGTH_SHORT).show();
                                    dialog.dismiss();
                                    return;
                                }
                                resetIfPostGame();
                                GameSettingsRepository.getInstance().setStartTime(time);
                                GameSettingsRepository.getInstance().setStartTime(time);
                                savePreference(FullscreenActivity.PREF_START_TIME, (int) time);
                                if (mTcpServer != null)
                                    mTcpServer.sendAllGameInfo(TcpServer.SEND_ALL);
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton(R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dialog.cancel();
                            }
                        });
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
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

        LayoutInflater li = LayoutInflater.from(this);
        View view = li.inflate(R.layout.team_ratio_dialog, null);

        final int numTeams = teamMode.teamCount;
        final LinearLayout team3Layout = view.findViewById(R.id.team3_layout);
        final LinearLayout team4Layout = view.findViewById(R.id.team4_layout);

        final EditText team1ET = view.findViewById(R.id.team1_ratio_et);
        final EditText team2ET = view.findViewById(R.id.team2_ratio_et);
        final EditText team3ET = view.findViewById(R.id.team3_ratio_et);
        final EditText team4ET = view.findViewById(R.id.team4_ratio_et);

        int[] currentSizes = GameSettingsRepository.getInstance().getTeamLayout().teamSizes();

        team1ET.setText(String.valueOf(currentSizes[0]));
        team2ET.setText(String.valueOf(currentSizes[1]));

        if (numTeams == 4) {
            team3Layout.setVisibility(View.VISIBLE);
            team4Layout.setVisibility(View.VISIBLE);
            team3ET.setText(String.valueOf(currentSizes[2]));
            team4ET.setText(String.valueOf(currentSizes[3]));
        } else {
            team3Layout.setVisibility(View.GONE);
            team4Layout.setVisibility(View.GONE);
        }

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        alertDialogBuilder.setView(view);
        alertDialogBuilder.setTitle("Configure Team Sizes");

        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                    }
                });

        final AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();

        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<String> texts = new ArrayList<>();
                for (EditText editText : new EditText[]{team1ET, team2ET, team3ET, team4ET}) {
                    texts.add(editText.getText().toString());
                }
                TeamSizes.Result result = TeamSizes.INSTANCE.validate(texts, numTeams);
                if (result instanceof TeamSizes.Result.BelowOne) {
                    Toast.makeText(DedicatedServerActivity.this, "Team limits must be at least 1 player slot", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (result instanceof TeamSizes.Result.WrongTotal) {
                    int totalEnteredSum = ((TeamSizes.Result.WrongTotal) result).getTotal();
                    Toast.makeText(DedicatedServerActivity.this, "Total player slots across all teams must equal " + GameSettingsRepository.MAX_PLAYER_ID + " (current sum: " + totalEnteredSum + ")", Toast.LENGTH_LONG).show();
                    return;
                }
                int[] newSizes = ((TeamSizes.Result.Valid) result).getSizes();

                resetIfPostGame();
                GameSettingsRepository.getInstance().setTeamSizes(newSizes);
                if (mTcpServer != null) {
                    mTcpServer.rebalanceAllPlayers(false);
                }
                getPlayerDisplayData();
                alertDialog.dismiss();
            }
        });
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
        new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert)
                .setMessage(getString(R.string.dedicated_reset_confirm, disconnected))
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
        if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_TIME) != 0) {
            startGameCountdown();
        } else {
            mSpawnTimer = new CountDownTimer(GameSettingsRepository.getInstance().getStartTime() * 1000, 999) {

                public void onTick(long millisUntilFinished) {
                    // Do nothing
                }

                public void onFinish() {
                    mGameTimer.setBase(SystemClock.elapsedRealtime());
                    mGameTimer.start();
                }
            }.start();
        }
        getPlayerDisplayData();
    }

    private void onGameEnded() {
        Log.d(TAG, "DedicatedServerActivity.onGameEnded called. mGameState=" + GameStateRepository.getInstance().getGameState());
        mViewModel.setPhase(SessionPhase.POST_GAME);
        mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, PlayerRepository.getInstance().getPlayerCount(true)));
        mGameTimer.stop();
        if (mSpawnTimer != null) {
            mSpawnTimer.cancel();
            mSpawnTimer = null;
        }
        if (mGameCountdownTimer != null) {
            mGameCountdownTimer.cancel();
            mGameCountdownTimer = null;
        }
        GameStateRepository.getInstance().setServerGameTimeRemaining(0);
        if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_TIME) != 0 && mGameCountDownTV != null) {
            String display = String.format("%02d:00", GameSettingsRepository.getInstance().getTimeLimit());
            mGameCountDownTV.setText(display);
        }
        getPlayerDisplayData();
    }

    private final BroadcastReceiver mServerUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.d(TAG, "mServerUpdateReceiver onReceive action=" + action);
            if (NetMsg.NETMSG_JOIN.equals(action) || NetMsg.NETMSG_LEAVE.equals(action)) {
                // The server ends the game itself when the last but one player leaves.
                mViewModel.updatePlayerCount();
                mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, PlayerRepository.getInstance().getPlayerCount(true)));
                getPlayerDisplayData();
            } else if (NetMsg.NETMSG_STARTGAME.equals(action)) {
                onGameStarted();
            } else if (NetMsg.NETMSG_ENDGAME.equals(action)) {
                onGameEnded();
            } else if (NetMsg.NETMSG_SESSIONRESET.equals(action)) {
                mViewModel.setPhase(SessionPhase.LOBBY);
                mViewModel.updatePlayerCount();
                mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, PlayerRepository.getInstance().getPlayerCount(true)));
                getPlayerDisplayData();
            } else if (NetMsg.NETMSG_ERROR.equals(action)) {
                String errorMessage = intent.getStringExtra(UDPListenerService.INTENT_MESSAGE);
                if (errorMessage != null && !errorMessage.isEmpty()) {
                    Toast.makeText(getApplicationContext(), errorMessage, Toast.LENGTH_SHORT).show();
                } else {
                    Log.d(TAG, "No error message given");
                }
            } else if (NetMsg.NETMSG_SERVERCREATED.equals(action)) {
                // UDP service is listening
                String ip = GameStateRepository.getInstance().getServerIP().toString();
                if (ip.startsWith("/"))
                    ip = ip.substring(1);
                mServerIPTV.setText(getString(R.string.server_status_serving_on, ip));
            } else if (NetMsg.NETMSG_SERVERCANCEL.equals(action)) {
                mServerIPTV.setText("");
                Toast.makeText(getApplicationContext(), getString(R.string.error_server_cancel), Toast.LENGTH_SHORT).show();
            } else if (NetMsg.NETMSG_PLAYERDATAUPDATE.equals(action)) {
                mViewModel.updatePlayerCount();
                getPlayerDisplayData();
            }
        }
    };

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

    private static IntentFilter makeServerUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(NetMsg.NETMSG_JOIN);
        intentFilter.addAction(NetMsg.NETMSG_LEAVE);
        intentFilter.addAction(NetMsg.NETMSG_STARTGAME);
        intentFilter.addAction(NetMsg.NETMSG_ENDGAME);
        intentFilter.addAction(NetMsg.NETMSG_ERROR);
        intentFilter.addAction(NetMsg.NETMSG_SERVERCREATED);
        intentFilter.addAction(NetMsg.NETMSG_SERVERCANCEL);
        intentFilter.addAction(NetMsg.NETMSG_PLAYERDATAUPDATE);
        intentFilter.addAction(NetMsg.NETMSG_SESSIONRESET);
        return intentFilter;
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

    private void startGameCountdown() {
        if (mGameCountdownTimer != null)
            mGameCountdownTimer.cancel();
        mGameCountdownTimer = new CountDownTimer((GameSettingsRepository.getInstance().getTimeLimit() * 60 * 1000) + (GameSettingsRepository.getInstance().getStartTime() * 1000), 1000) {

            public void onTick(long millisUntilFinished) {
                String display = ""+String.format("%02d:%02d",
                        TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished),
                        TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) - TimeUnit.MINUTES.toSeconds(
                                TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished)));
                mGameCountDownTV.setText(display);
                GameStateRepository.getInstance().setServerGameTimeRemaining(millisUntilFinished / 1000);
            }

            public void onFinish() {
                Log.d(TAG, "Game time ended!");
                Toast.makeText(getApplicationContext(), getString(R.string.dialog_game_time_expired), Toast.LENGTH_SHORT).show();
                if (mTcpServer != null)
                    mTcpServer.endGame();
                GameStateRepository.getInstance().setServerGameTimeRemaining(0);
            }
        }.start();
    }

}
