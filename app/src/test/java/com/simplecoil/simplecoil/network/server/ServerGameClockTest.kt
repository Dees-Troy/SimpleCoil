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

import com.simplecoil.simplecoil.data.repository.GameStateRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

/** The dedicated server's game clock under virtual time (dedicated server design §5.3, S2). */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerGameClockTest {

    private val scheduler = TestCoroutineScheduler()
    private val gameState = GameStateRepository()
    private val states = mutableListOf<ClockState>()
    private var timeUps = 0
    private val clock = ServerGameClock(StandardTestDispatcher(scheduler), { scheduler.currentTime }, gameState,
        onStateChanged = { states += it }, onTimeUp = { timeUps++ })

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    @Test
    fun timedGame_countsTheLimitPlusTheStartingTimer_inSecondTicks() {
        advance(500) // the clock is not zero-based
        clock.start(timeLimitMinutes = 2, startTimeSeconds = 5)
        assertEquals(listOf<ClockState>(ClockState.Counting(500 + 125_000)), states)
        scheduler.runCurrent() // the first tick is posted, as CountDownTimer's is
        assertEquals(125, gameState.serverGameTimeRemaining)
        advance(1000)
        assertEquals(124, gameState.serverGameTimeRemaining)
        advance(60_000)
        assertEquals(64, gameState.serverGameTimeRemaining)
        assertEquals(0, timeUps)
    }

    @Test
    fun timedGame_endsTheGameWhenTimeIsUp() {
        clock.start(timeLimitMinutes = 1, startTimeSeconds = 5)
        advance(64_999)
        assertEquals(0, timeUps)
        assertEquals(1, gameState.serverGameTimeRemaining)
        advance(1)
        assertEquals(1, timeUps)
        assertEquals(0, gameState.serverGameTimeRemaining)
        advance(10_000)
        assertEquals(1, timeUps) // once
    }

    @Test
    fun timedGame_aSleepingDevice_endsTheGameAtItsFirstTickAfterWaking() {
        // elapsedRealtime counts deep sleep; the coroutine's delay doesn't
        var now = 0L
        val sleepy = ServerGameClock(StandardTestDispatcher(scheduler), { now + scheduler.currentTime }, gameState, { }, { timeUps++ })
        sleepy.start(timeLimitMinutes = 10, startTimeSeconds = 0)
        scheduler.runCurrent()
        now += 11 * 60_000L
        advance(1000)
        assertEquals(1, timeUps)
    }

    @Test
    fun untimedGame_startsElapsing_whenTheStartingTimerEnds() {
        clock.start(timeLimitMinutes = null, startTimeSeconds = 5)
        assertEquals(listOf<ClockState>(ClockState.Starting(5000)), states)
        advance(4999)
        assertEquals(1, states.size)
        advance(1)
        assertEquals(ClockState.Elapsed(5000), states.last())
        assertEquals(0, gameState.serverGameTimeRemaining)
    }

    @Test
    fun untimedGame_withoutAStartingTimer_elapsesAtOnce() {
        clock.start(timeLimitMinutes = null, startTimeSeconds = 0)
        scheduler.runCurrent()
        assertEquals(ClockState.Elapsed(0), clock.state)
    }

    @Test
    fun stop_cancelsTheCountdown_andClearsTheTimeRemaining() {
        clock.start(timeLimitMinutes = 1, startTimeSeconds = 0)
        advance(10_000)
        assertEquals(50, gameState.serverGameTimeRemaining)
        clock.stop()
        assertEquals(0, gameState.serverGameTimeRemaining)
        assertEquals(ClockState.Idle, clock.state)
        advance(120_000)
        assertEquals(0, timeUps)
        assertEquals(0, gameState.serverGameTimeRemaining)
        assertEquals(listOf(ClockState.Counting(60_000), ClockState.Idle), states)
    }

    @Test
    fun stop_whenIdle_publishesNothing() {
        clock.stop()
        assertEquals(emptyList<ClockState>(), states)
    }
}
