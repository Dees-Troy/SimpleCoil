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

package com.simplecoil.simplecoil

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository
import com.simplecoil.simplecoil.databinding.ActivityFullscreenBinding
import com.simplecoil.simplecoil.ui.game.CompositeGameScreen
import com.simplecoil.simplecoil.ui.game.DialogCoordinator
import com.simplecoil.simplecoil.ui.game.GameNavigator
import com.simplecoil.simplecoil.ui.game.GameScreenRenderer
import com.simplecoil.simplecoil.ui.game.GameViewModel
import com.simplecoil.simplecoil.ui.game.GameViewModelFactory
import com.simplecoil.simplecoil.ui.game.SavedGameScreen
import com.simplecoil.simplecoil.ui.game.WindowController
import com.simplecoil.simplecoil.ui.permissions.BlePermissionController
import kotlinx.coroutines.launch

/**
 * The play screen: wires the views to [GameViewModel], which makes every decision and tells the
 * screen what to show through its GameScreen. Views, window flags and session values are drawn by
 * [GameScreenRenderer]; dialogs, toasts and menus are shown by [DialogCoordinator]. The permission
 * and Bluetooth flow in front of a connect is run by [BlePermissionController].
 */
class FullscreenActivity : AppCompatActivity(), GameNavigator {

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            vm.onQrScanned(result.contents)
        } else {
            Log.e(TAG, "QR scan cancelled or no result")
        }
    }

    private lateinit var vm: GameViewModel
    private lateinit var binding: ActivityFullscreenBinding
    private lateinit var renderer: GameScreenRenderer
    private lateinit var dialogs: DialogCoordinator
    private lateinit var permissions: BlePermissionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Back keeps the session running: send the task to the background instead of finishing.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
        renderer = GameScreenRenderer(binding, WindowController(window), lifecycleScope)
        vm = ViewModelProvider(this, GameViewModelFactory(application as SimpleCoilApp))[GameViewModel::class.java]
        dialogs = DialogCoordinator(this, vm, binding.useNetworkButton, binding.teamModeButton)
        vm.screen = CompositeGameScreen(renderer, dialogs, this)
        permissions = BlePermissionController(this, vm, ::startQRScan)
        val saved = savedInstanceState?.let {
            SavedGameScreen(
                it.getByte("mSelectedPlayerID", 0),
                it.getString("mDeviceAddress", ""),
                it.getBoolean("mUseNetwork", false),
                it.getInt("mNetworkMenuType", GameViewModel.NETWORK_TYPE_ENABLED),
            )
        }
        vm.onCreate(saved)

        // The session runs app-scoped (D4); render it whenever this screen exists.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                vm.session.collect(renderer::render)
            }
        }

        binding.reconnectWeaponButton.setOnClickListener { vm.onReconnectPressed() }
        binding.connectWeaponButton.setOnClickListener { vm.onConnectPressed() }
        binding.connectQrWeaponButton.setOnClickListener { permissions.scanQr() }
        binding.dedicatedServerButton.setOnClickListener { vm.onDedicatedServerPressed() }
        binding.teamMinusButton.visibility = View.VISIBLE
        binding.teamMinusButton.setOnClickListener { vm.onTeamMinusPressed() }
        binding.teamPlusButton.visibility = View.VISIBLE
        binding.teamPlusButton.setOnClickListener { vm.onTeamPlusPressed() }
        binding.startGameButton.setOnClickListener { vm.onStartGamePressed() }
        binding.playerSettingsButton.setOnClickListener { vm.onPlayerSettingsPressed() }
        binding.endGameButton.setOnClickListener { vm.onEndGamePressed() }
        binding.endNetworkGameButton.setOnClickListener { vm.onEndGamePressed() }
        binding.startRespawnButton.setOnClickListener { vm.onStartRespawnPressed() }
        binding.infoWv.loadUrl("file:///android_asset/simplecoil.html")
        binding.infoWv.visibility = View.VISIBLE
        binding.firingModeButton.setOnClickListener(dialogs::showFiringModeMenu)

        try {
            // Display app version
            binding.versionTv.text = packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Unable to get app information from PackageManager: " + e.message)
        }

        binding.useNetworkButton.setOnClickListener { vm.onNetworkButtonPressed() }
        binding.gameLimitButton.setOnClickListener { vm.onGameLimitPressed() }
        binding.teamModeButton.setOnClickListener { vm.onTeamModePressed() }
        binding.adminControlsButton.setOnClickListener(dialogs::showAdminMenu)
        binding.useNetworkButton.visibility = View.VISIBLE
        binding.playerDataButton.setOnClickListener { vm.openScoreboard() }
        vm.onViewsCreated()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val saved = vm.saveState()
        outState.putByte("mSelectedPlayerID", saved.selectedPlayerId)
        outState.putString("mDeviceAddress", saved.deviceAddress)
        outState.putBoolean("mUseNetwork", saved.useNetwork)
        outState.putInt("mNetworkMenuType", saved.networkMenuType)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onPause() {
        super.onPause()
        vm.onPause()
    }

    override fun onDestroy() {
        vm.onScreenDestroyed()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // QR pairing
    // ---------------------------------------------------------------------------------------

    private fun startQRScan() {
        try {
            val options = ScanOptions()
            options.setPrompt(getString(R.string.scan_qr_prompt))
            options.setOrientationLocked(false)
            options.setBeepEnabled(true)
            options.captureActivity = CaptureActivity::class.java
            barcodeLauncher.launch(options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch built-in QR scanner: " + e.message)
            Toast.makeText(this, "Could not launch QR scanner", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------------------------------
    // GameNavigator
    // ---------------------------------------------------------------------------------------

    override fun requestConnect() {
        if (GameViewModel.TEST_NETWORK) {
            renderer.showPlayScreen()
            return
        }
        permissions.connect()
    }

    override fun finishScreen() {
        finish()
    }

    override fun openDedicatedServer() {
        startActivity(Intent(this, DedicatedServerActivity::class.java))
    }

    /** Called by the scoreboard adapter for a team header. */
    fun showTeamRatioDialog() {
        vm.onTeamRatiosRequested()
    }

    /** Called by the scoreboard adapter's kick action. */
    fun kickPlayerFromAdmin(playerID: Byte) {
        vm.onKickPlayer(playerID)
    }

    companion object {
        private const val TAG = "scmain"

        // Still read by DedicatedServerActivity.
        const val PREF_NAME = UserPreferencesRepository.PREF_NAME
        const val PREF_START_TIME = UserPreferencesRepository.START_TIME
        const val PREF_LIMIT_TIME = UserPreferencesRepository.LIMIT_TIME
        const val PREF_LIMIT_LIVES = UserPreferencesRepository.LIMIT_LIVES
        const val PREF_LIMIT_SCORE = UserPreferencesRepository.LIMIT_SCORE
    }
}
