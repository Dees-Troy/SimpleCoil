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
import android.os.Handler;
import android.os.IBinder;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import javax.microedition.khronos.opengles.GL;

public class DedicatedServerActivity extends AppCompatActivity implements PopupMenu.OnMenuItemClickListener {
    private static final String TAG = "DEDSRV";

    private static final String PREF_GPS_MODE = "GPSMode";

    private TextView mServerIPTV = null;
    private TextView mNetworkPlayerCountTV = null;
    private Button mGameModeButton = null;
    private Button mGameLimitButton = null;
    private Button mGPSModeButton = null;
    private Button mEndGameButton = null;
    private TextView mGameLimitTV = null;
    private TextView mGameStatusTV = null;
    private ListView mPlayerDisplayList = null;

    private SharedPreferences sharedPreferences = null;

    private PlayerDisplayData[] mPlayerDisplayData = new PlayerDisplayData[Globals.MAX_PLAYER_ID + 1];
    PlayerDisplayDataListAdapter mPlayerDisplayListAdapter = null;

    // Code to manage Service lifecycle.
    private ServiceConnection mUDPServiceConnection = null;
    private UDPListenerService mUDPListenerService = null;

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
        startService(udpServiceIntent);
        bindService(udpServiceIntent, mUDPServiceConnection, BIND_AUTO_CREATE);
    }

    private TcpServer mTcpServer = null;
    private ServiceConnection mTcpServerServiceConnection = null;

    private void setupTcpServerServiceConnection() {
        mTcpServerServiceConnection = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                mTcpServer = ((TcpServer.LocalBinder) service).getService();
                mTcpServer.setDedicated();
                mTcpServer.startTcpServer();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                mTcpServer = null;
            }
        };
        Intent serviceIntent = new Intent(getBaseContext(), TcpServer.class);
        startService(serviceIntent);
        bindService(serviceIntent, mTcpServerServiceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dedicated_server);
        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        Globals.getInstance().mPlayerID = 0;
        mServerIPTV = findViewById(R.id.server_ip_tv);
        mNetworkPlayerCountTV = findViewById(R.id.player_count_tv);
        mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, 0));
        mGameModeButton = findViewById(R.id.game_mode_toggle_button);
        if (mGameModeButton != null) {
            mGameModeButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    PopupMenu popup = new PopupMenu(DedicatedServerActivity.this, v);
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
                    PopupMenu popup = new PopupMenu(DedicatedServerActivity.this, v);
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
                    mTcpServer.endGame();
                }
            }));
        }
        sharedPreferences = getSharedPreferences(FullscreenActivity.PREF_NAME, Context.MODE_PRIVATE);
        Globals.getInstance().mGameMode = sharedPreferences.getInt(FullscreenActivity.PREF_GAME_MODE, Globals.GAME_MODE_2TEAMS);
        if (Globals.getInstance().mGameMode == Globals.GAME_MODE_2TEAMS)
            mGameModeButton.setText(R.string.game_mode_2teams);
        else if (Globals.getInstance().mGameMode == Globals.GAME_MODE_4TEAMS)
            mGameModeButton.setText(R.string.game_mode_4teams);
        else
            mGameModeButton.setText(R.string.game_mode_ffa);
        Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_NONE;
        Globals.getInstance().mTimeLimit = sharedPreferences.getInt(FullscreenActivity.PREF_LIMIT_TIME, 0);
        if (Globals.getInstance().mTimeLimit != 0)
            Globals.getInstance().mGameLimit += Globals.GAME_LIMIT_TIME;
        Globals.getInstance().mLivesLimit = sharedPreferences.getInt(FullscreenActivity.PREF_LIMIT_LIVES, 0);
        if (Globals.getInstance().mLivesLimit != 0)
            Globals.getInstance().mGameLimit += Globals.GAME_LIMIT_LIVES;
        Globals.getInstance().mScoreLimit = sharedPreferences.getInt(FullscreenActivity.PREF_LIMIT_SCORE, 0);
        if (Globals.getInstance().mScoreLimit != 0)
            Globals.getInstance().mGameLimit += Globals.GAME_LIMIT_SCORE;
        setGameLimit();
        Globals.getInstance().mGPSMode = sharedPreferences.getInt(PREF_GPS_MODE, Globals.GPS_ALL);
        setGPSMode(Globals.getInstance().mGPSMode);
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
        mPlayerDisplayList.setOnItemClickListener(new AdapterView.OnItemClickListener() {

            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                PlayerSettingsAlertDialog dialog = new PlayerSettingsAlertDialog(DedicatedServerActivity.this);
                dialog.setServer((byte)position, mTcpServer);
                dialog.show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mServerUpdateReceiver, makeServerUpdateIntentFilter());
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
        mTcpServer.sendTCPMessageAll(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_SERVERCANCEL);
        mUDPListenerService.stopListen();
        if (mUDPServiceConnection != null)
            unbindService(mUDPServiceConnection);
        mTcpServer.stopTcpServer();
        if (mTcpServerServiceConnection != null)
            unbindService(mTcpServerServiceConnection);
    }

    private void savePreference(String prefName, int prefValue) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(prefName, prefValue);
        editor.apply();
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.game_mode_2teams_item:
                Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
                mGameModeButton.setText(R.string.game_mode_2teams);
                savePreference(FullscreenActivity.PREF_GAME_MODE, Globals.getInstance().mGameMode);
                setGPSMode(Globals.getInstance().mGPSMode);
                return true;
            case R.id.game_mode_4teams_item:
                Globals.getInstance().mGameMode = Globals.GAME_MODE_4TEAMS;
                mGameModeButton.setText(R.string.game_mode_4teams);
                savePreference(FullscreenActivity.PREF_GAME_MODE, Globals.getInstance().mGameMode);
                setGPSMode(Globals.getInstance().mGPSMode);
                return true;
            case R.id.game_mode_ffa_item:
                Globals.getInstance().mGameMode = Globals.GAME_MODE_FFA;
                mGameModeButton.setText(R.string.game_mode_ffa);
                savePreference(FullscreenActivity.PREF_GAME_MODE, Globals.getInstance().mGameMode);
                setGPSMode(Globals.getInstance().mGPSMode);
                return true;
            case R.id.gps_mode_disabled:
                setGPSMode(Globals.GPS_DISABLED);
                return true;
            case R.id.gps_mode_teammate:
                setGPSMode(Globals.GPS_TEAMMATE);
                return true;
            case R.id.gps_mode_all:
                setGPSMode(Globals.GPS_ALL);
                return true;
            default:
                return false;
        }
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
            mTcpServer.sendAllGameInfo();
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
                                if (gameLimitTime.isChecked()) {
                                    if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) == 0)
                                        Globals.getInstance().mGameLimit += Globals.GAME_LIMIT_TIME;
                                    else if (limit <= 0)
                                        Globals.getInstance().mGameLimit -= Globals.GAME_LIMIT_TIME;
                                    Globals.getInstance().mTimeLimit = limit;
                                } else if (gameLimitLives.isChecked()) {
                                    if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_LIVES) == 0)
                                        Globals.getInstance().mGameLimit += Globals.GAME_LIMIT_LIVES;
                                    else if (limit <= 0)
                                        Globals.getInstance().mGameLimit -= Globals.GAME_LIMIT_LIVES;
                                    Globals.getInstance().mLivesLimit = limit;
                                } else {
                                    if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_SCORE) == 0)
                                        Globals.getInstance().mGameLimit += Globals.GAME_LIMIT_SCORE;
                                    else if (limit <= 0)
                                        Globals.getInstance().mGameLimit -= Globals.GAME_LIMIT_SCORE;
                                    Globals.getInstance().mScoreLimit = limit;
                                }
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
            mTcpServer.sendAllGameInfo();
    }

    private void endGame() {
        mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, Globals.getPlayerCount()));
        mGameModeButton.setEnabled(true);
        mGameLimitButton.setEnabled(true);
        mGPSModeButton.setEnabled(true);
        mGameStatusTV.setText(R.string.dedicated_game_waiting);
        mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, 0));
        mUDPListenerService.allowJoin(true);
        mEndGameButton.setEnabled(false);
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
    }

    private final BroadcastReceiver mServerUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (NetMsg.NETMSG_JOIN.equals(action) || NetMsg.NETMSG_LEAVE.equals(action)) {
                mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, Globals.getPlayerCount() - 1));
                if (Globals.getInstance().mGameMode == Globals.GAME_MODE_FFA && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE && NetMsg.NETMSG_LEAVE.equals(action) && Globals.getPlayerCount() <= 1)
                    endGame(); // Everyone else is out so game is over - this only works in FFA because we don't keep track of who and how many people are on each team
                getPlayerDisplayData();
            } else if (NetMsg.NETMSG_STARTGAME.equals(action)) {
                if (Globals.getInstance().mGameState == Globals.GAME_STATE_NONE)
                    mTcpServer.startGame();
                Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
                mGameModeButton.setEnabled(false);
                mGameLimitButton.setEnabled(false);
                mGPSModeButton.setEnabled(false);
                mGameStatusTV.setText(R.string.dedicated_game_running);
                mUDPListenerService.allowJoin(false);
                mEndGameButton.setEnabled(true);
            } else if (NetMsg.NETMSG_ENDGAME.equals(action)) {
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                    endGame();
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
                getPlayerDisplayData();
            }
        }
    };

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
        return intentFilter;
    }

    private void getPlayerDisplayData() {
        if (mTcpServer == null) return;
        Globals.getmTeamPlayerNameSemaphore();
        Globals.getmPlayerSettingsSemaphore();
        mTcpServer.lockAccess();
        for (byte x = 0; x <= Globals.MAX_PLAYER_ID; x++) {
            TcpServer.ScoreData scoreData = mTcpServer.getScore(x);
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
        Globals.getInstance().mTeamPlayerNameSemaphore.release();
        Globals.getInstance().mPlayerSettingsSemaphore.release();
        mTcpServer.unlockAccess();
        if (mPlayerDisplayListAdapter != null) {
            mPlayerDisplayListAdapter.setData(mPlayerDisplayData);
            if (mPlayerDisplayList != null)
                mPlayerDisplayList.setAdapter(mPlayerDisplayListAdapter);
        }
    }

}
