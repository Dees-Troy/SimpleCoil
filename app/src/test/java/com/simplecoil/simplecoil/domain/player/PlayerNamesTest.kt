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

package com.simplecoil.simplecoil.domain.player

import com.simplecoil.simplecoil.ProfanityFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerNamesTest {

    @Test
    fun printableUnicode_isKept() {
        for (name in listOf("Zoë", "José-María", "Łukasz", "Ольга", "東京", "محمد", "O'Brien \"Ace\"", "C:\\path", "<b>&amp;", "🔥 Blaze 🔥", "👨‍👩‍👧")) {
            assertEquals(name, PlayerNames.sanitize(name))
        }
    }

    @Test
    fun controlAndFormatCharacters_areDropped() {
        assertEquals("Alice Bob", PlayerNames.sanitize("Alice\r\nBob"))
        assertEquals("AB", PlayerNames.sanitize("A\u0000\u0007\u007F\u0085B"))
        // Bidi override, zero-width space, BOM, word joiner, tag character
        assertEquals("evil", PlayerNames.sanitize("\u202Eevil\u200B\uFEFF\u2060\uDB40\uDC41"))
        assertEquals("a b", PlayerNames.sanitize("a\u2028b\u2029"))
    }

    @Test
    fun brokenOrUnrenderableCodePoints_areDropped() {
        assertEquals("ab", PlayerNames.sanitize("a\uD800b\uDC00"))
        assertEquals("ab", PlayerNames.sanitize("a\uE000b\uFFFD\uFFFF\uFDD0"))
    }

    @Test
    fun spaces_areNormalizedAndCollapsed() {
        assertEquals("Big Al", PlayerNames.sanitize("  Big\u00A0\u3000\t Al  "))
    }

    @Test
    fun invisibleOnlyNames_areEmpty() {
        assertEquals("", PlayerNames.sanitize(null))
        assertEquals("", PlayerNames.sanitize("   "))
        assertEquals("", PlayerNames.sanitize("\u3164\u3164"))
        assertEquals("", PlayerNames.sanitize("\u200B\u200D\u2800"))
        assertEquals("", PlayerNames.sanitize("\u0301\u0301"))
    }

    @Test
    fun combiningMarks_areCappedPerCharacter() {
        assertEquals("x\u0301\u0302y", PlayerNames.sanitize("x\u0301\u0302\u0303\u0304\u0305y"))
        // NFC composes what it can first
        assertEquals("\u00E9", PlayerNames.sanitize("e\u0301"))
    }

    @Test
    fun length_isCappedInCodePoints_withoutSplittingPairs() {
        val emoji = "😀".repeat(25)
        val sanitized = PlayerNames.sanitize(emoji)
        assertEquals(PlayerNames.MAX_LENGTH, PlayerNames.length(sanitized))
        assertEquals("😀".repeat(PlayerNames.MAX_LENGTH), sanitized)
        assertEquals("a".repeat(PlayerNames.MAX_LENGTH), PlayerNames.sanitize("a".repeat(30)))
        // Cut at a space: no trailing space left
        assertEquals("a".repeat(19), PlayerNames.sanitize("a".repeat(19) + " b"))
    }

    @Test
    fun isAllowed_keepsTheEmojiJoiner() {
        assertTrue(PlayerNames.isAllowed(0x200D))
        assertFalse(PlayerNames.isAllowed(0x200B))
        assertFalse(PlayerNames.isAllowed('\n'.code))
    }

    @Test
    fun profanityFilter_seesThroughAccentsAndFullwidth() {
        assertTrue(ProfanityFilter.containsProfanity("sh\u00EFt"))
        assertTrue(ProfanityFilter.containsProfanity("\uFF46\uFF55\uFF43\uFF4B"))
        assertFalse(ProfanityFilter.containsProfanity("Zoë"))
    }
}
