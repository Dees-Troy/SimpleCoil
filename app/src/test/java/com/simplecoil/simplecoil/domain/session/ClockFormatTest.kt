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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ClockFormatTest {
    private val saved = Locale.getDefault()

    @After
    fun tearDown() = Locale.setDefault(saved)

    @Test
    fun clockText_isAsciiInEveryLocale() {
        // DefaultLocale: an Arabic locale used to render the clock with Arabic-Indic digits
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        assertEquals("05:00", ClockFormat.minutes(5))
        assertEquals("12:07", ClockFormat.minutesSeconds(12, 7))
    }
}
