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

/**
 * Shared fixtures for the Recoil BLE protocol characterization tests.
 *
 * The Recoil blaster firmware is frozen, so everything in here is a description of the wire
 * format as implemented by the original (pre-refactor, branch "master") FullscreenActivity.
 * Nothing in this class may be changed to make a failing test pass; if a production change
 * breaks a test that uses these fixtures, the production change is what is wrong.
 */
final class RecoilProtocolFixtures {

    private RecoilProtocolFixtures() {}

    /** All command / config / telemetry packets on this hardware are exactly 20 bytes. */
    static final int PACKET_LENGTH = 20;

    /**
     * Parses a whitespace separated hex string ("10 00 80 00 05 ...") into a byte array. Golden
     * vectors are written in hex so they can be compared directly against BLE sniffer captures.
     */
    static byte[] hex(String hex) {
        String[] parts = hex.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    /** Formats bytes as "10 00 80 ..." for readable assertion failure messages. */
    static String toHex(byte[] data) {
        if (data == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    /** Builds a 20-byte telemetry packet with the given (offset, value) pairs set, rest zero. */
    static byte[] telemetry(int... offsetValuePairs) {
        if (offsetValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("offset/value pairs required");
        }
        byte[] data = new byte[PACKET_LENGTH];
        for (int i = 0; i < offsetValuePairs.length; i += 2) {
            data[offsetValuePairs[i]] = (byte) offsetValuePairs[i + 1];
        }
        return data;
    }

    // ---------------------------------------------------------------------------------------
    // Golden telemetry captures. Values match the annotated layout documented in the original
    // FullscreenActivity ("Telemetry data is 20 bytes of raw data in the following format").
    // ---------------------------------------------------------------------------------------

    /**
     * Idle pistol, player 1 assigned, 30 shots loaded, fresh alkaline batteries (0x10), status
     * byte 0x02, no hits, all button counters at zero.
     */
    static final byte[] GOLDEN_IDLE =
            hex("00 01 00 00 00 00 00 10 00 00 00 00 00 00 1E 02 00 00 00 00");

    /**
     * Same blaster after a trigger pull: buttons byte shows trigger held (0x01), the trigger
     * counter (low nibble of byte 3) advanced to 1, and shots remaining dropped to 29 (0x1D).
     */
    static final byte[] GOLDEN_TRIGGER_PULLED =
            hex("01 01 01 01 00 00 00 10 00 00 00 00 00 00 1D 02 00 00 00 00");

    /**
     * Reload button pressed: buttons 0x02, reload counter (high nibble of byte 3) advanced to 1
     * while the trigger counter stays at 1.
     */
    static final byte[] GOLDEN_RELOAD_PRESSED =
            hex("02 01 02 11 00 00 00 10 00 00 00 00 00 00 1D 02 00 00 00 00");

    /**
     * Hit by network player 2 (IR id 2 << 2 = 0x08) with shot id 5. The shot id byte carries
     * random-looking high bits (0x3D: shot id lives only in the low three bits).
     */
    static final byte[] GOLDEN_HIT_BY_PLAYER_2 =
            hex("03 01 00 11 00 00 00 0F 3D 08 6A 00 00 00 1D 02 00 00 00 00");

    /**
     * Simultaneous hits: slot 1 from player 1 (0x04, shot id 3), slot 2 from player 3 (0x0C,
     * shot id 6).
     */
    static final byte[] GOLDEN_DOUBLE_HIT =
            hex("04 01 00 11 00 00 00 0F 3B 04 70 3E 0C 71 1D 02 00 00 00 00");

    /**
     * Grenade #3 detonation hit. Grenades always report player id 167 (0xA7). The shot id byte
     * packs the grenade id in the high nibble (3) and the grenade event type in the low nibble
     * (0x01 = damage).
     */
    static final byte[] GOLDEN_GRENADE_DAMAGE =
            hex("05 01 00 11 00 00 00 0F 31 A7 00 00 00 00 1D 02 00 00 00 00");

    /** Grenade #9 asking to pair: event 0x0F, grenade id 9 → shot id byte 0x9F. */
    static final byte[] GOLDEN_GRENADE_PAIR =
            hex("06 01 00 11 00 00 00 0F 9F A7 00 00 00 00 1D 02 00 00 00 00");

    // ---------------------------------------------------------------------------------------
    // Reference decoder for the "hit by" fields, transcribed from master's FullscreenActivity
    // processTelemetryData(). This logic currently only exists inline in the activity, so it is
    // captured here as an executable specification for the upcoming refactor.
    // ---------------------------------------------------------------------------------------

    /** IR player id reported by grenades in the hit-by byte. */
    static final int GRENADE_PLAYER_ID = 167;

    /** Grenade event codes, low nibble of the shot id byte when hit-by == 167. */
    static final int GRENADE_DAMAGE = 0x01;
    static final int GRENADE_DISARM = 0x0D;
    static final int GRENADE_NEW_PAIR = 0x0E;
    static final int GRENADE_PAIR_ID = 0x0F;

    /** Unsigned hit-by value. Must be masked with 0xFF: IR ids above 127 (e.g. 167) are negative bytes. */
    static int hitBy(byte raw) {
        return raw & 0xFF;
    }

    /** Network player id = IR hit-by value >> 2 (player 1 → 0x04, player 2 → 0x08, ...). */
    static byte networkPlayerId(int hitBy) {
        return (byte) (hitBy >> 2);
    }

    /** Shot id: only the right-most three bits of the shot id byte. */
    static byte shotId(byte shotIdByte) {
        return (byte) (shotIdByte & 0x07);
    }

    /** Grenade event type: low nibble of the shot id byte. */
    static int grenadeEvent(byte shotIdByte) {
        return shotIdByte & 0x0F;
    }

    /** Grenade id (1..15): high nibble of the shot id byte. */
    static int grenadeId(byte shotIdByte) {
        return (shotIdByte & 0xF0) >> 4;
    }

    /** True when this hit slot is a grenade control (non-damage) event that must not cost health. */
    static boolean isGrenadeControlEvent(int hitBy, byte shotIdByte) {
        return hitBy == GRENADE_PLAYER_ID && grenadeEvent(shotIdByte) != GRENADE_DAMAGE;
    }
}
