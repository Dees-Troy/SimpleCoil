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

import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_BATTERY_LEVEL
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_BUTTONS
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_HIT_BY1
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_HIT_BY1_SHOTID
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_HIT_BY2
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_HIT_BY2_SHOTID
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_POWER_COUNTER
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_SHOTS_REMAINING
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_STATUS
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_TEAM
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_THUMB_COUNTER
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.TelemetryData
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.parseTelemetry
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_DOUBLE_HIT
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_GRENADE_DAMAGE
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_GRENADE_PAIR
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_HIT_BY_PLAYER_2
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_IDLE
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_RELOAD_PRESSED
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_TRIGGER_PULLED
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GRENADE_DAMAGE
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GRENADE_DISARM
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GRENADE_NEW_PAIR
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GRENADE_PAIR_ID
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GRENADE_PLAYER_ID
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.grenadeEvent
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.grenadeId
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.hitBy
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.isGrenadeControlEvent
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.networkPlayerId
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.telemetry
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Characterization tests for decoding the 20-byte telemetry notification sent by the blaster on
 * the telemetry characteristic (e6f59d12).
 *
 * Layout (offsets into the notification):
 *   0  packet sequence counter (ignored)
 *   1  player id currently programmed into the blaster
 *   2  live button bitmask (ignored by the parser; 0x01 trigger, 0x02 reload, 0x04 thumb, 0x10 power)
 *   3  hi nibble: reload press counter, lo nibble: trigger pull counter
 *   4  thumb / back button press counter
 *   5  power button press counter
 *   6  ignored
 *   7  battery level (raw, ~0x10 fresh alkaline)
 *   8  hit slot 1 shot id byte (shot id = low 3 bits; grenades: hi nibble id, lo nibble event)
 *   9  hit slot 1 "hit by" IR id (unsigned; player N = N << 2, grenade = 167)
 *   10 ignored
 *   11 hit slot 2 shot id byte
 *   12 hit slot 2 "hit by" IR id
 *   13 ignored
 *   14 shots remaining
 *   15 status (0x02 normal, 0x03 during reload)
 *   16-19 ignored
 *
 * Ported verbatim from the Java test. [assertNum] compares as Long, exactly like JUnit's
 * assertEquals(long, long) did for the Java byte/int comparisons.
 */
class TelemetryParsingProtocolTest {

    private fun assertNum(expected: Number, actual: Number) = assertEquals(expected.toLong(), actual.toLong())
    private fun assertNum(message: String, expected: Number, actual: Number) =
        assertEquals(message, expected.toLong(), actual.toLong())

    private fun parse(data: ByteArray?): TelemetryData = parseTelemetry(data).also { assertNotNull(it) }!!

    // ----------------------------------------------------------------------------------------
    // Offsets
    // ----------------------------------------------------------------------------------------

    @Test
    fun telemetryOffsets_matchFirmwareLayout() {
        assertNum(1, RECOIL_OFFSET_TEAM)
        assertNum(2, RECOIL_OFFSET_BUTTONS)
        assertNum(3, RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER)
        assertNum(4, RECOIL_OFFSET_THUMB_COUNTER)
        assertNum(5, RECOIL_OFFSET_POWER_COUNTER)
        assertNum(7, RECOIL_OFFSET_BATTERY_LEVEL)
        assertNum(8, RECOIL_OFFSET_HIT_BY1_SHOTID)
        assertNum(9, RECOIL_OFFSET_HIT_BY1)
        assertNum(11, RECOIL_OFFSET_HIT_BY2_SHOTID)
        assertNum(12, RECOIL_OFFSET_HIT_BY2)
        assertNum(14, RECOIL_OFFSET_SHOTS_REMAINING)
        assertNum(15, RECOIL_OFFSET_STATUS)
    }

    // ----------------------------------------------------------------------------------------
    // Golden captures
    // ----------------------------------------------------------------------------------------

    @Test
    fun idlePacket() {
        val t = parse(GOLDEN_IDLE)
        assertNum(1, t.playerId)
        assertNum(30, t.shotsRemaining)
        assertNum(0, t.triggerCounter)
        assertNum(0, t.reloadCounter)
        assertNum(0, t.thumbCounter)
        assertNum(0, t.powerCounter)
        assertNum(0x10, t.batteryLevel)
        assertNum(0, t.hitByPlayer1)
        assertNum(0, t.shotId1)
        assertNum(0, t.hitByPlayer2)
        assertNum(0, t.shotId2)
        assertNum(0x02, t.status)
    }

