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

import com.simplecoil.simplecoil.domain.team.TeamLayout
import com.simplecoil.simplecoil.domain.team.TeamMode

/**
 * Everything the server screen shows, as of the last change on the server's dispatcher
 * (dedicated server design §5.2). Replaces the screen's own copies of the state and its
 * per-player pulls, so a screen that comes back shows the current session (R4).
 */
data class ServerSnapshot(
    val phase: SessionPhase,
    /** The address the server listens on; null until it is known. */
    val serverIp: String?,
    /** Every player the server knows about, connected or not, by id. */
    val players: List<ServerPlayerRow>,
    /** The players on the network (PlayerRepository's count, as the screen always showed it). */
    val playerCount: Int,
    val clock: ClockState,
    val settings: ServerSettings,
)

/** One player on the server's scoreboard. */
data class ServerPlayerRow(
    val playerId: Byte,
    val name: String?,
    val points: Int,
    val eliminated: Int,
    val connected: Boolean,
    /** The player has its own lives limit, [lives] (0: none). */
    val overrideLives: Boolean,
    val lives: Int,
)

/** The game settings the operator controls (or an admin, for the team layout). */
data class ServerSettings(
    val teamLayout: TeamLayout,
    /** GameSettingsRepository.GAME_LIMIT_* flags. */
    val gameLimit: Int,
    val timeLimit: Int,
    val livesLimit: Int,
    val scoreLimit: Int,
    val startTimeSeconds: Long,
    /** GameSettingsRepository.GPS_*. */
    val gpsMode: Int,
    /** GPS is relayed: the mode is on, and teammate-only makes sense in this team mode. */
    val useGps: Boolean,
    val allowJoinInProgress: Boolean,
    val onlyServerSettings: Boolean,
    val allowChooseTeams: Boolean,
)

/** The operator's saved settings, loaded into the server when it starts (§5.5). */
data class SavedServerSettings(
    val teamMode: TeamMode,
    val timeLimit: Int,
    val livesLimit: Int,
    val scoreLimit: Int,
    val startTimeSeconds: Long,
    val gpsMode: Int,
    val allowChooseTeams: Boolean,
)
