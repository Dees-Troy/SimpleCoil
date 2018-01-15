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

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.AnimationDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Vibrator;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class FullscreenActivity extends AppCompatActivity {
    private static final String TAG = "scmain";

    private Button mConnectButton = null;
    private Button mTeamMinusButton = null;
    private Button mTeamPlusButton = null;
    private Button mStartGameButton = null;
    private Button mEndGameButton = null;
    private Button mEndNetworkGameButton = null;
    private Button mToggleGameModeButton = null;
    private Button mReadyButton = null;
    private TextView mNetworkPlayerCountTV = null;
    private TextView mScoreTV = null;
    private TextView mScoreLabelTV = null;
    private TextView mGameModeTV = null;
    private TextView mGameModeLabelTV = null;
    private Button mUseNetworkingButton = null;
    private TextView mEliminationCountTV = null;
    private TextView mShotsRemainingTV = null;
    private TextView mRecoilModeTV = null;
    private TextView mHitsTakenTV = null;
    private TextView mTeamTV = null;
    private TextView mShotModeTV = null;
    private TextView mEliminatedTV = null;
    private TextView mSpawnInTV = null;
    private ProgressBar mHealthBar = null;
    private ProgressBar mReloadBar = null;
    private ImageView mHitIV = null;
    private ImageView mBatteryLevelIV = null;
    private AnimationDrawable mHitAnimation = null;
    private ImageView mHitPlayerIV = null;
    private AnimationDrawable mHitPlayerAnimation = null;
    private ImageView mShotsFiredIV = null;
    private ImageView mScoreIncreaseIV = null;

    private CountDownTimer mSpawnTimer = null;
    private CountDownTimer mReloadTimer = null;

    private BluetoothLeScanner mBluetoothLeScanner;
    private BluetoothLeService mBluetoothLeService;

    private Vibrator vibrator = null;

    private static final byte RELOAD_COUNT = (byte) 30; // Number of shots you get after a reload, max 255
    private static final long RESPAWN_TIME_SECONDS = 10; // Time to wait for respawn after elimination and to start the game
    private static final long RELOAD_TIME_MILLISECONDS = 1500; // Reload downtime in ms. 1.5 seconds
    private static final int MAX_HEALTH = 20; // Number of hits you can take before you are eliminated
    private static final int MAX_EMPTY_TRIGGER_PULLS = 3; // automatically reloads if the trigger is pulled this many times while empty (for young players)

    private boolean mScanning = false;
    private volatile boolean mConnected = false;
    private boolean mCommunicating = false; // Used to make sure that we start receiving telemetry data after initial connection
    private String mDeviceAddress = ""; // MAC address of the tagger
    private byte mPlayerID = 0;
    private static byte mLastTeam = 0;
    private int mHitsTaken = 0; // total hits taken regardless of lives
    private static int mHealth = MAX_HEALTH;
    private byte mLastShotCount = 0;
    private byte mLastTriggerCount = 0;
    private byte mLastReloadButtonCount = 0;
    private byte mLastPowerButtonCount = 0;
    private byte mLastThumbButtonCount = 0;
    private static boolean mLastWasHit = false; // Used for filtering multiple reports of the same hit
    private static int mEliminationCount = 0;
    private static int mEmptyTriggerCount = 0;

    /* We calculate an average of the last 100 battery reports. Starting out with a single value
       simplifies some of the code that will have to be run every time the tagger sends telemetry
       data. */
    private long mBatteryCount = 1;
    private Queue<Byte> mBatteryQueue;
    private long mBatteryTotal = 16;
    // Brand new alkalines report 16, recently charged rechargables report 13ish
    private static final long BATTERY_LEVEL_GREEN = 14;
    private static final long BATTERY_LEVEL_BLUE = 12;
    private static final long BATTERY_LEVEL_YELLOW = 10;

    final private int REQUEST_CODE_LOCATION_PERMISSIONS = 1022;

    /* Reloading is done in 2 stages. The first stage effectively tells the tagger that it can't
       shoot anymore. We also use this state when the player is eliminated so they can't shoot while
       out of the game. At the second stage, we tell the tagger how many shots it now has. With a
       normal reload sequence, we wait until the tagger has received the first stage command and
       we automatically start a timer thread to finish the reload after the reload wait timer is up.
       We don't want this timer to run/finish if the player is eliminated, hence the eliminated
       state here. */
    private static final int RELOADING_STATE_NONE = 0;
    private static final int RELOADING_STATE_STARTED = 1;
    private static final int RELOADING_STATE_FINISHING = 2;
    private static final int RELOADING_STATE_ELIMINATED = 3;
    private int mReloading = RELOADING_STATE_ELIMINATED;

    private static final int SHOT_MODE_FULL_AUTO = 0;
    private static final int SHOT_MODE_SINGLE = 1;
    private static final int SHOT_MODE_BURST = 2;
    private int mCurrentMode = SHOT_MODE_SINGLE;

    private boolean mRecoilEnabled = true;

    private static final int GAME_STATE_NONE = 0; // Game not started
    private static final int GAME_STATE_RUNNING = 1; // Game running and player is in the game
    private static final int GAME_STATE_ELIMINATED = 2; // Game running but player is out right now
    private static int mGameState = GAME_STATE_NONE;

    private BluetoothGattCharacteristic mTelemetryCharacteristic = null;
    private BluetoothGattCharacteristic mCommandCharacteristic = null;
    private BluetoothGattCharacteristic mConfigCharacteristic = null;

    public final static int RECOIL_OFFSET_BUTTONS = 2;
    public final static int RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER = 3;
    public final static int RECOIL_OFFSET_THUMB_COUNTER = 4;
    public final static int RECOIL_OFFSET_POWER_COUNTER = 5;
    public final static int RECOIL_OFFSET_BATTERY_LEVEL = 7;
    public final static int RECOIL_OFFSET_HIT_BY1 = 9;
    public final static int RECOIL_OFFSET_HIT_BY2 = 12;
    public final static int RECOIL_OFFSET_TEAM = 1;
    public final static int RECOIL_OFFSET_SHOTS_REMAINING = 14;
    public final static int RECOIL_OFFSET_STATUS = 15;

    public final static int RECOIL_TRIGGER_BIT = 0x01;
    public final static int RECOIL_RELOAD_BIT = 0x02;
    public final static int RECOIL_THUMB_BIT = 0x04;
    public final static int RECOIL_POWER_BIT = 0x10;

    private boolean mUseNetwork = false;
    public static final int GAME_MODE_FFA = 1;
    public static final int GAME_MODE_2TEAMS = 2;
    public static final int GAME_MODE_4TEAMS = 3;
    private int mGameMode = GAME_MODE_2TEAMS;
    private int mScore = 0;
    private int mPlayerCount = 0;
    private boolean mReady = false;
    private int mNetworkTeam = 0;

    /* We run a continuous handler in the background while the tagger is connected to monitor the
       connection status. Simply put, we set connectionTestHandler to false every time the handler
       runs. Every time we receive telemetry data, we set connectionTestHandler back to true. When
       the handler runs again, if connectionTestHandler is still false, then the tagger has
       disconnected without us knowing. */
    private Handler connectionTestHandler;
    private static final int CONNECTION_TEST_INTERVAL_MILLISECONDS = 5000;
    private volatile boolean mConnectionTest = false;

    /* We run a single handler to check and see if we have connected to a weapon within 30 seconds */
    private static final int CONNECTION_FAIL_TEST_INTERVAL_MILLISECONDS = 30000;

    private static final int HIT_VIBRATE_DURATION_MILLISECONDS = 250;
    private static final int HIT_ANIMATION_DURATION_MILLISECONDS = 400;

    // Code to manage Service lifecycle.
    private ServiceConnection mBLEServiceConnection = null;

    private void setupBLEServiceConnection() {
        mBLEServiceConnection = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
                if (!mBluetoothLeService.initialize()) {
                    Log.e(TAG, "Unable to initialize Bluetooth");
                    finish();
                }
                // Automatically connects to the device upon successful start-up initialization.
                mBluetoothLeService.connect(mDeviceAddress);
                mConnected = true;
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                mBluetoothLeService = null;
            }
        };
        Intent gattServiceIntent = new Intent(getBaseContext(), BluetoothLeService.class);
        bindService(gattServiceIntent, mBLEServiceConnection, BIND_AUTO_CREATE);
    }

    // Code to manage Service lifecycle.
    private ServiceConnection mUDPServiceConnection = null;
    private UDPListenerService mUDPListenerService = null;

    private void setupUDPServiceConnection() {
        mUDPServiceConnection = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                mUDPListenerService = ((UDPListenerService.LocalBinder) service).getService();
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_fullscreen);
        mEliminationCountTV = findViewById(R.id.eliminations_count_tv);
        mConnectButton = findViewById(R.id.connect_weapon_button);
        if (mConnectButton != null) {
            mConnectButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    connectWeapon();
                }
            }));
        }
        mTeamMinusButton = findViewById(R.id.team_minus_button);
        if (mTeamMinusButton != null) {
            mTeamMinusButton.setVisibility(View.VISIBLE);
            mTeamMinusButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    if (mGameState != GAME_STATE_NONE)
                        return;
                    if (mPlayerID > (byte)1)
                        mPlayerID--;
                    else
                        mPlayerID = (byte)0x10;
                    setTeam();
                }
            }));
        }
        mTeamPlusButton = findViewById(R.id.team_plus_button);
        if (mTeamPlusButton != null) {
            mTeamPlusButton.setVisibility(View.VISIBLE);
            mTeamPlusButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    if (mGameState != GAME_STATE_NONE)
                        return;
                    if (mPlayerID < (byte)0x10)
                        mPlayerID++;
                    else
                        mPlayerID = (byte)0x01;
                    setTeam();
                }
            }));
        }
        mStartGameButton = findViewById(R.id.start_game_button);
        if (mStartGameButton != null) {
            mStartGameButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    if (mPlayerID == 0) {
                        Toast.makeText(getApplicationContext(), getString(R.string.error_select_team), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (mUseNetwork) {
                        mPlayerCount = mUDPListenerService.getPlayerCount();
                        if (mPlayerCount <= 1) {
                            Toast.makeText(getApplicationContext(), getString(R.string.not_enough_players_toast), Toast.LENGTH_SHORT).show();
                            mNetworkPlayerCountTV.setText(R.string.network_player_1count);
                            return;
                        }
                        mUDPListenerService.sendUDPBroadcast(UDPListenerService.UDPMSG_STARTGAME);
                    }
                    startGame();
                }
            }));
        }
        mEndGameButton = findViewById(R.id.end_game_button);
        if (mEndGameButton != null) {
            mEndGameButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    confirmEndGame();
                }
            }));
        }
        mEndNetworkGameButton = findViewById(R.id.end_network_game_button);
        if (mEndNetworkGameButton != null) {
            mEndNetworkGameButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    confirmEndGame();
                }
            }));
        }
        mShotsRemainingTV = findViewById(R.id.shots_remaining_tv);
        mHitsTakenTV = findViewById(R.id.hits_taken_tv);
        mTeamTV = findViewById(R.id.team_tv);
        mRecoilModeTV = findViewById(R.id.recoil_tv);
        mShotModeTV = findViewById(R.id.shot_mode_tv);
        mHealthBar = findViewById(R.id.health_pb);
        mHealthBar.setMax(MAX_HEALTH);
        mHealthBar.setProgress(MAX_HEALTH);
        mReloadBar = findViewById(R.id.reload_pb);
        mEliminatedTV = findViewById(R.id.eliminated_tv);
        mSpawnInTV = findViewById(R.id.spawn_countdown_tv);
        mHitIV = findViewById(R.id.hit_animation_iv);
        mBatteryLevelIV = findViewById(R.id.battery_iv);
        WebView wv = findViewById(R.id.info_wv);
        wv.loadUrl("file:///android_asset/simplecoil.html");
        wv.setVisibility(View.VISIBLE);

        showConnectLayout();
        initBatteryQueue();
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        try {
            // Display app version
            PackageInfo pInfo = this.getPackageManager().getPackageInfo(getPackageName(), 0);
            TextView versionTextView = findViewById(R.id.version_tv);
            versionTextView.setText(pInfo.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        mNetworkPlayerCountTV = findViewById(R.id.player_count_tv);
        mUseNetworkingButton = findViewById(R.id.use_network_button);
        if (mUseNetworkingButton != null) {
            mUseNetworkingButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    mUseNetwork = !mUseNetwork;
                    if (mUseNetwork) {
                        displayAllNetworkingOptions(true);
                        mUseNetworkingButton.setText(R.string.no_network_button);
                    } else {
                        displayAllNetworkingOptions(false);
                        mUseNetworkingButton.setVisibility(View.VISIBLE);
                        mUseNetworkingButton.setText(R.string.use_network_button);
                    }
                    setTeam();
                }
            }));
        }
        mReadyButton = findViewById(R.id.ready_button);
        if (mReadyButton != null) {
            mReadyButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    if (mPlayerID == 0) {
                        Toast.makeText(getApplicationContext(), getString(R.string.error_select_team), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    mReady = !mReady;
                    setReady();
                }
            }));
        }
        mToggleGameModeButton = findViewById(R.id.game_mode_toggle_button);
        if (mToggleGameModeButton != null) {
            mToggleGameModeButton.setOnClickListener((new View.OnClickListener() {
                public void onClick(View v) {
                    if (mGameMode == GAME_MODE_FFA) {
                        mGameMode = GAME_MODE_2TEAMS;
                        mGameModeTV.setText(R.string.game_mode_2teams);
                    } else if (mGameMode == GAME_MODE_2TEAMS) {
                        mGameMode = GAME_MODE_4TEAMS;
                        mGameModeTV.setText(R.string.game_mode_4teams);
                    } else {
                        mGameMode = GAME_MODE_FFA;
                        mGameModeTV.setText(R.string.game_mode_ffa);
                    }
                    mUDPListenerService.setGameMode(mGameMode);
                    setTeam();
                }
            }));
        }
        mGameModeLabelTV = findViewById(R.id.game_mode_label_tv);
        mGameModeTV = findViewById(R.id.game_mode_tv);
        mScoreLabelTV = findViewById(R.id.score_label_tv);
        mScoreTV = findViewById(R.id.score_tv);
        mHitPlayerIV = findViewById(R.id.hit_player_iv);
        mShotsFiredIV = findViewById(R.id.shots_fired_iv);
        mScoreIncreaseIV = findViewById(R.id.score_increase_iv);
        displayAllNetworkingOptions(false);
        mUseNetworkingButton.setVisibility(View.VISIBLE);
    }

    private void setReady() { setReady(true); }

    private void setReady(boolean sendPlayerLeft) {
        if (mReady) {
            // Start listening for broadcasts
            mNetworkPlayerCountTV.setText(R.string.network_player_1count);
            mUDPListenerService.setPlayerID(mPlayerID);
            mUDPListenerService.startPlayerScan();
            mReadyButton.setText(R.string.not_ready_button);
            mNetworkPlayerCountTV.setVisibility(View.VISIBLE);
            mTeamMinusButton.setVisibility(View.INVISIBLE);
            mTeamPlusButton.setVisibility(View.INVISIBLE);
            mToggleGameModeButton.setVisibility(View.GONE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            if (sendPlayerLeft)
                mUDPListenerService.sendUDPMessageAll(UDPListenerService.UDPMSG_NOPLAYER);
            mUDPListenerService.stopListen();
            mReadyButton.setText(R.string.ready_button);
            mNetworkPlayerCountTV.setVisibility(View.GONE);
            mTeamMinusButton.setVisibility(View.VISIBLE);
            mTeamPlusButton.setVisibility(View.VISIBLE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mNetworkPlayerCountTV.setVisibility(View.GONE);
            mToggleGameModeButton.setVisibility(View.VISIBLE);
        }
    }

    private void showConnectLayout() {
        RelativeLayout playLayout = findViewById(R.id.play_layout);
        if (playLayout != null) playLayout.setVisibility(View.GONE);
        RelativeLayout connectLayout = findViewById(R.id.connect_layout);
        if (connectLayout != null) connectLayout.setVisibility(View.VISIBLE);
    }

    private void initBatteryQueue() {
        if (mBatteryQueue == null)
            mBatteryQueue = new LinkedList<>();
        else
            mBatteryQueue.clear();
        mBatteryQueue.add((byte)16);
        mBatteryCount = 1;
        mBatteryTotal = 16;
    }

    private void startGame() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // hide nav bar
                        //| View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                        | View.SYSTEM_UI_FLAG_IMMERSIVE);
        mScore = 0;
        mScoreTV.setText("0");
        mEliminationCount = 0;
        mEliminationCountTV.setText("0");
        mHealth = MAX_HEALTH;
        mHealthBar.setProgress(mHealth);
        mEliminatedTV.setText(R.string.starting_game_label);
        mStartGameButton.setVisibility(View.GONE);
        mTeamMinusButton.setVisibility(View.INVISIBLE);
        mTeamPlusButton.setVisibility(View.INVISIBLE);
        if (mUseNetwork) {
            displayInGameNetworkingOptions();
            mEndNetworkGameButton.setVisibility(View.VISIBLE);
        } else {
            displayAllNetworkingOptions(false);
            mEndGameButton.setVisibility(View.VISIBLE);
        }
        startSpawn();
    }

    private void confirmEndGame() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setTitle(R.string.end_game_dialog_title);
        builder.setMessage(R.string.end_game_dialog_message);

        builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (mUseNetwork) {
                    mUDPListenerService.sendUDPBroadcast(UDPListenerService.UDPMSG_ENDGAME);
                }
                endGame();
            }
        });

        builder.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });

        AlertDialog alert = builder.create();
        alert.show();
    }

    private void endGame() {
        mGameState = GAME_STATE_NONE;
        if (mUseNetwork) {
            mUDPListenerService.stopListen();
            mReady = false;
            setReady(false);
            mPlayerCount = 0;
            mNetworkPlayerCountTV.setText(R.string.network_player_1count);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (mSpawnTimer != null)
            mSpawnTimer.cancel();
        if (mReloadTimer != null)
            mReloadTimer.cancel();
        mStartGameButton.setVisibility(View.VISIBLE);
        mTeamMinusButton.setVisibility(View.VISIBLE);
        mTeamPlusButton.setVisibility(View.VISIBLE);
        mEndGameButton.setVisibility(View.GONE);
        mEndNetworkGameButton.setVisibility(View.GONE);
        mHitIV.setVisibility(View.GONE);
        mEliminatedTV.setVisibility(View.GONE);
        mSpawnInTV.setVisibility(View.GONE);
        mGameState = GAME_STATE_NONE;
        displayAllNetworkingOptions(mUseNetwork);
        mUseNetworkingButton.setVisibility(View.VISIBLE);
        mHitPlayerIV.setVisibility(View.GONE);
        mShotsFiredIV.setVisibility(View.GONE);
        startReload(RELOADING_STATE_ELIMINATED);
    }

    /* Sending to Command a packet with 10 00 80 00 PLAYER# then 15 more 00's sets the player ID.
       This works for initially setting the ID, but does not work consistently afterwards. You can
       consistently change the player ID by doing a reload procedure, but doing a reload without
       having previously set a team does not seem to work. We do a reload cycle before starting the
       game each time to ensure that the player starts on the right team and with full ammo. */
    private void setTeam() {
        if (mBluetoothLeService == null || !mBluetoothLeService.isWriteAvailable())
            return;
        Log.d(TAG, "setting player ID to " + mPlayerID);
        if (mPlayerID < 1 || mPlayerID > 16) {
            Log.e(TAG, "Invalid Team!");
            return;
        }
        if (mCommandCharacteristic == null) {
            Log.e(TAG, "No command characteristic available");
            return;
        }
        byte[] command = new byte[20];
        command[0] = (byte)0x10;
        command[2] = (byte)0x80;
        command[4] = mPlayerID;
        mCommandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        mCommandCharacteristic.setValue(command);
        mBluetoothLeService.writeCharacteristic(mCommandCharacteristic);
        displayCurrentTeam();
    }

    private void displayCurrentTeam() {
        if (mUseNetwork && mGameMode != GAME_MODE_FFA) {
            mNetworkTeam = 1;
            int x = 8;
            if (mGameMode == GAME_MODE_2TEAMS) {
                if (mPlayerID > 8)
                    mNetworkTeam = 2;
            } else {
                x = 4;
                if (mPlayerID > 12)
                    mNetworkTeam = 4;
                else if (mPlayerID > 8)
                    mNetworkTeam = 3;
                else if (mPlayerID > 4)
                    mNetworkTeam = 2;
            }
            int player = (int) (mPlayerID - (byte)(x * (mNetworkTeam - 1)));
            mTeamTV.setText(getString(R.string.network_team, mNetworkTeam, player));
        } else {
            String teamStr = "" + mPlayerID;
            mTeamTV.setText(teamStr);
        }
        mUDPListenerService.setGameMode(mGameMode);
    }

    private void startReload() { startReload(RELOADING_STATE_STARTED); }

    /* Initial reload command that tells the tagger not to shoot anymore (or maybe it just sets the
       remaining shot counter to 0). It also sets the tagger in what I guess is status 0x03 instead
       of the usual 0x02. The command format is F0 00 02 00 PLAYER_ID and then 0 filled to the end. */
    private void startReload(int reloadStatus) {
        if (mReloading != RELOADING_STATE_NONE || mCommandCharacteristic == null || mBluetoothLeService == null || !mBluetoothLeService.isWriteAvailable())
            return;
        mReloading = reloadStatus;
        mShotsRemainingTV.setVisibility(View.INVISIBLE);
        mReloadBar.setVisibility(View.VISIBLE);
        byte[] command = new byte[20];
        command[0] = (byte)0xF0;
        command[2] = (byte)0x02;
        command[4] = mPlayerID;
        mCommandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        mCommandCharacteristic.setValue(command);
        mBluetoothLeService.writeCharacteristic(mCommandCharacteristic);
    }

    /* Second stage of the reload commands which tells the tagger how many shots to load and allows
       it to shoot again. Command format is 00 00 04 00 PLAYER_ID 00 SHOT_COUNT and then 0 filled. */
    private void finishReload() {
        if ((mReloading != RELOADING_STATE_STARTED && mReloading != RELOADING_STATE_ELIMINATED) || mCommandCharacteristic == null || mBluetoothLeService == null || !mBluetoothLeService.isWriteAvailable() || mGameState != GAME_STATE_RUNNING)
            return;
        mReloading = RELOADING_STATE_FINISHING;
        mEmptyTriggerCount = 0;
        Log.d(TAG, "Finishing reload");
        byte[] command = new byte[20];
        command[2] = (byte)0x04;
        command[4] = mPlayerID;
        command[6] = RELOAD_COUNT;
        setShotsRemaining(RELOAD_COUNT);
        mCommandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        mCommandCharacteristic.setValue(command);
        mBluetoothLeService.writeCharacteristic(mCommandCharacteristic);
    }

    private void setShotsRemaining(byte shotsRemaining) {
        String shotsRemainingStr = "" + shotsRemaining;
        if (shotsRemaining == 0)
            shotsRemainingStr = "0";
        mShotsRemainingTV.setText(shotsRemainingStr);
        mLastShotCount = shotsRemaining;
    }

    private void startSpawn() {
        if (mReloadTimer != null)
            mReloadTimer.cancel();
        mGameState = GAME_STATE_ELIMINATED;
        startReload(RELOADING_STATE_ELIMINATED);
        mHitIV.setVisibility(View.GONE);
        mEliminatedTV.setVisibility(View.VISIBLE);
        mSpawnInTV.setText(getResources().getString(R.string.spawn_in_label, RESPAWN_TIME_SECONDS));
        mSpawnInTV.setVisibility(View.VISIBLE);
        mSpawnTimer = new CountDownTimer(RESPAWN_TIME_SECONDS * 1000, 999) {

            public void onTick(long millisUntilFinished) {
                mSpawnInTV.setText(getResources().getString(R.string.spawn_in_label, (millisUntilFinished / 1000)));
                playSound(R.raw.beep, getApplicationContext());
            }

            public void onFinish() {
                Log.d(TAG, "spawned!");
                mEliminatedTV.setVisibility(View.GONE);
                mEliminatedTV.setText(R.string.eliminated_label);
                mSpawnInTV.setVisibility(View.GONE);
                mHealth = MAX_HEALTH;
                mHealthBar.setProgress(mHealth);
                mGameState = GAME_STATE_RUNNING;
                playSound(R.raw.spawn, getApplicationContext());
                finishReload();
            }
        }.start();
    }

    // Config 00 00 09 xx yy ff c8 ff ff 80 01 43 - xx is the number of shots and if you set yy to 01 for full auto for xx shots or 00 for single shot mode, increasing yy decreases RoF
    // setting 03 03 for shots and RoF gives a good 3 shot burst, 03 01 is so fast that you feel 1 recoil for 3 shots
    private void setShotMode(int mode) {
        if (mConfigCharacteristic == null || mBluetoothLeService == null || !mBluetoothLeService.isWriteAvailable())
            return;
        byte[] config = new byte[20];
        config[2]  = (byte)0x09;
        config[5]  = (byte)0xFF;
        config[6]  = (byte)0xC8;
        config[7]  = (byte)0xFF;
        config[8]  = (byte)0xFF;
        config[9]  = (byte)0x80;
        config[10] = (byte)0x01;
        config[11] = (byte)0x43;
        if (mode == SHOT_MODE_SINGLE) {
            config[3] = (byte)0xFE;
            config[4] = (byte)0x00;
            mCurrentMode = SHOT_MODE_SINGLE;
            mShotModeTV.setText(R.string.shot_mode_single);
        } else if (mode == SHOT_MODE_BURST) {
            config[3] = (byte)0x03;
            config[4] = (byte)0x03;
            mCurrentMode = SHOT_MODE_BURST;
            mShotModeTV.setText(R.string.shot_mode_burst3);
        } else if (mode == SHOT_MODE_FULL_AUTO) {
            config[3] = (byte)0xFE;
            config[4] = (byte)0x01;
            mCurrentMode = SHOT_MODE_FULL_AUTO;
            mShotModeTV.setText(R.string.shot_mode_auto);
        }
        mConfigCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        mConfigCharacteristic.setValue(config);
        mBluetoothLeService.writeCharacteristic(mConfigCharacteristic);
    }

    // Config 10 00 02 02 ff and 15 sets of 00 disables recoil
    // Config 10 00 02 03 ff and 15 sets of 00 enables recoil
    private void setRecoil(boolean enabled) {
        if (mConfigCharacteristic == null || mBluetoothLeService == null || !mBluetoothLeService.isWriteAvailable())
            return;
        byte[] config = new byte[20];
        config[0]  = (byte)0x10;
        config[2]  = (byte)0x02;
        config[4]  = (byte)0xFF;
        if (enabled) {
            config[3] = (byte)0x03;
            mRecoilModeTV.setText(R.string.recoil_enabled);
        } else {
            config[3] = (byte)0x02;
            mRecoilModeTV.setText(R.string.recoil_disabled);
        }
        mRecoilEnabled = enabled;
        mConfigCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        mConfigCharacteristic.setValue(config);
        mBluetoothLeService.writeCharacteristic(mConfigCharacteristic);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null && mDeviceAddress != null && !mDeviceAddress.isEmpty()) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mBluetoothReceiver, filter);
        registerReceiver(mUDPUpdateReceiver, makeUDPUpdateIntentFilter());
        setupUDPServiceConnection();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
        unregisterReceiver(mBluetoothReceiver);
        unregisterReceiver(mUDPUpdateReceiver);

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        resetBluetoothServices();
        if (mUDPServiceConnection != null)
            unbindService(mUDPServiceConnection);
    }

    private ScanCallback mLeScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            checkDeviceName(result.getDevice());
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            for (ScanResult result : results) {
                if (checkDeviceName(result.getDevice()))
                    return;
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
        }

        private boolean checkDeviceName(BluetoothDevice device) {
            if (device.getName() != null && !device.getName().isEmpty()) {
                if (device.getName().startsWith("SRG1")) {
                    Log.d(TAG, "Connecting to " + device.getName() + " '" + device.getAddress() + "'");
                    TextView connectStatusTV = findViewById(R.id.connect_status_tv);
                    if (connectStatusTV != null) {
                        connectStatusTV.setText(R.string.connect_status_connecting);
                    }
                    mDeviceAddress = device.getAddress();
                    mBluetoothLeScanner.stopScan(mLeScanCallback);
                    mScanning = false;
                    setupBLEServiceConnection();
                    return true;
                }
            }
            return false;
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE_LOCATION_PERMISSIONS:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission Granted
                    connectWeapon();
                } else {
                    // Permission Denied
                    Toast.makeText(this, getString(R.string.error_location_permission_required), Toast.LENGTH_SHORT)
                            .show();
                }
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private final BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null)
                return;

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON)
                    connectWeapon();
            }
        }
    };

    private void connectWeapon() {
        if (mScanning)
            return;

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
        }

        // Coarse location permissions are required to use Bluetooth on 6.0+ devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int hasLocationPermission = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (hasLocationPermission != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                        REQUEST_CODE_LOCATION_PERMISSIONS);
                return;
            }
        }

        // Request to turn on Bluetooth if it's not turned on
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        // Checks if Bluetooth is supported on the device.
        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Bluetooth is off");
            Intent intentBtEnabled = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            // The REQUEST_ENABLE_BT constant passed to startActivityForResult() is a locally defined integer (which must be greater than 0), that the system passes back to you in your onActivityResult()
            // implementation as the requestCode parameter.
            int REQUEST_ENABLE_BT = 1;
            startActivityForResult(intentBtEnabled, REQUEST_ENABLE_BT);
            return;
        }

        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
        // BluetoothAdapter through BluetoothManager.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            Log.e(TAG, "Failed to get Bluetooth service");
            return;
        }

        mBluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        Log.d(TAG, "starting to scan");
        mBluetoothLeScanner.startScan(mLeScanCallback);
        mConnectButton.setEnabled(false);
        mScanning = true;
        TextView connectStatusTV = findViewById(R.id.connect_status_tv);
        if (connectStatusTV != null) {
            connectStatusTV.setText(R.string.connect_status_scanning);
        }
        startConnectFailTest();
    }

    private void startConnectFailTest() {
        // Handler to see if we failed to find a weapon
        mConnected = false;
        Handler connectionFailTestHandler = new Handler();
        connectionFailTestHandler.postDelayed(new Runnable(){
            public void run(){
                if (!mConnected) {
                    Log.d(TAG, "Failed to find a weapon before timeout");
                    mScanning = false;
                    if (mBluetoothLeScanner != null)
                        mBluetoothLeScanner.stopScan(mLeScanCallback);
                    handleDisconnect();
                }
            }
        }, CONNECTION_FAIL_TEST_INTERVAL_MILLISECONDS);
    }

    private void resetBluetoothServices() {
        mConnected = false;
        if (mBluetoothLeService != null)
            mBluetoothLeService.close();
        if (mBLEServiceConnection != null)
            unbindService(mBLEServiceConnection);
        mBLEServiceConnection = null;
        mBluetoothLeService = null;
    }

    private void handleDisconnect() {
        resetBluetoothServices();
        showConnectLayout();
        mConnectButton.setEnabled(true);
        TextView connectStatusTV = findViewById(R.id.connect_status_tv);
        if (connectStatusTV != null) connectStatusTV.setText(R.string.connect_status_not_connected);
        mCurrentMode = SHOT_MODE_SINGLE;
        mShotModeTV.setText(R.string.shot_mode_single);
        mRecoilEnabled = true;
        mRecoilModeTV.setText(R.string.recoil_enabled);
        mPlayerID = 0;
        mTeamTV.setText(R.string.no_team);
        mLastShotCount = 0;
        endGame();
        initBatteryQueue();
        playSound(R.raw.eliminated, getApplicationContext());
    }

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                mCommunicating = false; // When we receive the first packet, we'll switch layouts
                startConnectionTest();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                handleDisconnect();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                //displayGattServices(mBluetoothLeService.getSupportedGattServices());
                Log.d(TAG, "services discovered!");
                TextView connectStatusTV = findViewById(R.id.connect_status_tv);
                if (connectStatusTV != null) connectStatusTV.setText(R.string.connect_status_communicating);
                for (BluetoothGattService gattService : mBluetoothLeService.getSupportedGattServices()) {
                    Log.d(TAG, "service: " + gattService.getUuid().toString());
                    if (gattService.getUuid().toString().equals(GattAttributes.RECOIL_MAIN_SERVICE)) {
                        Log.d(TAG, "Found Recoil Main Service");
                        mTelemetryCharacteristic = gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID));
                        if (mTelemetryCharacteristic != null) {
                            mBluetoothLeService.setCharacteristicNotification(mTelemetryCharacteristic, true);
                        } else {
                            Log.e(TAG, "Failed to find Telemetry characteristic");
                        }
                        mCommandCharacteristic = gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_COMMAND_UUID));
                        mConfigCharacteristic = gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_CONFIG_UUID));
                        playSound(R.raw.spawn, getApplicationContext());
                    }
                }
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                processTelemetryData();
            } else if (BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED.equals(action)) {
                if (mReloading == RELOADING_STATE_STARTED) {
                    // Using a timer here so we can cancel it if the player is eliminated while waiting to reload
                    mReloadTimer = new CountDownTimer(RELOAD_TIME_MILLISECONDS, RELOAD_TIME_MILLISECONDS) {
                        public void onTick(long millisUntilFinished) { /* do nothing */ }
                        public void onFinish() {
                            finishReload();
                        }
                    }.start();
                } else if (mReloading == RELOADING_STATE_FINISHING) {
                    mReloading = RELOADING_STATE_NONE;
                    mReloadBar.setVisibility(View.INVISIBLE);
                    mShotsRemainingTV.setVisibility(View.VISIBLE);
                    playSound(R.raw.reload, getApplicationContext());
                    Log.d(TAG, "Reload finished");
                }
            }
        }
    };

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED);
        return intentFilter;
    }

    private void startConnectionTest() {
        // This is a looping handler that checks to see if the tagger has disconnected
        mCommunicating = false;
        connectionTestHandler = new Handler();
        connectionTestHandler.postDelayed(new Runnable(){
            public void run(){
                if (!mConnected)
                    return;
                if (!mConnectionTest) {
                    Log.d(TAG, "Connection test reports disconnect!");
                    handleDisconnect();
                    return;
                }
                mConnectionTest = false;
                connectionTestHandler.postDelayed(this, CONNECTION_TEST_INTERVAL_MILLISECONDS);
            }
        }, CONNECTION_TEST_INTERVAL_MILLISECONDS);
    }

    public void playSound(int resId, Context ctx) {
        MediaPlayer mp = new MediaPlayer().create(ctx, resId);
        mp.start();
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                mp.release();
            }

        });
        mp.setLooping(false);
    }

    /* Telemetry data is 20 bytes of raw data in the following format:
       00 seems to be part of a continuous counter, first byte always 0 and second byte counts 0 to F, increments with each packet sent
       01 player ID, 01, 02, 03, etc. 00 when not set
       02 first byte is the power button 0 unpressed and 1 for pressed, second is button presses, 01 for trigger, 02 for reload, 04 for back button, add them together for pressing multiple buttons, so 07 means all three buttons are being pressed
       03 counters for the number of times a button has been pressed, the first byte is reload presses, second byte is trigger presses
       04 again a button counter, first byte is unused, second byte is for back/thumb/voice button presses
       05 first byte unused, second byte is a count of power button presses
       06 seem to be part of a continuous counter or otherwise just random???
       07 battery level - 10 is brand new alkalines, 0E for fully charged rechargeables with 0D showing up pretty quick, drops significantly when shooting
       08 00 related to being hit by ID 1, usually around 3* so 3D or 3E but somewhat random
       09 hit by player ID 1, player 1 seems to be 0x04 and player 2 is 0x08, 0x0C, 0x10, 0x14, 0x18
       10 related to being hit by ID 1 but fairly random in the 6* and 7* range
       11 related to being hit by ID 2, usually around 3* so 3D or 3E but somewhat random
       12 hit by player ID 2, player 1 seems to be 0x04 and player 2 is 0x08, sometimes this is the same as ID 1 but sometimes different if being shot by 2 people at once
       13 related to being hit by ID 2 but fairly random in the 6* and 7* range
       14 how many shots you have left, starts out at 0x1e which would be 30 in decimal and decreases when you pull the trigger
       15 usually 02 but I have seen 03 during reload wait... some kind of status?
       16 starts as 02 but changes to 00 after player ID is set
       17 00 Unused?
       18 00 Unused?
       19 00 Unused?
     */
    private void processTelemetryData() {
        final byte[] data = mTelemetryCharacteristic.getValue();
        if (data != null && data.length > 0) {
            byte player_id = data[RECOIL_OFFSET_TEAM];
            byte shotsRemaining = data[RECOIL_OFFSET_SHOTS_REMAINING];
            //byte status = data[RECOIL_OFFSET_STATUS];
            //int buttons = data[RECOIL_OFFSET_BUTTONS];
            int hit_by_player1 = data[RECOIL_OFFSET_HIT_BY1];
            byte trigger_counter = (byte)(data[RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] & (byte)0x0F);
            byte reload_counter = (byte)(data[RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] & (byte)0xF0);
            byte thumb_counter = data[RECOIL_OFFSET_THUMB_COUNTER];
            byte power_counter = data[RECOIL_OFFSET_POWER_COUNTER];
            mConnectionTest = true;
            if (!mCommunicating) {
                mCommunicating = true;
                RelativeLayout connectLayout = findViewById(R.id.connect_layout);
                if (connectLayout != null) connectLayout.setVisibility(View.GONE);
                RelativeLayout playLayout = findViewById(R.id.play_layout);
                if (playLayout != null) playLayout.setVisibility(View.VISIBLE);
                // Reset last counters when we start a new connection
                mLastTriggerCount = trigger_counter;
                mLastReloadButtonCount = reload_counter;
                mLastThumbButtonCount = thumb_counter;
                mLastPowerButtonCount = power_counter;
            }
            if (trigger_counter != mLastTriggerCount) {
                mLastTriggerCount = trigger_counter;
                if (shotsRemaining == 0 || mGameState != GAME_STATE_RUNNING) {
                    playSound(R.raw.empty, getApplicationContext());
                    mEmptyTriggerCount++;
                    if (mEmptyTriggerCount >= MAX_EMPTY_TRIGGER_PULLS && mGameState == GAME_STATE_RUNNING)
                        startReload(); // Auto reload after this many empty trigger pulls (for young players)
                }
            }
            /* Rather than monitor if a button is currently pressed, we monitor the counter. Usually
               when the player presses a button, we see many packets showing that the button is
               pressed. We don't want to toggle recoil or modes with every packet we receive so it
               makes more sense to just monitor when the counter changes so we can toggle eactly
               once each time that button is pressed. */
            if (reload_counter != mLastReloadButtonCount) {
                mLastReloadButtonCount = reload_counter;
                if (shotsRemaining != RELOAD_COUNT)
                    startReload();
            }
            if (thumb_counter != mLastThumbButtonCount) {
                mLastThumbButtonCount = thumb_counter;
                if (mCurrentMode == SHOT_MODE_SINGLE) {
                    setShotMode(SHOT_MODE_BURST);
                } else if (mCurrentMode == SHOT_MODE_BURST) {
                    setShotMode(SHOT_MODE_FULL_AUTO);
                } else if (mCurrentMode == SHOT_MODE_FULL_AUTO) {
                    setShotMode(SHOT_MODE_SINGLE);
                }
            }
            if (power_counter != mLastPowerButtonCount) {
                mLastPowerButtonCount = power_counter;
                mRecoilEnabled = !mRecoilEnabled;
                setRecoil(mRecoilEnabled);
            }
            byte battery_level = data[RECOIL_OFFSET_BATTERY_LEVEL];
            mBatteryQueue.add(battery_level);
            mBatteryTotal += (long)battery_level;
            if (mBatteryCount >= 100) {
                mBatteryTotal -= (long)mBatteryQueue.peek();
                mBatteryQueue.remove();
            } else {
                mBatteryCount++;
            }
            long averageBattery = mBatteryTotal / mBatteryCount;
            if (mBatteryLevelIV != null) {
                if (averageBattery >= BATTERY_LEVEL_GREEN)
                    mBatteryLevelIV.setImageResource(R.drawable.ic_battery_full_green_24dp);
                else if (averageBattery >= BATTERY_LEVEL_BLUE)
                    mBatteryLevelIV.setImageResource(R.drawable.ic_battery_80_blue_24dp);
                else if (averageBattery >= BATTERY_LEVEL_YELLOW)
                    mBatteryLevelIV.setImageResource(R.drawable.ic_battery_50_yellow_24dp);
                else
                    mBatteryLevelIV.setImageResource(R.drawable.ic_battery_alert_red_24dp);
            }
            /*if (buttons != 0) {
                boolean trigger = false;
                boolean reload = false;
                boolean thumb = false;
                boolean power = false;
                if ((buttons & RECOIL_TRIGGER_BIT) != 0) trigger = true;
                if ((buttons & RECOIL_RELOAD_BIT) != 0) reload = true;
                if ((buttons & RECOIL_THUMB_BIT) != 0) thumb = true;
                if ((buttons & RECOIL_POWER_BIT) != 0) power = true;
                //Log.d(TAG, "trigger: " + (trigger ? "X" : " ") + " reload: " + (reload ? "X" : " ") + " thumb: " + (thumb ? "X" : " ") + " power: " + (power ? "X" : " "));
                if (power) {
                    mHitsTaken = 0;
                    mHitsTakenTV.setText("0");
                }
            }*/
            if (hit_by_player1 != 0 && mGameState != GAME_STATE_NONE) {
                /* The tagger usually reports 2 hits per actual shot based on single shot mode
                   although there are a few times where it reports 3 hits per shot. This is
                   an attempt to filter out double reports without excluding multiple hits in
                   a row from full auto. */
                if (mLastWasHit) {
                    mLastWasHit = false;
                    Log.d(TAG, "hit filtered");
                } else {
                    mLastWasHit = true;
                    mHitsTaken++;
                    String hitsTaken = "" + mHitsTaken;
                    mHitsTakenTV.setText(hitsTaken);
                    int healthRemoved = -1;
                    byte hit_by_id = 0;
                    if (mUseNetwork) {
                        hit_by_id = (byte)(hit_by_player1 >> 2);
                        Log.d(TAG, "hit by 1 ID is " + hit_by_id);
                        if (mGameMode != GAME_MODE_FFA && calcNetworkTeam(hit_by_id) == mNetworkTeam) {
                            Log.d(TAG, "friendly fire ignored");
                            healthRemoved++;
                        } else {
                            if (mHealth + healthRemoved > 0)
                                mUDPListenerService.sendUDPMessage(UDPListenerService.UDPMSG_HIT, hit_by_id);
                            else
                                mUDPListenerService.sendUDPMessage(UDPListenerService.UDPMSG_OUT, hit_by_id);
                        }
                    }
                    // Often, hit_by_player2 is the same player ID as player1
                    int hit_by_player2 = data[RECOIL_OFFSET_HIT_BY2];
                    if (hit_by_player2 != 0 && hit_by_player2 != hit_by_player1 && mHealth + healthRemoved > 0) {
                        healthRemoved--;
                        if (mUseNetwork) {
                            hit_by_id = (byte)(hit_by_player1 >> 2);
                            Log.d(TAG, "hit by 2 ID is " + hit_by_id);
                            if (mGameMode != GAME_MODE_FFA && calcNetworkTeam(hit_by_id) == mNetworkTeam) {
                                Log.d(TAG, "friendly fire ignored");
                                healthRemoved++;
                            } else {
                                if (mHealth + healthRemoved > 0)
                                    mUDPListenerService.sendUDPMessage(UDPListenerService.UDPMSG_HIT, hit_by_id);
                                else
                                    mUDPListenerService.sendUDPMessage(UDPListenerService.UDPMSG_OUT, hit_by_id);
                            }
                        }
                    }
                    if (healthRemoved != 0) {
                        if (mGameState == GAME_STATE_RUNNING) {
                            mHealth += healthRemoved;
                            mHealthBar.setProgress(mHealth);
                            if (mHealth > 0) {
                                if (mHitIV != null && mHitAnimation == null) {
                                    // Show the "you're being hit" animation
                                    mHitIV.setVisibility(View.VISIBLE);
                                    mHitIV.setBackgroundResource(R.drawable.hit_animation);
                                    vibrator.vibrate(HIT_VIBRATE_DURATION_MILLISECONDS);
                                    playSound(R.raw.hit, getApplicationContext());
                                    Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
                                    mHitIV.startAnimation(animationFadeOut);
                                    mHitAnimation = (AnimationDrawable) mHitIV.getBackground();
                                    mHitAnimation.start();
                                    new Handler().postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            mHitIV.setVisibility(View.GONE);
                                            mHitAnimation.stop();
                                            mHitAnimation = null;
                                        }
                                    }, HIT_ANIMATION_DURATION_MILLISECONDS);
                                }
                            } else {
                                vibrator.vibrate(HIT_VIBRATE_DURATION_MILLISECONDS);
                                playSound(R.raw.eliminated, getApplicationContext());
                                mEliminationCount++;
                                String elimStr = "" + mEliminationCount;
                                mEliminationCountTV.setText(elimStr);
                                startSpawn();
                                if (mUseNetwork) {
                                    mUDPListenerService.sendUDPMessage(UDPListenerService.UDPMSG_ELIMINATED, hit_by_id);
                                }
                            }
                        }
                    }
                }
            } else {
                mLastWasHit = false;
            }
            /* Since setting player ID is somewhat unreliable, we use this to make sure that we are
               displaying the actual ID that the tagger is currently using. */
            if (mLastTeam != player_id) {
                Log.d(TAG, "Player ID changed to " + player_id);
                mLastTeam = player_id;
                mPlayerID = player_id;
                displayCurrentTeam();
            }
            /* We monitor the last shot count rather than trigger pulls here so we know when to
               update the displayed shots remaining and play shooting sounds. pew! pew! */
            if (mLastShotCount != shotsRemaining) {
                setShotsRemaining(shotsRemaining);
                if (shotsRemaining != RELOAD_COUNT && mReloading == RELOADING_STATE_NONE) {
                    playSound(R.raw.shootingshort, getApplicationContext());
                    if (mUseNetwork) {
                        mUDPListenerService.sendUDPMessageAll(UDPListenerService.UDPMSG_SHOTFIRED);
                    }
                }
            }
            // Handy utility code if you want to see the raw data
            /*if (data != null && data.length > 0) {
                final StringBuilder stringBuilder = new StringBuilder(data.length);
                for(byte byteChar : data)
                    stringBuilder.append(String.format("%02X ", byteChar));
                Log.d(TAG, stringBuilder.toString());
            }*/
        }
    }

    private int calcNetworkTeam(byte player_id) {
        int team = 1;
        if (mGameMode == GAME_MODE_2TEAMS) {
            if (player_id > 8)
                team = 2;
        } else if (mGameMode == GAME_MODE_4TEAMS){
            if (player_id > 12)
                team = 4;
            else if (player_id > 8)
                team = 3;
            else if (player_id > 4)
                team = 2;
        } else if (mGameMode == GAME_MODE_FFA)
            return player_id;
        return team;
    }

    private final BroadcastReceiver mUDPUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (UDPListenerService.UDPMSG_SHOTFIRED.equals(action)) {
                // Play a sound?
                if (mShotsFiredIV != null && mShotsFiredIV.getVisibility() != View.VISIBLE) {
                    // Show the "shots fired" image
                    mShotsFiredIV.setVisibility(View.VISIBLE);
                    Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
                    mShotsFiredIV.startAnimation(animationFadeOut);
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mShotsFiredIV.setVisibility(View.GONE);
                        }
                    }, HIT_ANIMATION_DURATION_MILLISECONDS);
                }
            } else if (UDPListenerService.UDPMSG_HIT.equals(action)) {
                // Play a sound?
                if (mHitPlayerIV != null && mHitPlayerAnimation == null) {
                    Log.d(TAG, "UDB Hit message received");
                    // Show the "you hit a player" animation
                    mHitPlayerIV.setVisibility(View.VISIBLE);
                    mHitPlayerIV.setBackgroundResource(R.drawable.hit_other_player_animation);
                    Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
                    mHitPlayerIV.startAnimation(animationFadeOut);
                    mHitPlayerAnimation = (AnimationDrawable) mHitPlayerIV.getBackground();
                    mHitPlayerAnimation.start();
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mHitPlayerIV.setVisibility(View.GONE);
                            mHitPlayerAnimation.stop();
                            mHitPlayerAnimation = null;
                        }
                    }, HIT_ANIMATION_DURATION_MILLISECONDS);
                }
            } else if (UDPListenerService.UDPMSG_OUT.equals(action)) {
                // Play a sound?
                if (mHitPlayerIV != null && mHitPlayerAnimation == null) {
                    // Show the "you hit a player that's already out" animation
                    mHitPlayerIV.setVisibility(View.VISIBLE);
                    mHitPlayerIV.setBackgroundResource(R.drawable.hit_other_player_out_animation);
                    Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
                    mHitPlayerIV.startAnimation(animationFadeOut);
                    mHitPlayerAnimation = (AnimationDrawable) mHitPlayerIV.getBackground();
                    mHitPlayerAnimation.start();
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mHitPlayerIV.setVisibility(View.GONE);
                            mHitPlayerAnimation.stop();
                            mHitPlayerAnimation = null;
                        }
                    }, HIT_ANIMATION_DURATION_MILLISECONDS);
                }
            } else if (UDPListenerService.UDPMSG_ELIMINATED.equals(action)) {
                // Increase score & play a sound?
                mScore++;
                String score = "" + mScore;
                mScoreTV.setText(score);
                mScoreIncreaseIV.setVisibility(View.VISIBLE);
                Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
                mScoreIncreaseIV.startAnimation(animationFadeOut);
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mScoreIncreaseIV.setVisibility(View.GONE);
                    }
                }, HIT_ANIMATION_DURATION_MILLISECONDS);
            } else if (UDPListenerService.UDPMSG_SCAN.equals(action)) {
                Toast.makeText(getApplicationContext(), getString(R.string.scan_started_toast), Toast.LENGTH_SHORT).show();
                mPlayerCount = mUDPListenerService.getPlayerCount();
                mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, mPlayerCount));
                String netGameMode = mUDPListenerService.getGameMode();
                if (netGameMode.equals("2")) {
                    mGameMode = GAME_MODE_2TEAMS;
                    mGameModeTV.setText(R.string.game_mode_2teams);
                } else if (netGameMode.equals("4")) {
                    mGameMode = GAME_MODE_4TEAMS;
                    mGameModeTV.setText(R.string.game_mode_4teams);
                } else {
                    mGameMode = GAME_MODE_FFA;
                    mGameModeTV.setText(R.string.game_mode_ffa);
                }
                setTeam();
            } else if (UDPListenerService.UDPMSG_PLAYER.equals(action) || UDPListenerService.UDPMSG_NOPLAYER.equals(action)) {
                mPlayerCount = mUDPListenerService.getPlayerCount();
                mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, mPlayerCount));
            } else if (UDPListenerService.UDPMSG_STARTGAME.equals(action)) {
                startGame();
            } else if (UDPListenerService.UDPMSG_ENDGAME.equals(action)) {
                endGame();
            } else if (UDPListenerService.UDPMSG_ERROR.equals(action)) {
                // Not used yet
            } else if (UDPListenerService.UDPMSG_VERSIONERROR.equals(action)) {
                Toast.makeText(getApplicationContext(), getString(R.string.error_udp_version), Toast.LENGTH_SHORT).show();
                mReady = false;
                setReady();
            } else if (UDPListenerService.UDPMSG_SAMETEAM.equals(action)) {
                Toast.makeText(getApplicationContext(), getString(R.string.error_same_id), Toast.LENGTH_SHORT).show();
                mReady = false;
                setReady();
            }
        }
    };

    private static IntentFilter makeUDPUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UDPListenerService.UDPMSG_SHOTFIRED);
        intentFilter.addAction(UDPListenerService.UDPMSG_HIT);
        intentFilter.addAction(UDPListenerService.UDPMSG_OUT);
        intentFilter.addAction(UDPListenerService.UDPMSG_ELIMINATED);
        intentFilter.addAction(UDPListenerService.UDPMSG_SCAN);
        intentFilter.addAction(UDPListenerService.UDPMSG_PLAYER);
        intentFilter.addAction(UDPListenerService.UDPMSG_STARTGAME);
        intentFilter.addAction(UDPListenerService.UDPMSG_ENDGAME);
        intentFilter.addAction(UDPListenerService.UDPMSG_ERROR);
        intentFilter.addAction(UDPListenerService.UDPMSG_VERSIONERROR);
        intentFilter.addAction(UDPListenerService.UDPMSG_SAMETEAM);
        return intentFilter;
    }

    private void displayAllNetworkingOptions(boolean enabled) {
        int visibility = enabled ? View.VISIBLE : View.GONE;
        mNetworkPlayerCountTV.setVisibility(visibility);
        mUseNetworkingButton.setVisibility(visibility);
        mReadyButton.setVisibility(visibility);
        mToggleGameModeButton.setVisibility(visibility);
        mGameModeLabelTV.setVisibility(visibility);
        mGameModeTV.setVisibility(visibility);
        mScoreLabelTV.setVisibility(visibility);
        mScoreTV.setVisibility(visibility);
    }

    private void displayInGameNetworkingOptions() {
        displayAllNetworkingOptions(false);
        mNetworkPlayerCountTV.setVisibility(View.VISIBLE);
        mGameModeLabelTV.setVisibility(View.VISIBLE);
        mGameModeTV.setVisibility(View.VISIBLE);
        mScoreLabelTV.setVisibility(View.VISIBLE);
        mScoreTV.setVisibility(View.VISIBLE);
        mEndNetworkGameButton.setVisibility(View.VISIBLE);
    }
}
