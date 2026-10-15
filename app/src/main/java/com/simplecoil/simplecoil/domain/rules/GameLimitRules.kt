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

/** One of the game limits. */
enum class LimitKind { TIME, LIVES, SCORE }

/** The game limits: which ones are on ([gameLimit] flags) and their values. */
data class Limits(val gameLimit: Int, val timeLimit: Int, val livesLimit: Int, val scoreLimit: Int) {
    /** [kind]'s value as the dialog shows it: 0 when that limit is off. */
    fun shown(kind: LimitKind): Int = when (kind) {
        LimitKind.TIME -> if (gameLimit and GameSettingsRepository.GAME_LIMIT_TIME != 0) timeLimit else 0
        LimitKind.LIVES -> if (gameLimit and GameSettingsRepository.GAME_LIMIT_LIVES != 0) livesLimit else 0
        LimitKind.SCORE -> if (gameLimit and GameSettingsRepository.GAME_LIMIT_SCORE != 0) scoreLimit else 0
    }
}

/** The game limit dialog's rules, shared by the play screen and the dedicated server. Pure. */
object GameLimitRules {
    const val MAX_LIMIT = 1000

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

    /**
     * Every limit the dialog entered, at once. Any one above [MAX_LIMIT] and nothing changes; an
     * empty or non-numeric field keeps its limit as it was.
     */
    fun apply(current: Limits, texts: Map<LimitKind, String>): Result {
        var limits = current
        var changed = false
        for ((kind, text) in texts) {
            when (val result = apply(limits, kind, text)) {
                Result.TooHigh -> return Result.TooHigh
                Result.Ignored -> {}
                is Result.Applied -> {
                    limits = result.limits
                    changed = true
                }
            }
        }
        return if (changed) Result.Applied(limits) else Result.Ignored
    }
}