    @Test
    fun triggerPulledPacket() {
        val t = parse(GOLDEN_TRIGGER_PULLED)
        assertNum(1, t.triggerCounter)
        assertNum(0, t.reloadCounter)
        assertNum(29, t.shotsRemaining)
    }

    @Test
    fun reloadPressedPacket() {
        val t = parse(GOLDEN_RELOAD_PRESSED)
        assertNum(1, t.triggerCounter)
        // The reload counter is kept as the raw high nibble (NOT shifted down): 0x10, not 0x01.
        assertNum(0x10.toByte(), t.reloadCounter)
    }

    @Test
    fun singleHitPacket() {
        val t = parse(GOLDEN_HIT_BY_PLAYER_2)
        assertNum(0x08, t.hitByPlayer1)
        assertNum(2, networkPlayerId(t.hitByPlayer1))
        assertNum(5, t.shotId1) // 0x3D & 0x07
        assertNum(0, t.hitByPlayer2)
        assertNum(0x0F, t.batteryLevel)
    }

    @Test
    fun doubleHitPacket() {
        val t = parse(GOLDEN_DOUBLE_HIT)
        assertNum(0x04, t.hitByPlayer1)
        assertNum(3, t.shotId1) // 0x3B & 0x07
        assertNum(0x0C, t.hitByPlayer2)
        assertNum(6, t.shotId2) // 0x3E & 0x07
        assertNum(1, networkPlayerId(t.hitByPlayer1))
        assertNum(3, networkPlayerId(t.hitByPlayer2))
    }

    @Test
    fun grenadeDamagePacket_hitByIsUnsigned167() {
        val t = parse(GOLDEN_GRENADE_DAMAGE)
        // 0xA7 is a negative byte; the parser must mask with 0xFF.
        assertNum(167, t.hitByPlayer1)
        assertNum(PlayerRepository.GRENADE_PLAYER_ID, t.hitByPlayer1)
        assertNum(1, t.shotId1) // 0x31 & 0x07
    }

    @Test
    fun grenadePairPacket_rawShotIdByteCarriesGrenadeIdAndEvent() {
        // shotId1 keeps only (byte & 0x07); the raw byte (D6) keeps the grenade id (hi nibble = 9)
        // and event code (lo nibble = 0x0F) recoverable from TelemetryData.
        val t = parse(GOLDEN_GRENADE_PAIR)
        assertNum(167, t.hitByPlayer1)
        assertNum(7, t.shotId1) // 0x9F & 0x07
        assertNum(0x9F.toByte(), t.rawShotIdByte1)
        assertNum(9, grenadeId(t.rawShotIdByte1))
        assertNum(GRENADE_PAIR_ID, grenadeEvent(t.rawShotIdByte1))
        assertTrue(isGrenadeControlEvent(t.hitByPlayer1, t.rawShotIdByte1))
    }

    @Test
    fun rawShotIdBytes_arePassedThroughAndAgreeWithReferenceDecoder_allValues() {
        for (v in 0..255) {
            val t = parse(telemetry(RECOIL_OFFSET_HIT_BY1_SHOTID, v, RECOIL_OFFSET_HIT_BY2_SHOTID, v xor 0xFF))
            assertNum(v.toByte(), t.rawShotIdByte1)
            assertNum((v xor 0xFF).toByte(), t.rawShotIdByte2)
            // The masked shot id is derived from the raw byte.
            assertNum(t.shotId1, RecoilProtocolFixtures.shotId(t.rawShotIdByte1))
            assertNum(t.shotId2, RecoilProtocolFixtures.shotId(t.rawShotIdByte2))
            assertNum(v shr 4, grenadeId(t.rawShotIdByte1))
            assertNum(v and 0x0F, grenadeEvent(t.rawShotIdByte1))
        }
    }

    // ----------------------------------------------------------------------------------------
    // Bit-level exhaustive checks
    // ----------------------------------------------------------------------------------------

    @Test
    fun reloadTriggerByte_splitsIntoIndependentNibbles_allValues() {
        for (v in 0..255) {
            val t = parse(telemetry(RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER, v))
            assertNum("trigger for 0x" + Integer.toHexString(v), (v and 0x0F).toByte(), t.triggerCounter)
            assertNum("reload for 0x" + Integer.toHexString(v), (v and 0xF0).toByte(), t.reloadCounter)
        }
    }

