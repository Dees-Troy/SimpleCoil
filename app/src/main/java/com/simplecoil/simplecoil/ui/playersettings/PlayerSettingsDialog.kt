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

package com.simplecoil.simplecoil.ui.playersettings

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.databinding.PlayerSettingsDialogBinding
import com.simplecoil.simplecoil.ui.dialogs.TeamSwapDialog

/**
 * One player's settings, for both screens (DEDICATED_SERVER_DESIGN.md §7.2). [local] edits this
 * phone's player; [server] edits a network player and adds the admin, recoil, team, kick and
 * apply-to-all/team controls. The host gets the result under [REQUEST_KEY].
 */
class PlayerSettingsDialog : DialogFragment() {

    private var firingMode = GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE
    private var recoilSetting = GameSettingsRepository.RECOIL_SETTING_DEFAULT
    private var targetTeam = 0
    /** Who on the full [targetTeam] the player trades places with, or 0. */
    private var swapWith: Byte = 0
    private lateinit var binding: PlayerSettingsDialogBinding

    private val isServer get() = requireArguments().getBoolean(ARG_SERVER)
    private val playerId get() = requireArguments().getByte(ARG_PLAYER_ID)
    /** Teams in the game; 0 in free-for-all. */
    private val teamCount get() = requireArguments().getInt(ARG_TEAM_COUNT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager.setFragmentResultListener(TeamSwapDialog.REQUEST_KEY, this) { _, result ->
            val swap = TeamSwapDialog.result(result)
            targetTeam = swap.team
            swapWith = swap.swapWith
            showTargetTeam()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val initial = valuesOf(args.getBundle(ARG_VALUES)!!)
        firingMode = savedInstanceState?.getInt(STATE_FIRING_MODE) ?: initial.firingMode
        recoilSetting = savedInstanceState?.getInt(STATE_RECOIL) ?: initial.recoilSetting
        targetTeam = savedInstanceState?.getInt(STATE_TARGET_TEAM) ?: 0
        swapWith = savedInstanceState?.getByte(STATE_SWAP_WITH) ?: 0

        val builder = AlertDialog.Builder(requireContext(), R.style.AppDialogTheme)
        @Suppress("UseGetLayoutInflater") // in the dialog's theme, as the AlertDialog subclass inflated it
        binding = PlayerSettingsDialogBinding.inflate(LayoutInflater.from(builder.context))
        show(initial)
        if (isServer) showServerControls(initial) else hideServerControls()
        wireControls()
        return builder
            .setView(binding.root)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                save()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_FIRING_MODE, firingMode)
        outState.putInt(STATE_RECOIL, recoilSetting)
        outState.putInt(STATE_TARGET_TEAM, targetTeam)
        outState.putByte(STATE_SWAP_WITH, swapWith)
    }

    // The fields hold ASCII digits that PlayerSettingsForm parses back; locale digits wouldn't parse.
    @SuppressLint("SetTextI18n")
    private fun show(values: PlayerSettingsValues) = with(binding) {
        healthEt.setText(values.health.toString())
        reloadShotsEt.setText(values.shots.toString())
        reloadTimeEt.setText(values.reloadTimeMillis.toString())
        reloadOnEmptySwitch.isChecked = values.reloadOnEmpty
        manualRespawnSwitch.isChecked = values.manualRespawn
        spawnTimeEt.setText(values.spawnTimeSeconds.toString())
        respawnShieldTimeEt.setText(values.respawnShieldTimeSeconds.toString())
        damageEt.setText((-values.damage).toString())
        overrideLivesLimitSwitch.isChecked = values.overrideLives
        livesEt.setText(values.lives.toString())
        shotModeSingle.isChecked = values.allowSingle
        shotModeBurst3.isChecked = values.allowBurst3
        shotModeAuto.isChecked = values.allowAuto
    }

    private fun showServerControls(values: PlayerSettingsValues) = with(binding) {
        val args = requireArguments()
        val name = args.getString(ARG_PLAYER_NAME)?.ifBlank { null } ?: getString(R.string.player_settings_unnamed_player)
        titleTv.text = if (teamCount == 0) getString(R.string.player_settings_id_title, name, playerId)
        else getString(R.string.player_settings_team_title, name, args.getInt(ARG_TEAM), args.getInt(ARG_INDEX_IN_TEAM))
        firingModeButton.visibility = View.VISIBLE
        showFiringMode()
        applyToAllSwitch.isChecked = false
        applyToTeamSwitch.isChecked = false
        applyToTeamSwitch.visibility = if (teamCount == 0) View.GONE else View.VISIBLE
        playerSection.visibility = View.VISIBLE
        adminSwitch.isChecked = values.isAdmin
        recoilSettingButton.visibility = View.VISIBLE
        showRecoilSetting()
        if (teamCount == 0) {
            switchTeamButton.visibility = View.GONE
        } else {
            switchTeamButton.visibility = View.VISIBLE
            showTargetTeam()
        }
    }

