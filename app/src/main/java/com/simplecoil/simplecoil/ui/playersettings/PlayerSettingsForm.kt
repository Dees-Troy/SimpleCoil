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

package com.simplecoil.simplecoil.ui.playersettings

import com.simplecoil.simplecoil.data.model.PlayerSettings
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository

/** One player's settings as the player settings dialog edits them. [damage] is negative, as stored. */
data class PlayerSettingsValues(
    val health: Int = GameSettingsRepository.MAX_HEALTH,
    val shots: Int = GameSettingsRepository.RELOAD_COUNT.toInt(),
    val reloadTimeMillis: Long = GameSettingsRepository.RELOAD_TIME_MILLISECONDS,
    val reloadOnEmpty: Boolean = false,
    val manualRespawn: Boolean = false,
    val spawnTimeSeconds: Long = GameSettingsRepository.RESPAWN_TIME_SECONDS,
    val respawnShieldTimeSeconds: Long = GameSettingsRepository.RESPAWN_SHIELD_TIME_SECONDS,
    val damage: Int = GameSettingsRepository.DAMAGE_PER_HIT,
    val overrideLives: Boolean = false,
    val lives: Int = 0,
    val allowSingle: Boolean = true,
    val allowBurst3: Boolean = true,
    val allowAuto: Boolean = true,
    val firingMode: Int = GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE,
    val recoilSetting: Int = GameSettingsRepository.RECOIL_SETTING_DEFAULT,
    val isAdmin: Boolean = false,
) {
    /** Writes these values into a network player's [settings]. */
    fun applyTo(settings: PlayerSettings) {
        settings.health = health
        settings.shots = shots.toByte()
        settings.reloadTime = reloadTimeMillis
        settings.reloadOnEmpty = reloadOnEmpty
        settings.manualRespawn = manualRespawn
        settings.spawnTime = spawnTimeSeconds
        settings.respawnShieldTime = respawnShieldTimeSeconds
        settings.damage = damage
        settings.overrideLives = overrideLives
        settings.lives = lives
        settings.allowShotModeSingle = allowSingle
        settings.allowShotModeBurst3 = allowBurst3
        settings.allowShotModeAuto = allowAuto
        settings.recoilSetting = recoilSetting
        settings.isAdmin = isAdmin
        settings.firingMode = firingMode
    }

    /** Writes the fields the local player can set into the game settings. */
    fun applyTo(settings: GameSettingsRepository) {
        settings.fullHealth = health
        settings.fullReload = shots.toByte()
        settings.reloadTime = reloadTimeMillis
        settings.isReloadOnEmpty = reloadOnEmpty
        settings.isManualRespawn = manualRespawn
        settings.respawnTime = spawnTimeSeconds
        settings.respawnShieldTime = respawnShieldTimeSeconds
        settings.damage = damage
        settings.isOverrideLives = overrideLives
        settings.overrideLivesVal = lives
        settings.isAllowSingleShotMode = allowSingle
        settings.isAllowBurst3ShotMode = allowBurst3
        settings.isAllowAutoShotMode = allowAuto
    }

    companion object {
        /** A network player's settings, as the server holds them. */
        @JvmStatic
        fun of(settings: PlayerSettings) = PlayerSettingsValues(
            health = settings.health,
            shots = settings.shots.toInt(),
            reloadTimeMillis = settings.reloadTime,
            reloadOnEmpty = settings.reloadOnEmpty,
            manualRespawn = settings.manualRespawn,
            spawnTimeSeconds = settings.spawnTime,
            respawnShieldTimeSeconds = settings.respawnShieldTime,
            damage = settings.damage,
            overrideLives = settings.overrideLives,
            lives = settings.lives,
            allowSingle = settings.allowShotModeSingle,
            allowBurst3 = settings.allowShotModeBurst3,
            allowAuto = settings.allowShotModeAuto,
            firingMode = settings.firingMode,
            recoilSetting = settings.recoilSetting,
            isAdmin = settings.isAdmin,
        )

        /** The local player's settings. */
        @JvmStatic
        fun of(settings: GameSettingsRepository) = PlayerSettingsValues(
            health = settings.fullHealth,
            shots = settings.fullReload.toInt(),
            reloadTimeMillis = settings.reloadTime,
            reloadOnEmpty = settings.isReloadOnEmpty,
            manualRespawn = settings.isManualRespawn,
            spawnTimeSeconds = settings.respawnTime,
            respawnShieldTimeSeconds = settings.respawnShieldTime,
            damage = settings.damage,
            overrideLives = settings.isOverrideLives,
            lives = settings.overrideLivesVal,
            allowSingle = settings.isAllowSingleShotMode,
            allowBurst3 = settings.isAllowBurst3ShotMode,
            allowAuto = settings.isAllowAutoShotMode,
            firingMode = settings.currentFiringMode,
        )
    }
}

