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
import com.simplecoil.simplecoil.ui.playersettings.PlayerSettingsForm.parseInRange
import org.junit.Assert.assertEquals
import org.junit.Test

/** The player settings dialog's fields (DEDICATED_SERVER_DESIGN.md §7.2; S8). */
class PlayerSettingsFormTest {

    private val defaults = PlayerSettingsInput(
        health = "20", reloadShots = "30", reloadTime = "1500", reloadOnEmpty = false, manualRespawn = false,
        spawnTime = "10", respawnShieldTime = "3", damage = "1", overrideLives = false, lives = "0",
        allowSingle = true, allowBurst3 = true, allowAuto = true,
        firingMode = GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE,
        recoilSetting = GameSettingsRepository.RECOIL_SETTING_DEFAULT, isAdmin = false,
    )

    private fun parse(input: PlayerSettingsInput) = (PlayerSettingsForm.parse(input) as PlayerSettingsForm.Result.Valid).values

    @Test
    fun theDefaultFields_giveTheDefaultSettings() {
        assertEquals(PlayerSettingsValues(), parse(defaults))
    }

    /** Each field: its in-range bounds are kept, and anything else takes the field's default. */
    @Test
    fun everyField_keepsItsBounds_andFallsBackOutsideThem() {
        data class Field(val set: (PlayerSettingsInput, String) -> PlayerSettingsInput, val get: (PlayerSettingsValues) -> Long,
                         val min: Long, val max: Long, val fallback: Long)
        val fields = mapOf(
            "health" to Field({ i, t -> i.copy(health = t) }, { it.health.toLong() }, 1, 1000, 20),
            "shots" to Field({ i, t -> i.copy(reloadShots = t) }, { it.shots.toLong() }, 1, 255, 30),
            "reload time" to Field({ i, t -> i.copy(reloadTime = t) }, { it.reloadTimeMillis }, 0, 10000, 1500),
            "spawn time" to Field({ i, t -> i.copy(spawnTime = t) }, { it.spawnTimeSeconds }, 1, 1000, 10),
            "shield time" to Field({ i, t -> i.copy(respawnShieldTime = t) }, { it.respawnShieldTimeSeconds }, 0, 1000, 3),
            "damage" to Field({ i, t -> i.copy(damage = t) }, { -it.damage.toLong() }, 1, 1000, 1),
            "lives" to Field({ i, t -> i.copy(lives = t) }, { it.lives.toLong() }, 1, 1000, 0),
        )
        for ((name, f) in fields) {
            assertEquals(name, f.min, f.get(parse(f.set(defaults, f.min.toString()))))
            assertEquals(name, f.max, f.get(parse(f.set(defaults, f.max.toString()))))
            for (bad in listOf((f.min - 1).toString(), (f.max + 1).toString(), "", " ", "abc", "-", "99999999999")) {
                assertEquals("$name '$bad'", f.fallback, f.get(parse(f.set(defaults, bad))))
            }
        }
    }

    @Test
    fun noShotMode_isAnError() {
        assertEquals(PlayerSettingsForm.Result.NoShotMode,
            PlayerSettingsForm.parse(defaults.copy(allowSingle = false, allowBurst3 = false, allowAuto = false)))
        assertEquals(true, parse(defaults.copy(allowSingle = false, allowBurst3 = false)).allowAuto)
    }

    @Test
    fun theFiringModeIsHeldAsAValue() {
        // S12: it used to be read back from the button's text
        assertEquals(GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE,
            parse(defaults.copy(firingMode = GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE)).firingMode)
    }

    @Test
    fun parseInRange_trimsAndFallsBack() {
        assertEquals(42, parseInRange(" 42 ", 1, 1000, 20))
        assertEquals(20, parseInRange(null, 1, 1000, 20))
    }

    @Test
    fun valuesRoundTripThroughPlayerSettings() {
        val values = PlayerSettingsValues(health = 50, shots = 12, damage = -3, overrideLives = true, lives = 4,
            allowBurst3 = false, firingMode = 2, recoilSetting = 1, isAdmin = true)
        val settings = PlayerSettings()
        values.applyTo(settings)
        assertEquals(values, PlayerSettingsValues.of(settings))
    }
}
