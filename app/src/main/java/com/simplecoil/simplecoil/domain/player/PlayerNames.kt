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

import java.text.Normalizer

/**
 * Which player names are allowed. Any printable Unicode is fine: letters in any script, accents,
 * punctuation, symbols and emoji, including JSON's `"` and `\` (WireCodec escapes them). What
 * is dropped is what breaks or disguises a name on someone else's screen:
 *
 * - control characters (NUL, escape, ...);
 * - format characters: bidi overrides that reverse the text after them, zero-width spaces and
 *   joiners, invisible tag characters. The emoji joiner U+200D stays, for emoji like 👨‍👩‍👧;
 * - lone surrogates, private-use, unassigned and noncharacter code points, and U+FFFD, which
 *   can't be relied on to render or to survive the trip through UTF-8;
 * - invisible "letters" (Hangul fillers, the blank Braille pattern) used to fake an empty name;
 * - more than [MAX_COMBINING_MARKS] combining marks on one character ("Zalgo" text).
 *
 * Tabs, newlines, line separators and other kinds of space become a plain space, so a name stays
 * on one line; runs of spaces collapse to one.
 */
object PlayerNames {
    /** In code points (an emoji is one or more), not UTF-16 chars. */
    const val MAX_LENGTH = 20
    const val MAX_COMBINING_MARKS = 2

    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val REPLACEMENT_CHARACTER = 0xFFFD
    private val INVISIBLE_LETTERS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800)

    /** Whether [codePoint] may appear in a name (spaces map to ' ' rather than being dropped). */
    @JvmStatic
    fun isAllowed(codePoint: Int): Boolean {
        if (codePoint == ZERO_WIDTH_JOINER) return true
        if (codePoint == REPLACEMENT_CHARACTER || codePoint in INVISIBLE_LETTERS) return false
        if ((codePoint and 0xFFFE) == 0xFFFE || codePoint in 0xFDD0..0xFDEF) return false
        return when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
            Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false
            else -> true
        }
    }

    /** Tabs, newlines, line separators, no-break, ideographic and em/en spaces and the like. */
    @JvmStatic
    fun isSpace(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

    private fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> false
    }

    /**
     * [raw] with the characters above removed, NFC-normalized, spaces collapsed, trimmed and cut
     * to [MAX_LENGTH] code points. "" when nothing visible is left.
     */
    @JvmStatic
    fun sanitize(raw: String?): String {
        if (raw == null) return ""
        val text = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val out = StringBuilder()
        var count = 0
        var marks = 0
        var visible = false
        var i = 0
        while (i < text.length && count < MAX_LENGTH) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (isSpace(cp)) {
                if (out.isNotEmpty() && out.last() != ' ') {
                    out.append(' ')
                    count++
                }
                marks = 0
                continue
            }
            if (!isAllowed(cp)) continue
            if (isMark(cp)) {
                // A mark needs a character to sit on, and only a couple of them
                if (out.isEmpty() || out.last() == ' ' || ++marks > MAX_COMBINING_MARKS) continue
            } else {
                marks = 0
                if (cp != ZERO_WIDTH_JOINER) visible = true
            }
            out.appendCodePoint(cp)
            count++
        }
        if (!visible) return ""
        return out.toString().trim()
    }

    /** Code points in [text], the unit [MAX_LENGTH] counts. */
    @JvmStatic
    fun length(text: CharSequence): Int = Character.codePointCount(text, 0, text.length)
}
