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
import android.graphics.drawable.AnimationDrawable;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.network.server.SessionPhase;
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
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.opengles.GL;

public class DedicatedServerActivity extends AppCompatActivity implements PopupMenu.OnMenuItemClickListener {
    private static final String TAG = "DEDSRV";

    private static final String PREF_GPS_MODE = "GPSMode";
    private static final String PREF_ALLOW_CHOOSE_TEAMS = "AllowChooseTeams";

    private TextView mServerIPTV = null;
    private Chronometer mGameTimer = null;
    private TextView mGameCountDownTV = null;
    private TextView mNetworkPlayerCountTV = null;
    private Button mGameModeButton = null;
    private Button mGameLimitButton = null;
    private Button mGPSModeButton = null;
    private Button mEndGameButton = null;
    private Button mStartGameButton = null;
    private Button mResetGameButton = null;
    private TextView mGameLimitTV = null;
    private TextView mGameStatusTV = null;
    private Switch mAllowJoinSwitch = null;
    private Switch mOnlyServerSettingsSwitch = null;
    private Switch mAllowChooseTeamsSwitch = null;
    private ListView mPlayerDisplayList = null;

    private CountDownTimer mGameCountdownTimer = null;
    private CountDownTimer mSpawnTimer = null;

    private SharedPreferences sharedPreferences = null;

    private DedicatedServerViewModel mViewModel = null;

    private PlayerDisplayData[] mPlayerDisplayData = new PlayerDisplayData[Globals.MAX_PLAYER_ID + 2];
    PlayerDisplayDataListAdapter mPlayerDisplayListAdapter = null;

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
        Globals.getInstance().mPlayerID = 0;
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;

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

