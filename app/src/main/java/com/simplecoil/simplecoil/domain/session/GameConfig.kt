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

import com.simplecoil.simplecoil.Globals
import com.simplecoil.simplecoil.data.repository.PlayerRepository

/**
 * Game configuration and shared game state as the session engine sees it. The app reads and
 * writes [Globals], the activity's single source of truth, through [GlobalsGameConfig]; tests use
 * a plain implementation.
 */
interface GameConfig {
    var gameState: Int
    var playerId: Byte
    var pairedGrenadeId: Byte
    val fullHealth: Int
    val fullReload: Byte
    val reloadTimeMillis: Long
    val respawnTimeSeconds: Long
    val respawnShieldTimeSeconds: Long
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
    val gameMode: Int

    fun networkTeam(playerId: Byte): Int
    fun playerName(playerId: Byte): String?

    /** Configured damage of a network player, or null when that player has no settings. */
    fun playerDamage(playerId: Byte): Int?
    fun grenadePairing(grenadeId: Int): Int
}

object GlobalsGameConfig : GameConfig {
    private val g get() = Globals.getInstance()

    override var gameState: Int
        get() = g.mGameState
        set(value) { g.mGameState = value }
    override var playerId: Byte
        get() = g.mPlayerID
        set(value) { g.mPlayerID = value }
    override var pairedGrenadeId: Byte
        get() = g.mPairedGrenadeID
        set(value) { g.mPairedGrenadeID = value }
    override val fullHealth get() = g.mFullHealth
    override val fullReload get() = g.mFullReload
    override val reloadTimeMillis get() = g.mReloadTime
    override val respawnTimeSeconds get() = g.mRespawnTime
    override val respawnShieldTimeSeconds get() = g.mRespawnShieldTime
    override val gameLimit get() = g.mGameLimit
    override val timeLimitMinutes get() = g.mTimeLimit
    override val livesLimit get() = g.mLivesLimit
    override val overrideLives get() = g.mOverrideLives
    override val overrideLivesValue get() = g.mOverrideLivesVal
    override val reloadOnEmpty get() = g.mReloadOnEmpty
    override val allowSingleShotMode get() = g.mAllowSingleShotMode
    override val allowBurstShotMode get() = g.mAllowBurst3ShotMode
    override val allowAutoShotMode get() = g.mAllowAutoShotMode
    override val serverRecoilSetting get() = g.mServerRecoilSetting
    override val firingMode get() = g.mCurrentFiringMode
    override val gameMode get() = g.mGameMode

    override fun networkTeam(playerId: Byte) = g.calcNetworkTeam(playerId)
    override fun playerName(playerId: Byte): String? = g.getPlayerName(playerId)
    override fun playerDamage(playerId: Byte): Int? = g.mPlayerSettings[playerId]?.damage
    override fun grenadePairing(grenadeId: Int) = PlayerRepository.getInstance().getGrenadePairing(grenadeId)
}
