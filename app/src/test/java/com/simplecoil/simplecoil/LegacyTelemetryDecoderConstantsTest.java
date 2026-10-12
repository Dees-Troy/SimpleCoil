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

package com.simplecoil.simplecoil;

import com.simplecoil.simplecoil.data.repository.PlayerRepository;
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

/**
 * FullscreenActivity still decodes raw telemetry inline (including grenade pairing), in
 * parallel with GattProtocolHandler. Until that is consolidated, keep the two decoders' wire
 * constants pinned to each other and to the firmware values.
 *
 * When the activity's decoder is removed during the refactor, delete the corresponding
 * assertions here; do not change the expected values.
 */
public class LegacyTelemetryDecoderConstantsTest {

    @Test
    public void activityTelemetryOffsets_matchProtocolHandler() {
        assertEquals(GattProtocolHandler.RECOIL_OFFSET_TEAM, FullscreenActivity.RECOIL_OFFSET_TEAM);
        assertEquals(GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER, FullscreenActivity.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER);
        assertEquals(GattProtocolHandler.RECOIL_OFFSET_THUMB_COUNTER, FullscreenActivity.RECOIL_OFFSET_THUMB_COUNTER);
        assertEquals(GattProtocolHandler.RECOIL_OFFSET_POWER_COUNTER, FullscreenActivity.RECOIL_OFFSET_POWER_COUNTER);
        assertEquals(GattProtocolHandler.RECOIL_OFFSET_BATTERY_LEVEL, FullscreenActivity.RECOIL_OFFSET_BATTERY_LEVEL);
        assertEquals(GattProtocolHandler.RECOIL_OFFSET_HIT_BY1_SHOTID, FullscreenActivity.RECOIL_OFFSET_HIT_BY1_SHOTID);
        assertEquals(GattProtocolHandler.RECOIL_OFFSET_HIT_BY1, FullscreenActivity.RECOIL_OFFSET_HIT_BY1);
        assertEquals(GattProtocolHandler.RECOIL_OFFSET_HIT_BY2_SHOTID, FullscreenActivity.RECOIL_OFFSET_HIT_BY2_SHOTID);
        assertEquals(GattProtocolHandler.RECOIL_OFFSET_HIT_BY2, FullscreenActivity.RECOIL_OFFSET_HIT_BY2);
        assertEquals(GattProtocolHandler.RECOIL_OFFSET_SHOTS_REMAINING, FullscreenActivity.RECOIL_OFFSET_SHOTS_REMAINING);
    }

    @Test
    public void activityGrenadeEventCodes_matchFirmware() throws Exception {
        // Low nibble of the hit shot-id byte when hit-by == 167.
        assertEquals((byte) 0x01, privateByte("GRENADE_DAMAGE"));
        assertEquals((byte) 0x0D, privateByte("GRENADE_DISARM"));
        assertEquals((byte) 0x0E, privateByte("GRENADE_NEW_PAIR"));
        assertEquals((byte) 0x0F, privateByte("GRENADE_PAIR_ID"));
    }

    @Test
    public void grenadePlayerId_is167EverywhereItIsDeclared() {
        assertEquals(167, PlayerRepository.GRENADE_PLAYER_ID);
        assertEquals(167, Globals.GRENADE_PLAYER_ID);
        assertEquals(16, Globals.MAX_GRENADE_IDS);
    }

    private static byte privateByte(String name) throws Exception {
        Field f = FullscreenActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getByte(null);
    }
}
