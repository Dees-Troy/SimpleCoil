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

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.simplecoil.simplecoil.PlayerDisplayData
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.domain.session.SessionToast
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.ui.dialogs.GameLimitDialog
import com.simplecoil.simplecoil.ui.dialogs.TeamRatioDialog
import com.simplecoil.simplecoil.ui.playersettings.PlayerSettingsDialog

/**
 * Dialogs, toasts and popup menus of the play screen ([GameDialogs]), moved from
 * FullscreenActivity. Dialogs are DialogFragments so they survive recreation; one asked for while
 * the activity's state is saved (in the background) is shown when it starts again, unless it was
 * dismissed in the meantime.
 */
class DialogCoordinator(
    private val activity: AppCompatActivity,
    private val vm: GameViewModel,
    private val networkButton: View,
    private val teamModeView: View,
) : GameDialogs, PopupMenu.OnMenuItemClickListener {

    private val fm get() = activity.supportFragmentManager
    private val appContext get() = activity.applicationContext
    private var networkPopup: PopupMenu? = null
    private var adminPopup: PopupMenu? = null
    private var adminAnchor: View? = null

    /** Dialog shows that waited for the activity to start again, by tag. */
    private val pending = LinkedHashMap<String, () -> Unit>()

    init {
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                val actions = pending.values.toList()
                pending.clear()
                actions.forEach { it() }
            }
        })
        fm.setFragmentResultListener(GameLimitDialog.REQUEST_KEY, activity) { _, result ->
            vm.onGameLimitEntered(GameLimitDialog.text(result), GameLimitDialog.kind(result))
        }
        fm.setFragmentResultListener(TeamRatioDialog.REQUEST_KEY, activity) { _, result ->
            vm.onTeamSizesEntered(TeamRatioDialog.sizes(result).map { it.toString() })
        }
        fm.setFragmentResultListener(PlayerSettingsDialog.REQUEST_KEY, activity) { _, result ->
            PlayerSettingsDialog.result(result).values?.let { vm.onLocalSettingsSaved(it) }
        }
        // Bring restored dialogs in line with the view model (it may have outlived them, or not).
        if (vm.disconnectDialogShowing) showDisconnectDialog() else remove(TAG_DISCONNECTED)
        if (vm.connectStatus != null) showConnectStatus(vm.connectStatus) else remove(TAG_CONNECT_STATUS)
        if (!vm.scoreboardVisible) remove(TAG_SCOREBOARD)
    }

    private fun show(tag: String, create: () -> DialogFragment) {
        if (fm.isStateSaved) {
            pending[tag] = { show(tag, create) }
            return
        }
        if (fm.findFragmentByTag(tag) == null) create().showNow(fm, tag)
    }

    private fun dismiss(tag: String) {
        pending.remove(tag)
        (fm.findFragmentByTag(tag) as? DialogFragment)?.dismissAllowingStateLoss()
    }

    private fun remove(tag: String) {
        fm.findFragmentByTag(tag)?.let { fm.beginTransaction().remove(it).commitNow() }
    }

    // ---------------------------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------------------------

    override fun showConnectStatus(status: ConnectStatus?) {
        if (status == null) return dismiss(TAG_CONNECT_STATUS)
        // The dialog reads the status from the view model, so an open one only needs a refresh.
        (fm.findFragmentByTag(TAG_CONNECT_STATUS) as? ConnectStatusDialog)?.refresh()
            ?: show(TAG_CONNECT_STATUS) { ConnectStatusDialog() }
    }

    override fun showDisconnectDialog() = show(TAG_DISCONNECTED) { BlasterDisconnectedDialog() }

    override fun dismissDisconnectDialog() = dismiss(TAG_DISCONNECTED)

    override fun showBlasterType(rifle: Boolean, address: String) {
        // We'll automatically assume that anything but a rifle is a pistol
        val text = appContext.getString(if (rifle) R.string.rifle_detected_toast else R.string.pistol_detected_toast, address)
        Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------------------------------
    // Game
    // ---------------------------------------------------------------------------------------

    override fun confirmEndGame() = show(TAG_END_GAME) { EndGameConfirmDialog() }

    override fun confirmReset(disconnectedPlayers: Int) = show(TAG_RESET) { ResetConfirmDialog.newInstance(disconnectedPlayers) }

    override fun showSessionToast(toast: SessionToast) {
        when (toast) {
            SessionToast.GRENADE_PAIRED -> toast(appContext.getString(R.string.grenade_paired_toast))
            SessionToast.OUT_OF_LIVES -> toast(appContext.getString(R.string.dialog_out_of_lives), Toast.LENGTH_LONG)
            SessionToast.RECOIL_FORCED_ENABLED -> toast("Recoil is forced ENABLED by server")
            SessionToast.RECOIL_FORCED_DISABLED -> toast("Recoil is forced DISABLED by server")
            SessionToast.GAME_TIME_EXPIRED -> toast(appContext.getString(R.string.dialog_game_time_expired))
        }
    }

    override fun showMessage(message: GameMessage, arg: Int) {
        when (message) {
            GameMessage.NOT_ENOUGH_PLAYERS -> toast(appContext.getString(R.string.not_enough_players_toast))
            GameMessage.PLAYER_SETTINGS_NOT_ALLOWED -> toast(appContext.getString(R.string.player_settings_not_allowed_toast))
            GameMessage.NO_WIFI -> toast(appContext.getString(R.string.error_no_wifi))
            GameMessage.LIMIT_TOO_HIGH -> toast(appContext.getString(R.string.error_limit_too_high))
            GameMessage.UDP_VERSION_ERROR -> toast(appContext.getString(R.string.error_udp_version))
            GameMessage.KICKED_BY_SERVER -> toast(appContext.getString(R.string.kicked_by_server_toast))
            GameMessage.INAPPROPRIATE_NAME -> toast("Please choose an appropriate player name!")
            GameMessage.LOCATION_PERMISSION_REQUIRED -> activityToast(activity.getString(R.string.error_location_permission_required))
            GameMessage.RATIOS_DURING_GAME -> activityToast("Cannot edit team ratios while a game is in progress")
            GameMessage.RATIOS_ADMIN_ONLY -> activityToast("Only server admins can edit team ratios")
            GameMessage.RATIOS_NOT_IN_FFA -> activityToast("Team ratios are not applicable in Free For All mode")
            GameMessage.RATIOS_MIN_ONE -> activityToast("Team limits must be at least 1 player slot")
            GameMessage.RATIOS_TOTAL ->
                activityToast("Total player slots across all teams must equal " + GameSettingsRepository.MAX_PLAYER_ID + " (current sum: " + arg + ")", Toast.LENGTH_LONG)
            GameMessage.KICK_SENT -> activityToast("Kick command sent")
            GameMessage.QR_NO_BLASTER -> toast(appContext.getString(R.string.qr_no_blaster_toast))
        }
    }

    override fun showText(text: String) = toast(text)

    private fun toast(text: String, duration: Int = Toast.LENGTH_SHORT) = Toast.makeText(appContext, text, duration).show()

    private fun activityToast(text: String, duration: Int = Toast.LENGTH_SHORT) = Toast.makeText(activity, text, duration).show()

    // ---------------------------------------------------------------------------------------
    // Menus
    // ---------------------------------------------------------------------------------------

    private fun popup(anchor: View) = PopupMenu(ContextThemeWrapper(activity, R.style.AppTheme_PopupOverlay), anchor)

    override fun prepareNetworkMenu() {
        if (networkPopup == null) {
            networkPopup = popup(networkButton).also { it.setOnMenuItemClickListener(this) }
        }
    }

    override fun rebuildNetworkMenu() {
        val menu = networkPopup?.also { it.setForceShowIcon(true) }?.menu ?: return
        menu.close()
        menu.clear()
        var switchOrder = 51
        for (entry in vm.networkMenuEntries()) {
            when (entry) {
                NetworkMenuEntry.Join -> menu.add(0, R.id.join_item, 20, R.string.join_button)
                NetworkMenuEntry.JoinIp -> menu.add(0, R.id.join_ip_item, 30, R.string.join_ip_button)
                is NetworkMenuEntry.PlayerName -> menu.add(0, R.id.player_name_item, 50, activity.getString(R.string.player_name_button, entry.name))
                is NetworkMenuEntry.SwitchTeam -> {
                    val itemId = switchTeamItemId(entry.team)
                    if (itemId != 0) {
                        val item = menu.add(0, itemId, switchOrder++, activity.getString(R.string.switch_to_team, entry.team))
                        if (entry.color != Color.TRANSPARENT) item.icon = createTeamColorSquare(entry.color)
                    }
                }
                NetworkMenuEntry.DisableNetwork -> menu.add(0, R.id.disable_network_item, 70, R.string.no_network_button)
            }
        }
    }

    private fun createTeamColorSquare(color: Int): Drawable {
        val density = activity.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            setStroke((1 * density).toInt(), Color.WHITE)
            val size = (16 * density).toInt()
            setSize(size, size)
        }
    }

    override fun showNetworkMenu() {
        networkPopup!!.show()
    }

    override fun showTeamModeMenu() {
        val popup = popup(teamModeView)
        popup.menuInflater.inflate(R.menu.team_mode_menu, popup.menu)
        // Shuffling only makes sense on a server, which this player isn't.
        popup.menu.removeItem(R.id.sort_teams_item)
        popup.setOnMenuItemClickListener(this)
        popup.show()
    }

    /** The firing mode button. */
    fun showFiringModeMenu(anchor: View) {
        val popup = popup(anchor)
        popup.menuInflater.inflate(R.menu.firing_mode_menu, popup.menu)
        popup.setOnMenuItemClickListener(this)
        popup.show()
        Toast.makeText(appContext, appContext.getString(R.string.firing_mode_cone_toast), Toast.LENGTH_LONG).show()
    }

    /** The admin controls button. */
    fun showAdminMenu(anchor: View) {
        val popup = popup(anchor)
        var order = 10
        for (action in vm.adminActions()) {
            val title = when (action) {
                AdminAction.START_GAME -> R.string.start_game_button
                AdminAction.END_GAME -> R.string.end_game_button
                AdminAction.TWO_TEAMS -> R.string.team_mode_2teams
                AdminAction.FOUR_TEAMS -> R.string.team_mode_4teams
                AdminAction.FREE_FOR_ALL -> R.string.team_mode_ffa
                AdminAction.SHUFFLE_TEAMS -> R.string.shuffle_teams_button
                AdminAction.RESET_GAME -> R.string.reset_game_admin
            }
            popup.menu.add(0, action.ordinal, order, title)
            order += 10
        }
        popup.setOnMenuItemClickListener { item ->
            Log.d(TAG, "showAdminControlsMenu onMenuItemClick: itemId=" + item.itemId)
            vm.onAdminAction(AdminAction.entries[item.itemId])
            true
        }
        popup.setOnDismissListener {
            if (adminPopup === popup) adminPopup = null
        }
        adminPopup = popup
        adminAnchor = anchor
        popup.show()
    }

    override fun rebuildAdminMenu() {
        val open = adminPopup ?: return
        val anchor = adminAnchor ?: return
        adminPopup = null
        open.dismiss()
        if (!activity.isFinishing && !activity.isDestroyed) showAdminMenu(anchor)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.disable_network_item -> vm.onNetworkMenuEntry(NetworkMenuEntry.DisableNetwork)
            R.id.join_item -> vm.onNetworkMenuEntry(NetworkMenuEntry.Join)
            R.id.join_ip_item -> vm.onNetworkMenuEntry(NetworkMenuEntry.JoinIp)
            R.id.player_name_item -> vm.onNetworkMenuEntry(NetworkMenuEntry.PlayerName(""))
            R.id.switch_team_1_item -> vm.onNetworkMenuEntry(NetworkMenuEntry.SwitchTeam(1, 0))
            R.id.switch_team_2_item -> vm.onNetworkMenuEntry(NetworkMenuEntry.SwitchTeam(2, 0))
            R.id.switch_team_3_item -> vm.onNetworkMenuEntry(NetworkMenuEntry.SwitchTeam(3, 0))
            R.id.switch_team_4_item -> vm.onNetworkMenuEntry(NetworkMenuEntry.SwitchTeam(4, 0))
            R.id.firing_mode_outdoor_no_cone_item -> vm.onFiringModeSelected(GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE)
            R.id.firing_mode_outdoor_with_cone_item -> vm.onFiringModeSelected(GameSettingsRepository.FIRING_MODE_OUTDOOR_WITH_CONE)
            R.id.firing_mode_indoor_no_cone_item -> vm.onFiringModeSelected(GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE)
            R.id.team_mode_2teams_item -> vm.onTeamModeSelected(TeamMode.TWO_TEAMS)
            R.id.team_mode_4teams_item -> vm.onTeamModeSelected(TeamMode.FOUR_TEAMS)
            R.id.team_mode_ffa_item -> vm.onTeamModeSelected(TeamMode.FREE_FOR_ALL)
            R.id.sort_teams_item -> vm.onAdminAction(AdminAction.SHUFFLE_TEAMS)
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Dialogs
    // ---------------------------------------------------------------------------------------

    override fun showPlayerNameDialog(current: String) = show(TAG_PLAYER_NAME) { PlayerNameDialog.newInstance(current) }

    override fun showQrNameDialog(current: String) = show(TAG_QR_NAME) { QrNameDialog.newInstance(current) }

    override fun showQrCode(payload: String, playerName: String) = show(TAG_QR_CODE) { QrCodeDialog.newInstance(payload, playerName) }

    /** The "Learn more about using QR codes" link on the connect screen. */
    fun showQrInfo() = show(TAG_QR_INFO) { QrInfoDialog() }

    override fun showServerIpDialog(suggestion: String) = show(TAG_SERVER_IP) { ServerIpDialog.newInstance(suggestion) }

    override fun showGameLimitDialog(allowScoreLimit: Boolean) = show(TAG_GAME_LIMIT) { GameLimitDialog.newInstance(allowScoreLimit) }

    override fun showTeamRatioDialog(numTeams: Int, sizes: IntArray) = show(TAG_TEAM_RATIO) { TeamRatioDialog.newInstance(numTeams, sizes) }

    override fun showLocalPlayerSettings() = show(TAG_PLAYER_SETTINGS) { PlayerSettingsDialog.local(vm.localPlayerSettings()) }

    override fun showScoreboard(players: Array<PlayerDisplayData?>) {
        if (activity.isFinishing || activity.isDestroyed) return
        val open = fm.findFragmentByTag(TAG_SCOREBOARD) as? ScoreboardDialog
        if (open != null) {
            open.setData(players)
            return
        }
        show(TAG_SCOREBOARD) { ScoreboardDialog() }
    }

    override fun closeScoreboard() = dismiss(TAG_SCOREBOARD)

    companion object {
        private const val TAG = "scmain"
        private const val TAG_CONNECT_STATUS = "connectStatus"
        private const val TAG_DISCONNECTED = "blasterDisconnected"
        private const val TAG_END_GAME = "endGame"
        private const val TAG_RESET = "resetGame"
        private const val TAG_PLAYER_NAME = "playerName"
        private const val TAG_SERVER_IP = "serverIp"
        private const val TAG_QR_NAME = "qrName"
        private const val TAG_QR_CODE = "qrCode"
        private const val TAG_QR_INFO = "qrInfo"
        private const val TAG_GAME_LIMIT = "gameLimit"
        private const val TAG_TEAM_RATIO = "teamRatio"
        private const val TAG_PLAYER_SETTINGS = "playerSettings"
        private const val TAG_SCOREBOARD = "scoreboard"

        private fun switchTeamItemId(team: Int) = when (team) {
            1 -> R.id.switch_team_1_item
            2 -> R.id.switch_team_2_item
            3 -> R.id.switch_team_3_item
            4 -> R.id.switch_team_4_item
            else -> 0
        }
    }
}
