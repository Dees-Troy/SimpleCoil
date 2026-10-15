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

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.databinding.ActivityFullscreenBinding
import com.simplecoil.simplecoil.domain.session.BatteryLevelPolicy
import com.simplecoil.simplecoil.domain.session.ClockFormat
import com.simplecoil.simplecoil.domain.session.GameClock
import com.simplecoil.simplecoil.domain.session.HitResolver
import com.simplecoil.simplecoil.domain.session.SessionState
import com.simplecoil.simplecoil.domain.session.SpawnPhase
import com.simplecoil.simplecoil.domain.team.TeamMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Draws the play screen: the lobby/network commands of [GameView] and the session values of
 * [SessionState]. Moved from FullscreenActivity with the same view changes in the same order.
 * Animation clean-up runs in [scope] (the activity's lifecycleScope), so nothing touches the
 * views after the activity is destroyed.
 */
class GameScreenRenderer(
    private val b: ActivityFullscreenBinding,
    private val window: WindowController,
    private val scope: CoroutineScope,
    /** No blaster on this device (a Chromebook): the connect screen shows only the dedicated server. */
    private val serverOnly: Boolean = false,
    /** The team label was tapped while joined to a dedicated server. */
    private val onTeamTapped: () -> Unit = {},
    /** The player name tile was tapped while joined to a dedicated server. */
    private val onPlayerNameTapped: () -> Unit = {},
    /** The shot mode tile was tapped while more than one shot mode is allowed. */
    private val onShotModeTapped: () -> Unit = {},
    /** The recoil tile was tapped while the server lets the player choose. */
    private val onRecoilTapped: () -> Unit = {},
    /** The score readout was tapped while a scoreboard is available. */
    private val onScoresTapped: () -> Unit = {},
) : GameView {

    private val context get() = b.root.context
    private var hitAnimation: AnimationDrawable? = null
    private var hitPlayerAnimation: AnimationDrawable? = null
    private var hitPlayerHide: Job? = null

    // ---------------------------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------------------------

    override fun showConnectScreen(reconnectVisible: Boolean) {
        b.playLayout.visibility = View.GONE
        b.connectLayout.visibility = View.VISIBLE
        if (serverOnly) {
            b.connectWeaponButton.visibility = View.GONE
            b.connectQrWeaponButton.visibility = View.GONE
            b.qrLearnMoreTv.visibility = View.GONE
            b.connectIntroTv.visibility = View.GONE
            b.dedicatedServerTopDivider.visibility = View.GONE
        }
        setReconnectVisible(reconnectVisible)
    }

    override fun showPlayScreen() {
        b.connectLayout.visibility = View.GONE
        b.playLayout.visibility = View.VISIBLE
    }

    override fun setReconnectVisible(visible: Boolean) {
        b.reconnectWeaponButton.visibility = if (visible && !serverOnly) View.VISIBLE else View.GONE
    }

    override fun setConnectButtonsEnabled(enabled: Boolean) {
        b.connectWeaponButton.isEnabled = enabled
        b.reconnectWeaponButton.isEnabled = enabled
        b.dedicatedServerButton.isEnabled = enabled
        b.connectQrWeaponButton.isEnabled = enabled
    }

    // ---------------------------------------------------------------------------------------
    // Game
    // ---------------------------------------------------------------------------------------

    override fun showGameStarted(useNetwork: Boolean, isAdmin: Boolean, freeForAll: Boolean) {
        window.enterGame()
        b.scoreTv.text = "0"
        b.teamScoreTv.text = "0"
        b.startGameButton.visibility = View.GONE
        b.teamMinusButton.visibility = View.INVISIBLE
        b.teamPlusButton.visibility = View.INVISIBLE
        b.gameLimitButton.visibility = View.GONE
        b.playerSettingsButton.visibility = View.GONE
        b.shuffleTeamsButton.isEnabled = false
        b.shuffleTeamsButton.visibility = View.GONE
        b.generateQrButton.visibility = View.GONE
        showTeamModeSelectable(false)
        if (useNetwork) {
            displayInGameNetworkingOptions(isAdmin, freeForAll)
        } else {
            displayAllNetworkingOptions(false, freeForAll)
            b.endGameButton.visibility = View.VISIBLE
        }
        b.useNetworkButton.visibility = View.INVISIBLE
        b.firingModeButton.visibility = View.INVISIBLE
    }

    override fun showGameEnded(useNetwork: Boolean, freeForAll: Boolean) {
        window.exitGame()
        b.endGameButton.visibility = View.GONE
        b.generateQrButton.visibility = View.VISIBLE
        displayAllNetworkingOptions(useNetwork, freeForAll)
        b.useNetworkButton.visibility = View.VISIBLE
        b.hitPlayerIv.visibility = View.GONE
        b.hitPlayerNameTv.visibility = View.GONE
        b.shotsFiredIv.visibility = View.GONE
    }

    override fun showScore(score: Int) {
        b.scoreTv.text = number(score)
        updateScoresDescription()
    }

    override fun showTeamScore(teamScore: Int) {
        b.teamScoreTv.text = number(teamScore)
        updateScoresDescription()
    }

    /** Lets TalkBack read the score readout as one item, e.g. "TEAM: 40, SCORE: 12". */
    private fun updateScoresDescription() {
        val entries = listOfNotNull(
            if (b.teamScoreTv.isVisible) context.getString(R.string.cd_score_entry, b.teamScoreLabelTv.text, b.teamScoreTv.text) else null,
            context.getString(R.string.cd_score_entry, b.scoreLabelTv.text, b.scoreTv.text),
        )
        b.scoresChip.contentDescription = entries.joinToString(", ")
    }

    private fun setTeamScoreVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        b.teamScoreLabelTv.visibility = visibility
        b.teamScoreTv.visibility = visibility
        updateScoresDescription()
    }

    override fun showGameLimit(timeLimitMinutes: Int?, livesLabel: Boolean, scoreLimit: Int?) {
        if (timeLimitMinutes != null) {
            b.gameCountdownTv.visibility = View.VISIBLE
            b.gameCountdownTv.text = ClockFormat.minutes(timeLimitMinutes.toLong())
            b.gameTimerChronometer.visibility = View.GONE
        } else {
            b.gameCountdownTv.visibility = View.GONE
            b.gameTimerChronometer.visibility = View.VISIBLE
        }
        b.eliminationsCountLabelTv.setText(if (livesLabel) R.string.lives_count_label else R.string.eliminations_count_label)
        if (scoreLimit != null) {
            b.scoreLabelTv.text = context.getString(R.string.score_limit_label, scoreLimit)
            b.teamScoreLabelTv.text = context.getString(R.string.team_score_limit_label, scoreLimit)
        } else {
            b.scoreLabelTv.setText(R.string.score_label)
            b.teamScoreLabelTv.setText(R.string.team_score_label)
        }
        updateScoresDescription()
    }

    override fun showPlayerSettings(fullHealth: Int, fullReload: Byte, damage: Int) {
        b.healthLabelTv.text = context.getString(R.string.health_label, fullHealth)
        b.shotsLabelTv.text = context.getString(R.string.shots_remaining_label, fullReload.toInt() and 0xFF, damage)
    }

    override fun showFiringMode(firingMode: Int) {
        when (firingMode) {
            GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE -> b.firingModeButton.setText(R.string.firing_mode_outdoor_no_cone)
            GameSettingsRepository.FIRING_MODE_OUTDOOR_WITH_CONE -> b.firingModeButton.setText(R.string.firing_mode_outdoor_with_cone)
            GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE -> b.firingModeButton.setText(R.string.firing_mode_indoor_no_cone)
        }
    }

    override fun showTeamMode(mode: TeamMode) {
        val text = when (mode) {
            TeamMode.TWO_TEAMS -> R.string.team_mode_2teams
            TeamMode.FOUR_TEAMS -> R.string.team_mode_4teams
            TeamMode.FREE_FOR_ALL -> R.string.team_mode_ffa
        }
        b.teamModeTv.setText(text)
        b.teamModeButton.setText(text)
    }

    /**
     * The team mode is a button offline before the game, otherwise a label. Not [shown] while
     * networking is on without a server yet; kept INVISIBLE so the QR button above stays put.
     */
    private fun showTeamModeSelectable(selectable: Boolean, shown: Boolean = true) {
        b.teamModeLabelTv.visibility = if (shown) View.VISIBLE else View.INVISIBLE
        b.teamModeButton.visibility = if (!shown) View.INVISIBLE else if (selectable) View.VISIBLE else View.GONE
        b.teamModeTv.visibility = if (shown && !selectable) View.VISIBLE else View.GONE
    }

    override fun showTeam(team: TeamDisplay) {
        b.teamLabelTv.visibility = View.VISIBLE
        b.playerIdTv.visibility = View.GONE
        if (team is TeamDisplay.Team) {
            b.teamLabelTv.text = context.getString(R.string.team_number_label, team.team)
            if (team.color != Color.TRANSPARENT) {
                val box = GradientDrawable().apply {
                    setColor(team.color)
                    cornerRadius = 8f
                }
                b.teamLabelTv.background = if (team.switchable) RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), box, null) else box
                b.teamLabelTv.setPadding(24, 12, 24, 12)
                b.teamLabelTv.setTextColor(if (team.team == 4) Color.BLACK else Color.WHITE)
            } else {
                clearTeamLabelStyle()
            }
            setTeamLabelTappable(team.switchable)
            if (team.playerName != null) {
                b.teamTv.text = team.playerName
                setPlayerNameTile(true)
                showPlayerId(context.getString(R.string.player_id_team_label, team.team, team.player))
            } else {
                b.teamTv.text = context.getString(R.string.network_team, team.player)
                setPlayerNameTile(false)
            }
            setTeamScoreVisible(team.teamScoreVisible)
            return
        }
        b.teamLabelTv.setText(R.string.team_label)
        clearTeamLabelStyle()
        setTeamLabelTappable(false)
        if (team is TeamDisplay.FreeForAll && team.playerName != null) {
            b.teamLabelTv.visibility = View.INVISIBLE
            b.teamTv.text = team.playerName
            setPlayerNameTile(true)
            showPlayerId(context.getString(R.string.player_id_ffa_label, team.playerId.toInt()))
        } else if (team is TeamDisplay.FreeForAll) {
            b.teamTv.text = number(team.playerId.toInt())
            setPlayerNameTile(false)
        } else {
            b.teamTv.setText(R.string.auto_sort_team)
            setPlayerNameTile(false)
        }
        setTeamScoreVisible(false)
    }

    /** The dedicated-server player id, e.g. "(1-1)", shown under the player count. */
    private fun showPlayerId(text: String) {
        b.playerIdTv.text = text
        b.playerIdTv.visibility = View.VISIBLE
    }

    /** On a dedicated server the team label is a button that offers to switch teams. */
    private fun setTeamLabelTappable(tappable: Boolean) {
        val label = b.teamLabelTv
        if (tappable) {
            label.setOnClickListener { onTeamTapped() }
            ViewCompat.replaceAccessibilityAction(label, AccessibilityActionCompat.ACTION_CLICK,
                context.getString(R.string.team_label_action), null)
        } else {
            label.setOnClickListener(null)
            label.isClickable = false
        }
        label.isFocusable = tappable
    }

    /**
     * On a dedicated server the player name is a tile like the score readout's that opens the
     * player name dialog. The tile's padding comes out of the top margin so the name stays put.
     */
    private fun setPlayerNameTile(tile: Boolean) {
        val name = b.teamTv
        val params = name.layoutParams as ViewGroup.MarginLayoutParams
        val dp = context.resources.displayMetrics.density
        if (tile) {
            name.setBackgroundResource(R.drawable.scores_chip_background)
            name.setPadding((12 * dp).toInt(), (2 * dp).toInt(), (12 * dp).toInt(), (2 * dp).toInt())
            params.topMargin = (38 * dp).toInt()
            name.setOnClickListener { onPlayerNameTapped() }
            ViewCompat.replaceAccessibilityAction(name, AccessibilityActionCompat.ACTION_CLICK,
                context.getString(R.string.player_name_action), null)
        } else {
            name.background = null
            name.setPadding(0, 0, 0, 0)
            params.topMargin = (40 * dp).toInt()
            name.setOnClickListener(null)
            name.isClickable = false
        }
        name.isFocusable = tile
        name.layoutParams = params
    }

    /**
     * The shot mode and recoil values are tiles like the score readout's while the player may
     * change them. The tile's padding comes out of the margins (and the end margin, for a view
     * aligned to the parent's end) so the text stays put.
     */
    private fun setSettingTile(view: TextView, tile: Boolean, action: Int, onTapped: () -> Unit, endMarginDp: Int = 0) {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        val dp = context.resources.displayMetrics.density
        val padH = (12 * dp).toInt()
        val padV = (2 * dp).toInt()
        val endMargin = (endMarginDp * dp).toInt()
        if (tile) {
            view.setBackgroundResource(R.drawable.scores_chip_background)
            view.setPadding(padH, padV, padH, padV)
            params.topMargin = -padV
            params.bottomMargin = -padV
            params.marginEnd = if (endMargin > 0) endMargin - padH else 0
            view.setOnClickListener { onTapped() }
            ViewCompat.replaceAccessibilityAction(view, AccessibilityActionCompat.ACTION_CLICK, context.getString(action), null)
        } else {
            view.background = null
            view.setPadding(0, 0, 0, 0)
            params.topMargin = 0
            params.bottomMargin = 0
            params.marginEnd = endMargin
            view.setOnClickListener(null)
            view.isClickable = false
        }
        view.isFocusable = tile
        view.layoutParams = params
    }

    private fun clearTeamLabelStyle() {
        b.teamLabelTv.background = null
        b.teamLabelTv.setPadding(0, 0, 0, 0)
        b.teamLabelTv.setTextColor(Color.WHITE)
    }

    override fun showPreGameButtons(buttons: PreGameButtons) {
        val offlineLobby = buttons.offline && !buttons.ready
        val lobbyVisibility = if (offlineLobby) View.VISIBLE else View.INVISIBLE
        b.teamMinusButton.visibility = lobbyVisibility
        b.teamPlusButton.visibility = lobbyVisibility
        b.gameLimitButton.visibility = if (offlineLobby) View.VISIBLE else View.GONE
        b.startGameButton.visibility = if (buttons.offline && buttons.startVisible) View.VISIBLE else View.GONE
        val playerSettingsVisibility = if ((buttons.offline || buttons.joined) && !buttons.serverSettingsOnly) View.VISIBLE else View.GONE
        b.firingModeButton.visibility = playerSettingsVisibility
        b.playerSettingsButton.visibility = playerSettingsVisibility
        b.shuffleTeamsButton.visibility = View.GONE
        showTeamModeSelectable(buttons.offline, shown = buttons.offline || buttons.joined)
    }

    override fun showAdminButton(visible: Boolean) {
        b.adminControlsButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------------------------

    private fun displayAllNetworkingOptions(enabled: Boolean, freeForAll: Boolean) {
        val visibility = if (enabled) View.VISIBLE else View.INVISIBLE
        b.scoresChip.visibility = visibility
        b.serverIpTv.visibility = View.GONE
        setTeamScoreVisible(enabled && !freeForAll)
    }

    private fun displayInGameNetworkingOptions(isAdmin: Boolean, freeForAll: Boolean) {
        displayAllNetworkingOptions(false, freeForAll)
        b.playerCountTv.visibility = View.VISIBLE
        b.scoresChip.visibility = View.VISIBLE
        b.endGameButton.visibility = if (isAdmin) View.VISIBLE else View.GONE
        if (!freeForAll) setTeamScoreVisible(true)
    }

    override fun showNetworkOptions(enabled: Boolean, freeForAll: Boolean) {
        displayAllNetworkingOptions(enabled, freeForAll)
    }

    override fun showNetworkButton(button: NetworkButton) {
        b.useNetworkButton.setText(when (button) {
            NetworkButton.USE_NETWORK -> R.string.use_network_button
            NetworkButton.MENU -> R.string.network_menu_button
            NetworkButton.SEARCHING -> R.string.network_searching_status
            NetworkButton.DISABLE -> R.string.disable_networking_button
        })
    }

    override fun showServerSearching() {
        b.serverIpTv.visibility = View.VISIBLE
        b.serverIpTv.setText(R.string.server_status_searching)
    }

    override fun showNotReady() {
        b.networkStatusIv.visibility = View.GONE
        b.playerCountTv.visibility = View.GONE
        window.setKeepScreenOn(false)
        b.serverIpTv.visibility = View.GONE
    }

    override fun setKeepScreenOn(on: Boolean) {
        window.setKeepScreenOn(on)
    }

    /**
     * While joined to a server the score readout doubles as the scoreboard button: an outlined
     * chip that ripples on press. Otherwise it is a plain readout.
     */
    override fun setScoreboardAvailable(available: Boolean) {
        val chip = b.scoresChip
        if (available) {
            chip.setBackgroundResource(R.drawable.scores_chip_background)
            chip.setOnClickListener { onScoresTapped() }
            ViewCompat.replaceAccessibilityAction(chip, AccessibilityActionCompat.ACTION_CLICK,
                context.getString(R.string.scores_chip_action), null)
        } else {
            chip.background = null
            chip.setOnClickListener(null)
            chip.isClickable = false
        }
        chip.isFocusable = available
    }

    override fun showPlayerCount(count: Int) {
        b.playerCountTv.text = context.resources.getQuantityString(R.plurals.network_player_count, count, count)
        b.playerCountTv.visibility = View.VISIBLE
    }

    override fun showOnlyOnePlayer() {
        b.playerCountTv.setText(R.string.network_player_1count)
    }

    override fun showConnectedToServer(ip: String) {
        b.serverIpTv.text = context.getString(R.string.server_status_connected_to, ip)
        b.serverIpTv.visibility = View.VISIBLE
        setScoreboardAvailable(true)
        b.networkStatusIv.visibility = View.VISIBLE
        b.networkStatusIv.setImageResource(R.drawable.ic_network_connected_24dp)
    }

    override fun hideStartButton() {
        b.startGameButton.visibility = View.GONE
    }

    override fun showNetworkConnected(connected: Boolean) {
        b.networkStatusIv.setImageResource(if (connected) R.drawable.ic_network_connected_24dp else R.drawable.ic_network_disconnected_24dp)
    }

    override fun showShotsFired() {
        if (b.shotsFiredIv.isVisible) return
        // Show the "shots fired" image
        b.shotsFiredIv.visibility = View.VISIBLE
        b.shotsFiredIv.startAnimation(AnimationUtils.loadAnimation(context.applicationContext, R.anim.fadeout))
        scope.launch {
            delay(HIT_ANIMATION_DURATION_MILLISECONDS)
            b.shotsFiredIv.visibility = View.GONE
        }
    }

    override fun showHitOtherPlayer(kind: HitResolver.Kind, name: String) {
        // A new hit while one is showing replaces it and restarts the timer
        hitPlayerHide?.cancel()
        hitPlayerAnimation?.stop()
        // Show the "you hit a player" / "you hit a player that's already out" / "you hit a player's shield" animation
        b.hitPlayerIv.visibility = View.VISIBLE
        b.hitPlayerIv.setBackgroundResource(when (kind) {
            HitResolver.Kind.HIT -> R.drawable.hit_other_player_animation
            HitResolver.Kind.OUT -> R.drawable.hit_other_player_out_animation
            HitResolver.Kind.SHIELDED -> R.drawable.hit_other_player_shielded_animation
        })
        val fadeOut = AnimationUtils.loadAnimation(context.applicationContext, R.anim.hitplayerfadeout)
        b.hitPlayerIv.startAnimation(fadeOut)
        val animation = b.hitPlayerIv.background as AnimationDrawable
        hitPlayerAnimation = animation
        animation.start()
        b.hitPlayerNameTv.visibility = View.VISIBLE
        b.hitPlayerNameTv.text = name
        b.hitPlayerNameTv.startAnimation(fadeOut)
        hitPlayerHide = scope.launch {
            delay(HIT_PLAYER_DISPLAY_DURATION_MILLISECONDS)
            b.hitPlayerIv.visibility = View.GONE
            animation.stop()
            hitPlayerAnimation = null
            hitPlayerHide = null
            b.hitPlayerNameTv.visibility = View.GONE
        }
    }

    override fun showScoreIncrease(name: String) {
        b.scoreIncreaseIv.visibility = View.VISIBLE
        val fadeOut = AnimationUtils.loadAnimation(context.applicationContext, R.anim.eliminatedfadeout)
        b.scoreIncreaseIv.startAnimation(fadeOut)
        b.scoreIncreasePlayerNameTv.visibility = View.VISIBLE
        b.scoreIncreasePlayerNameTv.text = name
        b.scoreIncreasePlayerNameTv.startAnimation(fadeOut)
        scope.launch {
            delay(ELIMINATED_ANIMATION_DURATION_MILLISECONDS)
            b.scoreIncreaseIv.visibility = View.GONE
            b.scoreIncreasePlayerNameTv.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------------------------------
    // Session
    // ---------------------------------------------------------------------------------------

    /** Last session state drawn; each view is only touched when its value changes. */
    private var rendered: SessionState? = null

    private fun number(value: Int): String = context.getString(R.string.number, value)

    fun render(s: SessionState) {
        val p = rendered
        rendered = s
        if (p == null || p.maxHealth != s.maxHealth) b.healthPb.max = s.maxHealth
        if (p == null || p.health != s.health) b.healthPb.progress = s.health
        if (p == null || p.shotsRemaining != s.shotsRemaining) b.shotsRemainingTv.text = number(s.shotsRemaining.toInt() and 0xFF)
        val reloadBarShown = s.reloadBarShown
        if (reloadBarShown != null && (p == null || reloadBarShown != p.reloadBarShown)) {
            if (reloadBarShown) {
                b.shotsRemainingTv.visibility = View.INVISIBLE
                b.reloadPb.visibility = View.VISIBLE
            } else {
                b.reloadPb.visibility = View.INVISIBLE
                b.shotsRemainingTv.visibility = View.VISIBLE
            }
        }
        if (p == null || p.hitsTaken != s.hitsTaken) b.hitsTakenTv.text = number(s.hitsTaken)
        if (p == null || p.eliminationDisplay != s.eliminationDisplay) b.eliminationsCountTv.text = number(s.eliminationDisplay)
        if (p == null || p.shotMode != s.shotMode) {
            when (s.shotMode) {
                GameSettingsRepository.SHOT_MODE_SINGLE -> b.shotModeTv.setText(R.string.shot_mode_single)
                GameSettingsRepository.SHOT_MODE_BURST -> b.shotModeTv.setText(R.string.shot_mode_burst3)
                GameSettingsRepository.SHOT_MODE_FULL_AUTO -> b.shotModeTv.setText(R.string.shot_mode_auto)
            }
        }
        if (p == null || p.recoilEnabled != s.recoilEnabled) {
            b.recoilTv.setText(if (s.recoilEnabled) R.string.recoil_enabled else R.string.recoil_disabled)
        }
        if (p == null || p.shotModeChangeable != s.shotModeChangeable) {
            setSettingTile(b.shotModeTv, s.shotModeChangeable, R.string.shot_mode_action, onShotModeTapped, endMarginDp = 15)
        }
        if (p == null || p.recoilChangeable != s.recoilChangeable) {
            setSettingTile(b.recoilTv, s.recoilChangeable, R.string.recoil_action, onRecoilTapped)
        }
        val battery = s.battery
        if (battery != null && (p == null || battery != p.battery)) {
            b.batteryIv.setImageResource(when (battery) {
                BatteryLevelPolicy.Level.FULL -> R.drawable.ic_battery_full_green_24dp
                BatteryLevelPolicy.Level.HIGH -> R.drawable.ic_battery_80_blue_24dp
                BatteryLevelPolicy.Level.MEDIUM -> R.drawable.ic_battery_50_yellow_24dp
                else -> R.drawable.ic_battery_alert_red_24dp
            })
        }
        if (p == null || p.hitOverlay != s.hitOverlay) renderHitOverlay(s)
        if (p == null || p.spawn != s.spawn) renderSpawn(s.spawn)
        if (p == null || p.shieldSeconds != s.shieldSeconds) {
            val shield = s.shieldSeconds
            if (shield == null) {
                b.respawnShieldCountdownTv.visibility = View.GONE
            } else {
                b.respawnShieldCountdownTv.text = context.getString(R.string.respawn_shield_label, shield)
                b.respawnShieldCountdownTv.visibility = View.VISIBLE
            }
        }
        if (p == null || p.clock != s.clock) renderClock(s.clock)
    }

    private fun renderHitOverlay(s: SessionState) {
        val name = s.hitOverlay
        if (name != null) {
            // Show the "you're being hit" animation
            b.hitAnimationIv.visibility = View.VISIBLE
            b.hitAnimationIv.setBackgroundResource(R.drawable.hit_animation)
            val fadeOut = AnimationUtils.loadAnimation(context.applicationContext, R.anim.fadeout)
            b.hitAnimationIv.startAnimation(fadeOut)
            val animation = b.hitAnimationIv.background as AnimationDrawable
            hitAnimation = animation
            animation.start()
            b.eliminatedByTv.visibility = View.VISIBLE
            b.eliminatedByTv.text = name
            b.eliminatedByTv.startAnimation(fadeOut)
        } else {
            b.hitAnimationIv.visibility = View.GONE
            hitAnimation?.stop()
            hitAnimation = null
            if (s.spawn is SpawnPhase.None) b.eliminatedByTv.visibility = View.INVISIBLE
        }
    }

    private fun renderSpawn(spawn: SpawnPhase) {
        b.startRespawnButton.visibility = if (spawn is SpawnPhase.AwaitingRespawn) View.VISIBLE else View.GONE
        when (spawn) {
            is SpawnPhase.Spawning -> {
                b.eliminatedTv.setText(if (spawn.startingGame) R.string.starting_game_label else R.string.eliminated_label)
                b.eliminatedTv.visibility = View.VISIBLE
                b.eliminatedByTv.text = spawn.eliminatedBy
                b.eliminatedByTv.visibility = View.VISIBLE
                b.spawnCountdownTv.text = context.resources.getString(R.string.spawn_in_label, spawn.seconds)
                b.spawnCountdownTv.visibility = View.VISIBLE
            }
            is SpawnPhase.AwaitingRespawn -> {
                b.eliminatedTv.setText(R.string.eliminated_label)
                b.eliminatedTv.visibility = View.VISIBLE
                b.eliminatedByTv.text = spawn.eliminatedBy
                b.eliminatedByTv.visibility = View.VISIBLE
                b.spawnCountdownTv.setText(R.string.manual_respawn_hint)
                b.spawnCountdownTv.visibility = View.VISIBLE
            }
            is SpawnPhase.OutOfLives -> {
                b.eliminatedTv.visibility = View.VISIBLE
                b.eliminatedByTv.text = spawn.eliminatedBy
                b.eliminatedByTv.visibility = View.VISIBLE
                b.spawnCountdownTv.setText(R.string.dialog_out_of_lives)
                b.spawnCountdownTv.visibility = View.VISIBLE
            }
            SpawnPhase.None -> {
                b.eliminatedTv.visibility = View.INVISIBLE
                b.eliminatedByTv.visibility = View.INVISIBLE
                b.spawnCountdownTv.visibility = View.GONE
            }
        }
    }

    private fun renderClock(clock: GameClock) {
        when (clock) {
            is GameClock.Chronometer -> {
                b.gameTimerChronometer.base = clock.base
                b.gameTimerChronometer.start()
            }
            is GameClock.Countdown -> b.gameCountdownTv.text = clock.text
            is GameClock.Stopped -> {
                b.gameTimerChronometer.stop()
                clock.countdownText?.let { b.gameCountdownTv.text = it }
            }
        }
    }

    companion object {
        // If you change these durations you will also need to change the duration in the res/anim/fadeout XML files to match
        const val HIT_ANIMATION_DURATION_MILLISECONDS = 400L
        const val HIT_PLAYER_DISPLAY_DURATION_MILLISECONDS = 1000L // res/anim/hitplayerfadeout
        const val ELIMINATED_ANIMATION_DURATION_MILLISECONDS = 800L
    }
}
