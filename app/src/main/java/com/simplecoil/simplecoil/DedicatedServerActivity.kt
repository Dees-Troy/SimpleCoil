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

package com.simplecoil.simplecoil

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.databinding.ActivityDedicatedServerBinding
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.ui.dialogs.ConfirmDialog
import com.simplecoil.simplecoil.ui.dialogs.GameLimitDialog
import com.simplecoil.simplecoil.ui.dialogs.StartingTimerDialog
import com.simplecoil.simplecoil.ui.dialogs.TeamRatioDialog
import com.simplecoil.simplecoil.ui.playersettings.PlayerSettingsDialog
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardAdapter
import com.simplecoil.simplecoil.ui.server.DedicatedServerRenderer
import com.simplecoil.simplecoil.ui.server.DedicatedServerViewModel
import com.simplecoil.simplecoil.ui.server.ServerUiEffect
import com.simplecoil.simplecoil.ui.sheet.FillingBottomSheet
import kotlinx.coroutines.launch

/**
 * The dedicated server's screen (dedicated server design §6.2). The server runs in its own
 * service and outlives this screen; the screen shows its state and sends the operator's
 * commands through [DedicatedServerViewModel]. Back stops the server, after a confirmation.
 */
class DedicatedServerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDedicatedServerBinding
    private lateinit var renderer: DedicatedServerRenderer
    private val vm: DedicatedServerViewModel by viewModels { DedicatedServerViewModel.Factory(application as SimpleCoilApp) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDedicatedServerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The device stays awake while this screen is in the foreground (Q3); the service holds Wi-Fi
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        listenForDialogResults()

        val scoreboard = ScoreboardAdapter(false, object : ScoreboardAdapter.Listener {
            override fun onTeamHeaderClicked() = vm.onTeamHeaderClicked()
            override fun onPlayerClicked(playerId: Byte) = vm.onPlayerClicked(playerId)
            override fun onKickClicked(playerId: Byte) = vm.onKick(playerId)
        })
        binding.playerList.layoutManager = LinearLayoutManager(this)
        binding.playerList.adapter = scoreboard
        renderer = DedicatedServerRenderer(binding, scoreboard)
        wireControls()
        binding.versionTv.text = packageManager.getPackageInfo(packageName, 0).versionName

        onBackPressedDispatcher.addCallback(this) { vm.onBackPressed() }
        // After the Back above, so an expanded sheet collapses before Back asks to stop the server
        attachPlayerSheet()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect { state -> state?.let { renderer.render(it) } } }
                launch { vm.effects.collect { onEffect(it) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onScreenVisible(true)
    }

    override fun onStop() {
        super.onStop()
        vm.onScreenVisible(false)
        renderer.pause()
    }

    private fun wireControls() = with(binding) {
        // Click, not checked-change: the renderer checks the segments from the server's state
        teamMode2teamsButton.setOnClickListener { onTeamModeClicked(TeamMode.TWO_TEAMS) }
        teamMode4teamsButton.setOnClickListener { onTeamModeClicked(TeamMode.FOUR_TEAMS) }
        teamModeFfaButton.setOnClickListener { onTeamModeClicked(TeamMode.FREE_FOR_ALL) }
        sortTeamsButton.setOnClickListener { vm.onSortTeams() }
        gpsModeDisabledButton.setOnClickListener { onGpsModeClicked(GameSettingsRepository.GPS_DISABLED) }
        gpsModeTeammateButton.setOnClickListener { onGpsModeClicked(GameSettingsRepository.GPS_TEAMMATE) }
        gpsModeAllButton.setOnClickListener { onGpsModeClicked(GameSettingsRepository.GPS_ALL) }
        gameLimitButton.setOnClickListener {
            val limits = vm.state.value?.limits ?: return@setOnClickListener
            showDialog(GameLimitDialog.newInstance(limits, true), GameLimitDialog.REQUEST_KEY)
        }
        startingTimerButton.setOnClickListener {
            val current = vm.state.value?.startTimeSeconds ?: GameSettingsRepository.START_TIME_SECONDS
            showDialog(StartingTimerDialog.newInstance(current, DedicatedServerViewModel.MAX_START_TIME_SECONDS), StartingTimerDialog.REQUEST_KEY)
        }
        startGameButton.setOnClickListener { vm.onStartPressed() }
        endGameButton.setOnClickListener { vm.onEndPressed() }
        resetGameButton.setOnClickListener { vm.onResetPressed() }
        // Click, not checked-change: the renderer sets the switches from the server's state
        allowJoinSwitch.setOnClickListener { vm.onAllowJoinChanged(allowJoinSwitch.isChecked) }
        onlyServerSettingsSwitch.setOnClickListener { vm.onOnlyServerSettingsChanged(onlyServerSettingsSwitch.isChecked) }
        allowChooseTeamsSwitch.setOnClickListener { vm.onAllowChooseTeamsChanged(allowChooseTeamsSwitch.isChecked) }
    }

    /** The players in a bottom sheet under the controls, never over Start/End/Reset; the large-screen layout shows them beside the controls instead. */
    private fun attachPlayerSheet() {
        val b = binding
        val sheet = b.playerSheet ?: return
        FillingBottomSheet(this, sheet, b.playerSheetHeader!!, b.serverCoordinator!!, b.serverContent!!, b.serverScroll!!,
            resources.getDimensionPixelSize(R.dimen.player_sheet_min_peek), keepAbove = b.phaseControls) { b.playerList.scrollToPosition(0) }
    }

    /** Tapping the chosen segment again changes nothing, so it doesn't reset the game. */
    private fun onTeamModeClicked(mode: TeamMode) {
        if (vm.state.value?.teamMode != mode) vm.onTeamModeSelected(mode)
    }

    private fun onGpsModeClicked(mode: Int) {
        val state = vm.state.value ?: return
        val current = if (state.useGps) state.gpsMode else GameSettingsRepository.GPS_DISABLED
        if (current != mode) vm.onGpsModeSelected(mode)
    }

    private fun listenForDialogResults() {
        val fm = supportFragmentManager
        fm.setFragmentResultListener(GameLimitDialog.REQUEST_KEY, this) { _, result ->
            vm.onGameLimitsEntered(GameLimitDialog.texts(result))
        }
        fm.setFragmentResultListener(StartingTimerDialog.REQUEST_KEY, this) { _, result ->
            vm.onStartTimeEntered(StartingTimerDialog.text(result))
        }
        fm.setFragmentResultListener(TeamRatioDialog.REQUEST_KEY, this) { _, result -> vm.onTeamSizesEntered(TeamRatioDialog.sizes(result)) }
        fm.setFragmentResultListener(PlayerSettingsDialog.REQUEST_KEY, this) { _, result ->
            vm.onPlayerSettingsSaved(PlayerSettingsDialog.result(result))
        }
        fm.setFragmentResultListener(REQUEST_RESET, this) { _, _ -> vm.onResetConfirmed() }
        fm.setFragmentResultListener(REQUEST_STOP, this) { _, _ -> vm.onStopConfirmed() }
    }

    private fun onEffect(effect: ServerUiEffect) {
        when (effect) {
            is ServerUiEffect.Toast ->
                Toast.makeText(applicationContext, getString(effect.text, *effect.args.toTypedArray()), Toast.LENGTH_SHORT).show()
            is ServerUiEffect.ConfirmReset -> showDialog(ConfirmDialog.newInstance(REQUEST_RESET,
                quantity(R.plurals.dedicated_reset_confirm, effect.disconnectedPlayers), R.string.reset_game_button), REQUEST_RESET)
            is ServerUiEffect.ConfirmStop -> showDialog(ConfirmDialog.newInstance(REQUEST_STOP,
                quantity(R.plurals.dedicated_stop_confirm, effect.players), R.string.dedicated_stop_button), REQUEST_STOP)
            is ServerUiEffect.ShowTeamRatio ->
                showDialog(TeamRatioDialog.newInstance(effect.teamCount, effect.sizes), TeamRatioDialog.REQUEST_KEY)
            is ServerUiEffect.ShowPlayerSettings -> showDialog(PlayerSettingsDialog.server(effect.playerId, effect.values,
                effect.teamCount, effect.team, effect.indexInTeam), PlayerSettingsDialog.REQUEST_KEY)
            ServerUiEffect.Finish -> finish()
        }
    }

    /** Shows [dialog] unless one is already showing under [tag] or the state is saved. */
    private fun showDialog(dialog: DialogFragment, tag: String) {
        val fm = supportFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(tag) != null) return
        dialog.show(fm, tag)
    }

    private fun quantity(id: Int, count: Int): String = resources.getQuantityString(id, count, count)

    private companion object {
        const val REQUEST_RESET = "serverReset"
        const val REQUEST_STOP = "serverStop"
    }
}