        mServerIPTV = findViewById(R.id.server_ip_tv);
        mGameTimer = findViewById(R.id.game_timer_chronometer);
        mGameCountDownTV = findViewById(R.id.game_countdown_tv);
        mNetworkPlayerCountTV = findViewById(R.id.player_count_tv);
        mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, 0));
        mGameModeButton = findViewById(R.id.game_mode_toggle_button);
        if (mGameModeButton != null) {
            mGameModeButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    PopupMenu popup = new PopupMenu(new ContextThemeWrapper(DedicatedServerActivity.this, R.style.AppTheme_PopupOverlay), v);
                    MenuInflater inflater = popup.getMenuInflater();
                    inflater.inflate(R.menu.game_mode_menu, popup.getMenu());
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
                    Log.d(TAG, "DedicatedServer UI: Start Game clicked. getPlayerCount=" + Globals.getPlayerCount(true));
                    if (Globals.getPlayerCount(true) <= 1) {
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
        });        sharedPreferences = getSharedPreferences(FullscreenActivity.PREF_NAME, Context.MODE_PRIVATE);
        Globals.getInstance().mGameMode = sharedPreferences.getInt(FullscreenActivity.PREF_GAME_MODE, Globals.GAME_MODE_2TEAMS);
        if (Globals.getInstance().mGameMode == Globals.GAME_MODE_2TEAMS)
            mGameModeButton.setText(R.string.game_mode_2teams);
        else if (Globals.getInstance().mGameMode == Globals.GAME_MODE_4TEAMS)
            mGameModeButton.setText(R.string.game_mode_4teams);
        else
            mGameModeButton.setText(R.string.game_mode_ffa);
        int gameLimit = Globals.GAME_LIMIT_NONE;
        Globals.getInstance().mTimeLimit = sharedPreferences.getInt(FullscreenActivity.PREF_LIMIT_TIME, 0);
        if (Globals.getInstance().mTimeLimit != 0)
            gameLimit += Globals.GAME_LIMIT_TIME;
        Globals.getInstance().mLivesLimit = sharedPreferences.getInt(FullscreenActivity.PREF_LIMIT_LIVES, 0);
        if (Globals.getInstance().mLivesLimit != 0)
            gameLimit += Globals.GAME_LIMIT_LIVES;
        Globals.getInstance().mScoreLimit = sharedPreferences.getInt(FullscreenActivity.PREF_LIMIT_SCORE, 0);
        if (Globals.getInstance().mScoreLimit != 0)
            gameLimit += Globals.GAME_LIMIT_SCORE;
        Globals.getInstance().mGameLimit = gameLimit;
        setGameLimit();
        Globals.getInstance().mGPSMode = sharedPreferences.getInt(PREF_GPS_MODE, Globals.GPS_ALL);
        setGPSMode(Globals.getInstance().mGPSMode);
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
        mOnlyServerSettingsSwitch.setChecked(Globals.getInstance().mOnlyServerSettings);
        mOnlyServerSettingsSwitch.setOnClickListener((new View.OnClickListener() {
            public void onClick(View v) {
                Globals.getInstance().mOnlyServerSettings = mOnlyServerSettingsSwitch.isChecked();
                mTcpServer.sendAllGameInfo(TcpServer.SEND_ALL);
            }
        }));
        mAllowChooseTeamsSwitch = findViewById(R.id.allow_choose_teams_switch);
        if (mAllowChooseTeamsSwitch != null) {
            boolean allowChooseTeams = sharedPreferences.getBoolean(PREF_ALLOW_CHOOSE_TEAMS, true);
            Globals.getInstance().mAllowChooseTeams = allowChooseTeams;
            GameSettingsRepository.getInstance().setAllowChooseTeams(allowChooseTeams);
            mAllowChooseTeamsSwitch.setChecked(allowChooseTeams);
            mAllowChooseTeamsSwitch.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean isChecked = mAllowChooseTeamsSwitch.isChecked();
                    Globals.getInstance().mAllowChooseTeams = isChecked;
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

        getPlayerDisplayData();
        mPlayerDisplayListAdapter = new PlayerDisplayDataListAdapter(DedicatedServerActivity.this, mPlayerDisplayData, false);
        mPlayerDisplayList = findViewById(R.id.player_list);
        mPlayerDisplayList.setAdapter(mPlayerDisplayListAdapter);
        Button shuffleTeamsButton = findViewById(R.id.shuffle_teams_button);
        if (shuffleTeamsButton != null) {
            shuffleTeamsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mTcpServer != null) {
                        resetIfPostGame();
                        mTcpServer.rebalanceAllPlayers(true);
                        Toast.makeText(DedicatedServerActivity.this, "Teams Sorted & Randomized!", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
        mPlayerDisplayList.setOnItemClickListener(new AdapterView.OnItemClickListener() {

            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (mPlayerDisplayListAdapter != null) {
                    PlayerDisplayDataListAdapter.DisplayRow row = mPlayerDisplayListAdapter.getDisplayRow(position);
                    if (row != null && row.type == PlayerDisplayDataListAdapter.DisplayRow.TYPE_TEAM_HEADER && row.teamNumber > 0) {
                        showTeamRatioDialog();
                        return;
                    }
                }
                byte playerID = mPlayerDisplayListAdapter.getPlayerID(position);
                if (playerID <= 0)
                    return;
                PlayerSettingsAlertDialog dialog = new PlayerSettingsAlertDialog(DedicatedServerActivity.this);
                dialog.setServer(playerID, mTcpServer);
                dialog.show();
            }
        });
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
        Globals.getInstance().mUseGPS = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Kick everyone; the server's client thread closes their sockets once it stops.
        if (mTcpServer != null)
            mTcpServer.sendTCPMessageAll(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_SERVERCANCEL);
        if (mUDPListenerService != null)
            mUDPListenerService.stopListen();
        if (mUDPServiceConnection != null)
            unbindService(mUDPServiceConnection);
        if (mTcpServer != null)
            mTcpServer.stopTcpServer();
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

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.game_mode_2teams_item) {
            resetIfPostGame();
            Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
            Globals.getInstance().resetDefaultTeamSizes();
            mGameModeButton.setText(R.string.game_mode_2teams);
            savePreference(FullscreenActivity.PREF_GAME_MODE, Globals.getInstance().mGameMode);
            setGPSMode(Globals.getInstance().mGPSMode);
            if (mTcpServer != null) {
                mTcpServer.rebalanceAllPlayers(false);
            }
            getPlayerDisplayData();
            return true;
        } else if (id == R.id.game_mode_4teams_item) {
            resetIfPostGame();
            Globals.getInstance().mGameMode = Globals.GAME_MODE_4TEAMS;
            Globals.getInstance().resetDefaultTeamSizes();
            mGameModeButton.setText(R.string.game_mode_4teams);
            savePreference(FullscreenActivity.PREF_GAME_MODE, Globals.getInstance().mGameMode);
            setGPSMode(Globals.getInstance().mGPSMode);
            if (mTcpServer != null) {
                mTcpServer.rebalanceAllPlayers(false);
            }
            getPlayerDisplayData();
            return true;
        } else if (id == R.id.game_mode_ffa_item) {
            resetIfPostGame();
            Globals.getInstance().mGameMode = Globals.GAME_MODE_FFA;
            Globals.getInstance().resetDefaultTeamSizes();
            mGameModeButton.setText(R.string.game_mode_ffa);
            savePreference(FullscreenActivity.PREF_GAME_MODE, Globals.getInstance().mGameMode);
            setGPSMode(Globals.getInstance().mGPSMode);
            if (mTcpServer != null) {
                mTcpServer.rebalanceAllPlayers(false);
            }
            getPlayerDisplayData();
            return true;
        } else if (id == R.id.gps_mode_disabled) {
            resetIfPostGame();
            setGPSMode(Globals.GPS_DISABLED);
            return true;
        } else if (id == R.id.gps_mode_teammate) {
            resetIfPostGame();
            setGPSMode(Globals.GPS_TEAMMATE);
            return true;
        } else if (id == R.id.gps_mode_all) {
            resetIfPostGame();
            setGPSMode(Globals.GPS_ALL);
            return true;
        }
        return false;
    }

    private void setGPSMode(int mode) {
        Globals.getInstance().mGPSMode = mode;
        if (mode == Globals.GPS_DISABLED || (Globals.getInstance().mGameMode == Globals.GAME_MODE_FFA && mode == Globals.GPS_TEAMMATE)) {
            Globals.getInstance().mUseGPS = false;
            mGPSModeButton.setText(R.string.gps_mode_disabled);
        } else {
            Globals.getInstance().mUseGPS = true;
            if (mTcpServer != null)
                mTcpServer.sendGPSData();
            if (mode == Globals.GPS_TEAMMATE)
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
                                if (gameLimitET.getText().toString().isEmpty()) {
                                    dialog.dismiss();
                                    return;
                                }
                                Integer limit = 0;
                                try {
                                    limit = Integer.parseInt(gameLimitET.getText().toString());
                                } catch (Exception ignored) {
                                    dialog.dismiss();
                                    return;
                                }
                                if (limit > 100) {
                                    Toast.makeText(getApplicationContext(), getString(R.string.error_limit_too_high), Toast.LENGTH_SHORT).show();
                                    dialog.dismiss();
                                    return;
                                }
                                resetIfPostGame();
                                int gameLimit = Globals.getInstance().mGameLimit;
                                if (gameLimitTime.isChecked()) {
                                    if ((gameLimit & Globals.GAME_LIMIT_TIME) == 0)
                                        gameLimit += Globals.GAME_LIMIT_TIME;
                                    else if (limit <= 0)
                                        gameLimit -= Globals.GAME_LIMIT_TIME;
                                    Globals.getInstance().mTimeLimit = limit;
                                } else if (gameLimitLives.isChecked()) {
                                    if ((gameLimit & Globals.GAME_LIMIT_LIVES) == 0)
                                        gameLimit += Globals.GAME_LIMIT_LIVES;
                                    else if (limit <= 0)
                                        gameLimit -= Globals.GAME_LIMIT_LIVES;
                                    Globals.getInstance().mLivesLimit = limit;
                                } else {
                                    if ((gameLimit & Globals.GAME_LIMIT_SCORE) == 0)
                                        gameLimit += Globals.GAME_LIMIT_SCORE;
                                    else if (limit <= 0)
                                        gameLimit -= Globals.GAME_LIMIT_SCORE;
                                    Globals.getInstance().mScoreLimit = limit;
                                }
                                Globals.getInstance().mGameLimit = gameLimit;
                                setGameLimit();
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
        if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0) {
            gameLimits += getString(R.string.dedicated_time_limit, Globals.getInstance().mTimeLimit);
            mGameCountDownTV.setVisibility(View.VISIBLE);
            String display = String.format("%02d:00", Globals.getInstance().mTimeLimit);
            mGameCountDownTV.setText(display);
            mGameTimer.setVisibility(View.GONE);
        } else {
            mGameCountDownTV.setVisibility(View.GONE);
            mGameTimer.setVisibility(View.VISIBLE);
        }
        if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_LIVES) != 0) {
            if (!gameLimits.isEmpty())
                gameLimits += getString(R.string.dedicated_limit_separator);
            gameLimits += getString(R.string.dedicated_lives_limit, Globals.getInstance().mLivesLimit);
        }
        if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_SCORE) != 0) {
            if (!gameLimits.isEmpty())
                gameLimits += getString(R.string.dedicated_limit_separator);
            gameLimits += getString(R.string.dedicated_score_limit, Globals.getInstance().mScoreLimit);
        }
        if (gameLimits.isEmpty())
            gameLimits = getString(R.string.dedicated_unlimited);
        mGameLimitTV.setText(gameLimits);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(FullscreenActivity.PREF_LIMIT_TIME, Globals.getInstance().mTimeLimit);
        editor.putInt(FullscreenActivity.PREF_LIMIT_SCORE, Globals.getInstance().mScoreLimit);
        editor.putInt(FullscreenActivity.PREF_LIMIT_LIVES, Globals.getInstance().mLivesLimit);
        editor.apply();
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

        int gameMode = Globals.getInstance().mGameMode;
        if (gameMode == Globals.GAME_MODE_FFA) {
            Toast.makeText(this, "Team ratios are not applicable in Free For All mode", Toast.LENGTH_SHORT).show();
            return;
        }

        LayoutInflater li = LayoutInflater.from(this);
        View view = li.inflate(R.layout.team_ratio_dialog, null);

        final int numTeams = (gameMode == Globals.GAME_MODE_4TEAMS) ? 4 : 2;
        final LinearLayout team3Layout = view.findViewById(R.id.team3_layout);
        final LinearLayout team4Layout = view.findViewById(R.id.team4_layout);

        final EditText team1ET = view.findViewById(R.id.team1_ratio_et);
        final EditText team2ET = view.findViewById(R.id.team2_ratio_et);
        final EditText team3ET = view.findViewById(R.id.team3_ratio_et);
        final EditText team4ET = view.findViewById(R.id.team4_ratio_et);

        int[] currentSizes = Globals.getInstance().getTeamSizes();

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
                EditText[] editTexts = new EditText[]{team1ET, team2ET, team3ET, team4ET};
                int[] newSizes = new int[numTeams];
                boolean invalidInput = false;
                int totalEnteredSum = 0;

                for (int i = 0; i < numTeams; i++) {
                    String text = editTexts[i].getText().toString().trim();
                    if (text.isEmpty()) {
                        invalidInput = true;
                        break;
                    }
                    try {
                        int val = Integer.parseInt(text);
                        if (val <= 0) {
                            invalidInput = true;
                            break;
                        }
                        newSizes[i] = val;
                        totalEnteredSum += val;
                    } catch (NumberFormatException e) {
                        invalidInput = true;
                        break;
                    }
                }

                if (invalidInput) {
                    Toast.makeText(DedicatedServerActivity.this, "Team limits must be at least 1 player slot", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (totalEnteredSum != Globals.MAX_PLAYER_ID) {
                    Toast.makeText(DedicatedServerActivity.this, "Total player slots across all teams must equal " + Globals.MAX_PLAYER_ID + " (current sum: " + totalEnteredSum + ")", Toast.LENGTH_LONG).show();
                    return;
                }

                resetIfPostGame();
                Globals.getInstance().setTeamSizes(newSizes);
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
        mGameModeButton.setEnabled(!running);
        mGameLimitButton.setEnabled(!running);
        mGPSModeButton.setEnabled(!running);
        Button shuffleTeamsBtn = findViewById(R.id.shuffle_teams_button);
        if (shuffleTeamsBtn != null) shuffleTeamsBtn.setEnabled(!running);
        if (running)
            mGameStatusTV.setText(R.string.dedicated_game_running);
        else if (postGame)
            mGameStatusTV.setText(R.string.dedicated_game_over);
        else
            mGameStatusTV.setText(R.string.dedicated_game_waiting);
    }

    private void onGameStarted() {
        Log.d(TAG, "DedicatedServerActivity.onGameStarted called. mGameState=" + Globals.getInstance().mGameState);
        mViewModel.setPhase(SessionPhase.RUNNING);
        GameSettingsRepository.getInstance().setAllowJoinInProgress(mAllowJoinSwitch.isChecked());
        if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0) {
            startGameCountdown();
        } else {
            mSpawnTimer = new CountDownTimer(Globals.getInstance().mRespawnTime * 1000, 999) {

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
        Log.d(TAG, "DedicatedServerActivity.onGameEnded called. mGameState=" + Globals.getInstance().mGameState);
        mViewModel.setPhase(SessionPhase.POST_GAME);
        mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, Globals.getPlayerCount(true)));
        mGameTimer.stop();
        if (mSpawnTimer != null) {
            mSpawnTimer.cancel();
            mSpawnTimer = null;
        }
        if (mGameCountdownTimer != null) {
            mGameCountdownTimer.cancel();
            mGameCountdownTimer = null;
        }
        Globals.getInstance().mServerGameTimeRemaining = 0;
        if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0 && mGameCountDownTV != null) {
            String display = String.format("%02d:00", Globals.getInstance().mTimeLimit);
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
                mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, Globals.getPlayerCount(true)));
                getPlayerDisplayData();
            } else if (NetMsg.NETMSG_STARTGAME.equals(action)) {
                onGameStarted();
            } else if (NetMsg.NETMSG_ENDGAME.equals(action)) {
                onGameEnded();
            } else if (NetMsg.NETMSG_SESSIONRESET.equals(action)) {
                mViewModel.setPhase(SessionPhase.LOBBY);
                mViewModel.updatePlayerCount();
                mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, Globals.getPlayerCount(true)));
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
                String ip = Globals.getInstance().mServerIP.toString();
                if (ip.startsWith("/"))
                    ip = ip.substring(1);
                mServerIPTV.setText(getString(R.string.server_status_serving_on, ip));
            } else if (NetMsg.NETMSG_SERVERCANCEL.equals(action)) {
                mServerIPTV.setText("");
                Toast.makeText(getApplicationContext(), getString(R.string.error_server_cancel), Toast.LENGTH_SHORT).show();
            } else if (NetMsg.NETMSG_PLAYERDATAUPDATE.equals(action)) {
                mViewModel.updatePlayerCount();
                getPlayerDisplayData();
                updateGameModeButtonText();
            }
        }
    };

    private void updateGameModeButtonText() {
        if (mGameModeButton != null) {
            if (Globals.getInstance().mGameMode == Globals.GAME_MODE_2TEAMS) {
                mGameModeButton.setText(R.string.game_mode_2teams);
            } else if (Globals.getInstance().mGameMode == Globals.GAME_MODE_4TEAMS) {
                mGameModeButton.setText(R.string.game_mode_4teams);
            } else {
                mGameModeButton.setText(R.string.game_mode_ffa);
            }
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
        TcpServer.ScoreData[] scores = new TcpServer.ScoreData[Globals.MAX_PLAYER_ID + 1];
        for (byte x = 1; x <= Globals.MAX_PLAYER_ID; x++) {
            scores[x] = mTcpServer.getScore(x);
        }
        Globals.getmTeamPlayerNameSemaphore();
        Globals.getmPlayerSettingsSemaphore();
        try {
            for (byte x = 1; x <= Globals.MAX_PLAYER_ID; x++) {
                TcpServer.ScoreData scoreData = scores[x];
                if (scoreData == null) {
                    mPlayerDisplayData[x] = null;
                } else {
                    mPlayerDisplayData[x] = new PlayerDisplayData();
                    mPlayerDisplayData[x].playerID = x;
                    mPlayerDisplayData[x].playerName = Globals.getInstance().mTeamPlayerNameMap.get(x);
                    mPlayerDisplayData[x].points = scoreData.points;
                    mPlayerDisplayData[x].eliminated = scoreData.eliminated;
                    mPlayerDisplayData[x].isConnected = scoreData.isConnected;
                    if (Globals.getInstance().mPlayerSettings.get(x) == null) {
                        mPlayerDisplayData[x].overrideLives = false;
                        mPlayerDisplayData[x].lives = 0;
                    } else {
                        mPlayerDisplayData[x].overrideLives = Globals.getInstance().mPlayerSettings.get(x).overrideLives;
                        mPlayerDisplayData[x].lives = Globals.getInstance().mPlayerSettings.get(x).lives;
                    }
                }
            }
        } finally {
            Globals.getInstance().mPlayerSettingsSemaphore.release();
            Globals.getInstance().mTeamPlayerNameSemaphore.release();
        }
        if (mPlayerDisplayListAdapter != null) {
            mPlayerDisplayListAdapter.setData(mPlayerDisplayData);
            if (mPlayerDisplayList != null)
                mPlayerDisplayList.setAdapter(mPlayerDisplayListAdapter);
        }
    }

    private void startGameCountdown() {
        if (mGameCountdownTimer != null)
            mGameCountdownTimer.cancel();
        mGameCountdownTimer = new CountDownTimer((Globals.getInstance().mTimeLimit * 60 * 1000) + (Globals.getInstance().mRespawnTime * 1000), 1000) {

            public void onTick(long millisUntilFinished) {
                String display = ""+String.format("%02d:%02d",
                        TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished),
                        TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) - TimeUnit.MINUTES.toSeconds(
                                TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished)));
                mGameCountDownTV.setText(display);
                Globals.getInstance().mServerGameTimeRemaining = millisUntilFinished / 1000;
            }

            public void onFinish() {
                Log.d(TAG, "Game time ended!");
                Toast.makeText(getApplicationContext(), getString(R.string.dialog_game_time_expired), Toast.LENGTH_SHORT).show();
                mTcpServer.endGame();
                Globals.getInstance().mServerGameTimeRemaining = 0;
            }
        }.start();
    }

}