    private fun hideServerControls() = with(binding) {
        playerSection.visibility = View.GONE
        recoilSettingButton.visibility = View.GONE
        firingModeButton.visibility = View.GONE
        // Reset Defaults stays under the divider, with no apply switches to label
        applySectionTitle.visibility = View.GONE
        applyToAllSwitch.visibility = View.GONE
        applyToTeamSwitch.visibility = View.GONE
    }

    private fun showFiringMode() {
        binding.firingModeButton.setText(
            when (firingMode) {
                GameSettingsRepository.FIRING_MODE_OUTDOOR_WITH_CONE -> R.string.firing_mode_outdoor_with_cone
                GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE -> R.string.firing_mode_indoor_no_cone
                else -> R.string.firing_mode_outdoor_no_cone
            }
        )
    }

    private fun showRecoilSetting() {
        binding.recoilSettingButton.setText(
            when (recoilSetting) {
                GameSettingsRepository.RECOIL_SETTING_ENABLED -> R.string.player_settings_recoil_enabled
                GameSettingsRepository.RECOIL_SETTING_DISABLED -> R.string.player_settings_recoil_disabled
                else -> R.string.player_settings_recoil_default
            }
        )
    }

    private fun showTargetTeam() {
        val swapName = fullTeam(targetTeam)?.firstOrNull { it.first == swapWith }?.second
        binding.switchTeamButton.text = when {
            targetTeam == 0 -> getString(R.string.switch_team_button)
            swapWith > 0 -> getString(R.string.switch_team_with_label, targetTeam,
                swapName?.ifBlank { null } ?: getString(R.string.team_swap_unnamed_player, swapWith.toInt()))
            else -> getString(R.string.team_number_label, targetTeam)
        }
    }

    /** Who is on [team] (id to name) if it had no open slot when the dialog opened; otherwise null. */
    private fun fullTeam(team: Int): List<Pair<Byte, String>>? {
        val args = requireArguments()
        val ids = args.getByteArray(ARG_FULL_TEAM_IDS + team) ?: return null
        val names = args.getStringArray(ARG_FULL_TEAM_NAMES + team) ?: return null
        return ids.zip(names)
    }

    private fun popup(anchor: View) = PopupMenu(ContextThemeWrapper(requireContext(), R.style.AppTheme_PopupOverlay), anchor)

