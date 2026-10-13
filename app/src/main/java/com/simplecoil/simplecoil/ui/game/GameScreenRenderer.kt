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
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.AnimationUtils
import androidx.core.view.isVisible
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.databinding.ActivityFullscreenBinding
import com.simplecoil.simplecoil.domain.session.BatteryLevelPolicy
import com.simplecoil.simplecoil.domain.session.ClockFormat
import com.simplecoil.simplecoil.domain.session.GameClock
import com.simplecoil.simplecoil.domain.session.SessionState
import com.simplecoil.simplecoil.domain.session.SpawnPhase
import com.simplecoil.simplecoil.domain.team.TeamMode
import kotlinx.coroutines.CoroutineScope
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
) : GameView {

    private val context get() = b.root.context
    private var hitAnimation: AnimationDrawable? = null
    private var hitPlayerAnimation: AnimationDrawable? = null

    // ---------------------------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------------------------

    override fun showConnectScreen(reconnectVisible: Boolean) {
        b.playLayout.visibility = View.GONE
        b.connectLayout.visibility = View.VISIBLE
        if (serverOnly) {
            b.connectWeaponButton.visibility = View.GONE
            b.connectQrWeaponButton.visibility = View.GONE
            b.connectStatusTv.visibility = View.GONE
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

    override fun showConnectStatus(status: ConnectStatus) {
        b.connectStatusTv.setText(when (status) {
            ConnectStatus.SCANNING -> R.string.connect_status_scanning
            ConnectStatus.CONNECTING -> R.string.connect_status_connecting
            ConnectStatus.COMMUNICATING -> R.string.connect_status_communicating
            ConnectStatus.NOT_CONNECTED -> R.string.connect_status_not_connected
        })
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
        b.endNetworkGameButton.visibility = View.GONE
        displayAllNetworkingOptions(useNetwork, freeForAll)
        b.useNetworkButton.visibility = View.VISIBLE
        b.hitPlayerIv.visibility = View.GONE
        b.hitPlayerNameTv.visibility = View.GONE
        b.shotsFiredIv.visibility = View.GONE
    }

    override fun showScore(score: Int) {
        b.scoreTv.text = number(score)
    }

    override fun showTeamScore(teamScore: Int) {
        b.teamScoreTv.text = number(teamScore)
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
    }

    override fun showPlayerSettings(fullHealth: Int, fullReload: Byte, damage: Int) {
        b.healthLabelTv.text = context.getString(R.string.health_label, fullHealth)
        b.shotsLabelTv.text = context.getString(R.string.shots_remaining_label, fullReload, damage)
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

    /** The team mode is a button for a server-less client before the game, otherwise a label. */
    private fun showTeamModeSelectable(selectable: Boolean) {
        b.teamModeButton.visibility = if (selectable) View.VISIBLE else View.GONE
        b.teamModeTv.visibility = if (selectable) View.GONE else View.VISIBLE
    }

    override fun showTeam(team: TeamDisplay) {
        if (team is TeamDisplay.Team) {
            b.teamLabelTv.text = context.getString(R.string.team_number_label, team.team)
            if (team.color != Color.TRANSPARENT) {
                b.teamLabelTv.background = GradientDrawable().apply {
                    setColor(team.color)
                    cornerRadius = 8f
                }
                b.teamLabelTv.setPadding(24, 12, 24, 12)
                b.teamLabelTv.setTextColor(if (team.team == 4) Color.BLACK else Color.WHITE)
            } else {
                clearTeamLabelStyle()
            }
            b.teamTv.text = team.playerName ?: context.getString(R.string.network_team, team.player)
            val visibility = if (team.teamScoreVisible) View.VISIBLE else View.INVISIBLE
            b.teamScoreLabelTv.visibility = visibility
            b.teamScoreTv.visibility = visibility
            return
        }
        b.teamLabelTv.setText(R.string.team_label)
        clearTeamLabelStyle()
        if (team is TeamDisplay.FreeForAll) {
            b.teamTv.text = number(team.playerId.toInt())
        } else {
            b.teamTv.setText(R.string.auto_sort_team)
        }
        b.teamScoreLabelTv.visibility = View.INVISIBLE
        b.teamScoreTv.visibility = View.INVISIBLE
    }

    private fun clearTeamLabelStyle() {
        b.teamLabelTv.background = null
        b.teamLabelTv.setPadding(0, 0, 0, 0)
        b.teamLabelTv.setTextColor(Color.WHITE)
    }

    override fun showPlayerName(name: String) {
        b.playerNameLabelTv.text = name
    }

    override fun showPreGameButtons(buttons: PreGameButtons) {
        val lobbyVisibility = if (buttons.ready) View.INVISIBLE else View.VISIBLE
        b.teamMinusButton.visibility = lobbyVisibility
        b.teamPlusButton.visibility = lobbyVisibility
        b.gameLimitButton.visibility = if (buttons.ready) View.GONE else View.VISIBLE
        if (!buttons.startVisible) {
            b.startGameButton.visibility = View.GONE
        } else {
            b.startGameButton.visibility = View.VISIBLE
            b.startGameButton.isEnabled = buttons.startEnabled
        }
        val playerSettingsVisibility = if (buttons.serverSettingsOnly) View.GONE else View.VISIBLE
        b.firingModeButton.visibility = playerSettingsVisibility
        b.playerSettingsButton.visibility = playerSettingsVisibility
        b.shuffleTeamsButton.visibility = View.GONE
        showTeamModeSelectable(buttons.teamModeSelectable)
    }

    override fun showAdminButton(visible: Boolean) {
        b.adminControlsButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------------------------

    private fun displayAllNetworkingOptions(enabled: Boolean, freeForAll: Boolean) {
        val visibility = if (enabled) View.VISIBLE else View.INVISIBLE
        // The team mode stays visible offline too; showTeamModeSelectable picks button or label.
        b.teamModeLabelTv.visibility = View.VISIBLE
        b.scoreLabelTv.visibility = visibility
        b.scoreTv.visibility = visibility
        b.playerNameLabelTv.visibility = visibility
        b.serverIpTv.visibility = View.GONE
        val teamScoreVisibility = if (!freeForAll) visibility else View.INVISIBLE
        b.teamScoreLabelTv.visibility = teamScoreVisibility
        b.teamScoreTv.visibility = teamScoreVisibility
    }

    private fun displayInGameNetworkingOptions(isAdmin: Boolean, freeForAll: Boolean) {
        displayAllNetworkingOptions(false, freeForAll)
        b.playerCountTv.visibility = View.VISIBLE
        b.teamModeLabelTv.visibility = View.VISIBLE
        b.scoreLabelTv.visibility = View.VISIBLE
        b.scoreTv.visibility = View.VISIBLE
        b.endNetworkGameButton.visibility = if (isAdmin) View.VISIBLE else View.GONE
        if (!freeForAll) {
            b.teamScoreLabelTv.visibility = View.VISIBLE
            b.teamScoreTv.visibility = View.VISIBLE
        }
    }

    override fun showNetworkOptions(enabled: Boolean, freeForAll: Boolean) {
        displayAllNetworkingOptions(enabled, freeForAll)
    }

    override fun showNetworkButton(button: NetworkButton) {
        b.useNetworkButton.setText(when (button) {
            NetworkButton.USE_NETWORK -> R.string.use_network_button
            NetworkButton.MENU -> R.string.network_menu_button
            NetworkButton.SEARCHING -> R.string.network_searching_status
            NetworkButton.CONNECTED -> R.string.network_connected_status
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

    override fun setPlayerDataButtonVisible(visible: Boolean) {
        b.playerDataButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun showPlayerCount(count: Int) {
        b.playerCountTv.text = context.getString(R.string.network_player_count, count)
        b.playerCountTv.visibility = View.VISIBLE
    }

    override fun showOnlyOnePlayer() {
        b.playerCountTv.setText(R.string.network_player_1count)
    }

    override fun showConnectedToServer(ip: String) {
        b.serverIpTv.text = context.getString(R.string.server_status_connected_to, ip)
        b.serverIpTv.visibility = View.VISIBLE
        b.playerDataButton.visibility = View.VISIBLE
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

    override fun showHitOtherPlayer(out: Boolean, name: String) {
        if (hitPlayerAnimation != null) return
        // Show the "you hit a player" / "you hit a player that's already out" animation
        b.hitPlayerIv.visibility = View.VISIBLE
        b.hitPlayerIv.setBackgroundResource(if (out) R.drawable.hit_other_player_out_animation else R.drawable.hit_other_player_animation)
        val fadeOut = AnimationUtils.loadAnimation(context.applicationContext, R.anim.fadeout)
        b.hitPlayerIv.startAnimation(fadeOut)
        val animation = b.hitPlayerIv.background as AnimationDrawable
        hitPlayerAnimation = animation
        animation.start()
        b.hitPlayerNameTv.visibility = View.VISIBLE
        b.hitPlayerNameTv.text = name
        b.hitPlayerNameTv.startAnimation(fadeOut)
        scope.launch {
            delay(HIT_ANIMATION_DURATION_MILLISECONDS)
            b.hitPlayerIv.visibility = View.GONE
            animation.stop()
            hitPlayerAnimation = null
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
        if (p == null || p.shotsRemaining != s.shotsRemaining) b.shotsRemainingTv.text = number(s.shotsRemaining.toInt())
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
        const val ELIMINATED_ANIMATION_DURATION_MILLISECONDS = 800L
    }
}
