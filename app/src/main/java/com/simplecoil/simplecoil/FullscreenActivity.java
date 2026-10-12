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
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;

import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository;
import com.simplecoil.simplecoil.domain.session.GameClock;
import com.simplecoil.simplecoil.domain.session.SessionState;
import com.simplecoil.simplecoil.domain.session.SessionToast;
import com.simplecoil.simplecoil.domain.session.SpawnPhase;
import com.simplecoil.simplecoil.ui.common.FlowCollectors;
import com.simplecoil.simplecoil.ui.game.AdminAction;
import com.simplecoil.simplecoil.ui.game.ConnectStatus;
import com.simplecoil.simplecoil.ui.game.GameMessage;
import com.simplecoil.simplecoil.ui.game.GameScreen;
import com.simplecoil.simplecoil.ui.game.GameViewModel;
import com.simplecoil.simplecoil.ui.game.GameViewModelFactory;
import com.simplecoil.simplecoil.ui.game.NetworkButton;
import com.simplecoil.simplecoil.ui.game.NetworkMenuEntry;
import com.simplecoil.simplecoil.ui.game.PreGameButtons;
import com.simplecoil.simplecoil.ui.game.SavedGameScreen;
import com.simplecoil.simplecoil.ui.game.TeamDisplay;
import com.simplecoil.simplecoil.ui.main.MainViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The play screen: views, dialogs, menus, permissions and window flags. All decisions are made by
 * {@link GameViewModel}, which tells this screen what to show through {@link GameScreen}; session
 * values are rendered from {@link SessionState}.
 */
public class FullscreenActivity extends AppCompatActivity implements PopupMenu.OnMenuItemClickListener, GameScreen {
    private static final String TAG = "scmain";

