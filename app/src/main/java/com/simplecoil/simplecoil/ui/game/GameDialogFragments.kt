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
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.simplecoil.simplecoil.PlayerDisplayData
import com.simplecoil.simplecoil.PlayerSettingsAlertDialog
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.SimpleCoilApp
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.databinding.BlasterDisconnectedDialogBinding
import com.simplecoil.simplecoil.databinding.PlayerDataDialogBinding
import com.simplecoil.simplecoil.databinding.PlayerNameDialogBinding
import com.simplecoil.simplecoil.databinding.ServerIpDialogBinding
import com.simplecoil.simplecoil.ui.dialogs.AppDialogFragment
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardAdapter
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardRows

/*
 * The play screen's dialogs as DialogFragments, so they survive recreation. Each one is built
 * exactly like the AlertDialog FullscreenActivity used to build inline (same layout, theme,
 * inflater context, buttons and cancelability) and reports back to GameViewModel.
 */

/** A play screen dialog; reaches the activity's GameViewModel (creating it if restored first). */
abstract class GameDialogFragment : AppDialogFragment() {
    protected val vm: GameViewModel by lazy {
        val activity = requireActivity()
        ViewModelProvider(activity, GameViewModelFactory(activity.application as SimpleCoilApp))[GameViewModel::class.java]
    }
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
