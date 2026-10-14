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

package com.simplecoil.simplecoil.domain.session

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.team.TeamLayout

/**
 * Game configuration and shared game state as the session engine sees it. The app reads and
 * writes the repositories, the single source of truth, through [RepositoryGameConfig]; tests use
 * a plain implementation.
 */
interface GameConfig {
    var gameState: Int
    var playerId: Byte
    var pairedGrenadeId: Byte
    val fullHealth: Int
    val fullReload: Byte
    val reloadTimeMillis: Long
    /** Eliminated players wait for the player to start the respawn countdown. */
    val manualRespawn: Boolean
    val respawnTimeSeconds: Long
    val respawnShieldTimeSeconds: Long
    val startCountdownSeconds: Long
    val gameLimit: Int
    val timeLimitMinutes: Int
    val livesLimit: Int
    val overrideLives: Boolean
    val overrideLivesValue: Int
    val reloadOnEmpty: Boolean
    val allowSingleShotMode: Boolean
    val allowBurstShotMode: Boolean
    val allowAutoShotMode: Boolean
    val serverRecoilSetting: Int
    val firingMode: Int

    /** The team mode and sizes; read once per decision so both come from the same snapshot. */
    val teamLayout: TeamLayout

    fun playerName(playerId: Byte): String?

    /** Configured damage of a network player, or null when that player has no settings. */
    fun playerDamage(playerId: Byte): Int?
    fun grenadePairing(grenadeId: Int): Int
}

class RepositoryGameConfig(
    private val settings: GameSettingsRepository,
    private val players: PlayerRepository,
    private val state: GameStateRepository,
) : GameConfig {
    override var gameState: Int
        get() = state.gameState
        set(value) { state.gameState = value }
    override var playerId: Byte
        get() = players.playerID
        set(value) { players.playerID = value }
    override var pairedGrenadeId: Byte
        get() = players.pairedGrenadeID
        set(value) { players.pairedGrenadeID = value }
    override val fullHealth get() = settings.fullHealth
    override val fullReload get() = settings.fullReload
    override val reloadTimeMillis get() = settings.reloadTime
    override val manualRespawn get() = settings.isManualRespawn
    override val respawnTimeSeconds get() = settings.respawnTime
    override val respawnShieldTimeSeconds get() = settings.respawnShieldTime
    override val startCountdownSeconds get() = settings.startTime
    override val gameLimit get() = settings.gameLimit
    override val timeLimitMinutes get() = settings.timeLimit
    override val livesLimit get() = settings.livesLimit
    override val overrideLives get() = settings.isOverrideLives
    override val overrideLivesValue get() = settings.overrideLivesVal
    override val reloadOnEmpty get() = settings.isReloadOnEmpty
    override val allowSingleShotMode get() = settings.isAllowSingleShotMode
    override val allowBurstShotMode get() = settings.isAllowBurst3ShotMode
    override val allowAutoShotMode get() = settings.isAllowAutoShotMode
    override val serverRecoilSetting get() = settings.serverRecoilSetting
    override val firingMode get() = settings.currentFiringMode
    override val teamLayout: TeamLayout get() = settings.teamLayout

    override fun playerName(playerId: Byte): String? = players.getPlayerName(playerId)
    override fun playerDamage(playerId: Byte): Int? = players.playerSettings[playerId]?.damage
    override fun grenadePairing(grenadeId: Int) = players.getGrenadePairing(grenadeId)
}
