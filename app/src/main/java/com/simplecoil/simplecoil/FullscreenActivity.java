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
import androidx.lifecycle.LifecycleOwnerKt;
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
import com.simplecoil.simplecoil.databinding.ActivityFullscreenBinding;
import com.simplecoil.simplecoil.ui.game.CompositeGameScreen;
import com.simplecoil.simplecoil.ui.game.GameDialogs;
import com.simplecoil.simplecoil.ui.game.GameNavigator;
import com.simplecoil.simplecoil.ui.game.GameScreenRenderer;
import com.simplecoil.simplecoil.ui.game.WindowController;
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
 * The play screen: dialogs, menus and permissions. All decisions are made by
 * {@link GameViewModel}, which tells this screen what to show through its GameScreen; views,
 * window flags and session values are drawn by {@link GameScreenRenderer}.
 */
public class FullscreenActivity extends AppCompatActivity implements PopupMenu.OnMenuItemClickListener, GameDialogs, GameNavigator {
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

    private ActivityFullscreenBinding binding;
    private GameScreenRenderer renderer;
    private PopupMenu mNetworkPopup = null;
    private AlertDialog mBlasterDisconnectDialog = null;
    private AlertDialog mScoreboardDialog = null;
    private PlayerDisplayDataListAdapter mScoreboardAdapter = null;

    private FragmentManager mFragmentMgr = null;
    private Fragment mMapFragment = null;

    final private int REQUEST_CODE_LOCATION_PERMISSIONS = 1022;
    final private int REQUEST_CODE_CAMERA_PERMISSION = 1024;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityFullscreenBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
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
        renderer = new GameScreenRenderer(binding, new WindowController(getWindow()), LifecycleOwnerKt.getLifecycleScope(this));
        vm = new ViewModelProvider(this, new GameViewModelFactory((SimpleCoilApp) getApplication())).get(GameViewModel.class);
        vm.setScreen(new CompositeGameScreen(renderer, this, this));
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
            if (health != null) {
                binding.healthPb.setProgress(health);
            }
        });
        mMainViewModel.getAmmoLiveData().observe(this, ammo -> {
            if (ammo != null) {
                binding.shotsRemainingTv.setText(String.valueOf(ammo));
            }
        });

        // The session runs app-scoped (D4); render it whenever this screen exists.
        FlowCollectors.collectWhileAtLeast(this, Lifecycle.State.CREATED, vm.getSession(), renderer::render);

        mFragmentMgr = getSupportFragmentManager();
        binding.reconnectWeaponButton.setOnClickListener(v -> vm.onReconnectPressed());
        binding.connectWeaponButton.setOnClickListener(v -> vm.onConnectPressed());
        binding.connectQrWeaponButton.setOnClickListener((v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CODE_CAMERA_PERMISSION);
                    return;
                }
            }
            startQRScan();
        }));
        binding.dedicatedServerButton.setOnClickListener(v -> vm.onDedicatedServerPressed());
        binding.teamMinusButton.setVisibility(View.VISIBLE);
        binding.teamMinusButton.setOnClickListener(v -> vm.onTeamMinusPressed());
        binding.teamPlusButton.setVisibility(View.VISIBLE);
        binding.teamPlusButton.setOnClickListener(v -> vm.onTeamPlusPressed());
        binding.startGameButton.setOnClickListener(v -> vm.onStartGamePressed());
        binding.playerSettingsButton.setOnClickListener(v -> vm.onPlayerSettingsPressed());
        binding.endGameButton.setOnClickListener(v -> vm.onEndGamePressed());
        binding.endNetworkGameButton.setOnClickListener(v -> vm.onEndGamePressed());
        binding.infoWv.loadUrl("file:///android_asset/simplecoil.html");
        binding.infoWv.setVisibility(View.VISIBLE);
        binding.firingModeButton.setOnClickListener((v -> {
            PopupMenu popup = new PopupMenu(new ContextThemeWrapper(FullscreenActivity.this, R.style.AppTheme_PopupOverlay), v);
            MenuInflater inflater = popup.getMenuInflater();
            inflater.inflate(R.menu.firing_mode_menu, popup.getMenu());
            popup.setOnMenuItemClickListener(FullscreenActivity.this);
            popup.show();
            Toast.makeText(getApplicationContext(), getString(R.string.firing_mode_cone_toast), Toast.LENGTH_LONG).show();
        }));

        try {
            // Display app version
            PackageInfo pInfo = this.getPackageManager().getPackageInfo(getPackageName(), 0);
            binding.versionTv.setText(pInfo.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unable to get app information from PackageManager: " + e.getMessage());
        }

        binding.useNetworkButton.setOnClickListener(v -> vm.onNetworkButtonPressed());
        binding.gameLimitButton.setOnClickListener(v -> vm.onGameLimitPressed());
        binding.shuffleTeamsButton.setOnClickListener(v -> vm.onShuffleTeamsPressed());
        binding.adminControlsButton.setOnClickListener(this::showAdminControlsMenu);
        binding.useNetworkButton.setVisibility(View.VISIBLE);
        binding.playerDataButton.setOnClickListener(v -> vm.openScoreboard());
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
        mFragmentTransc.add(binding.playLayout.getId(), mMapFragment);
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
            renderer.showPlayScreen();
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
    // GameDialogs / GameNavigator: connection
    // ---------------------------------------------------------------------------------------

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
    // GameDialogs: game
    // ---------------------------------------------------------------------------------------

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
    // GameDialogs: network
    // ---------------------------------------------------------------------------------------

    @Override
    public void prepareNetworkMenu() {
        if (mNetworkPopup == null) {
            mNetworkPopup = new PopupMenu(new ContextThemeWrapper(FullscreenActivity.this, R.style.AppTheme_PopupOverlay), binding.useNetworkButton);
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
        PopupMenu popup = new PopupMenu(new ContextThemeWrapper(FullscreenActivity.this, R.style.AppTheme_PopupOverlay), binding.useNetworkButton);
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

    // ---------------------------------------------------------------------------------------
    // GameDialogs: dialogs
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
}
