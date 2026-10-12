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

package com.simplecoil.simplecoil

import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.session.GrenadeDecoder
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Telemetry is decoded in two steps: GattProtocolHandler splits the packet (keeping the raw
 * shot-id bytes) and GrenadeDecoder interprets the grenade nibbles. Keep the two pinned to each
 * other and to the firmware values.
 *
 * Replaces LegacyTelemetryDecoderConstantsTest (D1): FullscreenActivity no longer decodes
 * telemetry or declares these constants. The offset parity assertions it held are gone with the
 * activity's copy of the offsets (the offsets themselves stay pinned in
 * TelemetryParsingProtocolTest); the expected values below are unchanged.
 */
class TelemetryDecoderParityTest {

    @Test
    fun grenadeEventCodes_matchFirmware() {
        // Low nibble of the hit shot-id byte when hit-by == 167.
        assertEquals(0x01.toByte(), GrenadeDecoder.GRENADE_DAMAGE)
        assertEquals(0x0D.toByte(), GrenadeDecoder.GRENADE_DISARM)
        assertEquals(0x0E.toByte(), GrenadeDecoder.GRENADE_NEW_PAIR)
        assertEquals(0x0F.toByte(), GrenadeDecoder.GRENADE_PAIR_ID)
    }

    @Test
    fun grenadePlayerId_is167EverywhereItIsDeclared() {
        assertEquals(167, PlayerRepository.GRENADE_PLAYER_ID)
        assertEquals(167, Globals.GRENADE_PLAYER_ID)
        assertEquals(16, Globals.MAX_GRENADE_IDS)
    }

    @Test
    fun handlerRawBytesFeedGrenadeDecoder_likeTheReferenceDecoder() {
        for (packet in listOf(RecoilProtocolFixtures.GOLDEN_GRENADE_PAIR, RecoilProtocolFixtures.GOLDEN_GRENADE_DAMAGE,
                RecoilProtocolFixtures.GOLDEN_DOUBLE_HIT, RecoilProtocolFixtures.GOLDEN_HIT_BY_PLAYER_2)) {
            val t = GattProtocolHandler.parseTelemetry(packet)!!
            for ((hitBy, raw, offset) in listOf(
                    Triple(t.hitByPlayer1, t.rawShotIdByte1, GattProtocolHandler.RECOIL_OFFSET_HIT_BY1_SHOTID),
                    Triple(t.hitByPlayer2, t.rawShotIdByte2, GattProtocolHandler.RECOIL_OFFSET_HIT_BY2_SHOTID))) {
                assertEquals(packet[offset], raw)
                assertEquals(RecoilProtocolFixtures.grenadeId(raw), GrenadeDecoder.grenadeId(raw))
                assertEquals(RecoilProtocolFixtures.grenadeEvent(raw), GrenadeDecoder.event(raw))
                assertEquals(RecoilProtocolFixtures.isGrenadeControlEvent(hitBy, raw), GrenadeDecoder.isGrenadeControlEvent(hitBy, raw))
            }
        }
    }
}
