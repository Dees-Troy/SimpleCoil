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

package com.simplecoil.simplecoil.hardware.blaster

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository

/**
 * Telemetry byte offset parsing and command/config packet construction for Bluetooth LE
 * blasters. The wire format is frozen (REFACTOR_DESIGN.md §0): every packet is 20 bytes.
 */
object GattProtocolHandler {

    // Telemetry packet byte offsets (20-byte payload)
    const val RECOIL_OFFSET_TEAM = 1
    const val RECOIL_OFFSET_BUTTONS = 2
    const val RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER = 3
    const val RECOIL_OFFSET_THUMB_COUNTER = 4
    const val RECOIL_OFFSET_POWER_COUNTER = 5
    const val RECOIL_OFFSET_BATTERY_LEVEL = 7
    const val RECOIL_OFFSET_HIT_BY1_SHOTID = 8
    const val RECOIL_OFFSET_HIT_BY1 = 9
    const val RECOIL_OFFSET_HIT_BY2_SHOTID = 11
    const val RECOIL_OFFSET_HIT_BY2 = 12
    const val RECOIL_OFFSET_SHOTS_REMAINING = 14
    const val RECOIL_OFFSET_STATUS = 15

    // Bits of the buttons-held byte (RECOIL_OFFSET_BUTTONS)
    const val RECOIL_BUTTON_TRIGGER = 0x01
    const val RECOIL_BUTTON_RELOAD = 0x02
    const val RECOIL_BUTTON_THUMB = 0x04
    const val RECOIL_BUTTON_POWER = 0x10

    // Blaster types
    const val BLASTER_TYPE_RIFLE: Byte = 0x01
    const val BLASTER_TYPE_PISTOL: Byte = 0x02

    // Command IDs
    const val COMMAND_ID_INCREMENT: Byte = 0x10

    private const val PACKET_LENGTH = 20
    private const val MIN_TELEMETRY_LENGTH = 16

    /**
     * A parsed telemetry packet. [shotId1]/[shotId2] are the shot ids (low three bits);
     * [rawShotIdByte1]/[rawShotIdByte2] are the unmasked bytes, which grenades use to carry their
     * id (high nibble) and event (low nibble). [buttonsHeld] is the buttons-held bitmask
     * (RECOIL_BUTTON_*).
     */
    data class TelemetryData(
        @JvmField val playerId: Byte,
        @JvmField val shotsRemaining: Byte,
        @JvmField val triggerCounter: Byte,
        @JvmField val reloadCounter: Byte,
        @JvmField val thumbCounter: Byte,
        @JvmField val powerCounter: Byte,
        @JvmField val batteryLevel: Byte,
        @JvmField val hitByPlayer1: Int,
        @JvmField val shotId1: Byte,
        @JvmField val hitByPlayer2: Int,
        @JvmField val shotId2: Byte,
        @JvmField val status: Byte,
        @JvmField val rawShotIdByte1: Byte,
        @JvmField val rawShotIdByte2: Byte,
        @JvmField val buttonsHeld: Int = 0,
    )

    /**
     * Parses a raw telemetry notification.
     *
     * @return the parsed packet, or null when [data] is null or shorter than 16 bytes
     */
    @JvmStatic
    fun parseTelemetry(data: ByteArray?): TelemetryData? {
        if (data == null || data.size < MIN_TELEMETRY_LENGTH) {
            return null
        }
        val reloadTrigger = data[RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER].toInt()
        val rawShotIdByte1 = data[RECOIL_OFFSET_HIT_BY1_SHOTID]
        val rawShotIdByte2 = data[RECOIL_OFFSET_HIT_BY2_SHOTID]
        return TelemetryData(
            playerId = data[RECOIL_OFFSET_TEAM],
            shotsRemaining = data[RECOIL_OFFSET_SHOTS_REMAINING],
            triggerCounter = (reloadTrigger and 0x0F).toByte(),
            reloadCounter = (reloadTrigger and 0xF0).toByte(),
            thumbCounter = data[RECOIL_OFFSET_THUMB_COUNTER],
            powerCounter = data[RECOIL_OFFSET_POWER_COUNTER],
            batteryLevel = data[RECOIL_OFFSET_BATTERY_LEVEL],
            // Unsigned: IR ids above 127 (e.g. grenades, 167) are negative bytes.
            hitByPlayer1 = data[RECOIL_OFFSET_HIT_BY1].toInt() and 0xFF,
            shotId1 = (rawShotIdByte1.toInt() and 0x07).toByte(),
            hitByPlayer2 = data[RECOIL_OFFSET_HIT_BY2].toInt() and 0xFF,
            shotId2 = (rawShotIdByte2.toInt() and 0x07).toByte(),
            status = data[RECOIL_OFFSET_STATUS],
            rawShotIdByte1 = rawShotIdByte1,
            rawShotIdByte2 = rawShotIdByte2,
            buttonsHeld = data[RECOIL_OFFSET_BUTTONS].toInt() and 0xFF,
        )
    }

