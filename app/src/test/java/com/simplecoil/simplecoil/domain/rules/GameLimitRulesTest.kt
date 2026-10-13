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

package com.simplecoil.simplecoil.domain.rules

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository.GAME_LIMIT_LIVES
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository.GAME_LIMIT_NONE
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository.GAME_LIMIT_SCORE
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository.GAME_LIMIT_TIME
import org.junit.Assert.assertEquals
import org.junit.Test

class GameLimitRulesTest {

    private val none = Limits(GAME_LIMIT_NONE, 10, 5, 20)

    private fun applied(current: Limits, kind: LimitKind, text: String) =
        (GameLimitRules.apply(current, kind, text) as GameLimitRules.Result.Applied).limits

    @Test
    fun aValue_turnsItsLimitOn_andSetsIt() {
        assertEquals(Limits(GAME_LIMIT_TIME, 15, 5, 20), applied(none, LimitKind.TIME, "15"))
        assertEquals(Limits(GAME_LIMIT_LIVES, 10, 3, 20), applied(none, LimitKind.LIVES, "3"))
        assertEquals(Limits(GAME_LIMIT_SCORE, 10, 5, 50), applied(none, LimitKind.SCORE, "50"))
    }

    @Test
    fun aValue_keepsTheOtherLimits() {
        val both = applied(applied(none, LimitKind.TIME, "15"), LimitKind.SCORE, "30")
        assertEquals(GAME_LIMIT_TIME or GAME_LIMIT_SCORE, both.gameLimit)
        assertEquals(Limits(GAME_LIMIT_TIME or GAME_LIMIT_SCORE, 15, 5, 40), applied(both, LimitKind.SCORE, "40"))
    }

    @Test
    fun zero_turnsAnOnLimitOff() {
        val on = Limits(GAME_LIMIT_TIME or GAME_LIMIT_LIVES, 10, 5, 20)
        assertEquals(Limits(GAME_LIMIT_LIVES, 0, 5, 20), applied(on, LimitKind.TIME, "0"))
        assertEquals(Limits(GAME_LIMIT_TIME, 10, -1, 20), applied(on, LimitKind.LIVES, "-1"))
    }

    @Test
    fun zero_onALimitThatIsOff_keepsItOff() {
        // S9: it used to turn the limit on, so a 0-minute game ended right after the starting timer
        assertEquals(Limits(GAME_LIMIT_NONE, 0, 5, 20), applied(none, LimitKind.TIME, "0"))
        assertEquals(Limits(GAME_LIMIT_NONE, 10, 0, 20), applied(none, LimitKind.LIVES, "0"))
    }

    @Test
    fun emptyOrNotANumber_isIgnored_andAboveTheMaximum_isTooHigh() {
        assertEquals(GameLimitRules.Result.Ignored, GameLimitRules.apply(none, LimitKind.TIME, ""))
        assertEquals(GameLimitRules.Result.Ignored, GameLimitRules.apply(none, LimitKind.TIME, "abc"))
        assertEquals(GameLimitRules.Result.TooHigh, GameLimitRules.apply(none, LimitKind.LIVES, "101"))
        assertEquals(Limits(GAME_LIMIT_LIVES, 10, 100, 20), applied(none, LimitKind.LIVES, "100"))
    }
}
