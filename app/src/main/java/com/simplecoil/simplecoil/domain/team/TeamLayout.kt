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

package com.simplecoil.simplecoil.domain.team

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository

/**
 * The team mode and the team sizes, which always change together. Immutable, so a reader that
 * takes one instance sees a consistent mode and sizes even while another thread swaps in a new
 * layout (STATE_CONSOLIDATION_DESIGN.md §3.3).
 *
 * Player ids 1..[MAX_PLAYER_ID] are dealt out to the teams in order: with sizes [8, 8, 8, 8],
 * ids 1-8 are team 1, 9-16 team 2, and so on. Free for all keeps a two-team size array, as the
 * protocol always has, so older clients and servers read it the same way.
 */
class TeamLayout private constructor(
    val mode: TeamMode,
    private val sizes: IntArray,
) {

    /** A copy of the team sizes; free for all reports the two-team sizes it carries. */
    fun teamSizes(): IntArray = sizes.copyOf()

    /** The team [playerId] is on (1-based), the id itself in free for all, or INVALID_PLAYER_ID. */
    fun teamOf(playerId: Int): Int {
        if (playerId < 1 || playerId > MAX_PLAYER_ID) return PlayerRepository.INVALID_PLAYER_ID
        if (mode.isFreeForAll) return playerId
        val numTeams = mode.teamCount
        var lastIdOnTeam = 0
        for (t in 0 until numTeams) {
            lastIdOnTeam += sizes[t]
            if (playerId <= lastIdOnTeam) return t + 1
        }
        return numTeams
    }

    fun teamOf(playerId: Byte): Int = teamOf(playerId.toInt())

    /** The first player id on [team]. */
    fun teamStartId(team: Int): Int {
        var startId = 1
        var t = 1
        while (t < team && t <= sizes.size) {
            startId += sizes[t - 1]
            t++
        }
        return startId
    }

    /** The last player id on [team], or [MAX_PLAYER_ID] for a team that doesn't exist. */
    fun teamEndId(team: Int): Int {
        if (team < 1 || team > sizes.size) return MAX_PLAYER_ID
        return teamStartId(team) + sizes[team - 1] - 1
    }

    /** This layout in [newMode]: unchanged if the mode is the same, otherwise the default sizes. */
    fun withMode(newMode: TeamMode): TeamLayout = if (newMode == mode) this else defaultFor(newMode)

    /**
     * This mode with [newSizes]. Null keeps the current sizes. Sizes that don't fit the mode (wrong
     * team count, or not adding up to [MAX_PLAYER_ID]) fall back to the defaults.
     */
    fun withSizes(newSizes: IntArray?): TeamLayout {
        if (newSizes == null) return this
        if (!isValid(mode, newSizes)) return defaultFor(mode)
        return TeamLayout(mode, newSizes.copyOf())
    }

    override fun equals(other: Any?): Boolean =
        other is TeamLayout && other.mode == mode && other.sizes.contentEquals(sizes)

    override fun hashCode(): Int = 31 * mode.hashCode() + sizes.contentHashCode()

    override fun toString(): String = "TeamLayout($mode, ${sizes.contentToString()})"

    companion object {
        private const val MAX_PLAYER_ID = GameSettingsRepository.MAX_PLAYER_ID.toInt()

        @JvmField val DEFAULT = defaultFor(TeamMode.DEFAULT)

        @JvmStatic
        fun defaultFor(mode: TeamMode): TeamLayout =
            if (mode == TeamMode.FOUR_TEAMS) TeamLayout(mode, intArrayOf(8, 8, 8, 8))
            else TeamLayout(mode, intArrayOf(16, 16))

        /** [mode] with [sizes] (see [withSizes]). */
        @JvmStatic
        fun of(mode: TeamMode, sizes: IntArray?): TeamLayout = defaultFor(mode).withSizes(sizes)

        private fun isValid(mode: TeamMode, sizes: IntArray): Boolean {
            val expectedTeams = if (mode == TeamMode.FOUR_TEAMS) 4 else 2
            return sizes.size == expectedTeams && sizes.sum() == MAX_PLAYER_ID
        }
    }
}