    private fun wireControls() = with(binding) {
        firingModeButton.setOnClickListener { view ->
            val popup = popup(view)
            popup.menuInflater.inflate(R.menu.firing_mode_menu, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                firingMode = when (item.itemId) {
                    R.id.firing_mode_outdoor_no_cone_item -> GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE
                    R.id.firing_mode_outdoor_with_cone_item -> GameSettingsRepository.FIRING_MODE_OUTDOOR_WITH_CONE
                    R.id.firing_mode_indoor_no_cone_item -> GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE
                    else -> return@setOnMenuItemClickListener false
                }
                showFiringMode()
                true
            }
            popup.show()
            Toast.makeText(requireContext(), R.string.firing_mode_cone_toast, Toast.LENGTH_LONG).show()
        }
        resetDefaultsButton.setOnClickListener {
            val defaults = PlayerSettingsValues()
            show(defaults)
            firingMode = defaults.firingMode
            showFiringMode()
            recoilSetting = defaults.recoilSetting
            showRecoilSetting()
        }
        applyToAllSwitch.setOnClickListener { if (applyToAllSwitch.isChecked) applyToTeamSwitch.isChecked = false }
        applyToTeamSwitch.setOnClickListener { if (applyToTeamSwitch.isChecked) applyToAllSwitch.isChecked = false }
        recoilSettingButton.setOnClickListener { view ->
            val popup = popup(view)
            popup.menu.add(0, GameSettingsRepository.RECOIL_SETTING_DEFAULT, 10, R.string.player_settings_recoil_default)
            popup.menu.add(0, GameSettingsRepository.RECOIL_SETTING_ENABLED, 20, R.string.player_settings_recoil_enabled)
            popup.menu.add(0, GameSettingsRepository.RECOIL_SETTING_DISABLED, 30, R.string.player_settings_recoil_disabled)
            popup.setOnMenuItemClickListener { item ->
                recoilSetting = item.itemId
                showRecoilSetting()
                true
            }
            popup.show()
        }
        switchTeamButton.setOnClickListener { view ->
            val popup = popup(view)
            val currentTeam = requireArguments().getInt(ARG_TEAM)
            for (team in 1..teamCount) {
                if (team != currentTeam) popup.menu.add(0, team, team * 10, getString(R.string.team_number_label, team))
            }
            popup.setOnMenuItemClickListener { item ->
                val full = fullTeam(item.itemId)
                if (full != null) {
                    // No open slot: pick who to switch with, which sets the team when chosen
                    if (childFragmentManager.findFragmentByTag(TeamSwapDialog.REQUEST_KEY) == null)
                        TeamSwapDialog.newInstance(playerId, item.itemId, full).show(childFragmentManager, TeamSwapDialog.REQUEST_KEY)
                } else {
                    targetTeam = item.itemId
                    swapWith = 0
                    showTargetTeam()
                }
                true
            }
            popup.show()
        }
        kickPlayerButton.setOnClickListener {
            parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_KICK to true, RESULT_PLAYER_ID to playerId))
            dismiss()
        }
    }

    private fun save() = with(binding) {
        val input = PlayerSettingsInput(
            health = healthEt.text.toString(),
            reloadShots = reloadShotsEt.text.toString(),
            reloadTime = reloadTimeEt.text.toString(),
            reloadOnEmpty = reloadOnEmptySwitch.isChecked,
            manualRespawn = manualRespawnSwitch.isChecked,
            spawnTime = spawnTimeEt.text.toString(),
            respawnShieldTime = respawnShieldTimeEt.text.toString(),
            damage = damageEt.text.toString(),
            overrideLives = overrideLivesLimitSwitch.isChecked,
            lives = livesEt.text.toString(),
            allowSingle = shotModeSingle.isChecked,
            allowBurst3 = shotModeBurst3.isChecked,
            allowAuto = shotModeAuto.isChecked,
            firingMode = firingMode,
            recoilSetting = recoilSetting,
            isAdmin = isServer && adminSwitch.isChecked,
        )
        when (val result = PlayerSettingsForm.parse(input)) {
            PlayerSettingsForm.Result.NoShotMode ->
                Toast.makeText(requireContext(), R.string.player_settings_shot_mode_error, Toast.LENGTH_SHORT).show()
            is PlayerSettingsForm.Result.Valid -> parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                bundleOf(
                    RESULT_PLAYER_ID to playerId,
                    RESULT_VALUES to bundleOf(result.values),
                    RESULT_APPLY_ALL to applyToAllSwitch.isChecked,
                    RESULT_APPLY_TEAM to applyToTeamSwitch.isChecked,
                    RESULT_TARGET_TEAM to targetTeam,
                    RESULT_SWAP_WITH to swapWith,
                ),
            )
        }
    }

    /** What the dialog returned. */
    class Result(
        val playerId: Byte,
        /** The kick button was pressed; nothing else was saved. */
        val kick: Boolean,
        val values: PlayerSettingsValues?,
        val applyToAll: Boolean,
        val applyToTeam: Boolean,
        /** The team to move the player to, or 0. */
        val targetTeam: Int,
        /** If [targetTeam] is full, the player on it to trade places with; 0 for nobody. */
        val swapWith: Byte = 0,
    )

    companion object {
        const val REQUEST_KEY = "playerSettings"
        private const val ARG_SERVER = "server"
        private const val ARG_PLAYER_ID = "playerId"
        private const val ARG_PLAYER_NAME = "playerName"
        private const val ARG_VALUES = "values"
        private const val ARG_TEAM_COUNT = "teamCount"
        private const val ARG_TEAM = "team"
        private const val ARG_INDEX_IN_TEAM = "indexInTeam"
        private const val ARG_FULL_TEAM_IDS = "fullTeamIds"
        private const val ARG_FULL_TEAM_NAMES = "fullTeamNames"
        private const val STATE_FIRING_MODE = "firingMode"
        private const val STATE_RECOIL = "recoilSetting"
        private const val STATE_TARGET_TEAM = "targetTeam"
        private const val STATE_SWAP_WITH = "swapWith"
        private const val RESULT_PLAYER_ID = "playerId"
        private const val RESULT_KICK = "kick"
        private const val RESULT_VALUES = "values"
        private const val RESULT_APPLY_ALL = "applyAll"
        private const val RESULT_APPLY_TEAM = "applyTeam"
        private const val RESULT_TARGET_TEAM = "targetTeam"
        private const val RESULT_SWAP_WITH = "swapWith"

        /** This phone's player. */
        @JvmStatic
        fun local(values: PlayerSettingsValues) = PlayerSettingsDialog().apply {
            arguments = bundleOf(ARG_SERVER to false, ARG_VALUES to bundleOf(values))
        }

        /**
         * Network player [playerId], named [playerName] (blank for none), on the server; [teamCount] 0 in free-for-all, otherwise the
         * player is number [indexInTeam] on [team]. Picking one of [fullTeams] (who is on each,
         * id to name) asks who to switch with.
         */
        @JvmStatic
        @JvmOverloads
        fun server(
            playerId: Byte,
            playerName: String,
            values: PlayerSettingsValues,
            teamCount: Int,
            team: Int,
            indexInTeam: Int,
            fullTeams: Map<Int, List<Pair<Byte, String>>> = emptyMap(),
        ) = PlayerSettingsDialog().apply {
            arguments = bundleOf(
                ARG_SERVER to true,
                ARG_PLAYER_ID to playerId,
                ARG_PLAYER_NAME to playerName,
                ARG_VALUES to bundleOf(values),
                ARG_TEAM_COUNT to teamCount,
                ARG_TEAM to team,
                ARG_INDEX_IN_TEAM to indexInTeam,
            ).apply {
                for ((t, players) in fullTeams) {
                    putByteArray(ARG_FULL_TEAM_IDS + t, players.map { it.first }.toByteArray())
                    putStringArray(ARG_FULL_TEAM_NAMES + t, players.map { it.second }.toTypedArray())
                }
            }
        }

        @JvmStatic
        fun result(bundle: Bundle) = Result(
            playerId = bundle.getByte(RESULT_PLAYER_ID),
            kick = bundle.getBoolean(RESULT_KICK),
            values = bundle.getBundle(RESULT_VALUES)?.let { valuesOf(it) },
            applyToAll = bundle.getBoolean(RESULT_APPLY_ALL),
            applyToTeam = bundle.getBoolean(RESULT_APPLY_TEAM),
            targetTeam = bundle.getInt(RESULT_TARGET_TEAM),
            swapWith = bundle.getByte(RESULT_SWAP_WITH),
        )

        private fun bundleOf(v: PlayerSettingsValues) = bundleOf(
            "health" to v.health, "shots" to v.shots, "reloadTime" to v.reloadTimeMillis,
            "reloadOnEmpty" to v.reloadOnEmpty, "manualRespawn" to v.manualRespawn, "spawnTime" to v.spawnTimeSeconds,
            "respawnShieldTime" to v.respawnShieldTimeSeconds, "damage" to v.damage, "overrideLives" to v.overrideLives,
            "lives" to v.lives, "single" to v.allowSingle, "burst3" to v.allowBurst3, "auto" to v.allowAuto,
            "firingMode" to v.firingMode, "recoilSetting" to v.recoilSetting, "isAdmin" to v.isAdmin,
        )

        private fun valuesOf(b: Bundle) = PlayerSettingsValues(
            health = b.getInt("health"), shots = b.getInt("shots"), reloadTimeMillis = b.getLong("reloadTime"),
            reloadOnEmpty = b.getBoolean("reloadOnEmpty"), manualRespawn = b.getBoolean("manualRespawn"),
            spawnTimeSeconds = b.getLong("spawnTime"), respawnShieldTimeSeconds = b.getLong("respawnShieldTime"),
            damage = b.getInt("damage"), overrideLives = b.getBoolean("overrideLives"), lives = b.getInt("lives"),
            allowSingle = b.getBoolean("single"), allowBurst3 = b.getBoolean("burst3"), allowAuto = b.getBoolean("auto"),
            firingMode = b.getInt("firingMode"), recoilSetting = b.getInt("recoilSetting"), isAdmin = b.getBoolean("isAdmin"),
        )
    }
}
