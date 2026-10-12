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

package com.simplecoil.simplecoil.hardware.blaster;

import com.simplecoil.simplecoil.data.repository.PlayerRepository;

import org.junit.Test;

import java.util.Random;

import static com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
 */
public class TelemetryParsingProtocolTest {

    // ----------------------------------------------------------------------------------------
    // Offsets
    // ----------------------------------------------------------------------------------------

    @Test
    public void telemetryOffsets_matchFirmwareLayout() {
        assertEquals(1, GattProtocolHandler.RECOIL_OFFSET_TEAM);
        assertEquals(2, GattProtocolHandler.RECOIL_OFFSET_BUTTONS);
        assertEquals(3, GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER);
        assertEquals(4, GattProtocolHandler.RECOIL_OFFSET_THUMB_COUNTER);
        assertEquals(5, GattProtocolHandler.RECOIL_OFFSET_POWER_COUNTER);
        assertEquals(7, GattProtocolHandler.RECOIL_OFFSET_BATTERY_LEVEL);
        assertEquals(8, GattProtocolHandler.RECOIL_OFFSET_HIT_BY1_SHOTID);
        assertEquals(9, GattProtocolHandler.RECOIL_OFFSET_HIT_BY1);
        assertEquals(11, GattProtocolHandler.RECOIL_OFFSET_HIT_BY2_SHOTID);
        assertEquals(12, GattProtocolHandler.RECOIL_OFFSET_HIT_BY2);
        assertEquals(14, GattProtocolHandler.RECOIL_OFFSET_SHOTS_REMAINING);
        assertEquals(15, GattProtocolHandler.RECOIL_OFFSET_STATUS);
    }

    // ----------------------------------------------------------------------------------------
    // Golden captures
    // ----------------------------------------------------------------------------------------

    @Test
    public void idlePacket() {
        GattProtocolHandler.TelemetryData t = GattProtocolHandler.parseTelemetry(GOLDEN_IDLE);
        assertNotNull(t);
        assertEquals(1, t.playerId);
        assertEquals(30, t.shotsRemaining);
        assertEquals(0, t.triggerCounter);
        assertEquals(0, t.reloadCounter);
        assertEquals(0, t.thumbCounter);
        assertEquals(0, t.powerCounter);
        assertEquals(0x10, t.batteryLevel);
        assertEquals(0, t.hitByPlayer1);
        assertEquals(0, t.shotId1);
        assertEquals(0, t.hitByPlayer2);
        assertEquals(0, t.shotId2);
        assertEquals(0x02, t.status);
    }

    @Test
    public void triggerPulledPacket() {
        GattProtocolHandler.TelemetryData t = GattProtocolHandler.parseTelemetry(GOLDEN_TRIGGER_PULLED);
        assertNotNull(t);
        assertEquals(1, t.triggerCounter);
        assertEquals(0, t.reloadCounter);
        assertEquals(29, t.shotsRemaining);
    }

    @Test
    public void reloadPressedPacket() {
        GattProtocolHandler.TelemetryData t = GattProtocolHandler.parseTelemetry(GOLDEN_RELOAD_PRESSED);
        assertNotNull(t);
        assertEquals(1, t.triggerCounter);
        // The reload counter is kept as the raw high nibble (NOT shifted down): 0x10, not 0x01.
        assertEquals((byte) 0x10, t.reloadCounter);
    }

    @Test
    public void singleHitPacket() {
        GattProtocolHandler.TelemetryData t = GattProtocolHandler.parseTelemetry(GOLDEN_HIT_BY_PLAYER_2);
        assertNotNull(t);
        assertEquals(0x08, t.hitByPlayer1);
        assertEquals(2, networkPlayerId(t.hitByPlayer1));
        assertEquals(5, t.shotId1); // 0x3D & 0x07
        assertEquals(0, t.hitByPlayer2);
        assertEquals(0x0F, t.batteryLevel);
    }

    @Test
    public void doubleHitPacket() {
        GattProtocolHandler.TelemetryData t = GattProtocolHandler.parseTelemetry(GOLDEN_DOUBLE_HIT);
        assertNotNull(t);
        assertEquals(0x04, t.hitByPlayer1);
        assertEquals(3, t.shotId1); // 0x3B & 0x07
        assertEquals(0x0C, t.hitByPlayer2);
        assertEquals(6, t.shotId2); // 0x3E & 0x07
        assertEquals(1, networkPlayerId(t.hitByPlayer1));
        assertEquals(3, networkPlayerId(t.hitByPlayer2));
    }