    /** Set player id: `CID 00 80 00 PID`, zero padded. */
    @JvmStatic
    fun buildSetTeamCommand(commandId: Byte, playerId: Byte): ByteArray {
        val command = ByteArray(PACKET_LENGTH)
        command[0] = commandId
        command[2] = 0x80.toByte()
        command[4] = playerId
        return command
    }

    /** Reload stage 1: `CID 00 02 00 PID`, zero padded. */
    @JvmStatic
    fun buildStartReloadCommand(commandId: Byte, playerId: Byte): ByteArray {
        val command = ByteArray(PACKET_LENGTH)
        command[0] = commandId
        command[2] = 0x02
        command[4] = playerId
        return command
    }

    /** Reload stage 2: `CID 00 04 00 PID PROFILE SHOTS`, zero padded. */
    @JvmStatic
    fun buildFinishReloadCommand(commandId: Byte, playerId: Byte, weaponProfile: Byte, shotCount: Byte): ByteArray {
        val command = ByteArray(PACKET_LENGTH)
        command[0] = commandId
        command[2] = 0x04
        command[4] = playerId
        command[5] = weaponProfile
        command[6] = shotCount
        return command
    }

    /** Shot mode and firing cone: `PROFILE 00 09 SHOTS ROF CONE_A CONE_B FF FF RECOIL 02 34`, zero padded. */
    @JvmStatic
    fun buildShotModeConfig(weaponProfile: Byte, shotMode: Int, blasterType: Byte, firingMode: Int): ByteArray {
        val config = ByteArray(PACKET_LENGTH)
        config[0] = weaponProfile
        config[2] = 0x09
        config[7] = 0xFF.toByte()
        config[8] = 0xFF.toByte()
        config[9] = 0x80.toByte() // Recoil strength default
        config[10] = 0x02
        config[11] = 0x34

        if (shotMode == GameSettingsRepository.SHOT_MODE_SINGLE) {
            config[3] = 0xFE.toByte()
            config[4] = 0x00
        } else if (shotMode == GameSettingsRepository.SHOT_MODE_BURST) {
            config[3] = 0x03
            config[4] = 0x03
            if (blasterType == BLASTER_TYPE_RIFLE) {
                config[9] = 0x78 // Reduced recoil for rifle burst
            }
        } else if (shotMode == GameSettingsRepository.SHOT_MODE_FULL_AUTO) {
            config[3] = 0xFE.toByte()
            config[4] = 0x01
        }

        when (firingMode) {
            GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE -> {
                config[5] = 0xFF.toByte()
                config[6] = 0x00
            }
            GameSettingsRepository.FIRING_MODE_OUTDOOR_WITH_CONE -> {
                config[5] = 0xFF.toByte()
                config[6] = 0xC8.toByte()
            }
            GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE -> {
                config[5] = 0x19
                config[6] = 0x00
            }
        }
        return config
    }

    /** Recoil on/off: `10 00 02 {03 on | 02 off} FF`, zero padded. */
    @JvmStatic
    fun buildRecoilConfig(enabled: Boolean): ByteArray {
        val config = ByteArray(PACKET_LENGTH)
        config[0] = 0x10
        config[2] = 0x02
        config[4] = 0xFF.toByte()
        config[3] = if (enabled) 0x03 else 0x02
        return config
    }
}
