/*
 * Copyright (C) 2026 Ethan Yonker
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

package com.simplecoil.simplecoil.ui.game

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.simplecoil.simplecoil.PlayerDisplayData
import com.simplecoil.simplecoil.PlayerSettingsAlertDialog
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.SimpleCoilApp
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.databinding.BlasterDisconnectedDialogBinding
import com.simplecoil.simplecoil.databinding.GameLimitDialogBinding
import com.simplecoil.simplecoil.databinding.PlayerDataDialogBinding
import com.simplecoil.simplecoil.databinding.PlayerNameDialogBinding
import com.simplecoil.simplecoil.databinding.ServerIpDialogBinding
import com.simplecoil.simplecoil.databinding.TeamRatioDialogBinding
import com.simplecoil.simplecoil.domain.rules.LimitKind
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardAdapter
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardRows

/*
 * The play screen's dialogs as DialogFragments, so they survive recreation. Each one is built
 * exactly like the AlertDialog FullscreenActivity used to build inline (same layout, theme,
 * inflater context, buttons and cancelability) and reports back to GameViewModel.
 */

/** A play screen dialog; reaches the activity's GameViewModel (creating it if restored first). */
abstract class GameDialogFragment : DialogFragment() {
    protected val vm: GameViewModel by lazy {
        val activity = requireActivity()
        ViewModelProvider(activity, GameViewModelFactory(activity.application as SimpleCoilApp))[GameViewModel::class.java]
    }

    /** The dialogs' layouts were inflated with the application context; keep their look. */
    protected val appInflater: LayoutInflater get() = LayoutInflater.from(requireContext().applicationContext)

    protected fun alertBuilder() = AlertDialog.Builder(requireContext(), R.style.Theme_AppCompat_DayNight_Dialog_Alert)
}

/** "Blaster disconnected" with Disconnect & Quit; shown until the link recovers. */
class BlasterDisconnectedDialog : GameDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        return alertBuilder()
            .setView(BlasterDisconnectedDialogBinding.inflate(appInflater).root)
            .setNegativeButton(R.string.blaster_disconnected_disconnect_quit) { dialog, _ ->
                vm.onDisconnectQuit()
                dialog.cancel()
            }
            .create()
    }
}

class EndGameConfirmDialog : GameDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.end_game_dialog_title)
            .setMessage(R.string.end_game_dialog_message)
            .setPositiveButton(R.string.yes) { _, _ -> vm.onEndGameConfirmed() }
            .setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
            .create()
}

/** An admin asked to reset the server after a game. */
class ResetConfirmDialog : GameDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.dedicated_reset_confirm, requireArguments().getInt(ARG_DISCONNECTED)))
            .setPositiveButton(R.string.reset_game_button) { _, _ -> vm.onResetConfirmed() }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .create()

    companion object {
        private const val ARG_DISCONNECTED = "disconnected"

        fun newInstance(disconnectedPlayers: Int) =
            ResetConfirmDialog().apply { arguments = bundleOf(ARG_DISCONNECTED to disconnectedPlayers) }
    }
}

class PlayerNameDialog : GameDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val binding = PlayerNameDialogBinding.inflate(appInflater)
        binding.playerNameEt.setText(requireArguments().getString(ARG_TEXT))
        return alertBuilder()
            .setView(binding.root)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                vm.onPlayerNameEntered(binding.playerNameEt.text.toString().trim())
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .create()
    }

    companion object {
        fun newInstance(current: String) = PlayerNameDialog().apply { arguments = bundleOf(ARG_TEXT to current) }
    }
}

class ServerIpDialog : GameDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val binding = ServerIpDialogBinding.inflate(appInflater)
        binding.serverIpEt.setText(requireArguments().getString(ARG_TEXT))
        return alertBuilder()
            .setView(binding.root)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                vm.onServerIpEntered(binding.serverIpEt.text.toString())
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .create()
    }

    companion object {
        fun newInstance(suggestion: String) = ServerIpDialog().apply { arguments = bundleOf(ARG_TEXT to suggestion) }
    }
}

