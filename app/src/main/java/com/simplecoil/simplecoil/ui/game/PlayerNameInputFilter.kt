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

package com.simplecoil.simplecoil.ui.game

import android.text.InputFilter
import android.text.Spanned
import com.simplecoil.simplecoil.domain.player.PlayerNames

/**
 * Keeps disallowed characters out of a name field as they are typed or pasted, and caps it at
 * [PlayerNames.MAX_LENGTH] code points (android:maxLength counts UTF-16 chars, so an emoji would
 * use up two). The name is still run through [PlayerNames.sanitize] when it is submitted.
 */
class PlayerNameInputFilter : InputFilter {
    override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int): CharSequence? {
        val kept = PlayerNames.length(dest) - Character.codePointCount(dest, dstart, dend)
        var room = PlayerNames.MAX_LENGTH - kept
        val out = StringBuilder()
        var changed = false
        var i = start
        while (i < end) {
            var cp = Character.codePointAt(source, i)
            i += Character.charCount(cp)
            if (cp != ' '.code && PlayerNames.isSpace(cp)) {
                // A pasted newline or tab
                cp = ' '.code
                changed = true
            }
            if (room <= 0 || !(cp == ' '.code || PlayerNames.isAllowed(cp))) {
                changed = true
                continue
            }
            out.appendCodePoint(cp)
            room--
        }
        return if (changed) out else null
    }
}
