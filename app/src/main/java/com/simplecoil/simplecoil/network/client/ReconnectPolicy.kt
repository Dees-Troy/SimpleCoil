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

/**
 * When the client tries to reach the server again (reconnect design §3.6). While the server
 * could still recognize the player (a game is running, or it ended and was not reset yet) it
 * keeps trying for good (D6); in the lobby there is nothing to come back to, so it gives up after
 * [maxLobbyTries]. Attempts are spaced 1 s, 2 s, 4 s, then 5 s apart, so an unreachable network
 * can't make it spin (D10). Thread-safe.
 */
class ReconnectPolicy(private val maxLobbyTries: Int = MAX_LOBBY_TRIES) {

    private var tries = 0
    private var backoffStep = 0

    /**
     * The connection was lost or an attempt failed, with the server last seen in [phase]. Returns
     * how long to wait before the next attempt, or null to give up.
     */
    @Synchronized
    fun nextDelay(phase: SessionPhase): Long? {
        tries++
        if (phase == SessionPhase.LOBBY && tries > maxLobbyTries) return null
        val delay = BACKOFF_MILLIS[minOf(backoffStep, BACKOFF_MILLIS.size - 1)]
        backoffStep++
        return delay
    }

    /** Connected: the next loss starts over. */
    @Synchronized
    fun onConnected() {
        tries = 0
        backoffStep = 0
    }

    /** Wi-Fi came back: try again at once and back off from the start. */
    @Synchronized
    fun onNetworkAvailable() {
        backoffStep = 0
    }

    companion object {
        const val MAX_LOBBY_TRIES = 3
        private val BACKOFF_MILLIS = longArrayOf(1_000, 2_000, 4_000, 5_000)
    }
}
