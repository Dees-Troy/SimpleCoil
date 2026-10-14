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
package com.simplecoil.simplecoil.hardware

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GattProtocolHandlerTest {

    private fun assertNum(expected: Number, actual: Number) = assertEquals(expected.toLong(), actual.toLong())

    @Test
    fun testParseTelemetryNullOrShort() {
        assertNull(GattProtocolHandler.parseTelemetry(null))
        assertNull(GattProtocolHandler.parseTelemetry(ByteArray(10)))
    }

    @Test
    fun testParseTelemetryValid() {
        val data = ByteArray(20)
        data[GattProtocolHandler.RECOIL_OFFSET_TEAM] = 3
        data[GattProtocolHandler.RECOIL_OFFSET_SHOTS_REMAINING] = 25
        data[GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] = 0x24 // 2 reload, 4 trigger
        data[GattProtocolHandler.RECOIL_OFFSET_BATTERY_LEVEL] = 12
        data[GattProtocolHandler.RECOIL_OFFSET_HIT_BY1] = 5
        data[GattProtocolHandler.RECOIL_OFFSET_HIT_BY1_SHOTID] = 3

        val telemetry = GattProtocolHandler.parseTelemetry(data)
        assertNotNull(telemetry)
        telemetry!!
        assertNum(3, telemetry.playerId)
        assertNum(25, telemetry.shotsRemaining)
        assertNum(4, telemetry.triggerCounter)
        assertNum(0x20.toByte(), telemetry.reloadCounter)
        assertNum(12, telemetry.batteryLevel)
        assertNum(5, telemetry.hitByPlayer1)
        assertNum(3, telemetry.shotId1)
    }

    @Test
    fun testBuildSetTeamCommand() {
        val cmd = GattProtocolHandler.buildSetTeamCommand(0x10, 2)
        assertNum(20, cmd.size)
        assertNum(0x10.toByte(), cmd[0])
        assertNum(0x80.toByte(), cmd[2])
        assertNum(2, cmd[4])
    }

    @Test
    fun testBuildStartReloadCommand() {
        val cmd = GattProtocolHandler.buildStartReloadCommand(0x20, 4)
        assertNum(20, cmd.size)
        assertNum(0x20.toByte(), cmd[0])
        assertNum(0x02.toByte(), cmd[2])
        assertNum(4, cmd[4])
    }

    @Test
    fun testBuildFinishReloadCommand() {
        val cmd = GattProtocolHandler.buildFinishReloadCommand(0x30, 4, 1, 30)
        assertNum(20, cmd.size)
        assertNum(0x30.toByte(), cmd[0])
        assertNum(0x04.toByte(), cmd[2])
        assertNum(4, cmd[4])
        assertNum(1, cmd[5])
        assertNum(30, cmd[6])
    }

    @Test
    fun testBuildShotModeConfig() {
        val configSingle = GattProtocolHandler.buildShotModeConfig(
            1,
            GameSettingsRepository.SHOT_MODE_SINGLE,
            GattProtocolHandler.BLASTER_TYPE_PISTOL,
            GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE,
        )
        assertNum(20, configSingle.size)
        assertNum(0xFE.toByte(), configSingle[3])
        assertNum(0x00.toByte(), configSingle[4])
        assertNum(0xFF.toByte(), configSingle[5])
        assertNum(0x00.toByte(), configSingle[6])

        val configAuto = GattProtocolHandler.buildShotModeConfig(
            1,
            GameSettingsRepository.SHOT_MODE_FULL_AUTO,
            GattProtocolHandler.BLASTER_TYPE_PISTOL,
            GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE,
        )
        assertNum(0xFE.toByte(), configAuto[3])
        assertNum(0x01.toByte(), configAuto[4])
        assertNum(0x19.toByte(), configAuto[5])
        assertNum(0x00.toByte(), configAuto[6])
    }

    @Test
    fun testBuildRecoilConfig() {
        val enabled = GattProtocolHandler.buildRecoilConfig(true)
        assertNum(0x03.toByte(), enabled[3])

        val disabled = GattProtocolHandler.buildRecoilConfig(false)
        assertNum(0x02.toByte(), disabled[3])
    }
}
