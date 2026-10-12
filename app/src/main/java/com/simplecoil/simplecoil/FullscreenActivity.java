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
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;
import com.simplecoil.simplecoil.domain.session.BatteryLevelPolicy;
import com.simplecoil.simplecoil.domain.session.GrenadeDecoder;
import com.simplecoil.simplecoil.domain.session.HitResolver;
import com.simplecoil.simplecoil.domain.session.RecoilPolicy;
import com.simplecoil.simplecoil.domain.session.ShotModePolicy;
import com.simplecoil.simplecoil.hardware.blaster.BlasterEvent;
import com.simplecoil.simplecoil.hardware.blaster.BleConnectionEvent;
import com.simplecoil.simplecoil.hardware.blaster.BleConnectionManager;
import com.simplecoil.simplecoil.hardware.blaster.BleDeviceRepository;
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler;
import com.simplecoil.simplecoil.hardware.blaster.QrPairingPayload;
import com.simplecoil.simplecoil.hardware.blaster.ScanMatcher;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;

import com.journeyapps.barcodescanner.CaptureActivity;
import com.simplecoil.simplecoil.ui.common.FlowCollectors;
import com.simplecoil.simplecoil.ui.main.MainViewModel;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import androidx.appcompat.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class FullscreenActivity extends AppCompatActivity implements PopupMenu.OnMenuItemClickListener {
    private static final String TAG = "scmain";

    private final ActivityResultLauncher<Intent> enableBtLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> connectWeapon());

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(new ScanContract(),
            result -> {
                if (result.getContents() != null) {
                    processQrResult(result.getContents());
                } else {
                    Log.e(TAG, "QR scan cancelled or no result");
                }
            });

    // For testing and debugging network only -- dumps you straight to the play game layout and allows you to switch teams without connecting a blaster
    private static final boolean TEST_NETWORK = false;

    protected MainViewModel mMainViewModel = null;

    private Button mReconnectButton = null;
    private Button mConnectButton = null;
    private Button mQRConnectButton = null;
    private Button mDedicatedServerButton = null;
    private Button mTeamMinusButton = null;
    private Button mTeamPlusButton = null;
    private Button mStartGameButton = null;
    private Button mPlayerSettingsButton = null;
    private Button mEndGameButton = null;
    private Button mEndNetworkGameButton = null;
    private Button mPlayerDataButton = null;
    private ImageView mNetworkStatusIV = null;
    private TextView mNetworkPlayerCountTV = null;
    private TextView mScoreTV = null;
    private TextView mScoreLabelTV = null;
    private TextView mTeamScoreTV = null;
    private TextView mTeamScoreLabelTV = null;
    private TextView mGameModeTV = null;
    private TextView mGameModeLabelTV = null;
    private Button mUseNetworkingButton = null;
    private Button mFiringModeButton = null;
    private TextView mEliminationCountTV = null;
    private TextView mShotsRemainingLabelTV = null;
    private TextView mShotsRemainingTV = null;
    private TextView mRecoilModeTV = null;
    private TextView mHitsTakenTV = null;
    private TextView mTeamLabelTV = null;
    private TextView mTeamTV = null;
    private TextView mShotModeTV = null;
    private TextView mEliminatedTV = null;
    private TextView mSpawnInTV = null;
    private TextView mEliminatedByTV = null;
    private TextView mHealthLabelTV = null;
    private TextView mPlayerNameTV = null;
    private ProgressBar mHealthBar = null;
    private ProgressBar mReloadBar = null;
    private ImageView mHitIV = null;
    private ImageView mBatteryLevelIV = null;
    private AnimationDrawable mHitAnimation = null;
    private ImageView mHitPlayerIV = null;
    private AnimationDrawable mHitPlayerAnimation = null;
    private TextView mHitPlayerNameTV = null;
    private ImageView mShotsFiredIV = null;
    private ImageView mScoreIncreaseIV = null;
    private TextView mScoreIncreasePlayerNameTV = null;
    private TextView mServerIPTV = null;
    private Chronometer mGameTimer = null;
    private Button mGameLimitButton = null;
    private TextView mGameCountDownTV = null;
    private PopupMenu mNetworkPopup = null;

    private FragmentManager mFragmentMgr = null;
    private Fragment mMapFragment = null;

    private CountDownTimer mSpawnTimer = null;
    private CountDownTimer mReloadTimer = null;
    private CountDownTimer mGameCountdownTimer = null;
    private CountDownTimer mRespawnShieldTimer = null;
    private TextView mRespawnShieldCountdownTV = null;
    private volatile boolean mIsShielded = false;
    private boolean mGameTimerRunning = false;


    private Vibrator vibrator = null;

    private static final int MAX_EMPTY_TRIGGER_PULLS = 3; // automatically reloads if the trigger is pulled this many times while empty (for young players)

    private boolean mHasConnectedToBlaster = false;
    private boolean mCommunicating = false; // Used to make sure that we start receiving telemetry data after initial connection
    private String mDeviceAddress = ""; // MAC address of the tagger

    private BleDeviceRepository blaster() {
        return ((SimpleCoilApp) getApplication()).getBleDeviceRepository();
    }

    private BleConnectionManager ble() {
        return ((SimpleCoilApp) getApplication()).getBleConnectionManager();
    }

    private void setDeviceAddress(String address) {
        mDeviceAddress = address != null ? address : "";
        Globals.getInstance().mDeviceAddress = mDeviceAddress;
    }
    private static byte mLastTeam = 0;
    private int mHitsTaken = 0; // total hits taken regardless of lives
    private static int mHealth = Globals.MAX_HEALTH;
    private byte mLastShotCount = 0;
    private byte mLastTriggerCount = 0;
    private byte mLastReloadButtonCount = 0;
    private byte mLastPowerButtonCount = 0;
    private byte mLastThumbButtonCount = 0;
    private static int mEliminationCount = 0;
    private static int mEmptyTriggerCount = 0;
    private static boolean mStartGameTimer = true;
    private static boolean mHasLivesLimit = false;
    private static int mLives = 0;

    private final HitResolver mHitResolver = new HitResolver();
    private final HitResolver.Lookups mHitLookups = new HitResolver.Lookups() {
        @Override
        public int networkTeam(byte playerId) {
            return Globals.getInstance().calcNetworkTeam(playerId);
        }

        @Override
        public int grenadePairing(int grenadeId) {
            return PlayerRepository.getInstance().getGrenadePairing(grenadeId);
        }

        @Override
        public Integer damage(byte playerId) {
            Globals.PlayerSettings settings = Globals.getInstance().mPlayerSettings.get(playerId);
            return settings != null ? settings.damage : null;
        }
    };

    private final BatteryLevelPolicy.Averager mBatteryAverager = new BatteryLevelPolicy.Averager();

    private static final byte BLASTER_TYPE_PISTOL = (byte)2;
    private static final byte BLASTER_TYPE_RIFLE = (byte)1;
    private static byte mBlasterType = BLASTER_TYPE_PISTOL;

    final private int REQUEST_CODE_LOCATION_PERMISSIONS = 1022;
    final private int REQUEST_CODE_CAMERA_PERMISSION = 1024;

    /* Reloading is done in 2 stages. The first stage effectively tells the tagger that it can't
       shoot anymore. We also use this state when the player is eliminated so they can't shoot while
       out of the game. At the second stage, we tell the tagger how many shots it now has. With a
       normal reload sequence, we wait until the tagger has received the first stage command, and
       we automatically start a timer thread to finish the reload after the reload wait timer is up.
       We don't want this timer to run/finish if the player is eliminated, hence the eliminated
       state here. */
    private static final int RELOADING_STATE_NONE = 0;
    private static final int RELOADING_STATE_STARTED = 1;
    private static final int RELOADING_STATE_FINISHING = 2;
    private static final int RELOADING_STATE_ELIMINATED = 3;
    private int mReloading = RELOADING_STATE_ELIMINATED;

    private int mCurrentShotMode = Globals.SHOT_MODE_SINGLE;

    private boolean mRecoilEnabled = true;

    public final static int RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER = 3;
    public final static int RECOIL_OFFSET_THUMB_COUNTER = 4;
    public final static int RECOIL_OFFSET_POWER_COUNTER = 5;
    public final static int RECOIL_OFFSET_BATTERY_LEVEL = 7;
    public final static int RECOIL_OFFSET_HIT_BY1_SHOTID = 8;
    public final static int RECOIL_OFFSET_HIT_BY1 = 9;
    public final static int RECOIL_OFFSET_HIT_BY2_SHOTID = 11;
    public final static int RECOIL_OFFSET_HIT_BY2 = 12;
    public final static int RECOIL_OFFSET_TEAM = 1;
    public final static int RECOIL_OFFSET_SHOTS_REMAINING = 14;

    private boolean mUseNetwork = false;
    private int mScore = 0;
    private int mTeamScore = 0;
    private volatile boolean mReady = false;
    private int mNetworkTeam = 0;
    private boolean mIsServer = false;
    private long mLastShotFired = 0; // to reduce shots fired message spam

    // Grenade stuff. Decoding lives in GrenadeDecoder; these aliases stay only for
    // LegacyTelemetryDecoderConstantsTest until the constants leave the activity (D1, Phase 3).
    @SuppressWarnings("unused") private static final byte GRENADE_DAMAGE = GrenadeDecoder.GRENADE_DAMAGE;
    @SuppressWarnings("unused") private static final byte GRENADE_NEW_PAIR = GrenadeDecoder.GRENADE_NEW_PAIR;
    @SuppressWarnings("unused") private static final byte GRENADE_PAIR_ID = GrenadeDecoder.GRENADE_PAIR_ID;
    @SuppressWarnings("unused") private static final byte GRENADE_DISARM = GrenadeDecoder.GRENADE_DISARM;

    /* We run a continuous handler in the background while the tagger is connected to monitor the
       connection status. Simply put, we set connectionTestHandler to false every time the handler
       runs. Every time we receive telemetry data, we set connectionTestHandler back to true. When
       the handler runs again, if connectionTestHandler is still false, then the tagger has
       disconnected without us knowing. */
    private Handler connectionTestHandler;
    private static final int CONNECTION_TEST_INTERVAL_MILLISECONDS = 5000;
    private volatile boolean mConnectionTest = false;


    private static final int HIT_VIBRATE_DURATION_MILLISECONDS = 250;
    // If you change these durations you will also need to change the duration in the res/anim/fadeout XML files to match
    private static final int HIT_ANIMATION_DURATION_MILLISECONDS = 400;
    private static final int ELIMINATED_ANIMATION_DURATION_MILLISECONDS = 800;

    private SharedPreferences sharedPreferences = null;
    public static final String PREF_NAME = "SimpleCoil";
    private static final String PREF_PLAYER_NAME = "PlayerName";
    private static final String PREF_PLAYER_ID = "PlayerID";
    private static final String PREF_SELECTED_PLAYER_ID = "SelectedPlayerID";
    private byte mSelectedPlayerID = 0;
    private static final String PREF_FIRING_MODE = "FiringMode";
    private static final String PREF_SHOT_MODE = "ShotMode";
    private static final String PREF_RECOIL_ENABLED = "RecoilEnabled";
    public static final String PREF_GAME_MODE = "GameMode";
    public static final String PREF_LIMIT_TIME = "TimeLimit";
    public static final String PREF_LIMIT_LIVES = "LivesLimit";
    public static final String PREF_LIMIT_SCORE = "ScoreLimit";
    public static final String PREF_DEVICE_ADDRESS = "DeviceAddress";


    // Code to manage Service lifecycle.
    private ServiceConnection mUDPServiceConnection = null;
    private UDPListenerService mUDPListenerService = null;
    private boolean mAutoScanStarted = false;
    private int mNetworkMenuType = NETWORK_TYPE_ENABLED;
    private volatile boolean mIsScoreboardVisible = false;
    private PlayerDisplayData[] mLatestPlayerDisplayData = null;
    private AlertDialog mScoreboardDialog = null;
    private PlayerDisplayDataListAdapter mScoreboardAdapter = null;

    private void openScoreboard() {
        Runnable runnable = () -> {
            mIsScoreboardVisible = true;
            if (mLatestPlayerDisplayData != null) {
                showOrUpdateScoreboardDialog(mLatestPlayerDisplayData);
            }
            if (mUseNetwork) {
                if (mIsServer && mTcpServer != null) {
                    String json = mTcpServer.getPlayerDataJson();
                    if (json != null && !json.isEmpty()) {
                        displayPlayerData(json);
                    } else {
                        mTcpServer.sendPlayerData(TcpServer.SEND_ALL);
                    }
                } else if (mTcpClient != null) {
                    mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_PLAYERDATAREQUEST);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            runOnUiThread(runnable);
        }
    }

    private void closeScoreboard() {
        Runnable runnable = () -> {
            mIsScoreboardVisible = false;
            if (mScoreboardDialog != null) {
                try {
                    mScoreboardDialog.setOnDismissListener(null);
                    if (mScoreboardDialog.isShowing()) {
                        mScoreboardDialog.dismiss();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error dismissing scoreboard dialog", e);
                }
                mScoreboardDialog = null;
                mScoreboardAdapter = null;
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            runOnUiThread(runnable);
        }
    }

    private void dismissScoreboardDialog() {
        closeScoreboard();
    }

    private final Handler mSearchTimeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable mSearchTimeoutRunnable = null;
    private static final long SEARCH_TIMEOUT_MS = 10000;

    private void startServerSearchTimeout() {
        cancelServerSearchTimeout();
        mSearchTimeoutRunnable = () -> {
            if (mUseNetwork && !mIsServer && mNetworkMenuType == NETWORK_TYPE_JOINING) {
                Log.d(TAG, "Server search cycle ended; re-triggering scan...");
                if (mUDPListenerService != null) {
                    mUDPListenerService.joinServer();
                }
                startServerSearchTimeout();
            }
        };
        mSearchTimeoutHandler.postDelayed(mSearchTimeoutRunnable, SEARCH_TIMEOUT_MS);
    }

    private void cancelServerSearchTimeout() {
        if (mSearchTimeoutRunnable != null) {
            mSearchTimeoutHandler.removeCallbacks(mSearchTimeoutRunnable);
            mSearchTimeoutRunnable = null;
        }
    }

    private void stopServerSearch(boolean disableNetwork) {
        cancelServerSearchTimeout();
        if (mUDPListenerService != null) {
            mUDPListenerService.endScanning();
        }
        mReady = false;
        setReady(disableNetwork);
        if (disableNetwork) {
            mAutoScanStarted = false;
            displayAllNetworkingOptions(false);
            mUseNetwork = false;
            mUseNetworkingButton.setText(R.string.use_network_button);
            if (Globals.getInstance().mPlayerID == 0) {
                Globals.getInstance().mPlayerID = (mSelectedPlayerID != 0) ? mSelectedPlayerID : 1;
            }
            setTeam();
        } else {
            setNetworkMenu(NETWORK_TYPE_ENABLED);
            displayAllNetworkingOptions(true);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isWifiDisconnected() {
        ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connManager == null) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = connManager.getActiveNetwork();
            if (network == null) {
                return true;
            }
            NetworkCapabilities capabilities = connManager.getNetworkCapabilities(network);
            return capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } else {
            NetworkInfo mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            return mWifi == null || !mWifi.isConnected();
        }
    }

    private void autoStartServerScan() {
        if (!mAutoScanStarted && mUDPListenerService != null && !mIsServer) {
            if (isWifiDisconnected()) {
                Log.d(TAG, "autoStartServerScan: Wi-Fi not connected, skipping auto-scan");
                return;
            }
            mAutoScanStarted = true;
            Log.d(TAG, "autoStartServerScan: starting automatic server discovery on launch");
            mUseNetwork = true;
            Globals.getInstance().mPlayerID = mSelectedPlayerID;
            displayAllNetworkingOptions(true);
            setNetworkMenu(NETWORK_TYPE_JOINING);
            setTeam();
            startServerSearchTimeout();
            mUDPListenerService.joinServer();
        }
    }

    private void startServiceSafely(Intent intent) {
        try {
            startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not start service directly: " + e.getMessage());
        }
    }

    private void startForegroundServiceSafely(Intent intent) {
        try {
            ContextCompat.startForegroundService(getBaseContext(), intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not start foreground service: " + e.getMessage());
            try {
                startService(intent);
            } catch (Exception ex) {
                Log.w(TAG, "Could not start service directly: " + ex.getMessage());
            }
        }
    }

    private void setupUDPServiceConnection() {
        mUDPServiceConnection = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                mUDPListenerService = ((UDPListenerService.LocalBinder) service).getService();
                if (mCommunicating) {
                    autoStartServerScan();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                mReady = false;
                setReady(false);
                mIsServer = false;
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                    endGame();
                mUDPListenerService = null;
            }
        };
        Intent udpServiceIntent = new Intent(getBaseContext(), UDPListenerService.class);
        startServiceSafely(udpServiceIntent);
        bindService(udpServiceIntent, mUDPServiceConnection, BIND_AUTO_CREATE);
    }

    private TcpClient mTcpClient = null;
    private ServiceConnection mTcpClientServiceConnection = null;

    private TcpServer mTcpServer = null;
    private ServiceConnection mTcpServerServiceConnection = null;

    private void setupTcpClientServiceConnection() {
        mTcpClientServiceConnection = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                mTcpClient = ((TcpClient.LocalBinder) service).getService();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                mTcpClient = null;
            }
        };
        Intent serviceIntent = new Intent(getBaseContext(), TcpClient.class);
        startForegroundServiceSafely(serviceIntent);
        bindService(serviceIntent, mTcpClientServiceConnection, BIND_AUTO_CREATE);
    }

    private void setupTcpServerServiceConnection() {
        mTcpServerServiceConnection = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                mTcpServer = ((TcpServer.LocalBinder) service).getService();
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
        setContentView(R.layout.activity_fullscreen);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Fragment f = getSupportFragmentManager().findFragmentById(R.id.map_fragment);
                if (f != null) {
                    mFragmentMgr.popBackStack();
                }
                moveTaskToBack(true);
            }
        });
        sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        if (savedInstanceState != null) {
            mSelectedPlayerID = savedInstanceState.getByte("mSelectedPlayerID", (byte)0);
            mDeviceAddress = savedInstanceState.getString("mDeviceAddress", "");
            setDeviceAddress(mDeviceAddress);
            mUseNetwork = savedInstanceState.getBoolean("mUseNetwork", false);
            mIsServer = savedInstanceState.getBoolean("mIsServer", false);
            mNetworkMenuType = savedInstanceState.getInt("mNetworkMenuType", NETWORK_TYPE_ENABLED);
        } else {
            mSelectedPlayerID = (byte) sharedPreferences.getInt(PREF_SELECTED_PLAYER_ID, 0);
            if (mSelectedPlayerID == 0) {
                mSelectedPlayerID = (byte) sharedPreferences.getInt(PREF_PLAYER_ID, 0);
            }
        }
        Globals.getInstance().mPlayerID = mSelectedPlayerID;

        mMainViewModel = new ViewModelProvider(this).get(MainViewModel.class);
        mMainViewModel.getHealthLiveData().observe(this, health -> {
            if (health != null && mHealthBar != null) {
                mHealthBar.setProgress(health);
            }
        });
        mMainViewModel.getAmmoLiveData().observe(this, ammo -> {
            if (ammo != null && mShotsRemainingTV != null) {
                mShotsRemainingTV.setText(String.valueOf(ammo));
            }
        });

        // Delivered only while resumed, like the GATT broadcast receiver this replaces.
        FlowCollectors.collectWhileAtLeast(this, Lifecycle.State.RESUMED, blaster().getEvents(), this::handleBlasterEvent);
        // Scan results and the scan give-up arrive whenever they happen, as the scan callback and
        // CountDownTimer did; adapter-on only while resumed, as its receiver was.
        FlowCollectors.collectWhileAtLeast(this, Lifecycle.State.CREATED, ble().getEvents(), this::handleConnectionEvent);
        FlowCollectors.collectWhileAtLeast(this, Lifecycle.State.RESUMED, ble().getEvents(), this::handleAdapterEvent);

        mFragmentMgr = getSupportFragmentManager();
        mEliminationCountTV = findViewById(R.id.eliminations_count_tv);
        mReconnectButton = findViewById(R.id.reconnect_weapon_button);
        mReconnectButton.setOnClickListener((v -> {
            if (sharedPreferences == null)
                sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            mDeviceAddress = sharedPreferences.getString(PREF_DEVICE_ADDRESS, "");
            setDeviceAddress(mDeviceAddress);
            if (!mDeviceAddress.isEmpty())
                connectWeapon();
            else {
                mReconnectButton.setVisibility(View.GONE);
            }
        }));
        mConnectButton = findViewById(R.id.connect_weapon_button);
        if (mConnectButton != null) {
            mConnectButton.setOnClickListener((v -> {
                mDeviceAddress = "";
                setDeviceAddress(mDeviceAddress);
                connectWeapon();
            }));
        }
        mQRConnectButton = findViewById(R.id.connect_qr_weapon_button);
        if (mQRConnectButton != null) {
            mQRConnectButton.setOnClickListener((v -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CODE_CAMERA_PERMISSION);
                        return;
                    }
                }
                startQRScan();
            }));
        }
        mDedicatedServerButton = findViewById(R.id.dedicated_server_button);
        if (mDedicatedServerButton != null) {
            mDedicatedServerButton.setOnClickListener((v -> {
                if (mUDPServiceConnection != null)
                    unbindService(mUDPServiceConnection);
                if (mTcpClientServiceConnection != null)
                    unbindService(mTcpClientServiceConnection);
                if (mTcpServerServiceConnection != null)
                    unbindService(mTcpServerServiceConnection);
                mUDPListenerService = null;
                mTcpClientServiceConnection = null;
                mTcpServerServiceConnection = null;
                startActivity(new Intent(FullscreenActivity.this, DedicatedServerActivity.class));
            }));
        }
        mTeamMinusButton = findViewById(R.id.team_minus_button);
        if (mTeamMinusButton != null) {
            mTeamMinusButton.setVisibility(View.VISIBLE);
            mTeamMinusButton.setOnClickListener((v -> {
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                    return;
                byte playerId = Globals.getInstance().mPlayerID;
                if (mUseNetwork) {
                    if (playerId > (byte)0)
                        playerId--;
                    else
                        playerId = Globals.MAX_PLAYER_ID;
                } else {
                    if (playerId > (byte)1)
                        playerId--;
                    else
                        playerId = Globals.MAX_PLAYER_ID;
                }
                Globals.getInstance().mPlayerID = playerId;
                mSelectedPlayerID = playerId;
                setTeam();
            }));
        }
        mTeamPlusButton = findViewById(R.id.team_plus_button);
        if (mTeamPlusButton != null) {
            mTeamPlusButton.setVisibility(View.VISIBLE);
            mTeamPlusButton.setOnClickListener((v -> {
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                    return;
                byte playerId = Globals.getInstance().mPlayerID;
                if (mUseNetwork) {
                    if (playerId < Globals.MAX_PLAYER_ID)
                        playerId++;
                    else
                        playerId = (byte)0x00;
                } else {
                    if (playerId < Globals.MAX_PLAYER_ID)
                        playerId++;
                    else
                        playerId = (byte)0x01;
                }
                Globals.getInstance().mPlayerID = playerId;
                mSelectedPlayerID = playerId;
                setTeam();
            }));
        }
        mStartGameButton = findViewById(R.id.start_game_button);
        if (mStartGameButton != null) {
            mStartGameButton.setOnClickListener((v -> {
                if (Globals.getInstance().mPlayerID == 0) {
                    Globals.getInstance().mPlayerID = 1;
                    setTeam();
                }
                if (mUseNetwork && mNetworkMenuType == NETWORK_TYPE_JOINED) {
                    if (Globals.getPlayerCount() <= 1) {
                        Toast.makeText(getApplicationContext(), getString(R.string.not_enough_players_toast), Toast.LENGTH_SHORT).show();
                        mNetworkPlayerCountTV.setText(R.string.network_player_1count);
                        return;
                    }
                    if (mIsServer)
                        mTcpServer.startGame();
                    else
                        mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_STARTGAME);
                } else {
                    if (mUseNetwork) {
                        stopServerSearch(true);
                    }
                    startGame();
                }
            }));
        }
        mPlayerSettingsButton = findViewById(R.id.player_settings_button);
        if (mPlayerSettingsButton != null) {
            mPlayerSettingsButton.setOnClickListener((v -> {
                if (!Globals.getInstance().mAllowPlayerSettings) {
                    Toast.makeText(getApplicationContext(), getString(R.string.player_settings_not_allowed_toast), Toast.LENGTH_SHORT).show();
                    return;
                }
                PlayerSettingsAlertDialog dialog = new PlayerSettingsAlertDialog(FullscreenActivity.this);
                dialog.setLocal(mTcpClient);
                dialog.show();
            }));
        }
        mEndGameButton = findViewById(R.id.end_game_button);
        if (mEndGameButton != null) {
            mEndGameButton.setOnClickListener((v -> confirmEndGame()));
        }
        mEndNetworkGameButton = findViewById(R.id.end_network_game_button);
        if (mEndNetworkGameButton != null) {
            mEndNetworkGameButton.setOnClickListener((v -> confirmEndGame()));
        }
        mShotsRemainingLabelTV = findViewById(R.id.shots_label_tv);
        mShotsRemainingTV = findViewById(R.id.shots_remaining_tv);
        mHitsTakenTV = findViewById(R.id.hits_taken_tv);
        mTeamLabelTV = findViewById(R.id.team_label_tv);
        mTeamTV = findViewById(R.id.team_tv);
        mRecoilModeTV = findViewById(R.id.recoil_tv);
        mShotModeTV = findViewById(R.id.shot_mode_tv);
        mHealthLabelTV = findViewById(R.id.health_label_tv);
        mHealthBar = findViewById(R.id.health_pb);
        mHealth = Globals.getInstance().mFullHealth;
        mHealthBar.setMax(mHealth);
        mHealthBar.setProgress(mHealth);
        mReloadBar = findViewById(R.id.reload_pb);
        mEliminatedTV = findViewById(R.id.eliminated_tv);
        mEliminatedByTV = findViewById(R.id.eliminated_by_tv);
        mSpawnInTV = findViewById(R.id.spawn_countdown_tv);
        mRespawnShieldCountdownTV = findViewById(R.id.respawn_shield_countdown_tv);
        mHitIV = findViewById(R.id.hit_animation_iv);
        mBatteryLevelIV = findViewById(R.id.battery_iv);
        WebView wv = findViewById(R.id.info_wv);
        wv.loadUrl("file:///android_asset/simplecoil.html");
        wv.setVisibility(View.VISIBLE);
        mGameTimer = findViewById(R.id.game_timer_chronometer);
        mFiringModeButton = findViewById(R.id.firing_mode_button);
        if (mFiringModeButton != null) {
            mFiringModeButton.setOnClickListener((v -> {
                PopupMenu popup = new PopupMenu(new ContextThemeWrapper(FullscreenActivity.this, R.style.AppTheme_PopupOverlay), v);
                MenuInflater inflater = popup.getMenuInflater();
                inflater.inflate(R.menu.firing_mode_menu, popup.getMenu());
                popup.setOnMenuItemClickListener(FullscreenActivity.this);
                popup.show();
                Toast.makeText(getApplicationContext(), getString(R.string.firing_mode_cone_toast), Toast.LENGTH_LONG).show();
            }));
        }

        showConnectLayout();
        initBatteryQueue();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vibratorManager != null) {
                vibrator = vibratorManager.getDefaultVibrator();
            }
        } else {
            @SuppressWarnings("deprecation")
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            vibrator = v;
        }
        sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Globals.getInstance().mPlayerName = sharedPreferences.getString(PREF_PLAYER_NAME, "Player");
        if (Globals.getInstance().mPlayerName.isEmpty() ||
            Globals.getInstance().mPlayerName.equalsIgnoreCase("Player") ||
            ProfanityFilter.containsProfanity(Globals.getInstance().mPlayerName)) {

            String randomName = NameGenerator.getRandomName();
            Globals.getInstance().mPlayerName = randomName;
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(PREF_PLAYER_NAME, randomName);
            editor.apply();
        }
        Globals.getInstance().mCurrentFiringMode = sharedPreferences.getInt(PREF_FIRING_MODE, Globals.FIRING_MODE_OUTDOOR_NO_CONE);
        mSelectedPlayerID = (byte) sharedPreferences.getInt(PREF_SELECTED_PLAYER_ID, 0);
        Globals.getInstance().mPlayerID = mSelectedPlayerID;
        getFiringMode();
        mRecoilEnabled = sharedPreferences.getBoolean(PREF_RECOIL_ENABLED, true);
        mCurrentShotMode = sharedPreferences.getInt(PREF_SHOT_MODE, Globals.SHOT_MODE_SINGLE);
        Globals.getInstance().mGameMode = sharedPreferences.getInt(PREF_GAME_MODE, Globals.GAME_MODE_2TEAMS);
        int gameLimit = Globals.GAME_LIMIT_NONE;
        Globals.getInstance().mTimeLimit = sharedPreferences.getInt(PREF_LIMIT_TIME, 0);
        if (Globals.getInstance().mTimeLimit != 0)
            gameLimit += Globals.GAME_LIMIT_TIME;
        Globals.getInstance().mLivesLimit = sharedPreferences.getInt(PREF_LIMIT_LIVES, 0);
        if (Globals.getInstance().mLivesLimit != 0)
            gameLimit += Globals.GAME_LIMIT_LIVES;
        Globals.getInstance().mScoreLimit = sharedPreferences.getInt(PREF_LIMIT_SCORE, 0);
        if (Globals.getInstance().mScoreLimit != 0)
            gameLimit += Globals.GAME_LIMIT_SCORE;
        Globals.getInstance().mGameLimit = gameLimit;
        mDeviceAddress = sharedPreferences.getString(PREF_DEVICE_ADDRESS, "");
        setDeviceAddress(mDeviceAddress);
        if (!mDeviceAddress.isEmpty()) {
            mReconnectButton.setVisibility(View.VISIBLE);
        } else {
            mReconnectButton.setVisibility(View.GONE);
        }

        try {
            // Display app version
            PackageInfo pInfo = this.getPackageManager().getPackageInfo(getPackageName(), 0);
            TextView versionTextView = findViewById(R.id.version_tv);
            versionTextView.setText(pInfo.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unable to get app information from PackageManager: " + e.getMessage());
        }

        mNetworkStatusIV = findViewById(R.id.network_status_iv);
        mNetworkPlayerCountTV = findViewById(R.id.player_count_tv);
        mUseNetworkingButton = findViewById(R.id.use_network_button);
        if (mUseNetworkingButton != null) {
            mUseNetworkingButton.setOnClickListener((v -> {
                if (isWifiDisconnected()) {
                    Toast.makeText(getApplicationContext(), getString(R.string.error_no_wifi), Toast.LENGTH_SHORT).show();
                    mUseNetwork = false;
                    return;
                }
                if (mNetworkPopup == null) {
                    mNetworkPopup = new PopupMenu(new ContextThemeWrapper(FullscreenActivity.this, R.style.AppTheme_PopupOverlay), v);
                    mNetworkPopup.setOnMenuItemClickListener(FullscreenActivity.this);
                }
                if (!mUseNetwork) {
                    mUseNetwork = true;
                    displayAllNetworkingOptions(true);
                    Globals.getInstance().mPlayerID = mSelectedPlayerID;
                    setNetworkMenu(NETWORK_TYPE_JOINING);
                    if (mUDPListenerService != null) {
                        mUDPListenerService.joinServer();
                    }
                } else {
                    setNetworkMenu(mNetworkMenuType);
                }
                mNetworkPopup.show();
                setTeam();
            }));
        }
        mGameModeLabelTV = findViewById(R.id.game_mode_label_tv);
        mGameModeTV = findViewById(R.id.game_mode_tv);
        mScoreLabelTV = findViewById(R.id.score_label_tv);
        mScoreTV = findViewById(R.id.score_tv);
        mTeamScoreLabelTV = findViewById(R.id.team_score_label_tv);
        mTeamScoreTV = findViewById(R.id.team_score_tv);
        mHitPlayerIV = findViewById(R.id.hit_player_iv);
        mHitPlayerNameTV = findViewById(R.id.hit_player_name_tv);
        mShotsFiredIV = findViewById(R.id.shots_fired_iv);
        mScoreIncreaseIV = findViewById(R.id.score_increase_iv);
        mScoreIncreasePlayerNameTV = findViewById(R.id.score_increase_player_name_tv);
        mServerIPTV = findViewById(R.id.server_ip_tv);
        mGameLimitButton = findViewById(R.id.game_limit_button);
        if (mGameLimitButton != null) {
            mGameLimitButton.setOnClickListener((v -> requestGameLimit()));
        }
        mGameCountDownTV = findViewById(R.id.game_countdown_tv);
        mPlayerNameTV = findViewById(R.id.player_name_label_tv);
        if (mPlayerNameTV != null)
            mPlayerNameTV.setText(Globals.getInstance().mPlayerName);
        Button shuffleTeamsButton = findViewById(R.id.shuffle_teams_button);
        if (shuffleTeamsButton != null) {
            shuffleTeamsButton.setOnClickListener(v -> {
                if (mIsServer && mTcpServer != null) {
                    mTcpServer.rebalanceAllPlayers(true);
                    Toast.makeText(getApplicationContext(), "Teams Sorted & Randomized!", Toast.LENGTH_SHORT).show();
                }
            });
        }
        Button adminControlsButton = findViewById(R.id.admin_controls_button);
        if (adminControlsButton != null) {
            adminControlsButton.setOnClickListener(this::showAdminControlsMenu);
        }
        displayAllNetworkingOptions(false);
        mUseNetworkingButton.setVisibility(View.VISIBLE);
        setGameLimit();
        mPlayerDataButton = findViewById(R.id.player_data_button);
        if (mPlayerDataButton != null) {
            mPlayerDataButton.setOnClickListener(v -> openScoreboard());
        }
        loadFragment();
        updatePlayerSettings();
    }

    private void showAdminControlsMenu(View v) {
        Log.d(TAG, "showAdminControlsMenu: current mGameState=" + Globals.getInstance().mGameState + ", mIsServer=" + mIsServer);
        PopupMenu popup = new PopupMenu(new ContextThemeWrapper(FullscreenActivity.this, R.style.AppTheme_PopupOverlay), v);
        if (Globals.getInstance().mGameState == Globals.GAME_STATE_NONE) {
            popup.getMenu().add(0, 101, 10, R.string.start_game_button);
            popup.getMenu().add(0, 103, 20, R.string.game_mode_2teams);
            popup.getMenu().add(0, 104, 30, R.string.game_mode_4teams);
            popup.getMenu().add(0, 105, 40, R.string.game_mode_ffa);
            popup.getMenu().add(0, 106, 50, R.string.shuffle_teams_button);
        } else {
            popup.getMenu().add(0, 102, 10, R.string.end_game_button);
        }

        popup.setOnMenuItemClickListener(item -> {
            Log.d(TAG, "showAdminControlsMenu onMenuItemClick: itemId=" + item.getItemId());
            switch (item.getItemId()) {
                case 101:
                    Log.d(TAG, "Server Controls: Start Game clicked. mIsServer=" + mIsServer + ", mTcpClient=" + (mTcpClient != null));
                    if (mIsServer && mTcpServer != null) {
                        mTcpServer.startGame();
                    } else if (mTcpClient != null) {
                        mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_STARTGAME);
                        mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ADMIN_STARTGAME);
                    }
                    return true;
                case 102:
                    Log.d(TAG, "Server Controls: End Game clicked. mIsServer=" + mIsServer + ", mTcpClient=" + (mTcpClient != null));
                    if (mIsServer && mTcpServer != null) {
                        mTcpServer.endGame();
                    } else if (mTcpClient != null) {
                        mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ADMIN_ENDGAME);
                    }
                    return true;
                case 103:
                    Log.d(TAG, "Server Controls: 2 Teams clicked");
                    changeAdminGameMode(Globals.GAME_MODE_2TEAMS);
                    return true;
                case 104:
                    Log.d(TAG, "Server Controls: 4 Teams clicked");
                    changeAdminGameMode(Globals.GAME_MODE_4TEAMS);
                    return true;
                case 105:
                    Log.d(TAG, "Server Controls: FFA clicked");
                    changeAdminGameMode(Globals.GAME_MODE_FFA);
                    return true;
                case 106:
                    Log.d(TAG, "Server Controls: Shuffle Teams clicked");
                    if (mIsServer && mTcpServer != null) {
                        mTcpServer.rebalanceAllPlayers(true);
                    } else if (mTcpClient != null) {
                        mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ADMIN_SORTTEAMS);
                    }
                    return true;
            }
            return false;
        });
        popup.show();
    }

    private void changeAdminGameMode(int mode) {
        Log.d(TAG, "changeAdminGameMode: mode=" + mode + ", mIsServer=" + mIsServer + ", mTcpClient=" + (mTcpClient != null));
        if (mIsServer && mTcpServer != null) {
            Globals.getInstance().mGameMode = mode;
            mTcpServer.rebalanceAllPlayers(false);
        } else if (mTcpClient != null) {
            mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ADMIN_GAMEMODE + mode);
        }
    }

    private static final int NETWORK_TYPE_ENABLED = 1;
    private static final int NETWORK_TYPE_JOINING = 2;
    private static final int NETWORK_TYPE_JOINED = 3;

    private void setNetworkMenu(int networkMenuType) {
        mNetworkMenuType = networkMenuType;
        if (mUseNetworkingButton != null) {
            switch (networkMenuType) {
                case NETWORK_TYPE_ENABLED:
                    mUseNetworkingButton.setText(R.string.network_menu_button);
                    break;
                case NETWORK_TYPE_JOINING:
                    mUseNetworkingButton.setText(R.string.network_searching_status);
                    break;
                case NETWORK_TYPE_JOINED:
                    mUseNetworkingButton.setText(R.string.network_connected_status);
                    break;
            }
        }
        if (mNetworkPopup != null) {
            mNetworkPopup.setForceShowIcon(true);
            mNetworkPopup.getMenu().close();
            mNetworkPopup.getMenu().clear();
            switch (networkMenuType) {
                case NETWORK_TYPE_ENABLED:
                    mNetworkPopup.getMenu().add(0, R.id.join_item, 20, R.string.join_button);
                    mNetworkPopup.getMenu().add(0, R.id.join_ip_item, 30, R.string.join_ip_button);
                    mNetworkPopup.getMenu().add(0, R.id.player_name_item, 50, getString(R.string.player_name_button, Globals.getInstance().mPlayerName));
                    if (Globals.getInstance().mGameState == Globals.GAME_STATE_NONE) {
                        mNetworkPopup.getMenu().add(0, R.id.game_mode_item, 60, R.string.game_mode_toggle_button);
                    }
                    mNetworkPopup.getMenu().add(0, R.id.disable_network_item, 70, R.string.no_network_button);
                    break;
                case NETWORK_TYPE_JOINING:
                    mNetworkPopup.getMenu().add(0, R.id.join_ip_item, 30, R.string.join_ip_button);
                    mNetworkPopup.getMenu().add(0, R.id.disable_network_item, 70, R.string.no_network_button);
                    break;
                case NETWORK_TYPE_JOINED:
                    mNetworkPopup.getMenu().add(0, R.id.player_name_item, 50, getString(R.string.player_name_button, Globals.getInstance().mPlayerName));
                    if (GameSettingsRepository.getInstance().isAllowChooseTeams() && Globals.getInstance().mGameState == Globals.GAME_STATE_NONE) {
                        int gameMode = Globals.getInstance().mGameMode;
                        if (gameMode == Globals.GAME_MODE_2TEAMS || gameMode == Globals.GAME_MODE_4TEAMS) {
                            int currentTeam = Globals.getInstance().calcNetworkTeam(Globals.getInstance().mPlayerID);
                            int numTeams = (gameMode == Globals.GAME_MODE_4TEAMS) ? 4 : 2;
                            int order = 51;
                            for (int t = 1; t <= numTeams; t++) {
                                if (t != currentTeam) {
                                    int itemId;
                                    switch (t) {
                                        case 1: itemId = R.id.switch_team_1_item; break;
                                        case 2: itemId = R.id.switch_team_2_item; break;
                                        case 3: itemId = R.id.switch_team_3_item; break;
                                        case 4: itemId = R.id.switch_team_4_item; break;
                                        default: itemId = 0; break;
                                    }
                                    if (itemId != 0) {
                                        MenuItem item = mNetworkPopup.getMenu().add(0, itemId, order++, getString(R.string.switch_to_team, t));
                                        int teamColor = Globals.getTeamColor(t);
                                        if (teamColor != Color.TRANSPARENT) {
                                            item.setIcon(createTeamColorSquare(teamColor));
                                        }
                                    }
                                }
                            }
                        }
                    }
                    mNetworkPopup.getMenu().add(0, R.id.disable_network_item, 70, R.string.no_network_button);
                    break;
            }
        }
        updatePreGameButtonVisibilities();
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.stop_searching_item) {
            stopServerSearch(false);
            return true;
        } else if (id == R.id.disable_network_item) {
            stopServerSearch(true);
            return true;
        } else if (id == R.id.join_item) {
            if (mNetworkMenuType == NETWORK_TYPE_JOINED)
                return true;
            mReady = true;
            setReady();
            setNetworkMenu(NETWORK_TYPE_JOINING);
            startServerSearchTimeout();
            mUDPListenerService.joinServer();
            return true;
        } else if (id == R.id.join_ip_item) {
            if (mNetworkMenuType == NETWORK_TYPE_JOINED)
                return true;
            requestServerIP();
            return true;
        } else if (id == R.id.player_name_item) {
            requestPlayerName();
            return true;
        } else if (id == R.id.switch_team_1_item) {
            requestSwitchTeam(1);
            return true;
        } else if (id == R.id.switch_team_2_item) {
            requestSwitchTeam(2);
            return true;
        } else if (id == R.id.switch_team_3_item) {
            requestSwitchTeam(3);
            return true;
        } else if (id == R.id.switch_team_4_item) {
            requestSwitchTeam(4);
            return true;
        } else if (id == R.id.game_mode_item) {
            PopupMenu popup = new PopupMenu(new ContextThemeWrapper(FullscreenActivity.this, R.style.AppTheme_PopupOverlay), mUseNetworkingButton);
            MenuInflater inflater = popup.getMenuInflater();
            inflater.inflate(R.menu.game_mode_menu, popup.getMenu());
            popup.setOnMenuItemClickListener(FullscreenActivity.this);
            popup.show();
            return true;
        } else if (id == R.id.please_wait_item) {
            return true;
        } else if (id == R.id.leave_item) {
            mReady = false;
            setReady();
            Globals.getInstance().mPlayerID = mSelectedPlayerID;
            setTeam();
            setNetworkMenu(NETWORK_TYPE_ENABLED);
            return true;
        } else if (id == R.id.firing_mode_outdoor_no_cone_item) {
            Globals.getInstance().mCurrentFiringMode = Globals.FIRING_MODE_OUTDOOR_NO_CONE;
            mFiringModeButton.setText(R.string.firing_mode_outdoor_no_cone);
            setShotMode(mCurrentShotMode);
            {
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt(PREF_FIRING_MODE, Globals.getInstance().mCurrentFiringMode);
                editor.apply();
            }
            return true;
        } else if (id == R.id.firing_mode_outdoor_with_cone_item) {
            Globals.getInstance().mCurrentFiringMode = Globals.FIRING_MODE_OUTDOOR_WITH_CONE;
            mFiringModeButton.setText(R.string.firing_mode_outdoor_with_cone);
            setShotMode(mCurrentShotMode);
            {
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt(PREF_FIRING_MODE, Globals.getInstance().mCurrentFiringMode);
                editor.apply();
            }
            return true;
        } else if (id == R.id.firing_mode_indoor_no_cone_item) {
            Globals.getInstance().mCurrentFiringMode = Globals.FIRING_MODE_INDOOR_NO_CONE;
            mFiringModeButton.setText(R.string.firing_mode_indoor_no_cone);
            setShotMode(mCurrentShotMode);
            {
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt(PREF_FIRING_MODE, Globals.getInstance().mCurrentFiringMode);
                editor.apply();
            }
            return true;
        } else if (id == R.id.shuffle_teams_item) {
            if (mIsServer && mTcpServer != null) {
                mTcpServer.rebalanceAllPlayers(true);
                Toast.makeText(getApplicationContext(), "Teams Sorted & Randomized!", Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (id == R.id.game_mode_2teams_item) {
            Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
            mGameModeTV.setText(R.string.game_mode_2teams);
            setTeam();
            if (mIsServer && mTcpServer != null) {
                mTcpServer.rebalanceAllPlayers(false);
            }
            return true;
        } else if (id == R.id.game_mode_4teams_item) {
            Globals.getInstance().mGameMode = Globals.GAME_MODE_4TEAMS;
            mGameModeTV.setText(R.string.game_mode_4teams);
            setTeam();
            if (mIsServer && mTcpServer != null) {
                mTcpServer.rebalanceAllPlayers(false);
            }
            return true;
        } else if (id == R.id.game_mode_ffa_item) {
            Globals.getInstance().mGameMode = Globals.GAME_MODE_FFA;
            mGameModeTV.setText(R.string.game_mode_ffa);
            setTeam();
            if (mIsServer && mTcpServer != null) {
                mTcpServer.rebalanceAllPlayers(false);
            }
            return true;
        }
        return false;
    }

    private void requestSwitchTeam(int targetTeam) {
        if (mTcpClient == null) {
            return;
        }
        if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
            Log.w(TAG, "Cannot request team change while game is running");
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put(TcpServer.JSON_PLAYERID, (int) Globals.getInstance().mPlayerID);
            json.put(TcpServer.JSON_REQUEST_TEAM_CHANGE, targetTeam);
            mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + json);
        } catch (JSONException e) {
            Log.e(TAG, "JSON Exception: " + e.getMessage());
        }
    }

    private Drawable createTeamColorSquare(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setStroke((int) (1 * getResources().getDisplayMetrics().density), Color.WHITE);
        int size = (int) (16 * getResources().getDisplayMetrics().density);
        drawable.setSize(size, size);
        return drawable;
    }

    private void getFiringMode() {
        switch (Globals.getInstance().mCurrentFiringMode) {
            case Globals.FIRING_MODE_OUTDOOR_NO_CONE:
                mFiringModeButton.setText(R.string.firing_mode_outdoor_no_cone);
                return;
            case Globals.FIRING_MODE_OUTDOOR_WITH_CONE:
                mFiringModeButton.setText(R.string.firing_mode_outdoor_with_cone);
                return;
            case Globals.FIRING_MODE_INDOOR_NO_CONE:
                mFiringModeButton.setText(R.string.firing_mode_indoor_no_cone);
        }
    }

    private void requestPlayerName() {
        LayoutInflater li = LayoutInflater.from(getApplicationContext());
        View view = li.inflate(R.layout.player_name_dialog, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        alertDialogBuilder.setView(view);

        final EditText playerNameET = view.findViewById(R.id.player_name_et);
        playerNameET.setText(Globals.getInstance().mPlayerName);

        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton(R.string.ok,
                        (dialog, id) -> {
                            String name = playerNameET.getText().toString().trim();
                            setPlayerName(name);
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.cancel,
                        (dialog, id) -> dialog.cancel());
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    private void requestServerIP() {
        LayoutInflater li = LayoutInflater.from(getApplicationContext());
        View view = li.inflate(R.layout.server_ip_dialog, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        alertDialogBuilder.setView(view);

        final EditText serverIPET = view.findViewById(R.id.server_ip_et);
        String ip;
        if (Globals.getInstance().mServerIP != null)
            ip = Globals.getInstance().mServerIP.toString();
        else
            ip = Globals.getIPAddressStr();
        if (ip.charAt(0) == '/')
            ip = ip.substring(1);
        serverIPET.setText(ip);

        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton(R.string.ok,
                        (dialog, id) -> {
                            mIsServer = true; // This tricks the setReady function into not searching for a server
                            mReady = true;
                            setReady();
                            mIsServer = false;
                            mUDPListenerService.joinServer(serverIPET.getText().toString());
                            setNetworkMenu(NETWORK_TYPE_JOINING);
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.cancel,
                        (dialog, id) -> dialog.cancel());
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
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
        if (mUseNetwork)
            gameLimitScore.setVisibility(View.VISIBLE);
        else
            gameLimitScore.setVisibility(View.GONE);
        gameLimitTime.setOnClickListener((v -> {
            gameLimitTime.setChecked(true);
            gameLimitLives.setChecked(false);
            gameLimitScore.setChecked(false);
        }));
        gameLimitLives.setOnClickListener((v -> {
            gameLimitTime.setChecked(false);
            gameLimitLives.setChecked(true);
            gameLimitScore.setChecked(false);
        }));
        gameLimitScore.setOnClickListener((v -> {
            gameLimitTime.setChecked(false);
            gameLimitLives.setChecked(false);
            gameLimitScore.setChecked(true);
        }));

        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton(R.string.ok,
                        (dialog, id) -> {
                            if (gameLimitET.getText().toString().isEmpty()) {
                                dialog.dismiss();
                                return;
                            }
                            int limit;
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
                        })
                .setNegativeButton(R.string.cancel,
                        (dialog, id) -> dialog.cancel());
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    private void setGameLimit() {
        if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0) {
            mGameCountDownTV.setVisibility(View.VISIBLE);
            @SuppressLint("DefaultLocale") String display = String.format("%02d:00", Globals.getInstance().mTimeLimit);
            mGameCountDownTV.setText(display);
            mGameTimer.setVisibility(View.GONE);
        } else {
            mGameCountDownTV.setVisibility(View.GONE);
            mGameTimer.setVisibility(View.VISIBLE);
        }
        TextView eliminationLabel = findViewById(R.id.eliminations_count_label_tv);
        if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_LIVES) != 0) {
            eliminationLabel.setText(R.string.lives_count_label);
            mEliminationCountTV.setText(String.valueOf(Globals.getInstance().mLivesLimit));
            mHasLivesLimit = true;
            mLives = Globals.getInstance().mLivesLimit;
        } else {
            eliminationLabel.setText(R.string.eliminations_count_label);
            mEliminationCountTV.setText(String.valueOf(mEliminationCount));
            mHasLivesLimit = false;
            mLives = 0;
        }
        if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_SCORE) != 0) {
            mScoreLabelTV.setText(getString(R.string.score_limit_label, Globals.getInstance().mScoreLimit));
            mTeamScoreLabelTV.setText(getString(R.string.team_score_limit_label, Globals.getInstance().mScoreLimit));
        } else {
            mScoreLabelTV.setText(R.string.score_label);
            mTeamScoreLabelTV.setText(R.string.team_score_label);
        }
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(PREF_LIMIT_TIME, Globals.getInstance().mTimeLimit);
        editor.putInt(PREF_LIMIT_SCORE, Globals.getInstance().mScoreLimit);
        editor.putInt(PREF_LIMIT_LIVES, Globals.getInstance().mLivesLimit);
        editor.apply();
        if (Globals.getInstance().mOverrideLives) {
            if (Globals.getInstance().mOverrideLivesVal != 0) {
                eliminationLabel.setText(R.string.lives_count_label);
                mEliminationCountTV.setText(String.valueOf(Globals.getInstance().mOverrideLivesVal));
                mHasLivesLimit = true;
                mLives = Globals.getInstance().mOverrideLivesVal;
            } else {
                eliminationLabel.setText(R.string.eliminations_count_label);
                mEliminationCountTV.setText(String.valueOf(mEliminationCount));
                mHasLivesLimit = false;
                mLives = 0;
            }
        }
    }

    private void setReady() { setReady(true); }

    private void setReady(boolean sendPlayerLeft) {
        if (mReady) {
            if (mUseNetwork) {
                mServerIPTV.setVisibility(View.VISIBLE);
                mServerIPTV.setText(R.string.server_status_searching);
            }
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            Globals.getInstance().mOnlyServerSettings = false;
            Globals.getInstance().mUseGPS = false;
            if (sendPlayerLeft) {
                if (mTcpClient != null) {
                    mTcpClient.leaveServer();
                }
            }
            if (!mIsServer) {
                if (mUDPListenerService != null) {
                    mUDPListenerService.stopListen();
                }
                if (mTcpClient != null) {
                    mTcpClient.stopTcpClient();
                }
            }
            mNetworkStatusIV.setVisibility(View.GONE);
            mNetworkPlayerCountTV.setVisibility(View.GONE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mServerIPTV.setVisibility(View.GONE);
            setNetworkMenu(NETWORK_TYPE_ENABLED);
            mPlayerDataButton.setVisibility(View.GONE);
        }
        updatePreGameButtonVisibilities();
        Intent intent = new Intent(NetMsg.NETMSG_GPSSETTING);
        sendBroadcast(intent);
    }

    private void showConnectLayout() {
        RelativeLayout playLayout = findViewById(R.id.play_layout);
        RelativeLayout connectLayout = findViewById(R.id.connect_layout);
        playLayout.setVisibility(View.GONE);
        connectLayout.setVisibility(View.VISIBLE);
        if (mDeviceAddress != null && !mDeviceAddress.isEmpty()) {
            mReconnectButton.setVisibility(View.VISIBLE);
        } else {
            mReconnectButton.setVisibility(View.GONE);
        }
    }

    private void initBatteryQueue() {
        mBatteryAverager.reset();
    }

    private void startGame() {
        dismissScoreboardDialog();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowInsetsControllerCompat windowInsetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars());
        windowInsetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );
        mScore = 0;
        mScoreTV.setText("0");
        mTeamScore = 0;
        mTeamScoreTV.setText("0");
        if (mHasLivesLimit)
            mEliminationCount = mLives;
        else
            mEliminationCount = 0;
        mEliminationCountTV.setText(String.valueOf(mEliminationCount));
        mHealth = Globals.getInstance().mFullHealth;
        mHealthBar.setMax(mHealth);
        mHealthBar.setProgress(mHealth);
        mEliminatedTV.setText(R.string.starting_game_label);
        mStartGameButton.setVisibility(View.GONE);
        mTeamMinusButton.setVisibility(View.INVISIBLE);
        mTeamPlusButton.setVisibility(View.INVISIBLE);
        mGameLimitButton.setVisibility(View.GONE);
        mPlayerSettingsButton.setVisibility(View.GONE);
        Button shuffleTeamsBtn = findViewById(R.id.shuffle_teams_button);
        if (shuffleTeamsBtn != null) {
            shuffleTeamsBtn.setEnabled(false);
            shuffleTeamsBtn.setVisibility(View.GONE);
        }
        if (mUseNetwork) {
            displayInGameNetworkingOptions();
            if (mIsServer) {
                mEndNetworkGameButton.setVisibility(View.VISIBLE);
            } else {
                mEndNetworkGameButton.setVisibility(View.GONE);
            }
            if (mUDPListenerService != null) {
                mUDPListenerService.startGame();
            }
        } else {
            displayAllNetworkingOptions(false);
            mEndGameButton.setVisibility(View.VISIBLE);
        }
        mUseNetworkingButton.setVisibility(View.INVISIBLE);
        mFiringModeButton.setVisibility(View.INVISIBLE);
        mStartGameTimer = true;
        setShotMode(mCurrentShotMode); // make sure that the shot mode is correctly set at the start of each game
        startSpawn("");
    }

    private void confirmEndGame() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setTitle(R.string.end_game_dialog_title);
        builder.setMessage(R.string.end_game_dialog_message);

        builder.setPositiveButton(R.string.yes, (dialog, which) -> {
            if (mUseNetwork) {
                if (Globals.getInstance().mIsAdmin || mIsServer) {
                    if (mTcpClient != null)
                        mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ADMIN_ENDGAME);
                    else if (mIsServer && mTcpServer != null)
                        mTcpServer.endGame();
                } else {
                    mTcpClient.leaveServer();
                    endGame();
                }
            } else {
                endGame();
            }
        });

        builder.setNegativeButton(R.string.no, (dialog, which) -> dialog.dismiss());

        AlertDialog alert = builder.create();
        alert.show();
    }

    private void showScoreboard() {
        openScoreboard();
    }

    private void endGame() {
        boolean wasInNetworkGame = mUseNetwork && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE;
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        hideWeaponDisconnect();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowInsetsControllerCompat windowInsetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars());
        if (mSpawnTimer != null) {
            mSpawnTimer.cancel();
            mSpawnTimer = null;
        }
        if (mRespawnShieldTimer != null) {
            mRespawnShieldTimer.cancel();
            mRespawnShieldTimer = null;
        }
        mIsShielded = false;
        if (mRespawnShieldCountdownTV != null) {
            mRespawnShieldCountdownTV.setVisibility(View.GONE);
        }
        if (mReloadTimer != null) {
            mReloadTimer.cancel();
            mReloadTimer = null;
        }
        mGameTimerRunning = false;
        if (mGameCountdownTimer != null) {
            mGameCountdownTimer.cancel();
            mGameCountdownTimer = null;
        }
        if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0 && mGameCountDownTV != null) {
            @SuppressLint("DefaultLocale") String display = String.format("%02d:00", Globals.getInstance().mTimeLimit);
            mGameCountDownTV.setText(display);
        }
        mEndGameButton.setVisibility(View.GONE);
        mEndNetworkGameButton.setVisibility(View.GONE);
        mHitIV.setVisibility(View.GONE);
        mEliminatedTV.setVisibility(View.INVISIBLE);
        mEliminatedByTV.setVisibility(View.INVISIBLE);
        mSpawnInTV.setVisibility(View.GONE);
        displayAllNetworkingOptions(mUseNetwork);
        mUseNetworkingButton.setVisibility(View.VISIBLE);
        mHitPlayerIV.setVisibility(View.GONE);
        mHitPlayerNameTV.setVisibility(View.GONE);
        mShotsFiredIV.setVisibility(View.GONE);
        startReload(RELOADING_STATE_ELIMINATED);
        mGameTimer.stop();
        mUseNetworkingButton.setVisibility(View.VISIBLE);

        updatePreGameButtonVisibilities();

        if (mReady && !mIsServer && mTcpClient != null && mTcpClient.isDedicatedServer()) {
            setNetworkMenu(NETWORK_TYPE_JOINED);
            mPlayerDataButton.setVisibility(View.VISIBLE);
        } else {
            setNetworkMenu(NETWORK_TYPE_ENABLED);
        }

        if (wasInNetworkGame) {
            showScoreboard();
        }
    }

    private void updatePreGameButtonVisibilities() {
        if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
            return;
        }

        if (mReady) {
            mTeamMinusButton.setVisibility(View.INVISIBLE);
            mTeamPlusButton.setVisibility(View.INVISIBLE);
            mGameLimitButton.setVisibility(View.GONE);
        } else {
            mTeamMinusButton.setVisibility(View.VISIBLE);
            mTeamPlusButton.setVisibility(View.VISIBLE);
            mGameLimitButton.setVisibility(View.VISIBLE);
        }

        boolean isConnectedClient = mUseNetwork && mNetworkMenuType == NETWORK_TYPE_JOINED && !mIsServer;
        boolean isSearchingForServer = mUseNetwork && mNetworkMenuType == NETWORK_TYPE_JOINING && !mIsServer;
        if (Globals.getInstance().mOnlyServerSettings || isConnectedClient) {
            mStartGameButton.setVisibility(View.GONE);
        } else {
            mStartGameButton.setVisibility(View.VISIBLE);
            mStartGameButton.setEnabled(!isSearchingForServer);
        }

        if (Globals.getInstance().mOnlyServerSettings) {
            mFiringModeButton.setVisibility(View.GONE);
            mPlayerSettingsButton.setVisibility(View.GONE);
        } else {
            mFiringModeButton.setVisibility(View.VISIBLE);
            mPlayerSettingsButton.setVisibility(View.VISIBLE);
        }

        Button shuffleTeamsBtn = findViewById(R.id.shuffle_teams_button);
        if (shuffleTeamsBtn != null) {
            shuffleTeamsBtn.setEnabled(true);
            shuffleTeamsBtn.setVisibility(mIsServer ? View.VISIBLE : View.GONE);
        }

        updateAdminButtonVisibility();
    }

    /* Sending to Command a packet with 10 00 80 00 PLAYER# then 15 more 00's sets the player ID.
       This works for initially setting the ID, but does not work consistently afterwards. You can
       consistently change the player ID by doing a reload procedure, but doing a reload without
       having previously set a team does not seem to work. We do a reload cycle before starting the
       game each time to ensure that the player starts on the right team and with full ammo. */
    private void setTeam() {
        if (!TEST_NETWORK) {
            if (Globals.getInstance().mPlayerID >= 1 && Globals.getInstance().mPlayerID <= Globals.MAX_PLAYER_ID) {
                Log.d(TAG, "setting player ID to " + Globals.getInstance().mPlayerID);
                blaster().setTeam(Globals.getInstance().mPlayerID);
            }
        }
        if (sharedPreferences != null) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putInt(PREF_PLAYER_ID, Globals.getInstance().mPlayerID);
            editor.putInt(PREF_SELECTED_PLAYER_ID, Globals.getInstance().mPlayerID);
            editor.putInt(PREF_GAME_MODE, Globals.getInstance().mGameMode);
            editor.apply();
        }
        displayCurrentTeam();
    }

    private void displayCurrentTeam() {
        if ((mUseNetwork || mSelectedPlayerID == 0) && Globals.getInstance().mPlayerID == 0) {
            mTeamLabelTV.setText(R.string.team_label);
            mTeamLabelTV.setBackground(null);
            mTeamLabelTV.setPadding(0, 0, 0, 0);
            mTeamLabelTV.setTextColor(Color.WHITE);
            mTeamTV.setText(R.string.auto_sort_team);
            mTeamScoreLabelTV.setVisibility(View.INVISIBLE);
            mTeamScoreTV.setVisibility(View.INVISIBLE);
        } else {
            if (!mUseNetwork && Globals.getInstance().mPlayerID == 0) {
                Globals.getInstance().mPlayerID = (mSelectedPlayerID != 0) ? mSelectedPlayerID : 1;
            }
            if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                mNetworkTeam = Globals.getInstance().calcNetworkTeam(Globals.getInstance().mPlayerID);
                int startID = Globals.getInstance().getTeamStartID(mNetworkTeam);
                int player = Globals.getInstance().mPlayerID - startID + 1;
                mTeamLabelTV.setText(getString(R.string.team_number_label, mNetworkTeam));

                int teamColor = Globals.getTeamColor(mNetworkTeam);

                if (teamColor != Color.TRANSPARENT) {
                    GradientDrawable drawable = new GradientDrawable();
                    drawable.setColor(teamColor);
                    drawable.setCornerRadius(8f);
                    mTeamLabelTV.setBackground(drawable);
                    mTeamLabelTV.setPadding(24, 12, 24, 12);
                    if (mNetworkTeam == 4) {
                        mTeamLabelTV.setTextColor(Color.BLACK);
                    } else {
                        mTeamLabelTV.setTextColor(Color.WHITE);
                    }
                } else {
                    mTeamLabelTV.setBackground(null);
                    mTeamLabelTV.setPadding(0, 0, 0, 0);
                    mTeamLabelTV.setTextColor(Color.WHITE);
                }

                if (mUseNetwork && mTcpClient != null && mTcpClient.isDedicatedServer()) {
                    String playerName = Globals.getInstance().mPlayerName;
                    if (playerName == null || playerName.isEmpty()) {
                        playerName = getString(R.string.network_team, player);
                    } else {
                        playerName = playerName + " (" + mNetworkTeam + "-" + player + ")";
                    }
                    mTeamTV.setText(playerName);
                } else {
                    mTeamTV.setText(getString(R.string.network_team, player));
                }
                if (mUseNetwork) {
                    mTeamScoreLabelTV.setVisibility(View.VISIBLE);
                    mTeamScoreTV.setVisibility(View.VISIBLE);
                } else {
                    mTeamScoreLabelTV.setVisibility(View.INVISIBLE);
                    mTeamScoreTV.setVisibility(View.INVISIBLE);
                }
            } else {
                mTeamLabelTV.setText(R.string.team_label);
                mTeamLabelTV.setBackground(null);
                mTeamLabelTV.setPadding(0, 0, 0, 0);
                mTeamLabelTV.setTextColor(Color.WHITE);
                String teamStr = "" + Globals.getInstance().mPlayerID;
                mTeamTV.setText(teamStr);
                mTeamScoreLabelTV.setVisibility(View.INVISIBLE);
                mTeamScoreTV.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void startReload() { startReload(RELOADING_STATE_STARTED); }

    /* Initial reload command that tells the tagger not to shoot anymore (or maybe it just sets the
       remaining shot counter to 0). It also sets the tagger in what I guess is status 0x03 instead
       of the usual 0x02. The command format is F0 00 02 00 PLAYER_ID and then 0 filled to the end. */
    private void startReload(int reloadStatus) {
        if (mReloading != RELOADING_STATE_NONE)
            return;
        mReloading = reloadStatus;
        mShotsRemainingTV.setVisibility(View.INVISIBLE);
        mReloadBar.setVisibility(View.VISIBLE);
        blaster().startReload(Globals.getInstance().mPlayerID);
    }

    /* Second stage of the reload commands which tells the tagger how many shots to load and allows
       it to shoot again. Command format is 00 00 04 00 PLAYER_ID 00 SHOT_COUNT and then 0 filled. */
    private void finishReload() {
        if ((mReloading != RELOADING_STATE_STARTED && mReloading != RELOADING_STATE_ELIMINATED) || Globals.getInstance().mGameState != Globals.GAME_STATE_RUNNING)
            return;
        mReloading = RELOADING_STATE_FINISHING;
        mEmptyTriggerCount = 0;
        Log.d(TAG, "Finishing reload");
        setShotsRemaining(Globals.getInstance().mFullReload);
        blaster().finishReload(Globals.getInstance().mPlayerID, Globals.getInstance().mFullReload);
    }

    private void setShotsRemaining(byte shotsRemaining) {
        String shotsRemainingStr = "" + shotsRemaining;
        if (shotsRemaining == 0)
            shotsRemainingStr = "0";
        mShotsRemainingTV.setText(shotsRemainingStr);
        mLastShotCount = shotsRemaining;
    }

    private void startRespawnShield(long shieldTimeInSeconds) {
        if (mRespawnShieldTimer != null) {
            mRespawnShieldTimer.cancel();
            mRespawnShieldTimer = null;
        }
        mIsShielded = true;
        mRespawnShieldCountdownTV.setText(getString(R.string.respawn_shield_label, shieldTimeInSeconds));
        mRespawnShieldCountdownTV.setVisibility(View.VISIBLE);
        mRespawnShieldTimer = new CountDownTimer(shieldTimeInSeconds * 1000, 999) {
            @Override
            public void onTick(long millisUntilFinished) {
                long remainingSec = (millisUntilFinished / 1000) + 1;
                mRespawnShieldCountdownTV.setText(getString(R.string.respawn_shield_label, remainingSec));
            }

            @Override
            public void onFinish() {
                mIsShielded = false;
                mRespawnShieldCountdownTV.setVisibility(View.GONE);
                mRespawnShieldTimer = null;
            }
        }.start();
    }

    private void startSpawn(String eliminatedBy) {
        if (mReloadTimer != null)
            mReloadTimer.cancel();
        if (mRespawnShieldTimer != null) {
            mRespawnShieldTimer.cancel();
            mRespawnShieldTimer = null;
        }
        if (mSpawnTimer != null) {
            mSpawnTimer.cancel();
            mSpawnTimer = null;
        }
        mIsShielded = false;
        if (mRespawnShieldCountdownTV != null) {
            mRespawnShieldCountdownTV.setVisibility(View.GONE);
        }
        Globals.getInstance().mGameState = Globals.GAME_STATE_ELIMINATED;
        startReload(RELOADING_STATE_ELIMINATED);
        mHitIV.setVisibility(View.GONE);
        mEliminatedTV.setVisibility(View.VISIBLE);
        mEliminatedByTV.setText(eliminatedBy);
        mEliminatedByTV.setVisibility(View.VISIBLE);

        if (mHasLivesLimit && mEliminationCount <= 0) {
            mSpawnInTV.setText(R.string.dialog_out_of_lives);
            mSpawnInTV.setVisibility(View.VISIBLE);
            return;
        }

        mSpawnInTV.setText(getResources().getString(R.string.spawn_in_label, Globals.getInstance().mRespawnTime));
        mSpawnInTV.setVisibility(View.VISIBLE);
        mSpawnTimer = new CountDownTimer(Globals.getInstance().mRespawnTime * 1000, 999) {

            public void onTick(long millisUntilFinished) {
                mSpawnInTV.setText(getResources().getString(R.string.spawn_in_label, (millisUntilFinished / 1000)));
                playSound(R.raw.beep, getApplicationContext());
            }

            public void onFinish() {
                Log.d(TAG, "spawned!");
                mEliminatedTV.setVisibility(View.INVISIBLE);
                mEliminatedTV.setText(R.string.eliminated_label);
                mEliminatedByTV.setVisibility(View.INVISIBLE);
                mSpawnInTV.setVisibility(View.GONE);
                mHealth = Globals.getInstance().mFullHealth;
                mHealthBar.setProgress(mHealth);
                Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
                playSound(R.raw.spawn, getApplicationContext());
                finishReload();
                if (mStartGameTimer) {
                    mStartGameTimer = false;
                    if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_TIME) != 0) {
                        if (!mGameTimerRunning)
                            startGameCountdown();
                    } else {
                        mGameTimer.setBase(SystemClock.elapsedRealtime());
                        mGameTimer.start();
                    }
                } else {
                    long shieldTime = Globals.getInstance().mRespawnShieldTime;
                    if (shieldTime > 0) {
                        startRespawnShield(shieldTime);
                    }
                }
            }
        }.start();
    }

    private void startGameCountdown() {
        startGameCountdown(Globals.getInstance().mTimeLimit * 60L);
    }

    private void startGameCountdown(long timeInSeconds) {
        mGameCountdownTimer = new CountDownTimer(timeInSeconds * 1000, 1000) {

            public void onTick(long millisUntilFinished) {
                @SuppressLint("DefaultLocale") String display = String.format("%02d:%02d",
                        TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished),
                        TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) - TimeUnit.MINUTES.toSeconds(
                                TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished)));
                mGameCountDownTV.setText(display);
            }

            public void onFinish() {
                Log.d(TAG, "Game time ended!");
                Toast.makeText(getApplicationContext(), getString(R.string.dialog_game_time_expired), Toast.LENGTH_SHORT).show();
                playSound(R.raw.eliminated, getApplicationContext());
                if (mUseNetwork) {
                    if (mIsServer && mTcpServer != null) {
                        mTcpServer.endGame();
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
                            endGame();
                        }
                    }, 3000);
                } else {
                    endGame();
                }
            }
        }.start();
    }

    // Config 00 00 09 xx yy ff c8 ff ff 80 01 34 - xx is the number of shots and if you set yy to 01 for full auto for xx shots or 00 for single shot mode, increasing yy decreases RoF
    // setting 03 03 for shots and RoF gives a good 3 shot burst, 03 01 is so fast that you feel 1 recoil for 3 shots
    private void setShotMode(int shotMode) {
        if (shotMode == Globals.SHOT_MODE_SINGLE) {
            mCurrentShotMode = Globals.SHOT_MODE_SINGLE;
            mShotModeTV.setText(R.string.shot_mode_single);
        } else if (shotMode == Globals.SHOT_MODE_BURST) {
            mCurrentShotMode = Globals.SHOT_MODE_BURST;
            mShotModeTV.setText(R.string.shot_mode_burst3);
        } else if (shotMode == Globals.SHOT_MODE_FULL_AUTO) {
            mCurrentShotMode = Globals.SHOT_MODE_FULL_AUTO;
            mShotModeTV.setText(R.string.shot_mode_auto);
        }
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(PREF_SHOT_MODE, mCurrentShotMode);
        editor.apply();
        blaster().setShotMode(shotMode, Globals.getInstance().mCurrentFiringMode);
    }

    // Config 10 00 02 02 ff and 15 sets of 00 disables recoil
    // Config 10 00 02 03 ff and 15 sets of 00 enables recoil
    private void setRecoil(boolean enabled) {
        if (enabled) {
            if (mRecoilModeTV != null) mRecoilModeTV.setText(R.string.recoil_enabled);
        } else {
            if (mRecoilModeTV != null) mRecoilModeTV.setText(R.string.recoil_disabled);
        }
        mRecoilEnabled = enabled;
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PREF_RECOIL_ENABLED, mRecoilEnabled);
        editor.apply();
        blaster().setRecoil(enabled);
    }

    private void applyCurrentRecoilSetting() {
        setRecoil(RecoilPolicy.effective(Globals.getInstance().mServerRecoilSetting, mRecoilEnabled));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putByte("mSelectedPlayerID", mSelectedPlayerID);
        outState.putString("mDeviceAddress", mDeviceAddress);
        outState.putBoolean("mUseNetwork", mUseNetwork);
        outState.putBoolean("mIsServer", mIsServer);
        outState.putInt("mNetworkMenuType", mNetworkMenuType);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sharedPreferences == null)
            sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (mSelectedPlayerID == 0) {
            mSelectedPlayerID = (byte) sharedPreferences.getInt(PREF_SELECTED_PLAYER_ID, 0);
            if (mSelectedPlayerID == 0) {
                mSelectedPlayerID = (byte) sharedPreferences.getInt(PREF_PLAYER_ID, 0);
            }
        }
        if (mSelectedPlayerID != 0 && Globals.getInstance().mPlayerID == 0) {
            Globals.getInstance().mPlayerID = mSelectedPlayerID;
        }
        String savedName = sharedPreferences.getString(PREF_PLAYER_NAME, "");
        if (!savedName.isEmpty()) {
            Globals.getInstance().mPlayerName = savedName;
        }
        if (mPlayerNameTV != null && Globals.getInstance().mPlayerName != null) {
            mPlayerNameTV.setText(Globals.getInstance().mPlayerName);
        }
        displayCurrentTeam();
        updatePlayerSettings();

        ble().reconnect(mDeviceAddress);
        ContextCompat.registerReceiver(this, mUDPUpdateReceiver, makeUDPUpdateIntentFilter(), ContextCompat.RECEIVER_NOT_EXPORTED);
        setupUDPServiceConnection();
        setupTcpClientServiceConnection();
        setupTcpServerServiceConnection();
        if (mUseNetwork && mTcpClient != null) {
            mTcpClient.sendPlayerInfo(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mUDPUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        dismissScoreboardDialog();
        ble().reset();
        if (mUDPServiceConnection != null)
            try {unbindService(mUDPServiceConnection);} catch (Exception e) {/* nothing */}
        if (mTcpClientServiceConnection != null)
            try {unbindService(mTcpClientServiceConnection);} catch (Exception e) {/* nothing */}
        if (mTcpServerServiceConnection != null)
            try {unbindService(mTcpServerServiceConnection);} catch (Exception e) {/* nothing */}
        super.onDestroy();
    }

    @Override
    public void sendBroadcast(Intent intent) {
        if (intent != null && intent.getPackage() == null) {
            intent.setPackage(getPackageName());
        }
        super.sendBroadcast(intent);
    }

    private void loadFragment() {
        if (mMapFragment == null) return;
        mMapFragment = getSupportFragmentManager().findFragmentById(R.id.map_fragment);
        if (mMapFragment != null)
            return;
        FragmentTransaction mFragmentTransc = mFragmentMgr.beginTransaction();
        RelativeLayout parentLayout = findViewById(R.id.play_layout);
        mFragmentTransc.add(parentLayout.getId(), mMapFragment);
        //mFragmentTransc.addToBackStack(null);
        mFragmentTransc.commit();
    }


    private void startQRScan() {
        try {
            ScanOptions options = new ScanOptions();
            options.setPrompt(getString(R.string.scan_qr_prompt));
            options.setOrientationLocked(false);
            options.setBeepEnabled(true);
            options.setCaptureActivity(CaptureActivity.class);
            barcodeLauncher.launch(options);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch built-in QR scanner: " + e.getMessage());
            Toast.makeText(this, "Could not launch QR scanner", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE_LOCATION_PERMISSIONS:
                boolean allGranted = true;
                if (grantResults.length > 0) {
                    for (int result : grantResults) {
                        if (result != PackageManager.PERMISSION_GRANTED) {
                            allGranted = false;
                            break;
                        }
                    }
                } else {
                    allGranted = false;
                }
                if (allGranted) {
                    // Permissions Granted
                    connectWeapon();
                    Intent intent = new Intent(NetMsg.NETMSG_GPSSETTING);
                    sendBroadcast(intent);
                } else {
                    // Permission Denied
                    Toast.makeText(this, getString(R.string.error_location_permission_required), Toast.LENGTH_SHORT)
                            .show();
                }
                break;
            case REQUEST_CODE_CAMERA_PERMISSION:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startQRScan();
                } else {
                    Toast.makeText(this, "Camera permission is required to scan QR codes", Toast.LENGTH_SHORT).show();
                }
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void handleConnectionEvent(BleConnectionEvent event) {
        if (event instanceof BleConnectionEvent.ScanMatched) {
            TextView connectStatusTV = findViewById(R.id.connect_status_tv);
            if (connectStatusTV != null) {
                connectStatusTV.setText(R.string.connect_status_connecting);
            }
            mDeviceAddress = ((BleConnectionEvent.ScanMatched) event).getAddress();
            setDeviceAddress(mDeviceAddress);
        } else if (event instanceof BleConnectionEvent.ScanTimedOut) {
            handleDisconnect();
        } else if (event instanceof BleConnectionEvent.InitializeFailed) {
            finish();
        }
    }

    // Auto-reconnect when Bluetooth is turned on (only while resumed, as before).
    private void handleAdapterEvent(BleConnectionEvent event) {
        if (event instanceof BleConnectionEvent.AdapterTurnedOn)
            connectWeapon();
    }

    private void setPlayerName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        name = name.trim();
        if (name.equalsIgnoreCase("Player") || ProfanityFilter.containsProfanity(name)) {
            if (ProfanityFilter.containsProfanity(name)) {
                Toast.makeText(getApplicationContext(), "Please choose an appropriate player name!", Toast.LENGTH_SHORT).show();
            }
            name = NameGenerator.getRandomName();
        }
        Globals.getInstance().mPlayerName = name;
        if (sharedPreferences != null) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(PREF_PLAYER_NAME, name);
            editor.apply();
        }
        if (mPlayerNameTV != null) {
            mPlayerNameTV.setText(name);
        }
        displayCurrentTeam();
        if (mReady && mTcpClient != null) {
            mTcpClient.sendPlayerNameChange();
        } else {
            displayAllNetworkingOptions(true);
            setNetworkMenu(NETWORK_TYPE_ENABLED);
        }
    }

    private void processQrResult(String rawQr) {
        QrPairingPayload payload = QrPairingPayload.parse(rawQr);
        if (payload != null) {
            String macAddress = payload.getMacAddress();
            String playerName = payload.getPlayerName();
            setDeviceAddress(macAddress);
            Log.d(TAG, "Got QR Code MAC: " + mDeviceAddress);
            if (!playerName.isEmpty()) {
                Log.d(TAG, "Got QR Code Player Name: " + playerName);
                setPlayerName(playerName);
            }
            connectWeapon();
        } else {
            Log.e(TAG, "Did not get any good QR result");
        }
    }

    // onActivityResult replaced with ActivityResultLauncher

    private void connectWeapon() {
        if (TEST_NETWORK) {
            RelativeLayout connectLayout = findViewById(R.id.connect_layout);
            if (connectLayout != null) connectLayout.setVisibility(View.GONE);
            RelativeLayout playLayout = findViewById(R.id.play_layout);
            if (playLayout != null) playLayout.setVisibility(View.VISIBLE);
            return;
        }

        if (ble().isScanning())
            return;

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Request Bluetooth & Location permissions dynamically based on Android version
        List<String> permissionsToRequest = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), REQUEST_CODE_LOCATION_PERMISSIONS);
            return;
        }

        // Initializes a Bluetooth manager and adapter.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            Log.e(TAG, "Failed to get Bluetooth service");
            Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        // Checks if Bluetooth is supported on the device.
        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Bluetooth is off");
            try {
                Intent intentBtEnabled = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                enableBtLauncher.launch(intentBtEnabled);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException enabling bluetooth: " + e.getMessage());
            }
            return;
        }

        // Scans and starts the 30 s give-up.
        if (ble().startScan(bluetoothAdapter, mDeviceAddress) == BleConnectionManager.ScanStart.PERMISSION_DENIED) {
            Toast.makeText(this, R.string.error_location_permission_required, Toast.LENGTH_SHORT).show();
            return;
        }

        mConnectButton.setEnabled(false);
        mReconnectButton.setEnabled(false);
        mDedicatedServerButton.setEnabled(false);
        mQRConnectButton.setEnabled(false);
        TextView connectStatusTV = findViewById(R.id.connect_status_tv);
        if (connectStatusTV != null) {
            connectStatusTV.setText(R.string.connect_status_scanning);
        }
    }


    private void handleDisconnect() {
        Log.d(TAG, "handleDisconnect called. mHasConnectedToBlaster=" + mHasConnectedToBlaster + ", mGameState=" + Globals.getInstance().mGameState);
        ble().cancelScanTimeout();
        ble().clearScanning();
        if (mHasConnectedToBlaster || Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
            Log.e(TAG, "blaster disconnected, attempt to reconnect");
            connectWeapon();
            showWeaponDisconnect();
            return;
        }
        ble().reset();
        showConnectLayout();
        mConnectButton.setEnabled(true);
        mReconnectButton.setEnabled(true);
        mDedicatedServerButton.setEnabled(true);
        mQRConnectButton.setEnabled(true);
        TextView connectStatusTV = findViewById(R.id.connect_status_tv);
        if (connectStatusTV != null) connectStatusTV.setText(R.string.connect_status_not_connected);
        mLastShotCount = 0;
        endGame();
        initBatteryQueue();
        playSound(R.raw.eliminated, getApplicationContext());
    }

    private AlertDialog mBlasterDisconnectDialog = null;

    private void showWeaponDisconnect() {
        if (mBlasterDisconnectDialog != null)
            return;
        LayoutInflater li = LayoutInflater.from(getApplicationContext());
        View view = li.inflate(R.layout.blaster_disconnected_dialog, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        alertDialogBuilder.setView(view);

        alertDialogBuilder
                .setCancelable(false)
                .setNegativeButton(R.string.blaster_disconnected_disconnect_quit,
                        (dialog, id) -> {
                            Log.d(TAG, "Disconnect & Quit clicked");
                            mHasConnectedToBlaster = false;
                            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
                            ble().cancelScanTimeout();
                            ble().stopScan();
                            stopServerSearch(true);
                            endGame();
                            handleDisconnect();
                            dialog.cancel();
                            mBlasterDisconnectDialog = null;
                        });
        mBlasterDisconnectDialog = alertDialogBuilder.create();
        mBlasterDisconnectDialog.show();
    }

    private void hideWeaponDisconnect() {
        if (mBlasterDisconnectDialog != null) {
            mBlasterDisconnectDialog.dismiss();
            mBlasterDisconnectDialog = null;
            new Handler(Looper.getMainLooper()).postDelayed(this::applyCurrentRecoilSetting, 600);
        }
    }

    // Handles the blaster's GATT events, in order.
    private void handleBlasterEvent(BlasterEvent event) {
        if (event instanceof BlasterEvent.Connected) {
            // BleConnectionManager marks itself connected on the same event.
            mCommunicating = false; // When we receive the first packet, we'll switch layouts
            startConnectionTest();
        } else if (event instanceof BlasterEvent.Disconnected) {
            handleDisconnect();
        } else if (event instanceof BlasterEvent.ServicesDiscovered) {
            // BlasterManager has already discovered the characteristics.
            TextView connectStatusTV = findViewById(R.id.connect_status_tv);
            if (connectStatusTV != null) connectStatusTV.setText(R.string.connect_status_communicating);
            if (Globals.getInstance().mGameState == Globals.GAME_STATE_NONE)
                playSound(R.raw.spawn, getApplicationContext());
            else {
                if (Globals.getInstance().mGameState == Globals.GAME_STATE_RUNNING)
                    startReload();
            }
            hideWeaponDisconnect();
        } else if (event instanceof BlasterEvent.Telemetry) {
            processTelemetryData(((BlasterEvent.Telemetry) event).getData());
        } else if (event instanceof BlasterEvent.TypeDiscovered) {
            mBlasterType = ((BlasterEvent.TypeDiscovered) event).getBlasterType();
            if (mBlasterType == BLASTER_TYPE_RIFLE) {
                Toast.makeText(getApplicationContext(), getString(R.string.rifle_detected_toast, mDeviceAddress), Toast.LENGTH_LONG).show();
            } else {
                // We'll automatically assume that this is a pistol
                Toast.makeText(getApplicationContext(), getString(R.string.pistol_detected_toast, mDeviceAddress), Toast.LENGTH_LONG).show();
            }
        } else if (event instanceof BlasterEvent.WriteFinished) {
            if (mReloading == RELOADING_STATE_STARTED) {
                // Using a timer here so we can cancel it if the player is eliminated while waiting to reload
                mReloadTimer = new CountDownTimer(Globals.getInstance().mReloadTime, Globals.getInstance().mReloadTime) {
                    public void onTick(long millisUntilFinished) { /* do nothing */ }
                    public void onFinish() {
                        finishReload();
                    }
                }.start();
            } else if (mReloading == RELOADING_STATE_FINISHING) {
                mReloading = RELOADING_STATE_NONE;
                mReloadBar.setVisibility(View.INVISIBLE);
                mShotsRemainingTV.setVisibility(View.VISIBLE);
                if (!Globals.getInstance().mReloadOnEmpty)
                    playSound(R.raw.reload, getApplicationContext());
                Log.d(TAG, "Reload finished");
            }
        }
    }

    private void startConnectionTest() {
        // This is a looping handler that checks to see if the tagger has disconnected
        mCommunicating = false;
        connectionTestHandler = new Handler(Looper.getMainLooper());
        connectionTestHandler.postDelayed(new Runnable(){
            public void run(){
                if (!ble().isConnected())
                    return;
                Log.d(TAG, "Connection test check: mCommunicating=" + mCommunicating + ", mConnectionTest=" + mConnectionTest + ", mConnected=" + ble().isConnected());
                if (!mCommunicating) {
                    connectionTestHandler.postDelayed(this, CONNECTION_TEST_INTERVAL_MILLISECONDS);
                    return;
                }
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

    @SuppressWarnings("deprecation")
    private void vibrate() {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(FullscreenActivity.HIT_VIBRATE_DURATION_MILLISECONDS, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(FullscreenActivity.HIT_VIBRATE_DURATION_MILLISECONDS);
        }
    }

    public void playSound(int resId, Context ctx) {
        MediaPlayer mp = MediaPlayer.create(ctx, resId);
        mp.start();
        mp.setOnCompletionListener(MediaPlayer::release);
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
    private void applyGrenadePairing(int hitBy, byte shotIdByte) {
        GrenadeDecoder.PairingAction action = GrenadeDecoder.pairingAction(hitBy, shotIdByte);
        if (action == GrenadeDecoder.PairingAction.PAIR) {
            // This is a grenade trying to pair
            if (Globals.getInstance().mPairedGrenadeID == 0) {
                Globals.getInstance().mPairedGrenadeID = (byte) GrenadeDecoder.grenadeId(shotIdByte);
                if (mUseNetwork && mTcpClient.isDedicatedServer())
                    mTcpClient.sendPlayerGrenade();
                Toast.makeText(getApplicationContext(), getString(R.string.grenade_paired_toast), Toast.LENGTH_SHORT).show();
            }
        } else if (action == GrenadeDecoder.PairingAction.UNPAIR) {
            Globals.getInstance().mPairedGrenadeID = 0;
        }
    }

    private void processTelemetryData(GattProtocolHandler.TelemetryData telemetry) {
        byte player_id = telemetry.playerId;
        byte shotsRemaining = telemetry.shotsRemaining;
        // Unsigned (0-255): hit-by ids above 127, e.g. grenades (167), are negative bytes on the wire.
        int hit_by_player1 = telemetry.hitByPlayer1;
        // Often, hit_by_player2 is the same player ID as player1
        int hit_by_player2 = telemetry.hitByPlayer2;
        byte trigger_counter = telemetry.triggerCounter;
        byte reload_counter = telemetry.reloadCounter;
        byte thumb_counter = telemetry.thumbCounter;
        byte power_counter = telemetry.powerCounter;
        mConnectionTest = true;
        if (!mCommunicating) {
            mCommunicating = true;
            mHasConnectedToBlaster = true;
            RelativeLayout connectLayout = findViewById(R.id.connect_layout);
            if (connectLayout != null) connectLayout.setVisibility(View.GONE);
            RelativeLayout playLayout = findViewById(R.id.play_layout);
            if (playLayout != null) playLayout.setVisibility(View.VISIBLE);
            // Reset last counters when we start a new connection
            mLastTriggerCount = trigger_counter;
            mLastReloadButtonCount = reload_counter;
            mLastThumbButtonCount = thumb_counter;
            mLastPowerButtonCount = power_counter;
            setShotMode(mCurrentShotMode);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (Globals.getInstance().mPlayerID != 0)
                    setTeam();
            }, 250);
            new Handler(Looper.getMainLooper()).postDelayed(this::applyCurrentRecoilSetting, 500);
            autoStartServerScan();
        }
        if (trigger_counter != mLastTriggerCount) {
            mLastTriggerCount = trigger_counter;
            if (shotsRemaining == 0 || Globals.getInstance().mGameState != Globals.GAME_STATE_RUNNING) {
                playSound(R.raw.empty, getApplicationContext());
                mEmptyTriggerCount++;
                if (mEmptyTriggerCount >= MAX_EMPTY_TRIGGER_PULLS && Globals.getInstance().mGameState == Globals.GAME_STATE_RUNNING)
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
            if (shotsRemaining != Globals.getInstance().mFullReload)
                startReload();
        }
        if (thumb_counter != mLastThumbButtonCount) {
            mLastThumbButtonCount = thumb_counter;
            Integer nextShotMode = ShotModePolicy.nextOnThumb(mCurrentShotMode, Globals.getInstance().mAllowSingleShotMode,
                    Globals.getInstance().mAllowBurst3ShotMode, Globals.getInstance().mAllowAutoShotMode);
            if (nextShotMode != null)
                setShotMode(nextShotMode);
        }
        if (power_counter != mLastPowerButtonCount) {
            mLastPowerButtonCount = power_counter;
            RecoilPolicy.PowerButton recoil = RecoilPolicy.onPowerButton(Globals.getInstance().mServerRecoilSetting, mRecoilEnabled);
            if (recoil.getForced()) {
                setRecoil(recoil.getEnabled());
                Toast.makeText(getApplicationContext(), recoil.getEnabled() ? "Recoil is forced ENABLED by server" : "Recoil is forced DISABLED by server", Toast.LENGTH_SHORT).show();
            } else {
                mRecoilEnabled = recoil.getEnabled();
                setRecoil(mRecoilEnabled);
            }
        }
        byte battery_level = telemetry.batteryLevel;
        long averageBattery = mBatteryAverager.add(battery_level);
        if (mBatteryLevelIV != null) {
            switch (BatteryLevelPolicy.level(averageBattery, mBlasterType)) {
                case FULL:
                    mBatteryLevelIV.setImageResource(R.drawable.ic_battery_full_green_24dp);
                    break;
                case HIGH:
                    mBatteryLevelIV.setImageResource(R.drawable.ic_battery_80_blue_24dp);
                    break;
                case MEDIUM:
                    mBatteryLevelIV.setImageResource(R.drawable.ic_battery_50_yellow_24dp);
                    break;
                default:
                    mBatteryLevelIV.setImageResource(R.drawable.ic_battery_alert_red_24dp);
            }
        }
        if (hit_by_player1 != 0) {
            applyGrenadePairing(hit_by_player1, telemetry.rawShotIdByte1);
            applyGrenadePairing(hit_by_player2, telemetry.rawShotIdByte2);
        }
        HitResolver.Result hit = mHitResolver.resolve(hit_by_player1, telemetry.rawShotIdByte1,
                hit_by_player2, telemetry.rawShotIdByte2,
                new HitResolver.Context(Globals.getInstance().mGameState != Globals.GAME_STATE_NONE, mIsShielded,
                        mUseNetwork, Globals.getInstance().mGameMode == Globals.GAME_MODE_FFA, mNetworkTeam, mHealth),
                mHitLookups);
        if (hit.getCountedHit()) {
            mHitsTaken++;
            String hitsTaken = "" + mHitsTaken;
            mHitsTakenTV.setText(hitsTaken);
        }
        for (HitResolver.Message message : hit.getMessages()) {
            if (mTcpClient != null)
                mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + (message.getOut() ? NetMsg.NETMSG_OUT : NetMsg.NETMSG_HIT) + message.getPlayerId());
        }
        int healthRemoved = hit.getHealthRemoved();
        byte hit_by_id = hit.getHitById();
        if (healthRemoved != 0) {
            if (Globals.getInstance().mGameState == Globals.GAME_STATE_RUNNING) {
                mHealth += healthRemoved;
                mHealthBar.setProgress(mHealth);
                if (mHealth > 0) {
                    if (mHitIV != null && mHitAnimation == null) {
                        // Show the "you're being hit" animation
                        mHitIV.setVisibility(View.VISIBLE);
                        mHitIV.setBackgroundResource(R.drawable.hit_animation);
                        vibrate();
                        playSound(R.raw.hit, getApplicationContext());
                        Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
                        mHitIV.startAnimation(animationFadeOut);
                        mHitAnimation = (AnimationDrawable) mHitIV.getBackground();
                        mHitAnimation.start();
                        mEliminatedByTV.setVisibility(View.VISIBLE);
                        if (hit_by_id == 0) {
                            mEliminatedByTV.setText("");
                        } else {
                            mEliminatedByTV.setText(Globals.getInstance().getPlayerName(hit_by_id));
                        }
                        mEliminatedByTV.startAnimation(animationFadeOut);
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            mHitIV.setVisibility(View.GONE);
                            mHitAnimation.stop();
                            mHitAnimation = null;
                            mEliminatedByTV.setVisibility(View.INVISIBLE);
                        }, HIT_ANIMATION_DURATION_MILLISECONDS);
                    }
                } else {
                    vibrate();
                    playSound(R.raw.eliminated, getApplicationContext());
                    if (mHasLivesLimit)
                        mEliminationCount--;
                    else
                        mEliminationCount++;
                    String elimStr = "" + mEliminationCount;
                    mEliminationCountTV.setText(elimStr);
                    if (mIsServer && mTcpServer != null) {
                        mTcpServer.setHostScore(mScore, mEliminationCount);
                    }
                    String eliminatedBy = "";
                    if (mUseNetwork)
                        eliminatedBy = Globals.getInstance().getPlayerName(hit_by_id);
                    startSpawn(eliminatedBy);
                    if (mUseNetwork) {
                        if (hit_by_id != Globals.getInstance().mPlayerID && Globals.getInstance().calcNetworkTeam(hit_by_id) != Globals.getInstance().calcNetworkTeam(Globals.getInstance().mPlayerID)) {
                            if (mTcpClient != null)
                                mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ELIMINATED + hit_by_id, true);
                        }
                    }
                    if (mHasLivesLimit && mEliminationCount <= 0) {
                        Toast.makeText(getApplicationContext(), getString(R.string.dialog_out_of_lives), Toast.LENGTH_LONG).show();
                    }
                }
            }
        }
        /* Since setting player ID is somewhat unreliable, we use this to make sure that we are
           displaying the actual ID that the tagger is currently using. */
        if (mLastTeam != player_id) {
            Log.d(TAG, "Player ID changed to " + player_id);
            mLastTeam = player_id;
            if (player_id != 0) {
                Globals.getInstance().mPlayerID = player_id;
                displayCurrentTeam();
            } else if (Globals.getInstance().mPlayerID != 0) {
                setTeam();
            }
        }
        /* We monitor the last shot count rather than trigger pulls here so we know when to
           update the displayed shots remaining and play shooting sounds. pew! pew! */
        if (mLastShotCount != shotsRemaining) {
            setShotsRemaining(shotsRemaining);
            if (shotsRemaining != Globals.getInstance().mFullReload && mReloading == RELOADING_STATE_NONE) {
                playSound(R.raw.shootingshort, getApplicationContext());
                if (mUseNetwork && mLastShotFired < System.currentTimeMillis()) {
                    mLastShotFired = System.currentTimeMillis() + HIT_ANIMATION_DURATION_MILLISECONDS;
                    if (mTcpClient != null)
                        mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_SHOTFIRED);
                }
                if (Globals.getInstance().mReloadOnEmpty && shotsRemaining == 0 && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE)
                    startReload();
            }
        }
        // Raw packets: see ProtocolTrace (adb logcat -s SCProto:D)
    }

    private final BroadcastReceiver mUDPUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (NetMsg.NETMSG_SHOTFIRED.equals(action)) {
                // Play a sound?
                mLastShotFired = System.currentTimeMillis() + HIT_ANIMATION_DURATION_MILLISECONDS; // Keeps us from spamming shots fired messages
                if (mShotsFiredIV != null && mShotsFiredIV.getVisibility() != View.VISIBLE) {
                    // Show the "shots fired" image
                    mShotsFiredIV.setVisibility(View.VISIBLE);
                    Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
                    mShotsFiredIV.startAnimation(animationFadeOut);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> mShotsFiredIV.setVisibility(View.GONE), HIT_ANIMATION_DURATION_MILLISECONDS);
                }
            } else if (NetMsg.NETMSG_HIT.equals(action)) {
                // Play a sound?
                if (mHitPlayerIV != null && mHitPlayerAnimation == null) {
                    // Show the "you hit a player" animation
                    mHitPlayerIV.setVisibility(View.VISIBLE);
                    mHitPlayerIV.setBackgroundResource(R.drawable.hit_other_player_animation);
                    Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
                    mHitPlayerIV.startAnimation(animationFadeOut);
                    mHitPlayerAnimation = (AnimationDrawable) mHitPlayerIV.getBackground();
                    mHitPlayerAnimation.start();
                    mHitPlayerNameTV.setVisibility(View.VISIBLE);
                    byte hitPlayerID = intent.getByteExtra(UDPListenerService.INTENT_PLAYERID, (byte)0);
                    if (hitPlayerID == 0) {
                        mHitPlayerNameTV.setText("");
                    } else {
                        mHitPlayerNameTV.setText(Globals.getInstance().getPlayerName(hitPlayerID));
                    }
                    mHitPlayerNameTV.startAnimation(animationFadeOut);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        mHitPlayerIV.setVisibility(View.GONE);
                        mHitPlayerAnimation.stop();
                        mHitPlayerAnimation = null;
                        mHitPlayerNameTV.setVisibility(View.GONE);
                    }, HIT_ANIMATION_DURATION_MILLISECONDS);
                }
            } else if (NetMsg.NETMSG_OUT.equals(action)) {
                // Play a sound?
                if (mHitPlayerIV != null && mHitPlayerAnimation == null) {
                    // Show the "you hit a player that's already out" animation
                    mHitPlayerIV.setVisibility(View.VISIBLE);
                    mHitPlayerIV.setBackgroundResource(R.drawable.hit_other_player_out_animation);
                    Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
                    mHitPlayerIV.startAnimation(animationFadeOut);
                    mHitPlayerAnimation = (AnimationDrawable) mHitPlayerIV.getBackground();
                    mHitPlayerAnimation.start();
                    mHitPlayerNameTV.setVisibility(View.VISIBLE);
                    byte hitPlayerID = intent.getByteExtra(UDPListenerService.INTENT_PLAYERID, (byte)0);
                    if (hitPlayerID == 0) {
                        mHitPlayerNameTV.setText("");
                    } else {
                        mHitPlayerNameTV.setText(Globals.getInstance().getPlayerName(hitPlayerID));
                    }
                    mHitPlayerNameTV.startAnimation(animationFadeOut);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        mHitPlayerIV.setVisibility(View.GONE);
                        mHitPlayerAnimation.stop();
                        mHitPlayerAnimation = null;
                        mHitPlayerNameTV.setVisibility(View.GONE);
                    }, HIT_ANIMATION_DURATION_MILLISECONDS);
                }
            } else if (NetMsg.NETMSG_ELIMINATED.equals(action)) {
                // Increase score
                mScore++;
                String score = Integer.valueOf(mScore).toString();
                mScoreTV.setText(score);
                if (mIsServer && mTcpServer != null) {
                    mTcpServer.setHostScore(mScore, mEliminationCount);
                }
                mScoreIncreaseIV.setVisibility(View.VISIBLE);
                Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.eliminatedfadeout);
                mScoreIncreaseIV.startAnimation(animationFadeOut);
                mScoreIncreasePlayerNameTV.setVisibility(View.VISIBLE);
                byte hitPlayerID = intent.getByteExtra(UDPListenerService.INTENT_PLAYERID, (byte)0);
                if (hitPlayerID == 0) {
                    mScoreIncreasePlayerNameTV.setText("");
                } else {
                    mScoreIncreasePlayerNameTV.setText(Globals.getInstance().getPlayerName(hitPlayerID));
                }
                mScoreIncreasePlayerNameTV.startAnimation(animationFadeOut);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    mScoreIncreaseIV.setVisibility(View.GONE);
                    mScoreIncreasePlayerNameTV.setVisibility(View.GONE);
                }, ELIMINATED_ANIMATION_DURATION_MILLISECONDS);
                playSound(R.raw.score, getApplicationContext());
                if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                    mTeamScore++;
                    score = "" + mTeamScore;
                    mTeamScoreTV.setText(score);
                    if (mTcpClient != null) {
                        mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_TEAMELIMINATED);
                    }
                }
                if (!mUseNetwork && (Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_SCORE) != 0) {
                    int currentScore = (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) ? mTeamScore : mScore;
                    if (currentScore >= Globals.getInstance().mScoreLimit) {
                        endGame();
                    }
                }
            } else if (NetMsg.NETMSG_TEAMELIMINATED.equals(action)) {
                // Increase team score in team games
                mTeamScore++;
                String score = "" + mTeamScore;
                mTeamScoreTV.setText(score);
                if (!mUseNetwork && (Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_SCORE) != 0 && mTeamScore >= Globals.getInstance().mScoreLimit) {
                    endGame();
                }
            } else if (NetMsg.NETMSG_JOIN.equals(action) || NetMsg.NETMSG_LEAVE.equals(action)) {
                mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, Globals.getPlayerCount()));
                mNetworkPlayerCountTV.setVisibility(View.VISIBLE);
                if (Globals.getInstance().mGameMode == Globals.GAME_MODE_FFA && Globals.getInstance().mGameState != Globals.GAME_STATE_NONE && NetMsg.NETMSG_LEAVE.equals(action) && Globals.getPlayerCount() <= 1)
                    endGame(); // Everyone else is out so game is over - this only works in FFA because we don't keep track of who and how many people are on each team
            } else if (NetMsg.NETMSG_LISTPLAYERS.equals(action)) {
                if (mReady) {
                    mUDPListenerService.endScanning();
                    mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, Globals.getPlayerCount()));
                    mNetworkPlayerCountTV.setVisibility(View.VISIBLE);
                    if (Globals.getInstance().mGameMode == Globals.GAME_MODE_2TEAMS) {
                        mGameModeTV.setText(R.string.game_mode_2teams);
                    } else if (Globals.getInstance().mGameMode == Globals.GAME_MODE_4TEAMS) {
                        mGameModeTV.setText(R.string.game_mode_4teams);
                    } else {
                        mGameModeTV.setText(R.string.game_mode_ffa);
                    }
                    setTeam();
                    if (!mIsServer && mReady && mUseNetwork && Globals.getInstance().mGameState == Globals.GAME_STATE_NONE) {
                        mReady = true;
                        setReady();
                        setGameLimit();
                        setNetworkMenu(NETWORK_TYPE_JOINED);
                        mStartGameButton.setVisibility(View.GONE);
                        String ip = Globals.getInstance().mServerIP != null ? Globals.getInstance().mServerIP.toString() : "";
                        if (ip.startsWith("/")) ip = ip.substring(1);
                        mServerIPTV.setText(getString(R.string.server_status_connected_to, ip));
                        mServerIPTV.setVisibility(View.VISIBLE);
                        if (mTcpClient.isDedicatedServer()) {
                            mPlayerDataButton.setVisibility(View.VISIBLE);
                            mNetworkStatusIV.setVisibility(View.VISIBLE);
                            mNetworkStatusIV.setImageResource(R.drawable.ic_network_connected_24dp);
                        }
                    }
                    if (intent.getBooleanExtra(NetMsg.INTENT_HASGAMEUPDATE, false)) {
                        mScore = intent.getIntExtra(NetMsg.INTENT_SCORE, 0);
                        String score = "" + mScore;
                        mScoreTV.setText(score);
                        int elims = intent.getIntExtra(NetMsg.INTENT_ELIMINATIONS, 0);
                        if (mHasLivesLimit) {
                            mEliminationCount = mLives - elims;
                        } else {
                            mEliminationCount = elims;
                        }
                        mEliminationCountTV.setText(String.valueOf(mEliminationCount));
                        if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                            mTeamScore = intent.getIntExtra(NetMsg.INTENT_TEAMSCORE, 0);
                            score = "" + mTeamScore;
                            mTeamScoreTV.setText(score);
                        }
                        long timeRemaining = intent.getLongExtra(NetMsg.INTENT_TIMEREMAINING, -1);
                        if (timeRemaining > 0 && Globals.getInstance().mGameState == Globals.GAME_STATE_RUNNING) {
                            if (mGameCountdownTimer != null)
                                mGameCountdownTimer.cancel();
                            startGameCountdown(timeRemaining);
                            mGameTimerRunning = true;
                        } else {
                            if (mGameCountdownTimer != null) {
                                mGameCountdownTimer.cancel();
                                mGameCountdownTimer = null;
                            }
                        }
                    }
                    if (intent.hasExtra(NetMsg.INTENT_GAMESTATE)) {
                        int serverGameState = intent.getIntExtra(NetMsg.INTENT_GAMESTATE, Globals.GAME_STATE_ELIMINATED);
                        if (serverGameState != Globals.GAME_STATE_ELIMINATED) {
                            if (serverGameState == Globals.GAME_STATE_NONE && Globals.getInstance().mGameState != serverGameState) {
                                endGame();
                            } else if (serverGameState == Globals.GAME_STATE_RUNNING) {
                                closeScoreboard();
                                if (Globals.getInstance().mGameState == Globals.GAME_STATE_NONE) {
                                    startGame();
                                }
                            }
                        }
                    }
                    updatePreGameButtonVisibilities();
                }
            } else if (NetMsg.NETMSG_PLAYERDATAUPDATE.equals(action)) {
                displayPlayerData(intent.getStringExtra(NetMsg.INTENT_PLAYERDATA));
            } else if (NetMsg.NETMSG_STARTGAME.equals(action)) {
                closeScoreboard();
                if (Globals.getInstance().mGameState == Globals.GAME_STATE_NONE)
                    startGame();
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
            } else if (NetMsg.NETMSG_VERSIONERROR.equals(action)) {
                Toast.makeText(getApplicationContext(), getString(R.string.error_udp_version), Toast.LENGTH_SHORT).show();
                mReady = false;
                setReady();
            } else if (NetMsg.NETMSG_SAMETEAM.equals(action)) {
                Toast.makeText(getApplicationContext(), getString(R.string.error_same_id), Toast.LENGTH_SHORT).show();
                mReady = false;
                setReady();
            } else if (NetMsg.NETMSG_FAILEDTOJOIN.equals(action)) {
                Log.d(TAG, "NETMSG_FAILEDTOJOIN received. mUseNetwork=" + mUseNetwork + ", mNetworkMenuType=" + mNetworkMenuType);
                if (mUseNetwork && !mIsServer && mNetworkMenuType == NETWORK_TYPE_JOINING) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (mUseNetwork && !mIsServer && mNetworkMenuType == NETWORK_TYPE_JOINING && mUDPListenerService != null) {
                            Log.d(TAG, "Auto-retrying server scan...");
                            mUDPListenerService.joinServer();
                        }
                    }, 2000);
                }
            } else if (NetMsg.NETMSG_SERVERCANCEL.equals(action)) {
                cancelServerSearchTimeout();
                if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
                    endGame();
                } else {
                    mReady = false;
                    setReady(false);
                }
                mUseNetwork = true;
                setNetworkMenu(NETWORK_TYPE_ENABLED);
                displayAllNetworkingOptions(true);
                Globals.getInstance().mPlayerID = mSelectedPlayerID;
                setTeam();
                Toast.makeText(getApplicationContext(), getString(R.string.kicked_by_server_toast), Toast.LENGTH_SHORT).show();
            } else if (NetMsg.NETMSG_SERVERREPLY.equals(action)) {
                cancelServerSearchTimeout();
                if (intent.hasExtra(UDPListenerService.INTENT_PLAYERID)) {
                    byte assignedID = intent.getByteExtra(UDPListenerService.INTENT_PLAYERID, (byte) 0);
                    if (assignedID != 0) {
                        Globals.getInstance().mPlayerID = assignedID;
                        setTeam();
                    }
                }
                mReady = true;
                setReady();
                mTcpClient.startTcpClient();
                setNetworkMenu(NETWORK_TYPE_JOINED);
            } else if (NetMsg.NETMSG_NETWORKCONNECTED.equals(action)) {
                cancelServerSearchTimeout();
                mNetworkStatusIV.setImageResource(R.drawable.ic_network_connected_24dp);
                if (!mIsServer && mReady && mUseNetwork) {
                    setNetworkMenu(NETWORK_TYPE_JOINED);
                }
            } else if (NetMsg.NETMSG_NETWORKDISCONNECTED.equals(action)) {
                mNetworkStatusIV.setImageResource(R.drawable.ic_network_disconnected_24dp);
            } else if (NetMsg.NETMSG_PLAYERSETTINGSUPDATE.equals(action)) {
                updatePlayerSettings();
            }
        }
    };

    private static IntentFilter makeUDPUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(NetMsg.NETMSG_SHOTFIRED);
        intentFilter.addAction(NetMsg.NETMSG_HIT);
        intentFilter.addAction(NetMsg.NETMSG_OUT);
        intentFilter.addAction(NetMsg.NETMSG_ELIMINATED);
        intentFilter.addAction(NetMsg.NETMSG_JOIN);
        intentFilter.addAction(NetMsg.NETMSG_FAILEDTOJOIN);
        intentFilter.addAction(NetMsg.NETMSG_LEAVE);
        intentFilter.addAction(NetMsg.NETMSG_LISTPLAYERS);
        intentFilter.addAction(NetMsg.NETMSG_PLAYERDATAUPDATE);
        intentFilter.addAction(NetMsg.NETMSG_STARTGAME);
        intentFilter.addAction(NetMsg.NETMSG_ENDGAME);
        intentFilter.addAction(NetMsg.NETMSG_ERROR);
        intentFilter.addAction(NetMsg.NETMSG_VERSIONERROR);
        intentFilter.addAction(NetMsg.NETMSG_SAMETEAM);
        intentFilter.addAction(NetMsg.NETMSG_SERVERCREATED);
        intentFilter.addAction(NetMsg.NETMSG_SERVERCANCEL);
        intentFilter.addAction(NetMsg.NETMSG_SERVERREPLY);
        intentFilter.addAction(NetMsg.NETMSG_TEAMELIMINATED);
        intentFilter.addAction(NetMsg.NETMSG_NETWORKCONNECTED);
        intentFilter.addAction(NetMsg.NETMSG_NETWORKDISCONNECTED);
        intentFilter.addAction(NetMsg.NETMSG_PLAYERSETTINGSUPDATE);
        return intentFilter;
    }

    private void displayAllNetworkingOptions(boolean enabled) {
        int visibility = enabled ? View.VISIBLE : View.INVISIBLE;
        mGameModeLabelTV.setVisibility(visibility);
        mGameModeTV.setVisibility(visibility);
        mScoreLabelTV.setVisibility(visibility);
        mScoreTV.setVisibility(visibility);
        mPlayerNameTV.setVisibility(visibility);
        mServerIPTV.setVisibility(View.GONE);
        if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
            mTeamScoreLabelTV.setVisibility(visibility);
            mTeamScoreTV.setVisibility(visibility);
        } else {
            mTeamScoreLabelTV.setVisibility(View.INVISIBLE);
            mTeamScoreTV.setVisibility(View.INVISIBLE);
        }
    }

    private void displayInGameNetworkingOptions() {
        displayAllNetworkingOptions(false);
        mNetworkPlayerCountTV.setVisibility(View.VISIBLE);
        mGameModeLabelTV.setVisibility(View.VISIBLE);
        mGameModeTV.setVisibility(View.VISIBLE);
        mScoreLabelTV.setVisibility(View.VISIBLE);
        mScoreTV.setVisibility(View.VISIBLE);
        if (mIsServer) {
            mEndNetworkGameButton.setVisibility(View.VISIBLE);
        } else {
            mEndNetworkGameButton.setVisibility(View.GONE);
        }
        if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
            mTeamScoreLabelTV.setVisibility(View.VISIBLE);
            mTeamScoreTV.setVisibility(View.VISIBLE);
        }
    }

    public void kickPlayerFromAdmin(byte playerID) {
        if (mIsServer && mTcpServer != null) {
            mTcpServer.kickPlayer(playerID);
            Toast.makeText(this, "Player kicked", Toast.LENGTH_SHORT).show();
        } else if (mTcpClient != null) {
            mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ADMIN_KICK + playerID);
            Toast.makeText(this, "Kick command sent", Toast.LENGTH_SHORT).show();
        }
    }

    public void showTeamRatioDialog() {
        if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
            Toast.makeText(this, "Cannot edit team ratios while a game is in progress", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!mIsServer && !Globals.getInstance().mIsAdmin) {
            Toast.makeText(this, "Only server admins can edit team ratios", Toast.LENGTH_SHORT).show();
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
                .setNegativeButton(R.string.cancel, (dialog, id) -> dialog.cancel());

        final AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();

        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
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
                Toast.makeText(FullscreenActivity.this, "Team limits must be at least 1 player slot", Toast.LENGTH_SHORT).show();
                return;
            }

            if (totalEnteredSum != Globals.MAX_PLAYER_ID) {
                Toast.makeText(FullscreenActivity.this, "Total player slots across all teams must equal " + Globals.MAX_PLAYER_ID + " (current sum: " + totalEnteredSum + ")", Toast.LENGTH_LONG).show();
                return;
            }

            Globals.getInstance().setTeamSizes(newSizes);
            if (mIsServer && mTcpServer != null) {
                mTcpServer.rebalanceAllPlayers(false);
            } else if (mTcpClient != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < newSizes.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(newSizes[i]);
                }
                mTcpClient.sendTCPMessage(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ADMIN_TEAMSIZES + sb);
            }
            alertDialog.dismiss();
        });
    }

    private void displayPlayerData(final String message) {
        if (message == null || message.isEmpty())
            return;
        new Thread(() -> {
            PlayerDisplayData[] finalData = new PlayerDisplayData[Globals.MAX_PLAYER_ID + 2];
            for (int x = 1; x <= Globals.MAX_PLAYER_ID; x++)
                finalData[x] = null;

            boolean hasSemaphore = false;
            int[] teamPoints = new int[4];
            for (int i = 0; i < 4; i++)
                teamPoints[i] = 0;
            try {
                JSONObject json = new JSONObject(message);
                JSONArray players = json.getJSONArray(TcpServer.JSON_PLAYERDATA);
                Globals.getInstance();
                Globals.getmPlayerSettingsSemaphore();
                hasSemaphore = true;
                for (int x = 0; x < players.length(); x++) {
                    JSONObject player = players.getJSONObject(x);
                    PlayerDisplayData playerData = new PlayerDisplayData();
                    playerData.playerID = (byte) player.getInt(TcpServer.JSON_PLAYERID);
                    playerData.playerName = player.getString(TcpServer.JSON_PLAYERNAME);
                    playerData.points = player.getInt(TcpServer.JSON_PLAYERPOINTS);
                    if (playerData.playerID == Globals.getInstance().mPlayerID) {
                        mScore = Math.max(mScore, playerData.points);
                        if (mIsServer && mTcpServer != null) {
                            mTcpServer.setHostScore(mScore, mEliminationCount);
                        }
                    }
                    if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                        int teamIdx = Globals.getInstance().calcNetworkTeam(playerData.playerID) - 1;
                        if (teamIdx >= 0 && teamIdx < 4) {
                            teamPoints[teamIdx] += playerData.points;
                        }
                    }
                    playerData.eliminated = player.getInt(TcpServer.JSON_PLAYERELIMINATED);
                    if (Globals.getInstance().mPlayerSettings.get(playerData.playerID) == null) {
                        playerData.overrideLives = false;
                        playerData.lives = 0;
                    } else {
                        playerData.overrideLives = Objects.requireNonNull(Globals.getInstance().mPlayerSettings.get(playerData.playerID)).overrideLives;
                        playerData.lives = Objects.requireNonNull(Globals.getInstance().mPlayerSettings.get(playerData.playerID)).lives;
                    }
                    finalData[playerData.playerID] = playerData;
                }
                Globals.getInstance().mPlayerSettingsSemaphore.release();
                hasSemaphore = false;
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing player data", e);
                if (hasSemaphore)
                    Globals.getInstance().mPlayerSettingsSemaphore.release();
                return;
            }
            if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
                int myTeamIdx = Globals.getInstance().calcNetworkTeam(Globals.getInstance().mPlayerID) - 1;
                if (myTeamIdx >= 0 && myTeamIdx < 4) {
                    mTeamScore = teamPoints[myTeamIdx];
                }
            }

            runOnUiThread(() -> {
                if (mScoreTV != null) mScoreTV.setText(String.valueOf(mScore));
                if (mTeamScoreTV != null) mTeamScoreTV.setText(String.valueOf(mTeamScore));
            });

            updateScoreboardData(finalData);
        }).start();
    }

    private void updateScoreboardData(final PlayerDisplayData[] playerDisplayData) {
        Runnable runnable = () -> {
            mLatestPlayerDisplayData = playerDisplayData;
            if (!mIsScoreboardVisible) {
                return;
            }
            showOrUpdateScoreboardDialog(playerDisplayData);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            runOnUiThread(runnable);
        }
    }

    private void showOrUpdateScoreboardDialog(PlayerDisplayData[] playerDisplayData) {
        if (isFinishing() || isDestroyed() || !mIsScoreboardVisible) return;

        if (mScoreboardDialog != null && mScoreboardDialog.isShowing() && mScoreboardAdapter != null) {
            mScoreboardAdapter.setData(playerDisplayData);
            return;
        }

        if (mScoreboardDialog != null) {
            try {
                mScoreboardDialog.setOnDismissListener(null);
                if (mScoreboardDialog.isShowing()) {
                    mScoreboardDialog.dismiss();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error dismissing scoreboard dialog", e);
            }
            mScoreboardDialog = null;
            mScoreboardAdapter = null;
        }

        mScoreboardAdapter = new PlayerDisplayDataListAdapter(FullscreenActivity.this, playerDisplayData, true);
        AlertDialog.Builder alertDialog = new AlertDialog.Builder(FullscreenActivity.this, R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        alertDialog.setNegativeButton(R.string.ok, (dialog, id) -> dialog.cancel());
        LayoutInflater inflater = getLayoutInflater();
        View view = inflater.inflate(R.layout.player_data_dialog, null);
        alertDialog.setView(view);
        ListView listView = view.findViewById(R.id.player_list);
        listView.setAdapter(mScoreboardAdapter);
        if (mIsServer) {
            listView.setOnItemClickListener((parent, view1, position, id) -> {
                PlayerDisplayDataListAdapter.DisplayRow row = mScoreboardAdapter.getDisplayRow(position);
                if (row != null && row.type == PlayerDisplayDataListAdapter.DisplayRow.TYPE_TEAM_HEADER && row.teamNumber > 0) {
                    showTeamRatioDialog();
                    return;
                }
                byte playerID = mScoreboardAdapter.getPlayerID(position);
                if (playerID <= 0)
                    return;
                PlayerSettingsAlertDialog dialog = new PlayerSettingsAlertDialog(FullscreenActivity.this);
                dialog.setServer(playerID, mTcpServer);
                dialog.show();
            });
        }
        mScoreboardDialog = alertDialog.create();
        mScoreboardDialog.setOnDismissListener(dialog -> {
            mScoreboardDialog = null;
            mScoreboardAdapter = null;
            mIsScoreboardVisible = false;
        });
        mScoreboardDialog.show();
    }

    private void updateAdminButtonVisibility() {
        Button adminBtn = findViewById(R.id.admin_controls_button);
        if (adminBtn != null) {
            boolean showAdmin = (mIsServer || Globals.getInstance().mIsAdmin) && mUseNetwork && mReady;
            adminBtn.setVisibility(showAdmin ? View.VISIBLE : View.GONE);
            Log.d(TAG, "updateAdminButtonVisibility: mIsServer=" + mIsServer + ", mIsAdmin=" + Globals.getInstance().mIsAdmin + ", mUseNetwork=" + mUseNetwork + ", mReady=" + mReady + " -> show=" + showAdmin);
        }
    }

    private void updatePlayerSettings() {
        mHealthLabelTV.setText(getString(R.string.health_label, Globals.getInstance().mFullHealth));
        mShotsRemainingLabelTV.setText(getString(R.string.shots_remaining_label, Globals.getInstance().mFullReload, (Globals.getInstance().mDamage * -1)));
        setGameLimit();
        updateAdminButtonVisibility();

        applyCurrentRecoilSetting();
        Integer allowedShotMode = ShotModePolicy.coerceToAllowed(mCurrentShotMode, Globals.getInstance().mAllowSingleShotMode,
                Globals.getInstance().mAllowBurst3ShotMode, Globals.getInstance().mAllowAutoShotMode);
        if (allowedShotMode != null)
            setShotMode(allowedShotMode);
        getFiringMode();
    }
}
