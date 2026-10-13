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

package com.simplecoil.simplecoil.network.server

import android.util.Log
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.domain.session.countDown
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The dedicated server's game clock, as timestamps on [ServerGameClock]'s clock
 * (SystemClock.elapsedRealtime), so a screen can render it whenever it exists.
 */
sealed interface ClockState {
    data object Idle : ClockState

    /** An untimed game's starting timer, running until [endsAtElapsed]. */
    data class Starting(val endsAtElapsed: Long) : ClockState

    /** A timed game: the time limit plus the starting timer, ending at [endsAtElapsed]. */
    data class Counting(val endsAtElapsed: Long) : ClockState

    /** An untimed game, under way since [startedAtElapsed]. */
    data class Elapsed(val startedAtElapsed: Long) : ClockState
}

/**
 * Enforces the time limit on the server, whether or not its screen exists (dedicated server
 * design §5.3, S2). Runs on [dispatcher], the server's dispatcher, so it is ordered with every
 * other change to the session. [clock] counts deep sleep, so a timed game ends on time once a
 * sleeping device wakes.
 */
class ServerGameClock(
    dispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val gameState: GameStateRepository,
    /** Every change of [state], on the dispatcher. */
    private val onStateChanged: (ClockState) -> Unit,
    /** Time is up (on the dispatcher): end the game. */
    private val onTimeUp: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Game clock failed", e) // must not take the server down (N5)
    })
    private var timer: Job? = null
    private val _state = MutableStateFlow<ClockState>(ClockState.Idle)

    val stateFlow: StateFlow<ClockState> = _state.asStateFlow()

    /** Any thread. */
    val state: ClockState get() = _state.value

    /**
     * Starts the clock for a game that just started: a timed game counts [timeLimitMinutes]
     * plus [startTimeSeconds] down in 1 s ticks, writing the seconds left (sent to joining
     * players) on each; an untimed one ([timeLimitMinutes] null) counts from the end of the
     * starting timer.
     */
    fun start(timeLimitMinutes: Int?, startTimeSeconds: Long) {
        timer?.cancel()
        val now = clock()
        if (timeLimitMinutes != null) {
            val totalMillis = timeLimitMinutes * 60_000L + startTimeSeconds * 1000
            setState(ClockState.Counting(now + totalMillis))
            timer = scope.countDown(clock, totalMillis, TICK_MILLIS,
                onTick = { millisLeft -> gameState.serverGameTimeRemaining = millisLeft / 1000 },
                onFinish = {
                    Log.d(TAG, "Game time ended")
                    timer = null
                    gameState.serverGameTimeRemaining = 0
                    onTimeUp()
                })
        } else {
            val startMillis = startTimeSeconds * 1000
            setState(ClockState.Starting(now + startMillis))
            timer = scope.countDown(clock, startMillis, TICK_MILLIS, onTick = {}, onFinish = {
                timer = null
                setState(ClockState.Elapsed(clock()))
            })
        }
    }

    /** The game ended or the session was reset: no time remains. */
    fun stop() {
        timer?.cancel()
        timer = null
        gameState.serverGameTimeRemaining = 0
        setState(ClockState.Idle)
    }

    private fun setState(state: ClockState) {
        if (_state.value == state) return
        _state.value = state
        onStateChanged(state)
    }

    private companion object {
        const val TAG = "ServerGameClock"
        const val TICK_MILLIS = 1000L
    }
}
