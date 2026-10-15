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

package com.simplecoil.simplecoil.ui.server

import android.os.SystemClock
import android.view.View
import androidx.core.view.children
import com.google.android.material.button.MaterialButtonToggleGroup
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.databinding.ActivityDedicatedServerBinding
import com.simplecoil.simplecoil.domain.session.ClockFormat
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.network.server.ClockState
import com.simplecoil.simplecoil.network.server.SessionPhase
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardAdapter
import java.util.concurrent.TimeUnit

/** Draws [ServerUiState] on the server screen's views. Main thread. */
class DedicatedServerRenderer(
    private val binding: ActivityDedicatedServerBinding,
    private val scoreboard: ScoreboardAdapter,
) {
    private val resources get() = binding.root.resources

    /** The clock as last drawn. */
    private var clock: ClockState? = null

    /** Redraws a timed game's countdown once a second while it counts. */
    private val countdownTicker = Runnable { showCountdown() }

    fun render(state: ServerUiState) = with(binding) {
        serverIpTv.text = state.serverIp?.let { resources.getString(R.string.server_status_serving_on, it) } ?: ""
        playerCountTv.text = quantity(R.plurals.network_player_count, state.playerCount)
        checkSegment(teamModeGroup, when (state.teamMode) {
            TeamMode.TWO_TEAMS -> R.id.team_mode_2teams_button
            TeamMode.FOUR_TEAMS -> R.id.team_mode_4teams_button
            TeamMode.FREE_FOR_ALL -> R.id.team_mode_ffa_button
        })
        checkSegment(gpsModeGroup, when {
            !state.useGps -> R.id.gps_mode_disabled_button
            state.gpsMode == GameSettingsRepository.GPS_TEAMMATE -> R.id.gps_mode_teammate_button
            else -> R.id.gps_mode_all_button
        })
        renderLimits(state)
        renderSwitches(state)
        renderPhase(state.phase, state.teamMode)
        renderClock(state)
        // A diffed update keeps the scroll position (S10)
        scoreboard.submitList(state.scoreboard)
    }

    /** Stops the clock's ticks while the screen is stopped; the next [render] draws it again. */
    fun pause() {
        clock = null
        binding.gameCountdownTv.removeCallbacks(countdownTicker)
        binding.gameTimerChronometer.stop()
    }

    private fun renderLimits(state: ServerUiState) = with(binding) {
        val limits = state.limits
        val parts = mutableListOf<String>()
        val timed = limits.gameLimit and GameSettingsRepository.GAME_LIMIT_TIME != 0
        if (timed) parts += quantity(R.plurals.dedicated_time_limit, limits.timeLimit)
        if (limits.gameLimit and GameSettingsRepository.GAME_LIMIT_LIVES != 0) parts += quantity(R.plurals.dedicated_lives_limit, limits.livesLimit)
        if (limits.gameLimit and GameSettingsRepository.GAME_LIMIT_SCORE != 0) parts += quantity(R.plurals.dedicated_score_limit, limits.scoreLimit)
        gameLimitTv.text = if (parts.isEmpty()) resources.getString(R.string.dedicated_unlimited)
        else parts.joinToString(resources.getString(R.string.dedicated_limit_separator))
        // A timed game counts down; an untimed one runs the chronometer
        gameCountdownTv.visibility = if (timed) View.VISIBLE else View.GONE
        gameTimerChronometer.visibility = if (timed) View.GONE else View.VISIBLE
    }

    private fun renderSwitches(state: ServerUiState) = with(binding) {
        if (allowJoinSwitch.isChecked != state.allowJoin) allowJoinSwitch.isChecked = state.allowJoin
        if (onlyServerSettingsSwitch.isChecked != state.onlyServerSettings) onlyServerSettingsSwitch.isChecked = state.onlyServerSettings
        if (allowChooseTeamsSwitch.isChecked != state.allowChooseTeams) allowChooseTeamsSwitch.isChecked = state.allowChooseTeams
    }

    /** Buttons and status for the server's session phase (reconnect design §3.1). */
    private fun renderPhase(phase: SessionPhase, teamMode: TeamMode) = with(binding) {
        val running = phase == SessionPhase.RUNNING
        val postGame = phase == SessionPhase.POST_GAME
        // All three steps stay in view; only the one to the next phase is enabled
        startGameButton.isEnabled = phase == SessionPhase.LOBBY
        endGameButton.isEnabled = running
        resetGameButton.isEnabled = postGame
        teamModeGroup.children.forEach { it.isEnabled = !running }
        sortTeamsButton.isEnabled = !running
        gameLimitButton.isEnabled = !running
        startingTimerButton.isEnabled = !running
        gpsModeGroup.children.forEach { it.isEnabled = !running }
        // Free for all has no teammates: the server treats teammate GPS as off (shown as Off)
        gpsModeTeammateButton.isEnabled = !running && !teamMode.isFreeForAll
        gameStatusTv.setText(when {
            running -> R.string.dedicated_game_running
            postGame -> R.string.dedicated_game_over
            else -> R.string.dedicated_game_waiting
        })
    }

    /**
     * The server's game clock (S2: the server keeps the time; this only draws it). A timed game
     * counts its time limit plus the starting timer down; an untimed one runs the chronometer
     * from the end of the starting timer. Before a timed game, the limit shows as MM:00.
     */
    private fun renderClock(state: ServerUiState) = with(binding) {
        val newClock = state.clock
        if (newClock == ClockState.Idle && state.limits.gameLimit and GameSettingsRepository.GAME_LIMIT_TIME != 0) {
            gameCountdownTv.text = ClockFormat.minutes(state.limits.timeLimit.toLong())
        }
        if (newClock == clock) return
        clock = newClock
        gameCountdownTv.removeCallbacks(countdownTicker)
        when (newClock) {
            is ClockState.Counting -> showCountdown()
            is ClockState.Elapsed -> {
                gameTimerChronometer.base = newClock.startedAtElapsed
                gameTimerChronometer.start()
            }
            ClockState.Idle -> gameTimerChronometer.stop()
            is ClockState.Starting -> {}
        }
    }

    private fun showCountdown() {
        val counting = clock as? ClockState.Counting ?: return
        val millisLeft = maxOf(0, counting.endsAtElapsed - SystemClock.elapsedRealtime())
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millisLeft)
        binding.gameCountdownTv.text = ClockFormat.minutesSeconds(minutes, TimeUnit.MILLISECONDS.toSeconds(millisLeft) - TimeUnit.MINUTES.toSeconds(minutes))
        if (millisLeft > 0) binding.gameCountdownTv.postDelayed(countdownTicker, 1000 - millisLeft % 1000 + 1)
    }

    private fun checkSegment(group: MaterialButtonToggleGroup, id: Int) {
        if (group.checkedButtonId != id) group.check(id)
    }

    private fun quantity(id: Int, count: Int): String = resources.getQuantityString(id, count, count)
}
