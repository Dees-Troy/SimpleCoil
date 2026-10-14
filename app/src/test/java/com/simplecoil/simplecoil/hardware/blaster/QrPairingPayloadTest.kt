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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPairingPayloadTest {

    private fun parse(raw: String?) = QrPairingPayload.parse(raw)

    @Test
    fun macOnly() {
        assertEquals(QrPairingPayload("AA:BB:CC:DD:EE:FF", ""), parse("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun macAndName() {
        assertEquals(QrPairingPayload("AA:BB:CC:DD:EE:FF", "Bob"), parse("AA:BB:CC:DD:EE:FF-Bob"))
    }

    @Test
    fun nameMayContainDashes_whenMacIsFullLength() {
        assertEquals(QrPairingPayload("AA:BB:CC:DD:EE:FF", "Mary-Jane"), parse("AA:BB:CC:DD:EE:FF-Mary-Jane"))
    }

    @Test
    fun shortMac_splitsAtFirstDash() {
        assertEquals(QrPairingPayload("AA:BB", "Mary-Jane"), parse("AA:BB-Mary-Jane"))
    }

    @Test
    fun whitespaceIsTrimmedAroundEachPart() {
        assertEquals(QrPairingPayload("AA:BB:CC:DD:EE:FF", "Bob"), parse("  AA:BB:CC:DD:EE:FF - Bob \n"))
        assertEquals(QrPairingPayload("AA:BB:CC:DD:EE:FF", ""), parse("AA:BB:CC:DD:EE:FF-   "))
    }

    @Test
    fun trimUsesJavaSemantics() {
        // String.trim() strips only chars <= ' ', so a no-break space stays part of the MAC.
        assertEquals(QrPairingPayload(" AA:BB", ""), parse(" AA:BB"))
    }

    @Test
    fun anythingWithoutADashIsTakenAsTheMac() {
        assertEquals(QrPairingPayload("hello", ""), parse("hello"))
    }

    @Test
    fun emptyOrMissingMac_isRejected() {
        assertNull(parse(null))
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("-Bob"))
        assertNull(parse(" - Bob"))
    }

    @Test
    fun encode_macOnly_whenNameIsBlank() {
        assertEquals("AA:BB:CC:DD:EE:FF", QrPairingPayload.encode("AA:BB:CC:DD:EE:FF", ""))
        assertEquals("AA:BB:CC:DD:EE:FF", QrPairingPayload.encode("AA:BB:CC:DD:EE:FF", "   "))
    }

    @Test
    fun encode_appendsTrimmedName() {
        assertEquals("AA:BB:CC:DD:EE:FF-Bob", QrPairingPayload.encode("AA:BB:CC:DD:EE:FF", " Bob "))
    }

    @Test
    fun encode_roundTripsThroughParse() {
        for (name in listOf("", "Bob", "Mary-Jane", "-Dash-", "A b.c!?_")) {
            val encoded = QrPairingPayload.encode("AA:BB:CC:DD:EE:FF", name)
            assertEquals(QrPairingPayload("AA:BB:CC:DD:EE:FF", name), parse(encoded))
        }
    }
}
