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

import com.simplecoil.simplecoil.domain.session.GrenadeDecoder.PairingAction
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrenadeDecoderTest {

    @Test
    fun eventCodes_matchFirmware() {
        assertEquals(RecoilProtocolFixtures.GRENADE_DAMAGE, GrenadeDecoder.GRENADE_DAMAGE.toInt())
        assertEquals(RecoilProtocolFixtures.GRENADE_DISARM, GrenadeDecoder.GRENADE_DISARM.toInt())
        assertEquals(RecoilProtocolFixtures.GRENADE_NEW_PAIR, GrenadeDecoder.GRENADE_NEW_PAIR.toInt())
        assertEquals(RecoilProtocolFixtures.GRENADE_PAIR_ID, GrenadeDecoder.GRENADE_PAIR_ID.toInt())
    }

    /** Every (hit-by byte, shot-id byte) pair decodes exactly as the reference decoder does. */
    @Test
    fun allInputs_agreeWithReferenceDecoder() {
        for (raw in 0..255) {
            val hitBy = RecoilProtocolFixtures.hitBy(raw.toByte())
            for (s in 0..255) {
                val shotIdByte = s.toByte()
                val event = RecoilProtocolFixtures.grenadeEvent(shotIdByte)
                val control = RecoilProtocolFixtures.isGrenadeControlEvent(hitBy, shotIdByte)
                assertEquals(event, GrenadeDecoder.event(shotIdByte))
                assertEquals(RecoilProtocolFixtures.grenadeId(shotIdByte), GrenadeDecoder.grenadeId(shotIdByte))
                assertEquals(control, GrenadeDecoder.isGrenadeControlEvent(hitBy, shotIdByte))
                val expected = when {
                    !control -> PairingAction.NONE
                    event == RecoilProtocolFixtures.GRENADE_PAIR_ID -> PairingAction.PAIR
                    event == RecoilProtocolFixtures.GRENADE_NEW_PAIR || event == RecoilProtocolFixtures.GRENADE_DISARM -> PairingAction.UNPAIR
                    else -> PairingAction.NONE
                }
                assertEquals("hitBy=$hitBy shotIdByte=$s", expected, GrenadeDecoder.pairingAction(hitBy, shotIdByte))
            }
        }
    }

    @Test
    fun goldenPairPacket_pairsGrenade9() {
        val packet = RecoilProtocolFixtures.GOLDEN_GRENADE_PAIR
        val hitBy = RecoilProtocolFixtures.hitBy(packet[GattProtocolHandler.RECOIL_OFFSET_HIT_BY1])
        val shotIdByte = packet[GattProtocolHandler.RECOIL_OFFSET_HIT_BY1_SHOTID]
        assertEquals(PairingAction.PAIR, GrenadeDecoder.pairingAction(hitBy, shotIdByte))
        assertEquals(9, GrenadeDecoder.grenadeId(shotIdByte))
    }

    @Test
    fun goldenDamagePacket_isNotAControlEvent() {
        val packet = RecoilProtocolFixtures.GOLDEN_GRENADE_DAMAGE
        val hitBy = RecoilProtocolFixtures.hitBy(packet[GattProtocolHandler.RECOIL_OFFSET_HIT_BY1])
        val shotIdByte = packet[GattProtocolHandler.RECOIL_OFFSET_HIT_BY1_SHOTID]
        assertFalse(GrenadeDecoder.isGrenadeControlEvent(hitBy, shotIdByte))
        assertEquals(PairingAction.NONE, GrenadeDecoder.pairingAction(hitBy, shotIdByte))
        assertEquals(3, GrenadeDecoder.grenadeId(shotIdByte))
    }

    @Test
    fun newPairAndDisarm_unpair() {
        assertEquals(PairingAction.UNPAIR, GrenadeDecoder.pairingAction(167, 0x9E.toByte()))
        assertEquals(PairingAction.UNPAIR, GrenadeDecoder.pairingAction(167, 0x9D.toByte()))
        // Unknown control events neither pair nor unpair, but still aren't damage.
        assertEquals(PairingAction.NONE, GrenadeDecoder.pairingAction(167, 0x92.toByte()))
        assertTrue(GrenadeDecoder.isGrenadeControlEvent(167, 0x92.toByte()))
        // Same nibbles from a player are ordinary hits.
        assertEquals(PairingAction.NONE, GrenadeDecoder.pairingAction(0x04, 0x9F.toByte()))
    }
}
