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

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository

/** Which game limit the operator or player is setting. */
enum class LimitKind { TIME, LIVES, SCORE }

/** The game limits: which ones are on ([gameLimit] flags) and their values. */
data class Limits(val gameLimit: Int, val timeLimit: Int, val livesLimit: Int, val scoreLimit: Int)

/** The game limit dialog's rules, shared by the play screen and the dedicated server. Pure. */
object GameLimitRules {
    const val MAX_LIMIT = 100

    sealed interface Result {
        data class Applied(val limits: Limits) : Result
        /** Empty or not a number: nothing changes. */
        data object Ignored : Result
        /** Above [MAX_LIMIT]: nothing changes, and the player is told. */
        data object TooHigh : Result
    }

    fun apply(current: Limits, kind: LimitKind, text: String): Result {
        if (text.isEmpty()) return Result.Ignored
        val limit = text.toIntOrNull() ?: return Result.Ignored
        if (limit > MAX_LIMIT) return Result.TooHigh
        val flag = when (kind) {
            LimitKind.TIME -> GameSettingsRepository.GAME_LIMIT_TIME
            LimitKind.LIVES -> GameSettingsRepository.GAME_LIMIT_LIVES
            LimitKind.SCORE -> GameSettingsRepository.GAME_LIMIT_SCORE
        }
        // A value above 0 turns the limit on; 0 or less turns it off (S9: 0 used to turn on a limit that was off)
        val gameLimit = if (limit > 0) current.gameLimit or flag else current.gameLimit and flag.inv()
        return Result.Applied(
            when (kind) {
                LimitKind.TIME -> current.copy(gameLimit = gameLimit, timeLimit = limit)
                LimitKind.LIVES -> current.copy(gameLimit = gameLimit, livesLimit = limit)
                LimitKind.SCORE -> current.copy(gameLimit = gameLimit, scoreLimit = limit)
            }
        )
    }
}