    @Test
    public void grenadeDamagePacket_hitByIsUnsigned167() {
        GattProtocolHandler.TelemetryData t = GattProtocolHandler.parseTelemetry(GOLDEN_GRENADE_DAMAGE);
        assertNotNull(t);
        // 0xA7 is a negative Java byte; the parser must mask with 0xFF.
        assertEquals(167, t.hitByPlayer1);
        assertEquals(PlayerRepository.GRENADE_PLAYER_ID, t.hitByPlayer1);
        assertEquals(1, t.shotId1); // 0x31 & 0x07
    }

    @Test
    public void grenadePairPacket_parserOnlyExposesLow3BitsOfShotIdByte() {
        // CHARACTERIZATION of current V2 behaviour: TelemetryData keeps only (byte & 0x07), so the
        // grenade id (hi nibble = 9) and event code (lo nibble = 0x0F) are NOT recoverable from
        // TelemetryData. Grenade pairing therefore still depends on raw bytes in
        // FullscreenActivity. If you extend TelemetryData to carry the raw byte, update this test.
        GattProtocolHandler.TelemetryData t = GattProtocolHandler.parseTelemetry(GOLDEN_GRENADE_PAIR);
        assertNotNull(t);
        assertEquals(167, t.hitByPlayer1);
        assertEquals(7, t.shotId1); // 0x9F & 0x07
    }

    // ----------------------------------------------------------------------------------------
    // Bit-level exhaustive checks
    // ----------------------------------------------------------------------------------------

    @Test
    public void reloadTriggerByte_splitsIntoIndependentNibbles_allValues() {
        for (int v = 0; v < 256; v++) {
            GattProtocolHandler.TelemetryData t = GattProtocolHandler.parseTelemetry(
                    telemetry(GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER, v));
            assertNotNull(t);
            assertEquals("trigger for 0x" + Integer.toHexString(v), (byte) (v & 0x0F), t.triggerCounter);
            assertEquals("reload for 0x" + Integer.toHexString(v), (byte) (v & 0xF0), t.reloadCounter);
        }
    }

    @Test
    public void hitByBytes_areDecodedUnsigned_allValues() {
        for (int v = 0; v < 256; v++) {
            GattProtocolHandler.TelemetryData t = GattProtocolHandler.parseTelemetry(telemetry(
                    GattProtocolHandler.RECOIL_OFFSET_HIT_BY1, v,
                    GattProtocolHandler.RECOIL_OFFSET_HIT_BY2, 255 - v));
            assertNotNull(t);
            assertEquals(v, t.hitByPlayer1);
            assertEquals(255 - v, t.hitByPlayer2);
            assertTrue(t.hitByPlayer1 >= 0 && t.hitByPlayer2 >= 0);
        }
    }

    @Test
    public void shotIdBytes_maskToLow3Bits_allValues() {
        for (int v = 0; v < 256; v++) {
            GattProtocolHandler.TelemetryData t = GattProtocolHandler.parseTelemetry(telemetry(
                    GattProtocolHandler.RECOIL_OFFSET_HIT_BY1_SHOTID, v,
                    GattProtocolHandler.RECOIL_OFFSET_HIT_BY2_SHOTID, v ^ 0xFF));
            assertNotNull(t);
            assertEquals(v & 0x07, t.shotId1);
            assertEquals((v ^ 0xFF) & 0x07, t.shotId2);
        }
    }

    @Test
    public void rawByteFields_arePassedThroughUnmasked_allValues() {
        // player id, thumb, power, battery, shots remaining and status are full raw bytes.
        for (int v = 0; v < 256; v++) {
            GattProtocolHandler.TelemetryData t = GattProtocolHandler.parseTelemetry(telemetry(
                    GattProtocolHandler.RECOIL_OFFSET_TEAM, v,
                    GattProtocolHandler.RECOIL_OFFSET_THUMB_COUNTER, v,
                    GattProtocolHandler.RECOIL_OFFSET_POWER_COUNTER, v,
                    GattProtocolHandler.RECOIL_OFFSET_BATTERY_LEVEL, v,
                    GattProtocolHandler.RECOIL_OFFSET_SHOTS_REMAINING, v,
                    GattProtocolHandler.RECOIL_OFFSET_STATUS, v));
            assertNotNull(t);
            assertEquals((byte) v, t.playerId);
            assertEquals((byte) v, t.thumbCounter);
            assertEquals((byte) v, t.powerCounter);
            assertEquals((byte) v, t.batteryLevel);
            assertEquals((byte) v, t.shotsRemaining);
            assertEquals((byte) v, t.status);
        }
    }

