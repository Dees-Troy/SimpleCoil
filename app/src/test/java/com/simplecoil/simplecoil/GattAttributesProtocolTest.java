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

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

/**
 * Locks down every GATT service / characteristic / descriptor UUID used to talk to Recoil
 * blasters. These UUIDs are burned into the blaster firmware and can never change.
 */
public class GattAttributesProtocolTest {

    // Literal copies of the firmware UUIDs. Do NOT replace these with references to
    // GattAttributes: the whole point is to catch a silent edit of that class.
    private static final String CCCD = "00002902-0000-1000-8000-00805f9b34fb";
    private static final String MAIN_SERVICE = "e6f59d10-8230-4a5c-b22f-c062b1d329e3";
    private static final String ID = "e6f59d11-8230-4a5c-b22f-c062b1d329e3";
    private static final String TELEMETRY = "e6f59d12-8230-4a5c-b22f-c062b1d329e3";
    private static final String COMMAND = "e6f59d13-8230-4a5c-b22f-c062b1d329e3";
    private static final String CONFIG = "e6f59d14-8230-4a5c-b22f-c062b1d329e3";

    @Test
    public void clientCharacteristicConfigDescriptor_isStandardCccd() {
        // Standard Bluetooth SIG CCCD (0x2902), written to enable telemetry notifications.
        assertEquals(CCCD, GattAttributes.CLIENT_CHARACTERISTIC_CONFIG);
    }

    @Test
    public void recoilServiceAndCharacteristicUuids_matchFirmware() {
        assertEquals("Recoil main service", MAIN_SERVICE, GattAttributes.RECOIL_MAIN_SERVICE);
        assertEquals("ID characteristic (read: firmware version + blaster type)", ID, GattAttributes.RECOIL_ID_UUID);
        assertEquals("Telemetry characteristic (notify: 20-byte status)", TELEMETRY, GattAttributes.RECOIL_TELEMETRY_UUID);
        assertEquals("Command characteristic (write: team / reload)", COMMAND, GattAttributes.RECOIL_COMMAND_UUID);
        assertEquals("Config characteristic (write: shot mode / recoil)", CONFIG, GattAttributes.RECOIL_CONFIG_UUID);
    }

    @Test
    public void uuidStrings_areLowercaseCanonicalForm() {
        // FullscreenActivity on master compared service UUIDs with String.equals() against
        // UUID.toString(), which is always lowercase. Keep the constants in canonical form so
        // any code path doing a case-sensitive comparison keeps matching.
        for (String uuid : new String[]{CCCD, MAIN_SERVICE, ID, TELEMETRY, COMMAND, CONFIG}) {
            assertEquals(uuid, UUID.fromString(uuid).toString());
        }
        assertEquals(GattAttributes.RECOIL_MAIN_SERVICE, UUID.fromString(GattAttributes.RECOIL_MAIN_SERVICE).toString());
        assertEquals(GattAttributes.RECOIL_ID_UUID, UUID.fromString(GattAttributes.RECOIL_ID_UUID).toString());
        assertEquals(GattAttributes.RECOIL_TELEMETRY_UUID, UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID).toString());
        assertEquals(GattAttributes.RECOIL_COMMAND_UUID, UUID.fromString(GattAttributes.RECOIL_COMMAND_UUID).toString());
        assertEquals(GattAttributes.RECOIL_CONFIG_UUID, UUID.fromString(GattAttributes.RECOIL_CONFIG_UUID).toString());
        assertEquals(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG, UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG).toString());
    }

    @Test
    public void recoilUuids_shareVendorBaseAndDifferOnlyInShortId() {
        // The Recoil vendor base is e6f59dXX-8230-4a5c-b22f-c062b1d329e3 with XX = 10..14.
        String[] uuids = {MAIN_SERVICE, ID, TELEMETRY, COMMAND, CONFIG};
        for (int i = 0; i < uuids.length; i++) {
            UUID u = UUID.fromString(uuids[i]);
            assertEquals(0x8230_4a5cL, u.getMostSignificantBits() & 0xFFFFFFFFL);
            assertEquals(0xb22fc062b1d329e3L, u.getLeastSignificantBits());
            assertEquals(0xe6f59d10L + i, u.getMostSignificantBits() >>> 32);
        }
    }

    @Test
    public void bluetoothLeServiceDerivedUuids_matchFirmware() {
        // BluetoothLeService routes notifications by comparing against these UUID objects.
        assertEquals(UUID.fromString(TELEMETRY), BluetoothLeService.UUID_RECOIL_TELEMETRY);
        assertEquals(UUID.fromString(ID), BluetoothLeService.UUID_RECOIL_ID);
    }

    @Test
    public void gattAttributes_exposesNoUnexpectedUuidConstants() throws Exception {
        // Guards against a new or renamed constant silently appearing without a matching test.
        Set<String> names = new HashSet<>();
        for (Field f : GattAttributes.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                names.add(f.getName());
            }
        }
        Set<String> expected = new HashSet<>();
        expected.add("CLIENT_CHARACTERISTIC_CONFIG");
        expected.add("RECOIL_MAIN_SERVICE");
        expected.add("RECOIL_ID_UUID");
        expected.add("RECOIL_TELEMETRY_UUID");
        expected.add("RECOIL_COMMAND_UUID");
        expected.add("RECOIL_CONFIG_UUID");
        assertEquals(expected, names);
    }

    @Test
    public void allUuids_areDistinct() {
        Set<String> all = new HashSet<>();
        all.add(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG);
        all.add(GattAttributes.RECOIL_MAIN_SERVICE);
        all.add(GattAttributes.RECOIL_ID_UUID);
        all.add(GattAttributes.RECOIL_TELEMETRY_UUID);
        all.add(GattAttributes.RECOIL_COMMAND_UUID);
        all.add(GattAttributes.RECOIL_CONFIG_UUID);
        assertEquals(6, all.size());
    }
}
