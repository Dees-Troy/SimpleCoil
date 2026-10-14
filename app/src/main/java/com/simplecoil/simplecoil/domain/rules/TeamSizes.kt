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

/** The team size dialog's rules, shared by the play screen and the dedicated server. Pure. */
object TeamSizes {

    sealed interface Result {
        data class Valid(val sizes: IntArray) : Result {
            override fun equals(other: Any?) = other is Valid && sizes.contentEquals(other.sizes)
            override fun hashCode() = sizes.contentHashCode()
        }
        /** A team field is empty, not a number, or below 1. */
        data object BelowOne : Result
        /** The sizes don't add up to every player slot. */
        data class WrongTotal(val total: Int) : Result
    }

    /** Validates the first [teamCount] of [texts]: each at least 1, together every player slot. */
    fun validate(texts: List<String>, teamCount: Int = texts.size): Result {
        val sizes = IntArray(teamCount)
        var total = 0
        for (i in 0 until teamCount) {
            val text = texts.getOrElse(i) { "" }.trim()
            val value = text.toIntOrNull()
            if (value == null || value <= 0) return Result.BelowOne
            sizes[i] = value
            total += value
        }
        if (total != GameSettingsRepository.MAX_PLAYER_ID.toInt()) return Result.WrongTotal(total)
        return Result.Valid(sizes)
    }
}