    private final ActivityResultLauncher<Intent> enableBtLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> connectWeapon());

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(new ScanContract(),
            result -> {
                if (result.getContents() != null) {
                    this.vm.onQrScanned(result.getContents());
                } else {
                    Log.e(TAG, "QR scan cancelled or no result");
                }
            });

    // Still read by DedicatedServerActivity.
    public static final String PREF_NAME = UserPreferencesRepository.PREF_NAME;
    public static final String PREF_GAME_MODE = UserPreferencesRepository.GAME_MODE;
    public static final String PREF_LIMIT_TIME = UserPreferencesRepository.LIMIT_TIME;
    public static final String PREF_LIMIT_LIVES = UserPreferencesRepository.LIMIT_LIVES;
    public static final String PREF_LIMIT_SCORE = UserPreferencesRepository.LIMIT_SCORE;

    protected MainViewModel mMainViewModel = null;
    private GameViewModel vm;

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
    private TextView mRespawnShieldCountdownTV = null;
    private PopupMenu mNetworkPopup = null;
    private AlertDialog mBlasterDisconnectDialog = null;
    private AlertDialog mScoreboardDialog = null;
    private PlayerDisplayDataListAdapter mScoreboardAdapter = null;
    private AnimationDrawable mHitAnimation = null;

    private FragmentManager mFragmentMgr = null;
    private Fragment mMapFragment = null;

    private static final byte BLASTER_TYPE_RIFLE = (byte)1;
    final private int REQUEST_CODE_LOCATION_PERMISSIONS = 1022;
    final private int REQUEST_CODE_CAMERA_PERMISSION = 1024;

    // If you change these durations you will also need to change the duration in the res/anim/fadeout XML files to match
    private static final int HIT_ANIMATION_DURATION_MILLISECONDS = 400;
    private static final int ELIMINATED_ANIMATION_DURATION_MILLISECONDS = 800;

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
        vm = new ViewModelProvider(this, new GameViewModelFactory((SimpleCoilApp) getApplication())).get(GameViewModel.class);
        vm.setScreen(this);
        SavedGameScreen saved = null;
        if (savedInstanceState != null) {
            saved = new SavedGameScreen(savedInstanceState.getByte("mSelectedPlayerID", (byte)0),
                    savedInstanceState.getString("mDeviceAddress", ""),
                    savedInstanceState.getBoolean("mUseNetwork", false),
                    savedInstanceState.getBoolean("mIsServer", false),
                    savedInstanceState.getInt("mNetworkMenuType", GameViewModel.NETWORK_TYPE_ENABLED));
        }
        vm.onCreate(saved);

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

        // The session runs app-scoped (D4); render it whenever this screen exists.
        FlowCollectors.collectWhileAtLeast(this, Lifecycle.State.CREATED, vm.getSession(), this::renderSession);

        mFragmentMgr = getSupportFragmentManager();
        mEliminationCountTV = findViewById(R.id.eliminations_count_tv);
        mReconnectButton = findViewById(R.id.reconnect_weapon_button);
        mReconnectButton.setOnClickListener(v -> vm.onReconnectPressed());
        mConnectButton = findViewById(R.id.connect_weapon_button);
        if (mConnectButton != null) {
            mConnectButton.setOnClickListener(v -> vm.onConnectPressed());
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
            mDedicatedServerButton.setOnClickListener(v -> vm.onDedicatedServerPressed());
        }
        mTeamMinusButton = findViewById(R.id.team_minus_button);
        if (mTeamMinusButton != null) {
            mTeamMinusButton.setVisibility(View.VISIBLE);
            mTeamMinusButton.setOnClickListener(v -> vm.onTeamMinusPressed());
        }
        mTeamPlusButton = findViewById(R.id.team_plus_button);
        if (mTeamPlusButton != null) {
            mTeamPlusButton.setVisibility(View.VISIBLE);
            mTeamPlusButton.setOnClickListener(v -> vm.onTeamPlusPressed());
        }
        mStartGameButton = findViewById(R.id.start_game_button);
        if (mStartGameButton != null) {
            mStartGameButton.setOnClickListener(v -> vm.onStartGamePressed());
        }
        mPlayerSettingsButton = findViewById(R.id.player_settings_button);
        if (mPlayerSettingsButton != null) {
            mPlayerSettingsButton.setOnClickListener(v -> vm.onPlayerSettingsPressed());
        }
        mEndGameButton = findViewById(R.id.end_game_button);
        if (mEndGameButton != null) {
            mEndGameButton.setOnClickListener(v -> vm.onEndGamePressed());
        }
        mEndNetworkGameButton = findViewById(R.id.end_network_game_button);
        if (mEndNetworkGameButton != null) {
            mEndNetworkGameButton.setOnClickListener(v -> vm.onEndGamePressed());
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
            mUseNetworkingButton.setOnClickListener(v -> vm.onNetworkButtonPressed());
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
            mGameLimitButton.setOnClickListener(v -> vm.onGameLimitPressed());
        }
        mGameCountDownTV = findViewById(R.id.game_countdown_tv);
        mPlayerNameTV = findViewById(R.id.player_name_label_tv);
        Button shuffleTeamsButton = findViewById(R.id.shuffle_teams_button);
        if (shuffleTeamsButton != null) {
            shuffleTeamsButton.setOnClickListener(v -> vm.onShuffleTeamsPressed());
        }
        Button adminControlsButton = findViewById(R.id.admin_controls_button);
        if (adminControlsButton != null) {
            adminControlsButton.setOnClickListener(this::showAdminControlsMenu);
        }
        mUseNetworkingButton.setVisibility(View.VISIBLE);
        mPlayerDataButton = findViewById(R.id.player_data_button);
        if (mPlayerDataButton != null) {
            mPlayerDataButton.setOnClickListener(v -> vm.openScoreboard());
        }
        vm.onViewsCreated();
        loadFragment();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        SavedGameScreen saved = vm.saveState();
        outState.putByte("mSelectedPlayerID", saved.getSelectedPlayerId());
        outState.putString("mDeviceAddress", saved.getDeviceAddress());
        outState.putBoolean("mUseNetwork", saved.getUseNetwork());
        outState.putBoolean("mIsServer", saved.isServer());
        outState.putInt("mNetworkMenuType", saved.getNetworkMenuType());
    }

    @Override
    protected void onResume() {
        super.onResume();
        vm.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        vm.onPause();
    }

    @Override
    protected void onDestroy() {
        vm.onScreenDestroyed();
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

    // ---------------------------------------------------------------------------------------
    // Connecting: permissions and Bluetooth, then GameViewModel scans
    // ---------------------------------------------------------------------------------------

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
                    vm.onLocationPermissionsGranted();
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

    @Override
    public void requestConnect() {
        connectWeapon();
    }

    private void connectWeapon() {
        if (GameViewModel.TEST_NETWORK) {
            showPlayScreen();
            return;
        }

        if (vm.isScanning())
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
        if (!vm.startScan(bluetoothAdapter)) {
            Toast.makeText(this, R.string.error_location_permission_required, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------------------------------
    // GameScreen: connection
    // ---------------------------------------------------------------------------------------

    @Override
    public void showConnectScreen(boolean reconnectVisible) {
        RelativeLayout playLayout = findViewById(R.id.play_layout);
        RelativeLayout connectLayout = findViewById(R.id.connect_layout);
        playLayout.setVisibility(View.GONE);
        connectLayout.setVisibility(View.VISIBLE);
        setReconnectVisible(reconnectVisible);
    }

    @Override
    public void showPlayScreen() {
        RelativeLayout connectLayout = findViewById(R.id.connect_layout);
        if (connectLayout != null) connectLayout.setVisibility(View.GONE);
        RelativeLayout playLayout = findViewById(R.id.play_layout);
        if (playLayout != null) playLayout.setVisibility(View.VISIBLE);
    }

    @Override
    public void setReconnectVisible(boolean visible) {
        mReconnectButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void setConnectButtonsEnabled(boolean enabled) {
        mConnectButton.setEnabled(enabled);
        mReconnectButton.setEnabled(enabled);
        mDedicatedServerButton.setEnabled(enabled);
        mQRConnectButton.setEnabled(enabled);
    }

    @Override
    public void showConnectStatus(@NonNull ConnectStatus status) {
        TextView connectStatusTV = findViewById(R.id.connect_status_tv);
        if (connectStatusTV == null) return;
        switch (status) {
            case SCANNING:
                connectStatusTV.setText(R.string.connect_status_scanning);
                break;
            case CONNECTING:
                connectStatusTV.setText(R.string.connect_status_connecting);
                break;
            case COMMUNICATING:
                connectStatusTV.setText(R.string.connect_status_communicating);
                break;
            case NOT_CONNECTED:
                connectStatusTV.setText(R.string.connect_status_not_connected);
                break;
        }
    }

    @Override
    public void showDisconnectDialog() {
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
                            vm.onDisconnectQuit();
                            dialog.cancel();
                            mBlasterDisconnectDialog = null;
                        });
        mBlasterDisconnectDialog = alertDialogBuilder.create();
        mBlasterDisconnectDialog.show();
    }

    @Override
    public void dismissDisconnectDialog() {
        if (mBlasterDisconnectDialog != null) {
            mBlasterDisconnectDialog.dismiss();
            mBlasterDisconnectDialog = null;
        }
    }

    @Override
    public void showBlasterType(boolean rifle, @NonNull String address) {
        if (rifle) {
            Toast.makeText(getApplicationContext(), getString(R.string.rifle_detected_toast, address), Toast.LENGTH_LONG).show();
        } else {
            // We'll automatically assume that this is a pistol
            Toast.makeText(getApplicationContext(), getString(R.string.pistol_detected_toast, address), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void finishScreen() {
        finish();
    }

    // ---------------------------------------------------------------------------------------
    // GameScreen: game
    // ---------------------------------------------------------------------------------------

    @Override
    public void showGameStarted(boolean useNetwork, boolean isServer, boolean freeForAll) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowInsetsControllerCompat windowInsetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars());
        windowInsetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );
        mScoreTV.setText("0");
        mTeamScoreTV.setText("0");
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
        if (useNetwork) {
            displayInGameNetworkingOptions(isServer, freeForAll);
            mEndNetworkGameButton.setVisibility(isServer ? View.VISIBLE : View.GONE);
        } else {
            displayAllNetworkingOptions(false, freeForAll);
            mEndGameButton.setVisibility(View.VISIBLE);
        }
        mUseNetworkingButton.setVisibility(View.INVISIBLE);
        mFiringModeButton.setVisibility(View.INVISIBLE);
    }

    @Override
    public void showGameEnded(boolean useNetwork, boolean freeForAll) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowInsetsControllerCompat windowInsetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars());
        mEndGameButton.setVisibility(View.GONE);
        mEndNetworkGameButton.setVisibility(View.GONE);
        displayAllNetworkingOptions(useNetwork, freeForAll);
        mUseNetworkingButton.setVisibility(View.VISIBLE);
        mHitPlayerIV.setVisibility(View.GONE);
        mHitPlayerNameTV.setVisibility(View.GONE);
        mShotsFiredIV.setVisibility(View.GONE);
    }

    @Override
    public void confirmEndGame() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.end_game_dialog_title);
        builder.setMessage(R.string.end_game_dialog_message);
        builder.setPositiveButton(R.string.yes, (dialog, which) -> vm.onEndGameConfirmed());
        builder.setNegativeButton(R.string.no, (dialog, which) -> dialog.dismiss());
        AlertDialog alert = builder.create();
        alert.show();
    }

    @Override
    public void showScore(int score) {
        if (mScoreTV != null) mScoreTV.setText(String.valueOf(score));
    }

    @Override
    public void showTeamScore(int teamScore) {
        if (mTeamScoreTV != null) mTeamScoreTV.setText(String.valueOf(teamScore));
    }

    @Override
    public void showGameLimit(Integer timeLimitMinutes, boolean livesLabel, Integer scoreLimit) {
        if (timeLimitMinutes != null) {
            mGameCountDownTV.setVisibility(View.VISIBLE);
            @SuppressLint("DefaultLocale") String display = String.format("%02d:00", timeLimitMinutes);
            mGameCountDownTV.setText(display);
            mGameTimer.setVisibility(View.GONE);
        } else {
            mGameCountDownTV.setVisibility(View.GONE);
            mGameTimer.setVisibility(View.VISIBLE);
        }
        TextView eliminationLabel = findViewById(R.id.eliminations_count_label_tv);
        eliminationLabel.setText(livesLabel ? R.string.lives_count_label : R.string.eliminations_count_label);
        if (scoreLimit != null) {
            mScoreLabelTV.setText(getString(R.string.score_limit_label, scoreLimit));
            mTeamScoreLabelTV.setText(getString(R.string.team_score_limit_label, scoreLimit));
        } else {
            mScoreLabelTV.setText(R.string.score_label);
            mTeamScoreLabelTV.setText(R.string.team_score_label);
        }
    }

    @Override
    public void showPlayerSettings(int fullHealth, byte fullReload, int damage) {
        mHealthLabelTV.setText(getString(R.string.health_label, fullHealth));
        mShotsRemainingLabelTV.setText(getString(R.string.shots_remaining_label, fullReload, damage));
    }

    @Override
    public void showFiringMode(int firingMode) {
        switch (firingMode) {
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

    @Override
    public void showGameMode(int gameMode) {
        if (gameMode == Globals.GAME_MODE_2TEAMS) {
            mGameModeTV.setText(R.string.game_mode_2teams);
        } else if (gameMode == Globals.GAME_MODE_4TEAMS) {
            mGameModeTV.setText(R.string.game_mode_4teams);
        } else {
            mGameModeTV.setText(R.string.game_mode_ffa);
        }
    }

    @Override
    public void showTeam(@NonNull TeamDisplay team) {
        if (team instanceof TeamDisplay.Team) {
            TeamDisplay.Team t = (TeamDisplay.Team) team;
            mTeamLabelTV.setText(getString(R.string.team_number_label, t.getTeam()));
            if (t.getColor() != Color.TRANSPARENT) {
                GradientDrawable drawable = new GradientDrawable();
                drawable.setColor(t.getColor());
                drawable.setCornerRadius(8f);
                mTeamLabelTV.setBackground(drawable);
                mTeamLabelTV.setPadding(24, 12, 24, 12);
                mTeamLabelTV.setTextColor(t.getTeam() == 4 ? Color.BLACK : Color.WHITE);
            } else {
                mTeamLabelTV.setBackground(null);
                mTeamLabelTV.setPadding(0, 0, 0, 0);
                mTeamLabelTV.setTextColor(Color.WHITE);
            }
            mTeamTV.setText(t.getPlayerName() != null ? t.getPlayerName() : getString(R.string.network_team, t.getPlayer()));
            int visibility = t.getTeamScoreVisible() ? View.VISIBLE : View.INVISIBLE;
            mTeamScoreLabelTV.setVisibility(visibility);
            mTeamScoreTV.setVisibility(visibility);
            return;
        }
        mTeamLabelTV.setText(R.string.team_label);
        mTeamLabelTV.setBackground(null);
        mTeamLabelTV.setPadding(0, 0, 0, 0);
        mTeamLabelTV.setTextColor(Color.WHITE);
        if (team instanceof TeamDisplay.FreeForAll) {
            mTeamTV.setText(String.valueOf(((TeamDisplay.FreeForAll) team).getPlayerId()));
        } else {
            mTeamTV.setText(R.string.auto_sort_team);
        }
        mTeamScoreLabelTV.setVisibility(View.INVISIBLE);
        mTeamScoreTV.setVisibility(View.INVISIBLE);
    }

    @Override
    public void showPlayerName(@NonNull String name) {
        if (mPlayerNameTV != null) mPlayerNameTV.setText(name);
    }

    @Override
    public void showPreGameButtons(@NonNull PreGameButtons buttons) {
        if (buttons.getReady()) {
            mTeamMinusButton.setVisibility(View.INVISIBLE);
            mTeamPlusButton.setVisibility(View.INVISIBLE);
            mGameLimitButton.setVisibility(View.GONE);
        } else {
            mTeamMinusButton.setVisibility(View.VISIBLE);
            mTeamPlusButton.setVisibility(View.VISIBLE);
            mGameLimitButton.setVisibility(View.VISIBLE);
        }
        if (!buttons.getStartVisible()) {
            mStartGameButton.setVisibility(View.GONE);
        } else {
            mStartGameButton.setVisibility(View.VISIBLE);
            mStartGameButton.setEnabled(buttons.getStartEnabled());
        }
        if (buttons.getServerSettingsOnly()) {
            mFiringModeButton.setVisibility(View.GONE);
            mPlayerSettingsButton.setVisibility(View.GONE);
        } else {
            mFiringModeButton.setVisibility(View.VISIBLE);
            mPlayerSettingsButton.setVisibility(View.VISIBLE);
        }
        Button shuffleTeamsBtn = findViewById(R.id.shuffle_teams_button);
        if (shuffleTeamsBtn != null) {
            shuffleTeamsBtn.setEnabled(true);
            shuffleTeamsBtn.setVisibility(buttons.getShuffleVisible() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void showAdminButton(boolean visible) {
        Button adminBtn = findViewById(R.id.admin_controls_button);
        if (adminBtn != null) adminBtn.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void showSessionToast(@NonNull SessionToast toast) {
        switch (toast) {
            case GRENADE_PAIRED:
                Toast.makeText(getApplicationContext(), getString(R.string.grenade_paired_toast), Toast.LENGTH_SHORT).show();
                break;
            case OUT_OF_LIVES:
                Toast.makeText(getApplicationContext(), getString(R.string.dialog_out_of_lives), Toast.LENGTH_LONG).show();
                break;
            case RECOIL_FORCED_ENABLED:
                Toast.makeText(getApplicationContext(), "Recoil is forced ENABLED by server", Toast.LENGTH_SHORT).show();
                break;
            case RECOIL_FORCED_DISABLED:
                Toast.makeText(getApplicationContext(), "Recoil is forced DISABLED by server", Toast.LENGTH_SHORT).show();
                break;
            case GAME_TIME_EXPIRED:
                Toast.makeText(getApplicationContext(), getString(R.string.dialog_game_time_expired), Toast.LENGTH_SHORT).show();
                break;
        }
    }

    @Override
    public void showMessage(@NonNull GameMessage message, int arg) {
        switch (message) {
            case NOT_ENOUGH_PLAYERS:
                Toast.makeText(getApplicationContext(), getString(R.string.not_enough_players_toast), Toast.LENGTH_SHORT).show();
                break;
            case PLAYER_SETTINGS_NOT_ALLOWED:
                Toast.makeText(getApplicationContext(), getString(R.string.player_settings_not_allowed_toast), Toast.LENGTH_SHORT).show();
                break;
            case TEAMS_SHUFFLED:
                Toast.makeText(getApplicationContext(), "Teams Sorted & Randomized!", Toast.LENGTH_SHORT).show();
                break;
            case NO_WIFI:
                Toast.makeText(getApplicationContext(), getString(R.string.error_no_wifi), Toast.LENGTH_SHORT).show();
                break;
            case LIMIT_TOO_HIGH:
                Toast.makeText(getApplicationContext(), getString(R.string.error_limit_too_high), Toast.LENGTH_SHORT).show();
                break;
            case UDP_VERSION_ERROR:
                Toast.makeText(getApplicationContext(), getString(R.string.error_udp_version), Toast.LENGTH_SHORT).show();
                break;
            case SAME_ID_ERROR:
                Toast.makeText(getApplicationContext(), getString(R.string.error_same_id), Toast.LENGTH_SHORT).show();
                break;
            case KICKED_BY_SERVER:
                Toast.makeText(getApplicationContext(), getString(R.string.kicked_by_server_toast), Toast.LENGTH_SHORT).show();
                break;
            case INAPPROPRIATE_NAME:
                Toast.makeText(getApplicationContext(), "Please choose an appropriate player name!", Toast.LENGTH_SHORT).show();
                break;
            case LOCATION_PERMISSION_REQUIRED:
                Toast.makeText(this, R.string.error_location_permission_required, Toast.LENGTH_SHORT).show();
                break;
            case RATIOS_DURING_GAME:
                Toast.makeText(this, "Cannot edit team ratios while a game is in progress", Toast.LENGTH_SHORT).show();
                break;
            case RATIOS_ADMIN_ONLY:
                Toast.makeText(this, "Only server admins can edit team ratios", Toast.LENGTH_SHORT).show();
                break;
            case RATIOS_NOT_IN_FFA:
                Toast.makeText(this, "Team ratios are not applicable in Free For All mode", Toast.LENGTH_SHORT).show();
                break;
            case RATIOS_MIN_ONE:
                Toast.makeText(this, "Team limits must be at least 1 player slot", Toast.LENGTH_SHORT).show();
                break;
            case RATIOS_TOTAL:
                Toast.makeText(this, "Total player slots across all teams must equal " + Globals.MAX_PLAYER_ID + " (current sum: " + arg + ")", Toast.LENGTH_LONG).show();
                break;
            case PLAYER_KICKED:
                Toast.makeText(this, "Player kicked", Toast.LENGTH_SHORT).show();
                break;
            case KICK_SENT:
                Toast.makeText(this, "Kick command sent", Toast.LENGTH_SHORT).show();
                break;
        }
    }

    @Override
    public void showText(@NonNull String text) {
        Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------------------------------
    // GameScreen: network
    // ---------------------------------------------------------------------------------------

    private void displayAllNetworkingOptions(boolean enabled, boolean freeForAll) {
        int visibility = enabled ? View.VISIBLE : View.INVISIBLE;
        mGameModeLabelTV.setVisibility(visibility);
        mGameModeTV.setVisibility(visibility);
        mScoreLabelTV.setVisibility(visibility);
        mScoreTV.setVisibility(visibility);
        mPlayerNameTV.setVisibility(visibility);
        mServerIPTV.setVisibility(View.GONE);
        if (!freeForAll) {
            mTeamScoreLabelTV.setVisibility(visibility);
            mTeamScoreTV.setVisibility(visibility);
        } else {
            mTeamScoreLabelTV.setVisibility(View.INVISIBLE);
            mTeamScoreTV.setVisibility(View.INVISIBLE);
        }
    }

    private void displayInGameNetworkingOptions(boolean isServer, boolean freeForAll) {
        displayAllNetworkingOptions(false, freeForAll);
        mNetworkPlayerCountTV.setVisibility(View.VISIBLE);
        mGameModeLabelTV.setVisibility(View.VISIBLE);
        mGameModeTV.setVisibility(View.VISIBLE);
        mScoreLabelTV.setVisibility(View.VISIBLE);
        mScoreTV.setVisibility(View.VISIBLE);
        mEndNetworkGameButton.setVisibility(isServer ? View.VISIBLE : View.GONE);
        if (!freeForAll) {
            mTeamScoreLabelTV.setVisibility(View.VISIBLE);
            mTeamScoreTV.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void showNetworkOptions(boolean enabled, boolean freeForAll) {
        displayAllNetworkingOptions(enabled, freeForAll);
    }

    @Override
    public void showNetworkButton(@NonNull NetworkButton button) {
        if (mUseNetworkingButton == null) return;
        switch (button) {
            case USE_NETWORK:
                mUseNetworkingButton.setText(R.string.use_network_button);
                break;
            case MENU:
                mUseNetworkingButton.setText(R.string.network_menu_button);
                break;
            case SEARCHING:
                mUseNetworkingButton.setText(R.string.network_searching_status);
                break;
            case CONNECTED:
                mUseNetworkingButton.setText(R.string.network_connected_status);
                break;
        }
    }

    @Override
    public void prepareNetworkMenu() {
        if (mNetworkPopup == null) {
            mNetworkPopup = new PopupMenu(new ContextThemeWrapper(FullscreenActivity.this, R.style.AppTheme_PopupOverlay), mUseNetworkingButton);
            mNetworkPopup.setOnMenuItemClickListener(FullscreenActivity.this);
        }
    }

    @Override
    public void rebuildNetworkMenu() {
        if (mNetworkPopup == null) return;
        mNetworkPopup.setForceShowIcon(true);
        mNetworkPopup.getMenu().close();
        mNetworkPopup.getMenu().clear();
        int switchOrder = 51;
        for (NetworkMenuEntry entry : vm.networkMenuEntries()) {
            if (entry instanceof NetworkMenuEntry.Join) {
                mNetworkPopup.getMenu().add(0, R.id.join_item, 20, R.string.join_button);
            } else if (entry instanceof NetworkMenuEntry.JoinIp) {
                mNetworkPopup.getMenu().add(0, R.id.join_ip_item, 30, R.string.join_ip_button);
            } else if (entry instanceof NetworkMenuEntry.PlayerName) {
                mNetworkPopup.getMenu().add(0, R.id.player_name_item, 50, getString(R.string.player_name_button, ((NetworkMenuEntry.PlayerName) entry).getName()));
            } else if (entry instanceof NetworkMenuEntry.GameMode) {
                mNetworkPopup.getMenu().add(0, R.id.game_mode_item, 60, R.string.game_mode_toggle_button);
            } else if (entry instanceof NetworkMenuEntry.SwitchTeam) {
                NetworkMenuEntry.SwitchTeam switchTeam = (NetworkMenuEntry.SwitchTeam) entry;
                int itemId = switchTeamItemId(switchTeam.getTeam());
                if (itemId != 0) {
                    MenuItem item = mNetworkPopup.getMenu().add(0, itemId, switchOrder++, getString(R.string.switch_to_team, switchTeam.getTeam()));
                    if (switchTeam.getColor() != Color.TRANSPARENT) {
                        item.setIcon(createTeamColorSquare(switchTeam.getColor()));
                    }
                }
            } else if (entry instanceof NetworkMenuEntry.DisableNetwork) {
                mNetworkPopup.getMenu().add(0, R.id.disable_network_item, 70, R.string.no_network_button);
            }
        }
    }

    private static int switchTeamItemId(int team) {
        switch (team) {
            case 1: return R.id.switch_team_1_item;
            case 2: return R.id.switch_team_2_item;
            case 3: return R.id.switch_team_3_item;
            case 4: return R.id.switch_team_4_item;
            default: return 0;
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

    @Override
    public void showNetworkMenu() {
        mNetworkPopup.show();
    }

    @Override
    public void showGameModeMenu() {
        PopupMenu popup = new PopupMenu(new ContextThemeWrapper(FullscreenActivity.this, R.style.AppTheme_PopupOverlay), mUseNetworkingButton);
        MenuInflater inflater = popup.getMenuInflater();
        inflater.inflate(R.menu.game_mode_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(FullscreenActivity.this);
        popup.show();
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.disable_network_item) {
            vm.onNetworkMenuEntry(NetworkMenuEntry.DisableNetwork.INSTANCE);
        } else if (id == R.id.join_item) {
            vm.onNetworkMenuEntry(NetworkMenuEntry.Join.INSTANCE);
        } else if (id == R.id.join_ip_item) {
            vm.onNetworkMenuEntry(NetworkMenuEntry.JoinIp.INSTANCE);
        } else if (id == R.id.player_name_item) {
            vm.onNetworkMenuEntry(new NetworkMenuEntry.PlayerName(""));
        } else if (id == R.id.switch_team_1_item) {
            vm.onNetworkMenuEntry(new NetworkMenuEntry.SwitchTeam(1, 0));
        } else if (id == R.id.switch_team_2_item) {
            vm.onNetworkMenuEntry(new NetworkMenuEntry.SwitchTeam(2, 0));
        } else if (id == R.id.switch_team_3_item) {
            vm.onNetworkMenuEntry(new NetworkMenuEntry.SwitchTeam(3, 0));
        } else if (id == R.id.switch_team_4_item) {
            vm.onNetworkMenuEntry(new NetworkMenuEntry.SwitchTeam(4, 0));
        } else if (id == R.id.game_mode_item) {
            vm.onNetworkMenuEntry(NetworkMenuEntry.GameMode.INSTANCE);
        } else if (id == R.id.firing_mode_outdoor_no_cone_item) {
            vm.onFiringModeSelected(Globals.FIRING_MODE_OUTDOOR_NO_CONE);
        } else if (id == R.id.firing_mode_outdoor_with_cone_item) {
            vm.onFiringModeSelected(Globals.FIRING_MODE_OUTDOOR_WITH_CONE);
        } else if (id == R.id.firing_mode_indoor_no_cone_item) {
            vm.onFiringModeSelected(Globals.FIRING_MODE_INDOOR_NO_CONE);
        } else if (id == R.id.game_mode_2teams_item) {
            vm.onGameModeSelected(Globals.GAME_MODE_2TEAMS);
        } else if (id == R.id.game_mode_4teams_item) {
            vm.onGameModeSelected(Globals.GAME_MODE_4TEAMS);
        } else if (id == R.id.game_mode_ffa_item) {
            vm.onGameModeSelected(Globals.GAME_MODE_FFA);
        } else {
            return false;
        }
        return true;
    }

    private void showAdminControlsMenu(View v) {
        PopupMenu popup = new PopupMenu(new ContextThemeWrapper(FullscreenActivity.this, R.style.AppTheme_PopupOverlay), v);
        List<AdminAction> actions = vm.adminActions();
        int order = 10;
        for (AdminAction action : actions) {
            int title;
            switch (action) {
                case START_GAME: title = R.string.start_game_button; break;
                case END_GAME: title = R.string.end_game_button; break;
                case TWO_TEAMS: title = R.string.game_mode_2teams; break;
                case FOUR_TEAMS: title = R.string.game_mode_4teams; break;
                case FREE_FOR_ALL: title = R.string.game_mode_ffa; break;
                default: title = R.string.shuffle_teams_button; break;
            }
            popup.getMenu().add(0, action.ordinal(), order, title);
            order += 10;
        }
        popup.setOnMenuItemClickListener(item -> {
            Log.d(TAG, "showAdminControlsMenu onMenuItemClick: itemId=" + item.getItemId());
            vm.onAdminAction(AdminAction.values()[item.getItemId()]);
            return true;
        });
        popup.show();
    }

    @Override
    public void showServerSearching() {
        mServerIPTV.setVisibility(View.VISIBLE);
        mServerIPTV.setText(R.string.server_status_searching);
    }

    @Override
    public void showNotReady() {
        mNetworkStatusIV.setVisibility(View.GONE);
        mNetworkPlayerCountTV.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mServerIPTV.setVisibility(View.GONE);
    }

    @Override
    public void setKeepScreenOn(boolean on) {
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void setPlayerDataButtonVisible(boolean visible) {
        mPlayerDataButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void showPlayerCount(int count) {
        mNetworkPlayerCountTV.setText(getString(R.string.network_player_count, count));
        mNetworkPlayerCountTV.setVisibility(View.VISIBLE);
    }

    @Override
    public void showOnlyOnePlayer() {
        mNetworkPlayerCountTV.setText(R.string.network_player_1count);
    }

    @Override
    public void showConnectedToServer(@NonNull String ip, boolean dedicated) {
        mServerIPTV.setText(getString(R.string.server_status_connected_to, ip));
        mServerIPTV.setVisibility(View.VISIBLE);
        if (dedicated) {
            mPlayerDataButton.setVisibility(View.VISIBLE);
            mNetworkStatusIV.setVisibility(View.VISIBLE);
            mNetworkStatusIV.setImageResource(R.drawable.ic_network_connected_24dp);
        }
    }

    @Override
    public void hideStartButton() {
        mStartGameButton.setVisibility(View.GONE);
    }

    @Override
    public void showNetworkConnected(boolean connected) {
        mNetworkStatusIV.setImageResource(connected ? R.drawable.ic_network_connected_24dp : R.drawable.ic_network_disconnected_24dp);
    }

    @Override
    public void showShotsFired() {
        if (mShotsFiredIV != null && mShotsFiredIV.getVisibility() != View.VISIBLE) {
            // Show the "shots fired" image
            mShotsFiredIV.setVisibility(View.VISIBLE);
            Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
            mShotsFiredIV.startAnimation(animationFadeOut);
            new Handler(Looper.getMainLooper()).postDelayed(() -> mShotsFiredIV.setVisibility(View.GONE), HIT_ANIMATION_DURATION_MILLISECONDS);
        }
    }

    @Override
    public void showHitOtherPlayer(boolean out, @NonNull String name) {
        if (mHitPlayerIV != null && mHitPlayerAnimation == null) {
            // Show the "you hit a player" / "you hit a player that's already out" animation
            mHitPlayerIV.setVisibility(View.VISIBLE);
            mHitPlayerIV.setBackgroundResource(out ? R.drawable.hit_other_player_out_animation : R.drawable.hit_other_player_animation);
            Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
            mHitPlayerIV.startAnimation(animationFadeOut);
            mHitPlayerAnimation = (AnimationDrawable) mHitPlayerIV.getBackground();
            mHitPlayerAnimation.start();
            mHitPlayerNameTV.setVisibility(View.VISIBLE);
            mHitPlayerNameTV.setText(name);
            mHitPlayerNameTV.startAnimation(animationFadeOut);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                mHitPlayerIV.setVisibility(View.GONE);
                mHitPlayerAnimation.stop();
                mHitPlayerAnimation = null;
                mHitPlayerNameTV.setVisibility(View.GONE);
            }, HIT_ANIMATION_DURATION_MILLISECONDS);
        }
    }

    @Override
    public void showScoreIncrease(@NonNull String name) {
        mScoreIncreaseIV.setVisibility(View.VISIBLE);
        Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.eliminatedfadeout);
        mScoreIncreaseIV.startAnimation(animationFadeOut);
        mScoreIncreasePlayerNameTV.setVisibility(View.VISIBLE);
        mScoreIncreasePlayerNameTV.setText(name);
        mScoreIncreasePlayerNameTV.startAnimation(animationFadeOut);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            mScoreIncreaseIV.setVisibility(View.GONE);
            mScoreIncreasePlayerNameTV.setVisibility(View.GONE);
        }, ELIMINATED_ANIMATION_DURATION_MILLISECONDS);
    }

    // ---------------------------------------------------------------------------------------
    // GameScreen: dialogs
    // ---------------------------------------------------------------------------------------

    @Override
    public void showPlayerNameDialog(@NonNull String current) {
        LayoutInflater li = LayoutInflater.from(getApplicationContext());
        View view = li.inflate(R.layout.player_name_dialog, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        alertDialogBuilder.setView(view);

        final EditText playerNameET = view.findViewById(R.id.player_name_et);
        playerNameET.setText(current);

        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton(R.string.ok,
                        (dialog, id) -> {
                            vm.onPlayerNameEntered(playerNameET.getText().toString().trim());
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.cancel,
                        (dialog, id) -> dialog.cancel());
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    @Override
    public void showServerIpDialog(@NonNull String suggestion) {
        LayoutInflater li = LayoutInflater.from(getApplicationContext());
        View view = li.inflate(R.layout.server_ip_dialog, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        alertDialogBuilder.setView(view);

        final EditText serverIPET = view.findViewById(R.id.server_ip_et);
        serverIPET.setText(suggestion);

        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton(R.string.ok,
                        (dialog, id) -> {
                            vm.onServerIpEntered(serverIPET.getText().toString());
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.cancel,
                        (dialog, id) -> dialog.cancel());
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    @Override
    public void showGameLimitDialog(boolean allowScoreLimit) {
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
        gameLimitScore.setVisibility(allowScoreLimit ? View.VISIBLE : View.GONE);
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
                            GameViewModel.LimitKind kind = gameLimitTime.isChecked() ? GameViewModel.LimitKind.TIME
                                    : gameLimitLives.isChecked() ? GameViewModel.LimitKind.LIVES : GameViewModel.LimitKind.SCORE;
                            vm.onGameLimitEntered(gameLimitET.getText().toString(), kind);
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.cancel,
                        (dialog, id) -> dialog.cancel());
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    @Override
    public void showTeamRatioDialog(int numTeams, @NonNull int[] currentSizes) {
        LayoutInflater li = LayoutInflater.from(this);
        View view = li.inflate(R.layout.team_ratio_dialog, null);

        final LinearLayout team3Layout = view.findViewById(R.id.team3_layout);
        final LinearLayout team4Layout = view.findViewById(R.id.team4_layout);

        final EditText team1ET = view.findViewById(R.id.team1_ratio_et);
        final EditText team2ET = view.findViewById(R.id.team2_ratio_et);
        final EditText team3ET = view.findViewById(R.id.team3_ratio_et);
        final EditText team4ET = view.findViewById(R.id.team4_ratio_et);

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
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < numTeams; i++) {
                texts.add(editTexts[i].getText().toString());
            }
            if (vm.onTeamSizesEntered(texts)) {
                alertDialog.dismiss();
            }
        });
    }

    /** Called by the scoreboard adapter for a team header (host). */
    public void showTeamRatioDialog() {
        vm.onTeamRatiosRequested();
    }

    /** Called by the scoreboard adapter's kick action. */
    public void kickPlayerFromAdmin(byte playerID) {
        vm.onKickPlayer(playerID);
    }

    @Override
    public void showLocalPlayerSettings(TcpClient client) {
        PlayerSettingsAlertDialog dialog = new PlayerSettingsAlertDialog(FullscreenActivity.this);
        dialog.setLocal(client);
        dialog.show();
    }

    @Override
    public void showServerPlayerSettings(byte playerId, TcpServer server) {
        PlayerSettingsAlertDialog dialog = new PlayerSettingsAlertDialog(FullscreenActivity.this);
        dialog.setServer(playerId, server);
        dialog.show();
    }

    @Override
    public void showScoreboard(@NonNull PlayerDisplayData[] playerDisplayData, boolean isServer) {
        if (isFinishing() || isDestroyed()) return;

        if (mScoreboardDialog != null && mScoreboardDialog.isShowing() && mScoreboardAdapter != null) {
            mScoreboardAdapter.setData(playerDisplayData);
            return;
        }

        dismissScoreboardDialog();

        mScoreboardAdapter = new PlayerDisplayDataListAdapter(FullscreenActivity.this, playerDisplayData, true);
        AlertDialog.Builder alertDialog = new AlertDialog.Builder(FullscreenActivity.this, R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        alertDialog.setNegativeButton(R.string.ok, (dialog, id) -> dialog.cancel());
        LayoutInflater inflater = getLayoutInflater();
        View view = inflater.inflate(R.layout.player_data_dialog, null);
        alertDialog.setView(view);
        ListView listView = view.findViewById(R.id.player_list);
        listView.setAdapter(mScoreboardAdapter);
        if (isServer) {
            listView.setOnItemClickListener((parent, view1, position, id) -> {
                PlayerDisplayDataListAdapter.DisplayRow row = mScoreboardAdapter.getDisplayRow(position);
                if (row != null && row.type == PlayerDisplayDataListAdapter.DisplayRow.TYPE_TEAM_HEADER && row.teamNumber > 0) {
                    vm.onScoreboardTeamHeader();
                    return;
                }
                vm.onScoreboardPlayer(mScoreboardAdapter.getPlayerID(position));
            });
        }
        mScoreboardDialog = alertDialog.create();
        mScoreboardDialog.setOnDismissListener(dialog -> {
            mScoreboardDialog = null;
            mScoreboardAdapter = null;
            vm.onScoreboardDismissed();
        });
        mScoreboardDialog.show();
    }

    @Override
    public void closeScoreboard() {
        dismissScoreboardDialog();
    }

    private void dismissScoreboardDialog() {
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
    }

    @Override
    public void openDedicatedServer() {
        startActivity(new Intent(FullscreenActivity.this, DedicatedServerActivity.class));
    }

    // ---------------------------------------------------------------------------------------
    // Session rendering
    // ---------------------------------------------------------------------------------------

    /** Last session state drawn; each view is only touched when its value changes. */
    private SessionState mRenderedSession = null;

    private void renderSession(SessionState s) {
        SessionState p = mRenderedSession;
        mRenderedSession = s;
        if (p == null || p.getMaxHealth() != s.getMaxHealth())
            mHealthBar.setMax(s.getMaxHealth());
        if (p == null || p.getHealth() != s.getHealth())
            mHealthBar.setProgress(s.getHealth());
        if (p == null || p.getShotsRemaining() != s.getShotsRemaining())
            mShotsRemainingTV.setText(String.valueOf(s.getShotsRemaining()));
        if (s.getReloadBarShown() != null && (p == null || !s.getReloadBarShown().equals(p.getReloadBarShown()))) {
            if (s.getReloadBarShown()) {
                mShotsRemainingTV.setVisibility(View.INVISIBLE);
                mReloadBar.setVisibility(View.VISIBLE);
            } else {
                mReloadBar.setVisibility(View.INVISIBLE);
                mShotsRemainingTV.setVisibility(View.VISIBLE);
            }
        }
        if (p == null || p.getHitsTaken() != s.getHitsTaken())
            mHitsTakenTV.setText(String.valueOf(s.getHitsTaken()));
        if (p == null || p.getEliminationDisplay() != s.getEliminationDisplay())
            mEliminationCountTV.setText(String.valueOf(s.getEliminationDisplay()));
        if (p == null || p.getShotMode() != s.getShotMode()) {
            if (s.getShotMode() == Globals.SHOT_MODE_SINGLE)
                mShotModeTV.setText(R.string.shot_mode_single);
            else if (s.getShotMode() == Globals.SHOT_MODE_BURST)
                mShotModeTV.setText(R.string.shot_mode_burst3);
            else if (s.getShotMode() == Globals.SHOT_MODE_FULL_AUTO)
                mShotModeTV.setText(R.string.shot_mode_auto);
        }
        if ((p == null || p.getRecoilEnabled() != s.getRecoilEnabled()) && mRecoilModeTV != null)
            mRecoilModeTV.setText(s.getRecoilEnabled() ? R.string.recoil_enabled : R.string.recoil_disabled);
        if (s.getBattery() != null && (p == null || s.getBattery() != p.getBattery()) && mBatteryLevelIV != null) {
            switch (s.getBattery()) {
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
        if (p == null || !Objects.equals(p.getHitOverlay(), s.getHitOverlay()))
            renderHitOverlay(s);
        if (p == null || !p.getSpawn().equals(s.getSpawn()))
            renderSpawn(s.getSpawn());
        if (p == null || !Objects.equals(p.getShieldSeconds(), s.getShieldSeconds())) {
            if (s.getShieldSeconds() == null) {
                mRespawnShieldCountdownTV.setVisibility(View.GONE);
            } else {
                mRespawnShieldCountdownTV.setText(getString(R.string.respawn_shield_label, s.getShieldSeconds()));
                mRespawnShieldCountdownTV.setVisibility(View.VISIBLE);
            }
        }
        if (p == null || !p.getClock().equals(s.getClock()))
            renderClock(s.getClock());
    }

    private void renderHitOverlay(SessionState s) {
        String name = s.getHitOverlay();
        if (name != null) {
            // Show the "you're being hit" animation
            mHitIV.setVisibility(View.VISIBLE);
            mHitIV.setBackgroundResource(R.drawable.hit_animation);
            Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
            mHitIV.startAnimation(animationFadeOut);
            mHitAnimation = (AnimationDrawable) mHitIV.getBackground();
            mHitAnimation.start();
            mEliminatedByTV.setVisibility(View.VISIBLE);
            mEliminatedByTV.setText(name);
            mEliminatedByTV.startAnimation(animationFadeOut);
        } else {
            mHitIV.setVisibility(View.GONE);
            if (mHitAnimation != null) {
                mHitAnimation.stop();
                mHitAnimation = null;
            }
            if (s.getSpawn() instanceof SpawnPhase.None)
                mEliminatedByTV.setVisibility(View.INVISIBLE);
        }
    }

    private void renderSpawn(SpawnPhase spawn) {
        if (spawn instanceof SpawnPhase.Spawning) {
            SpawnPhase.Spawning spawning = (SpawnPhase.Spawning) spawn;
            mEliminatedTV.setText(spawning.getStartingGame() ? R.string.starting_game_label : R.string.eliminated_label);
            mEliminatedTV.setVisibility(View.VISIBLE);
            mEliminatedByTV.setText(spawning.getEliminatedBy());
            mEliminatedByTV.setVisibility(View.VISIBLE);
            mSpawnInTV.setText(getResources().getString(R.string.spawn_in_label, spawning.getSeconds()));
            mSpawnInTV.setVisibility(View.VISIBLE);
        } else if (spawn instanceof SpawnPhase.OutOfLives) {
            mEliminatedTV.setVisibility(View.VISIBLE);
            mEliminatedByTV.setText(((SpawnPhase.OutOfLives) spawn).getEliminatedBy());
            mEliminatedByTV.setVisibility(View.VISIBLE);
            mSpawnInTV.setText(R.string.dialog_out_of_lives);
            mSpawnInTV.setVisibility(View.VISIBLE);
        } else {
            mEliminatedTV.setVisibility(View.INVISIBLE);
            mEliminatedByTV.setVisibility(View.INVISIBLE);
            mSpawnInTV.setVisibility(View.GONE);
        }
    }

    private void renderClock(GameClock clock) {
        if (clock instanceof GameClock.Chronometer) {
            mGameTimer.setBase(((GameClock.Chronometer) clock).getBase());
            mGameTimer.start();
        } else if (clock instanceof GameClock.Countdown) {
            mGameCountDownTV.setText(((GameClock.Countdown) clock).getText());
        } else {
            mGameTimer.stop();
            String text = ((GameClock.Stopped) clock).getCountdownText();
            if (text != null && mGameCountDownTV != null)
                mGameCountDownTV.setText(text);
        }
    }
}
