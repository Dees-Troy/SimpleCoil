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

package com.simplecoil.simplecoil.network.client

import com.simplecoil.simplecoil.network.server.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectPolicyTest {

    private val policy = ReconnectPolicy()

    @Test
    fun lobby_givesUpAfterThreeTries() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L), (1..3).map { policy.nextDelay(SessionPhase.LOBBY) })
        assertNull(policy.nextDelay(SessionPhase.LOBBY))
    }

    @Test
    fun runningOrAfterTheGame_triesForever_withTheBackoffCappedAt5s() {
        // D6: a client that dropped after the game used to give up after 3 tries.
        val delays = (1..50).map { policy.nextDelay(if (it % 2 == 0) SessionPhase.RUNNING else SessionPhase.POST_GAME) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L), delays.take(5))
        assertEquals(5_000L, delays.last())
    }

    @Test
    fun aResetSeenWhileRetrying_switchesToTheLobbyLimit() {
        repeat(10) { policy.nextDelay(SessionPhase.RUNNING) }
        assertNull(policy.nextDelay(SessionPhase.LOBBY))
    }

    @Test
    fun connecting_startsOver() {
        repeat(3) { policy.nextDelay(SessionPhase.LOBBY) }
        policy.onConnected()
        assertEquals(1_000L, policy.nextDelay(SessionPhase.LOBBY))
    }

    @Test
    fun wifiAvailable_resetsTheBackoff_butNotTheLobbyTries() {
        repeat(3) { policy.nextDelay(SessionPhase.RUNNING) }
        policy.onNetworkAvailable()
        assertEquals(1_000L, policy.nextDelay(SessionPhase.RUNNING))
        val lobby = ReconnectPolicy()
        repeat(3) { lobby.nextDelay(SessionPhase.LOBBY) }
        lobby.onNetworkAvailable()
        assertNull(lobby.nextDelay(SessionPhase.LOBBY))
    }
}
