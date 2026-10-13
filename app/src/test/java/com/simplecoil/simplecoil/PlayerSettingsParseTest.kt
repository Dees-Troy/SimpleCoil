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

import com.simplecoil.simplecoil.PlayerSettingsAlertDialog.parseInRange
import org.junit.Assert.assertEquals
import org.junit.Test

/** The player settings dialog's field parsing (dedicated server design H5). */
class PlayerSettingsParseTest {

    @Test
    fun `a number in range is used`() {
        assertEquals(1, parseInRange("1", 1, 1000, 20))
        assertEquals(1000, parseInRange("1000", 1, 1000, 20))
        assertEquals(0, parseInRange("0", 0, 1000, 3))
        assertEquals(42, parseInRange(" 42 ", 1, 1000, 20))
    }

    @Test
    fun `a number out of range falls back`() {
        assertEquals(20, parseInRange("0", 1, 1000, 20))
        assertEquals(20, parseInRange("1001", 1, 1000, 20))
        assertEquals(3, parseInRange("-1", 0, 1000, 3))
    }

    @Test
    fun `an empty or invalid field falls back instead of throwing`() {
        assertEquals(20, parseInRange("", 1, 1000, 20))
        assertEquals(20, parseInRange(null, 1, 1000, 20))
        assertEquals(20, parseInRange("-", 1, 1000, 20))
        assertEquals(20, parseInRange("abc", 1, 1000, 20))
        assertEquals(20, parseInRange("99999999999", 1, 1000, 20))
    }
}