    @Test
    public void ignoredBytes_doNotAffectParsedResult() {
        // Bytes 0, 2, 6, 10, 13 and 16-19 are not consumed by the decoder.
        int[] ignored = {0, 2, 6, 10, 13, 16, 17, 18, 19};
        GattProtocolHandler.TelemetryData base = GattProtocolHandler.parseTelemetry(GOLDEN_DOUBLE_HIT);
        byte[] noisy = GOLDEN_DOUBLE_HIT.clone();
        for (int i : ignored) noisy[i] = (byte) 0xFF;
        assertSameTelemetry(base, GattProtocolHandler.parseTelemetry(noisy));
    }

    // ----------------------------------------------------------------------------------------
    // Malformed / truncated / oversized
    // ----------------------------------------------------------------------------------------

    @Test
    public void nullPacket_returnsNull() {
        assertNull(GattProtocolHandler.parseTelemetry(null));
    }

    @Test
    public void packetsShorterThan16Bytes_returnNull() {
        // Byte 15 (status) is the last required field, so 16 is the minimum accepted length.
        for (int len = 0; len < 16; len++) {
            assertNull("length " + len, GattProtocolHandler.parseTelemetry(new byte[len]));
        }
    }

    @Test
    public void packetsOf16To20Bytes_parseIdentically() {
        byte[] full = GOLDEN_DOUBLE_HIT;
        GattProtocolHandler.TelemetryData expected = GattProtocolHandler.parseTelemetry(full);
        for (int len = 16; len <= 20; len++) {
            byte[] truncated = new byte[len];
            System.arraycopy(full, 0, truncated, 0, len);
            assertSameTelemetry(expected, GattProtocolHandler.parseTelemetry(truncated));
        }
    }

    @Test
    public void oversizedPacket_extraBytesIgnored() {
        byte[] big = new byte[64];
        System.arraycopy(GOLDEN_DOUBLE_HIT, 0, big, 0, 20);
        for (int i = 20; i < big.length; i++) big[i] = (byte) 0xAB;
        assertSameTelemetry(GattProtocolHandler.parseTelemetry(GOLDEN_DOUBLE_HIT),
                GattProtocolHandler.parseTelemetry(big));
    }

    @Test
    public void allOnesPacket_parsesWithoutError() {
        byte[] ff = new byte[20];
        java.util.Arrays.fill(ff, (byte) 0xFF);
        GattProtocolHandler.TelemetryData t = GattProtocolHandler.parseTelemetry(ff);
        assertNotNull(t);
        assertEquals(255, t.hitByPlayer1);
        assertEquals(255, t.hitByPlayer2);
        assertEquals(7, t.shotId1);
        assertEquals((byte) 0x0F, t.triggerCounter);
        assertEquals((byte) 0xF0, t.reloadCounter);
        assertEquals((byte) 0xFF, t.shotsRemaining);
    }

    @Test
    public void randomPackets_neverThrow() {
        Random random = new Random(0x5EED);
        for (int i = 0; i < 5000; i++) {
            byte[] data = new byte[random.nextInt(48)];
            random.nextBytes(data);
            GattProtocolHandler.TelemetryData t = GattProtocolHandler.parseTelemetry(data);
            assertEquals(data.length >= 16, t != null);
        }
    }

    @Test
    public void parser_doesNotMutateInput() {
        byte[] data = GOLDEN_GRENADE_PAIR.clone();
        GattProtocolHandler.parseTelemetry(data);
        assertEquals(toHex(GOLDEN_GRENADE_PAIR), toHex(data));
    }

    // ----------------------------------------------------------------------------------------
    // Hit / grenade field encoding (reference spec from master's FullscreenActivity)
    // ----------------------------------------------------------------------------------------

