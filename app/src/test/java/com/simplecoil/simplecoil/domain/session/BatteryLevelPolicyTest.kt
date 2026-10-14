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

import com.simplecoil.simplecoil.domain.session.BatteryLevelPolicy.Level
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryLevelPolicyTest {

    private val pistol: Byte = 2
    private val rifle: Byte = 1

    @Test
    fun pistolThresholds() {
        assertEquals(Level.FULL, BatteryLevelPolicy.level(14, pistol))
        assertEquals(Level.HIGH, BatteryLevelPolicy.level(13, pistol))
        assertEquals(Level.HIGH, BatteryLevelPolicy.level(12, pistol))
        assertEquals(Level.MEDIUM, BatteryLevelPolicy.level(11, pistol))
        assertEquals(Level.MEDIUM, BatteryLevelPolicy.level(10, pistol))
        assertEquals(Level.LOW, BatteryLevelPolicy.level(9, pistol))
        assertEquals(Level.LOW, BatteryLevelPolicy.level(-5, pistol))
    }

    @Test
    fun rifleThresholds() {
        assertEquals(Level.FULL, BatteryLevelPolicy.level(21, rifle))
        assertEquals(Level.HIGH, BatteryLevelPolicy.level(20, rifle))
        assertEquals(Level.HIGH, BatteryLevelPolicy.level(18, rifle))
        assertEquals(Level.MEDIUM, BatteryLevelPolicy.level(17, rifle))
        assertEquals(Level.MEDIUM, BatteryLevelPolicy.level(15, rifle))
        assertEquals(Level.LOW, BatteryLevelPolicy.level(14, rifle))
    }

    @Test
    fun unknownBlasterType_usesPistolThresholds() {
        for (type in listOf<Byte>(0, 3, -1)) {
            assertEquals(Level.FULL, BatteryLevelPolicy.level(14, type))
            assertEquals(Level.LOW, BatteryLevelPolicy.level(9, type))
        }
    }

    @Test
    fun average_isSeededWithOneSixteen() {
        val averager = BatteryLevelPolicy.Averager()
        // (16 + 12) / 2
        assertEquals(14, averager.add(12))
        // (16 + 12 + 5) / 3, integer division
        assertEquals(11, averager.add(5))
    }

    @Test
    fun goldenIdlePacket_reportsFreshAlkalines() {
        val level = RecoilProtocolFixtures.GOLDEN_IDLE[GattProtocolHandler.RECOIL_OFFSET_BATTERY_LEVEL]
        assertEquals(16, BatteryLevelPolicy.Averager().add(level))
    }

    @Test
    fun seed_rollsOutAfterOneHundredSamples() {
        val averager = BatteryLevelPolicy.Averager()
        var average = 0L
        repeat(99) { average = averager.add(20) }
        // 16 + 99 * 20 over 100 samples
        assertEquals(19, average)
        // The 100th report evicts the seed: 100 * 20 over 100.
        assertEquals(20, averager.add(20))
        // From here on it is a plain rolling window of 100: one 20 left plus 99 * 50, then all 50s.
        repeat(98) { averager.add(50) }
        assertEquals(49, averager.add(50))
        assertEquals(50, averager.add(50))
    }

    @Test
    fun rawByteIsSigned() {
        // 0xFF is -1, not 255: (16 - 1) / 2
        assertEquals(7, BatteryLevelPolicy.Averager().add(0xFF.toByte()))
    }

    @Test
    fun reset_restoresTheSeed() {
        val averager = BatteryLevelPolicy.Averager()
        repeat(150) { averager.add(5) }
        averager.reset()
        assertEquals(14, averager.add(12))
    }
}
