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

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * Locks down every GATT service / characteristic / descriptor UUID used to talk to Recoil
 * blasters. These UUIDs are burned into the blaster firmware and can never change.
 */
class GattAttributesProtocolTest {

    companion object {
        // Literal copies of the firmware UUIDs. Do NOT replace these with references to
        // GattAttributes: the whole point is to catch a silent edit of that class.
        private const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
        private const val MAIN_SERVICE = "e6f59d10-8230-4a5c-b22f-c062b1d329e3"
        private const val ID = "e6f59d11-8230-4a5c-b22f-c062b1d329e3"
        private const val TELEMETRY = "e6f59d12-8230-4a5c-b22f-c062b1d329e3"
        private const val COMMAND = "e6f59d13-8230-4a5c-b22f-c062b1d329e3"
        private const val CONFIG = "e6f59d14-8230-4a5c-b22f-c062b1d329e3"
    }

    /** Every UUID constant GattAttributes exposes, by name. */
    private val declared = mapOf(
        "CLIENT_CHARACTERISTIC_CONFIG" to GattAttributes.CLIENT_CHARACTERISTIC_CONFIG,
        "RECOIL_MAIN_SERVICE" to GattAttributes.RECOIL_MAIN_SERVICE,
        "RECOIL_ID_UUID" to GattAttributes.RECOIL_ID_UUID,
        "RECOIL_TELEMETRY_UUID" to GattAttributes.RECOIL_TELEMETRY_UUID,
        "RECOIL_COMMAND_UUID" to GattAttributes.RECOIL_COMMAND_UUID,
        "RECOIL_CONFIG_UUID" to GattAttributes.RECOIL_CONFIG_UUID,
    )

    @Test
    fun clientCharacteristicConfigDescriptor_isStandardCccd() {
        // Standard Bluetooth SIG CCCD (0x2902), written to enable telemetry notifications.
        assertEquals(CCCD, GattAttributes.CLIENT_CHARACTERISTIC_CONFIG)
    }

    @Test
    fun recoilServiceAndCharacteristicUuids_matchFirmware() {
        assertEquals("Recoil main service", MAIN_SERVICE, GattAttributes.RECOIL_MAIN_SERVICE)
        assertEquals("ID characteristic (read: firmware version + blaster type)", ID, GattAttributes.RECOIL_ID_UUID)
        assertEquals("Telemetry characteristic (notify: 20-byte status)", TELEMETRY, GattAttributes.RECOIL_TELEMETRY_UUID)
        assertEquals("Command characteristic (write: team / reload)", COMMAND, GattAttributes.RECOIL_COMMAND_UUID)
        assertEquals("Config characteristic (write: shot mode / recoil)", CONFIG, GattAttributes.RECOIL_CONFIG_UUID)
    }

    @Test
    fun uuidStrings_areLowercaseCanonicalForm() {
        // FullscreenActivity on master compared service UUIDs with String.equals() against
        // UUID.toString(), which is always lowercase. Keep the constants in canonical form so
        // any code path doing a case-sensitive comparison keeps matching.
        for (uuid in listOf(CCCD, MAIN_SERVICE, ID, TELEMETRY, COMMAND, CONFIG)) {
            assertEquals(uuid, UUID.fromString(uuid).toString())
        }
        for (uuid in declared.values) {
            assertEquals(uuid, UUID.fromString(uuid).toString())
        }
    }

    @Test
    fun recoilUuids_shareVendorBaseAndDifferOnlyInShortId() {
        // The Recoil vendor base is e6f59dXX-8230-4a5c-b22f-c062b1d329e3 with XX = 10..14.
        val uuids = listOf(MAIN_SERVICE, ID, TELEMETRY, COMMAND, CONFIG)
        for ((i, s) in uuids.withIndex()) {
            val u = UUID.fromString(s)
            assertEquals(0x8230_4a5cL, u.mostSignificantBits and 0xFFFFFFFFL)
            assertEquals(0xb22fc062b1d329e3uL.toLong(), u.leastSignificantBits)
            assertEquals(0xe6f59d10L + i, u.mostSignificantBits ushr 32)
        }
    }

    @Test
    fun bluetoothLeServiceDerivedUuids_matchFirmware() {
        // BluetoothLeService routes notifications by comparing against these UUID objects.
        assertEquals(UUID.fromString(TELEMETRY), BluetoothLeService.UUID_RECOIL_TELEMETRY)
        assertEquals(UUID.fromString(ID), BluetoothLeService.UUID_RECOIL_ID)
    }

    @Test
    fun gattAttributes_exposesNoUnexpectedUuidConstants() {
        // Guards against a new or renamed constant silently appearing without a matching test.
        // Synthetic members (e.g. a Kotlin object's INSTANCE) are not UUID constants.
        val names = GattAttributes::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java && !it.isSynthetic }
            .map { it.name }
            .toSet()
        assertEquals(declared.keys, names)
    }

    @Test
    fun allUuids_areDistinct() {
        assertEquals(6, declared.values.toSet().size)
    }
}