/** The dialog's fields as entered: texts as typed, toggles as set. */
data class PlayerSettingsInput(
    val health: String,
    val reloadShots: String,
    val reloadTime: String,
    val reloadOnEmpty: Boolean,
    val manualRespawn: Boolean,
    val spawnTime: String,
    val respawnShieldTime: String,
    /** Positive, as shown. */
    val damage: String,
    val overrideLives: Boolean,
    val lives: String,
    val allowSingle: Boolean,
    val allowBurst3: Boolean,
    val allowAuto: Boolean,
    val firingMode: Int,
    val recoilSetting: Int,
    val isAdmin: Boolean,
)

/**
 * Turns the player settings dialog's fields into settings. A field that is empty, not a number or
 * out of range takes its default; the form never throws (S8). Pure.
 */
object PlayerSettingsForm {

    sealed interface Result {
        data class Valid(val values: PlayerSettingsValues) : Result
        /** No shot mode is allowed. */
        data object NoShotMode : Result
    }

    fun parse(input: PlayerSettingsInput): Result {
        if (!input.allowSingle && !input.allowBurst3 && !input.allowAuto) return Result.NoShotMode
        return Result.Valid(
            PlayerSettingsValues(
                health = parseInRange(input.health, 1, 1000, GameSettingsRepository.MAX_HEALTH),
                shots = parseInRange(input.reloadShots, 1, 255, GameSettingsRepository.RELOAD_COUNT.toInt()),
                reloadTimeMillis = parseInRange(input.reloadTime, 0, 10000, GameSettingsRepository.RELOAD_TIME_MILLISECONDS.toInt()).toLong(),
                reloadOnEmpty = input.reloadOnEmpty,
                manualRespawn = input.manualRespawn,
                spawnTimeSeconds = parseInRange(input.spawnTime, 1, 1000, GameSettingsRepository.RESPAWN_TIME_SECONDS.toInt()).toLong(),
                respawnShieldTimeSeconds = parseInRange(input.respawnShieldTime, 0, 1000, GameSettingsRepository.RESPAWN_SHIELD_TIME_SECONDS.toInt()).toLong(),
                damage = -parseInRange(input.damage, 1, 1000, -GameSettingsRepository.DAMAGE_PER_HIT),
                overrideLives = input.overrideLives,
                lives = parseInRange(input.lives, 1, 1000, 0),
                allowSingle = input.allowSingle,
                allowBurst3 = input.allowBurst3,
                allowAuto = input.allowAuto,
                firingMode = input.firingMode,
                recoilSetting = input.recoilSetting,
                isAdmin = input.isAdmin,
            )
        )
    }

    /** The field's number when it parses and lies in [min, max], otherwise [fallback]. */
    fun parseInRange(text: CharSequence?, min: Int, max: Int, fallback: Int): Int {
        val value = text?.toString()?.trim()?.toIntOrNull() ?: return fallback
        return if (value < min || value > max) fallback else value
    }
}