    @Test
    fun hitByBytes_areDecodedUnsigned_allValues() {
        for (v in 0..255) {
            val t = parse(telemetry(RECOIL_OFFSET_HIT_BY1, v, RECOIL_OFFSET_HIT_BY2, 255 - v))
            assertNum(v, t.hitByPlayer1)
            assertNum(255 - v, t.hitByPlayer2)
            assertTrue(t.hitByPlayer1 >= 0 && t.hitByPlayer2 >= 0)
        }
    }

    @Test
    fun shotIdBytes_maskToLow3Bits_allValues() {
        for (v in 0..255) {
            val t = parse(telemetry(RECOIL_OFFSET_HIT_BY1_SHOTID, v, RECOIL_OFFSET_HIT_BY2_SHOTID, v xor 0xFF))
            assertNum(v and 0x07, t.shotId1)
            assertNum((v xor 0xFF) and 0x07, t.shotId2)
        }
    }

    @Test
    fun rawByteFields_arePassedThroughUnmasked_allValues() {
        // player id, thumb, power, battery, shots remaining and status are full raw bytes.
        for (v in 0..255) {
            val t = parse(telemetry(
                RECOIL_OFFSET_TEAM, v,
                RECOIL_OFFSET_THUMB_COUNTER, v,
                RECOIL_OFFSET_POWER_COUNTER, v,
                RECOIL_OFFSET_BATTERY_LEVEL, v,
                RECOIL_OFFSET_SHOTS_REMAINING, v,
                RECOIL_OFFSET_STATUS, v))
            assertNum(v.toByte(), t.playerId)
            assertNum(v.toByte(), t.thumbCounter)
            assertNum(v.toByte(), t.powerCounter)
            assertNum(v.toByte(), t.batteryLevel)
            assertNum(v.toByte(), t.shotsRemaining)
            assertNum(v.toByte(), t.status)
        }
    }

    @Test
    fun ignoredBytes_doNotAffectParsedResult() {
        // Bytes 0, 2, 6, 10, 13 and 16-19 are not consumed by the decoder.
        val ignored = intArrayOf(0, 2, 6, 10, 13, 16, 17, 18, 19)
        val base = parseTelemetry(GOLDEN_DOUBLE_HIT)
        val noisy = GOLDEN_DOUBLE_HIT.clone()
        for (i in ignored) noisy[i] = 0xFF.toByte()
        assertSameTelemetry(base, parseTelemetry(noisy))
    }

    // ----------------------------------------------------------------------------------------
    // Malformed / truncated / oversized
    // ----------------------------------------------------------------------------------------

    @Test
    fun nullPacket_returnsNull() {
        assertNull(parseTelemetry(null))
    }

    @Test
    fun packetsShorterThan16Bytes_returnNull() {
        // Byte 15 (status) is the last required field, so 16 is the minimum accepted length.
        for (len in 0 until 16) {
            assertNull("length $len", parseTelemetry(ByteArray(len)))
        }
    }

    @Test
    fun packetsOf16To20Bytes_parseIdentically() {
        val full = GOLDEN_DOUBLE_HIT
        val expected = parseTelemetry(full)
        for (len in 16..20) {
            assertSameTelemetry(expected, parseTelemetry(full.copyOf(len)))
        }
    }

    @Test
    fun oversizedPacket_extraBytesIgnored() {
        val big = ByteArray(64)
        System.arraycopy(GOLDEN_DOUBLE_HIT, 0, big, 0, 20)
        for (i in 20 until big.size) big[i] = 0xAB.toByte()
        assertSameTelemetry(parseTelemetry(GOLDEN_DOUBLE_HIT), parseTelemetry(big))
    }

    @Test
    fun allOnesPacket_parsesWithoutError() {
        val ff = ByteArray(20) { 0xFF.toByte() }
        val t = parse(ff)
        assertNum(255, t.hitByPlayer1)
        assertNum(255, t.hitByPlayer2)
        assertNum(7, t.shotId1)
        assertNum(0x0F.toByte(), t.triggerCounter)
        assertNum(0xF0.toByte(), t.reloadCounter)
        assertNum(0xFF.toByte(), t.shotsRemaining)
    }

    @Test
    fun randomPackets_neverThrow() {
        val random = Random(0x5EED)
        for (i in 0 until 5000) {
            val data = ByteArray(random.nextInt(48))
            random.nextBytes(data)
            val t = parseTelemetry(data)
            assertEquals(data.size >= 16, t != null)
        }
    }

    @Test
    fun parser_doesNotMutateInput() {
        val data = GOLDEN_GRENADE_PAIR.clone()
        parseTelemetry(data)
        assertEquals(toHex(GOLDEN_GRENADE_PAIR), toHex(data))
    }

