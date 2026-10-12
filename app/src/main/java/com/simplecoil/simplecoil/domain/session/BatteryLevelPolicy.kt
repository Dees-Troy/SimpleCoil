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

import java.util.LinkedList

/**
 * Battery indicator: a rolling average of the last 100 battery reports, seeded with one `16`
 * sample, mapped to four levels with separate pistol and rifle thresholds. Extracted verbatim
 * from FullscreenActivity (initBatteryQueue() and processTelemetryData()).
 */
object BatteryLevelPolicy {
    const val BLASTER_TYPE_RIFLE: Byte = 1

    // Brand new alkalines report 16, recently charged rechargables report 13ish
    const val PISTOL_GREEN = 14L
    const val PISTOL_BLUE = 12L
    const val PISTOL_YELLOW = 10L
    const val RIFLE_GREEN = 21L
    const val RIFLE_BLUE = 18L
    const val RIFLE_YELLOW = 15L

    enum class Level { FULL, HIGH, MEDIUM, LOW }

    /** Any type other than [BLASTER_TYPE_RIFLE] is treated as a pistol. */
    @JvmStatic
    fun level(average: Long, blasterType: Byte): Level = if (blasterType == BLASTER_TYPE_RIFLE) {
        when {
            average >= RIFLE_GREEN -> Level.FULL
            average >= RIFLE_BLUE -> Level.HIGH
            average >= RIFLE_YELLOW -> Level.MEDIUM
            else -> Level.LOW
        }
    } else {
        when {
            average >= PISTOL_GREEN -> Level.FULL
            average >= PISTOL_BLUE -> Level.HIGH
            average >= PISTOL_YELLOW -> Level.MEDIUM
            else -> Level.LOW
        }
    }

    /**
     * Rolling integer average. Starting with a single `16` sample simplifies the per-packet code;
     * the seed rolls out like any other sample after 100 reports.
     */
    class Averager {
        private val queue = LinkedList<Byte>()
        private var count = 1L
        private var total = 16L

        init {
            reset()
        }

        fun reset() {
            queue.clear()
            queue.add(16.toByte())
            count = 1
            total = 16
        }

        /** Adds one raw (signed) battery byte and returns the new average. */
        fun add(level: Byte): Long {
            queue.add(level)
            total += level
            if (count >= 100) {
                val peek = queue.peek()
                if (peek != null) {
                    total -= peek.toLong()
                }
                queue.remove()
            } else {
                count++
            }
            return total / count
        }
    }
}
