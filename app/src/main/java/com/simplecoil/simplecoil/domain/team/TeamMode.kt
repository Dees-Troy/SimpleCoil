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

package com.simplecoil.simplecoil.domain.team

/**
 * How players are split into teams. [wireValue] is what the network protocol ("gamemode",
 * ADMIN_GAMEMODE) and the saved preference use, so it must never change.
 */
enum class TeamMode(@JvmField val wireValue: Int, @JvmField val teamCount: Int) {
    FREE_FOR_ALL(1, 0),
    TWO_TEAMS(2, 2),
    FOUR_TEAMS(4, 4);

    val isFreeForAll: Boolean get() = this == FREE_FOR_ALL

    companion object {
        @JvmField val DEFAULT = TWO_TEAMS

        /** Null for a value the protocol doesn't define; callers ignore the message. */
        @JvmStatic
        fun fromWire(value: Int): TeamMode? = entries.firstOrNull { it.wireValue == value }

        /** For a stored value: anything unknown becomes [DEFAULT]. */
        @JvmStatic
        fun fromWireOrDefault(value: Int): TeamMode = fromWire(value) ?: DEFAULT
    }
}
