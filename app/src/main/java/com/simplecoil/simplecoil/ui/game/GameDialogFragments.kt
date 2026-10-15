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
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.simplecoil.simplecoil.PlayerDisplayData
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.SimpleCoilApp
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.databinding.BlasterDisconnectedDialogBinding
import com.simplecoil.simplecoil.databinding.ConnectStatusDialogBinding
import com.simplecoil.simplecoil.databinding.PlayerDataDialogBinding
import com.simplecoil.simplecoil.databinding.PlayerNameDialogBinding
import com.simplecoil.simplecoil.databinding.ServerIpDialogBinding
import com.simplecoil.simplecoil.ui.dialogs.AppDialogFragment
import com.simplecoil.simplecoil.ui.scoreboard.ScoreMatrixState
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

/** Progress while connecting to a blaster; shows [GameViewModel.connectStatus], with Cancel. */
class ConnectStatusDialog : GameDialogFragment() {
    private var binding: ConnectStatusDialogBinding? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val b = ConnectStatusDialogBinding.inflate(appInflater)
        binding = b
        refresh()
        return alertBuilder()
            .setView(b.root)
            .setNegativeButton(R.string.cancel) { _, _ -> vm.onConnectCancelled() }
            .create()
    }

    /** Show the view model's current status. */
    fun refresh() {
        val status = vm.connectStatus ?: return
        binding?.connectStatusTv?.setText(when (status) {
            ConnectStatus.SCANNING -> R.string.connect_status_scanning
            ConnectStatus.CONNECTING -> R.string.connect_status_connecting
            ConnectStatus.COMMUNICATING -> R.string.connect_status_communicating
        })
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
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
        alertBuilder()
            .setTitle(R.string.end_game_dialog_title)
            .setMessage(R.string.end_game_dialog_message)
            .setPositiveButton(R.string.yes) { _, _ -> vm.onEndGameConfirmed() }
            .setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
            .create()
}

/** An admin asked to reset the server after a game. */
class ResetConfirmDialog : GameDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        alertBuilder()
            .setMessage(requireArguments().getInt(ARG_DISCONNECTED).let { resources.getQuantityString(R.plurals.dedicated_reset_confirm, it, it) })
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
        binding.playerNameEt.filters = arrayOf(PlayerNameInputFilter())
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

/**
 * The scoreboard; GameViewModel pushes new data into it while it is open. Two faces: the list, and
 * the score matrix (SCORE_MATRIX_DESIGN.md §5.4), switched with the neutral button. The face is
 * the view model's, so a recreated dialog comes back on the same one.
 */
class ScoreboardDialog : GameDialogFragment() {
    private var adapter: ScoreboardAdapter? = null
    private var binding: PlayerDataDialogBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated with the activity: ask for fresh data (and the matrix, if that face was
        // showing), which arrives through setData() and setMatrix().
        if (savedInstanceState != null) vm.openScoreboard()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val binding = PlayerDataDialogBinding.inflate(activity.layoutInflater)
        this.binding = binding
        binding.scoreMatrix.maxHeightPx = (resources.displayMetrics.heightPixels * MATRIX_MAX_HEIGHT_FRACTION).toInt()
        val players = vm.scoreboardPlayers ?: arrayOfNulls(GameSettingsRepository.MAX_PLAYER_ID + 2)
        val adapter = ScoreboardAdapter(isClient = true, listener = object : ScoreboardAdapter.Listener {
            override fun onTeamHeaderClicked() = vm.onTeamRatiosRequested()
            override fun onKickClicked(playerId: Byte) = vm.onKickPlayer(playerId)
        })
        this.adapter = adapter
        binding.playerList.layoutManager = LinearLayoutManager(activity)
        binding.playerList.adapter = adapter
        setData(players)
        showFace()
        val builder = alertBuilder()
            .setNegativeButton(R.string.ok) { dialog, _ -> dialog.cancel() }
            .setView(binding.root)
        // Only a dedicated server has a matrix. The listener is set once shown: a button's
        // own listener would close the dialog.
        val matrixAvailable = vm.networkMenuType == GameViewModel.NETWORK_TYPE_JOINED
        if (matrixAvailable) builder.setNeutralButton(R.string.score_matrix, null)
        val dialog = builder.create()
        if (matrixAvailable) {
            dialog.setOnShowListener {
                val toggle = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                toggle.setText(if (vm.scoreMatrixVisible) R.string.scoreboard else R.string.score_matrix)
                toggle.setOnClickListener {
                    vm.onScoreMatrixToggled()
                    showFace()
                    toggle.setText(if (vm.scoreMatrixVisible) R.string.scoreboard else R.string.score_matrix)
                }
            }
        }
        return dialog
    }

    fun setData(players: Array<PlayerDisplayData?>) {
        adapter?.submitList(ScoreboardRows.build(players, vm.scoreboardContext()))
    }

    /** Shows the list or the matrix, as the view model has it. */
    private fun showFace() {
        val b = binding ?: return
        val matrix = vm.scoreMatrixVisible
        b.playerList.visibility = if (matrix) View.GONE else View.VISIBLE
        b.scoreMatrixFace.visibility = if (matrix) View.VISIBLE else View.GONE
        if (matrix) setMatrix(vm.scoreMatrixState ?: ScoreMatrixState.Loading)
    }

    /** The matrix face's content: progress, a message, or the grid. */
    fun setMatrix(state: ScoreMatrixState) {
        val b = binding ?: return
        val ready = state as? ScoreMatrixState.Ready
        if (ready != null) b.scoreMatrix.setMatrix(ready.matrix)
        val message = when {
            state == ScoreMatrixState.Unsupported -> R.string.score_matrix_unsupported
            ready != null && ready.matrix.isEmpty -> R.string.score_matrix_empty
            else -> 0
        }
        b.scoreMatrixProgress.visibility = if (state == ScoreMatrixState.Loading) View.VISIBLE else View.GONE
        b.scoreMatrix.visibility = if (ready != null && message == 0) View.VISIBLE else View.GONE
        b.scoreMatrixMessage.visibility = if (message != 0) View.VISIBLE else View.GONE
        if (message != 0) b.scoreMatrixMessage.setText(message)
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Closed by the user, not torn down with the activity.
        if (!isStateSaved) vm.onScoreboardDismissed()
    }
}

private const val ARG_TEXT = "text"

/** The score matrix grows to at most this much of the window's height, then scrolls. */
private const val MATRIX_MAX_HEIGHT_FRACTION = 0.8f
