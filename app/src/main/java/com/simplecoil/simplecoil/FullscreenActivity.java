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

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwnerKt;
import androidx.lifecycle.ViewModelProvider;

import kotlin.Unit;

import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository;
import com.simplecoil.simplecoil.ui.common.FlowCollectors;
import com.simplecoil.simplecoil.databinding.ActivityFullscreenBinding;
import com.simplecoil.simplecoil.ui.game.CompositeGameScreen;
import com.simplecoil.simplecoil.ui.game.DialogCoordinator;
import com.simplecoil.simplecoil.ui.permissions.BlePermissionController;
import com.simplecoil.simplecoil.ui.game.GameNavigator;
import com.simplecoil.simplecoil.ui.game.GameScreenRenderer;
import com.simplecoil.simplecoil.ui.game.WindowController;
import com.simplecoil.simplecoil.ui.game.GameViewModel;
import com.simplecoil.simplecoil.ui.game.GameViewModelFactory;
import com.simplecoil.simplecoil.ui.game.SavedGameScreen;


/**
 * The play screen: wires the views to {@link GameViewModel}, which makes every decision and tells
 * the screen what to show through its GameScreen. Views, window flags and session values are drawn
 * by {@link GameScreenRenderer}; dialogs, toasts and menus are shown by {@link DialogCoordinator}.
 * The permission and Bluetooth flow in front of a connect is run by {@link BlePermissionController}.
 */
public class FullscreenActivity extends AppCompatActivity implements GameNavigator {
    private static final String TAG = "scmain";

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

    private GameViewModel vm;

    private ActivityFullscreenBinding binding;
    private GameScreenRenderer renderer;
    private DialogCoordinator dialogs;
    private BlePermissionController permissions;

    private FragmentManager mFragmentMgr = null;
    private Fragment mMapFragment = null;


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
        dialogs = new DialogCoordinator(this, vm, binding.useNetworkButton);
        vm.setScreen(new CompositeGameScreen(renderer, dialogs, this));
        permissions = new BlePermissionController(this, vm, () -> {
            startQRScan();
            return Unit.INSTANCE;
        });
        SavedGameScreen saved = null;
        if (savedInstanceState != null) {
            saved = new SavedGameScreen(savedInstanceState.getByte("mSelectedPlayerID", (byte)0),
                    savedInstanceState.getString("mDeviceAddress", ""),
                    savedInstanceState.getBoolean("mUseNetwork", false),
                    savedInstanceState.getBoolean("mIsServer", false),
                    savedInstanceState.getInt("mNetworkMenuType", GameViewModel.NETWORK_TYPE_ENABLED));
        }
        vm.onCreate(saved);

        // The session runs app-scoped (D4); render it whenever this screen exists.
        FlowCollectors.collectWhileAtLeast(this, Lifecycle.State.CREATED, vm.getSession(), renderer::render);

        mFragmentMgr = getSupportFragmentManager();
        binding.reconnectWeaponButton.setOnClickListener(v -> vm.onReconnectPressed());
        binding.connectWeaponButton.setOnClickListener(v -> vm.onConnectPressed());
        binding.connectQrWeaponButton.setOnClickListener(v -> permissions.scanQr());
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
        binding.firingModeButton.setOnClickListener(dialogs::showFiringModeMenu);

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
        binding.adminControlsButton.setOnClickListener(dialogs::showAdminMenu);
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
    // QR pairing
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
    public void requestConnect() {
        if (GameViewModel.TEST_NETWORK) {
            renderer.showPlayScreen();
            return;
        }
        permissions.connect();
    }

    // ---------------------------------------------------------------------------------------
    // GameNavigator
    // ---------------------------------------------------------------------------------------

    @Override
    public void finishScreen() {
        finish();
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
    public void openDedicatedServer() {
        startActivity(new Intent(FullscreenActivity.this, DedicatedServerActivity.class));
    }
}
