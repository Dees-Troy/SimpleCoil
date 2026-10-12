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

import com.simplecoil.simplecoil.GattAttributes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class ProtocolTraceTest {

    @Test
    fun hex_formatsUnsignedUppercasePairs() {
        assertEquals("F0 00 80 00 01 FF 7F", ProtocolTrace.hex(RecoilProtocolFixtures.hex("F0 00 80 00 01 FF 7F")))
        assertEquals("", ProtocolTrace.hex(ByteArray(0)))
        assertEquals("null", ProtocolTrace.hex(null))
    }

    @Test
    fun name_mapsRecoilUuids() {
        assertEquals("TELEMETRY", ProtocolTrace.name(UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID)))
        assertEquals("COMMAND", ProtocolTrace.name(UUID.fromString(GattAttributes.RECOIL_COMMAND_UUID)))
        assertEquals("CONFIG", ProtocolTrace.name(UUID.fromString(GattAttributes.RECOIL_CONFIG_UUID)))
        assertEquals("ID", ProtocolTrace.name(UUID.fromString(GattAttributes.RECOIL_ID_UUID)))
        assertEquals("CCCD", ProtocolTrace.name(UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG)))
        val other = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        assertEquals(other.toString(), ProtocolTrace.name(other))
        assertEquals("null", ProtocolTrace.name(null))
    }

    @Test
    fun tracing_neverMutatesOrThrows() {
        val packet = RecoilProtocolFixtures.GOLDEN_DOUBLE_HIT.clone()
        val uuid = UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID)
        ProtocolTrace.receive(uuid, packet)
        ProtocolTrace.write(uuid, packet, 2)
        ProtocolTrace.descriptorWrite(null, null)
        ProtocolTrace.read(null)
        ProtocolTrace.event("x")
        assertArrayEquals(RecoilProtocolFixtures.GOLDEN_DOUBLE_HIT, packet)
    }
}
