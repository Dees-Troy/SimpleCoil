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

package com.simplecoil.simplecoil.ui.scoreboard

import com.simplecoil.simplecoil.domain.team.TeamLayout
import com.simplecoil.simplecoil.network.ServerMessage

/** What the scoreboard dialog's matrix face shows. */
sealed interface ScoreMatrixState {
    /** Asked, no answer yet. */
    data object Loading : ScoreMatrixState

    /** No answer in time: an older server. */
    data object Unsupported : ScoreMatrixState
    data class Ready(val matrix: ScoreMatrixModel) : ScoreMatrixState
}

/**
 * Who eliminated whom (score matrix design §5.2): rows are the shooters, columns the victims, in
 * the same order, so the diagonal is the own-elimination line. Built from the server's sparse
 * reply; pure.
 */
class ScoreMatrixModel(
    /** Row order, which is also the column order. */
    val players: List<Entry>,
    /** counts[row][col]: times players[row] eliminated players[col]. */
    val counts: Array<IntArray>,
    /** Eliminations made, per row. */
    val rowTotals: IntArray,
    /** Times eliminated by a known shooter, per column. */
    val columnTotals: IntArray,
    /** True in team games: names get team colours and same-team blocks are tinted. */
    val teams: Boolean,
    val gameId: Long?,
) {
    data class Entry(val playerId: Byte, val name: String, val team: Int, val connected: Boolean, val isSelf: Boolean)

    val size: Int get() = players.size
    val isEmpty: Boolean get() = rowTotals.all { it == 0 }
    val total: Int get() = rowTotals.sum()

    /** The row (and column) of this phone's own player, or -1. */
    val selfIndex: Int get() = players.indexOfFirst { it.isSelf }

    override fun equals(other: Any?): Boolean = other is ScoreMatrixModel && players == other.players &&
        counts.contentDeepEquals(other.counts) && teams == other.teams && gameId == other.gameId

    override fun hashCode(): Int = 31 * players.hashCode() + counts.contentDeepHashCode()

    companion object {
        /**
         * The dense matrix of [message]. Team games are ordered by team, then id, so teams form
         * diagonal blocks; free for all by id. Cells naming a player not in the list are ignored and
         * negative counts read as 0.
         */
        fun from(message: ServerMessage.ScoreMatrix, layout: TeamLayout, selfId: Byte): ScoreMatrixModel {
            val teams = !layout.mode.isFreeForAll
            val players = message.players
                .distinctBy { it.playerId }
                .map { Entry(it.playerId.toByte(), it.name, if (teams) it.team else 1, it.connected, it.playerId.toByte() == selfId) }
                .sortedWith(if (teams) compareBy({ it.team }, { it.playerId.toInt() and 0xFF }) else compareBy { it.playerId.toInt() and 0xFF })
            val indexOf = HashMap<Int, Int>()
            players.forEachIndexed { index, entry -> indexOf[entry.playerId.toInt() and 0xFF] = index }
            val counts = Array(players.size) { IntArray(players.size) }
            for (cell in message.cells) {
                val row = indexOf[cell.shooterId] ?: continue
                val col = indexOf[cell.victimId] ?: continue
                counts[row][col] += cell.count.coerceAtLeast(0)
            }
            val rowTotals = IntArray(players.size) { counts[it].sum() }
            val columnTotals = IntArray(players.size) { col -> counts.sumOf { it[col] } }
            return ScoreMatrixModel(players, counts, rowTotals, columnTotals, teams, message.gameId)
        }
    }
}