    // ----------------------------------------------------------------------------------------
    // Hit / grenade field encoding (reference spec from master's FullscreenActivity)
    // ----------------------------------------------------------------------------------------

    @Test
    fun reference_networkPlayerIdIsHitByShiftedRight2() {
        assertNum(1, networkPlayerId(0x04))
        assertNum(2, networkPlayerId(0x08))
        assertNum(3, networkPlayerId(0x0C))
        assertNum(5, networkPlayerId(0x14))
        assertNum(32, networkPlayerId(0x80)) // MAX_PLAYER_ID
        assertNum(41, networkPlayerId(GRENADE_PLAYER_ID)) // grenades are filtered before this matters
    }

    @Test
    fun reference_grenadeEventCodes() {
        assertNum(PlayerRepository.GRENADE_PLAYER_ID, GRENADE_PLAYER_ID)
        assertNum(0x01, GRENADE_DAMAGE)
        assertNum(0x0D, GRENADE_DISARM)
        assertNum(0x0E, GRENADE_NEW_PAIR)
        assertNum(0x0F, GRENADE_PAIR_ID)
        // Grenade ids occupy a 4-bit nibble; PlayerRepository sizes its pairing table to match.
        assertNum(16, PlayerRepository.MAX_GRENADE_IDS)
    }

    @Test
    fun reference_grenadeDamagePacketDecoding() {
        val shotByte = GOLDEN_GRENADE_DAMAGE[RECOIL_OFFSET_HIT_BY1_SHOTID]
        val hitBy = hitBy(GOLDEN_GRENADE_DAMAGE[RECOIL_OFFSET_HIT_BY1])
        assertNum(GRENADE_PLAYER_ID, hitBy)
        assertNum(3, grenadeId(shotByte))
        assertNum(GRENADE_DAMAGE, grenadeEvent(shotByte))
        // Damage events count as hits, so they are NOT control events.
        assertFalse(isGrenadeControlEvent(hitBy, shotByte))
    }

    @Test
    fun reference_grenadePairPacketDecoding() {
        val shotByte = GOLDEN_GRENADE_PAIR[RECOIL_OFFSET_HIT_BY1_SHOTID]
        val hitBy = hitBy(GOLDEN_GRENADE_PAIR[RECOIL_OFFSET_HIT_BY1])
        assertNum(9, grenadeId(shotByte))
        assertNum(GRENADE_PAIR_ID, grenadeEvent(shotByte))
        assertTrue(isGrenadeControlEvent(hitBy, shotByte))
    }

    @Test
    fun reference_grenadeNibbleDecoding_allValues() {
        for (v in 0..255) {
            val b = v.toByte()
            assertNum(v shr 4, grenadeId(b))
            assertNum(v and 0x0F, grenadeEvent(b))
            assertTrue(grenadeId(b) >= 0 && grenadeId(b) < PlayerRepository.MAX_GRENADE_IDS)
        }
    }

    @Test
    fun reference_nonGrenadeShooterIsNeverAControlEvent() {
        for (hitBy in 0..255) {
            if (hitBy == GRENADE_PLAYER_ID) continue
            for (b in 0..255) {
                assertFalse(isGrenadeControlEvent(hitBy, b.toByte()))
            }
        }
    }

    // ----------------------------------------------------------------------------------------

    private fun assertSameTelemetry(expected: TelemetryData?, actual: TelemetryData?) {
        assertNotNull(expected)
        assertNotNull(actual)
        expected!!
        actual!!
        assertNum(expected.playerId, actual.playerId)
        assertNum(expected.shotsRemaining, actual.shotsRemaining)
        assertNum(expected.triggerCounter, actual.triggerCounter)
        assertNum(expected.reloadCounter, actual.reloadCounter)
        assertNum(expected.thumbCounter, actual.thumbCounter)
        assertNum(expected.powerCounter, actual.powerCounter)
        assertNum(expected.batteryLevel, actual.batteryLevel)
        assertNum(expected.hitByPlayer1, actual.hitByPlayer1)
        assertNum(expected.shotId1, actual.shotId1)
        assertNum(expected.hitByPlayer2, actual.hitByPlayer2)
        assertNum(expected.shotId2, actual.shotId2)
        assertNum(expected.status, actual.status)
        assertNum(expected.rawShotIdByte1, actual.rawShotIdByte1)
        assertNum(expected.rawShotIdByte2, actual.rawShotIdByte2)
    }
}
