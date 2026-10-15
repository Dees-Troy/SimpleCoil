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

import com.simplecoil.simplecoil.PlayerDisplayData
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.domain.team.TeamLayout

/** One line of the scoreboard. Everything a row shows is in it, so DiffUtil sees every change. */
sealed interface ScoreboardRow {
    /** The column titles; the last column is lives remaining when a lives limit is on. */
    data class ColumnHeader(val livesColumn: Boolean) : ScoreboardRow

    /** A team's header ([team] 0: the free-for-all "players" header). Tapping it edits the team sizes when [editable]. */
    data class TeamHeader(
        val team: Int,
        val playerCount: Int,
        val capacity: Int,
        val score: Int,
        val editable: Boolean,
    ) : ScoreboardRow

    data class Player(
        val playerId: Byte,
        val name: String?,
        val points: Int,
        /** Lives remaining when the player has a lives limit, eliminations otherwise. */
        val livesOrEliminations: Int,
        val connected: Boolean,
        /** This phone's own player. */
        val isSelf: Boolean,
        val kickable: Boolean,
        /** GattProtocolHandler.BLASTER_TYPE_*; 0 when unknown. */
        val blasterType: Byte = 0,
        val hasGrenade: Boolean = false,
    ) : ScoreboardRow
}

/** What the scoreboard needs besides the players. */
data class ScoreboardContext(
    val layout: TeamLayout,
    val gameLimit: Int,
    val livesLimit: Int,
    /** The play screen's scoreboard (a client), as opposed to the dedicated server's. */
    val isClient: Boolean,
    /** This phone's player id; 0 on the server. */
    val selfId: Byte,
    /** This phone's player is a server admin. */
    val isAdmin: Boolean,
)

/**
 * Groups and sorts the scoreboard: teams by total score, then lives remaining (with a lives
 * limit) or fewest eliminations, then team number; players within a team by score, then lives
 * remaining or fewest eliminations, then name, then id. Pure; moved from
 * PlayerDisplayDataListAdapter.
 */
object ScoreboardRows {

    fun build(players: Array<PlayerDisplayData?>?, context: ScoreboardContext): List<ScoreboardRow> {
        val rows = mutableListOf<ScoreboardRow>()
        val livesFlag = context.gameLimit and GameSettingsRepository.GAME_LIMIT_LIVES != 0
        rows += ScoreboardRow.ColumnHeader(livesFlag)
        val canEdit = !context.isClient || context.isAdmin
        val layout = context.layout
        fun at(id: Int): PlayerDisplayData? = if (players != null && id < players.size) players[id] else null

        if (layout.mode.isFreeForAll) {
            rows += ScoreboardRow.TeamHeader(0, 0, 0, 0, canEdit)
            val list = (1..GameSettingsRepository.MAX_PLAYER_ID).mapNotNull { at(it) }.sortedWith(playerOrder(context))
            list.forEach { rows += playerRow(it, context) }
            return rows
        }

        class Team(val number: Int) {
            val members = mutableListOf<PlayerDisplayData>()
            var points = 0
            var livesRemaining = 0
            var eliminations = 0
            var livesLimitSet = false
        }
        val teams = (1..layout.mode.teamCount).map { t ->
            Team(t).apply {
                for (id in layout.teamStartId(t)..layout.teamEndId(t)) {
                    val p = at(id) ?: continue
                    members += p
                    points += p.points
                    eliminations += p.eliminated
                    if (isLivesLimitSet(p, context)) {
                        livesLimitSet = true
                        livesRemaining += livesRemaining(p, context)
                    }
                }
            }
        }
        val sizes = layout.teamSizes()
        val teamOrder = Comparator<Team> { t1, t2 ->
            if (t1.points != t2.points) return@Comparator t2.points.compareTo(t1.points)
            if (t1.livesLimitSet || t2.livesLimitSet || livesFlag) {
                if (t1.livesRemaining != t2.livesRemaining) return@Comparator t2.livesRemaining.compareTo(t1.livesRemaining)
            } else if (t1.eliminations != t2.eliminations) {
                return@Comparator t1.eliminations.compareTo(t2.eliminations)
            }
            t1.number.compareTo(t2.number)
        }
        for (team in teams.sortedWith(teamOrder)) {
            val capacity = sizes.getOrElse(team.number - 1) { 16 }
            rows += ScoreboardRow.TeamHeader(team.number, team.members.size, capacity, team.points, canEdit)
            for (p in team.members.sortedWith(playerOrder(context))) rows += playerRow(p, context)
        }
        return rows
    }

    private fun playerRow(p: PlayerDisplayData, context: ScoreboardContext): ScoreboardRow.Player {
        val id = p.playerID
        val stat = when {
            p.overrideLives -> if (p.lives != 0) p.lives - p.eliminated else p.eliminated
            context.gameLimit and GameSettingsRepository.GAME_LIMIT_LIVES != 0 -> context.livesLimit - p.eliminated
            else -> p.eliminated
        }
        val canKick = !context.isClient || context.isAdmin
        val idInt = id.toInt()
        return ScoreboardRow.Player(
            playerId = id,
            name = p.playerName,
            points = p.points,
            livesOrEliminations = stat,
            connected = p.isConnected,
            isSelf = id == context.selfId,
            kickable = canKick && idInt in 1..GameSettingsRepository.MAX_PLAYER_ID && id != context.selfId,
            blasterType = p.blasterType,
            hasGrenade = p.hasGrenade,
        )
    }

    private fun isLivesLimitSet(p: PlayerDisplayData, context: ScoreboardContext): Boolean =
        if (p.overrideLives) p.lives != 0 else context.gameLimit and GameSettingsRepository.GAME_LIMIT_LIVES != 0

    private fun livesRemaining(p: PlayerDisplayData, context: ScoreboardContext): Int = when {
        p.overrideLives -> if (p.lives != 0) p.lives - p.eliminated else 0
        context.gameLimit and GameSettingsRepository.GAME_LIMIT_LIVES != 0 -> context.livesLimit - p.eliminated
        else -> 0
    }

    private fun playerOrder(context: ScoreboardContext) = Comparator<PlayerDisplayData> { p1, p2 ->
        if (p1.points != p2.points) return@Comparator p2.points.compareTo(p1.points)
        if (isLivesLimitSet(p1, context) || isLivesLimitSet(p2, context)) {
            val l1 = livesRemaining(p1, context)
            val l2 = livesRemaining(p2, context)
            if (l1 != l2) return@Comparator l2.compareTo(l1)
        } else if (p1.eliminated != p2.eliminated) {
            return@Comparator p1.eliminated.compareTo(p2.eliminated)
        }
        val byName = (p1.playerName ?: "").compareTo(p2.playerName ?: "", ignoreCase = true)
        if (byName != 0) return@Comparator byName
        (p1.playerID.toInt() and 0xFF).compareTo(p2.playerID.toInt() and 0xFF)
    }
}