    @Test
    public void reference_networkPlayerIdIsHitByShiftedRight2() {
        assertEquals(1, networkPlayerId(0x04));
        assertEquals(2, networkPlayerId(0x08));
        assertEquals(3, networkPlayerId(0x0C));
        assertEquals(5, networkPlayerId(0x14));
        assertEquals(32, networkPlayerId(0x80)); // MAX_PLAYER_ID
        assertEquals(41, networkPlayerId(GRENADE_PLAYER_ID)); // grenades are filtered before this matters
    }

    @Test
    public void reference_grenadeEventCodes() {
        assertEquals(PlayerRepository.GRENADE_PLAYER_ID, GRENADE_PLAYER_ID);
        assertEquals(0x01, GRENADE_DAMAGE);
        assertEquals(0x0D, GRENADE_DISARM);
        assertEquals(0x0E, GRENADE_NEW_PAIR);
        assertEquals(0x0F, GRENADE_PAIR_ID);
        // Grenade ids occupy a 4-bit nibble; PlayerRepository sizes its pairing table to match.
        assertEquals(16, PlayerRepository.MAX_GRENADE_IDS);
    }

    @Test
    public void reference_grenadeDamagePacketDecoding() {
        byte shotByte = GOLDEN_GRENADE_DAMAGE[GattProtocolHandler.RECOIL_OFFSET_HIT_BY1_SHOTID];
        int hitBy = hitBy(GOLDEN_GRENADE_DAMAGE[GattProtocolHandler.RECOIL_OFFSET_HIT_BY1]);
        assertEquals(GRENADE_PLAYER_ID, hitBy);
        assertEquals(3, grenadeId(shotByte));
        assertEquals(GRENADE_DAMAGE, grenadeEvent(shotByte));
        // Damage events count as hits, so they are NOT control events.
        assertFalse(isGrenadeControlEvent(hitBy, shotByte));
    }

    @Test
    public void reference_grenadePairPacketDecoding() {
        byte shotByte = GOLDEN_GRENADE_PAIR[GattProtocolHandler.RECOIL_OFFSET_HIT_BY1_SHOTID];
        int hitBy = hitBy(GOLDEN_GRENADE_PAIR[GattProtocolHandler.RECOIL_OFFSET_HIT_BY1]);
        assertEquals(9, grenadeId(shotByte));
        assertEquals(GRENADE_PAIR_ID, grenadeEvent(shotByte));
        assertTrue(isGrenadeControlEvent(hitBy, shotByte));
    }

    @Test
    public void reference_grenadeNibbleDecoding_allValues() {
        for (int v = 0; v < 256; v++) {
            byte b = (byte) v;
            assertEquals(v >> 4, grenadeId(b));
            assertEquals(v & 0x0F, grenadeEvent(b));
            assertTrue(grenadeId(b) >= 0 && grenadeId(b) < PlayerRepository.MAX_GRENADE_IDS);
        }
    }

    @Test
    public void reference_nonGrenadeShooterIsNeverAControlEvent() {
        for (int hitBy = 0; hitBy < 256; hitBy++) {
            if (hitBy == GRENADE_PLAYER_ID) continue;
            for (int b = 0; b < 256; b++) {
                assertFalse(isGrenadeControlEvent(hitBy, (byte) b));
            }
        }
    }

    // ----------------------------------------------------------------------------------------

    private static void assertSameTelemetry(GattProtocolHandler.TelemetryData expected,
                                            GattProtocolHandler.TelemetryData actual) {
        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals(expected.playerId, actual.playerId);
        assertEquals(expected.shotsRemaining, actual.shotsRemaining);
        assertEquals(expected.triggerCounter, actual.triggerCounter);
        assertEquals(expected.reloadCounter, actual.reloadCounter);
        assertEquals(expected.thumbCounter, actual.thumbCounter);
        assertEquals(expected.powerCounter, actual.powerCounter);
        assertEquals(expected.batteryLevel, actual.batteryLevel);
        assertEquals(expected.hitByPlayer1, actual.hitByPlayer1);
        assertEquals(expected.shotId1, actual.shotId1);
        assertEquals(expected.hitByPlayer2, actual.hitByPlayer2);
        assertEquals(expected.shotId2, actual.shotId2);
        assertEquals(expected.status, actual.status);
    }
}