class GameLimitDialog : GameDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val binding = GameLimitDialogBinding.inflate(appInflater)
        val time = binding.gameLimitTimeRadio
        val lives = binding.gameLimitLivesRadio
        val score = binding.gameLimitScoreRadio
        time.isChecked = true
        lives.isChecked = false
        score.isChecked = false
        score.visibility = if (requireArguments().getBoolean(ARG_ALLOW_SCORE)) View.VISIBLE else View.GONE
        time.setOnClickListener {
            time.isChecked = true
            lives.isChecked = false
            score.isChecked = false
        }
        lives.setOnClickListener {
            time.isChecked = false
            lives.isChecked = true
            score.isChecked = false
        }
        score.setOnClickListener {
            time.isChecked = false
            lives.isChecked = false
            score.isChecked = true
        }
        return alertBuilder()
            .setView(binding.root)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                val kind = when {
                    time.isChecked -> LimitKind.TIME
                    lives.isChecked -> LimitKind.LIVES
                    else -> LimitKind.SCORE
                }
                vm.onGameLimitEntered(binding.gameLimitEt.text.toString(), kind)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .create()
    }

    companion object {
        private const val ARG_ALLOW_SCORE = "allowScoreLimit"

        fun newInstance(allowScoreLimit: Boolean) = GameLimitDialog().apply { arguments = bundleOf(ARG_ALLOW_SCORE to allowScoreLimit) }
    }
}

/** Team sizes; stays open until GameViewModel accepts them. */
class TeamRatioDialog : GameDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val numTeams = requireArguments().getInt(ARG_NUM_TEAMS)
        val sizes = requireArguments().getIntArray(ARG_SIZES)!!
        val binding = TeamRatioDialogBinding.inflate(LayoutInflater.from(requireContext()))
        val editTexts = listOf(binding.team1RatioEt, binding.team2RatioEt, binding.team3RatioEt, binding.team4RatioEt)
        editTexts[0].setText(sizes[0].toString())
        editTexts[1].setText(sizes[1].toString())
        if (numTeams == 4) {
            binding.team3Layout.visibility = View.VISIBLE
            binding.team4Layout.visibility = View.VISIBLE
            editTexts[2].setText(sizes[2].toString())
            editTexts[3].setText(sizes[3].toString())
        } else {
            binding.team3Layout.visibility = View.GONE
            binding.team4Layout.visibility = View.GONE
        }
        val dialog = alertBuilder()
            .setView(binding.root)
            .setTitle("Configure Team Sizes")
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel) { d, _ -> d.cancel() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (vm.onTeamSizesEntered(editTexts.take(numTeams).map { it.text.toString() })) dialog.dismiss()
            }
        }
        return dialog
    }

    companion object {
        private const val ARG_NUM_TEAMS = "numTeams"
        private const val ARG_SIZES = "sizes"

        fun newInstance(numTeams: Int, sizes: IntArray) =
            TeamRatioDialog().apply { arguments = bundleOf(ARG_NUM_TEAMS to numTeams, ARG_SIZES to sizes) }
    }
}

/** The local player's settings. */
class PlayerSettingsDialog : GameDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = PlayerSettingsAlertDialog(requireActivity())
        dialog.setLocal(vm.tcpClient)
        return dialog
    }

    companion object {
        fun local() = PlayerSettingsDialog().apply { arguments = Bundle() }
    }
}

/** The scoreboard; GameViewModel pushes new data into it while it is open. */
class ScoreboardDialog : GameDialogFragment() {
    private var adapter: ScoreboardAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated with the activity: ask for fresh data, which arrives through setData().
        if (savedInstanceState != null) vm.openScoreboard()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val binding = PlayerDataDialogBinding.inflate(activity.layoutInflater)
        val players = vm.scoreboardPlayers ?: arrayOfNulls(GameSettingsRepository.MAX_PLAYER_ID + 2)
        val adapter = ScoreboardAdapter(isClient = true, listener = object : ScoreboardAdapter.Listener {
            override fun onTeamHeaderClicked() = vm.onTeamRatiosRequested()
            override fun onKickClicked(playerId: Byte) = vm.onKickPlayer(playerId)
        })
        this.adapter = adapter
        binding.playerList.layoutManager = LinearLayoutManager(activity)
        binding.playerList.adapter = adapter
        setData(players)
        return alertBuilder()
            .setNegativeButton(R.string.ok) { dialog, _ -> dialog.cancel() }
            .setView(binding.root)
            .create()
    }

    fun setData(players: Array<PlayerDisplayData?>) {
        adapter?.submitList(ScoreboardRows.build(players, vm.scoreboardContext()))
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Closed by the user, not torn down with the activity.
        if (!isStateSaved) vm.onScoreboardDismissed()
    }
}

private const val ARG_TEXT = "text"
