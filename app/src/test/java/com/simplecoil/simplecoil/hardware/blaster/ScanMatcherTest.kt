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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanMatcherTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    @Test
    fun noSavedAddress_matchesSrg1Prefix() {
        assertTrue(ScanMatcher.matches("SRG1", mac, ""))
        assertTrue(ScanMatcher.matches("SRG1-1234", mac, ""))
        assertFalse(ScanMatcher.matches("srg1", mac, ""))
        assertFalse(ScanMatcher.matches("XSRG1", mac, ""))
        assertFalse(ScanMatcher.matches("Phone", mac, ""))
    }

    @Test
    fun savedAddress_matchesOnlyThatExactAddress() {
        assertTrue(ScanMatcher.matches("Anything", mac, mac))
        assertFalse(ScanMatcher.matches("SRG1", "11:22:33:44:55:66", mac))
        assertFalse(ScanMatcher.matches("SRG1", mac.lowercase(), mac))
    }

    @Test
    fun unnamedDevices_neverMatch() {
        assertFalse(ScanMatcher.matches(null, mac, mac))
        assertFalse(ScanMatcher.matches("", mac, mac))
        assertFalse(ScanMatcher.matches(null, mac, ""))
    }

    @Test
    fun nullAddress_doesNotMatchEmptySavedAddress() {
        assertFalse(ScanMatcher.matches("Phone", null, ""))
    }
}
